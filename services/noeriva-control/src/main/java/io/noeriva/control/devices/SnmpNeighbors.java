package io.noeriva.control.devices;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.snmp4j.smi.*;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.devices.SnmpDriver.number;

/** Read-only LLDP-MIB and CISCO-CDP-MIB cached observations, never synthetic adjacency.
 * LLDP reference: https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/LLDP-MIB.my
 * CDP reference: https://www.cisco.com/c/en/us/td/docs/voice_ip_comm/cucm/managed_services/12_5_1/cucm_b_managed-services-guide-1251/cucm_b_managed-services-guide-1251_chapter_0110.html
 */
final class SnmpNeighbors {
    static final String REM="1.0.8802.1.1.2.1.4.1.1.", LOC="1.0.8802.1.1.2.1.3.7.1.",
        MAN="1.0.8802.1.1.2.1.4.2.1.3", CDP="1.3.6.1.4.1.9.9.23.1.2.1.1.";
    static final int NEIGHBOR_LIMIT=64, ADDRESS_LIMIT=128;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> STOP=Set.of("SNMP_TIMEOUT","SNMP_DEADLINE","SNMP_VARIABLE_BUDGET","SNMP_AUTHENTICATION_FAILED","SNMP_ACCESS_DENIED","SNMP_TRANSPORT_ERROR");
    record Neighbor(String address,String mac,String source,String interfaceName,String vlan,String name,String chassisId,String chassisSubtype,
                    String portId,Long ttlSeconds,Long ageMinutes,String transport,String sourceRef,Integer localPortNumber,Integer interfaceIndex,List<String> managementAddresses){}
    private record Local(String name,Integer ifIndex){}
    static void collect(SnmpSession session,List<DeviceProtocol.Port> ports,Map<String,String> facts,Set<String> flags,List<String> capabilities)throws Exception{
        var observations=new ArrayList<Neighbor>();
        if(stopped(flags)){facts.put("lldpStatus","INCOMPLETE");facts.put("cdpStatus","INCOMPLETE");flags.add("SNMP_NEIGHBORS_SKIPPED_COLLECTION_BUDGET_OR_ERROR");}
        else{
            phase("lldp",session,facts,flags,capabilities,observations,()->lldp(session,ports,flags));
            if(stopped(flags)){facts.put("cdpStatus","INCOMPLETE");flags.add("SNMP_CDP_SKIPPED_COLLECTION_BUDGET_OR_ERROR");}
            else phase("cdp",session,facts,flags,capabilities,observations,()->cdp(session,ports,flags));
        }
        facts.put("neighborObservations",JSON.writeValueAsString(observations));
        facts.put("neighborCollectionProtocol","SNMP");
        facts.put("neighborAddressSelection","observed_ipv4_preferred; all_observed_addresses_retained");
        // Neither table exports remote remaining TTL. Local transmit holdtime is not remote TTL.
        facts.put("neighborLifetimeBasis","agent_cache_at_collection; remote_remaining_ttl_not_exposed");
    }
    @FunctionalInterface private interface Read { List<Neighbor> get()throws Exception; }
    private static void phase(String name,SnmpSession s,Map<String,String> facts,Set<String> flags,List<String> capabilities,List<Neighbor> all,Read read)throws Exception{
        int issues=s.issues();var before=new HashSet<>(flags);var rows=read.get();all.addAll(rows);
        boolean incomplete=issues!=s.issues()||flags.stream().anyMatch(f->!before.contains(f));
        facts.put(name+"Status",incomplete?"INCOMPLETE":rows.isEmpty()?"EMPTY_OR_UNSUPPORTED":"OBSERVED");
        facts.put(name+"NeighborCount",Integer.toString(rows.size()));
        if(incomplete)flags.add("SNMP_"+name.toUpperCase(Locale.ROOT)+"_INCOMPLETE");
        else if(rows.isEmpty())flags.add("SNMP_"+name.toUpperCase(Locale.ROOT)+"_NO_OBSERVATIONS_MIB_MAY_BE_UNAVAILABLE");
        if(!rows.isEmpty())capabilities.add(name+"-neighbors");
    }
    private static List<Neighbor> lldp(SnmpSession s,List<DeviceProtocol.Port> ports,Set<String> flags)throws Exception{
        var identities=s.walkSuffixes(REM+"5",3,3,NEIGHBOR_LIMIT+1,null);
        var rows=new LinkedHashMap<String,Variable>();
        for(var e:identities.entrySet()){
            if(lldpIndex(e.getKey())==null){flags.add("SNMP_LLDP_INVALID_INDEX");continue;}
            if(rows.size()==NEIGHBOR_LIMIT){flags.add("SNMP_LLDP_NEIGHBOR_LIMIT");break;}
            rows.put(e.getKey(),e.getValue());
        }
        if(rows.isEmpty()||stopped(flags))return List.of();
        var data=columns(s,rows.keySet(),List.of(REM+"4",REM+"6",REM+"7",REM+"9"));
        var localPorts=new LinkedHashSet<String>();rows.keySet().forEach(k->localPorts.add(k.split("\\.")[1]));
        var locals=stopped(flags)?Map.<String,Variable>of():columns(s,localPorts,List.of(LOC+"2",LOC+"3"));
        var addresses=new LinkedHashMap<String,List<String>>();
        if(!stopped(flags)){
            // INDEX TimeMark.LocalPort.RemIndex.AddressFamily.Length.AddressOctets.
            var managed=s.walkSuffixes(MAN,6,36,ADDRESS_LIMIT+1,null);int seen=0;
            for(String index:managed.keySet()){
                if(++seen>ADDRESS_LIMIT){flags.add("SNMP_LLDP_ADDRESS_LIMIT");break;}
                var decoded=managementIndex(index);
                if(decoded==null){flags.add("SNMP_LLDP_INVALID_ADDRESS_INDEX");continue;}
                if(!rows.containsKey(decoded.getKey()))continue;
                var values=addresses.computeIfAbsent(decoded.getKey(),k->new ArrayList<>());
                if(!values.contains(decoded.getValue()))values.add(decoded.getValue());
            }
        }
        var result=new ArrayList<Neighbor>();
        for(var e:rows.entrySet()){
            String key=e.getKey();int localPort=lldpIndex(key)[1];
            Long chassisType=number(data.get(REM+"4."+key)),portType=number(data.get(REM+"6."+key));
            String chassis=id(e.getValue(),chassisType,true),remote=id(data.get(REM+"7."+key),portType,false);
            Local local=local(locals.get(LOC+"3."+localPort),number(locals.get(LOC+"2."+localPort)),ports);
            if(chassis==null||remote==null||local==null){flags.add("SNMP_LLDP_NEIGHBOR_IDENTITY_INCOMPLETE");continue;}
            var management=List.copyOf(addresses.getOrDefault(key,List.of()));
            String address=management.isEmpty()?(Objects.equals(chassisType,5L)?chassis:null):preferredAddress(management);
            result.add(new Neighbor(address,Objects.equals(chassisType,4L)?chassis:null,"LLDP",local.name(),null,text(data.get(REM+"9."+key)),chassis,
                chassisSubtype(chassisType),remote,null,null,"SNMP",REM+"5."+key,localPort,local.ifIndex(),management));
        }
        return result;
    }
    private static List<Neighbor> cdp(SnmpSession s,List<DeviceProtocol.Port> ports,Set<String> flags)throws Exception{
        var ids=s.walkIndexed(CDP+"6",2,NEIGHBOR_LIMIT+1,null);
        if(ids.isEmpty()||stopped(flags))return List.of();
        var rows=new LinkedHashMap<String,Variable>();
        for(var e:ids.entrySet()){
            String[] parts=e.getKey().split("\\.");
            if(Integer.parseInt(parts[0])<=0){flags.add("SNMP_CDP_INVALID_INDEX");continue;}
            if(rows.size()==NEIGHBOR_LIMIT){flags.add("SNMP_CDP_NEIGHBOR_LIMIT");break;}
            rows.put(e.getKey(),e.getValue());
        }
        var data=columns(s,rows.keySet(),List.of(CDP+"3",CDP+"4",CDP+"7",CDP+"17",CDP+"19",CDP+"20"));
        var result=new ArrayList<Neighbor>();
        for(var e:rows.entrySet()){
            String key=e.getKey();int ifIndex=Integer.parseInt(key.split("\\.")[0]);
            var matches=ports.stream().filter(p->p.sourceRef().equals("1.3.6.1.2.1.2.2.1.1."+ifIndex)).toList();
            String deviceId=text(e.getValue()),remote=text(data.get(CDP+"7."+key));
            if(deviceId==null||remote==null||matches.size()!=1){flags.add("SNMP_CDP_NEIGHBOR_IDENTITY_INCOMPLETE");continue;}
            String name=text(data.get(CDP+"17."+key));
            var addresses=new ArrayList<String>();
            for(int column:List.of(19,3)){
                String ip=cdpAddress(number(data.get(CDP+column+"."+key)),data.get(CDP+(column+1)+"."+key));
                if(ip!=null&&!addresses.contains(ip))addresses.add(ip);
            }
            result.add(new Neighbor(preferredAddress(addresses),null,"CDP",matches.getFirst().name(),null,name==null?deviceId:name,
                null,null,remote,null,null,"SNMP",CDP+"6."+key,null,ifIndex,List.copyOf(addresses)));
        }
        return result;
    }
    private static Map<String,Variable> columns(SnmpSession s,Collection<String> indices,List<String> columns)throws Exception{
        var oids=new ArrayList<String>();for(String index:indices)for(String column:columns)oids.add(column+"."+index);return s.get(oids,false);
    }
    private static boolean stopped(Set<String> flags){return flags.stream().anyMatch(STOP::contains);}
    private static String preferredAddress(List<String> addresses){return addresses.stream().filter(address->!address.contains(":")).findFirst().orElse(addresses.isEmpty()?null:addresses.getFirst());}
    private static int[] lldpIndex(String suffix){
        try{String[] p=suffix.split("\\.");if(p.length!=3)return null;long tick=Long.parseLong(p[0]),port=Long.parseLong(p[1]),remote=Long.parseLong(p[2]);
            return tick>=0&&tick<=0xffffffffL&&port>0&&port<=Integer.MAX_VALUE&&remote>0&&remote<=Integer.MAX_VALUE?new int[]{0,(int)port,(int)remote}:null;
        }catch(NumberFormatException invalid){return null;}
    }
    static Map.Entry<String,String> managementIndex(String suffix){
        try{
            String[] p=suffix.split("\\.");if(p.length<6)return null;
            String row=String.join(".",Arrays.copyOf(p,3));if(lldpIndex(row)==null)return null;
            int family=Integer.parseInt(p[3]),size=Integer.parseInt(p[4]);
            if((family!=1||size!=4)&&(family!=2||size!=16)||p.length!=5+size)return null;
            byte[] bytes=new byte[size];for(int i=0;i<size;i++){int v=Integer.parseInt(p[i+5]);if(v<0||v>255)return null;bytes[i]=(byte)v;}
            String address=address(bytes);return address==null?null:Map.entry(row,address);
        }catch(NumberFormatException invalid){return null;}
    }
    private static Local local(Variable value,Long subtype,List<DeviceProtocol.Port> ports){
        String id=id(value,subtype,false);if(id==null)return null;
        if(Objects.equals(subtype,5L)){
            var matches=ports.stream().filter(p->p.name().equals(id)).toList();
            return new Local(id,matches.size()==1?portIndex(matches.getFirst()):null);
        }
        if(Objects.equals(subtype,3L)){
            var matches=ports.stream().filter(p->id.equalsIgnoreCase(p.macAddress())).toList();
            return matches.size()==1?new Local(matches.getFirst().name(),portIndex(matches.getFirst())):null;
        }
        // Locally assigned IDs remain genuine LLDP port IDs; do not reinterpret their numbers as ifIndex.
        return new Local(id,null);
    }
    private static Integer portIndex(DeviceProtocol.Port p){try{return Integer.valueOf(p.sourceRef().substring(p.sourceRef().lastIndexOf('.')+1));}catch(RuntimeException ignored){return null;}}
    private static String id(Variable value,Long subtype,boolean chassis){
        if(subtype==null||subtype<1||subtype>7||!(value instanceof OctetString octets))return null;
        if(subtype==(chassis?4:3)){
            if(octets.length()!=6)return null;byte[] b=octets.getValue();boolean nonzero=false;for(byte v:b)nonzero|=v!=0;
            return nonzero?HexFormat.ofDelimiter(":").formatHex(b):null;
        }
        if(subtype==(chassis?5:4)){
            byte[] b=octets.getValue();if(b.length<1)return null;
            if(b[0]==1&&b.length==5||b[0]==2&&b.length==17)return address(Arrays.copyOfRange(b,1,b.length));
            return null;
        }
        return text(value);
    }
    private static String chassisSubtype(Long value){return switch(value.intValue()){case 1->"chassisComponent";case 2->"interfaceAlias";case 3->"portComponent";case 4->"macAddress";case 5->"networkAddress";case 6->"interfaceName";default->"local";};}
    static String cdpAddress(Long type,Variable value){
        // CiscoNetworkProtocol ip(1), ipv6(20), distinct from IANA address families.
        if(!(value instanceof OctetString bytes))return null;
        return Objects.equals(type,1L)&&bytes.length()==4||Objects.equals(type,20L)&&bytes.length()==16?address(bytes.getValue()):null;
    }
    private static String address(byte[] bytes){
        if(bytes.length!=4&&bytes.length!=16)return null;
        try{var ip=InetAddress.getByAddress(bytes);return ip.isAnyLocalAddress()||ip.isMulticastAddress()?null:ip.getHostAddress();}catch(Exception invalid){return null;}
    }
    private static String text(Variable value){
        if(!(value instanceof OctetString octets)||octets.length()<1||octets.length()>255)return null;
        try{String result=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(octets.getValue())).toString().strip();
            return result.isEmpty()||result.chars().anyMatch(Character::isISOControl)?null:result;
        }catch(Exception invalid){return null;}
    }
}
