<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowRight, ArrowLeft, Eye, Search, RefreshCw } from "@lucide/vue";
import { useApiQuery } from "../services/queries";
import { useDeviceNames } from "../services/deviceNames";
import { queryString } from "../services/api";
import type { Event, Page } from "../services/types";
import { formatTime } from "../utils/format";
import { usePreferencesStore } from "../stores/preferences";
import StatusBadge from "../components/StatusBadge.vue";
import QueryState from "../components/QueryState.vue";
import ModalDialog from "../components/ModalDialog.vue";
const props = defineProps<{ deviceId?: string; embedded?: boolean }>();
const prefs = usePreferencesStore(),
  route = useRoute(),
  router = useRouter();
const deviceScope = computed(
  () => props.deviceId || String(route.query.deviceId || ""),
);
const cursors = ref<string[]>([]),
  search = ref(""),
  kind = ref(""),
  hours = ref("168"),
  rangeEnd = ref(new Date().toISOString());
watch([deviceScope, hours], () => {
  cursors.value = [];
});
const { data, isPending, error, refetch, isFetching } = useApiQuery<
  Page<Event>
>(
  () =>
    `/events${queryString({ deviceId: deviceScope.value, from: new Date(new Date(rangeEnd.value).getTime() - Number(hours.value) * 3600000).toISOString(), to: rangeEnd.value, limit: 25, cursor: cursors.value.at(-1) })}`,
);
const selected = ref<Event | null>(null);
const deviceName = useDeviceNames(() => [
  deviceScope.value,
  ...(data.value?.items || []).map((event) => event.deviceId),
]);
const kinds = computed(() => [
  ...new Set(data.value?.items.map((event) => event.kind) || []),
]);
const filtered = computed(() =>
  (data.value?.items || []).filter(
    (event) =>
      (!kind.value || event.kind === kind.value) &&
      `${event.id} ${event.deviceId} ${deviceName(event.deviceId)} ${event.message} ${event.source}`
        .toLowerCase()
        .includes(search.value.trim().toLowerCase()),
  ),
);
watch(
  () => data.value?.items,
  (items) => {
    const eventId = String(route.query.eventId || "");
    if (eventId)
      selected.value = items?.find((event) => event.id === eventId) || null;
  },
);
function closeSource() {
  selected.value = null;
  if (route.query.eventId) {
    const query = { ...route.query };
    delete query.eventId;
    void router.replace({ query });
  }
}
function refresh() {
  rangeEnd.value = new Date().toISOString();
  cursors.value = [];
  void refetch();
}
</script>
<template>
  <div v-if="!embedded" class="page-heading">
    <div>
      <h1>事件流</h1>
      <p>筛选基础设施事件，核对时间、来源与实体上下文。</p>
    </div>
    <div class="page-actions">
      <select v-model="hours" aria-label="事件时间范围">
        <option value="1">最近 1 小时</option>
        <option value="24">最近 24 小时</option>
        <option value="168">最近 7 天</option>
        <option value="720">最近 30 天</option></select
      ><button class="btn" @click="refresh">
        <RefreshCw aria-hidden="true" :size="14" />刷新
      </button>
    </div>
  </div>
  <section class="panel">
    <header v-if="embedded || deviceScope" class="panel-head">
      <div>
        <h2>{{ embedded ? "设备活动" : "设备事件" }}</h2>
        <p>{{ deviceName(deviceScope) }} · {{ prefs.timezone }}</p>
      </div>
      <RouterLink v-if="deviceScope && !embedded" to="/events" class="small"
        >清除设备范围</RouterLink
      >
    </header>
    <div class="section-toolbar">
      <div class="search-field">
        <Search aria-hidden="true" :size="16" /><input
          v-model="search"
          class="input"
          aria-label="筛选当前页事件"
          placeholder="筛选当前页事件、设备或记录 ID"
        />
      </div>
      <select v-model="kind" aria-label="筛选当前页事件类别">
        <option value="">全部类别</option>
        <option v-for="item in kinds" :key="item" :value="item">
          {{ item }}
        </option></select
      ><span class="toolbar-spacer"></span
      ><button
        class="btn ghost"
        @click="
          search = '';
          kind = '';
        "
      >
        重置
      </button>
    </div>
    <div class="density-strip">
      <span
        >当前页筛选 {{ filtered.length }} /
        {{ data?.items.length || 0 }} 条</span
      ><span>历史按时间范围与设备查询 · 名称和类别筛选仅作用于当前页</span>
    </div>
    <QueryState
      :pending="isPending"
      :error="error"
      :empty="!filtered.length"
      empty-title="当前范围内没有匹配事件"
      empty-description="可调整时间、设备或当前页筛选；没有记录不能排除采集缺口。"
      @retry="refetch()"
      ><div
        class="table-scroll"
        role="region"
        aria-label="事件记录，可横向滚动"
        tabindex="0"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>观测时间</th>
              <th>类别</th>
              <th>实体</th>
              <th>内容</th>
              <th>等级 / 来源</th>
              <th>来源记录</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="event in filtered" :key="event.id">
              <td class="mono small">
                {{ formatTime(event.observedAt, prefs.timezone) }}
              </td>
              <td>{{ event.kind }}</td>
              <td>
                <RouterLink
                  :to="`/assets/${event.deviceId}`"
                  :title="deviceName(event.deviceId)"
                  >{{ deviceName(event.deviceId) }}</RouterLink
                >
              </td>
              <td class="text-wrap">{{ event.message }}</td>
              <td>
                <StatusBadge :status="event.severity" />
                <div class="secondary-line">{{ event.source }}</div>
              </td>
              <td>
                <button
                  class="btn small-btn"
                  :aria-label="`检查事件 ${event.id} 的来源`"
                  @click="selected = event"
                >
                  <Eye aria-hidden="true" :size="14" />查看来源
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div></QueryState
    >
    <footer class="panel-foot">
      <span
        >{{ data?.items.length || 0 }} 条 · 第 {{ cursors.length + 1 }} 页 ·
        {{ prefs.timezone }}</span
      >
      <div class="inline-actions">
        <button
          class="btn small-btn"
          :disabled="!cursors.length || isFetching"
          @click="cursors.pop()"
        >
          <ArrowLeft aria-hidden="true" :size="13" />上一页</button
        ><button
          class="btn small-btn"
          :disabled="!data?.nextCursor || isFetching"
          @click="data?.nextCursor && cursors.push(data.nextCursor)"
        >
          下一页<ArrowRight aria-hidden="true" :size="13" />
        </button>
      </div>
    </footer>
  </section>
  <ModalDialog :open="!!selected" title="事件来源检查" @close="closeSource"
    ><div v-if="selected" class="form-body">
      <div class="notice">
        <strong>来源记录</strong> · {{ selected.source }}
        <p style="margin-top: 6px">{{ selected.message }}</p>
      </div>
      <dl class="stack">
        <div
          v-for="(value, key) in {
            事件标识: selected.id,
            设备: selected.deviceId,
            类型: selected.kind,
            观测时间: formatTime(selected.observedAt, prefs.timezone),
            接收时间: formatTime(selected.ingestedAt, prefs.timezone),
            质量标记: selected.qualityFlags?.join(' · ') || '无附加质量标记',
          }"
          :key="key"
          class="key-value"
        >
          <dt>{{ key }}</dt>
          <dd>{{ value }}</dd>
        </div>
      </dl>
      <details>
        <summary class="small">查看原始 JSON</summary>
        <pre class="raw-json">{{ JSON.stringify(selected, null, 2) }}</pre>
      </details>
      <RouterLink
        class="btn"
        :to="{ path: '/evidence', query: { deviceId: selected.deviceId } }"
        >查看设备证据资料</RouterLink
      >
      <p class="small muted">
        源时间 {{ selected.observedAt }}。该事件的存在不等于已确认根因。
      </p>
    </div></ModalDialog
  >
</template>
