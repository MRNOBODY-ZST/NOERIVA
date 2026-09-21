package io.noeriva.query;

/** Sample mean covers every stored observation; volume is an explicitly estimated gauge integral. */
public record MetricWindowSummary(Double mean, long sampleCount, String estimatedBytes,
        double observedSeconds, double coverage, String statistic) {}
