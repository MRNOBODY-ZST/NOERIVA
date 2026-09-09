package io.noeriva.control.discovery;

import java.time.Instant;
import java.util.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.discovery.DiscoveryModels.*;

/** Parses an allowlisted projection of stored observations; never resolves or contacts an address. */
final class DiscoveryEvidence {
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(4096).maxNumberLength(30).build()).build()).build();
    record Cidr(long network,long mask) { boolean contains(String address){try{long n=ipv4(address);return (n&mask)==network&&(mask>=0xfffffffeL||(n!=network&&n!=(network|(~mask&0xffffffffL))));}catch(IllegalArgumentException e){return false;}} }
    record Source(String id,String name,String observedAt,String observations,String flags,String addresses,String dhcp) {
        Source(String id,String name,String observedAt,String observations,String flags){this(id,name,observedAt,observations,flags,null,null);}
    }
    record Parsed(SourceResult result,int inspected,List<Evidence> evidence,List<String> flags) {}
    static long ipv4(String s){
        if(s==null||!s.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))throw new IllegalArgumentException("A canonical IPv4 literal is required");
        long n=0;for(String part:s.split("\\.")){int p=Integer.parseInt(part);if(p>255)throw new IllegalArgumentException("Invalid IPv4 octet");n=(n<<8)|p;}return n;
    }
    static boolean allowed(long n){long first=n>>>24;return first!=0&&first!=127&&first<224&&(n&0xffff0000L)!=0xa9fe0000L;}
    static Cidr cidr(String s){
        if(s==null||!s.matches("[^/]+/(?:2[4-9]|3[0-2])"))throw new IllegalArgumentException("CIDR must be a literal IPv4 network with prefix 24 through 32");
        int slash=s.indexOf('/'),prefix=Integer.parseInt(s.substring(slash+1));long n=ipv4(s.substring(0,slash));long mask=(0xffffffffL<<(32-prefix))&0xffffffffL;
        if(!allowed(n)||(n&mask)!=n)throw new IllegalArgumentException("CIDR must use an allowed network address without host bits");return new Cidr(n,mask);
    }
    static boolean fresh(Instant observed,Instant now){return observed!=null&&observed.isAfter(now.minusSeconds(900))&&!observed.isAfter(now);}
    static boolean live(Evidence e,Instant now){return fresh(e.observedAt(),now)&&(e.validUntil()==null||e.validUntil().isAfter(now));}
    static Parsed parse(Source source,Cidr cidr,Instant now){
        Instant observed=null;try{if(source.observedAt()!=null)observed=Instant.parse(source.observedAt());}catch(RuntimeException ignored){}
        if(absent(source.observations())&&absent(source.addresses())&&absent(source.dhcp()))return result(source,"MISSING",observed,"NO_SAVED_NEIGHBORS",0,List.of(),List.of());
        if(!fresh(observed,now))return result(source,"STALE",observed,"SOURCE_NOT_FRESH",0,List.of(),List.of());
        var flags=new LinkedHashSet<String>();
        try{
            if(source.flags()!=null){var f=JSON.readTree(source.flags());if(f.isArray())for(var value:f){String v=value.isString()?value.asString():"";if(v.matches("(?:SSH|SNMP)_[A-Z0-9_]{1,58}")&&flags.size()<32)flags.add(v);}}
            JsonNode array=combined(source);
            int inspected=Math.min(256,array.size());if(array.size()>256)flags.add("OBSERVATION_LIMIT");
            var evidence=new ArrayList<Evidence>();
            for(int i=0;i<inspected;i++){
                JsonNode row=array.get(i);if(!row.isObject()){flags.add("INVALID_OBSERVATION");continue;}
                String address=literal(row,"address"),kind=literal(row,"source");
                if(address==null||kind==null||!Set.of("ARP","LLDP","CDP","DHCP").contains(kind)){flags.add("INVALID_OBSERVATION");continue;}
                if(!cidr.contains(address)||!allowed(ipv4(address)))continue;
                var rowFlags=new LinkedHashSet<String>();
                Instant rowObserved=observed;try{if(row.hasNonNull("observedAt"))rowObserved=Instant.parse(row.path("observedAt").asText());}catch(RuntimeException invalid){flags.add("INVALID_OBSERVATION");continue;}if(!fresh(rowObserved,now))continue;
                Long ttl=null;Double age=null;Instant until=null;
                if(row.hasNonNull("ttlSeconds")){
                    JsonNode t=row.get("ttlSeconds");if(!t.isIntegralNumber()||!t.canConvertToLong()||t.asLong()<0||t.asLong()>65535){flags.add("INVALID_OBSERVATION");continue;}
                    ttl=t.asLong();until=rowObserved.plusSeconds(ttl);if(!until.isAfter(now))continue;
                }else if(!kind.equals("ARP"))rowFlags.add("TTL_UNKNOWN");
                if(row.hasNonNull("ageMinutes")){
                    JsonNode a=row.get("ageMinutes");if(!a.isNumber()||!Double.isFinite(a.asDouble())||a.asDouble()<0){flags.add("INVALID_OBSERVATION");continue;}
                    age=a.asDouble();if(age>15)rowFlags.add("ARP_ENTRY_OLD");
                }else if(kind.equals("ARP"))rowFlags.add("ARP_AGE_UNKNOWN");
                String rawMac=literal(row,"mac"),mac=mac(rawMac);if(rawMac!=null&&mac==null)rowFlags.add("MAC_INVALID");
                evidence.add(new Evidence(source.id(),source.name(),kind,rowObserved,address,mac,
                    text(row,"interfaceName",120,rowFlags),text(row,"vlan",64,rowFlags),text(row,"name",120,rowFlags),
                    text(row,"chassisId",256,rowFlags),text(row,"chassisSubtype",32,rowFlags),text(row,"portId",256,rowFlags),ttl,age,until,List.copyOf(rowFlags)));
            }
            return result(source,"USED",observed,null,inspected,evidence,List.copyOf(flags));
        }catch(RuntimeException error){return result(source,"INVALID",observed,"INVALID_NEIGHBOR_JSON",0,List.of(),List.of("INVALID_NEIGHBOR_JSON"));}
    }
    private static boolean absent(String value){return value==null||value.equals("null");}
    private static JsonNode combined(Source source){
        var result=JSON.createArrayNode();
        for(String kind:List.of("DHCP","ARP","NEIGHBOR")){
            String raw=kind.equals("DHCP")?source.dhcp():kind.equals("ARP")?source.addresses():source.observations();
            if(absent(raw))continue;if(raw.length()>262144)throw new IllegalArgumentException();var array=JSON.readTree(raw);if(!array.isArray())throw new IllegalArgumentException();
            for(int i=0;i<Math.min(array.size(),257);i++){
                var row=array.get(i);if(!row.isObject()){result.add(row);continue;}
                if(kind.equals("NEIGHBOR")){result.add(row);continue;}
                String state=row.path("neighborState").asText().toUpperCase(Locale.ROOT),type=row.path("entryType").asText().toUpperCase(Locale.ROOT);
                if(kind.equals("ARP")&&(Set.of("INVALID","INCOMPLETE","5","7").contains(state)||Set.of("LOCAL","INVALID","5","2").contains(type)))continue;
                String status=row.path("rowStatus").asText("").toUpperCase(Locale.ROOT);
                if(kind.equals("DHCP")&&!status.isEmpty()&&!Set.of("ACTIVE","1").contains(status))continue;
                var normalized=JSON.createObjectNode();normalized.put("source",kind);
                for(String field:List.of("address","mac","interfaceName","observedAt"))if(row.hasNonNull(field))normalized.set(field,row.get(field));
                if(row.hasNonNull("vlanId"))normalized.set("vlan",row.get("vlanId"));
                if(kind.equals("DHCP")){
                    if(row.hasNonNull("hostname"))normalized.set("name",row.get("hostname"));
                    if(row.hasNonNull("leaseSeconds")){
                        var lease=row.get("leaseSeconds");if(!lease.isIntegralNumber()||lease.asLong()<=0)continue;
                        // The display TTL schema is capped; retaining a shorter validity is conservative.
                        normalized.put("ttlSeconds",Math.min(lease.asLong(),65535));
                    }
                }
                result.add(normalized);
            }
        }
        return result;
    }
    private static Parsed result(Source s,String status,Instant observed,String reason,int count,List<Evidence> evidence,List<String> flags){return new Parsed(new SourceResult(s.id(),s.name(),status,observed,evidence.size(),reason),count,List.copyOf(evidence),flags);}
    private static String literal(JsonNode row,String key){var n=row.get(key);return n!=null&&n.isString()?n.asString():null;}
    private static String text(JsonNode row,String key,int limit,Set<String> flags){
        JsonNode n=row.get(key);if(n==null||n.isNull())return null;if(!n.isString()&&!n.isIntegralNumber())return null;
        String value=(n.isString()?n.asString():n.toString()).replaceAll("[\\p{Cc}\\p{Cf}]","").strip();if(value.isEmpty())return null;
        if(value.length()>limit){flags.add("FIELD_TRUNCATED");value=value.substring(0,limit);}return value;
    }
    static String mac(String raw){
        if(raw==null)return null;String n=raw.replace(":","").replace("-","").replace(".","").toLowerCase(Locale.ROOT);
        if(!n.matches("[0-9a-f]{12}")||n.equals("000000000000")||n.equals("ffffffffffff"))return null;
        return String.join(":",n.substring(0,2),n.substring(2,4),n.substring(4,6),n.substring(6,8),n.substring(8,10),n.substring(10,12));
    }
}
