package io.noeriva.query;

import java.math.*;
import java.time.*;
import java.util.*;

/** Square presentation of distinct, bounded time intervals from the already loaded observations. */
final class SquareHeatmapBuilder {
    private static final int TICK_SECONDS=300;

    HeatmapMatrix gauges(ZoneId zone,Instant now,int size,int cadence,MetricRepository.Result result) {
        if(result.points().size()>MetricAggregation.MAX_SAMPLES)
            throw new ProviderUnavailableException(result.source(),"Device gauge exceeds its point budget");
        var boundaries=boundaries(zone,now,size);int count=size*size;
        var flags=new TreeSet<>(result.qualityFlags());
        flags.addAll(List.of("DEVICE_INTERFACE_AGGREGATE","INTERFACE_OVERLAP_POSSIBLE","GAUGE_SAMPLE_MEAN","TEMPORAL_COVERAGE_ESTIMATED"));
        var samples=new long[count];var means=new double[count];var observed=new Instant[count];
        for(var point:result.points()) {
            if(!MetricAggregation.validSample(point,true)) {flags.add("MISSING_SAMPLE");continue;}
            if(point.timestamp().isAfter(now))continue;
            int index=index(boundaries,point.timestamp());if(index<0)continue;
            // Online mean avoids overflowing a sum of large, individually finite observations.
            samples[index]++;means[index]+=(point.value()-means[index])/samples[index];
            if(observed[index]==null||point.timestamp().isAfter(observed[index]))observed[index]=point.timestamp();
        }
        var cells=new ArrayList<MatrixCell>(count);
        for(int index=0;index<count;index++) {
            var interval=interval(boundaries,index);var cellFlags=new TreeSet<>(flags);
            boolean future=interval.from().isAfter(now),provisional=!future&&interval.to().isAfter(now);
            double expected=expectedSeconds(interval,now)/cadence;
            double coverage=expected<=0?0:Math.min(1,samples[index]/expected);
            Double value=samples[index]==0?null:means[index];
            transitionFlags(zone,interval,cellFlags);if(provisional)cellFlags.add("PROVISIONAL");
            cells.add(new MatrixCell(index,List.of(interval),state(future,provisional,value,coverage),value,
                coverage,observed[index],HeatmapBuilder.freshness(observed[index],now),result.revision(),
                provisional,List.copyOf(cellFlags),samples[index]));
        }
        return new HeatmapMatrix(size,List.copyOf(cells));
    }

