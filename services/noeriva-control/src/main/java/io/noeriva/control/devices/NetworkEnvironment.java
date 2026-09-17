package io.noeriva.control.devices;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Parsers for documented and hardware-observed read-only environmental commands. */
final class NetworkEnvironment {
    private NetworkEnvironment() {}
    static void parse(String profile,String output,List<DeviceProtocol.Sensor> sensors,Map<String,Double> metrics,Map<String,String> facts,Set<String> flags){
        int before=sensors.size();
        if(profile.equals("DELL_OS9"))dell(output,sensors,metrics,facts,flags);else cisco(output,sensors,flags);
        if(sensors.size()==before)flags.add("SSH_ENVIRONMENT_UNRECOGNIZED");
        facts.put("healthBasis","observed_environment_component_states");
    }
    static String health(List<DeviceProtocol.Sensor> sensors,Set<String> flags){
        if(sensors.stream().anyMatch(s->s.health().equals("CRITICAL")))return "CRITICAL";
        if(sensors.stream().anyMatch(s->s.health().equals("WARNING")))return "WARNING";
        if(flags.stream().anyMatch(f->f.startsWith("SSH_ENVIRONMENT_")))return "UNKNOWN";
        return sensors.stream().anyMatch(s->s.health().equals("HEALTHY"))?"HEALTHY":"UNKNOWN";
    }
    private static void dell(String text,List<DeviceProtocol.Sensor> out,Map<String,Double> metrics,Map<String,String> facts,Set<String> flags){
        String section="";String[] thermalColumns=null;double watts=0;int supplies=0;boolean incompletePower=false;
        for(String raw:text.split("\\n")){
            String line=raw.strip();
            if(line.contains("--")&&line.contains("Fan  Status")){section="fan";continue;}
            if(line.contains("--")&&line.contains("Power Supplies")){section="power";continue;}
            if(line.contains("--")&&line.contains("Unit Environment")){section="unit";continue;}
            if(line.contains("--")&&line.contains("Optional Modules")){section="module";continue;}
            if(line.contains("--")&&line.contains("Thermal Sensor")){section="thermal";continue;}
            if(section.equals("thermal")&&line.startsWith("Unit ")){thermalColumns=line.split("\\s+");continue;}
            if(!line.matches("\\*?\\s*[0-9]+\\s+.*"))continue;
            String[] c=line.replaceFirst("^\\*\\s*","").split("\\s+");
            String source="ssh:show environment#"+section+"/"+c[0]+"/"+(c.length>1?c[1]:"");
            switch(section){
                case "fan" -> {
                    if(c.length<5){flags.add("SSH_ENVIRONMENT_ROW_INVALID");break;}
                    add(out,"Fan "+c[0]+"/"+c[1],"fan_rpm","RPM",numeric(c[4]),state(c[3]),source+"/rpm",flags);
                    add(out,"Fan tray "+c[0]+"/"+c[1],"componentHealth","state",null,state(c[2]),source+"/state",flags);
                }
                case "power" -> {
                    if(c.length<7){incompletePower=true;flags.add("SSH_ENVIRONMENT_ROW_INVALID");break;}
                    String name="PSU "+c[0]+"/"+c[1];Double power=numeric(c[6]);
                    add(out,name,"power_watts","W",power,state(c[2]),source+"/watts",flags);
                    add(out,name+" fan","fan_rpm","RPM",numeric(c[5]),state(c[4]),source+"/fan",flags);
                    if(power==null||power<0||power>100000)incompletePower=true;else{watts+=power;supplies++;}
                }
                case "unit" -> {
                    if(c.length<4||!c[2].matches("-?[0-9]+(?:\\.[0-9]+)?C")){flags.add("SSH_ENVIRONMENT_ROW_INVALID");break;}
                    add(out,"Unit "+c[0],"temperature_celsius","Cel",numeric(c[2].substring(0,c[2].length()-1)),"UNKNOWN",source+"/temperature",flags);
                    String unitHealth=state(c[1]),voltageHealth=state(c[3]);
                    add(out,"Unit "+c[0]+" operation / voltage","componentHealth","state",null,worst(unitHealth,voltageHealth),source+"/state",flags);
                }
                case "module" -> {if(c.length==3)add(out,"Module "+c[0]+"/"+c[1],"temperature_celsius","Cel",numeric(c[2]),"UNKNOWN",source+"/temperature",flags);else flags.add("SSH_ENVIRONMENT_ROW_INVALID");}
                case "thermal" -> {
                    if(thermalColumns==null||c.length!=thermalColumns.length){flags.add("SSH_ENVIRONMENT_ROW_INVALID");break;}
                    for(int i=1;i<c.length;i++)add(out,"Unit "+c[0]+" · "+thermalColumns[i],"temperature_celsius","Cel",numeric(c[i]),"UNKNOWN","ssh:show environment#thermal/"+c[0]+"/"+thermalColumns[i],flags);
                }
            }
        }
        if(supplies>0&&!incompletePower){metrics.put("power_watts",watts);facts.put("powerSummary","sum_of_observed_psu_power_usage_watts");}
    }
    private static void cisco(String text,List<DeviceProtocol.Sensor> out,Set<String> flags){
        boolean table=false;
        for(String raw:text.split("\\n")){
            String line=raw.strip();if(line.startsWith("Sensor List:")){table=true;continue;}
            if(!table||line.isEmpty()||line.startsWith("Sensor "))continue;
            String[] c=line.split("\\s{2,}");if(c.length!=4){flags.add("SSH_ENVIRONMENT_ROW_INVALID");continue;}
            Matcher m=Pattern.compile("^(-?[0-9]+(?:\\.[0-9]+)?)\\s+(Celsius|mV|V DC|V AC|V|A|W|Watts|RPM|%)$",Pattern.CASE_INSENSITIVE).matcher(c[3]);
            if(!m.matches()){flags.add("SSH_ENVIRONMENT_UNIT_UNKNOWN");continue;}
            double value=Double.parseDouble(m.group(1));String unit=m.group(2).toLowerCase(Locale.ROOT),metric,normalized;
            switch(unit){
                case "celsius" -> {metric="temperature_celsius";normalized="Cel";}
                case "mv","v dc","v ac","v" -> {metric="voltage_volts";normalized="V";if(unit.equals("mv"))value/=1000;}
                case "a" -> {metric="current_amps";normalized="A";}
                case "w","watts" -> {metric="power_watts";normalized="W";}
                case "rpm" -> {metric="fan_rpm";normalized="RPM";}
                default -> {metric="fan_percent";normalized="%";}
            }
            add(out,c[1]+" · "+c[0],metric,normalized,value,state(c[2]),"ssh:show environment all#"+c[1]+"/"+c[0],flags);
        }
    }
    private static String state(String value){return switch(value.toLowerCase(Locale.ROOT)){case "up","ok","online","normal"->"HEALTHY";case "warning","minor","degraded"->"WARNING";case "down","failed","fault","critical","shutdown","major"->"CRITICAL";default->"UNKNOWN";};}
    private static String worst(String first,String second){return List.of(first,second).contains("CRITICAL")?"CRITICAL":List.of(first,second).contains("WARNING")?"WARNING":first.equals("HEALTHY")&&second.equals("HEALTHY")?"HEALTHY":"UNKNOWN";}
    private static Double numeric(String raw){try{double v=Double.parseDouble(raw);return Double.isFinite(v)?v:null;}catch(NumberFormatException e){return null;}}
    private static void add(List<DeviceProtocol.Sensor> out,String label,String metric,String unit,Double value,String health,String source,Set<String> flags){
        if(!metric.equals("componentHealth")&&(value==null||!Double.isFinite(value)||metric.equals("temperature_celsius")&&(value < -100||value>200)||!metric.equals("temperature_celsius")&&value<0)){flags.add("SSH_ENVIRONMENT_VALUE_INVALID");return;}
        if(out.size()>=128){flags.add("SSH_ENVIRONMENT_SENSOR_LIMIT");return;}
        out.add(new DeviceProtocol.Sensor(UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString(),label,metric,unit,value,health,source));
    }
}
