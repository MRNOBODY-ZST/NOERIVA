import { mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { describe, expect, it } from "vitest";
import BandwidthHeatmap from "../src/components/BandwidthHeatmap.vue";
import type { Heatmap } from "../src/services/types";
const quality = {
  asOf: null,
  sourceFreshness: "UNKNOWN",
  dataRevision: 1,
  provisional: false,
  qualityFlags: [],
  coverage: 1,
};
function fixture(): Heatmap {
  return {
    ...quality,
    resolution: 300,
    deviceId: "router",
    interfaceId: "if1",
    timezone: "UTC",
    direction: "rx",
    statistic: "time_weighted_mean",
    unit: "bps",
    fromDate: "2026-08-31",
    toDate: "2026-09-06",
    source: "TEST",
    cells: Array.from({ length: 168 }, (_, i) => ({
      ...quality,
      date: new Date(Date.UTC(2026, 7, 31 + Math.floor(i / 24)))
        .toISOString()
        .slice(0, 10),
      hour: i % 24,
      state:
        i === 0
          ? "OBSERVED_ZERO"
          : i === 1
            ? "MISSING"
            : i === 2
              ? "FUTURE"
              : i === 3
                ? "PARTIAL"
                : "OBSERVED",
      value: [1, 2].includes(i) ? null : i * 100,
      unit: "bps",
      aggregation: "time_weighted_mean",
      intervals: [],
    })),
  };
}
describe("square day-grouped bandwidth heatmap", () => {
  it("renders seven days and all 168 individually accessible hourly cells, distinguishing zero/missing/future", async () => {
    const wrapper = mount(BandwidthHeatmap, {
      props: { data: fixture() },
      global: { plugins: [createPinia()] },
    });
    expect(wrapper.findAll(".heatmap-day")).toHaveLength(7);
    expect(wrapper.findAll("button.heatmap-hour")).toHaveLength(168);
    const zero = wrapper.get('[data-cell="2026-08-31-0"]');
    const missing = wrapper.get('[data-cell="2026-08-31-1"]');
    const future = wrapper.get('[data-cell="2026-08-31-2"]');
    expect(zero.attributes("style")).not.toEqual(missing.attributes("style"));
    expect(future.attributes("style")).not.toEqual(missing.attributes("style"));
    expect(zero.attributes("aria-label")).toContain("00:00");
    await wrapper.get('[data-cell="2026-08-31-3"]').trigger("click");
    expect(wrapper.get(".chart-inspector").text()).toContain("03:00");
    expect(wrapper.get(".chart-inspector").text()).toContain("300");
    wrapper.unmount();
  });
  it("uses the same blue-to-red scale for every day and keeps observed zero visible", () => {
    const data = fixture();
    data.cells[24]!.value = 0;
    const wrapper = mount(BandwidthHeatmap, {
      props: { data },
      global: { plugins: [createPinia()] },
    });
    expect(
      wrapper.get('[data-cell="2026-08-31-0"]').attributes("style"),
    ).toContain("33, 102, 172");
    expect(
      wrapper.get('[data-cell="2026-09-01-0"]').attributes("style"),
    ).toContain("33, 102, 172");
    expect(
      wrapper.get('[data-cell="2026-09-06-23"]').attributes("style"),
    ).toContain("178, 24, 43");
    wrapper.unmount();
  });
});
