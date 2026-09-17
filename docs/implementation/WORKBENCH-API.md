# 澄明工作台 API

Base `/api/v1/workbench`. JSON, session authentication, organization from the authenticated identity only. GET requires VIEWER/OPERATOR/ADMIN; ordinary POST requires OPERATOR/ADMIN; investigation queries also allow VIEWER. Evidence and configuration imports require ADMIN. Collector result/network imports require COLLECTOR or ADMIN, checked explicitly. Every POST requires `X-Noeriva-Request: 1`.

All list responses: `{items:[],nextCursor:string|null,asOf:ISO,source:"CONTROL",mode:"CONNECTED"|"DEMO"}`. Default `limit=50`, valid 1–100; opaque `cursor` max 512. Domain lists order by creation time descending then ID descending. Incident, evidence, snapshot and check-definition lists accept optional `deviceId`; check-result history is scoped by its check path ID, and audit accepts the filters documented below. `q` is a literal title/name prefix, max 120, where listed. Server-created incident/evidence/snapshot/check IDs are UUIDs; imported check-result and network-evidence IDs are supplied by the caller. Dates are UTC ISO strings. Null means unknown. Mode DEMO is explicit process-local synthetic state; CONNECTED uses MySQL transactions. `provenance` is `MANUAL` or `SYNTHETIC`, supplied on imports; it describes the import, never device verification. Authorization is organization/role based; filtering or correlating by site does not establish fine-grained site permissions.

## Incidents

- `GET /incidents?deviceId=&status=&q=&cursor=&limit=`. Status empty/all or OPEN/INVESTIGATING/RESOLVED.
- `POST /incidents` → 201. Input `{title,severity,deviceId,alertId:null,assignee:null,note:""}`. Title 1–200, severity INFO/WARNING/CRITICAL, device must exist, optional alert must belong to that device. Assignee optional account in this organization, not a freeform person. Note ≤2000.
- `GET /incidents/{id}`.
- `POST /incidents/{id}/updates` → committed record. `{revision,title,status,assignee:null,note:""}`; resolving requires a note. Stale revision returns 409. Maximum 100 notes; no note content overwrite.

Incident: `{id,title,severity,status,deviceId,alertId,assignee,createdBy,createdAt,updatedAt,revision,notes:[{id,author,text,createdAt}]}`. Incident list items are IncidentSummary: the same fields with `notes` omitted and `noteCount` added. Notes are fetched with incident detail. MySQL projects large note/content bodies out of list queries before network transfer and decoding. New status OPEN/revision 1. Updating title/status/assignee requires the current revision; a nonblank note appends an immutable note.

## Evidence

- `GET /evidence?deviceId=&q=&cursor=&limit=` → metadata records (without content).
- `POST /evidence` → 201, ADMIN. `{deviceId,title,kind,source,observedAt,content,provenance}`. Kind NOTE/EVENT/OBSERVATION/NAT/LEASE/CONFIGURATION/CHECK; source 1–120; content 1–32768 characters (also ≤64 KiB UTF-8). Observation time from 2000 through now+120s. Content is text and is never executed.
- `GET /evidence/{id}` → full evidence; checksum is verified before returning content and reading is audited. A checksum mismatch returns 409 `EVIDENCE_INTEGRITY_FAILURE` and records an integrity-failure audit entry.
- `GET /evidence/{id}/manifest` → `{version:1,algorithm:"SHA-256",sha256,integrity:"UNSIGNED",worm:false,evidence:{...full evidence...}}`; reading/export is audited. SHA-256 covers the documented canonical `content` UTF-8 bytes exactly, permitting independent verification. It is a checksum, not a signature or legal integrity guarantee.

Evidence: `{id,deviceId,title,kind,source,observedAt,content,provenance,sha256,integrity:"UNSIGNED",createdBy,createdAt}`. List metadata has the same fields except `content`. No delete/overwrite endpoint.

## Configuration

- `GET /configuration/snapshots?deviceId=&q=&cursor=&limit=` → metadata (without content).
- `POST /configuration/snapshots` → 201, ADMIN. `{deviceId,title,source,capturedAt,content,provenance}`. Content ≤65536 characters, ≤128 KiB UTF-8, ≤2000 lines. Common credential-bearing lines and private-key blocks are replaced server-side before storage/checksum; source files should already be sanitized. Common composite keys (PrivateKey, rocommunity, authentication-key, SecretAccessKey), credential URLs and private-key blocks are covered; this is a documented pattern filter, not complete verification of every vendor format. No device push/restore action.
- `GET /configuration/snapshots/{id}` → `{id,deviceId,title,source,capturedAt,content,provenance,sha256,redactedLines,createdBy,createdAt}`; audited. Snapshot reads and both sides of diffs verify the stored checksum; mismatch returns audited 409 `CONFIGURATION_INTEGRITY_FAILURE`.
- `GET /configuration/diff?before=<id>&after=<id>` → `{deviceId,beforeId,afterId,added,removed,unchanged,lines:[{kind:"CONTEXT"|"ADDED"|"REMOVED",beforeLine:number|null,afterLine:number|null,text}],asOf}`. Both snapshots must belong to the same authorized device. Deterministic line diff; bounded 2000 lines per snapshot. Read is audited.

## Synthetic checks

