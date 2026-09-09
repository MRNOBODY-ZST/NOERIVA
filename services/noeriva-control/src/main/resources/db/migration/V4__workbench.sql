-- Low-volume typed human workflows. High-frequency telemetry stays in the query stores.
CREATE TABLE workbench_record (
 organization_id VARCHAR(64) NOT NULL, category VARCHAR(20) NOT NULL, id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL, title VARCHAR(200) NOT NULL, status VARCHAR(24) NOT NULL,
 revision BIGINT NOT NULL, payload JSON NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 last_result JSON,last_observed_at TIMESTAMP(6),last_result_id VARCHAR(128),
 PRIMARY KEY(organization_id,category,id),
 INDEX workbench_page(organization_id,category,created_at,id),
 INDEX workbench_device(organization_id,category,device_id,created_at,id),
 INDEX workbench_status(organization_id,category,status,created_at,id),
 INDEX workbench_title(organization_id,category,title,created_at,id),
 FOREIGN KEY(organization_id,device_id) REFERENCES device(organization_id,id)
);
CREATE TABLE workbench_check_result (
 organization_id VARCHAR(64) NOT NULL,id VARCHAR(128) NOT NULL,check_id VARCHAR(64) NOT NULL,
 observed_at TIMESTAMP(6) NOT NULL,received_at TIMESTAMP(6) NOT NULL,payload JSON NOT NULL,
 PRIMARY KEY(organization_id,id),INDEX check_result_page(organization_id,check_id,observed_at,id)
);
CREATE TABLE workbench_network_evidence (
 organization_id VARCHAR(64) NOT NULL,id VARCHAR(128) NOT NULL,device_id VARCHAR(64) NOT NULL,site_id VARCHAR(64) NOT NULL,kind VARCHAR(20) NOT NULL,
 private_ip VARCHAR(45) NOT NULL,private_port INT,public_ip VARCHAR(45),public_port INT,protocol VARCHAR(4),
 search_from TIMESTAMP(6) NOT NULL,search_to TIMESTAMP(6) NOT NULL,received_at TIMESTAMP(6) NOT NULL,payload JSON NOT NULL,
 PRIMARY KEY(organization_id,id),
 INDEX network_public(organization_id,kind,public_ip,public_port,protocol,search_from,search_to),
 INDEX network_private(organization_id,kind,private_ip,private_port,protocol,search_from,search_to),
 INDEX network_lease(organization_id,kind,site_id,private_ip,search_from,search_to),
 FOREIGN KEY(organization_id,device_id) REFERENCES device(organization_id,id)
);
CREATE INDEX audit_resource_page ON control_audit(organization_id,resource_id,created_at,id);
