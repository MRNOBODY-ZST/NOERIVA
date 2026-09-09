package io.noeriva.query;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@Profile("!demo")
public final class ClickHouseRollupRepository implements RollupRepository {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ClickHouseRollupRepository.class);
    private final WebClient client;
    private final String sourceId;
    @Autowired
    public ClickHouseRollupRepository(
            @Value("${noeriva.query.clickhouse-url:${NOERIVA_CLICKHOUSE_URL:http://localhost:8123}}") String url,
            @Value("${noeriva.query.clickhouse-user:${NOERIVA_CLICKHOUSE_USERNAME:noeriva}}") String user,
            @Value("${noeriva.query.clickhouse-password:${NOERIVA_CLICKHOUSE_PASSWORD:}}") String password,
            @Value("${noeriva.query.rollup-source:${NOERIVA_ROLLUP_SOURCE_ID:primary}}") String sourceId) {
        this(QueryHttpClient.create(url,user,password),sourceId);
    }
    ClickHouseRollupRepository(WebClient client,String sourceId) {this.client=client;this.sourceId=sourceId;QueryService.validateId(sourceId);}

    public Mono<Result> loadRollups(String org,String device,String component,String direction,Instant from,Instant to) {
        return loadRollups(org,device,component,sourceId,direction,from,to);
    }
    @Override public Mono<Result> loadRollups(String org,String device,String component,String requestedSource,String direction,Instant from,Instant to) {
        QueryService.validateId(requestedSource);
        return Mono.usingWhen(Mono.fromSupplier(()->"noeriva-chart-"+UUID.randomUUID()),
            queryId->query(queryId,org,device,component,requestedSource,direction,from,to),
            queryId->Mono.empty(),(queryId,error)->kill(queryId),this::kill)
            .onErrorMap(e->e instanceof ProviderUnavailableException?e:new ProviderUnavailableException("CLICKHOUSE","Rollup query failed or exceeded its storage budget",e));
    }
    private Mono<Result> query(String id,String org,String device,String component,String requestedSource,String direction,Instant from,Instant to) {
        String sql="""
            SELECT toUnixTimestamp64Milli(bucket_start_utc) AS startMillis,
              toUnixTimestamp64Milli(snapshot.1) AS endMillis,
              snapshot.2 AS validDurationSeconds, snapshot.3 AS validCounterDelta,
              snapshot.4 AS sampleCount, max_revision AS revision,
              toUnixTimestamp64Milli(snapshot.5) AS generatedMillis, snapshot.6 AS qualityFlags,
              toUnixTimestamp64Milli(snapshot.7) AS observedMillis
            FROM (
              SELECT bucket_start_utc,
                argMax(tuple(bucket_end_utc, valid_duration_seconds, valid_counter_delta,
                  sample_count, generated_at, quality_flags, last_observed_utc), revision) AS snapshot,
                max(revision) AS max_revision
              FROM noeriva.metric_rollups
              WHERE organization_id={organization:String} AND device_id={device:String}
                AND component_id={component:String} AND metric_id='bandwidth'
                AND direction={direction:String} AND source_id={source:String}
                AND algorithm_version=1 AND resolution_seconds=300
                AND bucket_start_utc>=fromUnixTimestamp64Milli({from:Int64})
                AND bucket_start_utc<fromUnixTimestamp64Milli({to:Int64})
              GROUP BY bucket_start_utc
            ) ORDER BY bucket_start_utc LIMIT 5000 FORMAT JSON
            """;
        return client.post().uri(b->b.path("/").queryParam("query_id",id)
                .queryParam("param_organization",org).queryParam("param_device",device)
                .queryParam("param_component",component).queryParam("param_direction",direction)
                .queryParam("param_source",requestedSource).queryParam("param_from",from.toEpochMilli())
                .queryParam("param_to",to.toEpochMilli()).queryParam("max_execution_time",5)
                .queryParam("max_rows_to_read",50000).queryParam("max_bytes_to_read",33554432)
                .queryParam("max_memory_usage",67108864).queryParam("max_result_rows",5000)
                .queryParam("max_result_bytes",2097152).queryParam("result_overflow_mode","throw")
                .queryParam("read_overflow_mode","throw").queryParam("timeout_overflow_mode","throw")
                .queryParam("wait_end_of_query",1).build())
            .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON).bodyValue(sql)
            .retrieve().bodyToMono(Payload.class).timeout(Duration.ofSeconds(6))
            .switchIfEmpty(Mono.error(new ProviderUnavailableException("CLICKHOUSE","Missing provider response")))
            .map(response->{
                if(response.data()==null||response.data().size()>5000) throw new ProviderUnavailableException("CLICKHOUSE","Invalid or excessive rollup response");
                List<RollupBucket> buckets=response.data().stream().map(row->new RollupBucket(
                    Instant.ofEpochMilli(row.startMillis()),Instant.ofEpochMilli(row.endMillis()),row.validDurationSeconds(),
                    row.validCounterDelta(),row.sampleCount(),row.revision(),Instant.ofEpochMilli(row.generatedMillis()),
                    Set.copyOf(row.qualityFlags()),row.observedMillis()==null?null:Instant.ofEpochMilli(row.observedMillis()))).toList();
                return new Result(buckets,"CLICKHOUSE:"+requestedSource);
            });
    }
    private Mono<Void> kill(String id) {
        // HTTP disconnect alone does not cancel ClickHouse. Cleanup issues an explicit query-id cancellation.
        return client.post().uri(b->b.path("/").queryParam("param_queryId",id).queryParam("max_execution_time",2).build())
            .contentType(MediaType.TEXT_PLAIN).bodyValue("KILL QUERY WHERE query_id = {queryId:String} ASYNC")
            .retrieve().toBodilessEntity().timeout(Duration.ofSeconds(2)).then()
            .onErrorResume(error->{LOG.warn("ClickHouse cancellation could not be confirmed; server deadline remains enforced");return Mono.empty();});
    }
    record Payload(List<Row> data) {}
    record Row(long startMillis,long endMillis,double validDurationSeconds,BigDecimal validCounterDelta,
               int sampleCount,long revision,long generatedMillis,List<String> qualityFlags,Long observedMillis) {}
}
