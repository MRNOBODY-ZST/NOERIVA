<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import { Check, TriangleAlert, RefreshCw } from "@lucide/vue";
import { useApiQuery } from "../services/queries";
import { useAlertSummary } from "../services/alerts";
import { request, queryString, ApiError } from "../services/api";
import type { Alert, Page } from "../services/types";
import { usePreferencesStore } from "../stores/preferences";
import { useSessionStore } from "../stores/session";
import { formatTime, stateLabel } from "../utils/format";
import StatusBadge from "../components/StatusBadge.vue";
import QueryState from "../components/QueryState.vue";
import ModalDialog from "../components/ModalDialog.vue";
const prefs = usePreferencesStore(),
  auth = useSessionStore(),
  client = useQueryClient();
const route = useRoute();
const severity = ref(""),
  selected = ref<Alert | null>(null);
const initialState = () =>
  ["OPEN", "ACKNOWLEDGED", "RESOLVED", ""].includes(
    String(route.query.state ?? "OPEN"),
  )
    ? String(route.query.state ?? "OPEN")
    : "OPEN";
const state = ref(initialState()),
  notice = ref("");
watch(
  () => route.query.state,
  () => {
    state.value = initialState();
  },
);
const {
  data: summary,
  error: summaryError,
  refetch: refetchSummary,
} = useAlertSummary(() => String(route.query.deviceId || ""));
const stateNames: Record<string, string> = {
  OPEN: "待确认",
  ACKNOWLEDGED: "已确认未恢复",
  RESOLVED: "已恢复",
  "": "全部状态",
};
const emptyTitle = computed(() =>
  severity.value
    ? "当前筛选下没有匹配告警"
    : state.value === "OPEN"
      ? "没有待确认告警"
      : `没有${stateNames[state.value]}记录`,
);
const emptyDescription = computed(() =>
  summaryError.value
    ? "告警统计暂不可用。当前列表按所选条件独立查询，已确认未恢复的总数需待统计恢复后核实。"
    : state.value === "OPEN"
      ? summary.value?.acknowledged
        ? `另有 ${summary.value.acknowledged} 项已确认未恢复。确认表示已知悉，请切换该状态继续跟踪。`
        : "待确认队列为空；已确认与已恢复的记录可通过状态筛选查看。"
      : "调整状态或等级筛选查看其他记录。",
);
function refresh() {
  void refetch();
  void refetchSummary();
}
const { data, isPending, error, refetch } = useApiQuery<Page<Alert>>(
  computed(
    () =>
      `/alerts${queryString({ state: state.value, deviceId: String(route.query.deviceId || ""), limit: 50 })}`,
  ),
);
const filtered = computed(() =>
  (data.value?.items || []).filter(
    (alert) => !severity.value || alert.severity === severity.value,
  ),
);
const acknowledge = useMutation({
  mutationFn: (alert: Alert) =>
    request<Alert>(`/alerts/${encodeURIComponent(alert.id)}/acknowledge`, {
      method: "POST",
      body: JSON.stringify({ revision: alert.revision }),
    }),
  onSuccess: async (alert) => {
    notice.value = `已确认“${alert.title}”。问题状态未被标记为恢复。`;
    await client.invalidateQueries({ queryKey: ["noeriva"] });
  },
  onError: async (error) => {
    if (error instanceof ApiError && error.status === 409) await refetch();
  },
});
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>告警队列</h1>
      <p>先处理关键异常，再沿设备、接口与证据定位影响。</p>
    </div>
    <button class="btn" @click="refresh()">
      <RefreshCw aria-hidden="true" :size="14" /> 刷新
    </button>
  </div>
  <p v-if="summaryError" class="wb-error" role="alert">
    告警统计暂时不可用；<template v-if="summary"
      >保留上次成功时间
      {{
        formatTime(summary.asOf, prefs.timezone)
      }}
      的统计，当前数量未知。</template
    >下方列表仍可独立查询。
    <button class="btn small-btn" @click="refetchSummary()">重试统计</button>
  </p>
  <div
    v-if="summary"
    class="alert-summary"
    :aria-label="summaryError ? '上次成功告警统计' : '告警状态统计'"
  >
    <button
      v-for="item in [
        { state: 'OPEN', label: '待确认', count: summary.open },
        {
          state: 'ACKNOWLEDGED',
          label: '已确认未恢复',
          count: summary.acknowledged,
        },
        { state: 'RESOLVED', label: '已恢复', count: summary.resolved },
      ]"
      :key="item.state"
      class="btn alert-summary-item"
      :aria-pressed="state === item.state"
      @click="
        state = item.state;
        severity = '';
        notice = '';
      "
    >
      <span>{{ item.label }}<small v-if="summaryError"> · 上次统计</small></span
      ><strong>{{ item.count }}</strong>
    </button>
    <p class="small muted">
      {{ route.query.deviceId ? "当前设备" : "全工作区" }} · 未恢复
      {{ summary.active }} 项，含待确认与已确认。{{
        summaryError
          ? "以上为上次成功统计；侧栏问号表示当前数量未知。"
          : "侧栏仅显示待确认数量。"
      }}
      {{ summaryError ? "上次成功" : "统计时间" }}
      {{ formatTime(summary.asOf, prefs.timezone) }}。
    </p>
  </div>
  <div v-if="notice" role="status" class="notice" style="margin-bottom: 20px">
    {{ notice }}
  </div>
  <div v-if="acknowledge.error.value" role="alert" class="error-state">
    <strong>确认未完成</strong>
    <p>{{ acknowledge.error.value.message }}</p>
    <p
      v-if="
        acknowledge.error.value instanceof ApiError &&
        acknowledge.error.value.status === 409
      "
      class="small"
    >
      记录已变化，队列已重新读取。请检查当前状态。
    </p>
  </div>
  <section class="panel">
    <div class="section-toolbar">
      <label class="field-label" for="alert-state">状态</label
      ><select id="alert-state" v-model="state" @change="notice = ''">
        <option value="">全部状态</option>
        <option value="OPEN">待确认</option>
        <option value="ACKNOWLEDGED">已确认未恢复</option>
        <option value="RESOLVED">已恢复</option></select
      ><select v-model="severity" aria-label="告警严重程度">
        <option value="">全部等级</option>
        <option value="CRITICAL">仅严重</option>
        <option value="WARNING">仅警告</option></select
      ><span class="toolbar-spacer"></span
      ><span class="small muted">确认 ≠ 故障已解决</span>
    </div>
    <QueryState
      :pending="isPending"
      :error="error"
      :empty="!filtered.length"
      :empty-title="emptyTitle"
      :empty-description="emptyDescription"
      @retry="refetch()"
      ><div
        class="table-scroll"
        role="region"
        aria-label="异常列表，可横向滚动"
        tabindex="0"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>级别</th>
              <th>告警 / 影响对象</th>
              <th>开始时间</th>
              <th>状态</th>
              <th>处理</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="alert in filtered" :key="alert.id">
              <td><StatusBadge :status="alert.severity" /></td>
              <td class="text-wrap">
                <strong style="font-weight: 500">{{ alert.title }}</strong>
                <div class="secondary-line">
                  <RouterLink :to="`/assets/${alert.deviceId}`">{{
                    alert.deviceName
                  }}</RouterLink>
                </div>
              </td>
              <td class="small mono">
                {{ formatTime(alert.openedAt, prefs.timezone) }}
              </td>
              <td>
                <StatusBadge
                  :status="alert.state"
                  :label="stateNames[alert.state]"
                />
              </td>
              <td>
                <button
                  v-if="alert.state === 'OPEN' && auth.canWrite"
                  class="btn small-btn"
                  :disabled="acknowledge.isPending.value"
                  @click="acknowledge.mutate(alert)"
                >
                  <Check aria-hidden="true" :size="14" /> 确认异常</button
                ><span v-else-if="alert.acknowledgedBy" class="small muted"
                  >{{ alert.acknowledgedBy }} 已确认</span
                ><span v-else class="small muted">{{
                  auth.canWrite ? "无需确认" : "只读权限"
                }}</span
                ><button
                  class="btn small-btn"
                  style="margin-left: 8px"
                  @click="selected = alert"
                >
                  查看记录</button
                ><RouterLink
                  v-if="auth.canWrite"
                  class="btn small-btn"
                  style="margin-left: 8px"
                  :to="{
                    path: '/incidents',
                    query: {
                      deviceId: alert.deviceId,
                      alertId: alert.id,
                      create: '1',
                    },
                  }"
                  >创建事件</RouterLink
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div></QueryState
    >
    <footer class="panel-foot">
      <span
        ><TriangleAlert aria-hidden="true" :size="12" style="display: inline" />
        确认表示已知悉，告警恢复由状态机单独处理。</span
      ><span
        >{{ filtered.length }} / {{ data?.items.length || 0 }} 项 · 当前结果最多
        50 项</span
      >
    </footer>
  </section>
  <ModalDialog
    :open="!!selected"
    title="告警详情与处置记录"
    @close="selected = null"
    ><div v-if="selected" class="form-body">
      <h3>{{ selected.title }}</h3>
      <dl class="stack">
        <div
          v-for="(value, key) in {
            告警标识: selected.id,
            当前状态: stateNames[selected.state] || stateLabel(selected.state),
            确认人: selected.acknowledgedBy || '尚未确认',
            确认时间: selected.acknowledgedAt
              ? formatTime(selected.acknowledgedAt, prefs.timezone)
              : '尚未确认',
            当前修订: selected.revision,
          }"
          :key="key"
          class="key-value"
        >
          <dt>{{ key }}</dt>
          <dd>{{ value }}</dd>
        </div>
      </dl>
      <RouterLink
        class="btn"
        :to="{ path: '/incidents', query: { deviceId: selected.deviceId } }"
        >打开事件工作台</RouterLink
      >
    </div></ModalDialog
  >
</template>
