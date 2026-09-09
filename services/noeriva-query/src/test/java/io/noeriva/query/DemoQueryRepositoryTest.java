package io.noeriva.query;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
class DemoQueryRepositoryTest {
    @Test void syntheticSevenDayWindowHasObservableZeroGapAndFiniteRates() {
        var repository=new DemoQueryRepository();
        Instant now=Instant.parse("2026-09-06T12:30:00Z");
        var service=new QueryService(repository,repository,Clock.fixed(now,ZoneOffset.UTC));
        try {
            StepVerifier.create(service.heatmap("org-a","device-a","eth0","UTC","rx")).assertNext(r->{
                assertEquals("SIMULATED",r.source());assertEquals(168,r.cells().size());
                assertTrue(r.cells().stream().anyMatch(c->c.state().equals("OBSERVED_ZERO")));
                assertTrue(r.cells().stream().anyMatch(c->c.qualityFlags().contains("SOURCE_GAP")));
                assertTrue(r.cells().stream().filter(c->c.value()!=null).allMatch(c->Double.isFinite(c.value())&&c.value()>=0));
            }).verifyComplete();
        } finally {service.close();repository.close();}
    }
}
