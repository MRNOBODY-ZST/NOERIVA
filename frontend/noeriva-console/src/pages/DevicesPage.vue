<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import {
  Search,
  Plus,
  Download,
  ArrowLeft,
  ArrowRight,
  Server,
  ChevronRight,
} from "@lucide/vue";
import { request, queryString } from "../services/api";
import { useApiQuery } from "../services/queries";
import type { Device, Page, Site } from "../services/types";
import { useSessionStore } from "../stores/session";
import { usePreferencesStore } from "../stores/preferences";
import { formatTime, stateLabel } from "../utils/format";
import QueryState from "../components/QueryState.vue";
import StatusBadge from "../components/StatusBadge.vue";
import ModalDialog from "../components/ModalDialog.vue";
const auth = useSessionStore(),
  prefs = usePreferencesStore(),
  route = useRoute(),
  router = useRouter(),
  client = useQueryClient();
const q = ref(String(route.query.q || "")),
  search = ref(q.value),
  site = ref(String(route.query.siteId || "")),
  type = ref(String(route.query.type || "")),
  health = ref(String(route.query.health || ""));
const cursors = ref<string[]>([]);
const cursor = computed(() => cursors.value.at(-1));
let timer: ReturnType<typeof setTimeout> | undefined;
watch(search, (value) => {
  clearTimeout(timer);
  timer = setTimeout(() => {
    q.value = value;
  }, 220);
});
onBeforeUnmount(() => clearTimeout(timer));
watch([q, site, type, health], () => {
  cursors.value = [];
  void router.replace({
    query: {
      ...(q.value ? { q: q.value } : {}),
      ...(site.value ? { siteId: site.value } : {}),
      ...(type.value ? { type: type.value } : {}),
      ...(health.value ? { health: health.value } : {}),
    },
  });
});
const { data, isPending, error, refetch, isFetching } = useApiQuery<
  Page<Device>
