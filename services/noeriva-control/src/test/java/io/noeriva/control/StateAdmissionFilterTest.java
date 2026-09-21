package io.noeriva.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;

class StateAdmissionFilterTest {
    private MockServerWebExchange summary(){return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/applications/summary?deviceId=synthetic&from=2026-01-01T00:00:00Z"));}

    @Test void applicationWindowCanCompleteAfterTheOrdinaryTwelveSecondDeadline(){
        var filter=new StateAdmissionFilter();var exchange=summary();
        StepVerifier.withVirtualTime(()->filter.filter(exchange,e->Mono.delay(Duration.ofSeconds(22)).then(Mono.fromRunnable(()->e.getResponse().setStatusCode(HttpStatus.OK)))))
            .expectSubscription().expectNoEvent(Duration.ofSeconds(21)).thenAwait(Duration.ofSeconds(1)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void applicationDeadlineCancelsWorkReturns503AndReleasesItsLease(){
        var filter=new StateAdmissionFilter();
        // A leaked two-slot admission would reject the third request.
        for(int i=0;i<3;i++){
            var exchange=summary();var cancelled=new AtomicBoolean();
            StepVerifier.withVirtualTime(()->filter.filter(exchange,e->Mono.<Void>never().doOnCancel(()->cancelled.set(true))))
                .expectSubscription().expectNoEvent(Duration.ofSeconds(49)).thenAwait(Duration.ofSeconds(1)).verifyComplete();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(cancelled).isTrue();
        }
    }

    @Test void otherRoutesAndMethodsKeepTheTwelveSecondDeadline(){
        var filter=new StateAdmissionFilter();
        var requests=java.util.List.of(MockServerHttpRequest.get("/api/v1/assets"),MockServerHttpRequest.get("/api/v1/applications/observations"),
            MockServerHttpRequest.get("/api/v1/applications/summary/extra"),MockServerHttpRequest.post("/api/v1/applications/summary"));
        for(var request:requests){
            var exchange=MockServerWebExchange.from(request);var cancelled=new AtomicBoolean();
            StepVerifier.withVirtualTime(()->filter.filter(exchange,e->Mono.<Void>never().doOnCancel(()->cancelled.set(true))))
                .expectSubscription().expectNoEvent(Duration.ofSeconds(11)).thenAwait(Duration.ofSeconds(1)).verifyComplete();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(cancelled).isTrue();
        }
    }

    @Test void applicationCapacityIsTwoIndependentOfSixtyFourStateRequestsAndCancellationReleases(){
        var filter=new StateAdmissionFilter();var active=new ArrayList<Disposable>();var invoked=new AtomicInteger();
        try{
            for(int i=0;i<2;i++)active.add(filter.filter(summary(),e->{invoked.incrementAndGet();return Mono.never();}).subscribe());
            var rejected=summary();filter.filter(rejected,e->{throw new AssertionError("Rejected work must not start");}).block();
            assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(rejected.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
            for(int i=0;i<64;i++)active.add(filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/assets")),e->{invoked.incrementAndGet();return Mono.never();}).subscribe());
            assertThat(invoked).hasValue(66);
            var stateRejected=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/assets"));
            filter.filter(stateRejected,e->{throw new AssertionError("Rejected work must not start");}).block();
            assertThat(stateRejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            active.getFirst().dispose();
            active.add(filter.filter(summary(),e->{invoked.incrementAndGet();return Mono.never();}).subscribe());
            assertThat(invoked).hasValue(67);
        }finally{active.forEach(Disposable::dispose);}
    }

    @Test void synchronousAndReactiveFailuresReleaseApplicationCapacity(){
        var filter=new StateAdmissionFilter();
        for(int i=0;i<3;i++){
            StepVerifier.create(filter.filter(summary(),e->{throw new IllegalArgumentException("synthetic");})).expectError(IllegalArgumentException.class).verify();
            StepVerifier.create(filter.filter(summary(),e->Mono.error(new IllegalStateException("synthetic")))).expectError(IllegalStateException.class).verify();
        }
    }

    @Test void timeoutAfterResponseCommitPropagatesWithoutChangingTheResponse(){
        var filter=new StateAdmissionFilter();var exchange=summary();
        StepVerifier.withVirtualTime(()->filter.filter(exchange,e->{e.getResponse().setStatusCode(HttpStatus.OK);return e.getResponse().setComplete().then(Mono.never());}))
            .expectSubscription().thenAwait(Duration.ofSeconds(50)).expectError(TimeoutException.class).verify();
        assertThat(exchange.getResponse().isCommitted()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
