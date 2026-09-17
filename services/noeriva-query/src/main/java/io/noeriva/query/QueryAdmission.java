package io.noeriva.query;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.*;
import java.util.concurrent.Semaphore;
import reactor.core.publisher.Mono;
public final class QueryAdmission {
    private final Map<QueryLane,Semaphore> permits=new EnumMap<>(QueryLane.class);
    private final int capacity;
    private final Duration deadline;
    public QueryAdmission(int permitsPerLane, Duration deadline) {
        if (permitsPerLane<1 || deadline.isZero() || deadline.isNegative()) throw new IllegalArgumentException("Invalid admission limits");
        capacity=permitsPerLane;this.deadline=deadline;
        for(var lane:QueryLane.values()) permits.put(lane,new Semaphore(permitsPerLane));
    }
    public <T> Mono<T> execute(QueryLane lane, Supplier<Mono<T>> operation) {
        // Eager cleanup happens before terminal signals. Cancellation also closes the lease exactly once.
        return Mono.using(()->acquire(lane),lease->Mono.defer(operation).timeout(deadline),Lease::close,true);
    }
    private Lease acquire(QueryLane lane) {
        var semaphore=permits.get(Objects.requireNonNull(lane));
        if (!semaphore.tryAcquire()) throw new QueryRejectedException("Query capacity exhausted for "+lane);
        return new Lease(semaphore);
    }
    public int active(QueryLane lane) { return capacity-permits.get(lane).availablePermits(); }
    private static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        Lease(Semaphore semaphore) {this.semaphore=semaphore;}
        public void close() {if(closed.compareAndSet(false,true)) semaphore.release();}
    }
}
