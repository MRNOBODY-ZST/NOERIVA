import { describe, expect, it } from "vitest";
import type { MonitoringSource } from "../src/services/workbench";
import {
  monitoringComparisonOption,
  monitoringMetrics,
  monitoringReadings,
  monitoringSourceKey,
} from "../src/utils/monitoringComparison";

export function source(
  deviceId: string,
  metrics: MonitoringSource["metrics"],
  extra: Partial<MonitoringSource> = {},
): MonitoringSource {
  return {
    deviceId,
    deviceName: "Router",
    deviceType: "ROUTER",
    siteId: "lab",
    siteName: "Lab",
    sourceId: "snmp",
    kind: "SNMP",
    health: "HEALTHY",
    observedAt: "2026-09-21T10:00:00Z",
    freshness: "FRESH",
    sequence: 1,
    epoch: "fixture",
    metrics,
    ...extra,
  };
}

describe("monitoring current-page comparison", () => {
  it("offers only supported metric keys observed in the supplied page, including missing observations", () => {
    expect(monitoringMetrics([
      source("a", { temperature_celsius: null, private_counter: 4 }),
      source("b", { cpu_percent: 0, bandwidth_rx_bps: 1_000_000 }),
    ])).toEqual(["cpu_percent", "temperature_celsius", "bandwidth_rx_bps"]);
    expect(monitoringMetrics([])).toEqual([]);
    expect(monitoringMetrics([source("a", { private_counter: 4 })])).toEqual([]);
  });

  it("keeps zero, negatives and per-source identity without adding unrelated metrics or turning missing data into zero", () => {
    const sources = [
      source("a", { temperature_celsius: -12, cpu_percent: 90 }),
      source("b", { temperature_celsius: 0 }),
      source("c", { temperature_celsius: null }),
      source("d", { cpu_percent: 50 }),
      source("e", { temperature_celsius: Infinity }),
    ];
    const readings = monitoringReadings(sources, "temperature_celsius");
    expect(readings.map((r) => r.value)).toEqual([0, -12, null, null, null]);
    expect(new Set(readings.map((r) => r.label)).size).toBe(5);
    expect(readings.find((r) => r.source.deviceId === "d")?.missing).toBe("未提供该指标");
    expect(readings.find((r) => r.source.deviceId === "c")?.missing).toBe("暂无有效读数");
    expect(monitoringSourceKey(source("a/b", {}, { sourceId: "c" }))).not.toBe(
      monitoringSourceKey(source("a", {}, { sourceId: "b/c" })),
    );
    expect(sources[0]?.metrics.temperature_celsius).toBe(-12);
  });

  it("formats one metric's real units, omits null bars and exposes stable source identity for selection", () => {
    const readings = monitoringReadings([
      source("a", { bandwidth_rx_bps: 1_500_000, cpu_percent: 77 }),
      source("b", { bandwidth_rx_bps: null }),
      source("c", { bandwidth_rx_bps: 0 }, { freshness: "STALE" }),
    ], "bandwidth_rx_bps");
    const option = monitoringComparisonOption(readings, "bandwidth_rx_bps", true, readings[0]!.key) as any;
    expect(option.series[0].data.map((row: { value: number }) => row.value)).toEqual([1_500_000, 0]);
    expect(option.series[0].data[0].sourceKey).toBe(readings[0]!.key);
    expect(option.series[0].data[0].itemStyle.borderWidth).toBe(2);
    expect(option.series[0].label.formatter({ value: 1_500_000 })).toBe("1.5 Mbps");
    expect(option.yAxis.data[1]).toContain("过期");
    expect(option.xAxis.axisLabel.formatter(1_000_000)).toBe("1 Mbps");
    expect(option.series[0].data[1].itemStyle.opacity).toBeLessThan(1);
  });
});
