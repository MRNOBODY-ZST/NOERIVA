package io.noeriva.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/** Full replacement snapshot; revision is never added to an older snapshot. */
public record RollupBucket(Instant start, Instant end, double validDurationSeconds,
        BigDecimal validCounterDelta, int sampleCount, long revision, Instant generatedAt,
        Set<String> qualityFlags, Instant lastObserved) {
    public RollupBucket(Instant start, Instant end, double validDurationSeconds, BigDecimal validCounterDelta,
            int sampleCount, long revision, Instant generatedAt, Set<String> qualityFlags) {
        this(start,end,validDurationSeconds,validCounterDelta,sampleCount,revision,generatedAt,qualityFlags,
            validDurationSeconds==java.time.Duration.between(start,end).toMillis()/1000.0?end:null);
    }
    public RollupBucket {
        if (start == null || end == null || !start.isBefore(end) || generatedAt == null
                || !Double.isFinite(validDurationSeconds) || validDurationSeconds < 0
                || validDurationSeconds > java.time.Duration.between(start,end).toMillis()/1000.0 + 1e-6
                || validCounterDelta == null || validCounterDelta.signum() < 0 || revision < 0 || sampleCount < 0) {
            throw new IllegalArgumentException("Invalid rollup snapshot");
        }
        qualityFlags = Set.copyOf(qualityFlags);
    }
    public Double averageBps() {
        if (validDurationSeconds == 0) return null;
        return validCounterDelta.multiply(BigDecimal.valueOf(8))
                .divide(BigDecimal.valueOf(validDurationSeconds), java.math.MathContext.DECIMAL128).doubleValue();
    }
}
