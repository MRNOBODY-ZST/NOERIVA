CREATE TABLE nat_audit_source (
 organization_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NOT NULL,
 source_address VARCHAR(45) NOT NULL, revision BIGINT NOT NULL, enabled BOOLEAN NOT NULL,
 last_packet_at TIMESTAMP(6) NULL, last_event_at TIMESTAMP(6) NULL, last_persisted_at TIMESTAMP(6) NULL,
 last_error VARCHAR(100) NULL, quality_flags JSON NOT NULL,
 templates BIGINT NOT NULL DEFAULT 0, received BIGINT NOT NULL DEFAULT 0,
 accepted BIGINT NOT NULL DEFAULT 0, persisted BIGINT NOT NULL DEFAULT 0,
 dropped BIGINT NOT NULL DEFAULT 0, sequence_gaps BIGINT NOT NULL DEFAULT 0,
 unknown_templates BIGINT NOT NULL DEFAULT 0, parse_errors BIGINT NOT NULL DEFAULT 0,
 duplicate_packets BIGINT NOT NULL DEFAULT 0, restart_count BIGINT NOT NULL DEFAULT 0,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,device_id), UNIQUE KEY nat_exporter_address(source_address),
 INDEX nat_enabled(enabled,organization_id,device_id),
 FOREIGN KEY(organization_id,device_id) REFERENCES device(organization_id,id)
);
