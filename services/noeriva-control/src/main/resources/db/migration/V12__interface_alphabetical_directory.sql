-- Rebuildable directory order follows payload names for both registration and
-- collector upserts; no application-side backfill or full-table read is needed.
ALTER TABLE network_interface ADD COLUMN name_sort VARCHAR(512)
 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
 GENERATED ALWAYS AS (LOWER(LEFT(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.name')),''),512))) VIRTUAL;
CREATE INDEX interface_workspace_name ON network_interface(organization_id,name_sort,id);
CREATE INDEX interface_device_name ON network_interface(organization_id,device_id,name_sort,id);
