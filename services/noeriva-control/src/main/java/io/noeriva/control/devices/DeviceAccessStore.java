package io.noeriva.control.devices;

import io.noeriva.control.*;
import io.r2dbc.spi.Row;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.devices.DeviceAccessModels.*;

@Repository
public class DeviceAccessStore {
    private final DatabaseClient db;private final TransactionalOperator tx;private final JsonMapper json;private final ControlRepository inventory;
    public DeviceAccessStore(ObjectProvider<DatabaseClient> db,ObjectProvider<TransactionalOperator> tx,JsonMapper json,ControlRepository inventory){this.db=db.getIfAvailable();this.tx=tx.getIfAvailable();this.json=json;this.inventory=inventory;}
    private DatabaseClient database(){if(db==null)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CONNECTED_STORAGE_REQUIRED","Device access requires the connected database profile");return db;}
    public boolean connected(){return db!=null;}
    static LocalDateTime local(Instant at){return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
    static Instant instant(Row r,String key){LocalDateTime value=r.get(key,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private <T>T read(Row r,String key,Class<T> type){String value=r.get(key,String.class);return value==null?null:json.readValue(value,type);}
    private Stored decode(Row r){return new Stored(r.get("organization_id",String.class),r.get("device_id",String.class),r.get("slot",String.class),r.get("revision",Long.class),Boolean.TRUE.equals(r.get("enabled",Boolean.class)),read(r,"settings",Settings.class),r.get("ciphertext",String.class),r.get("status",String.class),instant(r,"last_attempt_at"),instant(r,"last_success_at"),instant(r,"next_poll_at"),r.get("error_code",String.class),r.get("error_message",String.class),read(r,"last_reading",DeviceProtocol.Reading.class),read(r,"last_published",DeviceProtocol.Reading.class),r.get("lease_token",String.class),instant(r,"lease_until"),r.get("sequence",Long.class),r.get("source_epoch",String.class));}
    public Mono<Models.Device> required(String org,String device){return inventory.device(org,device).switchIfEmpty(Mono.error(ApiException.missing()));}
    public Mono<Management> management(String org,String device){return required(org,device).flatMap(d->db==null?Mono.just(new Management(d,d.revision())):database().sql("SELECT revision FROM device WHERE organization_id=:org AND id=:device").bind("org",org).bind("device",device).map((r,m)->new Management(d,r.get("revision",Long.class))).one());}
    public Mono<Management> update(String org,String actor,String device,Update input){
        return required(org,device).then(inventory.sites(org).filter(s->s.id().equals(input.siteId())).next().switchIfEmpty(Mono.error(ApiException.missing())))
          .then(Mono.defer(()->database().sql("UPDATE device SET name=:name,type=:type,site_id=:site,vendor=:vendor,model=:model,management_address=:host,revision=revision+1 WHERE organization_id=:org AND id=:device AND revision=:revision")
          .bind("name",input.name()).bind("type",input.type()).bind("site",input.siteId()).bind("vendor",Objects.toString(input.vendor(),"")).bind("model",Objects.toString(input.model(),"")).bind("host",input.managementAddress()).bind("org",org).bind("device",device).bind("revision",input.revision()).fetch().rowsUpdated()
          .flatMap(n->n==1?audit(org,actor,"DEVICE_UPDATED",device):Mono.error(ApiException.conflict())).then(management(org,device)).as(tx::transactional)));
    }
    public Flux<Stored> list(String org,String device){if(db==null)return Flux.empty();return database().sql("SELECT * FROM device_connection WHERE organization_id=:org AND device_id=:device ORDER BY slot").bind("org",org).bind("device",device).map((r,m)->decode(r)).all();}
    public Mono<Stored> get(String org,String device,String slot){return list(org,device).filter(v->v.slot().equals(slot)).singleOrEmpty();}
    public Mono<Stored> save(String org,String actor,String device,String slot,long revision,boolean enabled,Settings settings,String ciphertext){
        return Mono.defer(()->{
            String query=revision==0?"INSERT INTO device_connection(organization_id,device_id,slot,revision,enabled,settings,ciphertext,status,next_poll_at,source_epoch) VALUES(:org,:device,:slot,1,:enabled,:settings,:cipher,:status,:now,:epoch)":
            "UPDATE device_connection SET revision=revision+1,enabled=:enabled,settings=:settings,ciphertext=:cipher,status=:status,next_poll_at=:now,error_code='',error_message='',last_reading=NULL,last_published=NULL,last_success_at=NULL,last_attempt_at=NULL,lease_token='',lease_until=NULL,source_epoch=:epoch WHERE organization_id=:org AND device_id=:device AND slot=:slot AND revision=:revision AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))";
            var spec=database().sql(query).bind("org",org).bind("device",device).bind("slot",slot).bind("enabled",enabled).bind("settings",json.writeValueAsString(settings)).bind("cipher",ciphertext).bind("status",enabled?"QUEUED":"NOT_TESTED").bind("now",local(Instant.now())).bind("epoch",UUID.randomUUID().toString());if(revision!=0)spec=spec.bind("revision",revision);
            return spec.fetch().rowsUpdated().flatMap(n->n==1?releaseMetrics(org,device,slot).then(audit(org,actor,"DEVICE_ACCESS_SAVED",device+"/"+slot)):Mono.error(ApiException.conflict())).then(get(org,device,slot)).as(tx::transactional)
                .onErrorMap(org.springframework.dao.DuplicateKeyException.class,e->ApiException.conflict());
        });
    }
    public Mono<Stored> state(String org,String actor,String device,String slot,State input){
        return Mono.defer(()->database().sql("UPDATE device_connection SET revision=revision+1,enabled=:enabled,status=:status,next_poll_at=UTC_TIMESTAMP(6),lease_token='',lease_until=NULL WHERE organization_id=:org AND device_id=:device AND slot=:slot AND revision=:revision AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))")
            .bind("org",org).bind("device",device).bind("slot",slot).bind("enabled",input.enabled()).bind("status",input.enabled()?"QUEUED":"DISABLED").bind("revision",input.revision()).fetch().rowsUpdated()
            .flatMap(n->n==1?(input.enabled()?Mono.<Void>empty():releaseMetrics(org,device,slot)).then(audit(org,actor,input.enabled()?"DEVICE_POLLING_ENABLED":"DEVICE_POLLING_DISABLED",device+"/"+slot)):Mono.error(ApiException.conflict())).then(get(org,device,slot)).as(tx::transactional));
    }
    public Flux<Stored> due(String org,int limit){return database().sql("SELECT * FROM device_connection WHERE organization_id=:org AND enabled=1 AND next_poll_at<=UTC_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) ORDER BY next_poll_at LIMIT :limit").bind("org",org).bind("limit",limit).map((r,m)->decode(r)).all();}
    public Mono<Stored> acquire(Stored expected,boolean scheduled){
        String token=UUID.randomUUID().toString();
        return database().sql("UPDATE device_connection SET status='RUNNING',last_attempt_at=UTC_TIMESTAMP(6),lease_token=:token,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 90 SECOND),sequence=sequence+1 WHERE organization_id=:org AND device_id=:device AND slot=:slot AND revision=:revision AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))"+(scheduled?" AND enabled=1 AND next_poll_at<=UTC_TIMESTAMP(6)":""))
            .bind("token",token).bind("org",expected.org()).bind("device",expected.device()).bind("slot",expected.slot()).bind("revision",expected.revision()).fetch().rowsUpdated()
            .flatMap(n->n==1?get(expected.org(),expected.device(),expected.slot()).filter(v->token.equals(v.lease())).switchIfEmpty(Mono.error(ApiException.conflict())):Mono.error(new ApiException(HttpStatus.CONFLICT,"DEVICE_BUSY_OR_CHANGED","The connection changed or a read is already in progress")));
    }
    public Mono<Void> assertLease(Stored v){return database().sql("SELECT lease_token FROM device_connection WHERE organization_id=:org AND device_id=:device AND slot=:slot AND revision=:revision AND lease_token=:token AND lease_until>UTC_TIMESTAMP(6)")
        .bind("org",v.org()).bind("device",v.device()).bind("slot",v.slot()).bind("revision",v.revision()).bind("token",v.lease()).map((r,m)->true).one().switchIfEmpty(Mono.error(ApiException.conflict())).then();}
    public Mono<Stored> finish(Stored v,DeviceProtocol.Reading reading,boolean published,String errorCode,String errorMessage){
        String success=reading==null?"":",last_reading=:reading,last_success_at=UTC_TIMESTAMP(6)"+(published?",last_published=:reading":"");
        var spec=database().sql("UPDATE device_connection SET status=:status,error_code=:code,error_message=:message,next_poll_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL :seconds SECOND),lease_token='',lease_until=NULL"+success+" WHERE organization_id=:org AND device_id=:device AND slot=:slot AND revision=:revision AND lease_token=:token AND lease_until>UTC_TIMESTAMP(6)")
            .bind("status",reading==null?"ERROR":reading.qualityFlags().isEmpty()?"SUCCESS":"PARTIAL").bind("code",errorCode).bind("message",errorMessage).bind("seconds",v.settings().intervalSeconds()).bind("org",v.org()).bind("device",v.device()).bind("slot",v.slot()).bind("revision",v.revision()).bind("token",v.lease());
        if(reading!=null)spec=spec.bind("reading",json.writeValueAsString(reading));
        return spec.fetch().rowsUpdated().flatMap(n->n==1?get(v.org(),v.device(),v.slot()):Mono.error(ApiException.conflict()));
    }
    public Mono<Void> audit(String org,String actor,String action,String resource){return database().sql("INSERT INTO control_audit(organization_id,id,actor,action,resource_id,created_at) VALUES(:org,:id,:actor,:action,:resource,UTC_TIMESTAMP(6))").bind("org",org).bind("id",UUID.randomUUID().toString()).bind("actor",actor).bind("action",action).bind("resource",resource).fetch().rowsUpdated().then();}
    public Mono<Void> ports(Stored v,DeviceProtocol.Reading reading){
        Mono<Void> upserts=Flux.fromIterable(reading.ports()).concatMap(p->{String id=portId(v,p.key());var value=new Models.NetworkInterface(id,v.device(),p.name(),p.macAddress(),p.speedBps(),p.adminStatus(),p.operStatus());
            return database().sql("INSERT INTO network_interface(organization_id,id,device_id,payload,source_id) VALUES(:org,:id,:device,:payload,:source) ON DUPLICATE KEY UPDATE payload=VALUES(payload),source_id=VALUES(source_id)").bind("org",v.org()).bind("id",id).bind("device",v.device()).bind("payload",json.writeValueAsString(value)).bind("source",source(v)).fetch().rowsUpdated();},1).then();
        boolean complete=!reading.ports().isEmpty()&&reading.capabilities().contains("interface-counters")&&reading.qualityFlags().isEmpty();
        Mono<Void> retire=complete?database().sql("UPDATE network_interface SET payload=JSON_SET(payload,'$.operStatus','NOT_PRESENT') WHERE organization_id=:org AND device_id=:device AND source_id=:source AND id NOT IN (:ids)")
            .bind("org",v.org()).bind("device",v.device()).bind("source",source(v)).bind("ids",reading.ports().stream().map(p->portId(v,p.key())).toList()).fetch().rowsUpdated().then():Mono.empty();
        return upserts.then(retire).as(tx::transactional);
    }
    public Mono<Void> heartbeat(String org,Instant lastSuccess){
        var value=new Models.Collector("device-poller","设备协议采集 Worker",null,"ONLINE",Instant.now(),lastSuccess,0,0,null,"0.1.0",List.of("SNMP","REDFISH","SSH","READ_ONLY","DATABASE_LEASES"),"CONNECTED");
        return database().sql("INSERT INTO collector(organization_id,id,payload) VALUES(:org,'device-poller',:payload) ON DUPLICATE KEY UPDATE payload=VALUES(payload)").bind("org",org).bind("payload",json.writeValueAsString(value)).fetch().rowsUpdated().then();
    }
    private Mono<Void> releaseMetrics(String org,String device,String slot){return database().sql("DELETE FROM device_metric_binding WHERE organization_id=:org AND device_id=:device AND slot=:slot").bind("org",org).bind("device",device).bind("slot",slot).fetch().rowsUpdated().then();}
    public Mono<Set<String>> metricOwnership(Stored v,Set<String> metrics){
        return Flux.fromIterable(metrics).concatMap(metric->database().sql("INSERT IGNORE INTO device_metric_binding(organization_id,device_id,metric,slot) VALUES(:org,:device,:metric,:slot)").bind("org",v.org()).bind("device",v.device()).bind("metric",metric).bind("slot",v.slot()).fetch().rowsUpdated(),1).thenMany(database().sql("SELECT metric FROM device_metric_binding WHERE organization_id=:org AND device_id=:device AND slot=:slot").bind("org",v.org()).bind("device",v.device()).bind("slot",v.slot()).map((r,m)->r.get("metric",String.class)).all()).collect(java.util.stream.Collectors.toSet());
    }
    public Mono<Void> observedCapabilities(Stored v,DeviceProtocol.Reading reading){
        var caps=new ArrayList<String>();if(!reading.metrics().isEmpty())caps.add("metrics");if(!reading.ports().isEmpty())caps.add("interfaces");if(reading.ports().stream().anyMatch(p->p.discontinuity()!=null&&(p.inOctets()!=null||p.outOctets()!=null)))caps.add("heatmap");
        return Flux.fromIterable(caps).concatMap(cap->database().sql("UPDATE device SET capabilities=JSON_ARRAY_APPEND(capabilities,'$',:cap) WHERE organization_id=:org AND id=:device AND NOT JSON_CONTAINS(capabilities,JSON_QUOTE(:cap))").bind("cap",cap).bind("org",v.org()).bind("device",v.device()).fetch().rowsUpdated(),1).then();
    }
    public static String source(Stored v){return switch(v.slot()){case "snmp"->"network";case "redfish"->"bmc";case "ssh"->"ssh";default->throw new IllegalArgumentException("Unknown protocol slot");};}
    public static String portId(Stored v,String key){return UUID.nameUUIDFromBytes((v.scope()+"/"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
}
