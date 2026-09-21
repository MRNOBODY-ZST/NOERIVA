<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { RefreshCw } from "@lucide/vue";
import { request, queryString } from "../services/api";
import { useApiQuery } from "../services/queries";
import ChartCanvas from "../components/ChartCanvas.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { usePagedQuery } from "../services/workbench";
import {
  actionError,
  initialWindow,
  qualityText,
  trafficStatus,
  useTrafficHistory,
  validateWindow,
  type ApplicationObservation,
  type ApplicationSummary,
  type ApplicationSource,
} from "../services/traffic";
import { useSessionStore } from "../stores/session";
import { usePreferencesStore } from "../stores/preferences";
import {
  formatTime,
  formatRate,
  formatBytes,
  formatCount,
  shortNumber,
} from "../utils/format";
import QueryState from "../components/QueryState.vue";
import ModalDialog from "../components/ModalDialog.vue";
import DevicePicker from "../components/DevicePicker.vue";
import PagePager from "../components/PagePager.vue";
const route = useRoute(),
  auth = useSessionStore(),
  prefs = usePreferencesStore();
const admin = computed(() => auth.session?.roles.includes("ADMIN") ?? false);
const sources = usePagedQuery<ApplicationSource>("/applications/sources");
const window = initialWindow(60),
  from = ref(window.from),
  to = ref(window.to),
  preset = ref("1");
const summaryWindow = computed(() => {
  try {
    return validateWindow(from.value, to.value, 168);
  } catch {
    return null;
  }
});
function selectWindow() {
  if (preset.value === "custom") return;
  const next = initialWindow(Number(preset.value) * 60);
  from.value = next.from;
  to.value = next.to;
}
function refreshOverview() {
  if (preset.value !== "custom") selectWindow();
  void overview.refetch();
}
const deviceId = ref(String(route.query.deviceId || "")),
  interfaceIndex = ref(""),
  direction = ref(""),
  q = ref("");
const history = useTrafficHistory<ApplicationObservation>(
    "/applications/observations",
  ),
  queryError = ref("");
