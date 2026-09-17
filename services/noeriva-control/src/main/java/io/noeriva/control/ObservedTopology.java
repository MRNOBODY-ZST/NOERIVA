package io.noeriva.control;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import io.noeriva.control.devices.DeviceProtocol;
import static io.noeriva.control.Models.*;

/** Only LLDP/CDP adjacency is a link. An ARP entry never asserts a physical edge. */
@Component
public class ObservedTopology {
 private final DatabaseClient db;private final JsonMapper json;private final boolean worker;private final AtomicBoolean busy=new AtomicBoolean();
 public ObservedTopology(ObjectProvider<DatabaseClient> db,JsonMapper json,Environment env){this.db=db.getIfAvailable();this.json=json;worker=env.getProperty("NOERIVA_DEVICE_COLLECTOR_ENABLED",Boolean.class,false);}
 public record Source(String org,String device,String address,DeviceProtocol.Reading reading,String slot){
  public Source(String org,String device,String address,DeviceProtocol.Reading reading){this(org,device,address,reading,"");}
 }
 @Scheduled(fixedDelay=30000,initialDelay=15000) public void tick(){if(!worker||db==null||!busy.compareAndSet(false,true))return;sources().flatMap(rows->Flux.fromIterable(edges(rows,Instant.now())).concatMap(e->db.sql("INSERT INTO topology_edge(organization_id,id,source_id,target_id,payload) VALUES(:org,:id,:source,:target,:payload) ON DUPLICATE KEY UPDATE payload=VALUES(payload)").bind("org",e.getKey()).bind("id",e.getValue().id()).bind("source",e.getValue().source()).bind("target",e.getValue().target()).bind("payload",json.writeValueAsString(e.getValue())).fetch().rowsUpdated()).then()).then(db.sql("DELETE FROM topology_edge WHERE id LIKE 'observed-%' AND JSON_UNQUOTE(payload->'$.observedAt')<:before").bind("before",Instant.now().minusSeconds(180).toString()).fetch().rowsUpdated()).doFinally(s->busy.set(false)).onErrorComplete().subscribe();}
 // Choose the enabled source before checking freshness; stale SNMP must not revive SSH evidence.
 // Legacy persisted-adjacency cache is bounded independently; query snapshots derive scoped current evidence.
 // Materializing only keys before LIMIT avoids a filesort carrying large sensor JSON payloads.
 Mono<List<Source>> sources(){return db.sql("""
  SELECT /*+ MAX_EXECUTION_TIME(3000) */ selected.organization_id,selected.id,selected.management_address,c.slot,
    CASE WHEN OCTET_LENGTH(c.last_reading)<=262144 THEN c.last_reading ELSE NULL END last_reading
  FROM (
    SELECT d.organization_id,d.id,d.management_address,c.slot
    FROM device d JOIN device_connection c ON c.organization_id=d.organization_id AND c.device_id=d.id AND c.enabled=1
      AND c.slot=(SELECT preferred.slot FROM device_connection preferred WHERE preferred.organization_id=d.organization_id AND preferred.device_id=d.id AND preferred.enabled=1 ORDER BY CASE preferred.slot WHEN 'snmp' THEN 0 WHEN 'ssh' THEN 1 ELSE 2 END LIMIT 1)
    WHERE c.last_success_at>UTC_TIMESTAMP()-INTERVAL 180 SECOND AND c.last_reading IS NOT NULL
    ORDER BY d.organization_id,d.id LIMIT 128
  ) selected JOIN device_connection c ON c.organization_id=selected.organization_id AND c.device_id=selected.id AND c.slot=selected.slot
  """).map((r,m)->{String raw=r.get("last_reading",String.class);return new Source(r.get("organization_id",String.class),r.get("id",String.class),r.get("management_address",String.class),raw==null?null:json.readValue(raw,DeviceProtocol.Reading.class),r.get("slot",String.class));}).all().collectList();}
 public List<Map.Entry<String,Edge>> edges(List<Source> rows,Instant now){var result=new LinkedHashMap<String,Map.Entry<String,Edge>>();for(var s:rows){if(s.reading()==null||!ProjectionPolicy.freshness(s.reading().observedAt(),now).equals("FRESH"))continue;String observations=s.reading().facts().get("neighborObservations");if(observations==null||observations.length()>262144)continue;tools.jackson.databind.JsonNode nodes;try{nodes=json.readTree(observations);}catch(RuntimeException invalid){continue;}if(!nodes.isArray())continue;int inspected=0;for(var n:nodes){if(++inspected>256)break;String kind=n.path("source").asText();if(!Set.of("LLDP","CDP").contains(kind))continue;String local=n.path("interfaceName").asText("");String remote=n.path("portId").asText("");if(local.isBlank()||remote.isBlank())continue;if(n.hasNonNull("ttlSeconds")&&!s.reading().observedAt().plusSeconds(Math.clamp(n.path("ttlSeconds").asLong(),0,86400)).isAfter(now))continue;
 var targets=new LinkedHashSet<String>();for(var t:rows){if(!s.org().equals(t.org())||s.device().equals(t.device())||t.reading()==null)continue;String address=n.path("address").asText("");String name=n.path("name").asText("");String chassis=mac(n.path("chassisId").asText(""));boolean match=usableAddress(address)&&address.equals(t.address())||!name.isBlank()&&name.equalsIgnoreCase(Objects.toString(t.reading().identity().sysName(),""))||!chassis.isBlank()&&(chassis.equals(chassisMac(t))||t.reading().ports().stream().anyMatch(p->chassis.equals(mac(p.macAddress()))));if(match)targets.add(t.device());}
 if(targets.size()!=1)continue;String target=targets.iterator().next();String a=s.device(),b=target,pa=normalizePort(local),pb=normalizePort(remote);if(a.compareTo(b)>0){String swap=a;a=b;b=swap;swap=pa;pa=pb;pb=swap;}String key=s.org()+"/"+a+"/"+pa+"/"+b+"/"+pb;String id="observed-"+UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));var edge=new Edge(id,a,b,pa,pb,"PHYSICAL",kind,s.reading().observedAt());var previous=result.get(key);if(previous==null||previous.getValue().observedAt().isBefore(edge.observedAt()))result.put(key,Map.entry(s.org(),edge));
 }}return List.copyOf(result.values());}
 static String normalizePort(String name){return name.replaceAll("\\s+","").replaceAll("(?i)^TenGigabitEthernet","Te").replaceAll("(?i)^GigabitEthernet","Gi").replaceAll("(?i)^FortyGigE","Fo");}
 static boolean usableAddress(String value){
  try{var address=java.net.InetAddress.ofLiteral(value);byte[] bytes=address.getAddress();return !address.isAnyLocalAddress()&&!address.isLoopbackAddress()&&!address.isLinkLocalAddress()&&!address.isMulticastAddress()&&!(bytes.length==4&&((bytes[0]&255)==0||(bytes[0]&255)>=224));}catch(IllegalArgumentException invalid){return false;}
 }
 static String chassisMac(Source source){
  if(!"snmp".equals(source.slot()))return "";
  String oid=source.reading().facts().get("chassisMacSource");
  return oid!=null&&oid.length()<=160&&oid.matches("[0-2](?:\\.[0-9]{1,10}){3,30}")?mac(source.reading().facts().get("chassisMacAddress")):"";
 }
 static String mac(String value){if(value==null||!value.matches("(?i)(?:[0-9a-f]{12}|[0-9a-f]{2}(?::[0-9a-f]{2}){5}|[0-9a-f]{2}(?:-[0-9a-f]{2}){5}|[0-9a-f]{4}(?:\\.[0-9a-f]{4}){2})"))return "";String v=value.replaceAll("[^0-9A-Fa-f]","").toLowerCase(Locale.ROOT);return v.equals("000000000000")||(Integer.parseInt(v.substring(0,2),16)&1)!=0?"":v;}
}
