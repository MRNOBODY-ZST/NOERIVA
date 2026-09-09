package io.noeriva.control;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import static io.noeriva.control.SecurityConfiguration.Operator;
import static io.noeriva.control.WorkbenchModels.*;

@RestController @RequestMapping("/api/v1/workbench/checks")
public class CheckExecutionController {
    private final CheckExecution execution;
    public CheckExecutionController(CheckExecution execution){this.execution=execution;}
    @PostMapping("/{id}/run") public Mono<CheckResult> run(@AuthenticationPrincipal Operator actor,@PathVariable @Size(max=64) String id,@Valid @RequestBody Revision input){return execution.run(actor,id,input.revision());}
}
