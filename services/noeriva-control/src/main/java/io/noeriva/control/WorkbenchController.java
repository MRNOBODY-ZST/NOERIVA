package io.noeriva.control;

import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import static io.noeriva.control.Models.Page;
import static io.noeriva.control.SecurityConfiguration.Operator;
import static io.noeriva.control.WorkbenchModels.*;

@RestController @RequestMapping("/api/v1/workbench")
public class WorkbenchController {
    private final WorkbenchService service;
    public WorkbenchController(WorkbenchService service){this.service=service;}
    @GetMapping("/incidents") public Mono<Page<IncidentSummary>> incidents(@AuthenticationPrincipal Operator a,
        @RequestParam(defaultValue="") @Size(max=64) String deviceId,@RequestParam(defaultValue="") @Size(max=24) String status,
        @RequestParam(defaultValue="") @Size(max=120) String q,@RequestParam(defaultValue="") @Size(max=512) String cursor,
        @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return service.incidents(a,deviceId,status,q,cursor,limit);}
    @PostMapping("/incidents") @ResponseStatus(HttpStatus.CREATED) public Mono<Incident> createIncident(@AuthenticationPrincipal Operator a,@Valid @RequestBody IncidentInput in){return service.createIncident(a,in);}
    @GetMapping("/incidents/{id}") public Mono<Incident> incident(@AuthenticationPrincipal Operator a,@PathVariable @Size(max=64) String id){return service.incident(a,id);}
    @PostMapping("/incidents/{id}/updates") public Mono<Incident> updateIncident(@AuthenticationPrincipal Operator a,@PathVariable @Size(max=64) String id,@Valid @RequestBody IncidentUpdate in){return service.updateIncident(a,id,in);}
    @GetMapping("/evidence") public Mono<Page<EvidenceMetadata>> evidence(@AuthenticationPrincipal Operator a,
        @RequestParam(defaultValue="") @Size(max=64) String deviceId,@RequestParam(defaultValue="") @Size(max=120) String q,
        @RequestParam(defaultValue="") @Size(max=512) String cursor,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return service.evidence(a,deviceId,q,cursor,limit);}
    @PostMapping("/evidence") @ResponseStatus(HttpStatus.CREATED) public Mono<Evidence> createEvidence(@AuthenticationPrincipal Operator a,@Valid @RequestBody EvidenceInput in){return service.createEvidence(a,in);}
    @GetMapping("/evidence/{id}") public Mono<Evidence> evidence(@AuthenticationPrincipal Operator a,@PathVariable @Size(max=64) String id){return service.evidence(a,id,false);}
    @GetMapping("/evidence/{id}/manifest") public Mono<Manifest> manifest(@AuthenticationPrincipal Operator a,@PathVariable @Size(max=64) String id){return service.manifest(a,id);}
    @GetMapping("/configuration/snapshots") public Mono<Page<SnapshotMetadata>> snapshots(@AuthenticationPrincipal Operator a,
        @RequestParam(defaultValue="") @Size(max=64) String deviceId,@RequestParam(defaultValue="") @Size(max=120) String q,
        @RequestParam(defaultValue="") @Size(max=512) String cursor,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return service.snapshots(a,deviceId,q,cursor,limit);}
    @PostMapping("/configuration/snapshots") @ResponseStatus(HttpStatus.CREATED) public Mono<Snapshot> createSnapshot(@AuthenticationPrincipal Operator a,@Valid @RequestBody ConfigurationInput in){return service.createSnapshot(a,in);}
    @GetMapping("/configuration/snapshots/{id}") public Mono<Snapshot> snapshot(@AuthenticationPrincipal Operator a,@PathVariable @Size(max=64) String id){return service.snapshot(a,id);}
    @GetMapping("/configuration/diff") public Mono<Diff> diff(@AuthenticationPrincipal Operator a,@RequestParam @Size(max=64) String before,@RequestParam @Size(max=64) String after){return service.diff(a,before,after);}
    @GetMapping("/checks") public Mono<Page<Check>> checks(@AuthenticationPrincipal Operator a,
        @RequestParam(defaultValue="") @Size(max=64) String deviceId,@RequestParam(defaultValue="") @Size(max=120) String q,
        @RequestParam(defaultValue="") @Size(max=512) String cursor,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return service.checks(a,deviceId,q,cursor,limit);}
    @PostMapping("/checks") @ResponseStatus(HttpStatus.CREATED) public Mono<Check> createCheck(@AuthenticationPrincipal Operator a,@Valid @RequestBody CheckInput in){return service.createCheck(a,in);}
    @GetMapping("/checks/{id}") public Mono<Check> check(@AuthenticationPrincipal Operator a,@PathVariable @Size(max=64) String id){return service.check(a,id);}
    @PostMapping("/checks/{id}/updates") public Mono<Check> updateCheck(@AuthenticationPrincipal Operator a,@PathVariable @Size(max=64) String id,@Valid @RequestBody CheckUpdate in){return service.updateCheck(a,id,in);}
    @PostMapping("/checks/{id}/archive") public Mono<Check> archiveCheck(@AuthenticationPrincipal Operator a,@PathVariable @Size(max=64) String id,@Valid @RequestBody Revision in){return service.archiveCheck(a,id,in);}
    @GetMapping("/checks/{id}/results") public Mono<Page<CheckResult>> results(@AuthenticationPrincipal Operator a,@PathVariable @Size(max=64) String id,
        @RequestParam(defaultValue="") @Size(max=512) String cursor,@RequestParam(defaultValue="30") @Min(1) @Max(100) int limit){return service.results(a,id,cursor,limit);}
    @PostMapping("/check-results") @ResponseStatus(HttpStatus.CREATED) public Mono<CheckResult> reportResult(@AuthenticationPrincipal Operator a,@Valid @RequestBody CheckResultInput in){return service.reportResult(a,in);}
    @PostMapping("/network-evidence") @ResponseStatus(HttpStatus.CREATED) public Mono<NetworkEvidence> reportNetwork(@AuthenticationPrincipal Operator a,@Valid @RequestBody NetworkInput in){return service.reportNetwork(a,in);}
    @PostMapping("/investigations") public Mono<Investigation> investigate(@AuthenticationPrincipal Operator a,@Valid @RequestBody InvestigationInput in){return service.investigate(a,in);}
    @GetMapping("/audit") public Mono<Page<Audit>> audits(@AuthenticationPrincipal Operator a,
        @RequestParam(defaultValue="") @Size(max=128) String resourceId,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
        @RequestParam(defaultValue="") @Size(max=512) String cursor,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return service.audits(a,resourceId,from,to,cursor,limit);}
}