    HeatmapMatrix rollups(ZoneId zone,Instant now,int size,List<RollupBucket> input,String source) {
        if(input.size()>10_000)throw new IllegalArgumentException("Too many rollup snapshots");
        var boundaries=boundaries(zone,now,size);int count=size*size;
        var latest=new TreeMap<Instant,RollupBucket>();
        for(var bucket:input) {
            var previous=latest.get(bucket.start());
            if(previous==null||bucket.revision()>previous.revision())latest.put(bucket.start(),bucket);
            else if(bucket.revision()==previous.revision()&&!bucket.equals(previous))
                throw new IllegalArgumentException("Conflicting rollup revisions");
        }
        var deltas=new BigDecimal[count];Arrays.fill(deltas,BigDecimal.ZERO);
        var valid=new double[count];var revisions=new long[count];var observed=new Instant[count];
        var flags=new ArrayList<TreeSet<String>>(count);
        for(int i=0;i<count;i++)flags.add(new TreeSet<>("SIMULATED".equals(source)?Set.of("SIMULATED"):Set.of()));
        RollupBucket previous=null;
        for(var bucket:latest.values()) {
            if(previous!=null&&previous.end().isAfter(bucket.start()))
                throw new IllegalArgumentException("Overlapping resolutions or sources require explicit selection");
            previous=bucket;
            int index=index(boundaries,bucket.start());
            if(index<0) {
                if(bucket.start().isBefore(boundaries[0])&&bucket.end().isAfter(boundaries[0]))flags.getFirst().add("RESOLUTION_UNAVAILABLE");
                continue;
            }
            if(bucket.end().isAfter(boundaries[index+1])) {
                // An indivisible stored bucket cannot imply exact observations on either side of a display boundary.
                for(int affected=index;affected<count&&boundaries[affected].isBefore(bucket.end());affected++)
                    flags.get(affected).add("RESOLUTION_UNAVAILABLE");
                continue;
            }
            if(bucket.end().isAfter(now))continue;
            flags.get(index).addAll(bucket.qualityFlags());revisions[index]=Math.max(revisions[index],bucket.revision());
            valid[index]+=bucket.validDurationSeconds();deltas[index]=deltas[index].add(bucket.validCounterDelta());
            if(bucket.lastObserved()!=null&&bucket.lastObserved().isAfter(now))flags.get(index).add("CLOCK_SKEWED");
            else if(bucket.validDurationSeconds()>0&&bucket.lastObserved()!=null
                    &&(observed[index]==null||bucket.lastObserved().isAfter(observed[index])))observed[index]=bucket.lastObserved();
        }
        var cells=new ArrayList<MatrixCell>(count);
        for(int index=0;index<count;index++) {
            var interval=interval(boundaries,index);boolean future=interval.from().isAfter(now),provisional=!future&&interval.to().isAfter(now);
            double expected=expectedSeconds(interval,now),coverage=expected<=0?0:Math.min(1,valid[index]/expected);
            Double value=valid[index]==0?null:deltas[index].multiply(BigDecimal.valueOf(8)).divide(BigDecimal.valueOf(valid[index]),MathContext.DECIMAL128).doubleValue();
            if(value!=null&&!Double.isFinite(value))throw new ProviderUnavailableException(source,"Matrix bandwidth exceeds numeric range");
            var cellFlags=flags.get(index);transitionFlags(zone,interval,cellFlags);if(provisional)cellFlags.add("PROVISIONAL");
            cells.add(new MatrixCell(index,List.of(interval),state(future,provisional,value,coverage),value,
                coverage,observed[index],observed[index]==null?"MISSING":coverage>=.999999?"FRESH":"STALE",
                revisions[index],provisional,List.copyOf(cellFlags),null));
        }
        return new HeatmapMatrix(size,List.copyOf(cells));
    }

    private Instant[] boundaries(ZoneId zone,Instant now,int size) {
        QueryService.validateGridSize(size);if(size==0)throw new IllegalArgumentException("A matrix requires gridSize 7 or 28");
        var today=now.atZone(zone).toLocalDate();
        Instant start=today.minusDays(6).atStartOfDay(zone).toInstant(),end=today.plusDays(1).atStartOfDay(zone).toInstant();
        long seconds=Duration.between(start,end).getSeconds(),ticks=(seconds+TICK_SECONDS-1)/TICK_SECONDS;
        int count=size*size;var result=new Instant[count+1];
        for(int i=0;i<=count;i++)result[i]=start.plusSeconds(Math.min(seconds,ticks*i/count*TICK_SECONDS));
        return result;
    }

    private int index(Instant[] boundaries,Instant timestamp) {
        if(timestamp.isBefore(boundaries[0])||!timestamp.isBefore(boundaries[boundaries.length-1]))return -1;
        int found=Arrays.binarySearch(boundaries,timestamp);return found>=0?found:-found-2;
    }

    private TimeInterval interval(Instant[] boundaries,int index) {
        return new TimeInterval(boundaries[index],boundaries[index+1],Duration.between(boundaries[index],boundaries[index+1]).getSeconds());
    }

    private double expectedSeconds(TimeInterval interval,Instant now) {
        if(!interval.from().isBefore(now))return 0;
        return Duration.between(interval.from(),interval.to().isAfter(now)?now:interval.to()).toMillis()/1000.0;
    }

    private String state(boolean future,boolean provisional,Double value,double coverage) {
        return future?"FUTURE":value==null?"MISSING":coverage<.999999||provisional?"PARTIAL":value==0?"OBSERVED_ZERO":"OBSERVED";
    }

    private void transitionFlags(ZoneId zone,TimeInterval interval,Set<String> flags) {
        var transition=zone.getRules().nextTransition(interval.from().minusNanos(1));
        if(transition!=null&&transition.getInstant().isBefore(interval.to()))flags.add("TIMEZONE_TRANSITION");
    }
}
