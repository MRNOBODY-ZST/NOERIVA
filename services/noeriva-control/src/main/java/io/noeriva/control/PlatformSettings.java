package io.noeriva.control;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.SecurityConfiguration.Operator;

@Service
public class PlatformSettings {
 public record Input(long revision,String organizationName,String timezone,int defaultCollectionIntervalSeconds,int defaultTimeoutMillis,int defaultMaxInterfaces,boolean configurationSyncEnabled,int configurationSyncIntervalSeconds){}
 public record Values(long revision,String organizationName,String timezone,int defaultCollectionIntervalSeconds,int defaultTimeoutMillis,int defaultMaxInterfaces,boolean configurationSyncEnabled,int configurationSyncIntervalSeconds,Instant updatedAt,String updatedBy){}
 private final DatabaseClient db;private final JsonMapper json;private final Map<String,Values> demo=new ConcurrentHashMap<>();
 public PlatformSettings(ObjectProvider<DatabaseClient> db,JsonMapper json){this.db=db.getIfAvailable();this.json=json;}
 public static Values defaults(){return new Values(0,"NOERIVA 工作区","Asia/Shanghai",60,5000,256,true,3600,null,null);}
 public Mono<Values> get(String org){if(db==null)return Mono.fromSupplier(()->demo.getOrDefault(org,defaults()));return db.sql("SELECT payload FROM organization_settings WHERE organization_id=:org").bind("org",org).map((r,m)->json.readValue(r.get("payload",String.class),Values.class)).one().defaultIfEmpty(defaults());}
 public Mono<Values> save(Operator user,Input in){
  if(!user.roles().contains("ADMIN"))return Mono.error(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Administrator role is required"));validate(in);
  var value=new Values(in.revision()+1,in.organizationName().strip(),in.timezone(),in.defaultCollectionIntervalSeconds(),in.defaultTimeoutMillis(),in.defaultMaxInterfaces(),in.configurationSyncEnabled(),in.configurationSyncIntervalSeconds(),Instant.now(),user.username());
  if(db==null)return Mono.fromSupplier(()->{synchronized(demo){if(demo.getOrDefault(user.organizationId(),defaults()).revision()!=in.revision())throw ApiException.conflict();demo.put(user.organizationId(),value);return value;}});
  String sql=in.revision()==0?"INSERT IGNORE INTO organization_settings(organization_id,revision,payload,updated_at,updated_by) VALUES(:org,1,:payload,UTC_TIMESTAMP(6),:actor)":"UPDATE organization_settings SET revision=revision+1,payload=:payload,updated_at=UTC_TIMESTAMP(6),updated_by=:actor WHERE organization_id=:org AND revision=:revision";
  var q=db.sql(sql).bind("org",user.organizationId()).bind("payload",json.writeValueAsString(value)).bind("actor",user.username());if(in.revision()!=0)q=q.bind("revision",in.revision());
  return q.fetch().rowsUpdated().flatMap(n->n==1?Mono.just(value):Mono.error(ApiException.conflict()));
 }
 public static void validate(Input in){
  if(in==null||in.revision()<0||in.revision()==Long.MAX_VALUE||in.organizationName()==null||in.organizationName().isBlank()||in.organizationName().length()>120||in.timezone()==null||!ZoneId.getAvailableZoneIds().contains(in.timezone())||in.defaultCollectionIntervalSeconds()<15||in.defaultCollectionIntervalSeconds()>86400||in.defaultTimeoutMillis()<250||in.defaultTimeoutMillis()>10000||in.defaultMaxInterfaces()<1||in.defaultMaxInterfaces()>256||in.configurationSyncIntervalSeconds()<300||in.configurationSyncIntervalSeconds()>86400)throw new IllegalArgumentException("Settings are outside supported limits");
 }
}
