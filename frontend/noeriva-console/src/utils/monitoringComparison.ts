import type { EChartsCoreOption } from "echarts/core";
import { metricNames, type MonitoringSource } from "../services/workbench";
import { chartColors } from "./chartOptions";
import { formatMetric } from "./format";

export interface MonitoringReading {
  key: string;
  label: string;
  source: MonitoringSource;
  value: number | null;
  missing: string | null;
}

export function monitoringSourceKey(source: MonitoringSource): string {
  return JSON.stringify([source.deviceId, source.sourceId]);
}

export function monitoringMetrics(
  sources: readonly MonitoringSource[],
): string[] {
  return Object.keys(metricNames).filter((key) =>
    sources.some((source) => Object.hasOwn(source.metrics, key)),
  );
}

export function monitoringReadings(
  sources: readonly MonitoringSource[],
  metric: string,
): MonitoringReading[] {
  return sources
    .map((source) => {
      const provided = Object.hasOwn(source.metrics, metric);
      const observed = source.metrics[metric];
      const value =
        typeof observed === "number" && Number.isFinite(observed)
          ? observed
          : null;
      return {
        key: monitoringSourceKey(source),
        label: `${source.deviceName} [${source.deviceId}] · ${source.kind}/${source.sourceId}`,
        source,
        value,
        missing:
          value !== null ? null : provided ? "暂无有效读数" : "未提供该指标",
      };
    })
    .sort((a, b) => {
      if (a.value === null) return b.value === null ? 0 : 1;
      if (b.value === null) return -1;
      return b.value - a.value;
    });
}

export function monitoringMetricValue(
  value: number | null,
  metric: string,
): string {
  return formatMetric(value, metricNames[metric]?.unit).replace(
    /(\.\d*?[1-9])0+(?=\s)|\.0+(?=\s)/g,
    "$1",
  );
}

export function monitoringComparisonOption(
  readings: readonly MonitoringReading[],
  metric: string,
  dark: boolean,
  selectedKey?: string,
): EChartsCoreOption {
  const colors = chartColors(dark);
  const observed = readings.filter((reading) => reading.value !== null);
  const format = (value: number | null) => monitoringMetricValue(value, metric);
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 12, right: 85, top: 20, bottom: 36, containLabel: true },
    tooltip: {
      trigger: "item",
      renderMode: "richText",
      confine: true,
      formatter: (params: { data: { sourceKey: string }; value: number }) => {
        const reading = observed.find(
          (row) => row.key === params.data.sourceKey,
        );
        return reading
          ? `${reading.label}\n${metricNames[metric]?.name || metric}  ${format(params.value)}\n${reading.source.freshness === "STALE" ? "数据过期 · 最后读数" : "当前读数"}\n${reading.source.observedAt || "尚无采集记录"}\n点击查看历史趋势`
          : "";
      },
    },
    xAxis: {
      type: "value",
      splitNumber: 3,
      axisLabel: { color: colors.text, hideOverlap: true, formatter: format },
      splitLine: { lineStyle: { type: "dashed", color: colors.grid } },
    },
    yAxis: {
      type: "category",
      inverse: true,
      data: observed.map(
        (reading) =>
          `${reading.label}${reading.source.freshness === "STALE" ? " · 过期" : ""}`,
      ),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: colors.text, width: 115, overflow: "truncate" },
    },
    series: [
      {
        name: metricNames[metric]?.name || metric,
        type: "bar",
        barMaxWidth: 20,
        showBackground: true,
        backgroundStyle: {
          color: dark ? "#1e2d3e" : "#f3f6fa",
          borderRadius: 4,
        },
        label: {
          show: true,
          position: "right",
          color: colors.text,
          formatter: (params: { value: number }) => format(params.value),
        },
        data: observed.map((reading) => ({
          value: reading.value,
          sourceKey: reading.key,
          itemStyle: {
            color: colors.blue,
            opacity: reading.source.freshness === "STALE" ? 0.45 : 1,
            borderRadius: reading.value! < 0 ? [4, 0, 0, 4] : [0, 4, 4, 0],
            borderColor: dark ? "#d2e6ff" : "#174e88",
            borderWidth: reading.key === selectedKey ? 2 : 0,
          },
        })),
      },
    ],
  };
}
