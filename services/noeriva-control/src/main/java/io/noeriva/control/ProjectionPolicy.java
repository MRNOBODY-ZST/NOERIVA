package io.noeriva.control;

import java.time.Instant;
import java.util.Set;
import static io.noeriva.control.Models.*;

public final class ProjectionPolicy {
    private static final Set<String> METRICS = Set.of("cpu_percent", "memory_percent", "temperature_celsius", "power_watts", "bandwidth_rx_bps", "bandwidth_tx_bps");
    private ProjectionPolicy() {}
    public static boolean accepts(Observation current, Observation next, Instant now) {
        if(!Set.of("primary","host","bmc","network","system","ssh").contains(next.sourceId()))throw new IllegalArgumentException("Unsupported source identity");
        if(next.sequence()<0||next.sequence()==Long.MAX_VALUE)throw new IllegalArgumentException("Sequence exceeds supported revision range");
        if(next.observedAt().isBefore(Instant.parse("2000-01-01T00:00:00Z")))throw new IllegalArgumentException("Observation predates the supported event window");
        if (next.observedAt().isAfter(now.plusSeconds(120))) throw new IllegalArgumentException("Observation is more than 120 seconds in the future");
        if (next.metrics().entrySet().stream().anyMatch(e -> !METRICS.contains(e.getKey()) || e.getValue()==null || !Double.isFinite(e.getValue())))
            throw new IllegalArgumentException("Unknown metric or non-finite value");
        if (current == null) return true;
        if (current.epoch().equals(next.epoch())) return next.sequence() > current.sequence() && !next.observedAt().isBefore(current.observedAt());
        // No ordering exists between source boot UUIDs; a new epoch requires strictly newer source time.
        return next.observedAt().isAfter(current.observedAt());
    }
    public static String freshness(Instant observedAt, Instant now) {
        if (observedAt == null) return "MISSING";
        return observedAt.isBefore(now.minusSeconds(180)) ? "STALE" : "FRESH";
    }
    public static SourceState source(Observation event, Instant now) {
        return new SourceState(event.sourceId(),event.kind(),event.observedAt(),event.sequence(),event.epoch(),event.health(),event.metrics(),freshness(event.observedAt(),now));
    }
    public static String aggregateHealth(java.util.Collection<SourceState> sources) {
        var order=java.util.Map.of("UNKNOWN",0,"HEALTHY",1,"WARNING",2,"CRITICAL",3);
        return sources.stream().filter(s->"FRESH".equals(s.freshness())).map(SourceState::health).max(java.util.Comparator.comparingInt(h->order.getOrDefault(h,0))).orElse("UNKNOWN");
    }
}
