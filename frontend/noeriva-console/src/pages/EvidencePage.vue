<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { Plus, Download } from "@lucide/vue";
import { request } from "../services/api";
import {
  usePagedQuery,
  useWorkbenchAction,
  utcInputNow,
  fromUtcInput,
  downloadJson,
  type Evidence,
  type Audit,
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
const admin = computed(() => auth.session?.roles.includes("ADMIN"));
const q = ref(""),
  createOpen = ref(false),
  title = ref(""),
  deviceId = ref(String(route.query.deviceId || "")),
  kind = ref("NOTE"),
  source = ref("manual-review"),
  observedAt = ref(utcInputNow()),
  content = ref(""),
  selected = ref<Evidence | null>(null),
  readError = ref(""),
  reading = ref(false);
const list = usePagedQuery<Evidence>(
  "/workbench/evidence",
  computed(() => ({
    q: q.value,
    deviceId: String(route.query.deviceId || ""),
  })),
);
const audit = usePagedQuery<Audit>(
  "/workbench/audit",
  computed(() => ({ resourceId: selected.value?.id || "no-selection" })),
);
async function open(item: Evidence) {
  reading.value = true;
  readError.value = "";
  try {
    selected.value = await request<Evidence>(`/workbench/evidence/${item.id}`);
  } catch (e) {
    readError.value = e instanceof Error ? e.message : "无法读取证据";
  } finally {
    reading.value = false;
  }
}
async function exportRecord(item: Evidence) {
  reading.value = true;
  readError.value = "";
  try {
    downloadJson(
      `evidence-${item.id}.json`,
      await request(`/workbench/evidence/${item.id}/manifest`),
    );
    await audit.refetch();
  } catch (e) {
    readError.value = e instanceof Error ? e.message : "导出失败";
  } finally {
    reading.value = false;
  }
}
async function create() {
  try {
    const value = await action.run<Evidence>("/workbench/evidence", {
      title: title.value,
      deviceId: deviceId.value,
      kind: kind.value,
      source: source.value,
      observedAt: fromUtcInput(observedAt.value),
      content: content.value,
      provenance: "MANUAL",
    });
    if (value) {
      createOpen.value = false;
      title.value = "";
      content.value = "";
      action.notice.value = "证据已保存，内容摘要由服务端计算。";
    }
  } catch (e) {
    action.error.value = e instanceof Error ? e.message : "时间格式无效";
  }
}
watch(
  () => route.query.deviceId,
  (value) => {
    deviceId.value = String(value || "");
    selected.value = null;
  },
);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>证据资料</h1>
      <p>查看记录来源与完整性校验，保留原始内容和访问记录。</p>
    </div>
    <button v-if="admin" class="btn primary" @click="createOpen = true">
      <Plus :size="14" />登记证据
    </button>
  </div>
  <p v-if="action.notice.value" class="notice" role="status">
    {{ action.notice.value }}
  </p>
  <p v-if="readError" class="wb-error" role="alert">{{ readError }}</p>
  <section class="panel">
    <header class="panel-head">
      <h2>可查看的来源记录</h2>
      <span class="small muted">原文不可覆盖 · SHA-256</span>
    </header>
    <div class="wb-tools">
      <input
        v-model.lazy="q"
        @keydown.enter.prevent="q = ($event.target as HTMLInputElement).value"
        class="input grow"
        aria-label="证据标题前缀"
        placeholder="按标题前缀搜索，回车查询"
      /><button class="btn" @click="q = ''">重置</button>
    </div>
    <QueryState
      :pending="list.isPending.value"
      :error="list.error.value"
      :empty="!list.data.value?.items.length"
      empty-title="尚无证据资料"
      empty-description="登记观测或调查记录后，可在此校验内容摘要并导出。"
      @retry="list.refetch()"
      ><div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>证据 / ID</th>
              <th>类型</th>
              <th>观测时间</th>
              <th>来源性质</th>
              <th>完整性状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in list.data.value?.items" :key="item.id">
              <td>
                {{ item.title }}
                <p class="secondary-line mono">{{ item.id.slice(0, 8) }}</p>
              </td>
              <td>{{ item.kind }}</td>
              <td class="small mono">
                {{ formatTime(item.observedAt, prefs.timezone) }}
              </td>
              <td>
                {{ item.source }}
                <p class="secondary-line">
                  {{
                    item.provenance === "SYNTHETIC"
                      ? "合成测试记录"
                      : "人工登记"
                  }}
                </p>
              </td>
              <td>
                <span class="small">SHA-256 · 未签名</span>
                <p class="secondary-line mono">
                  {{ item.sha256.slice(0, 16) }}…
                </p>
              </td>
              <td>
                <div class="wb-inline">
                  <button
                    class="btn small-btn"
                    :disabled="reading"
                    @click="open(item)"
                  >
                    查看内容</button
                  ><button
                    class="icon-btn"
                    :disabled="reading"
                    :aria-label="`导出${item.title}`"
                    @click="exportRecord(item)"
                  >
                    <Download :size="15" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table></div></QueryState
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
  <div class="wb-grid wb-spaced">
    <section class="panel">
      <header class="panel-head"><h2>内容校验与来源</h2></header>
      <div class="panel-body">
        <dl class="wb-facts">
          <dt>摘要范围</dt>
          <dd>保存内容的完整 UTF-8 字节</dd>
          <dt>来源引用</dt>
          <dd>稳定 ID、设备、观测时间与登记者</dd>
          <dt>读取记录</dt>
          <dd>查看和导出均记录访问审计</dd>
          <dt>签名状态</dt>
          <dd>UNSIGNED · 未签名</dd>
        </dl>
      </div>
    </section>
    <p class="wb-note">
      校验和可用于比对内容。当前记录不具有 WORM 对象锁、数字签名或法律效力保证。
    </p>
  </div>
  <ModalDialog
    :open="!!selected"
    title="证据内容与访问记录"
    wide
    @close="selected = null"
    ><div v-if="selected" class="wb-form">
      <dl class="wb-facts">
        <dt>标题</dt>
        <dd>{{ selected.title }}</dd>
        <dt>SHA-256</dt>
        <dd class="mono">{{ selected.sha256 }}</dd>
        <dt>来源</dt>
        <dd>{{ selected.source }} · {{ selected.provenance }}</dd>
        <dt>观测时间</dt>
        <dd>{{ formatTime(selected.observedAt, prefs.timezone) }}</dd>
      </dl>
      <pre class="wb-raw">{{ selected.content }}</pre>
      <div class="actions">
        <button class="btn" :disabled="reading" @click="exportRecord(selected)">
          <Download :size="14" />导出清单与内容
        </button>
      </div>
      <h3>最近访问</h3>
      <QueryState
        :pending="audit.isPending.value"
        :error="audit.error.value"
        :empty="!audit.data.value?.items.length"
        empty-title="暂无访问记录"
        @retry="audit.refetch()"
        ><div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>账号</th>
                <th>动作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in audit.data.value?.items" :key="item.id">
                <td>{{ formatTime(item.createdAt, prefs.timezone) }}</td>
                <td>{{ item.actor }}</td>
                <td>{{ item.action }}</td>
              </tr>
            </tbody>
          </table>
        </div></QueryState
      >
    </div></ModalDialog
  >
  <ModalDialog :open="createOpen" title="登记证据" @close="createOpen = false"
    ><form class="wb-form" @submit.prevent="create">
      <div class="form-field">
        <label for="evidence-title">证据标题</label
        ><input
          id="evidence-title"
          v-model="title"
          class="input"
          required
          maxlength="200"
        />
      </div>
      <div class="form-field">
        <label for="evidence-device">关联设备</label
        ><DevicePicker id="evidence-device" v-model="deviceId" />
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label for="evidence-kind">记录类型</label
          ><select id="evidence-kind" v-model="kind" class="input">
            <option
              v-for="value in [
                'NOTE',
                'EVENT',
                'OBSERVATION',
                'NAT',
                'LEASE',
                'CONFIGURATION',
                'CHECK',
              ]"
              :key="value"
            >
              {{ value }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label for="evidence-source">来源说明</label
          ><input
            id="evidence-source"
            v-model="source"
            class="input"
            required
            maxlength="120"
          />
        </div>
      </div>
      <div class="form-field">
        <label for="evidence-at">观测时间 · UTC</label
        ><input
          id="evidence-at"
          v-model="observedAt"
          type="datetime-local"
          step="1"
          class="input"
          required
        />
      </div>
      <div class="form-field">
        <label for="evidence-content">证据内容（文本或 JSON）</label
        ><textarea
          id="evidence-content"
          v-model="content"
          class="input mono"
          required
          maxlength="32768"
          placeholder="填写已脱敏的来源内容，不包含密码或密钥。"
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
          保存证据
        </button>
      </div>
    </form></ModalDialog
  >
</template>
