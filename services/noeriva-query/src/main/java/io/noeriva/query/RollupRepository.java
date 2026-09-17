package io.noeriva.query;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;
public interface RollupRepository {
    Mono<Result> loadRollups(String organizationId,String deviceId,String interfaceId,String direction,Instant from,Instant to);
    /** Legacy providers remain primary-only; never silently substitute a different source. */
    default Mono<Result> loadRollups(String organizationId,String deviceId,String interfaceId,String sourceId,String direction,Instant from,Instant to){
        return "primary".equals(sourceId)?loadRollups(organizationId,deviceId,interfaceId,direction,from,to):
            Mono.error(new ProviderUnavailableException("ROLLUP","This rollup provider does not support the requested interface source"));
    }
    record Result(List<RollupBucket> buckets,String source) {}
}
