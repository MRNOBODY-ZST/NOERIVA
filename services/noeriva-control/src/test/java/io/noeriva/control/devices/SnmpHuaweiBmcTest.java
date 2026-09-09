package io.noeriva.control.devices;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.*;
import static org.assertj.core.api.Assertions.*;

class SnmpHuaweiBmcTest {
    private static final String ROOT=SnmpHuaweiBmc.ROOT,SENSOR=ROOT+"13.50.1.",FIRMWARE=ROOT+"11.50.1.";

    @Test void replaysSanitizedObservedNinetyNineRowsWithEighteenNumericReadings()throws Exception {
        try(var f=fixture();var input=getClass().getResourceAsStream("/snmp/huawei-imana-rh2288v2-observed-sensors.json")){
            var rows=new tools.jackson.databind.json.JsonMapper().readTree(input).path("rows");
            for(var row:rows){String index=row.path("index").asText();var columns=row.path("columns");
                for(int c=1;c<=12;c++){var value=columns.path(Integer.toString(c));if(value.isNumber())f.set(SENSOR+c+"."+index,new Integer32(value.asInt()));else f.text(SENSOR+c+"."+index,value.asText());}
            }
            var r=read(f);
            assertThat(r.sensors()).hasSize(101);
            assertThat(r.sensors()).filteredOn(s->s.sourceRef().startsWith(SENSOR)&&s.value()!=null).hasSize(18);
            assertThat(r.sensors()).filteredOn(s->s.metric().equals("temperature_celsius")).hasSize(6);
            assertThat(r.sensors()).filteredOn(s->s.metric().equals("temperature_margin_celsius")).hasSize(2);
            assertThat(r.sensors()).filteredOn(s->s.metric().equals("fan_rpm")).hasSize(8);
            assertThat(r.metrics()).containsEntry("temperature_celsius",30d);
            assertThat(r.facts().get("huaweiSensorEvidence")).contains("0x8001","0x8040","0x8080");
            assertThat(r.qualityFlags()).doesNotContain("SNMP_SENSOR_LIMIT","SNMP_VARIABLE_BUDGET","SNMP_SENSOR_VALUE_INVALID","SNMP_UNEXPECTED_INDEX");
            assertThat(f.requests.get()).as("bounded column walks replace more than 110 long-index GET batches").isLessThanOrEqualTo(90);
        }
    }
    @Test void emptyReturnedReadingAndStatusAreUnavailableColumnsNotMissingColumns()throws Exception {
        try(var f=fixture()){
            sensor(f,"CPU2 DTS",1,"","");sensor(f,"DIMM100",12,"na","");sensor(f,"Inlet Temp",1,"26","0x00c0");
            f.text(SENSOR+"4."+index("CPU2 DTS",17),"-1.000");
            var r=read(f);
            assertThat(r.health()).isEqualTo("HEALTHY");
            assertThat(r.qualityFlags()).doesNotContain("SNMP_SENSOR_COLUMNS_INCOMPLETE","SNMP_HEALTH_INCOMPLETE","SNMP_SENSOR_VALUE_INVALID");
            assertThat(r.sensors()).filteredOn(s->s.label().equals("CPU2 DTS")).singleElement().satisfies(s->{assertThat(s.value()).isNull();assertThat(s.health()).isEqualTo("UNKNOWN");});
            assertThat(r.facts().get("huaweiSensorEvidence")).contains("\"reading\":\"\"","\"rawStatus\":\"\"","RETURNED_UNAVAILABLE");
        }
    }
    @Test void legalAsnNullIsRetainedWithoutStoppingLaterRowsButNoSuchInstanceIsMissing()throws Exception {
        try(var f=fixture()){
            sensor(f,"AA Temp",1,"10","0x00c0");sensor(f,"BB Temp",1,"na","0x00c0");sensor(f,"CC Temp",1,"30","0x00c0");
            f.set(SENSOR+"2."+index("BB Temp",17),Null.instance);f.set(SENSOR+"9."+index("BB Temp",17),Null.instance);
            var r=read(f);
            assertThat(r.health()).isEqualTo("HEALTHY");assertThat(r.metrics()).containsEntry("temperature_celsius",30d);
            assertThat(r.qualityFlags()).doesNotContain("SNMP_SENSOR_COLUMNS_INCOMPLETE","SNMP_HEALTH_INCOMPLETE");
            assertThat(r.facts().get("huaweiSensorEvidence")).contains("RETURNED_NULL");
            f.set(SENSOR+"2."+index("BB Temp",17),Null.noSuchInstance);
            r=read(f);assertThat(r.health()).isEqualTo("UNKNOWN");assertThat(r.qualityFlags()).contains("SNMP_SENSOR_COLUMNS_INCOMPLETE","SNMP_HEALTH_INCOMPLETE");
        }
    }
    @Test void absentOptionalThresholdColumnsDoNotOverrideCompleteSystemHealthAndCriticalFields()throws Exception {
        try(var f=fixture()){
            sensor(f,"Inlet Temp",1,"26","0x00c0");
            for(int c=3;c<=8;c++)f.values.remove(new OID(SENSOR+c+"."+index("Inlet Temp",17)));
            var r=read(f);assertThat(r.health()).isEqualTo("HEALTHY");
            assertThat(r.qualityFlags()).doesNotContain("SNMP_SENSOR_COLUMNS_INCOMPLETE","SNMP_HEALTH_INCOMPLETE");
            assertThat(r.sensors()).filteredOn(s->s.label().equals("Inlet Temp")).singleElement().satisfies(s->assertThat(s.health()).isEqualTo("UNKNOWN"));
        }
    }
    @Test void delayedInitialUdpResponseUsesTheConfiguredBudgetWithoutRelaxingTimeoutFailures()throws Exception {
        try(var f=fixture()){
            f.firstResponseDelayMillis=250;var test=new SnmpDriverTest();
            assertThatThrownBy(()->test.read(test.target(f.port(),"2c","","","","",128),test.secrets()))
                .isInstanceOfSatisfying(DeviceProtocol.Failure.class,error->assertThat(error.code()).isEqualTo("SNMP_TIMEOUT"));
        }
        try(var f=fixture()){
            f.firstResponseDelayMillis=250;sensor(f,"Inlet Temp",1,"26","0x00c0");
            var r=read(f);assertThat(r.health()).isEqualTo("HEALTHY");
            assertThat(r.metrics()).containsEntry("temperature_celsius",26d).containsEntry("power_watts",159d);
            assertThat(r.qualityFlags()).doesNotContain("SNMP_TIMEOUT","SNMP_SENSOR_COLUMNS_INCOMPLETE","SNMP_HEALTH_INCOMPLETE");
        }
    }
    @Test void actuallyMissingTypeRemainsIncompleteInsteadOfBeingInferredFromItsName()throws Exception {
        try(var f=fixture()){
            sensor(f,"Inlet Temp",1,"26","0x00c0");f.values.remove(new OID(SENSOR+"10."+index("Inlet Temp",17)));
            var r=read(f);assertThat(r.health()).isEqualTo("UNKNOWN");assertThat(r.qualityFlags()).contains("SNMP_SENSOR_COLUMNS_INCOMPLETE","SNMP_HEALTH_INCOMPLETE");
            assertThat(r.sensors()).filteredOn(s->s.label().equals("Inlet Temp")).singleElement().satisfies(s->{assertThat(s.value()).isNull();assertThat(s.metric()).isEqualTo("sensor_state");});
        }
    }

