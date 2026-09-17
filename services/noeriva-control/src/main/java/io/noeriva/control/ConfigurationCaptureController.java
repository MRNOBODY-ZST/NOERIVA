package io.noeriva.control;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import reactor.core.publisher.Mono;
import static io.noeriva.control.SecurityConfiguration.Operator;
@RestController @RequestMapping("/api/v1/workbench/configuration/devices/{id}/capture")
public class ConfigurationCaptureController {
 private final ConfigurationCapture captures;public ConfigurationCaptureController(ConfigurationCapture captures){this.captures=captures;}
 @GetMapping public Mono<ConfigurationCapture.State> state(@AuthenticationPrincipal Operator user,@PathVariable String id){return captures.state(user.organizationId(),id);}
 @PostMapping public Mono<ConfigurationCapture.State> capture(@AuthenticationPrincipal Operator user,@PathVariable String id){return captures.capture(user,id);}
}
