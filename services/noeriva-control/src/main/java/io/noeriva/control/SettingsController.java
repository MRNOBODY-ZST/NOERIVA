package io.noeriva.control;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static io.noeriva.control.SecurityConfiguration.Operator;

@RestController @RequestMapping("/api/v1")
public class SettingsController {
 private final PlatformSettings settings;private final EntitySearch search;private final DatabaseClient db;private final PasswordEncoder encoder;private final SessionTokens sessions;
 public SettingsController(PlatformSettings settings,EntitySearch search,ObjectProvider<DatabaseClient> db,PasswordEncoder encoder,SessionTokens sessions){this.settings=settings;this.search=search;this.db=db.getIfAvailable();this.encoder=encoder;this.sessions=sessions;}
 @GetMapping("/settings") public Mono<Map<String,Object>> get(@AuthenticationPrincipal Operator user){return settings.get(user.organizationId()).zipWith(search.liveStatus()).map(t->view(user,t.getT1(),t.getT2()));}
 @PostMapping("/settings") public Mono<Map<String,Object>> save(@AuthenticationPrincipal Operator user,@RequestBody PlatformSettings.Input input){return settings.save(user,input).map(v->view(user,v,search.status()));}
 @PostMapping("/search/reindex") public Mono<Map<String,Object>> reindex(@AuthenticationPrincipal Operator user){if(!user.roles().contains("ADMIN"))return Mono.error(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Administrator role is required"));return search.reindex();}
 @GetMapping("/search") public Mono<EntitySearch.Result> search(@AuthenticationPrincipal Operator user,@RequestParam String q,@RequestParam(defaultValue="20") int limit){return search.search(user.organizationId(),q,limit);}
 public record PasswordInput(String currentPassword,String newPassword){@Override public String toString(){return "PasswordInput[REDACTED]";}}
 @PostMapping("/settings/password") public Mono<Map<String,Object>> password(@AuthenticationPrincipal Operator user,@RequestBody PasswordInput input){
  if(db==null)return Mono.error(new ApiException(HttpStatus.CONFLICT,"DEMO_ACCOUNT","Demo accounts cannot be changed"));
  if(input.currentPassword()==null||input.currentPassword().length()>1024||input.newPassword()==null||input.newPassword().length()<12||input.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)return Mono.error(new IllegalArgumentException("Password must be 12 characters or more and at most 72 UTF-8 bytes"));
  return Mono.fromCallable(()->{if(!encoder.matches(input.currentPassword(),user.password()))throw new ApiException(HttpStatus.FORBIDDEN,"PASSWORD_MISMATCH","Current password is incorrect");return encoder.encode(input.newPassword());}).subscribeOn(Schedulers.boundedElastic())
   .flatMap(hash->db.sql("UPDATE app_user SET password_hash=:hash WHERE organization_id=:org AND username=:user AND password_hash=:old").bind("hash",hash).bind("org",user.organizationId()).bind("user",user.username()).bind("old",user.password()).fetch().rowsUpdated())
   .flatMap(n->n==1?sessions.revokeUser(user.username()).thenReturn(Map.<String,Object>of("changed",true,"reauthenticate",true)):Mono.error(ApiException.conflict()));
 }
 private Map<String,Object> view(Operator user,PlatformSettings.Values v,Map<String,Object> es){
  var result=new LinkedHashMap<String,Object>();result.put("revision",v.revision());result.put("organizationName",v.organizationName());result.put("timezone",v.timezone());result.put("defaultCollectionIntervalSeconds",v.defaultCollectionIntervalSeconds());result.put("defaultTimeoutMillis",v.defaultTimeoutMillis());result.put("defaultMaxInterfaces",v.defaultMaxInterfaces());result.put("configurationSyncEnabled",v.configurationSyncEnabled());result.put("configurationSyncIntervalSeconds",v.configurationSyncIntervalSeconds());result.put("updatedAt",v.updatedAt());result.put("updatedBy",v.updatedBy());
  var runtime=new LinkedHashMap<String,Object>();runtime.put("mode",db==null?"DEMO":"CONNECTED");runtime.put("searchProvider",es.get("provider"));runtime.put("searchStatus",es.get("status"));runtime.put("searchLastIndexedAt",es.get("lastIndexedAt"));runtime.put("configurationCapture","READ_ONLY");runtime.put("historyRetention","NO_AUTOMATIC_DELETION");result.put("runtime",runtime);result.put("account",Map.of("username",user.username(),"roles",user.roles(),"organizationId",user.organizationId()));return result;
 }
}
