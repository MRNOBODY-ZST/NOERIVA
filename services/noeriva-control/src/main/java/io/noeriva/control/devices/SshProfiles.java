package io.noeriva.control.devices;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.devices.DeviceProtocol.*;
import static io.noeriva.control.devices.SshDriver.failure;

/** Fixed commands and bounded parsers. Source text never leaves this read in API facts. */
final class SshProfiles {
    private static final JsonMapper JSON=new JsonMapper();
    private static final Pattern NUMBER=Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)");
    private SshProfiles(){}
    static List<String> commands(String profile){
        if(profile==null)throw failure("SSH_INVALID_SETTINGS");
        return switch(profile){
            case "HUAWEI_IMANA"->List.of("ipmcget -d version","ipmcget -d fruinfo","ipmcget -d health","ipmcget -t sensor -d list");
            case "DELL_OS9"->List.of("show version","show inventory","show environment","show arp","show lldp neighbors detail");
            case "CISCO_IOS_XE"->List.of("show version","show inventory","show environment all","show ip arp","show lldp neighbors detail","show cdp neighbors detail");
            default->throw failure("SSH_INVALID_SETTINGS");
        };
    }
    static boolean allowedCommand(String profile,String command){
        return commands(profile).contains(command) || Set.of("DELL_OS9","CISCO_IOS_XE").contains(profile) && command.equals("show running-config");
    }
    static Reading read(SshSession session,String profile,String password)throws Exception{
        Map<String,String> replies=new LinkedHashMap<>();Set<String> flags=new LinkedHashSet<>();
        for(String command:commands(profile)){
            String result=session.command(command).replace(password,"[REDACTED]");
            if(Pattern.compile("(?im)(invalid input|unknown command|unsupported command|command not found|not supported)").matcher(result).find()){flags.add("SSH_COMMAND_UNSUPPORTED");continue;}
            if(Pattern.compile("(?im)(permission denied|not authorized|authorization failed|insufficient privilege|access denied)").matcher(result).find()){flags.add("SSH_COMMAND_FORBIDDEN");continue;}
            replies.put(command,result);
        }
        return parse(profile,replies,flags);
    }
    static Reading parse(String profile,Map<String,String> replies,Set<String> initialFlags){
        Set<String> flags=new LinkedHashSet<>(initialFlags);List<String> caps=new ArrayList<>();Map<String,String> facts=new LinkedHashMap<>();
        Map<String,Double> metrics=new LinkedHashMap<>();List<Sensor> sensors=new ArrayList<>();List<Neighbor> neighbors=new ArrayList<>();
        String version=replies.getOrDefault(profile.equals("HUAWEI_IMANA")?"ipmcget -d version":"show version","");
        if(version.isBlank())throw failure("SSH_IDENTITY_UNAVAILABLE");
        String vendor,family,model=null,serial=null,firmware=null,health="UNKNOWN";
        switch(profile){
            case "HUAWEI_IMANA"->{
                if(!version.contains("iMana"))throw failure("SSH_PROFILE_MISMATCH");vendor="Huawei";family="iMana";
                firmware=field(version,"Active\\s+iMana\\s+Version");
                String fru=replies.getOrDefault("ipmcget -d fruinfo","");model=field(fru,"Product Name");serial=field(fru,"Product Serial(?: Number)?");
                if(serial!=null)facts.put("serialKind","productSerial");
                String status=replies.get("ipmcget -d health");
                if(status!=null){if(Pattern.compile("(?im)^System in health state\\.?\\s*$").matcher(status).find()){health="HEALTHY";caps.add("health");}else flags.add("SSH_HEALTH_UNRECOGNIZED");}
                if(replies.containsKey("ipmcget -t sensor -d list")){parseSensors(replies.get("ipmcget -t sensor -d list"),sensors,flags);if(!sensors.isEmpty())caps.add("sensors");}
            }
            case "DELL_OS9"->{
                if(!version.toLowerCase(Locale.ROOT).contains("dell")&&!version.contains("Force10"))throw failure("SSH_PROFILE_MISMATCH");vendor="Dell";family="Networking OS9";
                model=field(version,"System Type");firmware=field(version,"Dell(?: EMC)? Application Software Version");
                String inventory=replies.getOrDefault("show inventory","");
                serial=field(inventory,"Serial Number");
                if(serial==null)serial=dellManagementColumn(inventory,"Serial Number","Part Number");
                if(serial!=null)facts.put("serialKind","serialNumber");
                if(serial==null){serial=field(inventory,"(?:Service Tag|Svc Tag)");if(serial!=null)facts.put("serialKind","serviceTag");}
                if(serial==null){serial=dellManagementColumn(inventory,"Svc Tag","Exprs Svc Code");if(serial!=null&&serial.matches("[a-zA-Z0-9]{7}"))facts.put("serialKind","serviceTag");else serial=null;}
            }
            case "CISCO_IOS_XE"->{
                if(!version.contains("Cisco IOS"))throw failure("SSH_PROFILE_MISMATCH");vendor="Cisco";family="IOS XE";
                firmware=match(version,"(?im)^Cisco IOS XE Software, Version\\s+([^\\r\\n]+)");
                if(firmware==null)firmware=match(version,"(?i)Version\\s+([0-9][^,\\s]*)");
                String inventory=replies.getOrDefault("show inventory","");
                var chassis=Pattern.compile("(?im)^NAME:\\s*\"?Chassis\"?[^\\n]*\\n\\s*PID:\\s*([^,\\n]+),\\s*VID:[^,\\n]*,\\s*SN:\\s*([^\\s,]+)").matcher(inventory);
                if(chassis.find()){model=clean(chassis.group(1));serial=clean(chassis.group(2));facts.put("serialKind","chassisSerial");}
                if(model==null)model=match(version,"(?im)^cisco\\s+(\\S+)\\s+.*processor");
            }
            default->throw failure("SSH_INVALID_SETTINGS");
        }
        if(profile.equals("HUAWEI_IMANA")){
            String sensorHealth=NetworkEnvironment.health(sensors,flags);
            if(Set.of("WARNING","CRITICAL").contains(sensorHealth))health=sensorHealth;
        }
        caps.addFirst("identity");facts.put("sshProfile",profile);
        if(!profile.equals("HUAWEI_IMANA")){
            String environment=replies.get(profile.equals("DELL_OS9")?"show environment":"show environment all");
            if(environment!=null){NetworkEnvironment.parse(profile,environment,sensors,metrics,facts,flags);health=NetworkEnvironment.health(sensors,flags);if(!health.equals("UNKNOWN"))caps.add("health");if(!sensors.isEmpty())caps.add("sensors");}
        }
        if(!profile.equals("HUAWEI_IMANA")){
            String arp=replies.get(profile.equals("DELL_OS9")?"show arp":"show ip arp");
            if(arp!=null){parseArp(arp,profile,neighbors,flags);if(arp.contains("Hardware")||arp.toLowerCase(Locale.ROOT).contains("no arp"))caps.add("arp-neighbors");else flags.add("SSH_ARP_UNRECOGNIZED");}
            String lldp=replies.get("show lldp neighbors detail");if(lldp!=null){parseLldp(lldp,profile,neighbors,flags);if(lldp.contains("Chassis")||lldp.toLowerCase(Locale.ROOT).matches("(?s).*(?:total entries displayed|no (?:lldp )?neighbors).*"))caps.add("lldp-neighbors");else flags.add("SSH_LLDP_UNRECOGNIZED");}
            String cdp=replies.get("show cdp neighbors detail");if(cdp!=null){parseCdp(cdp,neighbors,flags);if(cdp.contains("Device ID:")||cdp.toLowerCase(Locale.ROOT).contains("total cdp entries"))caps.add("cdp-neighbors");else if(cdp.toLowerCase(Locale.ROOT).contains("cdp is not enabled"))facts.put("cdpStatus","DISABLED_ON_DEVICE");else flags.add("SSH_CDP_UNRECOGNIZED");}
        }
        sensors.stream().filter(s->s.metric().equals("temperature_celsius")&&s.value()!=null).mapToDouble(Sensor::value).max().ifPresent(v->{metrics.put("temperature_celsius",v);facts.put("temperatureSummary","Maximum of available observed sensors; not an inlet-specific or average temperature");});
        if(profile.equals("HUAWEI_IMANA")&&!flags.contains("SSH_SENSOR_LIMIT")){
            var power=sensors.stream().filter(sensor->sensor.metric().equals("power_watts")).toList();
            if(!power.isEmpty()&&power.stream().allMatch(sensor->sensor.label().matches("(?i)Power[0-9]+")&&sensor.value()!=null)){
                metrics.put("power_watts",power.stream().mapToDouble(Sensor::value).sum());facts.put("powerSummary","sum_of_observed_numbered_psu_power_sensors");
            }
        }
        facts.put("neighborObservations",JSON.writeValueAsString(neighbors));
        facts.put("sensorCount",Integer.toString(sensors.size()));
        return new Reading(Instant.now(),new Identity(vendor,family,profile,model,serial,firmware,null,null,null),health,Map.copyOf(metrics),List.copyOf(sensors),List.of(),List.copyOf(caps),List.copyOf(flags),Map.copyOf(facts));
    }
    private static void parseSensors(String text,List<Sensor> sensors,Set<String> flags){
        int row=0;
        for(String line:text.split("\\n")){
            String[] cells=line.split("\\|",-1);if(cells.length<12||cells[0].trim().equalsIgnoreCase("sensor name"))continue;row++;
            String label=clean(cells[0]),unit=cells[2].trim(),raw=cells[1].trim(),status=cells[3].trim();if(label==null)continue;
            String metric,normalized;
            switch(unit.toLowerCase(Locale.ROOT)){
                case "degrees c"->{metric="temperature_celsius";normalized="Cel";}
                case "rpm"->{metric="fan_rpm";normalized="RPM";}
                case "watts"->{metric="power_watts";normalized="W";}
                case "volts"->{metric="voltage_volts";normalized="V";}
                case "amps"->{metric="current_amps";normalized="A";}
                case "discrete","unspecified"->{metric="sensor_state";normalized=unit;}
                default->{flags.add("SSH_SENSOR_UNIT_UNKNOWN");continue;}
            }
            Double value=metric.equals("sensor_state")?null:number(raw);
            if(value!=null&&(metric.equals("temperature_celsius")?(value < -100||value > 200):value<0)){value=null;flags.add("SSH_SENSOR_VALUE_INVALID");}
            if(value==null&&!metric.equals("sensor_state")&&!raw.equalsIgnoreCase("na")&&!raw.equalsIgnoreCase("n/a"))flags.add("SSH_SENSOR_VALUE_INVALID");
            if(sensors.size()>=128){flags.add("SSH_SENSOR_LIMIT");break;}
            String source="ssh:ipmcget -t sensor -d list#row="+row;
            String id=UUID.nameUUIDFromBytes((label+"/"+normalized+"/"+row).getBytes(StandardCharsets.UTF_8)).toString();
            sensors.add(new Sensor(id,label,metric,normalized,value,value==null&&!metric.equals("sensor_state")?"UNKNOWN":sensorHealth(status),source));
        }
        if(row==0)flags.add("SSH_SENSOR_TABLE_UNRECOGNIZED");
    }
    private static String sensorHealth(String status){return switch(status.toLowerCase(Locale.ROOT)){case "ok"->"HEALTHY";case "nc","lnc","unc","warning","non-critical"->"WARNING";case "cr","lc","uc","nr","lnr","unr","critical","non-recoverable"->"CRITICAL";default->"UNKNOWN";};}
    record Neighbor(String address,String mac,String source,String interfaceName,String vlan,String name,String chassisId,String chassisSubtype,String portId,Long ttlSeconds,Long ageMinutes){}
    private static void parseArp(String text,String profile,List<Neighbor> out,Set<String> flags){
        Pattern rows=profile.equals("DELL_OS9")?Pattern.compile("^Internet\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(.+?)\\s+(Vl\\s+[0-9]+|-)\\s+\\S+\\s*$"):
            Pattern.compile("^Internet\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(ARPA)\\s+(\\S+)\\s*$");
        for(String line:text.split("\\n")){
            var m=rows.matcher(line.trim());if(!m.matches())continue;String ip=address(m.group(1)),mac=mac(m.group(3));
            if(ip==null||mac==null){flags.add("SSH_ARP_INCOMPLETE");continue;}
            String iface=clean(m.group(profile.equals("DELL_OS9")?4:5)),vlan=profile.equals("DELL_OS9")?clean(m.group(5)):null;
            if(vlan!=null)vlan=vlan.replaceFirst("^Vl\\s+","");
            add(out,new Neighbor(ip,mac,"ARP",iface,vlan,null,null,null,null,null,positive(m.group(2))),flags);
        }
    }
    private static void parseLldp(String text,String profile,List<Neighbor> out,Set<String> flags){
        boolean dell=profile.equals("DELL_OS9");var current=new LinkedHashMap<String,String>();String local=null;
        for(String line:text.split("\\n")){
            String t=line.trim();
            if(dell&&t.startsWith("Local Interface ")){flushNeighbor(current,local,"LLDP",out,flags);current.clear();local=match(t,"^Local Interface (.*?) has [0-9]+ neighbor");}
            if(dell&&t.startsWith("Remote Chassis ID Subtype:")){flushNeighbor(current,local,"LLDP",out,flags);current.clear();}
            if(!dell&&t.startsWith("Local Intf:")){flushNeighbor(current,local,"LLDP",out,flags);current.clear();local=after(t);}
            if(t.startsWith(dell?"Remote Chassis ID:":"Chassis id:"))current.put("chassis",after(t));
            else if(t.startsWith("Remote Chassis ID Subtype:"))current.put("subtype",after(t));
            else if(t.startsWith(dell?"Remote Port ID:":"Port id:"))current.put("port",after(t));
            else if(t.startsWith("Local Port ID:"))current.put("local",after(t));
            else if(t.startsWith(dell?"Remote System Name:":"System Name:"))current.put("name",after(t));
            else if(t.startsWith("Remote TTL:")||t.startsWith("Time remaining:"))current.put("ttl",after(t).split("\\s+")[0]);
            else if(dell&&t.matches("(?i)Information valid for next [0-9]+ seconds\\s*"))current.put("remainingTtl",match(t,"(?i)valid for next ([0-9]+) seconds"));
            else if(t.startsWith("Remote Management Address (IPv4):")||t.startsWith("IP:"))current.put("address",after(t));
            else if(t.startsWith("Remote Management Address (IPv6):")&&!current.containsKey("address"))current.put("address",after(t));
        }
        flushNeighbor(current,local,"LLDP",out,flags);
    }
    private static void parseCdp(String text,List<Neighbor> out,Set<String> flags){
        var current=new LinkedHashMap<String,String>();
        for(String line:text.split("\\n")){
            String t=line.trim();
            if(t.startsWith("Device ID:")){flushNeighbor(current,null,"CDP",out,flags);current.clear();current.put("name",after(t));}
            else if(t.startsWith("IP address:"))current.put("address",after(t));
            else if(t.startsWith("Interface:")){var pair=Pattern.compile("^Interface:\\s*([^,]+),\\s*Port ID \\(outgoing port\\):\\s*(.+)$").matcher(t);if(pair.matches()){current.put("local",pair.group(1));current.put("port",pair.group(2));}}
            else if(t.startsWith("Holdtime"))current.put("ttl",after(t).split("\\s+")[0]);
        }
        flushNeighbor(current,null,"CDP",out,flags);
    }
    private static void flushNeighbor(Map<String,String> values,String local,String source,List<Neighbor> out,Set<String> flags){
        String chassis=clean(values.get("chassis")),port=clean(values.get("port")),iface=clean(values.getOrDefault("local",local));
        if(values.isEmpty())return;if(port==null||iface==null||source.equals("LLDP")&&chassis==null){flags.add("SSH_NEIGHBOR_INCOMPLETE");return;}
        String subtype=clean(values.get("subtype")),mac=subtype==null||subtype.toLowerCase(Locale.ROOT).contains("mac")?mac(chassis):null;
        add(out,new Neighbor(address(values.get("address")),mac,source,iface,null,clean(values.get("name")),chassis,subtype,port,positive(values.getOrDefault("remainingTtl",values.get("ttl"))),null),flags);
    }
    private static void add(List<Neighbor> out,Neighbor n,Set<String> flags){if(out.size()>=256){flags.add("SSH_NEIGHBOR_LIMIT");return;}if(!out.contains(n))out.add(n);}
    private static String field(String text,String name){return match(text,"(?im)^\\s*"+name+"\\s*:\\s*([^\\r\\n]+)");}
    private static String dellManagementColumn(String text,String column,String next){
        int start=-1,end=-1;
        for(String line:text.split("\\n")){
            if(line.contains("Unit Type")&&line.contains("Serial Number")&&line.contains("Svc Tag")){start=line.indexOf(column);end=line.indexOf(next);continue;}
            if(start>=0&&end>start&&line.length()>=end&&line.matches("\\s*\\*\\s+[0-9]+\\s+.*"))return clean(line.substring(start,end));
        }
        return null;
    }
    private static String match(String text,String regex){var m=Pattern.compile(regex).matcher(text);return m.find()?clean(m.group(1)):null;}
    private static String after(String text){return text.substring(text.indexOf(':')+1).trim();}
    private static String clean(String value){if(value==null)return null;String s=value.strip();if(s.isEmpty()||s.equals("-")||s.equalsIgnoreCase("na")||s.equalsIgnoreCase("n/a"))return null;return s.length()>240?s.substring(0,240):s;}
    private static Double number(String text){try{if(!NUMBER.matcher(text).matches())return null;double n=Double.parseDouble(text);return Double.isFinite(n)?n:null;}catch(NumberFormatException e){return null;}}
    private static Long positive(String text){try{if(text==null||!text.matches("[0-9]{1,12}"))return null;return Long.parseLong(text);}catch(NumberFormatException e){return null;}}
    private static String address(String text){if(text==null)return null;try{return InetAddress.ofLiteral(text).getHostAddress();}catch(IllegalArgumentException e){return null;}}
    private static String mac(String text){
        if(text==null||!text.matches("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}|[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}"))return null;
        String raw=text.replace(":","").replace("-","").replace(".","").toLowerCase(Locale.ROOT);var result=new StringJoiner(":");for(int i=0;i<12;i+=2)result.add(raw.substring(i,i+2));return result.toString();
    }
}