    @Test void readsObservedBmcSchemaWithActiveFirmwareRealUnitsAndRawDiscreteStates()throws Exception {
        try(var f=fixture()){
            sensor(f,"Inlet Temp",1,"26.00","0x00c0");sensor(f,"CPU1 DTS",1,"-59.00","0x00c0");
            sensor(f,"FAN1 F Speed",4,"2160.00","0x00c0");sensor(f,"Power1",9,"80.00","0x00c0");
            sensor(f,"Power2",9,"72.00","0x00c0");sensor(f,"DIMM001",12,"na","0x8040");
            f.text(SENSOR+"4."+index("Inlet Temp",17),"44.000");
            var r=read(f);
            assertThat(r.identity().profileId()).isEqualTo("huawei-server-bmc");
            assertThat(r.identity().model()).isEqualTo("RH2288H V2-12L");
            assertThat(r.identity().serialNumber()).isEqualTo("SYNTHETIC-HUAWEI-SERIAL");
            assertThat(r.identity().firmware()).isEqualTo("7.38");
            assertThat(r.metrics()).containsEntry("temperature_celsius",26d).containsEntry("power_watts",159d);
            assertThat(r.health()).isEqualTo("HEALTHY");
            assertThat(r.sensors()).filteredOn(s->s.label().equals("CPU1 DTS")).singleElement().satisfies(s->{assertThat(s.metric()).isEqualTo("temperature_margin_celsius");assertThat(s.value()).isEqualTo(-59d);});
            assertThat(r.sensors()).filteredOn(s->s.label().equals("DIMM001")).singleElement().satisfies(s->{assertThat(s.value()).isNull();assertThat(s.health()).isEqualTo("UNKNOWN");});
            assertThat(r.facts()).containsEntry("huaweiSystemHealthRaw","1").containsEntry("powerSource",ROOT+"1.13.0");
            assertThat(r.facts().get("huaweiSensorEvidence")).contains("0x8040","upperCritical","44.0");
            assertThat(r.qualityFlags()).doesNotContain("SNMP_UNEXPECTED_INDEX","SNMP_SENSOR_VALUE_INVALID");
        }
    }
    @Test void keepsUnverifiedSystemHealthUnknownEvenWhenATemperatureIsWithinThresholds()throws Exception {
        try(var f=fixture()){
            f.set(ROOT+"1.1.0",new Integer32(2));sensor(f,"Inlet Temp",1,"26","0x00c0");f.text(SENSOR+"4."+index("Inlet Temp",17),"44");
            var r=read(f);assertThat(r.health()).isEqualTo("UNKNOWN");assertThat(r.qualityFlags()).contains("SNMP_HEALTH_INCOMPLETE");assertThat(r.facts()).containsEntry("huaweiSystemHealthRaw","2");
        }
    }
    @Test void currentThresholdAlarmIsNotHiddenByGlobalHealthyCode()throws Exception {
        try(var f=fixture()){
            sensor(f,"Inlet Temp",1,"45","0x00c0");f.text(SENSOR+"4."+index("Inlet Temp",17),"44");
            assertThat(read(f).health()).isEqualTo("CRITICAL");
        }
    }
    @Test void neitherMissingReadingsNorUnknownSensorTypesProduceFabricatedNumbers()throws Exception {
        try(var f=fixture()){
            sensor(f,"Inlet Temp",1,"na","0x8000");sensor(f,"Power1",9,"0.00","0x00c0");sensor(f,"Mystery",199,"12","0x8000");
            var r=read(f);assertThat(r.metrics()).doesNotContainKey("temperature_celsius");
            assertThat(r.sensors()).filteredOn(s->s.label().equals("Power1")).singleElement().satisfies(s->assertThat(s.value()).isZero());
            assertThat(r.sensors()).filteredOn(s->s.label().equals("Mystery")).singleElement().satisfies(s->{assertThat(s.value()).isNull();assertThat(s.health()).isEqualTo("UNKNOWN");});
        }
    }
    @Test void sameEnterpriseAndProductRootDoNotEnableLegacySensorRulesOnUnverifiedModels()throws Exception {
        try(var f=fixture()){
            f.text(ROOT+"1.6.0","UNVERIFIED-NEXT-GENERATION");sensor(f,"Inlet Temp",1,"26","0x00c0");
            var r=read(f);assertThat(r.health()).isEqualTo("UNKNOWN");assertThat(r.sensors()).isEmpty();assertThat(r.qualityFlags()).contains("SNMP_BMC_PROFILE_UNVERIFIED");
        }
    }
    @Test void observesAllNinetyNineSensorRowsWithoutDiscardingUnavailableOrDiscreteSensors()throws Exception {
        try(var f=fixture()){
            for(int i=0;i<99;i++)sensor(f,"Sensor"+i,12,"na","0x8000");
            var r=read(f);assertThat(r.sensors()).hasSize(101);assertThat(r.facts()).containsEntry("huaweiSensorRows","99");
            assertThat(r.qualityFlags()).doesNotContain("SNMP_SENSOR_LIMIT","SNMP_VARIABLE_BUDGET","SNMP_UNEXPECTED_INDEX");
        }
    }
    private static SnmpUdpFixture fixture()throws Exception {
        var f=new SnmpUdpFixture();f.values.clear();
        f.text("1.3.6.1.2.1.1.1.0","Hardware management system");f.set("1.3.6.1.2.1.1.2.0",new OID("1.3.6.1.4.1.2011.2.235"));
        f.set("1.3.6.1.2.1.1.3.0",new TimeTicks(12345));f.text("1.3.6.1.2.1.1.5.0","synthetic-bmc");
        f.text(ROOT+"1.6.0","RH2288H V2-12L");f.text(ROOT+"1.7.0","SYNTHETIC-HUAWEI-SERIAL");
        f.set(ROOT+"1.1.0",new Integer32(1));f.set(ROOT+"1.13.0",new Integer32(159));
        for(String name:List.of("BIOS","Active iMana","Backup iMana")){
            String i=index(name,name.length());f.text(FIRMWARE+"1."+i,name);f.set(FIRMWARE+"2."+i,new Integer32(name.equals("BIOS")?4:1));
            f.text(FIRMWARE+"4."+i,name.equals("Active iMana")?"7.38":name.equals("Backup iMana")?"7.23":"V520");
        }
        return f;
    }
    private static DeviceProtocol.Reading read(SnmpUdpFixture f){
        // These tests validate MIB semantics, not a 150 ms loopback latency SLA.
        // A full container suite can delay the fixture's UDP dispatcher beyond that
        // interval before the initial system GET; dedicated timeout tests remain separate.
        var target=new DeviceProtocol.Target("must-not-resolve.invalid","127.0.0.1",f.port(),"SNMP","","2c","","","","","SYSTEM","",1000,128);
        var test=new SnmpDriverTest();return test.read(target,test.secrets());
    }
    private static void sensor(SnmpUdpFixture f,String name,int type,String reading,String status){
        String i=index(name,17);f.text(SENSOR+"1."+i,name);f.text(SENSOR+"2."+i,reading);f.text(SENSOR+"9."+i,status);f.set(SENSOR+"10."+i,new Integer32(type));
        for(int c=3;c<=8;c++)f.text(SENSOR+c+"."+i,"na");
    }
    private static String index(String name,int paddedLength){var bytes=Arrays.copyOf(name.getBytes(StandardCharsets.US_ASCII),paddedLength);StringBuilder s=new StringBuilder(Integer.toString(bytes.length));for(byte b:bytes)s.append('.').append(b&255);return s.toString();}
}
