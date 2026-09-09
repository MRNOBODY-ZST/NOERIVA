package io.noeriva.control;

import io.noeriva.query.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HeatmapSourceControllerTest {
    @Test void controllerResolvesInterfaceSourceInsideTheAuthenticatedOrganization(){
        var lookup=new AtomicReference<List<Object>>();
        ControlRepository repository=(ControlRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ControlRepository.class},(proxy,method,args)->switch(method.getName()){
            case "device" -> Mono.just(new Models.Device("device-a","switch","SWITCH","site-a","site","vendor","model","192.0.2.1","UNKNOWN","UNKNOWN",null,1,List.of("metrics")));
            case "interfaces" -> Flux.just(new Models.NetworkInterface("port-a","device-a","eth0",null,"1000000000","UP","UP"));
            case "interfaceSource" -> {lookup.set(Arrays.asList(args));yield Mono.just("network");}
            default -> throw new UnsupportedOperationException(method.getName());
        });
        RollupRepository rollups=(RollupRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RollupRepository.class},(proxy,method,args)->{
            if(method.getName().equals("loadRollups"))return Mono.just(new RollupRepository.Result(List.of(),"CLICKHOUSE:"+(args.length==7?args[3]:"primary")));
            throw new UnsupportedOperationException(method.getName());
        });
        var query=new QueryService(rollups,(o,d,m,f,t,p)->Mono.empty());
        try{
            var controller=new ControlController(repository,query,null,null,null);
            var user=new SecurityConfiguration.Operator("operator","redacted","org-authorized",List.of("ROLE_OPERATOR"));
            var result=controller.heatmap(user,"device-a","port-a","UTC","rx",7,"time_weighted_mean").block(Duration.ofSeconds(3));
            assertEquals("CLICKHOUSE:network",result.source());assertEquals(List.of("org-authorized","device-a","port-a"),lookup.get());
        }finally{query.close();}
    }
}
