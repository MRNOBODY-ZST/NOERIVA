package io.noeriva.control;

import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import static io.noeriva.control.Models.*;
import static io.noeriva.control.WorkspaceModels.*;
import static io.noeriva.control.SecurityConfiguration.Operator;

@RestController @RequestMapping("/api/v1/workspace") @Validated
public class WorkspaceController {
    private final WorkspaceReadRepository workspace;private final ControlRepository inventory;private final String mode;private EntitySearch entitySearch;
    public WorkspaceController(WorkspaceReadRepository workspace,ControlRepository inventory){this.workspace=workspace;this.inventory=inventory;this.mode=inventory instanceof DemoRepository?"DEMO":"CONNECTED";}
    @org.springframework.beans.factory.annotation.Autowired
    public WorkspaceController(WorkspaceReadRepository workspace,ControlRepository inventory,EntitySearch entitySearch){this(workspace,inventory);this.entitySearch=entitySearch;}
    @GetMapping("/monitoring") public Mono<Page<MonitoringSource>> monitoring(@AuthenticationPrincipal Operator user,
        @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,@RequestParam(defaultValue="") @Size(max=256) String cursor,
        @RequestParam(defaultValue="") @Size(max=120) String q,@RequestParam(defaultValue="") @Size(max=64) String siteId,
        @RequestParam(defaultValue="") @Size(max=64) String deviceId,@RequestParam(defaultValue="") @Pattern(regexp="|FRESH|STALE") String freshness){
        Instant asOf=Instant.now();return workspace.monitoring(user.organizationId(),limit+1,SourceCursor.decode(cursor),q.trim(),siteId,deviceId,freshness,asOf).collectList()
            .map(list->new Page<>(list.stream().limit(limit).toList(),list.size()>limit?SourceCursor.encode(list.get(limit-1)):null,asOf,source(),mode));
    }
    @GetMapping("/interfaces") public Mono<Page<WorkspaceInterface>> interfaces(@AuthenticationPrincipal Operator user,
        @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,@RequestParam(defaultValue="") @Size(max=4096) String cursor,
        @RequestParam(defaultValue="") @Size(max=120) String q,@RequestParam(defaultValue="") @Size(max=64) String siteId,@RequestParam(defaultValue="") @Size(max=64) String deviceId){
        Instant asOf=Instant.now();return workspace.interfaces(user.organizationId(),limit+1,cursor,q.trim(),siteId,deviceId,asOf).collectList()
            .map(list->new Page<>(list.stream().limit(limit).toList(),list.size()>limit?InterfaceCursor.encode(list.get(limit-1),InterfaceCursor.scope(user.organizationId(),q.trim(),siteId,deviceId)):null,asOf,source(),mode));
    }
    @GetMapping("/overview") public Mono<WorkspaceOverview> overview(@AuthenticationPrincipal Operator user,@RequestParam(defaultValue="") @Size(max=64) String siteId){
        Instant asOf=Instant.now();String org=user.organizationId();
        Mono<HistoryResult> history=siteId.isEmpty()?history(org,asOf.minus(Duration.ofDays(7)),asOf,5):Mono.just(new HistoryResult(List.of(),"NOT_REQUESTED"));
        return Mono.zip(workspace.totals(org,siteId,asOf),workspace.siteHealth(org,siteId,asOf).collectList(),workspace.priorityDevices(org,siteId,10).collectList(),history,workspace.trafficSource(org,siteId,asOf).map(Optional::of).defaultIfEmpty(Optional.empty()))
            .map(t->new WorkspaceOverview(t.getT1(),t.getT2(),t.getT3(),t.getT5().orElse(null),t.getT4().items(),t.getT4().status(),asOf,mode,flags(t.getT4())));
    }
    @GetMapping("/search") public Mono<WorkspaceSearch> search(@AuthenticationPrincipal Operator user,
        @RequestParam @Size(min=2,max=120) String q,@RequestParam(defaultValue="10") @Min(1) @Max(20) int limit){
        String query=q.trim();if(query.length()<2)throw new IllegalArgumentException("Search requires two characters");
        Instant asOf=Instant.now();Instant from=asOf.minus(Duration.ofDays(7));String needle=query.toLowerCase(Locale.ROOT);
        if(!mode.equals("DEMO")&&entitySearch!=null)return entitySearch.search(user.organizationId(),query,Math.min(60,limit*3)).flatMap(result->{
            var deviceIds=result.items().stream().map(EntitySearch.Hit::deviceId).distinct().toList();
            return inventory.deviceBatch(user.organizationId(),deviceIds).collectMap(Device::id).flatMap(devices->{
                var assets=result.items().stream().filter(h->h.kind().equals("DEVICE")&&devices.containsKey(h.id())).limit(limit).map(h->devices.get(h.id())).toList();
                var portHits=result.items().stream().filter(h->h.kind().equals("INTERFACE")&&devices.containsKey(h.deviceId())).toList();
                return reactor.core.publisher.Flux.fromIterable(portHits.stream().map(EntitySearch.Hit::deviceId).distinct().toList())
                    .concatMap(deviceId->inventory.interfaces(user.organizationId(),deviceId).filter(i->portHits.stream().anyMatch(h->h.deviceId().equals(deviceId)&&h.id().equals(i.id()))).map(i->{var d=devices.get(deviceId);return new WorkspaceInterface(i.id(),i.deviceId(),d.name(),d.siteId(),d.siteName(),i.name(),i.macAddress(),i.speedBps(),i.adminStatus(),i.operStatus(),d.lastSeen(),ProjectionPolicy.freshness(d.lastSeen(),asOf));})).take(limit)
                    .collectList().map(ports->new WorkspaceSearch(assets,List.of(),query,0,null,null,"ENTITY_DIRECTORY","NOT_INDEXED",asOf,mode,List.of(),ports,result.provider(),result.status(),result.indexedAt()));
            });
        });
        return Mono.zip(inventory.devices(user.organizationId(),mode.equals("DEMO")?10000:limit,"",query,"","","")
            .filter(d->!mode.equals("DEMO")||d.name().toLowerCase(Locale.ROOT).startsWith(needle)||d.managementAddress().toLowerCase(Locale.ROOT).startsWith(needle)).take(limit).collectList(),history(user.organizationId(),from,asOf,100))
            .map(t->new WorkspaceSearch(t.getT1(),t.getT2().items().stream().filter(e->(Objects.toString(e.message(),"")+" "+e.kind()+" "+e.deviceId()).toLowerCase(Locale.ROOT).contains(needle)).limit(limit).toList(),query,100,from,asOf,"RECENT_7_DAYS_MAX_100",t.getT2().status(),asOf,mode,flags(t.getT2())));
    }
    private Mono<HistoryResult> history(String org,Instant from,Instant to,int limit){return inventory.events(org,"",from,to,"",limit).collectList().map(items->new HistoryResult(items,"AVAILABLE"))
        .onErrorResume(ApiException.class,e->e.status.is5xxServerError()?Mono.just(new HistoryResult(List.of(),"UNAVAILABLE")):Mono.error(e));}
    private List<String> flags(HistoryResult history){var flags=new ArrayList<String>();if(mode.equals("DEMO"))flags.add("SYNTHETIC_DATA");if(history.status().equals("UNAVAILABLE"))flags.add("HISTORY_UNAVAILABLE");return List.copyOf(flags);}
    private String source(){return mode.equals("DEMO")?"SIMULATED":"MYSQL_CURRENT";}
}
