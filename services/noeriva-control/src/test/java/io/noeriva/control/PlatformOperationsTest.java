package io.noeriva.control;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import reactor.core.publisher.Mono;
import io.noeriva.control.devices.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.noeriva.control.SecurityConfiguration.Operator;

class PlatformOperationsTest {
 private final StaticListableBeanFactory beans=new StaticListableBeanFactory();
 private final JsonMapper json=JsonMapper.builder().build();
 private Operator user(String org){return new Operator("administrator","unused",org,List.of("ADMIN"));}
 private PlatformSettings settings(){return new PlatformSettings(beans.getBeanProvider(DatabaseClient.class),json);}
 @Test void settingsKeepTenantScopeAndRejectStaleWritesAndInvalidZones(){
  var settings=settings();var input=new PlatformSettings.Input(0,"Test Lab","UTC",60,5000,128,true,3600);
  assertThat(settings.save(user("one"),input).block().revision()).isEqualTo(1);
  assertThat(settings.get("two").block().revision()).isZero();
  assertThatThrownBy(()->settings.save(user("one"),input).block()).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(HttpStatus.CONFLICT));
  assertThatThrownBy(()->settings.save(new Operator("v","unused","one",List.of("VIEWER")),input).block()).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->PlatformSettings.validate(new PlatformSettings.Input(0,"Test","GMT+99",60,5000,128,true,3600))).isInstanceOf(IllegalArgumentException.class);
 }
 private DeviceProtocol.Reading reading(String name,Instant at,String neighbors){return new DeviceProtocol.Reading(at,new DeviceProtocol.Identity("test","switch","test","fixture","serial","v1",null,name,"fixture"),"HEALTHY",Map.of(),List.of(),List.of(),List.of(),List.of(),Map.of("neighborObservations",neighbors));}
 @Test void topologyOnlyUsesFreshUniqueLldpAndDeduplicatesReverseLinks(){
  var graph=new ObservedTopology(beans.getBeanProvider(DatabaseClient.class),json,new MockEnvironment());Instant now=Instant.now();
  String a="[{\"source\":\"LLDP\",\"transport\":\"SNMP\",\"sourceRef\":\"1.0.8802.1.1.2.1.4.1.1.7.0.5.1\",\"interfaceName\":\"TenGigabitEthernet 1/5/1\",\"portId\":\"Te0/3/0\",\"address\":\"192.0.2.3\",\"ttlSeconds\":120}]";
  String b="[{\"source\":\"LLDP\",\"interfaceName\":\"Te0/3/0\",\"portId\":\"Te1/5/1\",\"name\":\"dell\",\"ttlSeconds\":120}]";
  var first=new ObservedTopology.Source("org","a","192.0.2.1",reading("dell",now,a));
  var second=new ObservedTopology.Source("org","b","192.0.2.3",reading("cisco",now,b));
  assertThat(graph.edges(List.of(first,second),now)).hasSize(1);
  assertThat(graph.edges(List.of(first,second),now.plusSeconds(181))).isEmpty();
  assertThat(graph.edges(List.of(first,new ObservedTopology.Source("other","b","192.0.2.3",second.reading())),now)).isEmpty();
  var arp=new ObservedTopology.Source("org","a","192.0.2.1",reading("dell",now,a.replace("LLDP","ARP")));
  assertThat(graph.edges(List.of(arp,new ObservedTopology.Source("org","b","192.0.2.3",reading("cisco",now,"[]"))),now)).isEmpty();
  var duplicate=new ObservedTopology.Source("org","c","192.0.2.3",reading("duplicate",now,"[]"));
  assertThat(graph.edges(List.of(first,duplicate,new ObservedTopology.Source("org","b","192.0.2.3",reading("cisco",now,"[]"))),now)).isEmpty();
 }
 private String hardwareCiscoNeighbors(){return """
  [{"source":"LLDP","transport":"SNMP","interfaceName":"Te0/1/0","portId":"Xs0/2","chassisId":"f0:74:8d:cc:5b:d1","chassisSubtype":"macAddress","name":"SHHS-HYKJDL-A804-2","address":"127.0.0.1"},
   {"source":"LLDP","transport":"SNMP","interfaceName":"Te0/3/0","portId":"TenGigabitEthernet 1/5/1","chassisId":"0c:29:ef:b3:5f:20","chassisSubtype":"macAddress","name":null,"address":null}]
  """;}
 @Test void hardwareChassisAndInterfaceMacDifferenceCannotInventADellLink(){
  // Saved Cisco LLDP rows and Dell SNMP ifPhysAddress were observed on 2026-09-09.
  // Dell's chassis ends in 20, while all available SNMP port MACs end in 22.
  var graph=new ObservedTopology(beans.getBeanProvider(DatabaseClient.class),json,new MockEnvironment());Instant now=Instant.now();
  var cisco=new ObservedTopology.Source("org","cisco","192.168.254.3",reading("cisco",now,hardwareCiscoNeighbors()));
  var base=reading("dell",now,"[]");
  var dell=new DeviceProtocol.Reading(now,base.identity(),"HEALTHY",Map.of(),List.of(),List.of(new DeviceProtocol.Port("fixture-port","TenGigabitEthernet 1/5/1","0c:29:ef:b3:5f:22","10000000000","UP","UP",null,null,null,64,"IF-MIB.ifPhysAddress")),List.of(),List.of(),Map.of("snmpEngineId","0000178b02000c29efb35f20"));
  assertThat(graph.edges(List.of(cisco,new ObservedTopology.Source("org","dell","192.168.254.1",dell)),now)).isEmpty();
  // A loopback asset must not turn the unrelated Ruijie advertisement into a physical edge.
  assertThat(graph.edges(List.of(cisco,new ObservedTopology.Source("org","dell","192.168.254.1",dell),new ObservedTopology.Source("org","local","127.0.0.1",reading("local",now,"[]"))),now)).isEmpty();
 }
 @Test void verifiedDellSnmpChassisResolvesExactlyOneLinkAndRejectsUnknownOrDuplicateIdentity(){
  var graph=new ObservedTopology(beans.getBeanProvider(DatabaseClient.class),json,new MockEnvironment());Instant now=Instant.now();
  var cisco=new ObservedTopology.Source("org","cisco","192.168.254.3",reading("cisco",now,hardwareCiscoNeighbors()),"snmp");
  var base=reading("dell",now,"[]");
  var ports=List.of(new DeviceProtocol.Port("fixture-port","TenGigabitEthernet 1/5/1","0c:29:ef:b3:5f:22","10000000000","UP","UP",null,null,null,64,"IF-MIB.ifPhysAddress"));
  java.util.function.Function<Map<String,String>,DeviceProtocol.Reading> sample=facts->new DeviceProtocol.Reading(now,base.identity(),"HEALTHY",Map.of(),List.of(),ports,List.of(),List.of(),facts);
  String oid="1.3.6.1.4.1.6027.3.26.1.3.4.1.16.1";
  var verified=sample.apply(Map.of("chassisMacAddress","0c:29:ef:b3:5f:20","chassisMacSource",oid));
  var dell=new ObservedTopology.Source("org","dell","192.168.254.1",verified,"snmp");
  var local=new ObservedTopology.Source("org","local","127.0.0.1",reading("local",now,"[]"));
  var edges=graph.edges(List.of(cisco,dell,local),now);
  assertThat(edges).hasSize(1);var edge=edges.getFirst().getValue();assertThat(edge.source()).isEqualTo("cisco");assertThat(edge.target()).isEqualTo("dell");assertThat(edge.sourceInterface()).isEqualTo("Te0/3/0");assertThat(edge.targetInterface()).isEqualTo("Te1/5/1");
  for(var facts:List.of(Map.of("chassisMacAddress","0c:29:ef:b3:5f:20"),Map.of("chassisMacAddress","0c:29:ef:b3:5f:20","chassisMacSource","inferred-engine-id"),Map.of("chassisMacAddress","unknown","chassisMacSource",oid),Map.of("chassisMacAddress","00:00:00:00:00:00","chassisMacSource",oid),Map.of("chassisMacAddress","ff:ff:ff:ff:ff:ff","chassisMacSource",oid)))
   assertThat(graph.edges(List.of(cisco,new ObservedTopology.Source("org","dell","192.168.254.1",sample.apply(facts),"snmp")),now)).isEmpty();
  assertThat(graph.edges(List.of(cisco,new ObservedTopology.Source("org","dell","192.168.254.1",verified,"ssh")),now)).isEmpty();
  assertThat(graph.edges(List.of(cisco,new ObservedTopology.Source("other","dell","192.168.254.1",verified,"snmp")),now)).isEmpty();
  assertThat(graph.edges(List.of(cisco,dell,new ObservedTopology.Source("org","duplicate","192.0.2.2",verified,"snmp")),now)).isEmpty();
 }
 @Test void advertisedReservedAddressesNeverMatchAssetsButIndependentChassisEvidenceStillWorks(){
  var graph=new ObservedTopology(beans.getBeanProvider(DatabaseClient.class),json,new MockEnvironment());Instant now=Instant.now();
  for(String address:List.of("127.0.0.1","::1","0.0.0.0","::","224.0.0.1","ff02::1","169.254.1.1","fe80::1","0.1.2.3","255.255.255.255")){
   var source=new ObservedTopology.Source("org","source","192.0.2.1",reading("source",now,json.writeValueAsString(List.of(Map.of("source","LLDP","interfaceName","Te0/1","portId","Te0/2","address",address)))));
   assertThat(graph.edges(List.of(source,new ObservedTopology.Source("org","target",address,reading("target",now,"[]"))),now)).as(address).isEmpty();
  }
  var cisco=new ObservedTopology.Source("org","cisco","192.168.254.3",reading("cisco",now,hardwareCiscoNeighbors()));
  var base=reading("ruijie",now,"[]");
  var ruijie=new DeviceProtocol.Reading(now,base.identity(),"HEALTHY",Map.of(),List.of(),List.of(new DeviceProtocol.Port("fixture-port","Xs0/2","f0:74:8d:cc:5b:d1","10000000000","UP","UP",null,null,null,64,"fixture")),List.of(),List.of(),Map.of());
  var edges=graph.edges(List.of(cisco,new ObservedTopology.Source("org","ruijie","192.0.2.2",ruijie)),now);
  assertThat(edges).hasSize(1);assertThat(edges.getFirst().getValue().sourceInterface()).isEqualTo("Te0/1/0");assertThat(edges.getFirst().getValue().targetInterface()).isEqualTo("Xs0/2");
 }
 @Test void configurationPreservesMissingAndFailureInsteadOfInventingSuccess(){
  var reader=mock(ConfigurationReader.class);var workbench=mock(WorkbenchService.class);var inventory=mock(ControlRepository.class);
  var service=new ConfigurationCapture(reader,workbench,beans.getBeanProvider(DatabaseClient.class),settings(),inventory,new MockEnvironment());
  when(inventory.device("org","missing")).thenReturn(Mono.empty());
  assertThatThrownBy(()->service.capture(user("org"),"missing").block()).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(HttpStatus.NOT_FOUND));
  verifyNoInteractions(reader);
  when(inventory.device("org","offline")).thenReturn(Mono.just(mock(Models.Device.class)));
  when(reader.read("org","offline")).thenReturn(Mono.error(new DeviceProtocol.Failure("SSH_CONNECT_FAILED","connection failed")));
  var result=service.capture(user("org"),"offline").block();
  assertThat(result.status()).isEqualTo("ERROR");assertThat(result.capturedAt()).isNull();assertThat(result.snapshot()).isNull();
  verifyNoInteractions(workbench);
 }
 @Test void identicalConfigurationDoesNotCreateDuplicateSnapshot(){
  var reader=mock(ConfigurationReader.class);var workbench=mock(WorkbenchService.class);var inventory=mock(ControlRepository.class);
  var service=new ConfigurationCapture(reader,workbench,beans.getBeanProvider(DatabaseClient.class),settings(),inventory,new MockEnvironment());
  when(inventory.device("org","device")).thenReturn(Mono.just(mock(Models.Device.class)));
  Instant now=Instant.now();String raw="hostname fixture\npassword never-stored";var hash=WorkbenchService.sha256(WorkbenchService.redact(raw).content());
  when(reader.read("org","device")).thenReturn(Mono.just(new ConfigurationReader.Capture(raw,"SSH_RUNNING_CONFIGURATION",now)));
  var previous=new WorkbenchModels.SnapshotMetadata("snap","device","Captured","SSH_RUNNING_CONFIGURATION",now,"DEVICE_READ_ONLY",hash,1,"admin",now);
  when(workbench.snapshots(any(),eq("device"),eq(""),eq(""),eq(1))).thenReturn(Mono.just(new Models.Page<>(List.of(previous),null,now,"CONTROL","CONNECTED")));
  assertThat(service.capture(user("org"),"device").block().status()).isEqualTo("UNCHANGED");
  verify(workbench,never()).createSnapshot(any(),any());
 }
 @Test void passwordRevocationInvalidatesOldRedisTokensButKeepsNewSessions(){
  var redis=mock(ReactiveStringRedisTemplate.class);@SuppressWarnings("unchecked") var values=(ReactiveValueOperations<String,String>)mock(ReactiveValueOperations.class);
  var data=new java.util.concurrent.ConcurrentHashMap<String,String>();beans.addBean("redis",redis);when(redis.opsForValue()).thenReturn(values);
  when(values.setIfAbsent(anyString(),anyString())).thenAnswer(i->Mono.just(data.putIfAbsent(i.getArgument(0),i.getArgument(1))==null));
  when(values.get(anyString())).thenAnswer(i->Mono.justOrEmpty(data.get(i.getArgument(0))));
  when(values.set(anyString(),anyString(),any(Duration.class))).thenAnswer(i->{data.put(i.getArgument(0),i.getArgument(1));return Mono.just(true);});
  when(values.set(anyString(),anyString())).thenAnswer(i->{data.put(i.getArgument(0),i.getArgument(1));return Mono.just(true);});
  var sessions=new SessionTokens(beans.getBeanProvider(ReactiveStringRedisTemplate.class));
  var old=sessions.issue("admin").block();assertThat(sessions.username(old).block()).isEqualTo("admin");
  sessions.revokeUser("admin").block();assertThat(sessions.username(old).block()).isNull();
  var fresh=sessions.issue("admin").block();assertThat(sessions.username(fresh).block()).isEqualTo("admin");
  data.keySet().removeIf(k->k.startsWith("noeriva:session-user-version:"));assertThat(sessions.username(fresh).block()).isNull();
 }
}
