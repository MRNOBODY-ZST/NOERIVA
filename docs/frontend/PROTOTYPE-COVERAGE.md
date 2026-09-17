# Frontend coverage ledger

This is a real Vue client bound to `/api/v1`, not a standalone mockup. Synthetic data exists only in the explicit backend demo profile. The current user request authorized implementation; prior prototypes and unapproved defaults are not rewritten as historical approvals.

| Surface | DESIGNED | PREVIEW_INTERACTIVE | PRODUCTION_IMPLEMENTED (frontend) | BACKEND_VERIFIED |
|---|---|---|---|---|
| Shell / Chinese / light-dark / standard-compact / mobile drawer | Yes | Via explicit demo backend | Yes | Browser and API integration evidence recorded after QA |
| In-memory login / role-aware controls | Yes | Demo roles | Yes, Basic-to-Bearer contract | See implementation tests; not SSO |
| Overview / inventory filters / cursor paging / create | Yes | Demo backend | Yes | Real demo API, not device protocol proof |
| Device summary / metrics / source inspection | Yes | Demo backend | Yes | Query provider verification distinct from actual collector support |
| ECharts seven-day hour heatmap | Yes | Demo backend | Yes | 168-cell states, zero/missing/future/partial/DST semantics owned by backend |
| ECharts scoped connections / graph inspector / accessible list | Yes | Demo relationships labelled synthetic | Yes | Relationship payload, no assertion of actual discovery |
| Events / source dialog | Yes | Demo backend | Yes | No signed evidence package |
| Alerts / revision acknowledgement | Yes | Demo backend | Yes | Confirmation does not resolve condition |
| Collectors / capability matrix | Yes | Demo backend | Yes | Source declarations do not become verified vendor adapters |
| Config history, NAT/identity, evidence export, SSO, reports/SLOs and advanced adapters | Master V5 + route inventory | No fake successful surface | No | No |

`PRODUCTION_IMPLEMENTED (frontend)` means actual application source and API bindings exist; it is not a deployment, production-hardening or verified-infrastructure claim. Browser QA records actual successful and blocked checks in VISUAL-REVIEW.md.
