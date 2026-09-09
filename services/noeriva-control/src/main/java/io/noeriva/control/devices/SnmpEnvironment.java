package io.noeriva.control.devices;

import java.util.*;
import java.util.regex.Pattern;
import org.snmp4j.smi.Variable;
import static io.noeriva.control.devices.SnmpDriver.*;

/** Vendor facts supplement standard objects only after product/profile identification. */
final class SnmpEnvironment {
    private static final String DELL="1.3.6.1.4.1.6027.3.26.1.";
    private SnmpEnvironment() {}
    static String softwareVersion(SnmpProfiles.Profile profile,String description){
        if(description==null)return null;
        String regex=switch(profile.vendor()){
            case "Dell" -> "(?im)^Application Software Version:\\s*([^\\r\\n]+)";
            case "Cisco" -> "(?i)(?:Cisco IOS XE Software, Version|Version)\\s+([0-9][0-9A-Za-z.()_-]*)";
            default -> null;
        };
        if(regex==null)return null;var match=Pattern.compile(regex).matcher(description);return match.find()?identityText(match.group(1).strip()):null;
    }
    static void collect(SnmpSession session,SnmpProfiles.Profile profile,List<DeviceProtocol.Sensor> sensors,Map<String,String> facts,Set<String> flags,Map<Integer,Variable> classes,List<DeviceProtocol.Port> ports)throws Exception{
        if(profile.id().equals("huawei-server-bmc"))SnmpHuaweiBmc.collect(session,facts,flags,sensors);
        if(profile.id().equals("dell-os9-s6100-chassis"))dell(session,sensors,facts,flags);
        if(Set.of("cisco-process-entity","cisco-asr1002x-entity").contains(profile.id()))ciscoThresholds(session,sensors,facts,flags,classes,ports);
    }
    static String health(List<DeviceProtocol.Sensor> sensors,Set<String> flags,Map<String,String> facts){
        var excluded=DeviceObservationSummary.excludedSensors(facts);
        sensors=sensors.stream().filter(sensor->!excluded.contains(sensor.id())).toList();
        if(sensors.stream().anyMatch(s->s.health().equals("CRITICAL")))return "CRITICAL";
        if(sensors.stream().anyMatch(s->s.health().equals("WARNING")))return "WARNING";
        if(flags.contains("SNMP_HEALTH_INCOMPLETE")||flags.contains("SNMP_VARIABLE_BUDGET")||flags.contains("SNMP_SENSOR_LIMIT"))return "UNKNOWN";
        return sensors.stream().anyMatch(s->s.health().equals("HEALTHY"))?"HEALTHY":"UNKNOWN";
    }
    private static void dell(SnmpSession s,List<DeviceProtocol.Sensor> sensors,Map<String,String> facts,Set<String> flags)throws Exception{
        int checkpoint=s.issues();String base=DELL+"3.4.1.";
        var units=s.walk(base+"8",16,"SNMP_SENSOR_LIMIT");var temperatures=getColumns(s,units.keySet(),List.of(base+"13"));
        for(var unit:units.entrySet()){
            Long state=number(unit.getValue());String health=Objects.equals(state,1L)?"HEALTHY":Objects.equals(state,5L)?"CRITICAL":state!=null&&state>=2&&state<=4?"WARNING":"UNKNOWN";
            add(sensors,"Unit "+unit.getKey(),"componentHealth","state",null,health,base+"8."+unit.getKey());
            Long temp=number(temperatures.get(base+"13."+unit.getKey()));
            // Verified S6100 OS9 unit value against `show environment` temperature with C suffix.
            if(temp!=null&&temp>=0&&temp<=200)add(sensors,"Unit "+unit.getKey(),"temperature_celsius","Cel",temp.doubleValue(),"UNKNOWN",base+"13."+unit.getKey());
            else if(temp!=null)flags.add("SNMP_SENSOR_VALUE_INVALID");
        }
        String power=DELL+"4.6.1.";var states=s.walkIndexed(power+"4",3,32,"SNMP_SENSOR_LIMIT");
        var values=s.get(states.keySet().stream().map(i->power+"10."+i).toList(),false);double total=0;boolean complete=!states.isEmpty();Set<String> powerDevices=new HashSet<>();
        for(var entry:states.entrySet()){
            String index=entry.getKey();Long state=number(entry.getValue()),watts=number(values.get(power+"10."+index));
            String health=Objects.equals(state,1L)?"HEALTHY":Objects.equals(state,2L)?"WARNING":"UNKNOWN";
            add(sensors,"PSU "+index,"componentHealth","state",null,health,power+"4."+index);
            if(Objects.equals(state,3L))continue;
            powerDevices.add(index.substring(0,index.lastIndexOf('.')));
            if(watts!=null&&watts>=0&&watts<=100000){total+=watts;add(sensors,"PSU "+index,"power_watts","W",watts.doubleValue(),health,power+"10."+index);}
            else complete=false;
        }
        String fan=DELL+"4.7.1.4";
        s.walkIndexed(fan,3,32,"SNMP_SENSOR_LIMIT").forEach((index,value)->{
            Long state=number(value);add(sensors,"Fan tray "+index,"componentHealth","state",null,Objects.equals(state,1L)?"HEALTHY":Objects.equals(state,2L)?"WARNING":"UNKNOWN",fan+"."+index);
        });
        if(s.issues()!=checkpoint)flags.add("SNMP_HEALTH_INCOMPLETE");
        if(complete&&powerDevices.size()==1&&s.issues()==checkpoint){facts.put("powerWatts",Double.toString(total));facts.put("powerSummary","sum_of_observed_psu_power_usage_watts");}
        facts.put("healthBasis","DELL_NETWORKING_CHASSIS unit_power_supply_fan_operational_states");
    }
    private static void ciscoThresholds(SnmpSession s,List<DeviceProtocol.Sensor> sensors,Map<String,String> facts,Set<String> flags,Map<Integer,Variable> classes,List<DeviceProtocol.Port> ports)throws Exception{
        int checkpoint=s.issues();String base="1.3.6.1.4.1.9.9.91.1.2.1.1.";
        var evaluations=s.walkIndexed(base+"5",2,512,"SNMP_SENSOR_LIMIT");
        var severities=s.get(evaluations.entrySet().stream().filter(e->Objects.equals(number(e.getValue()),1L)).flatMap(e->java.util.stream.Stream.of(base+"2."+e.getKey(),base+"3."+e.getKey())).toList(),false);
        Map<Integer,String> states=new HashMap<>(),badRefs=new HashMap<>();Map<Integer,Boolean> lowOnly=new HashMap<>();int triggered=0;
        for(var entry:evaluations.entrySet()){
            Long evaluation=number(entry.getValue());int index=Integer.parseInt(entry.getKey().split("\\.")[0]);
            String state="UNKNOWN";
            if(Objects.equals(evaluation,2L))state="HEALTHY";
            else if(Objects.equals(evaluation,1L)){
                Long severity=number(severities.get(base+"2."+entry.getKey()));
                state=Objects.equals(severity,30L)?"CRITICAL":"WARNING";
                triggered++;
                if(rank(state)>=rank(states.getOrDefault(index,"UNKNOWN")))badRefs.put(index,base+"5."+entry.getKey());
                Long relation=number(severities.get(base+"3."+entry.getKey()));
                lowOnly.merge(index,Objects.equals(relation,1L)||Objects.equals(relation,2L),(a,b)->a&&b);
            }
            states.merge(index,state,SnmpEnvironment::worst);
        }
        boolean complete=s.issues()==checkpoint;
        if(!complete)flags.add("SNMP_HEALTH_INCOMPLETE");
        String valueBase="1.3.6.1.4.1.9.9.91.1.1.1.1.4.";
        for(int i=0;i<sensors.size();i++){
            var sensor=sensors.get(i);if(!sensor.sourceRef().startsWith(valueBase))continue;
            String status=states.get(Integer.parseInt(sensor.sourceRef().substring(valueBase.length())));
            if(status==null||!complete&&status.equals("HEALTHY"))continue;
            sensors.set(i,new DeviceProtocol.Sensor(sensor.id(),sensor.label(),sensor.metric(),sensor.unit(),sensor.value(),status,sensor.sourceRef()));
        }
        // A threshold belongs to its physical sensor; do not duplicate each triggered
        // threshold as another sensor and exhaust the bounded sensor inventory.
        for(var entry:badRefs.entrySet())if(sensors.stream().noneMatch(sensor->sensor.sourceRef().equals(valueBase+entry.getKey())))
            add(sensors,"Entity "+entry.getKey()+" threshold","componentHealth","state",null,states.get(entry.getKey()),entry.getValue());
        facts.put("triggeredThresholdCount",Integer.toString(triggered));facts.put("triggeredThresholdEntityCount",Integer.toString(badRefs.size()));
        if(complete&&!badRefs.isEmpty())inactiveOpticalThresholds(s,sensors,facts,classes,ports,lowOnly);
        facts.put("healthBasis","CISCO_ENTITY_SENSOR threshold_evaluation; sensor_readability_is_not_health");
    }
    private static void inactiveOpticalThresholds(SnmpSession s,List<DeviceProtocol.Sensor> sensors,Map<String,String> facts,Map<Integer,Variable> classes,List<DeviceProtocol.Port> ports,Map<Integer,Boolean> lowOnly)throws Exception{
        String valueBase="1.3.6.1.4.1.9.9.91.1.1.1.1.4.",parentBase="1.3.6.1.2.1.47.1.1.1.1.4",aliasBase="1.3.6.1.2.1.47.1.3.2.1.2",ifBase="1.3.6.1.2.1.2.2.1.1.";
        var eligible=sensors.stream().filter(sensor->sensor.sourceRef().startsWith(valueBase)&&Set.of("current_amps","optical_power_dbm").contains(sensor.metric())&&Set.of("WARNING","CRITICAL").contains(sensor.health())).filter(sensor->Boolean.TRUE.equals(lowOnly.get(Integer.parseInt(sensor.sourceRef().substring(valueBase.length()))))).toList();
        if(eligible.isEmpty())return;
        int checkpoint=s.issues();var parents=s.walk(parentBase,256,"SNMP_ENTITY_LIMIT");var aliases=s.walkIndexed(aliasBase,2,256,"SNMP_ENTITY_LIMIT");
        if(s.issues()!=checkpoint)return; // Partial topology must never suppress a fault.
        var evidence=new ArrayList<Map<String,Object>>();var excluded=new LinkedHashSet<String>();
        for(var sensor:eligible){
            int entity=Integer.parseInt(sensor.sourceRef().substring(valueBase.length()));Long parent=number(parents.get(entity));
            if(!Objects.equals(number(classes.get(entity)),8L)||parent==null||!Objects.equals(number(classes.get(parent.intValue())),9L))continue;
            // The transceiver's sensor and physical port are siblings. Require exactly
            // one port in this immediate module; never infer from a chassis ancestor.
            var siblings=parents.entrySet().stream().filter(e->Objects.equals(number(e.getValue()),parent)&&Objects.equals(number(classes.get(e.getKey())),10L)).map(Map.Entry::getKey).toList();
            if(siblings.size()!=1)continue;int physicalPort=siblings.getFirst();
            String pointer=oid(aliases.get(physicalPort+".0"));
            if(pointer==null||!pointer.startsWith(ifBase)||!pointer.substring(ifBase.length()).matches("[0-9]+"))continue;
            if(aliases.keySet().stream().anyMatch(k->k.startsWith(physicalPort+".")&&!k.equals(physicalPort+".0")))continue;
            var interfaces=ports.stream().filter(p->pointer.equals(p.sourceRef())).toList();
            if(interfaces.size()!=1||!interfaces.getFirst().adminStatus().equals("DOWN"))continue;
            if(sensor.metric().equals("current_amps")&&sensors.stream().noneMatch(other->other.metric().equals("optical_power_dbm")&&other.sourceRef().startsWith(valueBase)&&Objects.equals(number(parents.get(Integer.parseInt(other.sourceRef().substring(valueBase.length())))),parent)))continue;
            excluded.add(sensor.id());evidence.add(Map.of("sensorId",sensor.id(),"entityIndex",entity,"moduleEntityIndex",parent,"portEntityIndex",physicalPort,"interfaceIndex",pointer.substring(ifBase.length()),"interfaceName",interfaces.getFirst().name(),"adminStatus","DOWN","reason","ONLY_LOW_OPTICAL_OR_BIAS_THRESHOLDS_ON_ADMINISTRATIVELY_DOWN_PORT","mappingSource",aliasBase+"."+physicalPort+".0"));
        }
        if(!excluded.isEmpty()){
            facts.put("healthExcludedSensorIds",String.join(",",excluded));
            facts.put("inactiveInterfaceThresholdEvidence",tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(evidence));
            facts.put("healthExclusionBasis","Unique ENTITY containment and ifIndex alias, explicit admin DOWN, only low optical/bias thresholds; original sensor severity retained");
        }
    }
    private static String worst(String a,String b){return rank(a)>=rank(b)?a:b;}
    private static int rank(String value){return switch(value){case "CRITICAL"->3;case "WARNING"->2;case "HEALTHY"->1;default->0;};}
    private static void add(List<DeviceProtocol.Sensor> sensors,String label,String metric,String unit,Double value,String health,String oid){sensors.add(new DeviceProtocol.Sensor("snmp:"+oid,label,metric,unit,value,health,oid));}
}
