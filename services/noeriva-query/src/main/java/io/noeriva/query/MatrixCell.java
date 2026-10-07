package io.noeriva.query;

import java.time.Instant;
import java.util.List;

/** One distinct interval in a chronological square matrix; missing values are never zero. */
public record MatrixCell(int index, List<TimeInterval> intervals, String state, Double value,
        double coverage, Instant asOf, String sourceFreshness, long dataRevision,
        boolean provisional, List<String> qualityFlags, Long sampleCount) {}
