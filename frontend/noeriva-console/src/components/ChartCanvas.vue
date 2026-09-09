<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  init,
  use,
  type EChartsCoreOption,
  type EChartsType,
} from "echarts/core";
import { LineChart, HeatmapChart, GraphChart, BarChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  VisualMapComponent,
  DataZoomComponent,
  AriaComponent,
} from "echarts/components";
import { SVGRenderer } from "echarts/renderers";
import { LabelLayout } from "echarts/features";
use([
  LineChart,
  BarChart,
  HeatmapChart,
  GraphChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  VisualMapComponent,
  DataZoomComponent,
  AriaComponent,
  SVGRenderer,
  LabelLayout,
]);
const props = defineProps<{
  option: EChartsCoreOption;
  label: string;
  height?: string;
  square?: boolean;
  preserveLayout?: boolean;
  graphNodeColors?: string[];
  autoFitGraph?: boolean;
}>();
const emit = defineEmits<{
  select: [payload: unknown];
  open: [payload: unknown];
  fitStatus: [status: string];
}>();
const target = ref<HTMLDivElement>();
let chart: EChartsType | undefined;
let observer: ResizeObserver | undefined;
let initialFitDone = false;
const initialFitState = ref("pending");
const fitState = ref("idle");
let fitTimer: ReturnType<typeof setTimeout> | undefined;
let fitRevision = 0;
function setFitState(state: string) {
  fitState.value = state;
  emit("fitStatus", state);
}
function userInteraction() {
  initialFitDone = true;
  initialFitState.value = "cancelled";
  clearTimeout(fitTimer);
  fitRevision++;
  setFitState("cancelled");
}
function graphSeries() {
  const series = chart?.getOption().series as
    | {
        type?: string;
        zoom?: number;
        scaleLimit?: { min?: number; max?: number };
      }[]
    | undefined;
  const index = series?.findIndex((item) => item.type === "graph") ?? -1;
  return index >= 0 ? { index, ...series![index] } : null;
}
// Measure the public SVG geometry that the operator sees, including symbol size at the current zoom.
// This avoids private ECharts model/layout fields and also works for force, circular and fixed layouts.
function nodeBounds() {
  const svg = target.value?.querySelector("svg");
  if (!svg || !chart || !props.graphNodeColors?.length) return null;
  const viewport = svg.getBoundingClientRect();
  if (!viewport.width || !viewport.height) return null;
  const colors = new Set(props.graphNodeColors);
  const shapes = [
    ...svg.querySelectorAll<SVGGraphicsElement>(
      "path,circle,rect,polygon,ellipse",
    ),
  ].filter((shape) =>
    colors.has(shape.getAttribute("fill") || shape.style.fill),
  );
  let left = Infinity,
    top = Infinity,
    right = -Infinity,
    bottom = -Infinity;
  for (const shape of shapes) {
    const box = shape.getBoundingClientRect();
    if (!box.width || !box.height) continue;
    left = Math.min(left, box.left);
    top = Math.min(top, box.top);
    right = Math.max(right, box.right);
    bottom = Math.max(bottom, box.bottom);
  }
  if (!Number.isFinite(left)) return null;
  const xScale = chart.getWidth() / viewport.width,
    yScale = chart.getHeight() / viewport.height;
  return {
    left: (left - viewport.left) * xScale,
    top: (top - viewport.top) * yScale,
    right: (right - viewport.left) * xScale,
    bottom: (bottom - viewport.top) * yScale,
  };
}
function graphZoom(factor: number) {
  const series = graphSeries();
  if (!chart || !series) return;
  const current = series.zoom || 1;
  const next = Math.max(
    series.scaleLimit?.min ?? 0.05,
    Math.min(series.scaleLimit?.max ?? 3, current * factor),
  );
  // graphRoam updates the public view transform without restarting the force simulation (ECharts 6.1 roamHelper).
  chart.dispatchAction({
    type: "graphRoam",
    seriesIndex: series.index,
    zoom: next / current,
    originX: chart.getWidth() / 2,
    originY: chart.getHeight() / 2,
  });
}
async function fitGraph() {
  const revision = ++fitRevision;
  setFitState("fitting");
  for (let step = 0; step < 6; step++) {
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => resolve()),
    );
    if (!chart || revision !== fitRevision) return;
    chart.getZr().flush();
    const bounds = nodeBounds(),
      series = graphSeries();
    if (!bounds || !series) {
      setFitState("unavailable");
      return;
    }
    const width = chart.getWidth(),
      height = chart.getHeight();
    const padding = Math.min(48, width / 8, height / 8);
    const dx = width / 2 - (bounds.left + bounds.right) / 2;
    const dy = height / 2 - (bounds.top + bounds.bottom) / 2;
    const ratio = Math.min(
      (width - padding * 2) / (bounds.right - bounds.left),
      (height - padding * 2) / (bounds.bottom - bounds.top),
    );
    if (step > 0 && ratio >= 1 && Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) {
      setFitState("done");
      return;
    }
    chart.dispatchAction({
      type: "graphRoam",
      seriesIndex: series.index,
      dx,
      dy,
    });
    // Symbols scale more slowly than their positions (nodeScaleRatio), so remeasure and only shrink on correction passes.
    const scale = step ? Math.min(1, ratio) : ratio;
    graphZoom(scale < 1 ? scale * 0.97 : scale);
  }
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  if (!chart || revision !== fitRevision) return;
  chart.getZr().flush();
  const bounds = nodeBounds();
  const width = chart.getWidth(),
    height = chart.getHeight();
  const padding = Math.min(48, width / 8, height / 8);
  setFitState(
    bounds &&
      bounds.left >= padding &&
      bounds.top >= padding &&
      bounds.right <= width - padding &&
      bounds.bottom <= height - padding
      ? "done"
      : "limited",
  );
}
function fit() {
  userInteraction();
  return fitGraph();
}
function zoom(factor: number) {
  userInteraction();
  graphZoom(factor);
}
function scheduleInitialFit() {
  if (!props.autoFitGraph || initialFitDone) return;
  clearTimeout(fitTimer);
  // Force layout emits rendered frames asynchronously; wait for a quiet layout instead of its first "finished" event.
  fitTimer = setTimeout(() => {
    initialFitDone = true;
    initialFitState.value = "fitting";
    void fitGraph().then(() => {
      if (initialFitState.value === "fitting")
        initialFitState.value = fitState.value;
    });
  }, 300);
}
defineExpose({ fit, zoom });
onMounted(() => {
  if (!target.value) return;
  chart = init(target.value, undefined, { renderer: "svg" });
  chart.on("rendered", scheduleInitialFit);
  chart.setOption(props.option);
  chart.on("click", (payload) => emit("select", payload));
  chart.on("dblclick", (payload) => emit("open", payload));
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(target.value);
});
watch(
  () => props.option,
  (option) =>
    chart?.setOption(option, {
      notMerge: !props.preserveLayout,
      lazyUpdate: true,
    }),
);
onBeforeUnmount(() => {
  clearTimeout(fitTimer);
  fitRevision++;
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template>
  <div
    ref="target"
    class="chart-canvas"
    :class="{ square }"
    :style="height ? { height } : undefined"
    role="img"
    :aria-label="label"
    :data-initial-graph-fit="autoFitGraph ? initialFitState : undefined"
    :data-graph-fit="graphNodeColors ? fitState : undefined"
    @pointerdown="userInteraction"
    @wheel.passive="userInteraction"
  ></div>
</template>
