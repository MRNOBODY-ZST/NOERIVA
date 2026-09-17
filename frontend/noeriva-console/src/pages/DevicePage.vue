<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch, nextTick } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  Server,
  RefreshCw,
  ExternalLink,
  Database,
  Radio,
  Clock3,
} from "@lucide/vue";
import type { EChartsCoreOption } from "echarts/core";
import { useApiQuery } from "../services/queries";
import { queryString } from "../services/api";
import type {
  Summary,
  Page,
  Interface,
  MetricSeries,
  Heatmap,
  Topology,
} from "../services/types";
import { usePreferencesStore } from "../stores/preferences";
import {
  formatRate,
  formatTime,
  shortNumber,
  stateLabel,
  naturalNameSort,
  formatMetric,
} from "../utils/format";
import StatusBadge from "../components/StatusBadge.vue";
import QueryState from "../components/QueryState.vue";
import ChartCanvas from "../components/ChartCanvas.vue";
import BandwidthHeatmap from "../components/BandwidthHeatmap.vue";
import GraphPanel from "../components/GraphPanel.vue";
import EventsPage from "./EventsPage.vue";
import DeviceManagement from "../components/DeviceManagement.vue";
import DeviceCollection from "../components/DeviceCollection.vue";
import DeviceSensors from "../components/DeviceSensors.vue";
import DeviceTrafficStatus from "../components/DeviceTrafficStatus.vue";
const router = useRouter();
const route = useRoute(),
  prefs = usePreferencesStore();
const id = computed(() => String(route.params.id));
const tab = computed({
  get: () => String(route.query.tab || "overview"),
  set: (value) => {
    void router.replace({ query: { ...route.query, tab: value } });
  },
});
const tabs = [
  { id: "overview", label: "概览" },
  { id: "monitoring", label: "传感器与监测" },
  { id: "network", label: "接口与带宽" },
  { id: "connections", label: "资产关系" },
  { id: "activity", label: "事件与告警" },
  { id: "access", label: "接入与采集" },
  { id: "configuration", label: "配置与变更" },
  { id: "info", label: "原始资料" },
];
const {
  data: summary,
  isPending,
  error,
  refetch,
} = useApiQuery<Summary>(
  () => `/devices/${encodeURIComponent(id.value)}/summary`,
);
const device = computed(() => summary.value?.device);
// Capability assignment is authoritative; an empty current projection alone does
// not rule out historical samples from an assigned metric source.
const hasMetricSource = computed(
  () => device.value?.capabilities.includes("metrics") ?? false,
);
const {
  data: interfaces,
  error: interfaceError,
  isPending: interfacePending,
  refetch: refetchInterfaces,
} = useApiQuery<Page<Interface>>(
  () => `/devices/${encodeURIComponent(id.value)}/interfaces`,
);
const sortedInterfaces = computed(() =>
  naturalNameSort(interfaces.value?.items || []),
);
const interfaceTable = ref<HTMLElement>();
const interfaceId = ref(String(route.query.interfaceId || "")),
  direction = ref("rx"),
  activeMetric = ref("cpu_percent"),
  hours = ref("24");
