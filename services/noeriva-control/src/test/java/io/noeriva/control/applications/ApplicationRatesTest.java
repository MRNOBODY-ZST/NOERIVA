package io.noeriva.control.applications;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static io.noeriva.control.applications.ApplicationModels.*;
import static org.assertj.core.api.Assertions.*;
class ApplicationRatesTest {
    Sample sample(long seconds,String bytes,String packets,String enable,String name){return new Sample(Instant.parse("2026-09-07T00:00:00Z").plusSeconds(seconds),Long.toString(10000+seconds*100),"engine","2",List.of(new CounterRow(7,"Te0/1/0","Te0/1/0/mac",42,name,enable,bytes,bytes,packets,packets,123000d,500d,List.of())),List.of());}
    @SuppressWarnings("unchecked") List<Observation> derive(Sample next,Sample old,long rev,long oldRev)throws Exception{return (List<Observation>)Class.forName("io.noeriva.control.applications.ApplicationRates").getDeclaredMethod("derive",String.class,String.class,Sample.class,Sample.class,long.class,long.class,int.class).invoke(null,"device","sample",next,old,rev,oldRev,60);}
    @Test void exactUnsignedDeltaStaysSeparateFromReportedRate()throws Exception{var r=derive(sample(60,"18446744073709551000","700","10","http"),sample(0,"18446744073709550000","100","10","http"),1,1);assertThat(r).hasSize(2);assertThat(r.getFirst().derivedBps()).isCloseTo(1000d*8/60,within(0.000001));assertThat(r.getFirst().derivedPacketsPerSecond()).isEqualTo(10);assertThat(r.getFirst().reportedBps()).isEqualTo(123000);}
    @Test void firstSampleAndCredentialChangeDoNotManufactureRate()throws Exception{for(var r:List.of(derive(sample(60,"2000","700","10","http"),null,1,0),derive(sample(60,"2000","700","10","http"),sample(0,"1000","100","10","http"),2,1)))assertThat(r).allSatisfy(o->{assertThat(o.derivedBps()).isNull();assertThat(o.qualityFlags()).contains("NBAR_BASELINE_REQUIRED");});}
    @Test void restartReenableProtocolRenameAndGapBreakContinuity()throws Exception{var old=sample(0,"1000","100","10","http");for(var next:List.of(sample(60,"2000","700","20","http"),sample(60,"2000","700","10","tls"),sample(600,"2000","700","10","http"),new Sample(old.observedAt().plusSeconds(60),"50","engine","3",old.rows(),List.of())))assertThat(derive(next,old,1,1)).allSatisfy(o->assertThat(o.derivedBps()).isNull());}
    @Test void counterDecreaseIsResetRatherThanAnUnprovenWrap()throws Exception{assertThat(derive(sample(60,"20","2","10","http"),sample(0,"18446744073709551000","100","10","http"),1,1)).allSatisfy(o->{assertThat(o.derivedBps()).isNull();assertThat(o.qualityFlags()).contains("NBAR_COUNTER_RESET");});}
}
