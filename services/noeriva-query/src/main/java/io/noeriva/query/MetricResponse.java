package io.noeriva.query;
import java.time.Instant;
import java.util.List;
public record MetricResponse(String deviceId, String metric, String unit, Instant from, Instant to,
        Instant asOf, String source, String sourceFreshness, int resolution, long dataRevision,
        boolean provisional, double coverage, List<String> qualityFlags, List<MetricPoint> points) {}
