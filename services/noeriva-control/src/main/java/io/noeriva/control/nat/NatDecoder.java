package io.noeriva.control.nat;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static io.noeriva.control.nat.NatModels.*;

/** Fixed-width Cisco HSL v9 only. All state is bounded and committed after a whole valid datagram. */
public final class NatDecoder {
 private static final Duration TTL=Duration.ofMinutes(30);
 private static final int MAX_DOMAINS=256,MAX_TEMPLATES=64,MAX_RECORDS=1024;
 private final Map<String,Domain> domains=new LinkedHashMap<>();
 private record Retry(Decoded decoded,Instant expires){}
 private final LinkedHashMap<String,Retry> retries=new LinkedHashMap<>();
 private String lastKey;private Decoded lastDecoded;
 private record Field(int id,int length){}
 private record Template(List<Field> fields,int length,String digest,Instant at){}
 private static final class Domain {
  final Map<Integer,Template> templates=new HashMap<>();final LinkedHashMap<String,Instant> seen=new LinkedHashMap<>();
  long sequence,uptime,exportSeconds;Instant last;String epoch=UUID.randomUUID().toString();
  Domain(){} Domain(Domain d){templates.putAll(d.templates);seen.putAll(d.seen);sequence=d.sequence;uptime=d.uptime;exportSeconds=d.exportSeconds;last=d.last;epoch=d.epoch;}
 }
 public NatDecoder(){}
 public synchronized Decoded decode(String org,String device,String site,String source,byte[] packet,Instant received){
  if(packet==null||packet.length<20||packet.length>65507)throw bad("DATAGRAM_LENGTH");
  ByteBuffer b=ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);if(u16(b)!=9)throw bad("UNSUPPORTED_FLOW_VERSION");b.getShort();long uptime=u32(b),unix=u32(b),seq=u32(b),domainId=u32(b);
  Instant exported=Instant.ofEpochSecond(unix);String packetHash=sha(packet);String key=org+"/"+device+"/"+source+"/"+domainId;
  String retryKey=retryKey(org,device,source,packet);retries.entrySet().removeIf(e->!e.getValue().expires.isAfter(received));
  Retry retry=retries.get(retryKey);if(retry!=null){lastKey=retryKey;lastDecoded=retry.decoded;return new Decoded(lastDecoded.events(),0,0,false,false,false,lastDecoded.qualityFlags());}
  domains.entrySet().removeIf(e->e.getValue().last!=null&&e.getValue().last.plus(TTL).isBefore(received));
  if(!domains.containsKey(key)&&domains.size()>=MAX_DOMAINS)throw bad("DOMAIN_LIMIT");
  Domain d=domains.containsKey(key)?new Domain(domains.get(key)):new Domain();
  d.templates.entrySet().removeIf(e->!e.getValue().at.plus(TTL).isAfter(received));
  var flags=new LinkedHashSet<String>(BASE_FLAGS);boolean restart=false,gap=false,late=false;
  if(d.seen.containsKey(packetHash))return new Decoded(List.of(),0,0,false,true,false,List.copyOf(flags));
  if(d.last!=null){long forward=(seq-d.sequence)&0xffffffffL;long upForward=(uptime-d.uptime)&0xffffffffL;
   // A lower uptime plus a reset sequence is a restart candidate, except natural uint32 uptime wrap.
   if(unix<d.exportSeconds){late=true;flags.add("OUT_OF_ORDER");}
   else if(seq<d.sequence&&uptime<d.uptime&&upForward>0x7fffffffL&&!received.isBefore(d.last)&&d.uptime-uptime>1000){restart=true;d.templates.clear();d.epoch=UUID.randomUUID().toString();flags.add("EXPORTER_RESTART");}
   else if(forward>0x7fffffffL||forward==0){late=true;flags.add("OUT_OF_ORDER");}
   else if(forward!=1){gap=true;flags.add("SEQUENCE_GAP_OR_REORDER");}
  }
  if(Duration.between(exported,received).abs().compareTo(Duration.ofMinutes(5))>0)flags.add("EXPORTER_CLOCK_SKEW");
  int templateCount=0,recordIndex=0;long unknown=0;var events=new ArrayList<NatEvent>();
  while(b.hasRemaining()){
   if(b.remaining()<4){padding(b);break;}int start=b.position(),id=u16(b),length=u16(b);if(length<4||length-4>b.remaining())throw bad("FLOWSET_LENGTH");
   ByteBuffer p=b.slice(b.position(),length-4).order(ByteOrder.BIG_ENDIAN);b.position(start+length);
   if(id==0){
    while(p.remaining()>=4){int tid=u16(p),count=u16(p);if(tid<256||count<1||count>64||p.remaining()<count*4)throw bad("TEMPLATE_FIELDS");var fields=new ArrayList<Field>();var ids=new HashSet<Integer>();int size=0;
     for(int i=0;i<count;i++){int type=u16(p),n=u16(p);if(type==0||n<1||n>512||n==65535||!ids.add(type))throw bad("TEMPLATE_FIELD_LENGTH");int expected=expected(type);if(expected>0&&n!=expected)throw bad("HSL_FIELD_LENGTH");size+=n;fields.add(new Field(type,n));}if(size>8192)throw bad("RECORD_LENGTH");
     if(!late){if(!d.templates.containsKey(tid)&&d.templates.size()>=MAX_TEMPLATES)throw bad("TEMPLATE_LIMIT");d.templates.put(tid,new Template(List.copyOf(fields),size,sha(fields.toString().getBytes(StandardCharsets.UTF_8)),received));templateCount++;}
    }padding(p);
   }else if(id==1){flags.add("OPTIONS_NOT_DECODED");}
   else if(id>=256){
    if(late){flags.add("OUT_OF_ORDER_DATA_REJECTED");continue;}Template t=d.templates.get(id);if(t==null){unknown++;flags.add("NO_TEMPLATE");continue;}
    while(p.remaining()>=t.length){if(++recordIndex>MAX_RECORDS)throw bad("RECORD_LIMIT");Map<Integer,byte[]> values=new HashMap<>();for(Field f:t.fields){byte[] value=new byte[f.length];p.get(value);values.put(f.id,value);}
     if(values.containsKey(361)||values.containsKey(363)||values.containsKey(364)){flags.add("UNSUPPORTED_PORT_BLOCK");continue;}
     Integer event=number(values,230);if(event==null||event<1||event>3){flags.add("UNSUPPORTED_EVENT");continue;}
     var ef=new LinkedHashSet<>(flags);Instant at=null;byte[] time=values.get(323);if(time!=null){long millis=ByteBuffer.wrap(time).getLong();if(millis<0)throw bad("EVENT_TIMESTAMP");at=Instant.ofEpochMilli(millis);if(Duration.between(at,received).abs().compareTo(Duration.ofDays(1))>0)ef.add("DEVICE_CLOCK_SKEW");}else ef.add("DEVICE_TIME_MISSING");
     String privateIp=ip(values,8),publicIp=ip(values,225),destination=ip(values,12),translated=ip(values,226);
     if(event!=3&&(privateIp==null||publicIp==null))ef.add("MAPPING_FIELDS_MISSING");if(event!=3&&(number(values,7)==null||number(values,227)==null))ef.add("PORT_FIELDS_MISSING");if(event!=3&&!values.containsKey(4))ef.add("PROTOCOL_MISSING");if(event!=3&&(destination==null||translated==null)){ef.add("DESTINATION_FIELDS_MISSING");ef.add("BINDING_RECORD");}if(!values.containsKey(234))ef.add("VRF_MISSING");
     String eventId=sha((org+"/"+device+"/"+packetHash+"/"+recordIndex).getBytes(StandardCharsets.UTF_8));
     events.add(new NatEvent(eventId,device,site,source,domainId,d.epoch,seq,id,t.digest,packetHash,recordIndex,event==1?"CREATE":event==2?"DELETE":"POOL_EXHAUSTED",number(values,4),longNumber(values,234),privateIp,number(values,7),publicIp,number(values,227),destination,number(values,11),translated,number(values,228),longNumber(values,283),at,exported,received,List.copyOf(ef),"CISCO_NAT_HSL_V9"));
    }padding(p);
   }else throw bad("RESERVED_FLOWSET");
  }
  if(!late){d.sequence=seq;d.uptime=uptime;d.exportSeconds=unix;}d.last=received;d.seen.put(packetHash,received);while(d.seen.size()>256)d.seen.remove(d.seen.keySet().iterator().next());domains.put(key,d);
  Decoded decoded=new Decoded(List.copyOf(events),templateCount,unknown,gap,false,restart,List.copyOf(flags));lastKey=retryKey;lastDecoded=decoded;return decoded;
 }
 synchronized void forget(String org,String device,String source,byte[] packet){String key=retryKey(org,device,source,packet);if(!key.equals(lastKey)||lastDecoded==null||lastDecoded.events().isEmpty())return;retries.put(key,new Retry(lastDecoded,Instant.now().plusSeconds(120)));while(retries.size()>64||retries.values().stream().mapToInt(r->r.decoded.events().size()).sum()>2048)retries.remove(retries.keySet().iterator().next());}
 synchronized void confirm(String org,String device,String source,byte[] packet){String key=retryKey(org,device,source,packet);retries.remove(key);if(key.equals(lastKey)){lastDecoded=null;lastKey=null;}}
 private static String retryKey(String org,String device,String source,byte[] packet){return org+"/"+device+"/"+source+"/"+sha(packet);}
 private static int expected(int id){return switch(id){case 8,225,12,226,234,283->4;case 7,227,11,228->2;case 4,230->1;case 323->8;default->0;};}
 private static void padding(ByteBuffer b){if(b.remaining()>3)throw bad("RECORD_REMAINDER");while(b.hasRemaining())if(b.get()!=0)throw bad("NONZERO_PADDING");}
 private static Integer number(Map<Integer,byte[]> f,int id){Long n=longNumber(f,id);return n==null?null:n.intValue();}
 private static Long longNumber(Map<Integer,byte[]> f,int id){byte[] value=f.get(id);if(value==null)return null;long n=0;for(byte x:value)n=(n<<8)|Byte.toUnsignedInt(x);return n;}
 private static String ip(Map<Integer,byte[]> f,int id){byte[] v=f.get(id);return v==null?null:Byte.toUnsignedInt(v[0])+"."+Byte.toUnsignedInt(v[1])+"."+Byte.toUnsignedInt(v[2])+"."+Byte.toUnsignedInt(v[3]);}
 private static int u16(ByteBuffer b){return Short.toUnsignedInt(b.getShort());}private static long u32(ByteBuffer b){return Integer.toUnsignedLong(b.getInt());}
 static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static IllegalArgumentException bad(String code){return new IllegalArgumentException(code);}
}
