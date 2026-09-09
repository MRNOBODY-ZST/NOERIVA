package io.noeriva.control;

import io.r2dbc.spi.Row;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static io.noeriva.control.Models.*;
import static io.noeriva.control.WorkspaceModels.*;

@Repository @Profile("production")
class MySqlWorkspaceReadRepository implements WorkspaceReadRepository {
    private final DatabaseClient db;private final JsonMapper json;
    private static final String DEVICE_JOIN=" FROM device d JOIN site s ON s.organization_id=d.organization_id AND s.id=d.site_id LEFT JOIN device_current c ON c.organization_id=d.organization_id AND c.device_id=d.id ";
    private static final String COUNTS=CurrentStateSql.counts();
    MySqlWorkspaceReadRepository(DatabaseClient db,JsonMapper json){this.db=db;this.json=json;}
    @Override public Flux<MonitoringSource> monitoring(String org,int limit,SourceCursor cursor,String q,String site,String device,String freshness,Instant asOf){
        String sql="SELECT /*+ MAX_EXECUTION_TIME(3000) */ d.id device_id,d.name device_name,d.type device_type,d.site_id,s.name site_name,sc.payload FROM source_current sc JOIN device d ON d.organization_id=sc.organization_id AND d.id=sc.device_id JOIN site s ON s.organization_id=d.organization_id AND s.id=d.site_id WHERE sc.organization_id=:org AND (sc.device_id>:afterDevice OR (sc.device_id=:afterDevice AND sc.source_id>:afterSource))";
        sql+=" AND "+CurrentStateSql.activeSource("sc");
        if(!q.isEmpty())sql+=" AND (d.name LIKE :q OR d.management_address LIKE :q OR sc.source_id LIKE :q)";
        if(!site.isEmpty())sql+=" AND d.site_id=:site";if(!device.isEmpty())sql+=" AND d.id=:device";
        if(!freshness.isEmpty())sql+=" AND sc.observed_at"+(freshness.equals("FRESH")?">=":"<")+":staleBefore";
        var spec=db.sql(sql+" ORDER BY sc.device_id,sc.source_id LIMIT :limit").bind("org",org).bind("afterDevice",cursor.deviceId()).bind("afterSource",cursor.sourceId()).bind("limit",limit);
        spec=filters(spec,q,site,device);if(!freshness.isEmpty())spec=spec.bind("staleBefore",time(asOf.minusSeconds(180)));
        return spec.map((r,m)->{var o=json.readValue(r.get("payload",String.class),Observation.class);
            return new MonitoringSource(r.get("device_id",String.class),r.get("device_name",String.class),r.get("device_type",String.class),r.get("site_id",String.class),r.get("site_name",String.class),o.sourceId(),o.kind(),o.health(),o.observedAt(),ProjectionPolicy.freshness(o.observedAt(),asOf),o.metrics(),o.sequence(),o.epoch());}).all();
    }
    @Override public Flux<WorkspaceInterface> interfaces(String org,int limit,String cursor,String q,String site,String device,Instant asOf){
        var after=InterfaceCursor.decode(cursor,InterfaceCursor.scope(org,q,site,device));
        String sql="SELECT /*+ MAX_EXECUTION_TIME(3000) */ n.payload,d.name device_name,d.site_id,s.name site_name,c.last_seen FROM network_interface n FORCE INDEX ("+(device.isEmpty()?"interface_workspace_name":"interface_device_name")+") STRAIGHT_JOIN device d ON d.organization_id=n.organization_id AND d.id=n.device_id STRAIGHT_JOIN site s ON s.organization_id=d.organization_id AND s.id=d.site_id LEFT JOIN device_current c ON c.organization_id=d.organization_id AND c.device_id=d.id WHERE n.organization_id=:org";
        if(!after.id().isEmpty())sql+=" AND (n.name_sort>LOWER(:afterName) COLLATE utf8mb4_bin OR (n.name_sort=LOWER(:afterName) COLLATE utf8mb4_bin AND n.id>:afterId))";
        if(!q.isEmpty())sql+=" AND (d.name LIKE :q OR d.management_address LIKE :q OR JSON_UNQUOTE(JSON_EXTRACT(n.payload,'$.name')) LIKE :q)";
        if(!site.isEmpty())sql+=" AND d.site_id=:site";if(!device.isEmpty())sql+=" AND d.id=:device";
        // Bound the leading table's device key directly so the per-device order
        // index remains usable without sorting an entire joined result.
        if(!device.isEmpty())sql+=" AND n.device_id=:device";
        var spec=filters(db.sql(sql+" ORDER BY n.name_sort,n.id LIMIT :limit").bind("org",org).bind("limit",limit),q,site,device);
        if(!after.id().isEmpty())spec=spec.bind("afterName",after.name()).bind("afterId",after.id());
        return spec
            .map((r,m)->{var i=json.readValue(r.get("payload",String.class),NetworkInterface.class);Instant seen=instant(r,"last_seen");
                return new WorkspaceInterface(i.id(),i.deviceId(),r.get("device_name",String.class),r.get("site_id",String.class),r.get("site_name",String.class),i.name(),i.macAddress(),i.speedBps(),i.adminStatus(),i.operStatus(),seen,ProjectionPolicy.freshness(seen,asOf));}).all();
    }
    @Override public Flux<SiteHealth> siteHealth(String org,String site,Instant asOf){
        String sql="SELECT /*+ MAX_EXECUTION_TIME(3000) */ s.id,s.name,s.timezone,"+COUNTS+" FROM site s LEFT JOIN device d ON d.organization_id=s.organization_id AND d.site_id=s.id LEFT JOIN device_current c ON c.organization_id=d.organization_id AND c.device_id=d.id "+CurrentStateSql.join(":staleBefore")+" WHERE s.organization_id=:org"+(!site.isEmpty()?" AND s.id=:site":"")+" GROUP BY s.id,s.name,s.timezone ORDER BY s.id LIMIT 1000";
        var spec=db.sql(sql).bind("org",org).bind("staleBefore",time(asOf.minusSeconds(180)));if(!site.isEmpty())spec=spec.bind("site",site);
        return spec.map((r,m)->new SiteHealth(r.get("id",String.class),r.get("name",String.class),r.get("timezone",String.class),number(r,"devices"),number(r,"critical"),number(r,"warning"),number(r,"healthy"),number(r,"unknown"),number(r,"stale"))).all();
    }
    @Override public Flux<Device> priorityDevices(String org,String site,int limit){
        String sql="SELECT /*+ MAX_EXECUTION_TIME(3000) */ d.*,s.name site_name,"+CurrentStateSql.HEALTH+" health,"+CurrentStateSql.AVAILABILITY+" availability,c.last_seen,COALESCE(c.revision,d.revision) current_revision"+DEVICE_JOIN+CurrentStateSql.join("UTC_TIMESTAMP(6)-INTERVAL 180 SECOND")+"WHERE d.organization_id=:org AND "+CurrentStateSql.HEALTH+"<>'HEALTHY'"+(!site.isEmpty()?" AND d.site_id=:site":"")+" ORDER BY CASE "+CurrentStateSql.HEALTH+" WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,d.id LIMIT :limit";
        var spec=db.sql(sql).bind("org",org).bind("limit",limit);if(!site.isEmpty())spec=spec.bind("site",site);
        return spec.map((r,m)->new Device(r.get("id",String.class),r.get("name",String.class),r.get("type",String.class),r.get("site_id",String.class),r.get("site_name",String.class),r.get("vendor",String.class),r.get("model",String.class),r.get("management_address",String.class),r.get("health",String.class),r.get("availability",String.class),instant(r,"last_seen"),number(r,"current_revision"),List.of(json.readValue(r.get("capabilities",String.class),String[].class)))).all();
    }
    @Override public Mono<MonitoringSource> trafficSource(String org,String site,Instant asOf){
        String sql="SELECT /*+ MAX_EXECUTION_TIME(3000) */ d.id device_id,d.name device_name,d.type device_type,d.site_id,s.name site_name,sc.payload FROM source_current sc JOIN device d ON d.organization_id=sc.organization_id AND d.id=sc.device_id JOIN site s ON s.organization_id=d.organization_id AND s.id=d.site_id WHERE sc.organization_id=:org AND "+CurrentStateSql.activeSource("sc")+" AND JSON_CONTAINS_PATH(sc.payload,'one','$.metrics.bandwidth_rx_bps','$.metrics.bandwidth_tx_bps')"+(!site.isEmpty()?" AND d.site_id=:site":"")+" ORDER BY sc.observed_at DESC,sc.device_id,sc.source_id LIMIT 1";
        var spec=db.sql(sql).bind("org",org);if(!site.isEmpty())spec=spec.bind("site",site);
        return spec.map((r,m)->{var o=json.readValue(r.get("payload",String.class),Observation.class);
            return new MonitoringSource(r.get("device_id",String.class),r.get("device_name",String.class),r.get("device_type",String.class),r.get("site_id",String.class),r.get("site_name",String.class),o.sourceId(),o.kind(),o.health(),o.observedAt(),ProjectionPolicy.freshness(o.observedAt(),asOf),o.metrics(),o.sequence(),o.epoch());}).one();
    }
    @Override public Mono<Overview> totals(String org,String site,Instant asOf){
        var counts=db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ "+COUNTS+DEVICE_JOIN+CurrentStateSql.join(":staleBefore")+"WHERE d.organization_id=:org"+(!site.isEmpty()?" AND d.site_id=:site":"")).bind("org",org).bind("staleBefore",time(asOf.minusSeconds(180)));
        var alerts=db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ COUNT(*) n FROM alert a JOIN device d ON d.organization_id=a.organization_id AND d.id=a.device_id WHERE a.organization_id=:org AND a.state<>'RESOLVED'"+(!site.isEmpty()?" AND d.site_id=:site":"")).bind("org",org);
        var collectors=db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ COUNT(*) n FROM collector WHERE organization_id=:org"+(!site.isEmpty()?" AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.siteId'))=:site":"")).bind("org",org);
        if(!site.isEmpty()){counts=counts.bind("site",site);alerts=alerts.bind("site",site);collectors=collectors.bind("site",site);}
        return Mono.zip(counts.map((r,m)->new long[]{number(r,"devices"),number(r,"critical"),number(r,"warning"),number(r,"healthy"),number(r,"unknown"),number(r,"stale")}).one(),alerts.map((r,m)->number(r,"n")).one(),collectors.map((r,m)->number(r,"n")).one())
            .map(t->{var n=t.getT1();return new Overview(n[0],n[1],n[2],n[3],n[4],n[5],t.getT2(),t.getT3(),asOf,"CONNECTED");});
    }
    private DatabaseClient.GenericExecuteSpec filters(DatabaseClient.GenericExecuteSpec spec,String q,String site,String device){
        if(!q.isEmpty())spec=spec.bind("q",q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
        if(!site.isEmpty())spec=spec.bind("site",site);if(!device.isEmpty())spec=spec.bind("device",device);return spec;
    }
    private static LocalDateTime time(Instant at){return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
    private static Instant instant(Row r,String key){var value=r.get(key,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private static long number(Row r,String key){return ((Number)r.get(key)).longValue();}
}
