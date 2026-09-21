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
    @Override public Mono<Result> loadSamples(String org,String device,MetricDefinition metric,Instant from,Instant to) {
        QueryService.validateId(org);QueryService.validateId(device);
        String match=metric.series()+"{organization_id=\""+org+"\",device_id=\""+device+"\"}";
        Instant floor=from.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant start=floor.isBefore(from)?floor.plusMillis(1):floor;
        Instant end=to.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if(start.isAfter(end))return Mono.just(new Result(List.of(),"VICTORIAMETRICS",0,List.of()));
        return client.get().uri(b->b.path("/api/v1/export").queryParam("match[]","{selector}")
                .queryParam("start",start.toString()).queryParam("end",end.toString())
                .queryParam("max_rows_per_line",10000).queryParam("timeout","5s")
                .queryParam("deny_partial_response",1).build(Map.of("selector",match)))
            .retrieve().bodyToMono(String.class).defaultIfEmpty("").timeout(Duration.ofSeconds(6))
            .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .map(body->decodeExport(body,org,device,metric,from,to))
            .onErrorMap(e->e instanceof ProviderUnavailableException?e:new ProviderUnavailableException("VICTORIAMETRICS","Raw metric query failed or exceeded its storage budget",e));
    }
    private Result decodeExport(String body,String org,String device,MetricDefinition metric,Instant from,Instant to) {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var samples=new TreeMap<Instant,Double>();Map<String,String> identity=null;
        var flags=new TreeSet<String>();flags.add("NATIVE_REVISION_UNAVAILABLE");int seen=0;
        for(String line:body.lines().filter(s->!s.isBlank()).toList()) {
            var row=json.readValue(line,ExportRow.class);
            if(row.metric()==null||row.values()==null||row.timestamps()==null||row.values().size()!=row.timestamps().size())
                throw new ProviderUnavailableException("VICTORIAMETRICS","Malformed raw metric export");
            if(!org.equals(row.metric().get("organization_id"))||!device.equals(row.metric().get("device_id"))||!metric.series().equals(row.metric().get("__name__")))
                throw new ProviderUnavailableException("VICTORIAMETRICS","Raw metric scope mismatch");
            if(identity!=null&&!identity.equals(row.metric()))throw new ProviderUnavailableException("VICTORIAMETRICS","Ambiguous metric source");
            identity=row.metric();seen+=row.values().size();
            if(seen>MetricAggregation.MAX_SAMPLES)throw new ProviderUnavailableException("VICTORIAMETRICS","Raw metric sample budget exceeded");
            for(int i=0;i<row.values().size();i++) {
                Instant at=Instant.ofEpochMilli(row.timestamps().get(i));
                if(at.isBefore(from)||at.isAfter(to))throw new ProviderUnavailableException("VICTORIAMETRICS","Raw metric escaped requested window");
                Object raw=row.values().get(i);Double value=null;
                if(raw!=null) {
                    String text=raw.toString();
                    if(!Set.of("NaN","+Inf","-Inf","Inf","Infinity","-Infinity").contains(text))value=Double.valueOf(text);
                }
                if(value!=null&&!Double.isFinite(value))value=null;
                if(value==null)flags.add("NON_FINITE_SAMPLE");
                if(samples.containsKey(at)&&!Objects.equals(samples.get(at),value))throw new ProviderUnavailableException("VICTORIAMETRICS","Conflicting metric samples");
                samples.put(at,value);
            }
        }
        return new Result(samples.entrySet().stream().map(e->new MetricPoint(e.getKey(),e.getValue())).toList(),"VICTORIAMETRICS",0,List.copyOf(flags));
    }
    record ExportRow(Map<String,String> metric,List<Object> values,List<Long> timestamps) {}
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
