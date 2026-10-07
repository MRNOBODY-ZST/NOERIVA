package io.noeriva.query;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SquareHeatmapTest {
    @Test void squareRequestAggregatesOriginalObservationsRatherThanHourlyMeans() {
        var now=Instant.parse("2026-09-21T12:00:00Z");
        var query=new QueryService((o,d,i,r,f,t)->{throw new AssertionError("Not an interface scan");},
            (o,d,m,f,t,p)->Mono.just(new MetricRepository.Result(List.of(
                new MetricPoint(Instant.parse("2026-09-15T00:05:00Z"),0d),
                new MetricPoint(Instant.parse("2026-09-15T00:10:00Z"),0d),
                new MetricPoint(Instant.parse("2026-09-15T01:05:00Z"),90d)
            ),"VICTORIAMETRICS",0,List.of())),Clock.fixed(now,ZoneOffset.UTC));
        try {
            var result=query.deviceHeatmap("tenant-a","router","UTC","rx",7).block();
            var matrix=result.matrix();
            assertNotNull(matrix,"A requested square matrix must be returned");
            assertEquals(30d,matrix.cells().getFirst().value());
            assertEquals(3L,matrix.cells().getFirst().sampleCount());
        } finally {query.close();}
    }

    @Test void bothSizesCoverTheSameSevenLocalDatesExactlyOnceIncludingFuture() {
        var reads=new AtomicInteger();
        var query=deviceQuery("2026-09-21T00:00:00Z",List.of(),reads);
        try {
            for(int size:List.of(7,28)) {
                var response=query.deviceHeatmap("tenant-a","router","UTC","rx",size).block();
                assertEquals(168,response.cells().size());
                var matrix=response.matrix();assertNotNull(matrix);assertEquals(size,matrix.size());
                assertEquals(size*size,matrix.cells().size());
                assertEquals(Instant.parse("2026-09-15T00:00:00Z"),matrix.cells().getFirst().intervals().getFirst().from());
                assertEquals(Instant.parse("2026-09-22T00:00:00Z"),matrix.cells().getLast().intervals().getFirst().to());
                var endpoints=new HashSet<Instant>();
                for(int index=0;index<matrix.cells().size();index++) {
                    var cell=matrix.cells().get(index);var interval=cell.intervals().getFirst();
                    assertEquals(index,cell.index());assertEquals(1,cell.intervals().size());
                    assertTrue(interval.from().isBefore(interval.to()));assertTrue(endpoints.add(interval.from()));
                    assertEquals(0,interval.from().getEpochSecond()%300);
                    assertEquals(Duration.between(interval.from(),interval.to()).getSeconds(),interval.durationSeconds());
                    if(index>0)assertEquals(matrix.cells().get(index-1).intervals().getFirst().to(),interval.from());
                }
                assertEquals(size==7?12300:600,matrix.cells().getFirst().intervals().getFirst().durationSeconds());
                assertEquals(size==7?12600:900,matrix.cells().getLast().intervals().getFirst().durationSeconds());
            }
            assertEquals(2,reads.get(),"One existing provider read per request, never a matrix-specific scan");
            assertNull(query.deviceHeatmap("tenant-a","router","UTC","rx").block().matrix());
        } finally {query.close();}
    }

    @Test void zeroMissingPartialAndFutureRemainDistinctWithoutInterpolation() {
        var samples=new ArrayList<MetricPoint>();
        var start=Instant.parse("2026-09-15T00:00:00Z");
        for(int minute=0;minute<10;minute++)samples.add(new MetricPoint(start.plusSeconds(minute*60),0d));
        samples.add(new MetricPoint(start.plusSeconds(25*60),80d));
        samples.add(new MetricPoint(start.plusSeconds(26*60),-1d));
        samples.add(new MetricPoint(start.plusSeconds(27*60),Double.NaN));
        var query=deviceQuery("2026-09-21T00:00:00Z",samples,new AtomicInteger());
        try {
            var cells=matrix(query.deviceHeatmap("tenant-a","router","UTC","rx",28).block()).cells();
            assertEquals("OBSERVED_ZERO",cells.get(0).state());assertEquals(0d,cells.get(0).value());assertEquals(1d,cells.get(0).coverage());
            assertEquals("MISSING",cells.get(1).state());assertNull(cells.get(1).value());
            assertEquals("PARTIAL",cells.get(2).state());assertEquals(80d,cells.get(2).value());assertEquals(1L,cells.get(2).sampleCount());
            assertEquals(Instant.parse("2026-09-15T00:25:00Z"),cells.get(2).asOf());
            assertTrue(cells.get(2).qualityFlags().contains("MISSING_SAMPLE"));
            assertEquals("MISSING",cells.get(672).state());assertTrue(cells.get(672).provisional());
            assertEquals("FUTURE",cells.get(673).state());assertNull(cells.get(673).value());assertFalse(cells.get(673).provisional());
        } finally {query.close();}
    }

    @Test void interfaceMatrixUsesWholeRollupsAndValidDurationWeights() {
        var start=Instant.parse("2026-09-15T00:00:00Z");
        var rows=List.of(
            new RollupBucket(start,start.plusSeconds(300),300,BigDecimal.valueOf(300),3,1,start.plusSeconds(300),Set.of()),
            new RollupBucket(start.plusSeconds(300),start.plusSeconds(600),150,BigDecimal.valueOf(450),2,2,start.plusSeconds(600),Set.of("SOURCE_GAP"),start.plusSeconds(540)),
            new RollupBucket(start.plusSeconds(600),start.plusSeconds(900),300,BigDecimal.ZERO,3,1,start.plusSeconds(900),Set.of())
        );
        var query=interfaceQuery("2026-09-21T00:00:00Z",rows);
        try {
            var first=matrix(query.heatmap("tenant-a","router","eth0","primary","UTC","rx",28).block()).cells().getFirst();
            assertEquals(13.333333333333334,first.value(),1e-12);assertEquals(.75,first.coverage());assertEquals("PARTIAL",first.state());
            assertEquals(2,first.dataRevision());assertEquals(Instant.parse("2026-09-15T00:09:00Z"),first.asOf());
            assertTrue(first.qualityFlags().contains("SOURCE_GAP"));assertNull(first.sampleCount());
            var second=matrix(query.heatmap("tenant-a","router","eth0","primary","UTC","rx",28).block()).cells().get(1);
            assertEquals(0d,second.value());assertEquals("PARTIAL",second.state());assertEquals(1d/3,second.coverage(),1e-12);
        } finally {query.close();}
    }

    @Test void currentUnclosedRollupCannotInventObservedMatrixCoverage() {
        var start=Instant.parse("2026-09-21T00:00:00Z");
        var query=interfaceQuery("2026-09-21T00:02:00Z",List.of(new RollupBucket(start,start.plusSeconds(300),300,BigDecimal.valueOf(300),3,1,start.plusSeconds(300),Set.of())));
        try {
            var cell=matrix(query.heatmap("tenant-a","router","eth0","primary","UTC","rx",28).block()).cells().get(672);
            assertEquals("MISSING",cell.state());assertNull(cell.value());assertEquals(0,cell.coverage());assertTrue(cell.provisional());
        } finally {query.close();}
    }

    @Test void dstWeekUsesActualUtcDurationAndKeepsRepeatedTimeInTheRealInterval() {
        for(var row:List.of(
                List.of("2026-03-08T20:00:00Z","2026-03-02T05:00:00Z","2026-03-09T04:00:00Z","601200"),
                List.of("2026-11-01T20:00:00Z","2026-10-26T04:00:00Z","2026-11-02T05:00:00Z","608400"))) {
            var query=deviceQuery(row.get(0),List.of(),new AtomicInteger());
            try {
                var response=query.deviceHeatmap("tenant-a","router","America/New_York","rx",28).block();
                var cells=matrix(response).cells();assertEquals(784,cells.size());
                assertEquals(Instant.parse(row.get(1)),cells.getFirst().intervals().getFirst().from());
                assertEquals(Instant.parse(row.get(2)),cells.getLast().intervals().getFirst().to());
                assertEquals(Long.parseLong(row.get(3)),cells.stream().flatMap(c->c.intervals().stream()).mapToLong(TimeInterval::durationSeconds).sum());
                assertTrue(cells.stream().anyMatch(c->c.qualityFlags().contains("TIMEZONE_TRANSITION")));
            } finally {query.close();}
        }
    }

    @Test void unsupportedSizeFailsBeforeProvidersAreRead() {
        var reads=new AtomicInteger();var query=deviceQuery("2026-09-21T00:00:00Z",List.of(),reads);
        try {
            for(int size:List.of(-1,1,14,29,Integer.MAX_VALUE))assertThrows(IllegalArgumentException.class,()->query.deviceHeatmap("tenant-a","router","UTC","rx",size).block());
            assertEquals(0,reads.get());
        } finally {query.close();}
    }

    private HeatmapMatrix matrix(HeatmapResponse response) {
        assertNotNull(response.matrix(),"A requested square matrix must be returned");return response.matrix();
    }

    private QueryService deviceQuery(String now,List<MetricPoint> points,AtomicInteger reads) {
        return new QueryService((o,d,i,r,f,t)->{throw new AssertionError("No interface query");},
            (o,d,m,f,t,p)->{reads.incrementAndGet();return Mono.just(new MetricRepository.Result(points,"VICTORIAMETRICS",0,List.of()));},
            Clock.fixed(Instant.parse(now),ZoneOffset.UTC));
    }
    private QueryService interfaceQuery(String now,List<RollupBucket> rows) {
        return new QueryService((o,d,i,r,f,t)->Mono.just(new RollupRepository.Result(rows,"CLICKHOUSE")),
            (o,d,m,f,t,p)->{throw new AssertionError("No device gauge query");},Clock.fixed(Instant.parse(now),ZoneOffset.UTC));
    }
}
