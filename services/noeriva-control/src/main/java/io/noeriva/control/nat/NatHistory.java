package io.noeriva.control.nat;

import io.noeriva.control.*;
import io.noeriva.control.SecurityConfiguration.Operator;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.nat.NatModels.*;

@Component public class NatHistory {
 private final WebClient client;private final JsonMapper json;private final NatSourceStore sources;
 public NatHistory(WebClient.Builder builder,Environment env,JsonMapper json,NatSourceStore sources){this.json=json;this.sources=sources;String password=env.getProperty("NOERIVA_CLICKHOUSE_PASSWORD");client=password==null?null:builder.clone().baseUrl(env.getProperty("NOERIVA_CLICKHOUSE_URL","http://localhost:18123")).defaultHeaders(h->h.setBasicAuth(env.getProperty("NOERIVA_CLICKHOUSE_USERNAME","noeriva"),password)).codecs(c->c.defaultCodecs().maxInMemorySize(2*1024*1024)).build();}
 WebClient client(){if(client==null)throw unavailable();return client;}
 public Mono<Void> append(Envelope batch){if(batch.events()==null||batch.events().isEmpty()||batch.events().size()>128)throw new IllegalArgumentException("NAT_BATCH_LIMIT");var rows=new ArrayList<String>();
  for(NatEvent e:batch.events()){if(!e.deviceId().equals(batch.deviceId())||!e.id().matches("[0-9a-f]{64}")||!e.provenance().equals("CISCO_NAT_HSL_V9"))throw new IllegalArgumentException("NAT_ENVELOPE_SCOPE");var row=new LinkedHashMap<String,Object>();row.put("organization_id",batch.organizationId());row.put("device_id",batch.deviceId());row.put("event_id",e.id());row.put("exported_at",e.exportedAt().toString());row.put("received_at",e.receivedAt().toString());row.put("private_ip",Objects.toString(e.privateIp(),""));row.put("public_ip",Objects.toString(e.publicIp(),""));row.put("protocol",e.protocol()==null?-1:e.protocol());row.put("payload",json.writeValueAsString(e));row.put("revision",Long.MAX_VALUE-e.receivedAt().toEpochMilli());rows.add(json.writeValueAsString(row));}
  String body="INSERT INTO nat_audit_events FORMAT JSONEachRow\n"+String.join("\n",rows)+"\n";if(body.length()>1048576)throw new IllegalArgumentException("NAT_BATCH_BYTES");
  return client().post().uri(b->b.path("/").queryParam("database","noeriva").queryParam("async_insert",0).queryParam("wait_end_of_query",1).queryParam("date_time_input_format","best_effort").build()).bodyValue(body).retrieve().bodyToMono(String.class).defaultIfEmpty("").timeout(Duration.ofSeconds(12)).flatMap(s->s.isBlank()?Mono.empty():Mono.error(unavailable()));
 }
 record Cursor(String scope,Instant from,Instant to,Instant at,String id){}
 record Receipt(Instant receivedAt,Instant exportedAt,String eventId){}
 private static final int CANDIDATES=128;
 public Mono<EventPage> events(Operator actor,String device,Instant from,Instant to,Integer protocol,String privateIp,String publicIp,int limit,String cursor){
  NatSourceStore.readRole(actor);
  if(device==null||device.isBlank()||device.length()>64||limit<1||limit>100||protocol!=null&&(protocol<0||protocol>255)||(from==null)!=(to==null)||from!=null&&!to.isAfter(from))throw new IllegalArgumentException("NAT_QUERY_BOUNDS");
  String privateFilter=privateIp==null||privateIp.isBlank()?"":NatSourceStore.ipv4(privateIp,false),publicFilter=publicIp==null||publicIp.isBlank()?"":NatSourceStore.ipv4(publicIp,false);
  String scope=NatDecoder.sha(json.writeValueAsString(List.of(actor.organizationId(),device,Objects.toString(protocol,""),privateFilter,publicFilter)).getBytes(StandardCharsets.UTF_8));
  Cursor after=decode(cursor,scope);
  if(after!=null&&from!=null&&(!from.equals(after.from())||!to.equals(after.to())))throw new IllegalArgumentException("NAT_CURSOR_RANGE");
  Instant start=after==null?from:after.from(),end=after==null?(to==null?Instant.now():to):after.to();
  var parameters=new LinkedHashMap<String,Object>();parameters.put("org",actor.organizationId());parameters.put("device",device);parameters.put("to",end.toString());
  if(start!=null)parameters.put("from",start.toString());if(protocol!=null)parameters.put("protocol",protocol);if(!privateFilter.isEmpty())parameters.put("private",privateFilter);if(!publicFilter.isEmpty())parameters.put("public",publicFilter);
  if(after!=null){parameters.put("cursorAt",after.at().toString());parameters.put("cursorId",after.id());}
  // The receipt index has no payload and is physically ordered by the requested
  // scope and cursor. LIMIT stops the ordered read without scanning retained history.
  // It intentionally retains replay receipts: only the canonical FINAL lookup below
  // may decide the earliest receipt, so time filters cannot resurrect replays.
  // Scalar cursor bounds let CH 26.3 prune entire duplicate-key granules;
  // tuple comparison otherwise scans them despite identical exclusive semantics.
  String chosen="SELECT received_at AS receivedAt,exported_at AS exportedAt,event_id AS eventId FROM nat_audit_receipts PREWHERE organization_id={org:String} AND device_id={device:String} AND received_at<parseDateTime64BestEffort({to:String})"+(start==null?"":" AND received_at>=parseDateTime64BestEffort({from:String})")+(after==null?"":" AND (received_at<parseDateTime64BestEffort({cursorAt:String}) OR (received_at=parseDateTime64BestEffort({cursorAt:String}) AND event_id<{cursorId:FixedString(64)}))")+" WHERE 1"+(protocol==null?"":" AND protocol={protocol:Int16}")+(privateFilter.isEmpty()?"":" AND private_ip={private:String}")+(publicFilter.isEmpty()?"":" AND public_ip={public:String}")+" ORDER BY organization_id DESC,device_id DESC,received_at DESC,event_id DESC LIMIT "+(CANDIDATES+1)+" FORMAT JSONEachRow";
  return sources.requireDevice(actor.organizationId(),device).then(Mono.defer(()->read(chosen,parameters,true).flatMap(body->{
   List<Receipt> candidates=body.lines().filter(l->!l.isBlank()).map(l->json.readValue(l,Receipt.class)).toList();
   if(candidates.size()>CANDIDATES+1)throw unavailable();if(candidates.isEmpty())return Mono.just(new EventPage(List.of(),null,Instant.now(),"CONNECTED"));
   boolean candidateMore=candidates.size()>CANDIDATES;var examined=candidates.subList(0,Math.min(CANDIDATES,candidates.size()));
   for(Receipt r:examined)if(r.receivedAt()==null||r.exportedAt()==null||r.eventId()==null||!r.eventId().matches("[0-9a-f]{64}"))throw unavailable();
   String keys=examined.stream().map(r->"(fromUnixTimestamp64Milli("+r.exportedAt().toEpochMilli()+"),'"+r.eventId()+"')").distinct().collect(java.util.stream.Collectors.joining(","));
   String canonical="SELECT payload FROM nat_audit_events FINAL PREWHERE organization_id={org:String} AND device_id={device:String} AND (exported_at,event_id) IN ("+keys+") ORDER BY received_at DESC,event_id DESC LIMIT "+(CANDIDATES+1)+" FORMAT JSONEachRow";
   Set<String> receipts=new HashSet<>();examined.forEach(r->receipts.add(r.eventId()+"/"+r.receivedAt().toEpochMilli()));
   return read(canonical,parameters,false).map(payload->{
    var rows=payload.lines().filter(l->!l.isBlank()).map(l->json.readValue(json.readTree(l).path("payload").asString(),NatEvent.class))
      .filter(e->receipts.contains(e.id()+"/"+e.receivedAt().toEpochMilli())).toList();
    if(rows.size()>CANDIDATES)throw unavailable();var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
    String next=null;
    if(rows.size()>limit){var last=page.getLast();next=encode(new Cursor(scope,start,end,last.receivedAt(),last.id()));}
    else if(candidateMore){var last=examined.getLast();next=encode(new Cursor(scope,start,end,last.receivedAt(),last.eventId()));}
    return new EventPage(page,next,Instant.now(),"CONNECTED");
   });
  }).timeout(Duration.ofSeconds(10)).onErrorMap(e->e instanceof ApiException?e:unavailable())));
 }
 private Mono<String> read(String sql,Map<String,Object> parameters,boolean receiptIndex){
  return Mono.usingWhen(Mono.fromSupplier(()->"noeriva-nat-"+UUID.randomUUID()),id->client().post().uri(b->{
   b.path("/").queryParam("database","noeriva").queryParam("query_id",id).queryParam("date_time_output_format","iso").queryParam("optimize_move_to_prewhere",0).queryParam("optimize_read_in_order",1)
    .queryParam("max_execution_time",4).queryParam("max_threads",2).queryParam("max_rows_to_read",receiptIndex?0:2000000).queryParam("max_bytes_to_read",receiptIndex?0:134217728)
    // CH 26.3 substitutes whole-range estimates for actual rows in local THROW
    // mode, rejecting ordered LIMIT reads after millions of retained receipts.
    // Unlimited local BREAK bounds cannot produce a partial result; mandatory
    // leaf THROW bounds below enforce the same 200k actual rows / 128 MiB.
    .queryParam("max_rows_to_read_leaf",receiptIndex?200000:0).queryParam("max_bytes_to_read_leaf",receiptIndex?134217728:0).queryParam("read_overflow_mode_leaf","throw").queryParam("max_block_size",receiptIndex?256:65536).queryParam("max_memory_usage",134217728).queryParam("max_result_rows",CANDIDATES+1).queryParam("max_result_bytes",2097152)
    .queryParam("result_overflow_mode","throw").queryParam("read_overflow_mode",receiptIndex?"break":"throw").queryParam("timeout_overflow_mode","throw").queryParam("wait_end_of_query",1);
   parameters.forEach((key,value)->b.queryParam("param_"+key,value));return b.build();}).bodyValue(sql).retrieve().bodyToMono(String.class).defaultIfEmpty("").timeout(Duration.ofSeconds(5)),
   id->Mono.empty(),(id,error)->kill(id),this::kill);
 }
 private Cursor decode(String value,String scope){if(value==null||value.isBlank())return null;try{if(value.length()>800)throw new IllegalArgumentException();Cursor c=json.readValue(Base64.getUrlDecoder().decode(value),Cursor.class);if(!scope.equals(c.scope())||c.to()==null||c.at()==null||c.id()==null||!c.id().matches("[0-9a-f]{64}")||!c.at().isBefore(c.to())||c.from()!=null&&c.at().isBefore(c.from()))throw new IllegalArgumentException();return c;}catch(Exception e){throw new IllegalArgumentException("NAT_CURSOR_INVALID");}}
 private String encode(Cursor c){return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(c));}
 private Mono<Void> kill(String id){return client().post().uri(b->b.path("/").queryParam("param_id",id).queryParam("max_execution_time",2).build()).bodyValue("KILL QUERY WHERE query_id={id:String} ASYNC").retrieve().toBodilessEntity().timeout(Duration.ofSeconds(2)).then().onErrorComplete();}
 private static ApiException unavailable(){return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"NAT_HISTORY_UNAVAILABLE","NAT审计查询不可用或达到读取预算；请缩小筛选范围后重试，不能将结果视为空");}
}
