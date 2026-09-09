# Discovery candidates implementation plan

Scope approved in NETWORK-DISCOVERY-API.md. Only stored SSH evidence is read; no sockets, DNS, raw commands, credential copies, changes to devices/* or frontend/security.

1. Add DiscoveryIntegrationTest using isolated MySQL Testcontainers and actual Flyway migrations. Use reflection until DiscoveryService exists, so RED is an assertion failure rather than a compile failure. Cover CIDR/freshness/TTL, weak duplicate and contradictory observations, organization/site/read/write scope, repeated runs, existing inventory, concurrent register, explicit link, and evidence allowlist/budget.
2. Add V7 discovery_candidate and discovery_run tables with org/site/address uniqueness and page/MAC indexes. Keep platform asset UUIDs and history untouched.
3. Add DiscoveryModels immutable API records; DiscoveryEvidence handles strict literal IPv4/CIDR, fresh source JSON allowlist, bounded evidence and deterministic classification. No generic SNMP/SSH execution is added.
4. Add DiscoveryService with four permits and ten-second deadline around transactions; lock site before run/register/link, batch-read source JSON and matching inventory/candidates, upsert candidates in groups of32. Cancelled/failed writes roll back. Explicit register checks current same-site address and returns existing association before creating assets; repeated concurrent requests cannot create duplicate platform devices.
5. Add DiscoveryController four endpoints; service has independent role checks. Root owns filter/security integration and frontend agent consumes fixed contract.
6. Coordinate Maven with SSH agent, run RED then GREEN, inspect results, add focused regressions only for discovered gaps. Communicate contract deltas immediately. Update research implementation-status note without presenting wider neighbor discovery/physical merge design as completed.
