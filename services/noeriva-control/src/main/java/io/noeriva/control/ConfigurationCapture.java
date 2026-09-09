package io.noeriva.control;

import io.noeriva.control.devices.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;
import static io.noeriva.control.SecurityConfiguration.Operator;

@Service
public class ConfigurationCapture {
 public record State(String status,String message,Instant capturedAt,WorkbenchModels.Snapshot snapshot,String source,Instant lastAttemptAt){}
 private final ConfigurationReader reader;private final WorkbenchService workbench;private final DatabaseClient db;private final PlatformSettings settings;private final ControlRepository inventory;private final boolean worker;
 private final Map<String,State> memory=new ConcurrentHashMap<>();private final Semaphore permits=new Semaphore(2);private final AtomicBoolean scheduled=new AtomicBoolean();
 public ConfigurationCapture(ConfigurationReader reader,WorkbenchService workbench,ObjectProvider<DatabaseClient> db,PlatformSettings settings,ControlRepository inventory,Environment env){this.reader=reader;this.workbench=workbench;this.db=db.getIfAvailable();this.settings=settings;this.inventory=inventory;worker=env.getProperty("NOERIVA_DEVICE_COLLECTOR_ENABLED",Boolean.class,false);}
 public Mono<State> state(String org,String device){return inventory.device(org,device).switchIfEmpty(Mono.error(ApiException.missing())).then(db==null?Mono.just(memory.getOrDefault(org+"/"+device,new State("NOT_CAPTURED","尚无同步记录",null,null,null,null))):db.sql("SELECT status,message,last_attempt_at,last_success_at,source FROM configuration_capture WHERE organization_id=:org AND device_id=:device").bind("org",org).bind("device",device).map((r,m)->new State(r.get("status",String.class),r.get("message",String.class),instant(r.get("last_success_at",LocalDateTime.class)),null,r.get("source",String.class),instant(r.get("last_attempt_at",LocalDateTime.class)))).one().defaultIfEmpty(new State("NOT_CAPTURED","尚无同步记录",null,null,null,null)));}
 public Mono<State> capture(Operator user,String device){if(!user.roles().contains("ADMIN"))return Mono.error(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Administrator role is required"));return Mono.defer(()->{
  if(!permits.tryAcquire())return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS,"CAPTURE_BUSY","Configuration reader capacity reached"));
  return inventory.device(user.organizationId(),device).switchIfEmpty(Mono.error(ApiException.missing())).then(claim(user.organizationId(),device)).then(Mono.defer(()->reader.read(user.organizationId(),device))).flatMap(c->{
   var redacted=WorkbenchService.redact(c.content());String hash=WorkbenchService.sha256(redacted.content());
   return workbench.snapshots(user,device,"","",1).flatMap(old->{var last=old.items().stream().findFirst();if(last.isPresent()&&last.get().sha256().equals(hash)&&last.get().source().equals(c.source()))return saveState(user.organizationId(),device,new State("UNCHANGED",c.source().endsWith("BASELINE")?"基线读取成功，内容未变化；此来源不包含完整运行配置":"读取成功，配置内容未变化",c.capturedAt(),null,c.source(),Instant.now()),last.get().id(),hash);
    return workbench.createSnapshot(user,new WorkbenchModels.ConfigurationInput(device,"自动同步 · "+c.source(),c.source(),c.capturedAt(),c.content(),"DEVICE_READ_ONLY")).flatMap(snapshot->saveState(user.organizationId(),device,new State("SUCCESS",c.source().endsWith("BASELINE")?"已同步身份与接口基线；此来源不包含完整运行配置":"已同步设备运行配置",c.capturedAt(),snapshot,c.source(),Instant.now()),snapshot.id(),snapshot.sha256()));
   });
  }).onErrorResume(e->{if(e instanceof ApiException a)return Mono.error(e);String code=e instanceof DeviceProtocol.Failure f?f.code():"CONFIGURATION_READ_FAILED";return saveState(user.organizationId(),device,new State(code.equals("CONFIGURATION_UNSUPPORTED")?"UNSUPPORTED":"ERROR",code,null,null,null,Instant.now()),null,null);}).doFinally(s->permits.release());
 });}
 private Mono<Void> claim(String org,String device){if(db==null)return Mono.empty();return db.sql("INSERT IGNORE INTO configuration_capture(organization_id,device_id,status,message) VALUES(:org,:device,'NOT_CAPTURED','')").bind("org",org).bind("device",device).fetch().rowsUpdated().then(db.sql("UPDATE configuration_capture SET status='RUNNING',last_attempt_at=UTC_TIMESTAMP(6) WHERE organization_id=:org AND device_id=:device AND (status<>'RUNNING' OR last_attempt_at<UTC_TIMESTAMP()-INTERVAL 90 SECOND)").bind("org",org).bind("device",device).fetch().rowsUpdated()).flatMap(n->n==1?Mono.empty():Mono.error(ApiException.conflict()));}
 private Mono<State> saveState(String org,String device,State state,String snapshot,String hash){if(db==null){memory.put(org+"/"+device,state);return Mono.just(state);}return settings.get(org).flatMap(s->{var query=db.sql("UPDATE configuration_capture SET status=:status,message=:message,last_success_at=COALESCE(:success,last_success_at),next_capture_at=:next,snapshot_id=COALESCE(:snapshot,snapshot_id),content_hash=COALESCE(:hash,content_hash),source=COALESCE(:source,source) WHERE organization_id=:org AND device_id=:device").bind("org",org).bind("device",device).bind("status",state.status()).bind("message",state.message()).bind("next",LocalDateTime.ofInstant(Instant.now().plusSeconds(s.configurationSyncIntervalSeconds()),ZoneOffset.UTC));query=state.capturedAt()==null?query.bindNull("success",LocalDateTime.class):query.bind("success",LocalDateTime.ofInstant(state.capturedAt(),ZoneOffset.UTC));query=snapshot==null?query.bindNull("snapshot",String.class):query.bind("snapshot",snapshot);query=hash==null?query.bindNull("hash",String.class):query.bind("hash",hash);query=state.source()==null?query.bindNull("source",String.class):query.bind("source",state.source());return query.fetch().rowsUpdated().thenReturn(state);});}
 @Scheduled(fixedDelay=60000,initialDelay=25000) public void tick(){if(!worker||db==null||!scheduled.compareAndSet(false,true))return;db.sql("SELECT d.organization_id,d.id FROM device d LEFT JOIN configuration_capture c ON c.organization_id=d.organization_id AND c.device_id=d.id LEFT JOIN organization_settings s ON s.organization_id=d.organization_id WHERE COALESCE(JSON_UNQUOTE(s.payload->'$.configurationSyncEnabled'),'true')='true' AND (c.next_capture_at IS NULL OR c.next_capture_at<=UTC_TIMESTAMP(6)) AND (c.status IS NULL OR c.status<>'RUNNING' OR c.last_attempt_at<UTC_TIMESTAMP()-INTERVAL 90 SECOND) ORDER BY COALESCE(c.next_capture_at,'2000-01-01'),d.organization_id,d.id LIMIT 20").map((r,m)->Map.entry(r.get("organization_id",String.class),r.get("id",String.class))).all().concatMap(d->settings.get(d.getKey()).filter(PlatformSettings.Values::configurationSyncEnabled).flatMap(s->capture(new Operator("configuration-worker","",d.getKey(),List.of("ADMIN")),d.getValue()))).then().doFinally(s->scheduled.set(false)).onErrorComplete().subscribe();}
 private static Instant instant(LocalDateTime v){return v==null?null:v.toInstant(ZoneOffset.UTC);}
}
