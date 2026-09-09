package io.noeriva.query;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@Profile("!demo")
public final class VictoriaMetricsRepository implements MetricRepository {
    private final WebClient client;
    @Autowired
    public VictoriaMetricsRepository(
            @Value("${noeriva.query.metrics-url:${NOERIVA_METRICS_URL:http://localhost:8428}}") String url) {
        this(QueryHttpClient.create(url,null,null));
    }
    VictoriaMetricsRepository(WebClient client) {this.client=client;}
    public Mono<Result> loadMetrics(String org,String device,MetricDefinition metric,Instant from,Instant to,int points) {
        QueryService.validateId(org);QueryService.validateId(device);
        int step=QueryService.step(from,to,points);
        // VM represents timestamps in milliseconds. Round inward so it cannot return a point outside the API window.
        Instant floorFrom=from.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant lowerBound=floorFrom.isBefore(from)?floorFrom.plusMillis(1):floorFrom;
        Instant providerTo=to.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if(lowerBound.isAfter(providerTo))return Mono.just(new Result(List.of(),"VICTORIAMETRICS",0,List.of()));
        // Anchor the grid at the most recent permitted millisecond. A grid started at
        // from with a rounded integer step can omit almost a full step at the end
        // (over ten minutes for a 24h/120-point chart), hiding a newly enabled source.
        long intervals=Duration.between(lowerBound,providerTo).toMillis()/(step*1000L);
        Instant providerFrom=providerTo.minusSeconds(intervals*step);
        // Named metric catalog and mandatory tenant/device labels prevent arbitrary browser PromQL.
        String query=metric.series()+"{organization_id=\""+org+"\",device_id=\""+device+"\"}";
        return client.get().uri(b->b.path("/api/v1/query_range").queryParam("query","{expression}")
                .queryParam("start",providerFrom.toString()).queryParam("end",providerTo.toString())
                // VM's result cache rounds the requested window to step boundaries; preserve API bounds instead.
                .queryParam("nocache",1).queryParam("step",step).queryParam("timeout","5s").queryParam("deny_partial_response",1).build(Map.of("expression",query)))
            .retrieve().bodyToMono(Payload.class).timeout(Duration.ofSeconds(6))
            .switchIfEmpty(Mono.error(new ProviderUnavailableException("VICTORIAMETRICS","Missing metric response")))
            .map(payload->decode(payload,from,to,points))
            .onErrorMap(e->e instanceof ProviderUnavailableException?e:new ProviderUnavailableException("VICTORIAMETRICS","Metric query failed or exceeded its storage budget",e));
    }
    private Result decode(Payload payload,Instant from,Instant to,int limit) {
        if(!"success".equals(payload.status())||payload.data()==null||!"matrix".equals(payload.data().resultType())||payload.data().result()==null)
            throw new ProviderUnavailableException("VICTORIAMETRICS","Invalid metric response");
        if(Boolean.TRUE.equals(payload.isPartial())) throw new ProviderUnavailableException("VICTORIAMETRICS","Provider reports partial metric coverage");
        var series=payload.data().result();
        if(series.size()>1) throw new ProviderUnavailableException("VICTORIAMETRICS","Named metric source is ambiguous; configure a single catalog source");
        var flags=new TreeSet<String>();
        if(payload.warnings()!=null&&!payload.warnings().isEmpty()) flags.add("PROVIDER_WARNING");
        if(series.isEmpty()) return new Result(List.of(),"VICTORIAMETRICS",0,List.copyOf(flags));
        var values=series.getFirst().values();
        if(values==null||values.size()>limit) throw new ProviderUnavailableException("VICTORIAMETRICS","Metric result exceeded point budget");
        List<MetricPoint> points=new ArrayList<>();Instant previous=null;
        for(var pair:values) {
            if(pair.size()!=2) throw new ProviderUnavailableException("VICTORIAMETRICS","Malformed metric sample");
            var stamp=new java.math.BigDecimal(pair.getFirst().toString());
            Instant timestamp=Instant.ofEpochMilli(stamp.multiply(java.math.BigDecimal.valueOf(1000)).longValueExact());
            if(timestamp.isBefore(from)||timestamp.isAfter(to)||(previous!=null&&!timestamp.isAfter(previous))) throw new ProviderUnavailableException("VICTORIAMETRICS","Invalid metric sample ordering or window");
            String raw=pair.get(1).toString();
            Double value;
            if(Set.of("NaN","+Inf","-Inf","Inf").contains(raw)) {value=null;flags.add("NON_FINITE_SAMPLE");}
            else {value=Double.valueOf(raw);if(!Double.isFinite(value)) {value=null;flags.add("NON_FINITE_SAMPLE");}}
            points.add(new MetricPoint(timestamp,value));previous=timestamp;
        }
        // Native raw stores do not expose an application projection revision. Zero explicitly means unavailable.
        flags.add("NATIVE_REVISION_UNAVAILABLE");
        return new Result(List.copyOf(points),"VICTORIAMETRICS",0,List.copyOf(flags));
    }
    record Payload(String status,Data data,List<String> warnings,Boolean isPartial) {}
    record Data(String resultType,List<Series> result) {}
    record Series(Map<String,String> metric,List<List<Object>> values) {}
}
