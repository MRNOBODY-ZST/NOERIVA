package io.noeriva.control;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import static io.noeriva.control.WorkbenchModels.*;
import static io.noeriva.control.SecurityConfiguration.Operator;

@Service
public class WorkbenchService {
    private final WorkbenchRepository store;
    private final ControlRepository inventory;
    private final Scheduler diffScheduler=Schedulers.newBoundedElastic(2,8,"configuration-diff");
    private static final Pattern CREDENTIAL=Pattern.compile("(?i)(?:(?:\\b|_)(?:password|passwd|secret|community|token|api[_-]?key|authorization|credential|auth(?:entication)?[_-]?key|privacy[_-]?key|private[_-]?key|secret[_-]?access[_-]?key|access[_-]?key|key[_-]?string|pre-shared-key|psk|[rw]ocommunity)(?:\\b|_)|snmp-server\\s+user|(?:radius|tacacs)[^\\n]*\\bkey\\b|[a-z][a-z0-9+.-]*://[^\\s/:]+:[^\\s/@]+@)");
    public WorkbenchService(WorkbenchRepository store,ControlRepository inventory){this.store=store;this.inventory=inventory;}
    static Instant now(){return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    private static String id(){return UUID.randomUUID().toString();}
    private static String optional(String value){return value==null||value.isBlank()?null:value.strip();}
    private static String note(String value){return value==null?"":value.strip();}
    static void admin(Operator actor){if(!actor.roles().contains("ADMIN"))throw new ApiException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","An administrator must import evidence or configuration");}
    static void reporter(Operator actor){if(actor.roles().stream().noneMatch(r->r.equals("ADMIN")||r.equals("COLLECTOR")))throw new ApiException(HttpStatus.FORBIDDEN,"REPORTER_REQUIRED","A collector or administrator must report observations");}
    private Mono<Models.Device> device(Operator actor,String id){return inventory.device(actor.organizationId(),id).switchIfEmpty(Mono.error(ApiException.missing()));}
    private <T>Mono<T> required(Operator actor,String category,String id,Class<T> type){return store.get(actor.organizationId(),category,id,type).switchIfEmpty(Mono.error(ApiException.missing()));}
    static void observed(Instant at){if(at==null||at.isBefore(Instant.parse("2000-01-01T00:00:00Z"))||at.isAfter(now().plusSeconds(120)))throw new IllegalArgumentException("Observation time is outside the accepted range");}
    private static void bytes(String content,int maximum){if(content.getBytes(StandardCharsets.UTF_8).length>maximum)throw new IllegalArgumentException("Content exceeds its UTF-8 byte limit");}
    static String sha256(String content){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static <T> Models.Page<T> page(List<T> rows,int limit,Function<T,Instant> at,Function<T,String> identifier,String mode){
        List<T> items=rows.stream().limit(limit).toList();String cursor=rows.size()>limit?WorkbenchRepository.cursor(at.apply(items.getLast()),identifier.apply(items.getLast())):null;
        return new Models.Page<>(items,cursor,now(),"CONTROL",mode);
    }
    public Mono<Models.Page<IncidentSummary>> incidents(Operator a,String device,String status,String q,String cursor,int limit){
        if(!Set.of("","OPEN","INVESTIGATING","RESOLVED").contains(status))throw new IllegalArgumentException("Invalid incident status");
        return store.list(a.organizationId(),"INCIDENT",device,status,q,cursor,limit+1,IncidentSummary.class).collectList().map(rows->page(rows,limit,IncidentSummary::createdAt,IncidentSummary::id,store.mode()));
    }
    public Mono<Incident> incident(Operator a,String id){return required(a,"INCIDENT",id,Incident.class);}
    public Mono<Incident> createIncident(Operator a,IncidentInput in){
        Instant at=now();String owner=optional(in.assignee()),alert=optional(in.alertId());String text=note(in.note());
        var value=new Incident(id(),in.title().strip(),in.severity(),"OPEN",in.deviceId(),alert,owner,a.username(),at,at,1,text.isEmpty()?List.of():List.of(new Note(id(),a.username(),text,at)));
        return device(a,in.deviceId()).then(store.alert(a.organizationId(),alert,in.deviceId())).then(store.assignee(a.organizationId(),owner))
            .then(store.create(a.organizationId(),a.username(),"INCIDENT",value.id(),value.deviceId(),value.title(),value.status(),at,value));
    }
    public Mono<Incident> updateIncident(Operator a,String id,IncidentUpdate in){
        return incident(a,id).flatMap(old->{
            if(old.revision()!=in.revision())throw ApiException.conflict();String text=note(in.note());String owner=optional(in.assignee());
            if(in.status().equals("RESOLVED")&&text.isEmpty())throw new IllegalArgumentException("Resolving an incident requires a resolution note");
            if(!text.isEmpty()&&old.notes().size()>=100)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"NOTE_CAPACITY","This incident has reached its note limit");
            Instant at=now();var notes=new ArrayList<>(old.notes());if(!text.isEmpty())notes.add(new Note(id(),a.username(),text,at));
            var value=new Incident(old.id(),in.title().strip(),old.severity(),in.status(),old.deviceId(),old.alertId(),owner,old.createdBy(),old.createdAt(),at,old.revision()+1,List.copyOf(notes));
            return store.assignee(a.organizationId(),owner).then(store.update(a.organizationId(),a.username(),"INCIDENT",id,value.title(),value.status(),in.revision(),at,value));
        });
    }
    public Mono<Evidence> createEvidence(Operator a,EvidenceInput in){
        admin(a);observed(in.observedAt());bytes(in.content(),65536);Instant at=now();
        var value=new Evidence(id(),in.deviceId(),in.title().strip(),in.kind(),in.source().strip(),in.observedAt(),in.content(),in.provenance(),sha256(in.content()),"UNSIGNED",a.username(),at);
        return device(a,in.deviceId()).then(store.create(a.organizationId(),a.username(),"EVIDENCE",value.id(),value.deviceId(),value.title(),"UNSIGNED",at,value));
    }
    public Mono<Models.Page<EvidenceMetadata>> evidence(Operator a,String device,String q,String cursor,int limit){
        return store.list(a.organizationId(),"EVIDENCE",device,"",q,cursor,limit+1,Evidence.class)
            .map(e->new EvidenceMetadata(e.id(),e.deviceId(),e.title(),e.kind(),e.source(),e.observedAt(),e.provenance(),e.sha256(),e.integrity(),e.createdBy(),e.createdAt()))
            .collectList().map(rows->page(rows,limit,EvidenceMetadata::createdAt,EvidenceMetadata::id,store.mode()));
    }
    public Mono<Evidence> evidence(Operator a,String id,boolean export){return required(a,"EVIDENCE",id,Evidence.class)
        .flatMap(value->{
            if(!sha256(value.content()).equals(value.sha256()))return store.audit(a.organizationId(),a.username(),"EVIDENCE_INTEGRITY_FAILED",id)
                .then(Mono.error(new ApiException(HttpStatus.CONFLICT,"EVIDENCE_INTEGRITY_FAILURE","Evidence content no longer matches its recorded checksum")));
            return store.audit(a.organizationId(),a.username(),export?"EVIDENCE_EXPORTED":"EVIDENCE_READ",id).thenReturn(value);
        });}
    public Mono<Manifest> manifest(Operator a,String id){return evidence(a,id,true).map(e->new Manifest(1,"SHA-256",e.sha256(),"UNSIGNED",false,e));}
    record Redacted(String content,int count) {}
    static Redacted redact(String content){
        String[] lines=content.replace("\r\n","\n").replace('\r','\n').split("\n",-1);
        if(lines.length>2000)throw new IllegalArgumentException("Configuration exceeds 2000 lines");
        boolean keyBlock=false;int count=0;
        for(int i=0;i<lines.length;i++){
            String line=lines[i];boolean begin=line.matches(".*-----BEGIN (?:[A-Z ]*PRIVATE KEY)-----.*");boolean end=line.matches(".*-----END (?:[A-Z ]*PRIVATE KEY)-----.*");
            if(begin)keyBlock=true;
            if(keyBlock||CREDENTIAL.matcher(line).find()){lines[i]="[REDACTED]";count++;}
            if(end)keyBlock=false;
        }
        return new Redacted(String.join("\n",lines),count);
    }
    public Mono<Snapshot> createSnapshot(Operator a,ConfigurationInput in){
        admin(a);observed(in.capturedAt());bytes(in.content(),131072);Redacted redacted=redact(in.content());Instant at=now();
        var value=new Snapshot(id(),in.deviceId(),in.title().strip(),in.source().strip(),in.capturedAt(),redacted.content(),in.provenance(),sha256(redacted.content()),redacted.count(),a.username(),at);
        return device(a,in.deviceId()).then(store.create(a.organizationId(),a.username(),"CONFIGURATION",value.id(),value.deviceId(),value.title(),"READ_ONLY",at,value));
    }
    public Mono<Models.Page<SnapshotMetadata>> snapshots(Operator a,String device,String q,String cursor,int limit){
        return store.list(a.organizationId(),"CONFIGURATION",device,"",q,cursor,limit+1,Snapshot.class)
            .map(s->new SnapshotMetadata(s.id(),s.deviceId(),s.title(),s.source(),s.capturedAt(),s.provenance(),s.sha256(),s.redactedLines(),s.createdBy(),s.createdAt()))
            .collectList().map(rows->page(rows,limit,SnapshotMetadata::createdAt,SnapshotMetadata::id,store.mode()));
    }
    private Mono<Snapshot> verifiedSnapshot(Operator a,String id){return required(a,"CONFIGURATION",id,Snapshot.class).flatMap(value->{
        if(!sha256(value.content()).equals(value.sha256()))return store.audit(a.organizationId(),a.username(),"CONFIGURATION_INTEGRITY_FAILED",id)
            .then(Mono.error(new ApiException(HttpStatus.CONFLICT,"CONFIGURATION_INTEGRITY_FAILURE","Configuration content no longer matches its recorded checksum")));
        return Mono.just(value);
    });}
    public Mono<Snapshot> snapshot(Operator a,String id){return verifiedSnapshot(a,id).flatMap(s->store.audit(a.organizationId(),a.username(),"CONFIGURATION_READ",id).thenReturn(s));}
    public Mono<Diff> diff(Operator a,String before,String after){
        return Mono.zip(verifiedSnapshot(a,before),verifiedSnapshot(a,after)).publishOn(diffScheduler)
            .map(pair->lineDiff(pair.getT1(),pair.getT2()))
            .flatMap(value->store.audit(a.organizationId(),a.username(),"CONFIGURATION_DIFF_READ",before).then(store.audit(a.organizationId(),a.username(),"CONFIGURATION_DIFF_READ",after)).thenReturn(value));
    }
    static Diff lineDiff(Snapshot before,Snapshot after){
        if(!before.deviceId().equals(after.deviceId()))throw new IllegalArgumentException("Snapshots belong to different devices");
        String[] a=before.content().split("\n",-1),b=after.content().split("\n",-1);if(a.length>2000||b.length>2000)throw new IllegalArgumentException("Diff is too large");
        short[][] lcs=new short[a.length+1][b.length+1];
        for(int i=a.length-1;i>=0;i--)for(int j=b.length-1;j>=0;j--)lcs[i][j]=(short)(a[i].equals(b[j])?1+lcs[i+1][j+1]:Math.max(lcs[i+1][j],lcs[i][j+1]));
        var lines=new ArrayList<DiffLine>();int i=0,j=0,added=0,removed=0,unchanged=0;
        while(i<a.length||j<b.length){
            if(i<a.length&&j<b.length&&a[i].equals(b[j])){lines.add(new DiffLine("CONTEXT",i+1,j+1,a[i]));i++;j++;unchanged++;}
            else if(j<b.length&&(i==a.length||lcs[i][j+1]>lcs[i+1][j])){lines.add(new DiffLine("ADDED",null,j+1,b[j++]));added++;}
            else{lines.add(new DiffLine("REMOVED",i+1,null,a[i++]));removed++;}
        }
        return new Diff(before.deviceId(),before.id(),after.id(),added,removed,unchanged,List.copyOf(lines),now());
    }
    private static void target(String target){if(target.chars().anyMatch(c->Character.isISOControl(c)||Character.isWhitespace(c)))throw new IllegalArgumentException("Check targets may not contain whitespace or control characters");}
    public Mono<Check> createCheck(Operator a,CheckInput in){
        target(in.target());Instant at=now();var value=new Check(id(),in.deviceId(),in.name().strip(),in.type(),in.target(),in.intervalSeconds(),in.enabled(),false,in.provenance(),in.provenance().equals("MANUAL")?"NATIVE_WORKER":"COLLECTOR_REPORTED",1,a.username(),at,at,null);
        return device(a,in.deviceId()).then(store.create(a.organizationId(),a.username(),"CHECK",value.id(),value.deviceId(),value.name(),value.enabled()?"ENABLED":"DISABLED",at,value));
    }
    public Mono<Models.Page<Check>> checks(Operator a,String device,String q,String cursor,int limit){return store.list(a.organizationId(),"CHECK",device,"",q,cursor,limit+1,Check.class).collectList().map(rows->page(rows,limit,Check::createdAt,Check::id,store.mode()));}
    public Mono<Check> check(Operator a,String id){return required(a,"CHECK",id,Check.class);}
    public Mono<Check> updateCheck(Operator a,String id,CheckUpdate in){
        target(in.target());return check(a,id).flatMap(old->{if(old.archived()||old.revision()!=in.revision())throw ApiException.conflict();Instant at=now();
            var value=new Check(old.id(),old.deviceId(),in.name().strip(),old.type(),in.target(),in.intervalSeconds(),in.enabled(),false,old.provenance(),old.execution(),old.revision()+1,old.createdBy(),old.createdAt(),at,null);
            return store.update(a.organizationId(),a.username(),"CHECK",id,value.name(),value.enabled()?"ENABLED":"DISABLED",in.revision(),at,value).then(check(a,id));});
    }
    public Mono<Check> archiveCheck(Operator a,String id,Revision in){return check(a,id).flatMap(old->{if(old.archived()||old.revision()!=in.revision())throw ApiException.conflict();Instant at=now();
        var value=new Check(old.id(),old.deviceId(),old.name(),old.type(),old.target(),old.intervalSeconds(),false,true,old.provenance(),old.execution(),old.revision()+1,old.createdBy(),old.createdAt(),at,old.lastResult());
        return store.update(a.organizationId(),a.username(),"CHECK",id,value.name(),"ARCHIVED",in.revision(),at,value).then(check(a,id));});}
    public Mono<CheckResult> reportResult(Operator a,CheckResultInput in){
        reporter(a);observed(in.observedAt());if(in.latencyMs()!=null&&(!Double.isFinite(in.latencyMs())||in.latencyMs()<0||in.latencyMs()>600000)||in.status().equals("PASS")&&in.latencyMs()==null)throw new IllegalArgumentException("Invalid check latency");
        var value=new CheckResult(in.id(),in.checkId(),in.observedAt().truncatedTo(ChronoUnit.MICROS),in.status(),in.latencyMs(),note(in.message()),in.source().strip(),in.provenance(),now(),in.definitionRevision()==null?1:in.definitionRevision());
        return check(a,in.checkId()).flatMap(c->{if(c.archived()||!c.provenance().equals(in.provenance())||c.revision()!=value.definitionRevision())throw ApiException.conflict();return store.putResult(a.organizationId(),a.username(),value);});
    }
    public Mono<Models.Page<CheckResult>> results(Operator a,String id,String cursor,int limit){return check(a,id).thenMany(store.results(a.organizationId(),id,cursor,limit+1)).collectList().map(rows->page(rows,limit,CheckResult::observedAt,CheckResult::id,store.mode()));}
    static String ip(String value){
        if(value==null||value.length()>45||!value.matches("[0-9A-Fa-f:.]+"))throw new IllegalArgumentException("An IP literal is required");
        try{return InetAddress.ofLiteral(value).getHostAddress();}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid IP literal");}
    }
    private static void port(Integer port){if(port==null||port<1||port>65535)throw new IllegalArgumentException("Invalid port");}
    public Mono<NetworkEvidence> reportNetwork(Operator a,NetworkInput in){
        reporter(a);observed(in.validFrom());
        if(!in.validFrom().isBefore(in.validTo())||Duration.between(in.validFrom(),in.validTo()).compareTo(Duration.ofDays(31))>0||in.validTo().isAfter(now().plus(Duration.ofDays(31))))throw new IllegalArgumentException("Invalid evidence interval");
        String privateIp=ip(in.privateIp()),publicIp=null;
        if(in.kind().equals("NAT")){publicIp=ip(in.publicIp());port(in.privatePort());port(in.publicPort());if(!Set.of("TCP","UDP").contains(Objects.toString(in.protocol(),"")))throw new IllegalArgumentException("NAT protocol required");}
        else if(in.publicIp()!=null||in.publicPort()!=null||in.privatePort()!=null||in.protocol()!=null)throw new IllegalArgumentException("Lease records cannot contain NAT fields");
        String normalizedPublic=publicIp;
        return device(a,in.deviceId()).flatMap(device->{
            // Preserve the registered scope at import; later inventory moves cannot rewrite historical evidence.
            var value=new NetworkEvidence(in.id(),in.deviceId(),device.siteId(),in.kind(),privateIp,in.privatePort(),normalizedPublic,in.publicPort(),in.protocol(),in.validFrom(),in.validTo(),in.lifecycle(),in.clockUncertaintyMs(),in.source().strip(),in.provenance(),now());
            return store.putNetwork(a.organizationId(),a.username(),value);
        });
    }
    static boolean possibleAt(NetworkEvidence e,Instant at){return !at.isBefore(e.validFrom().minusMillis(e.clockUncertaintyMs()))&&at.isBefore(e.validTo().plusMillis(e.clockUncertaintyMs()));}
    private static boolean exactAt(NetworkEvidence e,Instant at){return !at.isBefore(e.validFrom())&&at.isBefore(e.validTo());}
    static Investigation correlate(String id,InvestigationInput q,List<NetworkEvidence> nats,List<NetworkEvidence> leases,String mode){
        if(nats.size()>100||leases.size()>100)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"INVESTIGATION_CAPACITY","Too many candidates to make a complete bounded determination");
        List<Candidate> candidates=nats.stream().filter(n->possibleAt(n,q.at())).map(n->{
            // NAT device is the observing gateway; lease device is its subject. Correlate their captured site, not their IDs.
            var matches=leases.stream().filter(l->l.siteId().equals(n.siteId())&&l.privateIp().equals(n.privateIp())&&l.provenance().equals(n.provenance())&&possibleAt(l,q.at())).toList();
            var flags=new LinkedHashSet<String>();if(n.provenance().equals("SYNTHETIC"))flags.add("SYNTHETIC_DATA");
            if(n.lifecycle().equals("SNAPSHOT_ONLY"))flags.add("SNAPSHOT_ONLY");if(matches.isEmpty())flags.add("MISSING_ADDRESS_LEASE");
            if(n.clockUncertaintyMs()>0||matches.stream().anyMatch(l->l.clockUncertaintyMs()>0))flags.add("CLOCK_UNCERTAINTY");
            if(matches.stream().map(NetworkEvidence::deviceId).distinct().count()>1)flags.add("CONFLICTING_DEVICE_CLAIM");
            return new Candidate(n,matches,List.copyOf(flags));
        }).toList();
        String status;
        if(candidates.isEmpty())status="NO_MATCH";
        else if(candidates.size()>1||candidates.stream().anyMatch(c->c.leases().size()>1||c.qualityFlags().contains("CLOCK_UNCERTAINTY")||c.qualityFlags().contains("CONFLICTING_DEVICE_CLAIM")))status="AMBIGUOUS";
        else{Candidate c=candidates.getFirst();boolean confirmed=c.nat().lifecycle().equals("COMPLETE")&&exactAt(c.nat(),q.at())&&c.leases().size()==1
            &&c.leases().getFirst().lifecycle().equals("COMPLETE")&&exactAt(c.leases().getFirst(),q.at());status=confirmed?"CONFIRMED":"INSUFFICIENT_EVIDENCE";}
        var flags=new LinkedHashSet<String>();candidates.forEach(c->flags.addAll(c.qualityFlags()));if(status.equals("NO_MATCH"))flags.add("NO_RETAINED_MATCH");
        return new Investigation(id,q,status,candidates,List.copyOf(flags),now(),mode);
    }
    public Mono<Investigation> investigate(Operator a,InvestigationInput in){
        observed(in.at());var q=new InvestigationInput(ip(in.ip()),in.port(),in.protocol(),in.at(),in.direction());String id=id();
        return store.networks(a.organizationId(),q).collectList().flatMap(nats->{
            if(nats.size()>100)return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS,"INVESTIGATION_CAPACITY","Too many candidates to make a complete bounded determination"));
            return store.leases(a.organizationId(),nats,q.at()).collectList().map(leases->correlate(id,q,nats,leases,store.mode()));
        })
            .flatMap(result->store.audit(a.organizationId(),a.username(),"INVESTIGATION_QUERIED",id).thenReturn(result));
    }
    public Mono<Models.Page<Audit>> audits(Operator a,String resource,Instant from,Instant to,String cursor,int limit){
        Instant end=to==null?now():to,start=from==null?end.minus(Duration.ofDays(7)):from;
        if(!start.isBefore(end)||Duration.between(start,end).compareTo(Duration.ofDays(31))>0)throw new IllegalArgumentException("Audit window must be positive and at most 31 days");
        return store.audits(a.organizationId(),resource,start,end,cursor,limit+1).collectList().map(rows->page(rows,limit,Audit::createdAt,Audit::id,store.mode()));
    }
    @PreDestroy void close(){diffScheduler.dispose();}
}
