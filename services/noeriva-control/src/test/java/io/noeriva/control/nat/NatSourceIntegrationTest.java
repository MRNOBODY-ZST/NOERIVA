package io.noeriva.control.nat;

import io.noeriva.control.*;
import io.noeriva.control.SecurityConfiguration.Operator;
import io.r2dbc.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers class NatSourceIntegrationTest {
 @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("nat_test").withUsername("test").withPassword("synthetic-nat-test-only");
 static DatabaseClient db;static TransactionalOperator tx;static final JsonMapper JSON=new JsonMapper();static final Duration WAIT=Duration.ofSeconds(20);
 String org,other;Operator admin,viewer;Object store;
 @BeforeAll static void database(){Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();var cf=ConnectionFactories.get(ConnectionFactoryOptions.builder().option(DRIVER,"mysql").option(HOST,MYSQL.getHost()).option(PORT,MYSQL.getMappedPort(3306)).option(DATABASE,MYSQL.getDatabaseName()).option(USER,MYSQL.getUsername()).option(PASSWORD,MYSQL.getPassword()).option(Option.valueOf("sslMode"),"DISABLED").option(Option.valueOf("allowPublicKeyRetrieval"),true).build());db=DatabaseClient.create(cf);tx=TransactionalOperator.create(new R2dbcTransactionManager(cf));}
 @BeforeEach void seed(){org=UUID.randomUUID().toString();other=UUID.randomUUID().toString();admin=new Operator("admin","unused",org,List.of("ADMIN"));viewer=new Operator("viewer","unused",org,List.of("VIEWER"));for(String o:List.of(org,other)){db.sql("INSERT INTO organization(id,name) VALUES(:o,'synthetic')").bind("o",o).fetch().rowsUpdated().block(WAIT);db.sql("INSERT INTO site(organization_id,id,name) VALUES(:o,'a','synthetic')").bind("o",o).fetch().rowsUpdated().block(WAIT);device(o,"gateway");}}
 void device(String o,String id){db.sql("INSERT INTO device(organization_id,id,name,type,site_id,management_address,capabilities) VALUES(:o,:id,'synthetic ASR','ROUTER','a','192.0.2.1',JSON_ARRAY())").bind("o",o).bind("id",id).fetch().rowsUpdated().block(WAIT);}
 Object store(){if(store!=null)return store;try{return store=Class.forName("io.noeriva.control.nat.NatSourceStore").getDeclaredConstructor(DatabaseClient.class,TransactionalOperator.class,JsonMapper.class,Clock.class).newInstance(db,tx,JSON,Clock.systemUTC());}catch(Exception e){throw new AssertionError("NAT source settings require persisted org-scoped CAS",e);}}
 JsonNode call(String name,Operator actor,String id,Map<String,Object> body){try{Method m=Arrays.stream(store().getClass().getMethods()).filter(x->x.getName().equals(name)).findFirst().orElseThrow();Object input=JSON.convertValue(body,m.getParameterTypes()[2]);return JSON.valueToTree(((Mono<?>)m.invoke(store(),actor,id,input)).block(WAIT));}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
 Map<String,Object> setting(long revision,boolean enabled,String ip){var m=new HashMap<String,Object>();m.put("revision",revision);m.put("enabled",enabled);m.put("sourceAddress",ip);return m;}
 @Test void defaultManagementAddressAndRevisionStateArePersistedAndAudited(){var s=call("save",admin,"gateway",setting(0,true,null));assertThat(s.path("sourceAddress").asString()).isEqualTo("192.0.2.1");assertThat(s.path("status").asString()).isEqualTo("WAITING");var stopped=call("state",admin,"gateway",Map.of("revision",1,"enabled",false));assertThat(stopped.path("revision").asLong()).isEqualTo(2);assertThat(stopped.path("status").asString()).isEqualTo("DISABLED");assertThat(db.sql("SELECT count(*) n FROM control_audit WHERE organization_id=:o").bind("o",org).map((r,m)->r.get("n",Long.class)).one().block(WAIT)).isEqualTo(2);}
 @Test void staleRevisionCannotOverwriteSourceSettings(){call("save",admin,"gateway",setting(0,true,null));assertThatThrownBy(()->call("save",admin,"gateway",setting(0,false,"192.0.2.2"))).isInstanceOf(ApiException.class);assertThatThrownBy(()->call("state",admin,"gateway",Map.of("revision",99,"enabled",false))).isInstanceOf(ApiException.class);}
 @Test void sourceAddressCannotBeClaimedByAnotherOrganization(){call("save",admin,"gateway",setting(0,false,null));var foreign=new Operator("other","unused",other,List.of("ADMIN"));assertThatThrownBy(()->call("save",foreign,"gateway",setting(0,true,null))).isInstanceOf(ApiException.class);}
 @Test void rolesAndForeignDeviceAreRejectedBeforeMutation(){assertThatThrownBy(()->call("save",viewer,"gateway",setting(0,true,null))).isInstanceOf(ApiException.class);device(other,"foreign-only");assertThatThrownBy(()->call("save",admin,"foreign-only",setting(0,true,null))).isInstanceOf(ApiException.class);}
 @Test void sourceMustBeCanonicalUnicastIpv4WithoutDns(){for(String ip:List.of("example.com","::1","127.0.0.1","224.0.0.1","0.0.0.0","192.000.2.1","192.0.2.1/32"))assertThatThrownBy(()->call("save",admin,"gateway",setting(0,true,ip))).isInstanceOf(IllegalArgumentException.class);}
 @Test void diagnosticOnlyPacketDoesNotInventAcceptedEventTimestamp(){call("save",admin,"gateway",setting(0,true,null));var s=(NatSourceStore)store();var b=new NatModels.Binding(org,"gateway","a","192.0.2.1",1);s.record(b,new NatSourceStore.Delta(0,1,0,0,0,1,0,0,0,Instant.now(),null,"NO_TEMPLATE",List.of("NO_TEMPLATE"))).block(WAIT);var view=s.get(org,"gateway").block(WAIT);assertThat(view.received()).isEqualTo(1);assertThat(view.lastPacketAt()).isNotNull();assertThat(view.lastEventAt()).isNull();assertThat(view.lastError()).isEqualTo("NO_TEMPLATE");assertThat(view.qualityFlags()).contains("UDP_UNAUTHENTICATED","COMPLETENESS_NOT_GUARANTEED");}
 @Test void changingExporterAddressCannotReuseThePreviousSourcesFreshness(){call("save",admin,"gateway",setting(0,true,null));((NatSourceStore)store()).record(new NatModels.Binding(org,"gateway","a","192.0.2.1",1),new NatSourceStore.Delta(1,1,1,0,0,0,0,0,0,Instant.now(),Instant.now(),null,List.of())).block(WAIT);var changed=call("save",admin,"gateway",setting(1,true,"192.0.2.2"));assertThat(changed.path("status").asString()).isEqualTo("WAITING");assertThat(changed.path("lastPacketAt").isNull()).isTrue();assertThat(changed.path("received").asLong()).isEqualTo(1);}
 @AfterEach void releaseUniqueAddress(){db.sql("DELETE FROM nat_audit_source WHERE organization_id IN (:a,:b)").bind("a",org).bind("b",other).fetch().rowsUpdated().onErrorComplete().block(WAIT);}
}
