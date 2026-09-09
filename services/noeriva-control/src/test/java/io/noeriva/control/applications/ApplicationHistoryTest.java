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
    @Test void queryAndWriteFailuresRemainUnavailable(){var bad=new ApplicationHistory(WebClient.builder(),new MockEnvironment().withProperty("NOERIVA_CLICKHOUSE_URL","http://127.0.0.1:1"),json);assertThatThrownBy(()->bad.append(org,List.of(row("a",8,"IN","http",now))).block(WAIT)).isInstanceOf(ApiException.class);assertThatThrownBy(()->bad.query(org,"router",null,"","",now.minusSeconds(60),now.plusSeconds(1),10,null).block(WAIT)).isInstanceOf(ApiException.class);}
}
