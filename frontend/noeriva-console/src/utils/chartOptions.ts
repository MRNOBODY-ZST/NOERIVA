import type { EChartsCoreOption } from "echarts/core";
import type { ApplicationSummary } from "../services/traffic";
import type { WorkspaceOverview } from "../services/workspace";
import { formatBytes, formatRate } from "./format";

function byteAxisLabel(value: number) {
  if (!Number.isFinite(value) || value < 0) return "—";
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
  const index = value
    ? Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)))
    : 0;
  return `${Number((value / 1024 ** index).toFixed(2))} ${units[index]}`;
}

export function chartColors(dark: boolean) {
  return {
    text: dark ? "#b8c8da" : "#526478",
    grid: dark ? "#2a3b4d" : "#e7edf4",
    blue: dark ? "#78b6ff" : "#357ac2",
    red: dark ? "#f28e8e" : "#ce5865",
  };
}
export function timeSeriesOption(options: {
  dark: boolean;
  timezone: string;
  daily: boolean;
  format: (value: number | null) => string;
  series: { name: string; data: [string, number | null][] }[];
  unit?: string;
}): EChartsCoreOption {
  const c = chartColors(options.dark);
  const timestamp = new Intl.DateTimeFormat("zh-CN", {
    timeZone: options.timezone,
    ...(options.daily
      ? { month: "2-digit" as const, day: "2-digit" as const }
      : { hour: "2-digit" as const, minute: "2-digit" as const }),
    hourCycle: "h23",
  });
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 14, right: 22, top: 44, bottom: 60, containLabel: true },
    tooltip: {
      trigger: "axis",
      renderMode: "richText",
      confine: true,
      axisPointer: { type: "line" },
      valueFormatter: (v: unknown) =>
        options.format(typeof v === "number" ? v : null),
    },
    legend: {
      top: 3,
      icon: "roundRect",
      itemWidth: 16,
      itemHeight: 5,
      textStyle: { color: c.text },
    },
    xAxis: {
      type: "time",
      splitNumber: 4,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: c.grid } },
      axisLabel: {
        color: c.text,
        hideOverlap: true,
        formatter: (v: number) => timestamp.format(v),
      },
      axisPointer: {
        label: {
          formatter: (p: { value: number }) =>
            new Intl.DateTimeFormat("zh-CN", {
              timeZone: options.timezone,
              month: "2-digit",
              day: "2-digit",
              hour: "2-digit",
              minute: "2-digit",
              hourCycle: "h23",
            }).format(p.value),
        },
      },
    },
    yAxis: {
      type: "value",
      name: options.unit,
      nameTextStyle: { color: c.text },
      axisLabel: { color: c.text, formatter: (v: number) => options.format(v) },
      splitLine: { lineStyle: { type: "dashed", color: c.grid } },
    },
    dataZoom: [
      {
        type: "slider",
        start: 0,
        end: 100,
        filterMode: "none",
        height: 20,
        bottom: 8,
        borderColor: c.grid,
        showDetail: false,
        brushSelect: false,
        handleSize: "85%",
        textStyle: { color: c.text },
        fillerColor: options.dark
          ? "rgba(120,182,255,.14)"
          : "rgba(53,122,194,.10)",
      },
      {
        type: "inside",
        filterMode: "none",
        zoomOnMouseWheel: false,
        moveOnMouseMove: false,
        moveOnMouseWheel: false,
      },
    ],
    series: options.series.map((series, i) => ({
      name: series.name,
      type: "line",
      data: series.data,
      connectNulls: false,
      smooth: false,
      showSymbol: true,
      symbol: "circle",
      symbolSize: series.data.length <= 24 ? 5 : 3,
      lineStyle: { width: 2.4 },
      itemStyle: { color: i ? c.red : c.blue },
      areaStyle: {
        color: {
          type: "linear",
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            {
              offset: 0,
              color: i
                ? options.dark
                  ? "rgba(242,142,142,.23)"
                  : "rgba(206,88,101,.19)"
                : options.dark
                  ? "rgba(120,182,255,.27)"
                  : "rgba(53,122,194,.22)",
            },
            { offset: 1, color: "rgba(255,255,255,0)" },
          ],
        },
      },
      emphasis: { focus: "series" },
    })),
  };
}
export function applicationRankingOption(
  items: ApplicationSummary["items"],
  mode: "bandwidth" | "bytes",
  dark: boolean,
): EChartsCoreOption {
  const c = chartColors(dark),
    bytes = mode === "bytes";
  const rows = [...items].sort((a, b) => {
    if (bytes) {
      if (a.cumulativeBytes == null) return b.cumulativeBytes == null ? 0 : 1;
      if (b.cumulativeBytes == null) return -1;
      const av = BigInt(a.cumulativeBytes),
        bv = BigInt(b.cumulativeBytes);
      return av === bv ? 0 : av > bv ? -1 : 1;
    }
    return (b.derivedBps ?? -Infinity) - (a.derivedBps ?? -Infinity);
  });
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 12, right: 28, top: 24, bottom: 28, containLabel: true },
    tooltip: {
      trigger: "item",
      renderMode: "richText",
      confine: true,
      formatter: (p: any) =>
        `${p.name}\n${bytes ? "累计流量" : "平均带宽"}  ${bytes ? formatBytes(p.data.exactBytes) : formatRate(p.value)}${bytes && p.data.exactBytes != null ? `\n${p.data.exactBytes} bytes` : ""}`,
    },
    xAxis: {
      type: "value",
      splitNumber: 3,
      axisLabel: {
        hideOverlap: true,
        color: c.text,
        formatter: (v: number) => (bytes ? byteAxisLabel(v) : formatRate(v)),
      },
      splitLine: { lineStyle: { type: "dashed", color: c.grid } },
    },
    yAxis: {
      type: "category",
      inverse: true,
      data: rows.map(
        (r) => `${r.application} · ${r.direction === "IN" ? "入站" : "出站"}`,
      ),
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: c.text, width: 115, overflow: "truncate" },
    },
    series: [
      {
        name: bytes ? "累计流量" : "区间平均带宽",
        type: "bar",
        barMaxWidth: 18,
        showBackground: true,
        backgroundStyle: {
          color: dark ? "#1e2d3e" : "#f3f6fa",
          borderRadius: 4,
        },
        data: rows.map((r) => ({
          value: bytes
            ? r.cumulativeBytes == null
              ? null
              : Number(r.cumulativeBytes)
            : r.derivedBps,
          exactBytes: r.cumulativeBytes,
          itemStyle: {
            color: r.direction === "IN" ? c.blue : c.red,
            borderRadius: [0, 4, 4, 0],
          },
        })),
      },
    ],
  };
}
export function siteHealthOption(
  sites: WorkspaceOverview["siteHealth"],
  dark: boolean,
): EChartsCoreOption {
  const c = chartColors(dark);
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 12, right: 25, top: 44, bottom: 24, containLabel: true },
    legend: {
      top: 3,
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: c.text },
    },
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      renderMode: "richText",
      confine: true,
      formatter: (params: any) => {
        const row = sites[params[0]?.dataIndex];
        return row
          ? `${row.siteName}\n${params.map((p: any) => `${p.seriesName}  ${p.value} 台`).join("\n")}\n数据过期  ${row.stale} 台（独立统计）`
          : "";
      },
    },
    xAxis: {
      type: "value",
      minInterval: 1,
      axisLabel: { color: c.text },
      splitLine: { lineStyle: { color: c.grid, type: "dashed" } },
    },
    yAxis: {
      type: "category",
      inverse: true,
      data: sites.map((s) => s.siteName),
      axisLabel: { color: c.text, width: 95, overflow: "truncate" },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    series: (
      [
        ["healthy", "健康", c.blue],
        ["warning", "警告", "#dfa14f"],
        ["critical", "严重", c.red],
        ["unknown", "未知", dark ? "#697e96" : "#b1bfd0"],
      ] as const
    ).map(([key, name, color]) => ({
      name,
      type: "bar",
      stack: "health",
      barMaxWidth: 26,
      emphasis: { focus: "series" },
      itemStyle: { color },
      data: sites.map((s) => s[key]),
    })),
  };
}
