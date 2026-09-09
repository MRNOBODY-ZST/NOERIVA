package io.noeriva.control;

import java.time.Instant;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import static io.noeriva.control.SecurityConfiguration.Operator;

/** Exact state counts, independent of the alert queue's page size. Acknowledgement is not recovery. */
@RestController @RequestMapping("/api/v1") @Validated
public class AlertSummaryController {
    private final ControlRepository repository;
    private final DatabaseClient db;
    public AlertSummaryController(ControlRepository repository,ObjectProvider<DatabaseClient> db){this.repository=repository;this.db=db.getIfAvailable();}
    public record AlertSummary(long open,long acknowledged,long resolved,long active,Instant asOf) {}

    @GetMapping("/alerts/summary")
    public Mono<AlertSummary> summary(@AuthenticationPrincipal Operator user,@RequestParam(defaultValue="") @Size(max=64) String deviceId){
        String org=user.organizationId();
        Mono<?> scope=deviceId.isEmpty()?Mono.just(true):repository.device(org,deviceId).switchIfEmpty(Mono.error(ApiException.missing()));
        return scope.then(Mono.defer(()->{
            if(db==null)return repository.alerts(org,deviceId,"",Integer.MAX_VALUE)
                .collect(()->new long[3],(counts,alert)->{switch(alert.state()){case "OPEN"->counts[0]++;case "ACKNOWLEDGED"->counts[1]++;case "RESOLVED"->counts[2]++;default->{}}})
                .map(counts->new AlertSummary(counts[0],counts[1],counts[2],counts[0]+counts[1],Instant.now()));
            var query=db.sql("SELECT /*+ MAX_EXECUTION_TIME(3000) */ COALESCE(SUM(state='OPEN'),0) pending,COALESCE(SUM(state='ACKNOWLEDGED'),0) acknowledged,COALESCE(SUM(state='RESOLVED'),0) resolved FROM alert WHERE organization_id=:org"+(deviceId.isEmpty()?"":" AND device_id=:device")).bind("org",org);
            if(!deviceId.isEmpty())query=query.bind("device",deviceId);
            return query.map((row,meta)->{
                long open=((Number)row.get("pending")).longValue(),acknowledged=((Number)row.get("acknowledged")).longValue(),resolved=((Number)row.get("resolved")).longValue();
                return new AlertSummary(open,acknowledged,resolved,open+acknowledged,Instant.now());
            }).one();
        }));
    }
}
