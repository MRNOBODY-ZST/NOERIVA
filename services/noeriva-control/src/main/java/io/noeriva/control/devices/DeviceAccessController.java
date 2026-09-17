package io.noeriva.control.devices;

import io.noeriva.control.*;
import jakarta.validation.Valid;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.*;
import static io.noeriva.control.SecurityConfiguration.Operator;
import static io.noeriva.control.devices.DeviceAccessModels.*;

@RestController @RequestMapping("/api/v1")
public class DeviceAccessController {
    private final DeviceAccessService service;private final Semaphore streams=new Semaphore(64);
    public DeviceAccessController(DeviceAccessService service){this.service=service;}
    @GetMapping("/device-support") public Map<String,Object> support(){return service.support();}
    @GetMapping("/devices/{id}/management") public Mono<Management> management(@AuthenticationPrincipal Operator user,@PathVariable String id){return service.management(user,id);}
    @PostMapping("/devices/{id}/updates") public Mono<Management> update(@AuthenticationPrincipal Operator user,@PathVariable String id,@Valid @RequestBody Update input){return service.update(user,id,input);}
    @GetMapping("/devices/{id}/connections") public Mono<Models.Items<ConnectionView>> connections(@AuthenticationPrincipal Operator user,@PathVariable String id){return service.connections(user,id);}
    @PostMapping("/devices/{id}/connections/{slot}") public Mono<ConnectionView> save(@AuthenticationPrincipal Operator user,@PathVariable String id,@PathVariable String slot,@Valid @RequestBody Save input){return service.save(user,id,slot,input);}
    @PostMapping("/devices/{id}/connections/{slot}/state") public Mono<ConnectionView> state(@AuthenticationPrincipal Operator user,@PathVariable String id,@PathVariable String slot,@Valid @RequestBody State input){return service.state(user,id,slot,input);}
    @PostMapping("/devices/{id}/connections/{slot}/test") public Mono<ConnectionView> test(@AuthenticationPrincipal Operator user,@PathVariable String id,@PathVariable String slot,@Valid @RequestBody Revision input){return service.read(user,id,slot,input,false);}
    @PostMapping("/devices/{id}/connections/{slot}/collect") public Mono<ConnectionView> collect(@AuthenticationPrincipal Operator user,@PathVariable String id,@PathVariable String slot,@Valid @RequestBody Revision input){return service.read(user,id,slot,input,true);}
    @GetMapping("/devices/{id}/collection") public Mono<Models.Items<CollectionView>> collection(@AuthenticationPrincipal Operator user,@PathVariable String id){return service.collection(user,id);}
    @GetMapping(value="/devices/{id}/live",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Models.Items<CollectionView>>> live(@AuthenticationPrincipal Operator user,@PathVariable String id,ServerWebExchange exchange){
        exchange.getResponse().getHeaders().set("X-Accel-Buffering","no");exchange.getResponse().getHeaders().setCacheControl("no-store");
        return Flux.using(()->{if(!streams.tryAcquire())throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"STREAM_CAPACITY","Device live view capacity reached");return true;},
            lease->service.collection(user,id).flux().concatWith(Flux.interval(Duration.ofSeconds(3)).onBackpressureDrop().concatMap(n->service.collection(user,id),1))
                .take(Duration.ofMinutes(10)).map(value->ServerSentEvent.<Models.Items<CollectionView>>builder(value).event("collection").build()),lease->streams.release());
    }
}
