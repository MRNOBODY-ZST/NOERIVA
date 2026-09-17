<script setup lang="ts">
import { computed, ref } from "vue";
import { TriangleAlert, RefreshCw } from "@lucide/vue";
import type { EChartsCoreOption } from "echarts/core";
import { useApiQuery } from "../services/queries";
import { queryString } from "../services/api";
import type { Page, Alert, Site, MetricSeries } from "../services/types";
import type { WorkspaceOverview } from "../services/workspace";
import { formatRate, formatTime, stateLabel } from "../utils/format";
import { usePreferencesStore } from "../stores/preferences";
import StatusBadge from "../components/StatusBadge.vue";
import QueryState from "../components/QueryState.vue";
import ChartCanvas from "../components/ChartCanvas.vue";
const prefs = usePreferencesStore();
const site = ref(""),
  hours = ref("1"),
  rangeEnd = ref(new Date().toISOString());
const { data, isPending, error, refetch } = useApiQuery<WorkspaceOverview>(
  () => `/workspace/overview${queryString({ siteId: site.value })}`,
);
const { data: sites } = useApiQuery<Page<Site>>("/sites");
const {
  data: alerts,
  error: alertError,
  isPending: alertPending,
  refetch: refetchAlerts,
} = useApiQuery<Page<Alert>>("/alerts?state=OPEN&limit=5");
const traffic = computed(() => data.value?.trafficSource);
const hasRx = computed(() => traffic.value?.metrics.bandwidth_rx_bps != null);
const hasTx = computed(() => traffic.value?.metrics.bandwidth_tx_bps != null);
const metricPath = (metric: string) =>
  `/devices/${encodeURIComponent(traffic.value?.deviceId || "")}/metrics${queryString({ metric, from: new Date(new Date(rangeEnd.value).getTime() - Number(hours.value) * 3600000).toISOString(), to: rangeEnd.value, points: 90 })}`;
