# Console page specifications

Baseline: B / Clarity, Chinese, light default, 44px rows, device-centered navigation. Frontend owns presentation only. API contract: `../implementation/API-CONTRACT.md`. All timestamps show selected IANA timezone; UTC is explicitly a display default. All queries carry authenticated organization/user boundaries via the session and Query keys.

## Shared contracts

- Real API loading/error/empty states use QueryState; source freshness and health remain distinct. Errors preserve request IDs. Denied requests are not empty results. Unsupported capabilities carry an explicit explanation.
- List pages use bounded server pagination; stable ID ascending is the only implemented device sort. No client-side sort misrepresents a partial result set. Filters reset the cursor stack.
- All routes have semantic headings, labelled controls, keyboard focus and responsive table scroll regions. Dialogs use native modal focus/escape semantics. Authentication remains in memory; logout cancels and clears sensitive queries.
- Remote state belongs to TanStack Vue Query; Pinia owns session metadata and theme/density/timezone/sidebar state; local form state stays in refs.
- Requests poll every 20 seconds only while their queries are active. The footer states polling; there is no fictional realtime label. SSE adoption remains a later optimization using the documented shared dispatcher contract.

| Route | Operator question / hierarchy | API and actions | Permission / states | Acceptance |
|---|---|---|---|---|
| `/overview` | What requires attention? Health ribbon, exception cue, device table, alert rail, collector freshness | GET overview/devices/alerts/collectors; drill into device/alerts | Authenticated readers; counts preserve unknown and overlapping stale semantics | Counts from API; direct links; failure retained; no unsupported remediation |
| `/devices` | What devices exist? Filter bar, bounded table, paging | GET devices/sites; POST devices; name/IP search, site/type/health filters, cursor navigation | ADMIN/OPERATOR create; VIEWER reads; new records UNKNOWN | Real create result navigates to committed ID; filters reset paging; no device connection implied |
| `/devices/:id?tab=` | What is the state of this device and its sources? Identity, health/availability/time, tabs | GET summary/interfaces/metrics/heatmap/connections/activity; metric/range/interface/direction selectors | Authoritative device ownership checked by backend; unsupported/empty source shown | No fabricated values; source IDs/time/epoch/sequence visible; host/BMC remain distinct |
| `/connections` | Which observed devices are connected? Scoped graph and inspector | GET topology with optional deviceId/limit200; drag, pan, zoom, fit, inspector, device links, list alternative | Authenticated scoped data; empty graph explicit | Node selection and double-click route; keyboard list/zoom alternatives; timestamps don't reset graph structure |
| `/alerts` | Which abnormal conditions need acknowledgement? State tabs, severity/title/asset/time | GET alerts; POST acknowledge with revision | ADMIN/OPERATOR acknowledge; VIEWER read only; 409 refreshes current state | Committed ack shown separately from recovery; errors not hidden; no automatic resolve |
| `/events` | What source supports an observed event? Timeline table and source dialog | GET events bounded by recent7days; device scope, cursor pages | Authenticated reads; escaped text; no signed package claims | Source observation/ingest timestamps, quality flags, ID visible; absent matches explained |
| `/collectors` | Can the collection chain be trusted? Per-source status/time/backlog/version | GET collectors | Read only; synthetic sources explicitly labelled | Heartbeat != successful collection; collector offline != device offline |
| `/system` | Which capabilities are actually implemented? Independent status axes, session role | GET capabilities | Server matrix authoritative | Roadmap/maturity/coverage/verification preserved; no layout-count support claims |
| Sign-in at requested route | Can I enter my workspace? Account/password/error | Basic GET session → opaque Bearer session when supplied, Basic fallback | In-memory only; normal password-manager/paste support | Invalid credentials visible; refresh requires login; no secret persisted |

## Device detail composition

- **总览 / 监测:** current source metrics, actual queried trend, seven-day interface heatmap and source records. Chart titles, units, ranges, timezone, freshness, coverage and revision are visible; full data tables provide access without hovering.
- **网络:** actual interface records with admin/oper status and declared speed. Empty interfaces are explained.
- **连接:** device-scoped GraphPanel; observed relationships and source provenance. It does not prove traffic traversed a path.
- **活动:** scoped event table and source inspector.
- **配置:** explicit planned state with matrix link; no fake snapshots/downloads/apply controls.
- **资料:** control-plane record identity, model/site/vendor and source-declared capability identifiers. Declarations do not equal protocol verification.

## Mandatory end-state coverage retained

These route families are retained as design/roadmap inventory, not implemented successful workspaces. The current fallback opens the capability status page. Full domain specifications remain in Master V5 F07 and capability/architecture documents.

| End-state route families | Intended placement / operator question | Phase dependency |
|---|---|---|
| `/assets/hosts`, `/assets/bmcs`, `/assets/network` | Device filters + context views; host/OS vs hardware/BMC remain separate | 1–3 |
| `/monitoring`, `/monitoring/checks` | Cross-device monitoring and independent service checks; reachable without first identifying a device | 2–3 and synthetic checks |
| `/network/interfaces`, `/network/switching`, `/network/routing`, `/network/optics` | Device Network tab with global deep links; observed L2/L3/optic conditions and source freshness | 3 |
| `/events`, `/search` | Global event and typed search context; current search covers devices only | 4 |
| `/audit/hosts`, `/audit/kubernetes`, `/audit/aaa` | Authorized administrative/OS/audit timeline with redaction | 5–6 |
| `/audit/vpn`, `/audit/assignments`, `/audit/dns` | Historical identity and IP ownership intervals with uncertainty | 6 |
| `/network/flows`, `/audit/nat`, `/audit/firewall`, `/audit/investigator` | Cross-device connection/NAT investigation; requires port/protocol/time and interval evidence | 7 |
| `/configuration` | Read-only snapshots, semantic diff/drift/compliance, source checksums and redaction | 8 |
| `/incidents`, `/reports`, `/slos` | Correlated exception chronology, policy-bounded reporting and defined SLOs | 9 |
| `/audit/evidence`, `/administration` | Evidence manifests/access/holds, roles/policy/retention; fundamental source/auth controls start at foundation | 10 hardening |
| `/assets/virtualization`, `/assets/storage`, `/assets/power`, `/assets/wireless` | Capability-aware device details and relationship views | 11 |
| `/assets/kubernetes` | Cluster/workload topology and audit context | 12 |
| `/integrations`, `/self-observability`, `/auth` | Adapter status, product health, full identity/session recovery | Foundation + 13 hardening |

## Deliberate first-slice limits

This implementation does not claim SSO, full internationalization, saved searches, arbitrary graph editing, cross-site graph filtering, generalized offline history, chart aggregation in the browser, NAT attribution, evidence export or configuration mutation. Browser resource caching never manufactures API responses. Exact current implementation status is separated in PROTOTYPE-COVERAGE.md.
