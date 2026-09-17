package io.noeriva.query;
import java.time.*;
import java.util.*;
import java.math.*;
import java.time.zone.*;
public final class HeatmapBuilder {
    public HeatmapResponse build(String device, String component, ZoneId zone, String direction,
            Instant now, List<RollupBucket> buckets, String source) {
        LocalDate today=now.atZone(zone).toLocalDate();
        if (buckets.size()>10_000) throw new IllegalArgumentException("Too many rollup snapshots");
        var latest=new TreeMap<Instant,RollupBucket>();
        for(var b:buckets) {
            var old=latest.get(b.start());
            if (old==null || b.revision()>old.revision()) latest.put(b.start(),b);
            else if (b.revision()==old.revision() && !b.equals(old)) throw new IllegalArgumentException("Conflicting rollup revisions");
        }
        RollupBucket prior=null;
        for(var b:latest.values()) {
            if (prior!=null && prior.end().isAfter(b.start())) throw new IllegalArgumentException("Overlapping resolutions or sources require explicit selection");
            prior=b;
        }
        List<HeatmapCell> cells=new ArrayList<>(168);
        for(int d=6;d>=0;d--) {
            LocalDate date=today.minusDays(d);
            for(int h=0;h<24;h++) cells.add(cell(date,h,intervals(date,h,zone),now,latest));
        }
        Instant asOf=cells.stream().map(HeatmapCell::asOf).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        long revision=cells.stream().mapToLong(HeatmapCell::dataRevision).max().orElse(0);
        var eligible=cells.stream().filter(c->!c.state().equals("FUTURE")&&!c.state().equals("DST_MISSING")).toList();
        double expected=0,observed=0;
        for(var c:eligible) {
            double seconds=c.intervals().stream().filter(i->i.from().isBefore(now))
                .mapToDouble(i->Duration.between(i.from(),i.to().isAfter(now)?now:i.to()).toMillis()/1000.0).sum();
            expected+=seconds;observed+=seconds*c.coverage();
        }
        double coverage=expected==0?0:observed/expected;
        Set<String> flags=new TreeSet<>();cells.forEach(c->flags.addAll(c.qualityFlags()));
        if("SIMULATED".equals(source)) flags.add("SIMULATED");
        return new HeatmapResponse(device,component,zone.getId(),direction,"time_weighted_mean","bps",today.minusDays(6),today,
                asOf,source,freshness(asOf,now),revision,coverage,300,cells.stream().anyMatch(HeatmapCell::provisional),List.copyOf(flags),List.copyOf(cells));
    }

    private HeatmapCell cell(LocalDate date,int hour,List<TimeInterval> intervals,Instant now,NavigableMap<Instant,RollupBucket> data) {
        Set<String> flags=new TreeSet<>();
        long totalDuration=intervals.stream().mapToLong(TimeInterval::durationSeconds).sum();
        if(totalDuration>3600) flags.add("DST_REPEATED");
        else if(totalDuration>0 && totalDuration<3600) flags.add("DST_SHORT_HOUR");
        boolean future=!intervals.isEmpty() && intervals.getFirst().from().isAfter(now);
        boolean provisional=!future&&intervals.stream().anyMatch(i->i.to().isAfter(now));
        double expected=0,valid=0;
        BigDecimal delta=BigDecimal.ZERO;
        Instant asOf=null;long revision=0;
        for(var interval:intervals) {
            Instant cutoff=interval.to().isAfter(now)?now:interval.to();
            if(!interval.from().isBefore(cutoff)) continue;
            expected+=Duration.between(interval.from(),cutoff).toMillis()/1000.0;
            var entry=data.floorEntry(interval.from());
            Instant key=entry==null?interval.from():entry.getKey();
            for(var b:data.subMap(key,true,interval.to(),false).values()) {
                if(!b.end().isAfter(interval.from())) continue;
                if(b.start().isBefore(interval.from()) || b.end().isAfter(interval.to())) {
                    flags.add("RESOLUTION_UNAVAILABLE");continue;
                }
                if(b.end().isAfter(cutoff)) continue; // Uncommitted/future bucket cannot imply observed coverage.
                flags.addAll(b.qualityFlags());revision=Math.max(revision,b.revision());
                valid+=b.validDurationSeconds();delta=delta.add(b.validCounterDelta());
                if(b.lastObserved()!=null && b.lastObserved().isAfter(now)) flags.add("CLOCK_SKEWED");
                else if(b.validDurationSeconds()>0 && b.lastObserved()!=null && (asOf==null || b.lastObserved().isAfter(asOf))) asOf=b.lastObserved();
            }
        }
        double coverage=expected<=0?0:Math.min(1,valid/expected);
        Double value=valid==0?null:delta.multiply(BigDecimal.valueOf(8)).divide(BigDecimal.valueOf(valid),MathContext.DECIMAL128).doubleValue();
        String state=intervals.isEmpty()?"DST_MISSING":future?"FUTURE":valid==0?"MISSING":coverage<0.999999||provisional?"PARTIAL":delta.signum()==0?"OBSERVED_ZERO":"OBSERVED";
        if(provisional) flags.add("PROVISIONAL");
        return new HeatmapCell(date,hour,state,value,"bps","8 * valid_counter_delta / valid_duration_seconds",intervals,
                coverage,asOf,asOf==null?"MISSING":coverage>=0.999999?"FRESH":"STALE",revision,provisional,List.copyOf(flags));
    }

    /** Intersect each local-hour projection with real constant-offset segments, including half-hour DST transitions. */
    static List<TimeInterval> intervals(LocalDate date,int hour,ZoneId zone) {
        Instant dayStart=date.atStartOfDay(zone).toInstant(),dayEnd=date.plusDays(1).atStartOfDay(zone).toInstant();
        if(!dayStart.isBefore(dayEnd)) return List.of();
        ZoneRules rules=zone.getRules();
        List<Instant> boundaries=new ArrayList<>();boundaries.add(dayStart);
        for(ZoneOffsetTransition transition=rules.nextTransition(dayStart.minusNanos(1));transition!=null&&transition.getInstant().isBefore(dayEnd);transition=rules.nextTransition(transition.getInstant())) {
            if(transition.getInstant().isAfter(dayStart)) boundaries.add(transition.getInstant());
        }
        boundaries.add(dayEnd);
        LocalDateTime localStart=date.atTime(hour,0),localEnd=localStart.plusHours(1);
        List<TimeInterval> result=new ArrayList<>();
        for(int i=0;i<boundaries.size()-1;i++) {
            Instant segStart=boundaries.get(i),segEnd=boundaries.get(i+1);
            ZoneOffset offset=rules.getOffset(segStart);
            Instant from=localStart.toInstant(offset),to=localEnd.toInstant(offset);
            if(from.isBefore(segStart)) from=segStart;
            if(to.isAfter(segEnd)) to=segEnd;
            if(from.isBefore(to)) result.add(new TimeInterval(from,to,Duration.between(from,to).getSeconds()));
        }
        return List.copyOf(result);
    }
    static String freshness(Instant observed,Instant now) {
        return observed==null?"MISSING":observed.isBefore(now.minusSeconds(600))?"STALE":"FRESH";
    }
}
