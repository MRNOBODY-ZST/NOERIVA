<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { Plus, Download, RefreshCw } from "@lucide/vue";
import { useApiQuery } from "../services/queries";
import { useDeviceNames } from "../services/deviceNames";
import { request, queryString } from "../services/api";
import {
  usePagedQuery,
  useWorkbenchAction,
  utcInputNow,
  fromUtcInput,
  downloadJson,
  type Snapshot,
  type ConfigurationDiff,
} from "../services/workbench";
import { usePreferencesStore } from "../stores/preferences";
import { useSessionStore } from "../stores/session";
import { formatTime } from "../utils/format";
import QueryState from "../components/QueryState.vue";
import ModalDialog from "../components/ModalDialog.vue";
import DevicePicker from "../components/DevicePicker.vue";
import PagePager from "../components/PagePager.vue";
const route = useRoute(),
  prefs = usePreferencesStore(),
  auth = useSessionStore(),
  action = useWorkbenchAction();
const filter = ref(String(route.query.deviceId || "")),
  deviceId = ref(filter.value),
  title = ref(""),
  source = ref("manual-snapshot"),
  capturedAt = ref(utcInputNow()),
  content = ref(""),
  createOpen = ref(false),
  before = ref(""),
  after = ref(""),
  diff = ref<ConfigurationDiff | null>(null),
  busy = ref(false),
  diffError = ref("");
const list = usePagedQuery<Snapshot>(
  "/workbench/configuration/snapshots",
  computed(() => ({ deviceId: filter.value })),
);
const comparisons = computed(() => list.data.value?.items || []);
const retainedSnapshots = ref(new Map<string, Snapshot>());
watch(
  comparisons,
  (items) => {
    const retained = new Map<string, Snapshot>();
    for (const id of [before.value, after.value]) {
      const value = retainedSnapshots.value.get(id);
      if (value) retained.set(id, value);
    }
    for (const item of items) retained.set(item.id, item);
    retainedSnapshots.value = retained;
  },
  { immediate: true },
);
const deviceName = useDeviceNames(() =>
  [...retainedSnapshots.value.values()].map((snapshot) => snapshot.deviceId),
);
const sourceNames: Record<string, string> = {
  SNMP_DEVICE_BASELINE: "SNMP 设备状态基线",
  SSH_RUNNING_CONFIGURATION: "SSH 运行配置",
  SSH_DEVICE_BASELINE: "SSH 身份与接口基线",
  REDFISH_DEVICE_BASELINE: "Redfish 身份与接口基线",
  "manual-snapshot": "人工导入",
};
const sourceName = (source: string) => sourceNames[source] || "设备配置";
const snapshotTitle = (snapshot: Snapshot) =>
  snapshot.title.startsWith("自动同步 · ")
    ? `自动同步 · ${sourceName(snapshot.source)}`
    : snapshot.title;
function snapshotLabel(id: string) {
  const snapshot = retainedSnapshots.value.get(id);
  return snapshot
    ? `${deviceName(snapshot.deviceId)} · ${formatTime(snapshot.capturedAt, prefs.timezone)} · ${sourceName(snapshot.source)}`
    : "已选快照（详情未加载）";
}
interface CaptureState {
  status: string;
  message: string;
  capturedAt: string | null;
  lastAttemptAt?: string | null;
  snapshot?: Snapshot | null;
}
const capture = useApiQuery<CaptureState>(
  computed(
    () =>
      `/workbench/configuration/devices/${encodeURIComponent(filter.value)}/capture`,
  ),
  () => !!filter.value,
);
const selected = ref<Snapshot | null>(null),
  detailPending = ref(false),
  detailError = ref("");
async function sync() {
  if (!filter.value) return;
  const value = await action.run<CaptureState>(
    `/workbench/configuration/devices/${encodeURIComponent(filter.value)}/capture`,
    {},
  );
  if (value) {
    action.notice.value = value.message || `配置同步：${value.status}`;
    await capture.refetch();
    await list.refetch();
  }
}
async function inspect(snapshot: Snapshot) {
  detailPending.value = true;
  detailError.value = "";
  selected.value = snapshot;
  try {
    selected.value = await request<Snapshot>(
      `/workbench/configuration/snapshots/${encodeURIComponent(snapshot.id)}`,
    );
  } catch (e) {
    detailError.value = e instanceof Error ? e.message : "快照读取失败";
  } finally {
    detailPending.value = false;
  }
}
watch(filter, () => {
  before.value = "";
  after.value = "";
  diff.value = null;
});

