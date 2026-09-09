package io.noeriva.control;

import io.noeriva.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import static io.noeriva.control.SecurityConfiguration.Operator;

@RestController @RequestMapping("/api/v1/devices")
public class DeviceBandwidthController {
    private final ControlRepository repository;private final QueryService query;
    public DeviceBandwidthController(ControlRepository repository,QueryService query){this.repository=repository;this.query=query;}
    @GetMapping("/{id}/bandwidth/heatmap")
    public Mono<HeatmapResponse> heatmap(@AuthenticationPrincipal Operator actor,@PathVariable String id,
        @RequestParam String timezone,@RequestParam(defaultValue="rx") String direction,
        @RequestParam(defaultValue="7") int days,@RequestParam(defaultValue="time_weighted_mean") String statistic){
        if(days!=7||!java.util.Set.of("time_weighted_mean","sample_mean").contains(statistic))throw new IllegalArgumentException("Only the seven-day sampled device gauge heatmap is supported");
        return repository.device(actor.organizationId(),id).switchIfEmpty(Mono.error(ApiException.missing())).flatMap(device->{
            if(!device.capabilities().contains("metrics"))return Mono.error(new ApiException(HttpStatus.CONFLICT,"UNSUPPORTED_CAPABILITY","No metric source is assigned to this device"));
            return query.deviceHeatmap(actor.organizationId(),id,timezone,direction);
        });
    }
}
