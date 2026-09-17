package io.noeriva.query;
import java.time.Instant;
public record MetricPoint(Instant timestamp, Double value) {}
