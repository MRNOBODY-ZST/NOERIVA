package io.noeriva.query;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class QueryAdmissionTest {
    @Test void cancellationReleasesPermitAndDoesNotConsumeOtherLane() {
        var a=new QueryAdmission(1,Duration.ofSeconds(2));
        var first=a.execute(QueryLane.INTERACTIVE_CHART,()->Mono.never()).subscribe();
        StepVerifier.create(a.execute(QueryLane.INTERACTIVE_CHART,()->Mono.just("denied"))).expectError(QueryRejectedException.class).verify();
        StepVerifier.create(a.execute(QueryLane.INTERACTIVE_STATE,()->Mono.just("ok"))).expectNext("ok").verifyComplete();
        first.dispose();
        StepVerifier.create(a.execute(QueryLane.INTERACTIVE_CHART,()->Mono.just("released"))).expectNext("released").verifyComplete();
        assertEquals(0,a.active(QueryLane.INTERACTIVE_CHART));
    }
    @Test void thrownSupplierAndReactiveFailureBothReleasePermits() {
        var a=new QueryAdmission(1,Duration.ofSeconds(2));
        StepVerifier.create(a.execute(QueryLane.INVESTIGATION,()->{throw new IllegalStateException("failure");})).expectError(IllegalStateException.class).verify();
        StepVerifier.create(a.execute(QueryLane.INVESTIGATION,()->Mono.error(new IllegalArgumentException()))).expectError(IllegalArgumentException.class).verify();
        assertEquals(0,a.active(QueryLane.INVESTIGATION));
    }
    @Test void timeoutCancelsActualSubscribedWorkAndReleasesCapacity() {
        var a=new QueryAdmission(1,Duration.ofMillis(20));
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        StepVerifier.create(a.execute(QueryLane.BATCH_EXPORT,()->Mono.never().doOnCancel(()->cancelled.set(true)))).expectError(java.util.concurrent.TimeoutException.class).verify();
        assertTrue(cancelled.get());
        assertEquals(0,a.active(QueryLane.BATCH_EXPORT));
    }
    @Test void unSubscribedRequestDoesNotReserveCapacity() {
        var a=new QueryAdmission(1,Duration.ofSeconds(2));
        a.execute(QueryLane.REPLAY_REBUILD,Mono::never);
        assertEquals(0,a.active(QueryLane.REPLAY_REBUILD));
    }
}
