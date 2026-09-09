# Query and rollup slice verification

Date: 2026-09-06. Scope: `services/noeriva-query`, a Java 25 / Spring Boot 4.1.1 library module. This is an implemented and tested first query/rollup slice; it does not complete the Phase 0–13 roadmap or certify production scale.

## Implemented behavior

- `QueryService` provides the typed `heatmap(organizationId, deviceId, interfaceId, timezone, direction)` and `metrics(organizationId, deviceId, metric, from, to, points)` Reactor APIs. The control HTTP boundary must verify device/interface ownership before invoking them. Explicit organization and stable resource identifiers remain mandatory in storage queries.
- Demo adapters are active only under the `demo` Spring profile. They generate deterministic, labelled synthetic counters and named metrics on bounded workers. Other profiles use actual VictoriaMetrics and ClickHouse HTTP adapters, without fallback to synthetic data when a provider fails.
- Metric requests use a six-item allowlist: CPU, memory, temperature, power, RX bandwidth, TX bandwidth. They accept 2–2,000 points and at most seven days. The browser supplies no arbitrary SQL or PromQL. Query results preserve missing/non-finite values, source names, source times, resolution, and quality flags. VictoriaMetrics does not expose this application's projection revision, so native queries return revision zero with `NATIVE_REVISION_UNAVAILABLE`.
- `CounterRollup` computes integer-safe deltas before rate conversion. It excludes reset/restart/gap intervals, accepts UInt64 wrap only when explicitly declared, splits intervals by elapsed duration at bucket boundaries, and marks estimated placement. Real observed zero is distinct from missing data. Conflicting samples at the same source timestamp require an explicit correction policy instead of guessing which record is newer.
- `HeatmapBuilder` returns seven local calendar dates and 24 labelled hour positions per date: 168 cells. It preserves zero/missing/partial/future/DST-missing states, repeated hours as separate UTC intervals in one cell, and non-hour timezone offsets. A timezone hour whose retained buckets cannot align exactly exposes `RESOLUTION_UNAVAILABLE`. Only complete buckets participate in an observed cell; current-hour metadata remains provisional. Section coverage is weighted by actual elapsed calendar duration, including DST.
- Corrected buckets are selected by latest revision before aggregation. Overlapping source/resolution selections and conflicting equal revisions are rejected. Last original source observation time is stored independently from bucket end and generation time, preventing an old partial bucket from appearing freshly observed.
- `QueryAdmission` maintains separate capacities for state, charts, investigation, export, and replay/rebuild. It acquires capacity only on subscription, rejects excess work immediately, and releases permits on completion, failure, timeout, and cancellation. CPU-heavy calendar/rollup/JSON work uses small bounded schedulers, never Netty event loops.

## Actual raw-to-rollup path

`RollupWorker.recompute(org, device, interface, source, from, to, direction)` performs a real bounded pipeline:

1. Validate a closed UTC-five-minute-aligned window of at most one day.
2. Obtain a durable monotonic revision through the injected control-plane `RollupStateStore`.
3. Read only the assigned named counter series from `/api/v1/export`, including 90 seconds of source-boundary context. HTTP response memory is capped at 2 MiB and raw samples at 10,000. Source organization/device/interface/source labels are checked again on returned rows.
4. Construct replacement five-minute buckets. Source epochs are preserved; absence is flagged. Float-store inputs carry `FLOATING_POINT_SOURCE`, and counters above the precise-double integer range also carry `COUNTER_QUANTIZATION`. These operational samples are not advertised as byte-exact original evidence.
5. Publish only buckets bracketed by actually visible source samples. Empty/expired/not-yet-visible input returns `RECOMPUTATION_UNAVAILABLE`, leaving prior stored buckets intact. Proven gaps can produce partial replacements with explicit quality flags.
6. Insert full snapshots into ClickHouse with `async_insert=0`, bounded execution/memory, and explicit columns. The writer requires an empty successful INSERT response, so an HTTP 200 containing a late ClickHouse exception cannot advance progress.
7. Call the durable checkpoint only after insertion acknowledgement. Processed coverage and complete-through are separate: gaps do not establish completeness. The control store must invalidate previous completeness when a newer overlapping partial correction replaces it.

