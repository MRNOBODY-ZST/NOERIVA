package io.noeriva.query;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;
import jakarta.annotation.PreDestroy;

/** Deterministic synthetic inputs are available only in the explicitly selected demo profile. */
@Component
@Profile("demo")
public final class DemoQueryRepository implements RollupRepository,MetricRepository {
    private final Scheduler simulator=Schedulers.newBoundedElastic(2,16,"noeriva-query-simulator");
    public Mono<RollupRepository.Result> loadRollups(String org,String device,String component,String direction,Instant from,Instant to) {
        return Mono.fromCallable(()->{
            List<CounterSample> samples=new ArrayList<>();
            long start=Math.floorDiv(from.getEpochSecond(),300)*300;
            long end=Math.floorDiv(to.getEpochSecond(),15)*15;
            BigInteger counter=new BigInteger("9007199254740993000");
            for(long second=start;second<=end;second+=15) {
                long tick=Math.floorDiv(second,15);
                double wave=1+0.48*Math.sin(second/9800.0)+0.16*Math.sin(second/460.0);
                long bytes=Math.floorMod(tick-1,5760)<240?0:(long)((direction.equals("tx")?55_000_000:130_000_000)*wave);
                counter=counter.add(BigInteger.valueOf(bytes));
                // One deterministic ten-minute collection gap every three days.
                if(Math.floorMod(tick,17280)>=6240&&Math.floorMod(tick,17280)<6280) continue;
                samples.add(new CounterSample(Instant.ofEpochSecond(second),counter,"synthetic-epoch-1",false));
            }
            var bins=new CounterRollup().aggregate(samples,Instant.ofEpochSecond(start),to,300,Duration.ofSeconds(45),1,to);
            return new RollupRepository.Result(bins,"SIMULATED");
        }).subscribeOn(simulator);
    }
    public Mono<MetricRepository.Result> loadMetrics(String org,String device,MetricDefinition metric,Instant from,Instant to,int limit) {
        return Mono.fromCallable(()->{
            int step=QueryService.step(from,to,limit);
            List<MetricPoint> points=new ArrayList<>();
            for(Instant time=from;!time.isAfter(to)&&points.size()<limit;time=time.plusSeconds(step)) {
                double wave=Math.sin(time.getEpochSecond()/360.0)+0.35*Math.sin(time.getEpochSecond()/71.0);
                double value=switch(metric.id()) {
                    case "cpu_percent" -> 34+wave*14;
                    case "memory_percent" -> 62+wave*5;
                    case "temperature_celsius" -> 48+wave*4;
                    case "power_watts" -> 342+wave*31;
                    case "bandwidth_tx_bps" -> 425_000_000+wave*80_000_000;
                    default -> 1_380_000_000+wave*280_000_000;
                };
                points.add(new MetricPoint(time,value));
            }
            return new MetricRepository.Result(List.copyOf(points),"SIMULATED",1,List.of("SIMULATED"));
        }).subscribeOn(simulator);
    }
    @PreDestroy public void close() {simulator.dispose();}
}
