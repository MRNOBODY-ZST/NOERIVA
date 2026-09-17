# NAT audit API — retained-history query contract (2026-09-09)

Base `/api/v1/nat-audit`. JSON uses camelCase. All calls are authenticated and organization-scoped. Read roles ADMIN/OPERATOR/VIEWER; writes ADMIN only plus `X-Noeriva-Request: 1`. No credentials are supplied to UDP HSL. Configuration binds exactly one canonical IPv4 exporter source address to a registered device; an address cannot be claimed by another device/organization.

## Sources and configuration

`GET /sources` → `{items: SourceView[]}`; only configured sources, at most 256. An empty list means none configured.

`POST /devices/{id}/settings` body `{revision:0,enabled:true,sourceAddress:null}`. Omitted/null/empty sourceAddress uses the device's literal management IPv4 address. First save revision=0; subsequent changes require the current revision. Response `SourceView`. Source address must be a unicast canonical IPv4 literal; no DNS or CIDR. Conflicting claims/revisions return 409. Configuration changes may take up to 5 seconds to reach the receiver. Disabling stops future admissions after refresh; already admitted events may finish persistence.

`POST /devices/{id}/state` body `{revision:1,enabled:false}` → `SourceView`.

```typescript
type SourceView = {
  deviceId:string; deviceName:string; siteId:string; sourceAddress:string;
  revision:number; enabled:boolean;
  status:'DISABLED'|'WAITING'|'RECEIVING'|'DEGRADED'|'STALE';
  lastPacketAt:string|null; lastEventAt:string|null;
  lastPersistedAt:string|null; lastError:string|null;
  templates:number; received:number; accepted:number; persisted:number;
  dropped:number; sequenceGaps:number; unknownTemplates:number; parseErrors:number;
  duplicatePackets:number; restartCount:number; updatedAt:string;
  qualityFlags:string[];
};
```

Counts are cumulative since configuration creation (not cleared by settings edits). `received` counts admitted datagrams. `accepted` counts event records acknowledged by Kafka, `persisted` counts sink-confirmed batches' records (retries may repeat this operational counter; it is not unique-event cardinality). `templates` counts accepted template definitions including refreshes. `dropped` counts datagrams rejected at the bounded admission/publish stage; `parseErrors`/`unknownTemplates` identify undecoded datagrams/flowsets. `sequenceGaps` counts forward sequence discontinuity observations, not a proven count of lost events; UDP reorder can cause them. `duplicatePackets` counts repeated datagrams identified in the receiver's bounded recent cache. No counter proves lossless delivery. Changes in deployment and receiver restarts can lose unflushed operational deltas; retained Kafka/ClickHouse records remain authoritative.

Status uses last packet freshness: enabled with no packets WAITING, >120 seconds STALE, recent known errors DEGRADED, otherwise RECEIVING. Presence of traffic is not authentication or proof of complete capture. `qualityFlags` always includes `UDP_UNAUTHENTICATED`, `COMPLETENESS_NOT_GUARANTEED`; additional failures are visible. `lastEventAt` is the received-at time of the most recent accepted event, so it remains useful when a device timestamp is missing. `lastPersistedAt` is sink acknowledgement time.

## Event query

`GET /events?deviceId=...&from=<ISO>&to=<ISO>&protocol=6&privateIp=192.0.2.10&publicIp=198.51.100.10&limit=50&cursor=...`

`deviceId` required. The default omits **both** `from` and `to`, querying all retained history. An explicit range supplies both, with `to>from`, without a fixed day cap. Time filtering/sort use **receivedAt**, newest first, not an absent/untrusted device clock. Protocol optional integer 0–255. IP filters optional exact canonical IPv4. Limit 1–100. The cursor freezes the first page's upper cutoff and binds organization/device, filters and range. Refresh without a cursor to include newly received events. Response is `{items:NatEvent[],nextCursor:string|null,asOf:string,mode:'CONNECTED'}`; backend failures return 503 rather than empty success. No exact whole-history count.

