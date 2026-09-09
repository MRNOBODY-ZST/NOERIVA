package io.noeriva.control.devices;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeviceCountersTest {
    DeviceProtocol.Reading reading(int seconds,String counter,String discontinuity,String uptime){return new DeviceProtocol.Reading(Instant.parse("2026-09-06T10:00:00Z").plusSeconds(seconds),null,"UNKNOWN",Map.of(),List.of(),List.of(new DeviceProtocol.Port("eth0","eth0","00:00:00:00:00:01","1000000000","UP","UP",counter,counter,discontinuity,64,"ifIndex:1")),List.of(),List.of(),Map.of("sysUptimeTicks",uptime));}
    @Test void firstSampleIsMissingNotZeroAndNextOrderedSampleProducesRate(){var first=DeviceCounters.enrich(reading(0,"1000","0","10000"),null,"epoch",60);assertThat(first.metrics()).doesNotContainKey("bandwidth_rx_bps");assertThat(first.qualityFlags()).contains("COUNTER_BASELINE_REQUIRED");var second=DeviceCounters.enrich(reading(60,"61000","0","16000"),first,"epoch",60);assertThat(second.metrics().get("bandwidth_rx_bps")).isEqualTo(8000.0);}
    @Test void rebootAndDiscontinuityDoNotProduceAnInventedSpike(){var first=DeviceCounters.enrich(reading(0,"1000","0","10000"),null,"epoch",60);var restarted=DeviceCounters.enrich(reading(60,"61000","0","100"),first,"epoch",60);assertThat(restarted.metrics()).isEmpty();assertThat(restarted.facts().get("collectorEpoch")).isNotEqualTo("epoch");var changed=DeviceCounters.enrich(reading(60,"61000","6000","16000"),first,"epoch",60);assertThat(changed.metrics()).isEmpty();}
    @Test void missingDiscontinuityAndRebootWithHigherUptimeStayMissing(){var first=reading(0,"1000",null,"1000");assertThat(DeviceCounters.enrich(reading(60,"61000",null,"7000"),first,"epoch",60).metrics()).isEmpty();first=reading(0,"1000","0","1000");var restarted=DeviceCounters.enrich(reading(120,"61000","0","4000"),first,"epoch",60);assertThat(restarted.metrics()).isEmpty();assertThat(restarted.qualityFlags()).contains("COUNTER_SOURCE_RESTART");}
    @Test void rawSamplesWithoutA64BitContinuityProofNeverBecomeValidHistoricalIntervals(){
        var reading=DeviceCounters.enrich(reading(0,"1000",null,"1000"),null,"epoch",60);var port=reading.ports().getFirst();
        var one=new DeviceAccessModels.Stored("org","device","snmp",1,true,null,"","SUCCESS",null,null,null,"","",null,null,"lease1",null,1,"epoch");
        var two=new DeviceAccessModels.Stored("org","device","snmp",1,true,null,"","SUCCESS",null,null,null,"","",null,null,"lease2",null,2,"epoch");
        var samples=List.of(new io.noeriva.query.CounterSample(reading.observedAt(),new java.math.BigInteger("1000"),DevicePublisher.rawEpoch(one,reading,port),false),new io.noeriva.query.CounterSample(reading.observedAt().plusSeconds(60),new java.math.BigInteger("61000"),DevicePublisher.rawEpoch(two,reading,port),false));
        var bucket=new io.noeriva.query.CounterRollup().aggregate(samples,reading.observedAt(),reading.observedAt().plusSeconds(60),300,java.time.Duration.ofSeconds(180),1,reading.observedAt()).getFirst();
        assertThat(bucket.validDurationSeconds()).isZero();assertThat(bucket.qualityFlags()).contains("SOURCE_RESTART");
        var counter32=new DeviceProtocol.Port("eth0","eth0",null,"1000000000","UP","UP","1000","1000","0",32,"if:1");
        assertThat(DevicePublisher.rawEpoch(one,reading,counter32)).isNotEqualTo(DevicePublisher.rawEpoch(two,reading,counter32));
    }
    @Test void resetsWrapAmbiguityGapsAndImpossibleSpeedsStayMissing(){assertThat(DeviceCounters.rate("1000","999",64,"1000000000",60)).isNull();assertThat(DeviceCounters.rate("1000","2000",32,"1000000000",60)).isNull();assertThat(DeviceCounters.rate("1000","1000000",64,"100",1)).isNull();var first=reading(0,"1000","0","10000");assertThat(DeviceCounters.enrich(reading(1000,"61000","0","110000"),first,"epoch",60).metrics()).isEmpty();}
}
