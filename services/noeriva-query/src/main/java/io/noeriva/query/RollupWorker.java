package io.noeriva.query;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;
import jakarta.annotation.PreDestroy;

@Service
@Profile("!demo")
public final class RollupWorker {
    private final RawCounterRepository source;
    private final RollupWriter sink;
    private final RollupStateStore state;
    private final Clock clock;
    private final QueryAdmission admission=new QueryAdmission(2,Duration.ofSeconds(30));
    private final Scheduler computation=Schedulers.newBoundedElastic(2,8,"noeriva-rollup-worker");
    @Autowired
    public RollupWorker(RawCounterRepository source,RollupWriter sink,RollupStateStore state) {this(source,sink,state,Clock.systemUTC());}
    public RollupWorker(RawCounterRepository source,RollupWriter sink,RollupStateStore state,Clock clock) {
        this.source=source;this.sink=sink;this.state=state;this.clock=clock;
    }
    public Mono<Integer> recompute(String org,String device,String component,String source,Instant from,Instant to,String direction) {
        return admission.execute(QueryLane.REPLAY_REBUILD,()->{
            RollupKey key=new RollupKey(org,device,component,source,direction);
            if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(1))>0
                    ||from.getNano()!=0||to.getNano()!=0||Math.floorMod(from.getEpochSecond(),300)!=0
                    ||Math.floorMod(to.getEpochSecond(),300)!=0||to.isAfter(clock.instant()))
                throw new IllegalArgumentException("Recompute requires closed, UTC-five-minute-aligned windows of at most one day");
            return state.nextRevision(key).flatMap(revision->this.source.loadCounters(key,from.minusSeconds(90),to.plusSeconds(90))
                .publishOn(computation).map(raw->snapshots(raw,from,to,revision))
                .flatMap(buckets->{
                    double observed=buckets.stream().mapToDouble(RollupBucket::validDurationSeconds).sum();
                    Instant first=buckets.getFirst().start(),through=buckets.getLast().end();
                    double coverage=observed/Duration.between(first,through).toSeconds();
                    Instant complete=coverage>=0.999999&&first.equals(from)&&through.equals(to)?through:null;
                    return sink.write(key,buckets).then(state.checkpoint(key,first,through,complete,revision,coverage)).thenReturn(buckets.size());
                }));
        });
    }
    private List<RollupBucket> snapshots(RawCounterRepository.Result raw,Instant from,Instant to,long revision) {
        if(raw.samples().size()<2) throw new ProviderUnavailableException("VICTORIAMETRICS","RECOMPUTATION_UNAVAILABLE: raw inputs are not yet visible or no longer retained");
        Instant earliest=raw.samples().stream().map(CounterSample::timestamp).min(Comparator.naturalOrder()).orElseThrow();
        Instant latest=raw.samples().stream().map(CounterSample::timestamp).max(Comparator.naturalOrder()).orElseThrow();
        var result=new CounterRollup().aggregate(raw.samples(),from,to,300,Duration.ofSeconds(90),revision,clock.instant()).stream()
            .filter(b->!b.start().isBefore(earliest)&&!b.end().isAfter(latest))
            .map(b->{var flags=new TreeSet<>(b.qualityFlags());flags.addAll(raw.qualityFlags());
                return new RollupBucket(b.start(),b.end(),b.validDurationSeconds(),b.validCounterDelta(),b.sampleCount(),b.revision(),b.generatedAt(),flags,b.lastObserved());}).toList();
        if(result.isEmpty()) throw new ProviderUnavailableException("VICTORIAMETRICS","RECOMPUTATION_UNAVAILABLE: no complete raw visibility window");
        return result;
    }
    @PreDestroy public void close() {computation.dispose();}
}
