import { mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { init, use, setPlatformAPI } from "echarts/core";
import { HeatmapChart } from "echarts/charts";
import {
  GridComponent,
  VisualMapComponent,
  TooltipComponent,
} from "echarts/components";
import { SVGRenderer } from "echarts/renderers";
use([
  HeatmapChart,
  GridComponent,
  VisualMapComponent,
  TooltipComponent,
  SVGRenderer,
]);
import { describe, expect, it } from "vitest";
import BandwidthHeatmap from "../src/components/BandwidthHeatmap.vue";
import ChartCanvas from "../src/components/ChartCanvas.vue";
import type { Heatmap, HeatCell } from "../src/services/types";

// Deterministic text measurement only; ECharts still executes the real SVG renderer.
setPlatformAPI({ measureText: (text) => ({ width: String(text).length * 7 }) });
describe("native ECharts heatmap rendering", () => {
  it("renders observed zero and separate missing/future layers without a visualMap exception", () => {
    const quality = {
      asOf: null,
      sourceFreshness: "UNKNOWN",
      dataRevision: 1,
      provisional: false,
      qualityFlags: [],
      coverage: 0,
    };
    const cell = (
      hour: number,
      state: string,
      value: number | null,
    ): HeatCell => ({
      ...quality,
      date: "2026-09-06",
      hour,
      state,
      value,
      unit: "bps",
      aggregation: "time_weighted_mean",
      intervals: [],
    });
    const data: Heatmap = {
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
      cells: [
        cell(0, "OBSERVED_ZERO", 0),
        cell(1, "MISSING", null),
        cell(2, "FUTURE", null),
      ],
    };
    const wrapper = mount(BandwidthHeatmap, {
      props: { data },
      global: { plugins: [createPinia()], stubs: { ChartCanvas: true } },
    });
    const chart = init(null, undefined, {
      renderer: "svg",
      ssr: true,
      width: 420,
      height: 420,
    });
    try {
      expect(() =>
        chart.setOption(wrapper.findComponent(ChartCanvas).props("option")),
      ).not.toThrow();
      expect(chart.renderToSVGString()).toContain("<svg");
    } finally {
      chart.dispose();
      wrapper.unmount();
    }
  });
});