const rangeEnd = ref(new Date().toISOString());
const metricLabels: Record<string, { label: string; unit: string }> = {
  cpu_percent: { label: "CPU 使用率", unit: "%" },
  memory_percent: { label: "内存使用率", unit: "%" },
  temperature_celsius: { label: "温度", unit: "°C" },
  power_watts: { label: "功耗", unit: "W" },
  bandwidth_rx_bps: { label: "接收带宽", unit: "bit/s" },
  bandwidth_tx_bps: { label: "发送带宽", unit: "bit/s" },
};
watch(
  () => [id.value, route.query.interfaceId],
  () => {
    interfaceId.value = String(route.query.interfaceId || "");
  },
);
watch(
  () => device.value?.type,
  (type) => {
    activeMetric.value =
      type === "BMC"
        ? "temperature_celsius"
        : ["ROUTER", "SWITCH", "FIREWALL"].includes(type || "")
          ? "bandwidth_rx_bps"
          : "cpu_percent";
  },
);
watch(
  () => [
    id.value,
    tab.value,
    route.query.interfaceId,
    sortedInterfaces.value.length,
  ],
  async () => {
    if (tab.value !== "network" || !route.query.interfaceId) return;
    await nextTick();
    const row = Array.from(
      interfaceTable.value?.querySelectorAll<HTMLElement>(
        "[data-interface-id]",
      ) || [],
    ).find(
      (item) => item.dataset.interfaceId === String(route.query.interfaceId),
    );
    row?.scrollIntoView?.({
      block: "center",
      behavior:
        prefs.reducedMotion ||
        globalThis.matchMedia?.("(prefers-reduced-motion: reduce)").matches
          ? "auto"
          : "smooth",
    });
  },
  { immediate: true },
);
const showMonitoring = computed(() =>
  ["overview", "monitoring"].includes(tab.value),
);
const pageVisible = ref(document.visibilityState !== "hidden");
const visibleMonitoring = computed(
  () => showMonitoring.value && pageVisible.value,
);
const advanceRange = () => {
  rangeEnd.value = new Date().toISOString();
};
// Relative queries must move their URL window, not refetch a stale fixed end time.
watch(
  [id, tab, activeMetric, hours, pageVisible],
  () => {
    if (visibleMonitoring.value) advanceRange();
  },
  { flush: "sync" },
);
let rangeTimer: ReturnType<typeof setInterval> | undefined;
const stopRangeTimer = () => {
  clearInterval(rangeTimer);
  rangeTimer = undefined;
};
watch(
  visibleMonitoring,
  (visible) => {
    stopRangeTimer();
    if (visible) {
      advanceRange();
      rangeTimer = setInterval(advanceRange, 20_000);
    }
  },
  { immediate: true },
);
const visibilityChanged = () => {
  pageVisible.value = document.visibilityState !== "hidden";
};
onMounted(() =>
  document.addEventListener("visibilitychange", visibilityChanged),
);
onUnmounted(() => {
  stopRangeTimer();
  document.removeEventListener("visibilitychange", visibilityChanged);
});
const {
  data: metric,
  error: metricError,
  isPending: metricPending,
  refetch: refetchMetric,
} = useApiQuery<MetricSeries>(
  computed(
    () =>
      `/devices/${encodeURIComponent(id.value)}/metrics${queryString({ metric: activeMetric.value, from: new Date(new Date(rangeEnd.value).getTime() - Number(hours.value) * 3600000).toISOString(), to: rangeEnd.value, points: 120 })}`,
  ),
  () => visibleMonitoring.value && hasMetricSource.value,
  { refetchInterval: false },
);
const {
  data: heatmap,
  error: heatError,
  isPending: heatPending,
  refetch: refetchHeat,
} = useApiQuery<Heatmap>(
  computed(
    () =>
      `/devices/${encodeURIComponent(id.value)}${interfaceId.value ? `/interfaces/${encodeURIComponent(interfaceId.value)}` : ""}/bandwidth/heatmap${queryString({ timezone: prefs.timezone, direction: direction.value, days: 7, statistic: "time_weighted_mean" })}`,
  ),
  () =>
    visibleMonitoring.value && (hasMetricSource.value || !!interfaceId.value),
);
const {
  data: topology,
  error: topologyError,
  isPending: topologyPending,
  refetch: refetchTopology,
} = useApiQuery<Topology>(
  () => `/topology${queryString({ view: "ALL", deviceId: id.value })}`,
  () => tab.value === "connections",
);
const sourceMetrics = computed(
  () =>
    summary.value?.sources.flatMap((source) =>
      Object.entries(source.metrics).map(([key, value]) => ({
        key,
        value,
        source,
      })),
    ) || [],
);
const metricOption = computed<EChartsCoreOption>(() => ({
  animation: false,
  grid: { left: 58, right: 22, top: 24, bottom: 40 },
  tooltip: {
    trigger: "axis",
    renderMode: "richText",
    valueFormatter: (value: unknown) =>
      formatMetric(
        typeof value === "number" ? value : null,
        metric.value?.unit || metricLabels[activeMetric.value]?.unit,
      ),
  },
  xAxis: {
    type: "time",
    axisLine: { lineStyle: { color: prefs.dark ? "#3d5066" : "#dfe6ed" } },
    axisTick: { show: false },
    axisLabel: {
      color: prefs.dark ? "#b0bfd0" : "#536579",
      fontSize: 11,
      formatter: (value: number) =>
        new Intl.DateTimeFormat("zh-CN", {
          timeZone: prefs.timezone,
          hour: "2-digit",
          minute: "2-digit",
          hourCycle: "h23",
        }).format(value),
    },
    splitLine: { show: false },
  },
  yAxis: {
    type: "value",
    name: activeMetric.value.endsWith("_bps")
      ? "带宽"
      : metric.value?.unit || metricLabels[activeMetric.value]?.unit,
    axisLabel: {
      color: prefs.dark ? "#b0bfd0" : "#536579",
      fontSize: 11,
      formatter: (value: number) =>
        activeMetric.value.endsWith("_bps")
          ? formatRate(value)
          : formatMetric(value, metricLabels[activeMetric.value]?.unit),
    },
    splitLine: {
      lineStyle: { color: prefs.dark ? "#2a3b4d" : "#e9eef4", type: "dashed" },
    },
    nameTextStyle: { color: prefs.dark ? "#b0bfd0" : "#536579" },
  },
  series: [
    {
      name: metricLabels[activeMetric.value]?.label,
      type: "line",
      data:
        metric.value?.points.map((point) => [point.timestamp, point.value]) ||
        [],
      connectNulls: false,
      showSymbol: false,
      smooth: false,
      lineStyle: { color: prefs.dark ? "#8bbaff" : "#3d78b9", width: 2 },
      itemStyle: { color: "#3d78b9" },
      areaStyle: { color: prefs.dark ? "#8bbaff" : "#3d78b9", opacity: 0.055 },
    },
  ],
}));
function refresh() {
  rangeEnd.value = new Date().toISOString();
  void refetch();
  void refetchInterfaces();
  if (visibleMonitoring.value && hasMetricSource.value) void refetchHeat();
}
</script>
<template>
  <div style="margin-bottom: 16px">
    <RouterLink class="small muted" to="/assets"
      ><ArrowLeft
        aria-hidden="true"
        :size="13"
        style="display: inline; vertical-align: -2px"
      />
      返回资产目录</RouterLink
    >
  </div>
  <QueryState :pending="isPending" :error="error" @retry="refetch()"
    ><template v-if="device && summary"
      ><div class="page-heading">
        <div>
          <div class="detail-identity">
            <div class="identity-icon">
              <Server aria-hidden="true" :size="23" :stroke-width="1.6" />
            </div>
            <div>
              <h1>{{ device.name }}</h1>
              <div class="identity-meta">
                <span>{{ stateLabel(device.type) }}</span
                ><span>{{ device.vendor }} {{ device.model }}</span
                ><span class="mono">{{ device.managementAddress }}</span>
              </div>
            </div>
          </div>
          <div class="identity-meta" style="margin-top: 16px">
            <StatusBadge :status="device.health" /><StatusBadge
              :status="device.availability"
            /><span>{{ device.siteName }}</span
            ><span
              ><Clock3 aria-hidden="true" :size="12" style="display: inline" />
              {{ formatTime(device.lastSeen, prefs.timezone) }}</span
            >
          </div>
        </div>
        <div class="page-actions">
          <template
            v-if="['ROUTER', 'SWITCH', 'FIREWALL'].includes(device.type)"
          >
            <RouterLink
              class="btn"
              :to="{ path: '/applications', query: { deviceId: device.id } }"
              >应用监测</RouterLink
            >
            <RouterLink
              class="btn"
              :to="{ path: '/nat-audit', query: { deviceId: device.id } }"
              >NAT 审计</RouterLink
            >
          </template>
          <RouterLink
            class="btn"
            :to="{ path: '/events', query: { deviceId: device.id } }"
            ><ExternalLink aria-hidden="true" :size="14" /> 相关事件</RouterLink
          ><button class="btn" @click="refresh">
            <RefreshCw aria-hidden="true" :size="14" /> 刷新
          </button>
        </div>
      </div>
      <nav class="tabs" aria-label="设备详情标签">
        <button
          v-for="item in tabs"
          :key="item.id"
          class="tab"
          :class="{ active: tab === item.id }"
          :aria-current="tab === item.id ? 'page' : undefined"
          @click="tab = item.id"
        >
          {{ item.label }}
        </button>
      </nav>
      <div
        v-if="
          summary.qualityFlags?.length || summary.sourceFreshness !== 'FRESH'
        "
        class="notice"
        style="margin-bottom: 20px"
      >
        <strong>数据质量</strong> · {{ stateLabel(summary.sourceFreshness) }} ·
        覆盖 {{ shortNumber(summary.coverage * 100) }}%
        <span v-if="summary.qualityFlags?.length"
          >· {{ summary.qualityFlags.join(" · ") }}</span
        >
      </div>
      <div v-if="showMonitoring" class="stack">
        <section class="panel">
          <dl v-if="sourceMetrics.length" class="metric-summary">
            <div
              v-for="item in sourceMetrics.slice(0, 4)"
              :key="`${item.source.sourceId}-${item.key}`"
            >
              <dt>{{ metricLabels[item.key]?.label || item.key }}</dt>
              <dd>
                {{ formatMetric(item.value, metricLabels[item.key]?.unit) }}
              </dd>
              <div class="secondary-line">
                {{ item.source.sourceId }} ·
                {{ stateLabel(item.source.freshness) }}
              </div>
            </div>
            <div v-if="sourceMetrics.length <= 2">
              <dt>未恢复告警</dt>
              <dd>{{ summary.activeAlerts }} <small>项</small></dd>
              <div class="secondary-line">当前设备范围</div>
            </div>
            <div v-if="sourceMetrics.length <= 2">
              <dt>当前数据覆盖</dt>
              <dd>
                {{ shortNumber(summary.coverage * 100) }} <small>%</small>
              </dd>
              <div class="secondary-line">
                {{ stateLabel(summary.sourceFreshness) }}
              </div>
            </div>
            <div v-if="sourceMetrics.length === 1">
              <dt>观测来源</dt>
              <dd>{{ summary.sources.length }} <small>个</small></dd>
              <div class="secondary-line">独立来源状态</div>
            </div>
          </dl>
          <div v-else class="empty-state">
            <strong>尚无当前监测值</strong>
            <p v-if="summary.sources.length">
              已有来源观测，当前未提供数值指标。身份与邻居等结果可在“接入与采集”查看。
            </p>
            <p v-else>保留设备记录，等待受信任来源提供有效观测。</p>
          </div>
          <p
            v-if="
              sourceMetrics
                .slice(0, 4)
                .some((item) => item.key.startsWith('bandwidth_'))
            "
            class="chart-footnote"
          >
            设备级带宽：已采集接口的进出口合计，逻辑与物理接口可能重叠。热力图默认汇总，可切换具体接口。
          </p>
          <div class="panel-foot">
            <span
              >当前态来源 {{ summary.sources.length }} 个 · 最后成功观测
              {{ formatTime(device.lastSeen, prefs.timezone) }}</span
            ><span>{{ summary.activeAlerts }} 项未恢复告警（含已确认）</span>
          </div>
        </section>
        <div class="two-col">
          <div class="stack" style="align-content: start">
            <section class="panel">
              <header class="panel-head">
                <div>
                  <h2>监测趋势</h2>
                  <p>{{ prefs.timezone }} · 缺失样本保留为空隙</p>
                </div>
                <div class="inline-actions">
                  <select
                    v-model="activeMetric"
                    aria-label="监测指标"
                    :disabled="!hasMetricSource"
                  >
                    <option
                      v-for="(item, key) in metricLabels"
                      :key="key"
                      :value="key"
                    >
                      {{ item.label }}
                    </option></select
                  ><select
                    v-model="hours"
                    aria-label="趋势时间范围"
                    :disabled="!hasMetricSource"
                  >
                    <option value="1">近 1 小时</option>
                    <option value="6">近 6 小时</option>
                    <option value="24">近 24 小时</option>
                    <option value="168">近 7 天</option>
                  </select>
                </div>
              </header>
              <p
                v-if="activeMetric.startsWith('bandwidth_')"
                class="chart-footnote"
              >
                设备级带宽：已采集接口的进出口合计，逻辑与物理接口可能重叠。热力图默认汇总，可切换具体接口。
              </p>
              <div v-if="!hasMetricSource" class="empty-state" role="status">
                <strong>{{
                  summary.sources.length ? "暂无数值指标" : "等待监测来源"
                }}</strong>
                <p v-if="summary.sources.length">
                  当前已接入来源，但现有读取尚未提供可查询的数值指标。若需网络流量，请配置并采集
                  SNMP 等支持接口计数的来源。
                </p>
                <p v-else>
                  设备已登记，尚未分配监测来源。分配来源并收到有效观测后，可查看监测趋势。
                </p>
              </div>
              <QueryState
                v-else
                :pending="metricPending"
                :error="metricError"
                :empty="!metric?.points.some((point) => point.value !== null)"
                empty-title="该指标没有可用样本"
                empty-description="当前设备、指标和时间范围尚未产生可查询的观测。"
                @retry="refetchMetric()"
                ><div class="chart-wrap">
                  <ChartCanvas
                    :option="metricOption"
                    :label="`${metricLabels[activeMetric]?.label}时间序列；完整数值可在下方展开。`"
                  />
                </div>
                <p class="chart-footnote">
                  {{ metric?.source }} · 覆盖
                  {{ shortNumber((metric?.coverage || 0) * 100) }}% ·
                  {{ metric?.provisional ? "临时汇总" : "当前修订" }}
                  {{ metric?.dataRevision }}
                </p>
                <details class="data-alternative">
                  <summary>查看趋势数据表</summary>
                  <div
                    class="table-scroll"
                    role="region"
                    aria-label="趋势数据表"
                    tabindex="0"
                    style="max-height: 260px"
                  >
                    <table class="data-table">
                      <thead>
                        <tr>
                          <th>时间 · {{ prefs.timezone }}</th>
                          <th>
                            {{ metricLabels[activeMetric]?.label }} /
                            {{ metric?.unit }}
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr
                          v-for="point in metric?.points"
                          :key="point.timestamp"
                        >
                          <td class="mono">
                            {{ formatTime(point.timestamp, prefs.timezone) }}
                          </td>
                          <td>
                            {{
                              point.value === null
                                ? "缺失"
                                : activeMetric.endsWith("_bps")
                                  ? formatRate(point.value)
                                  : shortNumber(point.value)
                            }}
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </details></QueryState
              >
            </section>
            <section class="panel">
              <header class="panel-head">
                <h2>观测来源</h2>
                <Database aria-hidden="true" :size="16" class="muted" />
              </header>
              <div
                v-for="source in summary.sources"
                :key="source.sourceId"
                class="queue-row"
              >
                <Radio aria-hidden="true" :size="16" class="muted" />
                <div class="queue-main">
                  <div class="queue-label">
                    <strong class="small">{{ source.sourceId }}</strong
                    ><StatusBadge :status="source.freshness" />
                  </div>
                  <small
                    >{{ source.kind }} ·
                    {{ formatTime(source.observedAt, prefs.timezone) }}</small
                  ><small
                    >epoch {{ source.epoch }} · sequence
                    {{ source.sequence }}</small
                  >
                </div>
              </div>
              <div v-if="!summary.sources.length" class="empty-state">
                暂无来源记录
              </div>
            </section>
          </div>
          <section class="panel">
            <header class="panel-head">
              <div>
                <h2>七日带宽热力图</h2>
                <p>
                  日期 × 小时 ·
                  {{ interfaceId ? "接口时间加权均值" : "设备汇总采样均值" }}
                </p>
              </div>
              <select v-model="direction" aria-label="带宽方向">
                <option value="rx">RX 接收</option>
                <option value="tx">TX 发送</option>
              </select>
            </header>
            <div v-if="interfaces?.items.length" class="section-toolbar">
              <label for="heat-interface" class="small muted">范围</label
              ><select
                id="heat-interface"
                v-model="interfaceId"
                style="flex: 1"
              >
                <option value="">设备总进出口带宽</option>
                <option
                  v-for="item in sortedInterfaces"
                  :key="item.id"
                  :value="item.id"
                >
                  {{ item.name }} · {{ formatRate(Number(item.speedBps)) }}
                </option>
              </select>
            </div>
            <QueryState
              v-if="hasMetricSource || interfaceId"
              :pending="heatPending"
              :error="heatError"
              @retry="refetchHeat()"
              ><BandwidthHeatmap v-if="heatmap" :data="heatmap" /></QueryState
            ><QueryState
              v-else
              :pending="interfacePending"
              :error="interfaceError"
              empty
              empty-title="等待带宽采集"
              empty-description="设备尚未提供带宽指标。启用 SNMP 接口采集后可查看设备汇总和各接口历史。"
              @retry="refetchInterfaces()"
            />
          </section>
        </div>
        <DeviceSensors v-if="tab === 'monitoring'" :device-id="id" />
      </div>
      <section v-else-if="tab === 'network'" class="panel">
        <header class="panel-head">
          <div>
            <h2>设备接口</h2>
            <p>接口配置状态与观测状态独立展示</p>
          </div>
        </header>
        <QueryState
          :pending="interfacePending"
          :error="interfaceError"
          :empty="!interfaces?.items.length"
          empty-title="该设备尚无接口记录"
          @retry="refetchInterfaces()"
          ><div
            class="table-scroll"
            role="region"
            ref="interfaceTable"
            aria-label="设备接口列表"
            tabindex="0"
          >
            <table class="data-table">
              <thead>
                <tr>
                  <th>接口名称</th>
                  <th>MAC 地址</th>
                  <th>速率</th>
                  <th>管理状态</th>
                  <th>运行状态</th>
                  <th>监测</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="item in sortedInterfaces"
                  :key="item.id"
                  :class="{ 'wb-select-row': interfaceId === item.id }"
                  :data-interface-id="item.id"
                >
                  <td class="mono">{{ item.name }}</td>
                  <td class="mono">{{ item.macAddress || "未提供" }}</td>
                  <td>{{ formatRate(Number(item.speedBps)) }}</td>
                  <td><StatusBadge :status="item.adminStatus" /></td>
                  <td><StatusBadge :status="item.operStatus" /></td>
                  <td>
                    <button
                      class="btn small-btn"
                      @click="
                        router.replace({
                          query: {
                            ...route.query,
                            interfaceId: item.id,
                            tab: 'monitoring',
                          },
                        })
                      "
                    >
                      查看带宽
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div></QueryState
        >
      </section>
      <section v-else-if="tab === 'connections'" class="panel">
        <QueryState
          :pending="topologyPending"
          :error="topologyError"
          :empty="!topology?.nodes.length"
          @retry="refetchTopology()"
          ><GraphPanel v-if="topology" :topology="topology"
        /></QueryState>
      </section>
      <div v-else-if="tab === 'activity'" class="stack">
        <div class="page-actions">
          <RouterLink
            class="btn"
            :to="{ path: '/alerts', query: { deviceId: id } }"
            >设备告警</RouterLink
          ><RouterLink
            class="btn"
            :to="{ path: '/incidents', query: { deviceId: id } }"
            >事件工作台</RouterLink
          ><RouterLink
            class="btn"
            :to="{ path: '/evidence', query: { deviceId: id } }"
            >证据资料</RouterLink
          >
        </div>
        <EventsPage :device-id="id" embedded />
      </div>
      <div v-else-if="tab === 'access'" :key="id">
        <DeviceManagement :device-id="id" />
        <DeviceTrafficStatus
          v-if="['ROUTER', 'SWITCH', 'FIREWALL'].includes(device.type)"
          :device-id="id"
        />
        <DeviceCollection
          class="wb-spaced"
          :device-id="id"
          :management-address="device.managementAddress"
        />
      </div>
      <section v-else-if="tab === 'configuration'" class="panel">
        <div class="empty-state">
          <Database
            aria-hidden="true"
            :size="28"
            class="faint"
            style="margin: 0 auto 16px"
          /><strong>审阅设备配置快照</strong>
          <p>查看本设备已保存的脱敏快照，比较两个版本，追溯捕获时间和来源。</p>
          <RouterLink
            class="btn section-gap"
            :to="{ path: '/configuration', query: { deviceId: id } }"
            >打开配置与变更</RouterLink
          >
        </div>
      </section>
      <section v-else class="panel">
        <header class="panel-head"><h2>设备资料与能力</h2></header>
        <div class="panel-body">
          <dl class="mini-grid">
            <div
              v-for="(value, key) in {
                设备标识: device.id,
                类型: stateLabel(device.type),
                站点: device.siteName,
                管理地址: device.managementAddress,
                厂商: device.vendor || '未记录',
                型号: device.model || '未记录',
                记录修订: device.revision,
                最后观测: formatTime(device.lastSeen, prefs.timezone),
              }"
              :key="key"
              class="key-value"
            >
              <dt>{{ key }}</dt>
              <dd>{{ value }}</dd>
            </div>
          </dl>
          <div class="section-gap">
            <div class="section-heading">
              <h3>来源声明能力</h3>
              <RouterLink :to="{ path: '/evidence', query: { deviceId: id } }"
                >查看证据资料 →</RouterLink
              >
            </div>
            <div class="legend-row" style="margin-top: 12px">
              <span
                v-for="capability in device.capabilities"
                :key="capability"
                class="tag"
                >{{ capability }}</span
              ><span v-if="!device.capabilities.length" class="muted small"
                >尚未声明能力</span
              >
            </div>
            <p class="small muted section-gap">
              来源声明不代表已验证真实厂商或协议支持。支持情况见系统能力矩阵。
            </p>
          </div>
        </div>
      </section></template
    ></QueryState
  >
</template>
