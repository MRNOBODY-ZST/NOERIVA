package io.noeriva.control;
import io.noeriva.query.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
class DeviceBandwidthControllerTest {
 @Test void missingOwnedDeviceStopsBeforeAnyMetricProviderAccess(){
  var seen=new AtomicReference<List<Object>>();
  var repository=(ControlRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ControlRepository.class},(proxy,method,args)->{assertEquals("device",method.getName());seen.set(Arrays.asList(args));return Mono.empty();});
  var query=new QueryService((o,d,i,r,f,t)->{throw new AssertionError();},(o,d,m,f,t,p)->{throw new AssertionError("Unowned device reached provider");});
  try{var controller=new DeviceBandwidthController(repository,query);assertThrows(ApiException.class,()->controller.heatmap(new SecurityConfiguration.Operator("viewer","unused","tenant-a",List.of("VIEWER")),"foreign-device","UTC","rx",7,"time_weighted_mean").block());assertEquals(List.of("tenant-a","foreign-device"),seen.get());}finally{query.close();}
 }
}
