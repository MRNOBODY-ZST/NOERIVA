package io.noeriva.query;
import java.time.*;
import java.util.List;
public record HeatmapResponse(String deviceId, String interfaceId, String timezone,
        String direction, String statistic, String unit, LocalDate fromDate, LocalDate toDate,
        Instant asOf, String source, String sourceFreshness, long dataRevision, double coverage,
        int resolution, boolean provisional, List<String> qualityFlags, List<HeatmapCell> cells) {}
