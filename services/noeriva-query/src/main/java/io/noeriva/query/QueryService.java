package io.noeriva.query;
import java.time.*;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import reactor.core.scheduler.*;
import jakarta.annotation.PreDestroy;

@Service
public final class QueryService {
    private final RollupRepository rollups;
    private final MetricRepository metricRepository;
    private final Clock clock;
    private final QueryAdmission admission=new QueryAdmission(8,Duration.ofSeconds(7));
    private final Scheduler computation=Schedulers.newBoundedElastic(2,32,"noeriva-query-compute");
    private final HeatmapBuilder heatmaps=new HeatmapBuilder();
    @Autowired
    public QueryService(RollupRepository rollups,MetricRepository metrics) {this(rollups,metrics,Clock.systemUTC());}
    public QueryService(RollupRepository rollups,MetricRepository metrics,Clock clock) {
        this.rollups=rollups;this.metricRepository=metrics;this.clock=clock;
    }
    public Mono<HeatmapResponse> heatmap(String organizationId,String deviceId,String interfaceId,String timezone,String direction) {
        return heatmapForSource(organizationId,deviceId,interfaceId,null,timezone,direction);
    }
    public Mono<HeatmapResponse> heatmap(String organizationId,String deviceId,String interfaceId,String sourceId,String timezone,String direction) {
        return Mono.defer(()->{validateId(sourceId);return heatmapForSource(organizationId,deviceId,interfaceId,sourceId,timezone,direction);});
    }
    private Mono<HeatmapResponse> heatmapForSource(String organizationId,String deviceId,String interfaceId,String sourceId,String timezone,String direction) {
        return admission.execute(QueryLane.INTERACTIVE_CHART,()->{
            validateId(organizationId);validateId(deviceId);validateId(interfaceId);
            if(!Set.of("rx","tx").contains(direction)) throw new IllegalArgumentException("Direction must be rx or tx");
            final ZoneId zone;
            try {zone=ZoneId.of(Objects.requireNonNull(timezone));}
            catch(RuntimeException e) {throw new IllegalArgumentException("A valid explicit IANA timezone is required");}
            Instant now=clock.instant();
            Instant from=now.atZone(zone).toLocalDate().minusDays(6).atStartOfDay(zone).toInstant();
            return (sourceId==null?rollups.loadRollups(organizationId,deviceId,interfaceId,direction,from,now):
                rollups.loadRollups(organizationId,deviceId,interfaceId,sourceId,direction,from,now))
                .publishOn(computation).map(r->heatmaps.build(deviceId,interfaceId,zone,direction,now,r.buckets(),r.source()));
        });
    }
    /** Device gauges already combine the collector's selected interface observations. Never sum interface gauges again here. */
    public Mono<HeatmapResponse> deviceHeatmap(String organizationId,String deviceId,String timezone,String direction) {
        return admission.execute(QueryLane.INTERACTIVE_CHART,()->{
            validateId(organizationId);validateId(deviceId);
            if(!Set.of("rx","tx").contains(direction))throw new IllegalArgumentException("Direction must be rx or tx");
            final ZoneId zone;
            try{zone=ZoneId.of(Objects.requireNonNull(timezone));}catch(RuntimeException e){throw new IllegalArgumentException("A valid explicit IANA timezone is required");}
            Instant now=clock.instant(),from=now.atZone(zone).toLocalDate().minusDays(6).atStartOfDay(zone).toInstant();
            int points=2000,step=step(from,now,points);
            return metricRepository.loadMetrics(organizationId,deviceId,MetricDefinition.require("bandwidth_"+direction+"_bps"),from,now,points)
                .publishOn(computation).map(result->new GaugeHeatmapBuilder().build(deviceId,zone,direction,now,step,result));
        });
    }
    public Mono<MetricResponse> metrics(String organizationId,String deviceId,String metric,Instant from,Instant to,int points) {
        return admission.execute(QueryLane.INTERACTIVE_CHART,()->{
            validateId(organizationId);validateId(deviceId);
            MetricDefinition definition=MetricDefinition.require(metric);
            Instant now=clock.instant();
            if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(7))>0
                    ||to.isAfter(now.plusSeconds(5))||points<2||points>2000) throw new IllegalArgumentException("Metric query requires 2–2000 points and a non-future range of at most 7 days");
            int step=step(from,to,points);
            return metricRepository.loadMetrics(organizationId,deviceId,definition,from,to,points).publishOn(computation).map(r->{
                if(r.points().size()>points) throw new ProviderUnavailableException(r.source(),"Provider exceeded point budget");
                Instant asOf=r.points().stream().filter(p->p.value()!=null).map(MetricPoint::timestamp).max(Comparator.naturalOrder()).orElse(null);
                long expected=Duration.between(from,to).getSeconds()/step+1;
                long observed=r.points().stream().filter(p->p.value()!=null).count();
                return new MetricResponse(deviceId,metric,definition.unit(),from,to,asOf,r.source(),HeatmapBuilder.freshness(asOf,now),step,
                    r.revision(),false,Math.min(1,(double)observed/expected),r.qualityFlags(),List.copyOf(r.points()));
            });
        });
    }
    static int step(Instant from,Instant to,int points) {
        return Math.max(1,(int)Math.ceil(Duration.between(from,to).toMillis()/1000.0/(points-1)));
    }
    static void validateId(String value) {
        if(value==null||!value.matches("[A-Za-z0-9_.:@-]{1,128}")) throw new IllegalArgumentException("Invalid scoped resource identifier");
    }
    @PreDestroy public void close() {computation.dispose();}
}
