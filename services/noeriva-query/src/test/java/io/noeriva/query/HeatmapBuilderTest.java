package io.noeriva.query;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class HeatmapBuilderTest {
    private final HeatmapBuilder builder = new HeatmapBuilder();
    private HeatmapResponse build(String now, String zone, List<RollupBucket> data) {
        return builder.build("device-a","eth0",ZoneId.of(zone),"rx",Instant.parse(now),data,"SIMULATED");
    }
    @Test void distinguishesMissingFutureAndCurrentHourIn168Cells() {
        var r=build("2026-09-06T12:30:00Z","UTC",List.of());
        assertEquals(168,r.cells().size());
        var today=r.cells().stream().filter(c->c.date().equals(LocalDate.of(2026,9,6))).toList();
        assertEquals("MISSING",today.get(0).state());
        assertTrue(today.get(12).provisional());
        assertEquals("FUTURE",today.get(13).state());
        assertNull(today.get(13).value());
    }
    @Test void springDstMissingHourIsNotAnOutage() {
        var r=build("2026-03-08T20:00:00Z","America/New_York",List.of());
        var day=r.cells().stream().filter(c->c.date().equals(LocalDate.of(2026,3,8))).toList();
        assertEquals("DST_MISSING",day.get(2).state());
        assertEquals(23*3600,day.stream().flatMap(c->c.intervals().stream()).mapToLong(TimeInterval::durationSeconds).sum());
    }
    @Test void autumnRepeatedHourRetainsBothUtcIntervals() {
        var r=build("2026-11-01T20:00:00Z","America/New_York",List.of());
        var day=r.cells().stream().filter(c->c.date().equals(LocalDate.of(2026,11,1))).toList();
        assertEquals(2,day.get(1).intervals().size());
        assertTrue(day.get(1).qualityFlags().contains("DST_REPEATED"));
        assertEquals(25*3600,day.stream().flatMap(c->c.intervals().stream()).mapToLong(TimeInterval::durationSeconds).sum());
    }
    @Test void halfHourZoneUsesActualUtcBoundaries() {
        var r=build("2026-09-06T12:00:00Z","Asia/Kolkata",List.of());
        var cell=r.cells().stream().filter(c->c.date().equals(LocalDate.of(2026,9,6))&&c.hour()==0).findFirst().orElseThrow();
        assertEquals(Instant.parse("2026-09-05T18:30:00Z"),cell.intervals().getFirst().from());
        assertEquals(Instant.parse("2026-09-05T19:30:00Z"),cell.intervals().getFirst().to());
    }
    @Test void currentHourAtItsExactStartIsProvisionalInsteadOfFuture() {
        var r=build("2026-09-06T12:00:00Z","UTC",List.of());
        assertTrue(r.cells().get(156).provisional());
        assertEquals("MISSING",r.cells().get(156).state());
    }
    @Test void latestBucketCorrectionReplacesInsteadOfAddingAndKeepsObservedZero() {
        Instant start=Instant.parse("2026-09-06T00:00:00Z");
        var old=bucket(start,BigDecimal.valueOf(3600),1);
        var corrected=bucket(start,BigDecimal.ZERO,2);
        var r=build("2026-09-06T12:00:00Z","UTC",List.of(old,corrected));
        var cell=r.cells().get(144);
        assertEquals("OBSERVED_ZERO",cell.state());
        assertEquals(0.0,cell.value());
        assertEquals(2,cell.dataRevision());
        assertEquals(1.0,cell.coverage());
    }
    @Test void incompleteBucketCannotMasqueradeAsCompleteOrZero() {
        Instant s=Instant.parse("2026-09-06T00:00:00Z");
        var b=new RollupBucket(s,s.plusSeconds(3600),1800,BigDecimal.valueOf(1800),121,1,s.plusSeconds(3600),Set.of("SOURCE_GAP"));
        var cell=build("2026-09-06T12:00:00Z","UTC",List.of(b)).cells().get(144);
        assertEquals("PARTIAL",cell.state());
        assertEquals(8.0,cell.value());
        assertEquals(0.5,cell.coverage());
    }
    private RollupBucket bucket(Instant start,BigDecimal delta,long revision) {
        return new RollupBucket(start,start.plusSeconds(3600),3600,delta,241,revision,start.plusSeconds(3600),Set.of());
    }
}
