<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Plus, RefreshCw } from "@lucide/vue";
import { useApiQuery } from "../services/queries";
import {
  usePagedQuery,
  useWorkbenchAction,
  type Incident,
  type IncidentSummary,
} from "../services/workbench";
import { usePreferencesStore } from "../stores/preferences";
import { useSessionStore } from "../stores/session";
import { formatTime } from "../utils/format";
import QueryState from "../components/QueryState.vue";
import StatusBadge from "../components/StatusBadge.vue";
import ModalDialog from "../components/ModalDialog.vue";
import DevicePicker from "../components/DevicePicker.vue";
import PagePager from "../components/PagePager.vue";
const incidentStates: Record<string, string> = {
  OPEN: "待处理",
  INVESTIGATING: "调查中",
  RESOLVED: "已解决",
};
const route = useRoute(),
  router = useRouter(),
  prefs = usePreferencesStore(),
  auth = useSessionStore();
const status = ref(""),
  selected = ref(String(route.params.id || route.query.id || "")),
  createOpen = ref(route.query.create === "1");
const title = ref(""),
  deviceId = ref(String(route.query.deviceId || "")),
  alertId = ref(String(route.query.alertId || "")),
  severity = ref("WARNING"),
  note = ref(""),
  assignee = ref("");
const editRevision = ref(0);
const updateTitle = ref(""),
  updateStatus = ref("INVESTIGATING"),
  updateAssignee = ref(""),
  updateNote = ref("");
