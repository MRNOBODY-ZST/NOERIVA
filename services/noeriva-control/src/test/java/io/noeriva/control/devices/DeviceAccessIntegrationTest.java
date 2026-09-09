package io.noeriva.control.devices;

import io.noeriva.control.*;
import io.r2dbc.spi.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static io.noeriva.control.devices.DeviceAccessModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class DeviceAccessIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("device_test").withUsername("device_test").withPassword("isolated-device-test-only");
    static final Duration TIMEOUT=Duration.ofSeconds(30);
    static DatabaseClient db;static TransactionalOperator tx;static JsonMapper json;static MySqlRepository inventory;static StaticListableBeanFactory beans;
    DeviceAccessStore store;DeviceAccessService service;TargetPolicy policy;CredentialVault vault;String org,device;SecurityConfiguration.Operator admin,other,viewer;
    @BeforeAll static void database(){
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
        var factory=ConnectionFactories.get(ConnectionFactoryOptions.builder().option(DRIVER,"mysql").option(HOST,MYSQL.getHost()).option(PORT,MYSQL.getMappedPort(3306)).option(DATABASE,MYSQL.getDatabaseName()).option(USER,MYSQL.getUsername()).option(PASSWORD,MYSQL.getPassword()).option(Option.valueOf("sslMode"),"DISABLED").option(Option.valueOf("allowPublicKeyRetrieval"),true).build());
        db=DatabaseClient.create(factory);tx=TransactionalOperator.create(new R2dbcTransactionManager(factory));json=JsonMapper.builder().build();beans=new StaticListableBeanFactory();beans.addBean("db",db);beans.addBean("tx",tx);
        var history=new HistoryStore(WebClient.builder(),new MockEnvironment().withProperty("NOERIVA_CLICKHOUSE_URL","http://127.0.0.1:9").withProperty("NOERIVA_CLICKHOUSE_PASSWORD","unused"),json);inventory=new MySqlRepository(db,tx,json,history);
    }
    DeviceAccessStore store(){return new DeviceAccessStore(beans.getBeanProvider(DatabaseClient.class),beans.getBeanProvider(TransactionalOperator.class),json,inventory);}
    @BeforeEach void setup(){
        org="access-"+UUID.randomUUID();admin=new SecurityConfiguration.Operator("admin","unused",org,List.of("ADMIN"));other=new SecurityConfiguration.Operator("other","unused","other",List.of("ADMIN"));viewer=new SecurityConfiguration.Operator("viewer","unused",org,List.of("VIEWER"));
        db.sql("INSERT INTO organization(id,name) VALUES(:org,'Access')").bind("org",org).fetch().rowsUpdated().block(TIMEOUT);
        db.sql("INSERT INTO site(organization_id,id,name) VALUES(:org,'site','Access')").bind("org",org).fetch().rowsUpdated().block(TIMEOUT);
        device=inventory.create(org,"admin",new Models.CreateDevice("Fixture","SWITCH","site","","","10.0.0.7")).block(TIMEOUT).id();
        store=store();vault=new CredentialVault(Base64.getEncoder().encodeToString(new byte[32]));policy=new TargetPolicy("10.0.0.0/8");service=service(List.of());
    }
    DeviceAccessService service(List<DeviceProtocol> protocols){return new DeviceAccessService(store,vault,policy,protocols,null,new MockEnvironment(),null);}
    @AfterEach void close(){policy.close();}
    Save input(long rev,String host,String secret){return new Save(rev,host,161,false,60,1000,128,"","2c","","","","","SYSTEM","",new SecretInput(secret,null,null,null));}
    Stored saved(){service.save(admin,device,"snmp",input(0,"10.0.0.7","local-test-community")).block(TIMEOUT);return store.get(org,device,"snmp").block(TIMEOUT);}
    @Test void credentialsAreEncryptedInSqlNeverReturnedAndCannotCrossTenantOrProtocol(){
        var result=service.save(admin,device,"snmp",input(0,"10.0.0.7","local-test-community")).block(TIMEOUT);
        assertThat(result.hasCommunity()).isTrue();assertThat(json.writeValueAsString(result)).doesNotContain("local-test-community","ciphertext");
        var stored=store().get(org,device,"snmp").block(TIMEOUT);assertThat(stored.ciphertext()).doesNotContain("local-test-community");assertThat(vault.decrypt(stored.scope(),stored.ciphertext()).community()).isEqualTo("local-test-community");
        assertThatThrownBy(()->service.connections(viewer,device).block(TIMEOUT)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.connections(other,device).block(TIMEOUT)).isInstanceOf(ApiException.class);
        String safe=json.writeValueAsString(service.collection(viewer,device).block(TIMEOUT));assertThat(safe).doesNotContain("ciphertext","community","username","host");
    }
    @Test void preservingSecretsRequiresSameTargetAndOneConcurrentUpdateWins(){
        saved();var input=input(1,"10.0.0.7",null);
        var results=Flux.merge(service.save(admin,device,"snmp",input).materialize(),service.save(admin,device,"snmp",input).materialize()).collectList().block(TIMEOUT);
        assertThat(results.stream().filter(Signal::isOnNext).count()).isEqualTo(1);assertThat(results.stream().filter(Signal::isOnError).count()).isEqualTo(1);
        var latest=store.get(org,device,"snmp").block(TIMEOUT);assertThat(latest.revision()).isEqualTo(2);assertThat(vault.decrypt(latest.scope(),latest.ciphertext()).community()).isEqualTo("local-test-community");
        assertThatThrownBy(()->service.save(admin,device,"snmp",input(2,"10.0.0.8",null)).block(TIMEOUT)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.save(admin,device,"snmp",input(2,"10.0.0.7","********")).block(TIMEOUT)).isInstanceOf(ApiException.class);
    }
    @Test void inventoryRevisionIsIndependentFromObservationAndEditingDoesNotRetargetCredentials(){
        saved();var first=service.management(admin,device).block(TIMEOUT);
        inventory.project(org,new Models.Observation("telemetry",device,"network","DeviceSummaryObserved","epoch",500,Instant.now(),"HEALTHY",Map.of(),"test")).block(TIMEOUT);
        inventory.project(org,new Models.Observation("telemetry2",device,"network","DeviceSummaryObserved","epoch",501,Instant.now(),"HEALTHY",Map.of(),"test")).block(TIMEOUT);
        var observed=service.management(admin,device).block(TIMEOUT);assertThat(observed.inventoryRevision()).isEqualTo(first.inventoryRevision());assertThat(observed.device().revision()).isNotEqualTo(observed.inventoryRevision());
        var result=service.update(admin,device,new Update(first.inventoryRevision(),"Renamed","ROUTER","site","Dell","S4048","10.0.0.8")).block(TIMEOUT);
        assertThat(result.device().name()).isEqualTo("Renamed");assertThat(result.inventoryRevision()).isEqualTo(first.inventoryRevision()+1);
        assertThat(store.get(org,device,"snmp").block(TIMEOUT).settings().host()).isEqualTo("10.0.0.7");
        assertThatThrownBy(()->service.update(admin,device,new Update(first.inventoryRevision(),"Old","ROUTER","site","","","10.0.0.8")).block(TIMEOUT)).isInstanceOf(ApiException.class);
    }
    @Test void databaseLeasePreventsDuplicatePollAndOldCompletionAfterRecovery(){
        Stored configured=saved();var attempt=store.acquire(configured,false).block(TIMEOUT);
        assertThatThrownBy(()->store.acquire(configured,false).block(TIMEOUT)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.save(admin,device,"snmp",input(1,"10.0.0.7",null)).block(TIMEOUT)).isInstanceOf(ApiException.class);
        db.sql("UPDATE device_connection SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE organization_id=:org").bind("org",org).fetch().rowsUpdated().block(TIMEOUT);
        var replacement=store.acquire(configured,false).block(TIMEOUT);assertThat(replacement.lease()).isNotEqualTo(attempt.lease());
        assertThatThrownBy(()->store.finish(attempt,null,false,"TIMEOUT","expired").block(TIMEOUT)).isInstanceOf(ApiException.class);
        var reading=reading();store.finish(replacement,reading,false,"","").block(TIMEOUT);
        assertThat(store.get(org,device,"snmp").block(TIMEOUT).lastReading()).isEqualTo(reading);
    }
    @Test void failuresKeepLastSuccessfulEvidenceAndDisabledDevicesNeverEnterDueQueue(){
        var attempt=store.acquire(saved(),false).block(TIMEOUT);store.finish(attempt,reading(),false,"","").block(TIMEOUT);
        var next=store.acquire(store.get(org,device,"snmp").block(TIMEOUT),false).block(TIMEOUT);
        var failure=store.finish(next,null,false,"SNMP_TIMEOUT","No response").block(TIMEOUT);
        assertThat(failure.status()).isEqualTo("ERROR");assertThat(failure.lastReading()).isNotNull();assertThat(failure.lastSuccessAt()).isNotNull();assertThat(store.due(org,32).collectList().block(TIMEOUT)).isEmpty();
        service.state(admin,device,"snmp",new State(1,true)).block(TIMEOUT);assertThat(store.due(org,32).collectList().block(TIMEOUT)).hasSize(1);
    }
    @Test void failureDiagnosticsCreateSpecificAlertOnceAndRecoveryResolvesWithoutRefreshingDeviceEvidence(){
        var configured=saved();var attempt=store.acquire(configured,false).block(TIMEOUT);
        var failure=store.finish(attempt,null,false,"SNMP_TIMEOUT","No response").block(TIMEOUT);
        var diagnostics=new DeviceFailureEvents(beans.getBeanProvider(HistoryStore.class),beans.getBeanProvider(DatabaseClient.class));
        diagnostics.failed(configured,failure,"SNMP_TIMEOUT").block(TIMEOUT);
        var alert=inventory.alerts(org,device,"OPEN",10).single().block(TIMEOUT);
        assertThat(alert.title()).contains("SNMP_TIMEOUT").contains("设备未在采集时限内返回有效响应");
        inventory.acknowledge(org,"operator",alert.id(),alert.revision()).block(TIMEOUT);
        diagnostics.failed(failure,failure,"SNMP_TIMEOUT").block(TIMEOUT);
        assertThat(inventory.alerts(org,device,"ACKNOWLEDGED",10).single().block(TIMEOUT).revision()).isEqualTo(alert.revision()+1);
        assertThat(inventory.sources(org,device).collectList().block(TIMEOUT)).isEmpty();
        assertThat(inventory.device(org,device).block(TIMEOUT).lastSeen()).isNull();
        var recovering=store.acquire(failure,false).block(TIMEOUT);var recovered=store.finish(recovering,reading(),false,"","").block(TIMEOUT);
        diagnostics.recovered(failure,recovered).block(TIMEOUT);
        assertThat(inventory.alerts(org,device,"RESOLVED",10).collectList().block(TIMEOUT)).hasSize(1);
    }
    @Test void manualTestUsesValidatedAddressAndPersistsOnlySafeErrors(){
        saved();DeviceProtocol success=new DeviceProtocol(){public String protocol(){return "SNMP";}public Mono<Reading> read(Target target,Secrets secrets){assertThat(target.address()).isEqualTo("10.0.0.7");assertThat(secrets.community()).isEqualTo("local-test-community");return Mono.just(reading());}};
        var result=service(List.of(success)).read(admin,device,"snmp",new Revision(1),false).block(TIMEOUT);assertThat(result.status()).isEqualTo("SUCCESS");
        DeviceProtocol failure=new DeviceProtocol(){public String protocol(){return "SNMP";}public Mono<Reading> read(Target target,Secrets secrets){return Mono.error(new RuntimeException("sensitive upstream local-test-community"));}};
        var error=service(List.of(failure)).read(admin,device,"snmp",new Revision(1),false).block(TIMEOUT);assertThat(error.status()).isEqualTo("ERROR");assertThat(error.lastReading()).isNotNull();assertThat(json.writeValueAsString(error)).doesNotContain("local-test-community","sensitive upstream");
    }
    @Test void metricBindingsAvoidTwoProtocolSourcesOverwritingTheSameNamedSeries(){
        var snmp=saved();var redfish=new Save(0,"10.0.0.7",443,false,60,1000,128,"monitor","","","","","","SYSTEM","",new SecretInput(null,null,null,"local-rf-password"));service.save(admin,device,"redfish",redfish).block(TIMEOUT);
        var bmc=store.get(org,device,"redfish").block(TIMEOUT);
        assertThat(store.metricOwnership(snmp,Set.of("temperature_celsius")).block(TIMEOUT)).contains("temperature_celsius");assertThat(store.metricOwnership(bmc,Set.of("temperature_celsius","power_watts")).block(TIMEOUT)).containsExactly("power_watts");
        service.state(admin,device,"snmp",new State(1,false)).block(TIMEOUT);
        assertThat(store.metricOwnership(bmc,Set.of("temperature_celsius","power_watts")).block(TIMEOUT)).containsExactlyInAnyOrder("temperature_celsius","power_watts");
    }
    @Test void disabledConnectionMayBeTestedButCannotPublishOrReclaimMetrics(){
        saved();var calls=new java.util.concurrent.atomic.AtomicInteger();
        DeviceProtocol driver=new DeviceProtocol(){public String protocol(){return "SNMP";}public Mono<Reading> read(Target target,Secrets secrets){calls.incrementAndGet();return Mono.just(reading());}};
        var publisher=mock(DevicePublisher.class);
        when(publisher.publish(any(),any())).thenAnswer(i->Mono.just(i.getArgument(1,DeviceProtocol.Reading.class)));
        var guarded=new DeviceAccessService(store,vault,policy,List.of(driver),publisher,new MockEnvironment(),null);
        assertThatThrownBy(()->guarded.read(admin,device,"snmp",new Revision(1),true).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DEVICE_COLLECTION_DISABLED"));
        assertThat(calls).hasValue(0);assertThat(store.get(org,device,"snmp").block(TIMEOUT).lease()).isNullOrEmpty();verifyNoInteractions(publisher);
        assertThat(guarded.read(admin,device,"snmp",new Revision(1),false).block(TIMEOUT).status()).isEqualTo("SUCCESS");assertThat(calls).hasValue(1);
        guarded.state(admin,device,"snmp",new State(1,true)).block(TIMEOUT);
        assertThat(guarded.read(admin,device,"snmp",new Revision(2),true).block(TIMEOUT).status()).isEqualTo("SUCCESS");
        verify(publisher).publish(any(),any());assertThat(calls).hasValue(2);
        guarded.state(admin,device,"snmp",new State(2,false)).block(TIMEOUT);
        assertThatThrownBy(()->guarded.read(admin,device,"snmp",new Revision(3),true).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DEVICE_COLLECTION_DISABLED"));
        assertThat(calls).hasValue(2);
    }
    @Test void navigatingAwayDoesNotLeaveAStartedManualReadPermanentlyRunning() throws Exception {
        saved();var response=Sinks.<DeviceProtocol.Reading>one();var started=new java.util.concurrent.CountDownLatch(1);
        DeviceProtocol driver=new DeviceProtocol(){public String protocol(){return "SNMP";}public Mono<Reading> read(Target target,Secrets secrets){return response.asMono().doOnSubscribe(s->started.countDown());}};
        var request=service(List.of(driver)).read(admin,device,"snmp",new Revision(1),false).subscribe();assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();request.dispose();response.tryEmitValue(reading());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(store.get(org,device,"snmp").block(TIMEOUT).status()).isEqualTo("SUCCESS"));
    }
    @Test void completeInterfaceSnapshotMarksMissingPortsWhilePartialReadsPreserveLastKnownInventory(){
        var v=saved();var identity=reading().identity();var p1=new DeviceProtocol.Port("eth0","eth0",null,"1000000000","UP","UP","1","1","0",64,"if:1");var p2=new DeviceProtocol.Port("eth1","eth1",null,"1000000000","UP","UP","1","1","0",64,"if:2");
        var first=new DeviceProtocol.Reading(Instant.now(),identity,"UNKNOWN",Map.of(),List.of(),List.of(p1,p2),List.of("interface-counters"),List.of(),Map.of());store.ports(v,first).block(TIMEOUT);
        var partial=new DeviceProtocol.Reading(Instant.now(),identity,"UNKNOWN",Map.of(),List.of(),List.of(p1),List.of("interface-counters"),List.of("SNMP_INTERFACE_LIMIT"),Map.of());store.ports(v,partial).block(TIMEOUT);
        assertThat(inventory.interfaces(org,device).filter(p->p.name().equals("eth1")).blockFirst(TIMEOUT).operStatus()).isEqualTo("UP");
        var complete=new DeviceProtocol.Reading(Instant.now(),identity,"UNKNOWN",Map.of(),List.of(),List.of(p1),List.of("interface-counters"),List.of(),Map.of());store.ports(v,complete).block(TIMEOUT);
        assertThat(inventory.interfaces(org,device).filter(p->p.name().equals("eth1")).blockFirst(TIMEOUT).operStatus()).isEqualTo("NOT_PRESENT");
    }
    Save ssh(long revision,String pin,String password){return new Save(revision,"10.0.0.7",22,false,60,1000,128,"synthetic-user",null,null,null,null,null,null,null,new SecretInput(null,null,null,password),"HUAWEI_IMANA",pin);}
    @Test void sshCredentialsPersistInTheirOwnSlotAndChangedPinRequiresResupply(){
        String pin="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
        var first=service.save(admin,device,"ssh",ssh(0,pin,"synthetic-ssh-password")).block(TIMEOUT);
        assertThat(first.protocol()).isEqualTo("SSH");assertThat(first.sshProfile()).isEqualTo("HUAWEI_IMANA");assertThat(first.hasPassword()).isTrue();
        assertThat(json.writeValueAsString(first)).doesNotContain("synthetic-ssh-password","ciphertext");
        var stored=store().get(org,device,"ssh").block(TIMEOUT);assertThat(stored.settings().sshHostKeySha256()).isEqualTo(pin);assertThat(DeviceAccessStore.source(stored)).isEqualTo("ssh");
        assertThat(stored.ciphertext()).doesNotContain("synthetic-ssh-password");assertThat(vault.decrypt(stored.scope(),stored.ciphertext()).password()).isEqualTo("synthetic-ssh-password");
        service.save(admin,device,"ssh",ssh(1,pin,null)).block(TIMEOUT);
        byte[] bytes=new byte[32];bytes[0]=1;String newPin="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(bytes);
        assertThatThrownBy(()->service.save(admin,device,"ssh",ssh(2,newPin,null)).block(TIMEOUT)).isInstanceOf(ApiException.class);
        assertThat(store.get(org,device,"ssh").block(TIMEOUT).revision()).isEqualTo(2);
        assertThat(service.save(admin,device,"ssh",ssh(2,newPin,"synthetic-ssh-password")).block(TIMEOUT).revision()).isEqualTo(3);
    }
    @Test void threeSlotsReturnSafeCollectionAndSshTestPersistsNeighborEvidence(){
        saved();service.save(admin,device,"redfish",new Save(0,"10.0.0.7",443,false,60,1000,128,"monitor","","","","","","SYSTEM","",new SecretInput(null,null,null,"synthetic-redfish-password"))).block(TIMEOUT);
        String pin="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);service.save(admin,device,"ssh",ssh(0,pin,"synthetic-ssh-password")).block(TIMEOUT);
        String neighbors="[{\"address\":\"192.0.2.1\",\"mac\":\"00:00:5e:00:53:01\",\"source\":\"ARP\",\"interfaceName\":\"eth0\"}]";
        DeviceProtocol driver=new DeviceProtocol(){public String protocol(){return "SSH";}public Mono<Reading> read(Target target,Secrets secrets){
            assertThat(target.address()).isEqualTo("10.0.0.7");assertThat(target.sshProfile()).isEqualTo("HUAWEI_IMANA");assertThat(target.sshHostKeySha256()).isEqualTo(pin);assertThat(secrets.password()).isEqualTo("synthetic-ssh-password");
            return Mono.just(new Reading(Instant.now(),reading().identity(),"UNKNOWN",Map.of(),List.of(),List.of(),List.of("identity","arp-neighbors"),List.of(),Map.of("neighborObservations",neighbors)));}};
        var result=service(List.of(driver)).read(admin,device,"ssh",new Revision(1),false).block(TIMEOUT);assertThat(result.status()).isEqualTo("SUCCESS");assertThat(result.lastReading().facts()).containsEntry("neighborObservations",neighbors);
        var safe=service.collection(viewer,device).block(TIMEOUT);assertThat(safe.items()).hasSize(3);assertThat(json.writeValueAsString(safe)).doesNotContain("ciphertext","synthetic-ssh-password","synthetic-redfish-password","sshHostKeySha256","username");
        assertThat(store.list(org,device).map(DeviceAccessStore::source).collectList().block(TIMEOUT)).containsExactlyInAnyOrder("network","bmc","ssh");
    }
    static DeviceProtocol.Reading reading(){return new DeviceProtocol.Reading(Instant.parse("2026-09-06T10:00:00Z"),new DeviceProtocol.Identity("Dell","OS9","dell-os9","S4048","serial","9.14","oid","switch","fixture"),"UNKNOWN",Map.of(),List.of(),List.of(),List.of("identity"),List.of(),Map.of());}
}
