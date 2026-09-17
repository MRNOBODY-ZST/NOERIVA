package io.noeriva.control;

import io.r2dbc.spi.Row;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.WorkbenchModels.*;

/** Only the typed service calls this store. Production never falls back to memory. */
@Repository
public class WorkbenchRepository {
    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final JsonMapper json;
    private final boolean demo;
    private record Stored(String org,String category,String id,String device,String title,String status,long revision,Instant created,Instant updated,String payload) {}
    private record ScopedAudit(String org,Audit value) {}
    private final Map<String,Stored> records=new ConcurrentHashMap<>();
    private final Map<String,CheckResult> results=new ConcurrentHashMap<>();
    private final Map<String,NetworkEvidence> networks=new ConcurrentHashMap<>();
    private final List<ScopedAudit> audits=new ArrayList<>();
    private final Object memoryLock=new Object();

    public WorkbenchRepository(ObjectProvider<DatabaseClient> database,ObjectProvider<TransactionalOperator> transaction,JsonMapper json,Environment env) {
        this.db=database.getIfAvailable();this.tx=transaction.getIfAvailable();this.json=json;
        this.demo=Arrays.asList(env.getActiveProfiles()).contains("demo")||env.getActiveProfiles().length==0&&Arrays.asList(env.getDefaultProfiles()).contains("demo");
        if(!demo&&(db==null||tx==null))throw new IllegalStateException("Workbench requires the connected database");
    }
    String mode(){return demo?"DEMO":"CONNECTED";}
    private static String key(String org,String kind,String id){return org+"/"+kind+"/"+id;}
    private static LocalDateTime local(Instant at){return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
    private static Instant instant(Row row,String field){return row.get(field,LocalDateTime.class).toInstant(ZoneOffset.UTC);}
    private static ApiException capacity(){return new ApiException(HttpStatus.TOO_MANY_REQUESTS,"WORKBENCH_CAPACITY","The bounded workbench query or fixture capacity was exceeded");}
    static String cursor(Instant at,String id){return Base64.getUrlEncoder().withoutPadding().encodeToString((at+"|"+id).getBytes(StandardCharsets.UTF_8));}
    private static boolean follows(Instant at,String id,EventCursor c){return c==null||at.isBefore(c.at())||at.equals(c.at())&&id.compareTo(c.id())<0;}
    private static String like(String q){return q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
    private <T>T read(String payload,Class<T> type){return json.readValue(payload,type);}
    private <T>T read(Row row,Class<T> type){
        T value=read(row.get("payload",String.class),type);
        if(type==Check.class){String latest=row.get("last_result",String.class);if(latest!=null)value=type.cast(withResult((Check)value,read(latest,CheckResult.class)));}
        return value;
    }
    static Check withResult(Check c,CheckResult r){return new Check(c.id(),c.deviceId(),c.name(),c.type(),c.target(),c.intervalSeconds(),c.enabled(),c.archived(),c.provenance(),c.execution(),c.revision(),c.createdBy(),c.createdAt(),c.updatedAt(),r);}
    private <T>T memoryRead(Stored value,Class<T> type){
        if(type==IncidentSummary.class){Incident i=read(value.payload,Incident.class);return type.cast(new IncidentSummary(i.id(),i.title(),i.severity(),i.status(),i.deviceId(),i.alertId(),i.assignee(),i.createdBy(),i.createdAt(),i.updatedAt(),i.revision(),i.notes().size()));}
        T parsed=read(value.payload,type);
        if(type==Check.class){Check c=(Check)parsed;CheckResult last=results.entrySet().stream().filter(e->e.getKey().startsWith(value.org+"/")&&e.getValue().checkId().equals(c.id())&&(c.archived()||e.getValue().definitionRevision()==c.revision())).map(Map.Entry::getValue).max(Comparator.comparing(CheckResult::observedAt).thenComparing(CheckResult::id)).orElse(null);parsed=type.cast(withResult(c,last));}
        return parsed;
    }
    <T>Mono<T> get(String org,String category,String id,Class<T> type){
        if(demo)return Mono.defer(()->Mono.justOrEmpty(records.get(key(org,category,id))).map(v->memoryRead(v,type)));
        return db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ payload,last_result FROM workbench_record WHERE organization_id=:org AND category=:category AND id=:id")
            .bind("org",org).bind("category",category).bind("id",id).map((r,m)->read(r,type)).one();
    }
    <T>Flux<T> list(String org,String category,String device,String status,String q,String cursor,int limit,Class<T> type){
        EventCursor c=EventCursor.decode(cursor);
        if(demo)return Flux.defer(()->Flux.fromStream(records.values().stream().filter(r->r.org.equals(org)&&r.category.equals(category))
            .filter(r->device.isEmpty()||r.device.equals(device)).filter(r->status.isEmpty()||r.status.equals(status))
            .filter(r->q.isEmpty()||r.title.toLowerCase(Locale.ROOT).startsWith(q.toLowerCase(Locale.ROOT))).filter(r->follows(r.created,r.id,c))
            .sorted(Comparator.comparing(Stored::created).thenComparing(Stored::id).reversed()).limit(limit).map(r->memoryRead(r,type))));
        // Project metadata inside MySQL: never transfer/decode every large content or note body on the event loop.
        String projection=type==IncidentSummary.class?"JSON_SET(JSON_REMOVE(payload,'$.notes'),'$.noteCount',JSON_LENGTH(payload->'$.notes')) AS payload":Set.of("EVIDENCE","CONFIGURATION").contains(category)?"JSON_REMOVE(payload,'$.content') AS payload":"payload";
        String sql="SELECT /*+ MAX_EXECUTION_TIME(3000) */ "+projection+",last_result FROM workbench_record WHERE organization_id=:org AND category=:category";
        if(!device.isEmpty())sql+=" AND device_id=:device";if(!status.isEmpty())sql+=" AND status=:status";if(!q.isEmpty())sql+=" AND title LIKE :q";
        if(c!=null)sql+=" AND (created_at<:at OR(created_at=:at AND id<:cursor))";
        var spec=db.sql(sql+" ORDER BY created_at DESC,id DESC LIMIT :limit").bind("org",org).bind("category",category).bind("limit",limit);
        if(!device.isEmpty())spec=spec.bind("device",device);if(!status.isEmpty())spec=spec.bind("status",status);if(!q.isEmpty())spec=spec.bind("q",like(q));
        if(c!=null)spec=spec.bind("at",local(c.at())).bind("cursor",c.id());
        return spec.map((r,m)->read(r,type)).all();
    }
    <T>Mono<T> create(String org,String actor,String category,String id,String device,String title,String status,Instant at,T value){
        return Mono.defer(()->{
            String payload=json.writeValueAsString(value);
            if(demo)return Mono.fromCallable(()->{synchronized(memoryLock){if(records.size()>=10000)throw capacity();
                if(records.putIfAbsent(key(org,category,id),new Stored(org,category,id,device,title,status,1,at,at,payload))!=null)throw ApiException.conflict();
                memoryAudit(org,actor,category+"_CREATED",id);return value;}});
            return db.sql("INSERT INTO workbench_record(organization_id,category,id,device_id,title,status,revision,payload,created_at,updated_at) VALUES(:org,:category,:id,:device,:title,:status,1,:payload,:at,:at)")
                .bind("org",org).bind("category",category).bind("id",id).bind("device",device).bind("title",title).bind("status",status).bind("payload",payload).bind("at",local(at))
                .fetch().rowsUpdated().then(audit(org,actor,category+"_CREATED",id)).thenReturn(value).as(tx::transactional);
        });
    }
    <T>Mono<T> update(String org,String actor,String category,String id,String title,String status,long expected,Instant at,T value){
        return Mono.defer(()->{
            String payload=json.writeValueAsString(value);
            if(demo)return Mono.fromCallable(()->{synchronized(memoryLock){Stored old=records.get(key(org,category,id));if(old==null)throw ApiException.missing();if(old.revision!=expected)throw ApiException.conflict();
                records.put(key(org,category,id),new Stored(org,category,id,old.device,title,status,expected+1,old.created,at,payload));memoryAudit(org,actor,category+"_UPDATED",id);return value;}});
            String clearLatest=category.equals("CHECK")&&!status.equals("ARCHIVED")?",last_result=NULL,last_observed_at=NULL,last_result_id=NULL":"";
            return db.sql("UPDATE workbench_record SET title=:title,status=:status,revision=revision+1,payload=:payload,updated_at=:at"+clearLatest+" WHERE organization_id=:org AND category=:category AND id=:id AND revision=:revision")
                .bind("title",title).bind("status",status).bind("payload",payload).bind("at",local(at)).bind("org",org).bind("category",category).bind("id",id).bind("revision",expected)
                .fetch().rowsUpdated().flatMap(n->n==1?audit(org,actor,category+"_UPDATED",id).thenReturn(value):Mono.error(ApiException.conflict())).as(tx::transactional);
        });
    }
    Mono<Void> assignee(String org,String name){
        if(name==null)return Mono.empty();
        if(demo)return "demo".equals(org)&&Set.of("admin","viewer").contains(name)?Mono.empty():Mono.error(ApiException.missing());
        return db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ username FROM app_user WHERE organization_id=:org AND username=:name AND enabled=1 AND (FIND_IN_SET('ADMIN',roles)>0 OR FIND_IN_SET('OPERATOR',roles)>0 OR FIND_IN_SET('VIEWER',roles)>0)")
            .bind("org",org).bind("name",name).map((r,m)->r.get("username",String.class)).one().switchIfEmpty(Mono.error(ApiException.missing())).then();
    }
    Mono<Void> alert(String org,String id,String device){
        if(id==null)return Mono.empty();
        if(demo){boolean exists="demo".equals(org)&&("alert-temperature".equals(id)&&"bmc-compute-07".equals(device)||"alert-interface".equals(id)&&"edge-sw-02".equals(device));return exists?Mono.empty():Mono.error(ApiException.missing());}
        return db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ id FROM alert WHERE organization_id=:org AND id=:id AND device_id=:device").bind("org",org).bind("id",id).bind("device",device)
            .map((r,m)->r.get("id",String.class)).one().switchIfEmpty(Mono.error(ApiException.missing())).then();
    }
    private void memoryAudit(String org,String actor,String action,String resource){if(audits.size()>=100000)throw capacity();audits.add(new ScopedAudit(org,new Audit(UUID.randomUUID().toString(),actor,action,resource,WorkbenchService.now())));}
    Mono<Void> audit(String org,String actor,String action,String resource){
        if(demo)return Mono.fromRunnable(()->{synchronized(memoryLock){memoryAudit(org,actor,action,resource);}});
        return db.sql("INSERT INTO control_audit(id,organization_id,actor,action,resource_id) VALUES(:id,:org,:actor,:action,:resource)")
            .bind("id",UUID.randomUUID().toString()).bind("org",org).bind("actor",actor).bind("action",action).bind("resource",resource).fetch().rowsUpdated().then();
    }
    Flux<Audit> audits(String org,String resource,Instant from,Instant to,String cursor,int limit){
        EventCursor c=EventCursor.decode(cursor);
        if(demo)return Flux.defer(()->{synchronized(memoryLock){return Flux.fromIterable(audits.stream().filter(a->a.org.equals(org)).map(ScopedAudit::value)
            .filter(a->resource.isEmpty()||a.resourceId().equals(resource)).filter(a->!a.createdAt().isBefore(from)&&a.createdAt().isBefore(to))
            .filter(a->follows(a.createdAt(),a.id(),c)).sorted(Comparator.comparing(Audit::createdAt).thenComparing(Audit::id).reversed()).limit(limit).toList());}});
        String sql="SELECT /*+ MAX_EXECUTION_TIME(3000) */ id,actor,action,resource_id,created_at FROM control_audit WHERE organization_id=:org AND created_at>=:from AND created_at<:to";
        if(!resource.isEmpty())sql+=" AND resource_id=:resource";if(c!=null)sql+=" AND(created_at<:at OR(created_at=:at AND id<:cursor))";
        var spec=db.sql(sql+" ORDER BY created_at DESC,id DESC LIMIT :limit").bind("org",org).bind("from",local(from)).bind("to",local(to)).bind("limit",limit);
        if(!resource.isEmpty())spec=spec.bind("resource",resource);if(c!=null)spec=spec.bind("at",local(c.at())).bind("cursor",c.id());
        return spec.map((r,m)->new Audit(r.get("id",String.class),r.get("actor",String.class),r.get("action",String.class),r.get("resource_id",String.class),instant(r,"created_at"))).all();
    }
    static boolean sameResult(CheckResult a,CheckResult b){return new CheckResult(a.id(),a.checkId(),a.observedAt(),a.status(),a.latencyMs(),a.message(),a.source(),a.provenance(),b.receivedAt(),a.definitionRevision()).equals(b);}
    Mono<CheckResult> putResult(String org,String actor,CheckResult value){
        if(demo)return Mono.fromCallable(()->{synchronized(memoryLock){Stored check=records.get(key(org,"CHECK",value.checkId()));if(check==null)throw ApiException.missing();if(check.status.equals("ARCHIVED")||check.revision!=value.definitionRevision())throw ApiException.conflict();
            CheckResult old=results.get(key(org,"RESULT",value.id()));if(old!=null){if(!sameResult(old,value))throw ApiException.conflict();return old;}
            if(results.size()>=100000)throw capacity();results.put(key(org,"RESULT",value.id()),value);memoryAudit(org,actor,"CHECK_RESULT_REPORTED",value.checkId());return value;}});
        return db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ status,revision FROM workbench_record WHERE organization_id=:org AND category='CHECK' AND id=:id FOR UPDATE").bind("org",org).bind("id",value.checkId())
            .map((r,m)->!r.get("status",String.class).equals("ARCHIVED")&&r.get("revision",Long.class)==value.definitionRevision()).one().switchIfEmpty(Mono.error(ApiException.missing())).flatMap(valid->valid?Mono.just(true):Mono.error(ApiException.conflict()))
            .then(db.sql("INSERT IGNORE INTO workbench_check_result(organization_id,id,check_id,observed_at,received_at,payload) VALUES(:org,:id,:check,:at,:received,:payload)")
                .bind("org",org).bind("id",value.id()).bind("check",value.checkId()).bind("at",local(value.observedAt())).bind("received",local(value.receivedAt())).bind("payload",json.writeValueAsString(value)).fetch().rowsUpdated())
            .then(db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ payload FROM workbench_check_result WHERE organization_id=:org AND id=:id").bind("org",org).bind("id",value.id()).map((r,m)->read(r.get("payload",String.class),CheckResult.class)).one())
            .flatMap(old->{if(!sameResult(old,value))return Mono.error(ApiException.conflict());
                return db.sql("UPDATE workbench_record SET last_result=:payload,last_observed_at=:at,last_result_id=:result WHERE organization_id=:org AND category='CHECK' AND id=:check AND (last_observed_at IS NULL OR last_observed_at<:at OR(last_observed_at=:at AND last_result_id<:result))")
                    .bind("payload",json.writeValueAsString(old)).bind("at",local(old.observedAt())).bind("result",old.id()).bind("org",org).bind("check",old.checkId()).fetch().rowsUpdated()
                    .then(audit(org,actor,"CHECK_RESULT_REPORTED",old.checkId())).thenReturn(old);}).as(tx::transactional);
    }
    Flux<CheckResult> results(String org,String check,String cursor,int limit){
        EventCursor c=EventCursor.decode(cursor);
        if(demo)return Flux.defer(()->Flux.fromStream(results.entrySet().stream().filter(e->e.getKey().startsWith(org+"/")).map(Map.Entry::getValue).filter(r->r.checkId().equals(check))
            .filter(r->follows(r.observedAt(),r.id(),c)).sorted(Comparator.comparing(CheckResult::observedAt).thenComparing(CheckResult::id).reversed()).limit(limit)));
        String sql="SELECT /*+ MAX_EXECUTION_TIME(3000) */ payload FROM workbench_check_result WHERE organization_id=:org AND check_id=:check"+(c==null?"":" AND(observed_at<:at OR(observed_at=:at AND id<:cursor))");
        var spec=db.sql(sql+" ORDER BY observed_at DESC,id DESC LIMIT :limit").bind("org",org).bind("check",check).bind("limit",limit);if(c!=null)spec=spec.bind("at",local(c.at())).bind("cursor",c.id());
        return spec.map((r,m)->read(r.get("payload",String.class),CheckResult.class)).all();
    }
    static boolean sameNetwork(NetworkEvidence a,NetworkEvidence b){return new NetworkEvidence(a.id(),a.deviceId(),a.siteId(),a.kind(),a.privateIp(),a.privatePort(),a.publicIp(),a.publicPort(),a.protocol(),a.validFrom(),a.validTo(),a.lifecycle(),a.clockUncertaintyMs(),a.source(),a.provenance(),b.receivedAt()).equals(b);}
    Mono<NetworkEvidence> putNetwork(String org,String actor,NetworkEvidence value){
        if(demo)return Mono.fromCallable(()->{synchronized(memoryLock){NetworkEvidence old=networks.get(key(org,"NETWORK",value.id()));if(old!=null){if(!sameNetwork(old,value))throw ApiException.conflict();return old;}
            if(networks.size()>=100000)throw capacity();networks.put(key(org,"NETWORK",value.id()),value);memoryAudit(org,actor,"NETWORK_EVIDENCE_REPORTED",value.id());return value;}});
        var spec=db.sql("INSERT IGNORE INTO workbench_network_evidence(organization_id,id,device_id,site_id,kind,private_ip,private_port,public_ip,public_port,protocol,search_from,search_to,received_at,payload) VALUES(:org,:id,:device,:site,:kind,:privateIp,:privatePort,:publicIp,:publicPort,:protocol,:from,:to,:received,:payload)")
            .bind("org",org).bind("id",value.id()).bind("device",value.deviceId()).bind("site",value.siteId()).bind("kind",value.kind()).bind("privateIp",value.privateIp())
            .bind("from",local(value.validFrom().minusMillis(value.clockUncertaintyMs()).minusNanos(1000)))
            .bind("to",local(value.validTo().plusMillis(value.clockUncertaintyMs()).plusNanos(1000)))
            .bind("received",local(value.receivedAt())).bind("payload",json.writeValueAsString(value));
        spec=value.privatePort()==null?spec.bindNull("privatePort",Integer.class):spec.bind("privatePort",value.privatePort());
        spec=value.publicIp()==null?spec.bindNull("publicIp",String.class):spec.bind("publicIp",value.publicIp());
        spec=value.publicPort()==null?spec.bindNull("publicPort",Integer.class):spec.bind("publicPort",value.publicPort());
        spec=value.protocol()==null?spec.bindNull("protocol",String.class):spec.bind("protocol",value.protocol());
        return spec.fetch().rowsUpdated().then(db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ payload FROM workbench_network_evidence WHERE organization_id=:org AND id=:id").bind("org",org).bind("id",value.id())
            .map((r,m)->read(r.get("payload",String.class),NetworkEvidence.class)).one()).flatMap(old->sameNetwork(old,value)?audit(org,actor,"NETWORK_EVIDENCE_REPORTED",old.id()).thenReturn(old):Mono.error(ApiException.conflict())).as(tx::transactional);
    }
    Flux<NetworkEvidence> networks(String org,InvestigationInput q){
        boolean pub=q.direction().equals("PUBLIC_TO_PRIVATE");
        if(demo)return Flux.defer(()->Flux.fromStream(networks.entrySet().stream().filter(e->e.getKey().startsWith(org+"/")).map(Map.Entry::getValue)
            .filter(n->n.kind().equals("NAT")&&q.ip().equals(pub?n.publicIp():n.privateIp())&&Objects.equals(q.port(),pub?n.publicPort():n.privatePort())&&q.protocol().equals(n.protocol()))
            .filter(n->WorkbenchService.possibleAt(n,q.at())).sorted(Comparator.comparing(NetworkEvidence::id)).limit(101)));
        String ip=pub?"public_ip":"private_ip",port=pub?"public_port":"private_port";
        return db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ payload FROM workbench_network_evidence WHERE organization_id=:org AND kind='NAT' AND "+ip+"=:ip AND "+port+"=:port AND protocol=:protocol AND search_from<=:at AND search_to>:at ORDER BY id LIMIT 101")
            .bind("org",org).bind("ip",q.ip()).bind("port",q.port()).bind("protocol",q.protocol()).bind("at",local(q.at()))
            .map((r,m)->read(r.get("payload",String.class),NetworkEvidence.class)).all();
    }
    Flux<NetworkEvidence> leases(String org,List<NetworkEvidence> nats,Instant at){
        if(nats.isEmpty())return Flux.empty();var ips=nats.stream().map(NetworkEvidence::privateIp).distinct().toList();var sites=nats.stream().map(NetworkEvidence::siteId).distinct().toList();
        if(demo)return Flux.defer(()->Flux.fromStream(networks.entrySet().stream().filter(e->e.getKey().startsWith(org+"/")).map(Map.Entry::getValue)
            .filter(n->n.kind().equals("ADDRESS_LEASE")&&sites.contains(n.siteId())&&ips.contains(n.privateIp())&&WorkbenchService.possibleAt(n,at)).sorted(Comparator.comparing(NetworkEvidence::id)).limit(101)));
        return db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ payload FROM workbench_network_evidence WHERE organization_id=:org AND kind='ADDRESS_LEASE' AND site_id IN (:sites) AND private_ip IN (:ips) AND search_from<=:at AND search_to>:at ORDER BY id LIMIT 101")
            .bind("org",org).bind("sites",sites).bind("ips",ips).bind("at",local(at)).map((r,m)->read(r.get("payload",String.class),NetworkEvidence.class)).all();
    }
}
