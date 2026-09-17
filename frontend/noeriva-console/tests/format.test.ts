import { describe, expect, it } from "vitest";
import {
  formatRate,
  formatTime,
  formatLatency,
  formatBytes,
  formatCount,
  stateLabel,
} from "../src/utils/format";
describe("operational data semantics", () => {
  it("formats byte and packet counters without rounding Counter64 through Number", () => {
    expect(formatBytes("4326510598")).toBe("4.03 GiB");
    expect(formatBytes("21918098559")).toBe("20.41 GiB");
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(1024)).toBe("1 KiB");
    expect(formatBytes(null)).toBe("—");
    expect(formatBytes("bad")).toBe("—");
    expect(formatCount("18446744073709551610")).toBe(
      "18,446,744,073,709,551,610",
    );
    expect(formatCount(null)).toBe("—");
  });
  it("rounds check latency for display and scales milliseconds without changing the raw value", () => {
    expect(formatLatency(7.893618)).toBe("7.89 ms");
    expect(formatLatency(10736.261)).toBe("10.74 s");
    expect(formatLatency(0)).toBe("0 ms");
    expect(formatLatency(null)).toBe("—");
  });
  it("distinguishes an observed zero from missing telemetry", () => {
    expect(formatRate(0)).toBe("0 bps");
    expect(formatRate(null)).toBe("—");
    expect(formatRate(undefined)).toBe("—");
    expect(formatRate(Number.NaN)).toBe("—");
  });
  it("uses decimal network rates and preserves unit meaning", () => {
    expect(formatRate(1_500_000_000)).toBe("1.50 Gbps");
    expect(formatRate(123_000)).toBe("123 Kbps");
  });
  it("never turns unknown states or absent timestamps into healthy current data", () => {
    expect(stateLabel("UNKNOWN")).toBe("未知");
    expect(stateLabel("new-state")).toBe("new-state");
    expect(formatTime(null)).toBe("尚无采集记录");
    expect(formatTime("not-a-date")).toBe("时间不可用");
  });
});
