CREATE TABLE discovery_candidate (
 organization_id VARCHAR(64) NOT NULL,id CHAR(36) NOT NULL,site_id VARCHAR(64) NOT NULL,
 address VARCHAR(15) NOT NULL,mac VARCHAR(17) NULL,status VARCHAR(32) NOT NULL,revision BIGINT NOT NULL,
 associated_device_id VARCHAR(64) NULL,payload JSON NOT NULL,
 PRIMARY KEY(organization_id,id),UNIQUE KEY discovery_address(organization_id,site_id,address),
 KEY discovery_site_page(organization_id,site_id,id),KEY discovery_status_page(organization_id,status,id),
 KEY discovery_site_status(organization_id,site_id,status,id),KEY discovery_mac(organization_id,site_id,mac,id),
 FOREIGN KEY(organization_id,site_id) REFERENCES site(organization_id,id),
 FOREIGN KEY(organization_id,associated_device_id) REFERENCES device(organization_id,id)
);
CREATE TABLE discovery_run (
 organization_id VARCHAR(64) NOT NULL,id CHAR(36) NOT NULL,site_id VARCHAR(64) NOT NULL,cidr VARCHAR(18) NOT NULL,
 result JSON NOT NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,id),KEY discovery_run_site(organization_id,site_id,created_at,id),
 FOREIGN KEY(organization_id,site_id) REFERENCES site(organization_id,id)
);
-- Index the bounded safe evidence, including multiple MACs when the summary MAC is ambiguous.
CREATE TABLE discovery_mac_claim (
 organization_id VARCHAR(64) NOT NULL,candidate_id CHAR(36) NOT NULL,site_id VARCHAR(64) NOT NULL,mac VARCHAR(17) NOT NULL,
 PRIMARY KEY(organization_id,candidate_id,mac),KEY discovery_claim_lookup(organization_id,site_id,mac,candidate_id),
 FOREIGN KEY(organization_id,candidate_id) REFERENCES discovery_candidate(organization_id,id)
);
CREATE INDEX device_management_lookup ON device(organization_id,site_id,management_address,id);
