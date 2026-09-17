-- Stable exporter timestamp is part of the identity order so datagram replays
-- deduplicate even when a receiver restart gives them a different received_at.
CREATE TABLE IF NOT EXISTS noeriva.nat_audit_events
(
 organization_id String, device_id String, event_id FixedString(64),
 exported_at DateTime64(3,'UTC'), received_at DateTime64(3,'UTC'),
 private_ip String, public_ip String, protocol Int16,
 payload String, revision UInt64,
 INDEX nat_received_at received_at TYPE minmax GRANULARITY 1,
 INDEX nat_private_ip private_ip TYPE bloom_filter(0.01) GRANULARITY 1,
 INDEX nat_public_ip public_ip TYPE bloom_filter(0.01) GRANULARITY 1
)
ENGINE = ReplacingMergeTree(revision)
PARTITION BY toYYYYMM(exported_at)
ORDER BY (organization_id,device_id,exported_at,event_id)
SETTINGS index_granularity = 256;
-- Small granules bound the second-stage payload lookup even when exporter and
-- receipt clocks are uncorrelated. Existing tables require an explicit migration;
-- CREATE IF NOT EXISTS does not change their immutable index granularity.
-- No implicit retention deletion. API bounds execution and fails closed when
-- the selected organization's history exceeds its query budget.
