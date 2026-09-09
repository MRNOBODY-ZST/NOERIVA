package io.noeriva.control;

import java.time.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import io.noeriva.control.devices.TargetPolicy;
import java.util.*;
import io.r2dbc.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
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
import static io.noeriva.control.WorkbenchModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class WorkbenchIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("workbench_test").withUsername("workbench_test").withPassword("isolated-workbench-test-only");
    static final Duration TIMEOUT=Duration.ofSeconds(30);
    static DatabaseClient db;static TransactionalOperator tx;static JsonMapper json;static MySqlRepository inventory;
    WorkbenchRepository store;WorkbenchService service;SecurityConfiguration.Operator admin,other;String org,otherOrg,device;
    @BeforeAll static void database(){
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
        var factory=ConnectionFactories.get(ConnectionFactoryOptions.builder().option(DRIVER,"mysql").option(HOST,MYSQL.getHost()).option(PORT,MYSQL.getMappedPort(3306)).option(DATABASE,MYSQL.getDatabaseName())
            .option(USER,MYSQL.getUsername()).option(PASSWORD,MYSQL.getPassword()).option(Option.valueOf("sslMode"),"DISABLED").option(Option.valueOf("allowPublicKeyRetrieval"),true).build());
        db=DatabaseClient.create(factory);tx=TransactionalOperator.create(new R2dbcTransactionManager(factory));json=JsonMapper.builder().build();
        var history=new HistoryStore(WebClient.builder(),new MockEnvironment().withProperty("NOERIVA_CLICKHOUSE_URL","http://127.0.0.1:9").withProperty("NOERIVA_CLICKHOUSE_PASSWORD","unused"),json);
        inventory=new MySqlRepository(db,tx,json,history);
    }
    WorkbenchRepository repository(){var beans=new StaticListableBeanFactory();beans.addBean("db",db);beans.addBean("tx",tx);var env=new MockEnvironment();env.setActiveProfiles("production");return new WorkbenchRepository(beans.getBeanProvider(DatabaseClient.class),beans.getBeanProvider(TransactionalOperator.class),json,env);}
    @BeforeEach void setup(){
        org="wb-"+UUID.randomUUID();otherOrg="wb-"+UUID.randomUUID();admin=new SecurityConfiguration.Operator("admin","unused",org,List.of("ADMIN"));other=new SecurityConfiguration.Operator("other","unused",otherOrg,List.of("ADMIN"));
        db.sql("INSERT INTO organization(id,name) VALUES(:org,'Workbench'),(:other,'Other')").bind("org",org).bind("other",otherOrg).fetch().rowsUpdated().block(TIMEOUT);
        db.sql("INSERT INTO site(organization_id,id,name) VALUES(:org,'site','Workbench')").bind("org",org).fetch().rowsUpdated().block(TIMEOUT);
        device=inventory.create(org,"admin",new Models.CreateDevice("Fixture","HOST","site",null,null,"192.0.2.7")).block(TIMEOUT).id();
        store=repository();service=new WorkbenchService(store,inventory);
    }
    @AfterEach void close(){service.close();}
    IncidentInput incident(String title){return new IncidentInput(title,"WARNING",device,null,null,"initial note");}
    Check check(){return service.createCheck(admin,new CheckInput(device,"TCP fixture","TCP","192.0.2.7:22",60,true,"SYNTHETIC")).block(TIMEOUT);}
    long count(String table){return db.sql("SELECT COUNT(*) n FROM "+table+" WHERE organization_id=:org").bind("org",org).map((r,m)->r.get("n",Long.class)).one().block(TIMEOUT);}
    @Test void persistedRecordsSurviveRepositoryReconstructionAndMutationAuditFailureRollsBack(){
        var value=service.createIncident(admin,incident("Persisted")).block(TIMEOUT);
        assertThat(repository().get(org,"INCIDENT",value.id(),Incident.class).block(TIMEOUT)).isEqualTo(value);
        var bad=new SecurityConfiguration.Operator("x".repeat(121),"unused",org,List.of("ADMIN"));long before=count("workbench_record");
        assertThatThrownBy(()->service.createIncident(bad,incident("Rollback")).block(TIMEOUT)).isInstanceOf(RuntimeException.class);
        assertThat(count("workbench_record")).isEqualTo(before);
    }
    @Test void concurrentIncidentUpdatesHaveOneWinnerAndTenantReadIsForbidden(){
        var incident=service.createIncident(admin,incident("Concurrent")).block(TIMEOUT);
        var update=new IncidentUpdate(1,"Concurrent","INVESTIGATING",null,"checked");
        var results=Flux.merge(service.updateIncident(admin,incident.id(),update).materialize(),service.updateIncident(admin,incident.id(),update).materialize()).collectList().block(TIMEOUT);
        assertThat(results.stream().filter(Signal::isOnNext).count()).isEqualTo(1);assertThat(results.stream().filter(Signal::isOnError).count()).isEqualTo(1);
        assertThat(service.incident(admin,incident.id()).block(TIMEOUT).notes()).hasSize(2);
        assertThatThrownBy(()->service.incident(other,incident.id()).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.incidents(other,"","","","",50).block(TIMEOUT).items()).isEmpty();
    }
    @Test void keysetPaginationAndLiteralSearchDoNotSkipOrLeakRows(){
        for(int i=0;i<4;i++)service.createIncident(admin,incident(i<2?"literal% "+i:"other "+i)).block(TIMEOUT);
        var first=service.incidents(admin,"","","","",2).block(TIMEOUT);var second=service.incidents(admin,"","","",first.nextCursor(),2).block(TIMEOUT);
        assertThat(first.items()).hasSize(2);assertThat(second.items()).hasSize(2);assertThat(second.nextCursor()).isNull();
        var ids=new HashSet<String>();first.items().forEach(i->ids.add(i.id()));second.items().forEach(i->ids.add(i.id()));assertThat(ids).hasSize(4);
        assertThat(service.incidents(admin,"","","literal%","",50).block(TIMEOUT).items()).hasSize(2);
    }
    @Test void checkLatestResultIsOrderedByObservationAndArchivedChecksRetainHistory(){
        var check=check();Instant at=WorkbenchService.now().minusSeconds(20);
        var newer=new CheckResultInput("newer",check.id(),at,"PASS",12.0,"new","fixture","SYNTHETIC",null);
        service.reportResult(admin,newer).block(TIMEOUT);service.reportResult(admin,new CheckResultInput("older",check.id(),at.minusSeconds(10),"FAIL",null,"old","fixture","SYNTHETIC",null)).block(TIMEOUT);
        assertThat(service.checks(admin,"","","",50).block(TIMEOUT).items().getFirst().lastResult().id()).isEqualTo("newer");
        service.reportResult(admin,newer).block(TIMEOUT);assertThat(count("workbench_check_result")).isEqualTo(2);
        assertThatThrownBy(()->service.reportResult(admin,new CheckResultInput("newer",check.id(),at,"FAIL",null,"changed","fixture","SYNTHETIC",null)).block(TIMEOUT)).isInstanceOf(ApiException.class);
        service.archiveCheck(admin,check.id(),new Revision(1)).block(TIMEOUT);
        assertThatThrownBy(()->service.reportResult(admin,newer).block(TIMEOUT)).isInstanceOf(ApiException.class);
        assertThat(service.results(admin,check.id(),"",30).block(TIMEOUT).items()).hasSize(2);
    }
    @Test void realSqlTemporalQueriesPreserveAmbiguityAndRejectCrossTenantEvidence(){
        Instant at=WorkbenchService.now().minusSeconds(30);
        var nat=new NetworkInput("nat",device,"NAT","192.0.2.7",51000,"198.51.100.26",54021,"TCP",at.minusSeconds(10),at.plusSeconds(10),"COMPLETE",0,"fixture","SYNTHETIC");
        var lease=new NetworkInput("lease",device,"ADDRESS_LEASE","192.0.2.7",null,null,null,null,at.minusSeconds(20),at.plusSeconds(20),"COMPLETE",0,"fixture","SYNTHETIC");
        service.reportNetwork(admin,nat).block(TIMEOUT);service.reportNetwork(admin,lease).block(TIMEOUT);
        var query=new InvestigationInput("198.51.100.26",54021,"TCP",at,"PUBLIC_TO_PRIVATE");
        assertThat(service.investigate(admin,query).block(TIMEOUT).status()).isEqualTo("CONFIRMED");
        assertThat(service.investigate(other,query).block(TIMEOUT).status()).isEqualTo("NO_MATCH");
        service.reportNetwork(admin,new NetworkInput("nat-uncertain",device,"NAT","192.0.2.7",51001,"198.51.100.26",54021,"TCP",at.minusSeconds(1),at.plusSeconds(1),"COMPLETE",2000,"fixture","SYNTHETIC")).block(TIMEOUT);
        assertThat(service.investigate(admin,query).block(TIMEOUT).status()).isEqualTo("AMBIGUOUS");
    }
    @Test void evidenceReadsMustDetectContentThatNoLongerMatchesItsChecksum(){
        var value=service.createEvidence(admin,new EvidenceInput(device,"Integrity","NOTE","fixture",WorkbenchService.now(),"original","SYNTHETIC")).block(TIMEOUT);
        db.sql("UPDATE workbench_record SET payload=JSON_SET(payload,'$.content','tampered') WHERE organization_id=:org AND category='EVIDENCE' AND id=:id")
            .bind("org",org).bind("id",value.id()).fetch().rowsUpdated().block(TIMEOUT);
        assertThatThrownBy(()->service.evidence(admin,value.id(),false).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("EVIDENCE_INTEGRITY_FAILURE"));
    }
    @Test void coarseSqlTimeCandidatesCannotBeFilteredBeforeEnforcingCompletenessBound(){
        Instant at=WorkbenchService.now().minusSeconds(30);
        Flux.range(0,101).concatMap(i->service.reportNetwork(admin,new NetworkInput(String.format("a-expired-%03d",i),device,"NAT","192.0.2.7",51000,"198.51.100.27",54022,"TCP",at.minusSeconds(10),at,"COMPLETE",0,"fixture","SYNTHETIC"))).then().block(TIMEOUT);
        service.reportNetwork(admin,new NetworkInput("z-valid",device,"NAT","192.0.2.7",51000,"198.51.100.27",54022,"TCP",at.minusSeconds(10),at.plusSeconds(10),"COMPLETE",0,"fixture","SYNTHETIC")).block(TIMEOUT);
        var query=new InvestigationInput("198.51.100.27",54022,"TCP",at,"PUBLIC_TO_PRIVATE");
        assertThatThrownBy(()->service.investigate(admin,query).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("INVESTIGATION_CAPACITY"));
    }
    @Test void gatewayNatAndDifferentHostLeaseConfirmWithinTheirCapturedSite(){
        String host=inventory.create(org,"admin",new Models.CreateDevice("Lease host","HOST","site",null,null,"192.0.2.8")).block(TIMEOUT).id();
        Instant at=WorkbenchService.now().minusSeconds(30);
        service.reportNetwork(admin,new NetworkInput("nat",device,"NAT","192.0.2.8",51000,"198.51.100.29",54024,"TCP",at.minusSeconds(10),at.plusSeconds(10),"COMPLETE",0,"fixture","SYNTHETIC")).block(TIMEOUT);
        service.reportNetwork(admin,new NetworkInput("lease",host,"ADDRESS_LEASE","192.0.2.8",null,null,null,null,at.minusSeconds(10),at.plusSeconds(10),"COMPLETE",0,"fixture","SYNTHETIC")).block(TIMEOUT);
        var result=service.investigate(admin,new InvestigationInput("198.51.100.29",54024,"TCP",at,"PUBLIC_TO_PRIVATE")).block(TIMEOUT);
        assertThat(result.status()).isEqualTo("CONFIRMED");assertThat(result.candidates().getFirst().leases().getFirst().deviceId()).isEqualTo(host);
    }
    @Test void reusedPrivateAddressInAnotherSiteDoesNotSupplyMissingHistoricalLease(){
        db.sql("INSERT INTO site(organization_id,id,name) VALUES(:org,'other-site','Other site')").bind("org",org).fetch().rowsUpdated().block(TIMEOUT);
        String host=inventory.create(org,"admin",new Models.CreateDevice("Other site host","HOST","other-site",null,null,"192.0.2.8")).block(TIMEOUT).id();
        Instant at=WorkbenchService.now().minusSeconds(30);
        service.reportNetwork(admin,new NetworkInput("nat",device,"NAT","192.0.2.8",51000,"198.51.100.29",54024,"TCP",at.minusSeconds(10),at.plusSeconds(10),"COMPLETE",0,"fixture","SYNTHETIC")).block(TIMEOUT);
        service.reportNetwork(admin,new NetworkInput("lease",host,"ADDRESS_LEASE","192.0.2.8",null,null,null,null,at.minusSeconds(10),at.plusSeconds(10),"COMPLETE",0,"fixture","SYNTHETIC")).block(TIMEOUT);
        var result=service.investigate(admin,new InvestigationInput("198.51.100.29",54024,"TCP",at,"PUBLIC_TO_PRIVATE")).block(TIMEOUT);
        assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(result.candidates().getFirst().leases()).isEmpty();
    }
    @Test void snapshotReadsAndDiffRefuseContentThatDoesNotMatchStoredChecksum(){
        var before=service.createSnapshot(admin,new ConfigurationInput(device,"Before","fixture",WorkbenchService.now(),"hostname original","SYNTHETIC")).block(TIMEOUT);
        var after=service.createSnapshot(admin,new ConfigurationInput(device,"After","fixture",WorkbenchService.now(),"hostname updated","SYNTHETIC")).block(TIMEOUT);
        db.sql("UPDATE workbench_record SET payload=JSON_SET(payload,'$.content','hostname tampered') WHERE organization_id=:org AND category='CONFIGURATION' AND id=:id")
            .bind("org",org).bind("id",after.id()).fetch().rowsUpdated().block(TIMEOUT);
        assertThatThrownBy(()->service.snapshot(admin,after.id()).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("CONFIGURATION_INTEGRITY_FAILURE"));
        assertThatThrownBy(()->service.diff(admin,before.id(),after.id()).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("CONFIGURATION_INTEGRITY_FAILURE"));
    }
    @Test void metadataQueriesNeverMaterializeLargeContentInRepositoryResults(){
        service.createEvidence(admin,new EvidenceInput(device,"Large evidence","NOTE","fixture",WorkbenchService.now(),"large ".repeat(4000),"SYNTHETIC")).block(TIMEOUT);
        service.createSnapshot(admin,new ConfigurationInput(device,"Large snapshot","fixture",WorkbenchService.now(),"interface eth0\n".repeat(1000),"SYNTHETIC")).block(TIMEOUT);
        assertThat(store.list(org,"EVIDENCE","","","","",50,Evidence.class).single().block(TIMEOUT).content()).isNull();
        assertThat(store.list(org,"CONFIGURATION","","","","",50,Snapshot.class).single().block(TIMEOUT).content()).isNull();
        service.createIncident(admin,incident("Summary notes")).block(TIMEOUT);
        assertThat(store.list(org,"INCIDENT","","","","",50,IncidentSummary.class).single().block(TIMEOUT).noteCount()).isEqualTo(1);
    }
    @Test void definitionRevisionIsValidatedInsideResultTransactionAndLatestIsCleared(){
        var check=check();Instant at=WorkbenchService.now().minusSeconds(10);
        var original=service.reportResult(admin,new CheckResultInput("original",check.id(),at,"PASS",10.0,"","fixture","SYNTHETIC",1L)).block(TIMEOUT);
        var updated=service.updateCheck(admin,check.id(),new CheckUpdate(1,"New target","192.0.2.99:22",60,true)).block(TIMEOUT);
        assertThat(updated.lastResult()).isNull();
        assertThatThrownBy(()->store.putResult(org,"admin",original).block(TIMEOUT)).isInstanceOf(ApiException.class);
        service.reportResult(admin,new CheckResultInput("current",check.id(),at.plusSeconds(1),"PASS",11.0,"","fixture","SYNTHETIC",2L)).block(TIMEOUT);
        assertThat(service.check(admin,check.id()).block(TIMEOUT).lastResult().definitionRevision()).isEqualTo(2);
        assertThat(service.results(admin,check.id(),"",30).block(TIMEOUT).items()).hasSize(2);
    }
    CheckExecution execution(WorkbenchService workbench,TargetPolicy policy,boolean worker) {
        var beans=new StaticListableBeanFactory();beans.addBean("database",db);
        return new CheckExecution(workbench,policy,beans.getBeanProvider(DatabaseClient.class),new MockEnvironment().withProperty("NOERIVA_DEVICE_COLLECTOR_ENABLED",Boolean.toString(worker)));
    }
    record NativeLease(String token,long revision,LocalDateTime next){}
    NativeLease lease(String id){return db.sql("SELECT lease_id,definition_revision,next_run_at FROM check_execution WHERE organization_id=:org AND check_id=:id").bind("org",org).bind("id",id)
        .map((row,metadata)->new NativeLease(row.get("lease_id",String.class),row.get("definition_revision",Long.class),row.get("next_run_at",LocalDateTime.class))).one().block(TIMEOUT);}
    void awaitResult(String id,long revision){Mono.defer(()->service.check(admin,id)).filter(c->c.lastResult()!=null&&c.lastResult().definitionRevision()==revision)
        .repeatWhenEmpty(50,repeats->repeats.delayElements(Duration.ofMillis(100))).block(TIMEOUT);}
    void awaitReleased(String id){Mono.defer(()->db.sql("SELECT COUNT(*) done FROM check_execution WHERE organization_id=:org AND check_id=:id AND lease_id IS NULL").bind("org",org).bind("id",id)
        .map((row,metadata)->row.get("done",Long.class)).one()).filter(done->done==1).repeatWhenEmpty(50,repeats->repeats.delayElements(Duration.ofMillis(100))).block(TIMEOUT);}

    @Test void nativeCheckMigrationAndCrossInstanceLeasePreserveRevisionAndRelease()throws Exception {
        long migration=db.sql("SELECT COUNT(*) n FROM flyway_schema_history WHERE version='11' AND success=1").map((row,metadata)->row.get("n",Long.class)).one().block(TIMEOUT);
        assertThat(migration).isEqualTo(1);
        var requests=new AtomicInteger();var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var status=new AtomicInteger(200);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);var threads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(threads);
        server.createContext("/",exchange->{requests.incrementAndGet();entered.countDown();try{finish.await(5,TimeUnit.SECONDS);exchange.sendResponseHeaders(status.get(),-1);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        var policyA=new TargetPolicy("127.0.0.1/32");var policyB=new TargetPolicy("127.0.0.1/32");var secondService=new WorkbenchService(repository(),inventory);
        var first=execution(service,policyA,false);var second=execution(secondService,policyB,false);
        try {
            String target="http://127.0.0.1:"+server.getAddress().getPort()+"/health";
            var check=service.createCheck(admin,new CheckInput(device,"Native fixture","HTTP",target,300,true,"MANUAL")).block(TIMEOUT);
            assertThat(check.execution()).isEqualTo("NATIVE_WORKER");
            var future=first.run(admin,check.id(),1).toFuture();assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->second.run(admin,check.id(),1).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,error->{assertThat(error.status).isEqualTo(HttpStatus.CONFLICT);assertThat(error.code).isEqualTo("CHECK_ALREADY_RUNNING");});
            assertThat(lease(check.id()).token()).isNotBlank();assertThat(requests).hasValue(1);
            finish.countDown();assertThat(future.get(5,TimeUnit.SECONDS).status()).isEqualTo("PASS");
            assertThat(lease(check.id()).token()).isNull();assertThat(lease(check.id()).revision()).isEqualTo(1);assertThat(lease(check.id()).next()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(200));
            status.set(503);var update=service.updateCheck(admin,check.id(),new CheckUpdate(1,"Updated fixture",target,60,false)).block(TIMEOUT);assertThat(update.lastResult()).isNull();
            assertThatThrownBy(()->first.run(admin,check.id(),1).block(TIMEOUT)).isInstanceOf(ApiException.class);
            var result=second.run(admin,check.id(),2).block(TIMEOUT);assertThat(result.status()).isEqualTo("FAIL");assertThat(result.message()).contains("HTTP 503");
            assertThat(lease(check.id()).token()).isNull();assertThat(lease(check.id()).revision()).isEqualTo(2);assertThat(service.results(admin,check.id(),"",30).block(TIMEOUT).items()).hasSize(2);assertThat(requests).hasValue(2);
            assertThat(secondService.check(admin,check.id()).block(TIMEOUT).lastResult().definitionRevision()).isEqualTo(2);
        } finally {finish.countDown();first.close();second.close();secondService.close();policyA.close();policyB.close();server.stop(0);threads.shutdownNow();}
    }

    @Test void nativeWorkerSqlSchedulesDueManualDefinitionsAndNewRevisionsOnly()throws Exception {
        var paths=new CopyOnWriteArrayList<String>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
        server.createContext("/",exchange->{paths.add(exchange.getRequestURI().getPath());exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        var policy=new TargetPolicy("127.0.0.1/32");var worker=execution(service,policy,true);
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var enabled=service.createCheck(admin,new CheckInput(device,"Scheduled enabled","HTTP",base+"/enabled",3600,true,"MANUAL")).block(TIMEOUT);
            service.createCheck(admin,new CheckInput(device,"Scheduled disabled","HTTP",base+"/disabled",30,false,"MANUAL")).block(TIMEOUT);
            service.createCheck(admin,new CheckInput(device,"Scheduled fixture","HTTP",base+"/synthetic",30,true,"SYNTHETIC")).block(TIMEOUT);
            worker.tick();awaitResult(enabled.id(),1);awaitReleased(enabled.id());
            assertThat(paths).containsExactly("/enabled");assertThat(lease(enabled.id()).next()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(3500));
            // next_run_at is an hour away, but changing the definition revision makes it due now.
            service.updateCheck(admin,enabled.id(),new CheckUpdate(1,"Scheduled revision 2",base+"/updated",3600,true)).block(TIMEOUT);
            Mono.defer(()->{worker.tick();return service.check(admin,enabled.id());}).filter(c->c.lastResult()!=null&&c.lastResult().definitionRevision()==2)
                .repeatWhenEmpty(50,repeats->repeats.delayElements(Duration.ofMillis(100))).block(TIMEOUT);
            awaitReleased(enabled.id());assertThat(paths).containsExactly("/enabled","/updated");assertThat(lease(enabled.id()).revision()).isEqualTo(2);
            assertThat(service.results(admin,enabled.id(),"",30).block(TIMEOUT).items()).hasSize(2);
            service.archiveCheck(admin,enabled.id(),new Revision(2)).block(TIMEOUT);
        } finally {worker.close();policy.close();server.stop(0);}
    }

    @Test void configurationCapturePersistsRedactedSnapshotsAndSettingsAndPreservesLastSuccessOnFailure(){
        var beans=new StaticListableBeanFactory();beans.addBean("db",db);
        var settings=new PlatformSettings(beans.getBeanProvider(DatabaseClient.class),json);
        var input=new PlatformSettings.Input(0,"Lab","UTC",60,5000,128,true,900);
        assertThat(settings.save(admin,input).block(TIMEOUT).revision()).isEqualTo(1);
        assertThat(new PlatformSettings(beans.getBeanProvider(DatabaseClient.class),json).get(org).block(TIMEOUT).timezone()).isEqualTo("UTC");
        assertThat(settings.get(otherOrg).block(TIMEOUT).revision()).isZero();
        assertThatThrownBy(()->settings.save(admin,input).block(TIMEOUT)).isInstanceOf(ApiException.class);
        var reader=mock(io.noeriva.control.devices.ConfigurationReader.class);
        String raw="hostname test\nusername fixture secret sensitive-fixture-material\nend";
        var capture=new io.noeriva.control.devices.ConfigurationReader.Capture(raw,"SSH_RUNNING_CONFIGURATION",WorkbenchService.now());
        when(reader.read(org,device)).thenReturn(Mono.just(capture));
        var sync=new ConfigurationCapture(reader,service,beans.getBeanProvider(DatabaseClient.class),settings,inventory,new MockEnvironment());
        var first=sync.capture(admin,device).block(TIMEOUT);
        assertThat(first.status()).isEqualTo("SUCCESS");assertThat(first.snapshot().content()).doesNotContain("sensitive-fixture-material");assertThat(first.snapshot().redactedLines()).isEqualTo(1);
        assertThat(sync.capture(admin,device).block(TIMEOUT).status()).isEqualTo("UNCHANGED");
        assertThat(service.snapshots(admin,device,"","",10).block(TIMEOUT).items()).hasSize(1);
        when(reader.read(org,device)).thenReturn(Mono.error(new io.noeriva.control.devices.DeviceProtocol.Failure("SSH_CONNECT_FAILED","safe")));
        assertThat(sync.capture(admin,device).block(TIMEOUT).status()).isEqualTo("ERROR");
        var persisted=sync.state(org,device).block(TIMEOUT);assertThat(persisted.status()).isEqualTo("ERROR");assertThat(persisted.capturedAt()).isEqualTo(capture.capturedAt());
        assertThatThrownBy(()->sync.capture(other,device).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test void snmpBaselinePersistsItsLimitedScopeAndNeverClaimsCompleteRunningConfiguration(){
        var beans=new StaticListableBeanFactory();beans.addBean("db",db);
        var settings=new PlatformSettings(beans.getBeanProvider(DatabaseClient.class),json);
        var reader=mock(io.noeriva.control.devices.ConfigurationReader.class);
        var capture=new io.noeriva.control.devices.ConfigurationReader.Capture("{\"scope\":\"DEVICE_IDENTITY_AND_INTERFACE_BASELINE\",\"identity\":{},\"interfaces\":[]}","SNMP_DEVICE_BASELINE",WorkbenchService.now());
        when(reader.read(org,device)).thenReturn(Mono.just(capture));
        var sync=new ConfigurationCapture(reader,service,beans.getBeanProvider(DatabaseClient.class),settings,inventory,new MockEnvironment());
        var result=sync.capture(admin,device).block(TIMEOUT);
        assertThat(result.status()).isEqualTo("SUCCESS");assertThat(result.source()).isEqualTo("SNMP_DEVICE_BASELINE");
        assertThat(result.message()).contains("基线","不包含完整运行配置").doesNotContain("设备未提供");
        assertThat(result.snapshot().source()).isEqualTo("SNMP_DEVICE_BASELINE");
        assertThat(sync.state(org,device).block(TIMEOUT).source()).isEqualTo("SNMP_DEVICE_BASELINE");
        var unchanged=sync.capture(admin,device).block(TIMEOUT);assertThat(unchanged.status()).isEqualTo("UNCHANGED");assertThat(unchanged.message()).contains("不包含完整运行配置");
        assertThat(service.snapshots(admin,device,"","",10).block(TIMEOUT).items()).hasSize(1);
    }

    @Test void topologySelectsEnabledSnmpBeforeFreshnessWithoutFallingBackToSsh(){
        var beans=new StaticListableBeanFactory();beans.addBean("db",db);
        var graph=new ObservedTopology(beans.getBeanProvider(DatabaseClient.class),json,new MockEnvironment());
        var instant=Instant.now();
        for(String slot:List.of("ssh","snmp")){
            var reading=new io.noeriva.control.devices.DeviceProtocol.Reading(instant,new io.noeriva.control.devices.DeviceProtocol.Identity("test","switch","fixture","model","serial","v1",null,slot,"fixture"),"UNKNOWN",Map.of(),List.of(),List.of(),List.of(),List.of(),Map.of("neighborObservations","[]"));
            db.sql("INSERT INTO device_connection(organization_id,device_id,slot,revision,enabled,settings,ciphertext,status,next_poll_at,source_epoch,last_success_at,last_reading) VALUES(:org,:device,:slot,1,1,JSON_OBJECT(),'unread-fixture','SUCCESS',UTC_TIMESTAMP(6),'fixture',UTC_TIMESTAMP(6),:reading)").bind("org",org).bind("device",device).bind("slot",slot).bind("reading",json.writeValueAsString(reading)).fetch().rowsUpdated().block(TIMEOUT);
        }
        var rows=graph.sources().block(TIMEOUT).stream().filter(s->s.org().equals(org)).toList();
        assertThat(rows).hasSize(1);assertThat(rows.getFirst().reading().identity().sysName()).isEqualTo("snmp");
        db.sql("UPDATE device_connection SET last_success_at=UTC_TIMESTAMP()-INTERVAL 181 SECOND WHERE organization_id=:org AND device_id=:device AND slot='snmp'").bind("org",org).bind("device",device).fetch().rowsUpdated().block(TIMEOUT);
        assertThat(graph.sources().block(TIMEOUT).stream().filter(s->s.org().equals(org))).isEmpty();
        db.sql("UPDATE device_connection SET enabled=0 WHERE organization_id=:org AND device_id=:device AND slot='snmp'").bind("org",org).bind("device",device).fetch().rowsUpdated().block(TIMEOUT);
        rows=graph.sources().block(TIMEOUT).stream().filter(s->s.org().equals(org)).toList();assertThat(rows).hasSize(1);assertThat(rows.getFirst().reading().identity().sysName()).isEqualTo("ssh");
        db.sql("UPDATE device_connection SET enabled=0 WHERE organization_id=:org AND device_id=:device").bind("org",org).bind("device",device).fetch().rowsUpdated().block(TIMEOUT);
        assertThat(graph.sources().block(TIMEOUT).stream().filter(s->s.org().equals(org))).isEmpty();
    }

    @Test void topologySnapshotUsesRealScopedSqlWithoutCmdbWritesAndSelectedStaleSnmpNeverRevivesSsh(){
        var beans=new StaticListableBeanFactory();beans.addBean("db",db);var graph=new TopologyService(inventory,beans.getBeanProvider(DatabaseClient.class),json);
        var at=Instant.now();var endpoint=Map.of("mac","02:11:22:33:44:55","address","192.168.4.77","source","IP-MIB/ipNetToPhysical","interfaceName","Vlan4","entryType","DYNAMIC","neighborState","REACHABLE");
        var path=Map.of("mac","02:11:22:33:44:55","interfaceName","Gi1/8","vlanIds",List.of(4),"entryStatus","LEARNED","sourceRef","1.3.6.1.2.1.17.7.1.2.2.1.2.7.2.17.34.51.68.85");
        var reading=new io.noeriva.control.devices.DeviceProtocol.Reading(at,new io.noeriva.control.devices.DeviceProtocol.Identity("test","switch","fixture","model","serial","v1",null,"snmp","fixture"),"UNKNOWN",Map.of(),List.of(),List.of(),List.of(),List.of(),Map.of("addressObservations",json.writeValueAsString(List.of(endpoint)),"forwardingObservations",json.writeValueAsString(List.of(path))));
        for(String slot:List.of("ssh","snmp"))db.sql("INSERT INTO device_connection(organization_id,device_id,slot,revision,enabled,settings,ciphertext,status,next_poll_at,source_epoch,last_success_at,last_reading) VALUES(:org,:device,:slot,1,1,JSON_OBJECT(),'unread-fixture','SUCCESS',UTC_TIMESTAMP(6),'fixture',UTC_TIMESTAMP(6),:reading)").bind("org",org).bind("device",device).bind("slot",slot).bind("reading",json.writeValueAsString(reading)).fetch().rowsUpdated().block(TIMEOUT);
        long before=count("device");var result=graph.snapshot(org,"site",device,"ALL",4,200).block(TIMEOUT);
        assertThat(result.nodes()).hasSize(2);assertThat(result.edges()).anyMatch(e->e.kind().equals("L2_INFERRED"));assertThat(result.vlans()).singleElement().satisfies(v->assertThat(v.nodeCount()).isEqualTo(2));assertThat(count("device")).isEqualTo(before);
        assertThatThrownBy(()->graph.snapshot(otherOrg,"",device,"ALL",null,200).block(TIMEOUT)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->graph.snapshot(org,"other-site",device,"ALL",null,200).block(TIMEOUT)).isInstanceOf(ApiException.class);
        db.sql("UPDATE device_connection SET last_success_at=UTC_TIMESTAMP()-INTERVAL 181 SECOND WHERE organization_id=:org AND device_id=:device AND slot='snmp'").bind("org",org).bind("device",device).fetch().rowsUpdated().block(TIMEOUT);
        assertThat(graph.snapshot(org,"","","ALL",null,200).block(TIMEOUT).nodes()).allMatch(Models.Node::registered);
    }
    @Test void topologyOversizedSourceIsReportedWithoutDownloadingUnboundedPayload(){
        var beans=new StaticListableBeanFactory();beans.addBean("db",db);var graph=new TopologyService(inventory,beans.getBeanProvider(DatabaseClient.class),json);
        db.sql("INSERT INTO device_connection(organization_id,device_id,slot,revision,enabled,settings,ciphertext,status,next_poll_at,source_epoch,last_success_at,last_reading) VALUES(:org,:device,'snmp',1,1,JSON_OBJECT(),'unread-fixture','SUCCESS',UTC_TIMESTAMP(6),'fixture',UTC_TIMESTAMP(6),:reading)").bind("org",org).bind("device",device).bind("reading",json.writeValueAsString(Map.of("oversized","x".repeat(270000)))).fetch().rowsUpdated().block(TIMEOUT);
        var result=graph.snapshot(org,"","","ALL",null,200).block(TIMEOUT);assertThat(result.qualityFlags()).contains("SOURCE_BYTES_LIMIT");assertThat(result.nodes()).hasSize(1).allMatch(Models.Node::registered);
    }

}
