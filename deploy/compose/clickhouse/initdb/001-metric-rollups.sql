CREATE DATABASE IF NOT EXISTS noeriva;

-- Revision identifies a replacement snapshot, not a new logical bucket.
-- No TTL: approved lifecycle/hold policy is required before deletion.
CREATE TABLE IF NOT EXISTS noeriva.metric_rollups
(
    organization_id String,
    device_id String,
    component_id String,
    metric_id String,
    direction String,
    source_id String,
    bucket_start_utc DateTime64(3, 'UTC'),
    bucket_end_utc DateTime64(3, 'UTC'),
    resolution_seconds UInt32,
    algorithm_version UInt32,
    revision UInt64,
    generated_at DateTime64(3, 'UTC'),
    last_observed_utc Nullable(DateTime64(3, 'UTC')),
    valid_duration_seconds Float64,
    valid_counter_delta Decimal(38, 6),
    sample_count UInt32,
    quality_flags Array(String)
)
ENGINE = ReplacingMergeTree(revision)
PARTITION BY toYYYYMM(bucket_start_utc)
ORDER BY (organization_id, device_id, component_id, metric_id, direction,
          resolution_seconds, bucket_start_utc, source_id, algorithm_version);
