<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { Plus, RefreshCw } from "@lucide/vue";
import { queryString } from "../services/api";
import { useApiQuery } from "../services/queries";
import {
  usePagedQuery,
  useWorkbenchAction,
  type CheckDefinition,
  type CheckResult,
} from "../services/workbench";
import type { Page } from "../services/types";
import { usePreferencesStore } from "../stores/preferences";
import { useSessionStore } from "../stores/session";
import { formatTime, formatLatency } from "../utils/format";
import QueryState from "../components/QueryState.vue";
import ModalDialog from "../components/ModalDialog.vue";
import DevicePicker from "../components/DevicePicker.vue";
import PagePager from "../components/PagePager.vue";
const prefs = usePreferencesStore(),
  auth = useSessionStore(),
  route = useRoute(),
  action = useWorkbenchAction();
const q = ref(""),
  selected = ref<CheckDefinition | null>(null),
  editorOpen = ref(false),
  editing = ref<CheckDefinition | null>(null),
  name = ref(""),
  deviceId = ref(String(route.query.deviceId || "")),
  type = ref("TCP"),
  target = ref(""),
  intervalSeconds = ref(60),
  enabled = ref(true);
const list = usePagedQuery<CheckDefinition>(
  "/workbench/checks",
  computed(() => ({
    q: q.value,
    deviceId: String(route.query.deviceId || ""),
  })),
);
const resultCursors = ref<string[]>([]),
  archiving = ref<CheckDefinition | null>(null);
watch(
  () => selected.value?.id,
  () => {
    resultCursors.value = [];
  },
);
const results = useApiQuery<Page<CheckResult>>(
  computed(
    () =>
      `/workbench/checks/${selected.value?.id}/results${queryString({ limit: 30, cursor: resultCursors.value.at(-1) })}`,
  ),
  () => !!selected.value,
);
function editor(item?: CheckDefinition) {
  editing.value = item || null;
  name.value = item?.name || "";
  deviceId.value = item?.deviceId || String(route.query.deviceId || "");
  type.value = item?.type || "TCP";
  target.value = item?.target || "";
  intervalSeconds.value = item?.intervalSeconds || 60;
  enabled.value = item?.enabled ?? true;
  action.error.value = "";
  editorOpen.value = true;
}
async function save() {
  const body = {
    name: name.value,
    target: target.value,
    intervalSeconds: Number(intervalSeconds.value),
    enabled: enabled.value,
  };
  const item = await action.run<CheckDefinition>(
    editing.value
      ? `/workbench/checks/${editing.value.id}/updates`
      : "/workbench/checks",
    editing.value
      ? { ...body, revision: editing.value.revision }
      : {
          ...body,
          deviceId: deviceId.value,
          type: type.value,
          provenance: "MANUAL",
        },
  );
  if (item) {
    editorOpen.value = false;
    selected.value = item;
    action.notice.value =
      "检查定义已保存；启用的人工定义由平台执行器按周期运行，也可以立即执行。";
  }
}
async function toggle(item: CheckDefinition) {
  const v = await action.run<CheckDefinition>(
    `/workbench/checks/${item.id}/updates`,
    {
      revision: item.revision,
      name: item.name,
      target: item.target,
      intervalSeconds: item.intervalSeconds,
      enabled: !item.enabled,
    },
  );
  if (v) {
    if (selected.value?.id === v.id) selected.value = v;
    action.notice.value = v.enabled
      ? "检查已启用。"
      : "检查已停用，历史结果保留。";
  }
}
async function archive() {
  if (!archiving.value) return;
  const value = await action.run<CheckDefinition>(
    `/workbench/checks/${archiving.value.id}/archive`,
    { revision: archiving.value.revision },
  );
  if (value) {
    if (selected.value?.id === value.id) selected.value = value;
    archiving.value = null;
    action.notice.value = "探测定义已归档，历史结果保留。";
  }
}
async function runCheck(item: CheckDefinition) {
  const value = await action.run<CheckResult>(
    `/workbench/checks/${item.id}/run`,
    { revision: item.revision },
  );
  if (value) {
    selected.value = { ...item, lastResult: value };
    action.notice.value = `探测已完成：${stateName(value.status)} · ${value.message}`;
    await results.refetch();
  }
}
const stateName = (state: string | undefined) =>
  state === "PASS" ? "通过" : state === "FAIL" ? "失败" : "尚无有效结果";
