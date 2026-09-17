<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { RefreshCw } from "@lucide/vue";
import {
  usePagedQuery,
  metricNames,
  type MonitoringSource,
} from "../services/workbench";
import { useApiQuery } from "../services/queries";
import { queryString } from "../services/api";
import type { Site, Page, MetricSeries } from "../services/types";
import { usePreferencesStore } from "../stores/preferences";
import { formatTime, shortNumber, formatMetric } from "../utils/format";
import QueryState from "../components/QueryState.vue";
import StatusBadge from "../components/StatusBadge.vue";
import PagePager from "../components/PagePager.vue";
import ChartCanvas from "../components/ChartCanvas.vue";
const prefs = usePreferencesStore(),
  route = useRoute();
const q = ref(""),
  siteId = ref(String(route.query.siteId || "")),
  freshness = ref(""),
  selected = ref<MonitoringSource | null>(null),
  metric = ref("temperature_celsius"),
  hours = ref("1"),
  to = ref(new Date().toISOString()),
  showValues = ref(false);
const sites = useApiQuery<Page<Site>>("/sites");
const list = usePagedQuery<MonitoringSource>(
  "/workspace/monitoring",
  computed(() => ({
    q: q.value,
    siteId: siteId.value,
    freshness: freshness.value,
    deviceId: String(route.query.deviceId || ""),
  })),
);
function select(source: MonitoringSource) {
  selected.value = source;
  metric.value =
    Object.keys(source.metrics).find((key) => key === "temperature_celsius") ||
    Object.keys(source.metrics).find((key) => metricNames[key]) ||
    "";
  to.value = new Date().toISOString();
}
watch([q, siteId, freshness], () => (selected.value = null));
const series = useApiQuery<MetricSeries>(
  computed(
    () =>
      `/devices/${selected.value?.deviceId}/metrics${queryString({ metric: metric.value, from: new Date(Date.parse(to.value) - Number(hours.value) * 3600000).toISOString(), to: to.value, points: 120 })}`,
  ),
  () => !!selected.value && !!metricNames[metric.value],
);
const chart = computed(() => ({
  animation: false,
  grid: { left: 60, right: 24, top: 20, bottom: 42 },
  tooltip: {
    trigger: "axis",
    renderMode: "richText",
    valueFormatter: (v: unknown) =>
      formatMetric(
        typeof v === "number" ? v : null,
        metricNames[metric.value]?.unit,
      ),
  },
  xAxis: {
    type: "time",
    axisLabel: {
      formatter: (v: number) =>
        new Intl.DateTimeFormat("zh-CN", {
          timeZone: prefs.timezone,
          hour: "2-digit",
          minute: "2-digit",
          hourCycle: "h23",
        }).format(v),
    },
  },
  yAxis: {
    type: "value",
    name: metric.value.endsWith("_bps")
      ? "带宽"
      : series.data.value?.unit || metricNames[metric.value]?.unit,
    axisLabel: {
      formatter: (v: number) =>
        formatMetric(v, metricNames[metric.value]?.unit),
    },
  },
  series: [
    {
      type: "line",
      showSymbol: false,
      connectNulls: false,
      data: series.data.value?.points.map((p) => [p.timestamp, p.value]) || [],
      lineStyle: { width: 2, color: "#255ea8" },
      itemStyle: { color: "#255ea8" },
    },
  ],
}));
function value(key: string, v: number | null) {
  return formatMetric(
    v,
    key.endsWith("_bps") ? "bit/s" : metricNames[key]?.unit || "",
  );
}
watch(
  () => route.query.siteId,
  (value) => {
    siteId.value = String(value || "");
  },
);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>监测与传感器</h1>
      <p>当前值与历史趋势并列，数据质量不隐藏在图表背后。</p>
      <p class="small">
        <RouterLink
          :to="{
            path: '/applications',
            query: route.query.deviceId
              ? { deviceId: String(route.query.deviceId) }
              : {},
          }"
          >Cisco 接口应用汇总请进入独立应用监测 ↗</RouterLink
        >
      </p>
    </div>
    <button
      class="btn"
      @click="
        list.refetch();
        to = new Date().toISOString();
      "
    >
      <RefreshCw :size="14" />刷新
    </button>
  </div>
  <section class="panel">
    <div class="wb-tools">
      <input
        v-model.lazy="q"
        @keydown.enter.prevent="q = ($event.target as HTMLInputElement).value"
        class="input grow"
        aria-label="监测来源前缀搜索"
        placeholder="设备名称、IP 或来源 ID 前缀，回车查询"
      /><select v-model="siteId" class="input" aria-label="监测站点">
        <option value="">全部站点</option>
        <option v-for="s in sites.data.value?.items" :key="s.id" :value="s.id">
          {{ s.name }}
        </option></select
      ><select v-model="freshness" class="input" aria-label="来源新鲜度">
        <option value="">全部新鲜度</option>
        <option value="FRESH">新鲜</option>
        <option value="STALE">过期</option>
      </select>
    </div>
    <QueryState
      :pending="list.isPending.value"
      :error="list.error.value"
      :empty="!list.data.value?.items.length"
      empty-title="等待监测来源"
      empty-description="当前范围没有采集来源。资产登记后，需要由采集端上报观测。"
      @retry="list.refetch()"
      ><div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>设备 / 来源</th>
              <th>站点</th>
              <th>状态与新鲜度</th>
              <th>当前观测</th>
              <th>采集时间</th>
              <th>历史</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="s in list.data.value?.items"
              :key="`${s.deviceId}/${s.sourceId}`"
              :class="{
                'wb-select-row':
                  selected?.deviceId === s.deviceId &&
                  selected?.sourceId === s.sourceId,
              }"
            >
              <td>
                <RouterLink :to="`/devices/${s.deviceId}`">{{
                  s.deviceName
                }}</RouterLink>
                <p class="secondary-line">{{ s.kind }} · {{ s.sourceId }}</p>
              </td>
              <td>{{ s.siteName }}</td>
              <td>
                <StatusBadge
                  :status="s.freshness === 'STALE' ? 'UNKNOWN' : s.health"
                />
                <div class="secondary-line">
                  <StatusBadge :status="s.freshness" />
                </div>
              </td>
              <td>
                <div
                  v-for="[key, v] in Object.entries(s.metrics).slice(0, 6)"
                  :key="key"
                  class="small"
                >
                  {{ metricNames[key]?.name || key }} · {{ value(key, v) }}
                </div>
                <span v-if="!Object.keys(s.metrics).length" class="small muted"
                  >此来源未提供聚合读数</span
                >
                <details v-if="Object.keys(s.metrics).length > 6" class="small">
                  <summary>
                    另 {{ Object.keys(s.metrics).length - 6 }} 项指标
                  </summary>
                  <div
                    v-for="[key, v] in Object.entries(s.metrics).slice(6)"
                    :key="key"
                  >
                    {{ metricNames[key]?.name || key }} · {{ value(key, v) }}
                  </div>
                </details>
                <p class="secondary-line">
                  <RouterLink
                    :to="{
                      path: `/devices/${s.deviceId}`,
                      query: { tab: 'monitoring' },
                    }"
                    >查看设备传感器 ↗</RouterLink
                  >
                </p>
              </td>
              <td class="small mono">
                {{ formatTime(s.observedAt, prefs.timezone) }}
              </td>
              <td>
                <button class="btn small-btn" @click="select(s)">
                  查看趋势
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div></QueryState
    ><PagePager
      :pending="list.isFetching.value"
      :page="list.page.value"
      :count="list.data.value?.items.length || 0"
      :previous="list.hasPrevious.value"
      :next="!!list.data.value?.nextCursor"
      @back="list.back"
      @forward="list.next"
    />
  </section>
  <div v-if="selected" class="wb-grid wb-spaced">
    <section class="panel">
      <header class="panel-head">
        <h2>{{ selected.deviceName }} · 历史趋势</h2>
        <button class="btn small-btn" @click="showValues = !showValues">
          {{ showValues ? "隐藏数据" : "查看数据" }}
        </button>
      </header>
      <div class="wb-tools">
        <select v-model="metric" class="input" aria-label="监测指标">
          <option
            v-for="key in Object.keys(selected.metrics).filter(
              (k) => metricNames[k],
            )"
            :key="key"
            :value="key"
          >
            {{ metricNames[key]?.name }}
          </option></select
        ><select v-model="hours" class="input" aria-label="监测时间范围">
          <option value="0.25">最近 15 分钟</option>
          <option value="1">最近 1 小时</option>
          <option value="6">最近 6 小时</option>
        </select>
      </div>
      <div v-if="!metric" class="wb-note">此来源尚未提供可查询的指标。</div>
      <QueryState
        v-else
        :pending="series.isPending.value"
        :error="series.error.value"
        :empty="!series.data.value?.points.length"
        empty-title="当前时间窗没有历史样本"
        @retry="series.refetch()"
        ><ChartCanvas
          :option="chart"
          :label="`${selected.deviceName} ${metricNames[metric]?.name}历史趋势`"
          height="260px"
        />
        <div v-if="showValues" class="table-scroll" style="max-height: 300px">
          <table class="data-table">
            <thead>
              <tr>
                <th>时间 {{ prefs.timezone }}</th>
                <th>数值 {{ series.data.value?.unit }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in series.data.value?.points" :key="p.timestamp">
                <td>{{ formatTime(p.timestamp, prefs.timezone) }}</td>
                <td>{{ formatMetric(p.value, metricNames[metric]?.unit) }}</td>
              </tr>
            </tbody>
          </table>
        </div></QueryState
      >
    </section>
    <aside class="panel">
      <header class="panel-head"><h2>采集质量</h2></header>
      <div class="panel-body">
        <dl class="wb-facts">
          <dt>当前来源</dt>
          <dd>{{ selected.kind }} / {{ selected.sourceId }}</dd>
          <dt>新鲜度</dt>
          <dd><StatusBadge :status="selected.freshness" /></dd>
          <dt>历史来源</dt>
          <dd>{{ series.data.value?.source || "等待查询" }}</dd>
          <dt>覆盖率</dt>
          <dd>
            {{
              series.data.value
                ? `${shortNumber(series.data.value.coverage * 100)}%`
                : "—"
            }}
          </dd>
          <dt>时区</dt>
          <dd>{{ prefs.timezone }}</dd>
          <dt>数据版本</dt>
          <dd>{{ series.data.value?.dataRevision ?? "—" }}</dd>
        </dl>
        <p class="wb-note wb-spaced">
          历史曲线按设备与指标查询；当前值保留所选来源标识。缺失的观测不会填成零。
        </p>
      </div>
    </aside>
  </div>
</template>
