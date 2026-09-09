<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { Plus, RefreshCw } from "@lucide/vue";
import { request } from "../services/api";
import { useApiQuery } from "../services/queries";
import {
  actionError,
  endpoint,
  initialWindow,
  qualityText,
  trafficStatus,
  useTrafficHistory,
  validateWindow,
  type NatEvent,
  type NatSource,
} from "../services/traffic";
import { useSessionStore } from "../stores/session";
import { usePreferencesStore } from "../stores/preferences";
import { formatTime } from "../utils/format";
import QueryState from "../components/QueryState.vue";
import ModalDialog from "../components/ModalDialog.vue";
import DevicePicker from "../components/DevicePicker.vue";
const route = useRoute(),
  auth = useSessionStore(),
  prefs = usePreferencesStore();
const admin = computed(() => auth.session?.roles.includes("ADMIN") ?? false);
const sources = useApiQuery<{ items: NatSource[] }>("/nat-audit/sources");
const deviceId = ref(String(route.query.deviceId || "")),
  window = initialWindow();
const range = ref("all");
const from = ref(window.from),
  to = ref(window.to),
  protocol = ref(""),
  privateIp = ref(""),
  publicIp = ref("");
const history = useTrafficHistory<NatEvent>("/nat-audit/events"),
  queryError = ref("");
const detail = ref<NatEvent | null>(null),
  pending = ref(""),
  error = ref(""),
  notice = ref("");
const editorOpen = ref(false),
  editDevice = ref(""),
  revision = ref(0),
  sourceAddress = ref(""),
  enabled = ref(false);
