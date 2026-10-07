package io.noeriva.query;
import java.time.*;
import java.util.List;
public record HeatmapResponse(String deviceId, String interfaceId, String timezone,
        String direction, String statistic, String unit, LocalDate fromDate, LocalDate toDate,
        Instant asOf, String source, String sourceFreshness, long dataRevision, double coverage,
        int resolution, boolean provisional, List<String> qualityFlags, List<HeatmapCell> cells,
        HeatmapMatrix matrix) {
    public HeatmapResponse(String deviceId, String interfaceId, String timezone, String direction,
            String statistic, String unit, LocalDate fromDate, LocalDate toDate, Instant asOf,
            String source, String sourceFreshness, long dataRevision, double coverage, int resolution,
            boolean provisional, List<String> qualityFlags, List<HeatmapCell> cells) {
        this(deviceId,interfaceId,timezone,direction,statistic,unit,fromDate,toDate,asOf,source,
            sourceFreshness,dataRevision,coverage,resolution,provisional,qualityFlags,cells,null);
    }

    HeatmapResponse withMatrix(HeatmapMatrix value) {
        return new HeatmapResponse(deviceId,interfaceId,timezone,direction,statistic,unit,fromDate,toDate,
            asOf,source,sourceFreshness,dataRevision,coverage,resolution,provisional,qualityFlags,cells,value);
    }
}
