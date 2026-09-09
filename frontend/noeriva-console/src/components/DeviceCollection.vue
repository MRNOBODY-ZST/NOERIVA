<script setup lang="ts">
import { computed, ref } from "vue";
import { useQueryClient } from "@tanstack/vue-query";
import { useSessionStore } from "../stores/session";
import { usePreferencesStore } from "../stores/preferences";
import { useApiQuery } from "../services/queries";
import { request, ApiError } from "../services/api";
import {
  useDeviceCollection,
  collectionLabels,
  type ConnectionView,
  type DeviceSupport,
  type Slot,
} from "../services/devices";
import { formatTime } from "../utils/format";
import QueryState from "./QueryState.vue";
import DeviceConnectionEditor from "./DeviceConnectionEditor.vue";
import DeviceReading from "./DeviceReading.vue";
const props = defineProps<{ deviceId: string; managementAddress: string }>();
const auth = useSessionStore(),
  prefs = usePreferencesStore(),
  client = useQueryClient();
const admin = computed(() => auth.session?.roles.includes("ADMIN") ?? false);
const support = useApiQuery<DeviceSupport>("/device-support");
const connections = useApiQuery<{ items: ConnectionView[] }>(
  computed(() => `/devices/${encodeURIComponent(props.deviceId)}/connections`),
  admin,
);
const collection = useDeviceCollection(() => props.deviceId),
  editing = ref<Slot | null>(null),
  pending = ref(""),
  error = ref(""),
  notice = ref("");
const slots: Slot[] = ["snmp", "redfish", "ssh"];
const slotLabels: Record<Slot, { title: string; detail: string }> = {
  snmp: { title: "SNMP 网络采集", detail: "来源 network · 独立 SNMP 认证" },
  redfish: { title: "Redfish 硬件采集", detail: "来源 bmc · HTTPS 证书验证" },
  ssh: {
    title: "SSH 只读采集",
    detail: "来源 ssh · 固定主机密钥指纹与内置命令",
  },
};
const connection = (slot: Slot) =>
  connections.data.value?.items.find((v) => v.slot === slot) || null;
const current = (slot: Slot) =>
  collection.data.value?.items.find((v) => v.slot === slot);
