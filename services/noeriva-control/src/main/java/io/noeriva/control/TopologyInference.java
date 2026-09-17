package io.noeriva.control;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.Models.*;

/** Bounded, read-only correlation. A learned MAC is reachability evidence, never a cable assertion. */
final class TopologyInference {
 private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(4096).build()).build()).build();
 private static final int ROWS=512,ENDPOINTS=4096,EVIDENCE=8;
 private final String org;private final Instant now;private final Map<String,Device> devices=new LinkedHashMap<>();
 private final Map<String,ObservedTopology.Source> sources=new LinkedHashMap<>();
 private final Map<String,Set<String>> identities=new HashMap<>();private final Map<String,Endpoint> endpoints=new LinkedHashMap<>();
 private final Map<String,Edge> edges=new LinkedHashMap<>();private final Map<String,Set<Integer>> deviceVlans=new HashMap<>();
 private final Map<Integer,Set<String>> vlanNames=new TreeMap<>();private final Set<String> uplinks=new HashSet<>(),flags=new TreeSet<>();
 private final List<Fact> facts=new ArrayList<>();private final Map<String,Endpoint> networkNeighbors=new HashMap<>();
 private record Fact(String device,String site,String kind,String mac,String address,String port,List<Integer> vlans,TopologyEvidence evidence,boolean uncertain) {}
 private static final class Endpoint {
  final String key,site,mac;final Set<Integer> vlans=new TreeSet<>();final Set<String> addresses=new TreeSet<>(),flags=new TreeSet<>();
  final List<Fact> facts=new ArrayList<>();final List<TopologyEvidence> evidence=new ArrayList<>();String target,name;boolean network;
  Endpoint(String key,String site,String mac){this.key=key;this.site=site;this.mac=mac;}
  void add(Fact f){facts.add(f);vlans.addAll(f.vlans());if(!f.address().isEmpty())addresses.add(f.address());if(evidence.size()<EVIDENCE&&!evidence.contains(f.evidence()))evidence.add(f.evidence());if(f.uncertain())flags.add("AMBIGUOUS_VLAN_MEMBERSHIP");}
 }
 TopologyInference(String org,List<Device> inventory,List<ObservedTopology.Source> readings,Instant now){
  this.org=org;this.now=now;inventory.stream().limit(513).forEach(d->devices.put(d.id(),d));
  readings.stream().limit(128).filter(s->org.equals(s.org())&&devices.containsKey(s.device())&&s.reading()!=null&&fresh(s.reading().observedAt())).forEach(s->sources.put(s.device(),s));
  for(var d:devices.values())if(ObservedTopology.usableAddress(d.managementAddress()))index(d.siteId(),"ip",canonicalAddress(d.managementAddress()),d.id());
  for(var s:sources.values()){
   var d=devices.get(s.device());String site=d.siteId();
   if(s.reading().identity()!=null)index(site,"name",text(s.reading().identity().sysName()),d.id());
   index(site,"mac",ObservedTopology.chassisMac(s),d.id());
   s.reading().ports().stream().limit(1024).forEach(p->index(site,"mac",ObservedTopology.mac(p.macAddress()),d.id()));
  }
 }
 Topology build(String site,String device,String view,Integer vlan,int limit,List<Edge> retained,List<String> extraFlags){
  flags.addAll(extraFlags);for(var s:sources.values())readVlans(s);
  for(var s:sources.values())readNeighbors(s);
  // Persisted observed links are re-derived from current enabled evidence, never resurrected from history.
  for(var edge:retained)if(!edge.id().startsWith("observed-")&&devices.containsKey(edge.source())&&devices.containsKey(edge.target()))edges.put(edge.id(),edge);
  for(var s:sources.values())if("snmp".equals(s.slot()))readEndpoints(s);
  correlate();var resultNodes=new LinkedHashMap<String,Node>();
  for(var d:devices.values()){
   var s=sources.get(d.id());String cm=s==null?"":ObservedTopology.chassisMac(s);
   resultNodes.put(d.id(),new Node(d.id(),d.name(),d.type(),d.health(),true,d.siteId(),d.availability(),s==null?ProjectionPolicy.freshness(d.lastSeen(),now):"FRESH",sorted(deviceVlans.get(d.id())),ObservedTopology.usableAddress(d.managementAddress())?List.of(canonicalAddress(d.managementAddress())):List.of(),displayMac(cm),"OBSERVED",List.of(),List.of()));
  }
  for(var endpoint:endpoints.values()){
   if(endpoint.target!=null)continue;String id=id("discovered-",endpoint.key);String confidence=confidence(endpoint);
   String label=endpoint.name!=null?endpoint.name:(endpoint.network?"未登记邻居 · ":"未登记终端 · ")+(endpoint.mac.isEmpty()?id.substring(id.length()-8):displayMac(endpoint.mac).substring(9));
   resultNodes.put(id,new Node(id,label,"UNKNOWN","UNKNOWN",false,endpoint.site,"UNKNOWN","FRESH",List.copyOf(endpoint.vlans),List.copyOf(endpoint.addresses),displayMac(endpoint.mac),confidence,List.copyOf(endpoint.flags),List.copyOf(endpoint.evidence)));
  }
  var candidateEdges=edges.values().stream().filter(e->view.equals("ALL")||e.kind().equals("PHYSICAL")).filter(e->vlan==null||e.vlanIds().contains(vlan)).toList();
  Set<String> neighbors=new HashSet<>();if(!device.isBlank()){neighbors.add(device);candidateEdges.stream().filter(e->e.source().equals(device)||e.target().equals(device)).forEach(e->{neighbors.add(e.source());neighbors.add(e.target());});}
  var candidates=resultNodes.values().stream().filter(n->site.isEmpty()||site.equals(n.siteId())).filter(n->device.isEmpty()||neighbors.contains(n.id())).filter(n->vlan==null||n.vlanIds().contains(vlan)).sorted(Comparator.comparing((Node n)->!n.id().equals(device)).thenComparing(n->!n.registered()).thenComparing(Node::id)).toList();
  if(candidates.size()>limit)flags.add("NODE_LIMIT");var selected=candidates.stream().limit(limit).toList();Set<String> included=new HashSet<>();selected.forEach(n->included.add(n.id()));
  var selectedEdges=candidateEdges.stream().filter(e->included.contains(e.source())&&included.contains(e.target())).sorted(Comparator.comparing(Edge::inferred).thenComparing(Edge::id)).toList();
  if(selectedEdges.size()>400)flags.add("EDGE_LIMIT");selectedEdges=selectedEdges.stream().limit(400).toList();
  Set<Integer> ids=new TreeSet<>();selected.forEach(n->ids.addAll(n.vlanIds()));selectedEdges.forEach(e->ids.addAll(e.vlanIds()));var groups=new ArrayList<TopologyVlan>();
  for(int v:ids){int nc=(int)selected.stream().filter(n->n.vlanIds().contains(v)).count(),ec=(int)selectedEdges.stream().filter(e->e.vlanIds().contains(v)).count();groups.add(new TopologyVlan(v,String.join(" / ",vlanNames.getOrDefault(v,Set.of())),nc,ec));}
  return new Topology(selected,selectedEdges,now,List.copyOf(flags),view,List.copyOf(groups));
 }
 private void readVlans(ObservedTopology.Source s){
  for(var n:array(s,"vlanObservations")){var v=vlans(n);if(v.isEmpty())continue;deviceVlans.computeIfAbsent(s.device(),k->new TreeSet<>()).addAll(v);String name=text(n,"name");if(!name.isEmpty())for(int id:v)vlanNames.computeIfAbsent(id,k->new TreeSet<>()).add(name);}
  for(String key:List.of("addressTableStatus","forwardingTableStatus","vlanTableStatus","dhcpTableStatus")){
   String state=s.reading().facts().get(key);if(state!=null&&!Set.of("OBSERVED","NOT_APPLICABLE").contains(state))flags.add(key.replace("TableStatus","").toUpperCase(Locale.ROOT)+"_"+state);
  }
 }
 private void readNeighbors(ObservedTopology.Source s){
  String site=devices.get(s.device()).siteId();for(var n:array(s,"neighborObservations")){
   String protocol=text(n,"source"),port=port(n,"interfaceName"),remote=port(n,"portId");if(!Set.of("LLDP","CDP").contains(protocol)||port.isEmpty()||remote.isEmpty()||!live(n,s))continue;
   String address=address(n),mac=ObservedTopology.mac(text(n,"chassisId")),name=text(n,"name");var matches=matches(site,address,mac,name);matches.remove(s.device());
   if(matches.size()>1){flags.add("NEIGHBOR_IDENTITY_CONFLICT");continue;}
   String target;if(matches.size()==1)target=matches.iterator().next();else{
    if(mac.isEmpty()&&address.isEmpty()&&name.isEmpty())continue;
    String key=org+"/"+site+"/neighbor/"+(!mac.isEmpty()?mac:!address.isEmpty()?address:name);
    var endpoint=endpoint(key,site,mac);if(endpoint==null)continue;endpoint.network=true;if(!mac.isEmpty())networkNeighbors.put(site+"/"+mac,endpoint);endpoint.name=name.isEmpty()?null:name;endpoint.addresses.addAll(address.isEmpty()?List.of():List.of(address));
    var e=evidence(s,n,protocol,"设备通过邻居协议直接通告的端口关系");if(endpoint.evidence.size()<EVIDENCE)endpoint.evidence.add(e);target=id("discovered-",key);
   }
   var evidence=evidence(s,n,protocol,"设备通过邻居协议直接通告的端口关系");
   addEdge(s.device(),target,port,remote,"PHYSICAL",protocol,"OBSERVED",List.of(),List.of(),List.of(evidence));
   uplinks.add(s.device()+"/"+port);uplinks.add(target+"/"+remote);
  }
 }
 private void readEndpoints(ObservedTopology.Source s){
  String site=devices.get(s.device()).siteId();
  for(var n:array(s,"addressObservations")){
   String mac=ObservedTopology.mac(text(n,"mac")),address=address(n),state=text(n,"neighborState").toUpperCase(Locale.ROOT),type=text(n,"entryType").toUpperCase(Locale.ROOT);
   if(mac.isEmpty()||address.isEmpty()||!live(n,s)||Set.of("INVALID","INCOMPLETE","5","7").contains(state)||Set.of("LOCAL","5","INVALID","2").contains(type))continue;
   addFact(new Fact(s.device(),site,"ARP",mac,address,port(n,"interfaceName"),vlans(n),evidence(s,n,"ARP","邻居缓存的 IP/MAC 关联；不能证明物理直连"),false));
  }
  for(var n:array(s,"forwardingObservations")){
   String mac=ObservedTopology.mac(text(n,"mac")),port=port(n,"interfaceName");if(mac.isEmpty()||!live(n,s)||!text(n,"entryStatus").equals("LEARNED"))continue;
   var vlans=vlans(n);addFact(new Fact(s.device(),site,"FDB",mac,"",port,vlans,evidence(s,n,"FDB","交换机在该端口学习到源 MAC；接入关系为推断"),vlans.size()>1));
  }
  for(var n:array(s,"dhcpObservations")){
   String mac=ObservedTopology.mac(text(n,"mac")),address=address(n),status=text(n,"rowStatus").toUpperCase(Locale.ROOT);
   if(mac.isEmpty()||address.isEmpty()||!live(n,s)||!status.isEmpty()&&!Set.of("ACTIVE","1").contains(status))continue;
   if(n.hasNonNull("leaseSeconds")&&(!n.path("leaseSeconds").isIntegralNumber()||n.path("leaseSeconds").asLong()<=0||at(n,s).plusSeconds(Math.min(n.path("leaseSeconds").asLong(),31536000)).isBefore(now)))continue;
   addFact(new Fact(s.device(),site,"DHCP",mac,address,port(n,"interfaceName"),vlans(n),evidence(s,n,"DHCP_SNOOPING","交换机 DHCP Snooping 绑定；不是全网 DHCP 租约清单"),false));
  }
 }
 private void correlate(){
  // Unknown VLAN observations may join only one observed VLAN domain; never equate an FDB id with a VLAN id.
  Map<String,Set<String>> domains=new HashMap<>();for(var f:facts)if(!f.vlans().isEmpty())domains.computeIfAbsent(f.site()+"/"+f.mac(),k->new TreeSet<>()).add(domain(f));
  for(var f:facts){String base=f.site()+"/"+f.mac();var known=domains.getOrDefault(base,Set.of());String domain=f.vlans().isEmpty()&&known.size()==1?known.iterator().next():domain(f);String key=org+"/"+base+"/"+domain;
   var e=endpoint(key,f.site(),f.mac());if(e==null)continue;e.add(f);var neighbor=networkNeighbors.get(base);if(neighbor!=null){e.target=id("discovered-",neighbor.key);neighbor.vlans.addAll(f.vlans());if(neighbor.evidence.size()<EVIDENCE&&!neighbor.evidence.contains(f.evidence()))neighbor.evidence.add(f.evidence());if(!f.address().isEmpty())neighbor.addresses.add(f.address());}if(f.vlans().isEmpty()&&known.size()>1)e.flags.add("VLAN_DOMAIN_AMBIGUOUS");
  }
  Map<String,Set<String>> addressOwners=new HashMap<>();for(var e:endpoints.values())for(String a:e.addresses)addressOwners.computeIfAbsent(e.site+"/"+e.vlans+"/"+a,k->new HashSet<>()).add(e.mac);
  for(var e:endpoints.values()){
   if(e.network)continue;for(String a:e.addresses)if(addressOwners.getOrDefault(e.site+"/"+e.vlans+"/"+a,Set.of()).size()>1)e.flags.add("IP_MAC_CONFLICT");
   if(virtualMac(e.mac))e.flags.add("SHARED_VIRTUAL_MAC");if(e.addresses.size()>1)e.flags.add("MULTIPLE_ADDRESSES");
   var matches=new LinkedHashSet<String>();matches.addAll(lookup(e.site,"mac",e.mac));for(String a:e.addresses)matches.addAll(lookup(e.site,"ip",a));
   if(matches.size()>1)e.flags.add("ASSET_IDENTITY_CONFLICT");
   if(e.target==null&&matches.size()==1){String target=matches.iterator().next();var d=devices.get(target);boolean router=Set.of("ROUTER","SWITCH","FIREWALL").contains(d.type());
    if(router&&!e.addresses.isEmpty()&&e.addresses.stream().anyMatch(a->!a.equals(canonicalAddress(d.managementAddress())))&&lookup(e.site,"mac",e.mac).contains(target))e.flags.add("PROXY_ARP_OR_SHARED_ROUTER_MAC");
    else if(!conflicted(e))e.target=target;
   }
   if(e.target!=null)deviceVlans.computeIfAbsent(e.target,k->new TreeSet<>()).addAll(e.vlans);
   String target=e.target==null?id("discovered-",e.key):e.target;
   Map<String,List<Fact>> paths=new LinkedHashMap<>();for(var f:e.facts)if(Set.of("FDB","DHCP").contains(f.kind())){
    deviceVlans.computeIfAbsent(f.device(),k->new TreeSet<>()).addAll(f.vlans());
    if(f.port().isEmpty()){e.flags.add("INTERFACE_MAPPING_MISSING");continue;}
    if(uplinks.contains(f.device()+"/"+f.port())){e.flags.add("UPLINK_LEARNING_SUPPRESSED");continue;}
    paths.computeIfAbsent(f.device()+"/"+f.port(),k->new ArrayList<>()).add(f);
   }
   if(paths.size()>1)e.flags.add("MULTIPLE_ACCESS_PATHS");
   if(paths.size()==1&&!conflicted(e)&&!e.flags.contains("AMBIGUOUS_VLAN_MEMBERSHIP")&&!e.flags.contains("VLAN_DOMAIN_AMBIGUOUS")){
    var path=paths.values().iterator().next();var f=path.getFirst();if(!target.equals(f.device()))addEdge(f.device(),target,f.port(),null,"L2_INFERRED",path.stream().anyMatch(x->x.kind().equals("FDB"))?"FDB":"DHCP_SNOOPING",confidence(e),List.copyOf(e.vlans),List.copyOf(e.flags),List.copyOf(e.evidence));
   }
   // L3 is deliberately separate from attachment and never converted from CIDR similarity.
   if(!conflicted(e))for(var f:e.facts)if(f.kind().equals("ARP")&&!target.equals(f.device()))addEdge(f.device(),target,f.port(),null,"L3_INFERRED","ARP","UNCONFIRMED",List.copyOf(e.vlans),List.copyOf(e.flags),List.of(f.evidence()));
  }
 }
 private void addFact(Fact fact){if(facts.size()<16384)facts.add(fact);else flags.add("FACT_LIMIT");}
 private String domain(Fact f){return f.vlans().isEmpty()?"unknown":f.vlans().toString();}
 private boolean conflicted(Endpoint e){return e.flags.stream().anyMatch(f->Set.of("IP_MAC_CONFLICT","ASSET_IDENTITY_CONFLICT","PROXY_ARP_OR_SHARED_ROUTER_MAC","SHARED_VIRTUAL_MAC","MULTIPLE_ACCESS_PATHS").contains(f));}
 private String confidence(Endpoint e){if(conflicted(e))return "CONFLICT";if(e.network)return "OBSERVED";boolean fdb=e.facts.stream().anyMatch(f->f.kind().equals("FDB")),ip=e.facts.stream().anyMatch(f->Set.of("ARP","DHCP").contains(f.kind()));return fdb&&ip&&!e.flags.contains("AMBIGUOUS_VLAN_MEMBERSHIP")?"CORROBORATED":"UNCONFIRMED";}
 private Endpoint endpoint(String key,String site,String mac){if(endpoints.containsKey(key))return endpoints.get(key);if(endpoints.size()>=ENDPOINTS){flags.add("ENDPOINT_LIMIT");return null;}var e=new Endpoint(key,site,mac);endpoints.put(key,e);return e;}
 private void addEdge(String source,String target,String sp,String tp,String kind,String protocol,String confidence,List<Integer> vlans,List<String> flags,List<TopologyEvidence> evidence){
  if(source.equals(target))return;if(edges.size()>=8192){this.flags.add("DERIVED_EDGE_LIMIT");return;}String a=source,b=target,pa=sp,pb=tp;if(!kind.endsWith("INFERRED")&&a.compareTo(b)>0){a=target;b=source;pa=tp;pb=sp;}
  String id=id("derived-",org+"/"+a+"/"+pa+"/"+b+"/"+pb+"/"+kind+"/"+vlans);Instant at=evidence.stream().map(TopologyEvidence::observedAt).max(Comparator.naturalOrder()).orElse(now);
  edges.putIfAbsent(id,new Edge(id,a,b,pa,pb,kind,protocol,at,!kind.equals("PHYSICAL"),confidence,vlans,flags,evidence));
 }
 private List<JsonNode> array(ObservedTopology.Source s,String key){String value=s.reading().facts().get(key);if(value==null)return List.of();if(value.length()>262144){flags.add("OBSERVATION_BYTES_LIMIT");return List.of();}try{var n=JSON.readTree(value);if(!n.isArray()){flags.add("INVALID_OBSERVATION_JSON");return List.of();}if(n.size()>ROWS)flags.add("OBSERVATION_ROW_LIMIT");var result=new ArrayList<JsonNode>();for(int i=0;i<Math.min(ROWS,n.size());i++)if(n.get(i).isObject())result.add(n.get(i));return result;}catch(RuntimeException ex){flags.add("INVALID_OBSERVATION_JSON");return List.of();}}
 private List<Integer> vlans(JsonNode n){var set=new TreeSet<Integer>();if(n.path("vlanIds").isArray())for(var v:n.path("vlanIds"))if(v.isIntegralNumber()&&v.asInt()>0&&v.asInt()<4095&&set.size()<64)set.add(v.asInt());var v=n.path("vlanId");if(v.isIntegralNumber()&&v.asInt()>0&&v.asInt()<4095)set.add(v.asInt());return List.copyOf(set);}
 private Set<String> matches(String site,String address,String mac,String name){var result=new LinkedHashSet<String>();result.addAll(lookup(site,"ip",address));result.addAll(lookup(site,"mac",mac));result.addAll(lookup(site,"name",name));return result;}
 private void index(String site,String type,String value,String id){if(!value.isEmpty())identities.computeIfAbsent(site+"/"+type+"/"+value.toLowerCase(Locale.ROOT),k->new LinkedHashSet<>()).add(id);}
 private Set<String> lookup(String site,String type,String value){return value.isEmpty()?Set.of():identities.getOrDefault(site+"/"+type+"/"+value.toLowerCase(Locale.ROOT),Set.of());}
 private Instant at(JsonNode n,ObservedTopology.Source s){try{return n.hasNonNull("observedAt")?Instant.parse(n.path("observedAt").asText()):s.reading().observedAt();}catch(RuntimeException ex){return Instant.EPOCH;}}
 private boolean live(JsonNode n,ObservedTopology.Source s){Instant at=at(n,s);return fresh(at)&&(!n.hasNonNull("ttlSeconds")||n.path("ttlSeconds").isIntegralNumber()&&at.plusSeconds(Math.clamp(n.path("ttlSeconds").asLong(),0,86400)).isAfter(now));}
 private boolean fresh(Instant at){return at!=null&&!at.isAfter(now)&&at.isAfter(now.minusSeconds(180));}
 private TopologyEvidence evidence(ObservedTopology.Source s,JsonNode n,String protocol,String detail){String ref=text(n,"sourceRef");return new TopologyEvidence(protocol,s.device(),empty(port(n,"interfaceName")),ref.isEmpty()?null:ref,at(n,s),detail);}
 private static String address(JsonNode n){return canonicalAddress(text(n,"address"));}
 private static String canonicalAddress(String address){return ObservedTopology.usableAddress(address)?java.net.InetAddress.ofLiteral(address).getHostAddress():"";}
 private static boolean virtualMac(String mac){return mac.startsWith("00005e0001")||mac.startsWith("00005e0002")||mac.startsWith("00000c07ac")||mac.startsWith("00000c9ff");}
 private static String port(JsonNode n,String key){return ObservedTopology.normalizePort(text(n,key));}
 private static String text(JsonNode n,String key){var v=n.path(key);return v.isString()||v.isIntegralNumber()?text(v.asText()):"";}
 private static String text(String value){if(value==null)return "";value=value.replaceAll("[\\p{Cc}\\p{Cf}]","").strip();return value.substring(0,Math.min(value.length(),256));}
 private static String empty(String value){return value.isEmpty()?null:value;}
 private static String id(String prefix,String key){return prefix+UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));}
 private static String displayMac(String mac){if(mac.isEmpty())return null;return String.join(":",mac.substring(0,2),mac.substring(2,4),mac.substring(4,6),mac.substring(6,8),mac.substring(8,10),mac.substring(10,12));}
 private static List<Integer> sorted(Set<Integer> values){return values==null?List.of():values.stream().sorted().toList();}
}
