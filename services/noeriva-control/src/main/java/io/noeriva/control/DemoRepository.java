package io.noeriva.control;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static io.noeriva.control.Models.*;

/** Explicit synthetic, process-local profile. Never selected as a persistence fallback. */
@Repository @Profile("demo")
public class DemoRepository implements ControlRepository {
    private final Map<String,Device> inventory = new ConcurrentHashMap<>();
    private final Map<String,Observation> observations = new ConcurrentHashMap<>();
    private final Map<String,Alert> alerts = new ConcurrentHashMap<>();
    private final Map<String,Event> events = new ConcurrentHashMap<>();
    private final List<NetworkInterface> interfaces = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Instant fixtureTime = Instant.now().minusSeconds(35);
    private static final List<Site> SITES = List.of(new Site("lab-a","研发中心 · A 区","UTC"),new Site("edge-b","边缘站点 · B 区","UTC"));

    public DemoRepository() {
        seed("core-asr-01","ROUTER","Cisco","ASR 1001-X","192.0.2.1","HEALTHY",Map.of("bandwidth_rx_bps", 720000000.0));
        seed("lab-sw-01","SWITCH","Dell","S5248F-ON","192.0.2.2","HEALTHY",Map.of("temperature_celsius",39.0));
        seed("edge-sw-02","SWITCH","Generic","L3 Switch","192.0.2.3","WARNING",Map.of("temperature_celsius",67.0));
        seed("compute-07","HOST","Lenovo","ThinkSystem SR650","192.0.2.7","HEALTHY",Map.of("cpu_percent",42.3,"memory_percent",61.8));
        seed("bmc-compute-07","BMC","Lenovo","XClarity Controller","192.0.2.107","WARNING",Map.of("temperature_celsius",72.0,"power_watts",286.0));
        var original = observations.get("bmc-compute-07/bmc");
        observations.put("bmc-compute-07/bmc",new Observation(original.id(),original.deviceId(),original.sourceId(),original.kind(),original.epoch(),original.sequence(),fixtureTime.minusSeconds(420),original.health(),original.metrics(),original.message()));
        var bmc=inventory.get("bmc-compute-07");
        inventory.put(bmc.id(),new Device(bmc.id(),bmc.name(),bmc.type(),bmc.siteId(),bmc.siteName(),bmc.vendor(),bmc.model(),bmc.managementAddress(),bmc.health(),bmc.availability(),fixtureTime.minusSeconds(420),bmc.revision(),bmc.capabilities()));
        alerts.put("alert-temperature",new Alert("alert-temperature","bmc-compute-07","bmc-compute-07","WARNING","OPEN","进风温度升高，BMC 采集已过期",fixtureTime.minusSeconds(900),null,null,1));
        alerts.put("alert-interface",new Alert("alert-interface","edge-sw-02","edge-sw-02","WARNING","OPEN","上联端口间歇性丢包",fixtureTime.minusSeconds(520),null,null,1));
        interfaces.add(new NetworkInterface("if-core-1","core-asr-01","TenGigabitEthernet0/0/0","02:00:00:00:00:01","10000000000","UP","UP"));
        interfaces.add(new NetworkInterface("if-lab-1","lab-sw-01","ethernet1/1/1","02:00:00:00:00:02","10000000000","UP","UP"));
        interfaces.add(new NetworkInterface("if-edge-1","edge-sw-02","Ethernet1","02:00:00:00:00:03","1000000000","UP","UP"));
        interfaces.add(new NetworkInterface("if-compute-1","compute-07","eno1","02:00:00:00:00:07","10000000000","UP","UP"));
    }
    private void seed(String id,String type,String vendor,String model,String address,String health,Map<String,Double> metrics) {
        inventory.put(id,new Device(id,id,type,"lab-a",SITES.getFirst().name(),vendor,model,address,health,"ONLINE",fixtureTime,1,
            type.equals("BMC")?List.of("summary","metrics"):List.of("summary","metrics","interfaces","heatmap","connections")));
        String source=type.equals("BMC")?"bmc":"host";
        observations.put(id+"/"+source,new Observation("seed-"+id,id,source,"DeviceSummaryObserved","simulated-boot",1,fixtureTime,health,metrics,"模拟采集快照"));
        events.put("event-"+id,new Event("event-"+id,id,"DeviceSummaryObserved",health.equals("HEALTHY")?"INFO":"WARNING","模拟设备状态观测 · "+id,fixtureTime,fixtureTime,"SIMULATED",List.of("SYNTHETIC_DATA")));
    }
    private Device current(Device d){
        Instant now=Instant.now();
        var sources=observations.values().stream().filter(o->d.id().equals(o.deviceId())).map(o->ProjectionPolicy.source(o,now)).toList();
        return new Device(d.id(),d.name(),d.type(),d.siteId(),d.siteName(),d.vendor(),d.model(),d.managementAddress(),ProjectionPolicy.aggregateHealth(sources),sources.stream().anyMatch(s->"FRESH".equals(s.freshness()))?"ONLINE":"UNKNOWN",d.lastSeen(),d.revision(),d.capabilities());
    }
    private boolean authorized(String org) { return "demo".equals(org); }
    @Override public Flux<Device> devices(String org,int limit,String cursor,String q,String site,String type,String health) {
        return Flux.defer(() -> !authorized(org)?Flux.empty():Flux.fromStream(inventory.values().stream().map(this::current).filter(d -> d.id().compareTo(cursor)>0)
            .filter(d -> q.isEmpty() || (d.name()+" "+d.managementAddress()).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
            .filter(d -> site.isEmpty()||site.equals(d.siteId())).filter(d -> type.isEmpty()||type.equals(d.type())).filter(d -> health.isEmpty()||health.equals(d.health()))
            .sorted(Comparator.comparing(Device::id)).limit(limit)));
    }
    @Override public Mono<Device> device(String org,String id) { return Mono.defer(() -> authorized(org)?Mono.justOrEmpty(inventory.get(id)).map(this::current):Mono.empty()); }
    @Override public Flux<Device> deviceBatch(String org,List<String> ids) {return authorized(org)?Flux.fromIterable(ids).mapNotNull(inventory::get).map(this::current):Flux.empty();}
    @Override public Mono<Device> create(String org,String actor,CreateDevice input) {
        return Mono.fromCallable(() -> { if (!authorized(org)) throw ApiException.missing();
            var site=SITES.stream().filter(s -> s.id().equals(input.siteId())).findFirst().orElseThrow(ApiException::missing);
            if(inventory.size()>=10000) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"DEMO_CAPACITY","Demo device capacity reached");
            var d=new Device(UUID.randomUUID().toString(),input.name(),input.type(),site.id(),site.name(),input.vendor(),input.model(),input.managementAddress(),"UNKNOWN","UNKNOWN",null,1,List.of("summary"));
            inventory.put(d.id(),d);return d; });
    }
    @Override public Flux<Site> sites(String org) { return authorized(org)?Flux.fromIterable(SITES):Flux.empty(); }
    @Override public Flux<SourceState> sources(String org,String device) { return Flux.defer(() -> authorized(org)?Flux.fromStream(observations.values().stream().filter(o -> o.deviceId().equals(device)).sorted(Comparator.comparing(Observation::sourceId)).map(o -> ProjectionPolicy.source(o,Instant.now()))):Flux.empty()); }
    @Override public Flux<NetworkInterface> interfaces(String org,String device) { return authorized(org)?Flux.fromStream(interfaces.stream().filter(i -> i.deviceId().equals(device))):Flux.empty(); }
    @Override public Mono<NetworkInterface> registerInterface(String org,String actor,String device,RegisterInterface input){return device(org,device).switchIfEmpty(Mono.error(ApiException.missing())).map(d->{var result=new NetworkInterface(UUID.randomUUID().toString(),device,input.name(),input.macAddress(),input.speedBps(),"UNKNOWN","UNKNOWN");synchronized(interfaces){interfaces.add(result);}return result;});}
    @Override public Flux<Alert> alerts(String org,String device,String state,int limit) {
        return Flux.defer(() -> authorized(org)?Flux.fromStream(alerts.values().stream().filter(a -> device.isEmpty()||device.equals(a.deviceId())).filter(a -> state.isEmpty()||state.equals(a.state())).sorted(Comparator.comparing(Alert::openedAt).reversed()).limit(limit)):Flux.empty());
    }
    @Override public Mono<Alert> acknowledge(String org,String actor,String id,long revision) {
        return Mono.fromCallable(() -> { if(!authorized(org)||!alerts.containsKey(id))throw ApiException.missing();
            return alerts.compute(id,(key,a)-> {if(a.revision()!=revision || !a.state().equals("OPEN"))throw ApiException.conflict();
                return new Alert(a.id(),a.deviceId(),a.deviceName(),a.severity(),"ACKNOWLEDGED",a.title(),a.openedAt(),Instant.now(),actor,a.revision()+1);}); });
    }
    @Override public Flux<Event> events(String org,String device,Instant from,Instant to,String cursor,int limit) {
        return Flux.defer(() -> authorized(org)?Flux.fromStream(events.values().stream().filter(e -> device.isEmpty()||device.equals(e.deviceId()))
            .filter(e -> !e.observedAt().isBefore(from)&&e.observedAt().isBefore(to)).filter(e -> cursor.isEmpty() || e.observedAt().isBefore(EventCursor.decode(cursor).at()) || e.observedAt().equals(EventCursor.decode(cursor).at()) && e.id().compareTo(EventCursor.decode(cursor).id())<0)
            .sorted(Comparator.comparing(Event::observedAt).thenComparing(Event::id).reversed()).limit(limit)):Flux.empty());
    }
    @Override public Flux<Collector> collectors(String org) { return authorized(org)?Flux.just(new Collector("noeriva-edge-lab-a","noeriva-edge-lab-a","lab-a","SIMULATED",fixtureTime,fixtureTime,0,268435456,null,"0.1.0",List.of("synthetic-summary"),"SIMULATED")):Flux.empty(); }
    @Override public Flux<Edge> edges(String org,String device,int limit) {
        if(!authorized(org)) return Flux.empty();
        return Flux.fromIterable(List.of(new Edge("edge-1","core-asr-01","lab-sw-01","Te0/0/0","ethernet1/1/1","PHYSICAL","SIMULATED",fixtureTime),new Edge("edge-2","lab-sw-01","edge-sw-02","ethernet1/1/2","Ethernet1","PHYSICAL","SIMULATED",fixtureTime),new Edge("edge-3","lab-sw-01","compute-07","ethernet1/1/7","eno1","PHYSICAL","SIMULATED",fixtureTime),new Edge("edge-4","compute-07","bmc-compute-07",null,null,"MANAGEMENT","SIMULATED",fixtureTime)))
            .filter(e -> device.isEmpty()||device.equals(e.source())||device.equals(e.target())).take(limit);
    }
    @Override public Mono<Overview> overview(String org) { return devices(org,10001,"","","","","").collectList().map(list -> new Overview(list.size(),count(list,"CRITICAL"),count(list,"WARNING"),count(list,"HEALTHY"),count(list,"UNKNOWN"),list.stream().filter(d->ProjectionPolicy.freshness(d.lastSeen(),Instant.now()).equals("STALE")).count(),authorized(org)?alerts.values().stream().filter(a->!a.state().equals("RESOLVED")).count():0,authorized(org)?1:0,Instant.now(),"DEMO")); }
    private long count(List<Device> list,String health) {return list.stream().filter(d->d.health().equals(health)).count();}
    @Override public Mono<Boolean> project(String org,Observation o) { return Mono.fromCallable(() -> { if(!authorized(org)||!inventory.containsKey(o.deviceId()))throw ApiException.missing();
        synchronized(observations) {
            var key=o.deviceId()+"/"+o.sourceId(); var previous=observations.get(key);
            if(!ProjectionPolicy.accepts(previous,o,Instant.now()))return false;
            observations.put(key,o);
            inventory.computeIfPresent(o.deviceId(),(id,d)->new Device(d.id(),d.name(),d.type(),d.siteId(),d.siteName(),d.vendor(),d.model(),d.managementAddress(),ProjectionPolicy.aggregateHealth(observations.values().stream().filter(s->s.deviceId().equals(o.deviceId())).map(s->ProjectionPolicy.source(s,Instant.now())).toList()),"ONLINE",o.observedAt(),d.revision()+1,d.capabilities()));
            if(events.size()>=10000) events.keySet().stream().min(String::compareTo).ifPresent(events::remove);
            events.put(o.id(),new Event(o.id(),o.deviceId(),o.kind(),o.health(),o.message(),o.observedAt(),Instant.now(),"SIMULATED",List.of("SYNTHETIC_DATA")));
            return true;
        }
    }); }
}
