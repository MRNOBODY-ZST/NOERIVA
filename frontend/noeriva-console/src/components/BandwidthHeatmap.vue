<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { EChartsCoreOption } from "echarts/core";
import type { Heatmap, HeatmapMatrixCell } from "../services/types";
import { formatRate, stateLabel, formatTime } from "../utils/format";
import {
  hasHeatValue,
  heatmapGeometry,
  heatmapMatrix,
  heatStateColor,
  matrixPalette,
} from "../utils/heatmapMatrix";
import { usePreferencesStore } from "../stores/preferences";
import ChartCanvas from "./ChartCanvas.vue";
const props = withDefaults(
  defineProps<{ data: Heatmap; gridSize?: 7 | 28 }>(),
  { gridSize: 7 },
);
const emit = defineEmits<{ size: [value: 7 | 28] }>();
const prefs = usePreferencesStore(),
  target = ref<HTMLDivElement>(),
  measured = ref({ width: 600, height: 600 }),
  selected = ref<HeatmapMatrixCell | null>(null),
  row = ref(0),
  column = ref(0);
const size = computed(() => props.data.matrix?.size || props.gridSize);
const matrix = computed(() =>
  heatmapMatrix(props.data.matrix?.cells || [], size.value),
);
const geometry = computed(() =>
  heatmapGeometry(measured.value.width, measured.value.height, size.value),
);
let observer: ResizeObserver | undefined;
function intervalLabel(cell: HeatmapMatrixCell) {
  return cell.intervals
    .map(
      (i) =>
        `${formatTime(i.from, props.data.timezone)} → ${formatTime(i.to, props.data.timezone)}`,
    )
    .join(" / ");
}
function selectCell(cell: HeatmapMatrixCell) {
  selected.value = cell;
  row.value = Math.floor(cell.index / size.value);
  column.value = cell.index % size.value;
}
function selectFromControls() {
  const cell = props.data.matrix?.cells.find(
    (c) => c.index === Number(row.value) * size.value + Number(column.value),
  );
  if (cell) selectCell(cell);
}
function onChartSelect(payload: unknown) {
  const cell = (payload as { data?: { cell?: HeatmapMatrixCell } })?.data?.cell;
  if (cell) selectCell(cell);
}
function cellLabel(cell: HeatmapMatrixCell) {
  return `${intervalLabel(cell)}\n${stateLabel(cell.state)} · ${formatRate(hasHeatValue(cell) ? cell.value : null)}\n覆盖 ${(cell.coverage * 100).toFixed(0)}%`;
}
const option = computed<EChartsCoreOption>(() => {
  const layout = geometry.value,
    textColor = prefs.dark ? "#c6d3e2" : "#526478";
  const item = (
    point: (typeof matrix.value.points)[number],
    available: boolean,
  ) => ({
    name: `时段 ${point.cell.index + 1}`,
    value: [String(point.x), String(point.y), available ? point.cell.value : 1],
    cell: point.cell,
    itemStyle: {
      ...(!available ? { color: heatStateColor(point.cell, prefs.dark) } : {}),
      borderColor:
        selected.value?.index === point.cell.index
          ? prefs.dark
            ? "#ffffff"
            : "#162a43"
          : point.cell.state === "PARTIAL"
            ? "#ad7415"
            : prefs.dark
              ? "#233246"
              : "#ffffff",
      borderWidth:
        selected.value?.index === point.cell.index
          ? 2.5
          : point.cell.state === "PARTIAL"
            ? 1.8
            : 0.4,
      ...(!available && point.cell.state === "FUTURE"
        ? {
            borderColor: prefs.dark ? "#627184" : "#cad2dc",
            borderType: "dashed",
          }
        : {}),
    },
  });
  const base = {
    type: "heatmap",
    coordinateSystem: "matrix",
    label: { show: false },
    emphasis: {
      itemStyle: {
        borderColor: prefs.dark ? "#ffffff" : "#162a43",
        borderWidth: 2,
      },
    },
  };
  return {
    animation: false,
    aria: {
      enabled: true,
      label: {
        description: `七日带宽 ${size.value}乘${size.value} 连续矩阵，时间从左至右、逐行向下。可使用行和列选择器检查时段。`,
      },
    },
    matrix: {
      x: { data: matrix.value.coordinates, show: false },
      y: { data: matrix.value.coordinates, show: false },
      left: layout.left,
      top: layout.top,
      width: layout.width,
      height: layout.height,
      backgroundStyle: { color: "transparent", borderWidth: 0 },
      body: {
        itemStyle: { color: "transparent", borderWidth: 0 },
        label: { show: false },
        silent: true,
      },
    },
    tooltip: {
      trigger: "item",
      renderMode: "richText",
      confine: true,
      formatter: (p: unknown) => {
        const cell = (p as { data?: { cell?: HeatmapMatrixCell } })?.data?.cell;
        return cell ? cellLabel(cell) : "";
      },
    },
    visualMap: {
      type: "continuous",
      min: matrix.value.minimum,
      max: matrix.value.maximum,
      dimension: 2,
      seriesIndex: 0,
      calculable: true,
      orient: "horizontal",
      top: 2,
      left: "center",
      itemWidth: 10,
      itemHeight: Math.max(80, Math.min(220, measured.value.width - 105)),
      text: ["高", "低"],
      textGap: 8,
      textStyle: { color: textColor, fontSize: 10 },
      formatter: (v: number) => formatRate(v),
      inRange: { color: matrixPalette },
      outOfRange: { color: prefs.dark ? "#283646" : "#e8edf2", opacity: 0.35 },
    },
    series: [
      {
        ...base,
        name: "区间平均带宽",
        data: matrix.value.points
          .filter((p) => hasHeatValue(p.cell))
          .map((p) => item(p, true)),
      },
      {
        ...base,
        name: "未观测时段",
        data: matrix.value.points
          .filter((p) => !hasHeatValue(p.cell))
          .map((p) => item(p, false)),
      },
    ],
  };
});
watch(
  () => props.data,
  (data, previous) => {
    if (
      data.matrix?.size !== previous.matrix?.size ||
      data.deviceId !== previous.deviceId ||
      data.interfaceId !== previous.interfaceId ||
      data.direction !== previous.direction ||
      data.timezone !== previous.timezone ||
      data.fromDate !== previous.fromDate
    ) {
      selected.value = null;
      row.value = 0;
      column.value = 0;
      return;
    }
    const index = selected.value?.index;
    const cell =
      index == null ? null : data.matrix?.cells.find((c) => c.index === index);
    if (cell) selectCell(cell);
    else selected.value = null;
  },
);
function measure() {
  const rect = target.value?.getBoundingClientRect();
  if (rect?.width && rect.height)
    measured.value = { width: rect.width, height: rect.height };
}
watch(
  target,
  (element, previous) => {
    if (previous) observer?.unobserve(previous);
    if (element) observer?.observe(element);
    measure();
  },
  { flush: "post" },
);
onMounted(() => {
  measure();
  if (typeof ResizeObserver !== "undefined") {
    observer = new ResizeObserver(measure);
    if (target.value) observer.observe(target.value);
  }
});
onBeforeUnmount(() => observer?.disconnect());
</script>
<template>
  <div class="heatmap-toolbar">
    <label
      >矩阵大小<select
        :value="gridSize"
        aria-label="热力图网格"
        @change="
          emit(
            'size',
            Number(($event.target as HTMLSelectElement).value) as 7 | 28,
          )
        "
      >
        <option :value="7">7 × 7</option>
        <option :value="28">28 × 28</option>
      </select></label
    >
    <span>{{ data.fromDate }} — {{ data.toDate }} · {{ data.timezone }}</span>
  </div>
  <div v-if="!data.matrix" class="notice">
    当前来源暂未提供矩阵数据，请刷新重试。
  </div>
  <div
    v-else
    ref="target"
    class="heatmap-square"
    :aria-label="`七日带宽 ${size} × ${size} 连续热力矩阵`"
    :data-grid-size="size"
    :data-cell-size="geometry.cellSize"
    :data-plot-left="geometry.left"
    :data-plot-top="geometry.top"
    :data-plot-width="geometry.width"
    :data-plot-height="geometry.height"
  >
    <ChartCanvas
      :option="option"
      square
      :label="`七日带宽 ${size} × ${size} 热力矩阵`"
      @select="onChartSelect"
    />
  </div>
  <p class="heatmap-reading-note">
    时间从左至右、逐行向下 · {{ size * size }} 个连续时段 · 蓝色低，红色高 ·
    色阶范围见图例
  </p>
  <div class="chart-footnote legend-row">
    <span
      ><i class="legend-box" style="background: #313695"></i
      >有效观测（含零值）</span
    >
    <span
      ><i
        class="legend-box"
        :style="{ background: prefs.dark ? '#374553' : '#e4e8ed' }"
      ></i
      >缺失</span
    >
    <span
      ><i
        class="legend-box"
        style="background: transparent; border-style: dashed"
      ></i
      >未来</span
    >
    <span
      ><i class="legend-box" style="border: 2px solid #ad7415"></i
      >部分覆盖</span
    >
  </div>
  <div v-if="data.matrix" class="heatmap-selection">
    <label
      >行<select
        v-model.number="row"
        aria-label="热力图行"
        @change="selectFromControls"
      >
        <option v-for="i in size" :key="i" :value="i - 1">第 {{ i }} 行</option>
      </select></label
    >
    <label
      >列<select
        v-model.number="column"
        aria-label="热力图列"
        @change="selectFromControls"
      >
        <option v-for="i in size" :key="i" :value="i - 1">第 {{ i }} 列</option>
      </select></label
    >
    <button type="button" class="btn small-btn" @click="selectFromControls">
      查看时段
    </button>
  </div>
  <div class="chart-inspector" aria-live="polite">
    <template v-if="selected"
      ><strong>{{ intervalLabel(selected) }}</strong
      ><span
        >{{ stateLabel(selected.state) }} ·
        {{ formatRate(hasHeatValue(selected) ? selected.value : null) }} · 覆盖
        {{ (selected.coverage * 100).toFixed(0) }}%</span
      ><span>{{
        selected.intervals.map((i) => `${i.from} → ${i.to}`).join(" / ")
      }}</span
      ><span
        >{{
          data.statistic === "sample_mean"
            ? `${selected.sampleCount} 个有效样本的均值`
            : "有效计数差按观测时长加权"
        }}
        · 修订 {{ selected.dataRevision }} ·
        {{ selected.qualityFlags.join(" · ") || "无附加质量标记" }}</span
      ></template
    >
    <template v-else
      ><strong>选择一个格子，查看对应时段</strong
      ><span>每格按原始有效观测重新聚合；缺失时段不补零。</span
      ><span
        >{{ data.direction.toUpperCase() }} · {{ data.source }} ·
        时间格边界对齐五分钟，详情显示实际区间。</span
      ></template
    >
  </div>
  <details v-if="data.matrix" class="data-alternative">
    <summary>查看完整 {{ size * size }} 个时段的数据表</summary>
    <div
      class="table-scroll"
      role="region"
      aria-label="七日带宽矩阵数据表"
      tabindex="0"
      style="max-height: 320px"
    >
      <table class="data-table">
        <thead>
          <tr>
            <th>时段</th>
            <th>时间</th>
            <th>状态</th>
            <th>带宽</th>
            <th>覆盖率</th>
            <th>详情</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="cell in data.matrix.cells" :key="cell.index">
            <td>{{ cell.index + 1 }}</td>
            <td>{{ intervalLabel(cell) }}</td>
            <td>{{ stateLabel(cell.state) }}</td>
            <td>{{ formatRate(hasHeatValue(cell) ? cell.value : null) }}</td>
            <td>{{ (cell.coverage * 100).toFixed(0) }}%</td>
            <td>
              <button
                type="button"
                class="btn small-btn"
                :aria-label="`查看时段 ${cell.index + 1} 详情`"
                @click="selectCell(cell)"
              >
                查看
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </details>
</template>
<style scoped>
.heatmap-square {
  position: relative;
  width: 100%;
  max-width: 680px;
  aspect-ratio: 1;
  margin: 0 auto;
}
.heatmap-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 12px;
}
.heatmap-toolbar label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.heatmap-toolbar span {
  font-size: 12px;
  color: var(--noeriva-muted);
}
.heatmap-toolbar select,
.heatmap-selection select {
  font-size: 12px;
  max-width: 100%;
}
.heatmap-reading-note {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--noeriva-muted);
  text-align: center;
}
.heatmap-selection {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 10px;
  margin: 14px 0 10px;
}
.heatmap-selection label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--noeriva-muted);
}
</style>
