# Dell / Cisco monitoring deployment plan

Scope: enable existing Dell OS9 and Cisco IOS XE network collection on the two-node deployment; add independently controlled Cisco NBAR application observations and NAT HSL v9 audit events. Device identities and encrypted SNMP connections are reused. Existing routing, NAT forwarding and other devices remain outside these changes.

1. Inspect device SNMP permissions, interface identities and HSL configuration. Validate read-only SNMP v3 from the actual collectors. Enable interface/CPU/memory collection only after a successful published observation.
2. Add a separately leased application collector using CISCO-NBAR-PROTOCOL-DISCOVERY-MIB. Preserve Counter64 values, counter epochs and missing values; separate device-reported rates from computed rates. Store bounded searchable history in ClickHouse.
3. Add an independently enabled, source-bound NAT UDP receiver on node .62. Decode bounded NetFlow v9 templates, preserve event provenance and loss indicators, publish to Kafka and acknowledge durable ClickHouse writes. Configuration lives in MySQL; event history is never inferred from NAT snapshots.
4. Add `/applications` and `/nat-audit` pages, independent source controls, device links, bounded filters, and clear evidence-quality states. ADMIN controls configuration; existing read roles inspect observations.
5. Add MySQL V8/V9, ClickHouse schema and Kafka initialization; expose UDP NodePort 32055 with Local traffic policy and pin receiver to .62. Deploy verified amd64 images to both nodes.
6. Configure Cisco export after receiver readiness, preserve a private before/after snapshot, verify real templates and create/delete events. Validate application statistics across two polls and interface monitoring from both devices, then use ComputerUse to inspect new pages and controls.
7. Record deployment/API/hardware/UI evidence and remaining capture limitations. Remove temporary plaintext credential files after encrypted configuration is verified.

Parallel ownership: NAT package/schema/backend tests (backend_workbench); SNMP and applications package/schema/tests (inventory_queries); frontend routes/pages/tests (frontend_clarity); shared configuration/security, devices, deployment and end-to-end validation (root).
