-- Distributed leases keep manual and worker runs from overlapping across API replicas.
CREATE TABLE check_execution (
 organization_id VARCHAR(64) NOT NULL,
 check_id VARCHAR(64) NOT NULL,
 definition_revision BIGINT NOT NULL DEFAULT 0,
 lease_id VARCHAR(64) NULL,
 lease_until TIMESTAMP(6) NULL,
 next_run_at TIMESTAMP(6) NULL,
 last_attempt_at TIMESTAMP(6) NULL,
 PRIMARY KEY (organization_id,check_id),
 INDEX check_execution_due (next_run_at,lease_until)
);
UPDATE workbench_record
 SET payload=JSON_SET(payload,'$.execution','NATIVE_WORKER')
 WHERE category='CHECK' AND JSON_UNQUOTE(payload->'$.provenance')='MANUAL';