watch(
  () => route.query.deviceId,
  () => {
    selected.value = null;
  },
);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>合成探测</h1>
      <p>独立于设备遥测，观察检查定义、延迟与历史结果。</p>
    </div>
    <div class="wb-inline">
      <button class="btn" @click="list.refetch()">
        <RefreshCw :size="14" />刷新</button
      ><button v-if="auth.canWrite" class="btn primary" @click="editor()">
        <Plus :size="14" />新建检查
      </button>
    </div>
  </div>
  <p v-if="action.notice.value" class="notice" role="status">
    {{ action.notice.value }}
  </p>
  <p v-if="action.error.value && !editorOpen" class="wb-error" role="alert">
    {{ action.error.value }}
  </p>
  <section class="panel">
    <header class="panel-head">
      <h2>探测定义与最近结果</h2>
      <span class="small muted">内置执行器 · 真实只读网络探测</span>
    </header>
    <div class="wb-tools">
      <input
        v-model.lazy="q"
        @keydown.enter.prevent="q = ($event.target as HTMLInputElement).value"
        class="input grow"
        aria-label="检查名称前缀搜索"
        placeholder="检查名称前缀，回车查询"
      />
    </div>
    <QueryState
      :pending="list.isPending.value"
      :error="list.error.value"
      :empty="!list.data.value?.items.length"
      empty-title="尚无检查定义"
      empty-description="登记并启用检查目标，平台执行器会按周期探测；也可以立即执行一次。"
      @retry="list.refetch()"
      ><div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>检查 / 目标</th>
              <th>类型</th>
              <th>周期</th>
              <th>最近延迟</th>
              <th>结果</th>
              <th>定义状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in list.data.value?.items"
              :key="item.id"
              :class="{ 'wb-select-row': selected?.id === item.id }"
            >
              <td>
                <button class="wb-source-button" @click="selected = item">
                  {{ item.name }}
                </button>
                <p class="secondary-line mono">{{ item.target }}</p>
              </td>
              <td>{{ item.type }}</td>
              <td>{{ item.intervalSeconds }} 秒</td>
              <td>
                {{ formatLatency(item.lastResult?.latencyMs) }}
              </td>
              <td>
                {{ stateName(item.lastResult?.status) }}
                <p class="secondary-line">
                  {{
                    item.lastResult?.provenance === "SYNTHETIC"
                      ? "合成测试 · "
                      : ""
                  }}{{
                    item.lastResult
                      ? formatTime(item.lastResult.observedAt, prefs.timezone)
                      : "等待上报"
                  }}
                </p>
              </td>
              <td>
                {{ item.archived ? "已归档" : item.enabled ? "启用" : "停用" }}
              </td>
              <td>
                <div class="wb-inline">
                  <button class="btn small-btn" @click="selected = item">
                    历史</button
                  ><template v-if="auth.canWrite && !item.archived"
                    ><button
                      v-if="item.provenance !== 'SYNTHETIC'"
                      class="btn small-btn"
                      :disabled="action.pending.value"
                      @click="runCheck(item)"
                    >
                      立即执行</button
                    ><button class="btn small-btn" @click="editor(item)">
                      编辑</button
                    ><button
                      class="btn small-btn"
                      :disabled="action.pending.value"
                      @click="toggle(item)"
                    >
                      {{ item.enabled ? "停用" : "启用" }}</button
                    ><button
                      class="btn small-btn"
                      :disabled="action.pending.value"
                      @click="archiving = item"
                    >
                      归档
                    </button></template
                  >
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
  <section v-if="selected" class="panel wb-spaced">
    <header class="panel-head">
      <h2>{{ selected.name }} · 历史结果</h2>
      <RouterLink :to="`/devices/${selected.deviceId}`">关联设备 ↗</RouterLink>
    </header>
    <QueryState
      :pending="results.isPending.value"
      :error="results.error.value"
      :empty="!results.data.value?.items.length"
      empty-title="等待首次探测结果"
      empty-description="启用的人工定义由平台周期执行，也可点击立即执行。请检查执行器状态与目标允许网段。"
      @retry="results.refetch()"
      ><div class="panel-body">
        <div
          class="wb-checks"
          role="img"
          aria-label="最近最多30次探测，按新到旧排列"
        >
          <span
            v-for="r in results.data.value?.items"
            :key="r.id"
            :class="r.status"
            :title="`${formatTime(r.observedAt, prefs.timezone)} ${stateName(r.status)}`"
          />
        </div>
      </div>
      <div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>观测时间</th>
              <th>结果</th>
              <th>延迟</th>
              <th>定义版本</th>
              <th>来源</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in results.data.value?.items" :key="r.id">
              <td class="mono small">
                {{ formatTime(r.observedAt, prefs.timezone) }}
              </td>
              <td>{{ stateName(r.status) }}</td>
              <td>{{ formatLatency(r.latencyMs) }}</td>
              <td>{{ r.definitionRevision }}</td>
              <td>{{ r.source }} · {{ r.provenance }}</td>
              <td class="text-wrap">{{ r.message || "—" }}</td>
            </tr>
          </tbody>
        </table>
      </div></QueryState
    >
    <div class="wb-tools">
      <span class="small muted"
        >历史第 {{ resultCursors.length + 1 }} 页 · 本页
        {{ results.data.value?.items.length || 0 }} 项</span
      ><button
        class="btn small-btn"
        :disabled="!resultCursors.length || results.isFetching.value"
        @click="resultCursors.pop()"
      >
        上一页历史</button
      ><button
        class="btn small-btn"
        :disabled="!results.data.value?.nextCursor || results.isFetching.value"
        @click="
          results.data.value?.nextCursor &&
          resultCursors.push(results.data.value.nextCursor)
        "
      >
        下一页历史
      </button>
    </div>
  </section>
  <ModalDialog
    :open="!!archiving"
    title="归档探测定义"
    @close="archiving = null"
    ><div v-if="archiving" class="panel-body">
      <p>
        归档“{{ archiving.name }}”后保留历史，拒收新结果且不能继续编辑此定义。
      </p>
      <p v-if="action.error.value" class="wb-error" role="alert">
        {{ action.error.value }}
      </p>
      <div class="wb-inline wb-spaced">
        <button class="btn" @click="archiving = null">取消</button
        ><button
          class="btn primary"
          :disabled="action.pending.value"
          @click="archive"
        >
          确认归档
        </button>
      </div>
    </div></ModalDialog
  >
  <p class="wb-note wb-spaced">
    人工定义由平台执行器只读探测，受允许网段与正常 TLS
    证书验证约束。停用保留历史；手动立即执行不改变周期设置。单一探测点失败仅表示该次检查失败，仍需独立判断根因。
  </p>
  <ModalDialog
    :open="editorOpen"
    :title="editing ? '编辑检查定义' : '新建检查定义'"
    @close="editorOpen = false"
    ><form class="wb-form" @submit.prevent="save">
      <div class="form-field">
        <label for="check-name">检查名称</label
        ><input
          id="check-name"
          v-model="name"
          class="input"
          required
          maxlength="200"
        />
      </div>
      <div v-if="!editing" class="form-field">
        <label for="check-device">关联设备</label
        ><DevicePicker id="check-device" v-model="deviceId" />
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label for="check-type">检查类型</label
          ><select
            id="check-type"
            v-model="type"
            class="input"
            :disabled="!!editing"
          >
            <option
              v-for="t in ['TCP', 'HTTP', 'HTTPS', 'DNS', 'TLS']"
              :key="t"
            >
              {{ t }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label for="check-interval">间隔（秒）</label
          ><input
            id="check-interval"
            v-model="intervalSeconds"
            class="input"
            type="number"
            min="30"
            max="86400"
            required
          />
        </div>
      </div>
      <div class="form-field">
        <label for="check-target">目标地址</label
        ><input
          id="check-target"
          v-model="target"
          class="input mono"
          required
          maxlength="253"
          placeholder="TCP/TLS: 主机:端口；HTTP(S): URL；DNS: 主机名"
        />
      </div>
      <label class="wb-inline"
        ><input v-model="enabled" type="checkbox" />启用定义</label
      >
      <p v-if="action.error.value" class="wb-error" role="alert">
        {{ action.error.value }}
      </p>
      <div class="actions">
        <button
          class="btn primary"
          :disabled="action.pending.value || !deviceId"
        >
          保存检查定义
        </button>
      </div>
    </form></ModalDialog
  >
</template>
