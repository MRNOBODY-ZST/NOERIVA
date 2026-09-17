package io.noeriva.control.applications;

import io.noeriva.control.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.applications.ApplicationModels.*;

@Repository public class ApplicationHistory {
    private final WebClient client;private final JsonMapper json;
    public ApplicationHistory(WebClient.Builder builder,Environment env,JsonMapper json){this.json=json;this.client=builder.clone().baseUrl(env.getProperty("NOERIVA_CLICKHOUSE_URL","http://localhost:8123"))
        .defaultHeaders(h->h.setBasicAuth(env.getProperty("NOERIVA_CLICKHOUSE_USERNAME","noeriva"),env.getProperty("NOERIVA_CLICKHOUSE_PASSWORD","")))
        .codecs(c->c.defaultCodecs().maxInMemorySize(4*1024*1024)).build();}
    public Mono<Void> append(String org,List<Observation> observations){
        return Mono.defer(()->{
            if(observations.size()>512)return Mono.error(new IllegalArgumentException("Application batch exceeds its bound"));if(observations.isEmpty())return Mono.empty();
            StringBuilder body=new StringBuilder("INSERT INTO noeriva.application_observations FORMAT JSONEachRow\n");
            for(var o:observations)body.append(json.writeValueAsString(Map.of("organization_id",org,"device_id",o.deviceId(),"observation_id",o.id(),"observed_at",o.observedAt().toString(),"interface_index",o.interfaceIndex(),"direction",o.direction(),"application",o.application(),"payload",json.writeValueAsString(o),"revision",1))).append('\n');
            if(body.length()>3*1024*1024)return Mono.error(new IllegalArgumentException("Application batch exceeds its byte bound"));
            return client.post().uri(b->b.path("/").queryParam("async_insert",0).queryParam("wait_end_of_query",1).queryParam("date_time_input_format","best_effort").queryParam("max_execution_time",6).queryParam("max_memory_usage",134217728).build())
                .contentType(MediaType.TEXT_PLAIN).bodyValue(body.toString()).retrieve().bodyToMono(String.class).defaultIfEmpty("").timeout(Duration.ofSeconds(8))
                .flatMap(response->response.isBlank()?Mono.<Void>empty():Mono.error(new IllegalStateException("Insert not confirmed")))
                .onErrorMap(e->unavailable());
        });
    }
    public Mono<Models.Page<Observation>> query(String org,String device,Integer index,String direction,String q,Instant from,Instant to,int limit,String cursor){
        String scope=String.join("/",org,device,Objects.toString(index,""),direction,q);String after=ApplicationCursor.decode(scope,cursor);
        Instant end=to==null?Instant.now():to,start=from==null?end.minusSeconds(3600):from;
        Long afterTime=null;String afterId=null;
        if(!after.isEmpty())try{String[] parts=after.split(":",4);long cursorFrom=Long.parseLong(parts[0]),cursorTo=Long.parseLong(parts[1]);if(from!=null&&from.toEpochMilli()!=cursorFrom||to!=null&&to.toEpochMilli()!=cursorTo)throw new IllegalArgumentException();start=Instant.ofEpochMilli(cursorFrom);end=Instant.ofEpochMilli(cursorTo);afterTime=Long.parseLong(parts[2]);afterId=parts[3];if(!afterId.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();}catch(Exception e){throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CURSOR","Invalid observation cursor");}
        if(!start.isBefore(end)||Duration.between(start,end).compareTo(Duration.ofDays(7))>0)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_APPLICATION_RANGE","Select a time range of at most seven days");
        long fromMillis=start.toEpochMilli(),toMillis=end.toEpochMilli();
        String sql="SELECT payload FROM noeriva.application_observations FINAL WHERE organization_id={org:String} AND device_id={device:String} AND observed_at>=fromUnixTimestamp64Milli({from:Int64}) AND observed_at<fromUnixTimestamp64Milli({to:Int64})"+(index==null?"":" AND interface_index={index:UInt32}")+(direction.isEmpty()?"":" AND direction={direction:String}")+(q.isEmpty()?"":" AND positionCaseInsensitiveUTF8(application,{q:String})>0")+(after.isEmpty()?"":" AND (observed_at,observation_id)<(fromUnixTimestamp64Milli({afterTime:Int64}),{afterId:String})")+" ORDER BY observed_at DESC,observation_id DESC LIMIT "+(limit+1)+" FORMAT JSONEachRow";
        Long time=afterTime;String id=afterId;
        return Mono.usingWhen(Mono.fromSupplier(()->"noeriva-app-"+UUID.randomUUID()),queryId->client.post().uri(b->{
            b.path("/").queryParam("query_id",queryId).queryParam("param_org",org).queryParam("param_device",device).queryParam("param_from",fromMillis).queryParam("param_to",toMillis).queryParam("max_execution_time",5).queryParam("max_rows_to_read",1000000).queryParam("max_bytes_to_read",67108864).queryParam("max_memory_usage",134217728).queryParam("max_result_rows",501).queryParam("max_result_bytes",4194304).queryParam("max_threads",2).queryParam("result_overflow_mode","throw").queryParam("read_overflow_mode","throw").queryParam("timeout_overflow_mode","throw").queryParam("wait_end_of_query",1);
            if(index!=null)b.queryParam("param_index",index);if(!direction.isEmpty())b.queryParam("param_direction",direction);if(!q.isEmpty())b.queryParam("param_q",q);if(time!=null)b.queryParam("param_afterTime",time).queryParam("param_afterId",id);return b.build();})
            .contentType(MediaType.TEXT_PLAIN).bodyValue(sql).retrieve().bodyToMono(String.class).defaultIfEmpty("").timeout(Duration.ofSeconds(7))
            .map(body->{var values=body.lines().filter(line->!line.isBlank()).map(line->json.readValue(json.readTree(line).get("payload").asText(),Observation.class)).toList();if(values.size()>limit+1)throw unavailable();var items=List.copyOf(values.subList(0,Math.min(limit,values.size())));var last=items.isEmpty()?null:items.getLast();String next=values.size()>limit?ApplicationCursor.encode(scope,fromMillis+":"+toMillis+":"+last.observedAt().toEpochMilli()+":"+last.id()):null;return new Models.Page<>(items,next,Instant.now(),"CLICKHOUSE","CONNECTED");}),
            queryId->Mono.empty(),(queryId,error)->kill(queryId),this::kill).onErrorMap(e->e instanceof ApiException?e:unavailable());
    }
    /** Read exactly the latest sampling batch; cumulative UInt64 counters never enter a rate sum. */
    public Mono<Summary> summary(String org,String device,Integer index,String direction,String q,int limit,int freshnessSeconds){
        String scope="organization_id={org:String} AND device_id={device:String}";
        // The newest batch timestamp is independent of q, so a missing application
        // cannot pull an older sample into an otherwise current distribution.
        String sql="WITH latest AS (SELECT observed_at FROM noeriva.application_observations WHERE "+scope+" ORDER BY observed_at DESC LIMIT 1) SELECT payload FROM noeriva.application_observations FINAL WHERE "+scope+" AND observed_at IN (SELECT observed_at FROM latest)"+(index==null?"":" AND interface_index={index:UInt32}")+(direction.isEmpty()?"":" AND direction={direction:String}")+(q.isEmpty()?"":" AND positionCaseInsensitiveUTF8(application,{q:String})>0")+" ORDER BY observation_id LIMIT 513 FORMAT JSONEachRow";
        // CH 26.3 local THROW evaluates whole-range estimates for latest-batch
        // ordered reads. Unlimited local BREAK cannot truncate a result; leaf
        // THROW retains the same actual 1m-row / 64 MiB read budget.
        return Mono.usingWhen(Mono.fromSupplier(()->"noeriva-app-summary-"+UUID.randomUUID()),id->client.post().uri(b->{
            b.path("/").queryParam("query_id",id).queryParam("param_org",org).queryParam("param_device",device).queryParam("max_execution_time",5).queryParam("max_rows_to_read",0).queryParam("max_bytes_to_read",0).queryParam("max_rows_to_read_leaf",1000000).queryParam("max_bytes_to_read_leaf",67108864).queryParam("read_overflow_mode_leaf","throw").queryParam("max_block_size",256).queryParam("max_memory_usage",134217728).queryParam("max_result_rows",513).queryParam("max_result_bytes",4194304).queryParam("max_threads",2).queryParam("result_overflow_mode","throw").queryParam("read_overflow_mode","break").queryParam("timeout_overflow_mode","throw").queryParam("wait_end_of_query",1);
            if(index!=null)b.queryParam("param_index",index);if(!direction.isEmpty())b.queryParam("param_direction",direction);if(!q.isEmpty())b.queryParam("param_q",q);return b.build();})
            .bodyValue(sql).retrieve().bodyToMono(String.class).defaultIfEmpty("").timeout(Duration.ofSeconds(7)).map(body->{
                var rows=body.lines().filter(line->!line.isBlank()).map(line->json.readValue(json.readTree(line).get("payload").asText(),Observation.class)).toList();
                if(rows.size()>512)throw unavailable();return summarize(device,rows,limit,freshnessSeconds,Instant.now());
            }),id->Mono.empty(),(id,error)->kill(id),this::kill).onErrorMap(e->e instanceof ApiException?e:unavailable());
    }
    static Summary summarize(String device,List<Observation> rows,int limit,int freshnessSeconds,Instant now){
        var flags=new TreeSet<String>();rows.forEach(row->flags.addAll(row.qualityFlags()));
        List<Integer> indices=rows.stream().map(Observation::interfaceIndex).distinct().sorted().toList();
        if(indices.size()>1)flags.add("INTERFACE_OVERLAP_POSSIBLE");flags.add("LATEST_SAMPLE_DISTRIBUTION");
        Instant at=rows.stream().map(Observation::observedAt).max(Comparator.naturalOrder()).orElse(null);
        if(at!=null&&rows.stream().anyMatch(row->!at.equals(row.observedAt())))throw unavailable();
        var grouped=rows.stream().collect(java.util.stream.Collectors.groupingBy(row->row.application()+"\u0000"+row.direction()));
        var items=grouped.values().stream().map(group->{
            var itemFlags=new TreeSet<String>();group.forEach(row->itemFlags.addAll(row.qualityFlags()));
            var interfaces=group.stream().map(Observation::interfaceIndex).distinct().sorted().toList();if(interfaces.size()>1)itemFlags.add("INTERFACE_OVERLAP_POSSIBLE");
            Double derived=sumComplete(group.stream().map(Observation::derivedBps).toList()),reported=sumComplete(group.stream().map(Observation::reportedBps).toList());
            if(derived==null)itemFlags.add("RATE_INCOMPLETE");
            return new SummaryItem(group.getFirst().application(),group.getFirst().direction(),interfaces,derived,reported,group.size(),List.copyOf(itemFlags));
        }).sorted(Comparator.comparing(SummaryItem::derivedBps,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(SummaryItem::application).thenComparing(SummaryItem::direction)).toList();
        return new Summary(device,now,at,"CLICKHOUSE","CONNECTED",at==null?"MISSING":at.isBefore(now.minusSeconds(freshnessSeconds))?"STALE":"FRESH","COUNTER_DELTA_PER_SECOND",indices,rows.size(),items.size(),items.size()>limit,List.copyOf(flags),items.stream().limit(limit).toList());
    }
    private static Double sumComplete(List<Double> values){if(values.stream().anyMatch(v->v==null||!Double.isFinite(v)||v<0))return null;double sum=values.stream().mapToDouble(Double::doubleValue).sum();return Double.isFinite(sum)?sum:null;}
    private Mono<Void> kill(String id){return client.post().uri(b->b.path("/").queryParam("param_id",id).queryParam("max_execution_time",2).build()).bodyValue("KILL QUERY WHERE query_id={id:String} ASYNC").retrieve().toBodilessEntity().timeout(Duration.ofSeconds(2)).then().onErrorComplete();}
    private static ApiException unavailable(){return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"APPLICATION_HISTORY_UNAVAILABLE","Application history is unavailable or exceeded its bounded query budget");}
}
