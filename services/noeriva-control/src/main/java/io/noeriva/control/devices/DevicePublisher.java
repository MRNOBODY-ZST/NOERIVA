package io.noeriva.control.devices;

import io.noeriva.control.*;
import io.noeriva.query.MetricDefinition;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.devices.DeviceAccessModels.*;

@Component
public class DevicePublisher {
    private final DeviceAccessStore store;private final IngestService ingest;private final WebClient metrics;private final JsonMapper json;
    public DevicePublisher(DeviceAccessStore store,IngestService ingest,Environment env,JsonMapper json){this.store=store;this.ingest=ingest;this.json=json;
        metrics=WebClient.builder().baseUrl(env.getProperty("NOERIVA_METRICS_URL","http://localhost:18428"))
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(Duration.ofSeconds(8)))).build();}
    public Mono<DeviceProtocol.Reading> publish(Stored value,DeviceProtocol.Reading raw){
        var reading=DeviceCounters.enrich(raw,value.lastPublished(),value.sourceEpoch(),value.settings().intervalSeconds());
        return store.assertLease(value).then(store.metricOwnership(value,reading.metrics().keySet())).flatMap(owned->{
            var rows=new ArrayList<Map<String,Object>>();var current=new TreeMap<String,Double>();
            reading.metrics().forEach((id,number)->{if(owned.contains(id)){rows.add(row(value,MetricDefinition.require(id).series(),Map.of(),number,reading.observedAt()));current.put(id,number);}});
            for(var port:reading.ports()){
                String epoch=rawEpoch(value,reading,port);
                Map<String,String> labels=Map.of("interface_id",DeviceAccessStore.portId(value,port.key()),"source_id",DeviceAccessStore.source(value),"source_epoch",epoch);
                addCounter(rows,value,reading,labels,"noeriva_interface_receive_bytes_total",port.inOctets());addCounter(rows,value,reading,labels,"noeriva_interface_transmit_bytes_total",port.outOctets());
            }
            var facts=new LinkedHashMap<>(reading.facts());facts.put("publishedMetricIds",String.join(",",current.keySet()));
            DeviceProtocol.Reading result=new DeviceProtocol.Reading(reading.observedAt(),reading.identity(),reading.health(),reading.metrics(),reading.sensors(),reading.ports(),reading.capabilities(),reading.qualityFlags(),Map.copyOf(facts));
            String body=String.join("\n",rows.stream().map(json::writeValueAsString).toList())+"\n";
            Mono<Void> write=rows.isEmpty()?Mono.empty():metrics.post().uri("/api/v1/import").contentType(MediaType.APPLICATION_NDJSON).bodyValue(body).retrieve().toBodilessEntity().timeout(Duration.ofSeconds(9)).then();
            var observation=new Models.Observation("poll_"+value.lease(),value.device(),DeviceAccessStore.source(value),"DeviceSummaryObserved",value.sourceEpoch(),value.sequence(),reading.observedAt(),reading.health(),current,DeviceObservationSummary.message(reading,value.protocol()));
            return store.ports(value,reading).then(write).then(ingest.accept(value.org(),new Models.IngestBatch("poll_"+value.lease(),List.of(observation))))
                .then(store.observedCapabilities(value,reading)).thenReturn(result);
        });
    }
    static String rawEpoch(Stored value,DeviceProtocol.Reading reading,DeviceProtocol.Port port){
        // The historical contract does not carry 32-bit wrap bounds; isolate those samples rather than inventing a rate.
        if(port.counterBits()!=64||port.discontinuity()==null||!DeviceCounters.continuityEvidence(reading))return value.lease();
        return reading.facts().get("collectorEpoch")+"/"+port.discontinuity()+"/64";
    }
    private Map<String,Object> row(Stored v,String metric,Map<String,String> extra,Number value,Instant at){
        var labels=new LinkedHashMap<String,String>();labels.put("__name__",metric);labels.put("organization_id",v.org());labels.put("device_id",v.device());labels.putAll(extra);
        return Map.of("metric",labels,"values",List.of(value),"timestamps",List.of(at.toEpochMilli()));
    }
    private void addCounter(List<Map<String,Object>> rows,Stored v,DeviceProtocol.Reading reading,Map<String,String> labels,String metric,String raw){
        if(raw!=null)rows.add(row(v,metric,labels,new java.math.BigInteger(raw),reading.observedAt()));
    }
}