The raw metric names are `noeriva_interface_receive_bytes_total` and `noeriva_interface_transmit_bytes_total`. Required labels are `organization_id`, `device_id`, `interface_id`, and `source_id`; `source_epoch` is strongly recommended. The initial 90-second maximum accepted gap assumes the configured 15-second source cadence. Longer-cadence sources require an explicit catalog/configuration extension.

The query uses `noeriva.metric_rollups`, `metric_id=bandwidth`, `resolution_seconds=300`, `algorithm_version=1`, and the configured source ID. `argMax(tuple(...), revision)` selects complete replacement snapshots before summing durations and deltas. `last_observed_utc` preserves actual source freshness. Reads have explicit row, byte, memory, result and server-time budgets. Cancellation sends `KILL QUERY` for the actual generated ClickHouse query ID; if cancellation cannot be confirmed, a warning is emitted and the five-second server deadline remains the backstop.

## Configuration

| Environment setting | Purpose |
|---|---|
| `NOERIVA_METRICS_URL` | VictoriaMetrics endpoint; requests include a five-second timeout |
| `NOERIVA_CLICKHOUSE_URL` | ClickHouse HTTP endpoint |
| `NOERIVA_CLICKHOUSE_USERNAME` | ClickHouse username, default `noeriva` |
| `NOERIVA_CLICKHOUSE_PASSWORD` | External secret, never returned to a browser |
| `NOERIVA_ROLLUP_SOURCE_ID` | Explicit series/rollup source, default `primary` |

The root control module owns authorization, durable MySQL revision/checkpoint transactions and scheduling. The query module owns no infrastructure deployment or retention activation.

## Verification evidence

The module tests cover exact UInt64 arithmetic, declared wrap, reset/restart/gap handling, weighted boundary splitting, observed zero, source-time preservation, correction replacement, New York 23/25-hour DST days, Kolkata UTC+05:30 alignment, exact-current-hour state, query validation, provider failure, partial data, and cancellation-safe admission.

Local HTTP integration tests exercise actual WebClient requests and decoding, scoped query binding, ClickHouse query-ID cancellation, native `NaN` handling, bounded point requests, raw-export-to-insert sequencing, revisioned correction, and HTTP-200 late insert failure rejection.