The lightweight `nat_audit_receipts` index is ordered by organization, device, received timestamp and event ID. Each request reads at most 128 candidate receipts in reverse index order, then loads their canonical `nat_audit_events FINAL` identities using the immutable exporter timestamp/event ID primary key. A replay receipt is returned only if it is the canonical earliest receipt, including when a later replay lies inside a selected range but the original does not. A page may contain fewer than the requested limit, or no events, while still returning a next cursor when candidates were replays; clients must preserve continuation until the cursor is null. An index backfill can repeat rows without changing results.

Both tables use `index_granularity=256`. Each stage has two threads, four seconds of server execution, 128 MiB read/memory and 2 MiB result budgets; the receipt stage additionally limits reads to 200,000 rows and the canonical stage to 2,000,000. Receipt limits use mandatory leaf THROW guards with unlimited local BREAK thresholds: local thresholds cannot terminate a result, and the leaf guard still rejects actual read overflow. This avoids ClickHouse 26.3 local THROW substituting an entire-range estimate for a small ordered read. Cursor predicates use equivalent scalar timestamp/ID comparisons so duplicate-key granules are pruned; a tuple comparison on this version can scan an entire repeated-key block. The entire chain has a ten-second deadline and cancels owned queries on timeout/disconnection. Sparse filters or very large uncorrelated history can exceed budgets and return 503; narrowing the range is then appropriate. All-history selection does not imply an unbounded scan.

