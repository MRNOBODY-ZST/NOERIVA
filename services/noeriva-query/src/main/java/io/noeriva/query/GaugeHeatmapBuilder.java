package io.noeriva.query;

import java.time.*;
import java.util.*;

/** Hourly means of bounded, evenly sampled device gauges; no counter-delta claim. */
final class GaugeHeatmapBuilder {
    HeatmapResponse build(String device,ZoneId zone,String direction,Instant now,int step,MetricRepository.Result result){
        if(result.points().size()>2000)throw new ProviderUnavailableException(result.source(),"Device gauge exceeds its point budget");
        var base=new TreeSet<>(result.qualityFlags());
        base.addAll(List.of("DEVICE_INTERFACE_AGGREGATE","INTERFACE_OVERLAP_POSSIBLE","GAUGE_SAMPLED_MEAN"));
        LocalDate today=now.atZone(zone).toLocalDate();var cells=new ArrayList<HeatmapCell>(168);
        Instant asOf=null;double expectedTotal=0,observedTotal=0;
        for(int d=6;d>=0;d--)for(int hour=0;hour<24;hour++){
            LocalDate date=today.minusDays(d);var intervals=HeatmapBuilder.intervals(date,hour,zone);var flags=new TreeSet<>(base);
            long duration=intervals.stream().mapToLong(TimeInterval::durationSeconds).sum();
            if(duration>3600)flags.add("DST_REPEATED");else if(duration>0&&duration<3600)flags.add("DST_SHORT_HOUR");
            boolean future=!intervals.isEmpty()&&intervals.getFirst().from().isAfter(now);
            boolean provisional=!future&&intervals.stream().anyMatch(i->i.to().isAfter(now));
            if(provisional)flags.add("PROVISIONAL");
            double expected=intervals.stream().filter(i->i.from().isBefore(now)).mapToDouble(i->Duration.between(i.from(),i.to().isAfter(now)?now:i.to()).toMillis()/1000.0/step).sum();
            var samples=result.points().stream().filter(p->p.value()!=null&&Double.isFinite(p.value())&&!p.timestamp().isAfter(now))
                .filter(p->intervals.stream().anyMatch(i->!p.timestamp().isBefore(i.from())&&p.timestamp().isBefore(i.to()))).toList();
            Instant observed=samples.stream().map(MetricPoint::timestamp).max(Comparator.naturalOrder()).orElse(null);
            if(observed!=null&&(asOf==null||observed.isAfter(asOf)))asOf=observed;
            Double value=samples.isEmpty()?null:samples.stream().mapToDouble(MetricPoint::value).average().orElseThrow();
            double coverage=expected==0?0:Math.min(1,samples.size()/expected);expectedTotal+=expected;observedTotal+=Math.min(samples.size(),expected);
            String state=intervals.isEmpty()?"DST_MISSING":future?"FUTURE":value==null?"MISSING":coverage<.999999||provisional?"PARTIAL":value==0?"OBSERVED_ZERO":"OBSERVED";
            cells.add(new HeatmapCell(date,hour,state,value,"bps","mean of sampled device aggregate bps gauges",intervals,coverage,observed,HeatmapBuilder.freshness(observed,now),result.revision(),provisional,List.copyOf(flags)));
        }
        cells.forEach(cell->base.addAll(cell.qualityFlags()));
        return new HeatmapResponse(device,"__device__",zone.getId(),direction,"sample_mean","bps",today.minusDays(6),today,asOf,result.source(),HeatmapBuilder.freshness(asOf,now),result.revision(),expectedTotal==0?0:observedTotal/expectedTotal,step,cells.stream().anyMatch(HeatmapCell::provisional),List.copyOf(base),List.copyOf(cells));
    }
}