const list = usePagedQuery<IncidentSummary>(
  "/workbench/incidents",
  computed(() => ({
    status: status.value,
    deviceId: String(route.query.deviceId || ""),
  })),
);
const detail = useApiQuery<Incident>(
  computed(() => `/workbench/incidents/${encodeURIComponent(selected.value)}`),
  () => !!selected.value,
);
const action = useWorkbenchAction();
watch(
  () => detail.data.value?.id,
  () => {
    const v = detail.data.value;
    if (v) {
      editRevision.value = v.revision;
      updateTitle.value = v.title;
      updateStatus.value = v.status;
      updateAssignee.value = v.assignee || "";
    }
  },
);
watch(
  () => route.params.id || route.query.id,
  (v) => (selected.value = String(v || "")),
);
function open(id: string) {
  selected.value = id;
  updateNote.value = "";
  action.error.value = "";
  void router.replace({ query: { ...route.query, create: undefined, id } });
}
function closeCreate() {
  createOpen.value = false;
  if (route.query.create !== undefined)
    void router.replace({ query: { ...route.query, create: undefined } });
}
async function create() {
  const value = await action.run<Incident>("/workbench/incidents", {
    title: title.value,
    severity: severity.value,
    deviceId: deviceId.value,
    alertId: alertId.value || null,
    assignee: assignee.value || null,
    note: note.value,
  });
  if (value) {
    createOpen.value = false;
    open(value.id);
    title.value = "";
    note.value = "";
    action.notice.value = "事件单已创建并保存。";
  }
}
async function update() {
  const d = detail.data.value;
  if (!d) return;
  const value = await action.run<Incident>(
    `/workbench/incidents/${d.id}/updates`,
    {
      revision: editRevision.value,
      title: updateTitle.value,
      status: updateStatus.value,
      assignee: updateAssignee.value || null,
      note: updateNote.value,
    },
  );
  if (value) {
    editRevision.value = value.revision;
    updateNote.value = "";
    action.notice.value = "处置记录已保存。";
  } else {
    const current = (await detail.refetch()).data;
    if (current) {
      editRevision.value = current.revision;
      updateTitle.value = current.title;
      updateStatus.value = current.status;
      updateAssignee.value = current.assignee || "";
    }
  }
}
watch(
  [
    () => route.query.deviceId,
    () => route.query.alertId,
    () => route.query.create,
  ],
  () => {
    deviceId.value = String(route.query.deviceId || "");
    alertId.value = String(route.query.alertId || "");
    if (route.query.create === "1") createOpen.value = true;
  },
);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>事件工作台</h1>
      <p>让时间顺序、关联证据和待验证假设各自清晰。</p>
    </div>
    <div class="wb-inline">
      <button class="btn" @click="list.refetch()">
        <RefreshCw :size="14" />刷新</button
      ><button
        v-if="auth.canWrite"
        class="btn primary"
        @click="createOpen = true"
      >
        <Plus :size="14" />创建事件
      </button>
    </div>
  </div>
  <p v-if="action.notice.value" class="notice" role="status">
    {{ action.notice.value }}
  </p>
  <p v-if="action.error.value" class="wb-error" role="alert">
    {{ action.error.value }}
  </p>
  <section class="panel">
    <div class="wb-tools">
      <label for="incident-status">事件状态</label
      ><select id="incident-status" v-model="status" class="input">
        <option value="">全部状态</option>
        <option value="OPEN">待处理</option>
        <option value="INVESTIGATING">调查中</option>
        <option value="RESOLVED">已解决</option></select
      ><span class="small muted">每次处置保留操作者与版本</span>
    </div>
    <QueryState
      :pending="list.isPending.value"
      :error="list.error.value"
      :empty="!list.data.value?.items.length"
      empty-title="尚无事件单"
      empty-description="从告警或设备创建事件，记录调查和处置过程。"
      @retry="list.refetch()"
      ><div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>事件 / 编号</th>
              <th>严重度</th>
              <th>状态</th>
              <th>负责人</th>
              <th>更新时间</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in list.data.value?.items"
              :key="item.id"
              :class="{ 'wb-select-row': selected === item.id }"
            >
              <td>
                <button class="wb-source-button" @click="open(item.id)">
                  {{ item.title }}
                </button>
                <p class="secondary-line mono">{{ item.id.slice(0, 8) }}</p>
              </td>
              <td><StatusBadge :status="item.severity" /></td>
              <td>{{ incidentStates[item.status] }}</td>
              <td>{{ item.assignee || "尚未分配" }}</td>
              <td class="mono small">
                {{ formatTime(item.updatedAt, prefs.timezone) }}
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
  <QueryState
    v-if="selected"
    :pending="detail.isPending.value"
    :error="detail.error.value"
    @retry="detail.refetch()"
    ><div v-if="detail.data.value" class="wb-grid wb-spaced">
      <div class="wb-stack">
        <section class="panel">
          <header class="panel-head">
            <h2>{{ detail.data.value.title }}</h2>
            <span class="small muted"
              >版本 {{ detail.data.value.revision }}</span
            >
          </header>
          <div class="panel-body">
            <ol class="wb-timeline">
              <li>
                <time>{{
                  formatTime(detail.data.value.createdAt, prefs.timezone)
                }}</time>
                <p>{{ detail.data.value.createdBy }} 创建了事件单</p>
              </li>
              <li v-for="entry in detail.data.value.notes" :key="entry.id">
                <time
                  >{{ formatTime(entry.createdAt, prefs.timezone) }} ·
                  {{ entry.author }}</time
                >
                <p>{{ entry.text }}</p>
              </li>
            </ol>
            <p class="wb-note">
              记录观察事实、关联与假设时请注明依据。时间接近并不证明因果。
            </p>
          </div>
        </section>
        <section v-if="auth.canWrite" class="panel">
          <header class="panel-head"><h2>更新处置</h2></header>
          <form class="wb-form" @submit.prevent="update">
            <div class="form-field">
              <label for="update-title">事件标题</label
              ><input
                id="update-title"
                v-model="updateTitle"
                class="input"
                required
                maxlength="200"
              />
            </div>
            <div class="form-grid">
              <div class="form-field">
                <label for="update-status">处置状态</label
                ><select
                  id="update-status"
                  v-model="updateStatus"
                  class="input"
                >
                  <option value="OPEN">待处理</option>
                  <option value="INVESTIGATING">调查中</option>
                  <option value="RESOLVED">已解决</option>
                </select>
              </div>
              <div class="form-field">
                <label for="update-owner">负责人账号</label
                ><input
                  id="update-owner"
                  v-model="updateAssignee"
                  class="input"
                  placeholder="当前组织账号，可留空"
                />
              </div>
            </div>
            <div class="form-field">
              <label for="update-note">处置记录 / 待验证假设</label
              ><textarea
                id="update-note"
                v-model="updateNote"
                class="input"
                required
                maxlength="2000"
                placeholder="记录检查内容、观察结果和下一步；解决时填写恢复依据。"
              />
            </div>
            <div class="actions">
              <button class="btn primary" :disabled="action.pending.value">
                保存处置记录
              </button>
            </div>
          </form>
        </section>
      </div>
      <aside class="wb-stack">
        <section class="panel">
          <header class="panel-head"><h2>事件上下文</h2></header>
          <div class="panel-body">
            <dl class="wb-facts">
              <dt>关联设备</dt>
              <dd>
                <RouterLink :to="`/devices/${detail.data.value.deviceId}`"
                  >打开设备详情</RouterLink
                >
              </dd>
              <dt>告警记录</dt>
              <dd>{{ detail.data.value.alertId || "独立事件" }}</dd>
              <dt>负责人</dt>
              <dd>{{ detail.data.value.assignee || "尚未分配" }}</dd>
              <dt>开始时间</dt>
              <dd>
                {{ formatTime(detail.data.value.createdAt, prefs.timezone) }}
              </dd>
            </dl>
          </div>
        </section>
        <section class="panel">
          <header class="panel-head"><h2>证据资料</h2></header>
          <div class="panel-body">
            <p class="small muted">查看同一设备的来源与保存的证据记录。</p>
            <RouterLink
              :to="{
                path: '/evidence',
                query: { deviceId: detail.data.value.deviceId },
              }"
              >进入证据资料 ↗</RouterLink
            >
          </div>
        </section>
      </aside>
    </div></QueryState
  >
  <ModalDialog :open="createOpen" title="创建事件单" @close="closeCreate"
    ><form class="wb-form" @submit.prevent="create">
      <div class="form-field">
        <label for="incident-title">事件标题</label
        ><input
          id="incident-title"
          v-model="title"
          class="input"
          required
          maxlength="200"
        />
      </div>
      <div class="form-field">
        <label for="incident-device">关联设备</label
        ><DevicePicker id="incident-device" v-model="deviceId" />
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label for="incident-severity">严重度</label
          ><select id="incident-severity" v-model="severity" class="input">
            <option value="INFO">信息</option>
            <option value="WARNING">警告</option>
            <option value="CRITICAL">严重</option>
          </select>
        </div>
        <div class="form-field">
          <label for="incident-owner">负责人账号</label
          ><input
            id="incident-owner"
            v-model="assignee"
            class="input"
            placeholder="可留空"
          />
        </div>
      </div>
      <div class="form-field">
        <label for="incident-alert">关联告警 ID（可选）</label
        ><input id="incident-alert" v-model="alertId" class="input" />
      </div>
      <div class="form-field">
        <label for="incident-note">初始观察</label
        ><textarea
          id="incident-note"
          v-model="note"
          class="input"
          maxlength="2000"
        />
      </div>
      <p v-if="action.error.value" class="wb-error" role="alert">
        {{ action.error.value }}
      </p>
      <div class="actions">
        <button type="button" class="btn" @click="closeCreate">取消</button
        ><button
          class="btn primary"
          :disabled="action.pending.value || !deviceId"
        >
          保存事件单
        </button>
      </div>
    </form></ModalDialog
  >
</template>
