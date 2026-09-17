<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { EChartsCoreOption } from "echarts/core";
import type { HeatCell, Heatmap } from "../services/types";
import { formatRate, stateLabel, formatTime } from "../utils/format";
import { usePreferencesStore } from "../stores/preferences";
import ChartCanvas from "./ChartCanvas.vue";
const props = defineProps<{ data: Heatmap }>();
const prefs = usePreferencesStore();
const selected = ref<HeatCell | null>(null);
const dates = computed(() =>
  [...new Set(props.data.cells.map((cell) => cell.date))].sort().reverse(),
);
watch(
  () => props.data,
  (data) => {
    if (selected.value)
      selected.value =
        data.cells.find(
          (cell) =>
            cell.date === selected.value?.date &&
            cell.hour === selected.value?.hour,
        ) || null;
  },
);
const option = computed<EChartsCoreOption>(() => {
  const valid = props.data.cells.filter(
    (cell) =>
      cell.value !== null &&
      !["MISSING", "FUTURE", "DST_MISSING"].includes(cell.state),
  );
  const missing = props.data.cells.filter(
    (cell) =>
      cell.value === null ||
      ["MISSING", "FUTURE", "DST_MISSING"].includes(cell.state),
  );
  const cellPoint = (cell: HeatCell) => ({
    value: [dates.value.indexOf(cell.date), cell.hour, cell.value ?? 0],
    cell,
    itemStyle:
      cell.state === "PARTIAL"
        ? { borderColor: "#b58031", borderWidth: 2 }
        : undefined,
  });
  return {
    animation: false,
    grid: { left: 54, right: 16, top: 16, bottom: 54 },
    tooltip: {
      position: "top",
      renderMode: "richText",
      formatter: (payload: unknown) => {
        const cell = (payload as { data: { cell: HeatCell } }).data.cell;
        return `${cell.date}  ${String(cell.hour).padStart(2, "0")}:00–${String(cell.hour + 1).padStart(2, "0")}:00\n${stateLabel(cell.state)} · ${formatRate(cell.value)}\n覆盖率 ${(cell.coverage * 100).toFixed(0)}%`;
      },
    },
    xAxis: {
      type: "category",
      data: dates.value.map((date) => date.slice(5)),
      position: "bottom",
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: prefs.dark ? "#b0bfd0" : "#536579",
        fontSize: 11,
        interval: 0,
      },
    },
    yAxis: {
      type: "category",
      data: Array.from(
        { length: 24 },
        (_, hour) => `${String(hour).padStart(2, "0")}:00`,
      ),
      inverse: true,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: prefs.dark ? "#b0bfd0" : "#536579",
        fontSize: 10,
        interval: 3,
      },
    },
    visualMap: [
      {
        min: 0,
        max: Math.max(...valid.map((cell) => cell.value || 0), 1),
        seriesIndex: 0,
        dimension: 2,
        calculable: false,
        orient: "horizontal",
        left: "center",
        bottom: 0,
        itemWidth: 10,
        itemHeight: 150,
        text: ["高", "低"],
        textStyle: { color: prefs.dark ? "#b0bfd0" : "#536579" },
        inRange: {
          color: prefs.dark
            ? ["#213c54", "#3375aa", "#80b9e8"]
            : ["#edf3fb", "#c3d9f1", "#6b9bce", "#255ea8"],
        },
      },
      {
        type: "piecewise",
        show: false,
        seriesIndex: 1,
        dimension: 2,
        pieces: [
          { value: 0, color: prefs.dark ? "#374553" : "#e4e8ed" },
          { value: 1, color: prefs.dark ? "#172331" : "#ffffff" },
        ],
      },
    ],
    series: [
      {
        type: "heatmap",
        data: valid.map(cellPoint),
        itemStyle: {
          borderColor: prefs.dark ? "#172331" : "#fff",
          borderWidth: 2,
        },
        emphasis: { itemStyle: { borderColor: "#172538", borderWidth: 2 } },
      },
      {
        type: "heatmap",
        data: missing.map((cell) => ({
          ...cellPoint(cell),
          value: [
            dates.value.indexOf(cell.date),
            cell.hour,
            cell.state === "FUTURE" ? 1 : 0,
          ],
          itemStyle: {
            color:
              cell.state === "FUTURE"
                ? prefs.dark
                  ? "#172331"
                  : "#fff"
                : prefs.dark
                  ? "#374553"
                  : "#e4e8ed",
            borderColor: prefs.dark ? "#2a3b4d" : "#d5dde7",
            borderWidth: 1,
          },
        })),
        emphasis: { itemStyle: { borderColor: "#536579", borderWidth: 2 } },
      },
    ],
  };
});
function inspect(payload: unknown) {
  selected.value =
    (payload as { data?: { cell?: HeatCell } }).data?.cell || null;
}
</script>
<template>
  <div class="chart-wrap">
    <ChartCanvas
      :option="option"
      square
      label="七日日期乘小时带宽热力图，数值与缺失状态可在下方表格读取。"
      @select="inspect"
    />
  </div>
  <div class="chart-footnote legend-row">
    <span><i class="legend-box" style="background: #edf3fb"></i>观测为零</span
    ><span><i class="legend-box" style="background: #e4e8ed"></i>缺失</span
    ><span><i class="legend-box" style="background: transparent"></i>未来</span
    ><span
      ><i class="legend-box" style="border: 2px solid #b58031"></i>部分</span
    >
  </div>
  <div class="chart-inspector" aria-live="polite">
    <template v-if="selected"
      ><strong
        >{{ selected.date }} ·
        {{ String(selected.hour).padStart(2, "0") }}:00–{{
          String(selected.hour + 1).padStart(2, "0")
        }}:00</strong
      ><span
        >{{ stateLabel(selected.state) }} · {{ formatRate(selected.value) }} ·
        覆盖 {{ (selected.coverage * 100).toFixed(0) }}%</span
      ><span>{{
        selected.intervals
          .map(
            (item) =>
              `${formatTime(item.from, data.timezone)} → ${formatTime(item.to, data.timezone)}`,
          )
          .join(" / ") || "无对应 UTC 时间区间"
      }}</span
      ><span
        >修订 {{ selected.dataRevision }} ·
        {{ selected.qualityFlags.join(" · ") || "无附加质量标记" }}</span
      ></template
    ><template v-else
      ><strong>选择一个小时，检查数据来源</strong
      ><span
        >{{ data.fromDate }} 至 {{ data.toDate }} · {{ data.timezone }}</span
      ><span
        >{{
          data.statistic === "sample_mean" ? "设备汇总采样均值" : "时间加权均值"
        }}
        · {{ data.direction.toUpperCase() }} · {{ data.source }} · 小时末端为
        24:00</span
      ></template
    >
  </div>
  <details class="data-alternative">
    <summary>查看完整 168 个时段的数据表</summary>
    <div
      class="table-scroll"
      role="region"
      aria-label="七日带宽数据表"
      tabindex="0"
      style="max-height: 320px"
    >
      <table class="data-table">
        <thead>
          <tr>
            <th>日期</th>
            <th>小时</th>
            <th>状态</th>
            <th>带宽</th>
            <th>覆盖率</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="cell in data.cells" :key="`${cell.date}-${cell.hour}`">
            <td>{{ cell.date }}</td>
            <td>{{ String(cell.hour).padStart(2, "0") }}:00</td>
            <td>{{ stateLabel(cell.state) }}</td>
            <td>{{ formatRate(cell.value) }}</td>
            <td>{{ (cell.coverage * 100).toFixed(0) }}%</td>
          </tr>
        </tbody>
      </table>
    </div>
  </details>
</template>
