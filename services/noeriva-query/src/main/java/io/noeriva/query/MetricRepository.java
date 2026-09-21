package io.noeriva.query;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;
public interface MetricRepository {
    Mono<Result> loadMetrics(String organizationId,String deviceId,MetricDefinition metric,Instant from,Instant to,int points);
    /** Full bounded observations for interval statistics, not query-range decimation. */
    default Mono<Result> loadSamples(String org,String device,MetricDefinition metric,Instant from,Instant to) {
        return loadMetrics(org,device,metric,from,to,2000);
    }
    record Result(List<MetricPoint> points,String source,long revision,List<String> qualityFlags) {}
}
