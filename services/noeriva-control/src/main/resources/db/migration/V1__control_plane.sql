CREATE TABLE organization (id VARCHAR(64) PRIMARY KEY, name VARCHAR(120) NOT NULL);
CREATE TABLE app_user (username VARCHAR(120) PRIMARY KEY, organization_id VARCHAR(64) NOT NULL, password_hash VARCHAR(100) NOT NULL, roles VARCHAR(150) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, permission_revision BIGINT NOT NULL DEFAULT 1, FOREIGN KEY(organization_id) REFERENCES organization(id));
CREATE TABLE site (organization_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL, name VARCHAR(120) NOT NULL, timezone VARCHAR(80), PRIMARY KEY(organization_id,id));
CREATE TABLE device (
 organization_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL, name VARCHAR(120) NOT NULL, type VARCHAR(32) NOT NULL,
 site_id VARCHAR(64) NOT NULL, vendor VARCHAR(120), model VARCHAR(120), management_address VARCHAR(253) NOT NULL,
 capabilities JSON NOT NULL, revision BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,id), INDEX device_site_page(organization_id,site_id,id), INDEX device_type_page(organization_id,type,id),
 FOREIGN KEY(organization_id,site_id) REFERENCES site(organization_id,id)
);
CREATE TABLE device_current (
 organization_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NOT NULL, health VARCHAR(16) NOT NULL,
 availability VARCHAR(16) NOT NULL, last_seen TIMESTAMP(6), revision BIGINT NOT NULL, PRIMARY KEY(organization_id,device_id),
 INDEX current_health(organization_id,health,device_id), FOREIGN KEY(organization_id,device_id) REFERENCES device(organization_id,id)
);
CREATE TABLE source_current (
 organization_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NOT NULL, source_id VARCHAR(64) NOT NULL,
 observed_at TIMESTAMP(6) NOT NULL, payload JSON NOT NULL, PRIMARY KEY(organization_id,device_id,source_id),
 FOREIGN KEY(organization_id,device_id) REFERENCES device(organization_id,id)
);
CREATE TABLE network_interface (
 organization_id VARCHAR(64) NOT NULL,id VARCHAR(64) NOT NULL,device_id VARCHAR(64) NOT NULL,payload JSON NOT NULL,
 PRIMARY KEY(organization_id,id), INDEX interface_device(organization_id,device_id,id), FOREIGN KEY(organization_id,device_id) REFERENCES device(organization_id,id)
);
CREATE TABLE topology_edge (
 organization_id VARCHAR(64) NOT NULL,id VARCHAR(64) NOT NULL,source_id VARCHAR(64) NOT NULL,target_id VARCHAR(64) NOT NULL,payload JSON NOT NULL,
 PRIMARY KEY(organization_id,id), INDEX edge_source(organization_id,source_id,id), INDEX edge_target(organization_id,target_id,id)
);
CREATE TABLE collector (
 organization_id VARCHAR(64) NOT NULL,id VARCHAR(64) NOT NULL,payload JSON NOT NULL, PRIMARY KEY(organization_id,id)
);
CREATE TABLE alert (
 organization_id VARCHAR(64) NOT NULL,id VARCHAR(128) NOT NULL,device_id VARCHAR(64) NOT NULL,device_name VARCHAR(120) NOT NULL,
 severity VARCHAR(16) NOT NULL,state VARCHAR(20) NOT NULL,title VARCHAR(300) NOT NULL,opened_at TIMESTAMP(6) NOT NULL,
 acknowledged_at TIMESTAMP(6),acknowledged_by VARCHAR(120),revision BIGINT NOT NULL,
 PRIMARY KEY(organization_id,id), INDEX alert_state(organization_id,state,opened_at,id), INDEX alert_device(organization_id,device_id,state,opened_at,id)
);
CREATE TABLE outbox (
 id CHAR(36) PRIMARY KEY,organization_id VARCHAR(64) NOT NULL,aggregate_id VARCHAR(128) NOT NULL,kind VARCHAR(64) NOT NULL,
 payload JSON NOT NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),published_at TIMESTAMP(6),
 INDEX outbox_pending(published_at,created_at,id)
);
CREATE TABLE control_audit (
 id CHAR(36) PRIMARY KEY, organization_id VARCHAR(64) NOT NULL, actor VARCHAR(120) NOT NULL, action VARCHAR(64) NOT NULL,
 resource_id VARCHAR(128) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX audit_scope(organization_id,created_at,id)
);
-- No automatic TTL, retention deletion, sample/log history, or credential material in device records.
