package io.noeriva.control.applications;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.noeriva.control.applications.ApplicationModels.*;
import static org.assertj.core.api.Assertions.*;
class ApplicationSummaryTest {
 final Instant now=Instant.parse("2026-09-09T12:00:00Z");
 Observation row(int index,String app,String direction,Double rate){return new Observation("id"+index,"router",index,"eth"+index,1,app,direction,now,"18446744073709551615","18446744073709551615",500d,rate,null,60d,"epoch",List.of());}
 @Test void sumsOnlyDerivedRatesWithDirectionSeparationAndOverlapEvidence(){
  var result=ApplicationHistory.summarize("router",List.of(row(8,"tls","IN",10d),row(9,"tls","IN",20d),row(8,"tls","OUT",50d)),12,180,now);
  assertThat(result.items()).hasSize(2);assertThat(result.items().getFirst().derivedBps()).isEqualTo(50d);assertThat(result.items().get(1).derivedBps()).isEqualTo(30d);assertThat(result.qualityFlags()).contains("INTERFACE_OVERLAP_POSSIBLE");assertThat(result.rateBasis()).isEqualTo("COUNTER_DELTA_PER_SECOND");
 }
 @Test void baselineAndPartialRateRemainNullAndOldSamplesStayStale(){
  var result=ApplicationHistory.summarize("router",List.of(row(8,"tls","IN",10d),row(9,"tls","IN",null)),12,180,now.plusSeconds(500));
  assertThat(result.items().getFirst().derivedBps()).isNull();assertThat(result.items().getFirst().qualityFlags()).contains("RATE_INCOMPLETE");assertThat(result.freshness()).isEqualTo("STALE");
 }
}
