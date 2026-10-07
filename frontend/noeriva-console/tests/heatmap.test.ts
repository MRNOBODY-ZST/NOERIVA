import { mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { describe, expect, it } from "vitest";
import BandwidthHeatmap from "../src/components/BandwidthHeatmap.vue";
import { heatmapMatrix, hasHeatValue } from "../src/utils/heatmapMatrix";
import type { Heatmap } from "../src/services/types";
const quality = {
  asOf: null,
  sourceFreshness: "UNKNOWN",
  dataRevision: 1,
  provisional: false,
  qualityFlags: [],
  coverage: 1,
};
function fixture(size: 7 | 28 = 7): Heatmap {
  return {
    ...quality,
    resolution: 300,
    deviceId: "router",
    interfaceId: "if1",
    timezone: "UTC",
    direction: "rx",
    statistic: "sample_mean",
    unit: "bps",
    fromDate: "2026-09-01",
    toDate: "2026-09-07",
    source: "TEST",
    cells: [],
    matrix: {
      size,
      cells: Array.from({ length: size * size }, (_, index) => ({
        ...quality,
        index,
        value: index === 1 || index === 2 ? null : index * 100,
        state:
          index === 0
            ? "OBSERVED_ZERO"
            : index === 1
              ? "MISSING"
              : index === 2
                ? "FUTURE"
                : index === 3
                  ? "PARTIAL"
                  : "OBSERVED",
        sampleCount: index === 1 || index === 2 ? 0 : 2,
        intervals: [
          {
            from: new Date(Date.UTC(2026, 8, 1) + index * 900000).toISOString(),
            to: new Date(
              Date.UTC(2026, 8, 1) + (index + 1) * 900000,
            ).toISOString(),
            durationSeconds: 900,
          },
        ],
      })),
    },
  };
}
const render = (data: Heatmap) =>
  mount(BandwidthHeatmap, {
    props: { data },
    global: {
      plugins: [createPinia()],
      stubs: {
        ChartCanvas: {
          name: "ChartCanvas",
          props: ["option", "label"],
          emits: ["select"],
          template: '<div class="test-chart"/>',
        },
      },
    },
  });
describe("single square bandwidth matrix", () => {
  it.each([7, 28] as const)(
    "maps all %i-square real time bins to one matrix without copying values or splitting day panels",
    (size) => {
      const data = fixture(size),
        cells = data.matrix!.cells;
      const matrix = heatmapMatrix([...cells].reverse(), size);
      expect(matrix.points).toHaveLength(size * size);
      expect(new Set(matrix.points.map((p) => `${p.x}/${p.y}`)).size).toBe(
        size * size,
      );
      expect(matrix.points[0]?.cell).toBe(cells[0]);
      expect(matrix.points.at(-1)).toMatchObject({
        x: size - 1,
        y: size - 1,
        cell: cells.at(-1),
      });
      const w = render(data);
      expect(w.findAll(".heatmap-square")).toHaveLength(1);
      expect(w.findAll(".heatmap-day")).toHaveLength(0);
      w.unmount();
    },
  );
  it("keeps zero in the measured scale and separates missing/future bins", () => {
    const data = fixture(),
      w = render(data),
      option: any = w.findComponent({ name: "ChartCanvas" }).props("option");
    expect(hasHeatValue(data.matrix!.cells[0]!)).toBe(true);
    expect(hasHeatValue(data.matrix!.cells[1]!)).toBe(false);
    expect(option.series[0].data).toHaveLength(47);
    expect(option.series[0].data[0].value[2]).toBe(0);
    expect(option.series[1].data[0].itemStyle.color).not.toBe(
      option.series[1].data[1].itemStyle.color,
    );
    expect(option.visualMap.seriesIndex).toBe(0);
    expect(option.visualMap.min).toBe(0);
    const positive = data.matrix!.cells.filter(
      (c) => c.value !== null && c.value > 0,
    );
    expect(heatmapMatrix(positive, 7).minimum).toBe(300);
    w.unmount();
  });
  it("opens true interval and quality details from chart selection and keyboard-accessible row/column controls", async () => {
    const data = fixture(),
      w = render(data);
    w.findComponent({ name: "ChartCanvas" }).vm.$emit("select", {
      data: { cell: data.matrix!.cells[3] },
    });
    await w.vm.$nextTick();
    expect(w.get(".chart-inspector").text()).toContain("00:45");
    await w.get('[aria-label="热力图行"]').setValue("2");
    await w.get('[aria-label="热力图列"]').setValue("1");
    expect(w.get(".chart-inspector").text()).toContain("1.5 Kbps");
    expect(w.get(".chart-inspector").text()).toContain("100%");
    await w.get('[aria-label="热力图网格"]').setValue("28");
    expect(w.emitted("size")?.[0]).toEqual([28]);
    w.unmount();
  });
  it("resets selection and controls when grid size changes", async () => {
    const data = fixture(),
      w = render(data);
    w.findComponent({ name: "ChartCanvas" }).vm.$emit("select", {
      data: { cell: data.matrix!.cells[40] },
    });
    await w.vm.$nextTick();
    expect(
      (w.get('[aria-label="热力图行"]').element as HTMLSelectElement).value,
    ).toBe("5");
    await w.setProps({ data: fixture(28), gridSize: 28 });
    expect(w.get(".chart-inspector").text()).toContain("选择一个格子");
    expect(
      (w.get('[aria-label="热力图行"]').element as HTMLSelectElement).value,
    ).toBe("0");
    expect(
      (w.get('[aria-label="热力图列"]').element as HTMLSelectElement).value,
    ).toBe("0");
    w.unmount();
  });
});
