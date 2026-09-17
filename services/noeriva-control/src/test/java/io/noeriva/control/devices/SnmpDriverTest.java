package io.noeriva.control.devices;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.*;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SnmpDriverTest {
    @Test void truncatedTemperatureTableDoesNotSuppressCompleteUniqueCpuAndMemory() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.9.1.1525"));
            String cpu="1.3.6.1.4.1.9.9.109.1.1.1.1.";
            f.set(cpu+"2.7",new Integer32(101));f.set(cpu+"6.7",new Gauge32(10));f.set(cpu+"17.7",new Counter64(25));f.set(cpu+"19.7",new Counter64(75));
            String sensor="1.3.6.1.4.1.9.9.91.1.1.1.1.";
            for(int i=1;i<=256;i++){f.set(sensor+"1."+i,new Integer32(i<=64?8:99));f.set(sensor+"2."+i,new Integer32(9));f.set(sensor+"3."+i,new Integer32(0));f.set(sensor+"4."+i,new Integer32(35));f.set(sensor+"5."+i,new Integer32(1));}
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.qualityFlags()).contains("SNMP_SENSOR_LIMIT");
            assertThat(r.metrics()).containsEntry("cpu_percent",10d).containsEntry("memory_percent",25d).doesNotContainKey("temperature_celsius");
        }
    }
    @Test void dellS6100UsesDocumentedThreePartProcessorIndexAndMemoryIsNotTemperature() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.6027.1.3.28"));
            f.text("1.3.6.1.2.1.1.1.0","Dell Networking OS9 9.14(2.23) synthetic");
            String base="1.3.6.1.4.1.6027.3.26.1.4.4.1.";
            f.set(base+"1.2.1.1",new Gauge32(17));f.set(base+"6.2.1.1",new Gauge32(44));
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.metrics()).containsEntry("cpu_percent",17d).containsEntry("memory_percent",44d).doesNotContainKey("temperature_celsius");
            assertThat(r.sensors()).filteredOn(s->s.metric().equals("memory_percent")).singleElement().satisfies(s->assertThat(s.sourceRef()).isEqualTo(base+"6.2.1.1"));
            f.set(base+"6.2.1",new Gauge32(99)); // malformed two-part legacy suffix is not a processor.
            r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.qualityFlags()).contains("SNMP_UNEXPECTED_INDEX");
            assertThat(r.metrics()).containsEntry("cpu_percent",17d).doesNotContainKey("memory_percent");
            assertThat(r.sensors()).filteredOn(s->s.metric().equals("memory_percent")).singleElement().satisfies(s->assertThat(s.value()).isEqualTo(44));
        }
    }
    DeviceProtocol driver(){
        try{return (DeviceProtocol)Class.forName("io.noeriva.control.devices.SnmpDriver").getConstructor().newInstance();}
        catch(ReflectiveOperationException e){throw new AssertionError("SNMP driver must implement the read-only protocol",e);}
    }
    DeviceProtocol.Target target(int port,String version,String user,String level,String auth,String privacy,int limit){
        return new DeviceProtocol.Target("must-not-resolve.invalid","127.0.0.1",port,"SNMP",user,version,level,auth,privacy,"","SYSTEM","",150,limit);
    }
    DeviceProtocol.Secrets secrets(){return new DeviceProtocol.Secrets("synthetic-community","auth-test-pass","priv-test-pass",null);}
    DeviceProtocol.Reading read(DeviceProtocol.Target target,DeviceProtocol.Secrets secrets){
        DeviceProtocol d=driver();
        try{return d.read(target,secrets).block(Duration.ofSeconds(28));}
        finally{if(d instanceof AutoCloseable c)try{c.close();}catch(Exception ignored){}}
    }
    @Test void v2cReadsActualUdpPreservingUnsignedCountersAndEntityMetrics() throws Exception{
        try(var f=new SnmpUdpFixture()){
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.identity().vendor()).isEqualTo("Huawei");
            assertThat(r.identity().model()).isEqualTo("SYNTHETIC-MODEL");
            assertThat(r.ports()).hasSize(1);
            var p=r.ports().getFirst();
            assertThat(p.inOctets()).isEqualTo("9223372036854776000");
            assertThat(p.speedBps()).isEqualTo("10000000000");
            assertThat(p.operStatus()).isEqualTo("DOWN");
            assertThat(p.counterBits()).isEqualTo(64);
            assertThat(p.discontinuity()).isEqualTo("100");
            assertThat(r.facts()).containsEntry("sysUptimeTicks","123456");
            assertThat(r.sensors()).anySatisfy(s->{assertThat(s.metric()).isEqualTo("temperature_celsius");assertThat(s.value()).isEqualTo(36);});
        }
    }
    @Test void entityFirmwareAndSoftwareRevisionRemainSeparateFacts() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.text("1.3.6.1.2.1.47.1.1.1.1.9.101","SYNTHETIC-FIRMWARE");
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.identity().firmware()).isEqualTo("SYNTHETIC-FIRMWARE");
            assertThat(r.facts()).containsEntry("softwareVersion","SYNTHETIC-VRP");
        }
    }
    @Test void missingEntityFirmwareDoesNotRelabelSoftwareAsFirmware() throws Exception{
        try(var f=new SnmpUdpFixture()){
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.identity().firmware()).isNull();
            assertThat(r.facts()).containsEntry("softwareVersion","SYNTHETIC-VRP");
        }
    }
    @Test void wrongCommunityNeverBecomesEmptyHealthyReading() throws Exception{
        try(var f=new SnmpUdpFixture()){
            assertThatThrownBy(()->read(target(f.port(),"2c","","","","",128),new DeviceProtocol.Secrets("wrong-sensitive-community",null,null,null)))
              .isInstanceOf(DeviceProtocol.Failure.class).hasMessageNotContaining("wrong-sensitive-community");
        }
    }
    @Test void v3Sha256Aes128AndWrongKeysUseRealUsm() throws Exception{
        try(var f=new SnmpUdpFixture()){
            var t=target(f.port(),"3","synthetic-user","authPriv","SHA256","AES128",128);
            var r=read(t,secrets());
            assertThat(r.ports()).hasSize(1);
            assertThat(r.facts()).containsKey("snmpEngineId");
            for(var s:List.of(new DeviceProtocol.Secrets(null,"wrong-auth-pass","priv-test-pass",null),new DeviceProtocol.Secrets(null,"auth-test-pass","wrong-priv-pass",null))){
                assertThatThrownBy(()->read(t,s)).isInstanceOf(DeviceProtocol.Failure.class)
                    .hasMessageNotContaining("wrong-auth-pass").hasMessageNotContaining("wrong-priv-pass");
            }
        }
    }
    @Test void explicitlyConfiguredLegacyAndNoAuthSecurityLevelsWork() throws Exception{
        try(var f=new SnmpUdpFixture()){
            for(var t:List.of(target(f.port(),"3","sha1-user","authPriv","SHA1","DES",128),
                target(f.port(),"3","md5-user","authNoPriv","MD5","",128),
                target(f.port(),"3","sha512-user","authPriv","SHA512","AES128",128),
                target(f.port(),"3","plain-user","noAuthNoPriv","","",128))){
                assertThat(read(t,secrets()).ports()).hasSize(1);
            }
        }
    }
    @Test void interfaceLimitAndWrongOrderedAgentStayBounded() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.addPort(8,"Ethernet1/2","00:11:22:33:44:66");f.addPort(9,"Ethernet1/3","00:11:22:33:44:77");
            var r=read(target(f.port(),"2c","","","","",2),secrets());
            assertThat(r.ports()).hasSize(2);
            assertThat(r.qualityFlags()).contains("SNMP_INTERFACE_LIMIT");
            f.wrongOrder=true;
            r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.qualityFlags()).contains("SNMP_NON_INCREASING_OID");
            assertThat(r.ports()).isEmpty();
        }
    }
    @Test void sparseTablesDoNotZipDifferentInterfacesOrInventCounters() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.addPort(8,"Ethernet1/2","00:11:22:33:44:66");
            f.values.remove(new OID("1.3.6.1.2.1.31.1.1.1.6.7"));
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.ports()).filteredOn(p->p.name().equals("Ethernet1/1")).singleElement().satisfies(p->assertThat(p.inOctets()).isNull());
            assertThat(r.ports()).filteredOn(p->p.name().equals("Ethernet1/2")).singleElement().satisfies(p->assertThat(p.inOctets()).isEqualTo("9223372036854776000"));
        }
    }
    @Test void ifIndexChangeDoesNotChangeStrongPortIdentity() throws Exception{
        try(var f=new SnmpUdpFixture()){
            var t=target(f.port(),"2c","","","","",128);
            String key=read(t,secrets()).ports().getFirst().key();
            f.values.keySet().removeIf(oid->(oid.startsWith(new OID("1.3.6.1.2.1.2.2.1"))||oid.startsWith(new OID("1.3.6.1.2.1.31.1.1.1")))&&oid.last()==7);
            f.addPort(103,"Ethernet1/1","00:11:22:33:44:55");
            assertThat(read(t,secrets()).ports().getFirst().key()).isEqualTo(key);
        }
    }
    @Test void refusesUnsupportedSecurityWithoutSendingPackets() throws Exception{
        try(var f=new SnmpUdpFixture()){
            assertThatThrownBy(()->read(target(f.port(),"3","synthetic-user","authPriv","SHA256","AES256",128),secrets()))
                .isInstanceOf(DeviceProtocol.Failure.class);
            assertThat(f.requests.get()).isZero();
        }
    }
    @Test void hardVariableBudgetIncludesSparseAndFallbackQueries() throws Exception{
        try(var f=new SnmpUdpFixture()){
            for(int i=1000;i<1256;i++)f.addPort(i,"Ethernet"+i,"00:11:22:33:"+String.format("%02x:%02x",i>>8,i&255));
            for(int i=200;i<464;i++){f.set("1.3.6.1.2.1.47.1.1.1.1.5."+i,new Integer32(9));f.text("1.3.6.1.2.1.47.1.1.1.1.7."+i,"synthetic module "+i);}
            var r=read(target(f.port(),"2c","","","","",256),secrets());
            assertThat(r.qualityFlags()).contains("SNMP_VARIABLE_BUDGET","SNMP_INTERFACE_LIMIT","SNMP_ENTITY_LIMIT");
            assertThat(Integer.parseInt(r.facts().get("snmpVariableCount"))).isLessThanOrEqualTo(2500);
            assertThat(f.requests.get()).isLessThan(150);
        }
    }
    @Test void genericFallbackDoesNotTrustVendorWordsAndPreservesCounter32() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.90.1"));f.text("1.3.6.1.2.1.1.1.0","Cisco IOS XE H3C Huawei Dell untrusted text");
            for(String oid:List.of("1.3.6.1.2.1.31.1.1.1.6.7","1.3.6.1.2.1.31.1.1.1.10.7","1.3.6.1.2.1.31.1.1.1.15.7"))f.values.remove(new OID(oid));
            f.set("1.3.6.1.2.1.2.2.1.10.7",new Counter32(4294967295L));f.set("1.3.6.1.2.1.2.2.1.16.7",new Counter32(0));f.set("1.3.6.1.2.1.2.2.1.5.7",new Gauge32(1000000000));
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.identity().vendor()).isEqualTo("Unknown");assertThat(r.identity().profileId()).isEqualTo("generic-snmp");
            assertThat(r.sensors()).isEmpty();assertThat(r.health()).isEqualTo("UNKNOWN");
            assertThat(r.ports()).singleElement().satisfies(p->{assertThat(p.counterBits()).isEqualTo(32);assertThat(p.inOctets()).isEqualTo("4294967295");assertThat(p.outOctets()).isEqualTo("0");assertThat(p.speedBps()).isEqualTo("1000000000");});
        }
    }
    @Test void h3cPercentAndUnknownTemperatureUnitsStaySeparate() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.25506.1.999"));f.text("1.3.6.1.2.1.1.1.0","H3C Comware synthetic");
            f.set("1.3.6.1.4.1.25506.2.6.1.1.1.1.6.101",new Integer32(24));f.set("1.3.6.1.4.1.25506.2.6.1.1.1.1.8.101",new Integer32(48));f.set("1.3.6.1.4.1.25506.2.6.1.1.1.1.12.101",new Integer32(65535));
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.identity().vendor()).isEqualTo("H3C");assertThat(r.metrics()).containsEntry("cpu_percent",24d).containsEntry("memory_percent",48d).doesNotContainKey("temperature_celsius");
        }
    }
    @Test void ciscoAndStandardSensorEnumerationsAndPrecisionAreDistinct() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.9.1.999"));f.text("1.3.6.1.2.1.1.1.0","Cisco IOS XE synthetic");
            String cpu="1.3.6.1.4.1.9.9.109.1.1.1.1.";f.set(cpu+"2.50",new Integer32(101));f.set(cpu+"10.50",new Gauge32(12));f.set(cpu+"9.50",new Integer32(10));f.set(cpu+"12.50",new Gauge32(100));f.set(cpu+"13.50",new Gauge32(300));
            for(String base:List.of("1.3.6.1.4.1.9.9.91.1.1.1.1.","1.3.6.1.2.1.99.1.1.1.")){
                int index=base.contains("99.1")?201:200;
                f.set(base+"1."+index,new Integer32(base.contains("99.1")?8:6));f.set(base+"2."+index,new Integer32(9));f.set(base+"3."+index,new Integer32(1));f.set(base+"4."+index,new Integer32(365));f.set(base+"5."+index,new Integer32(1));
            }
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.metrics()).containsEntry("cpu_percent",12d).containsEntry("memory_percent",25d).containsEntry("temperature_celsius",36.5);
            assertThat(r.sensors()).filteredOn(s->s.metric().equals("temperature_celsius")).hasSize(2).allSatisfy(s->assertThat(s.value()).isEqualTo(36.5));
        }
    }
    @Test void dellPrivateValuesAreNotAssumedToBeGenericOs9Metrics() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.6027.1.999"));f.text("1.3.6.1.2.1.1.1.0","Dell OS9 synthetic");
            f.set("1.3.6.1.4.1.6027.3.10.1.2.9.1.2.1",new Integer32(77));
            var r=read(target(f.port(),"2c","","","","",128),secrets());assertThat(r.identity().vendor()).isEqualTo("Dell");assertThat(r.identity().family()).contains("candidate");assertThat(r.metrics()).isEmpty();assertThat(r.ports()).hasSize(1);
        }
    }
    @Test void cancellationClosesAnInFlightReadWithoutDroppedErrors() throws Exception{
        var dropped=new java.util.concurrent.CopyOnWriteArrayList<Throwable>();reactor.core.publisher.Hooks.onErrorDropped(dropped::add);
        try(var f=new SnmpUdpFixture();var d=new SnmpDriver()){
            f.silent=true;var t=new DeviceProtocol.Target("never-resolve.invalid","127.0.0.1",f.port(),"SNMP","","2c","","","","","SYSTEM","",5000,128);
            var subscription=d.read(t,secrets()).subscribe();
            long until=System.nanoTime()+Duration.ofSeconds(3).toNanos();while(f.requests.get()==0 && System.nanoTime()<until)Thread.sleep(5);
            assertThat(f.requests.get()).isPositive();subscription.dispose();Thread.sleep(200);
            assertThat(dropped).isEmpty();
        }finally{reactor.core.publisher.Hooks.resetOnErrorDropped();}
    }
    @Test void ciscoHighCapacityMemorySourceNamesBothActualCounterOids() throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.9.1.999"));
            String cpu="1.3.6.1.4.1.9.9.109.1.1.1.1.";f.set(cpu+"2.50",new Integer32(101));
            f.set(cpu+"17.50",new Counter64(300));f.set(cpu+"19.50",new Counter64(100));
            f.set(cpu+"12.50",new Gauge32(1));f.set(cpu+"13.50",new Gauge32(99));
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.sensors()).filteredOn(s->s.metric().equals("memory_percent")).singleElement().satisfies(s->{assertThat(s.value()).isEqualTo(75d);assertThat(s.sourceRef()).contains(cpu+"17.50").contains(cpu+"19.50");});
        }
    }
    @Test void bmcCandidatesAndInspurUseOnlyProvenGenericSensors() throws Exception{
        for(var row:List.of(new String[]{"2011.999","Huawei iBMC synthetic","Huawei","iBMC"},
            new String[]{"2011.999","Huawei iMana 200 synthetic","Huawei","iMana"},
            new String[]{"674.999","Dell iDRAC synthetic","Dell","iDRAC"},
            new String[]{"37945.999","Inspur BMC synthetic","Inspur","BMC"},
            new String[]{"25506.999","H3C HDM synthetic","H3C","HDM"})){
            try(var f=new SnmpUdpFixture()){
                f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1."+row[0]));f.text("1.3.6.1.2.1.1.1.0",row[1]);
                var r=read(target(f.port(),"2c","","","","",128),secrets());
                assertThat(r.identity().vendor()).isEqualTo(row[2]);assertThat(r.identity().family()).contains(row[3]).contains("candidate");
                assertThat(r.identity().profileId()).contains("generic");assertThat(r.sensors()).isEmpty();assertThat(r.ports()).hasSize(1);
            }
        }
    }
    @Test void totalSensorLimitCannotTurnATruncatedSetIntoADeviceAggregate() throws Exception{
        try(var f=new SnmpUdpFixture()){
            String base="1.3.6.1.4.1.2011.5.25.31.1.1.1.1.";
            for(int index=1;index<=64;index++){
                f.set(base+"5."+index,new Integer32(25));
                f.set(base+"7."+index,new Integer32(50));
            }
            // 64 CPU + 64 memory rows precede the single temperature row: 129 actual sensors.
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.sensors()).hasSize(128);
            assertThat(r.qualityFlags()).contains("SNMP_SENSOR_LIMIT");
            assertThat(r.metrics()).isEmpty();
            assertThat(Integer.parseInt(r.facts().get("snmpVariableCount"))).isLessThanOrEqualTo(2500);
        }
    }
}