const eventNames = {
  CREATE: "创建观测",
  DELETE: "删除观测",
  POOL_EXHAUSTED: "地址池耗尽",
};
function recentWindow() {
  range.value = "custom";
  const value = initialWindow();
  from.value = value.from;
  to.value = value.to;
}
watch(
  () => route.query.deviceId,
  (value) => {
    deviceId.value = String(value || "");
  },
);
watch(
  [deviceId, from, to, protocol, privateIp, publicIp, range],
  () => {
    history.clear();
    detail.value = null;
  },
  { flush: "sync" },
);
function query() {
  queryError.value = "";
  try {
    if (!deviceId.value) throw Error("请先选择观察网关。");
    if (
      protocol.value !== "" &&
      (!/^\d+$/.test(protocol.value) || Number(protocol.value) > 255)
    )
      throw Error("协议号须为 0–255 的整数。");
    history.submit({
      deviceId: deviceId.value,
      ...(range.value === "all"
        ? {}
        : validateWindow(from.value, to.value, Number.MAX_SAFE_INTEGER)),
      protocol: protocol.value,
      privateIp: privateIp.value.trim(),
      publicIp: publicIp.value.trim(),
    });
  } catch (e) {
    queryError.value = actionError(e);
  }
}
watch(
  () => sources.data.value?.items,
  (items) => {
    if (!deviceId.value && items?.length)
      deviceId.value =
        items.find((s) => s.enabled)?.deviceId || items[0]!.deviceId;
  },
  { immediate: true },
);
watch(
  [deviceId, range],
  () => {
    if (deviceId.value) query();
  },
  { immediate: true, flush: "post" },
);
function edit(source?: NatSource) {
  const id = source?.deviceId || deviceId.value;
  source =
    source || sources.data.value?.items.find((item) => item.deviceId === id);
  editDevice.value = id;
  revision.value = source?.revision || 0;
  sourceAddress.value = source?.sourceAddress || "";
  enabled.value = source?.enabled ?? false;
  error.value = "";
  editorOpen.value = true;
}
watch(editDevice, (value) => {
  const source = sources.data.value?.items.find(
    (item) => item.deviceId === value,
  );
  revision.value = source?.revision || 0;
  sourceAddress.value = source?.sourceAddress || "";
  enabled.value = source?.enabled ?? false;
});
async function mutate(id: string, action: "settings" | "state", body: object) {
  pending.value = `${id}/${action}`;
  error.value = "";
  notice.value = "";
  try {
    await request<NatSource>(
      `/nat-audit/devices/${encodeURIComponent(id)}/${action}`,
      { method: "POST", body: JSON.stringify(body) },
    );
    editorOpen.value = false;
    notice.value =
      "接收设置已保存，最长约 5 秒生效。已接纳的事件可能继续持久化，历史记录保留。";
  } catch (e) {
    error.value = actionError(e);
  } finally {
    await sources.refetch();
    pending.value = "";
  }
}
function save() {
  if (!editDevice.value) {
    error.value = "请选择设备。";
    return;
  }
  void mutate(editDevice.value, "settings", {
    revision: revision.value,
    enabled: enabled.value,
    sourceAddress: sourceAddress.value.trim() || null,
  });
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>NAT 审计</h1>
      <p>按平台接收时间检索 Cisco HSL 事件，保留设备时钟、来源与证据缺口。</p>
    </div>
    <div class="wb-inline">
      <button class="btn" @click="sources.refetch()">
        <RefreshCw :size="14" />刷新接收状态</button
      ><button
        v-if="admin"
        class="btn primary"
        data-configure-nat
        @click="edit()"
      >
        <Plus :size="14" />配置接收来源
      </button>
    </div>
  </div>
  <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  <p v-if="error && !editorOpen" class="wb-error" role="alert">{{ error }}</p>
  <section class="panel">
    <header class="panel-head">
      <h2>接收来源</h2>
      <span class="small muted"
        >最多 256 个已配置来源 · 无需在此输入设备密码</span
      >
    </header>
    <QueryState
      :pending="sources.isPending.value"
      :error="sources.error.value"
      :empty="!sources.data.value?.items.length"
      empty-title="尚未配置 NAT 接收来源"
      empty-description="管理员绑定已登记网关的实际导出源 IPv4。平台接收开关不会修改路由器导出配置。"
      @retry="sources.refetch()"
    >
      <div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>网关 / 来源地址</th>
              <th>接收状态</th>
              <th>最近报文 / 接纳事件</th>
              <th>持久化确认</th>
              <th>运行计数与质量</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in sources.data.value?.items" :key="s.deviceId">
              <td>
                <RouterLink :to="`/assets/${s.deviceId}?tab=access`">{{
                  s.deviceName
                }}</RouterLink>
                <p class="secondary-line mono">{{ s.sourceAddress }}</p>
              </td>
              <td>
                <strong>{{ trafficStatus[s.status] || s.status }}</strong>
                <p class="secondary-line">
                  {{ s.enabled ? "接收已启用" : "接收已停用" }} · 版本
                  {{ s.revision }}
                </p>
                <p v-if="s.lastError" class="small danger-text">
                  {{ s.lastError }}
                </p>
              </td>
              <td class="small">
                {{ formatTime(s.lastPacketAt, prefs.timezone) }}
                <p class="secondary-line">
                  事件接纳 {{ formatTime(s.lastEventAt, prefs.timezone) }}
                </p>
              </td>
              <td class="small">
                {{ formatTime(s.lastPersistedAt, prefs.timezone) }}
                <p class="secondary-line">
                  确认计数 {{ s.persisted }}（重试可能重复）
                </p>
              </td>
              <td>
                <details>
                  <summary>
                    报文 {{ s.received }} · 序列缺口 {{ s.sequenceGaps }}
                  </summary>
                  <dl class="wb-facts wb-spaced">
                    <dt>模板定义 / 刷新</dt>
                    <dd>{{ s.templates }}</dd>
                    <dt>Kafka 接纳事件</dt>
                    <dd>{{ s.accepted }}</dd>
                    <dt>拒绝报文</dt>
                    <dd>{{ s.dropped }}</dd>
                    <dt>未知模板 / 解析失败</dt>
                    <dd>{{ s.unknownTemplates }} / {{ s.parseErrors }}</dd>
                    <dt>重复报文 / 重启</dt>
                    <dd>{{ s.duplicatePackets }} / {{ s.restartCount }}</dd>
                  </dl>
                </details>
                <p class="secondary-line">{{ qualityText(s.qualityFlags) }}</p>
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
                    查看事件</button
                  ><button
                    v-if="admin"
                    class="btn small-btn"
                    :disabled="!!pending"
                    @click="edit(s)"
                  >
                    编辑</button
                  ><button
                    v-if="admin"
                    class="btn small-btn"
                    :data-nat-state="s.deviceId"
                    :disabled="!!pending"
                    @click="
                      mutate(s.deviceId, 'state', {
                        revision: s.revision,
                        enabled: !s.enabled,
                      })
                    "
                  >
                    {{ s.enabled ? "停用接收" : "启用接收" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </QueryState>
    <p class="wb-note">
      计数自来源配置创建累计；序列缺口是跳号观测，不代表丢失事件数。UDP
      无法保证完整接收，计数为 0
      也不是无丢失证明；接收器重启可能丢失尚未刷新计数。
    </p>
  </section>
  <section class="panel wb-spaced">
    <header class="panel-head">
      <h2>事件查询</h2>
      <span class="small muted">默认全部保留历史 · 按接收时间从新到旧</span>
    </header>
    <form class="wb-form" data-nat-query @submit.prevent="query">
      <div class="form-grid">
        <div class="form-field">
          <label for="nat-device">观察网关</label
          ><DevicePicker id="nat-device" v-model="deviceId" />
        </div>
        <div class="form-field">
          <label for="nat-range">接收时间范围</label
          ><select id="nat-range" v-model="range" class="input">
            <option value="all">全部保留历史</option>
            <option value="custom">自定义时间范围</option>
          </select>
        </div>
        <div v-if="range === 'custom'" class="form-field">
          <label for="nat-from">接收开始 · UTC</label
          ><input
            id="nat-from"
            v-model="from"
            class="input"
            type="datetime-local"
            step="1"
            required
          />
        </div>
        <div v-if="range === 'custom'" class="form-field">
          <label for="nat-to">接收结束 · UTC</label
          ><input
            id="nat-to"
            v-model="to"
            class="input"
            type="datetime-local"
            step="1"
            required
          />
        </div>
        <div class="form-field">
          <label for="nat-protocol">协议号（空为全部）</label
          ><input
            id="nat-protocol"
            v-model="protocol"
            class="input"
            type="number"
            min="0"
            max="255"
            placeholder="6 TCP / 17 UDP"
          />
        </div>
        <div class="form-field">
          <label for="nat-private">私网 IPv4 · 精确匹配</label
          ><input
            id="nat-private"
            v-model="privateIp"
            class="input mono"
            maxlength="15"
            placeholder="可选"
          />
        </div>
        <div class="form-field">
          <label for="nat-public">公网 IPv4 · 精确匹配</label
          ><input
            id="nat-public"
            v-model="publicIp"
            class="input mono"
            maxlength="15"
            placeholder="可选"
          />
        </div>
      </div>
      <div class="wb-inline">
        <button class="btn primary" :disabled="history.isFetching.value">
          查询事件</button
        ><button class="btn" type="button" @click="recentWindow">
          设为最近 15 分钟</button
        ><span class="small muted"
          >默认从最新记录开始。分页保持同一查询截止时间，点击查询可重新读取最新记录。</span
        >
      </div>
    </form>
    <p v-if="queryError" class="wb-error" role="alert">{{ queryError }}</p>
    <template v-if="history.filters.value"
      ><p class="wb-note">
        <template v-if="history.filters.value.from"
          >已提交接收范围 {{ history.filters.value.from }} 至
          {{ history.filters.value.to }}。</template
        ><template v-else>查询全部保留历史，按平台接收时间从新到旧。</template
        >单条创建或删除观测不等于完整会话，不能据此确定用户身份。
      </p>
      <QueryState
        :pending="history.isPending.value"
        :error="history.error.value"
        :empty="!history.data.value?.items.length"
        empty-title="当前范围没有保留事件"
        :empty-description="
          history.data.value?.nextCursor
            ? '本页重复报文已去重，仍有更早记录，请点击下一页继续。'
            : '此结果不表示从未发生 NAT；请检查接收状态、模板和时间范围。'
        "
        @retry="history.refetch()"
        ><div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>事件 / 协议</th>
                <th>私网 → 公网</th>
                <th>目标 / 转换后目标</th>
                <th>平台接收时间 {{ prefs.timezone }}</th>
                <th>设备事件时间</th>
                <th>质量 / 来源</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="e in history.data.value?.items"
                :key="e.id"
                :data-nat-event="e.id"
              >
                <td>
                  {{ eventNames[e.eventType] }}
                  <p class="secondary-line">
                    {{
                      e.protocol === 6
                        ? "TCP · 6"
                        : e.protocol === 17
                          ? "UDP · 17"
                          : (e.protocol ?? "—")
                    }}
                    · VRF {{ e.vrfId ?? "—" }}
                  </p>
                </td>
                <td class="mono small">
                  {{ endpoint(e.privateIp, e.privatePort) }}
                  <p>→ {{ endpoint(e.publicIp, e.publicPort) }}</p>
                </td>
                <td class="mono small">
                  {{ endpoint(e.destinationIp, e.destinationPort) }}
                  <p>
                    →
                    {{
                      endpoint(
                        e.translatedDestinationIp,
                        e.translatedDestinationPort,
                      )
                    }}
                  </p>
                </td>
                <td class="small">
                  {{ formatTime(e.receivedAt, prefs.timezone) }}
                </td>
                <td class="small">
                  {{
                    e.deviceEventAt
                      ? formatTime(e.deviceEventAt, prefs.timezone)
                      : "设备时间缺失"
                  }}
                </td>
                <td>
                  <p class="small">
                    {{
                      qualityText(e.qualityFlags) ||
                      "无附加标记；完整性仍不保证"
                    }}
                  </p>
                  <button class="btn small-btn" @click="detail = e">
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
          {{ history.data.value?.items.length || 0 }} 条；不是历史总数</span
        ><button
          class="btn small-btn"
          :disabled="!history.hasPrevious.value || history.isFetching.value"
          @click="history.back"
        >
          上一页</button
        ><button
          class="btn small-btn"
          data-nat-next
          :disabled="
            !history.data.value?.nextCursor || history.isFetching.value
          "
          @click="history.next"
        >
          下一页
        </button>
      </div>
    </template>
    <p v-else class="wb-note">
      选择网关并提交时间范围，读取该组织内的实际 NAT 事件。
    </p>
  </section>
  <ModalDialog
    :open="editorOpen"
    title="配置 NAT 接收来源"
    @close="editorOpen = false"
    ><form class="wb-form" data-nat-settings @submit.prevent="save">
      <div class="form-field">
        <label for="nat-edit-device">登记设备</label
        ><DevicePicker id="nat-edit-device" v-model="editDevice" />
      </div>
      <div class="form-field">
        <label for="nat-source-address">实际导出源 IPv4</label
        ><input
          id="nat-source-address"
          v-model="sourceAddress"
          class="input mono"
          maxlength="15"
          placeholder="留空使用当前设备管理 IPv4"
        /><small class="muted"
          >单个 IPv4，不支持域名或
          CIDR；与路由器实际源地址一致。此页面不修改路由器配置。</small
        >
      </div>
      <label class="wb-inline"
        ><input v-model="enabled" type="checkbox" />启用平台接收</label
      >
      <p class="small muted">
        独立版本 {{ revision }} · 无设备凭据 · 仅 Cisco NAT HSL NetFlow v9
      </p>
      <p v-if="error" class="wb-error" role="alert">{{ error }}</p>
      <div class="wb-inline">
        <button class="btn primary" :disabled="!!pending">保存接收设置</button
        ><button
          type="button"
          class="btn"
          :disabled="!!pending"
          @click="
            edit(
              sources.data.value?.items.find((s) => s.deviceId === editDevice),
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
    title="NAT 事件来源记录"
    wide
    @close="detail = null"
    ><div class="wb-form">
      <p class="wb-note">
        事件时间、导出时间和接收时间分别保留；SHA-256
        用于来源关联，不代表签名或完整会话证据。非 TCP/UDP
        的端口值不解释为传输层端口。
      </p>
      <pre class="wb-raw">{{ JSON.stringify(detail, null, 2) }}</pre>
    </div></ModalDialog
  >
</template>
