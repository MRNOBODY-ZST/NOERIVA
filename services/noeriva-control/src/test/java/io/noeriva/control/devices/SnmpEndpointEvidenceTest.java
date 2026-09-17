package io.noeriva.control.devices;

import java.net.InetAddress;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SnmpEndpointEvidenceTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final SnmpProfiles.Profile DELL=new SnmpProfiles.Profile("dell-os9-s6100-chassis","Dell","S6100 OS9","OID");
    private static final SnmpProfiles.Profile CISCO=new SnmpProfiles.Profile("cisco-asr1002x-entity","Cisco","IOS XE","OID");
    private static DeviceProtocol.Target target(int port){return new DeviceProtocol.Target("no-dns.invalid","127.0.0.1",port,"SNMP","","2c","","","","","SYSTEM","",100,128);}
    private static DeviceProtocol.Secrets secrets(){return new DeviceProtocol.Secrets("synthetic-community",null,null,null);}
    private static DeviceProtocol.Port port(int index,String name){return new DeviceProtocol.Port("p"+index,name,null,null,"UP","UP",null,null,null,64,"1.3.6.1.2.1.2.2.1.1."+index);}
    private record Result(Map<String,String> facts,Set<String> flags,List<String> capabilities){JsonNode rows(String table){return JSON.readTree(facts.get(table+"Observations"));}}
    private Result collect(SnmpUdpFixture f,SnmpProfiles.Profile profile,List<DeviceProtocol.Port> ports)throws Exception{
        var facts=new LinkedHashMap<String,String>();var flags=new LinkedHashSet<String>();var capabilities=new ArrayList<String>();
        try(var session=new SnmpSession(target(f.port()),secrets(),System.nanoTime()+Duration.ofSeconds(7).toNanos(),flags)){
            SnmpEndpointEvidence.collect(session,profile,ports,facts,flags,capabilities);session.engineFacts(facts);
        }return new Result(facts,flags,capabilities);
    }
    private static void vlan(SnmpUdpFixture f,String index,long fdb,String name){
        f.set(SnmpEndpointEvidence.VLAN+"3."+index,new Gauge32(fdb));f.text(SnmpEndpointEvidence.VLAN_NAME+"."+index.substring(index.indexOf('.')+1),name);
    }
    private static void fdb(SnmpUdpFixture f,String index,int bridgePort,int status,boolean q){
        String base=q?SnmpEndpointEvidence.QFDB:SnmpEndpointEvidence.FDB;f.set(base+"2."+index,new Integer32(bridgePort));f.set(base+"3."+index,new Integer32(status));
    }
    private static void physical(SnmpUdpFixture f,String index,String mac,int type,int state){
        f.set(SnmpEndpointEvidence.PHYSICAL+"4."+index,OctetString.fromHexString(mac));f.set(SnmpEndpointEvidence.PHYSICAL+"6."+index,new Integer32(type));f.set(SnmpEndpointEvidence.PHYSICAL+"7."+index,new Integer32(state));
    }
    @Test void joinsDocumentedBridgePortAndSharedFdbWithoutAssumingEitherEqualsVlanOrIfIndex()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.values.clear();vlan(f,"4294967295.4",9000,"Servers");vlan(f,"4294967295.9",9000,"Shared learning domain");
            f.set(SnmpEndpointEvidence.VLAN+"4.4294967295.4",new OctetString(new byte[]{(byte)0xa0,1}));
            f.set(SnmpEndpointEvidence.VLAN+"5.4294967295.4",new OctetString(new byte[]{(byte)0x80}));
            f.set(SnmpEndpointEvidence.BRIDGE_PORT+".41",new Integer32(2102277));fdb(f,"9000.0.217.44.0.27.29",41,3,true);
            // Same real Dell MIB shape as the 2026-09-09 read-only probe; deliberately different FDB/VLAN IDs.
            physical(f,"1275070464.1.4.192.168.4.2","00:d9:2c:00:1b:1d",3,6);
            var r=collect(f,DELL,List.of(port(2102277,"fortyGigE 1/5/1"),port(1275070464,"Vlan 4")));
            assertThat(r.rows("forwarding").size()).isEqualTo(1);var row=r.rows("forwarding").get(0);
            assertThat(row.path("interfaceIndex").asInt()).isEqualTo(2102277);assertThat(row.path("bridgePort").asInt()).isEqualTo(41);
            assertThat(row.path("interfaceName").asText()).isEqualTo("fortyGigE 1/5/1");assertThat(row.path("fdbId").asLong()).isEqualTo(9000);
            assertThat(row.path("vlanIds").toString()).isEqualTo("[4,9]");assertThat(row.path("entryStatus").asText()).isEqualTo("LEARNED");
            assertThat(row.path("sourceRefs").size()).isEqualTo(5);assertThat(row.path("observedAt").asText()).isNotBlank();
            assertThat(r.rows("vlan").get(0).path("timeMark").asLong()).isEqualTo(4294967295L);
            assertThat(r.rows("vlan").get(0).path("egressBridgePorts").toString()).isEqualTo("[1,3,16]");
            assertThat(r.rows("vlan").get(1).path("egressBridgePorts").isNull()).isTrue();
            var address=r.rows("address").get(0);assertThat(address.path("address").asText()).isEqualTo("192.168.4.2");
            assertThat(address.path("entryType").asText()).isEqualTo("DYNAMIC");assertThat(address.path("neighborState").asText()).isEqualTo("UNKNOWN");
            assertThat(address.path("rawEntryType").asInt()).isEqualTo(3);assertThat(address.path("rawNeighborState").asInt()).isEqualTo(6);
            assertThat(r.facts()).containsEntry("addressTableStatus","OBSERVED").containsEntry("forwardingTableStatus","OBSERVED").containsEntry("vlanTableStatus","OBSERVED").containsEntry("dhcpTableStatus","NOT_APPLICABLE");
            assertThat(r.flags()).isEmpty();
        }
    }
    @Test void decodesIpv6AndPreservesLocalInvalidAndIncompleteStatesForConsumerFiltering()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.values.clear();byte[] bytes=InetAddress.ofLiteral("2001:db8::2").getAddress();StringJoiner index=new StringJoiner(".","7.2.16.","");for(byte b:bytes)index.add(Integer.toString(Byte.toUnsignedInt(b)));
            physical(f,index.toString(),"00:11:22:33:44:66",3,2);physical(f,"7.1.4.192.0.2.1","00:11:22:33:44:55",5,1);
            physical(f,"7.1.4.192.0.2.9","00:11:22:33:44:99",2,7);
            var r=collect(f,DELL,List.of(port(7,"Vlan 4")));assertThat(r.rows("address").size()).isEqualTo(3);
            assertThat(r.rows("address").toString()).contains("2001:db8:0:0:0:0:0:2","LOCAL","INVALID","INCOMPLETE","STALE");
            assertThat(r.facts().get("endpointEvidenceLifetimeBasis")).contains("cache_age_not_reachability");
        }
    }
    @Test void legacyFallbackRetainsUnknownVlanAndUnresolvedBridgeMapping()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.values.clear();fdb(f,"0.17.34.51.68.102",41,3,false);
            f.set(SnmpEndpointEvidence.ARP+"2.7.192.0.2.2",OctetString.fromHexString("00:11:22:33:44:66"));f.set(SnmpEndpointEvidence.ARP+"4.7.192.0.2.2",new Integer32(4));
            var r=collect(f,DELL,List.of(port(41,"This is not necessarily bridge port 41")));
            var row=r.rows("forwarding").get(0);assertThat(row.path("source").asText()).isEqualTo("BRIDGE-MIB");
            assertThat(row.path("interfaceIndex").isNull()).isTrue();assertThat(row.path("interfaceName").isNull()).isTrue();assertThat(row.path("vlanIds").isEmpty()).isTrue();assertThat(row.path("fdbId").isNull()).isTrue();
            assertThat(r.facts()).containsEntry("forwardingTableStatus","INCOMPLETE");assertThat(r.flags()).contains("SNMP_ENDPOINT_BRIDGE_MAPPING_INCOMPLETE");
            assertThat(r.rows("address").get(0).path("source").asText()).isEqualTo("RFC1213/ipNetToMedia");
            assertThat(r.rows("address").get(0).path("entryType").asText()).isEqualTo("STATIC");
        }
    }
    @Test void ciscoDhcpUsesTypedAddressFixedSixOctetMacIndexAndExplicitLeaseAndStatus()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.values.clear();String index="4.0.17.34.51.68.102";
            f.set(SnmpEndpointEvidence.DHCP+"4."+index,new OctetString(InetAddress.ofLiteral("192.0.2.20").getAddress()));
            f.set(SnmpEndpointEvidence.DHCP+"3."+index,new Integer32(1));f.set(SnmpEndpointEvidence.DHCP+"5."+index,new Integer32(700));
            f.set(SnmpEndpointEvidence.DHCP+"6."+index,new Gauge32(3600));f.set(SnmpEndpointEvidence.DHCP+"7."+index,new Integer32(1));f.text(SnmpEndpointEvidence.DHCP+"8."+index,"observed-host");
            var r=collect(f,CISCO,List.of(port(700,"Gi1/0/7")));var row=r.rows("dhcp").get(0);
            assertThat(row.path("mac").asText()).isEqualTo("00:11:22:33:44:66");assertThat(row.path("address").asText()).isEqualTo("192.0.2.20");
            assertThat(row.path("interfaceIndex").asInt()).isEqualTo(700);assertThat(row.path("vlanId").asInt()).isEqualTo(4);
            assertThat(row.path("leaseSeconds").asLong()).isEqualTo(3600);assertThat(row.path("rowStatus").asText()).isEqualTo("ACTIVE");
            assertThat(row.path("hostname").asText()).isEqualTo("observed-host");assertThat(row.has("expiresAt")).isFalse();
            assertThat(r.facts()).containsEntry("dhcpTableStatus","OBSERVED");assertThat(r.capabilities()).contains("dhcp-snooping-bindings");
        }
    }
    @Test void malformedAddressesMulticastMacAndOutOfRangeOctetsCannotCreateEndpointEvidence()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.values.clear();physical(f,"7.1.4.192.0.2.256","00:11:22:33:44:66",3,1);
            physical(f,"7.1.4.192.0.2.3","01:11:22:33:44:66",3,1);physical(f,"7.1.4.0.0.0.0","00:11:22:33:44:66",3,1);
            fdb(f,"1.4294967295.17.34.51.68.102",7,3,true);
            String bad="4.4294967295.17.34.51.68.102";f.set(SnmpEndpointEvidence.DHCP+"4."+bad,new OctetString(new byte[]{(byte)192,0,2,1}));
            var r=collect(f,CISCO,List.of());assertThat(r.rows("address").isEmpty()).isTrue();assertThat(r.rows("forwarding").isEmpty()).isTrue();assertThat(r.rows("dhcp").isEmpty()).isTrue();
            assertThat(r.flags()).contains("SNMP_ENDPOINT_INVALID_EVIDENCE");assertThat(r.facts()).containsEntry("addressTableStatus","INCOMPLETE").containsEntry("forwardingTableStatus","INCOMPLETE").containsEntry("dhcpTableStatus","INCOMPLETE");
        }
    }
    @Test void boundedPaginationReportsTruncationAndNeverClaimsAnEmptyCompleteTable()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.values.clear();for(int i=1;i<=129;i++)physical(f,"7.1.4.192.0.2."+i,"00:11:22:33:44:66",3,6);
            var r=collect(f,DELL,List.of());assertThat(r.rows("address").size()).isEqualTo(128);assertThat(r.facts()).containsEntry("addressTableStatus","INCOMPLETE");
            assertThat(r.flags()).contains("SNMP_ENDPOINT_ROW_LIMIT");assertThat(Integer.parseInt(r.facts().get("snmpVariableCount"))).isLessThanOrEqualTo(1100);
            assertThat(f.requests.get()).isLessThan(40);
        }
    }
    @Test void bmcProfilesPerformNoEndpointRequestsAndUnsupportedNetworkTablesStayUnknown()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.values.clear();var bmc=collect(f,new SnmpProfiles.Profile("huawei-server-bmc","Huawei","iMana","OID"),List.of());
            assertThat(f.requests.get()).isZero();assertThat(bmc.facts()).containsEntry("addressTableStatus","NOT_APPLICABLE").containsEntry("dhcpTableStatus","NOT_APPLICABLE");
            var r=collect(f,CISCO,List.of());assertThat(r.rows("address").isEmpty()).isTrue();assertThat(r.rows("forwarding").isEmpty()).isTrue();
            assertThat(r.facts()).containsEntry("addressTableStatus","EMPTY_OR_UNSUPPORTED").containsEntry("forwardingTableStatus","EMPTY_OR_UNSUPPORTED").containsEntry("dhcpTableStatus","EMPTY_OR_UNSUPPORTED");
        }
    }
    @Test void auxiliaryVariableBudgetStopsAndRestoresWithoutPoisoningRequiredReads()throws Exception{
        try(var f=new SnmpUdpFixture()){
            var flags=new LinkedHashSet<String>();try(var s=new SnmpSession(target(f.port()),secrets(),System.nanoTime()+Duration.ofSeconds(5).toNanos(),flags)){
                try(var budget=s.auxiliaryBudget(1000,3)){s.walkSuffixes("1.3.6.1.2.1.2.2.1",2,2,128,null);assertThat(s.remainingVariables()).isZero();}
                assertThat(flags).contains("SNMP_ENDPOINT_BUDGET").doesNotContain("SNMP_VARIABLE_BUDGET");
                assertThat(s.get(List.of("1.3.6.1.2.1.1.5.0"),true)).hasSize(1);
            }
        }
    }
    @Test void timeoutStopsOptionalTablesImmediatelyAndLeavesSkippedPhasesExplicit()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.silent=true;long start=System.nanoTime();var r=collect(f,CISCO,List.of());assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
            assertThat(f.requests.get()).isEqualTo(1);assertThat(r.facts()).containsEntry("vlanTableStatus","INCOMPLETE").containsEntry("forwardingTableStatus","SKIPPED_BUDGET").containsEntry("addressTableStatus","SKIPPED_BUDGET");
            assertThat(r.flags()).contains("SNMP_TIMEOUT");
        }
    }
    @Test void approachingGlobalDeadlineSkipsEndpointReadsAndPreservesTheTelemetryBudget()throws Exception{
        try(var f=new SnmpUdpFixture()){
            var facts=new LinkedHashMap<String,String>();var flags=new LinkedHashSet<String>();try(var s=new SnmpSession(target(f.port()),secrets(),System.nanoTime()+Duration.ofMillis(600).toNanos(),flags)){
                SnmpEndpointEvidence.collect(s,DELL,List.of(),facts,flags,new ArrayList<>());
            }assertThat(f.requests.get()).isZero();assertThat(facts).containsEntry("addressTableStatus","SKIPPED_BUDGET");assertThat(flags).isEmpty();
        }
    }
}
