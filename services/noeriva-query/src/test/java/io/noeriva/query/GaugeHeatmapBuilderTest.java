package io.noeriva.query;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class GaugeHeatmapBuilderTest {
 @Test void averagesDeviceGaugesWithoutSummingAgainAndKeepsMissingNull(){
  Instant now=Instant.parse("2026-09-09T12:30:00Z");var rows=List.of(new MetricPoint(now.minusSeconds(3600),100d),new MetricPoint(now.minusSeconds(3300),300d));
  var result=new GaugeHeatmapBuilder().build("router",ZoneId.of("UTC"),"rx",now,300,new MetricRepository.Result(rows,"VICTORIAMETRICS",0,List.of()));
  assertEquals(168,result.cells().size());assertEquals("__device__",result.interfaceId());assertEquals("sample_mean",result.statistic());
  var cell=result.cells().stream().filter(c->c.date().equals(LocalDate.of(2026,9,9))&&c.hour()==11).findFirst().orElseThrow();assertEquals(200d,cell.value());assertEquals(2d/12,cell.coverage(),.00001);
  assertTrue(result.qualityFlags().contains("INTERFACE_OVERLAP_POSSIBLE"));assertNull(result.cells().getFirst().value());assertEquals("STALE",result.sourceFreshness());
 }
 @Test void deviceScopeAndDirectionSelectOneNamedMetricWithBoundedPoints(){
  var seen=new AtomicReference<List<Object>>();var query=new QueryService((o,d,i,r,f,t)->{throw new AssertionError("No interface counter scan");},(o,d,m,f,t,p)->{seen.set(List.of(o,d,m.id(),p));return Mono.just(new MetricRepository.Result(List.of(),"VICTORIAMETRICS",0,List.of()));},Clock.fixed(Instant.parse("2026-09-09T12:30:00Z"),ZoneOffset.UTC));
  try{query.deviceHeatmap("tenant-a","router","Asia/Shanghai","tx").block();assertEquals(List.of("tenant-a","router","bandwidth_tx_bps",2000),seen.get());assertThrows(IllegalArgumentException.class,()->query.deviceHeatmap("tenant-a","router","UTC","arbitrary").block());}finally{query.close();}
 }
 @Test void dstMissingHourRetainsSemanticGap(){
  var result=new GaugeHeatmapBuilder().build("router",ZoneId.of("America/New_York"),"rx",Instant.parse("2026-03-08T20:00:00Z"),300,new MetricRepository.Result(List.of(),"VICTORIAMETRICS",0,List.of()));
  assertEquals("DST_MISSING",result.cells().stream().filter(c->c.date().equals(LocalDate.of(2026,3,8))&&c.hour()==2).findFirst().orElseThrow().state());
 }
}
