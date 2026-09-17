package io.noeriva.control.nat;

import io.noeriva.control.SecurityConfiguration.Operator;
import io.noeriva.control.ApiException;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static io.noeriva.control.nat.NatModels.*;

@Testcontainers class NatHistoryIntegrationTest {
 @Container static final GenericContainer<?> CH=new GenericContainer<>("clickhouse/clickhouse-server:26.3.32.14").withExposedPorts(8123).withEnv("CLICKHOUSE_USER","test").withEnv("CLICKHOUSE_PASSWORD","synthetic-nat-history-only").withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT","1").waitingFor(Wait.forHttp("/ping").forPort(8123));
 static final JsonMapper JSON=new JsonMapper();static final Duration WAIT=Duration.ofSeconds(20);static WebClient http;NatHistory history;Operator actor;String org;
 @BeforeAll static void schema()throws Exception{http=WebClient.builder().baseUrl("http://"+CH.getHost()+":"+CH.getMappedPort(8123)).defaultHeaders(h->h.setBasicAuth("test","synthetic-nat-history-only")).build();sql("CREATE DATABASE noeriva");Path file=Path.of("../../deploy/kubernetes/two-node/config/003-nat-audit.sql");String text=Files.readString(file);sql(text.substring(text.indexOf("CREATE TABLE"),text.indexOf(";",text.indexOf("CREATE TABLE"))));for(String statement:Files.readString(Path.of("../../deploy/kubernetes/two-node/config/005-nat-receipt-index.sql")).split(";")){if(statement.contains("CREATE TABLE")||statement.contains("CREATE MATERIALIZED VIEW"))sql(statement);}}
 static void sql(String value){try{http.post().uri("/").bodyValue(value).retrieve().bodyToMono(String.class).block(WAIT);}catch(org.springframework.web.reactive.function.client.WebClientResponseException error){throw new AssertionError(error.getResponseBodyAsString(),error);}}
 @BeforeEach void setup(){org=UUID.randomUUID().toString();actor=new Operator("viewer","unused",org,List.of("VIEWER"));var sources=new NatSourceStore(null,null,JSON,Clock.systemUTC()){@Override public Mono<Void> requireDevice(String o,String d){return Mono.empty();}};history=new NatHistory(WebClient.builder(),new MockEnvironment().withProperty("NOERIVA_CLICKHOUSE_URL","http://"+CH.getHost()+":"+CH.getMappedPort(8123)).withProperty("NOERIVA_CLICKHOUSE_USERNAME","test").withProperty("NOERIVA_CLICKHOUSE_PASSWORD","synthetic-nat-history-only"),JSON,sources);}
 NatEvent event(int seq,Instant received,int protocol){return new NatDecoder().decode(org,"gateway","site","192.0.2.1",NatDecoderTest.packet(1,seq,1000,NatDecoderTest.template(NatDecoderTest.FIELDS),NatDecoderTest.data(NatDecoderTest.FIELDS,protocol,1,seq)),received).events().getFirst();}
 EventPage page(Operator a,int limit,String cursor,Integer protocol){return history.events(a,"gateway",NatDecoderTest.NOW.minusSeconds(10),NatDecoderTest.NOW.plusSeconds(3600),protocol,"","",limit,cursor).block(WAIT);}
 @Test void persistedEventsAreScopedFilteredAndCursorPagedWithoutDuplicateReplay(){var a=event(1,NatDecoderTest.NOW,6);var b=event(2,NatDecoderTest.NOW.plusSeconds(1),17);var c=event(3,NatDecoderTest.NOW.plusSeconds(2),6);var batch=new Envelope(org,"gateway",List.of(a,b,c));history.append(batch).block(WAIT);history.append(batch).block(WAIT);var p=page(actor,2,"",null);assertThat(p.items()).hasSize(2);assertThat(p.nextCursor()).isNotBlank();assertThat(page(actor,2,p.nextCursor(),null).items()).hasSize(1);assertThat(page(actor,100,"",6).items()).hasSize(2);assertThat(page(new Operator("other","unused","foreign",List.of("VIEWER")),100,"",null).items()).isEmpty();assertThatThrownBy(()->page(actor,2,p.nextCursor(),6)).isInstanceOf(IllegalArgumentException.class);}
 @Test void sameRawPacketReplayAfterReceiverRestartKeepsEarliestCaptureOnly(){var first=event(1,NatDecoderTest.NOW,6);var replay=event(1,NatDecoderTest.NOW.plusSeconds(60),6);history.append(new Envelope(org,"gateway",List.of(first))).block(WAIT);history.append(new Envelope(org,"gateway",List.of(replay))).block(WAIT);var p=page(actor,100,"",null);assertThat(p.items()).hasSize(1);assertThat(p.items().getFirst().receivedAt()).isEqualTo(NatDecoderTest.NOW);var narrow=history.events(actor,"gateway",NatDecoderTest.NOW.plusSeconds(30),NatDecoderTest.NOW.plusSeconds(90),null,"","",10,"").block(WAIT);assertThat(narrow.items()).isEmpty();}
 @Test void explicitIpFilterAndMissingDeviceTimeRoundTrip(){int[][] f=Arrays.copyOf(NatDecoderTest.FIELDS,11);var e=new NatDecoder().decode(org,"gateway","site","192.0.2.1",NatDecoderTest.packet(1,1,1000,NatDecoderTest.template(f),NatDecoderTest.data(f,6,1,0)),NatDecoderTest.NOW).events().getFirst();history.append(new Envelope(org,"gateway",List.of(e))).block(WAIT);var p=history.events(actor,"gateway",NatDecoderTest.NOW.minusSeconds(1),NatDecoderTest.NOW.plusSeconds(1),6,"192.0.2.10","198.51.100.10",10,"").block(WAIT);assertThat(p.items()).hasSize(1);assertThat(p.items().getFirst().deviceEventAt()).isNull();assertThat(p.items().getFirst().qualityFlags()).contains("DEVICE_TIME_MISSING");}
 @Test void sinkFailureCannotBecomeAnEmptySuccessfulHistory(){var bad=new NatHistory(WebClient.builder(),new MockEnvironment().withProperty("NOERIVA_CLICKHOUSE_URL","http://127.0.0.1:1").withProperty("NOERIVA_CLICKHOUSE_PASSWORD","synthetic-nat-history-only"),JSON,new NatSourceStore(null,null,JSON,Clock.systemUTC()){@Override public Mono<Void> requireDevice(String o,String d){return Mono.empty();}});assertThatThrownBy(()->bad.events(actor,"gateway",NatDecoderTest.NOW,NatDecoderTest.NOW.plusSeconds(1),null,"","",10,"").block(WAIT)).isInstanceOf(ApiException.class).extracting(e->((ApiException)e).code).isEqualTo("NAT_HISTORY_UNAVAILABLE");}
 @Test void allRetainedCursorFreezesCutoffAndRejectsOtherTenantsOrChangedBounds(){
  history.append(new Envelope(org,"gateway",List.of(event(1,NatDecoderTest.NOW.minus(Duration.ofDays(500)),6),event(2,NatDecoderTest.NOW.minus(Duration.ofDays(30)),6),event(3,NatDecoderTest.NOW,6)))).block(WAIT);
  var first=history.events(actor,"gateway",null,null,null,"","",1,"").block(WAIT);assertThat(first.nextCursor()).isNotBlank();
  history.append(new Envelope(org,"gateway",List.of(event(4,Instant.now().plusSeconds(2),6)))).block(WAIT);
  var second=history.events(actor,"gateway",null,null,null,"","",1,first.nextCursor()).block(WAIT);
  assertThat(second.items().getFirst().receivedAt()).isEqualTo(NatDecoderTest.NOW.minus(Duration.ofDays(30)));
  assertThat(history.events(actor,"gateway",null,null,null,"","",1,second.nextCursor()).block(WAIT).items().getFirst().receivedAt()).isEqualTo(NatDecoderTest.NOW.minus(Duration.ofDays(500)));
  assertThatThrownBy(()->history.events(new Operator("viewer","unused","other",List.of("VIEWER")),"gateway",null,null,null,"","",1,first.nextCursor())).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->history.events(actor,"gateway",NatDecoderTest.NOW.minusSeconds(1),NatDecoderTest.NOW.plusSeconds(1),null,"","",1,first.nextCursor())).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->history.events(actor,"gateway",null,NatDecoderTest.NOW,null,"","",1,"")).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void indexedReplaysNeverReappearEvenWhenCanonicalFirstReceiptIsOutsideSelectedRange(){
  history.append(new Envelope(org,"gateway",List.of(event(1,NatDecoderTest.NOW.minus(Duration.ofDays(100)),6)))).block(WAIT);
  for(int i=0;i<140;i++)history.append(new Envelope(org,"gateway",List.of(event(1,NatDecoderTest.NOW.plusSeconds(i),6)))).block(WAIT);
  var first=history.events(actor,"gateway",NatDecoderTest.NOW.minusSeconds(1),NatDecoderTest.NOW.plusSeconds(200),null,"","",50,"").block(WAIT);
  assertThat(first.items()).isEmpty();assertThat(first.nextCursor()).isNotBlank();
  var second=history.events(actor,"gateway",NatDecoderTest.NOW.minusSeconds(1),NatDecoderTest.NOW.plusSeconds(200),null,"","",50,first.nextCursor()).block(WAIT);assertThat(second.items()).isEmpty();assertThat(second.nextCursor()).isNull();
 }
 @Test void millionDuplicateReceiptKeysStillAdvanceWithoutDuplicatesAndActualReadLimitStillThrows(){
  try {
  var oldest=event(1,NatDecoderTest.NOW,6);var a=event(2,NatDecoderTest.NOW.plusSeconds(1),6);var b=event(3,NatDecoderTest.NOW.plusSeconds(1),6);var newer=a.id().compareTo(b.id())>0?a:b;var older=newer==a?b:a;history.append(new Envelope(org,"gateway",List.of(oldest,older,newer))).block(WAIT);
  sql("INSERT INTO noeriva.nat_audit_receipts SELECT organization_id,device_id,received_at,event_id,exported_at,private_ip,public_ip,protocol FROM (SELECT * FROM noeriva.nat_audit_receipts WHERE organization_id='"+org+"' AND device_id='gateway' ORDER BY received_at DESC,event_id DESC LIMIT 1) AS source CROSS JOIN numbers(1000000) AS duplicates");
  var first=history.events(actor,"gateway",null,null,null,"","",50,"").block(WAIT);assertThat(first.items()).extracting(NatEvent::id).containsExactly(newer.id());assertThat(first.nextCursor()).isNotBlank();
  var second=history.events(actor,"gateway",null,null,null,"","",50,first.nextCursor()).block(WAIT);assertThat(second.items()).extracting(NatEvent::id).containsExactly(older.id(),oldest.id());assertThat(second.nextCursor()).isNull();
  // This deliberately scans real rows instead of relying on count() metadata.
  // It must fail, proving local BREAK never permits silent partial reads because
  // the actual leaf row/byte guard throws first.
  assertThatThrownBy(()->http.post().uri("/?max_rows_to_read=0&max_bytes_to_read=0&read_overflow_mode=break&max_rows_to_read_leaf=200000&max_bytes_to_read_leaf=134217728&read_overflow_mode_leaf=throw&max_execution_time=4&timeout_overflow_mode=throw&max_threads=2&max_block_size=256")
      .bodyValue("SELECT sum(cityHash64(event_id)) FROM noeriva.nat_audit_receipts WHERE organization_id='"+org+"' AND device_id='gateway'").retrieve().bodyToMono(String.class).block(WAIT)).isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class)
      .satisfies(error->assertThat(((org.springframework.web.reactive.function.client.WebClientResponseException)error).getResponseBodyAsString()).contains("TOO_MANY_ROWS"));
  }catch(Exception error){sql("SYSTEM FLUSH LOGS");String details=http.post().bodyValue("SELECT query,exception,read_rows FROM system.query_log WHERE type IN (\'ExceptionWhileProcessing\',\'ExceptionBeforeStart\') AND startsWith(query_id,\'noeriva-nat-\') FORMAT JSONEachRow").retrieve().bodyToMono(String.class).block(WAIT);throw new AssertionError(details,error);}
 }
 @Test void shuffledExporterClockHistoryRemainsQueryableWithinUnchangedReadBudget(){
  // Complete synthetic event payloads over ten days; exporter order deliberately
  // destroys received_at minmax locality. A payload-first scan exceeds 128 MiB.
  var baseline=event(1,NatDecoderTest.NOW,6);var payload=new LinkedHashMap<String,Object>(JSON.readValue(JSON.writeValueAsString(baseline),Map.class));
  payload.put("id","__ID__");payload.put("receivedAt","__RECEIVED__");payload.put("exportedAt","__EXPORTED__");payload.put("qualityFlags",List.of("UDP_UNAUTHENTICATED","COMPLETENESS_NOT_GUARANTEED","EXPORTER_CLOCK_SKEW","SYNTHETIC"));
  Instant end=Instant.parse("2026-09-07T00:00:00Z");long endMillis=end.toEpochMilli();String template=JSON.writeValueAsString(payload).replace("\\","\\\\").replace("'","\\'");
  sql("INSERT INTO noeriva.nat_audit_events WITH lower(hex(SHA256(toString(number)))) AS synthetic_id, fromUnixTimestamp64Milli(toInt64("+endMillis+")-toInt64((200000-number)*4320)) AS captured, fromUnixTimestamp64Milli(toInt64("+endMillis+")-toInt64(cityHash64(synthetic_id)%864000000)) AS exported SELECT '"+org+"','gateway',synthetic_id,exported,captured,'192.0.2.10','198.51.100.10',6,replaceAll(replaceAll(replaceAll('"+template+"','__ID__',synthetic_id),'__RECEIVED__',formatDateTime(captured,'%Y-%m-%dT%H:%i:%S.%fZ')),'__EXPORTED__',formatDateTime(exported,'%Y-%m-%dT%H:%i:%S.%fZ')),toUInt64(9223372036854775807)-toUInt64(toUnixTimestamp64Milli(captured)) FROM numbers(200000)");
  for(int hours:List.of(1,24,240)){
   var result=history.events(actor,"gateway",end.minus(Duration.ofHours(hours)),end,null,"","",100,"").block(WAIT);
   assertThat(result.items()).hasSize(100);assertThat(result.nextCursor()).isNotBlank();
   for(int i=0;i<100;i++){assertThat(result.items().get(i).id()).isEqualTo(NatDecoder.sha(Integer.toString(199999-i).getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertThat(result.items().get(i).receivedAt()).isEqualTo(end.minusMillis((i+1)*4320L));}
   var next=history.events(actor,"gateway",end.minus(Duration.ofHours(hours)),end,null,"","",100,result.nextCursor()).block(WAIT);assertThat(next.items().getFirst().id()).isEqualTo(NatDecoder.sha("199899".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
  var all=history.events(actor,"gateway",null,null,null,"","",100,"").block(WAIT);assertThat(all.items()).hasSize(100);assertThat(all.nextCursor()).isNotBlank();
  sql("SYSTEM FLUSH LOGS");
  String stats=http.post().bodyValue("SELECT max(read_rows) FROM system.query_log WHERE type='QueryFinish' AND query LIKE 'SELECT received_at AS receivedAt%nat_audit_receipts%' FORMAT TSV").retrieve().bodyToMono(String.class).block(WAIT);
  assertThat(Long.parseLong(stats.trim())).as("Ordered receipt lookup must stop far before a 200k retained-history scan").isLessThan(100000L);
 }
}
