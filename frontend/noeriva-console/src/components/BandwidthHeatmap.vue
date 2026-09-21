<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { HeatCell, Heatmap } from "../services/types";
import { formatRate, stateLabel, formatTime } from "../utils/format";
import { usePreferencesStore } from "../stores/preferences";
const props = defineProps<{ data: Heatmap }>();
const prefs = usePreferencesStore();
const selected = ref<HeatCell | null>(null);
const days = computed(() =>
  [...new Set(props.data.cells.map((cell) => cell.date))]
    .sort()
    .map((date) => ({
      date,
      cells: props.data.cells
        .filter((cell) => cell.date === date)
        .sort((a, b) => a.hour - b.hour),
    })),
);
const valid = (cell: HeatCell) =>
  cell.value !== null &&
  Number.isFinite(cell.value) &&
  !["MISSING", "FUTURE", "DST_MISSING"].includes(cell.state);
const maximum = computed(() =>
  Math.max(1, ...props.data.cells.filter(valid).map((cell) => cell.value!)),
);
const palette = [
  [33, 102, 172],
  [146, 197, 222],
  [247, 247, 247],
  [244, 165, 130],
  [178, 24, 43],
];
function cellStyle(cell: HeatCell) {
  let rgb: number[];
  if (!valid(cell))
    rgb =
      cell.state === "FUTURE"
        ? prefs.dark
          ? [23, 35, 49]
          : [255, 255, 255]
        : prefs.dark
          ? [55, 69, 83]
          : [228, 232, 237];
  else {
    const scale = Math.max(0, Math.min(1, cell.value! / maximum.value)) * 4;
    const index = Math.min(3, Math.floor(scale)),
      fraction = scale - index;
    rgb = palette[index]!.map((v, i) =>
      Math.round(v + (palette[index + 1]![i]! - v) * fraction),
    );
  }
  const light = rgb[0]! * 0.299 + rgb[1]! * 0.587 + rgb[2]! * 0.114 > 150;
  return {
    backgroundColor: `rgb(${rgb.join(", ")})`,
    color: light ? "#25354a" : "#ffffff",
  };
}
function cellLabel(cell: HeatCell) {
  return `${cell.date} ${String(cell.hour).padStart(2, "0")}:00–${String(cell.hour + 1).padStart(2, "0")}:00 · ${stateLabel(cell.state)} · ${formatRate(cell.value)} · 覆盖 ${(cell.coverage * 100).toFixed(0)}%`;
}
function weekday(date: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    weekday: "short",
    timeZone: "UTC",
  }).format(new Date(`${date}T12:00:00Z`));
}
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
</script>
<template>
  <div
    class="heatmap-square"
    aria-label="七日带宽热力图，按天分组，每格代表一小时"
  >
    <section
      v-for="day in days"
      :key="day.date"
      class="heatmap-day"
      :aria-label="day.date"
    >
      <header>
        <strong>{{ day.date.slice(5) }}</strong
        ><span>{{ weekday(day.date) }}</span>
      </header>
      <div class="heatmap-day-hours">
        <button
          v-for="cell in day.cells"
          :key="cell.hour"
          type="button"
          class="heatmap-hour"
          :class="{
            partial: cell.state === 'PARTIAL',
            missing: !valid(cell),
            selected:
              selected?.date === cell.date && selected?.hour === cell.hour,
          }"
          :data-cell="`${cell.date}-${cell.hour}`"
          :data-state="cell.state"
          :style="cellStyle(cell)"
          :title="cellLabel(cell)"
          :aria-label="cellLabel(cell)"
          :aria-pressed="
            selected?.date === cell.date && selected?.hour === cell.hour
          "
          @click="selected = cell"
        >
          {{ String(cell.hour).padStart(2, "0") }}
        </button>
      </div>
    </section>
    <div class="heatmap-scale" aria-label="蓝色代表低带宽，红色代表高带宽">
      <strong>小时平均带宽</strong>
      <div class="heatmap-colorbar"></div>
      <div class="heatmap-scale-labels">
        <span>0 bit/s</span><span>{{ formatRate(maximum) }}</span>
      </div>
      <span>蓝色低 · 红色高</span>
    </div>
  </div>
  <div class="chart-footnote legend-row">
    <span><i class="legend-box" style="background: #2166ac"></i>观测为零</span
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
          data.statistic === "sample_mean" ? "设备小时样本均值" : "时间加权均值"
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

<style scoped>
.heatmap-square {
  width: 100%;
  max-width: 680px;
  aspect-ratio: 1;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-template-rows: repeat(4, minmax(0, 1fr));
  gap: 2%;
  padding: 2%;
  container-type: inline-size;
}
.heatmap-day,
.heatmap-scale {
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--noeriva-border);
  border-radius: 8px;
  padding: 3% 5%;
  background: var(--noeriva-surface);
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8%;
}
.heatmap-day header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  font-size: clamp(10px, 2.4cqw, 14px);
  line-height: 1;
}
.heatmap-day header span {
  color: var(--noeriva-muted);
  font-size: 0.85em;
}
.heatmap-day-hours {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 3px;
  width: 100%;
}
.heatmap-hour {
  aspect-ratio: 1;
  width: 100%;
  min-width: 0;
  min-height: 0;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 2px;
  font: inherit;
  font-size: clamp(7px, 1.65cqw, 11px);
  line-height: 1;
  cursor: pointer;
  font-variant-numeric: tabular-nums;
}
.heatmap-hour.missing {
  border-color: var(--noeriva-border);
}
.heatmap-hour.partial {
  box-shadow: inset 0 0 0 1px #b58031;
}
.heatmap-hour.selected,
.heatmap-hour:focus-visible {
  outline: 2px solid var(--noeriva-text);
  outline-offset: 1px;
  z-index: 1;
}
.heatmap-scale {
  gap: 9%;
  font-size: clamp(9px, 2cqw, 12px);
}
.heatmap-scale > span {
  color: var(--noeriva-muted);
}
.heatmap-colorbar {
  height: 10px;
  border-radius: 3px;
  background: linear-gradient(
    90deg,
    #2166ac,
    #92c5de,
    #f7f7f7,
    #f4a582,
    #b2182b
  );
}
.heatmap-scale-labels {
  display: flex;
  justify-content: space-between;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}
</style>
