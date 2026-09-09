package io.noeriva.control;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class WorkspaceEntitySearchScopeTest {
 @Test void everyPortDeepLinkIsResolvedFromCurrentTenantInventory(){
  var calls=new ArrayList<List<Object>>();Instant at=Instant.now();
  var device=new Models.Device("device-a","Current Router","ROUTER","site-a","Main","Cisco","ASR","192.0.2.1","HEALTHY","ONLINE",at,1,List.of("interfaces"));
  var port=new Models.NetworkInterface("port-a","device-a","Te0/1/0",null,"10000000000","UP","UP");
  var inventory=(ControlRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ControlRepository.class},(proxy,method,args)->{
   calls.add(List.of(method.getName(),args[0]));assertThat(args[0]).isEqualTo("tenant-a");
   return switch(method.getName()){
    case "deviceBatch" -> Flux.just(device);
    case "interfaces" -> {assertThat(args[1]).isEqualTo("device-a");yield Flux.just(port);}
    default -> throw new AssertionError(method.getName());
   };
  });
  var search=new EntitySearch(new StaticListableBeanFactory().getBeanProvider(DatabaseClient.class),WebClient.builder(),new MockEnvironment(),new JsonMapper()){
   @Override public Mono<Result> search(String org,String q,int limit){assertThat(org).isEqualTo("tenant-a");return Mono.just(new Result(List.of(
    new Hit("INTERFACE","port-a","device-a","stale-name","stale-device","","ELASTICSEARCH",at),
    new Hit("INTERFACE","gone-port","device-a","removed","stale-device","","ELASTICSEARCH",at),
    new Hit("INTERFACE","foreign-port","foreign-device","foreign","foreign","","ELASTICSEARCH",at)),"ELASTICSEARCH","READY",at));}
  };
  var controller=new WorkspaceController(null,inventory,search);
  var result=controller.search(new SecurityConfiguration.Operator("viewer","unused","tenant-a",List.of("VIEWER")),"port",10).block();
  assertThat(result.interfaces()).hasSize(1);var actual=result.interfaces().getFirst();assertThat(actual.id()).isEqualTo("port-a");assertThat(actual.deviceId()).isEqualTo("device-a");assertThat(actual.deviceName()).isEqualTo("Current Router");assertThat(actual.name()).isEqualTo("Te0/1/0");assertThat(result.provider()).isEqualTo("ELASTICSEARCH");assertThat(calls).isNotEmpty();
 }
}
