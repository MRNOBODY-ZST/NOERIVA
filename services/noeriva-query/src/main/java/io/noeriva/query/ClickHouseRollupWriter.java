package io.noeriva.query;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;

@Component
@Profile("!demo")
public final class ClickHouseRollupWriter implements RollupWriter {
    private final WebClient client;
    private final JsonMapper json=JsonMapper.builder().build();
    private final Scheduler encoder=Schedulers.newBoundedElastic(2,8,"noeriva-rollup-encode");
    @Autowired
    public ClickHouseRollupWriter(
            @Value("${noeriva.query.clickhouse-url:${NOERIVA_CLICKHOUSE_URL:http://localhost:8123}}") String url,
            @Value("${noeriva.query.clickhouse-user:${NOERIVA_CLICKHOUSE_USERNAME:noeriva}}") String user,
            @Value("${noeriva.query.clickhouse-password:${NOERIVA_CLICKHOUSE_PASSWORD:}}") String password) {
        this(QueryHttpClient.create(url,user,password));
    }
    ClickHouseRollupWriter(WebClient client) {this.client=client;}
    public Mono<Void> write(RollupKey key,List<RollupBucket> snapshots) {
        return Mono.fromCallable(()->encode(key,snapshots)).subscribeOn(encoder).flatMap(body->
            client.post().uri(b->b.path("/").queryParam("async_insert",0).queryParam("wait_end_of_query",1)
                    .queryParam("date_time_input_format","best_effort").queryParam("max_execution_time",5)
                    .queryParam("max_memory_usage",67108864).queryParam("query_id","noeriva-rollup-"+UUID.randomUUID()).build())
                .contentType(MediaType.TEXT_PLAIN).bodyValue(body).retrieve().bodyToMono(String.class).defaultIfEmpty("")
                .timeout(Duration.ofSeconds(6)).flatMap(response->{
                    // ClickHouse can emit a late exception after HTTP 200. A plain INSERT succeeds with an empty body.
                    if(!response.isBlank())return Mono.<Void>error(new ProviderUnavailableException("CLICKHOUSE","Insert returned an unexpected body; acknowledgement is not verified"));
                    return Mono.<Void>empty();
                }))
            .onErrorMap(e->e instanceof ProviderUnavailableException?e:new ProviderUnavailableException("CLICKHOUSE","Full rollup snapshot insert failed or acknowledgement is unknown",e));
    }
    private String encode(RollupKey key,List<RollupBucket> snapshots) {
        if(snapshots.isEmpty()||snapshots.size()>288)throw new IllegalArgumentException("One insert contains 1–288 replacement buckets");
        StringBuilder body=new StringBuilder("INSERT INTO noeriva.metric_rollups FORMAT JSONEachRow\n");
        for(var bucket:snapshots) {
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("organization_id",key.organizationId());row.put("device_id",key.deviceId());row.put("component_id",key.interfaceId());
            row.put("metric_id","bandwidth");row.put("direction",key.direction());row.put("source_id",key.sourceId());
            row.put("bucket_start_utc",bucket.start().toString());row.put("bucket_end_utc",bucket.end().toString());
            row.put("resolution_seconds",300);row.put("algorithm_version",1);row.put("revision",bucket.revision());
            row.put("generated_at",bucket.generatedAt().toString());row.put("valid_duration_seconds",bucket.validDurationSeconds());
            row.put("valid_counter_delta",bucket.validCounterDelta().setScale(6,java.math.RoundingMode.HALF_EVEN).toPlainString());
            row.put("sample_count",bucket.sampleCount());row.put("quality_flags",bucket.qualityFlags());
            row.put("last_observed_utc",bucket.lastObserved()==null?null:bucket.lastObserved().toString());
            body.append(json.writeValueAsString(row)).append('\n');
        }
        return body.toString();
    }
    @PreDestroy public void close() {encoder.dispose();}
}
