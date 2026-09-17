package io.noeriva.control;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import io.noeriva.control.devices.DeviceProtocol;
import static io.noeriva.control.Models.*;
import static org.assertj.core.api.Assertions.*;

class TopologyInferenceTest {
 private static final Instant NOW=Instant.parse("2026-09-09T09:00:00Z");private static final String MAC="02:11:22:33:44:55";
 private final JsonMapper json=JsonMapper.builder().build();
 private Device device(String id,String site,String address){return new Device(id,id,"SWITCH",site,site,"fixture","fixture",address,"HEALTHY","ONLINE",NOW,1,List.of());}
 private ObservedTopology.Source source(String org,String id,Map<String,List<Map<String,Object>>> tables){var facts=new LinkedHashMap<String,String>();tables.forEach((k,v)->facts.put(k,json.writeValueAsString(v)));return new ObservedTopology.Source(org,id,"192.0.2.1",new DeviceProtocol.Reading(NOW,new DeviceProtocol.Identity("fixture","network","fixture","fixture",null,null,null,id,null),"HEALTHY",Map.of(),List.of(),List.of(),List.of(),List.of(),facts),"snmp");}
 private Map<String,Object> arp(String mac,String address){return Map.of("source","IP-MIB/ipNetToPhysical","mac",mac,"address",address,"interfaceName","Vlan4","neighborState","REACHABLE","entryType","DYNAMIC","sourceRef","1.3.6.1.2.1.4.35.1.4.4.1.4.192.0.2.10");}
 private Map<String,Object> fdb(String mac,String port,Integer...vlans){return Map.of("source","Q-BRIDGE-MIB","mac",mac,"interfaceName",port,"bridgePort",88,"interfaceIndex",2049,"fdbId",700,"vlanIds",List.of(vlans),"entryStatus","LEARNED","sourceRef","1.3.6.1.2.1.17.7.1.2.2.1.2.700.2.17.34.51.68.85");}
 private Map<String,Object> neighbor(String mac,String local){return Map.of("source","LLDP","transport","SNMP","interfaceName",local,"portId","Te1/5/1","chassisId",mac,"sourceRef","1.0.8802.1.1.2.1.4.1.1.7.0.1.1");}
 private Topology graph(List<Device> devices,List<ObservedTopology.Source> sources,String view){return new TopologyInference("org",devices,sources,NOW).build("","",view,null,200,List.of(),List.of());}
 private List<Node> discovered(Topology t){return t.nodes().stream().filter(n->!n.registered()).toList();}
 private List<Edge> l2(Topology t){return t.edges().stream().filter(e->e.kind().equals("L2_INFERRED")).toList();}
 @Test void joinsArpFdbVlanWithoutTreatingFdbIdOrBridgePortAsVlanOrIfIndex(){
  var src=source("org","switch",Map.of("addressObservations",List.of(arp(MAC,"192.0.2.10")),"forwardingObservations",List.of(fdb(MAC,"Gi1/8",4)),"vlanObservations",List.of(Map.of("vlanId",4,"name","office","fdbId",700))));
  var t=graph(List.of(device("switch","site","192.0.2.1")),List.of(src),"ALL");
  assertThat(discovered(t)).hasSize(1);var n=discovered(t).getFirst();assertThat(n.vlanIds()).containsExactly(4);assertThat(n.addresses()).containsExactly("192.0.2.10");assertThat(n.availability()).isEqualTo("UNKNOWN");assertThat(n.confidence()).isEqualTo("CORROBORATED");assertThat(n.name()).doesNotContain("192.0.2");
  assertThat(l2(t)).singleElement().satisfies(e->{assertThat(e.sourceInterface()).isEqualTo("Gi1/8");assertThat(e.inferred()).isTrue();assertThat(e.evidence()).hasSize(2);});assertThat(t.vlans()).containsExactly(new TopologyVlan(4,"office",2,2));
 }
 @Test void arpNeverCreatesPhysicalConnectionAndPhysicalModeRejectsLegacyLogicalEdges(){
  var devices=List.of(device("a","site","192.0.2.1"),device("b","site","192.0.2.2"));var source=source("org","a",Map.of("addressObservations",List.of(arp(MAC,"192.0.2.10"))));
  var t=new TopologyInference("org",devices,List.of(source),NOW).build("","","PHYSICAL",null,200,List.of(new Edge("manual","a","b",null,null,"LOGICAL","manual",NOW)),List.of());
  assertThat(t.edges()).isEmpty();assertThat(discovered(t)).hasSize(1);
 }
 @Test void sharedFdbMembershipRemainsAmbiguousAndDoesNotAssertAccessEdge(){
  var source=source("org","a",Map.of("forwardingObservations",List.of(fdb(MAC,"Gi1/8",4,8)),"addressObservations",List.of(arp(MAC,"192.0.2.10"))));var t=graph(List.of(device("a","site","192.0.2.1")),List.of(source),"ALL");
  assertThat(l2(t)).isEmpty();assertThat(discovered(t).getFirst().qualityFlags()).contains("AMBIGUOUS_VLAN_MEMBERSHIP");assertThat(discovered(t).getFirst().vlanIds()).containsExactly(4,8);
 }
 @Test void sameMacInDistinctVlansStaysSeparatedAndUnknownArpCannotChooseDomain(){
  var s=source("org","a",Map.of("forwardingObservations",List.of(fdb(MAC,"Gi1/8",4),fdb(MAC,"Gi1/9",8)),"addressObservations",List.of(arp(MAC,"192.0.2.10"))));var t=graph(List.of(device("a","site","192.0.2.1")),List.of(s),"ALL");
  assertThat(discovered(t)).hasSize(3);assertThat(discovered(t).stream().filter(n->!n.addresses().isEmpty()).findFirst().orElseThrow().qualityFlags()).contains("VLAN_DOMAIN_AMBIGUOUS");
 }
 @Test void duplicateIpMacConflictAndVirtualRouterMacDoNotBecomeArbitraryCables(){
  var s=source("org","a",Map.of("addressObservations",List.of(arp(MAC,"192.0.2.10"),arp("02:11:22:33:44:66","192.0.2.10"),arp("00:00:5e:00:01:01","192.0.2.11")),"forwardingObservations",List.of(fdb(MAC,"Gi1/8"),fdb("02:11:22:33:44:66","Gi1/9"),fdb("00:00:5e:00:01:01","Gi1/10"))));
  var t=graph(List.of(device("a","site","192.0.2.1")),List.of(s),"ALL");assertThat(t.edges()).isEmpty();assertThat(discovered(t)).allMatch(n->n.confidence().equals("CONFLICT"));
 }
 @Test void multipleSwitchLearningPathsAreNotResolvedByArbitraryOrdering(){
  var a=source("org","a",Map.of("forwardingObservations",List.of(fdb(MAC,"Gi1/8",4))));var b=source("org","b",Map.of("forwardingObservations",List.of(fdb(MAC,"Gi1/9",4))));
  var t=graph(List.of(device("a","site","192.0.2.1"),device("b","site","192.0.2.2")),List.of(a,b),"ALL");assertThat(l2(t)).isEmpty();assertThat(discovered(t)).singleElement().satisfies(n->assertThat(n.qualityFlags()).contains("MULTIPLE_ACCESS_PATHS"));
 }
 @Test void unknownLldpNeighborAndItsFdbIdentityAreOneNodeAndUplinkLearningIsSuppressed(){
  String routerMac="02:aa:bb:cc:dd:ee";var s=source("org","a",Map.of("neighborObservations",List.of(neighbor(routerMac,"Te1/1")),"forwardingObservations",List.of(fdb(routerMac,"Te1/1",4),fdb(MAC,"Te1/1",4)),"addressObservations",List.of(arp(MAC,"192.0.2.10"))));
  var t=graph(List.of(device("a","site","192.0.2.1")),List.of(s),"ALL");assertThat(discovered(t)).hasSize(2);assertThat(l2(t)).isEmpty();assertThat(t.edges().stream().filter(e->e.kind().equals("PHYSICAL"))).hasSize(1);
  assertThat(discovered(t).stream().filter(n->routerMac.equals(n.mac())).findFirst().orElseThrow().evidence()).hasSize(2);
 }
 @Test void offlineRegisteredAssetIsReusedByIpAndArpCannotPromoteHealth(){
  var old=new Device("offline","offline","BMC","site","site",null,null,"192.0.2.10","UNKNOWN","OFFLINE",NOW.minusSeconds(172800),1,List.of());
  var s=source("org","a",Map.of("addressObservations",List.of(arp(MAC,"192.0.2.10")),"forwardingObservations",List.of(fdb(MAC,"Gi1/8",4))));var t=graph(List.of(device("a","site","192.0.2.1"),old),List.of(s),"ALL");
  assertThat(discovered(t)).isEmpty();assertThat(l2(t)).singleElement().satisfies(e->assertThat(e.target()).isEqualTo("offline"));assertThat(t.nodes().stream().filter(n->n.id().equals("offline"))).singleElement().satisfies(n->{assertThat(n.availability()).isEqualTo("OFFLINE");assertThat(n.health()).isEqualTo("UNKNOWN");});
 }
 @Test void expiredLeaseInvalidArpAndUnmappedFdbDoNotManufactureAttachments(){
  var invalid=new HashMap<>(arp(MAC,"192.0.2.10"));invalid.put("neighborState","INCOMPLETE");var unmapped=new HashMap<>(fdb(MAC,"Gi1/8",4));unmapped.remove("interfaceName");
  var lease=Map.<String,Object>of("mac",MAC,"address","192.0.2.10","interfaceName","Gi1/8","leaseSeconds",10,"observedAt",NOW.minusSeconds(20).toString(),"rowStatus","ACTIVE");
  var t=graph(List.of(device("a","site","192.0.2.1")),List.of(source("org","a",Map.of("addressObservations",List.of(invalid),"forwardingObservations",List.of(unmapped),"dhcpObservations",List.of(lease)))),"ALL");assertThat(t.edges()).isEmpty();assertThat(discovered(t)).singleElement().satisfies(n->{assertThat(n.addresses()).isEmpty();assertThat(n.qualityFlags()).contains("INTERFACE_MAPPING_MISSING");});
 }
 @Test void freshDhcpBindingCreatesL2InferenceButNotPhysical(){
  var row=Map.<String,Object>of("mac",MAC,"address","192.0.2.10","interfaceName","Gi1/8","leaseSeconds",300,"vlanId",4,"rowStatus","ACTIVE","sourceRef","1.3.6.1.4.1.9.9.380.1.4.1.1.2.4.2.17.34.51.68.85");
  var t=graph(List.of(device("a","site","192.0.2.1")),List.of(source("org","a",Map.of("dhcpObservations",List.of(row)))),"ALL");assertThat(l2(t)).hasSize(1);assertThat(t.edges()).allMatch(Edge::inferred);
 }
 @Test void scopeFreshnessAndSnapshotBudgetsAreExplicitAndDeterministic(){
  var a=device("a","site","192.0.2.1");var other=device("other","elsewhere","192.0.2.10");var s=source("org","a",Map.of("addressObservations",List.of(arp(MAC,"192.0.2.10")),"forwardingObservations",List.of(fdb(MAC,"Gi1/8",4))));
  var graph=new TopologyInference("org",List.of(a,other),List.of(s,source("other","other",Map.of("addressObservations",List.of(arp(MAC,"192.0.2.30"))))),NOW);
  var t=graph.build("site","a","ALL",4,2,List.of(),List.of());assertThat(t.nodes()).hasSize(2).allMatch(n->n.siteId().equals("site"));assertThat(discovered(t)).hasSize(1);assertThat(t.vlans().getFirst().nodeCount()).isEqualTo(2);
  var small=new TopologyInference("org",List.of(a),List.of(s),NOW).build("","","ALL",null,1,List.of(),List.of());assertThat(small.qualityFlags()).contains("NODE_LIMIT");assertThat(small.edges()).isEmpty();assertThat(small.vlans().getFirst().nodeCount()).isEqualTo(1);
  var fresh=graph(List.of(a),List.of(s),"ALL");assertThat(fresh).isEqualTo(graph(List.of(a),List.of(s),"ALL"));
  assertThat(new TopologyInference("org",List.of(a),List.of(s),NOW.plusSeconds(181)).build("","","ALL",null,200,List.of(),List.of()).edges()).isEmpty();
 }
 @Test void boundedRowsSignalTruncationAndNeverReturnDanglingEdges(){
  var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<600;i++)rows.add(arp(String.format("02:11:22:33:%02x:%02x",i/256,i%256),"198.51."+(i/254)+"."+(i%254+1)));
  var t=graph(List.of(device("a","site","192.0.2.1")),List.of(source("org","a",Map.of("addressObservations",rows))),"ALL");assertThat(t.qualityFlags()).contains("OBSERVATION_ROW_LIMIT","NODE_LIMIT");assertThat(t.nodes()).hasSize(200);var ids=t.nodes().stream().map(Node::id).toList();assertThat(t.edges()).allMatch(e->ids.contains(e.source())&&ids.contains(e.target())&&!e.source().equals(e.target()));
 }
 @Test void equivalentIpv6LiteralsReuseRegisteredIdentityWithoutDnsResolution(){
  var s=source("org","a",Map.of("addressObservations",List.of(arp(MAC,"2001:db8::10")),"forwardingObservations",List.of(fdb(MAC,"Gi1/8",4))));
  var t=graph(List.of(device("a","site","192.0.2.1"),device("ipv6","site","2001:0db8:0000:0000:0000:0000:0000:0010")),List.of(s),"ALL");
  assertThat(discovered(t)).isEmpty();assertThat(l2(t)).singleElement().satisfies(e->assertThat(e.target()).isEqualTo("ipv6"));
 }
 @Test void proxyArpWithRegisteredRouterMacCannotMergeRemoteAddressesIntoRouter(){
  var original=source("org","a",Map.of("addressObservations",List.of(arp(MAC,"192.0.2.10")),"forwardingObservations",List.of(fdb(MAC,"Gi1/8",4))));
  var facts=new HashMap<>(original.reading().facts());facts.put("chassisMacAddress",MAC);facts.put("chassisMacSource","1.3.6.1.4.1.6027.3.26.1.3.4.1.16.1");var r=original.reading();
  var s=new ObservedTopology.Source("org","a","192.0.2.1",new DeviceProtocol.Reading(NOW,r.identity(),r.health(),r.metrics(),r.sensors(),r.ports(),r.capabilities(),r.qualityFlags(),facts),"snmp");
  var t=graph(List.of(device("a","site","192.0.2.1")),List.of(s),"ALL");assertThat(t.edges()).isEmpty();assertThat(discovered(t)).singleElement().satisfies(n->assertThat(n.qualityFlags()).contains("PROXY_ARP_OR_SHARED_ROUTER_MAC"));
 }

}
