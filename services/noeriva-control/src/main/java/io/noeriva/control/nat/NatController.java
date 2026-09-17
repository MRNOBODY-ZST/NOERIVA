package io.noeriva.control.nat;

import io.noeriva.control.SecurityConfiguration.Operator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import static io.noeriva.control.nat.NatModels.*;

@RestController @RequestMapping("/api/v1/nat-audit")
public class NatController {
 private final NatSourceStore sources;private final NatHistory history;
 public NatController(NatSourceStore sources,NatHistory history){this.sources=sources;this.history=history;}
 @GetMapping("/sources") public Mono<SourceItems> sources(@AuthenticationPrincipal Operator actor){return sources.list(actor);}
 @PostMapping("/devices/{id}/settings") public Mono<SourceView> settings(@AuthenticationPrincipal Operator actor,@PathVariable @Size(max=64) String id,@Valid @RequestBody SettingsInput input){return sources.save(actor,id,input);}
 @PostMapping("/devices/{id}/state") public Mono<SourceView> state(@AuthenticationPrincipal Operator actor,@PathVariable @Size(max=64) String id,@Valid @RequestBody StateInput input){return sources.state(actor,id,input);}
 @GetMapping("/events") public Mono<EventPage> events(@AuthenticationPrincipal Operator actor,@RequestParam @Size(max=64) String deviceId,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(required=false) Integer protocol,@RequestParam(defaultValue="") @Size(max=45) String privateIp,@RequestParam(defaultValue="") @Size(max=45) String publicIp,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,@RequestParam(defaultValue="") @Size(max=800) String cursor){return history.events(actor,deviceId,from,to,protocol,privateIp,publicIp,limit,cursor);}
}
