package io.noeriva.control;

/** Shared read-time state expressions: historical checkpoints never prove current health. */
final class CurrentStateSql {
    static final String HEALTH="COALESCE(live.health,'UNKNOWN')";
    static final String AVAILABILITY="CASE WHEN live.has_fresh=1 THEN 'ONLINE' WHEN c.last_seen IS NOT NULL AND EXISTS (SELECT 1 FROM device_connection dc WHERE dc.organization_id=d.organization_id AND dc.device_id=d.id AND dc.enabled=1 AND dc.error_code<>'' AND dc.error_code<>'PUBLICATION_FAILED' AND dc.last_attempt_at>COALESCE(dc.last_success_at,'2000-01-01')) THEN 'OFFLINE' ELSE 'UNKNOWN' END";
    private CurrentStateSql() {}
    static String activeSource(String alias) {
        // Only managed publisher observations are governed by a connection's enabled state.
        // Retain external collector sources and legacy observations without a managed connection.
        return "NOT EXISTS (SELECT 1 FROM device_connection dc WHERE dc.organization_id="+alias+".organization_id AND dc.device_id="+alias+".device_id AND dc.enabled=0 AND dc.slot=CASE "+alias+".source_id WHEN 'network' THEN 'snmp' WHEN 'bmc' THEN 'redfish' WHEN 'ssh' THEN 'ssh' ELSE '' END AND LEFT(JSON_UNQUOTE(JSON_EXTRACT("+alias+".payload,'$.id')),5)='poll_')";
    }
    static String join(String cutoff) {
        return " LEFT JOIN (SELECT sc.organization_id,sc.device_id,ELT(MAX(CASE WHEN sc.observed_at>="+cutoff+" THEN CASE JSON_UNQUOTE(JSON_EXTRACT(sc.payload,'$.health')) WHEN 'HEALTHY' THEN 2 WHEN 'WARNING' THEN 3 WHEN 'CRITICAL' THEN 4 ELSE 1 END ELSE 1 END),'UNKNOWN','HEALTHY','WARNING','CRITICAL') health,MAX(sc.observed_at>="+cutoff+") has_fresh FROM source_current sc WHERE sc.organization_id=:org AND "+activeSource("sc")+" GROUP BY sc.organization_id,sc.device_id) live ON live.organization_id=d.organization_id AND live.device_id=d.id ";
    }
    static String counts() {
        return "COUNT(d.id) devices,COALESCE(SUM("+HEALTH+"='CRITICAL'),0) critical,COALESCE(SUM("+HEALTH+"='WARNING'),0) warning,COALESCE(SUM("+HEALTH+"='HEALTHY'),0) healthy,COALESCE(SUM(d.id IS NOT NULL AND "+HEALTH+"='UNKNOWN'),0) unknown,COALESCE(SUM(d.id IS NOT NULL AND live.has_fresh=0),0) stale";
    }
}
