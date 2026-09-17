package io.noeriva.query;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HeatmapSourceRoutingTest {
    @SuppressWarnings("unchecked")
    private Mono<HeatmapResponse> explicitSource(QueryService service,String source){
        try{return (Mono<HeatmapResponse>)QueryService.class.getMethod("heatmap",String.class,String.class,String.class,String.class,String.class,String.class)
            .invoke(service,"org-a","device-a","port-a",source,"UTC","rx");}
        catch(ReflectiveOperationException e){throw new AssertionError("A scoped heatmap must accept the authorized interface source",e);}
    }
    @Test void explicitNetworkSourceFlowsThroughQueryServiceToBoundClickhouseParameter()throws Exception{
        var request=new AtomicReference<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{request.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8));byte[] body="{\"data\":[]}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        var repository=new ClickHouseRollupRepository(QueryHttpClient.create("http://127.0.0.1:"+server.getAddress().getPort(),null,null),"primary");
        var service=new QueryService(repository,(o,d,m,f,t,p)->Mono.empty());
        try{
            var result=explicitSource(service,"network").block(Duration.ofSeconds(8));
            assertEquals("CLICKHOUSE:network",result.source());
            assertTrue(request.get().contains("param_source=network"));assertFalse(request.get().contains("param_source=primary"));
            assertTrue(request.get().contains("param_organization=org-a"));assertTrue(request.get().contains("param_component=port-a"));
            var legacy=service.heatmap("org-a","device-a","port-a","UTC","rx").block(Duration.ofSeconds(8));
            assertEquals("CLICKHOUSE:primary",legacy.source());assertTrue(request.get().contains("param_source=primary"));
        }finally{service.close();server.stop(0);}
    }
    @Test void invalidSourceFailsBeforeProviderAccess(){
        var service=new QueryService((o,d,i,r,f,t)->{fail("Invalid source reached provider");return Mono.empty();},(o,d,m,f,t,p)->Mono.empty());
        try{assertThrows(IllegalArgumentException.class,()->explicitSource(service,"network\"}").block(Duration.ofSeconds(2)));}
        finally{service.close();}
    }
}
