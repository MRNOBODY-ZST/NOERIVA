package io.noeriva.control;

import io.r2dbc.spi.Row;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import tools.jackson.databind.json.JsonMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import static io.noeriva.control.Models.*;

@Repository @Profile("production")
public class MySqlRepository implements ControlRepository {
    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final JsonMapper json;
    private final HistoryStore history;
    private static final String DEVICE_SELECT="SELECT d.*,s.name site_name,"+CurrentStateSql.HEALTH+" health,"+CurrentStateSql.AVAILABILITY+" availability,c.last_seen,COALESCE(c.revision,d.revision) current_revision FROM device d JOIN site s ON s.organization_id=d.organization_id AND s.id=d.site_id LEFT JOIN device_current c ON c.organization_id=d.organization_id AND c.device_id=d.id "+CurrentStateSql.join("UTC_TIMESTAMP(6)-INTERVAL 180 SECOND");
    public MySqlRepository(DatabaseClient db,TransactionalOperator tx,JsonMapper json,HistoryStore history){this.db=db;this.tx=tx;this.json=json;this.history=history;}
    private String encode(Object value){return json.writeValueAsString(value);}
    private <T>T decode(String value,Class<T> type){return json.readValue(value,type);}
    private static Instant instant(Row r,String key){var v=r.get(key,LocalDateTime.class);return v==null?null:v.toInstant(ZoneOffset.UTC);}
    private Device device(Row r){return new Device(r.get("id",String.class),r.get("name",String.class),r.get("type",String.class),r.get("site_id",String.class),r.get("site_name",String.class),r.get("vendor",String.class),r.get("model",String.class),r.get("management_address",String.class),r.get("health",String.class),r.get("availability",String.class),instant(r,"last_seen"),r.get("current_revision",Long.class),List.of(decode(r.get("capabilities",String.class),String[].class)));}
    private Alert alert(Row r){return new Alert(r.get("id",String.class),r.get("device_id",String.class),r.get("device_name",String.class),r.get("severity",String.class),r.get("state",String.class),r.get("title",String.class),instant(r,"opened_at"),instant(r,"acknowledged_at"),r.get("acknowledged_by",String.class),r.get("revision",Long.class));}
    @Override public Flux<Device> devices(String org,int limit,String cursor,String q,String site,String type,String health) {
        String sql=DEVICE_SELECT+"WHERE d.organization_id=:org AND d.id>:cursor";
        if(!q.isEmpty())sql+=" AND (d.name LIKE :q OR d.management_address LIKE :q)";
        if(!site.isEmpty())sql+=" AND d.site_id=:site";
        if(!type.isEmpty())sql+=" AND d.type=:type";
        if(!health.isEmpty())sql+=" AND "+CurrentStateSql.HEALTH+"=:health";
        var spec=db.sql(sql+" ORDER BY d.id LIMIT :limit").bind("org",org).bind("cursor",cursor).bind("limit",limit);
        if(!q.isEmpty())spec=spec.bind("q",q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
        if(!site.isEmpty())spec=spec.bind("site",site);
        if(!type.isEmpty())spec=spec.bind("type",type);
        if(!health.isEmpty())spec=spec.bind("health",health);
        return spec.map((r,m)->device(r)).all();
    }
    @Override public Mono<Device> device(String org,String id){return db.sql(DEVICE_SELECT+"WHERE d.organization_id=:org AND d.id=:id").bind("org",org).bind("id",id).map((r,m)->device(r)).one();}
    @Override public Flux<Device> deviceBatch(String org,List<String> ids){return ids.isEmpty()?Flux.empty():db.sql(DEVICE_SELECT+"WHERE d.organization_id=:org AND d.id IN (:ids) ORDER BY d.id").bind("org",org).bind("ids",ids).map((r,m)->device(r)).all();}
    @Override public Mono<Device> create(String org,String actor,CreateDevice in) {
        String id=UUID.randomUUID().toString();
        return sites(org).filter(s->s.id().equals(in.siteId())).next().switchIfEmpty(Mono.error(ApiException.missing())).flatMap(site ->
            db.sql("INSERT INTO device(organization_id,id,name,type,site_id,vendor,model,management_address,capabilities) VALUES(:org,:id,:name,:type,:site,:vendor,:model,:address,'[\"summary\"]')")
            .bind("org",org).bind("id",id).bind("name",in.name()).bind("type",in.type()).bind("site",in.siteId()).bind("vendor",Objects.toString(in.vendor(),"")).bind("model",Objects.toString(in.model(),"")).bind("address",in.managementAddress()).fetch().rowsUpdated()
            .then(outbox(org,id,"DeviceCreated",Map.of("id",id,"name",in.name())))
            .then(audit(org,actor,"DEVICE_CREATED",id)).then(device(org,id))).as(tx::transactional);
    }
    @Override public Flux<Site> sites(String org){return db.sql("SELECT id,name,timezone FROM site WHERE organization_id=:org ORDER BY id LIMIT 1000").bind("org",org).map((r,m)->new Site(r.get("id",String.class),r.get("name",String.class),r.get("timezone",String.class))).all();}
    @Override public Flux<SourceState> sources(String org,String device){return db.sql("SELECT sc.payload FROM source_current sc WHERE sc.organization_id=:org AND sc.device_id=:device AND "+CurrentStateSql.activeSource("sc")+" ORDER BY sc.source_id LIMIT 32").bind("org",org).bind("device",device).map((r,m)->ProjectionPolicy.source(decode(r.get("payload",String.class),Observation.class),Instant.now())).all();}
    @Override public Flux<NetworkInterface> interfaces(String org,String device){return db.sql("SELECT payload FROM network_interface WHERE organization_id=:org AND device_id=:device ORDER BY id LIMIT 1000").bind("org",org).bind("device",device).map((r,m)->decode(r.get("payload",String.class),NetworkInterface.class)).all();}
    @Override public Mono<String> interfaceSource(String org,String device,String interfaceId){return db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ source_id FROM network_interface WHERE organization_id=:org AND device_id=:device AND id=:interface LIMIT 1").bind("org",org).bind("device",device).bind("interface",interfaceId).map((r,m)->r.get("source_id",String.class)).one();}
    @Override public Mono<NetworkInterface> registerInterface(String org,String actor,String device,RegisterInterface input){
        var result=new NetworkInterface(UUID.randomUUID().toString(),device,input.name(),input.macAddress(),input.speedBps(),"UNKNOWN","UNKNOWN");
        return device(org,device).switchIfEmpty(Mono.error(ApiException.missing())).then(db.sql("INSERT INTO network_interface(organization_id,id,device_id,payload) VALUES(:org,:id,:device,:payload)").bind("org",org).bind("id",result.id()).bind("device",device).bind("payload",encode(result)).fetch().rowsUpdated())
            .then(db.sql("UPDATE device SET capabilities='[\"summary\",\"metrics\",\"interfaces\",\"heatmap\",\"connections\"]',revision=revision+1 WHERE organization_id=:org AND id=:device").bind("org",org).bind("device",device).fetch().rowsUpdated())
            .then(audit(org,actor,"INTERFACE_REGISTERED",result.id())).then(outbox(org,device,"InterfaceRegistered",result)).thenReturn(result).as(tx::transactional);
    }
    @Override public Flux<Alert> alerts(String org,String device,String state,int limit){
        String sql="SELECT * FROM alert WHERE organization_id=:org"+(!device.isEmpty()?" AND device_id=:device":"")+(!state.isEmpty()?" AND state=:state":"")+" ORDER BY opened_at DESC,id DESC LIMIT :limit";
        var query=db.sql(sql).bind("org",org).bind("limit",limit);if(!device.isEmpty())query=query.bind("device",device);if(!state.isEmpty())query=query.bind("state",state);return query.map((r,m)->alert(r)).all();
    }
    @Override public Mono<Alert> acknowledge(String org,String actor,String id,long revision){
        return db.sql("UPDATE alert SET state='ACKNOWLEDGED',acknowledged_at=UTC_TIMESTAMP(6),acknowledged_by=:actor,revision=revision+1 WHERE organization_id=:org AND id=:id AND revision=:revision AND state='OPEN'")
            .bind("actor",actor).bind("org",org).bind("id",id).bind("revision",revision).fetch().rowsUpdated().flatMap(n->{if(n==0)return Mono.error(ApiException.conflict());
                return audit(org,actor,"ALERT_ACKNOWLEDGED",id).then(outbox(org,id,"AlertAcknowledged",Map.of("id",id,"revision",revision+1)))
                    .then(db.sql("SELECT * FROM alert WHERE organization_id=:org AND id=:id").bind("org",org).bind("id",id).map((r,m)->alert(r)).one());}).as(tx::transactional);
    }
    @Override public Flux<Event> events(String org,String device,Instant from,Instant to,String cursor,int limit){return history.events(org,device,from,to,cursor,limit);}
    @Override public Flux<Collector> collectors(String org){return db.sql("SELECT payload FROM collector WHERE organization_id=:org ORDER BY id LIMIT 1000").bind("org",org).map((r,m)->decode(r.get("payload",String.class),Collector.class)).all();}
    @Override public Flux<Edge> edges(String org,String device,int limit){var sql="SELECT payload FROM topology_edge WHERE organization_id=:org"+(!device.isEmpty()?" AND (source_id=:device OR target_id=:device)":"")+" ORDER BY id LIMIT :limit";var query=db.sql(sql).bind("org",org).bind("limit",limit);if(!device.isEmpty())query=query.bind("device",device);return query.map((r,m)->decode(r.get("payload",String.class),Edge.class)).all();}
    @Override public Mono<Overview> overview(String org){
        return db.sql("SELECT "+CurrentStateSql.counts()+" FROM device d LEFT JOIN device_current c ON c.organization_id=d.organization_id AND c.device_id=d.id "+CurrentStateSql.join("UTC_TIMESTAMP(6)-INTERVAL 180 SECOND")+" WHERE d.organization_id=:org")
            .bind("org",org).map((r,m)->new long[]{number(r,"devices"),number(r,"critical"),number(r,"warning"),number(r,"healthy"),number(r,"unknown"),number(r,"stale")}).one()
            .zipWith(db.sql("SELECT COUNT(*) n FROM alert WHERE organization_id=:org AND state<>'RESOLVED'").bind("org",org).map((r,m)->number(r,"n")).one())
            .zipWith(db.sql("SELECT COUNT(*) n FROM collector WHERE organization_id=:org").bind("org",org).map((r,m)->number(r,"n")).one())
            .map(t->{var a=t.getT1().getT1();return new Overview(a[0],a[1],a[2],a[3],a[4],a[5],t.getT1().getT2(),t.getT2(),Instant.now(),"CONNECTED");});
    }
    private long number(Row row,String key){return ((Number)row.get(key)).longValue();}
    @Override public Mono<Boolean> project(String org,Observation next){
        // Lock one existing device row to serialize source replacement and its device checkpoint.
        return db.sql("SELECT id FROM device WHERE organization_id=:org AND id=:device FOR UPDATE").bind("org",org).bind("device",next.deviceId()).map((r,m)->r.get("id",String.class)).one().switchIfEmpty(Mono.error(ApiException.missing()))
            .flatMap(id->db.sql("SELECT payload FROM source_current WHERE organization_id=:org AND device_id=:device AND source_id=:source").bind("org",org).bind("device",id).bind("source",next.sourceId())
                .map((r,m)->Optional.of(decode(r.get("payload",String.class),Observation.class))).one().defaultIfEmpty(Optional.empty()))
            .flatMap(old->{if(!ProjectionPolicy.accepts(old.orElse(null),next,Instant.now()))return Mono.just(false);
                return db.sql("INSERT INTO source_current(organization_id,device_id,source_id,observed_at,payload) VALUES(:org,:device,:source,:at,:payload) ON DUPLICATE KEY UPDATE observed_at=VALUES(observed_at),payload=VALUES(payload)")
                    .bind("org",org).bind("device",next.deviceId()).bind("source",next.sourceId()).bind("at",LocalDateTime.ofInstant(next.observedAt(),ZoneOffset.UTC)).bind("payload",encode(next)).fetch().rowsUpdated()
                    .then(sources(org,next.deviceId()).collectList()).flatMap(allSources->db.sql("INSERT INTO device_current(organization_id,device_id,health,availability,last_seen,revision) VALUES(:org,:device,:health,'ONLINE',:at,1) ON DUPLICATE KEY UPDATE health=VALUES(health),availability='ONLINE',last_seen=GREATEST(last_seen,VALUES(last_seen)),revision=revision+1")
                        .bind("org",org).bind("device",next.deviceId()).bind("health",ProjectionPolicy.aggregateHealth(allSources)).bind("at",LocalDateTime.ofInstant(next.observedAt(),ZoneOffset.UTC)).fetch().rowsUpdated())
                    .then(next.metrics().isEmpty()?Mono.empty():db.sql("UPDATE device SET capabilities=JSON_ARRAY_APPEND(capabilities,'$','metrics') WHERE organization_id=:org AND id=:device AND NOT JSON_CONTAINS(capabilities,'\"metrics\"')").bind("org",org).bind("device",next.deviceId()).fetch().rowsUpdated().then())
                    .then(openAlert(org,next)).thenReturn(true);
            }).as(tx::transactional);
    }
    private Mono<Void> openAlert(String org,Observation o){
        if(!Set.of("HEALTHY","WARNING","CRITICAL").contains(o.health()))return Mono.empty();
        String id="state-"+o.deviceId()+"-"+o.sourceId();
        if(id.length()>128)id=UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        final String alertId=id;
        if(o.health().equals("HEALTHY"))return db.sql("UPDATE alert SET state='RESOLVED',revision=revision+1 WHERE organization_id=:org AND id=:id AND state<>'RESOLVED'")
            .bind("org",org).bind("id",alertId).fetch().rowsUpdated().then();
        return db.sql("INSERT INTO alert(organization_id,id,device_id,device_name,severity,state,title,opened_at,revision) SELECT :org,:id,id,name,:severity,'OPEN',:title,:at,1 FROM device WHERE organization_id=:org AND id=:device ON DUPLICATE KEY UPDATE acknowledged_at=IF(alert.state='RESOLVED' OR (VALUES(severity)='CRITICAL' AND alert.severity<>'CRITICAL'),NULL,alert.acknowledged_at),acknowledged_by=IF(alert.state='RESOLVED' OR (VALUES(severity)='CRITICAL' AND alert.severity<>'CRITICAL'),NULL,alert.acknowledged_by),opened_at=IF(alert.state='RESOLVED',VALUES(opened_at),alert.opened_at),revision=IF(alert.state='RESOLVED' OR VALUES(severity)<>alert.severity OR VALUES(title)<>alert.title,alert.revision+1,alert.revision),state=IF(alert.state='RESOLVED' OR (VALUES(severity)='CRITICAL' AND alert.severity<>'CRITICAL'),'OPEN',alert.state),title=VALUES(title),severity=VALUES(severity)")
            .bind("org",org).bind("id",id).bind("severity",o.health()).bind("title",alertTitle(o)).bind("at",LocalDateTime.ofInstant(o.observedAt(),ZoneOffset.UTC)).bind("device",o.deviceId()).fetch().rowsUpdated()
            .flatMap(changed->changed>0?outbox(org,alertId,"AlertStateObserved",Map.of("id",alertId,"severity",o.health())):Mono.empty());
    }
    private static String alertTitle(Observation o){
        String reason=Objects.toString(o.message(),"").strip();
        if(reason.isEmpty())reason="来源 "+o.sourceId()+" 报告健康状态 "+o.health();
        String title=o.sourceId()+" / "+o.health()+" · "+reason;
        return title.substring(0,Math.min(title.length(),240));
    }
    Mono<Void> outbox(String org,String id,String kind,Object payload){return db.sql("INSERT INTO outbox(id,organization_id,aggregate_id,kind,payload) VALUES(:event,:org,:id,:kind,:payload)").bind("event",UUID.randomUUID().toString()).bind("org",org).bind("id",id).bind("kind",kind).bind("payload",encode(payload)).fetch().rowsUpdated().then();}
    Mono<Void> audit(String org,String actor,String action,String id){return db.sql("INSERT INTO control_audit(id,organization_id,actor,action,resource_id) VALUES(:event,:org,:actor,:action,:id)").bind("event",UUID.randomUUID().toString()).bind("org",org).bind("actor",actor).bind("action",action).bind("id",id).fetch().rowsUpdated().then();}
}
