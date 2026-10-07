<script setup lang="ts">
import { computed } from "vue";
import { metricNames, type MonitoringSource } from "../services/workbench";
import {
  monitoringComparisonOption,
  monitoringMetricValue,
  monitoringMetrics,
  monitoringReadings,
} from "../utils/monitoringComparison";
import ChartCanvas from "./ChartCanvas.vue";

const props = defineProps<{
  sources: MonitoringSource[];
  metric: string;
  dark: boolean;
  selectedKey?: string;
}>();
const emit = defineEmits<{
  "update:metric": [metric: string];
  select: [source: MonitoringSource, metric: string];
}>();
const metrics = computed(() => monitoringMetrics(props.sources));
const readings = computed(() =>
  monitoringReadings(props.sources, props.metric),
);
const observed = computed(() =>
  readings.value.filter((row) => row.value !== null),
);
const missing = computed(() =>
  readings.value.filter((row) => row.value === null),
);
const stale = computed(
  () => readings.value.filter((row) => row.source.freshness === "STALE").length,
);
const option = computed(() =>
  monitoringComparisonOption(
    readings.value,
    props.metric,
    props.dark,
    props.selectedKey,
  ),
);
function selectKey(key: string) {
  const reading = readings.value.find((row) => row.key === key);
  if (reading) emit("select", reading.source, props.metric);
}
function selectBar(payload: unknown) {
  const key = (payload as { data?: { sourceKey?: string } })?.data?.sourceKey;
  if (key) selectKey(key);
}
</script>

<template>
  <section class="monitoring-comparison" aria-label="监测当前值对比">
    <header class="comparison-head">
      <div>
        <h2>当前值对比</h2>
        <p class="small muted">
          当前页 {{ sources.length }} 个来源 · 按同一指标比较
        </p>
      </div>
      <select
        :value="metric"
        :disabled="!metrics.length"
        class="input"
        aria-label="当前值对比指标"
        @change="
          emit('update:metric', ($event.target as HTMLSelectElement).value)
        "
      >
        <option v-if="!metrics.length" value="">暂无可比较指标</option>
        <option v-for="key in metrics" :key="key" :value="key">
          {{ metricNames[key]?.name }}
        </option>
      </select>
    </header>
    <template v-if="metrics.length">
      <p class="comparison-quality small muted" aria-live="polite">
        {{ observed.length }} 个有效读数 · {{ missing.length }} 个缺失 ·
        {{ stale }} 个过期
      </p>
      <div v-if="observed.length" class="comparison-chart-scroll">
        <ChartCanvas
          :option="option"
          :label="`当前页 ${metricNames[metric]?.name}来源对比，点击柱形查看历史趋势`"
          :height="`${Math.max(220, observed.length * 34 + 70)}px`"
          @select="selectBar"
        />
      </div>
      <p v-else class="wb-note">
        当前页的{{
          metricNames[metric]?.name
        }}没有有效读数，等待采集来源提供观测。
      </p>
      <div class="comparison-select">
        <label for="monitoring-source-select" class="small"
          >选择来源并查看趋势</label
        >
        <select
          id="monitoring-source-select"
          class="input"
          :value="selectedKey || ''"
          aria-label="选择监测来源并查看趋势"
          @change="selectKey(($event.target as HTMLSelectElement).value)"
        >
          <option value="" disabled>选择当前页来源</option>
          <option v-for="row in readings" :key="row.key" :value="row.key">
            {{ row.label }} ·
            {{ row.missing || monitoringMetricValue(row.value, metric)
            }}{{ row.source.freshness === "STALE" ? " · 过期" : "" }}
          </option>
        </select>
      </div>
      <div
        v-if="missing.length"
        class="comparison-missing"
        aria-label="缺失的当前读数"
      >
        <p class="small muted">以下来源没有当前读数：</p>
        <ul>
          <li v-for="row in missing" :key="row.key" class="small">
            {{ row.label }} · {{ row.missing
            }}{{ row.source.freshness === "STALE" ? " · 过期" : "" }}
          </li>
        </ul>
      </div>
      <p class="small muted comparison-help">
        点击柱形或选择来源查看历史。过期来源以淡色标记，缺失读数单独列出。
      </p>
    </template>
    <p v-else class="wb-note">
      当前页来源尚未提供可比较的数值指标。展开来源明细查看采集状态与设备信息。
    </p>
  </section>
</template>

<style scoped>
.monitoring-comparison {
  padding: 20px;
  min-width: 0;
}
.comparison-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
}
.comparison-head h2 {
  margin-bottom: 5px;
}
.comparison-quality {
  margin: 14px 0 0;
}
.comparison-chart-scroll {
  max-height: 560px;
  overflow-y: auto;
  overflow-x: hidden;
}
.comparison-select {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.comparison-select select {
  min-width: 0;
  flex: 1;
  max-width: 720px;
}
.comparison-missing {
  margin-top: 16px;
}
.comparison-missing ul {
  margin: 6px 0 0;
  padding-left: 18px;
  max-height: 160px;
  overflow-y: auto;
}
.comparison-missing li {
  margin: 4px 0;
  overflow-wrap: anywhere;
}
.comparison-help {
  margin: 14px 0 0;
}
@media (max-width: 640px) {
  .monitoring-comparison {
    padding: 14px;
  }
  .comparison-head {
    align-items: stretch;
    flex-direction: column;
  }
  .comparison-head .input {
    width: 100%;
  }
  .comparison-select {
    align-items: stretch;
    flex-direction: column;
    gap: 7px;
  }
}
</style>
