package io.noeriva.control;

import io.noeriva.query.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.time.Duration;

@Component @Order(-150)
public class StateAdmissionFilter implements WebFilter {
    private final QueryAdmission requests=new QueryAdmission(64,Duration.ofSeconds(12));
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        String path=exchange.getRequest().getPath().value();
        if(!path.startsWith("/api/")||path.contains("/stream/")||path.contains("/ingest/")||path.matches("/api/v1/workbench/configuration/devices/[^/]+/capture")||path.matches("/api/v1/workbench/checks/[^/]+/run")||path.matches("/api/v1/applications/devices/[^/]+/collect")||path.matches("/api/v1/devices/[^/]+/live")||path.matches("/api/v1/devices/[^/]+/connections/(snmp|redfish|ssh)/(test|collect)"))return chain.filter(exchange);
        return requests.execute(QueryLane.INTERACTIVE_STATE,()->chain.filter(exchange)).onErrorResume(QueryRejectedException.class,e->{exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);exchange.getResponse().getHeaders().set("Retry-After","1");return exchange.getResponse().setComplete();});
    }
}
