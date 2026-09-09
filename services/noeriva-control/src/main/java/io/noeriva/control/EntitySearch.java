package io.noeriva.control;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;

/** Rebuildable, organization-scoped entity search. Device state remains in MySQL. */
@Service
public class EntitySearch {
 public record Hit(String kind,String id,String deviceId,String name,String deviceName,String address,String source,Instant indexedAt){}
 public record Result(List<Hit> items,String provider,String status,Instant indexedAt){}
 private static final String INDEX="noeriva-entities-v1";
 private final WebClient client;private final DatabaseClient db;private final JsonMapper json;private final boolean worker;
 private final AtomicBoolean running=new AtomicBoolean();private volatile Instant lastIndexed;private volatile String state="NOT_CONFIGURED";
 public EntitySearch(ObjectProvider<DatabaseClient> database,WebClient.Builder builder,Environment env,JsonMapper json){
  this.db=database.getIfAvailable();this.json=json;String url=env.getProperty("NOERIVA_ELASTICSEARCH_URL","");this.worker=env.getProperty("NOERIVA_DEVICE_COLLECTOR_ENABLED",Boolean.class,false);
  client=url.isBlank()?null:builder.clone().baseUrl(url).defaultHeaders(h->h.setBasicAuth(env.getProperty("NOERIVA_ELASTICSEARCH_USERNAME","elastic"),env.getProperty("NOERIVA_ELASTICSEARCH_PASSWORD",""))).codecs(c->c.defaultCodecs().maxInMemorySize(2*1024*1024)).build();if(client!=null)state="WAITING";
 }
 public Map<String,Object> status(){var result=new LinkedHashMap<String,Object>();result.put("provider",client==null?"UNCONFIGURED":"ELASTICSEARCH");result.put("status",state);result.put("lastIndexedAt",lastIndexed);result.put("reindexing",running.get());return result;}
 public Mono<Map<String,Object>> liveStatus(){if(client==null)return Mono.just(status());return client.get().uri("/"+INDEX+"/_search?size=1&sort=indexedAt:desc&_source=indexedAt").retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(3)).map(s->{var hits=json.readTree(s).path("hits").path("hits");if(!hits.isEmpty())lastIndexed=Instant.parse(hits.get(0).path("_source").path("indexedAt").asText());state="READY";return status();}).onErrorResume(e->{state="UNAVAILABLE";return Mono.just(status());});}
 public Mono<Result> search(String org,String query,int limit){
  if(client==null)return Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SEARCH_NOT_CONFIGURED","Elasticsearch is not configured"));
  String needle=query.strip().toLowerCase(Locale.ROOT);if(needle.length()<2||needle.length()>120||limit<1||limit>60)throw new IllegalArgumentException("Search requires 2–120 characters");
  String escaped=needle.replace("\\","\\\\").replace("*","\\*").replace("?","\\?");
  var body=Map.of("size",limit,"timeout","2s","track_total_hits",false,"query",Map.of("bool",Map.of("filter",List.of(Map.of("term",Map.of("organizationId",org)),Map.of("wildcard",Map.of("text",Map.of("value","*"+escaped+"*")))))));
  return client.post().uri("/"+INDEX+"/_search?allow_partial_search_results=false").bodyValue(body).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(4)).map(raw->{var n=json.readTree(raw);if(!n.path("hits").path("hits").isArray()||n.path("hits").path("hits").size()>limit||!n.path("timed_out").isBoolean()||!n.path("_shards").path("failed").isIntegralNumber()||n.path("timed_out").asBoolean()||n.path("_shards").path("failed").asInt()>0)throw new IllegalStateException("Incomplete search");var hits=new ArrayList<Hit>();for(var h:n.path("hits").path("hits")){var d=h.path("_source");if(!org.equals(d.path("organizationId").asText()))throw new IllegalStateException("Invalid search scope");hits.add(new Hit(d.path("kind").asText(),d.path("id").asText(),d.path("deviceId").asText(),d.path("name").asText(),d.path("deviceName").asText(),d.path("address").asText(),"ELASTICSEARCH",Instant.parse(d.path("indexedAt").asText())));}state="READY";return new Result(List.copyOf(hits),"ELASTICSEARCH","READY",hits.stream().map(Hit::indexedAt).max(Instant::compareTo).orElse(lastIndexed));}).onErrorMap(e->new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SEARCH_UNAVAILABLE","Elasticsearch search is unavailable; no partial substitute was returned"));
 }
 public Mono<Map<String,Object>> reindex(){if(client==null||db==null)return Mono.error(new ApiException(HttpStatus.CONFLICT,"SEARCH_NOT_CONFIGURED","Search projection is not configured"));if(!running.compareAndSet(false,true))return Mono.just(status());state="INDEXING";Instant started=Instant.now();ensureIndex().then(indexPages("","")).then(pruneBefore(started)).doOnSuccess(v->{lastIndexed=Instant.now();state="READY";}).doOnError(e->state="UNAVAILABLE").doFinally(s->running.set(false)).onErrorComplete().subscribe();return Mono.just(status());}
 @Scheduled(fixedDelay=60000,initialDelay=10000) public void tick(){if(worker&&client!=null&&db!=null)reindex().onErrorComplete().subscribe();}
 private Mono<Void> ensureIndex(){
  var properties=new LinkedHashMap<String,Object>();for(String k:List.of("organizationId","kind","id","deviceId","address"))properties.put(k,Map.of("type","keyword"));for(String k:List.of("name","deviceName"))properties.put(k,Map.of("type","keyword","ignore_above",512));properties.put("text",Map.of("type","wildcard"));properties.put("indexedAt",Map.of("type","date"));
  var body=Map.of("settings",Map.of("number_of_shards",1,"number_of_replicas",0),"mappings",Map.of("dynamic","strict","properties",properties));
  return client.put().uri("/"+INDEX).bodyValue(body).exchangeToMono(r->r.bodyToMono(String.class).defaultIfEmpty("").flatMap(s->{if(r.statusCode().is2xxSuccessful()||r.statusCode().value()==400&&s.contains("resource_already_exists_exception"))return Mono.<Void>empty();return Mono.<Void>error(new IllegalStateException("INDEX_CREATE_FAILED"));})).timeout(Duration.ofSeconds(5));
 }
 private record Entity(String org,String id,String name,String address){}
 private Mono<Void> indexPages(String orgAfter,String idAfter){return db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ organization_id,id,name,management_address FROM device WHERE (organization_id>:org OR(organization_id=:org AND id>:id)) ORDER BY organization_id,id LIMIT 100").bind("org",orgAfter).bind("id",idAfter).map((r,m)->new Entity(r.get("organization_id",String.class),r.get("id",String.class),r.get("name",String.class),r.get("management_address",String.class))).all().collectList().flatMap(rows->{if(rows.isEmpty())return Mono.empty();return Flux.fromIterable(rows).concatMap(this::indexDevice).then(rows.size()<100?Mono.empty():Mono.defer(()->{var last=rows.getLast();return indexPages(last.org(),last.id());}));});}
 private Mono<Void> indexDevice(Entity device){var docs=new ArrayList<Map<String,Object>>();docs.add(document(device,"DEVICE",device.id(),device.name(),device.address()));
  return db.sql("SELECT payload FROM network_interface WHERE organization_id=:org AND device_id=:device ORDER BY id LIMIT 1000").bind("org",device.org()).bind("device",device.id()).map((r,m)->json.readValue(r.get("payload",String.class),Models.NetworkInterface.class)).all().doOnNext(i->docs.add(document(device,"INTERFACE",i.id(),i.name(),Objects.toString(i.macAddress(),"")))).then(Mono.defer(()->bulk(docs)));
 }
 private Map<String,Object> document(Entity d,String kind,String id,String name,String address){var doc=new LinkedHashMap<String,Object>();doc.put("organizationId",d.org());doc.put("kind",kind);doc.put("id",id);doc.put("deviceId",d.id());doc.put("name",name);doc.put("deviceName",d.name());doc.put("address",address);doc.put("text",(name+" "+d.name()+" "+address+" "+d.address()+" "+kind).toLowerCase(Locale.ROOT));doc.put("indexedAt",Instant.now().toString());return doc;}
 private Mono<Void> pruneBefore(Instant started){return client.post().uri("/"+INDEX+"/_delete_by_query?conflicts=proceed&refresh=true&timeout=5s").bodyValue(Map.of("query",Map.of("range",Map.of("indexedAt",Map.of("lt",started.toString()))))).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(10)).flatMap(raw->{var result=json.readTree(raw);return !result.path("timed_out").isBoolean()||!result.path("failures").isArray()||result.path("timed_out").asBoolean()||!result.path("failures").isEmpty()?Mono.error(new IllegalStateException("SEARCH_PRUNE_FAILED")):Mono.empty();});}
 private Mono<Void> bulk(List<Map<String,Object>> docs){return Flux.fromIterable(docs).buffer(200).concatMap(batch->{var body=new StringBuilder();for(var d:batch){String id=Base64.getUrlEncoder().withoutPadding().encodeToString((d.get("organizationId")+"/"+d.get("kind")+"/"+d.get("id")).getBytes(java.nio.charset.StandardCharsets.UTF_8));body.append(json.writeValueAsString(Map.of("index",Map.of("_index",INDEX,"_id",id)))).append('\n').append(json.writeValueAsString(d)).append('\n');}return client.post().uri("/_bulk?refresh=false").contentType(MediaType.APPLICATION_NDJSON).bodyValue(body.toString()).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(10)).flatMap(raw->{var result=json.readTree(raw);if(!result.path("errors").isBoolean()||result.path("errors").asBoolean()||!result.path("items").isArray()||result.path("items").size()!=batch.size())return Mono.error(new IllegalStateException("SEARCH_BULK_FAILED"));for(var item:result.path("items")){int status=item.path("index").path("status").asInt();if(status<200||status>=300||item.path("index").hasNonNull("error"))return Mono.error(new IllegalStateException("SEARCH_BULK_FAILED"));}return Mono.empty();});}).then();}
}
