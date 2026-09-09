package io.noeriva.control.discovery;

import io.noeriva.control.ApiException;
import io.noeriva.control.Models.Page;
import io.noeriva.control.SecurityConfiguration.Operator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import static io.noeriva.control.discovery.DiscoveryModels.*;

/** Database-only discovery. Site locks serialize registration without any network side effects. */
@Service
public class DiscoveryService {
    private static final Set<String> STATUSES=Set.of("NEW","EXISTING","LINKED","REGISTERED","POSSIBLE_DUPLICATE","CONFLICT");
    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final JsonMapper json;
    private final Clock clock;
    private final Semaphore admission=new Semaphore(4);
    @Autowired public DiscoveryService(ObjectProvider<DatabaseClient> db,ObjectProvider<TransactionalOperator> tx,JsonMapper json){this(db.getIfAvailable(),tx.getIfAvailable(),json,Clock.systemUTC());}
    DiscoveryService(DatabaseClient db,TransactionalOperator tx,JsonMapper json,Clock clock){this.db=db;this.tx=tx;this.json=json;this.clock=clock;}
    private static String id(){return UUID.randomUUID().toString();}
    private static void readRole(Operator a){if(a==null||a.roles().stream().noneMatch(Set.of("ADMIN","OPERATOR","VIEWER")::contains))throw new ApiException(HttpStatus.FORBIDDEN,"DISCOVERY_READ_FORBIDDEN","This role cannot read discovery evidence");}
    private static void writeRole(Operator a){if(a==null||a.roles().stream().noneMatch(Set.of("ADMIN","OPERATOR")::contains))throw new ApiException(HttpStatus.FORBIDDEN,"DISCOVERY_WRITE_FORBIDDEN","An administrator or operator must review discovery candidates");}
    private static void identifier(String value){if(value==null||value.isBlank()||value.length()>64)throw new IllegalArgumentException("A valid identifier is required");}
    private static void uuid(String value){try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IllegalArgumentException("Invalid candidate cursor or identifier");}}
    private static ApiException capacity(){return new ApiException(HttpStatus.TOO_MANY_REQUESTS,"DISCOVERY_CAPACITY","Discovery capacity is busy or the related evidence exceeds the bounded review window");}
    private <T> Mono<T> bounded(boolean transaction,Supplier<Mono<T>> work){
        return Mono.defer(()->{
            if(db==null||tx==null)return Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DISCOVERY_UNAVAILABLE","Discovery requires the connected platform database"));
            if(!admission.tryAcquire())return Mono.error(capacity());
            Mono<T> task=Mono.defer(work);if(transaction)task=task.as(tx::transactional);
            return task.timeout(Duration.ofSeconds(10),Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DISCOVERY_TIMEOUT","Discovery exceeded its database execution budget"))).doFinally(signal->admission.release());
        });
    }
    private Mono<Void> lockSite(String org,String site){return db.sql("SELECT id FROM site WHERE organization_id=:org AND id=:site FOR UPDATE").bind("org",org).bind("site",site).map((r,m)->r.get("id",String.class)).one().switchIfEmpty(Mono.error(ApiException.missing())).then();}
    public Mono<RunResult> run(Operator a,RunInput in){
        writeRole(a);if(in==null)throw new IllegalArgumentException("A discovery request is required");identifier(in.siteId());
        if(in.sourceDeviceIds()==null||in.sourceDeviceIds().isEmpty()||in.sourceDeviceIds().size()>8||new HashSet<>(in.sourceDeviceIds()).size()!=in.sourceDeviceIds().size())throw new IllegalArgumentException("Choose one through eight distinct source devices");
        in.sourceDeviceIds().forEach(DiscoveryService::identifier);var cidr=DiscoveryEvidence.cidr(in.cidr());
        return bounded(true,()->lockSite(a.organizationId(),in.siteId()).then(Mono.defer(()->{
            Instant now=clock.instant();
            return sources(a.organizationId(),in).flatMap(sources->{
                if(sources.size()!=in.sourceDeviceIds().size())return Mono.error(ApiException.missing());
                var parsed=sources.stream().map(s->DiscoveryEvidence.parse(s,cidr,now)).toList();
                var observations=parsed.stream().flatMap(p->p.evidence().stream()).toList();
                var flags=new LinkedHashSet<String>();parsed.forEach(p->flags.addAll(p.flags()));
                return updateCandidates(a,in.siteId(),observations,now,flags).flatMap(candidates->{
                    var result=new RunResult(id(),in.siteId(),in.cidr(),now,in.sourceDeviceIds().size(),(int)parsed.stream().filter(p->p.result().status().equals("USED")).count(),
                        parsed.stream().mapToInt(DiscoveryEvidence.Parsed::inspected).sum(),candidates.size(),
                        (int)candidates.stream().filter(c->c.status().equals("EXISTING")).count(),(int)candidates.stream().filter(c->c.reasons().contains("MAC_SHARED_BY_ADDRESSES")).count(),
                        (int)candidates.stream().filter(c->c.status().equals("CONFLICT")).count(),parsed.stream().map(DiscoveryEvidence.Parsed::result).toList(),List.copyOf(flags));
                    return db.sql("INSERT INTO discovery_run(organization_id,id,site_id,cidr,result) VALUES(:org,:id,:site,:cidr,:result)").bind("org",a.organizationId()).bind("id",result.id()).bind("site",in.siteId()).bind("cidr",in.cidr()).bind("result",json.writeValueAsString(result)).fetch().rowsUpdated()
                        .then(audit(a,"DISCOVERY_RUN",result.id())).thenReturn(result);
                });
            });
        })));
    }
    private Mono<List<DiscoveryEvidence.Source>> sources(String org,RunInput in){
        // Each of at most eight device primary keys selects one enabled slot before evidence validation.
        // A missing/stale/invalid SNMP snapshot stays visible rather than falling back to saved SSH.
        return db.sql("""
            SELECT /*+ MAX_EXECUTION_TIME(3000) */ d.id,d.name,
              JSON_UNQUOTE(JSON_EXTRACT(c.last_reading,'$.observedAt')) observed_at,
              CASE WHEN JSON_EXTRACT(c.last_reading,'$.facts.neighborObservations') IS NULL THEN NULL
                WHEN OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(c.last_reading,'$.facts.neighborObservations'))) <= 262144
                THEN JSON_UNQUOTE(JSON_EXTRACT(c.last_reading,'$.facts.neighborObservations')) ELSE 'OVERSIZED' END observations,
              CASE WHEN JSON_EXTRACT(c.last_reading,'$.facts.addressObservations') IS NULL THEN NULL
                WHEN OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(c.last_reading,'$.facts.addressObservations'))) <= 262144
                THEN JSON_UNQUOTE(JSON_EXTRACT(c.last_reading,'$.facts.addressObservations')) ELSE 'OVERSIZED' END addresses,
              CASE WHEN JSON_EXTRACT(c.last_reading,'$.facts.dhcpObservations') IS NULL THEN NULL
                WHEN OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(c.last_reading,'$.facts.dhcpObservations'))) <= 262144
                THEN JSON_UNQUOTE(JSON_EXTRACT(c.last_reading,'$.facts.dhcpObservations')) ELSE 'OVERSIZED' END dhcp,
              CASE WHEN OCTET_LENGTH(JSON_EXTRACT(c.last_reading,'$.qualityFlags')) <= 4096
                THEN CAST(JSON_EXTRACT(c.last_reading,'$.qualityFlags') AS CHAR) ELSE '[]' END flags
            FROM device d LEFT JOIN device_connection c ON c.organization_id=d.organization_id AND c.device_id=d.id AND c.enabled=1
              AND c.slot=(SELECT preferred.slot FROM device_connection preferred
                WHERE preferred.organization_id=d.organization_id AND preferred.device_id=d.id AND preferred.enabled=1 AND preferred.slot IN ('snmp','ssh')
                ORDER BY CASE preferred.slot WHEN 'snmp' THEN 0 ELSE 1 END LIMIT 1)
            WHERE d.organization_id=:org AND d.site_id=:site AND d.id IN (:ids) ORDER BY d.id
            """).bind("org",org).bind("site",in.siteId()).bind("ids",in.sourceDeviceIds())
            .map((r,m)->new DiscoveryEvidence.Source(r.get("id",String.class),r.get("name",String.class),r.get("observed_at",String.class),r.get("observations",String.class),r.get("flags",String.class),r.get("addresses",String.class),r.get("dhcp",String.class))).all().collectList();
    }
    record Asset(String id,String address) {}
    private Mono<List<Asset>> assets(String org,String site,Collection<String> addresses,boolean lock){
        return db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ id,management_address FROM device WHERE organization_id=:org AND site_id=:site AND management_address IN (:addresses) ORDER BY id LIMIT 1025"+(lock?" FOR UPDATE":""))
            .bind("org",org).bind("site",site).bind("addresses",addresses).map((r,m)->new Asset(r.get("id",String.class),r.get("management_address",String.class))).all().collectList()
            .map(rows->{if(rows.size()>1024)throw capacity();return rows;});
    }
    private Mono<List<Candidate>> related(String org,String site,Collection<String> addresses,Collection<String> macs){
        String condition="address IN (:addresses)"+(macs.isEmpty()?"":" OR id IN (SELECT candidate_id FROM discovery_mac_claim WHERE organization_id=:org AND site_id=:site AND mac IN (:macs))");
        var q=db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ payload FROM discovery_candidate WHERE organization_id=:org AND site_id=:site AND ("+condition+") ORDER BY id LIMIT 1025").bind("org",org).bind("site",site).bind("addresses",addresses);
        if(!macs.isEmpty())q=q.bind("macs",macs);return q.map((r,m)->json.readValue(r.get("payload",String.class),Candidate.class)).all().collectList().map(rows->{if(rows.size()>1024)throw capacity();return rows;});
    }
    private Mono<List<Candidate>> updateCandidates(Operator a,String site,List<Evidence> incoming,Instant now,Set<String> flags){
        if(incoming.isEmpty())return Mono.just(List.of());
        var addresses=new LinkedHashSet<String>();var macs=new LinkedHashSet<String>();for(var e:incoming){addresses.add(e.address());if(e.mac()!=null)macs.add(e.mac());}
        // Sequential reads share the transaction connection. Both are batched, with an explicit row ceiling.
        return related(a.organizationId(),site,addresses,macs).flatMap(existing->assets(a.organizationId(),site,addresses,false).flatMap(inventory->{
            var old=new LinkedHashMap<String,Candidate>();for(var c:existing)old.put(c.address(),c);
            var grouped=new LinkedHashMap<String,List<Evidence>>();
            for(var c:existing)if(addresses.contains(c.address()))grouped.put(c.address(),new ArrayList<>(c.evidence().stream().filter(e->DiscoveryEvidence.live(e,now)).toList()));
            for(var e:incoming)grouped.computeIfAbsent(e.address(),key->new ArrayList<>()).add(e);
            var byMac=new HashMap<String,Set<String>>();
            for(var c:existing)for(String mac:evidenceMacs(c))byMac.computeIfAbsent(mac,key->new HashSet<>()).add(c.address());
            for(var e:incoming)if(e.mac()!=null)byMac.computeIfAbsent(e.mac(),key->new HashSet<>()).add(e.address());
            var byAddress=new HashMap<String,List<Asset>>();for(var asset:inventory)byAddress.computeIfAbsent(asset.address(),key->new ArrayList<>()).add(asset);
            var updated=new ArrayList<Candidate>();
            for(var entry:grouped.entrySet())updated.add(classify(old.get(entry.getKey()),site,entry.getKey(),entry.getValue(),byAddress.getOrDefault(entry.getKey(),List.of()),byMac,flags));
            // Show both sides of a weak duplicate even when only one address was refreshed in this run.
            for(var c:existing)if(!addresses.contains(c.address())&&evidenceMacs(c).stream().anyMatch(mac->byMac.getOrDefault(mac,Set.of()).size()>1)&&!c.reasons().contains("MAC_SHARED_BY_ADDRESSES")){
                var reasons=new ArrayList<>(c.reasons());reasons.add("MAC_SHARED_BY_ADDRESSES");
                updated.add(new Candidate(c.id(),c.revision()+1,c.address(),c.siteId(),c.name(),c.mac(),c.status().equals("NEW")?"POSSIBLE_DUPLICATE":c.status(),List.copyOf(reasons),c.evidence(),c.associatedDeviceId(),c.firstSeenAt(),c.lastSeenAt()));
            }
            return persist(a.organizationId(),updated).thenReturn(List.copyOf(updated));
        }));
    }
    private static Set<String> evidenceMacs(Candidate c){var macs=new LinkedHashSet<String>();for(var e:c.evidence())if(e.mac()!=null)macs.add(e.mac());return macs;}
    private Candidate classify(Candidate old,String site,String address,List<Evidence> observations,List<Asset> assets,Map<String,Set<String>> byMac,Set<String> flags){
        var unique=new LinkedHashSet<>(observations);var all=new ArrayList<>(unique);all.sort(Comparator.comparing(Evidence::observedAt).reversed());
        var macs=new HashSet<String>();var names=new HashSet<String>();var chassis=new HashMap<String,Set<String>>();
        for(var e:all){if(e.mac()!=null)macs.add(e.mac());if(e.name()!=null)names.add(e.name());if(e.chassisId()!=null)chassis.computeIfAbsent(Objects.toString(e.chassisSubtype(),"UNKNOWN"),k->new HashSet<>()).add(e.chassisId());}
        var reasons=new ArrayList<String>();reasons.add("NEIGHBOR_EVIDENCE_ONLY");boolean conflict=false;
        if(macs.size()>1){reasons.add("ADDRESS_HAS_MULTIPLE_MACS");conflict=true;}
        if(chassis.values().stream().anyMatch(ids->ids.size()>1)){reasons.add("CHASSIS_IDENTITY_CONFLICT");conflict=true;}
        if(assets.size()>1){reasons.add("MULTIPLE_EXISTING_ASSETS");conflict=true;}
        boolean duplicate=macs.stream().anyMatch(mac->byMac.getOrDefault(mac,Set.of()).size()>1);if(duplicate)reasons.add("MAC_SHARED_BY_ADDRESSES");if(names.size()>1)reasons.add("NAME_VARIANTS");
        if(all.size()>16){if(!reasons.contains("EVIDENCE_LIMIT"))reasons.add("EVIDENCE_LIMIT");flags.add("EVIDENCE_LIMIT");}
        String associated=old==null?null:old.associatedDeviceId();String state="NEW";
        if(associated!=null)state=Set.of("REGISTERED","LINKED","EXISTING").contains(old.status())?old.status():"LINKED";
        else if(assets.size()==1){associated=assets.getFirst().id();state="EXISTING";}
        else if(duplicate)state="POSSIBLE_DUPLICATE";
        if(conflict)state="CONFLICT";
        String mac=macs.size()==1?macs.iterator().next():null;String name=all.stream().map(Evidence::name).filter(Objects::nonNull).findFirst().orElse(old==null?null:old.name());
        Instant latest=all.getFirst().observedAt(),first=old==null?all.getLast().observedAt():old.firstSeenAt();if(old!=null&&old.lastSeenAt().isAfter(latest))latest=old.lastSeenAt();
        // Reserve contradictory representatives before filling the recent evidence window. Otherwise a
        // seventeenth contradictory row could disappear and silently permit registration on the next run.
        var retained=new LinkedHashSet<Evidence>();
        if(macs.size()>1){var seen=new HashSet<String>();for(var e:all)if(e.mac()!=null&&seen.add(e.mac())){retained.add(e);if(seen.size()==2)break;}}
        String conflictingSubtype=chassis.entrySet().stream().filter(e->e.getValue().size()>1).map(Map.Entry::getKey).findFirst().orElse(null);
        if(conflictingSubtype!=null){var seen=new HashSet<String>();for(var e:all)if(e.chassisId()!=null&&Objects.toString(e.chassisSubtype(),"UNKNOWN").equals(conflictingSubtype)&&seen.add(e.chassisId())){retained.add(e);if(seen.size()==2)break;}}
        for(var e:all){if(retained.size()==16)break;retained.add(e);}
        var evidence=retained.stream().sorted(Comparator.comparing(Evidence::observedAt).reversed()).toList();
        return new Candidate(old==null?id():old.id(),old==null?1:old.revision()+1,address,site,name,mac,state,List.copyOf(reasons),evidence,associated,first,latest);
    }
    private Mono<Void> persist(String org,List<Candidate> candidates){
        return Flux.fromIterable(candidates).buffer(32).concatMap(batch->{
            var values=new ArrayList<String>();for(int i=0;i<batch.size();i++)values.add("(:org,:id"+i+",:site"+i+",:address"+i+",:mac"+i+",:status"+i+",:revision"+i+",:associated"+i+",:payload"+i+")");
            var q=db.sql("INSERT INTO discovery_candidate(organization_id,id,site_id,address,mac,status,revision,associated_device_id,payload) VALUES "+String.join(",",values)+" ON DUPLICATE KEY UPDATE mac=VALUES(mac),status=VALUES(status),revision=VALUES(revision),associated_device_id=VALUES(associated_device_id),payload=VALUES(payload)").bind("org",org);
            for(int i=0;i<batch.size();i++){Candidate c=batch.get(i);q=q.bind("id"+i,c.id()).bind("site"+i,c.siteId()).bind("address"+i,c.address()).bind("status"+i,c.status()).bind("revision"+i,c.revision()).bind("payload"+i,json.writeValueAsString(c));q=c.mac()==null?q.bindNull("mac"+i,String.class):q.bind("mac"+i,c.mac());q=c.associatedDeviceId()==null?q.bindNull("associated"+i,String.class):q.bind("associated"+i,c.associatedDeviceId());}
            return q.fetch().rowsUpdated().then(indexMacs(org,batch));
        }).then();
    }
    private Mono<Void> indexMacs(String org,List<Candidate> batch){
        var values=new ArrayList<String>();var claims=new ArrayList<List<String>>();
        for(var c:batch)for(String mac:evidenceMacs(c)){int i=claims.size();values.add("(:org,:candidate"+i+",:site"+i+",:mac"+i+")");claims.add(List.of(c.id(),c.siteId(),mac));}
        Mono<Void> delete=db.sql("DELETE FROM discovery_mac_claim WHERE organization_id=:org AND candidate_id IN (:ids)").bind("org",org).bind("ids",batch.stream().map(Candidate::id).toList()).fetch().rowsUpdated().then();
        if(values.isEmpty())return delete;
        var insert=db.sql("INSERT INTO discovery_mac_claim(organization_id,candidate_id,site_id,mac) VALUES "+String.join(",",values)).bind("org",org);
        for(int i=0;i<claims.size();i++)insert=insert.bind("candidate"+i,claims.get(i).get(0)).bind("site"+i,claims.get(i).get(1)).bind("mac"+i,claims.get(i).get(2));
        return delete.then(insert.fetch().rowsUpdated()).then();
    }
    public Mono<Page<Candidate>> candidates(Operator a,String siteId,String status,String cursor,int limit){
        readRole(a);String site=siteId==null?"":siteId,state=status==null?"":status,after=cursor==null?"":cursor;
        if(!site.isEmpty())identifier(site);if(!state.isEmpty()&&!STATUSES.contains(state))throw new IllegalArgumentException("Invalid candidate status");if(!after.isEmpty())uuid(after);if(limit<1||limit>100)throw new IllegalArgumentException("Candidate page limit must be 1 through 100");
        return bounded(false,()->{
            var q=db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ payload FROM discovery_candidate WHERE organization_id=:org"+(site.isEmpty()?"":" AND site_id=:site")+(state.isEmpty()?"":" AND status=:state")+(after.isEmpty()?"":" AND id>:after")+" ORDER BY id LIMIT :limit").bind("org",a.organizationId()).bind("limit",limit+1);
            if(!site.isEmpty())q=q.bind("site",site);if(!state.isEmpty())q=q.bind("state",state);if(!after.isEmpty())q=q.bind("after",after);
            return q.map((r,m)->json.readValue(r.get("payload",String.class),Candidate.class)).all().collectList().map(rows->{var items=rows.stream().limit(limit).toList();return new Page<>(items,rows.size()>limit?items.getLast().id():null,clock.instant(),"DISCOVERY","CONNECTED");});
        });
    }
    private Mono<Candidate> candidate(String org,String id,boolean lock){return db.sql("SELECT payload FROM discovery_candidate WHERE organization_id=:org AND id=:id"+(lock?" FOR UPDATE":"")).bind("org",org).bind("id",id).map((r,m)->json.readValue(r.get("payload",String.class),Candidate.class)).one().switchIfEmpty(Mono.error(ApiException.missing()));}
    private Mono<Candidate> locked(Operator a,String id){return candidate(a.organizationId(),id,false).flatMap(c->lockSite(a.organizationId(),c.siteId()).then(candidate(a.organizationId(),id,true)));}
    private static Candidate associate(Candidate c,String device,String state,String name){return new Candidate(c.id(),c.revision()+1,c.address(),c.siteId(),name==null?c.name():name,c.mac(),state,c.reasons(),c.evidence(),device,c.firstSeenAt(),c.lastSeenAt());}
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"CANDIDATE_CONFLICT","Conflicting evidence requires investigation before registration");}
    public Mono<Candidate> register(Operator a,String id,RegisterInput in){
        writeRole(a);uuid(id);if(in==null||in.revision()<1||in.name()==null||in.name().isBlank()||in.name().length()>120||in.type()==null||!Set.of("HOST","BMC","SWITCH","ROUTER","FIREWALL").contains(in.type()))throw new IllegalArgumentException("A valid revision, name and asset type are required");
        return bounded(true,()->locked(a,id).flatMap(c->{
            if(c.associatedDeviceId()!=null)return Mono.just(c);
            if(c.revision()!=in.revision())return Mono.error(ApiException.conflict());if(c.status().equals("CONFLICT"))return Mono.error(conflict());
            return assets(a.organizationId(),c.siteId(),List.of(c.address()),true).flatMap(existing->{
                if(existing.size()>1)return Mono.error(conflict());
                String device=existing.isEmpty()?id():existing.getFirst().id();Candidate result=associate(c,device,existing.isEmpty()?"REGISTERED":"EXISTING",existing.isEmpty()?in.name().strip():null);
                Mono<Void> create=existing.isEmpty()?createDevice(a,c,device,in):Mono.empty();
                return create.then(persist(a.organizationId(),List.of(result))).then(audit(a,"DISCOVERY_REGISTER",c.id())).thenReturn(result);
            });
        }));
    }
    private Mono<Void> createDevice(Operator a,Candidate c,String device,RegisterInput in){
        return db.sql("INSERT INTO device(organization_id,id,name,type,site_id,management_address,capabilities) VALUES(:org,:id,:name,:type,:site,:address,JSON_ARRAY('summary'))").bind("org",a.organizationId()).bind("id",device).bind("name",in.name().strip()).bind("type",in.type()).bind("site",c.siteId()).bind("address",c.address()).fetch().rowsUpdated()
            .then(db.sql("INSERT INTO outbox(id,organization_id,aggregate_id,kind,payload) VALUES(:event,:org,:id,'DeviceCreated',:payload)").bind("event",id()).bind("org",a.organizationId()).bind("id",device).bind("payload",json.writeValueAsString(Map.of("id",device,"name",in.name().strip()))).fetch().rowsUpdated()).then(audit(a,"DEVICE_CREATED",device));
    }
    public Mono<Candidate> link(Operator a,String id,LinkInput in){
        writeRole(a);uuid(id);if(in==null||in.revision()<1)throw new IllegalArgumentException("A valid revision is required");identifier(in.deviceId());
        return bounded(true,()->locked(a,id).flatMap(c->{
            if(c.associatedDeviceId()!=null){if(c.associatedDeviceId().equals(in.deviceId()))return Mono.just(c);return Mono.error(new ApiException(HttpStatus.CONFLICT,"CANDIDATE_ALREADY_ASSOCIATED","This candidate is already associated with another asset"));}
            if(c.revision()!=in.revision())return Mono.error(ApiException.conflict());
            return db.sql("SELECT id FROM device WHERE organization_id=:org AND site_id=:site AND id=:id").bind("org",a.organizationId()).bind("site",c.siteId()).bind("id",in.deviceId()).map((r,m)->r.get("id",String.class)).one().switchIfEmpty(Mono.error(ApiException.missing())).flatMap(device->{
                Candidate result=associate(c,device,"LINKED",null);return persist(a.organizationId(),List.of(result)).then(audit(a,"DISCOVERY_LINK",c.id())).thenReturn(result);
            });
        }));
    }
    private Mono<Void> audit(Operator a,String action,String resource){return db.sql("INSERT INTO control_audit(id,organization_id,actor,action,resource_id) VALUES(:id,:org,:actor,:action,:resource)").bind("id",id()).bind("org",a.organizationId()).bind("actor",a.username()).bind("action",action).bind("resource",resource).fetch().rowsUpdated().then();}
}
