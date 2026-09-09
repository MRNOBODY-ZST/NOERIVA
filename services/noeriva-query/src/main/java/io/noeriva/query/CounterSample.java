package io.noeriva.query;

import java.math.BigInteger;
import java.time.Instant;

/** Source counter values remain exact until a rate or interval estimate is calculated. */
public record CounterSample(Instant timestamp, BigInteger value, String sourceEpoch, boolean wrapDeclared) {
    public CounterSample {
        java.util.Objects.requireNonNull(timestamp);
        java.util.Objects.requireNonNull(sourceEpoch);
        if (value == null || value.signum() < 0 || value.bitLength() > 64) {
            throw new IllegalArgumentException("Counter must be an unsigned 64-bit integer");
        }
    }
}
