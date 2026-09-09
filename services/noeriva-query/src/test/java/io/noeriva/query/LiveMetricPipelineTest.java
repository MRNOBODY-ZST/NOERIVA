package io.noeriva.query;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in integration against the authorized local COMPACT engines; creates only namespaced synthetic observations. */
@EnabledIfEnvironmentVariable(named="NOERIVA_LIVE_QUERY_TEST",matches="true")
class LiveMetricPipelineTest {
    @Test void storedRawSamplesProduceQueryableReplacementBucket() {
        String vmUrl=System.getenv("NOERIVA_METRICS_URL"),chUrl=System.getenv("NOERIVA_CLICKHOUSE_URL");
        String password=System.getenv("NOERIVA_CLICKHOUSE_PASSWORD");
        var vm=QueryHttpClient.create(vmUrl,null,null);
        var ch=QueryHttpClient.create(chUrl,"noeriva",password);
        var raw=new VictoriaRawCounterRepository(vm);var writer=new ClickHouseRollupWriter(ch);
        var reader=new ClickHouseRollupRepository(ch,"primary");
        var revision=new AtomicLong();
        RollupStateStore state=new RollupStateStore() {
            public Mono<Long> nextRevision(RollupKey key) {return Mono.fromSupplier(revision::incrementAndGet);}
            public Mono<Void> checkpoint(RollupKey key,Instant start,Instant end,Instant complete,long rev,double coverage) {
                assertNotNull(complete);assertEquals(1.0,coverage);return Mono.empty();
            }
        };
        var worker=new RollupWorker(raw,writer,state);
        String org="query-it-"+UUID.randomUUID();
        Instant from=Instant.ofEpochSecond(Math.floorDiv(Instant.now().getEpochSecond(),300)*300-600),to=from.plusSeconds(300);
        List<Long> times=new ArrayList<>(),values=new ArrayList<>();
        for(int second=0;second<=300;second+=15){times.add(from.plusSeconds(second).toEpochMilli());values.add(1_000_000L+second*100L);}
        var payload=new JsonMapper().writeValueAsString(Map.of("metric",Map.of("__name__","noeriva_interface_receive_bytes_total","organization_id",org,"device_id","device-live-test","interface_id","eth0","source_id","primary","source_epoch","boot-it"),"values",values,"timestamps",times));
        try {
            StepVerifier.create(vm.post().uri("/api/v1/import").bodyValue(payload+"\n").retrieve().toBodilessEntity()
                .then(awaitSourceVisibility(raw,new RollupKey(org,"device-live-test","eth0","primary","rx"),from,to,times,values))
                .then(worker.recompute(org,"device-live-test","eth0","primary",from,to,"rx")))
                .expectNext(1).expectComplete().verify(Duration.ofSeconds(45));
            StepVerifier.create(reader.loadRollups(org,"device-live-test","eth0","rx",from,to))
                .assertNext(result->{assertEquals(1,result.buckets().size());var bucket=result.buckets().getFirst();
                    assertEquals(30_000,bucket.validCounterDelta().intValueExact());assertEquals(800.0,bucket.averageBps());
                    assertEquals(to,bucket.lastObserved());assertEquals(1,bucket.revision());}).verifyComplete();
        } finally {worker.close();raw.close();writer.close();}
    }

    /** Import acknowledgement precedes export visibility. Poll only readiness; never retry computation or provider errors. */
    private static Mono<Void> awaitSourceVisibility(RawCounterRepository raw,RollupKey key,Instant from,Instant to,
            List<Long> timestamps,List<Long> values) {
        return Mono.defer(()->raw.loadCounters(key,from.minusSeconds(90),to.plusSeconds(90)))
            .filter(result->{
                Map<Long,java.math.BigInteger> observed=new HashMap<>();
                for(var sample:result.samples()) {
                    assertEquals("boot-it",sample.sourceEpoch());
                    assertNull(observed.put(sample.timestamp().toEpochMilli(),sample.value()),"Unexpected duplicate source sample");
                }
                assertTrue(observed.size()<=timestamps.size(),"Export contained unexpected extra samples");
                for(int i=0;i<timestamps.size();i++) {
                    var actual=observed.get(timestamps.get(i));
                    if(actual!=null)assertEquals(java.math.BigInteger.valueOf(values.get(i)),actual,"Imported counter changed");
                }
                assertTrue(timestamps.containsAll(observed.keySet()),"Export contained unexpected source timestamps");
                return observed.size()==timestamps.size();
            })
            .repeatWhenEmpty(repeats->repeats.delayElements(Duration.ofMillis(250)))
            .timeout(Duration.ofSeconds(25),Mono.error(new AssertionError("Imported raw samples were not fully export-visible within 25 seconds")))
            .then();
    }
}
