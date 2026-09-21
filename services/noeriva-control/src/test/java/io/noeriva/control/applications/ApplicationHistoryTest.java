package io.noeriva.control.applications;
import io.noeriva.control.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static io.noeriva.control.applications.ApplicationModels.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers class ApplicationHistoryTest {
    @Container static final GenericContainer<?> CH=new GenericContainer<>("clickhouse/clickhouse-server:26.3.32.14").withExposedPorts(8123).withEnv("CLICKHOUSE_USER","test").withEnv("CLICKHOUSE_PASSWORD","synthetic-application-history").withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT","1").waitingFor(Wait.forHttp("/ping").forPort(8123));
    static final Duration WAIT=Duration.ofSeconds(20);static WebClient client;static JsonMapper json=JsonMapper.builder().build();ApplicationHistory history;String org;Instant now;
    @BeforeAll static void schema()throws Exception{
        client=WebClient.builder().baseUrl("http://"+CH.getHost()+":"+CH.getMappedPort(8123)).defaultHeaders(h->h.setBasicAuth("test","synthetic-application-history")).build();
        client.post().bodyValue("CREATE DATABASE IF NOT EXISTS noeriva").retrieve().toBodilessEntity().block(WAIT);
        Path p=Path.of("../../deploy/kubernetes/two-node/config/004-application-monitoring.sql");if(!Files.exists(p))p=Path.of("deploy/kubernetes/two-node/config/004-application-monitoring.sql");
        client.post().bodyValue(Files.readString(p)).retrieve().toBodilessEntity().block(WAIT);
    }
    @BeforeEach void setup(){org="app-"+UUID.randomUUID();now=Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);history=new ApplicationHistory(WebClient.builder(),new MockEnvironment().withProperty("NOERIVA_CLICKHOUSE_URL","http://"+CH.getHost()+":"+CH.getMappedPort(8123)).withProperty("NOERIVA_CLICKHOUSE_USERNAME","test").withProperty("NOERIVA_CLICKHOUSE_PASSWORD","synthetic-application-history"),json);}
    Observation row(String id,int index,String direction,String application,Instant at){return new Observation(ApplicationRates.hash(id),"router",index,"Te0/1/0",42,application,direction,at,"18446744073709551000","9223372036854776000",123000d,null,null,null,"epoch",List.of("NBAR_BASELINE_REQUIRED"));}
    @Test void repeatedBatchIsDeduplicatedAndUnsignedStringsSurviveStorage(){var rows=List.of(row("a",8,"IN","http",now),row("b",8,"OUT","http",now),row("c",9,"IN","tls",now));history.append(org,rows).block(WAIT);history.append(org,rows).block(WAIT);var page=history.query(org,"router",null,"","",now.minusSeconds(60),now.plusSeconds(60),100,null).block(WAIT);assertThat(page.items()).hasSize(3);assertThat(page.items()).allSatisfy(o->assertThat(o.bytes()).isEqualTo("18446744073709551000"));assertThat(history.query("other","router",null,"","",now.minusSeconds(60),now.plusSeconds(60),100,null).block(WAIT).items()).isEmpty();}
    @Test void filtersAndCursorReturnEveryRowOnceAndRejectFilterChanges(){var rows=List.of(row("a",8,"IN","https",now),row("b",8,"IN","http",now),row("c",9,"IN","http",now),row("d",8,"OUT","http",now));history.append(org,rows).block(WAIT);Instant from=now.minusSeconds(60),to=now.plusSeconds(60);var first=history.query(org,"router",8,"IN","http",from,to,1,null).block(WAIT);assertThat(first.items()).hasSize(1);assertThat(first.nextCursor()).isNotBlank();var second=history.query(org,"router",8,"IN","http",from,to,1,first.nextCursor()).block(WAIT);assertThat(second.items()).hasSize(1);assertThat(second.items().getFirst().id()).isNotEqualTo(first.items().getFirst().id());assertThat(second.nextCursor()).isNull();assertThatThrownBy(()->history.query(org,"router",9,"IN","http",from,to,1,first.nextCursor()).block(WAIT)).isInstanceOf(ApiException.class);}
    @Test void defaultWindowPaginationFreezesItsInitialBounds(){history.append(org,List.of(row("a",8,"IN","http",now),row("b",8,"IN","http",now.minusSeconds(1)))).block(WAIT);var first=history.query(org,"router",null,"","",null,null,1,null).block(WAIT);var second=history.query(org,"router",null,"","",null,null,1,first.nextCursor()).block(WAIT);assertThat(first.items()).hasSize(1);assertThat(second.items()).hasSize(1);assertThat(second.items().getFirst().id()).isNotEqualTo(first.items().getFirst().id());}
    @Test void latestDistributionDoesNotReviveAnOlderApplicationAndRemainsScoped(){
        history.append(org,List.of(row("old",8,"IN","old-app",now.minusSeconds(60)),row("new",8,"IN","https",now))).block(WAIT);
        var result=history.summary(org,"router",null,"","",12,180).block(WAIT);assertThat(result.items()).extracting(SummaryItem::application).containsExactly("https");assertThat(result.items().getFirst().derivedBps()).isNull();assertThat(result.items().getFirst().reportedBps()).isEqualTo(123000d);
        assertThat(history.summary(org,"router",null,"","old-app",12,180).block(WAIT).items()).isEmpty();assertThat(history.summary("other","router",null,"","",12,180).block(WAIT).items()).isEmpty();
    }
    @Test void latestDistributionSurvivesMillionRowHistoryWithoutLosingActualReadBudget(){
        var old=row("old",8,"IN","old-app",now.minusSeconds(600));
        String payload=json.writeValueAsString(old).replace(old.id(),"__ID__").replace("\\","\\\\").replace("'","\\'");
        // Historical payloads retain huge UInt64 cumulative counters. They must
        // never contaminate the latest batch or become application bit rates.
        String insert="INSERT INTO noeriva.application_observations SELECT '"+org+"','router',lower(hex(SHA256(toString(number)))),fromUnixTimestamp64Milli("+old.observedAt().toEpochMilli()+"),8,'IN','old-app',replaceAll('"+payload+"','__ID__',lower(hex(SHA256(toString(number))))),1 FROM numbers(1200000)";
        client.post().bodyValue(insert).retrieve().toBodilessEntity().block(WAIT);
        var latest=new Observation(ApplicationRates.hash("latest"),"router",8,"Te0/1/0",42,"https","IN",now,"18446744073709551000","9223372036854776000",123000d,321d,2d,60d,"epoch",List.of());
        history.append(org,List.of(latest)).block(WAIT);
        var summary=history.summary(org,"router",null,"","",12,180).block(WAIT);
        assertThat(summary.items()).extracting(SummaryItem::application).containsExactly("https");
        assertThat(summary.items().getFirst().derivedBps()).isEqualTo(321d);
        assertThat(summary.observedAt()).isEqualTo(now);assertThat(summary.sampleRows()).isEqualTo(1);
        assertThat(history.summary(org,"router",null,"","old-app",12,180).block(WAIT).items()).isEmpty();
        assertThat(history.summary("foreign","router",null,"","",12,180).block(WAIT).items()).isEmpty();
        client.post().bodyValue("SYSTEM FLUSH LOGS").retrieve().toBodilessEntity().block(WAIT);
        String reads=client.post().bodyValue("SELECT max(read_rows) FROM system.query_log WHERE type='QueryFinish' AND startsWith(query_id,'noeriva-app-summary-') FORMAT TSV").retrieve().bodyToMono(String.class).block(WAIT);
        assertThat(Long.parseLong(reads.trim())).as("Latest batch must not scan retained historical payloads").isLessThan(100000L);
        assertThatThrownBy(()->client.post().uri("/?max_rows_to_read=0&max_bytes_to_read=0&read_overflow_mode=break&max_rows_to_read_leaf=1000000&max_bytes_to_read_leaf=67108864&read_overflow_mode_leaf=throw&max_execution_time=5&max_block_size=256&max_threads=2")
            .bodyValue("SELECT sum(cityHash64(observation_id)) FROM noeriva.application_observations WHERE organization_id='"+org+"' AND device_id='router'").retrieve().bodyToMono(String.class).block(WAIT))
            .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class)
            .satisfies(error->assertThat(((org.springframework.web.reactive.function.client.WebClientResponseException)error).getResponseBodyAsString()).containsAnyOf("TOO_MANY_ROWS","TOO_MANY_BYTES"));
    }
    Observation interval(String id,int index,Instant at,String delta,double seconds){return new Observation(ApplicationRates.hash(id),"router",index,"eth"+index,42,"tls","IN",at,"18446744073709551000",null,null,Double.parseDouble(delta)*8/seconds,null,seconds,"epoch",List.of(),delta);}
    @Test void sevenDayWindowAggregatesMillionsOfPayloadsWithinItsMemoryBudget(){
        Instant from=now.minusSeconds(7*86400);
        var sample=new Observation(ApplicationRates.hash("large-history"),"router",8,"TenGigabitEthernet0/1/0",42,"__APPLICATION__","IN",now,"18446744073709551000",null,123000d,80d,null,80d,ApplicationRates.hash("synthetic-epoch"),List.of("NBAR_ROW_LIMIT","NBAR_COUNTER_UNAVAILABLE"),"800");
        String payload=json.writeValueAsString(sample).replace("\\","\\\\").replace("'","\\'");
        String legacyPayload=payload.replace("\"intervalBytes\":\"800\"","\"intervalBytes\":null");
        // 256 applications × 2 interfaces × 6,250 non-overlapping 80s intervals.
        // Wide payloads, repeated flags and both legacy/exact counters exercise
        // the final guards and query-wide sort memory during small-group aggregation.
        String application="concat('application-',toString(intDiv(number%512,2)))";
        String insert="INSERT INTO noeriva.application_observations SELECT '"+org+"','router',lower(hex(SHA256(toString(number)))),fromUnixTimestamp64Milli("+from.toEpochMilli()+"+(intDiv(number,512)+1)*80000),toUInt32(number%2+1),'IN',"+application+",replaceAll(if(intDiv(number,512)<3125,'"+legacyPayload+"','"+payload+"'),'__APPLICATION__',"+application+"),1 FROM numbers(3200000)";
        client.post().bodyValue(insert).retrieve().toBodilessEntity().block(Duration.ofSeconds(60));
        WindowSummary result;
        try {result=history.window(org,"router",null,"","",from,now,12,180).block(Duration.ofSeconds(50));}
        catch (ApiException failure) {
            client.post().bodyValue("SYSTEM FLUSH LOGS").retrieve().toBodilessEntity().block(WAIT);
            String reason=client.post().bodyValue("SELECT exception_code,exception FROM system.query_log WHERE startsWith(query_id,'noeriva-app-window-') AND type='ExceptionWhileProcessing' ORDER BY event_time_microseconds DESC LIMIT 1 FORMAT JSONEachRow").retrieve().bodyToMono(String.class).block(WAIT);
            throw new AssertionError("The bounded complete window must succeed: "+reason,failure);
        }
        assertThat(result.sampleRows()).isEqualTo(3200000);
        assertThat(result.totalBytes()).isEqualTo("2560000000");
        assertThat(result.meanBps()).isEqualTo(40960d);
        assertThat(result.totalApplications()).isEqualTo(256);
        assertThat(result.qualityFlags()).contains("HISTORICAL_RATE_ESTIMATE","NBAR_ROW_LIMIT");
        assertThat(result.coverage()).isCloseTo(500000d/604800,within(.000001));
        assertThat(result.trend()).hasSize(7);
        assertThat(result.trend().getFirst().inBytes()).isEqualTo("442368000");
        assertThat(result.trend().getLast().inBytes()).isNull();
        client.post().bodyValue("SYSTEM FLUSH LOGS").retrieve().toBodilessEntity().block(WAIT);
        String memory=client.post().bodyValue("SELECT max(memory_usage) FROM system.query_log WHERE type='QueryFinish' AND startsWith(query_id,'noeriva-app-window-') AND read_rows>=3200000 FORMAT TSV").retrieve().bodyToMono(String.class).block(WAIT);
        assertThat(Long.parseLong(memory.trim())).as("The successful full window must retain its 512 MiB hard ceiling").isBetween(1L,536870912L);
        String spills=client.post().bodyValue("SELECT max(ProfileEvents['ExternalAggregationWritePart']) FROM system.query_log WHERE type='QueryFinish' AND startsWith(query_id,'noeriva-app-window-') AND read_rows>=3200000 FORMAT TSV").retrieve().bodyToMono(String.class).block(WAIT);
        assertThat(Long.parseLong(spills.trim())).as("Small aggregate states must not repeatedly spill because the preceding sort retains memory").isZero();
    }
    void appendPayload(String label,int index,Map<String,Object> payload){
        String body=json.writeValueAsString(Map.of("organization_id",org,"device_id","router","observation_id",ApplicationRates.hash(label),"observed_at",now.toString(),"interface_index",index,"direction","IN","application","tls","payload",json.writeValueAsString(payload),"revision",1));
        client.post().uri("/?date_time_input_format=best_effort").bodyValue("INSERT INTO noeriva.application_observations FORMAT JSONEachRow\n"+body).retrieve().toBodilessEntity().block(WAIT);
    }
    @Test void typedWindowPreservesAbsentNullZeroAndFractionalLegacyRates(){
        var base=new HashMap<String,Object>();base.put("protocolIndex",42);base.put("intervalSeconds",60);base.put("qualityFlags",List.of());
        appendPayload("absent",1,base);
        var nullRate=new HashMap<>(base);nullRate.put("derivedBps",null);appendPayload("null",2,nullRate);
        var zero=new HashMap<>(base);zero.put("derivedBps",0);appendPayload("zero",3,zero);
        var fractional=new HashMap<>(base);fractional.put("derivedBps",80.5);fractional.put("intervalBytes",null);appendPayload("fractional",4,fractional);
        var numericText=new HashMap<>(base);numericText.put("derivedBps","80.5");numericText.put("intervalSeconds","60");appendPayload("text",5,numericText);
        var booleanRate=new HashMap<>(base);booleanRate.put("derivedBps",true);appendPayload("boolean",6,booleanRate);
        var objectRate=new HashMap<>(base);objectRate.put("derivedBps",Map.of("value",80));appendPayload("object",7,objectRate);
        var booleanDuration=new HashMap<>(base);booleanDuration.put("intervalSeconds",true);booleanDuration.put("derivedBps",80);appendPayload("boolean-duration",8,booleanDuration);
        for(int index:List.of(1,2))assertThat(history.window(org,"router",index,"IN","",now.minusSeconds(60),now,12,180).block(WAIT).totalBytes()).isNull();
        assertThat(history.window(org,"router",3,"IN","",now.minusSeconds(60),now,12,180).block(WAIT).totalBytes()).isEqualTo("0");
        var legacy=history.window(org,"router",4,"IN","",now.minusSeconds(60),now,12,180).block(WAIT);
        assertThat(legacy.totalBytes()).isEqualTo("604");assertThat(legacy.meanBps()).isEqualTo(80.5);
        assertThat(legacy.qualityFlags()).contains("HISTORICAL_RATE_ESTIMATE");
        assertThat(history.window(org,"router",5,"IN","",now.minusSeconds(60),now,12,180).block(WAIT).totalBytes()).isEqualTo("604");
        for(int index:List.of(6,7,8))assertThat(history.window(org,"router",index,"IN","",now.minusSeconds(60),now,12,180).block(WAIT).totalBytes()).isNull();
    }
    @Test void invalidHugeLegacyRatesStayMissingAndCannotOverflowDecimalConversion(){
        appendPayload("overflow",8,Map.of("protocolIndex",42,"intervalSeconds",10800,"derivedBps",1e100,"qualityFlags",List.of()));
        var result=history.window(org,"router",8,"IN","",now.minusSeconds(10800),now,12,180).block(WAIT);
        assertThat(result.totalBytes()).isNull();assertThat(result.meanBps()).isNull();assertThat(result.coverage()).isZero();
    }
    @Test void maximumUnsignedDeltaSurvivesClippingNearTheThreeHourIntervalLimit(){
        history.append(org,List.of(interval("max-clipped",8,now,"18446744073709551610",10800))).block(WAIT);
        var result=history.window(org,"router",8,"IN","",now.minusSeconds(10800).plusMillis(1),now,12,180).block(WAIT);
        assertThat(result.totalBytes()).isEqualTo("18446742365677692933");
        assertThat(result.qualityFlags()).contains("ESTIMATED_BOUNDARY");
    }
    @Test void windowClipsDeltasAndSumsInterfaceMeansWithoutDividingByInterfaceCount(){
        Instant from=now.minusSeconds(120),to=now.minusSeconds(30);
        history.append(org,List.of(interval("one",8,now.minusSeconds(60),"600",60),interval("two",8,now,"1200",60),interval("three",9,now,"2400",120))).block(WAIT);
        var result=history.window(org,"router",null,"","",from,to,12,180).block(WAIT);
        assertThat(result.totalBytes()).isEqualTo("3000");
        assertThat(result.items().getFirst().derivedBps()).isCloseTo(266.6666666667,within(.000001));
        assertThat(result.items().getFirst().coverage()).isEqualTo(1);
        assertThat(result.items().getFirst().qualityFlags()).contains("ESTIMATED_BOUNDARY","INTERFACE_OVERLAP_POSSIBLE");
        assertThat(result.trend()).hasSize(2);
        assertThat(history.window("foreign","router",null,"","",from,to,12,180).block(WAIT).totalBytes()).isNull();
    }
    @Test void windowPreservesMissingAndZeroAndFlagsLegacyEstimates(){
        Instant from=now.minusSeconds(120);
        var old=new Observation(ApplicationRates.hash("legacy"),"router",8,"eth8",42,"tls","IN",now,"999999999",null,null,80d,null,60d,"epoch",List.of());
        history.append(org,List.of(old,row("missing",9,"OUT","tls",now),interval("zero",10,now,"0",60))).block(WAIT);
        var result=history.window(org,"router",null,"","",from,now,12,180).block(WAIT);
        assertThat(result.inBytes()).isEqualTo("600");assertThat(result.outBytes()).isNull();assertThat(result.totalBytes()).isEqualTo("600");
        assertThat(result.meanBps()).isNull();assertThat(result.coverage()).isZero();assertThat(result.qualityFlags()).contains("HISTORICAL_RATE_ESTIMATE","PARTIAL_WINDOW");
        assertThat(result.trend().getFirst().inBps()).isNull();
        assertThat(history.window(org,"router",10,"IN","",from,now,12,180).block(WAIT).totalBytes()).isEqualTo("0");
    }
    @Test void windowRetainsUnsignedIncrementPrecisionAndSevenDayBuckets(){
        String delta="18446744073709551610";
        history.append(org,List.of(interval("huge",8,now,delta,60))).block(WAIT);
        var result=history.window(org,"router",8,"IN","",now.minusSeconds(7*86400),now,12,180).block(WAIT);
        assertThat(result.inBytes()).isEqualTo(delta);assertThat(result.items().getFirst().cumulativeBytes()).isEqualTo(delta);
        assertThat(result.resolutionSeconds()).isEqualTo(86400);assertThat(result.trend()).hasSize(7);
        assertThat(result.trend().getLast().inBytes()).isEqualTo(delta);assertThat(result.trend().getFirst().inBps()).isNull();
    }
    @Test void invalidIntervalsDoNotTurnMissingCountersIntoZeroTraffic(){
        var invalid=new Observation(ApplicationRates.hash("bad-delta"),"router",8,"eth8",42,"tls","IN",now,"999",null,null,10d,null,60d,"epoch",List.of(),"999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999");
        history.append(org,List.of(invalid)).block(WAIT);
        assertThat(history.window(org,"router",8,"IN","",now.minusSeconds(60),now,12,180).block(WAIT).totalBytes()).isNull();
    }
    @Test void windowExcludesOverlappingRepeatedBaselineIntervals(){
        history.append(org,List.of(interval("a",8,now.minusSeconds(30),"600",60),interval("b",8,now,"900",90))).block(WAIT);
        var result=history.window(org,"router",8,"IN","",now.minusSeconds(90),now,12,180).block(WAIT);
        assertThat(result.totalBytes()).isEqualTo("600");assertThat(result.qualityFlags()).contains("OVERLAPPING_INTERVAL_EXCLUDED");
    }
    @Test void queryAndWriteFailuresRemainUnavailable(){var bad=new ApplicationHistory(WebClient.builder(),new MockEnvironment().withProperty("NOERIVA_CLICKHOUSE_URL","http://127.0.0.1:1"),json);assertThatThrownBy(()->bad.append(org,List.of(row("a",8,"IN","http",now))).block(WAIT)).isInstanceOf(ApiException.class);assertThatThrownBy(()->bad.query(org,"router",null,"","",now.minusSeconds(60),now.plusSeconds(1),10,null).block(WAIT)).isInstanceOf(ApiException.class);}
}