async function saved() {
  editing.value = null;
  notice.value = "连接已保存。请先测试识别，再启用周期采集。";
  await client.invalidateQueries({ queryKey: ["noeriva"] });
}
async function run(slot: Slot, action: "test" | "collect" | "state") {
  const value = connection(slot);
  if (!value) return;
  if (action === "collect" && !value.enabled) {
    error.value = "连接已停用。请先启用采集，或使用测试连接检查访问能力。";
    return;
  }
  pending.value = `${slot}-${action}`;
  error.value = "";
  notice.value = "";
  try {
    await request(
      `/devices/${encodeURIComponent(props.deviceId)}/connections/${slot}/${action}`,
      {
        method: "POST",
        body: JSON.stringify({
          revision: value.revision,
          ...(action === "state" ? { enabled: !value.enabled } : {}),
        }),
      },
    );
    notice.value =
      action === "state"
        ? value.enabled
          ? "已请求停用；已发出的只读请求可能完成。"
          : "已启用周期采集，等待执行器提供新观测。"
        : action === "test"
          ? "测试已完成，请查看实际识别结果与错误详情。"
          : "本次采集已完成，请查看观测结果。";
    await client.invalidateQueries({ queryKey: ["noeriva"] });
  } catch (e) {
    error.value =
      e instanceof ApiError && e.status === 409
        ? "连接版本已变化，已重新读取；请检查后重试。"
        : e instanceof Error
          ? e.message
          : "操作失败，请重试。";
    await connections.refetch();
  } finally {
    pending.value = "";
  }
}
</script>
<template>
  <div class="device-collection">
    <div class="section-toolbar">
      <div>
        <h2>接入与采集</h2>
        <p class="small muted">
          测试、自动采集与设备健康分别展示。只读取设备，不下发配置。
        </p>
      </div>
      <span class="small" role="status"
        >{{ collection.streaming.value ? "实时更新" : "轮询刷新 · 每 20 秒"
        }}<span v-if="collection.data.value?.asOf">
          · {{ formatTime(collection.data.value.asOf, prefs.timezone) }}</span
        ></span
      ><button
        class="btn"
        @click="
          collection.refetch();
          admin && connections.refetch();
        "
      >
        刷新
      </button>
    </div>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="wb-error" role="alert">{{ error }}</p>
    <QueryState
      :pending="support.isPending.value"
      :error="support.error.value"
      @retry="support.refetch()"
      ><p
        v-if="support.data.value && !support.data.value.credentialStorageReady"
        class="notice"
      >
        凭据存储尚未配置。请管理员配置服务端主密钥后保存连接；已有资产资料和采集记录仍可查看。
      </p></QueryState
    >
    <QueryState
      :pending="collection.isPending.value"
      :error="collection.error.value"
      @retry="collection.refetch()"
      ><div class="wb-grid device-connection-grid">
        <section v-for="slot in slots" :key="slot" class="panel">
          <header class="panel-head">
            <div>
              <h2>
                {{ slotLabels[slot].title }}
              </h2>
              <p>
                {{ slotLabels[slot].detail }}
              </p>
            </div>
            <button
              v-if="admin"
              class="btn"
              :disabled="
                !support.data.value?.credentialStorageReady ||
                connections.isPending.value ||
                !!connections.error.value ||
                !!pending
              "
              @click="editing = editing === slot ? null : slot"
            >
              {{
                editing === slot
                  ? "收起编辑"
                  : connection(slot)
                    ? "编辑连接"
                    : "配置连接"
              }}
            </button>
          </header>
          <DeviceConnectionEditor
            v-if="editing === slot && admin"
            :key="`${deviceId}/${slot}/${connection(slot)?.revision || 0}`"
            :device-id="deviceId"
            :slot="slot"
            :management-address="managementAddress"
            :connection="connection(slot)"
            @cancel="editing = null"
            @saved="saved"
          />
          <div v-else class="panel-body">
            <template v-if="current(slot)"
              ><div class="wb-inline">
                <strong>{{
                  collectionLabels[current(slot)!.status] ||
                  current(slot)!.status
                }}</strong
                ><span class="small muted"
                  >{{
                    current(slot)!.enabled ? "周期采集已启用" : "周期采集已停用"
                  }}
                  · 版本 {{ current(slot)!.revision }}</span
                >
              </div>
              <p
                v-if="
                  current(slot)!.lastSuccessAt &&
                  Date.now() - Date.parse(current(slot)!.lastSuccessAt!) >
                    180000
                "
                class="notice wb-spaced"
                role="status"
              >
                最近成功读取已超过 180
                秒，以下读数属于历史观测；当前可用性与健康以设备概览为准。
              </p>
              <dl class="wb-facts wb-spaced">
                <template v-if="admin && connection(slot)"
                  ><dt>连接目标</dt>
                  <dd class="mono">
                    {{ connection(slot)!.host }}:{{ connection(slot)!.port }}
                  </dd>
                  <dt>周期 / 超时</dt>
                  <dd>
                    {{ connection(slot)!.intervalSeconds }} 秒 /
                    {{ connection(slot)!.timeoutMillis }} ms
                  </dd>
                  <dt>认证状态</dt>
                  <dd>已配置 · 密钥不回显</dd></template
                >
                <dt>最近尝试</dt>
                <dd>
                  {{ formatTime(current(slot)!.lastAttemptAt, prefs.timezone) }}
                </dd>
                <dt>最近成功</dt>
                <dd>
                  {{
                    current(slot)!.lastSuccessAt
                      ? formatTime(current(slot)!.lastSuccessAt, prefs.timezone)
                      : "尚无成功读取"
                  }}
                </dd>
                <dt>下次调度</dt>
                <dd>
                  {{ formatTime(current(slot)!.nextPollAt, prefs.timezone) }}
                </dd>
              </dl>
              <p
                v-if="current(slot)!.errorCode"
                class="wb-error wb-spaced"
                role="alert"
              >
                {{ current(slot)!.errorCode }} ·
                {{ current(slot)!.errorMessage }}
              </p>
              <div v-if="admin && connection(slot)" class="wb-inline wb-spaced">
                <button
                  class="btn"
                  :disabled="!!pending"
                  @click="run(slot, 'test')"
                >
                  {{
                    pending === `${slot}-test` ? "正在测试…" : "测试连接并识别"
                  }}</button
                ><button
                  class="btn"
                  :disabled="!!pending || !connection(slot)!.enabled"
                  :title="
                    !connection(slot)!.enabled
                      ? '请先启用采集；测试连接仍可使用'
                      : undefined
                  "
                  @click="run(slot, 'collect')"
                >
                  {{
                    pending === `${slot}-collect` ? "正在采集…" : "立即采集"
                  }}</button
                ><button
                  class="btn primary"
                  :disabled="!!pending"
                  @click="run(slot, 'state')"
                >
                  {{ connection(slot)!.enabled ? "停用采集" : "启用采集" }}
                </button>
              </div>
              <p v-if="!current(slot)!.enabled" class="wb-note">
                此连接已停用，周期采集与立即采集均不会执行。手动测试仍会发起只读连接；启用后才会持续上报观测。
              </p></template
            >
            <div v-else class="empty-state">
              <strong>尚未配置连接</strong>
              <p>
                {{
                  admin
                    ? "保存连接与凭据，然后测试设备识别。"
                    : "请管理员配置连接与凭据。"
                }}
              </p>
            </div>
          </div>
          <DeviceReading
            v-if="current(slot)?.lastReading"
            :reading="current(slot)!.lastReading!"
          />
        </section></div
    ></QueryState>
    <QueryState
      v-if="admin"
      :error="connections.error.value"
      @retry="connections.refetch()"
    />
    <section class="panel wb-spaced">
      <header class="panel-head">
        <div>
          <h2>厂商与协议支持范围</h2>
          <p>预设档案说明适配范围；实际匹配以设备读取结果为准。</p>
        </div>
      </header>
      <div
        class="table-scroll"
        role="region"
        aria-label="厂商支持目录"
        tabindex="0"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>品牌 / 系列</th>
              <th>档案</th>
              <th>协议</th>
              <th>实现 / 验证</th>
              <th>范围说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in support.data.value?.items" :key="item.id">
              <td>{{ item.vendor }} / {{ item.family }}</td>
              <td class="mono">{{ item.id }}</td>
              <td>{{ item.protocols.join(" · ") }}</td>
              <td>
                {{ item.implemented ? "已实现协议适配" : "规划中" }}
                <p class="secondary-line" :title="item.verification">
                  {{
                    item.verification === "SIMULATOR_TESTED_HARDWARE_PENDING"
                      ? "协议模拟器已验证 · 真机待验收"
                      : item.verification === "HARDWARE_VERIFIED_SCOPED"
                        ? "指定机型真机已验证"
                        : item.verification
                  }}
                </p>
              </td>
              <td>{{ item.notes }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p
        v-if="!support.data.value?.items.length"
        class="panel-body small muted"
      >
        尚无支持目录记录。
      </p>
    </section>
  </div>
</template>
<style scoped>
.device-collection {
  margin-top: 22px;
}
.device-connection-grid {
  align-items: start;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 18px;
}
.device-collection :deep(.device-reading) {
  border-top: 1px solid var(--border);
}
.device-collection :deep(.device-reading-details) {
  margin-top: 16px;
}
.device-collection :deep(summary) {
  cursor: pointer;
  font-weight: 600;
  padding: 10px 0;
}
.device-collection :deep(.wb-facts dd) {
  overflow-wrap: anywhere;
}
.section-toolbar {
  flex-wrap: wrap;
}
.section-toolbar > div {
  flex: 1;
  min-width: 220px;
}
@media (max-width: 1100px) {
  .device-connection-grid {
    grid-template-columns: 1fr;
  }
}
</style>
