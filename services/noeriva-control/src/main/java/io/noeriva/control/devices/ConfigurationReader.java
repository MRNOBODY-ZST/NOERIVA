package io.noeriva.control.devices;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;
import static io.noeriva.control.devices.DeviceAccessModels.Stored;

/** Separate bounded read-only session; raw running configuration never enters telemetry. */
@Component
public class ConfigurationReader {
 public record Capture(String content,String source,Instant capturedAt){}
 private final DeviceAccessStore connections;private final CredentialVault vault;private final TargetPolicy policy;private final Map<String,DeviceProtocol> drivers;private final JsonMapper json;
 public ConfigurationReader(DeviceAccessStore connections,CredentialVault vault,TargetPolicy policy,List<DeviceProtocol> drivers,JsonMapper json){this.connections=connections;this.vault=vault;this.policy=policy;this.json=json;this.drivers=new HashMap<>();drivers.forEach(d->this.drivers.put(d.protocol(),d));}
 static String normalizeRunningConfiguration(String raw){
  String normalized=raw.replace("\r\n","\n").replace('\r','\n');
  if(java.util.regex.Pattern.compile("(?im)^\\s*%[^\\n]*(?:invalid|error|unrecognized|authorization|denied|failed)").matcher(normalized).find())throw new DeviceProtocol.Failure("CONFIGURATION_ACCESS_DENIED","The device refused to return running configuration");
  var lines=new ArrayList<String>(Arrays.asList(normalized.split("\n",-1)));
  while(!lines.isEmpty()&&(lines.getFirst().isBlank()||lines.getFirst().strip().equals("show running-config")||lines.getFirst().startsWith("Building configuration")||lines.getFirst().startsWith("Current configuration")))lines.removeFirst();
  while(!lines.isEmpty()&&lines.getLast().isBlank())lines.removeLast();
  if(lines.isEmpty()||!lines.getLast().strip().equals("end")||lines.stream().noneMatch(v->v.startsWith("hostname ")))throw new DeviceProtocol.Failure("CONFIGURATION_INCOMPLETE","The device did not return a complete running configuration");
  String content=String.join("\n",lines);
  if(lines.size()>2000||content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072)throw new DeviceProtocol.Failure("CONFIGURATION_OUTPUT_LIMIT","Configuration exceeds the 2000-line or 128-KiB capture limit");
  return content;
 }
 public Mono<Capture> read(String org,String device){return connections.list(org,device).filter(Stored::enabled).filter(v->Set.of("snmp","redfish","ssh").contains(v.slot())).sort(Comparator.comparingInt(v->switch(v.slot()){case "snmp"->0;case "redfish"->1;default->2;})).next().switchIfEmpty(Mono.error(new DeviceProtocol.Failure("CONFIGURATION_UNSUPPORTED","No enabled SNMP, Redfish or SSH connection is available for read-only synchronization"))).flatMap(v->policy.resolve(v.settings().host()).flatMap(address->{
  var target=v.settings().target(v.protocol(),address);var secrets=vault.decrypt(org+"/"+device+"/"+v.slot(),v.ciphertext());
  if(v.slot().equals("ssh")&&Set.of("DELL_OS9","CISCO_IOS_XE").contains(v.settings().sshProfile()))return Mono.fromCallable(()->{try(var session=new SshSession(target,System.nanoTime()+Duration.ofSeconds(35).toNanos())){session.connect(secrets);String text=normalizeRunningConfiguration(session.readRunningConfiguration());return new Capture(text,"SSH_RUNNING_CONFIGURATION",Instant.now());}}).subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofSeconds(38));
  // A selected protocol failure remains visible; it never initiates a lower-priority SSH session.
  var driver=drivers.get(v.protocol());if(driver==null)return Mono.error(new DeviceProtocol.Failure("PROTOCOL_UNAVAILABLE","No driver is installed for the selected configuration source"));
  return driver.read(target,secrets).map(r->{var body=new LinkedHashMap<String,Object>();body.put("scope","DEVICE_IDENTITY_AND_INTERFACE_BASELINE");body.put("identity",r.identity());body.put("interfaces",r.ports().stream().map(p->Map.of("name",p.name(),"adminStatus",p.adminStatus(),"speedBps",Objects.toString(p.speedBps(),""))).toList());return new Capture(json.writerWithDefaultPrettyPrinter().writeValueAsString(body),v.protocol()+"_DEVICE_BASELINE",r.observedAt());});
 }));}
}
