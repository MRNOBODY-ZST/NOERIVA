package io.noeriva.control.applications;

import io.noeriva.control.*;
import io.noeriva.control.devices.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import static io.noeriva.control.SecurityConfiguration.Operator;
import static io.noeriva.control.applications.ApplicationModels.*;

@Service public class ApplicationService {
    private final ApplicationStore store;private final DeviceAccessStore devices;private final CredentialVault vault;private final TargetPolicy policy;private final ApplicationProtocol driver;private final ApplicationHistory history;
    private final Semaphore collectors=new Semaphore(4);
    public ApplicationService(ApplicationStore store,DeviceAccessStore devices,CredentialVault vault,TargetPolicy policy,ApplicationProtocol driver,ApplicationHistory history){this.store=store;this.devices=devices;this.vault=vault;this.policy=policy;this.driver=driver;this.history=history;}
    private static void role(Operator user,boolean write){if(user==null||user.roles().stream().noneMatch(write?List.of("ADMIN")::contains:List.of("ADMIN","OPERATOR","VIEWER")::contains))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","This action is not available to your role");}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_APPLICATION_SETTINGS","Select bounded intervals, distinct interface indices and row limits");}
    public Mono<Models.Page<Source>> sources(Operator user,int limit,String cursor){role(user,false);if(limit<1||limit>200)throw invalid();String scope=user.organizationId()+"/application-sources";String after=ApplicationCursor.decode(scope,cursor);if(after.length()>64)throw invalid();return store.sources(user.organizationId(),after,limit+1).collectList().map(rows->{var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Models.Page<>(items,rows.size()>limit?ApplicationCursor.encode(scope,items.getLast().deviceId()):null,Instant.now(),"MYSQL","CONNECTED");});}
    private Mono<DeviceAccessModels.Stored> credential(String org,String device){return devices.required(org,device).then(devices.get(org,device,"snmp")).switchIfEmpty(Mono.error(ApiException.missing()));}
    public Mono<Source> save(Operator user,String device,SettingsInput input){role(user,true);if(input==null||input.revision()<0||input.intervalSeconds()<30||input.intervalSeconds()>3600||input.maxRows()<1||input.maxRows()>256||input.interfaceIndices()==null||input.interfaceIndices().isEmpty()||input.interfaceIndices().size()>8||input.interfaceIndices().stream().anyMatch(i->i==null||i<1)||new HashSet<>(input.interfaceIndices()).size()!=input.interfaceIndices().size())throw invalid();
        if(input.maxRows()<input.interfaceIndices().size())throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_APPLICATION_SETTINGS","协议行总限额不能少于选定接口数");
        var normalized=new SettingsInput(input.revision(),input.enabled(),input.intervalSeconds(),input.interfaceIndices().stream().sorted().toList(),input.maxRows());return credential(user.organizationId(),device).then(store.save(user.organizationId(),user.username(),device,normalized)).then(store.source(user.organizationId(),device));}
    public Mono<Source> state(Operator user,String device,StateInput input){role(user,true);if(input==null||input.revision()<1)throw invalid();return credential(user.organizationId(),device).then(store.state(user.organizationId(),user.username(),device,input)).then(store.source(user.organizationId(),device));}
    public Mono<Source> collect(Operator user,String device,CollectInput input){role(user,true);if(input==null||input.revision()<1)throw invalid();return credential(user.organizationId(),device).then(store.get(user.organizationId(),device)).switchIfEmpty(Mono.error(ApiException.missing())).flatMap(v->{if(v.revision()!=input.revision())return Mono.error(ApiException.conflict());return store.audit(user.organizationId(),user.username(),"APPLICATION_COLLECTION_REQUESTED",device).then(poll(v,false));}).then(store.source(user.organizationId(),device));}
    public Mono<Stored> poll(Stored expected,boolean scheduled){
        return Mono.defer(()->{
            if(!collectors.tryAcquire())return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS,"APPLICATION_CAPACITY","Application collection capacity reached"));
            return store.acquire(expected,scheduled).flatMap(v->credential(v.organizationId(),v.deviceId()).flatMap(c->{
                return Mono.defer(()->policy.resolve(c.settings().host()).flatMap(address->driver.read(c.settings().target("SNMP",address),vault.decrypt(c.scope(),c.ciphertext()),v.interfaceIndices(),v.maxRows())))
                    .flatMap(sample->{var rows=ApplicationRates.derive(v.deviceId(),v.leaseToken(),sample,v.baseline(),c.revision(),v.baselineCredentialRevision(),v.intervalSeconds());var flags=new LinkedHashSet<>(sample.qualityFlags());rows.forEach(r->flags.addAll(r.qualityFlags()));
                        return store.assertLease(v,c.revision()).then(history.append(v.organizationId(),rows).onErrorMap(e->new DeviceProtocol.Failure("PUBLICATION_FAILED","Application storage did not confirm this sample; the baseline was not advanced")))
                            .then(store.finish(v,sample,c.revision(),rows.size(),"","",List.copyOf(flags)));
                    });
            }).timeout(Duration.ofSeconds(40)).onErrorResume(e->{String code=e instanceof DeviceProtocol.Failure f?f.code():e instanceof ApiException a?a.code:"APPLICATION_COLLECTION_FAILED";String message=e instanceof DeviceProtocol.Failure f?f.getMessage():"The application read or storage commit did not complete within its budget";return store.finish(v,null,0,0,code,message,List.of()).timeout(Duration.ofSeconds(4));}))
                .timeout(Duration.ofSeconds(45)).doFinally(signal->collectors.release());
        }).cache(); // A started bounded collection finishes when the HTTP reader navigates away.
    }
    public Mono<Summary> summary(Operator user,String device,Integer index,String direction,String q,int limit){
        role(user,false);String d=direction==null?"":direction,text=q==null?"":q.trim();
        if(device==null||device.isBlank()||device.length()>64||index!=null&&index<1||limit<1||limit>30||!Set.of("","IN","OUT").contains(d)||text.length()>80)throw invalid();
        return devices.required(user.organizationId(),device).then(store.source(user.organizationId(),device))
            .flatMap(source->history.summary(user.organizationId(),device,index,d,text,limit,Math.max(180,source.intervalSeconds()*3)));
    }
    public Mono<Models.Page<Observation>> observations(Operator user,String device,Integer index,String direction,String q,Instant from,Instant to,int limit,String cursor){
        role(user,false);if(device==null||device.isBlank()||device.length()>64||index!=null&&index<1||limit<1||limit>500)throw invalid();String d=direction==null?"":direction;String text=q==null?"":q.trim();if(!Set.of("","IN","OUT").contains(d)||text.length()>80)throw invalid();
        if(from!=null&&to!=null&&(!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(7))>0))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_APPLICATION_RANGE","Select a time range of at most seven days");
        return devices.required(user.organizationId(),device).then(Mono.defer(()->history.query(user.organizationId(),device,index,d,text,from,to,limit,cursor)));
    }
}