const overview = useApiQuery<ApplicationSummary>(
  computed(
    () =>
      `/applications/summary${queryString({ deviceId: deviceId.value, ...summaryWindow.value, interfaceIndex: interfaceIndex.value, direction: direction.value, q: q.value.trim(), limit: 12 })}`,
  ),
  () =>
    !!deviceId.value &&
    !!summaryWindow.value &&
    (!interfaceIndex.value || /^[1-9]\d*$/.test(interfaceIndex.value)),
);
watch(
  () => sources.data.value?.items,
  (items) => {
    if (!deviceId.value && items?.length)
      deviceId.value =
        items.find((source) => source.enabled)?.deviceId || items[0]!.deviceId;
  },
  { immediate: true },
);
const applicationChartHeight = computed(
  () =>
    `${Math.max(300, (overview.data.value?.items.length || 0) * 34 + 55)}px`,
);
const applicationChart = computed(() => ({
  animation:
    !prefs.reducedMotion &&
    !globalThis.matchMedia?.("(prefers-reduced-motion: reduce)").matches,
  animationDuration: 350,
  grid: { left: 120, right: 24, top: 18, bottom: 40 },
  tooltip: {
    trigger: "axis",
    renderMode: "richText",
    valueFormatter: (v: unknown) =>
      formatRate(typeof v === "number" ? v : null),
  },
  xAxis: {
    type: "value",
    splitNumber: 3,
    axisLabel: {
      formatter: (v: number) => formatRate(v),
      hideOverlap: true,
      color: prefs.dark ? "#a8b7c8" : "#526478",
    },
    splitLine: {
      lineStyle: { type: "dashed", color: prefs.dark ? "#2a3b4d" : "#e9eef4" },
    },
  },
  yAxis: {
    type: "category",
    inverse: true,
    data:
      overview.data.value?.items.map(
        (row) => `${row.application} · ${row.direction === "IN" ? "入" : "出"}`,
      ) || [],
    axisLabel: {
      width: 110,
      overflow: "truncate",
      color: prefs.dark ? "#a8b7c8" : "#526478",
    },
  },
  series: [
    {
      name: "区间平均带宽",
      type: "bar",
      barMaxWidth: 20,
      data:
        overview.data.value?.items.map((row) => ({
          value: row.derivedBps,
          itemStyle: {
            color: row.direction === "IN" ? "#397fb7" : "#70a897",
            borderRadius: [0, 3, 3, 0],
          },
        })) || [],
    },
  ],
}));
const trendChart = computed(() => ({
  animation: false,
  grid: { left: 68, right: 24, top: 40, bottom: 45 },
  tooltip: {
    trigger: "axis",
    renderMode: "richText",
    valueFormatter: (v: unknown) =>
      formatRate(typeof v === "number" ? v : null),
  },
  legend: {
    data: ["入站", "出站"],
    top: 2,
    textStyle: { color: prefs.dark ? "#a8b7c8" : "#526478" },
  },
  xAxis: {
    type: "time",
    splitNumber: 4,
    axisLabel: {
      hideOverlap: true,
      color: prefs.dark ? "#a8b7c8" : "#526478",
      formatter: (v: number) =>
        new Intl.DateTimeFormat("zh-CN", {
          timeZone: prefs.timezone,
          ...(overview.data.value?.resolutionSeconds &&
          overview.data.value.resolutionSeconds >= 86400
            ? { month: "2-digit" as const, day: "2-digit" as const }
            : { hour: "2-digit" as const, minute: "2-digit" as const }),
          hourCycle: "h23",
        }).format(v),
    },
  },
  yAxis: {
    type: "value",
    axisLabel: {
      formatter: (v: number) => formatRate(v),
      color: prefs.dark ? "#a8b7c8" : "#526478",
    },
    splitLine: {
      lineStyle: { type: "dashed", color: prefs.dark ? "#2a3b4d" : "#e9eef4" },
    },
  },
  series: [
    {
      name: "入站",
      type: "line",
      showSymbol: false,
      connectNulls: false,
      itemStyle: { color: "#397fb7" },
      data:
        overview.data.value?.trend?.map((p) => [p.timestamp, p.inBps]) || [],
    },
    {
      name: "出站",
      type: "line",
      showSymbol: false,
      connectNulls: false,
      itemStyle: { color: "#70a897" },
      data:
        overview.data.value?.trend?.map((p) => [p.timestamp, p.outBps]) || [],
    },
  ],
}));
const bucketLabel = computed(() => {
  const seconds = overview.data.value?.resolutionSeconds;
  return seconds
    ? seconds >= 86400
      ? "每天"
      : seconds >= 3600
        ? "每小时"
        : `每 ${seconds / 60} 分钟`
    : "每个时间段";
});
watch(deviceId, () => {
  interfaceIndex.value = "";
});
const interfaceChoices = computed(
  () =>
    sources.data.value?.items.find((s) => s.deviceId === deviceId.value)
      ?.interfaceIndices || [],
);
const pending = ref(""),
  error = ref(""),
  notice = ref("");
const editing = ref<ApplicationSource | null>(null),
  intervalSeconds = ref(60),
  ifIndices = ref(""),
  maxRows = ref(128),
  enabled = ref(false);
