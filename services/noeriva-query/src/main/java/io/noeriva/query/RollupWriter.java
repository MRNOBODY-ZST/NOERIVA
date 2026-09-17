package io.noeriva.query;
import java.util.List;
import reactor.core.publisher.Mono;
public interface RollupWriter {
    Mono<Void> write(RollupKey key,List<RollupBucket> fullSnapshots);
}
