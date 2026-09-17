package io.noeriva.control.devices;

import jakarta.annotation.PreDestroy;
import org.snmp4j.smi.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, read-only SNMP collector. Profiles represent observed MIBs, not all-model certification. */
@Component
public final class SnmpDriver implements DeviceProtocol, AutoCloseable {
    private static final String SYS="1.3.6.1.2.1.1.", IF="1.3.6.1.2.1.2.2.1.", X="1.3.6.1.2.1.31.1.1.1.", ENTITY="1.3.6.1.2.1.47.1.1.1.1.";
    private final Scheduler scheduler=Schedulers.newBoundedElastic(8,64,"snmp-read",60,true);
    @Override public String protocol(){return "SNMP";}
    @Override public Mono<Reading> read(Target target,Secrets secrets){
        return Mono.defer(()->{
            long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
            AtomicReference<SnmpSession> active=new AtomicReference<>();
            AtomicBoolean cancelled=new AtomicBoolean();
            return Mono.fromCallable(()->{
                Set<String> flags=new LinkedHashSet<>();
                try(var session=new SnmpSession(target,secrets,deadline,flags)){
                    active.set(session);
                    if(cancelled.get())return null;
                    return collect(session,target,flags);
                }catch(Exception e){if(cancelled.get())return null;throw e;}
                finally{active.set(null);}
            }).subscribeOn(scheduler).doOnCancel(()->{cancelled.set(true);var s=active.getAndSet(null);if(s!=null)s.close();})
                .timeout(Duration.ofSeconds(25))
                .onErrorMap(e->e instanceof Failure?e:new Failure(e instanceof java.util.concurrent.TimeoutException?"SNMP_DEADLINE":"SNMP_READ_FAILED",
                    e instanceof java.util.concurrent.TimeoutException?"SNMP collection exceeded its 25 second budget.":"SNMP collection could not complete; verify target and security settings."));
        });
    }
    private Reading collect(SnmpSession s,Target target,Set<String> flags)throws Exception{
        long start=System.nanoTime();
        var system=s.get(List.of(SYS+"1.0",SYS+"2.0",SYS+"3.0",SYS+"5.0"),true);
        String object=oid(system.get(SYS+"2.0")),description=text(system.get(SYS+"1.0")),name=text(system.get(SYS+"5.0"));
        if(object==null && description==null)throw new Failure("SNMP_NO_IDENTITY","The agent returned no supported system identity objects.");
        var profile=SnmpProfiles.identify(object,description);
        Map<String,String> facts=new LinkedHashMap<>();
        put(facts,"sysUptimeTicks",unsigned(system.get(SYS+"3.0")));
        facts.put("identificationBasis",profile.basis());
        facts.put("snmpVersion",target.snmpVersion());
        if("3".equals(target.snmpVersion()))facts.put("snmpSecurityLevel",target.securityLevel());
        List<String> capabilities=new ArrayList<>(List.of("system-identity"));
        var huaweiIdentity=SnmpHuaweiBmc.identity(s,profile,facts,flags);

        // ENTITY class indexes are joined by their real suffix, never by list position.
        var classes=s.walk(ENTITY+"5",256,"SNMP_ENTITY_LIMIT");
        var entityData=getColumns(s,classes.keySet(),List.of(ENTITY+"7"));
        var chassisIndices=classes.entrySet().stream().filter(e->Objects.equals(number(e.getValue()),3L)).map(Map.Entry::getKey).toList();
        entityData.putAll(getColumns(s,chassisIndices,List.of(ENTITY+"9",ENTITY+"10",ENTITY+"11",ENTITY+"13")));
        Integer chassis=classes.entrySet().stream().filter(e->number(e.getValue())!=null && number(e.getValue())==3).map(Map.Entry::getKey).findFirst().orElse(null);
        // Selecting an arbitrary module as chassis would manufacture device serial/model identity.
        String model=atText(entityData,ENTITY+"13",chassis),serial=atText(entityData,ENTITY+"11",chassis),firmware=atText(entityData,ENTITY+"9",chassis);
        String software=identityText(atText(entityData,ENTITY+"10",chassis));
        model=identityText(model);serial=identityText(serial);firmware=identityText(firmware);
        if(model==null)model=huaweiIdentity.model();
        if(serial==null)serial=huaweiIdentity.serial();
        if(firmware==null)firmware=huaweiIdentity.firmware();
        if(software==null)software=SnmpEnvironment.softwareVersion(profile,description);
        put(facts,"softwareVersion",software);
        if(software!=null)facts.put("softwareVersionSource",identityText(atText(entityData,ENTITY+"10",chassis))==null?SYS+"1.0":ENTITY+"10."+chassis);
        if(model==null&&profile.id().equals("cisco-asr1002x-entity"))model="ASR1002-X";
        if(serial!=null)facts.put("serialKind","chassisSerial");
        if(profile.id().equals("dell-os9-s6100-chassis")){
            String base="1.3.6.1.4.1.6027.3.26.1.3.4.1.";
            var units=s.walk(base+"8",16,"SNMP_MULTIPLE_CHASSIS");
            if(units.size()==1){int unit=units.keySet().iterator().next();var detail=getColumns(s,List.of(unit),List.of(base+"10",base+"11",base+"16",base+"23"));
                if(serial==null){serial=identityText(atText(detail,base+"11",unit));if(serial!=null)facts.put("serialKind","unitSerial");}
                if(serial==null){serial=identityText(atText(detail,base+"23",unit));if(serial!=null)facts.put("serialKind","serviceTag");}
                if(software==null){software=identityText(atText(detail,base+"10",unit));put(facts,"softwareVersion",software);if(software!=null)facts.put("softwareVersionSource",base+"10."+unit);}
                // DELL-NETWORKING-CHASSIS-MIB dellNetStackUnitMacAddress, not an engine-ID suffix or a bridge-port address.
                Variable unitMac=detail.get(base+"16."+unit);
                if(unitMac instanceof OctetString octets&&octets.length()==6){String address=mac(unitMac);if(address!=null){facts.put("chassisMacAddress",address);facts.put("chassisMacSource",base+"16."+unit);}}
            }
        }
        if(!classes.isEmpty())capabilities.add("entity-inventory");
        if(classes.values().stream().filter(v->number(v)!=null && number(v)==3).count()>1)flags.add("SNMP_MULTIPLE_CHASSIS");

        var indexRows=s.walk(IF+"1",target.maxInterfaces()+1,null);
        var indices=new ArrayList<>(indexRows.keySet());
        if(indices.size()>target.maxInterfaces()){flags.add("SNMP_INTERFACE_LIMIT");indices=new ArrayList<>(indices.subList(0,target.maxInterfaces()));}
        Map<String,Variable> portsData=getColumns(s,indices,List.of(X+"1",IF+"6",X+"6",X+"10",X+"19",X+"15",IF+"7",IF+"8"));
        // Query fallback columns only for rows where the corresponding optional high-capacity data is absent.
        var fallback=new ArrayList<String>();
        for(Integer i:indices){
            if(text(portsData.get(X+"1."+i))==null)fallback.add(IF+"2."+i);
            if(positiveUnsigned(portsData.get(X+"15."+i))==null)fallback.add(IF+"5."+i);
            if(!(portsData.get(X+"6."+i) instanceof Counter64) && !(portsData.get(X+"10."+i) instanceof Counter64)){
                fallback.add(IF+"10."+i);fallback.add(IF+"16."+i);
            }
        }
        portsData.putAll(s.get(fallback,false));
        List<Port> ports=new ArrayList<>();Set<String> keys=new HashSet<>();
        for(Integer i:indices){
            String portName=text(portsData.get(X+"1."+i));if(portName==null)portName=text(portsData.get(IF+"2."+i));
            String mac=mac(portsData.get(IF+"6."+i));
            if(portName==null && mac==null){flags.add("SNMP_INTERFACE_IDENTITY_MISSING");continue;}
            if(portName==null || mac==null)flags.add("SNMP_WEAK_INTERFACE_IDENTITY");
            String key=stableKey(portName,mac);
            if(!keys.add(key)){flags.add("SNMP_DUPLICATE_INTERFACE_IDENTITY");continue;}
            Variable hcIn=portsData.get(X+"6."+i),hcOut=portsData.get(X+"10."+i);
            boolean hc=hcIn instanceof Counter64 || hcOut instanceof Counter64;
            String in=counter(hc?hcIn:portsData.get(IF+"10."+i),hc),out=counter(hc?hcOut:portsData.get(IF+"16."+i),hc);
            if(!hc && (in!=null || out!=null))flags.add("SNMP_COUNTER32_FALLBACK");
            if(in==null || out==null)flags.add("SNMP_COUNTERS_INCOMPLETE");
            String speed=positiveUnsigned(portsData.get(X+"15."+i));
            speed=speed==null?positiveUnsigned(portsData.get(IF+"5."+i)):new BigInteger(speed).multiply(BigInteger.valueOf(1_000_000)).toString();
            String discontinuity=portsData.get(X+"19."+i) instanceof TimeTicks?unsigned(portsData.get(X+"19."+i)):null;
            if(discontinuity==null)flags.add("SNMP_DISCONTINUITY_UNAVAILABLE");
            ports.add(new Port(key,portName==null?mac:portName,mac,speed,status(portsData.get(IF+"7."+i),true),status(portsData.get(IF+"8."+i),false),
                in,out,discontinuity,hc?64:32,IF+"1."+i));
        }
        if(!ports.isEmpty())capabilities.add("interface-counters");
        List<Sensor> sensors=new ArrayList<>();
        var incompleteMetrics=SnmpProfiles.sensors(s,profile,entityData,flags,sensors);
        SnmpEnvironment.collect(s,profile,sensors,facts,flags,classes,ports);
        boolean totalSensorLimit=sensors.size()>128;
        if(totalSensorLimit){
            flags.add("SNMP_SENSOR_LIMIT");
            sensors=new ArrayList<>(sensors.subList(0,128));
        }
        if(!sensors.isEmpty())capabilities.add("sensors");
        Map<String,Double> metrics=new LinkedHashMap<>();
        // A limit may hide another source: a retained singleton is not evidence of a device aggregate.
        for(String metric:List.of("cpu_percent","memory_percent","temperature_celsius")){
            if(totalSensorLimit||incompleteMetrics.contains(metric))continue;
            var matching=sensors.stream().filter(sensor->metric.equals(sensor.metric())).toList();
            if(matching.size()==1&&matching.getFirst().value()!=null)metrics.put(metric,matching.getFirst().value());
        }
        if(!totalSensorLimit&&!incompleteMetrics.contains("temperature_celsius"))sensors.stream().filter(sensor->"temperature_celsius".equals(sensor.metric())&&sensor.value()!=null).mapToDouble(Sensor::value).max().ifPresent(v->{metrics.put("temperature_celsius",v);facts.put("temperatureSummary","maximum_of_observed_temperature_sensors");});
        if(!totalSensorLimit&&facts.containsKey("powerWatts"))metrics.put("power_watts",Double.parseDouble(facts.remove("powerWatts")));
        String health=SnmpEnvironment.health(sensors,flags,facts);if(!health.equals("UNKNOWN"))capabilities.add("health");
        SnmpNeighbors.collect(s,ports,facts,flags,capabilities);
        SnmpEndpointEvidence.collect(s,profile,ports,facts,flags,capabilities);
        s.engineFacts(facts);
        facts.put("collectionDurationMillis",Long.toString(Duration.ofNanos(System.nanoTime()-start).toMillis()));
        facts.put("profileValidation","MIB_OBSERVED_NOT_HARDWARE_CERTIFIED");
        return new Reading(Instant.now(),new Identity(profile.vendor(),profile.family(),profile.id(),model,serial,firmware,object,name,description),
            health,Map.copyOf(metrics),List.copyOf(sensors),List.copyOf(ports),List.copyOf(capabilities),List.copyOf(flags),Map.copyOf(facts));
    }
    static Map<String,Variable> getColumns(SnmpSession s,Collection<Integer> indices,List<String> columns)throws Exception{
        List<String> oids=new ArrayList<>();for(String column:columns)for(Integer i:indices)oids.add(column+"."+i);return s.get(oids,false);
    }
    static String atText(Map<String,Variable> data,String column,Integer index){return index==null?null:text(data.get(column+"."+index));}
    static String text(Variable v){if(!(v instanceof OctetString value))return null;String result=value.toString().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","").trim();return result.isEmpty()?null:result.substring(0,Math.min(result.length(),1024));}
    static String identityText(String value){if(value==null||Set.of("na","n/a","none","unknown","not specified","not available","-").contains(value.strip().toLowerCase(Locale.ROOT)))return null;return value;}
    static String oid(Variable v){return v instanceof OID value?value.toDottedString():null;}
    static Long number(Variable v){if(v instanceof Integer32 i)return (long)i.getValue();if(v instanceof UnsignedInteger32 i)return i.getValue();return null;}
    static String unsigned(Variable v){if(v instanceof Counter64 c)return Long.toUnsignedString(c.getValue());if(v instanceof UnsignedInteger32 c)return Long.toString(c.getValue());return null;}
    private static String positiveUnsigned(Variable v){String n=unsigned(v);return n==null || "0".equals(n)?null:n;}
    private static String counter(Variable v,boolean hc){return (hc && v instanceof Counter64 || !hc && v instanceof Counter32)?unsigned(v):null;}
    private static String mac(Variable v){if(!(v instanceof OctetString value) || value.length()==0 || value.length()>32)return null;byte[] b=value.getValue();boolean nonzero=false;for(byte n:b)nonzero|=n!=0;return nonzero?HexFormat.ofDelimiter(":").formatHex(b):null;}
    private static String stableKey(String name,String mac)throws Exception{return "snmp:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(((name==null?"":name)+"\u0000"+(mac==null?"":mac)).getBytes(StandardCharsets.UTF_8))).substring(0,32);}
    private static void put(Map<String,String> map,String key,String value){if(value!=null)map.put(key,value);}
    private static String status(Variable v,boolean admin){Long n=number(v);if(n==null)return "UNKNOWN";return switch(n.intValue()){case 1->"UP";case 2->"DOWN";case 3->"TESTING";case 5->admin?"UNKNOWN":"DORMANT";case 6->admin?"UNKNOWN":"NOT_PRESENT";case 7->admin?"UNKNOWN":"LOWER_LAYER_DOWN";default->"UNKNOWN";};}
    @Override @PreDestroy public void close(){scheduler.dispose();}
}
