-- Low-volume normalized state/control events only. No automatic TTL.
-- A given event_id keeps its observed_at; revisions replace its full snapshot.
CREATE TABLE IF NOT EXISTS noeriva.control_events
(
    organization_id String,
    event_id String,
    device_id String,
    observed_at DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC'),
    kind LowCardinality(String),
    severity LowCardinality(String),
    message String,
    source String,
    revision UInt64
)
ENGINE = ReplacingMergeTree(revision)
PARTITION BY toYYYYMM(observed_at)
ORDER BY (organization_id, device_id, observed_at, event_id);
