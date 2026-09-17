package io.noeriva.control.devices;

import org.snmp4j.smi.Variable;
import java.util.*;
import static io.noeriva.control.devices.SnmpDriver.*;

/** Reviewed OID registry. Primary evidence and units live in docs/devices/SNMP-PROTOCOL-RESEARCH.md. */
final class SnmpProfiles {
    record Profile(String id,String vendor,String family,String basis){}
    private static final String ENTITY="1.3.6.1.2.1.47.1.1.1.1.7";
    private static final String HUAWEI="1.3.6.1.4.1.2011.5.25.31.1.1.1.1.";
    private static final String H3C="1.3.6.1.4.1.25506.2.6.1.1.1.1.";
    private static final String CISCO="1.3.6.1.4.1.9.9.109.1.1.1.1.";
    private SnmpProfiles(){}
    static Profile identify(String object,String description){
        String d=description==null?"":description.toLowerCase(Locale.ROOT);
        if("1.3.6.1.4.1.2011.2.235".equals(object))return new Profile("huawei-server-bmc","Huawei","iMana / iBMC candidate","HUAWEI_SERVER_PRODUCT_OID");
        if(under(object,"1.3.6.1.4.1.2011")){
            if(d.contains("ibmc") || d.contains("imana"))return new Profile("huawei-bmc-generic-snmp","Huawei",d.contains("ibmc")?"iBMC candidate":"iMana candidate","ENTERPRISE_OID_AND_DESCRIPTION_HINT");
            return d.contains("vrp")?new Profile("huawei-entity-extent","Huawei","VRP candidate","ENTERPRISE_OID_AND_DESCRIPTION_HINT"):
                new Profile("huawei-generic-snmp","Huawei","Huawei product; OS unverified","ENTERPRISE_OID");
        }
        if(under(object,"1.3.6.1.4.1.25506")){
            if(d.contains("hdm"))return new Profile("h3c-bmc-generic-snmp","H3C","HDM candidate","ENTERPRISE_OID_AND_DESCRIPTION_HINT");
            return new Profile("h3c-entity-extent","H3C",d.contains("comware")?"Comware candidate":"H3C product; OS unverified","ENTERPRISE_OID");
        }
        if("1.3.6.1.4.1.9.1.1525".equals(object))return new Profile("cisco-asr1002x-entity","Cisco","IOS XE / ASR1002-X","CISCO_PRODUCTS_MIB_OBJECT_ID");
        if(under(object,"1.3.6.1.4.1.9"))return new Profile("cisco-process-entity","Cisco",d.contains("ios xe") || d.contains("ios-xe")?"IOS XE candidate":"Cisco network","ENTERPRISE_OID");
        if(under(object,"1.3.6.1.4.1.6027") || under(object,"1.3.6.1.4.1.674")){
            if(d.contains("idrac"))return new Profile("dell-bmc-generic-snmp","Dell","iDRAC candidate","ENTERPRISE_OID_AND_DESCRIPTION_HINT");
            if("1.3.6.1.4.1.6027.1.3.28".equals(object))return new Profile("dell-os9-s6100-chassis","Dell","S6100 OS9 candidate","DELL_PRODUCTS_MIB_OBJECT_ID");
            return new Profile("dell-generic-snmp","Dell",d.contains("os9") || d.contains("application software version: 9.")?"Network OS9 candidate":under(object,"1.3.6.1.4.1.6027")?"Dell / Force10; OS unverified":"Dell product; OS unverified","ENTERPRISE_OID");
        }
        if(under(object,"1.3.6.1.4.1.37945"))return new Profile("inspur-generic-snmp","Inspur",d.contains("bmc")?"BMC candidate":"Inspur product; OS unverified","ENTERPRISE_OID");
        return new Profile("generic-snmp","Unknown","Standard SNMP","STANDARD_MIB_ONLY");
    }
    private static boolean under(String oid,String root){return oid!=null && (oid.equals(root) || oid.startsWith(root+"."));}
    static Set<String> sensors(SnmpSession session,Profile profile,Map<String,Variable> entities,Set<String> flags,List<DeviceProtocol.Sensor> result)throws Exception{
        Set<String> incomplete=new HashSet<>();
        switch(profile.id()){
            case "huawei-entity-extent" -> {
                group(session,flags,incomplete,List.of("cpu_percent"),f->percent(session,HUAWEI+"5","cpu_percent",entities,result,f));
                group(session,flags,incomplete,List.of("memory_percent"),f->percent(session,HUAWEI+"7","memory_percent",entities,result,f));
                group(session,flags,incomplete,List.of("temperature_celsius"),f->{var values=session.walk(HUAWEI+"11",64,"SNMP_SENSOR_LIMIT");
                    values.forEach((i,v)->{Long n=number(v);if(n!=null && n>=-100 && n<=200)add(result,"temperature_celsius","°C",n.doubleValue(),HUAWEI+"11."+i,label(entities,i));else f.add("SNMP_SENSOR_VALUE_INVALID");});});
            }
            case "h3c-entity-extent" -> {
                group(session,flags,incomplete,List.of("cpu_percent"),f->percent(session,H3C+"6","cpu_percent",entities,result,f));
                group(session,flags,incomplete,List.of("memory_percent"),f->percent(session,H3C+"8","memory_percent",entities,result,f));
                // h3cEntityExtTemperature.12 units are not proven for every generation: no fabricated °C.
            }
            case "cisco-process-entity", "cisco-asr1002x-entity" -> {
                group(session,flags,incomplete,List.of("cpu_percent","memory_percent"),f->cisco(session,entities,result,f));
                group(session,flags,incomplete,List.of("temperature_celsius"),f->sensorTable(session,"1.3.6.1.4.1.9.9.91.1.1.1.1.",profile.id().equals("cisco-asr1002x-entity")?8:6,entities,result,f));
            }
            case "dell-os9-s6100-chassis" -> {
                group(session,flags,incomplete,List.of("cpu_percent"),f->dell(session,result,f,"1","cpu_percent"));
                group(session,flags,incomplete,List.of("memory_percent"),f->dell(session,result,f,"6","memory_percent"));
            }
            default -> { /* Dell enterprise roots identify the vendor, not a validated private MIB layout. */ }
        }
        group(session,flags,incomplete,List.of("temperature_celsius"),f->standardSensors(session,entities,result,f));
        return incomplete;
    }
    @FunctionalInterface private interface SensorGroup {void read(Set<String> flags)throws Exception;}
    private static void group(SnmpSession s,Set<String> flags,Set<String> incomplete,List<String> metrics,SensorGroup read)throws Exception{
        int checkpoint=s.issues();var local=new LinkedHashSet<String>();read.read(local);
        if(s.issues()!=checkpoint||!local.isEmpty())incomplete.addAll(metrics);flags.addAll(local);
    }
    private static void dell(SnmpSession s,List<DeviceProtocol.Sensor> result,Set<String> flags,String column,String metric)throws Exception{
        // DELL-NETWORKING-CHASSIS-MIB 9.14.2.1: (deviceType, deviceIndex, processorIndex).
        // Column 6 is memory percent, never temperature; no private temperature unit is inferred.
        String base="1.3.6.1.4.1.6027.3.26.1.4.4.1.";
            s.walkIndexed(base+column,3,64,"SNMP_SENSOR_LIMIT").forEach((index,value)->{
                Long n=number(value);
                if(n!=null&&n>=0&&n<=100)add(result,metric,"%",n.doubleValue(),base+column+"."+index,"Processor "+index+(column.equals("1")?" · 5 s":""));
                else flags.add("SNMP_SENSOR_VALUE_INVALID");
            });
    }
    private static void percent(SnmpSession s,String column,String metric,Map<String,Variable> entities,List<DeviceProtocol.Sensor> result,Set<String> flags)throws Exception{
        s.walk(column,64,"SNMP_SENSOR_LIMIT").forEach((i,v)->{
            Long n=number(v);if(n!=null && n>=0 && n<=100)add(result,metric,"%",n.doubleValue(),column+"."+i,label(entities,i));
            else flags.add("SNMP_SENSOR_VALUE_INVALID");
        });
    }
    private static void cisco(SnmpSession s,Map<String,Variable> entities,List<DeviceProtocol.Sensor> result,Set<String> flags)throws Exception{
        var indexes=s.walk(CISCO+"2",64,"SNMP_SENSOR_LIMIT");
        var data=getColumns(s,indexes.keySet(),List.of(CISCO+"10",CISCO+"9",CISCO+"6",CISCO+"12",CISCO+"13",CISCO+"17",CISCO+"19"));
        for(var entry:indexes.entrySet()){
            int i=entry.getKey();Long physical=number(entry.getValue());String label=physical==null?"Processor "+i:label(entities,physical.intValue());
            Long cpu=number(data.get(CISCO+"10."+i)),window=number(data.get(CISCO+"9."+i));String source=CISCO+"10."+i;
            if(cpu==null || window==null || window<=0){cpu=number(data.get(CISCO+"6."+i));source=CISCO+"6."+i;}
            if(cpu!=null){if(cpu>=0 && cpu<=100)add(result,"cpu_percent","%",cpu.doubleValue(),source,label);else flags.add("SNMP_SENSOR_VALUE_INVALID");}
            String used=unsigned(data.get(CISCO+"17."+i)),free=unsigned(data.get(CISCO+"19."+i));
            String memorySource=CISCO+"17."+i+" + "+CISCO+"19."+i;
            if(used==null || free==null){used=unsigned(data.get(CISCO+"12."+i));free=unsigned(data.get(CISCO+"13."+i));memorySource=CISCO+"12."+i+" + "+CISCO+"13."+i;}
            if(used!=null && free!=null){
                double u=Double.parseDouble(used),f=Double.parseDouble(free);
                if(u+f>0)add(result,"memory_percent","%",100*u/(u+f),memorySource,label);
            }
        }
    }
    private static void standardSensors(SnmpSession s,Map<String,Variable> entities,List<DeviceProtocol.Sensor> result,Set<String> flags)throws Exception{
        sensorTable(s,"1.3.6.1.2.1.99.1.1.1.",8,entities,result,flags);
    }
    private static void sensorTable(SnmpSession s,String base,int celsiusType,Map<String,Variable> entities,List<DeviceProtocol.Sensor> result,Set<String> flags)throws Exception{
        var types=s.walk(base+"1",256,"SNMP_SENSOR_LIMIT");
        if(base.equals("1.3.6.1.2.1.99.1.1.1.")){
            // Cisco publishes the same physical sensor through both MIBs. Preserve the
            // vendor observation (and its threshold join), then use standard rows only
            // for physical entities without a usable vendor observation.
            String vendorValue="1.3.6.1.4.1.9.9.91.1.1.1.1.4.";
            var covered=result.stream().map(DeviceProtocol.Sensor::sourceRef).filter(ref->ref.startsWith(vendorValue)).collect(java.util.stream.Collectors.toSet());
            types.entrySet().removeIf(entry->covered.contains(vendorValue+entry.getKey()));
        }
        var data=getColumns(s,types.keySet(),List.of(base+"2",base+"3",base+"4",base+"5"));
        for(var entry:types.entrySet()){
            Long type=number(entry.getValue());if(type==null)continue;
            String metric=null,unit=null;
            if(type==celsiusType){metric="temperature_celsius";unit="Cel";}
            else if(type==(celsiusType==6?4:6)){metric="power_watts";unit="W";}
            else if(type==(celsiusType==6?3:5)){metric="current_amps";unit="A";}
            else if(type==(celsiusType==6?1:3)||type==(celsiusType==6?2:4)){metric="voltage_volts";unit="V";}
            else if(celsiusType==8&&type==10){metric="fan_rpm";unit="RPM";}
            else if(celsiusType==8&&base.startsWith("1.3.6.1.4.1.9.")&&type==14){metric="optical_power_dbm";unit="dBm";}
            if(metric==null)continue;
            int i=entry.getKey();Long scale=number(data.get(base+"2."+i)),precision=number(data.get(base+"3."+i)),value=number(data.get(base+"4."+i)),status=number(data.get(base+"5."+i));
            if(!Objects.equals(status,1L) || scale==null || scale<1 || scale>17 || precision==null || precision<-8 || precision>9 || value==null){flags.add("SNMP_SENSOR_VALUE_INVALID");continue;}
            double normalized=value*Math.pow(10,(scale-9)*3-precision);
            if(Double.isFinite(normalized) && (metric.equals("temperature_celsius")?normalized>=-100&&normalized<=200:metric.equals("optical_power_dbm")?normalized>=-200&&normalized<=100:metric.equals("voltage_volts")?Math.abs(normalized)<=1_000_000:normalized>=0))add(result,metric,unit,normalized,base+"4."+i,label(entities,i));
            else flags.add("SNMP_SENSOR_VALUE_INVALID");
        }
    }
    private static String label(Map<String,Variable> entities,int index){String value=atText(entities,ENTITY,index);return value==null?"Entity "+index:value;}
    private static void add(List<DeviceProtocol.Sensor> result,String metric,String unit,double value,String source,String label){
        result.add(new DeviceProtocol.Sensor("snmp:"+source,label,metric,unit,value,"UNKNOWN",source));
    }
}
