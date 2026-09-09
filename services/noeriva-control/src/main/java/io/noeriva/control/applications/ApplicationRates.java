package io.noeriva.control.applications;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static io.noeriva.control.applications.ApplicationModels.*;

/** Exact unsigned counter deltas. A decrease without wrap evidence is a reset, never a spike. */
final class ApplicationRates {
    private ApplicationRates(){}
    static List<Observation> derive(String device,String sampleId,Sample next,Sample old,long credentialRevision,long oldCredentialRevision,int configuredInterval){
        var previous=new HashMap<String,CounterRow>();if(old!=null)old.rows().forEach(r->previous.put(key(r),r));
        double seconds=old==null?0:Duration.between(old.observedAt(),next.observedAt()).toMillis()/1000d;
        boolean continuity=old!=null&&credentialRevision==oldCredentialRevision&&seconds>0&&seconds<=Math.max(180,configuredInterval*3)&&uptime(old,next,seconds)
            &&Objects.equals(old.engineId(),next.engineId())&&Objects.equals(old.engineBoots(),next.engineBoots());
        var result=new ArrayList<Observation>();
        for(var row:next.rows()){
            var base=previous.get(key(row));
            String epoch=hash(credentialRevision+"/"+next.engineId()+"/"+next.engineBoots()+"/"+row.interfaceIdentity()+"/"+row.enableTime()+"/"+row.protocolIndex()+"/"+row.application());
            for(String direction:List.of("IN","OUT")){
                boolean in=direction.equals("IN");String bytes=in?row.inBytes():row.outBytes(),packets=in?row.inPackets():row.outPackets();
                var flags=new LinkedHashSet<>(next.qualityFlags());flags.addAll(row.qualityFlags());Double bps=null,pps=null,interval=null;
                boolean identity=base!=null&&row.interfaceIdentity()!=null&&row.enableTime()!=null&&Objects.equals(base.interfaceIdentity(),row.interfaceIdentity())&&Objects.equals(base.enableTime(),row.enableTime())&&base.application().equals(row.application());
                if(!continuity||!identity){flags.add(old!=null&&seconds>Math.max(180,configuredInterval*3)?"NBAR_GAP":"NBAR_BASELINE_REQUIRED");}
                else{
                    BigInteger delta=delta(bytes,in?base.inBytes():base.outBytes()),packetDelta=delta(packets,in?base.inPackets():base.outPackets());
                    if(delta!=null&&delta.signum()<0||packetDelta!=null&&packetDelta.signum()<0)flags.add("NBAR_COUNTER_RESET");
                    else{
                        if(delta!=null)bps=delta.doubleValue()*8/seconds;if(packetDelta!=null)pps=packetDelta.doubleValue()/seconds;
                        if(bps!=null||pps!=null)interval=seconds;
                        if(delta==null||packetDelta==null)flags.add("NBAR_COUNTER_UNAVAILABLE");
                    }
                }
                result.add(new Observation(hash(sampleId+"/"+key(row)+"/"+direction),device,row.interfaceIndex(),row.interfaceName(),row.protocolIndex(),row.application(),direction,next.observedAt(),bytes,packets,in?row.reportedInBps():row.reportedOutBps(),bps,pps,interval,epoch,List.copyOf(flags)));
            }
        }
        return List.copyOf(result);
    }
    private static String key(CounterRow row){return row.interfaceIndex()+"/"+row.protocolIndex();}
    private static BigInteger delta(String current,String old){try{if(current==null||old==null)return null;var a=new BigInteger(current);var b=new BigInteger(old);if(a.signum()<0||b.signum()<0||a.bitLength()>64||b.bitLength()>64)return null;return a.subtract(b);}catch(NumberFormatException e){return null;}}
    private static boolean uptime(Sample old,Sample next,double seconds){try{if(old.sysUptimeTicks()==null||next.sysUptimeTicks()==null)return false;long a=Long.parseLong(old.sysUptimeTicks()),b=Long.parseLong(next.sysUptimeTicks());return b>=a&&Math.abs((b-a)/100d-seconds)<=30;}catch(NumberFormatException e){return false;}}
    static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
