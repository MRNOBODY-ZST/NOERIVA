CREATE TABLE rollup_progress (
 organization_id VARCHAR(128) NOT NULL,device_id VARCHAR(128) NOT NULL,interface_id VARCHAR(128) NOT NULL,source_id VARCHAR(128) NOT NULL,direction VARCHAR(2) NOT NULL,
 next_revision BIGINT NOT NULL DEFAULT 0,last_applied_revision BIGINT NOT NULL DEFAULT 0,
 processed_from TIMESTAMP(6),processed_through TIMESTAMP(6),complete_from TIMESTAMP(6),complete_through TIMESTAMP(6),coverage DOUBLE,
 PRIMARY KEY(organization_id,device_id,interface_id,source_id,direction)
);
