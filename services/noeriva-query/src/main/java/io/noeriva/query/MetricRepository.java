package io.noeriva.query;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;
public interface MetricRepository {
    Mono<Result> loadMetrics(String organizationId,String deviceId,MetricDefinition metric,Instant from,Instant to,int points);
    record Result(List<MetricPoint> points,String source,long revision,List<String> qualityFlags) {}
}