A connected CUA test exposed an additional chart boundary defect: a six-hour CPU request beginning `2026-09-06T02:06:38.123Z` with 120 points produced a VictoriaMetrics cached point at `02:05:28Z`, outside the requested window. The adapter now sets `nocache=1` for these bounded range queries; the [pinned VictoriaMetrics v1.151.0 handler](https://github.com/VictoriaMetrics/VictoriaMetrics/blob/v1.151.0/app/vmselect/prometheus/prometheus.go#L910) confirms cache-enabled queries adjust start/end to step boundaries. It also rounds provider bounds inward to millisecond precision, preserving the original API window when callers supply nanoseconds. A window containing no representable millisecond returns no samples without widening the provider query. Regression tests cover both observed failures and continued rejection of out-of-window responses. Bounds, point budgets and timeouts remain enforced; this correctness choice bypasses the provider result cache for named interactive metrics.

`LiveMetricPipelineTest` is explicitly opt-in through `NOERIVA_LIVE_QUERY_TEST=true`. It was executed against the authorized running COMPACT VictoriaMetrics and ClickHouse engines at local ports 18428 and 18123. It imported a fresh `query-it-<UUID>` synthetic source, ran the actual raw-export/rollup/insert/read path, and verified a 30,000-byte delta, 800 bps mean, revision 1 and the original last-observed timestamp. The test adds namespaced synthetic records; it does not delete existing data. The generated local credential file was read only by the test launcher and no credentials were printed.

Run the ordinary suite with `./mvnw -pl services/noeriva-query test`. To include the live test, also supply the three endpoint/credential settings above and set `NOERIVA_LIVE_QUERY_TEST=true`. No actual-engine success is claimed when that opt-in test is skipped.

The latest query-module verification executed 40 tests with zero failures/errors/skips, including the opt-in live engine test. Ordinary runs intentionally skip that one live-environment test unless explicitly enabled.

## Durable historical repair jobs

The control module now exposes a bounded `ROLLUP_REPAIR` job type through `/api/v1/query-jobs`. This is historical metric repair, not an export implementation. An administrator posts `deviceId`, `interfaceId`, `from`, `to`, and `direction`; the server validates organization/interface ownership and a closed UTC-five-minute-aligned window of at most one day before committing the `PENDING` job and its administrative audit record in the same MySQL transaction. GET by job ID is organization-scoped.

The table is introduced by `V3__rollup_jobs.sql`. Admission is capped at 100 active jobs per organization. The background worker runs only when `NOERIVA_ROLLUP_ENABLED`/`noeriva.rollup.enabled` is true, claims one row using transaction locks, enforces a 30-second recompute deadline, and records `SUCCEEDED`/`FAILED`, bucket count and a bounded error code. It uses a two-minute lease and at most three claims after expired leases. Lease-token checks prevent a stale worker from completing a newly reclaimed job. Shutdown disposes the active subscription; an interrupted job can be recovered after its lease expires. Demo mode explicitly returns HTTP 503 for durable repairs.

Nine dedicated repair-job tests passed: two validation/administrator tests, two actual demo HTTP tests, and five MySQL 8.4.11 Testcontainers tests covering migration, ownership/audit/status, concurrent exclusive claiming, stale-lease protection, worker success/progress, invisible-source failure and queue limits. These complement the actual VM/ClickHouse pipeline test; the MySQL job tests use controlled raw-source/sink collaborators so they do not claim another real-device test.

The connected Compose [repair smoke result](rollup-job-smoke.json), produced by [the repair smoke script](../../scripts/smoke-rollup-job.py), also passed against the actual engines: an hour of counters three hours in the past was imported into VictoriaMetrics, queued through the administrator API as a durable job, processed by an independent container worker into 12 ClickHouse replacement buckets, and queried as one 800 bps heatmap cell. The other 167 cells remained missing/future. This verifies the synthetic historical-repair path across the running stack; it does not establish real-device support or automatic dirty-window discovery.

## Explicit limits

- The current implementation persists five-minute bandwidth rollups. An independently scheduled hourly layer, gauge rollups, source catalogs beyond the named initial metrics, all vendor/collector adapters, and broad old-history dirty-bucket orchestration remain later work.
- A recent-window sweep alone does not repair three-hour/seven-day late replay or a long outage. The durable administrator repair jobs provide a bounded path for known affected windows. Automatic identification of all historical dirty windows and unattended multi-day reconciliation remain separate acceptance requirements.
- There is no query-result cache in this module. Every heatmap read sees the selected published bucket revisions; adding caches requires scope/policy/revision invalidation and revocation checks.
- The worker supports operational float-store counters with visible precision limits. Exact integer evidence requires an approved raw/typed evidence source; this path is not an evidence archive.
- Native metric-query warnings remain visible; ambiguous multiple series fail. Empty provider results mean unknown coverage. Provider errors never become fabricated zero observations or successful empty history.
- Production HA, cross-replica visibility, multi-worker lease ownership, retention-expiry recovery, WORM/evidence integrity, load/soak/failure benchmarks and production latency targets are not established by these unit or COMPACT integration tests.
- Test output on this macOS host includes a Netty native DNS resolver fallback warning and Java native-access warnings. The tested loopback requests succeeded; platform runtime packaging should include the appropriate native resolver before production sign-off.

## Technical references checked

- VictoriaMetrics native export, query arguments and bounded search options: <https://docs.victoriametrics.com/victoriametrics/> (read 2026-09-06).
- VictoriaMetrics range-query timeout semantics: <https://docs.victoriametrics.com/victoriametrics/keyconcepts/> (read 2026-09-06).
- ClickHouse HTTP parameter binding, query cancellation and response-status caveats: <https://clickhouse.com/docs/concepts/features/interfaces/http> (read 2026-09-06).

The V5 master remains authoritative. This file records implementation and measured verification boundaries rather than replacing its domain or performance contracts.