>(
  computed(
    () =>
      `/devices${queryString({ q: q.value, siteId: site.value, type: type.value, health: health.value, limit: 25, cursor: cursor.value })}`,
  ),
);
const { data: sites } = useApiQuery<Page<Site>>("/sites");
const createOpen = ref(false);
const configureAfterCreate = ref(true);
const form = ref({
  name: "",
  type: "HOST",
  siteId: "",
  vendor: "",
  model: "",
  managementAddress: "",
});
const create = useMutation({
  mutationFn: () =>
    request<Device>("/devices", {
      method: "POST",
      body: JSON.stringify(form.value),
    }),
  onSuccess: async (device) => {
    await client.invalidateQueries({ queryKey: ["noeriva"] });
    createOpen.value = false;
    await router.push({
      path: `/assets/${device.id}`,
      query: configureAfterCreate.value ? { tab: "access" } : {},
    });
  },
});
function openCreate() {
  form.value = {
    name: "",
    type: "HOST",
    siteId: sites.value?.items[0]?.id || "",
    vendor: "",
    model: "",
    managementAddress: "",
  };
  create.reset();
  configureAfterCreate.value = true;
  createOpen.value = true;
}
function exportPage() {
  const rows = [
    [
      "资产名称",
      "设备标识",
      "类型",
      "管理地址",
      "站点",
      "厂商",
      "型号",
      "健康",
      "可用性",
      "最后观测",
    ],
    ...(data.value?.items || []).map((device) => [
      device.name,
      device.id,
      stateLabel(device.type),
      device.managementAddress,
      device.siteName,
      device.vendor,
      device.model,
      stateLabel(device.health),
      stateLabel(device.availability),
      device.lastSeen || "",
    ]),
  ];
  const csv =
    "\uFEFF" +
    rows
      .map((row) =>
        row
          .map(
            (value) =>
              '"' +
              String(value)
                .replace(/^[=+@\-\t\r]/, (prefix) => "'" + prefix)
                .replaceAll('"', '""') +
              '"',
          )
          .join(","),
      )
      .join("\r\n");
  const url = URL.createObjectURL(
    new Blob([csv], { type: "text/csv;charset=utf-8" }),
  );
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `noeriva-assets-page-${cursors.value.length + 1}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>资产目录</h1>
      <p>统一身份、位置与采集状态；从资产进入监测、连接与历史证据。</p>
    </div>
    <div class="page-actions">
      <RouterLink
        class="btn"
        :to="{ path: '/assets/discovery', query: site ? { siteId: site } : {} }"
        >发现设备</RouterLink
      >
      <button
        class="btn"
        :aria-pressed="prefs.compact"
        @click="prefs.compact = !prefs.compact"
      >
        {{ prefs.compact ? "标准行高" : "紧凑行高" }}</button
      ><button v-if="auth.canWrite" class="btn primary" @click="openCreate">
        <Plus aria-hidden="true" :size="16" /> 登记设备
      </button>
    </div>
  </div>
  <section class="panel">
    <div class="section-toolbar">
      <div class="search-field">
        <Search aria-hidden="true" :size="16" /><input
          v-model="search"
          class="input"
          aria-label="搜索设备"
          placeholder="按名称或 IP 前缀搜索…"
        />
      </div>
      <select v-model="site" aria-label="按站点筛选">
        <option value="">全部站点</option>
        <option v-for="item in sites?.items" :key="item.id" :value="item.id">
          {{ item.name }}
        </option></select
      ><select v-model="type" aria-label="按设备类型筛选">
        <option value="">全部类型</option>
        <option
          v-for="item in ['HOST', 'BMC', 'SWITCH', 'ROUTER', 'FIREWALL']"
          :key="item"
          :value="item"
        >
          {{ stateLabel(item) }}
        </option></select
      ><select v-model="health" aria-label="按健康状态筛选">
        <option value="">全部健康状态</option>
        <option
          v-for="item in ['HEALTHY', 'WARNING', 'CRITICAL', 'UNKNOWN']"
          :key="item"
          :value="item"
        >
          {{ stateLabel(item) }}
        </option></select
      ><button
        v-if="q || site || type || health"
        class="btn ghost"
        @click="
          search = '';
          q = '';
          site = '';
          type = '';
          health = '';
        "
      >
        清除筛选
      </button>
      <span class="toolbar-spacer"></span
      ><button class="btn" :disabled="!data?.items.length" @click="exportPage">
        <Download aria-hidden="true" :size="14" />导出当前页
      </button>
    </div>
    <div class="density-strip">
      <span
        >当前页 <strong>{{ data?.items.length || 0 }}</strong> 项</span
      ><span>持久设备标识与管理地址分别保存</span
      ><span>健康、可用性与采集时间独立呈现</span>
    </div>
    <QueryState
      :pending="isPending"
      :error="error"
      :empty="!data?.items.length"
      empty-title="没有匹配的设备"
      empty-description="调整筛选条件，或登记第一台设备。新设备的状态保持未知，直到收到有效观测。"
      @retry="refetch()"
      ><div
        class="table-scroll"
        role="region"
        aria-label="设备列表，可横向滚动"
        tabindex="0"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>设备名称 / 管理地址</th>
              <th>类型</th>
              <th>健康</th>
              <th>可用性</th>
              <th>站点</th>
              <th>厂商 / 型号</th>
              <th>最后观测</th>
              <th><span class="sr-only">查看详情</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="device in data?.items" :key="device.id">
              <td>
                <RouterLink :to="`/assets/${device.id}`" class="device-name"
                  ><Server aria-hidden="true" :size="16" class="faint" />{{
                    device.name
                  }}</RouterLink
                >
                <div class="secondary-line mono">
                  {{ device.managementAddress }}
                </div>
              </td>
              <td>{{ stateLabel(device.type) }}</td>
              <td><StatusBadge :status="device.health" /></td>
              <td><StatusBadge :status="device.availability" /></td>
              <td>{{ device.siteName }}</td>
              <td>
                {{ device.vendor || "未记录" }}
                <div class="secondary-line">
                  {{ device.model || "型号未记录" }}
                </div>
              </td>
              <td class="mono small">
                {{ formatTime(device.lastSeen, prefs.timezone) }}
              </td>
              <td>
                <RouterLink
                  :to="`/assets/${device.id}`"
                  :aria-label="`查看 ${device.name}`"
                  class="icon-btn"
                  ><ChevronRight aria-hidden="true" :size="16"
                /></RouterLink>
              </td>
            </tr>
          </tbody>
        </table></div
    ></QueryState>
    <footer class="panel-foot">
      <span
        >{{ data?.items.length || 0 }} 台设备 · 第 {{ cursors.length + 1 }} 页 ·
        标识升序</span
      >
      <div class="inline-actions">
        <button
          class="btn small-btn"
          :disabled="!cursors.length || isFetching"
          @click="cursors.pop()"
        >
          <ArrowLeft aria-hidden="true" :size="14" /> 上一页</button
        ><button
          class="btn small-btn"
          :disabled="!data?.nextCursor || isFetching"
          @click="data?.nextCursor && cursors.push(data.nextCursor)"
        >
          下一页 <ArrowRight aria-hidden="true" :size="14" />
        </button>
      </div>
    </footer>
  </section>
  <p class="small muted section-gap">
    健康状态与最后观测独立展示。登记后可配置独立的 SNMP、Redfish 或 SSH
    连接，测试识别并启用采集。
  </p>
  <ModalDialog :open="createOpen" title="登记设备" @close="createOpen = false"
    ><form class="form-body" @submit.prevent="create.mutate()">
      <div class="notice">
        先保存资产资料。随后可在设备详情配置连接、测试身份与能力，并启用只读采集。
      </div>
      <div class="form-field">
        <label for="device-name">设备名称</label
        ><input
          id="device-name"
          v-model="form.name"
          class="input"
          maxlength="120"
          required
          placeholder="例如 compute-08"
        />
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label for="device-type">设备类型</label
          ><select id="device-type" v-model="form.type">
            <option
              v-for="item in ['HOST', 'BMC', 'SWITCH', 'ROUTER', 'FIREWALL']"
              :key="item"
              :value="item"
            >
              {{ stateLabel(item) }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label for="device-site">所属站点</label
          ><select id="device-site" v-model="form.siteId" required>
            <option disabled value="">请选择站点</option>
            <option
              v-for="item in sites?.items"
              :key="item.id"
              :value="item.id"
            >
              {{ item.name }}
            </option>
          </select>
        </div>
      </div>
      <div class="form-field">
        <label for="device-address">管理地址</label
        ><input
          id="device-address"
          v-model="form.managementAddress"
          class="input mono"
          required
          maxlength="253"
          placeholder="IPv4、IPv6 或主机名"
        />
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label for="device-vendor">厂商</label
          ><input
            id="device-vendor"
            v-model="form.vendor"
            class="input"
            maxlength="120"
          />
        </div>
        <div class="form-field">
          <label for="device-model">型号</label
          ><input
            id="device-model"
            v-model="form.model"
            class="input"
            maxlength="120"
          />
        </div>
      </div>
      <label class="wb-inline"
        ><input v-model="configureAfterCreate" type="checkbox" />
        保存后继续接入配置</label
      >
      <p v-if="create.error.value" class="danger-text" role="alert">
        {{ create.error.value.message }}
      </p>
      <div class="form-actions">
        <button type="button" class="btn" @click="createOpen = false">
          取消</button
        ><button
          class="btn primary"
          :disabled="create.isPending.value || !form.siteId"
        >
          {{ create.isPending.value ? "正在保存…" : "登记设备" }}
        </button>
      </div>
    </form></ModalDialog
  >
</template>
