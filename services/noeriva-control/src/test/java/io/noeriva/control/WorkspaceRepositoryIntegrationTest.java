package io.noeriva.control;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static io.noeriva.control.Models.*;
import static io.noeriva.control.WorkspaceModels.*;
import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class WorkspaceRepositoryIntegrationTest {
    private static final Duration TIMEOUT=Duration.ofSeconds(30);
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("workspace_test").withUsername("workspace_test").withPassword("isolated-container-test-only");
    private static DatabaseClient db;private static MySqlRepository inventory;private static MySqlWorkspaceReadRepository workspace;
    private String org,other;private Instant asOf;
    @BeforeAll static void database(){
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
        var factory=ConnectionFactories.get(ConnectionFactoryOptions.builder().option(DRIVER,"mysql").option(HOST,MYSQL.getHost()).option(PORT,MYSQL.getMappedPort(3306)).option(DATABASE,MYSQL.getDatabaseName()).option(USER,MYSQL.getUsername()).option(PASSWORD,MYSQL.getPassword()).option(Option.valueOf("sslMode"),"DISABLED").option(Option.valueOf("allowPublicKeyRetrieval"),true).build());
        db=DatabaseClient.create(factory);var json=JsonMapper.builder().build();
        var history=new HistoryStore(WebClient.builder(),new MockEnvironment().withProperty("NOERIVA_CLICKHOUSE_URL","http://127.0.0.1:9").withProperty("NOERIVA_CLICKHOUSE_PASSWORD","unused-test-dependency"),json);
        inventory=new MySqlRepository(db,TransactionalOperator.create(new R2dbcTransactionManager(factory)),json,history);workspace=new MySqlWorkspaceReadRepository(db,json);
    }
    @BeforeEach void organizations(){
        org="ws-"+UUID.randomUUID();other="ws-"+UUID.randomUUID();asOf=Instant.now().truncatedTo(ChronoUnit.SECONDS);
        db.sql("INSERT INTO organization(id,name) VALUES(:org,'Workspace A'),(:other,'Workspace B')").bind("org",org).bind("other",other).fetch().rowsUpdated().block(TIMEOUT);
        db.sql("INSERT INTO site(organization_id,id,name,timezone) VALUES(:org,'a','Site A','UTC'),(:org,'b','Site B','UTC'),(:other,'a','Private Site','UTC')").bind("org",org).bind("other",other).fetch().rowsUpdated().block(TIMEOUT);
    }
    @Test void sourcePaginationFiltersAndTimeDoNotMixTenantsOrDeviceSources(){
        Device first=create(org,"alpha","a"),second=create(org,"beta","a"),privateDevice=create(other,"private","a");
        observe(org,first,"host","HEALTHY",asOf.minusSeconds(10),Map.of("cpu_percent",42.0));
        observe(org,first,"bmc","CRITICAL",asOf.minusSeconds(181),Map.of("temperature_celsius",80.0));
        observe(org,second,"network","HEALTHY",asOf.minusSeconds(180),Map.of("bandwidth_rx_bps",123.0));
        observe(other,privateDevice,"host","CRITICAL",asOf.minusSeconds(10),Map.of("cpu_percent",99.0));
        var all=workspace.monitoring(org,100,new SourceCursor("",""),"","","","",asOf).collectList().block(TIMEOUT);
        assertThat(all).hasSize(3).allMatch(s->!s.deviceId().equals(privateDevice.id()));
        assertThat(workspace.trafficSource(org,"a",asOf).block(TIMEOUT).deviceId()).isEqualTo(second.id());
        assertThat(workspace.trafficSource(org,"b",asOf).block(TIMEOUT)).isNull();
        var firstPage=workspace.monitoring(org,1,new SourceCursor("",""),"","","","",asOf).collectList().block(TIMEOUT);
        var remaining=workspace.monitoring(org,100,SourceCursor.decode(SourceCursor.encode(firstPage.getFirst())),"","","","",asOf).collectList().block(TIMEOUT);
        assertThat(remaining).containsExactlyElementsOf(all.subList(1,3));
        assertThat(workspace.monitoring(org,100,new SourceCursor("",""),"alpha","a",first.id(),"STALE",asOf).collectList().block(TIMEOUT))
            .singleElement().satisfies(s->{assertThat(s.sourceId()).isEqualTo("bmc");assertThat(s.metrics()).containsEntry("temperature_celsius",80.0);assertThat(s.freshness()).isEqualTo("STALE");});
        assertThat(workspace.monitoring(org,100,new SourceCursor("",""),"","","","FRESH",asOf).collectList().block(TIMEOUT)).hasSize(2);
        assertThat(workspace.monitoring(other,100,new SourceCursor("",""),"","",first.id(),"",asOf).collectList().block(TIMEOUT)).isEmpty();
    }
    @Test void interfaceDirectoryUsesLiteralPrefixAndUnknownIsNotOnline(){
        Device device=create(org,"switch","a"),privateDevice=create(other,"private","a");
        var first=inventory.registerInterface(org,"actor",device.id(),new RegisterInterface("uplink%one","18446744073709551615",null)).block(TIMEOUT);
        inventory.registerInterface(org,"actor",device.id(),new RegisterInterface("uplink-other","1000000000",null)).block(TIMEOUT);
        inventory.registerInterface(other,"actor",privateDevice.id(),new RegisterInterface("uplink%private","1",null)).block(TIMEOUT);
        var rows=workspace.interfaces(org,100,"","uplink%","a","",asOf).collectList().block(TIMEOUT);
        assertThat(rows).singleElement().satisfies(i->{assertThat(i.id()).isEqualTo(first.id());assertThat(i.deviceName()).isEqualTo("switch");assertThat(i.speedBps()).isEqualTo("18446744073709551615");assertThat(i.operStatus()).isEqualTo("UNKNOWN");assertThat(i.deviceLastSeen()).isNull();assertThat(i.deviceFreshness()).isEqualTo("MISSING");});
        var all=workspace.interfaces(org,100,"","","","",asOf).collectList().block(TIMEOUT);
        assertThat(workspace.interfaces(org,100,InterfaceCursor.encode(all.getFirst(),InterfaceCursor.scope(org,"","","")),"","","",asOf).collectList().block(TIMEOUT)).containsExactly(all.get(1));
        assertThat(workspace.interfaces(org,100,"","","b","",asOf).collectList().block(TIMEOUT)).isEmpty();
        assertThat(workspace.interfaces(other,100,"","","",device.id(),asOf).collectList().block(TIMEOUT)).isEmpty();
    }
    @Test void alphabeticalInterfacePagesAreStableScopedAndGeneratedNamesFollowUpdates(){
        var firstDevice=create(org,"first-switch","a");var secondDevice=create(org,"second-switch","b");
        var foreign=create(other,"foreign-switch","a");
        var expected=new ArrayList<NetworkInterface>();
        for(int i=41;i>=1;i--)expected.add(inventory.registerInterface(org,"actor",i%2==0?firstDevice.id():secondDevice.id(),new RegisterInterface((i%3==0?"te":"Te")+"0/3/"+i,"10000000000",null)).block(TIMEOUT));
        expected.add(inventory.registerInterface(org,"actor",firstDevice.id(),new RegisterInterface("Te0/3/2","10000000000",null)).block(TIMEOUT));
        inventory.registerInterface(other,"actor",foreign.id(),new RegisterInterface("Te0/3/0-private","1",null)).block(TIMEOUT);
        var wanted=expected.stream().sorted(Comparator.comparing((NetworkInterface i)->i.name().toLowerCase(Locale.ROOT)).thenComparing(NetworkInterface::id)).map(NetworkInterface::id).toList();
        var actual=new ArrayList<String>();String cursor="";int pages=0;
        do{
            var batch=workspace.interfaces(org,7,cursor,"","","",asOf).collectList().block(TIMEOUT);
            if(batch.isEmpty())break;
            actual.addAll(batch.stream().map(WorkspaceInterface::id).toList());
            cursor=InterfaceCursor.encode(batch.getLast(),InterfaceCursor.scope(org,"","",""));
            assertThat(++pages).isLessThan(10);
        }while(true);
        assertThat(actual).containsExactlyElementsOf(wanted).doesNotHaveDuplicates();assertThat(pages).isGreaterThan(1);
        var page=workspace.interfaces(org,1,"","","","",asOf).collectList().block(TIMEOUT);
        String continuation=InterfaceCursor.encode(page.getFirst(),InterfaceCursor.scope(org,"","",""));
        assertThatThrownBy(()->workspace.interfaces(other,7,continuation,"","","",asOf)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->workspace.interfaces(org,7,continuation,"Te","","",asOf)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->workspace.interfaces(org,7,continuation,"","a","",asOf)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->workspace.interfaces(org,7,continuation,"","",firstDevice.id(),asOf)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->workspace.interfaces(org,7,"old-uuid-cursor","","","",asOf)).isInstanceOf(IllegalArgumentException.class);
        var selected=workspace.interfaces(org,100,"","","a",firstDevice.id(),asOf).collectList().block(TIMEOUT);
        assertThat(selected).allMatch(i->i.deviceId().equals(firstDevice.id())).isSortedAccordingTo(InterfaceCursor.order());
        String selectedCursor=InterfaceCursor.encode(selected.getFirst(),InterfaceCursor.scope(org,"","a",firstDevice.id()));
        assertThat(workspace.interfaces(org,100,selectedCursor,"","a",firstDevice.id(),asOf).collectList().block(TIMEOUT)).containsExactlyElementsOf(selected.subList(1,selected.size()));
        var changed=expected.getFirst();
        db.sql("UPDATE network_interface SET payload=JSON_SET(payload,'$.name','Aa-renamed') WHERE organization_id=:org AND id=:id").bind("org",org).bind("id",changed.id()).fetch().rowsUpdated().block(TIMEOUT);
        assertThat(workspace.interfaces(org,1,"","","","",asOf).blockFirst(TIMEOUT).id()).isEqualTo(changed.id());
        var plan=db.sql("EXPLAIN SELECT n.payload FROM network_interface n FORCE INDEX(interface_workspace_name) STRAIGHT_JOIN device d ON d.organization_id=n.organization_id AND d.id=n.device_id WHERE n.organization_id=:org AND (n.name_sort>'te0/3/2' OR(n.name_sort='te0/3/2' AND n.id>'0')) ORDER BY n.name_sort,n.id LIMIT 8").bind("org",org).fetch().all().collectList().block(TIMEOUT);
        assertThat(plan.toString()).contains("interface_workspace_name").doesNotContain("Using filesort");
    }
    @Test void siteCountsIncludeUnknownAndEmptySitesAndPrioritySortsBySeverity(){
        Device unknown=create(org,"unknown","a"),healthy=create(org,"healthy","a"),warning=create(org,"warning","a"),critical=create(org,"critical","a");
        observe(org,healthy,"host","HEALTHY",asOf.minusSeconds(10),Map.of());observe(org,warning,"host","WARNING",asOf.minusSeconds(10),Map.of());observe(org,critical,"bmc","CRITICAL",asOf.minusSeconds(181),Map.of());
        var totals=workspace.totals(org,"",asOf).block(TIMEOUT);
        assertThat(totals).extracting(Overview::devices,Overview::healthy,Overview::warning,Overview::critical,Overview::unknown,Overview::stale,Overview::activeAlerts).containsExactly(4L,1L,1L,0L,2L,1L,2L);
        var sites=workspace.siteHealth(org,"",asOf).collectList().block(TIMEOUT);
        assertThat(sites).hasSize(2);assertThat(sites.getFirst()).extracting(SiteHealth::unknown,SiteHealth::stale).containsExactly(2L,1L);assertThat(sites.get(1)).extracting(SiteHealth::devices,SiteHealth::unknown,SiteHealth::stale).containsExactly(0L,0L,0L);
        assertThat(workspace.priorityDevices(org,"",10).map(Device::id).collectList().block(TIMEOUT)).startsWith(warning.id()).containsExactlyInAnyOrder(critical.id(),warning.id(),unknown.id());
        assertThat(workspace.priorityDevices(org,"b",10).collectList().block(TIMEOUT)).isEmpty();
        assertThat(workspace.totals(org,"b",asOf).block(TIMEOUT).devices()).isZero();
        assertThat(workspace.totals(other,"",asOf).block(TIMEOUT).devices()).isZero();
    }
    @Test void disabledManagedSourcesDoNotReduceCurrentCoverageOrReappearInWorkspace(){
        var device=create(org,"snmp-switch","a");
        managedConnection(device,"snmp",true,"");managedConnection(device,"ssh",false,"");
        managedObservation(device,"network","HEALTHY",asOf.minusSeconds(10),Map.of("bandwidth_rx_bps",100d));
        managedObservation(device,"ssh","CRITICAL",asOf.minusSeconds(3600),Map.of("bandwidth_rx_bps",200d));
        var controller=new ControlController(inventory,null,null,new MockEnvironment(),null);
        var summary=controller.summary(new SecurityConfiguration.Operator("admin","unused",org,List.of("ADMIN")),device.id()).block(TIMEOUT);
        assertThat(summary.sourceFreshness()).isEqualTo("FRESH");assertThat(summary.coverage()).isEqualTo(1d);assertThat(summary.qualityFlags()).isEmpty();
        assertThat(summary.sources()).extracting(SourceState::sourceId).containsExactly("network");
        assertThat(workspace.monitoring(org,100,new SourceCursor("",""),"","",device.id(),"",asOf).collectList().block(TIMEOUT)).extracting(MonitoringSource::sourceId).containsExactly("network");
        // Even a newer disabled critical checkpoint cannot override healthy SNMP or traffic selection.
        inventory.project(org,new Observation("poll_"+UUID.randomUUID(),device.id(),"ssh","DeviceSummaryObserved","new-epoch",2,asOf.minusSeconds(1),"CRITICAL",Map.of("bandwidth_rx_bps",999d),"retained disabled evidence")).block(TIMEOUT);
        assertThat(inventory.device(org,device.id()).block(TIMEOUT).health()).isEqualTo("HEALTHY");
        assertThat(workspace.trafficSource(org,"a",asOf).block(TIMEOUT).sourceId()).isEqualTo("network");
        assertThat(workspace.totals(org,"",asOf).block(TIMEOUT)).extracting(Overview::healthy,Overview::critical).containsExactly(1L,0L);
        assertThat(workspace.siteHealth(org,"a",asOf).blockFirst(TIMEOUT).healthy()).isEqualTo(1);
        assertThat(workspace.priorityDevices(org,"",10).collectList().block(TIMEOUT)).isEmpty();
        assertThat(db.sql("SELECT COUNT(*) n FROM source_current WHERE organization_id=:org AND device_id=:device").bind("org",org).bind("device",device.id()).map((r,m)->r.get("n",Long.class)).one().block(TIMEOUT)).isEqualTo(2);
        assertThat(inventory.alerts(org,device.id(),"",100).collectList().block(TIMEOUT)).isNotEmpty();
    }
    @Test void unmanagedAndExternalSourcesRemainCurrentEvenWithAnUnrelatedDisabledConnection(){
        var legacy=create(org,"unmanaged-source","a");managedObservation(legacy,"ssh","WARNING",asOf.minusSeconds(10),Map.of());
        assertThat(inventory.sources(org,legacy.id()).collectList().block(TIMEOUT)).hasSize(1);
        var external=create(org,"external-collector","a");managedConnection(external,"ssh",false,"");managedConnection(external,"redfish",false,"");
        observe(org,external,"ssh","HEALTHY",asOf.minusSeconds(10),Map.of());
        managedObservation(external,"host","HEALTHY",asOf.minusSeconds(10),Map.of());
        managedObservation(external,"bmc","CRITICAL",asOf.minusSeconds(10),Map.of());
        assertThat(inventory.sources(org,external.id()).collectList().block(TIMEOUT)).extracting(SourceState::sourceId).containsExactlyInAnyOrder("ssh","host");
        assertThat(inventory.device(org,external.id()).block(TIMEOUT).health()).isEqualTo("HEALTHY");
        assertThat(workspace.monitoring(org,100,new SourceCursor("",""),"","","","",asOf).collectList().block(TIMEOUT)).hasSize(3);
        assertThat(workspace.monitoring(other,100,new SourceCursor("",""),"","",external.id(),"",asOf).collectList().block(TIMEOUT)).isEmpty();
    }
    @Test void disabledHistoricalSshAndNeverSuccessfulSnmpPreserveOfflineAndOriginalLastSeen(){
        var device=create(org,"offline-imana","a");Instant lastSeen=Instant.parse("2026-09-07T06:41:49Z");
        managedObservation(device,"ssh","HEALTHY",lastSeen,Map.of());
        managedConnection(device,"ssh",false,"");managedConnection(device,"snmp",true,"SNMP_READ_FAILED");
        var result=inventory.device(org,device.id()).block(TIMEOUT);
        assertThat(result.health()).isEqualTo("UNKNOWN");assertThat(result.availability()).isEqualTo("OFFLINE");assertThat(result.lastSeen()).isEqualTo(lastSeen);
        var summary=new ControlController(inventory,null,null,new MockEnvironment(),null).summary(new SecurityConfiguration.Operator("admin","unused",org,List.of("ADMIN")),device.id()).block(TIMEOUT);
        assertThat(summary.sourceFreshness()).isEqualTo("MISSING");assertThat(summary.coverage()).isZero();assertThat(summary.sources()).isEmpty();assertThat(summary.device().availability()).isEqualTo("OFFLINE");
        assertThat(workspace.totals(org,"",asOf).block(TIMEOUT)).extracting(Overview::healthy,Overview::unknown).containsExactly(0L,1L);
    }
    private void managedConnection(Device device,String slot,boolean enabled,String error){
        db.sql("INSERT INTO device_connection(organization_id,device_id,slot,revision,enabled,settings,ciphertext,status,next_poll_at,source_epoch,error_code,last_attempt_at) VALUES(:org,:device,:slot,1,:enabled,JSON_OBJECT(),'unread-fixture','NOT_TESTED',UTC_TIMESTAMP(6),'fixture',:error,UTC_TIMESTAMP(6))").bind("org",org).bind("device",device.id()).bind("slot",slot).bind("enabled",enabled).bind("error",error).fetch().rowsUpdated().block(TIMEOUT);
    }
    private void managedObservation(Device device,String source,String health,Instant at,Map<String,Double> metrics){inventory.project(org,new Observation("poll_"+UUID.randomUUID(),device.id(),source,"DeviceSummaryObserved","epoch",1,at,health,metrics,"Managed source fixture")).block(TIMEOUT);}
    private Device create(String organization,String name,String site){return inventory.create(organization,"operator",new CreateDevice(name,"HOST",site,"Test","Test","192.0.2.1")).block(TIMEOUT);}
    private void observe(String organization,Device device,String source,String health,Instant at,Map<String,Double> metrics){inventory.project(organization,new Observation(UUID.randomUUID().toString(),device.id(),source,"DeviceSummaryObserved","epoch",1,at,health,metrics,"Integration source")).block(TIMEOUT);}
}
