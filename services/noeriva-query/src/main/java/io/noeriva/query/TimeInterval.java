package io.noeriva.query;
import java.time.Instant;
public record TimeInterval(Instant from, Instant to, long durationSeconds) {}