const detail = ref<ApplicationObservation | null>(null);
function recentWindow() {
  const value = initialWindow(60);
  from.value = value.from;
  to.value = value.to;
  preset.value = "1";
}
watch(
  () => route.query.deviceId,
  (value) => {
    deviceId.value = String(value || "");
  },
);
watch(
  [deviceId, from, to, interfaceIndex, direction, q],
  () => {
    history.clear();
    detail.value = null;
  },
  { flush: "sync" },
);
function query() {
  queryError.value = "";
  try {
    if (!deviceId.value) throw Error("请选择观察设备。");
    if (
      interfaceIndex.value &&
      (!/^\d+$/.test(interfaceIndex.value) ||
        Number(interfaceIndex.value) < 1 ||
        Number(interfaceIndex.value) > 2147483647)
    )
      throw Error("ifIndex 须为正整数。");
    history.submit({
      deviceId: deviceId.value,
      ...validateWindow(from.value, to.value, 168),
      interfaceIndex: interfaceIndex.value,
      direction: direction.value,
      q: q.value.trim(),
    });
  } catch (e) {
    queryError.value = actionError(e);
  }
}
function edit(source: ApplicationSource) {
  editing.value = source;
  intervalSeconds.value = source.intervalSeconds;
  ifIndices.value = source.interfaceIndices.join(", ");
  maxRows.value = source.maxRows;
  enabled.value = source.enabled;
  error.value = "";
}
async function mutate(
  source: ApplicationSource,
  action: "settings" | "state" | "collect",
  body: object,
) {
  pending.value = `${source.deviceId}/${action}`;
  error.value = "";
  notice.value = "";
  try {
    const result = await request<ApplicationSource>(
      `/applications/devices/${encodeURIComponent(source.deviceId)}/${action}`,
      { method: "POST", body: JSON.stringify(body) },
    );
    if (action === "settings") editing.value = null;
    notice.value =
      action === "collect"
        ? `本次读取已返回：${trafficStatus[result.status] || result.status}。以实际状态与质量标记为准；这不会改变周期启停。查看新观测时，请重设最近时间窗再查询。`
        : "应用设置已保存，仅影响平台应用采集调度；基础 SNMP 启停与路由器协议发现设置各自独立。";
    if (
      action === "collect" &&
      history.filters.value?.deviceId === source.deviceId
    )
      await history.refetch();
  } catch (e) {
    error.value = actionError(e);
  } finally {
    await sources.refetch();
    await overview.refetch();
    pending.value = "";
  }
}
function save() {
  if (!editing.value) return;
  const parts = ifIndices.value.trim().split(/[,，\s]+/),
    indices = parts.map(Number);
  if (
    parts.some((p) => !/^\d+$/.test(p)) ||
    indices.length < 1 ||
    indices.length > 8 ||
    indices.some((n) => !Number.isInteger(n) || n < 1 || n > 2147483647) ||
    new Set(indices).size !== indices.length
  ) {
    error.value = "请输入 1–8 个互异的正整数 ifIndex，以逗号分隔。";
    return;
  }
  if (
    !Number.isInteger(Number(intervalSeconds.value)) ||
    Number(intervalSeconds.value) < 30 ||
    Number(intervalSeconds.value) > 3600 ||
    !Number.isInteger(Number(maxRows.value)) ||
    Number(maxRows.value) < 1 ||
    Number(maxRows.value) > 256
  ) {
    error.value = "周期须为 30–3600 秒，协议行上限须为 1–256 的整数。";
    return;
  }
  void mutate(editing.value, "settings", {
    revision: editing.value.revision,
    enabled: enabled.value,
    intervalSeconds: Number(intervalSeconds.value),
    interfaceIndices: indices,
    maxRows: Number(maxRows.value),
  });
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>应用监测</h1>
      <p>按时间范围查看应用平均带宽、累计流量与变化趋势。</p>
    </div>
    <button class="btn" @click="sources.refetch()">
      <RefreshCw :size="14" />刷新来源状态
    </button>
  </div>
  <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  <p v-if="error && !editing" class="wb-error" role="alert">{{ error }}</p>
  <section class="panel" aria-label="应用时间窗汇总">
    <header class="panel-head">
      <div>
        <h2>应用带宽排行</h2>
        <p>有效采样区间平均值 · 最多 12 个应用方向</p>
      </div>
      <StatusBadge
        v-if="overview.data.value"
        :status="overview.data.value.freshness"
      />
    </header>
    <form
      class="wb-form application-query"
      data-application-query
      @submit.prevent="query"
    >
      <div class="application-filter-grid">
        <div class="form-field">
          <label for="application-device">观察设备</label
          ><DevicePicker id="application-device" v-model="deviceId" />
        </div>
        <div class="form-field">
          <label for="application-window">时间范围</label
          ><select
            id="application-window"
            v-model="preset"
            class="input"
            @change="selectWindow"
          >
            <option value="0.25">最近 15 分钟</option>
            <option value="1">最近 1 小时</option>
            <option value="6">最近 6 小时</option>
            <option value="24">最近 24 小时</option>
            <option value="168">最近 7 天</option>
            <option value="custom">自定义</option>
          </select>
        </div>
        <div class="form-field">
          <label for="application-from">观测开始 · UTC</label
          ><input
            id="application-from"
            v-model="from"
            @input="preset = 'custom'"
            class="input"
            type="datetime-local"
            step="1"
            required
          />
        </div>
        <div class="form-field">
          <label for="application-to">观测结束 · UTC</label
          ><input
            id="application-to"
            v-model="to"
            @input="preset = 'custom'"
            class="input"
            type="datetime-local"
            step="1"
            required
          />
        </div>
        <div class="form-field">
          <label for="application-ifindex">接口 ifIndex</label
          ><select
            id="application-ifindex"
            v-model="interfaceIndex"
            class="input"
          >
            <option value="">全部已配置接口</option>
            <option
              v-for="index in interfaceChoices"
              :key="index"
              :value="String(index)"
            >
              ifIndex {{ index }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label for="application-direction">方向</label
          ><select id="application-direction" v-model="direction" class="input">
            <option value="">全部方向</option>
            <option value="IN">入站 IN</option>
            <option value="OUT">出站 OUT</option>
          </select>
        </div>
        <div class="form-field">
          <label for="application-q">应用名称 · 包含匹配</label
          ><input
            id="application-q"
            v-model="q"
            class="input"
            maxlength="80"
            placeholder="输入后回车查询"
          />
        </div>
      </div>
      <div class="wb-inline">
        <button class="btn primary" :disabled="history.isFetching.value">
          查询历史明细</button
        ><button class="btn" type="button" @click="recentWindow">
          最近 1 小时</button
        ><span class="small muted"
          >图表随筛选更新；历史明细按提交时的固定范围查询。</span
        >
      </div>
    </form>
    <p v-if="queryError" class="wb-error" role="alert">{{ queryError }}</p>
    <div class="application-refresh">
      <button
        class="btn small-btn"
        :disabled="!deviceId || overview.isFetching.value"
        @click="refreshOverview"
      >
        刷新时间窗
      </button>
    </div>
    <p v-if="!deviceId" class="wb-note">
      等待已配置的应用来源；配置来源后将自动显示应用排行。
    </p>
    <QueryState
      v-else
      :pending="overview.isPending.value"
      :error="overview.error.value"
      :empty="!overview.data.value?.items.length"
      empty-title="尚无应用采样"
      empty-description="请检查独立应用采集来源。首次样本建立基线，后续有效样本生成速率。"
      @retry="overview.refetch()"
    >
      <div class="application-metrics">
        <div>
          <span>已观测累计流量</span
          ><strong data-application-total-bytes>{{
            formatBytes(overview.data.value?.totalBytes)
          }}</strong
          ><small>当前时间范围内</small>
        </div>
        <div>
          <span>入站累计</span
          ><strong>{{ formatBytes(overview.data.value?.inBytes) }}</strong
          ><small>有效差分字节</small>
        </div>
        <div>
          <span>出站累计</span
          ><strong>{{ formatBytes(overview.data.value?.outBytes) }}</strong
          ><small>有效差分字节</small>
        </div>
        <div>
          <span>平均带宽</span
          ><strong>{{ formatRate(overview.data.value?.meanBps) }}</strong
          ><small data-application-coverage
            >有效时段 · 覆盖
            {{
              shortNumber((overview.data.value?.coverage ?? 0) * 100)
            }}%</small
          >
        </div>
      </div>
      <div class="application-charts">
        <div>
          <h3>区间平均带宽排行</h3>
          <ChartCanvas
            :option="applicationChart"
            label="应用区间平均带宽排行"
            :height="applicationChartHeight"
          />
        </div>
        <div>
          <h3>
            流量趋势 <span class="small muted">{{ bucketLabel }}平均</span>
          </h3>
          <ChartCanvas
            :option="trendChart"
            label="应用入站与出站区间平均带宽趋势"
            :height="applicationChartHeight"
          />
        </div>
      </div>
      <p class="wb-note">
        累计流量仅累加有效采样区间；平均带宽按各接口的有效采样时长计算。缺失时段不补零，覆盖不足时不推算完整时间窗总量。
      </p>
      <p class="wb-note">
        最近有效观测
        {{ formatTime(overview.data.value?.observedAt, prefs.timezone) }} ·
        {{ overview.data.value?.sampleRows }} 个方向样本 · 覆盖
        {{ overview.data.value?.totalApplications }} 个应用方向。<span
          v-if="overview.data.value?.truncated"
          >图表展示平均带宽最高的 12 项；上方总量包含所有匹配应用。</span
        >{{ qualityText(overview.data.value?.qualityFlags || []) }}
      </p>
      <details class="data-alternative" open>
        <summary>查看排行数值与来源质量</summary>
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>应用</th>
                <th>方向 / ifIndex</th>
                <th>区间平均带宽</th>
                <th>累计流量</th>
                <th>时段覆盖</th>
                <th>质量</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in overview.data.value?.items"
                :key="`${row.application}/${row.direction}`"
              >
                <td>{{ row.application }}</td>
                <td>
                  {{ row.direction }} · {{ row.interfaceIndices.join(", ") }}
                </td>
                <td>{{ formatRate(row.derivedBps) }}</td>
                <td
                  :title="
                    row.cumulativeBytes == null
                      ? '没有有效流量'
                      : `${row.cumulativeBytes} bytes`
                  "
                >
                  {{ formatBytes(row.cumulativeBytes) }}
                </td>
                <td>{{ shortNumber(row.coverage * 100) }}%</td>
                <td>{{ qualityText(row.qualityFlags) || "无附加标记" }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
    </QueryState>
  </section>
  <section class="panel wb-spaced">
    <header class="panel-head">
      <h2>独立应用采集</h2>
      <span class="small muted"
        >只读 SNMP · 应用开关不依赖基础 SNMP 周期启停</span
      >
    </header>
    <QueryState
      :pending="sources.isPending.value"
      :error="sources.error.value"
      :empty="!sources.data.value?.items.length"
      empty-title="当前来源页没有已保存的 SNMP 连接"
      empty-description="先在设备的接入与采集中保存 SNMP 凭据，再选择明确的 ifIndex 配置应用监测。实际 NBAR 支持以读取结果为准。"
      @retry="sources.refetch()"
    >
      <div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>设备 / 协议</th>
              <th>应用状态</th>
              <th>覆盖配置</th>
              <th>最近尝试 / 成功</th>
              <th>质量与错误</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in sources.data.value?.items" :key="s.deviceId">
              <td>
                <RouterLink :to="`/assets/${s.deviceId}?tab=access`">{{
                  s.deviceName
                }}</RouterLink>
                <p class="secondary-line">
                  {{ s.protocol }} · 凭据版本 {{ s.credentialRevision }}
                </p>
              </td>
              <td>
                <strong>{{ trafficStatus[s.status] || s.status }}</strong>
                <p class="secondary-line">
                  {{ s.enabled ? "应用周期已启用" : "应用周期已停用" }} ·
                  应用版本 {{ s.revision }}
                </p>
                <p class="secondary-line">
                  下次调度 {{ formatTime(s.nextPollAt, prefs.timezone) }}
                </p>
              </td>
              <td class="small">
                ifIndex {{ s.interfaceIndices.join(", ") || "尚未选择" }}
                <p class="secondary-line">
                  {{ s.intervalSeconds }} 秒 · 最多 {{ s.maxRows }} 个协议行 /
                  轮
                </p>
                <p class="secondary-line">
                  最近保存 {{ s.lastRowCount }} 个方向观测行
                </p>
              </td>
              <td class="small">
                {{ formatTime(s.lastAttemptAt, prefs.timezone) }}
                <p class="secondary-line">
                  成功 {{ formatTime(s.lastSuccessAt, prefs.timezone) }}
                </p>
              </td>
              <td class="small">
                <p v-if="s.errorCode" class="danger-text">
                  {{ s.errorCode }} · {{ s.errorMessage }}
                </p>
                <p>{{ qualityText(s.qualityFlags) || "无附加质量标记" }}</p>
              </td>
              <td>
                <div class="wb-inline">
                  <button
                    class="btn small-btn"
                    @click="
                      deviceId = s.deviceId;
                      query();
                    "
                  >
                    查看观测</button
                  ><button
                    v-if="admin"
                    class="btn small-btn"
                    :data-application-edit="s.deviceId"
                    :disabled="!!pending"
                    @click="edit(s)"
                  >
                    配置应用</button
                  ><button
                    v-if="admin"
                    class="btn small-btn"
                    :data-application-collect="s.deviceId"
                    :disabled="!!pending || s.revision === 0"
                    @click="mutate(s, 'collect', { revision: s.revision })"
                  >
                    {{
                      pending === `${s.deviceId}/collect`
                        ? "正在读取…"
                        : "立即采集"
                    }}</button
                  ><button
                    v-if="admin"
                    class="btn small-btn"
                    :data-application-state="s.deviceId"
                    :disabled="!!pending || s.revision === 0"
                    @click="
                      mutate(s, 'state', {
                        revision: s.revision,
                        enabled: !s.enabled,
                      })
                    "
                  >
                    {{ s.enabled ? "停用应用周期" : "启用应用周期" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div> </QueryState
    ><PagePager
      :pending="sources.isFetching.value"
      :page="sources.page.value"
      :count="sources.data.value?.items.length || 0"
      :previous="sources.hasPrevious.value"
      :next="!!sources.data.value?.nextCursor"
      @back="sources.back"
      @forward="sources.next"
    />
    <p class="wb-note">
      覆盖范围仅限明确选择的接口与本轮读到的应用；覆盖比例与采样率未提供，不推算全网总量。协议行上限包含所有所选接口，每行保留入站和出站两个方向。已有
      SNMP 连接不代表设备支持或已启用 NBAR。
    </p>
  </section>
  <section class="panel wb-spaced">
    <header class="panel-head">
      <h2>历史应用观测</h2>
      <span class="small muted"
        >固定时间窗口 · 最长 7 天 · 按 observedAt 筛选</span
      >
    </header>

    <template v-if="history.filters.value"
      ><p class="wb-note">
        已提交观测范围 {{ history.filters.value.from }} 至
        {{ history.filters.value.to }}。此数据不提供客户端 IP、会话数或 NAT
        映射；原始累计字节和包数不作为速率。
      </p>
      <QueryState
        :pending="history.isPending.value"
        :error="history.error.value"
        :empty="!history.data.value?.items.length"
        empty-title="当前范围没有保留的应用观测"
        empty-description="查看来源状态，核对 ifIndex、设备 NBAR 配置与时间范围；空结果不表示零流量。"
        @retry="history.refetch()"
        ><div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>接口 / 应用</th>
                <th>方向 / 观测时间 {{ prefs.timezone }}</th>
                <th>设备累计计数（字节）</th>
                <th>设备累计包数</th>
                <th>设备报告速率</th>
                <th>平台差分速率</th>
                <th>质量 / 来源</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="o in history.data.value?.items"
                :key="o.id"
                :data-application-observation="o.id"
              >
                <td>
                  <strong>{{ o.application }}</strong>
                  <p class="secondary-line">
                    {{ o.interfaceName || "接口名称未知" }} · ifIndex
                    {{ o.interfaceIndex }}
                  </p>
                  <p class="secondary-line">协议索引 {{ o.protocolIndex }}</p>
                </td>
                <td class="small">
                  {{ o.direction === "IN" ? "入站 IN" : "出站 OUT" }}
                  <p>{{ formatTime(o.observedAt, prefs.timezone) }}</p>
                </td>
                <td
                  class="mono small"
                  data-bytes
                  :title="
                    o.bytes == null ? '未提供累计字节' : `${o.bytes} bytes`
                  "
                >
                  {{ formatBytes(o.bytes) }}
                </td>
                <td
                  class="mono small"
                  data-packets
                  :title="
                    o.packets == null ? '未提供累计包数' : `${o.packets} 包`
                  "
                >
                  {{ formatCount(o.packets) }}
                </td>
                <td data-reported-rate>{{ formatRate(o.reportedBps) }}</td>
                <td>
                  <span data-derived-rate>{{ formatRate(o.derivedBps) }}</span>
                  <p class="secondary-line">
                    {{ shortNumber(o.derivedPacketsPerSecond) }} 包/秒 · 间隔
                    {{ o.intervalSeconds ?? "—" }} 秒
                  </p>
                </td>
                <td class="small">
                  <p>{{ qualityText(o.qualityFlags) || "无附加质量标记" }}</p>
                  <button class="btn small-btn" @click="detail = o">
                    查看来源记录
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div></QueryState
      >
      <div class="wb-tools">
        <span class="small muted"
          >第 {{ history.page.value }} 页 · 本页
          {{ history.data.value?.items.length || 0 }}
          个方向观测行；不是流数或会话数</span
        ><button
          class="btn small-btn"
          :disabled="!history.hasPrevious.value || history.isFetching.value"
          @click="history.back"
        >
          上一页</button
        ><button
          class="btn small-btn"
          data-application-next
          :disabled="
            !history.data.value?.nextCursor || history.isFetching.value
          "
          @click="history.next"
        >
          下一页
        </button>
      </div></template
    >
    <p v-else class="wb-note">
      选择设备并提交时间范围，读取实际持久化的应用观测。
    </p>
    <p class="wb-note">
      设备报告速率保留设备速率对象口径，设备内部窗口未由 API
      提供。平台差分只使用相邻成功持久化样本；首次、重启、身份或凭据版本变化、计数下降、间隔过长时为
      —，不会补零或猜测回绕。
    </p>
  </section>
  <ModalDialog
    :open="!!editing"
    title="配置独立应用监测"
    @close="editing = null"
    ><form
      v-if="editing"
      class="wb-form"
      data-application-settings
      @submit.prevent="save"
    >
      <p>
        <strong>{{ editing.deviceName }}</strong> · 应用版本
        {{ editing.revision }} · SNMP 凭据版本 {{ editing.credentialRevision }}
      </p>
      <div class="form-field">
        <label for="application-ifindices">明确选择接口 ifIndex</label
        ><input
          id="application-ifindices"
          v-model="ifIndices"
          class="input mono"
          required
          placeholder="例如 8, 16；1–8 个互异正整数"
        /><small class="muted"
          >这是设备 SNMP ifIndex，不是平台接口
          UUID。请按设备实际接口索引填写，不自动选择前几个接口。</small
        >
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label for="application-interval">采集周期 · 秒</label
          ><input
            id="application-interval"
            v-model.number="intervalSeconds"
            class="input"
            type="number"
            min="30"
            max="3600"
            required
          />
        </div>
        <div class="form-field">
          <label for="application-maxrows">每轮协议行上限</label
          ><input
            id="application-maxrows"
            v-model.number="maxRows"
            class="input"
            type="number"
            min="1"
            max="256"
            required
          />
        </div>
      </div>
      <label class="wb-inline"
        ><input
          id="application-enabled"
          v-model="enabled"
          type="checkbox"
        />启用独立应用周期</label
      >
      <p class="wb-note">
        复用当前 SNMP 连接的加密凭据与目标，无需重复填写秘密。基础 SNMP
        周期停用不影响应用调度；应用开关不会启用路由器上的协议发现，也不会改动设备配置。
      </p>
      <p v-if="error" class="wb-error" role="alert">{{ error }}</p>
      <div class="wb-inline">
        <button class="btn primary" :disabled="!!pending">保存应用设置</button
        ><button
          class="btn"
          type="button"
          :disabled="!!pending"
          @click="
            edit(
              sources.data.value?.items.find(
                (s) => s.deviceId === editing?.deviceId,
              ) || editing,
            )
          "
        >
          重新载入当前配置
        </button>
      </div>
    </form></ModalDialog
  >
  <ModalDialog
    :open="!!detail"
    title="NBAR 应用观测来源记录"
    wide
    @close="detail = null"
    ><div class="wb-form">
      <p class="wb-note">
        原始 Counter64 以十进制字符串保留精度；sourceEpoch
        标识差分基线。应用名称为 NBAR 分类，不是完整 URL 或用户行为。
      </p>
      <pre class="wb-raw">{{ JSON.stringify(detail, null, 2) }}</pre>
    </div></ModalDialog
  >
</template>

<style scoped>
.application-filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
.application-filter-grid .form-field:last-child {
  grid-column: span 2;
}
.application-filter-grid .form-field {
  min-width: 0;
}
.application-filter-grid .input {
  width: 100%;
  min-width: 0;
}
.application-query {
  padding-bottom: 8px;
}
.application-refresh {
  display: flex;
  justify-content: flex-end;
  padding: 0 20px 16px;
}
.application-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  padding: 20px;
  border-top: 1px solid var(--noeriva-border);
}
.application-metrics > div {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-right: 12px;
}
.application-metrics span,
.application-metrics small {
  color: var(--noeriva-muted);
  font-size: 12px;
}
.application-metrics strong {
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.application-charts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  padding: 0 20px;
}
.application-charts > div {
  min-width: 0;
}
.application-charts h3 {
  font-size: 14px;
  margin: 12px 0 4px;
}
@media (max-width: 1050px) {
  .application-filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .application-charts {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  .application-filter-grid .form-field:last-child {
    grid-column: auto;
  }
  .application-filter-grid {
    grid-template-columns: 1fr;
  }
  .application-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .application-metrics strong {
    font-size: 20px;
  }
}
</style>
