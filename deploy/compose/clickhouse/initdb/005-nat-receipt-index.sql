-- Ordered, lightweight all-retained-history candidate index. Canonical event
-- identity and earliest receipt remain exclusively in nat_audit_events FINAL.
-- Repeated index rows are safe: the API validates canonical receipts and uses
-- exclusive (received_at,event_id) cursors. No TTL or data deletion is implied.
CREATE TABLE IF NOT EXISTS noeriva.nat_audit_receipts
(
 organization_id String, device_id String, received_at DateTime64(3,'UTC'),
 event_id FixedString(64), exported_at DateTime64(3,'UTC'),
 private_ip String, public_ip String, protocol Int16,
 INDEX receipt_private_ip private_ip TYPE bloom_filter(0.01) GRANULARITY 1,
 INDEX receipt_public_ip public_ip TYPE bloom_filter(0.01) GRANULARITY 1,
 INDEX receipt_protocol protocol TYPE set(256) GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(received_at)
ORDER BY (organization_id,device_id,received_at,event_id)
SETTINGS index_granularity = 256;
CREATE MATERIALIZED VIEW IF NOT EXISTS noeriva.nat_audit_receipts_mv
TO noeriva.nat_audit_receipts AS
SELECT organization_id,device_id,received_at,event_id,exported_at,private_ip,public_ip,protocol
FROM noeriva.nat_audit_events;
-- Existing installations must backfill AFTER creating this view, before deploying
-- the new query API. INSERT SELECT is server-side streaming; run once per retained
-- exported_at month (or smaller day batches), with explicit execution/read budgets.
-- Repeating a completed batch is safe for correctness but adds index rows.
-- INSERT INTO noeriva.nat_audit_receipts
-- SELECT organization_id,device_id,received_at,event_id,exported_at,private_ip,public_ip,protocol
-- FROM noeriva.nat_audit_events WHERE exported_at >= {from:DateTime64(3)}
-- AND exported_at < {to:DateTime64(3)} SETTINGS max_threads=2,max_execution_time=60,max_memory_usage=268435456;
