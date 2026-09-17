package io.noeriva.control.devices;

import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import org.snmp4j.smi.*;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.devices.SnmpDriver.number;

/** Cached SNMP evidence only: a forwarding entry does not prove a direct attachment.
 * See docs/devices/SNMP-ENDPOINT-EVIDENCE.md for MIB semantics and bounded-read policy. */
final class SnmpEndpointEvidence {
    static final String PHYSICAL="1.3.6.1.2.1.4.35.1.", ARP="1.3.6.1.2.1.4.22.1.",
        BRIDGE_PORT="1.3.6.1.2.1.17.1.4.1.2", FDB="1.3.6.1.2.1.17.4.3.1.",
        QFDB="1.3.6.1.2.1.17.7.1.2.2.1.", VLAN="1.3.6.1.2.1.17.7.1.4.2.1.",
        VLAN_NAME="1.3.6.1.2.1.17.7.1.4.3.1.1", DHCP="1.3.6.1.4.1.9.9.380.1.4.1.1.";
    static final int ROW_LIMIT=128, AUXILIARY_VARIABLES=1100;
    static final long AUXILIARY_MILLIS=5000;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> STOP=Set.of("SNMP_TIMEOUT","SNMP_DEADLINE","SNMP_VARIABLE_BUDGET","SNMP_ENDPOINT_BUDGET",
        "SNMP_AUTHENTICATION_FAILED","SNMP_ACCESS_DENIED","SNMP_TRANSPORT_ERROR");
    record AddressObservation(String source,String address,String mac,Integer interfaceIndex,String interfaceName,
        String neighborState,String entryType,Long rawNeighborState,Long rawEntryType,String observedAt,String sourceRef,List<String> sourceRefs){}
    record ForwardingObservation(String source,String mac,Integer bridgePort,Integer interfaceIndex,String interfaceName,
        Long fdbId,List<Integer> vlanIds,String entryStatus,Long rawEntryStatus,String observedAt,String sourceRef,List<String> sourceRefs){}
    record VlanObservation(Integer vlanId,String name,Long fdbId,Long timeMark,List<Integer> egressBridgePorts,
        List<Integer> untaggedBridgePorts,String observedAt,String sourceRef,List<String> sourceRefs){}
    record DhcpObservation(String source,String mac,String address,Integer interfaceIndex,String interfaceName,Integer vlanId,
        Long leaseSeconds,String rowStatus,Long rawRowStatus,String hostname,String observedAt,String sourceRef,List<String> sourceRefs){}
    private record AddressIndex(int ifIndex,String address){}
    private record ForwardIndex(Long fdbId,String mac){}
    private static final class State {
        final SnmpSession session;final List<DeviceProtocol.Port> ports;final Map<String,String> facts;final Set<String> flags;
        final String observedAt=Instant.now().toString();final int limit;int warnings;
        final List<AddressObservation> addresses=new ArrayList<>();final List<ForwardingObservation> forwarding=new ArrayList<>();
        final List<VlanObservation> vlans=new ArrayList<>();final List<DhcpObservation> dhcp=new ArrayList<>();
        final Map<Integer,Integer> bridgePorts=new LinkedHashMap<>();final Map<Long,Set<Integer>> fdbVlans=new LinkedHashMap<>();
        State(SnmpSession s,List<DeviceProtocol.Port> p,Map<String,String> f,Set<String> g){session=s;ports=p;facts=f;flags=g;limit=Math.clamp(s.remainingVariables()/8,16,ROW_LIMIT);}
        boolean stopped(){return flags.stream().anyMatch(STOP::contains);}
        Map<String,Variable> walk(String column,int min,int max)throws Exception{
            if(stopped())return Map.of();var rows=session.walkSuffixes(column,min,max,limit+1,null);
            if(rows.size()>limit){warn("SNMP_ENDPOINT_ROW_LIMIT");var truncated=new LinkedHashMap<String,Variable>();rows.entrySet().stream().limit(limit).forEach(e->truncated.put(e.getKey(),e.getValue()));return truncated;}
            return rows;
        }
        Map<String,Variable> columns(Collection<String> indices,String...columns)throws Exception{
            if(stopped()||indices.isEmpty())return Map.of();var oids=new ArrayList<String>();for(String col:columns)for(String index:indices)oids.add(col+"."+index);return session.get(oids,false);
        }
        void warn(String flag){warnings++;flags.add(flag);}
        void invalid(){warn("SNMP_ENDPOINT_INVALID_EVIDENCE");}
        String name(Integer index){if(index==null)return null;var matches=ports.stream().filter(p->("1.3.6.1.2.1.2.2.1.1."+index).equals(p.sourceRef())).toList();return matches.size()==1?matches.getFirst().name():null;}
    }
    private SnmpEndpointEvidence(){}
    static void collect(SnmpSession s,SnmpProfiles.Profile profile,List<DeviceProtocol.Port> ports,Map<String,String> facts,Set<String> flags,List<String> capabilities)throws Exception{
        boolean network=network(profile),cisco=profile.id().startsWith("cisco-");var state=new State(s,ports,facts,flags);
        for(String table:List.of("address","forwarding","vlan","dhcp"))facts.put(table+"TableStatus",network&&(!table.equals("dhcp")||cisco)?"SKIPPED_BUDGET":"NOT_APPLICABLE");
        if(network&&!state.stopped()&&s.remainingVariables()>=100&&s.remainingMillis()>=750){
            try(var budget=s.auxiliaryBudget(AUXILIARY_MILLIS,AUXILIARY_VARIABLES)){
                phase(state,"vlan",()->vlans(state));
                phase(state,"forwarding",()->forwarding(state));
                phase(state,"address",()->addresses(state));
                if(cisco)phase(state,"dhcp",()->dhcp(state));
            }
        }
        facts.put("addressObservations",JSON.writeValueAsString(state.addresses));facts.put("forwardingObservations",JSON.writeValueAsString(state.forwarding));
        facts.put("vlanObservations",JSON.writeValueAsString(state.vlans));facts.put("dhcpObservations",JSON.writeValueAsString(state.dhcp));
        facts.put("endpointEvidenceProtocol","SNMP");facts.put("endpointEvidenceObservedAt",state.observedAt);
        facts.put("endpointEvidenceRowLimit",Integer.toString(state.limit));facts.put("endpointEvidenceVariableBudget",Integer.toString(AUXILIARY_VARIABLES));
        facts.put("endpointEvidenceLifetimeBasis","agent_cache_at_collection; no_active_probe; cache_age_not_reachability");
        facts.put("endpointAttachmentBasis","FDB_is_a_learned_forwarding_path; direct_attachment_requires_independent_evidence");
        if(!state.addresses.isEmpty())capabilities.add("address-cache");if(!state.forwarding.isEmpty())capabilities.add("forwarding-table");
        if(!state.vlans.isEmpty())capabilities.add("vlans");if(!state.dhcp.isEmpty())capabilities.add("dhcp-snooping-bindings");
    }
    private static boolean network(SnmpProfiles.Profile p){return Set.of("dell-os9-s6100-chassis","cisco-asr1002x-entity","cisco-process-entity","huawei-entity-extent").contains(p.id())
        ||p.id().equals("h3c-entity-extent")&&p.family().contains("Comware")||p.id().equals("dell-generic-snmp")&&p.family().contains("Network OS9");}
    @FunctionalInterface private interface Read {int run()throws Exception;}
    private static void phase(State state,String table,Read read)throws Exception{
        if(state.stopped())return;int issues=state.session.issues(),warnings=state.warnings;var before=new HashSet<>(state.flags);int rows;
        try{rows=read.run();}catch(DeviceProtocol.Failure e){state.flags.add(e.code());state.facts.put(table+"TableStatus","INCOMPLETE");return;}
        boolean incomplete=issues!=state.session.issues()||warnings!=state.warnings||state.flags.stream().anyMatch(f->!before.contains(f));
        state.facts.put(table+"TableStatus",incomplete?"INCOMPLETE":rows==0?"EMPTY_OR_UNSUPPORTED":"OBSERVED");
    }
    private static int vlans(State s)throws Exception{
        var rows=s.walk(VLAN+"3",2,2);if(rows.isEmpty())return 0;
        var names=new LinkedHashSet<String>();for(String key:rows.keySet()){String[] p=key.split("\\.");if(vlan(p[1])!=null)names.add(p[1]);}
        var data=s.columns(names,VLAN_NAME);
        // Membership is optional. Missing PortList is an absent observation, never an empty membership claim.
        var membership=s.columns(rows.keySet(),VLAN+"4",VLAN+"5");
        for(var row:rows.entrySet()){
            String[] index=row.getKey().split("\\.");Integer vlan=vlan(index[1]);Long fdb=number(row.getValue());
            if(vlan==null||fdb==null||fdb<0){s.invalid();continue;}
            s.fdbVlans.computeIfAbsent(fdb,k->new TreeSet<>()).add(vlan);
            var refs=new ArrayList<String>();refs.add(VLAN+"3."+row.getKey());if(data.containsKey(VLAN_NAME+"."+vlan))refs.add(VLAN_NAME+"."+vlan);
            for(int col:List.of(4,5))if(membership.containsKey(VLAN+col+"."+row.getKey()))refs.add(VLAN+col+"."+row.getKey());
            s.vlans.add(new VlanObservation(vlan,SnmpDriver.text(data.get(VLAN_NAME+"."+vlan)),fdb,Long.parseLong(index[0]),
                portList(membership.get(VLAN+"4."+row.getKey())),portList(membership.get(VLAN+"5."+row.getKey())),s.observedAt,refs.getFirst(),List.copyOf(refs)));
        }return s.vlans.size();
    }
    private static int forwarding(State s)throws Exception{
        var bridge=s.walk(BRIDGE_PORT,1,1);for(var row:bridge.entrySet()){
            Integer port=positive(row.getKey()),index=positive(number(row.getValue()));if(port==null||index==null){s.invalid();continue;}s.bridgePorts.put(port,index);
        }
        var rows=s.walk(QFDB+"2",7,7);boolean q=!rows.isEmpty();
        if(!q&&!s.stopped())rows=s.walk(FDB+"2",6,6);String base=q?QFDB:FDB;
        var states=s.columns(rows.keySet(),base+"3");
        for(var row:rows.entrySet()){
            ForwardIndex index=forwardIndex(row.getKey(),q);Long portNumber=number(row.getValue()),status=number(states.get(base+"3."+row.getKey()));
            if(index==null||portNumber==null||portNumber<0||portNumber>Integer.MAX_VALUE){s.invalid();continue;}
            if(status==null)s.warn("SNMP_ENDPOINT_FDB_STATUS_MISSING");
            Integer port=portNumber.intValue(),ifIndex=s.bridgePorts.get(port);var refs=new ArrayList<String>();refs.add(base+"2."+row.getKey());
            if(status!=null)refs.add(base+"3."+row.getKey());if(ifIndex!=null)refs.add(BRIDGE_PORT+"."+port);
            if(port>0&&ifIndex==null)s.warn("SNMP_ENDPOINT_BRIDGE_MAPPING_INCOMPLETE");
            List<Integer> vlans=index.fdbId()==null?List.of():List.copyOf(s.fdbVlans.getOrDefault(index.fdbId(),Set.of()));
            for(var vlan:s.vlans)if(Objects.equals(vlan.fdbId(),index.fdbId()))refs.add(vlan.sourceRef());
            s.forwarding.add(new ForwardingObservation(q?"Q-BRIDGE-MIB":"BRIDGE-MIB",index.mac(),port,ifIndex,s.name(ifIndex),index.fdbId(),vlans,
                fdbStatus(status),status,s.observedAt,refs.getFirst(),List.copyOf(refs)));
        }return s.forwarding.size();
    }
    private static int addresses(State s)throws Exception{
        var rows=s.walk(PHYSICAL+"4",7,23);boolean physical=!rows.isEmpty();
        if(!physical&&!s.stopped())rows=s.walk(ARP+"2",5,5);String base=physical?PHYSICAL:ARP;
        var data=physical?s.columns(rows.keySet(),PHYSICAL+"6",PHYSICAL+"7"):s.columns(rows.keySet(),ARP+"4");
        for(var row:rows.entrySet()){
            AddressIndex index=addressIndex(row.getKey(),physical);String mac=mac(row.getValue());
            if(index==null||mac==null){s.invalid();continue;}
            Long type=number(data.get(base+(physical?"6":"4")+"."+row.getKey()));Long state=physical?number(data.get(PHYSICAL+"7."+row.getKey())):null;
            if(type==null)s.warn("SNMP_ENDPOINT_ADDRESS_TYPE_MISSING");
            var refs=new ArrayList<String>();refs.add(base+(physical?"4":"2")+"."+row.getKey());
            if(type!=null)refs.add(base+(physical?"6":"4")+"."+row.getKey());if(state!=null)refs.add(PHYSICAL+"7."+row.getKey());
            s.addresses.add(new AddressObservation(physical?"IP-MIB/ipNetToPhysical":"RFC1213/ipNetToMedia",index.address(),mac,index.ifIndex(),s.name(index.ifIndex()),
                neighborState(state),addressType(type),state,type,s.observedAt,refs.getFirst(),List.copyOf(refs)));
        }return s.addresses.size();
    }
    private static int dhcp(State s)throws Exception{
        var rows=s.walk(DHCP+"4",7,7);var data=s.columns(rows.keySet(),DHCP+"3",DHCP+"5",DHCP+"6",DHCP+"7",DHCP+"8");
        for(var row:rows.entrySet()){
            String[] parts=row.getKey().split("\\.");Integer vlan=vlan(parts[0]);String mac=macOctets(parts,1);Long type=number(data.get(DHCP+"3."+row.getKey()));
            String address=inetAddress(row.getValue(),type);Integer index=positive(number(data.get(DHCP+"5."+row.getKey())));Long status=number(data.get(DHCP+"7."+row.getKey()));
            if(vlan==null||mac==null||address==null){s.invalid();continue;}
            if(status==null||index==null)s.warn("SNMP_ENDPOINT_DHCP_COLUMNS_INCOMPLETE");
            var refs=new ArrayList<String>();refs.add(DHCP+"4."+row.getKey());for(int col:List.of(3,5,6,7,8))if(data.containsKey(DHCP+col+"."+row.getKey()))refs.add(DHCP+col+"."+row.getKey());
            s.dhcp.add(new DhcpObservation("CISCO-DHCP-SNOOPING-MIB",mac,address,index,s.name(index),vlan,number(data.get(DHCP+"6."+row.getKey())),
                Objects.equals(status,1L)?"ACTIVE":Objects.equals(status,6L)?"DESTROY":"UNKNOWN",status,SnmpDriver.text(data.get(DHCP+"8."+row.getKey())),s.observedAt,refs.getFirst(),List.copyOf(refs)));
        }return s.dhcp.size();
    }
    static List<Integer> portList(Variable value){
        if(!(value instanceof OctetString bytes)||bytes.length()>512)return null;var ports=new ArrayList<Integer>();byte[] raw=bytes.getValue();
        for(int i=0;i<raw.length;i++)for(int bit=0;bit<8;bit++)if((raw[i]&(0x80>>>bit))!=0)ports.add(i*8+bit+1);return List.copyOf(ports);
    }
    private static ForwardIndex forwardIndex(String suffix,boolean q){try{
        String[] p=suffix.split("\\.");if(p.length!=(q?7:6))return null;String mac=macOctets(p,q?1:0);if(mac==null)return null;
        return new ForwardIndex(q?Long.parseLong(p[0]):null,mac);
    }catch(NumberFormatException e){return null;}}
    private static AddressIndex addressIndex(String suffix,boolean physical){try{
        String[] p=suffix.split("\\.");Integer index=positive(p[0]);if(index==null)return null;int offset=1,size=4;
        if(physical){if(p.length<3)return null;int family=Integer.parseInt(p[1]);size=Integer.parseInt(p[2]);offset=3;if((family!=1||size!=4)&&(family!=2||size!=16))return null;}
        if(p.length!=offset+size)return null;byte[] bytes=bytes(p,offset,size);String address=literal(bytes);return address==null?null:new AddressIndex(index,address);
    }catch(NumberFormatException e){return null;}}
    private static String inetAddress(Variable value,Long type){
        if(!(value instanceof OctetString bytes))return null;return Objects.equals(type,1L)&&bytes.length()==4||Objects.equals(type,2L)&&bytes.length()==16?literal(bytes.getValue()):null;
    }
    private static String literal(byte[] bytes){if(bytes==null)return null;try{var ip=InetAddress.getByAddress(bytes);return ip.isAnyLocalAddress()||ip.isMulticastAddress()||ip.isLoopbackAddress()?null:ip.getHostAddress();}catch(Exception invalid){return null;}}
    private static byte[] bytes(String[] parts,int offset,int count){try{byte[] bytes=new byte[count];for(int i=0;i<count;i++){int n=Integer.parseInt(parts[offset+i]);if(n<0||n>255)return null;bytes[i]=(byte)n;}return bytes;}catch(NumberFormatException|IndexOutOfBoundsException invalid){return null;}}
    private static String macOctets(String[] parts,int offset){return parts.length-offset==6?mac(bytes(parts,offset,6)):null;}
    private static String mac(Variable value){return value instanceof OctetString octets?mac(octets.getValue()):null;}
    private static String mac(byte[] bytes){if(bytes==null||bytes.length!=6||(bytes[0]&1)!=0)return null;boolean zero=true;for(byte b:bytes)if(b!=0)zero=false;return zero?null:HexFormat.ofDelimiter(":").formatHex(bytes);}
    private static Integer positive(String value){try{return positive(Long.parseLong(value));}catch(NumberFormatException e){return null;}}
    private static Integer positive(Long value){return value!=null&&value>0&&value<=Integer.MAX_VALUE?value.intValue():null;}
    private static Integer vlan(String value){Integer id=positive(value);return id!=null&&id<=4094?id:null;}
    private static String addressType(Long value){return value==null?"UNKNOWN":switch(value.intValue()){case 1->"OTHER";case 2->"INVALID";case 3->"DYNAMIC";case 4->"STATIC";case 5->"LOCAL";default->"UNKNOWN";};}
    private static String neighborState(Long value){return value==null?"UNKNOWN":switch(value.intValue()){case 1->"REACHABLE";case 2->"STALE";case 3->"DELAY";case 4->"PROBE";case 5->"INVALID";case 6->"UNKNOWN";case 7->"INCOMPLETE";default->"UNKNOWN";};}
    private static String fdbStatus(Long value){return value==null?"UNKNOWN":switch(value.intValue()){case 1->"OTHER";case 2->"INVALID";case 3->"LEARNED";case 4->"SELF";case 5->"MANAGEMENT";default->"UNKNOWN";};}
}
