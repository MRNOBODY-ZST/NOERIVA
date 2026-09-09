package io.noeriva.control.devices;

import java.net.InetAddress;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SnmpNeighborsTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static DeviceProtocol.Target target(int port){return new DeviceProtocol.Target("must-not-resolve.invalid","127.0.0.1",port,"SNMP","","2c","","","","","SYSTEM","",100,128);}
    private static DeviceProtocol.Secrets secrets(){return new DeviceProtocol.Secrets("synthetic-community",null,null,null);}
    private static DeviceProtocol.Port port(int index,String name,String mac){return new DeviceProtocol.Port("p"+index,name,mac,null,"UP","UP",null,null,null,64,"1.3.6.1.2.1.2.2.1.1."+index);}
    private record Result(Map<String,String> facts,Set<String> flags,List<String> capabilities){JsonNode rows(){return JSON.readTree(facts.get("neighborObservations"));}}
    private Result collect(SnmpUdpFixture fixture,List<DeviceProtocol.Port> ports)throws Exception{
        var facts=new LinkedHashMap<String,String>();var flags=new LinkedHashSet<String>();var capabilities=new ArrayList<String>();
        try(var session=new SnmpSession(target(fixture.port()),secrets(),System.nanoTime()+Duration.ofSeconds(5).toNanos(),flags)){
            SnmpNeighbors.collect(session,ports,facts,flags,capabilities);session.engineFacts(facts);
        }
        return new Result(facts,flags,capabilities);
    }
    private void lldp(SnmpUdpFixture f,String index,String name,String remote){
        f.set(SnmpNeighbors.REM+"4."+index,new Integer32(4));
        f.set(SnmpNeighbors.REM+"5."+index,OctetString.fromHexString("00:11:22:33:44:66"));
        f.set(SnmpNeighbors.REM+"6."+index,new Integer32(5));
        f.text(SnmpNeighbors.REM+"7."+index,remote);
        f.text(SnmpNeighbors.REM+"9."+index,name);
    }
    private void local(SnmpUdpFixture f,int number,int type,String name){f.set(SnmpNeighbors.LOC+"2."+number,new Integer32(type));f.text(SnmpNeighbors.LOC+"3."+number,name);}
    private void management(SnmpUdpFixture f,String row,int family,String ip)throws Exception{
        byte[] bytes=InetAddress.ofLiteral(ip).getAddress();StringBuilder index=new StringBuilder(row+"."+family+"."+bytes.length);
        for(byte b:bytes)index.append('.').append(Byte.toUnsignedInt(b));f.set(SnmpNeighbors.MAN+"."+index,new Integer32(2));
    }
    @Test void joinsFullUnsignedLldpIndexesAndDecodesIpv4Ipv6WithoutInventingLifetime()throws Exception{
        try(var f=new SnmpUdpFixture()){
            String first="4294967295.71.1",second="17.71.2";
            lldp(f,first,"remote-a","Te0/3/0");lldp(f,second,"remote-b","Te0/3/1");local(f,71,5,"Ethernet1/1");
            management(f,first,1,"192.0.2.10");management(f,first,2,"2001:db8::10");management(f,second,1,"192.0.2.20");
            var r=collect(f,List.of(port(7,"Ethernet1/1","00:11:22:33:44:55")));
            assertThat(r.rows().size()).isEqualTo(2);assertThat(r.facts()).containsEntry("lldpStatus","OBSERVED");
            JsonNode a=null,b=null;for(var n:r.rows()){if(n.path("name").asText().equals("remote-a"))a=n;else b=n;}
            assertThat(a).isNotNull();assertThat(b).isNotNull();
            assertThat(a.path("address").asText()).isEqualTo("192.0.2.10");assertThat(b.path("address").asText()).isEqualTo("192.0.2.20");
            assertThat(a.path("managementAddresses").size()).isEqualTo(2);
            assertThat(a.path("interfaceName").asText()).isEqualTo("Ethernet1/1");
            assertThat(a.path("localPortNumber").asInt()).isEqualTo(71);assertThat(a.path("interfaceIndex").asInt()).isEqualTo(7);
            assertThat(a.path("mac").asText()).isEqualTo("00:11:22:33:44:66");
            assertThat(a.path("sourceRef").asText()).endsWith(first);assertThat(a.path("transport").asText()).isEqualTo("SNMP");
            assertThat(a.path("ttlSeconds").isNull()).isTrue();assertThat(a.path("ageMinutes").isNull()).isTrue();
            assertThat(r.capabilities()).contains("lldp-neighbors");
        }
    }
    @Test void reproducesIosXeLldpEvidenceWithDistinctRuijieAndUnnamedDellNeighbors()throws Exception{
        // Sanitized read-only 2026-09-09 IOS XE MIB evidence, replayed over local UDP.
        try(var f=new SnmpUdpFixture()){
            lldp(f,"0.8.1","SHHS-HYKJDL-A804-2","Xs0/2");
            f.set(SnmpNeighbors.REM+"5.0.8.1",OctetString.fromHexString("f0:74:8d:cc:5b:d1"));
            lldp(f,"0.9.2","","TenGigabitEthernet 1/5/1");
            f.set(SnmpNeighbors.REM+"5.0.9.2",OctetString.fromHexString("0c:29:ef:b3:5f:20"));
            local(f,8,5,"Te0/1/0");local(f,9,5,"Te0/3/0");management(f,"0.8.1",1,"127.0.0.1");
            var r=collect(f,List.of(port(8,"TenGigabitEthernet0/1/0","00:11:22:33:44:55"),port(9,"TenGigabitEthernet0/3/0","00:11:22:33:44:56")));
            assertThat(r.rows().size()).isEqualTo(2);
            var ruijie=r.rows().get(0);var dell=r.rows().get(1);
            assertThat(ruijie.path("name").asText()).isEqualTo("SHHS-HYKJDL-A804-2");
            assertThat(ruijie.path("address").asText()).isEqualTo("127.0.0.1");
            assertThat(dell.path("chassisId").asText()).isEqualTo("0c:29:ef:b3:5f:20");
            assertThat(dell.path("interfaceName").asText()).isEqualTo("Te0/3/0");
            assertThat(dell.path("portId").asText()).isEqualTo("TenGigabitEthernet 1/5/1");
            assertThat(dell.path("address").isNull()).isTrue();assertThat(dell.path("name").isNull()).isTrue();
            assertThat(r.facts()).containsEntry("lldpStatus","OBSERVED").containsEntry("cdpStatus","EMPTY_OR_UNSUPPORTED");
        }
    }
    @Test void dellSingleStackUnitUsesDocumentedMacButNeverGuessesForMultipleUnitsOrMalformedMac()throws Exception{
        try(var f=new SnmpUdpFixture();var driver=new SnmpDriver()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.6027.1.3.28"));
            String base="1.3.6.1.4.1.6027.3.26.1.3.4.1.";
            f.set(base+"8.1",new Integer32(1));f.set(base+"16.1",OctetString.fromHexString("0c:29:ef:b3:5f:20"));
            var reading=driver.read(target(f.port()),secrets()).block(Duration.ofSeconds(28));
            assertThat(reading.facts()).containsEntry("chassisMacAddress","0c:29:ef:b3:5f:20").containsEntry("chassisMacSource",base+"16.1");
            f.set(base+"16.1",OctetString.fromHexString("00:0c:29:ef:b3:5f:20"));
            reading=driver.read(target(f.port()),secrets()).block(Duration.ofSeconds(28));assertThat(reading.facts()).doesNotContainKey("chassisMacAddress");
            f.set(base+"16.1",OctetString.fromHexString("0c:29:ef:b3:5f:20"));f.set(base+"8.2",new Integer32(1));
            reading=driver.read(target(f.port()),secrets()).block(Duration.ofSeconds(28));assertThat(reading.facts()).doesNotContainKey("chassisMacAddress");
        }
    }
    @Test void absentLocalMappingDoesNotAssumeLldpLocalPortNumberIsIfIndex()throws Exception{
        try(var f=new SnmpUdpFixture()){
            lldp(f,"0.7.1","remote","Te0/3/0");
            var r=collect(f,List.of(port(7,"Ethernet1/1","00:11:22:33:44:55")));
            assertThat(r.rows().isEmpty()).isTrue();assertThat(r.flags()).contains("SNMP_LLDP_NEIGHBOR_IDENTITY_INCOMPLETE");
            assertThat(r.facts()).containsEntry("lldpStatus","INCOMPLETE");
        }
    }
    @Test void localMacRequiresUniqueInterfaceMatchAndLocalNumericIdIsNotIfIndex()throws Exception{
        try(var f=new SnmpUdpFixture()){
            lldp(f,"0.70.1","remote","Te0/3/0");f.set(SnmpNeighbors.LOC+"2.70",new Integer32(3));f.set(SnmpNeighbors.LOC+"3.70",OctetString.fromHexString("00:11:22:33:44:55"));
            var p=port(7,"Ethernet1/1","00:11:22:33:44:55");
            var r=collect(f,List.of(p));assertThat(r.rows().get(0).path("interfaceIndex").asInt()).isEqualTo(7);
            r=collect(f,List.of(p,port(8,"Ethernet1/2",p.macAddress())));assertThat(r.rows().isEmpty()).isTrue();
            local(f,70,7,"77");r=collect(f,List.of(p));assertThat(r.rows().get(0).path("interfaceName").asText()).isEqualTo("77");
            assertThat(r.rows().get(0).path("interfaceIndex").isNull()).isTrue();
        }
    }
    @Test void rejectsMalformedSparseRowsAndInvalidAddressIndexesWithoutZipping()throws Exception{
        try(var f=new SnmpUdpFixture()){
            lldp(f,"0.7.1","remote-a","port-a");lldp(f,"0.7.2","remote-b","port-b");local(f,7,5,"Ethernet1/1");
            f.values.remove(new OID(SnmpNeighbors.REM+"7.0.7.1"));
            f.set(SnmpNeighbors.REM+"5.0.7.3.99",new OctetString("malformed-index"));
            f.set(SnmpNeighbors.MAN+".0.7.2.1.4.192.0.2.256",new Integer32(2));
            var r=collect(f,List.of(port(7,"Ethernet1/1","00:11:22:33:44:55")));
            assertThat(r.rows().size()).isEqualTo(1);assertThat(r.rows().get(0).path("name").asText()).isEqualTo("remote-b");
            assertThat(r.rows().get(0).path("portId").asText()).isEqualTo("port-b");assertThat(r.rows().get(0).path("address").isNull()).isTrue();
            assertThat(r.flags()).contains("SNMP_UNEXPECTED_INDEX","SNMP_LLDP_INVALID_ADDRESS_INDEX","SNMP_LLDP_NEIGHBOR_IDENTITY_INCOMPLETE");
        }
    }
    @Test void cdpSupportsMultipleNeighborsPerIfIndexAndTypedBinaryManagementAddresses()throws Exception{
        try(var f=new SnmpUdpFixture()){
            for(int i=1;i<=2;i++){
                f.text(SnmpNeighbors.CDP+"6.7."+i,"device-"+i);f.text(SnmpNeighbors.CDP+"7.7."+i,"Gi0/"+i);
                f.set(SnmpNeighbors.CDP+"3.7."+i,new Integer32(1));f.set(SnmpNeighbors.CDP+"4.7."+i,new OctetString(new byte[]{(byte)192,0,2,(byte)i}));
            }
            f.text(SnmpNeighbors.CDP+"17.7.1","actual-sysname");
            f.set(SnmpNeighbors.CDP+"19.7.1",new Integer32(20));f.set(SnmpNeighbors.CDP+"20.7.1",new OctetString(InetAddress.ofLiteral("2001:db8::7").getAddress()));
            var r=collect(f,List.of(port(7,"Ethernet1/1","00:11:22:33:44:55")));
            assertThat(r.rows().size()).isEqualTo(2);assertThat(r.facts()).containsEntry("cdpStatus","OBSERVED");
            assertThat(r.rows().get(0).path("address").asText()).isEqualTo("192.0.2.1");
            assertThat(r.rows().get(0).path("managementAddresses").get(0).asText()).isEqualTo("2001:db8:0:0:0:0:0:7");
            assertThat(r.rows().get(0).path("name").asText()).isEqualTo("actual-sysname");
            assertThat(r.rows().get(1).path("address").asText()).isEqualTo("192.0.2.2");
            assertThat(r.rows().get(1).path("name").asText()).isEqualTo("device-2");
            assertThat(r.capabilities()).contains("cdp-neighbors");
            assertThat(SnmpNeighbors.cdpAddress(2L,new OctetString(new byte[]{1,2,3,4}))).isNull();
            assertThat(SnmpNeighbors.cdpAddress(1L,new OctetString(new byte[]{0,0,0,0}))).isNull();
        }
    }
    @Test void emptyUnsupportedTablesRemainExplicitlyUnobserved()throws Exception{
        try(var f=new SnmpUdpFixture()){
            var r=collect(f,List.of());assertThat(r.rows().isEmpty()).isTrue();assertThat(r.capabilities()).isEmpty();
            assertThat(r.facts()).containsEntry("lldpStatus","EMPTY_OR_UNSUPPORTED").containsEntry("cdpStatus","EMPTY_OR_UNSUPPORTED");
            assertThat(r.flags()).contains("SNMP_LLDP_NO_OBSERVATIONS_MIB_MAY_BE_UNAVAILABLE","SNMP_CDP_NO_OBSERVATIONS_MIB_MAY_BE_UNAVAILABLE");
        }
    }
    @Test void timeoutIsBoundedAndDoesNotRetryAnotherProtocolOrInventEmptySuccess()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.silent=true;long start=System.nanoTime();var r=collect(f,List.of());
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
            assertThat(f.requests.get()).isEqualTo(1);assertThat(r.rows().isEmpty()).isTrue();
            assertThat(r.facts()).containsEntry("lldpStatus","INCOMPLETE").containsEntry("cdpStatus","INCOMPLETE");
            assertThat(r.flags()).contains("SNMP_TIMEOUT","SNMP_CDP_SKIPPED_COLLECTION_BUDGET_OR_ERROR");
        }
    }
    @Test void neighborAndAddressLimitsStayWithinSharedVariableBudget()throws Exception{
        try(var f=new SnmpUdpFixture()){
            local(f,7,5,"Ethernet1/1");for(int i=1;i<=70;i++)lldp(f,"0.7."+i,"neighbor"+i,"port"+i);
            for(int i=1;i<=140;i++)management(f,"0.7.1",1,"192.0.2."+i);
            var r=collect(f,List.of(port(7,"Ethernet1/1","00:11:22:33:44:55")));
            assertThat(r.rows().size()).isEqualTo(64);assertThat(r.flags()).contains("SNMP_LLDP_NEIGHBOR_LIMIT","SNMP_LLDP_ADDRESS_LIMIT");
            assertThat(Integer.parseInt(r.facts().get("snmpVariableCount"))).isLessThanOrEqualTo(SnmpSession.VARIABLE_BUDGET);
            assertThat(f.requests.get()).isLessThan(70);
        }
    }
    @Test void nonIncreasingOidAndMalformedFloodCannotEscapeTheSessionBudget()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.wrongOrder=true;var r=collect(f,List.of());assertThat(r.flags()).contains("SNMP_NON_INCREASING_OID");assertThat(r.rows().isEmpty()).isTrue();
            f.wrongOrder=false;for(int i=1;i<=3000;i++)f.text(SnmpNeighbors.REM+"5.0.7."+i+".9","invalid");
            r=collect(f,List.of());assertThat(r.flags()).contains("SNMP_VARIABLE_BUDGET","SNMP_UNEXPECTED_INDEX");
            assertThat(Integer.parseInt(r.facts().get("snmpVariableCount"))).isLessThanOrEqualTo(2500);assertThat(r.rows().isEmpty()).isTrue();
        }
    }
    @Test void normalDriverReturnsSnmpNeighborEvidenceWithoutAnySshDependency()throws Exception{
        try(var f=new SnmpUdpFixture();var driver=new SnmpDriver()){
            lldp(f,"0.7.1","remote","Te0/3/0");local(f,7,5,"Ethernet1/1");management(f,"0.7.1",1,"192.0.2.10");
            var reading=driver.read(target(f.port()),secrets()).block(Duration.ofSeconds(28));
            assertThat(reading.capabilities()).contains("lldp-neighbors","interface-counters");
            assertThat(JSON.readTree(reading.facts().get("neighborObservations")).get(0).path("source").asText()).isEqualTo("LLDP");
            assertThat(reading.facts()).containsEntry("neighborCollectionProtocol","SNMP");
        }
    }
    @Test void accessDeniedHasSafeStatusIndexAndRequestOidWithoutSecretOrBisectRetry()throws Exception{
        for(int status:List.of(org.snmp4j.PDU.authorizationError,org.snmp4j.PDU.noAccess))try(var f=new SnmpUdpFixture();var driver=new SnmpDriver()){
            f.rejectedGetStatus=status;f.rejectedGets.add(new OID("1.3.6.1.2.1.1.2.0"));
            assertThatThrownBy(()->driver.read(target(f.port()),secrets()).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(DeviceProtocol.Failure.class,e->{
                    assertThat(e.code()).isEqualTo("SNMP_ACCESS_DENIED");
                    assertThat(e.getMessage()).contains("status="+status,"index=2","oid=1.3.6.1.2.1.1.2.0").doesNotContain("synthetic-community");
                });
            assertThat(f.requests.get()).isEqualTo(1);
        }
    }
    @Test void lengthPrefixedIndexesPreserveEmbeddedNulAndRejectMalformedLengthOrOctets()throws Exception{
        try(var f=new SnmpUdpFixture()){
            String root="1.3.6.1.4.1.2011.2.235.1.1.13.50.1.1";
            f.text(root+".4.66.73.79.83","BIOS");f.text(root+".3.65.66.0","AB");
            f.text(root+".2.65.66.67","wrong length");f.text(root+".2.65.256","wrong byte");
            var flags=new LinkedHashSet<String>();
            try(var session=new SnmpSession(target(f.port()),secrets(),System.nanoTime()+Duration.ofSeconds(5).toNanos(),flags)){
                assertThat(session.walkOctetStringIndex(root,64,20,"SNMP_SENSOR_LIMIT")).containsOnlyKeys("4.66.73.79.83","3.65.66.0");
                assertThat(flags).contains("SNMP_UNEXPECTED_INDEX");
                int before=f.requests.get();
                assertThat(session.walkOctetStringIndex(root,64,20,"SNMP_SENSOR_LIMIT",1)).containsOnlyKeys("4.66.73.79.83","3.65.66.0");
                assertThat(f.requests.get()-before).isGreaterThanOrEqualTo(4);
                assertThatThrownBy(()->session.walkOctetStringIndex(root,65,20,null)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(()->session.walkOctetStringIndex(root,64,20,null,0)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
}
