package io.noeriva.control;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class EntitySearchTest {
 private static final JsonMapper JSON=new JsonMapper();private HttpServer server;private EntitySearch search;
 private final AtomicReference<JsonNode> sent=new AtomicReference<>();private final AtomicReference<String> response=new AtomicReference<>();
 private final AtomicReference<String> path=new AtomicReference<>();
 @BeforeEach void start()throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{try{
   path.set(exchange.getRequestURI().getPath());
   String requestBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
   // Bulk requests are NDJSON (two JSON documents per indexed item), not one
   // ordinary JSON value. Only decode the search request captured by assertions.
   if(path.get().endsWith("/_search"))sent.set(JSON.readTree(requestBody));
   byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
  }finally{exchange.close();}});server.start();
  var env=new MockEnvironment().withProperty("NOERIVA_ELASTICSEARCH_URL","http://127.0.0.1:"+server.getAddress().getPort()).withProperty("NOERIVA_ELASTICSEARCH_PASSWORD","synthetic-search-test");
  search=new EntitySearch(new StaticListableBeanFactory().getBeanProvider(DatabaseClient.class),WebClient.builder(),env,JSON);
  response.set(payload(List.of(hit("tenant-a","port-id","device-id"))));
 }
 @AfterEach void stop(){server.stop(0);}
 private static Map<String,Object> hit(String org,String id,String device){return Map.of("_source",Map.of("organizationId",org,"kind","INTERFACE","id",id,"deviceId",device,"name","Te0/1/0","deviceName","ASR Router","address","00:11:22:33:44:55","indexedAt","2026-09-09T00:00:00Z"));}
 private static String payload(List<Map<String,Object>> rows){return JSON.writeValueAsString(Map.of("timed_out",false,"_shards",Map.of("failed",0),"hits",Map.of("hits",rows)));}
 @Test void tenantFilterLiteralWildcardAndInterfaceDeepLinkFieldsArePreserved(){
  var result=search.search("tenant-a","PoRt*?\\Name",7).block(Duration.ofSeconds(5));
  assertThat(path.get()).isEqualTo("/noeriva-entities-v1/_search");assertThat(sent.get().path("size").asInt()).isEqualTo(7);assertThat(sent.get().path("timeout").asText()).isEqualTo("2s");assertThat(sent.get().path("track_total_hits").asBoolean()).isFalse();
  var filter=sent.get().path("query").path("bool").path("filter");assertThat(filter.get(0).path("term").path("organizationId").asText()).isEqualTo("tenant-a");assertThat(filter.get(1).path("wildcard").path("text").path("value").asText()).isEqualTo("*port\\*\\?\\\\name*");
  assertThat(result.items()).hasSize(1);var port=result.items().getFirst();assertThat(port.id()).isEqualTo("port-id");assertThat(port.deviceId()).isEqualTo("device-id");assertThat(port.kind()).isEqualTo("INTERFACE");assertThat(port.source()).isEqualTo("ELASTICSEARCH");
 }
 @Test void foreignTenantResultFailsClosedInsteadOfReturningAnyPartialHits(){
  response.set(payload(List.of(hit("tenant-a","port-a","device-a"),hit("tenant-b","port-b","device-b"))));
  assertUnavailable();
 }
 @Test void timedOutAndFailedShardResponsesFailClosed(){
  response.set("{\"timed_out\":true,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":[]}}");assertUnavailable();
  response.set("{\"timed_out\":false,\"_shards\":{\"failed\":1},\"hits\":{\"hits\":[]}}");assertUnavailable();
 }
 @Test void missingHitEnvelopeCannotBecomeAnEmptySuccessfulSearch(){response.set("{}");assertUnavailable();}
 @Test void providerCannotExceedTheRequestedHitBudget(){response.set(payload(List.of(hit("tenant-a","a","device"),hit("tenant-a","b","device"))));assertThatThrownBy(()->search.search("tenant-a","port",1).block(Duration.ofSeconds(5))).isInstanceOf(ApiException.class);}
 @Test void indexingRequiresCompleteAcknowledgementsBeforeThePruningStage()throws Exception{
  var bulk=EntitySearch.class.getDeclaredMethod("bulk",List.class);bulk.setAccessible(true);
  var document=Map.<String,Object>of("organizationId","tenant-a","kind","DEVICE","id","device-a");
  response.set("{}");assertThatThrownBy(()->((reactor.core.publisher.Mono<?>)bulk.invoke(search,List.of(document))).block(Duration.ofSeconds(5))).isInstanceOf(RuntimeException.class);
  response.set("{\"errors\":false,\"items\":[]}");assertThatThrownBy(()->((reactor.core.publisher.Mono<?>)bulk.invoke(search,List.of(document))).block(Duration.ofSeconds(5))).isInstanceOf(RuntimeException.class);
  response.set("{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}");
  assertThat(((reactor.core.publisher.Mono<?>)bulk.invoke(search,List.of(document))).block(Duration.ofSeconds(5))).isNull();
 }
 private void assertUnavailable(){assertThatThrownBy(()->search.search("tenant-a","port",20).block(Duration.ofSeconds(5))).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code).isEqualTo("SEARCH_UNAVAILABLE"));}
}
