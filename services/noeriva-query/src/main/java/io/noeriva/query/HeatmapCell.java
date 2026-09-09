package io.noeriva.query;
import java.time.*;
import java.util.List;
public record HeatmapCell(LocalDate date, int hour, String state, Double value, String unit,
        String aggregation, List<TimeInterval> intervals, double coverage, Instant asOf,
        String sourceFreshness, long dataRevision, boolean provisional, List<String> qualityFlags) {}
