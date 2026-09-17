package io.noeriva.query;

import static org.junit.jupiter.api.Assertions.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RollupWorkerTest {
    Instant from=Instant.parse("2026-09-06T00:00:00Z"),to=from.plusSeconds(300);
    private static class State implements RollupStateStore {
        AtomicLong revision=new AtomicLong();Instant complete,processed;double coverage;int checkpoints;
        public Mono<Long> nextRevision(RollupKey key) {return Mono.fromSupplier(revision::incrementAndGet);}
        public Mono<Void> checkpoint(RollupKey key,Instant start,Instant end,Instant complete,long revision,double coverage) {
            return Mono.fromRunnable(()->{this.complete=complete;processed=end;this.coverage=coverage;checkpoints++;});
        }
    }
    private RawCounterRepository source(boolean gap) {
        return (key,start,end)->{
            assertEquals(from.minusSeconds(90),start);assertEquals(to.plusSeconds(90),end);
            var samples=new ArrayList<CounterSample>();
            for(int second=0;second<=300;second+=15) {
                if(gap&&second>60&&second<180)continue;
                samples.add(new CounterSample(from.plusSeconds(second),BigInteger.valueOf(second*100L),"boot-1",false));
            }
            return Mono.just(new RawCounterRepository.Result(samples,Set.of("FLOATING_POINT_SOURCE")));
        };
    }
    @Test void writesReplacementBeforeCheckpointAndPreservesSourcePrecisionFlags() {
        State state=new State();var written=new ArrayList<RollupBucket>();
        RollupWriter sink=(key,buckets)->Mono.fromRunnable(()->{assertEquals(0,state.checkpoints);written.addAll(buckets);});
        var worker=new RollupWorker(source(false),sink,state,Clock.fixed(to.plusSeconds(100),ZoneOffset.UTC));
        StepVerifier.create(worker.recompute("org-a","device-a","eth0","primary",from,to,"rx")).expectNext(1).verifyComplete();
        assertEquals(30000,written.getFirst().validCounterDelta().intValueExact());assertEquals(1,written.getFirst().revision());
        assertTrue(written.getFirst().qualityFlags().contains("FLOATING_POINT_SOURCE"));assertEquals(to,state.complete);assertEquals(1,state.coverage);
    }
    @Test void missingCoverageCannotAdvanceCompletenessAndSinkFailureCannotAdvanceCheckpoint() {
        State state=new State();
        var worker=new RollupWorker(source(true),(key,b)->Mono.empty(),state,Clock.fixed(to.plusSeconds(100),ZoneOffset.UTC));
        StepVerifier.create(worker.recompute("org-a","device-a","eth0","primary",from,to,"rx")).expectNext(1).verifyComplete();
        assertNull(state.complete);assertEquals(to,state.processed);assertTrue(state.coverage<1);
        State failed=new State();
        var broken=new RollupWorker(source(false),(key,b)->Mono.error(new ProviderUnavailableException("CLICKHOUSE","failed")),failed,Clock.fixed(to.plusSeconds(100),ZoneOffset.UTC));
        StepVerifier.create(broken.recompute("org-a","device-a","eth0","primary",from,to,"rx")).expectError(ProviderUnavailableException.class).verify();
        assertEquals(0,failed.checkpoints);
    }
    @Test void emptyInvisibleRawDataDoesNotCreateFalseMissingRevisionOrCheckpoint() {
        State state=new State();
        var worker=new RollupWorker((k,f,t)->Mono.just(new RawCounterRepository.Result(List.of(),Set.of())),(k,b)->{fail("Empty data must not overwrite existing history");return Mono.empty();},state,Clock.fixed(to.plusSeconds(100),ZoneOffset.UTC));
        StepVerifier.create(worker.recompute("org-a","device-a","eth0","primary",from,to,"rx")).expectError(ProviderUnavailableException.class).verify();
        assertEquals(0,state.checkpoints);
    }
    @Test void refusesUnalignedOrOversizedRebuildWindows() {
        State state=new State();
        var worker=new RollupWorker(source(false),(k,b)->Mono.empty(),state,Clock.fixed(to.plusSeconds(100),ZoneOffset.UTC));
        StepVerifier.create(worker.recompute("org-a","device-a","eth0","primary",from.plusSeconds(1),to,"rx")).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(worker.recompute("org-a","device-a","eth0","primary",from.minusSeconds(2*86400),to,"rx")).expectError(IllegalArgumentException.class).verify();
    }
}