const rx = useApiQuery<MetricSeries>(
  () => metricPath("bandwidth_rx_bps"),
  hasRx,
);
const tx = useApiQuery<MetricSeries>(
  () => metricPath("bandwidth_tx_bps"),
  hasTx,
);
const chartPending = computed(
  () =>
    (hasRx.value && rx.isPending.value) || (hasTx.value && tx.isPending.value),
);
const chartError = computed(() => rx.error.value || tx.error.value);
const chartEmpty = computed(
  () =>
    ![...(rx.data.value?.points || []), ...(tx.data.value?.points || [])].some(
      (point) => point.value != null,
    ),
);
const trafficOption = computed<EChartsCoreOption>(() => ({
  animation: false,
  aria: { enabled: true },
  grid: { left: 65, right: 20, top: 20, bottom: 35 },
  tooltip: {
    trigger: "axis",
    valueFormatter: (value: unknown) =>
      formatRate(typeof value === "number" ? value : null),
  },
  xAxis: {
    type: "time",
    axisLine: { lineStyle: { color: prefs.dark ? "#304154" : "#d6dfe9" } },
    axisLabel: {
      color: prefs.dark ? "#a8b7c8" : "#526478",
      fontSize: 12,
      formatter: (value: number) =>
        new Intl.DateTimeFormat("zh-CN", {
          timeZone: prefs.timezone,
          hour: "2-digit",
          minute: "2-digit",
          hourCycle: "h23",
        }).format(value),
    },
    axisTick: { show: false },
    splitNumber: 5,
  },
  yAxis: {
    type: "value",
    axisLabel: {
      color: prefs.dark ? "#a8b7c8" : "#526478",
      fontSize: 11,
      formatter: (value: number) => formatRate(value),
    },
    splitLine: {
      lineStyle: { color: prefs.dark ? "#304154" : "#d6dfe9", type: "dashed" },
    },
  },
  series: [
    {
      name: "入站",
      type: "line",
      showSymbol: false,
      connectNulls: false,
      data:
        rx.data.value?.points.map((point) => [point.timestamp, point.value]) ||
        [],
      lineStyle: { color: prefs.dark ? "#8ab8ff" : "#255ea8", width: 2.4 },
      itemStyle: { color: prefs.dark ? "#8ab8ff" : "#255ea8" },
    },
    {
      name: "出站",
      type: "line",
      showSymbol: false,
      connectNulls: false,
      data:
        tx.data.value?.points.map((point) => [point.timestamp, point.value]) ||
        [],
      lineStyle: {
        color: prefs.dark ? "#8499ae" : "#64758a",
        width: 1.7,
        type: "dashed",
      },
      itemStyle: { color: prefs.dark ? "#8499ae" : "#64758a" },
    },
  ],
}));
function refresh() {
  rangeEnd.value = new Date().toISOString();
  void refetch();
  void refetchAlerts();
}
function retryTraffic() {
  if (hasRx.value) void rx.refetch();
  if (hasTx.value) void tx.refetch();
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>运行总览</h1>
      <p>在同一处理解健康状态、关键变化与下一步操作。</p>
    </div>
    <div class="page-actions">
      <select v-model="site" aria-label="站点范围">
        <option value="">全部站点</option>
        <option v-for="item in sites?.items" :key="item.id" :value="item.id">
          {{ item.name }}
        </option></select
      ><select v-model="hours" aria-label="流量时间窗口">
        <option value="0.25">最近 15 分钟</option>
        <option value="1">最近 1 小时</option>
        <option value="6">最近 6 小时</option>
        <option value="24">最近 24 小时</option></select
      ><button class="btn" @click="refresh">
        <RefreshCw aria-hidden="true" :size="14" />刷新
      </button>
    </div>
  </div>
  <QueryState :pending="isPending" :error="error" @retry="refetch()">
    <div v-if="data" class="clarity-grid">
      <div class="clarity-main">
        <div class="clarity-stats">
          <div>
            <p>资产总数 · 当前范围</p>
            <strong
              ><RouterLink
                :to="{ path: '/assets', query: site ? { siteId: site } : {} }"
                >{{ data.totals.devices }}</RouterLink
              ></strong
            >
            <p>
              {{ data.totals.healthy }} 台健康 ·
              {{ data.totals.critical + data.totals.warning }} 台需关注 ·
              {{ data.totals.unknown }} 台状态未知
            </p>
          </div>
          <div>
            <p>未恢复告警 · 含已确认</p>
            <strong
              ><RouterLink :to="{ path: '/alerts', query: { state: '' } }">{{
                data.totals.activeAlerts
              }}</RouterLink></strong
            >
            <p>
              {{ data.totals.critical }} 台严重 ·
              {{ data.totals.stale }} 台数据过期
            </p>
          </div>
          <div>
            <p>状态统计时刻</p>
            <strong>{{
              formatTime(data.asOf, prefs.timezone).slice(-8, -3)
            }}</strong>
            <p>
              {{ formatTime(data.asOf, prefs.timezone).split(" ")[0] }} ·
              {{ prefs.timezone }}
            </p>
          </div>
        </div>
        <section class="flat-section">
          <div class="section-heading">
            <h2>优先巡检</h2>
            <RouterLink
              :to="{ path: '/assets', query: site ? { siteId: site } : {} }"
              >完整资产目录 →</RouterLink
            >
          </div>
          <div class="panel">
            <div v-if="!data.priorityDevices.length" class="empty-state">
              <strong>当前范围没有待巡检资产</strong>
              <p>严重、警告或状态未知的资产会出现在这里。</p>
            </div>
            <div
              v-else
              class="table-scroll"
              role="region"
              aria-label="优先巡检资产"
              tabindex="0"
            >
              <table class="data-table">
                <thead>
                  <tr>
                    <th>资产 / 类型</th>
                    <th>健康</th>
                    <th>可用性</th>
                    <th>最后观测</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="device in data.priorityDevices" :key="device.id">
                    <td>
                      <RouterLink
                        :to="`/assets/${device.id}`"
                        class="device-name"
                        >{{ device.name }}</RouterLink
                      >
                      <p class="secondary-line">
                        {{ stateLabel(device.type) }} · {{ device.siteName }}
                      </p>
                    </td>
                    <td><StatusBadge :status="device.health" /></td>
                    <td><StatusBadge :status="device.availability" /></td>
                    <td class="mono">
                      {{ formatTime(device.lastSeen, prefs.timezone) }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="panel-foot">
              <span>严重 → 警告 → 未知 · 最多 10 台</span
              ><RouterLink to="/topology">关系视图 →</RouterLink>
            </div>
          </div>
        </section>
        <section class="panel clarity-chart">
          <header class="panel-head">
            <div>
              <h2>流量趋势</h2>
              <p v-if="traffic">
                <RouterLink
                  :to="`/assets/${traffic.deviceId}?tab=monitoring`"
                  >{{ traffic.deviceName }}</RouterLink
                >
                · {{ traffic.sourceId }} · 单一来源
              </p>
              <p v-else>来自设备的带宽观测</p>
            </div>
            <StatusBadge v-if="traffic" :status="traffic.freshness" />
          </header>
          <div v-if="!traffic" class="empty-state">
            <strong>等待带宽观测</strong>
            <p>当前站点尚无包含入站或出站带宽的采集来源。</p>
            <RouterLink to="/monitoring" class="small"
              >查看监测来源 →</RouterLink
            >
          </div>
          <template v-else
            ><div class="chart-stats">
              <div>
                <strong>{{
                  formatRate(traffic.metrics.bandwidth_rx_bps)
                }}</strong
                ><small>入站 · 最近值</small>
              </div>
              <div>
                <strong>{{
                  formatRate(traffic.metrics.bandwidth_tx_bps)
                }}</strong
                ><small>出站 · 最近值</small>
              </div>
            </div>
            <QueryState
              :pending="chartPending"
              :error="chartError"
              :empty="chartEmpty"
              empty-title="当前窗口暂无带宽样本"
              empty-description="选择更长时间窗口，或检查来源最近采集时间。"
              @retry="retryTraffic"
              ><ChartCanvas
                :option="trafficOption"
                :label="`${traffic.deviceName} 入站与出站带宽趋势`"
            /></QueryState>
            <div class="chart-legend">
              <span><i class="legend-line"></i>入站</span
              ><span><i class="legend-line secondary"></i>出站</span
              ><span
                >观测于
                {{ formatTime(traffic.observedAt, prefs.timezone) }}</span
              >
            </div></template
          >
        </section>
        <section class="panel section-gap">
          <header class="panel-head">
            <h2>站点健康</h2>
            <RouterLink to="/assets" class="small">查看资产 →</RouterLink>
          </header>
          <div
            class="table-scroll"
            role="region"
            aria-label="站点健康统计"
            tabindex="0"
          >
            <table class="data-table">
              <thead>
                <tr>
                  <th>站点</th>
                  <th>资产</th>
                  <th>需关注</th>
                  <th>状态未知</th>
                  <th>数据过期</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in data.siteHealth" :key="item.siteId">
                  <td>
                    <RouterLink
                      :to="{ path: '/assets', query: { siteId: item.siteId } }"
                      >{{ item.siteName }}</RouterLink
                    >
                  </td>
                  <td>{{ item.devices }}</td>
                  <td>{{ item.critical + item.warning }} 台</td>
                  <td>{{ item.unknown }} 台</td>
                  <td>
                    <span :class="item.stale ? 'danger-text' : 'muted'"
                      >{{ item.stale }} 台</span
                    >
                  </td>
                </tr>
                <tr v-if="!data.siteHealth.length">
                  <td colspan="5">当前范围尚无站点</td>
                </tr>
              </tbody>
            </table>
          </div>
          <footer class="panel-foot">
            <span>健康与数据过期分别统计</span><span>{{ prefs.timezone }}</span>
          </footer>
        </section>
      </div>
      <aside class="right-rail">
        <section class="flat-section">
          <div class="section-heading">
            <h2>告警队列</h2>
            <RouterLink to="/alerts">全部 →</RouterLink>
          </div>
          <p class="small muted" style="margin-bottom: 8px">
            全工作区 · 最近 5 条待确认
          </p>
          <QueryState
            :pending="alertPending"
            :error="alertError"
            :empty="!alerts?.items.length"
            empty-title="没有待确认告警"
            @retry="refetchAlerts()"
            ><div
              v-for="alert in alerts?.items"
              :key="alert.id"
              class="queue-row"
            >
              <TriangleAlert
                aria-hidden="true"
                :size="15"
                :class="alert.severity === 'CRITICAL' ? 'danger-text' : 'muted'"
              />
              <div class="queue-main">
                <div class="queue-label">
                  <StatusBadge :status="alert.severity" /><span
                    class="small faint"
                    >{{
                      formatTime(alert.openedAt, prefs.timezone).slice(-8)
                    }}</span
                  >
                </div>
                <p style="margin-top: 5px">
                  <RouterLink :to="`/assets/${alert.deviceId}`">{{
                    alert.title
                  }}</RouterLink>
                </p>
                <small>{{ alert.deviceName }}</small>
              </div>
            </div></QueryState
          >
        </section>
        <section class="flat-section">
          <div class="section-heading">
            <h2>最近变化</h2>
            <RouterLink to="/events">全部 →</RouterLink>
          </div>
          <p v-if="data.recentEventsStatus === 'UNAVAILABLE'" class="notice">
            事件历史暂时不可用。当前资产状态仍可查看。
          </p>
          <p
            v-else-if="data.recentEventsStatus === 'NOT_REQUESTED'"
            class="small muted"
          >
            已限定站点。请从巡检资产进入设备事件，查看对应来源。
          </p>
          <p v-else-if="!data.recentEvents.length" class="small muted">
            最近 7 天内没有事件记录。
          </p>
          <ol v-else class="clarity-timeline">
            <li v-for="event in data.recentEvents" :key="event.id">
              <time class="mono">{{
                formatTime(event.observedAt, prefs.timezone)
              }}</time
              ><strong>{{ event.message }}</strong>
              <p>{{ event.kind }} · {{ event.source }}</p>
              <RouterLink
                :to="{
                  path: '/events',
                  query: { deviceId: event.deviceId, eventId: event.id },
                }"
                >查看来源</RouterLink
              >
            </li>
          </ol>
        </section>
        <div class="notice">
          在资产目录中筛选和查看设备；进入事件工作台记录调查过程，保留来源与待验证假设。
        </div>
      </aside>
    </div>
  </QueryState>
</template>
