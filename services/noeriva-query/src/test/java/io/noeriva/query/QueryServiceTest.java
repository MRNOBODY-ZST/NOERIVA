package io.noeriva.query;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class QueryServiceTest {
    private final Instant now=Instant.parse("2026-09-06T12:30:00Z");
    private QueryService service(RollupRepository r,MetricRepository m) {
        return new QueryService(r,m,Clock.fixed(now,ZoneOffset.UTC));
    }
    private RollupRepository noRollups=(org,device,component,direction,from,to)->Mono.just(new RollupRepository.Result(List.of(),"CLICKHOUSE"));
    private MetricRepository noMetrics=(org,device,metric,from,to,points)->Mono.just(new MetricRepository.Result(List.of(),"VICTORIAMETRICS",0,List.of()));
    @Test void rejectsUnknownMetricAndOversizedRangesBeforeProviderAccess() {
        var s=service(noRollups,(o,d,m,f,t,p)->{fail("Provider accessed for invalid request");return Mono.empty();});
        StepVerifier.create(s.metrics("org-a","device-a","arbitrary_promql",now.minusSeconds(3600),now,100)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(s.metrics("org-a","device-a","cpu_percent",now.minusSeconds(8*86400),now,100)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(s.metrics("org-a","device-a","cpu_percent",now.minusSeconds(3600),now,10000)).expectError(IllegalArgumentException.class).verify();
    }
    @Test void missingMetricDataRemainsMissingAndCarriesItsProvider() {
        StepVerifier.create(service(noRollups,noMetrics).metrics("org-a","device-a","cpu_percent",now.minusSeconds(3600),now,100))
            .assertNext(r->{assertEquals(0,r.coverage());assertNull(r.asOf());assertEquals("MISSING",r.sourceFreshness());assertEquals("VICTORIAMETRICS",r.source());assertTrue(r.points().isEmpty());}).verifyComplete();
    }
    @Test void providerFailureCannotBecomeAnEmptySuccess() {
        RollupRepository unavailable=(o,d,i,r,f,t)->Mono.error(new ProviderUnavailableException("CLICKHOUSE","Unavailable"));
        StepVerifier.create(service(unavailable,noMetrics).heatmap("org-a","device-a","eth0","UTC","rx")).expectError(ProviderUnavailableException.class).verify();
    }
    @Test void explicitTimezoneAndDirectionAreRequired() {
        var s=service(noRollups,noMetrics);
        StepVerifier.create(s.heatmap("org-a","device-a","eth0","not/a-zone","rx")).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(s.heatmap("org-a","device-a","eth0","UTC","rx+tx")).expectError(IllegalArgumentException.class).verify();
    }
    @Test void passesAuthorizedScopeAndCalendarWindowToRollupSource() {
        RollupRepository source=(o,d,i,r,f,t)->{
            assertEquals("org-a",o);assertEquals("device-a",d);assertEquals("eth0",i);
            assertEquals(Instant.parse("2026-08-30T18:30:00Z"),f);assertEquals(now,t);
            return Mono.just(new RollupRepository.Result(List.of(),"CLICKHOUSE"));
        };
        StepVerifier.create(service(source,noMetrics).heatmap("org-a","device-a","eth0","Asia/Kolkata","rx"))
            .assertNext(r->assertEquals(168,r.cells().size())).verifyComplete();
    }
}
