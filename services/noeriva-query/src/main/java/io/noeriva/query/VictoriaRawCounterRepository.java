package io.noeriva.query;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;

@Component
@Profile("!demo")
public final class VictoriaRawCounterRepository implements RawCounterRepository {
    private final WebClient client;
    private final JsonMapper json=JsonMapper.builder().build();
    private final Scheduler decoder=Schedulers.newBoundedElastic(2,8,"noeriva-raw-decode");
    @Autowired
    public VictoriaRawCounterRepository(@Value("${noeriva.query.metrics-url:${NOERIVA_METRICS_URL:http://localhost:8428}}") String url) {
        this(QueryHttpClient.create(url,null,null));
    }
    VictoriaRawCounterRepository(WebClient client) {this.client=client;}
    public Mono<Result> loadCounters(RollupKey key,Instant from,Instant to) {
        String series=key.direction().equals("rx")?"noeriva_interface_receive_bytes_total":"noeriva_interface_transmit_bytes_total";
        String match=series+"{organization_id=\""+key.organizationId()+"\",device_id=\""+key.deviceId()+"\",interface_id=\""+key.interfaceId()+"\",source_id=\""+key.sourceId()+"\"}";
        return client.get().uri(b->b.path("/api/v1/export").queryParam("match[]","{selector}")
                .queryParam("start",from.toString()).queryParam("end",to.toString())
                .queryParam("timeout","5s").queryParam("max_rows_per_line",10000)
                .queryParam("deny_partial_response",1).build(Map.of("selector",match)))
            .retrieve().bodyToMono(String.class).defaultIfEmpty("").timeout(Duration.ofSeconds(6))
            .publishOn(decoder).map(body->decode(body,key,from,to))
            .onErrorMap(e->e instanceof ProviderUnavailableException?e:new ProviderUnavailableException("VICTORIAMETRICS","Raw counter export failed its bounded contract",e));
    }
    private Result decode(String body,RollupKey key,Instant from,Instant to) {
        List<CounterSample> samples=new ArrayList<>();Set<String> flags=new TreeSet<>();flags.add("FLOATING_POINT_SOURCE");
        Map<String,String> acceptedLabels=null;
        for(String line:body.lines().filter(s->!s.isBlank()).toList()) {
            ExportRow row=json.readValue(line,ExportRow.class);
            if(row.metric()==null||row.values()==null||row.timestamps()==null||row.values().size()!=row.timestamps().size())
                throw new ProviderUnavailableException("VICTORIAMETRICS","Malformed raw counter export");
            if(!key.organizationId().equals(row.metric().get("organization_id"))||!key.deviceId().equals(row.metric().get("device_id"))
                    ||!key.interfaceId().equals(row.metric().get("interface_id"))||!key.sourceId().equals(row.metric().get("source_id")))
                throw new ProviderUnavailableException("VICTORIAMETRICS","Raw source scope mismatch");
            // Epoch changes create different series but remain the same physical counter source.
            Map<String,String> identity=new TreeMap<>(row.metric());identity.remove("source_epoch");
            if(acceptedLabels!=null&&!acceptedLabels.equals(identity)) throw new ProviderUnavailableException("VICTORIAMETRICS","Ambiguous raw counter source");
            acceptedLabels=identity;
            String epoch=row.metric().getOrDefault("source_epoch","unknown");
            if(epoch.equals("unknown"))flags.add("SOURCE_EPOCH_UNKNOWN");
            if(samples.size()+row.values().size()>10000)throw new ProviderUnavailableException("VICTORIAMETRICS","Raw sample budget exceeded");
            for(int i=0;i<row.values().size();i++) {
                Instant at=Instant.ofEpochMilli(row.timestamps().get(i));
                if(at.isBefore(from)||at.isAfter(to))throw new ProviderUnavailableException("VICTORIAMETRICS","Export escaped requested raw window");
                BigInteger value;
                try {value=row.values().get(i).toBigIntegerExact();}
                catch(ArithmeticException error) {throw new ProviderUnavailableException("VICTORIAMETRICS","Counter export contains a fractional counter",error);}
                if(value.bitLength()>53)flags.add("COUNTER_QUANTIZATION");
                samples.add(new CounterSample(at,value,epoch,false));
            }
        }
        return new Result(List.copyOf(samples),Set.copyOf(flags));
    }
    record ExportRow(Map<String,String> metric,List<BigDecimal> values,List<Long> timestamps) {}
    @PreDestroy public void close() {decoder.dispose();}
}
