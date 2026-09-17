package io.noeriva.control.devices;

import io.noeriva.control.applications.ApplicationModels.*;
import org.snmp4j.smi.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static io.noeriva.control.devices.SnmpDriver.*;

/** GET/GETBULK only: independently scheduled NBAR, never enables router protocol discovery. */
@Component
public final class SnmpNbarDriver implements io.noeriva.control.applications.ApplicationProtocol,AutoCloseable {
    private static final String P="1.3.6.1.4.1.9.9.244.1.2.1.1.",S="1.3.6.1.4.1.9.9.244.1.1.1.1.",X="1.3.6.1.2.1.31.1.1.1.",IF="1.3.6.1.2.1.2.2.1.";
    private final Scheduler scheduler=Schedulers.newBoundedElastic(4,32,"snmp-nbar",60,true);
    public Mono<Sample> read(DeviceProtocol.Target target,DeviceProtocol.Secrets secrets,List<Integer> indices,int maxRows){
        return Mono.defer(()->{
            if(indices==null||indices.isEmpty()||indices.size()>8||new HashSet<>(indices).size()!=indices.size()||indices.stream().anyMatch(i->i==null||i<1)||maxRows<indices.size()||maxRows>256)
                return Mono.error(new DeviceProtocol.Failure("NBAR_INVALID_SELECTION","Select 1–8 distinct interfaces and a total row limit between the interface count and 256"));
            long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();var active=new AtomicReference<SnmpSession>();var cancelled=new AtomicBoolean();
            return Mono.fromCallable(()->{var flags=new LinkedHashSet<String>();try(var session=new SnmpSession(target,secrets,deadline,flags)){active.set(session);if(cancelled.get())return null;return collect(session,indices,maxRows,flags);}catch(Exception e){if(cancelled.get())return null;throw e;}finally{active.set(null);}})
                .subscribeOn(scheduler).doOnCancel(()->{cancelled.set(true);var s=active.getAndSet(null);if(s!=null)s.close();}).timeout(Duration.ofSeconds(25))
                .onErrorMap(e->e instanceof DeviceProtocol.Failure?e:new DeviceProtocol.Failure("NBAR_READ_FAILED","The bounded NBAR read could not complete"));
        });
    }
    private Sample collect(SnmpSession s,List<Integer> indices,int maxRows,Set<String> flags)throws Exception{
        var system=s.get(List.of("1.3.6.1.2.1.1.2.0","1.3.6.1.2.1.1.3.0"),true);
        String object=oid(system.get("1.3.6.1.2.1.1.2.0"));
        if(object==null||!object.startsWith("1.3.6.1.4.1.9."))throw new DeviceProtocol.Failure("NBAR_UNSUPPORTED","NBAR collection requires an observed Cisco enterprise identity");
        var meta=getColumns(s,indices,List.of(S+"1",S+"2",X+"1",IF+"2",IF+"6"));
        var rows=new ArrayList<CounterRow>();
        int remainingEnabled=(int)indices.stream().filter(index->Objects.equals(number(meta.get(S+"1."+index)),1L)).count();
        for(int index:indices){
            Long status=number(meta.get(S+"1."+index));
            if(!Objects.equals(status,1L)){flags.add(Objects.equals(status,2L)?"NBAR_DISABLED":"NBAR_UNSUPPORTED");continue;}
            String enable=meta.get(S+"2."+index) instanceof TimeTicks?unsigned(meta.get(S+"2."+index)):null;
            String name=text(meta.get(X+"1."+index));if(name==null)name=text(meta.get(IF+"2."+index));
            Variable mv=meta.get(IF+"6."+index);String mac=mv instanceof OctetString octets&&octets.length()>0&&octets.length()<=32?HexFormat.of().formatHex(octets.getValue()):null;
            String identity=name==null?null:name+"/"+Objects.toString(mac,"");
            // Reserve a fair share for every remaining enabled interface. Any
            // unused quota stays available to later interfaces in this sample.
            int available=(maxRows-rows.size())/remainingEnabled--;
            var names=s.walk(P+"2."+index,available+1,null);
            if(names.size()>available){flags.add("NBAR_ROW_LIMIT");var bounded=new LinkedHashMap<Integer,Variable>();names.entrySet().stream().limit(available).forEach(e->bounded.put(e.getKey(),e.getValue()));names=bounded;}
            var oids=new ArrayList<String>();for(int protocol:names.keySet())for(int column:List.of(7,8,9,10,11,12))oids.add(P+column+"."+index+"."+protocol);
            var values=s.get(oids,false);
            for(var e:names.entrySet()){
                int protocol=e.getKey();String app=text(e.getValue());if(app==null||protocol<1){flags.add("NBAR_INVALID_PROTOCOL");continue;}if(app.length()>255)app=app.substring(0,255);
                String suffix=index+"."+protocol;var rowFlags=new LinkedHashSet<String>();
                String in=hc(values.get(P+"9."+suffix)),out=hc(values.get(P+"10."+suffix)),inP=hc(values.get(P+"7."+suffix)),outP=hc(values.get(P+"8."+suffix));
                if(in==null||out==null||inP==null||outP==null)rowFlags.add("NBAR_COUNTER_UNAVAILABLE");
                if(enable==null)rowFlags.add("NBAR_ENABLE_TIME_UNAVAILABLE");if(identity==null)rowFlags.add("NBAR_INTERFACE_IDENTITY_UNAVAILABLE");
                rows.add(new CounterRow(index,name==null?"ifIndex "+index:name,identity,protocol,app,enable,in,out,inP,outP,rate(values.get(P+"11."+suffix)),rate(values.get(P+"12."+suffix)),List.copyOf(rowFlags)));
            }
        }
        var facts=new HashMap<String,String>();s.engineFacts(facts);
        return new Sample(Instant.now(),system.get("1.3.6.1.2.1.1.3.0") instanceof TimeTicks?unsigned(system.get("1.3.6.1.2.1.1.3.0")):null,facts.get("snmpEngineId"),facts.get("snmpEngineBoots"),List.copyOf(rows),List.copyOf(flags));
    }
    private static String hc(Variable v){return v instanceof Counter64?unsigned(v):null;}
    private static Double rate(Variable v){Long n=v instanceof UnsignedInteger32?number(v):null;return n==null?null:n.doubleValue()*1000;}
    @Override @jakarta.annotation.PreDestroy public void close(){scheduler.dispose();}
}
