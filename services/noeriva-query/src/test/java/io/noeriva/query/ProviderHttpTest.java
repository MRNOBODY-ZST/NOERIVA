package io.noeriva.query;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import java.math.BigDecimal;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import reactor.test.StepVerifier;

class ProviderHttpTest {
    private HttpServer server;
    private ExecutorService executor;
    private final Instant from=Instant.parse("2026-09-06T00:00:00Z"),to=from.plusSeconds(300);
    @AfterEach void stop() {if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();}
    private String serve(HttpHandler handler) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newFixedThreadPool(4);server.setExecutor(executor);server.createContext("/",handler);server.start();
        return "http://127.0.0.1:"+server.getAddress().getPort();
    }
    private static void respond(HttpExchange exchange,int status,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    @Test void clickhousePreservesDecimalCounterAndBindsScope() throws Exception {
        AtomicReference<String> request=new AtomicReference<>();
        String url=serve(exchange->{request.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8));respond(exchange,200,"""
            {"data":[{"startMillis":1788652800000,"endMillis":1788653100000,"validDurationSeconds":300,"validCounterDelta":"18446744073709551300","sampleCount":21,"revision":7,"generatedMillis":1788653100000,"qualityFlags":[]}]}
            """);});
        var repository=new ClickHouseRollupRepository(QueryHttpClient.create(url,null,null),"primary");
        StepVerifier.create(repository.loadRollups("org-a","device-a","eth0","rx",from,to)).assertNext(result->{
            assertEquals("18446744073709551300",result.buckets().getFirst().validCounterDelta().toPlainString());assertEquals(7,result.buckets().getFirst().revision());
        }).verifyComplete();
        assertTrue(request.get().contains("param_organization=org-a"));assertTrue(request.get().contains("max_execution_time=5"));
    }
    @Test void cancellationSendsKillForTheActualQueryId() throws Exception {
        CountDownLatch began=new CountDownLatch(1),killed=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<String> queryId=new AtomicReference<>(),killId=new AtomicReference<>();
        String url=serve(exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            String raw=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
            if(body.startsWith("KILL QUERY")) {killId.set(raw.split("param_queryId=")[1].split("&")[0]);respond(exchange,200,"{}");killed.countDown();return;}
            queryId.set(raw.split("query_id=")[1].split("&")[0]);began.countDown();
            try {release.await(5,TimeUnit.SECONDS);respond(exchange,200,"{\"data\":[]}");}catch(InterruptedException e){Thread.currentThread().interrupt();}
        });
        var repository=new ClickHouseRollupRepository(QueryHttpClient.create(url,null,null),"primary");
        var subscription=repository.loadRollups("org-a","device-a","eth0","rx",from,to).subscribe();
        assertTrue(began.await(3,TimeUnit.SECONDS));subscription.dispose();
        assertTrue(killed.await(3,TimeUnit.SECONDS));assertEquals(queryId.get(),killId.get());release.countDown();
    }
    @Test void victoriaMetricsKeepsNanMissingAndEnforcesScopeAndTimeout() throws Exception {
        AtomicReference<String> query=new AtomicReference<>();
        String url=serve(exchange->{query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8));respond(exchange,200,"""
            {"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[[1788652800,"0"],[1788652830,"NaN"]]}]}}
            """);});
        var repository=new VictoriaMetricsRepository(QueryHttpClient.create(url,null,null));
        StepVerifier.create(repository.loadMetrics("org-a","device-a",MetricDefinition.require("cpu_percent"),from,to,11)).assertNext(result->{
            assertEquals(0.0,result.points().getFirst().value());assertNull(result.points().get(1).value());assertTrue(result.qualityFlags().contains("NON_FINITE_SAMPLE"));
        }).verifyComplete();
        assertTrue(query.get().contains("organization_id=\"org-a\""));assertTrue(query.get().contains("device_id=\"device-a\""));assertTrue(query.get().contains("timeout=5s"));
    }
    @Test void failedProviderDoesNotBecomeEmptyData() throws Exception {
        String url=serve(exchange->respond(exchange,503,"{\"error\":\"unavailable\"}"));
        var repository=new VictoriaMetricsRepository(QueryHttpClient.create(url,null,null));
        StepVerifier.create(repository.loadMetrics("org-a","device-a",MetricDefinition.require("cpu_percent"),from,to,11)).expectError(ProviderUnavailableException.class).verify();
    }
    @Test void sixHourChartUsesInWindowFractionalGridWithoutProviderCacheRounding() throws Exception {
        Instant chartFrom=Instant.parse("2026-09-06T02:06:38.123Z"),chartTo=chartFrom.plusSeconds(21600);
        AtomicReference<String> query=new AtomicReference<>();
        String url=serve(exchange->{
            String parameters=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
            query.set(parameters);
            // The uncached provider preserves the requested grid; cache rounding can escape the window.
            String requestedStart=parameters.split("start=")[1].split("&")[0];
            String timestamp=parameters.contains("nocache=1")?BigDecimal.valueOf(Instant.parse(requestedStart).toEpochMilli(),3).toPlainString():"1788660328";
            respond(exchange,200,"{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},\"values\":[["+timestamp+",\"54.977\"]]}]}}");
        });
        var repository=new VictoriaMetricsRepository(QueryHttpClient.create(url,null,null));
        StepVerifier.create(repository.loadMetrics("org-a","device-a",MetricDefinition.require("cpu_percent"),chartFrom,chartTo,120))
            .assertNext(result->{assertTrue(!result.points().getFirst().timestamp().isBefore(chartFrom));assertTrue(!result.points().getFirst().timestamp().isAfter(chartTo));assertEquals(54.977,result.points().getFirst().value());})
            .verifyComplete();
        assertTrue(query.get().contains("step=182"));assertTrue(query.get().contains("nocache=1"));
        assertTrue(query.get().contains("end="+chartTo));
    }
    @Test void newlyEnabledMetricAppearsAtTheEndOfA24HourChart() throws Exception {
        Instant end=Instant.parse("2026-09-07T04:58:45.123Z"),begin=end.minusSeconds(86400);
        String url=serve(exchange->{
            Map<String,String> parameters=new HashMap<>();
            for(String pair:exchange.getRequestURI().getRawQuery().split("&")) {
                String[] fields=pair.split("=",2);parameters.put(fields[0],URLDecoder.decode(fields[1],StandardCharsets.UTF_8));
            }
            Instant start=Instant.parse(parameters.get("start")),stop=Instant.parse(parameters.get("end"));
            long step=Long.parseLong(parameters.get("step"));
            List<List<Object>> samples=new ArrayList<>();
            // A real new source exists only in the final five minutes of the requested day.
            for(Instant at=start;!at.isAfter(stop);at=at.plusSeconds(step))
                if(!at.isBefore(end.minusSeconds(300)))samples.add(List.of(BigDecimal.valueOf(at.toEpochMilli(),3),"12345"));
            respond(exchange,200,JsonMapper.builder().build().writeValueAsString(Map.of("status","success","data",Map.of("resultType","matrix","result",samples.isEmpty()?List.of():List.of(Map.of("metric",Map.of(),"values",samples))))));
        });
        var repository=new VictoriaMetricsRepository(QueryHttpClient.create(url,null,null));
        StepVerifier.create(repository.loadMetrics("org-a","device-a",MetricDefinition.require("bandwidth_rx_bps"),begin,end,120))
            .assertNext(result->{assertFalse(result.points().isEmpty(),"new samples cannot disappear because of grid alignment");assertEquals(end,result.points().getLast().timestamp());assertTrue(result.points().size()<=120);})
            .verifyComplete();
    }
    @Test void outOfWindowProviderPointStillFailsRatherThanBeingDropped() throws Exception {
        String url=serve(exchange->respond(exchange,200,"""
            {"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[[1788652799,"42"],[1788652800,"43"]]}]}}
            """));
        var repository=new VictoriaMetricsRepository(QueryHttpClient.create(url,null,null));
        StepVerifier.create(repository.loadMetrics("org-a","device-a",MetricDefinition.require("cpu_percent"),from,to,11))
            .expectError(ProviderUnavailableException.class).verify();
    }
    @Test void subMillisecondBoundsAreRoundedInwardToTheProviderClock() throws Exception {
        Instant chartFrom=Instant.parse("2026-09-06T02:06:38.123456789Z"),chartTo=chartFrom.plusSeconds(21600);
        AtomicReference<String> query=new AtomicReference<>();
        String url=serve(exchange->{
            String parameters=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
            query.set(parameters);
            String requestedStart=parameters.split("start=")[1].split("&")[0];
            String timestamp=BigDecimal.valueOf(Instant.parse(requestedStart).toEpochMilli(),3).toPlainString();
            respond(exchange,200,"{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},\"values\":[["+timestamp+",\"54.977\"]]}]}}");
        });
        var repository=new VictoriaMetricsRepository(QueryHttpClient.create(url,null,null));
        StepVerifier.create(repository.loadMetrics("org-a","device-a",MetricDefinition.require("cpu_percent"),chartFrom,chartTo,120))
            .assertNext(result->{var stamp=result.points().getFirst().timestamp();assertFalse(stamp.isBefore(chartFrom));assertFalse(stamp.isAfter(chartTo));assertEquals(0,stamp.getNano()%1_000_000);})
            .verifyComplete();
        assertFalse(query.get().contains("start=2026-09-06T02:06:38.123Z"));
        assertTrue(query.get().contains("end=2026-09-06T08:06:38.123Z"));
    }
    @Test void rangeWithoutAnyRepresentableMillisecondReturnsNoSamplesWithoutWideningIt() throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        String url=serve(exchange->{requests.incrementAndGet();respond(exchange,503,"{}");});
        var repository=new VictoriaMetricsRepository(QueryHttpClient.create(url,null,null));
        Instant start=Instant.parse("2026-09-06T02:06:38.123100Z"),end=Instant.parse("2026-09-06T02:06:38.123900Z");
        StepVerifier.create(repository.loadMetrics("org-a","device-a",MetricDefinition.require("cpu_percent"),start,end,2))
            .assertNext(result->assertTrue(result.points().isEmpty())).verifyComplete();
        assertEquals(0,requests.get());
    }
    @Test void rawExportToVersionedInsertPreservesTimeAndCorrectsWithoutAdding() throws Exception {
        JsonMapper json=JsonMapper.builder().build();
        var scale=new java.util.concurrent.atomic.AtomicInteger(100);
        List<String> inserts=new CopyOnWriteArrayList<>();
        List<String> wireQueries=new CopyOnWriteArrayList<>();
        String url=serve(exchange->{
            wireQueries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8));
            if(exchange.getRequestURI().getPath().equals("/api/v1/export")) {
                var values=new ArrayList<BigDecimal>();var timestamps=new ArrayList<Long>();
                for(int second=0;second<=300;second+=15) {
                    values.add(new BigDecimal("9007199254741000").add(BigDecimal.valueOf((long)second*scale.get())));
                    timestamps.add(from.plusSeconds(second).toEpochMilli());
                }
                respond(exchange,200,json.writeValueAsString(Map.of("metric",Map.of("organization_id","org-a","device_id","device-a","interface_id","eth0","source_id","primary","source_epoch","boot-1"),"values",values,"timestamps",timestamps))+"\n");
            } else {
                inserts.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));respond(exchange,200,"");
            }
        });
        var revision=new java.util.concurrent.atomic.AtomicLong();var checkpoints=new java.util.concurrent.atomic.AtomicInteger();
        RollupStateStore state=new RollupStateStore() {
            public reactor.core.publisher.Mono<Long> nextRevision(RollupKey key) {return reactor.core.publisher.Mono.fromSupplier(revision::incrementAndGet);}
            public reactor.core.publisher.Mono<Void> checkpoint(RollupKey key,Instant first,Instant through,Instant complete,long rev,double coverage) {
                return reactor.core.publisher.Mono.fromRunnable(()->{assertEquals(to,complete);assertTrue(inserts.size()>checkpoints.get());checkpoints.incrementAndGet();});
            }
        };
        var raw=new VictoriaRawCounterRepository(QueryHttpClient.create(url,null,null));
        var writer=new ClickHouseRollupWriter(QueryHttpClient.create(url,null,null));
        var worker=new RollupWorker(raw,writer,state,Clock.fixed(to.plusSeconds(120),ZoneOffset.UTC));
        try {
            StepVerifier.create(worker.recompute("org-a","device-a","eth0","primary",from,to,"rx")).expectNext(1).verifyComplete();
            scale.set(200);
            StepVerifier.create(worker.recompute("org-a","device-a","eth0","primary",from,to,"rx")).expectNext(1).verifyComplete();
            var first=json.readTree(inserts.getFirst().split("\n")[1]);var second=json.readTree(inserts.get(1).split("\n")[1]);
            assertEquals("30000.000000",first.get("valid_counter_delta").asString());
            assertEquals("60000.000000",second.get("valid_counter_delta").asString());
            assertEquals(1,first.get("revision").asInt());assertEquals(2,second.get("revision").asInt());
            assertEquals(from.toString(),second.get("bucket_start_utc").asString());
            assertTrue(second.get("quality_flags").toString().contains("COUNTER_QUANTIZATION"));
            assertEquals(2,checkpoints.get());
            assertTrue(wireQueries.stream().anyMatch(q->q.contains("async_insert=0")));
            assertTrue(wireQueries.stream().anyMatch(q->q.contains("source_id=\"primary\"")));
        } finally {worker.close();raw.close();writer.close();}
    }
    @Test void http200WithLateClickhouseErrorIsNotASuccessfulInsert() throws Exception {
        String url=serve(exchange->respond(exchange,200,"Code: 241. DB::Exception: Memory limit exceeded. (__exception__)"));
        var writer=new ClickHouseRollupWriter(QueryHttpClient.create(url,null,null));
        var snapshot=new RollupBucket(from,to,300,BigDecimal.valueOf(30000),21,1,to,Set.of());
        try {
            StepVerifier.create(writer.write(new RollupKey("org-a","device-a","eth0","primary","rx"),List.of(snapshot)))
                .expectError(ProviderUnavailableException.class).verify();
        } finally {writer.close();}
    }
}
