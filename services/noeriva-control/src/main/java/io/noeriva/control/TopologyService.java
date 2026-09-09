package io.noeriva.control;

import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import io.noeriva.control.devices.DeviceProtocol;
import static io.noeriva.control.Models.*;

@Service
public final class TopologyService {
 private final ControlRepository repository;private final DatabaseClient db;private final JsonMapper json;private final Semaphore capacity=new Semaphore(2);
 public TopologyService(ControlRepository repository,ObjectProvider<DatabaseClient> db,JsonMapper json){this.repository=repository;this.db=db.getIfAvailable();this.json=json;}
 public Mono<Topology> snapshot(String org,String site,String device,String view,Integer vlan,int limit){
  return Mono.using(()->{if(!capacity.tryAcquire())throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"TOPOLOGY_CAPACITY","Topology query capacity reached");return true;},lease->{
   Mono<Optional<Device>> requested=device.isEmpty()?Mono.just(Optional.empty()):repository.device(org,device).filter(d->site.isEmpty()||site.equals(d.siteId())).switchIfEmpty(Mono.error(ApiException.missing())).map(Optional::of);
   return requested.flatMap(requestedDevice->repository.devices(org,513,"","",site,"","").collectList().flatMap(inventory->{
    var flags=new ArrayList<String>();if(inventory.size()>512)flags.add("INVENTORY_LIMIT");var scoped=new LinkedHashMap<String,Device>();requestedDevice.ifPresent(d->scoped.put(d.id(),d));inventory.stream().limit(512).forEach(d->scoped.putIfAbsent(d.id(),d));
    if(repository instanceof DemoRepository)flags.add("SYNTHETIC_DATA");
    return readings(org,new ArrayList<>(scoped.keySet()),device,flags).zipWith(repository.edges(org,"",401).collectList()).map(pair->{if(pair.getT2().size()>400)flags.add("STORED_EDGE_LIMIT");return new TopologyInference(org,new ArrayList<>(scoped.values()),pair.getT1(),Instant.now()).build(site,device,view,vlan,limit,pair.getT2().stream().limit(400).toList(),flags);});
   })).timeout(Duration.ofSeconds(8));
  },lease->capacity.release());
 }
 private Mono<List<ObservedTopology.Source>> readings(String org,List<String> ids,String device,List<String> flags){
  if(db==null||ids.isEmpty())return Mono.just(List.of());
  // Primary-key bounded inventory, one enabled preferred slot per asset, bounded row/payload transfer.
  // Select before freshness: an unavailable SNMP source must not revive historical SSH facts.
  return db.sql("""
   SELECT /*+ MAX_EXECUTION_TIME(3000) */ selected.id,selected.management_address,c.slot,
    CASE WHEN OCTET_LENGTH(c.last_reading)<=262144 THEN c.last_reading ELSE NULL END reading
   FROM (
    SELECT d.organization_id,d.id,d.management_address,c.slot
    FROM device d JOIN device_connection c ON c.organization_id=d.organization_id AND c.device_id=d.id AND c.enabled=1
     AND c.slot=(SELECT preferred.slot FROM device_connection preferred WHERE preferred.organization_id=d.organization_id
       AND preferred.device_id=d.id AND preferred.enabled=1 ORDER BY CASE preferred.slot WHEN 'snmp' THEN 0 WHEN 'ssh' THEN 1 ELSE 2 END LIMIT 1)
    WHERE d.organization_id=:org AND d.id IN (:ids) AND c.last_success_at>UTC_TIMESTAMP()-INTERVAL 180 SECOND AND c.last_reading IS NOT NULL
    ORDER BY (d.id=:device) DESC,CASE c.slot WHEN 'snmp' THEN 0 ELSE 1 END,d.id LIMIT 129
   ) selected JOIN device_connection c ON c.organization_id=selected.organization_id AND c.device_id=selected.id AND c.slot=selected.slot
   """).bind("org",org).bind("ids",ids).bind("device",device).map((r,m)->{
    String raw=r.get("reading",String.class);DeviceProtocol.Reading reading=null;
    if(raw==null)flags.add("SOURCE_BYTES_LIMIT");else try{reading=json.readValue(raw,DeviceProtocol.Reading.class);}catch(RuntimeException e){flags.add("INVALID_SOURCE_READING");}
    return new ObservedTopology.Source(org,r.get("id",String.class),r.get("management_address",String.class),reading,r.get("slot",String.class));
   }).all().collectList().map(rows->{if(rows.size()>128)flags.add("SOURCE_LIMIT");return rows.stream().sorted(Comparator.comparing((ObservedTopology.Source source)->!source.device().equals(device)).thenComparing(source->!source.slot().equals("snmp")).thenComparing(ObservedTopology.Source::device)).limit(128).toList();});
 }
}
