package io.noeriva.control;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import static io.noeriva.control.Models.*;

/** Typed low-volume domain event sink. Numerical samples never enter this table. */
@Component @Profile("production")
public class HistoryStore {
    private final WebClient client;
    private final JsonMapper json;
    public HistoryStore(WebClient.Builder builder,Environment env,JsonMapper json) {
        this.json=json;
        this.client=builder.clone().baseUrl(env.getProperty("NOERIVA_CLICKHOUSE_URL","http://localhost:18123"))
            .defaultHeaders(h->h.setBasicAuth(env.getProperty("NOERIVA_CLICKHOUSE_USERNAME","noeriva"),env.getRequiredProperty("NOERIVA_CLICKHOUSE_PASSWORD")))
            .codecs(c->c.defaultCodecs().maxInMemorySize(2*1024*1024)).build();
    }
    public Mono<Void> append(String org,Observation o,Instant acceptedAt) {
        var row=new LinkedHashMap<String,Object>();
        row.put("organization_id",org);row.put("event_id",o.id());row.put("device_id",o.deviceId());row.put("observed_at",o.observedAt().toString());row.put("ingested_at",acceptedAt.toString());
        row.put("kind",o.kind());row.put("severity",o.health());row.put("message",Objects.toString(o.message(),""));row.put("source",o.sourceId());row.put("revision",o.sequence()+1);
        String token=UUID.nameUUIDFromBytes((org+"/"+o.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return client.post().uri(b->b.path("/").queryParam("database","noeriva").queryParam("async_insert",0).queryParam("wait_end_of_query",1).queryParam("buffer_size",1048576).queryParam("date_time_input_format","best_effort").queryParam("insert_deduplication_token",token).build())
            .bodyValue("INSERT INTO control_events FORMAT JSONEachRow\n"+json.writeValueAsString(row)).retrieve().bodyToMono(String.class).defaultIfEmpty("").timeout(Duration.ofSeconds(10))
            .flatMap(body->body.isBlank()?Mono.empty():Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SINK_REJECTED","ClickHouse did not confirm the insert")));
    }
    public Flux<Event> events(String org,String device,Instant from,Instant to,String cursor,int limit) {
        String queryId=UUID.randomUUID().toString();
        var after=EventCursor.decode(cursor);
        // Complete tuple selected together; retries cannot mix fields from different versions.
        String sql="SELECT event_id,tupleElement(v,1) device_id,tupleElement(v,2) kind,tupleElement(v,3) severity,tupleElement(v,4) message,tupleElement(v,5) observed_at,tupleElement(v,6) ingested_at,tupleElement(v,7) source FROM (SELECT event_id,argMax(tuple(device_id,kind,severity,message,observed_at,ingested_at,source),revision) v FROM control_events WHERE organization_id={org:String} AND observed_at>=parseDateTime64BestEffort({from:String}) AND observed_at<parseDateTime64BestEffort({to:String})"+(!device.isEmpty()?" AND device_id={device:String}":"")+" GROUP BY event_id)"+(after!=null?" WHERE (observed_at,event_id)<(parseDateTime64BestEffort({cursorTime:String}),{cursorId:String})":"")+" ORDER BY observed_at DESC,event_id DESC LIMIT "+limit+" FORMAT JSONEachRow";
        return client.post().uri(b->{b.path("/").queryParam("database","noeriva").queryParam("query_id",queryId).queryParam("param_org",org).queryParam("param_from",from.toString()).queryParam("param_to",to.toString())
                .queryParam("max_execution_time",5).queryParam("max_rows_to_read",1000000).queryParam("max_bytes_to_read",67108864).queryParam("max_memory_usage",134217728).queryParam("max_result_bytes",2097152).queryParam("max_threads",2).queryParam("result_overflow_mode","throw");
            if(!device.isEmpty())b.queryParam("param_device",device);if(after!=null)b.queryParam("param_cursorTime",after.at().toString()).queryParam("param_cursorId",after.id());return b.build();})
            .bodyValue(sql).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(7))
            .flatMapMany(body->Flux.fromStream(body.lines().filter(line->!line.isBlank()).map(line->{var n=json.readTree(line);return new Event(n.get("event_id").asText(),n.get("device_id").asText(),n.get("kind").asText(),n.get("severity").asText(),n.get("message").asText(),parse(n.get("observed_at").asText()),parse(n.get("ingested_at").asText()),n.get("source").asText(),List.of());})))
            .onErrorMap(e->new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"HISTORY_UNAVAILABLE","Structured history is unavailable; the result is not complete"))
            .doOnCancel(()->kill(queryId));
    }
    private static Instant parse(String value){return value.endsWith("Z")?Instant.parse(value):LocalDateTime.parse(value.replace(' ','T')).toInstant(ZoneOffset.UTC);}
    private void kill(String id){client.post().uri("/?database=noeriva").bodyValue("KILL QUERY WHERE query_id='"+id+"' ASYNC").retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(2)).onErrorComplete().subscribe();}
}