Deployment requires `005-nat-receipt-index.sql` and a one-time server-side backfill of retained event keys **before** exposing the new query API. Create the materialized view first to capture concurrent inserts, then backfill bounded exported-time slices. Repeating a slice is correctness-safe, though it adds index rows. Never drop the existing event table. The view records inserted blocks, so canonical deduplication remains in the original FINAL table, as described by [ClickHouse's materialized-view semantics](https://clickhouse.com/docs/concepts/features/materialized-views/cascading-materialized-views). Index-order LIMIT reads use [ClickHouse's ordered-read optimization](https://clickhouse.com/docs/reference/statements/select/order-by#optimization-of-data-reading).

```typescript
type NatEvent = {
  id:string; deviceId:string; siteId:string; sourceAddress:string;
  sourceDomain:number; exporterEpoch:string; packetSequence:number;
  templateId:number; templateSha256:string; packetSha256:string; recordIndex:number;
  eventType:'CREATE'|'DELETE'|'POOL_EXHAUSTED'; protocol:number|null; vrfId:number|null;
  privateIp:string|null; privatePort:number|null;
  publicIp:string|null; publicPort:number|null;
  destinationIp:string|null; destinationPort:number|null;
  translatedDestinationIp:string|null; translatedDestinationPort:number|null;
  poolId:number|null;
  deviceEventAt:string|null; exportedAt:string; receivedAt:string;
  qualityFlags:string[]; provenance:'CISCO_NAT_HSL_V9';
};
```

Ports preserve literal uint16 values (including zero); non-TCP/UDP protocol values are preserved without interpreting ports as TCP/UDP. Missing address/port fields remain null and add quality flags. A CREATE/DELETE observation is not a complete session; no user identity or interval is manufactured. Destination/NBAR names are not inferred.

## Runtime and evidence boundary

Receiver only starts for production with `NOERIVA_NAT_RECEIVER_ENABLED=true`, UDP port `NOERIVA_NAT_UDP_PORT` defaults to 2055. It consumes only recent enabled source bindings, fails closed if refresh fails, and never accepts an organization supplied by a datagram. UDP service must preserve actual exporter source address; the root deployment uses a single receiver on node .62, NodePort 32055 with Local traffic policy. Kafka topic is `noeriva.nat.v1`; sink writes dedicated `nat_audit_events` with synchronous ClickHouse acknowledgement before committing the Kafka record. MySQL migration V8 stores source settings/status only.

Only bounded NetFlow v9 fixed-width templates and verified standard HSL field IDs are decoded. IPFIX v10/variable-length/enterprise formats are rejected or reported unsupported, not guessed. Template cache has TTL, source/domain isolation and limits; unknown templates never create fabricated events. Stored event IDs include packet digest and record position to make replay idempotent, independently of collector restart. Raw payloads are not returned by this API.

Implementation tests cover binary/parser boundaries, real UDP transport, database revision/source claims, organization/role boundaries, and real ClickHouse query/replay where available. Live hardware acceptance is separate and conducted by the main deployment task.

Production-profile Spring context tests enable the real `@EnableKafka` endpoint registration with circular references explicitly disabled. They verify the dedicated topic/group/record-ack factory and the receiver's start/close lifecycle. Broker auto-connection is disabled only in these wiring tests; they do not claim live Kafka delivery. The factory is a static bean so listener initialization does not depend on the unfinished runtime instance.

The production-version ClickHouse regression uses 200,000 complete synthetic events spanning ten days with deliberately shuffled exporter timestamps. It checks exact result IDs, arrival timestamps, and cursor continuation for one-hour, 24-hour, ten-day and all-retained windows within the query budgets, and reads the server query log to assert the candidate lookup did not scan the complete dataset. Separate replay tests verify earliest-capture selection and an empty later-only window. A million-identical-receipt regression verifies that continuation skips the complete duplicate block, retains both a lower ID at the same timestamp and an older timestamp, and still throws when a deliberate non-limited hash aggregation reaches the leaf read budget. This is a bounded regression dataset, not a claim of unlimited throughput or lossless UDP capture.

## Backfill execution and verification

`scripts/backfill-nat-receipts.py` reads `NOERIVA_CLICKHOUSE_URL`, `NOERIVA_CLICKHOUSE_USERNAME` and `NOERIVA_CLICKHOUSE_PASSWORD` from the environment. Scope and fixed time bounds are explicit:

```sh
python3 scripts/backfill-nat-receipts.py --organization "$NAT_ORG" \
  --device "$NAT_DEVICE" --from 2026-09-07T00:00:00Z --to "$NAT_CUTOFF" \
  --checkpoint /opt/noeriva-migration/nat-receipts.json
```

Every default 24-hour slice is a synchronous `INSERT SELECT` of organization/device/receipt/export/key/filter columns only, with the predicate `organization_id={org:String} AND device_id={device:String} AND exported_at>=parseDateTime64BestEffort({from:String}) AND exported_at<parseDateTime64BestEffort({to:String})`. No `FINAL`, payload scan, truncation or deletion occurs during backfill. A completed slice is checkpointed only after acknowledgement. Budgets: 60 seconds, 2 threads, 256 MiB memory, 1 GiB read, 10 million read rows, 16,384-row blocks. A failed slice can be retried with smaller `--chunk-hours 1`; retries can duplicate receipt-index rows, which query validation/cursors handle safely.

Before selecting bounds, inspect the scoped canonical minimum/maximum `exported_at` with a bounded query; exporter clock skew can put receipts outside the nominal capture date. Choose complete bounds around all retained exporter dates. Creating the materialized view before backfill covers later inserts, even those whose exporter clock is old. Keep the fixed backfill cutoff and successful query IDs in the checkpoint.

After `SYSTEM FLUSH LOGS`, verify the checkpoint query IDs have `type='QueryFinish'`, zero exception and expected written/read row counts in `system.query_log`. A server budget failure does not create a completed checkpoint. Also sample earliest/latest canonical keys from each slice and compare matching `(received_at,event_id,exported_at)` tuples in the receipt index; then use the new API to check descending all-time pages, filtered pages and duplicate-free continuation. A count of raw index rows alone cannot prove correctness because overlapping backfills and replays can add index rows.

ClickHouse implementation references: [26.3 ReadProgressCallback](https://github.com/ClickHouse/ClickHouse/blob/v26.3.32.14-lts/src/QueryPipeline/ReadProgressCallback.cpp) explains the local THROW estimate substitution; [ordered reads](https://clickhouse.com/docs/reference/statements/select/order-by) and [query complexity limits](https://clickhouse.com/docs/concepts/features/configuration/settings/query-complexity) describe the relevant execution controls. Regression tests run against the deployed 26.3.32.14 release.
