package io.noeriva.control;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.TimeoutException;

@RestControllerAdvice
class HttpSupport {
    @ExceptionHandler(Exception.class)
    Mono<org.springframework.http.ResponseEntity<Map<String,String>>> error(Exception ex,ServerWebExchange exchange) {
        HttpStatus status=HttpStatus.INTERNAL_SERVER_ERROR;
        String code="INTERNAL_ERROR",message="The request could not be completed";
        if(ex instanceof ApiException a){status=a.status;code=a.code;message=a.getMessage();}
        else if(ex instanceof org.springframework.web.bind.support.WebExchangeBindException || ex instanceof IllegalArgumentException || ex instanceof jakarta.validation.ConstraintViolationException || ex instanceof org.springframework.web.method.annotation.HandlerMethodValidationException){status=HttpStatus.BAD_REQUEST;code="INVALID_REQUEST";message="Invalid request parameters";}
        else if(ex instanceof ResponseStatusException r){status=HttpStatus.valueOf(r.getStatusCode().value());code=status.name();message=r.getReason()==null?"Request rejected":r.getReason();}
        else if(ex instanceof TimeoutException || ex.getClass().getSimpleName().contains("Provider")){status=HttpStatus.SERVICE_UNAVAILABLE;code="PROVIDER_UNAVAILABLE";message="The data provider is unavailable; no complete result can be reported";}
        else if(ex.getClass().getSimpleName().contains("Admission") || ex instanceof io.noeriva.query.QueryRejectedException || ex instanceof java.util.concurrent.RejectedExecutionException){status=HttpStatus.TOO_MANY_REQUESTS;code="QUERY_CAPACITY";message="Query capacity reached; retry shortly";}
        if(status.is5xxServerError()) org.slf4j.LoggerFactory.getLogger(HttpSupport.class).warn("request_failed requestId={} type={}",exchange.getAttribute("requestId"),ex.getClass().getSimpleName());
        var builder=org.springframework.http.ResponseEntity.status(status);
        if(status==HttpStatus.TOO_MANY_REQUESTS)builder.header("Retry-After","1");
        return Mono.just(builder.body(Map.of("code",code,"message",message,"requestId",Objects.toString(exchange.getAttribute("requestId"),"unknown"))));
    }
}

@Component @Order(-200)
class RequestIdFilter implements WebFilter {
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        String id=UUID.randomUUID().toString();
        exchange.getAttributes().put("requestId",id);
        exchange.getResponse().getHeaders().set("X-Request-Id",id);
        exchange.getResponse().getHeaders().set("Cache-Control","no-store");
        return chain.filter(exchange);
    }
}
