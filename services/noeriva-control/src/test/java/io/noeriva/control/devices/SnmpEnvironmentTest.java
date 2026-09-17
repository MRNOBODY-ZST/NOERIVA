package io.noeriva.control.devices;

import org.junit.jupiter.api.Test;
import org.snmp4j.smi.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SnmpEnvironmentTest {
    private final SnmpDriverTest helper=new SnmpDriverTest();
    private DeviceProtocol.Target target(int p,String v,String u,String l,String a,String priv,int limit){return helper.target(p,v,u,l,a,priv,limit);}
    private DeviceProtocol.Secrets secrets(){return helper.secrets();}
    private DeviceProtocol.Reading read(DeviceProtocol.Target t,DeviceProtocol.Secrets s){return helper.read(t,s);}

    @Test void asr1002UsesHardwareAndPlatformDocumentedSensorEnumsAndAll284Thresholds()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.9.1.1525"));
            f.text("1.3.6.1.2.1.1.1.0","Cisco IOS Software [Cupertino], ASR1000 Software, Version 17.9.8, RELEASE SOFTWARE");
            String base="1.3.6.1.4.1.9.9.91.1.1.1.1.";
            sensor(f,base,7,8,9,0,25);sensor(f,base,7001,4,8,0,1498);sensor(f,base,4,5,9,0,6);sensor(f,base,1098,14,9,1,-189);
            String threshold="1.3.6.1.4.1.9.9.91.1.2.1.1.";
            for(int i=1;i<=284;i++)f.set(threshold+"5.7."+i,new Integer32(2));
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.identity().profileId()).isEqualTo("cisco-asr1002x-entity");
            assertThat(r.sensors()).anySatisfy(s->{assertThat(s.metric()).isEqualTo("temperature_celsius");assertThat(s.value()).isEqualTo(25d);});
            assertThat(r.sensors()).anySatisfy(s->{assertThat(s.metric()).isEqualTo("voltage_volts");assertThat(s.value()).isEqualTo(1.498);});
            assertThat(r.sensors()).anySatisfy(s->{assertThat(s.metric()).isEqualTo("current_amps");assertThat(s.value()).isEqualTo(6d);});
            assertThat(r.sensors()).anySatisfy(s->{assertThat(s.metric()).isEqualTo("optical_power_dbm");assertThat(s.value()).isCloseTo(-18.9,within(0.000001));});
            assertThat(r.health()).isEqualTo("HEALTHY");assertThat(r.qualityFlags()).doesNotContain("SNMP_SENSOR_LIMIT");
            assertThat(Integer.parseInt(r.facts().get("snmpVariableCount"))).isLessThanOrEqualTo(2500);
            f.set(threshold+"5.7.284",new Integer32(1));f.set(threshold+"2.7.284",new Integer32(30));
            assertThat(read(target(f.port(),"2c","","","","",128),secrets()).health()).isEqualTo("CRITICAL");
        }
    }
    @Test void dellUnavailableSerialFallsBackToDocumentedServiceTagAndPsuWatts(){
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.6027.1.3.28"));
            f.text("1.3.6.1.2.1.47.1.1.1.1.11.101","NA");f.text("1.3.6.1.2.1.47.1.1.1.1.10.101","");
            String unit="1.3.6.1.4.1.6027.3.26.1.3.4.1.";
            f.set(unit+"8.1",new Integer32(1));f.set(unit+"13.1",new Gauge32(26));f.text(unit+"11.1","NA");f.text(unit+"23.1","SYN0001");f.text(unit+"10.1","9.14(2.23)");
            String power="1.3.6.1.4.1.6027.3.26.1.4.6.1.";
            for(int i=1;i<=2;i++){f.set(power+"4.2.1."+i,new Integer32(1));f.set(power+"10.2.1."+i,new Integer32(i==1?98:119));}
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.identity().serialNumber()).isEqualTo("SYN0001");assertThat(r.facts()).containsEntry("serialKind","serviceTag").containsEntry("softwareVersion","9.14(2.23)");
            assertThat(r.metrics()).containsEntry("power_watts",217d).containsEntry("temperature_celsius",26d);assertThat(r.health()).isEqualTo("HEALTHY");
        }catch(Exception e){throw new AssertionError(e);}
    }
    @Test void oneOptionalAgentErrorCannotHideTheChassisSerialOrRemainingColumns()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.rejectedGets.add(new OID("1.3.6.1.2.1.47.1.1.1.1.9.101"));
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.identity().serialNumber()).isEqualTo("SYNTHETIC-SERIAL");assertThat(r.identity().model()).isEqualTo("SYNTHETIC-MODEL");assertThat(r.qualityFlags()).contains("SNMP_AGENT_ERROR");
            assertThat(r.facts()).containsEntry("snmpRejectedOptionalOids","1.3.6.1.2.1.47.1.1.1.1.9.101");
        }
    }
    @Test void duplicateStandardMibDoesNotExhaustSensorsAndNegativeVoltageRailIsValid()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.9.1.1525"));
            String vendor="1.3.6.1.4.1.9.9.91.1.1.1.1.",standard="1.3.6.1.2.1.99.1.1.1.",threshold="1.3.6.1.4.1.9.9.91.1.2.1.1.";
            for(int i=1;i<=90;i++){sensor(f,vendor,i,8,9,0,35);sensor(f,standard,i,8,9,0,35);f.set(threshold+"5."+i+".1",new Integer32(2));}
            sensor(f,vendor,1326,4,8,0,-5223);sensor(f,standard,1326,4,8,0,-5223);
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.sensors()).hasSize(91);assertThat(r.metrics()).containsEntry("temperature_celsius",35d);
            assertThat(r.sensors()).filteredOn(s->s.metric().equals("voltage_volts")).singleElement().satisfies(s->assertThat(s.value()).isCloseTo(-5.223,within(0.00001)));
            assertThat(r.qualityFlags()).doesNotContain("SNMP_SENSOR_LIMIT","SNMP_SENSOR_VALUE_INVALID","SNMP_VARIABLE_BUDGET");
        }
    }
    @Test void onlyProvenAdminDownLowOpticsThresholdsAreExcludedFromAggregateHealth()throws Exception{
        try(var f=new SnmpUdpFixture()){
            f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.9.1.1525"));
            String entity="1.3.6.1.2.1.47.1.1.1.1.",vendor="1.3.6.1.4.1.9.9.91.1.1.1.1.",threshold="1.3.6.1.4.1.9.9.91.1.2.1.1.",alias="1.3.6.1.2.1.47.1.3.2.1.2.";
            f.set(entity+"5.1092",new Integer32(9));f.set(entity+"5.1093",new Integer32(10));f.set(entity+"4.1093",new Integer32(1092));
            for(int i:List.of(1097,1098)){f.set(entity+"5."+i,new Integer32(8));f.set(entity+"4."+i,new Integer32(1092));}
            f.set(alias+"1093.0",new OID("1.3.6.1.2.1.2.2.1.1.7"));
            sensor(f,vendor,7,8,9,0,30);f.set(threshold+"5.7.1",new Integer32(2));
            sensor(f,vendor,1097,5,9,0,0);sensor(f,vendor,1098,14,9,1,-189);
            for(int i:List.of(1097,1098))for(int j=3;j<=4;j++){f.set(threshold+"5."+i+"."+j,new Integer32(1));f.set(threshold+"2."+i+"."+j,new Integer32(30));f.set(threshold+"3."+i+"."+j,new Integer32(2));}
            f.set("1.3.6.1.2.1.2.2.1.7.7",new Integer32(2));
            var r=read(target(f.port(),"2c","","","","",128),secrets());
            assertThat(r.health()).isEqualTo("HEALTHY");assertThat(r.sensors()).hasSize(3);
            assertThat(r.sensors()).filteredOn(s->s.health().equals("CRITICAL")).hasSize(2);
            assertThat(r.facts()).containsEntry("triggeredThresholdCount","4");assertThat(DeviceObservationSummary.excludedSensors(r.facts())).hasSize(2);
            assertThat(DeviceObservationSummary.message(r,"SNMP")).contains("管理关闭接口").doesNotContain("CRITICAL");
            f.set("1.3.6.1.2.1.2.2.1.7.7",new Integer32(1));
            r=read(target(f.port(),"2c","","","","",128),secrets());assertThat(r.health()).isEqualTo("CRITICAL");assertThat(DeviceObservationSummary.excludedSensors(r.facts())).isEmpty();
            f.set("1.3.6.1.2.1.2.2.1.7.7",new Integer32(2));f.set(threshold+"3.1098.4",new Integer32(4));
            r=read(target(f.port(),"2c","","","","",128),secrets());assertThat(r.health()).isEqualTo("CRITICAL");assertThat(DeviceObservationSummary.excludedSensors(r.facts())).hasSize(1);
            f.set(threshold+"3.1098.4",new Integer32(2));f.set(entity+"5.1094",new Integer32(10));f.set(entity+"4.1094",new Integer32(1092));
            r=read(target(f.port(),"2c","","","","",128),secrets());assertThat(r.health()).isEqualTo("CRITICAL");assertThat(DeviceObservationSummary.excludedSensors(r.facts())).isEmpty();
        }
    }
    private void sensor(SnmpUdpFixture f,String base,int index,int type,int scale,int precision,int value){
        f.set(base+"1."+index,new Integer32(type));f.set(base+"2."+index,new Integer32(scale));f.set(base+"3."+index,new Integer32(precision));f.set(base+"4."+index,new Integer32(value));f.set(base+"5."+index,new Integer32(1));
    }
}
