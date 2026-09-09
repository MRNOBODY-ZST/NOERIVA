# Device Integration Implementation Plan

> Use independent agents for protocol drivers and frontend work; the root owns contracts, vault, persistence, scheduler and integration verification.

**Goal:** Users can create/edit an asset, configure SNMP or Redfish credentials, identify its supported profile, inspect real readings and enable recurring read-only collection.

**Architecture:** Add bounded drivers to the existing Java service, with encrypted per-device connection records and durable leases. API instances serve onboarding; the existing worker executes collection. Keep Vue Clarity layout and existing metrics/history providers.

**Spec:** `docs/devices/DEVICE-INTEGRATION-API.md`; research in `docs/devices/*-PROTOCOL-RESEARCH.md`.

## Constraints

- No hardware available initially; protocol simulators must be identified as such. Real model/firmware verification remains explicit.
- Device transport credentials are separate from application login. Only ADMIN stores or uses device secrets; read roles can inspect sanitized telemetry.
- No device configuration changes, browser remote console, password guessing or automatic weaker protocol fallback.
- Preserve current data and existing tests. Separate metadata revision from source projection revision.
- Apply target CIDR, TLS trust/pin, traversal, timeout, response and concurrency bounds.

## Tasks

- [ ] Research official SNMP/MIB, Redfish resource formats and compatibility for all requested vendors.
- [ ] Implement and test SNMP driver against UDP protocol fixtures, including v2c/v3 authentication and counter discontinuity.
- [ ] Implement and test Redfish traversal, normalizers, authentication/TLS and bounded partial-resource handling against HTTPS fixtures.
- [ ] Add V6 schema, metadata editing, encrypted credentials, versioned operations, durable scheduler leases, publisher and safe SSE; test isolation, conflicts and secret redaction.
- [ ] Add Clarity metadata editor and connection/collection UI with masked secret state, automatic identity, observed capabilities and source tables.
- [ ] Wire Compose/Helm key and collector settings; preserve worker/API separation and document target network prerequisites.
- [ ] Run automated and connected protocol simulator tests, ComputerUse onboarding/edit/test/collect flows, build/deploy and write support/verification matrices.

Execution is authorized by the user's request to implement device support and create/edit workflows. Use the recommended protocol-first design; no additional approval gate for reversible local implementation.