async function compare() {
  busy.value = true;
  diffError.value = "";
  diff.value = null;
  try {
    diff.value = await request<ConfigurationDiff>(
      `/workbench/configuration/diff${queryString({ before: before.value, after: after.value })}`,
    );
  } catch (e) {
    diffError.value = e instanceof Error ? e.message : "比较失败";
  } finally {
    busy.value = false;
  }
}
async function create() {
  try {
    const v = await action.run<Snapshot>("/workbench/configuration/snapshots", {
      title: title.value,
      deviceId: deviceId.value,
      source: source.value,
      capturedAt: fromUtcInput(capturedAt.value),
      content: content.value,
      provenance: "MANUAL",
    });
    if (v) {
      createOpen.value = false;
      title.value = "";
      content.value = "";
      action.notice.value = `快照已保存，服务端脱敏 ${v.redactedLines} 行。`;
    }
  } catch (e) {
    action.error.value = e instanceof Error ? e.message : "时间无效";
  }
}
watch(
  () => route.query.deviceId,
  (value) => {
    filter.value = String(value || "");
    deviceId.value = filter.value;
    before.value = "";
    after.value = "";
    diff.value = null;
  },
);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>配置与变更</h1>
      <p>只读快照与差异审阅，保留具体修改和来源。</p>
    </div>
    <button
      v-if="auth.session?.roles.includes('ADMIN')"
      class="btn primary"
      @click="createOpen = true"
    >
      <Plus :size="14" />导入快照
    </button>
  </div>
  <p v-if="action.notice.value" class="notice" role="status">
    {{ action.notice.value }}
  </p>
  <section class="panel">
    <div class="wb-tools">
      <label for="configuration-filter">观察设备</label
      ><DevicePicker id="configuration-filter" v-model="filter" /><button
        v-if="auth.session?.roles.includes('ADMIN')"
        class="btn primary"
        :disabled="!filter || action.pending.value"
        @click="sync"
      >
        <RefreshCw :size="14" />{{
          action.pending.value ? "正在同步…" : "同步设备配置"
        }}</button
      ><button
        class="btn"
        :disabled="list.isFetching.value"
        @click="
          list.refetch();
          filter && capture.refetch();
        "
      >
        刷新快照
      </button>
    </div>
    <div v-if="filter" class="wb-note" role="status">
      <template v-if="capture.isPending.value">正在读取同步状态…</template
      ><template v-else-if="capture.error.value">{{
        capture.error.value.message
      }}</template
      ><template v-else
        >{{ capture.data.value?.status || "尚未同步" }} ·
        {{
          capture.data.value?.message || "可使用已保存的设备连接捕获只读配置。"
        }}<span v-if="capture.data.value?.capturedAt">
          · 最近捕获
          {{ formatTime(capture.data.value.capturedAt, prefs.timezone) }}</span
        ></template
      >
    </div>
    <p v-else class="wb-note">
      选择设备查看同步状态或立即捕获。自动同步开关与周期可在系统设置中管理。
    </p>
    <p v-if="action.error.value && !createOpen" class="wb-error" role="alert">
      {{ action.error.value }}
    </p>
    <header class="panel-head">
      <h2>配置快照</h2>
      <span class="small muted">服务端脱敏 · 只读</span>
    </header>
    <QueryState
      :pending="list.isPending.value"
      :error="list.error.value"
      :empty="!list.data.value?.items.length"
      empty-title="尚无配置快照"
      empty-description="选择设备同步只读配置，或导入配置文本。设备未支持的读取方式会显示具体原因。"
      @retry="list.refetch()"
      ><div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>快照</th>
              <th>设备</th>
              <th>捕获时间</th>
              <th>来源</th>
              <th>脱敏</th>
              <th>比较</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in list.data.value?.items" :key="item.id">
              <td>
                {{ snapshotTitle(item) }}
                <p class="secondary-line mono">
                  {{ item.sha256.slice(0, 16) }}…
                </p>
              </td>
              <td>
                <RouterLink :to="`/devices/${item.deviceId}`">{{
                  deviceName(item.deviceId)
                }}</RouterLink>
              </td>
              <td class="small mono">
                {{ formatTime(item.capturedAt, prefs.timezone) }}
              </td>
              <td>
                {{ sourceName(item.source) }}
                <p class="secondary-line">
                  {{
                    item.provenance === "SYNTHETIC"
                      ? "合成测试"
                      : item.provenance === "MANUAL"
                        ? "人工导入"
                        : "设备只读采集"
                  }}
                </p>
              </td>
              <td>{{ item.redactedLines }} 行</td>
              <td>
                <div class="wb-inline">
                  <button class="btn small-btn" @click="inspect(item)">
                    查看快照</button
                  ><button class="btn small-btn" @click="before = item.id">
                    设为之前</button
                  ><button class="btn small-btn" @click="after = item.id">
                    设为之后
                  </button>
                </div>
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
  <section class="panel wb-spaced">
    <form class="wb-tools" @submit.prevent="compare">
      <label for="snapshot-before">之前</label
      ><select id="snapshot-before" v-model="before" class="input" required>
        <option value="">选择快照</option>
        <option
          v-if="before && !comparisons.some((s) => s.id === before)"
          :value="before"
        >
          {{ snapshotLabel(before) }}
        </option>
        <option v-for="s in comparisons" :key="s.id" :value="s.id">
          {{ snapshotLabel(s.id) }}
        </option></select
      ><label for="snapshot-after">之后</label
      ><select id="snapshot-after" v-model="after" class="input" required>
        <option value="">选择快照</option>
        <option
          v-if="after && !comparisons.some((s) => s.id === after)"
          :value="after"
        >
          {{ snapshotLabel(after) }}
        </option>
        <option v-for="s in comparisons" :key="s.id" :value="s.id">
          {{ snapshotLabel(s.id) }}
        </option></select
      ><button class="btn primary" :disabled="busy || !before || !after">
        比较差异</button
      ><button
        v-if="diff"
        type="button"
        class="btn"
        @click="downloadJson(`configuration-diff-${diff.afterId}.json`, diff)"
      >
        <Download :size="14" />下载差异
      </button>
    </form>
    <p v-if="diffError" class="wb-error" role="alert">{{ diffError }}</p>
    <div v-if="busy" class="wb-note" role="status">正在读取并比较快照…</div>
    <template v-else-if="diff"
      ><div class="wb-tools small">
        <span>新增 {{ diff.added }} 行</span
        ><span>移除 {{ diff.removed }} 行</span
        ><span>未变 {{ diff.unchanged }} 行</span
        ><span v-if="!diff.added && !diff.removed">两个快照没有差异</span>
      </div>
      <div class="wb-diff" role="region" aria-label="配置逐行差异" tabindex="0">
        <div
          v-for="(line, index) in diff.lines"
          :key="index"
          class="wb-diff-row"
          :class="line.kind"
        >
          <span>{{ line.beforeLine ?? "" }}</span
          ><span>{{ line.afterLine ?? "" }}</span
          ><span>{{
            line.kind === "ADDED" ? "+" : line.kind === "REMOVED" ? "-" : " "
          }}</span
          ><span>{{ line.text }}</span>
        </div>
      </div></template
    >
    <p v-else class="wb-note">
      选择同一设备的两份快照查看差异。捕获时间与设备事件时间分别保留。
    </p>
  </section>
  <p class="wb-note wb-spaced">
    凭据行和私钥块在保存前由服务端脱敏。快照没有配置下发或设备恢复操作；不能仅凭变更时间与告警相近认定根因。
  </p>
  <ModalDialog
    :open="!!selected"
    title="配置快照详情"
    wide
    @close="selected = null"
    ><div v-if="selected" class="panel-body">
      <h3>{{ snapshotTitle(selected) }}</h3>
      <p class="wb-note">
        {{ deviceName(selected.deviceId) }} ·
        {{ sourceName(selected.source) }} · {{ selected.source }} ·
        {{ formatTime(selected.capturedAt, prefs.timezone) }} · SHA256
        {{ selected.sha256 }}
      </p>
      <p v-if="detailPending" role="status">正在读取脱敏配置…</p>
      <p v-else-if="detailError" class="wb-error" role="alert">
        {{ detailError }}
      </p>
      <template v-else>
        <pre class="raw-json">{{ selected.content || "快照为空" }}</pre>
        <button
          class="btn wb-spaced"
          @click="downloadJson(`configuration-${selected.id}.json`, selected)"
        >
          下载脱敏快照
        </button></template
      >
    </div></ModalDialog
  >
  <ModalDialog
    :open="createOpen"
    title="导入只读配置快照"
    wide
    @close="createOpen = false"
    ><form class="wb-form" @submit.prevent="create">
      <div class="form-field">
        <label for="config-title">快照标题</label
        ><input
          id="config-title"
          v-model="title"
          class="input"
          required
          maxlength="200"
        />
      </div>
      <div class="form-field">
        <label for="config-device">关联设备</label
        ><DevicePicker id="config-device" v-model="deviceId" />
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label for="config-source">来源说明</label
          ><input
            id="config-source"
            v-model="source"
            class="input"
            required
            maxlength="120"
          />
        </div>
        <div class="form-field">
          <label for="config-at">捕获时间 · UTC</label
          ><input
            id="config-at"
            v-model="capturedAt"
            type="datetime-local"
            step="1"
            class="input"
            required
          />
        </div>
      </div>
      <div class="form-field">
        <label for="config-content">配置文本</label
        ><textarea
          id="config-content"
          v-model="content"
          class="input mono"
          required
          maxlength="65536"
          style="min-height: 230px"
          placeholder="粘贴已脱敏的只读快照，最多 2000 行。"
        />
      </div>
      <p v-if="action.error.value" class="wb-error" role="alert">
        {{ action.error.value }}
      </p>
      <div class="actions">
        <button
          class="btn primary"
          :disabled="action.pending.value || !deviceId"
        >
          脱敏并保存快照
        </button>
      </div>
    </form></ModalDialog
  >
</template>
