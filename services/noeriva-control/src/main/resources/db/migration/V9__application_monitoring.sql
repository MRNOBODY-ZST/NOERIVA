CREATE TABLE application_source (
 organization_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 interval_seconds INT NOT NULL, interface_indices JSON NOT NULL, max_rows INT NOT NULL,
 status VARCHAR(24) NOT NULL, last_attempt_at DATETIME(6) NULL, last_success_at DATETIME(6) NULL,
 next_poll_at DATETIME(6) NOT NULL, error_code VARCHAR(64) NOT NULL DEFAULT '', error_message VARCHAR(500) NOT NULL DEFAULT '',
 lease_token VARCHAR(64) NOT NULL DEFAULT '', lease_until DATETIME(6) NULL,
 baseline JSON NULL, baseline_credential_revision BIGINT NOT NULL DEFAULT 0,
 last_row_count INT NOT NULL DEFAULT 0, quality_flags JSON NOT NULL,
 PRIMARY KEY(organization_id,device_id),
 KEY idx_application_due(organization_id,enabled,next_poll_at),
 CONSTRAINT fk_application_device FOREIGN KEY(organization_id,device_id) REFERENCES device(organization_id,id),
 CONSTRAINT ck_application_interval CHECK(interval_seconds BETWEEN 30 AND 3600),
 CONSTRAINT ck_application_rows CHECK(max_rows BETWEEN 1 AND 256)
);
