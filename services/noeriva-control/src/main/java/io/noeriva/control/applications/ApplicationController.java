package io.noeriva.control.applications;
import io.noeriva.control.Models;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import static io.noeriva.control.SecurityConfiguration.Operator;
import static io.noeriva.control.applications.ApplicationModels.*;
@RestController @RequestMapping("/api/v1/applications") public class ApplicationController {
    private final ApplicationService service;
    public ApplicationController(ApplicationService service){this.service=service;}
    @GetMapping("/sources") public Mono<Models.Page<Source>> sources(@AuthenticationPrincipal Operator user,@RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String cursor){return service.sources(user,limit,cursor);}
    @PostMapping("/devices/{id}/settings") public Mono<Source> save(@AuthenticationPrincipal Operator user,@PathVariable String id,@Valid @RequestBody SettingsInput input){return service.save(user,id,input);}
    @PostMapping("/devices/{id}/state") public Mono<Source> state(@AuthenticationPrincipal Operator user,@PathVariable String id,@Valid @RequestBody StateInput input){return service.state(user,id,input);}
    @PostMapping("/devices/{id}/collect") public Mono<Source> collect(@AuthenticationPrincipal Operator user,@PathVariable String id,@Valid @RequestBody CollectInput input){return service.collect(user,id,input);}
    @GetMapping("/summary") public Mono<Summary> summary(@AuthenticationPrincipal Operator user,@RequestParam String deviceId,@RequestParam(required=false) Integer interfaceIndex,@RequestParam(required=false) String direction,@RequestParam(required=false) String q,@RequestParam(defaultValue="12") int limit){return service.summary(user,deviceId,interfaceIndex,direction,q,limit);}
    @GetMapping("/observations") public Mono<Models.Page<Observation>> observations(@AuthenticationPrincipal Operator user,@RequestParam String deviceId,@RequestParam(required=false) Integer interfaceIndex,@RequestParam(required=false) String direction,@RequestParam(required=false) String q,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="100") int limit,@RequestParam(required=false) String cursor){return service.observations(user,deviceId,interfaceIndex,direction,q,from,to,limit,cursor);}
}
