package io.noeriva.control.devices;

import java.util.*;
import java.util.regex.Pattern;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.SMIConstants;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.devices.SnmpDriver.*;

/** Read-only iMana objects verified on RH2288 V2 / RH2288H V2 firmware U1029 7.x. */
final class SnmpHuaweiBmc {
    static final String ROOT="1.3.6.1.4.1.2011.2.235.1.1.";
    private static final String SENSOR=ROOT+"13.50.1.", FIRMWARE=ROOT+"11.50.1.";
    private static final Pattern MODEL=Pattern.compile("(?i)(?:Tecal\\s+)?RH2288H?\\s+V2(?:-12L)?");
    private static final Pattern DECIMAL=Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)");
    private static final JsonMapper JSON=new JsonMapper();
    record Identity(String model,String serial,String firmware) {}
    private SnmpHuaweiBmc() {}

    static Identity identity(SnmpSession s,SnmpProfiles.Profile profile,Map<String,String> facts,Set<String> flags)throws Exception {
        if(!profile.id().equals("huawei-server-bmc"))return new Identity(null,null,null);
        var values=s.get(List.of(ROOT+"1.6.0",ROOT+"1.7.0"),false);
        String model=identityText(text(values.get(ROOT+"1.6.0"))),serial=identityText(text(values.get(ROOT+"1.7.0")));
        if(model!=null){facts.put("huaweiBmcModel",model);facts.put("modelSource",ROOT+"1.6.0");}
        if(serial!=null){facts.put("serialKind","chassisSerial");facts.put("serialSource",ROOT+"1.7.0");}
        if(!scoped(facts)){flags.add("SNMP_BMC_PROFILE_UNVERIFIED");return new Identity(model,serial,null);}
        facts.put("huaweiBmcMappingScope","RH2288_V2_RH2288H_V2_U1029_7x_observed");
        var names=s.walkOctetStringIndex(FIRMWARE+"1",64,32,"SNMP_FIRMWARE_LIMIT",1);
        var details=columns(s,names.keySet(),FIRMWARE,List.of(2,4));
        var candidates=new ArrayList<Map.Entry<String,String>>();
        for(var entry:names.entrySet()){
            String index=entry.getKey(),name=text(entry.getValue()),version=identityText(text(details.get(FIRMWARE+"4."+index)));
            Long type=number(details.get(FIRMWARE+"2."+index));
            // The observed table includes both Active iMana and Backup iMana with type=1.
            if(version!=null&&Objects.equals(type,1L)&&"Active iMana".equalsIgnoreCase(name))candidates.add(Map.entry(index,version));
        }
        if(candidates.size()>1)flags.add("SNMP_BMC_FIRMWARE_AMBIGUOUS");
        String firmware=candidates.size()==1?candidates.getFirst().getValue():null;
        if(firmware!=null)facts.put("firmwareSource",FIRMWARE+"4."+candidates.getFirst().getKey());
        return new Identity(model,serial,firmware);
    }

    static void collect(SnmpSession s,Map<String,String> facts,Set<String> flags,List<DeviceProtocol.Sensor> sensors)throws Exception {
        if(!scoped(facts))return;
        int checkpoint=s.issues();
        var system=s.get(List.of(ROOT+"1.1.0",ROOT+"1.13.0"),false);
        Long health=number(system.get(ROOT+"1.1.0"));
        if(health!=null){
            facts.put("huaweiSystemHealthRaw",health.toString());facts.put("healthBasis",ROOT+"1.1.0 iMana systemHealth; only observed value 1=OK is mapped");
            sensors.add(new DeviceProtocol.Sensor("snmp:"+ROOT+"1.1.0","iMana system health","componentHealth","state",null,health==1?"HEALTHY":"UNKNOWN",ROOT+"1.1.0"));
        }
        if(!Objects.equals(health,1L))flags.add("SNMP_HEALTH_INCOMPLETE");
        Long power=number(system.get(ROOT+"1.13.0"));
        if(power!=null&&power>=0&&power<=100_000){
            facts.put("powerWatts",power.toString());facts.put("powerSummary","iMana presentSystemPower verified against BMC WebUI watts");facts.put("powerSource",ROOT+"1.13.0");
            sensors.add(new DeviceProtocol.Sensor("snmp:"+ROOT+"1.13.0","System power","power_watts","W",power.doubleValue(),"UNKNOWN",ROOT+"1.13.0"));
        }else if(power!=null)flags.add("SNMP_SENSOR_VALUE_INVALID");
        var names=s.walkNullableOctetStringIndex(SENSOR+"1",64,128,"SNMP_SENSOR_LIMIT",20);
        var values=sensorColumns(s,List.of(2,9,10));
        boolean requiredIncomplete=names.isEmpty()||s.issues()!=checkpoint;
        // Read critical data first. Missing/unavailable optional thresholds cannot invalidate systemHealth.
        values.putAll(sensorColumns(s,List.of(3,4,5,6,7,8)));
        parse(names,values,sensors,facts,flags);
        if(requiredIncomplete)flags.add("SNMP_HEALTH_INCOMPLETE");
        facts.put("huaweiSensorTransport","bounded_column_GETBULK_20; critical_reading_status_type_before_optional_thresholds");
    }

    static void parse(Map<String,Variable> names,Map<String,Variable> values,List<DeviceProtocol.Sensor> sensors,Map<String,String> facts,Set<String> flags){
        var evidence=new LinkedHashMap<String,Object>();
        for(var entry:names.entrySet()){
            String index=entry.getKey(),name=identityText(text(entry.getValue()));
            if(name==null){flags.add("SNMP_SENSOR_IDENTITY_MISSING");flags.add("SNMP_HEALTH_INCOMPLETE");continue;}
            Variable reading=values.get(SENSOR+"2."+index),state=values.get(SENSOR+"9."+index);
            String raw=rawText(reading),status=rawText(state);
            Long type=number(values.get(SENSOR+"10."+index));
            if(!nullableValuePresent(reading)||!nullableValuePresent(state)||type==null){flags.add("SNMP_SENSOR_COLUMNS_INCOMPLETE");flags.add("SNMP_HEALTH_INCOMPLETE");}
            String metric="sensor_state",unit="state";
            if(Objects.equals(type,1L)){metric=name.matches("(?i)CPU[0-9]+ DTS")?"temperature_margin_celsius":"temperature_celsius";unit="Cel";}
            else if(Objects.equals(type,4L)){metric="fan_rpm";unit="RPM";}
            else if(Objects.equals(type,9L)&&name.matches("(?i)Power[0-9]+")){metric="power_watts";unit="W";}
            Double value=metric.equals("sensor_state")?null:decimal(raw);
            if(value!=null&&(metric.startsWith("temperature_")?(value < -100||value > 200):value < 0)){value=null;flags.add("SNMP_SENSOR_VALUE_INVALID");}
            if(!metric.equals("sensor_state")&&value==null&&!unavailable(raw))flags.add("SNMP_SENSOR_VALUE_INVALID");
            var meta=new LinkedHashMap<String,Object>();
            meta.put("name",name);if(nullableValuePresent(reading))meta.put("reading",raw);if(nullableValuePresent(state))meta.put("rawStatus",status);if(type!=null)meta.put("sensorType",type);
            meta.put("readingAvailability",availability(reading));meta.put("statusAvailability",availability(state));
            var thresholds=new LinkedHashMap<String,Double>();
            String[] labels={"upperNonRecoverable","upperCritical","upperMinor","lowerNonRecoverable","lowerCritical","lowerMinor"};
            for(int column=3;column<=8;column++){
                Double threshold=decimal(text(values.get(SENSOR+column+"."+index)));
                if(threshold!=null)thresholds.put(labels[column-3],threshold);
            }
            String sensorHealth=thresholdHealth(value,thresholds);
            if(!thresholds.isEmpty())meta.put("thresholds",thresholds);
            meta.put("healthBasis",sensorHealth.equals("UNKNOWN")?"raw_status_retained_without_unverified_bit_decoding":"observed_numeric_thresholds");
            String source=SENSOR+"2."+index;
            sensors.add(new DeviceProtocol.Sensor("snmp:"+source,name,metric,unit,value,sensorHealth,source));
            evidence.put(index,meta);
        }
        facts.put("huaweiSensorEvidence",JSON.writeValueAsString(evidence));
        facts.put("huaweiSensorRows",Integer.toString(names.size()));
        facts.put("huaweiSensorStatusEncoding","raw_IPMI_status_hex_preserved_not_assumed_health_enum");
        facts.put("huaweiSensorUnitsBasis","RH2288_iMana_7x_observed_type1_temperature_type4_RPM_PowerN_watts; CPU_DTS_is_relative_temperature");
    }
    private static String thresholdHealth(Double value,Map<String,Double> thresholds){
        if(value==null||thresholds.isEmpty())return "UNKNOWN";
        for(String name:List.of("upperNonRecoverable","upperCritical","lowerNonRecoverable","lowerCritical"))if(exceeded(value,thresholds,name))return "CRITICAL";
        for(String name:List.of("upperMinor","lowerMinor"))if(exceeded(value,thresholds,name))return "WARNING";
        return "HEALTHY";
    }
    private static boolean exceeded(double value,Map<String,Double> thresholds,String name){Double threshold=thresholds.get(name);return threshold!=null&&(name.startsWith("upper")?value>=threshold:value<=threshold);}
    private static boolean scoped(Map<String,String> facts){String model=facts.get("huaweiBmcModel");return model!=null&&MODEL.matcher(model).matches();}
    private static boolean nullableValuePresent(Variable value){return value instanceof OctetString||(value instanceof Null&&value.getSyntax()==SMIConstants.SYNTAX_NULL);}
    private static String rawText(Variable value){return value instanceof OctetString?Optional.ofNullable(text(value)).orElse(""):null;}
    private static String availability(Variable value){if(!nullableValuePresent(value))return "MISSING";if(value instanceof Null)return "RETURNED_NULL";return unavailable(rawText(value))?"RETURNED_UNAVAILABLE":"VALUE";}
    private static boolean unavailable(String raw){return raw==null||Set.of("","na","n/a","unknown","unavailable").contains(raw.toLowerCase(Locale.ROOT));}
    private static Double decimal(String raw){if(raw==null||!DECIMAL.matcher(raw.strip()).matches())return null;try{double v=Double.parseDouble(raw.strip());return Double.isFinite(v)?v:null;}catch(NumberFormatException e){return null;}}
    private static Map<String,Variable> sensorColumns(SnmpSession s,List<Integer> columns)throws Exception{
        var result=new LinkedHashMap<String,Variable>();
        for(int column:columns){
            int checkpoint=s.issues();
            s.walkNullableOctetStringIndex(SENSOR+column,64,128,"SNMP_SENSOR_LIMIT",20).forEach((index,value)->result.put(SENSOR+column+"."+index,value));
            if(s.issues()!=checkpoint)break;
        }
        return result;
    }
    private static Map<String,Variable> columns(SnmpSession s,Set<String> indices,String base,List<Integer> columns)throws Exception{
        var oids=columns.stream().flatMap(column->indices.stream().map(index->base+column+"."+index)).toList();
        var result=new LinkedHashMap<String,Variable>();
        // Legacy iMana has long OCTET STRING index OIDs; limit each GET's encoded size.
        for(int offset=0;offset<oids.size();offset+=8){
            int checkpoint=s.issues();var batch=s.get(oids.subList(offset,Math.min(offset+8,oids.size())),false);result.putAll(batch);
            if(batch.isEmpty()&&s.issues()!=checkpoint)break;
        }
        return result;
    }
}
