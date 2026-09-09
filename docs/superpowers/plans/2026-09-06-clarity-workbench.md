# Clarity Workbench Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement independently owned tasks and review the integrated result.

**Goal:** Implement the user-selected Clarity reference as a working 15-page operations workspace with persisted investigation and response workflows.

**Architecture:** Retain Vue 3 and existing control/query services. Add bounded workspace read projections and isolated workbench domain APIs with MySQL migrations; preserve existing metric and rollup contracts.

**Tech Stack:** Vue 3 / TypeScript / TanStack Query, Java 25 / Spring WebFlux / R2DBC, MySQL, Kafka, Redis, VictoriaMetrics, ClickHouse, Docker Compose, Helm.

**Spec:** `docs/frontend/CLARITY-PAGE-PLAN.md`; API definitions in `docs/implementation/WORKSPACE-API.md` and `WORKBENCH-API.md`.

## Global Constraints

- Exact B tokens and table/open-layout hierarchy in the page specification.
- Preserve existing workspace changes, credentials, unrelated running apps and port ownership.
- Tenant comes from authenticated identity; role check and reference validation run on server.
- Never label a synthetic record as a real device observation; do not equate missing data with zero.
- Existing metric time boundary and inventory-only empty-state regressions remain tested.
- Use Computer Use for UI validation; local file URL rejected by browser policy, so reference CSS/HTML is read as authorized source data, not opened using an alternate browser path.

## Task 1: Shell and existing pages

Files: App.vue, SidebarNav.vue, router.ts, styles/tokens.css, styles/base.css, preferences store, current page components.

- [x] Extract exact colors, typography, navigation and B overview composition from source.
- [x] Build grouped navigation and route aliases; retain source and metric capabilities.
- [x] Consume workspace overview projection, preserve correct scope and truthful metrics.
- [x] Validate tests/build and compare implemented DOM style to extracted reference tokens.

## Task 2: Bounded read models

Files: new WorkspaceQueryController/WorkspaceReadRepository and tests; WORKSPACE-API.md.

- [x] Define exact DTOs for source monitoring, joined interfaces, overview and bounded search.
- [x] Test pagination bounds, tenant filtering and error conditions.
- [x] Implement indexed SQL and demo mappings; no N+1 upstream requests.
- [x] Verify query contracts against frontend consumers.

## Task 3: Persistent workbench domains

Files: new Workbench classes, V4 migration, tests; WORKBENCH-API.md.

- [x] Define request/response contracts before UI integration.
- [x] Test role checks, reference validation, revision conflicts, redaction and temporal ambiguity.
- [x] Implement incidents, immutable evidence, redacted snapshots/diff, checks/results and NAT/lease investigation.
- [x] Verify persistence and audit transaction behavior with local MySQL.

## Task 4: Eight new page components

Files: MonitoringPage, NetworkPage, IncidentsPage, InvestigatorPage, EvidencePage, ConfigurationPage, ChecksPage, SettingsPage; services/workbench.ts and components shared only where semantics match.

- [x] Implement purpose-specific UI from the page specification and exact domain contracts.
- [x] Add source-aware empty/error states, stable submitted query state and revision-aware mutations.
- [x] Validate type contracts and key failure/interaction behavior.

## Task 5: Connected Mock validation and delivery

Files: scripts/mock-workbench.py, docs/frontend/CLARITY-VERIFICATION.md and API docs.

- [x] Rebuild local API/worker/console without exposing credentials.
- [x] Seed named synthetic workflows via authenticated APIs and verify persisted reads.
- [x] Run appropriate backend/frontend checks and Computer Use workflows across all pages.
- [x] Review integrated code, fix actionable findings, lint/render Helm and state actual deployment limits.

Execution decision: explicit user refactor request and selected B reference authorize implementation; proceed in this workspace with independent file ownership. Detailed plan and verification remain reviewable; no production push or external deployment is implied.

## Delivery evidence

Completed on 2026-09-06. See `docs/frontend/CLARITY-VERIFICATION.md` for the ComputerUse page ledger, 108 backend tests, 32 frontend tests, local concurrency results, and actual deployment boundaries. Collector cards and topology edges remain data-dependent; their empty CONNECTED states were verified rather than replacing them with invented observations.
