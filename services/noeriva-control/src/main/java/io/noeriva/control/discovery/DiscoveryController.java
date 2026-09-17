package io.noeriva.control.discovery;

import io.noeriva.control.Models.Page;
import io.noeriva.control.SecurityConfiguration.Operator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import static io.noeriva.control.discovery.DiscoveryModels.*;

@RestController @RequestMapping("/api/v1/discovery")
public class DiscoveryController {
    private final DiscoveryService service;
    public DiscoveryController(DiscoveryService service){this.service=service;}
    @PostMapping("/runs") public Mono<RunResult> run(@AuthenticationPrincipal Operator actor,@Valid @RequestBody RunInput input){return service.run(actor,input);}
    @GetMapping("/candidates") public Mono<Page<Candidate>> candidates(@AuthenticationPrincipal Operator actor,
        @RequestParam(defaultValue="") @Size(max=64) String siteId,@RequestParam(defaultValue="") @Size(max=32) String status,
        @RequestParam(defaultValue="") @Size(max=36) String cursor,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return service.candidates(actor,siteId,status,cursor,limit);}
    @PostMapping("/candidates/{id}/register") public Mono<Candidate> register(@AuthenticationPrincipal Operator actor,@PathVariable @Size(max=36) String id,@Valid @RequestBody RegisterInput input){return service.register(actor,id,input);}
    @PostMapping("/candidates/{id}/link") public Mono<Candidate> link(@AuthenticationPrincipal Operator actor,@PathVariable @Size(max=36) String id,@Valid @RequestBody LinkInput input){return service.link(actor,id,input);}
}
