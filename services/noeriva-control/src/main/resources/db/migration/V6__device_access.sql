CREATE TABLE device_connection (
 organization_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NOT NULL, slot VARCHAR(16) NOT NULL,
 revision BIGINT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 settings JSON NOT NULL, ciphertext TEXT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'NOT_TESTED',
 last_attempt_at DATETIME(6) NULL,last_success_at DATETIME(6) NULL,next_poll_at DATETIME(6) NOT NULL,
 error_code VARCHAR(64) NOT NULL DEFAULT '',error_message VARCHAR(500) NOT NULL DEFAULT '',
 last_reading JSON NULL,last_published JSON NULL,
 lease_token VARCHAR(36) NOT NULL DEFAULT '',lease_until DATETIME(6) NULL,
 sequence BIGINT NOT NULL DEFAULT 0,source_epoch VARCHAR(36) NOT NULL,
 PRIMARY KEY(organization_id,device_id,slot),
 KEY ix_device_due(organization_id,enabled,next_poll_at,lease_until),
 FOREIGN KEY(organization_id,device_id) REFERENCES device(organization_id,id)
);
ALTER TABLE network_interface ADD COLUMN source_id VARCHAR(64) NOT NULL DEFAULT 'primary';

CREATE TABLE device_metric_binding (
 organization_id VARCHAR(64) NOT NULL,device_id VARCHAR(64) NOT NULL,metric VARCHAR(64) NOT NULL,slot VARCHAR(16) NOT NULL,
 PRIMARY KEY(organization_id,device_id,metric),
 FOREIGN KEY(organization_id,device_id,slot) REFERENCES device_connection(organization_id,device_id,slot)
);
