package io.noeriva.control.devices;

import java.math.*;
import java.time.Duration;
import java.util.*;

/** Counter differences require two ordered, uninterrupted samples; missing values stay missing. */
public final class DeviceCounters {
    private DeviceCounters(){}
    public static DeviceProtocol.Reading enrich(DeviceProtocol.Reading next,DeviceProtocol.Reading previous,String sourceEpoch,int interval){
        var metrics=new LinkedHashMap<>(next.metrics());var flags=new LinkedHashSet<>(next.qualityFlags());var facts=new LinkedHashMap<>(next.facts());
        String epoch=previous==null?sourceEpoch:previous.facts().getOrDefault("collectorEpoch",sourceEpoch);
        boolean restarted=previous!=null&&(restartedUptime(previous,next)||changed(previous.facts().get("snmpEngineBoots"),next.facts().get("snmpEngineBoots"))||changed(previous.facts().get("snmpEngineId"),next.facts().get("snmpEngineId")));
        if(restarted){epoch=UUID.randomUUID().toString();flags.add("COUNTER_SOURCE_RESTART");}facts.put("collectorEpoch",epoch);
        if(!next.ports().isEmpty()){
            double seconds=previous==null?0:Duration.between(previous.observedAt(),next.observedAt()).toNanos()/1e9;
            Map<String,DeviceProtocol.Port> prior=new HashMap<>();if(previous!=null)previous.ports().forEach(p->prior.put(p.key(),p));
            boolean rxComplete=true,txComplete=true;double rx=0,tx=0;
            for(var port:next.ports()){
                var old=prior.get(port.key());boolean valid=old!=null&&continuityEvidence(next)&&continuityEvidence(previous)&&!restarted&&seconds>0&&seconds<=Math.max(180,interval*3L)&&port.counterBits()==old.counterBits()&&port.discontinuity()!=null&&old.discontinuity()!=null&&Objects.equals(port.discontinuity(),old.discontinuity());
                Double r=valid?rate(old.inOctets(),port.inOctets(),port.counterBits(),port.speedBps(),seconds):null,t=valid?rate(old.outOctets(),port.outOctets(),port.counterBits(),port.speedBps(),seconds):null;
                if(r==null)rxComplete=false;else rx+=r;if(t==null)txComplete=false;else tx+=t;
            }
            if(rxComplete)metrics.put("bandwidth_rx_bps",rx);if(txComplete)metrics.put("bandwidth_tx_bps",tx);
            if(!rxComplete||!txComplete)flags.add(previous==null?"COUNTER_BASELINE_REQUIRED":"COUNTER_RATE_INCOMPLETE");
            facts.put("bandwidthSemantics","Sum of returned interfaces; logical and physical interfaces may overlap. Not site throughput.");
        }
        return new DeviceProtocol.Reading(next.observedAt(),next.identity(),next.health(),Map.copyOf(metrics),next.sensors(),next.ports(),next.capabilities(),List.copyOf(flags),Map.copyOf(facts));
    }
    static boolean continuityEvidence(DeviceProtocol.Reading reading){return reading!=null&&(reading.facts().get("sysUptimeTicks")!=null||reading.facts().get("snmpEngineBoots")!=null&&reading.facts().get("snmpEngineId")!=null);}
    private static boolean restartedUptime(DeviceProtocol.Reading old,DeviceProtocol.Reading next){
        try{String before=old.facts().get("sysUptimeTicks"),after=next.facts().get("sysUptimeTicks");if(before==null||after==null)return false;
            double ticks=new BigInteger(after).subtract(new BigInteger(before)).doubleValue()/100;
            double elapsed=Duration.between(old.observedAt(),next.observedAt()).toMillis()/1000.0;
            return ticks<0||Math.abs(ticks-elapsed)>30;
        }catch(NumberFormatException e){return true;}
    }
    private static boolean changed(String old,String next){return old!=null&&next!=null&&!old.equals(next);}
    static Double rate(String old,String next,int bits,String speed,double seconds){
        try{
            if(old==null||next==null||!(bits==32||bits==64))return null;
            BigInteger a=new BigInteger(old),b=new BigInteger(next),delta=b.subtract(a),limit=BigInteger.ONE.shiftLeft(bits);
            if(a.signum()<0||b.signum()<0||a.compareTo(limit)>=0||b.compareTo(limit)>=0||delta.signum()<0)return null;
            double maximum=speed==null?0:new BigInteger(speed).doubleValue();
            // At 32 bits, even a positive delta is ambiguous if a full wrap could occur during the interval.
            if(bits==32&&(maximum<=0||maximum*seconds/8>=limit.doubleValue()))return null;
            double value=delta.doubleValue()*8/seconds;if(!Double.isFinite(value)||maximum>0&&value>maximum*1.01)return null;
            return value;
        }catch(NumberFormatException e){return null;}
    }
}
