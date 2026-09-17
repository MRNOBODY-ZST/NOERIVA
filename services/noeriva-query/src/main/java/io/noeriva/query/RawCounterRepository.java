package io.noeriva.query;
import java.time.Instant;
import java.util.*;
import reactor.core.publisher.Mono;
public interface RawCounterRepository {
    Mono<Result> loadCounters(RollupKey key,Instant from,Instant to);
    record Result(List<CounterSample> samples,Set<String> qualityFlags) {}
}
