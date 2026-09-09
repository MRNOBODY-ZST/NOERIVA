package io.noeriva.control.applications;

import io.noeriva.control.*;
import io.r2dbc.spi.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import io.noeriva.control.devices.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static io.noeriva.control.applications.ApplicationModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers class ApplicationStoreTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("applications_test").withUsername("test").withPassword("isolated-test-only");
    static DatabaseClient db;static TransactionalOperator tx;static JsonMapper json;static StaticListableBeanFactory beans;
    String org,device;Object store;Class<?> type;static final Duration WAIT=Duration.ofSeconds(20);
    @BeforeAll static void database(){
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
        var cf=ConnectionFactories.get(ConnectionFactoryOptions.builder().option(DRIVER,"mysql").option(HOST,MYSQL.getHost()).option(PORT,MYSQL.getMappedPort(3306)).option(DATABASE,MYSQL.getDatabaseName()).option(USER,MYSQL.getUsername()).option(PASSWORD,MYSQL.getPassword()).option(Option.valueOf("sslMode"),"DISABLED").option(Option.valueOf("allowPublicKeyRetrieval"),true).build());
        db=DatabaseClient.create(cf);tx=TransactionalOperator.create(new R2dbcTransactionManager(cf));json=JsonMapper.builder().build();beans=new StaticListableBeanFactory();beans.addBean("db",db);beans.addBean("tx",tx);
    }
    @BeforeEach void setup()throws Exception{
        org="app-"+UUID.randomUUID();device=UUID.randomUUID().toString();
        db.sql("INSERT INTO organization(id,name) VALUES(:org,'App')").bind("org",org).fetch().rowsUpdated().block(WAIT);
        db.sql("INSERT INTO site(organization_id,id,name) VALUES(:org,'site','Site')").bind("org",org).fetch().rowsUpdated().block(WAIT);
        db.sql("INSERT INTO device(organization_id,id,name,type,site_id,vendor,model,management_address,capabilities) VALUES(:org,:id,'Router','ROUTER','site','Cisco','','10.0.0.1','[]')").bind("org",org).bind("id",device).fetch().rowsUpdated().block(WAIT);
        db.sql("INSERT INTO device_connection(organization_id,device_id,slot,revision,enabled,settings,ciphertext,next_poll_at,source_epoch) VALUES(:org,:id,'snmp',1,0,'{}','encrypted-test',UTC_TIMESTAMP(6),'epoch')").bind("org",org).bind("id",device).fetch().rowsUpdated().block(WAIT);
        type=Class.forName("io.noeriva.control.applications.ApplicationStore");store=type.getConstructor(ObjectProvider.class,ObjectProvider.class,JsonMapper.class).newInstance(beans.getBeanProvider(DatabaseClient.class),beans.getBeanProvider(TransactionalOperator.class),json);
    }
    @SuppressWarnings("unchecked") <T> Mono<T> call(String name,Class<?>[] types,Object...args)throws Exception{return (Mono<T>)type.getMethod(name,types).invoke(store,args);}
    Mono<Stored> save(long revision)throws Exception{return call("save",new Class[]{String.class,String.class,String.class,SettingsInput.class},org,"admin",device,new SettingsInput(revision,true,60,List.of(7),128));}
    Mono<Stored> acquire(Stored v,boolean scheduled)throws Exception{return call("acquire",new Class[]{Stored.class,boolean.class},v,scheduled);}
    Mono<Stored> state(Stored v,boolean enabled)throws Exception{return call("state",new Class[]{String.class,String.class,String.class,StateInput.class},org,"admin",device,new StateInput(v.revision(),enabled));}
    @Test void independentEnabledDoesNotChangeDisabledSnmpAndConcurrentSaveHasOneWinner()throws Exception{
        var results=Flux.merge(save(0).materialize(),save(0).materialize()).collectList().block(WAIT);assertThat(results.stream().filter(Signal::isOnNext)).hasSize(1);assertThat(results.stream().filter(Signal::isOnError)).hasSize(1);
        assertThat(db.sql("SELECT enabled FROM device_connection WHERE organization_id=:org").bind("org",org).map((r,m)->r.get("enabled",Boolean.class)).one().block(WAIT)).isFalse();
    }
    @Test void leaseBlocksSecondCollectorAndSettingsMutation()throws Exception{var v=save(0).block(WAIT);var lease=acquire(v,false).block(WAIT);assertThat(lease.status()).isEqualTo("RUNNING");assertThatThrownBy(()->acquire(v,false).block(WAIT)).isInstanceOf(ApiException.class);assertThatThrownBy(()->state(v,false).block(WAIT)).isInstanceOf(ApiException.class);}
    @Test void disabledApplicationsDoNotAcquireScheduledLeaseButAllowManual()throws Exception{var disabled=state(save(0).block(WAIT),false).block(WAIT);assertThat(disabled.revision()).isEqualTo(2);assertThatThrownBy(()->acquire(disabled,true).block(WAIT)).isInstanceOf(ApiException.class);assertThat(acquire(disabled,false).block(WAIT).status()).isEqualTo("RUNNING");}
    @Test void tenantScopeAndSafeSourceProjectionExcludeEncryptedCredential()throws Exception{
        save(0).block(WAIT);
        assertThat(this.<Stored>call("get",new Class[]{String.class,String.class},"other",device).block(WAIT)).isNull();
        var source=this.<Source>call("source",new Class[]{String.class,String.class},org,device).block(WAIT);
        assertThat(source.credentialRevision()).isEqualTo(1);assertThat(json.writeValueAsString(source)).doesNotContain("encrypted-test","ciphertext","username");
    }
    @Test void expiredLeaseCannotCommitAndFailedPublicationDoesNotAdvanceBaseline()throws Exception{
        var v=acquire(save(0).block(WAIT),false).block(WAIT);var sample=new Sample(Instant.now(),"100","engine","2",List.of(),List.of());
        this.<Stored>call("finish",new Class[]{Stored.class,Sample.class,long.class,int.class,String.class,String.class,List.class},v,null,1L,0,"PUBLICATION_FAILED","Storage did not confirm",List.of()).block(WAIT);
        var current=this.<Stored>call("get",new Class[]{String.class,String.class},org,device).block(WAIT);assertThat(current.baseline()).isNull();assertThat(current.lastSuccessAt()).isNull();
        var leased=acquire(current,false).block(WAIT);db.sql("UPDATE application_source SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE organization_id=:org").bind("org",org).fetch().rowsUpdated().block(WAIT);
        assertThatThrownBy(()->call("finish",new Class[]{Stored.class,Sample.class,long.class,int.class,String.class,String.class,List.class},leased,sample,1L,0,"","",List.of()).block(WAIT)).isInstanceOf(ApiException.class);
    }
    ApplicationService service(boolean failPublication,java.util.concurrent.atomic.AtomicInteger calls)throws Exception{
        var vault=new CredentialVault(Base64.getEncoder().encodeToString(new byte[32]));
        var settings=new DeviceAccessModels.Settings("10.0.0.1",161,60,1000,128,"","2c","","","","","","",true,false,false,false);
        db.sql("UPDATE device_connection SET settings=:settings,ciphertext=:cipher WHERE organization_id=:org").bind("settings",json.writeValueAsString(settings)).bind("cipher",vault.encrypt(org+"/"+device+"/snmp",new DeviceProtocol.Secrets("synthetic-only",null,null,null))).bind("org",org).fetch().rowsUpdated().block(WAIT);
        var devices=new DeviceAccessStore(beans.getBeanProvider(DatabaseClient.class),beans.getBeanProvider(TransactionalOperator.class),json,null){
            @Override public Mono<Models.Device> required(String requested,String id){return db.sql("SELECT id FROM device WHERE organization_id=:org AND id=:id").bind("org",requested).bind("id",id).map((r,m)->new Models.Device(id,"Router","ROUTER","site","Site","Cisco","","10.0.0.1","UNKNOWN","UNKNOWN",null,1,List.of())).one().switchIfEmpty(Mono.error(ApiException.missing()));}
        };
        var history=new ApplicationHistory(WebClient.builder(),new MockEnvironment(),json){@Override public Mono<Void> append(String requested,List<Observation> rows){assertThat(requested).isEqualTo(org);return failPublication?Mono.error(new RuntimeException("sensitive-upstream")):Mono.empty();}};
        var policy=new TargetPolicy("10.0.0.0/8");
        ApplicationProtocol driver=(target,secrets,indices,max)->{calls.incrementAndGet();assertThat(secrets.community()).isEqualTo("synthetic-only");assertThat(target.address()).isEqualTo("10.0.0.1");policy.close();return Mono.just(new Sample(Instant.now(),"123456","engine","2",List.of(new CounterRow(7,"Te0/1/0","name/mac",42,"http","10","100","200","1","2",10d,20d,List.of())),List.of()));};
        return new ApplicationService((ApplicationStore)store,devices,vault,policy,driver,history);
    }
    @Test void serviceCollectWorksWithBothSchedulesDisabledAndKeepsFailureSecretSafe()throws Exception{
        var v=state(save(0).block(WAIT),false).block(WAIT);var calls=new java.util.concurrent.atomic.AtomicInteger();
        var admin=new SecurityConfiguration.Operator("admin","unused",org,List.of("ADMIN"));
        var result=service(true,calls).collect(admin,device,new CollectInput(v.revision())).block(WAIT);
        assertThat(calls).hasValue(1);assertThat(result.status()).isEqualTo("ERROR");assertThat(result.errorCode()).isEqualTo("PUBLICATION_FAILED");assertThat(result.enabled()).isFalse();assertThat(result.lastSuccessAt()).isNull();assertThat(json.writeValueAsString(result)).doesNotContain("sensitive-upstream","synthetic-only");
        result=service(false,calls).collect(admin,device,new CollectInput(v.revision())).block(WAIT);assertThat(result.status()).isEqualTo("PARTIAL");assertThat(result.lastRowCount()).isEqualTo(2);assertThat(result.qualityFlags()).contains("NBAR_BASELINE_REQUIRED");
    }
    @Test void serviceGuardsWritesForReadRolesAndRejectsCrossTenant()throws Exception{
        var v=save(0).block(WAIT);var calls=new java.util.concurrent.atomic.AtomicInteger();var service=service(false,calls);
        for(String role:List.of("VIEWER","OPERATOR")){
            var user=new SecurityConfiguration.Operator("reader","unused",org,List.of(role));
            assertThat(service.sources(user,50,null).block(WAIT).items()).hasSize(1);
            assertThatThrownBy(()->service.state(user,device,new StateInput(v.revision(),false))).isInstanceOf(ApiException.class);
            assertThatThrownBy(()->service.collect(user,device,new CollectInput(v.revision()))).isInstanceOf(ApiException.class);
        }
        var other=new SecurityConfiguration.Operator("admin","unused","other",List.of("ADMIN"));
        assertThatThrownBy(()->service.collect(other,device,new CollectInput(v.revision())).block(WAIT)).isInstanceOf(ApiException.class);assertThat(calls).hasValue(0);
    }
}
