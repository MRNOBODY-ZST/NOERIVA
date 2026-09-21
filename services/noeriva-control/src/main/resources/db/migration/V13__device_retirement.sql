-- Retain the identity and all historical references when an administrator removes an asset.
ALTER TABLE device ADD COLUMN deleted_at TIMESTAMP(6) NULL, ADD COLUMN deleted_by VARCHAR(120) NULL;
CREATE INDEX device_active_page ON device(organization_id,deleted_at,id);
