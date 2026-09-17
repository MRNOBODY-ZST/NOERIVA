CREATE TABLE IF NOT EXISTS noeriva.application_observations
(
 organization_id String, device_id String, observation_id String,
 observed_at DateTime64(3, 'UTC'), interface_index UInt32, direction LowCardinality(String),
 application String, payload String, revision UInt64
)
ENGINE = ReplacingMergeTree(revision)
PARTITION BY toYYYYMM(observed_at)
ORDER BY (organization_id, device_id, observed_at, observation_id)
SETTINGS index_granularity = 2048;
