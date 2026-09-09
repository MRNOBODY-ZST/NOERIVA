-- Workspace lookups remain tenant-scoped; current snapshots only, never metric samples.
CREATE INDEX source_workspace_latest ON source_current(organization_id,observed_at DESC,device_id,source_id);
CREATE INDEX device_name_lookup ON device(organization_id,name,id);
CREATE INDEX device_address_lookup ON device(organization_id,management_address,id);
