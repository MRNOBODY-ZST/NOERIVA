package io.noeriva.control.discovery;

import io.noeriva.control.*;
import io.noeriva.control.SecurityConfiguration.Operator;
import io.r2dbc.spi.*;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class DiscoveryIntegrationTest {
 @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("discovery_test").withUsername("test").withPassword("synthetic-discovery-only").withCommand("--log-bin-trust-function-creators=1");
 static DatabaseClient db;static TransactionalOperator tx;static ConnectionPool pool;static final JsonMapper JSON=new JsonMapper();
 final Instant now=Instant.parse("2026-09-07T00:00:00Z");final Duration wait=Duration.ofSeconds(20);
 String org,other;Operator admin,viewer;Object service;
 @BeforeAll static void database(){
  Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
  var cf=ConnectionFactories.get(ConnectionFactoryOptions.builder().option(DRIVER,"mysql").option(HOST,MYSQL.getHost()).option(PORT,MYSQL.getMappedPort(3306)).option(DATABASE,MYSQL.getDatabaseName()).option(USER,MYSQL.getUsername()).option(PASSWORD,MYSQL.getPassword()).option(Option.valueOf("sslMode"),"DISABLED").option(Option.valueOf("allowPublicKeyRetrieval"),true).build());
  pool=new ConnectionPool(ConnectionPoolConfiguration.builder(cf).initialSize(8).maxSize(8).build());pool.warmup().block(Duration.ofSeconds(20));
  db=DatabaseClient.create(pool);tx=TransactionalOperator.create(new R2dbcTransactionManager(pool));
 }
 @AfterAll static void closePool(){if(pool!=null)pool.disposeLater().block(Duration.ofSeconds(20));}
 @BeforeEach void fixture(){
  org="disc-"+UUID.randomUUID();other="disc-"+UUID.randomUUID();admin=new Operator("admin","unused",org,List.of("ADMIN"));viewer=new Operator("viewer","unused",org,List.of("VIEWER"));
  db.sql("INSERT INTO organization(id,name) VALUES(:org,'synthetic discovery'),(:other,'other discovery')").bind("org",org).bind("other",other).fetch().rowsUpdated().block(wait);
  for(String o:List.of(org,other))db.sql("INSERT INTO site(organization_id,id,name) VALUES(:org,'a','site a'),(:org,'b','site b')").bind("org",o).fetch().rowsUpdated().block(wait);
  device(org,"gateway","a","192.0.2.1");device(org,"other-site","b","192.0.2.2");device(other,"foreign","a","192.0.2.3");
 }
 Object service(){if(service!=null)return service;try{var c=Class.forName("io.noeriva.control.discovery.DiscoveryService").getDeclaredConstructor(DatabaseClient.class,TransactionalOperator.class,JsonMapper.class,Clock.class);c.setAccessible(true);return service=c.newInstance(db,tx,JSON,Clock.fixed(now,ZoneOffset.UTC));}catch(Exception e){throw new AssertionError("DiscoveryService must implement the stored-evidence workflow",e);}}
 @SuppressWarnings("unchecked") Mono<Object> invoke(String method,Operator actor,String id,Map<String,Object> body){
  try{Method m=Arrays.stream(service().getClass().getMethods()).filter(x->x.getName().equals(method)).findFirst().orElseThrow();Object input=JSON.convertValue(body,m.getParameterTypes()[m.getParameterCount()-1]);return (Mono<Object>)m.invoke(service(),id==null?new Object[]{actor,input}:new Object[]{actor,id,input});}
  catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(ReflectiveOperationException e){throw new AssertionError(e);}
 }
 JsonNode call(String method,Operator actor,String id,Map<String,Object> body){return JSON.valueToTree(invoke(method,actor,id,body).block(wait));}
 JsonNode run(){return call("run",admin,null,Map.of("sourceDeviceIds",List.of("gateway"),"cidr","192.168.4.0/24","siteId","a"));}
 JsonNode page(Operator actor,String site){try{return JSON.valueToTree(((Mono<?>)service().getClass().getMethod("candidates",Operator.class,String.class,String.class,String.class,int.class).invoke(service(),actor,site,"","",100)).block(wait));}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
 JsonNode only(){var items=page(viewer,"a").path("items");assertThat(items.size()).isEqualTo(1);return items.get(0);}
 void device(String o,String id,String site,String address){db.sql("INSERT INTO device(organization_id,id,name,type,site_id,management_address,capabilities) VALUES(:org,:id,:id,'SWITCH',:site,:address,JSON_ARRAY('summary'))").bind("org",o).bind("id",id).bind("site",site).bind("address",address).fetch().rowsUpdated().block(wait);}
 void source(String id,Instant observed,List<Map<String,Object>> observations){
  source(id,"ssh",true,observed,observations,List.of());
 }
 void source(String id,String slot,boolean enabled,Instant observed,List<Map<String,Object>> observations,List<String> flags){
  var reading=Map.of("observedAt",observed,"qualityFlags",flags,"facts",observations==null?Map.of():Map.of("neighborObservations",JSON.writeValueAsString(observations),"rawCli","must-never-persist-raw"));
  db.sql("INSERT INTO device_connection(organization_id,device_id,slot,revision,enabled,settings,ciphertext,status,next_poll_at,source_epoch,last_reading) VALUES(:org,:id,:slot,1,:enabled,JSON_OBJECT(),'unread-synthetic-ciphertext','SUCCESS',UTC_TIMESTAMP(6),'synthetic',:reading) ON DUPLICATE KEY UPDATE last_reading=VALUES(last_reading),enabled=VALUES(enabled)").bind("org",org).bind("id",id).bind("slot",slot).bind("enabled",enabled).bind("reading",JSON.writeValueAsString(reading)).fetch().rowsUpdated().block(wait);
 }
 Map<String,Object> arp(String address,String mac){return Map.of("address",address,"mac",mac,"source","ARP","interfaceName","Vlan4","ageMinutes",2,"password","must-never-persist-password");}
 void seed(Map<String,Object>... rows){source("gateway",now.minusSeconds(30),Arrays.asList(rows));}
 @Test void normalizesAndFiltersOnlyFreshStoredEvidenceAndIsIdempotent(){
  seed(arp("192.168.4.10","0011.2233.4455"),arp("192.168.5.10","00:11:22:33:44:66"));
  var result=run();assertThat(result.path("sourcesUsed").asInt()).isEqualTo(1);assertThat(result.path("candidatesUpdated").asInt()).isEqualTo(1);
  var first=only();assertThat(first.path("address").asString()).isEqualTo("192.168.4.10");assertThat(first.path("mac").asString()).isEqualTo("00:11:22:33:44:55");assertThat(first.path("status").asString()).isEqualTo("NEW");
  assertThat(first.toString()).doesNotContain("password","must-never-persist","ciphertext","rawCli");run();assertThat(only().path("id").asString()).isEqualTo(first.path("id").asString());
 }
 @Test void enabledSnmpIsTheSingleSourceAndSnmpQualityFlagsRemainVisible(){
  source("gateway","ssh",true,now.minusSeconds(5),List.of(arp("192.168.4.11","001122334466")),List.of());
  source("gateway","snmp",true,now.minusSeconds(30),List.of(Map.of("address","192.168.4.10","source","LLDP","transport","SNMP","sourceRef","1.0.1","interfaceName","Te1/1","portId","Te0/1")),List.of("SNMP_LLDP_INCOMPLETE","not-allowlisted"));
  var result=run();assertThat(result.path("sources").size()).isEqualTo(1);assertThat(result.path("sourcesUsed").asInt()).isEqualTo(1);assertThat(result.path("qualityFlags").toString()).contains("SNMP_LLDP_INCOMPLETE").doesNotContain("not-allowlisted");
  assertThat(only().path("address").asString()).isEqualTo("192.168.4.10");assertThat(only().path("evidence").get(0).path("source").asString()).isEqualTo("LLDP");
 }
 @Test void selectedSnmpMissingOrStaleEvidenceNeverRevivesSavedSsh(){
  seed(arp("192.168.4.11","001122334466"));
  source("gateway","snmp",true,now.minusSeconds(30),null,List.of());
  var missing=run();assertThat(missing.path("sourcesUsed").asInt()).isZero();assertThat(missing.path("sources").get(0).path("reason").asString()).isEqualTo("NO_SAVED_NEIGHBORS");
  source("gateway","snmp",true,now.minusSeconds(901),List.of(arp("192.168.4.10","001122334455")),List.of());
  var stale=run();assertThat(stale.path("sourcesUsed").asInt()).isZero();assertThat(stale.path("sources").get(0).path("status").asString()).isEqualTo("STALE");assertThat(page(viewer,"a").path("items").size()).isZero();
 }
 @Test void disabledEvidenceIsExcludedAndOnlyAnEnabledAlternativeIsEligible(){
  source("gateway","snmp",false,now.minusSeconds(10),List.of(arp("192.168.4.10","001122334455")),List.of());
  source("gateway","ssh",false,now.minusSeconds(10),List.of(arp("192.168.4.11","001122334466")),List.of());
  assertThat(run().path("sourcesUsed").asInt()).isZero();assertThat(page(viewer,"a").path("items").size()).isZero();
  source("gateway","ssh",true,now.minusSeconds(10),List.of(arp("192.168.4.11","001122334466")),List.of());
  assertThat(run().path("sourcesUsed").asInt()).isEqualTo(1);assertThat(only().path("address").asString()).isEqualTo("192.168.4.11");
 }
 @Test void exactFreshnessBoundaryAndFutureReadingsAreNotCurrent(){
  for(Instant at:List.of(now.minusSeconds(900),now.plusSeconds(1))){source("gateway",at,List.of(arp("192.168.4.10","001122334455")));assertThat(run().path("sourcesUsed").asInt()).isZero();assertThat(page(viewer,"a").path("items").size()).isZero();}
 }
 @Test void expiredLldpIsSkippedWhileArpAgeAndUnknownTtlRemainExplicit(){
  source("gateway",now.minusSeconds(60),List.of(Map.of("address","192.168.4.11","source","LLDP","ttlSeconds",30),Map.of("address","192.168.4.12","source","LLDP","ttlSeconds",120),Map.of("address","192.168.4.13","source","ARP","ageMinutes",99)));
  run();var items=page(viewer,"a").path("items");assertThat(items.size()).isEqualTo(2);assertThat(items.toString()).contains("192.168.4.12","192.168.4.13","99.0").doesNotContain("192.168.4.11");
 }
 @Test void sameMacDifferentAddressesAreWeakDuplicatesAndConflictingMacsBlockRegister(){
  seed(arp("192.168.4.10","001122334455"),arp("192.168.4.11","001122334455"));run();for(var c:page(viewer,"a").path("items"))assertThat(c.path("status").asString()).isEqualTo("POSSIBLE_DUPLICATE");
  seed(arp("192.168.4.10","001122334455"),arp("192.168.4.10","aabbccddeeff"));run();
  var c=java.util.stream.StreamSupport.stream(page(viewer,"a").path("items").spliterator(),false).filter(x->x.path("address").asString().endsWith(".10")).findFirst().orElseThrow();
  assertThat(c.path("status").asString()).isEqualTo("CONFLICT");assertThatThrownBy(()->call("register",admin,c.path("id").asString(),Map.of("revision",c.path("revision").asLong(),"name","new","type","HOST"))).isInstanceOf(ApiException.class).extracting(e->((ApiException)e).code).isEqualTo("CANDIDATE_CONFLICT");
 }
 @Test void existingInventoryIsLinkedWithoutDuplicateRegistration(){
  device(org,"existing","a","192.168.4.10");seed(arp("192.168.4.10","001122334455"));run();var c=only();assertThat(c.path("status").asString()).isEqualTo("EXISTING");assertThat(c.path("associatedDeviceId").asString()).isEqualTo("existing");
  var r=call("register",admin,c.path("id").asString(),Map.of("revision",c.path("revision").asLong(),"name","not-created","type","HOST"));assertThat(r.path("associatedDeviceId").asString()).isEqualTo("existing");assertThat(addressCount()).isEqualTo(1);
 }
 @Test void concurrentRegisterCreatesExactlyOneAssetAndAssociation(){
  seed(arp("192.168.4.10","001122334455"));run();var c=only();var body=Map.<String,Object>of("revision",c.path("revision").asLong(),"name","single-new-device","type","HOST");
  var results=Flux.range(0,4).flatMap(i->invoke("register",admin,c.path("id").asString(),body),4).collectList().block(wait);
  assertThat(results).hasSize(4);assertThat(addressCount()).isEqualTo(1);assertThat(only().path("status").asString()).isEqualTo("REGISTERED");
 }
 @Test void explicitLinkDoesNotRewriteTargetAndCrossSiteTargetIsRejected(){
  seed(arp("192.168.4.10","001122334455"));run();var c=only();var id=c.path("id").asString();long revision=c.path("revision").asLong();
  assertThatThrownBy(()->call("link",admin,id,Map.of("revision",revision,"deviceId","other-site"))).isInstanceOf(ApiException.class);
  var linked=call("link",admin,id,Map.of("revision",revision,"deviceId","gateway"));assertThat(linked.path("status").asString()).isEqualTo("LINKED");
  assertThat(db.sql("SELECT management_address FROM device WHERE organization_id=:org AND id='gateway'").bind("org",org).map((r,m)->r.get("management_address",String.class)).one().block(wait)).isEqualTo("192.0.2.1");
 }
 @Test void rolesOrganizationAndSiteAreCheckedInsideService(){
  assertThatThrownBy(()->call("run",viewer,null,Map.of("sourceDeviceIds",List.of("gateway"),"cidr","192.168.4.0/24","siteId","a"))).isInstanceOf(ApiException.class).extracting(e->((ApiException)e).code).isEqualTo("DISCOVERY_WRITE_FORBIDDEN");
  for(String id:List.of("foreign","other-site"))assertThatThrownBy(()->call("run",admin,null,Map.of("sourceDeviceIds",List.of(id),"cidr","192.168.4.0/24","siteId","a"))).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->page(new Operator("collector","unused",org,List.of("COLLECTOR")),"a")).isInstanceOf(ApiException.class);
 }
 @Test void literalCidrRejectsWildcardDnsHostBitsAndBroadNetworks(){
  for(String cidr:List.of("168.4.*","example.com/24","192.168.4.1/24","192.168.0.0/16","127.0.0.0/24","192.168.004.0/24"))assertThatThrownBy(()->call("run",admin,null,Map.of("sourceDeviceIds",List.of("gateway"),"cidr",cidr,"siteId","a"))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void observationsAreCappedAndEvidenceIsAllowlisted(){
  var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<300;i++)rows.add(arp("192.168.4.10","001122334455"));source("gateway",now.minusSeconds(10),rows);
  var r=run();assertThat(r.path("observationsRead").asInt()).isEqualTo(256);assertThat(r.path("qualityFlags").toString()).contains("OBSERVATION_LIMIT");assertThat(only().path("evidence").size()).isLessThanOrEqualTo(16);
 }
 @Test void linkedAssetIsNeverRedirectedWhenNewConflictingEvidenceArrives(){
  seed(arp("192.168.4.10","001122334455"));run();var c=only();
  call("link",admin,c.path("id").asString(),Map.of("revision",c.path("revision").asLong(),"deviceId","gateway"));
  device(org,"later-manual","a","192.168.4.10");seed(arp("192.168.4.10","aabbccddeeff"));run();
  assertThat(only().path("associatedDeviceId").asString()).isEqualTo("gateway");assertThat(only().path("status").asString()).isEqualTo("CONFLICT");assertThat(only().path("reasons").toString()).contains("ADDRESS_HAS_MULTIPLE_MACS");
 }
 @Test void evidenceLimitRetainsContradictionAcrossRuns(){
  var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<20;i++)rows.add(Map.of("address","192.168.4.10","mac","001122334455","source","ARP","interfaceName","port"+i));rows.add(arp("192.168.4.10","aabbccddeeff"));
  source("gateway",now.minusSeconds(30),rows);run();assertThat(only().path("evidence").size()).isEqualTo(16);assertThat(only().path("status").asString()).isEqualTo("CONFLICT");
  seed(arp("192.168.4.10","001122334455"));run();assertThat(only().path("status").asString()).isEqualTo("CONFLICT");
 }
 @Test void duplicateLookupIncludesMacsOfConflictingCandidates(){
  seed(arp("192.168.4.10","001122334455"),arp("192.168.4.10","aabbccddeeff"));run();assertThat(only().path("mac").isNull()).isTrue();
  seed(arp("192.168.4.11","001122334455"));run();
  var c=java.util.stream.StreamSupport.stream(page(viewer,"a").path("items").spliterator(),false).filter(x->x.path("address").asString().endsWith(".11")).findFirst().orElseThrow();
  assertThat(c.path("status").asString()).isEqualTo("POSSIBLE_DUPLICATE");
 }
 @Test void missingSourceAndManualInventoryConflictsAreExplicit(){
  var missing=run();assertThat(missing.path("sources").get(0).path("status").asString()).isEqualTo("MISSING");assertThat(missing.path("sourcesUsed").asInt()).isZero();
  device(org,"manual-one","a","192.168.4.10");device(org,"manual-two","a","192.168.4.10");seed(arp("192.168.4.10","001122334455"));run();
  assertThat(only().path("status").asString()).isEqualTo("CONFLICT");assertThat(only().path("reasons").toString()).contains("MULTIPLE_EXISTING_ASSETS");assertThat(only().path("associatedDeviceId").isNull()).isTrue();
 }
 @Test void sameAddressInDifferentSitesStaysSeparateAndCandidateCannotCrossOrganizations(){
  seed(arp("192.168.4.10","001122334455"));run();source("other-site",now.minusSeconds(30),List.of(arp("192.168.4.10","001122334455")));
  call("run",admin,null,Map.of("sourceDeviceIds",List.of("other-site"),"cidr","192.168.4.0/24","siteId","b"));
  assertThat(page(viewer,"b").path("items").size()).isEqualTo(1);var c=only();assertThat(c.path("status").asString()).isEqualTo("NEW");
  assertThatThrownBy(()->call("register",new Operator("other-admin","unused",other,List.of("ADMIN")),c.path("id").asString(),Map.of("revision",1,"name","forbidden","type","HOST"))).isInstanceOf(ApiException.class);
 }
 @Test void directedBroadcastAndNetworkAddressAreNotHostCandidates(){
  seed(arp("192.168.4.0","001122334455"),arp("192.168.4.255","001122334466"),arp("192.168.4.10","001122334477"));run();assertThat(only().path("address").asString()).isEqualTo("192.168.4.10");
 }
 @Test void pagesAreStableFilteredAndReadOnly(){
  seed(arp("192.168.4.10","001122334455"),arp("192.168.4.11","aabbccddeeff"));run();var s=(DiscoveryService)service();
  var first=s.candidates(viewer,"a","NEW","",1).block(wait);assertThat(first.items()).hasSize(1);assertThat(first.nextCursor()).isNotNull();
  var second=s.candidates(viewer,"a","NEW",first.nextCursor(),1).block(wait);assertThat(second.items()).hasSize(1);assertThat(second.nextCursor()).isNull();assertThat(second.items().getFirst().id()).isNotEqualTo(first.items().getFirst().id());
  assertThat(s.candidates(viewer,"b","","",100).block(wait).items()).isEmpty();assertThat(s.candidates(viewer,"a","CONFLICT","",100).block(wait).items()).isEmpty();
  assertThatThrownBy(()->s.candidates(viewer,"a","invalid","",1)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void failedRunRollsBackCandidateWritesAndAudit(){
  seed(arp("192.168.4.10","001122334455"));
  db.sql("CREATE TRIGGER discovery_fail BEFORE INSERT ON discovery_run FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic transaction failure'").fetch().rowsUpdated().block(wait);
  try{assertThatThrownBy(this::run).isInstanceOf(RuntimeException.class);assertThat(page(viewer,"a").path("items").size()).isZero();assertThat(count("discovery_run")).isZero();assertThat(count("control_audit")).isZero();}
  finally{db.sql("DROP TRIGGER discovery_fail").fetch().rowsUpdated().block(wait);}
 }
 @Test void cancellationAfterCandidateWriteRollsBackBeforeReleasingSite(){
  seed(arp("192.168.4.10","001122334455"));String lock="discovery-cancel-"+UUID.randomUUID();
  db.sql("CREATE TRIGGER discovery_slow BEFORE INSERT ON discovery_run FOR EACH ROW BEGIN DO GET_LOCK('"+lock+"',0); DO SLEEP(2); DO RELEASE_LOCK('"+lock+"'); END").fetch().rowsUpdated().block(wait);
  reactor.core.Disposable subscription=null;
  try{
   subscription=invoke("run",admin,null,Map.of("sourceDeviceIds",List.of("gateway"),"cidr","192.168.4.0/24","siteId","a")).subscribe(v->{},e->{});
   await().atMost(Duration.ofSeconds(5)).until(()->db.sql("SELECT IF(IS_USED_LOCK(:key) IS NULL,0,1) n").bind("key",lock).map((r,m)->r.get("n",Integer.class)).one().block(wait)==1);
   subscription.dispose();
   // This lock is granted only after cancellation has completed the transaction rollback.
   db.sql("SELECT id FROM site WHERE organization_id=:org AND id='a' FOR UPDATE").bind("org",org).map((r,m)->r.get("id",String.class)).one().as(tx::transactional).block(wait);
   assertThat(page(viewer,"a").path("items").size()).isZero();assertThat(count("discovery_run")).isZero();assertThat(count("control_audit")).isZero();
  }finally{if(subscription!=null)subscription.dispose();db.sql("DROP TRIGGER discovery_slow").fetch().rowsUpdated().block(wait);}
 }
 @Test void fourPendingRequestsBoundAdmissionAndReleaseOnCancel()throws Exception{
  var locked=new java.util.concurrent.CountDownLatch(1);var held=db.sql("SELECT id FROM site WHERE organization_id=:org AND id='a' FOR UPDATE").bind("org",org).map((r,m)->r.get("id",String.class)).one().doOnNext(v->locked.countDown()).then(Mono.never()).as(tx::transactional).subscribe();
  var requests=new ArrayList<reactor.core.Disposable>();
  try{
   assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();for(int i=0;i<4;i++)requests.add(invoke("run",admin,null,Map.of("sourceDeviceIds",List.of("gateway"),"cidr","192.168.4.0/24","siteId","a")).subscribe(v->{},e->{}));
   assertThatThrownBy(this::run).isInstanceOf(ApiException.class).extracting(e->((ApiException)e).code).isEqualTo("DISCOVERY_CAPACITY");
  }finally{requests.forEach(reactor.core.Disposable::dispose);held.dispose();}
  await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(run().path("sourcesRequested").asInt()).isEqualTo(1));
 }
 long count(String table){return db.sql("SELECT COUNT(*) n FROM "+table+" WHERE organization_id=:org").bind("org",org).map((r,m)->r.get("n",Long.class)).one().block(wait);}
 long addressCount(){return db.sql("SELECT COUNT(*) n FROM device WHERE organization_id=:org AND site_id='a' AND management_address='192.168.4.10'").bind("org",org).map((r,m)->r.get("n",Long.class)).one().block(wait);}
 @Test void snmpAddressAndDhcpTablesPopulateOneCandidateAndRejectOwnOrExpiredRows(){
  source("gateway","snmp",true,now.minusSeconds(30),null,List.of());
  var arp=Map.of("source","IP-MIB/ipNetToPhysical","mac","02:11:22:33:44:55","address","192.168.4.10","interfaceName","Vlan4","entryType","DYNAMIC","neighborState","REACHABLE","observedAt",now.minusSeconds(30));
  var local=new HashMap<String,Object>(arp);local.put("address","192.168.4.1");local.put("entryType","LOCAL");
  var dhcp=Map.of("source","CISCO-DHCP-SNOOPING-MIB","mac","02:11:22:33:44:55","address","192.168.4.10","interfaceName","Gi1/8","vlanId",4,"leaseSeconds",300,"rowStatus","ACTIVE","observedAt",now.minusSeconds(30));
  var expired=new HashMap<String,Object>(dhcp);expired.put("address","192.168.4.11");expired.put("leaseSeconds",1);
  var reading=Map.of("observedAt",now.minusSeconds(30),"qualityFlags",List.of(),"facts",Map.of("addressObservations",JSON.writeValueAsString(List.of(arp,local)),"dhcpObservations",JSON.writeValueAsString(List.of(dhcp,expired))));
  db.sql("UPDATE device_connection SET last_reading=:reading WHERE organization_id=:org AND device_id='gateway' AND slot='snmp'").bind("org",org).bind("reading",JSON.writeValueAsString(reading)).fetch().rowsUpdated().block(wait);
  assertThat(run().path("candidatesUpdated").asInt()).isEqualTo(1);var candidate=only();assertThat(candidate.path("evidence").size()).isEqualTo(2);assertThat(candidate.path("evidence").toString()).contains("DHCP","ARP","Gi1/8");
  assertThat(candidate.path("address").asString()).isEqualTo("192.168.4.10");
 }

}
