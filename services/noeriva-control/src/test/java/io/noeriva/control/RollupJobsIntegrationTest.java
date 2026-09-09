package io.noeriva.control;

import static org.junit.jupiter.api.Assertions.*;
import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import io.noeriva.query.*;
import io.noeriva.control.SecurityConfiguration.Operator;
import io.r2dbc.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.*;

@Testcontainers
class RollupJobsIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11")
        .withDatabaseName("noeriva_jobs_test").withUsername("jobs_test").withPassword("isolated-jobs-test-only");
    static DatabaseClient db;
    static TransactionalOperator tx;
    private static final Duration DEADLINE=Duration.ofSeconds(30);
    String org,otherOrg;Operator admin,other;
    RollupWorker worker;
    RollupJobs jobs;
    Instant from,to;

    @BeforeAll static void database() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
        var connection=ConnectionFactories.get(ConnectionFactoryOptions.builder().option(DRIVER,"mysql").option(HOST,MYSQL.getHost())
            .option(PORT,MYSQL.getMappedPort(3306)).option(DATABASE,MYSQL.getDatabaseName()).option(USER,MYSQL.getUsername()).option(PASSWORD,MYSQL.getPassword())
            .option(Option.valueOf("sslMode"),"DISABLED").option(Option.valueOf("allowPublicKeyRetrieval"),true).build());
        db=DatabaseClient.create(connection);tx=TransactionalOperator.create(new R2dbcTransactionManager(connection));
    }
    @BeforeEach void fixtures() {
        org="jobs-"+UUID.randomUUID();otherOrg="jobs-"+UUID.randomUUID();
        admin=new Operator("admin","unused",org,List.of("ADMIN"));other=new Operator("admin-other","unused",otherOrg,List.of("ADMIN"));
        from=Instant.ofEpochSecond(Math.floorDiv(Instant.now().getEpochSecond(),300)*300-600);to=from.plusSeconds(300);
        db.sql("INSERT INTO organization(id,name) VALUES(:org,'Job tests'),(:other,'Other job tests')").bind("org",org).bind("other",otherOrg).fetch().rowsUpdated().block(DEADLINE);
        db.sql("INSERT INTO site(organization_id,id,name) VALUES(:org,'site-a','Test site')").bind("org",org).fetch().rowsUpdated().block(DEADLINE);
        db.sql("INSERT INTO device(organization_id,id,name,type,site_id,management_address,capabilities) VALUES(:org,'device-a','Test device','NETWORK','site-a','192.0.2.1',JSON_ARRAY('metrics'))")
            .bind("org",org).fetch().rowsUpdated().block(DEADLINE);
        db.sql("INSERT INTO network_interface(organization_id,id,device_id,payload) VALUES(:org,'eth0','device-a',JSON_OBJECT())")
            .bind("org",org).fetch().rowsUpdated().block(DEADLINE);
        RawCounterRepository raw=(key,start,end)->{
            var samples=new ArrayList<CounterSample>();
            for(int second=0;second<=300;second+=15)samples.add(new CounterSample(from.plusSeconds(second),BigInteger.valueOf(second*100L),"boot-it",false));
            return Mono.just(new RawCounterRepository.Result(samples,Set.of()));
        };
        worker=new RollupWorker(raw,(key,buckets)->Mono.empty(),new MySqlRollupStateStore(db,tx));
        jobs=new RollupJobs(db,tx,worker,org,"primary",true,Clock.systemUTC());
    }
    @AfterEach void cleanup() {jobs.close();worker.close();}
    RollupJobs.Request request() {return new RollupJobs.Request("device-a","eth0",from,to,"rx");}

    @Test void enqueueChecksOwnershipAndPersistsAuditAndScopedStatus() {
        var created=jobs.create(admin,request()).block(DEADLINE);
        assertEquals("PENDING",created.status());assertEquals("ROLLUP_REPAIR",created.type());assertEquals("admin",created.createdBy());
        assertEquals(1,db.sql("SELECT COUNT(*) AS n FROM control_audit WHERE organization_id=:org AND resource_id=:id AND action='ROLLUP_REPAIR_CREATED'")
            .bind("org",org).bind("id",created.id()).map((r,m)->r.get("n",Long.class)).one().block(DEADLINE));
        var read=assertThrows(ApiException.class,()->jobs.get(other,created.id()).block(DEADLINE));assertEquals(HttpStatus.NOT_FOUND,read.status);
        var wrong=assertThrows(ApiException.class,()->jobs.create(other,request()).block(DEADLINE));assertEquals(HttpStatus.NOT_FOUND,wrong.status);
    }
    @Test void concurrentClaimIsUniqueAndStaleLeaseCannotFinishReclaimedJob() {
        var created=jobs.create(admin,request()).block(DEADLINE);
        var second=new RollupJobs(db,tx,worker,org,"primary",true,Clock.systemUTC());
        try {
            var claimed=Flux.merge(jobs.claim(),second.claim()).collectList().block(DEADLINE);assertEquals(1,claimed.size());
            var old=claimed.getFirst();
            db.sql("UPDATE rollup_job SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE organization_id=:org AND id=:id")
                .bind("org",org).bind("id",created.id()).fetch().rowsUpdated().block(DEADLINE);
            var fresh=second.claim().block(DEADLINE);assertNotNull(fresh);assertNotEquals(old.token(),fresh.token());
            jobs.finish(old,"SUCCEEDED",1,null).block(DEADLINE);
            assertEquals("RUNNING",jobs.get(admin,created.id()).block(DEADLINE).status());
            second.finish(fresh,"SUCCEEDED",1,null).block(DEADLINE);
            var complete=jobs.get(admin,created.id()).block(DEADLINE);assertEquals("SUCCEEDED",complete.status());assertEquals(2,complete.attempts());
        } finally {second.close();}
    }
    @Test void runOnePublishesSuccessAndCountAfterWorkerAndProgressComplete() {
        var created=jobs.create(admin,request()).block(DEADLINE);
        jobs.runOne().block(DEADLINE);
        var complete=jobs.get(admin,created.id()).block(DEADLINE);assertEquals("SUCCEEDED",complete.status());assertEquals(1,complete.bucketCount());
        assertNotNull(db.sql("SELECT complete_through FROM rollup_progress WHERE organization_id=:org").bind("org",org)
            .map((r,m)->r.get("complete_through",LocalDateTime.class)).one().block(DEADLINE));
    }
    @Test void invisibleSourceFailsJobWithoutClaimingSuccessfulRepair() {
        var failedWorker=new RollupWorker((k,f,t)->Mono.just(new RawCounterRepository.Result(List.of(),Set.of())),(k,b)->Mono.empty(),new MySqlRollupStateStore(db,tx));
        var failing=new RollupJobs(db,tx,failedWorker,org,"primary",true,Clock.systemUTC());
        try {
            var created=failing.create(admin,request()).block(DEADLINE);failing.runOne().block(DEADLINE);
            var result=failing.get(admin,created.id()).block(DEADLINE);assertEquals("FAILED",result.status());assertEquals("RECOMPUTATION_UNAVAILABLE",result.errorCode());assertNull(result.bucketCount());
        } finally {failing.close();failedWorker.close();}
    }
    @Test void queueAdmissionIsBoundedPerOrganization() {
        Flux.range(0,100).flatMap(i->db.sql("INSERT INTO rollup_job(id,organization_id,created_by,device_id,interface_id,source_id,direction,window_from,window_to,status) VALUES(:id,:org,'fixture','device-a','eth0','primary','rx',:from,:to,'PENDING')")
            .bind("id",UUID.randomUUID().toString()).bind("org",org).bind("from",LocalDateTime.ofInstant(from,ZoneOffset.UTC)).bind("to",LocalDateTime.ofInstant(to,ZoneOffset.UTC))
            .fetch().rowsUpdated(),4).then().block(DEADLINE);
        var error=assertThrows(ApiException.class,()->jobs.create(admin,request()).block(DEADLINE));assertEquals(HttpStatus.TOO_MANY_REQUESTS,error.status);
    }
    @Test void capturesAuthorizedInterfaceSourceAndDoesNotRerouteAnAlreadyQueuedJob() {
        db.sql("UPDATE network_interface SET source_id='network' WHERE organization_id=:org AND id='eth0'").bind("org",org).fetch().rowsUpdated().block(DEADLINE);
        var directory=new MySqlRepository(db,tx,new tools.jackson.databind.json.JsonMapper(),null);
        assertEquals("network",directory.interfaceSource(org,"device-a","eth0").block(DEADLINE));
        assertNull(directory.interfaceSource(otherOrg,"device-a","eth0").block(DEADLINE));
        assertNull(directory.interfaceSource(org,"wrong-device","eth0").block(DEADLINE));
        var source=new java.util.concurrent.atomic.AtomicReference<String>();
        var checking=sourceWorker(source);
        var routed=new RollupJobs(db,tx,checking,org,"primary",true,Clock.systemUTC(),directory);
        try {
            var created=routed.create(admin,request()).block(DEADLINE);assertEquals("network",created.sourceId());
            db.sql("UPDATE network_interface SET source_id='bmc' WHERE organization_id=:org AND id='eth0'").bind("org",org).fetch().rowsUpdated().block(DEADLINE);
            routed.runOne().block(DEADLINE);
            assertEquals("network",source.get());assertEquals("SUCCEEDED",routed.get(admin,created.id()).block(DEADLINE).status());
        } finally {routed.close();checking.close();}
    }
    @Test void legacyPersistedPrimaryJobKeepsItsSourceAfterInterfaceAssignmentChanges() {
        var created=jobs.create(admin,request()).block(DEADLINE);assertEquals("primary",created.sourceId());
        db.sql("UPDATE network_interface SET source_id='network' WHERE organization_id=:org AND id='eth0'").bind("org",org).fetch().rowsUpdated().block(DEADLINE);
        var source=new java.util.concurrent.atomic.AtomicReference<String>();var checking=sourceWorker(source);
        var runner=new RollupJobs(db,tx,checking,org,"network",true,Clock.systemUTC());
        try {runner.runOne().block(DEADLINE);assertEquals("primary",source.get());assertEquals("SUCCEEDED",runner.get(admin,created.id()).block(DEADLINE).status());}
        finally {runner.close();checking.close();}
    }
    private RollupWorker sourceWorker(java.util.concurrent.atomic.AtomicReference<String> source){
        return new RollupWorker((key,start,end)->{
            source.set(key.sourceId());var samples=new ArrayList<CounterSample>();
            for(int second=0;second<=300;second+=15)samples.add(new CounterSample(from.plusSeconds(second),BigInteger.valueOf(second*100L),"boot-source-it",false));
            return Mono.just(new RawCounterRepository.Result(samples,Set.of()));
        },(key,buckets)->Mono.empty(),new MySqlRollupStateStore(db,tx));
    }
}
