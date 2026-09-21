package io.noeriva.control;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import static io.noeriva.control.Models.*;

public interface ControlRepository {
    Flux<Device> devices(String org, int limit, String cursor, String q, String site, String type, String health);
    Mono<Device> device(String org, String id);
    Flux<Device> deviceBatch(String org, java.util.List<String> ids);
    Mono<Device> create(String org, String actor, CreateDevice input);
    Mono<Void> delete(String org,String actor,String id);
    Flux<Site> sites(String org);
    Flux<SourceState> sources(String org, String device);
    Flux<NetworkInterface> interfaces(String org, String device);
    default Mono<String> interfaceSource(String org,String device,String interfaceId){return Mono.just("primary");}
    Mono<NetworkInterface> registerInterface(String org,String actor,String device,RegisterInterface input);
    Flux<Alert> alerts(String org, String device, String state, int limit);
    Mono<Alert> acknowledge(String org, String actor, String id, long revision);
    Flux<Event> events(String org, String device, Instant from, Instant to, String cursor, int limit);
    default Flux<Event> actionableEvents(String org,Instant from,Instant to,int limit) {
        return events(org,"",from,to,"",1000).filter(e->java.util.Set.of("WARN","WARNING","CRITICAL","ERROR","FAULT","FATAL").contains(e.severity().toUpperCase(java.util.Locale.ROOT))).take(limit);
    }
    Flux<Collector> collectors(String org);
    Flux<Edge> edges(String org, String device, int limit);
    Mono<Overview> overview(String org);
    Mono<Boolean> project(String org, Observation observation);
}