- `GET /checks?deviceId=&q=&cursor=&limit=`.
- `POST /checks` → 201. `{deviceId,name,type,target,intervalSeconds,enabled,provenance}`. Type TCP/HTTP/HTTPS/DNS/TLS, target 1–253 printable characters, interval 30–86400. Registering a definition never contacts the target.
- `GET /checks/{id}`.
- `POST /checks/{id}/updates` → `{revision,name,target,intervalSeconds,enabled}`; current revision required. Every definition update clears the latest-result projection; historical results remain readable with their definition revision. Definition remains bound to its original device/type/provenance.
- `POST /checks/{id}/archive` → `{revision}`; archives definition, disables it; retains history. An archived check cannot be changed or ingest further results.
- `GET /checks/{id}/results?cursor=&limit=30` → Page<CheckResult> ordered observation time descending.
- `POST /check-results` → 201, COLLECTOR or ADMIN. `{id,checkId,observedAt,status,latencyMs:null,message:"",source,provenance,definitionRevision:1}`. Omitted/null definitionRevision defaults to 1; after an update reporters must supply the current definition revision. A stale revision is rejected under the same row lock as result persistence (409). Stable caller ID 1–128 `[A-Za-z0-9_-]`; PASS/FAIL/UNKNOWN; PASS requires finite latency 0–600000ms, otherwise latency optional; message ≤1000. Repeating identical ID/body returns existing result; different body returns 409. Result time [2000, now+120s]. Source describes reporting source. Synthetic provenance is preserved.

Check: `{id,deviceId,name,type,target,intervalSeconds,enabled,archived,provenance,execution:"COLLECTOR_REPORTED",revision,createdBy,createdAt,updatedAt,lastResult:CheckResult|null}`. CheckResult: input fields plus `receivedAt`, with nonnull integer `definitionRevision`; observedAt is normalized to microsecond precision, matching persisted history ordering. An unobserved definition has lastResult null; it is not PASS.

The repository does not include a probe scheduler/executor. `enabled` and `intervalSeconds` store execution intent for a separately integrated reporter; saving, enabling or disabling a definition never contacts the target or stops an external process. A disabled, unarchived definition may still accept a valid current-revision report; archiving rejects further reports. There is no DELETE endpoint or independent site-level execution authorization. The console edits/enables/disables definitions and displays the latest 30 history rows; archive and further result-history cursors are API capabilities without corresponding console controls.

## Temporal investigation

- `POST /network-evidence` → 201, COLLECTOR or ADMIN. One typed record: `{id,deviceId,kind:"NAT"|"ADDRESS_LEASE",privateIp,privatePort:null,publicIp:null,publicPort:null,protocol:null,validFrom,validTo,lifecycle:"COMPLETE"|"SNAPSHOT_ONLY",clockUncertaintyMs:0,source,provenance}`. NAT requires both IPs/ports and TCP/UDP, lease requires no NAT fields. IPs are literal IPv4/IPv6, never resolved. Ports 1–65535. Positive validity interval ≤31 days, clock uncertainty 0–300000ms. Caller IDs stable 1–128; identical replay idempotent, conflicting payload 409.
- `POST /investigations` → `{id,query:{ip,port,protocol,at,direction},status,candidates:[{nat:NetworkEvidence,leases:NetworkEvidence[],qualityFlags:[]}],qualityFlags:[],asOf,mode}`. Input `{ip,port,protocol,at,direction:"PUBLIC_TO_PRIVATE"|"PRIVATE_TO_PUBLIC"}`. Queries typed interval evidence, including explicitly bounded clock uncertainty. Outcomes: CONFIRMED only one COMPLETE NAT and one matching COMPLETE lease in the same captured site at the exact instant, no clock uncertainty; AMBIGUOUS for overlapping candidates/leases or uncertainty; INSUFFICIENT_EVIDENCE for snapshot-only/missing lease; NO_MATCH means no retained matching NAT records. Maximum 100 NAT candidates and 100 leases; exceeding bound fails 429, never truncates into a false conclusion. No personnel attribution or packet payload is inferred. Queries are audited.

Network evidence: input plus `siteId` and `receivedAt`. `siteId` is derived from the registered device at import, never accepted from the caller, and remains fixed if current inventory changes later. For NAT records deviceId identifies the observing gateway; for ADDRESS_LEASE it identifies the lease subject. Correlation matches captured siteId + private IP + provenance, preserving both IDs. A gateway and a different host in the same site can confirm; a lease from another site cannot supply attribution. Multiple concurrent leases remain ambiguous; distinct lease subjects additionally carry CONFLICTING_DEVICE_CLAIM. The scope is the imported site, not independently established historical location or VRF membership. Investigation identifiers identify an audited query, not a durable exported evidence package.

## Audit

`GET /audit?resourceId=&from=&to=&cursor=&limit=` → Page<{id,actor,action,resourceId,createdAt}>. Defaults previous 7 days, maximum 31 days. Resource ID ≤128. Includes existing inventory/alert audit entries and workbench writes/reads. Access is scoped to the authenticated organization; credentials and evidence content are not copied into audit rows.

Application errors retain `{code,message,requestId}`. Missing or other-organization references return 404. Invalid inputs return 400, stale revisions/conflicting stable IDs 409, bounded-capacity rejection 429. Transactions persist workbench mutations and their audit entries together. Workbench SELECT queries have a MySQL MAX_EXECUTION_TIME(3000) budget; diffs run on a bounded two-thread CPU scheduler. No network probes, signature service, WORM, or real-device configuration execution are implied by these endpoints.
