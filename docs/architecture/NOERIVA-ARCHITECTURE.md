# NOERIVA architecture — implemented first slice

The [V5 master](../source-v5/NOERIVA_MASTER_PROMPT_V5.md) remains the authority. The repository now contains a Java 25 / Spring Boot 4.1.1 control application, an independently testable query/rollup library, a Vue/TypeScript console, local Compose packaging and Helm application packaging. This is a partial experimental vertical slice. It is not completion of all fourteen phases, verified vendor support, or production HA certification.

## Runtime boundaries

| Unit | Actual responsibility |
|---|---|
| `services/noeriva-control` | Spring WebFlux HTTP API, Spring Security, sessions, organization-scoped inventory/interfaces, current-source projections, basic state alerts/acknowledgement, Kafka state ingestion/history projection, transactional MySQL outbox and rollup progress |
| `services/noeriva-query` | Named metric queries, calendar heatmaps, exact counter arithmetic, bounded admission, raw-export recomputation and full ClickHouse rollup snapshots |
| `frontend/noeriva-console` | Device-centered Vue console; remote state through typed APIs, metric/graph/heatmap rendering, Chinese operator UI and explicit demo/coverage states |
| Compose / Helm | COMPACT dependencies and non-root application packaging; production databases can be operator-managed or external |

These are purposeful modules, not a requirement to create a separate service for every future domain. Query and rollup computation use independent bounded workers. No normal request-path storage operation calls `.block()` on a Netty event loop. Startup migrations/bootstrap and the explicitly dedicated Kafka consumer boundary are separate from request processing.

## Data ownership

| Data | V5 owner | Current implemented extent |
|---|---|---|
| Inventory, permission policy, workflow and outbox | MySQL / R2DBC | Partial control model, source checkpoints, durable mutation/outbox and rollup state |
| Numerical hot samples | vmagent + VictoriaMetrics | Native named queries and raw counter export; synthetic-source integration verified |
| Replayable discrete transport | Kafka | Low-volume JSON state/outbox lane; high-volume Protobuf and bounded Kafka Streams topology remain future implementation |
| Typed history and metric summaries | ClickHouse | Structured current-slice history plus versioned five-minute bandwidth summaries |
| Redacted text/entity search | Selective Elasticsearch | Designed; current slice does not claim a complete global-search index |
| Serving cache/session and invalidation hints | Redis | Current slice stores short-lived opaque sessions; query/current-state caching and invalidation hints remain planned; no durable high-volume history ownership |
| Raw evidence, configuration objects, exports | Supported S3-compatible ObjectStore | Designed; no verified WORM, legal-hold or signed-evidence implementation claimed |

Each observation has an explicit owning path. There is no synchronous global transaction across MySQL, Kafka, ClickHouse, VictoriaMetrics, Redis and S3. Received, durably queued, queryable and evidence-archived are different states. A successful state batch is not a claim that raw evidence was archived.

## Correctness and query boundaries

Current state preserves source epoch/sequence/time and keeps host/BMC freshness independent. Device reads use current models, not historical metric scans. Control actions return committed revisions and use a transactional outbox. Basic alert state is not a duplicate implementation of the future full numeric/correlation rule engine.

The query module admits state, charts, investigation, export and rebuild under independent bounded capacities. Metric names are allowlisted and org/device scope is mandatory. Heatmaps select one authorized interface, direction and configured source. The API returns source time, coverage, revision, resolution, provisional state and quality flags. Unknown values remain null; provider failure does not become zero or a false successful empty result.

The implemented heatmap uses corrected five-minute snapshots and a 168-cell local-calendar scaffold. It handles missing/zero/partial/future/DST semantics, including repeated hours and UTC+05:30 boundaries. Raw counters remain integer-safe during delta arithmetic; VictoriaMetrics-derived counters explicitly disclose floating-point quantization. The asynchronous recomputation worker never treats a notification as proof of sample visibility.

Administrators can enqueue audited `ROLLUP_REPAIR` jobs for a known late-data window through `/api/v1/query-jobs`. Jobs are durable and organization-scoped, with a 100-active-job cap, exclusive leased execution, a 30-second processing deadline and explicit failure codes. They repair at most one day per job and are not an export API. Automatic historical dirty-window discovery remains unimplemented.

Protected lookups remain an explicit future boundary: canonical NAT interval selection must precede corrected validity filtering; candidate-index lag cannot yield definitive `NO_MATCH`. There is no implemented NAT/identity investigator pretending to satisfy this contract.

## Security and deployment

The first slice uses organization-scoped Spring Security controls, an explicit bootstrap flow, secret references and stateless authorization headers. Physical device access remains read-only by default. Admin, collector, metric-scrape and operator responsibilities are separated where implemented; granular audit/raw/export permissions remain required before future sensitive domains are enabled.

COMPACT is a local single-instance verification profile. Kubernetes application charts can use external/operator-managed data services. NetworkPolicy is not encryption and replicas are not backups. HA_PERF/SCALE references from V5 remain plans requiring real failure domains, capacity measurements, recovery drills and supported-version validation.

NoerivaEdge mTLS enrollment, actual Redfish/SNMP/vendor adapters, NAT/Flow/identity correlation, complete configuration audit, evidence retention locks and broad production hardening are not verified implementations in this delivery. All mandatory domains remain in the [capability matrix](../capabilities/CAPABILITY-MATRIX.md), with separate phase, maturity, coverage and verification axes.

Implementation evidence and limitations are in [query-report.md](../implementation/query-report.md). Repository-level build, API and UI verification belongs in the final verification report. Performance numbers in V5 remain targets, not results of this first slice.
