package io.noeriva.query;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CounterRollupTest {
    private final Instant start = Instant.parse("2026-09-06T00:00:00Z");
    private final CounterRollup engine = new CounterRollup();
    private CounterSample sample(int second, String counter) {
        return new CounterSample(start.plusSeconds(second), new BigInteger(counter), "boot-1", false);
    }
    private List<RollupBucket> roll(List<CounterSample> samples) {
        return engine.aggregate(samples, start, start.plusSeconds(600), 300, Duration.ofSeconds(60), 1, start.plusSeconds(600));
    }
    @Test void preservesRealZeroButDoesNotZeroFillUnobservedTime() {
        var result = roll(List.of(sample(0, "500"), sample(30, "500")));
        assertEquals(0, result.getFirst().validCounterDelta().compareTo(BigDecimal.ZERO));
        assertEquals(30, result.getFirst().validDurationSeconds());
        assertNull(result.get(1).averageBps());
        assertEquals(0.0, result.getFirst().averageBps());
    }
    @Test void partialBucketKeepsActualSourceTimeInsteadOfBucketEndOrBuildTime() {
        var result=roll(List.of(sample(0,"0"),sample(30,"300")));
        var response=new HeatmapBuilder().build("device-a","eth0",ZoneOffset.UTC,"rx",start.plusSeconds(3600),result,"SIMULATED");
        var cell=response.cells().get(144);
        assertEquals(start.plusSeconds(30),cell.asOf());
    }
    @Test void computesUint64DeltaBeforeFloatingPointConversion() {
        var result = roll(List.of(sample(0, "18446744073709551000"), sample(30, "18446744073709551300")));
        assertEquals(300, result.getFirst().validCounterDelta().intValueExact());
        assertEquals(80.0, result.getFirst().averageBps());
    }
    @Test void gapsAndResetsCannotInventTraffic() {
        var result = roll(List.of(sample(0, "500"), sample(30, "100"), sample(300, "10000")));
        assertNull(result.getFirst().averageBps());
        assertTrue(result.getFirst().qualityFlags().contains("COUNTER_RESET"));
        assertTrue(result.getFirst().qualityFlags().contains("SOURCE_GAP"));
    }
    @Test void declaredUint64WrapIsAnExactDelta() {
        var wrapped = new CounterSample(start.plusSeconds(30), BigInteger.valueOf(14), "boot-1", true);
        var result = roll(List.of(sample(0,"18446744073709551600"), wrapped));
        assertEquals(30, result.getFirst().validCounterDelta().intValueExact());
        assertTrue(result.getFirst().qualityFlags().contains("COUNTER_WRAP"));
    }
    @Test void bootChangeInvalidatesEvenAnIncreasingCounter() {
        var result = roll(List.of(sample(0,"100"), new CounterSample(start.plusSeconds(30), BigInteger.valueOf(500),"boot-2",false)));
        assertNull(result.getFirst().averageBps());
        assertTrue(result.getFirst().qualityFlags().contains("SOURCE_RESTART"));
    }
    @Test void splitsBoundaryUsingObservedDurationAndMarksEstimation() {
        var result = roll(List.of(sample(270,"0"), sample(330,"600")));
        assertEquals(300, result.get(0).validCounterDelta().intValueExact());
        assertEquals(300, result.get(1).validCounterDelta().intValueExact());
        assertEquals(30,result.get(0).validDurationSeconds());
        assertEquals(80.0,result.get(1).averageBps());
        assertTrue(result.get(0).qualityFlags().contains("ESTIMATED_BOUNDARY"));
    }
    @Test void rejectsConflictingDuplicateSampleTimesAndOversizedWork() {
        assertThrows(IllegalArgumentException.class, () -> roll(List.of(sample(0,"0"),sample(0,"1"))));
        assertThrows(IllegalArgumentException.class, () -> engine.aggregate(List.of(),start,start.plusSeconds(31536000),1,Duration.ofSeconds(60),1,start));
    }
}
