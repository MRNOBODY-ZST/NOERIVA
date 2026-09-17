package io.noeriva.control;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import io.noeriva.query.*;
import static io.noeriva.control.Models.*;
import static io.noeriva.control.SecurityConfiguration.Operator;

@RestController @RequestMapping("/api/v1") @Validated
public class ControlController {
    private final ControlRepository repository; private final QueryService query; private final IngestService ingest;
    private final String mode; private final Semaphore streams=new Semaphore(200); private final SessionTokens tokens;
    public ControlController(ControlRepository repository,QueryService query,IngestService ingest,Environment env,SessionTokens tokens){this.repository=repository;this.query=query;this.ingest=ingest;this.tokens=tokens;this.mode=repository instanceof DemoRepository?"DEMO":"CONNECTED";}
    @GetMapping("/session") public Mono<Map<String,Object>> session(@AuthenticationPrincipal Operator user,@RequestHeader("Authorization") String authorization){
        return (authorization.startsWith("Basic ")?tokens.issue(user.username()):Mono.just(authorization.substring(7))).map(token->{Map<String,Object> response=new LinkedHashMap<>();response.put("username",user.username());response.put("organizationId",user.organizationId());response.put("roles",user.roles());response.put("mode",mode);response.put("timezone",null);response.put("accessToken",token);response.put("tokenType","Bearer");return response;});
    }
    @GetMapping("/overview") public Mono<Overview> overview(@AuthenticationPrincipal Operator user){return repository.overview(user.organizationId());}
    @GetMapping("/sites") public Mono<Items<Site>> sites(@AuthenticationPrincipal Operator user){return repository.sites(user.organizationId()).collectList().map(list->new Items<>(list,Instant.now()));}
    @GetMapping("/devices") public Mono<Page<Device>> devices(@AuthenticationPrincipal Operator user,
        @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,@RequestParam(defaultValue="") @Size(max=64) String cursor,
        @RequestParam(defaultValue="") @Size(max=120) String q,@RequestParam(defaultValue="") @Size(max=64) String siteId,
        @RequestParam(defaultValue="") @Size(max=32) String type,@RequestParam(defaultValue="") @Size(max=16) String health){
        return repository.devices(user.organizationId(),limit+1,cursor,q,siteId,type,health).collectList().map(list->new Page<>(list.stream().limit(limit).toList(),list.size()>limit?list.get(limit-1).id():null,Instant.now(),"CONTROL",mode));
    }
    private Mono<Device> required(String org,String id){return repository.device(org,id).switchIfEmpty(Mono.error(ApiException.missing()));}
    @GetMapping("/devices/{id}") public Mono<Device> device(@AuthenticationPrincipal Operator user,@PathVariable String id){return required(user.organizationId(),id);}
    @PostMapping("/devices") @ResponseStatus(HttpStatus.CREATED) public Mono<Device> create(@AuthenticationPrincipal Operator user,@Valid @RequestBody CreateDevice input){return repository.create(user.organizationId(),user.username(),input);}
    @GetMapping("/devices/{id}/summary") public Mono<DeviceSummary> summary(@AuthenticationPrincipal Operator user,@PathVariable String id){
        return required(user.organizationId(),id).flatMap(device->repository.sources(user.organizationId(),id).collectList()
            .zipWith(repository.alerts(user.organizationId(),id,"",1000).filter(a->!a.state().equals("RESOLVED")).count())
            .map(t->{var sources=t.getT1();var fresh=sources.stream().filter(s->s.freshness().equals("FRESH")).count();String state=sources.isEmpty()?"MISSING":fresh==sources.size()?"FRESH":"STALE";
                return new DeviceSummary(device,sources,t.getT2(),Instant.now(),state,sources.isEmpty()?0:(double)fresh/sources.size(),0,Long.toString(device.revision()),false,state.equals("FRESH")?List.of():List.of("SOURCE_"+state));}));
    }
    @GetMapping("/devices/{id}/interfaces") public Mono<Items<NetworkInterface>> interfaces(@AuthenticationPrincipal Operator user,@PathVariable String id){return required(user.organizationId(),id).then(repository.interfaces(user.organizationId(),id).collectList()).map(list->new Items<>(list,Instant.now()));}
    @PostMapping("/devices/{id}/interfaces") @ResponseStatus(HttpStatus.CREATED) public Mono<NetworkInterface> registerInterface(@AuthenticationPrincipal Operator user,@PathVariable String id,@Valid @RequestBody RegisterInterface input){if(new java.math.BigInteger(input.speedBps()).compareTo(new java.math.BigInteger("18446744073709551615"))>0)throw new IllegalArgumentException("Speed exceeds UInt64");return repository.registerInterface(user.organizationId(),user.username(),id,input);}
    @GetMapping("/devices/{id}/metrics") public Mono<MetricResponse> metrics(@AuthenticationPrincipal Operator user,@PathVariable String id,
        @RequestParam(defaultValue="cpu_percent") String metric,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="120") @Min(2) @Max(2000) int points){
        Instant end=to==null?Instant.now():to;Instant start=from==null?end.minusSeconds(3600):from;
        return required(user.organizationId(),id).flatMap(d->{if(!d.capabilities().contains("metrics"))return Mono.error(new ApiException(HttpStatus.CONFLICT,"UNSUPPORTED_CAPABILITY","No metric source is assigned to this device"));return query.metrics(user.organizationId(),id,metric,start,end,points);});
    }
    @GetMapping("/devices/{id}/interfaces/{interfaceId}/bandwidth/heatmap") public Mono<HeatmapResponse> heatmap(@AuthenticationPrincipal Operator user,@PathVariable String id,@PathVariable String interfaceId,
        @RequestParam String timezone,@RequestParam(defaultValue="rx") String direction,@RequestParam(defaultValue="7") int days,@RequestParam(defaultValue="time_weighted_mean") String statistic){
        if(days!=7||!statistic.equals("time_weighted_mean"))return Mono.error(new IllegalArgumentException("Only the seven-day time-weighted mean is supported"));
        return required(user.organizationId(),id).then(repository.interfaces(user.organizationId(),id).filter(i->i.id().equals(interfaceId)).next().switchIfEmpty(Mono.error(ApiException.missing())))
            .then(repository.interfaceSource(user.organizationId(),id,interfaceId).switchIfEmpty(Mono.error(ApiException.missing())))
            .flatMap(source->query.heatmap(user.organizationId(),id,interfaceId,source,timezone,direction));
    }
    @org.springframework.beans.factory.annotation.Autowired private TopologyService topologyService;
    @GetMapping("/topology") public Mono<Topology> topology(@AuthenticationPrincipal Operator user,
        @RequestParam(defaultValue="") @Size(max=64) String deviceId,@RequestParam(defaultValue="") @Size(max=64) String siteId,
        @RequestParam(defaultValue="PHYSICAL") @Pattern(regexp="PHYSICAL|ALL") String view,
        @RequestParam(required=false) @Min(1) @Max(4094) Integer vlanId,
        @RequestParam(defaultValue="200") @Min(1) @Max(200) int limit){
        return topologyService.snapshot(user.organizationId(),siteId,deviceId,view,vlanId,limit);
    }
    @GetMapping({"/events","/devices/{id}/activity"}) public Mono<Page<Event>> events(@AuthenticationPrincipal Operator user,@PathVariable(required=false) String id,
        @RequestParam(defaultValue="") String deviceId,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,@RequestParam(defaultValue="") @Size(max=512) String cursor,
        @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to){
        Instant end=to==null?Instant.now():to;Instant start=from==null?end.minus(Duration.ofDays(7)):from;
        if(!start.isBefore(end)||Duration.between(start,end).compareTo(Duration.ofDays(31))>0)return Mono.error(new IllegalArgumentException("Event range must be positive and at most 31 days"));
        EventCursor.decode(cursor);String device=id==null?deviceId:id;
        return (device.isEmpty()?Mono.just(true):required(user.organizationId(),device)).then(repository.events(user.organizationId(),device,start,end,cursor,limit+1).collectList())
            .map(list->new Page<>(list.stream().limit(limit).toList(),list.size()>limit?EventCursor.encode(list.get(limit-1)):null,Instant.now(),mode.equals("DEMO")?"SIMULATED":"CLICKHOUSE",mode));
    }
    @GetMapping("/alerts") public Mono<Items<Alert>> alerts(@AuthenticationPrincipal Operator user,@RequestParam(defaultValue="") String deviceId,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return repository.alerts(user.organizationId(),deviceId,state,limit).collectList().map(list->new Items<>(list,Instant.now()));}
    @PostMapping("/alerts/{id}/acknowledge") public Mono<Alert> acknowledge(@AuthenticationPrincipal Operator user,@PathVariable String id,@Valid @RequestBody Acknowledge input){return repository.acknowledge(user.organizationId(),user.username(),id,input.revision());}
    @GetMapping("/collectors") public Mono<Items<Collector>> collectors(@AuthenticationPrincipal Operator user){return repository.collectors(user.organizationId()).collectList().map(list->new Items<>(list,Instant.now()));}
    @PostMapping("/ingest/batches") @ResponseStatus(HttpStatus.ACCEPTED) public Mono<AcceptedBatch> ingest(@AuthenticationPrincipal Operator user,@Valid @RequestBody IngestBatch input){return ingest.accept(user.organizationId(),input);}
    @GetMapping(value="/stream/device-status",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public Flux<org.springframework.http.codec.ServerSentEvent<Map<String,Object>>> stream(){
        return Flux.using(()->{if(!streams.tryAcquire())throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"STREAM_CAPACITY","Realtime capacity reached");return true;},lease->Flux.interval(Duration.ZERO,Duration.ofSeconds(15)).onBackpressureDrop()
            .map(n->org.springframework.http.codec.ServerSentEvent.<Map<String,Object>>builder(Map.of("revision",n,"asOf",Instant.now().toString())).event("snapshot-required").build()),lease->streams.release());
    }
    @GetMapping("/capabilities") public Map<String,Object> capabilities(){return Map.of("mode",mode,"items",List.of(
        capability("inventory","资产与当前状态",1,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","设备新建/独立版本编辑、来源新鲜度、接口目录与审计；已验收机型和固件范围见设备支持目录"),
        capability("workspace","澄明工作台查询",1,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","工作台、站点聚合与优先巡检；Elasticsearch 设备/接口搜索与直接定位"),
        capability("metrics","指标与带宽热力图",2,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","VictoriaMetrics / ClickHouse 有界查询；总带宽与接口热图、单位换算；Dell S6100-ON与Cisco ASR1002-X真机趋势已验收"),
        capability("alerts","状态告警与确认",1,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","状态转换、乐观锁确认；数值规则、通知与静默尚未实现"),
        capability("incidents","事件单与处置记录",9,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","创建、负责人、状态与版本化记录；保留操作者和审计，不自动断言根因"),
        capability("configuration","配置快照与差异",8,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","周期或手动只读同步、凭据脱敏、内容摘要与差异；Dell/Cisco运行配置，BMC身份接口基线；不下发配置"),
        capability("checks","探测定义与结果",2,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","TCP/HTTP/HTTPS/DNS/TLS有界探测、跨实例租约调度、立即执行、来源结果与历史"),
        capability("nat","NAT 与租约区间调查",7,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","Cisco NAT HSL回传、全保留历史倒序游标、可选时间范围与地址过滤；无现实人员身份归因"),
        capability("evidence","证据内容与访问审计",10,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","MySQL 持久保存、SHA-256 校验与清单导出；未签名，无 WORM 或法律保留"),
        capability("network","网络协议与设备采集",3,"EXPERIMENTAL","PARTIAL","INTEGRATION_TESTED","SNMP v2c/v3、Redfish、固定SSH只读采集；已注册设备的LLDP/CDP真实邻接；Trap和厂商覆盖按支持目录核验"),
        capability("governance","证据保全与完整治理",10,"DESIGNED","NONE","NOT_TESTED","对象存储、数字签名、保留锁、法律保留、细粒度站点授权待实现")));
    }
    private Map<String,Object> capability(String id,String name,int phase,String maturity,String coverage,String verification,String notes){return Map.of("id",id,"name",name,"phase",phase,"roadmapTier",phase<=3?"MVP":"PLANNED","maturity",maturity,"coverage",coverage,"verification",verification,"notes",notes);}
}
