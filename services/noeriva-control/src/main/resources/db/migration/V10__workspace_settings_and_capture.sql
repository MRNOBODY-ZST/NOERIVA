CREATE TABLE organization_settings (
 organization_id VARCHAR(64) PRIMARY KEY,
 revision BIGINT NOT NULL,
 payload JSON NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 updated_by VARCHAR(120) NOT NULL
);
CREATE TABLE configuration_capture (
 organization_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NOT NULL,
 status VARCHAR(32) NOT NULL, message VARCHAR(500) NOT NULL DEFAULT '',
 last_attempt_at DATETIME(6), last_success_at DATETIME(6), next_capture_at DATETIME(6),
 snapshot_id VARCHAR(64), content_hash VARCHAR(64), source VARCHAR(120),
 PRIMARY KEY(organization_id,device_id)
);
