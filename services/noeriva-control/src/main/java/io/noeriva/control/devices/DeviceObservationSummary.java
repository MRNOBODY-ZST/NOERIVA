package io.noeriva.control.devices;

import java.util.*;

/** Human-readable evidence carried by events and alert titles, without raw credentials/output. */
public final class DeviceObservationSummary {
    private DeviceObservationSummary() {}
    static Set<String> excludedSensors(Map<String,String> facts){String ids=facts.get("healthExcludedSensorIds");return ids==null||ids.isBlank()?Set.of():Set.copyOf(Arrays.asList(ids.split(",")));}
    public static String message(DeviceProtocol.Reading reading,String protocol){
        var parts=new ArrayList<String>();parts.add(protocol+" 采集：健康 "+reading.health());
        var excluded=excludedSensors(reading.facts());
        var bad=reading.sensors().stream().filter(s->!excluded.contains(s.id())&&Set.of("WARNING","CRITICAL").contains(s.health())).limit(4).toList();
        for(var s:bad)parts.add(s.label()+" "+s.health()+(s.value()==null?"":" ("+String.format(Locale.ROOT,"%.2f",s.value())+" "+s.unit()+")"));
        if(!reading.ports().isEmpty()){
            long up=reading.ports().stream().filter(p->p.operStatus().equals("UP")).count();parts.add("接口 UP "+up+" / "+reading.ports().size());
            reading.ports().stream().filter(p->p.adminStatus().equals("UP")&&Set.of("DOWN","LOWER_LAYER_DOWN").contains(p.operStatus())).limit(3).forEach(p->parts.add(p.name()+" 管理UP / 运行"+p.operStatus()));
        }
        if(!reading.sensors().isEmpty())parts.add("观测传感器 "+reading.sensors().size()+" 项");
        if(!excluded.isEmpty())parts.add("管理关闭接口的低光功率/偏置阈值 "+excluded.size()+" 项仅保留组件证据，不计整机故障");
        if(!reading.qualityFlags().isEmpty())parts.add("部分数据："+String.join(", ",reading.qualityFlags().stream().limit(4).toList()));
        String message=String.join("；",parts);return message.substring(0,Math.min(message.length(),1000));
    }
}
