package io.noeriva.query;
import java.time.Instant;
import reactor.core.publisher.Mono;
/** Implemented by the authoritative control store. Global revisions order writes across workers/restarts. */
public interface RollupStateStore {
    Mono<Long> nextRevision(RollupKey key);
    /** Called only after synchronous persistence. completeThrough is null when contiguous source coverage is unproven. */
    Mono<Void> checkpoint(RollupKey key,Instant processedFrom,Instant processedThrough,Instant completeThrough,long revision,double coverage);
}
