<script setup lang="ts">
import { computed } from "vue";
import { useApiQuery } from "../services/queries";
import {
  qualityText,
  trafficStatus,
  type ApplicationSource,
  type NatSource,
} from "../services/traffic";
import type { Page } from "../services/types";
import { usePreferencesStore } from "../stores/preferences";
import { formatTime } from "../utils/format";
import QueryState from "./QueryState.vue";
const props = defineProps<{ deviceId: string }>(),
  prefs = usePreferencesStore();
const applications = useApiQuery<Page<ApplicationSource>>(
  "/applications/sources?limit=200",
);
const nat = useApiQuery<{ items: NatSource[] }>("/nat-audit/sources");
const application = computed(() =>
  applications.data.value?.items.find((s) => s.deviceId === props.deviceId),
);
const natSource = computed(() =>
  nat.data.value?.items.find((s) => s.deviceId === props.deviceId),
);
</script>
<template>
  <div class="wb-grid wb-spaced" aria-label="独立网络采集状态">
    <section class="panel">
      <header class="panel-head">
        <h2>独立应用监测</h2>
        <RouterLink :to="{ path: '/applications', query: { deviceId } }"
          >打开应用监测 ↗</RouterLink
        >
      </header>
      <div class="panel-body">
        <QueryState
          :pending="applications.isPending.value"
          :error="applications.error.value"
          @retry="applications.refetch()"
          ><template v-if="application"
            ><strong>{{
              trafficStatus[application.status] || application.status
            }}</strong>
            <p class="secondary-line">
              {{ application.enabled ? "应用周期已启用" : "应用周期已停用" }} ·
              应用版本 {{ application.revision }}
            </p>
            <p class="small wb-spaced">
              最近成功
              {{ formatTime(application.lastSuccessAt, prefs.timezone) }}
            </p>
            <p class="small">
              接口 ifIndex
              {{ application.interfaceIndices.join(", ") || "尚未选择" }} ·
              每轮最多 {{ application.maxRows }} 个协议行
            </p>
            <p v-if="application.errorCode" class="danger-text small">
              {{ application.errorCode }} · {{ application.errorMessage }}
            </p>
            <p class="small muted">
              {{ qualityText(application.qualityFlags) }}
            </p></template
          >
          <p v-else class="small muted">
            {{
              applications.data.value?.nextCursor
                ? "本页来源中未找到该设备。请打开应用监测继续分页查看，不能据此判断未配置。"
                : "尚无可用 SNMP 连接来源。先保存设备 SNMP 连接，再配置独立应用采集。"
            }}
          </p></QueryState
        >
        <p class="small muted wb-spaced">
          复用 SNMP 凭据，应用周期独立启停；不修改设备 NBAR 配置，不提供 NAT
          会话。
        </p>
      </div>
    </section>
    <section class="panel">
      <header class="panel-head">
        <h2>NAT 事件接收</h2>
        <RouterLink :to="{ path: '/nat-audit', query: { deviceId } }"
          >打开 NAT 审计 ↗</RouterLink
        >
      </header>
      <div class="panel-body">
        <QueryState
          :pending="nat.isPending.value"
          :error="nat.error.value"
          @retry="nat.refetch()"
          ><template v-if="natSource"
            ><strong>{{
              trafficStatus[natSource.status] || natSource.status
            }}</strong>
            <p class="secondary-line">
              {{ natSource.enabled ? "接收已启用" : "接收已停用" }} · 接收版本
              {{ natSource.revision }}
            </p>
            <p class="small wb-spaced">
              最近报文 {{ formatTime(natSource.lastPacketAt, prefs.timezone) }}
            </p>
            <p class="small">
              持久化确认
              {{ formatTime(natSource.lastPersistedAt, prefs.timezone) }}
            </p>
            <p v-if="natSource.lastError" class="danger-text small">
              {{ natSource.lastError }}
            </p>
            <p class="small muted">
              {{ qualityText(natSource.qualityFlags) }}
            </p></template
          >
          <p v-else class="small muted">
            尚未绑定 NAT 导出来源。管理员可在 NAT 审计中配置实际源 IPv4
            与平台接收开关。
          </p></QueryState
        >
        <p class="small muted wb-spaced">
          被动接收 HSL 事件；与 SSH、基础 SNMP
          启停分别控制。接收成功不证明完整会话。
        </p>
      </div>
    </section>
  </div>
</template>
