package io.noeriva.query;
import java.time.*;
import java.util.*;
import java.math.*;
public final class CounterRollup {
    public List<RollupBucket> aggregate(List<CounterSample> samples, Instant from, Instant to,
            int resolution, Duration maximumGap, long revision, Instant generatedAt) {
        Objects.requireNonNull(samples);
        if (!from.isBefore(to) || resolution < 1 || maximumGap.isNegative() || maximumGap.isZero()
                || samples.size()>100_000 || revision<0) throw new IllegalArgumentException("Invalid or oversized rollup window");
        long first=Math.floorDiv(from.getEpochSecond(),resolution)*resolution;
        long count=Math.ceilDiv(to.getEpochSecond()-first+(to.getNano()>0?1:0),resolution);
        if (count>10_000) throw new IllegalArgumentException("At most 10000 buckets per rollup job");
        TreeMap<Instant,CounterSample> ordered=new TreeMap<>();
        for (var sample:samples) {
            var old=ordered.putIfAbsent(sample.timestamp(),sample);
            if (old!=null && !old.equals(sample)) throw new IllegalArgumentException("Conflicting samples require explicit source correction before rollup");
        }
        var bins=new TreeMap<Instant,Accumulator>();
        for (long i=0;i<count;i++) {
            Instant start=Instant.ofEpochSecond(first+i*resolution);
            bins.put(start,new Accumulator(start,start.plusSeconds(resolution)));
        }
        CounterSample previous=null;
        for (var next:ordered.values()) {
            if (previous!=null) consume(previous,next,from,to,resolution,maximumGap,bins);
            previous=next;
        }
        return bins.values().stream().map(a->new RollupBucket(a.start,a.end,a.duration,a.delta,
                a.samples.size(),revision,generatedAt,a.flags,a.lastObserved)).toList();
    }

    private void consume(CounterSample previous,CounterSample next,Instant from,Instant to,int resolution,
            Duration maximumGap,NavigableMap<Instant,Accumulator> bins) {
        Instant start=previous.timestamp().isBefore(from)?from:previous.timestamp();
        Instant end=next.timestamp().isAfter(to)?to:next.timestamp();
        if (!start.isBefore(end)) return;
        Duration elapsed=Duration.between(previous.timestamp(),next.timestamp());
        Set<String> flags=new TreeSet<>();
        BigInteger delta=next.value().subtract(previous.value());
        boolean valid=true;
        if (!previous.sourceEpoch().equals(next.sourceEpoch())) { flags.add("SOURCE_RESTART");valid=false; }
        if (elapsed.compareTo(maximumGap)>0) { flags.add("SOURCE_GAP");valid=false; }
        if (delta.signum()<0) {
            if (next.wrapDeclared() && previous.sourceEpoch().equals(next.sourceEpoch())) {
                delta=BigInteger.ONE.shiftLeft(64).subtract(previous.value()).add(next.value());
                flags.add("COUNTER_WRAP");
            } else {flags.add("COUNTER_RESET");valid=false;}
        }
        for (Instant cursor=start;cursor.isBefore(end);) {
            Instant bucketStart=Instant.ofEpochSecond(Math.floorDiv(cursor.getEpochSecond(),resolution)*resolution);
            Instant stop=bucketStart.plusSeconds(resolution).isBefore(end)?bucketStart.plusSeconds(resolution):end;
            var a=bins.get(bucketStart);
            if (a!=null) {
                a.flags.addAll(flags);
                a.samples.add(previous.timestamp());a.samples.add(next.timestamp());
                if (valid) {
                    if(a.lastObserved==null||next.timestamp().isAfter(a.lastObserved))a.lastObserved=next.timestamp();
                    BigDecimal duration=seconds(Duration.between(cursor,stop));
                    BigDecimal totalDuration=seconds(elapsed);
                    a.duration+=duration.doubleValue();
                    a.delta=a.delta.add(new BigDecimal(delta).multiply(duration).divide(totalDuration,MathContext.DECIMAL128));
                    if (!cursor.equals(previous.timestamp()) || !stop.equals(next.timestamp())) a.flags.add("ESTIMATED_BOUNDARY");
                }
            }
            cursor=stop;
        }
    }
    private static BigDecimal seconds(Duration d) {
        return BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(),9));
    }
    private static final class Accumulator {
        final Instant start,end;
        double duration;
        Instant lastObserved;
        BigDecimal delta=BigDecimal.ZERO;
        Set<String> flags=new TreeSet<>();
        Set<Instant> samples=new HashSet<>();
        Accumulator(Instant start,Instant end) {this.start=start;this.end=end;}
    }
}
