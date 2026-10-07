import { describe, expect, it } from "vitest";
import {
  timeSeriesOption,
  applicationRankingOption,
  siteHealthOption,
} from "../src/utils/chartOptions";

describe("analytical chart data semantics", () => {
  it("keeps actual bucket values, missing gaps and all-range zoom", () => {
    const points: [string, number | null][] = [
      ["2026-09-20T00:00:00Z", 0],
      ["2026-09-20T01:00:00Z", null],
      ["2026-09-20T02:00:00Z", 42],
    ];
    const option: any = timeSeriesOption({
      dark: false,
      timezone: "UTC",
      daily: false,
      format: (v) => String(v),
      series: [{ name: "入站", data: points }],
    });
    expect(option.series[0].data).toEqual(points);
    expect(option.series[0].connectNulls).toBe(false);
    expect(option.series[0].smooth).toBe(false);
    expect(option.series[0].stack).toBeUndefined();
    expect(option.dataZoom[0]).toMatchObject({
      start: 0,
      end: 100,
      filterMode: "none",
    });
  });
  it("keeps an isolated zero bucket visible in a sparse 24-hour series", () => {
    const points: [string, number | null][] = Array.from(
      { length: 25 },
      (_, i) => [
        new Date(Date.UTC(2026, 8, 20, i)).toISOString(),
        i === 12 ? 0 : null,
      ],
    );
    const option: any = timeSeriesOption({
      dark: false,
      timezone: "UTC",
      daily: false,
      format: (v) => String(v),
      series: [{ name: "稀疏带宽", data: points }],
    });
    expect(
      option.series[0].data.filter(
        (p: [string, number | null]) => p[1] !== null,
      ),
    ).toEqual([points[12]]);
    expect(option.series[0].showSymbol).toBe(true);
    expect(option.series[0].symbolSize).toBeGreaterThan(0);
    expect(option.series[0].connectNulls).toBe(false);
  });
  it("sorts cumulative byte strings precisely and never mutates or replaces null with zero", () => {
    const rows = [
      {
        application: "lower",
        direction: "IN",
        derivedBps: 100,
        cumulativeBytes: "9007199254740992",
      },
      {
        application: "higher",
        direction: "OUT",
        derivedBps: 50,
        cumulativeBytes: "9007199254740993",
      },
      {
        application: "missing",
        direction: "IN",
        derivedBps: null,
        cumulativeBytes: null,
      },
      {
        application: "zero",
        direction: "IN",
        derivedBps: 0,
        cumulativeBytes: "0",
      },
    ] as any;
    const option: any = applicationRankingOption(rows, "bytes", false);
    expect(option.yAxis.data).toEqual([
      "higher · 出站",
      "lower · 入站",
      "zero · 入站",
      "missing · 入站",
    ]);
    expect(option.series[0].data.map((d: any) => d.value)).toEqual([
      Number("9007199254740993"),
      Number("9007199254740992"),
      0,
      null,
    ]);
    expect(rows[0].application).toBe("lower");
    expect(option.series[0].data[0].exactBytes).toBe("9007199254740993");
    expect(option.xAxis.axisLabel.formatter(1e19)).toContain("EiB");
  });
  it("stacks mutually exclusive health states without adding overlapping stale counts", () => {
    const option: any = siteHealthOption(
      [
        {
          siteName: "A",
          healthy: 10,
          warning: 2,
          critical: 1,
          unknown: 3,
          stale: 5,
        },
      ] as any,
      false,
    );
    expect(option.series.map((s: any) => s.name)).toEqual([
      "健康",
      "警告",
      "严重",
      "未知",
    ]);
    expect(option.series.reduce((n: number, s: any) => n + s.data[0], 0)).toBe(
      16,
    );
    expect(option.series.every((s: any) => s.stack === "health")).toBe(true);
  });
});
