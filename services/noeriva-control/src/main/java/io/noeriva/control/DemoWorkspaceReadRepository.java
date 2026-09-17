package io.noeriva.control;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static io.noeriva.control.Models.*;
import static io.noeriva.control.WorkspaceModels.*;

/** The bounded in-memory demo is an explicit profile, never a provider-failure fallback. */
@Repository @Profile("demo")
class DemoWorkspaceReadRepository implements WorkspaceReadRepository {
    private final DemoRepository inventory;
    DemoWorkspaceReadRepository(DemoRepository inventory){this.inventory=inventory;}
    private Flux<Device> devices(String org,String site,String device){return device.isEmpty()?inventory.devices(org,10000,"","",site,"",""):inventory.device(org,device).filter(d->site.isEmpty()||site.equals(d.siteId())).flux();}
    private static boolean prefix(String q,String... values){return q.isEmpty()||Arrays.stream(values).anyMatch(value->value.toLowerCase(Locale.ROOT).startsWith(q.toLowerCase(Locale.ROOT)));}
    @Override public Flux<MonitoringSource> monitoring(String org,int limit,SourceCursor cursor,String q,String site,String device,String freshness,Instant asOf){
        return devices(org,site,device).concatMap(d->inventory.sources(org,d.id()).map(s->new MonitoringSource(d.id(),d.name(),d.type(),d.siteId(),d.siteName(),s.sourceId(),s.kind(),s.health(),s.observedAt(),ProjectionPolicy.freshness(s.observedAt(),asOf),s.metrics(),s.sequence(),s.epoch()))
            .filter(s->prefix(q,d.name(),d.managementAddress(),s.sourceId())))
            .filter(s->freshness.isEmpty()||freshness.equals(s.freshness()))
            .filter(s->new SourceCursor(s.deviceId(),s.sourceId()).compareTo(cursor)>0)
            .sort(Comparator.comparing(MonitoringSource::deviceId).thenComparing(MonitoringSource::sourceId)).take(limit);
    }
    @Override public Flux<WorkspaceInterface> interfaces(String org,int limit,String cursor,String q,String site,String device,Instant asOf){
        var after=InterfaceCursor.decode(cursor,InterfaceCursor.scope(org,q,site,device));
        return devices(org,site,device).concatMap(d->inventory.interfaces(org,d.id()).filter(i->prefix(q,d.name(),d.managementAddress(),i.name()))
            .map(i->new WorkspaceInterface(i.id(),i.deviceId(),d.name(),d.siteId(),d.siteName(),i.name(),i.macAddress(),i.speedBps(),i.adminStatus(),i.operStatus(),d.lastSeen(),ProjectionPolicy.freshness(d.lastSeen(),asOf))))
            .filter(after::before).sort(InterfaceCursor.order()).take(limit);
    }
    @Override public Flux<SiteHealth> siteHealth(String org,String site,Instant asOf){
        return inventory.sites(org).filter(s->site.isEmpty()||site.equals(s.id())).concatMap(s->devices(org,s.id(),"").collectList()
            .map(list->new SiteHealth(s.id(),s.name(),s.timezone(),list.size(),count(list,"CRITICAL"),count(list,"WARNING"),count(list,"HEALTHY"),count(list,"UNKNOWN"),stale(list,asOf))));
    }
    @Override public Flux<Device> priorityDevices(String org,String site,int limit){
        return devices(org,site,"").filter(d->!d.health().equals("HEALTHY")).sort(Comparator.<Device>comparingInt(d->switch(d.health()){case "CRITICAL"->0;case "WARNING"->1;default->2;}).thenComparing(Device::id)).take(limit);
    }
    @Override public Mono<MonitoringSource> trafficSource(String org,String site,Instant asOf){
        return monitoring(org,50000,new SourceCursor("",""),"",site,"","",asOf)
            .filter(s->s.metrics().containsKey("bandwidth_rx_bps")||s.metrics().containsKey("bandwidth_tx_bps"))
            .sort(Comparator.comparing(MonitoringSource::observedAt).reversed().thenComparing(MonitoringSource::deviceId).thenComparing(MonitoringSource::sourceId)).next();
    }
    @Override public Mono<Overview> totals(String org,String site,Instant asOf){
        return devices(org,site,"").collectList().flatMap(list->{Set<String> ids=new HashSet<>(list.stream().map(Device::id).toList());
            return inventory.alerts(org,"","",10000).filter(a->!a.state().equals("RESOLVED")&&ids.contains(a.deviceId())).count()
                .zipWith(inventory.collectors(org).filter(c->site.isEmpty()||site.equals(c.siteId())).count())
                .map(t->new Overview(list.size(),count(list,"CRITICAL"),count(list,"WARNING"),count(list,"HEALTHY"),count(list,"UNKNOWN"),stale(list,asOf),t.getT1(),t.getT2(),asOf,"DEMO"));});
    }
    private long count(List<Device> devices,String health){return devices.stream().filter(d->d.health().equals(health)).count();}
    private long stale(List<Device> devices,Instant asOf){return devices.stream().filter(d->ProjectionPolicy.freshness(d.lastSeen(),asOf).equals("STALE")).count();}
}
