package io.noeriva.query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Bounded aggregation of original observations, never averages of downsampled means. */
final class MetricAggregation {
    static final int MAX_SAMPLES=100_000;
    record Result(List<MetricPoint> points, MetricWindowSummary summary, List<String> flags) {}
    static int cadence(List<MetricPoint> points) {
        if(points.size()<2)return 60;
        long[] gaps=new long[points.size()-1];int count=0;
        for(int i=1;i<points.size();i++) {
            long gap=Duration.between(points.get(i-1).timestamp(),points.get(i).timestamp()).toMillis();
            if(gap>0)gaps[count++]=gap;
        }
        if(count==0)return 60;
        Arrays.sort(gaps,0,count);
        return (int)Math.max(1,Math.min(3600,Math.ceil(gaps[(count-1)/2]/1000.0)));
    }
    static boolean validSample(MetricPoint point,boolean bandwidth) {
        return point!=null&&point.value()!=null&&Double.isFinite(point.value())&&(!bandwidth||point.value()>=0);
    }
    static Result aggregate(List<MetricPoint> input, Instant from, Instant to, int step, boolean bandwidth) {
        if(input.size()>MAX_SAMPLES)throw new ProviderUnavailableException("VICTORIAMETRICS","Raw metric sample budget exceeded");
        List<MetricPoint> samples=input.stream().sorted(Comparator.comparing(MetricPoint::timestamp)).toList();
        long durationMillis=Duration.between(from,to).toMillis();
        int bins=(int)Math.max(1,(durationMillis+step*1000L-1)/(step*1000L));
        double[] sums=new double[bins];int[] counts=new int[bins];
        double total=0,observedSeconds=0;long count=0;BigDecimal bits=BigDecimal.ZERO;
        int maximumGap=cadence(samples)*3;
        var flags=new TreeSet<String>();flags.add("GAUGE_SAMPLE_MEAN");
        flags.add("TEMPORAL_COVERAGE_ESTIMATED");
        MetricPoint previous=null;
        for(MetricPoint point:samples) {
            if(point.timestamp().isBefore(from)||point.timestamp().isAfter(to)
                    ||(previous!=null&&!point.timestamp().isAfter(previous.timestamp())))
                throw new ProviderUnavailableException("VICTORIAMETRICS","Invalid raw metric ordering or window");
            boolean valid=validSample(point,bandwidth);
            if(valid) {
                int bin=(int)Math.min(bins-1,Duration.between(from,point.timestamp()).toMillis()/(step*1000L));
                sums[bin]+=point.value();counts[bin]++;total+=point.value();count++;
                if(validSample(previous,bandwidth)) {
                    double elapsed=Duration.between(previous.timestamp(),point.timestamp()).toMillis()/1000.0;
                    if(elapsed>0&&elapsed<=maximumGap) {
                        observedSeconds+=elapsed;
                        // Collector rates describe the preceding interval. Its exact duration is unavailable in old gauges.
                        if(bandwidth)bits=bits.add(BigDecimal.valueOf(point.value()).multiply(BigDecimal.valueOf(elapsed)));
                    } else flags.add("GAPS_NOT_EXTRAPOLATED");
                }
            } else flags.add("MISSING_SAMPLE");
            previous=point;
        }
        List<MetricPoint> points=new ArrayList<>();
        if(count>0)for(int i=0;i<bins;i++) {
            Double value=counts[i]==0?null:sums[i]/counts[i];
            if(value!=null&&!Double.isFinite(value))throw new ProviderUnavailableException("VICTORIAMETRICS","Metric bucket aggregate overflow");
            points.add(new MetricPoint(from.plusMillis(Math.min(durationMillis,(i+1)*step*1000L)),value));
        }
        Double mean=count==0?null:total/count;
        if(mean!=null&&!Double.isFinite(mean))throw new ProviderUnavailableException("VICTORIAMETRICS","Metric aggregate overflow");
        String bytes=null;
        if(bandwidth&&observedSeconds>0) {
            bytes=bits.divide(BigDecimal.valueOf(8),0,RoundingMode.HALF_UP).toPlainString();
            flags.add("VOLUME_ESTIMATE");
        }
        double coverage=durationMillis<=0?0:Math.min(1,observedSeconds/(durationMillis/1000.0));
        if(coverage<1)flags.add("INCOMPLETE_TIME_COVERAGE");
        return new Result(List.copyOf(points),new MetricWindowSummary(mean,count,bytes,observedSeconds,coverage,"sample_mean"),List.copyOf(flags));
    }
}
