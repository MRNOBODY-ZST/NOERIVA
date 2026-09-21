package io.noeriva.query;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricAggregationTest {
    private final Instant start=Instant.parse("2026-09-06T00:00:00Z");
    private MetricPoint point(int seconds,Double value){return new MetricPoint(start.plusSeconds(seconds),value);}
    @Test void averagesEverySampleWithoutAveragingBucketMeans() {
        var result=MetricAggregation.aggregate(List.of(point(0,0.0),point(30,60.0),point(40,120.0),point(90,600.0)),start,start.plusSeconds(120),60,true);
        assertEquals(List.of(60.0,600.0),result.points().stream().map(MetricPoint::value).toList());
        assertEquals(195.0,result.summary().mean());assertEquals(4,result.summary().sampleCount());
        assertEquals("4125",result.summary().estimatedBytes());assertEquals(0.75,result.summary().coverage());
    }
    @Test void missingAndLongGapsAreNeverChargedAsTransferredTraffic() {
        var result=MetricAggregation.aggregate(List.of(point(0,80.0),point(30,80.0),point(60,null),point(90,80.0),point(120,80.0),point(600,80.0)),start,start.plusSeconds(720),60,true);
        assertEquals("600",result.summary().estimatedBytes());assertEquals(60.0,result.summary().observedSeconds());
        assertNull(result.points().get(5).value());assertTrue(result.flags().contains("GAPS_NOT_EXTRAPOLATED"));assertTrue(result.flags().contains("MISSING_SAMPLE"));
    }
    @Test void observedZeroDiffersFromUnavailableAndSingleSampleDoesNotInventVolume() {
        var zero=MetricAggregation.aggregate(List.of(point(0,0.0),point(30,0.0)),start,start.plusSeconds(60),30,true);
        assertEquals("0",zero.summary().estimatedBytes());assertEquals(0.0,zero.summary().mean());
        var missing=MetricAggregation.aggregate(List.of(point(0,null)),start,start.plusSeconds(60),30,true);
        assertNull(missing.summary().mean());assertNull(missing.summary().estimatedBytes());assertTrue(missing.points().isEmpty());
        var single=MetricAggregation.aggregate(List.of(point(30,80.0)),start,start.plusSeconds(60),30,true);
        assertNull(single.summary().estimatedBytes());assertEquals(0,single.summary().coverage());
    }
    @Test void supportsHealthyTenMinuteAndHourlyCollectionIntervals() {
        for(int seconds:List.of(600,3600)) {
            var samples=List.of(point(0,80d),point(seconds,80d),point(seconds*2,80d));
            assertEquals(seconds,MetricAggregation.cadence(samples));
            var result=MetricAggregation.aggregate(samples,start,start.plusSeconds(seconds*2),seconds,true);
            assertEquals(1,result.summary().coverage());
            assertEquals(seconds*2,result.summary().observedSeconds());
            assertEquals(Long.toString(seconds*20L),result.summary().estimatedBytes());
            assertFalse(result.flags().contains("GAPS_NOT_EXTRAPOLATED"));
        }
        var sparse=List.of(point(0,80d),point(14400,80d));
        assertEquals(3600,MetricAggregation.cadence(sparse));
        assertNull(MetricAggregation.aggregate(sparse,start,start.plusSeconds(14400),3600,true).summary().estimatedBytes());
    }
    @Test void rejectsOverflowingBucketEvenWhenWholeWindowSumIsFinite() {
        var samples=List.of(point(0,-Double.MAX_VALUE),point(60,Double.MAX_VALUE),point(90,Double.MAX_VALUE));
        assertThrows(ProviderUnavailableException.class,()->MetricAggregation.aggregate(samples,start,start.plusSeconds(120),60,false));
    }
}
