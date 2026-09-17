<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useQueryClient } from "@tanstack/vue-query";
import { ArrowLeft, RefreshCw } from "@lucide/vue";
import { useApiQuery } from "../services/queries";
import { usePagedQuery } from "../services/workbench";
import { request, queryString, ApiError } from "../services/api";
import {
  candidateLabels,
  discoveryReasonLabels,
  canonicalDiscoveryCidr,
  type DiscoveryCandidate,
  type DiscoveryRun,
  type NeighborEvidence,
} from "../services/discovery";
import type { Device, Page, Site } from "../services/types";
import { useSessionStore } from "../stores/session";
import { usePreferencesStore } from "../stores/preferences";
import { formatTime, stateLabel } from "../utils/format";
import QueryState from "../components/QueryState.vue";
import PagePager from "../components/PagePager.vue";
import ModalDialog from "../components/ModalDialog.vue";
const auth = useSessionStore(),
  prefs = usePreferencesStore(),
  route = useRoute(),
  router = useRouter(),
  client = useQueryClient();
const siteId = ref(String(route.query.siteId || "")),
  status = ref("");
const sites = useApiQuery<Page<Site>>("/sites");
const list = usePagedQuery<DiscoveryCandidate>(
  "/discovery/candidates",
  computed(() => ({ siteId: siteId.value, status: status.value })),
);
const cidr = ref(""),
  sourceQuery = ref(""),
  sourceChoice = ref("");
const sources = ref<{ id: string; name: string; managementAddress: string }[]>(
  [],
);
const sourceDevices = useApiQuery<Page<Device>>(
  computed(
    () =>
      `/devices${queryString({ siteId: siteId.value, q: sourceQuery.value, limit: 20 })}`,
  ),
  () => auth.canWrite && !!siteId.value,
);
const pending = ref(false),
  error = ref(""),
  runResult = ref<DiscoveryRun | null>(null);
const selected = ref<DiscoveryCandidate | null>(null),
  name = ref(""),
  type = ref("HOST"),
  independentConfirmed = ref(false),
  targetId = ref(""),
  targetQuery = ref("");
const targets = useApiQuery<Page<Device>>(
  computed(
    () =>
      `/devices${queryString({ siteId: selected.value?.siteId, q: targetQuery.value, limit: 20 })}`,
  ),
  () => auth.canWrite && !!selected.value && !selected.value.associatedDeviceId,
);
const sourceLabels = {
  USED: "已使用",
  MISSING: "缺少邻居读取",
  STALE: "来源已过期",
  INVALID: "来源格式无效",
};
const siteName = (id: string) =>
  sites.data.value?.items.find((s) => s.id === id)?.name || id;
const reasonLabel = (reason: string) => discoveryReasonLabels[reason] || reason;
const evidenceExpiry = (e: NeighborEvidence) =>
  !e.validUntil
    ? "未提供 TTL 到期时间"
    : `${Date.parse(e.validUntil) <= Date.parse(list.data.value?.asOf || new Date().toISOString()) ? "TTL 已过期" : "TTL 到期"} · ${formatTime(e.validUntil, prefs.timezone)}`;
watch(siteId, () => {
  sources.value = [];
  sourceChoice.value = "";
  sourceQuery.value = "";
  runResult.value = null;
  selected.value = null;
  error.value = "";
});
watch(status, () => {
  selected.value = null;
});
watch(sourceQuery, () => {
  sourceChoice.value = "";
});
watch(targetQuery, () => {
  targetId.value = "";
});
function addSource() {
  const device = sourceDevices.data.value?.items.find(
    (d) => d.id === sourceChoice.value,
  );
  if (
    device &&
    sources.value.length < 8 &&
    !sources.value.some((d) => d.id === device.id)
  )
    sources.value.push({
      id: device.id,
      name: device.name,
      managementAddress: device.managementAddress,
    });
  sourceChoice.value = "";
}
async function generate() {
  if (!auth.canWrite || pending.value) return;
  error.value = "";
  if (!siteId.value || !sources.value.length) {
    error.value = "请选择站点与 1–8 个来源设备。";
    return;
  }
  if (!canonicalDiscoveryCidr(cidr.value.trim())) {
    error.value =
      "请输入准确的 IPv4 CIDR 网络地址（/24–/32），不接受通配符、前导零或带主机位的网段。";
    return;
  }
  pending.value = true;
  try {
    const value = await request<DiscoveryRun>("/discovery/runs", {
      method: "POST",
      body: JSON.stringify({
        sourceDeviceIds: sources.value.map((d) => d.id),
        cidr: cidr.value.trim(),
        siteId: siteId.value,
      }),
    });
    runResult.value = value;
    await list.refetch();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "发现运行失败，请重试。";
  } finally {
    pending.value = false;
  }
}
function review(item: DiscoveryCandidate) {
  selected.value = item;
  name.value = item.name || item.address;
  type.value = "HOST";
  independentConfirmed.value = false;
  targetId.value = "";
  targetQuery.value = "";
  error.value = "";
}
async function reread() {
  const id = selected.value?.id;
  const value = await list.refetch();
  if (id) {
    const item = value.data?.items.find((c) => c.id === id);
    if (item) selected.value = item;
    else {
      selected.value = null;
      error.value = "候选已不在当前筛选页，请重新查找后审核。";
    }
  }
}
async function resolve(action: "register" | "link") {
  const item = selected.value;
  if (!item || !auth.canWrite || pending.value || item.associatedDeviceId)
    return;
  error.value = "";
  if (action === "register") {
    if (item.status === "CONFLICT") {
      error.value = "存在身份线索冲突，请调查后明确关联已有资产。";
      return;
    }
    if (item.status === "POSSIBLE_DUPLICATE" && !independentConfirmed.value) {
      error.value = "请先核对重复疑点，并确认登记为独立资产。";
      return;
    }
    if (!name.value.trim()) {
      error.value = "请填写新资产名称。";
      return;
    }
  } else if (!targetId.value) {
    error.value = "请选择同站点的已有资产。";
    return;
  }
  pending.value = true;
  try {
    const value = await request<DiscoveryCandidate>(
      `/discovery/candidates/${encodeURIComponent(item.id)}/${action}`,
      {
        method: "POST",
        body: JSON.stringify(
          action === "register"
            ? {
                revision: item.revision,
                name: name.value.trim(),
                type: type.value,
              }
            : { revision: item.revision, deviceId: targetId.value },
        ),
      },
    );
    await client.invalidateQueries({ queryKey: ["noeriva"] });
    selected.value = value;
    if (value.associatedDeviceId) {
      selected.value = null;
      await router.push({
        path: `/assets/${encodeURIComponent(value.associatedDeviceId)}`,
        query: action === "register" ? { tab: "access" } : {},
      });
    }
  } catch (e) {
    error.value =
      e instanceof ApiError && e.status === 409
        ? "候选已变化或存在关联冲突，已重新读取；请核对证据和版本后重试。"
        : e instanceof Error
          ? e.message
          : "操作失败，请重试。";
    if (e instanceof ApiError && e.status === 409) await reread();
  } finally {
    pending.value = false;
  }
}
</script>
<template>
  <RouterLink class="small muted" to="/assets"
    ><ArrowLeft :size="13" aria-hidden="true" /> 返回资产目录</RouterLink
  >
  <div class="page-heading section-gap">
    <div>
      <h1>发现设备</h1>
      <p>从已有邻居证据查找候选，核对重复疑点，再登记或关联资产。</p>
    </div>
    <button class="btn" :disabled="pending" @click="reread()">
      <RefreshCw :size="14" aria-hidden="true" />刷新候选
    </button>
  </div>
  <p class="notice">
    只读取来源设备已保存的 ARP / LLDP / CDP
    事实，不扫描或连接候选地址。IP、MAC、名称相同均不直接证明是同一台物理设备。
  </p>
  <p v-if="error && !selected" class="wb-error section-gap" role="alert">
    {{ error }}
  </p>
  <section class="panel section-gap">
    <header class="panel-head">
      <div>
        <h2>范围与来源</h2>
        <p>候选按站点隔离。生成运行只使用最近 15 分钟内的来源读取。</p>
      </div>
    </header>
    <div class="wb-tools">
      <label for="discovery-site">站点</label
      ><select
        id="discovery-site"
        v-model="siteId"
        class="input"
        :disabled="pending"
      >
        <option value="">全部候选 · 生成前请选择站点</option>
        <option
          v-for="site in sites.data.value?.items"
          :key="site.id"
          :value="site.id"
        >
          {{ site.name }}
        </option></select
      ><small v-if="sites.error.value" class="danger-text">{{
        sites.error.value.message
      }}</small>
    </div>
    <form
      v-if="auth.canWrite"
      data-run-discovery
      class="wb-form"
      @submit.prevent="generate"
    >
      <div class="form-field">
        <label for="discovery-cidr">准确 IPv4 CIDR 范围</label
        ><input
          id="discovery-cidr"
          v-model="cidr"
          class="input mono"
          required
          maxlength="18"
          placeholder="例如 192.0.2.0/24，请填写实际网段"
          :disabled="pending"
        /><small class="muted"
          >支持 /24–/32。请填写完整网段，不接受 * 通配符或省略的地址。</small
        >
      </div>
      <fieldset class="discovery-source-fieldset">
        <legend>已保存读取的来源设备 · {{ sources.length }} / 8</legend>
        <p v-if="!siteId" class="muted small">先选择来源所属站点。</p>
        <template v-else
          ><div class="form-grid">
            <div class="form-field">
              <label for="discovery-source-query">查找来源</label
              ><input
                id="discovery-source-query"
                v-model.lazy="sourceQuery"
                @keydown.enter.prevent="
                  sourceQuery = ($event.target as HTMLInputElement).value
                "
                class="input"
                placeholder="名称或 IP 前缀，回车查询"
                :disabled="pending"
              />
            </div>
            <div class="form-field">
              <label for="discovery-source">来源设备</label
              ><select
                id="discovery-source"
                v-model="sourceChoice"
                class="input"
                :disabled="pending || sourceDevices.isPending.value"
              >
                <option value="">选择来源设备</option>
                <option
                  v-for="device in sourceDevices.data.value?.items"
                  :key="device.id"
                  :value="device.id"
                  :disabled="sources.some((d) => d.id === device.id)"
                >
                  {{ device.name }} · {{ device.managementAddress }}
                </option>
              </select>
            </div>
          </div>
          <button
            data-add-source
            type="button"
            class="btn"
            :disabled="!sourceChoice || sources.length >= 8 || pending"
            @click="addSource"
          >
            添加来源
          </button>
          <p class="small muted">
            当前查找最多 20 项。没有 SSH 邻居结果的来源会在运行中报告缺失。
          </p>
          <p v-if="sourceDevices.error.value" class="wb-error">
            {{ sourceDevices.error.value.message }}
          </p></template
        >
        <ul v-if="sources.length" class="discovery-source-list">
          <li v-for="source in sources" :key="source.id">
            <span
              ><strong>{{ source.name }}</strong> ·
              <span class="mono">{{ source.managementAddress }}</span></span
            ><RouterLink
              :to="{ path: `/assets/${source.id}`, query: { tab: 'access' } }"
              >查看接入与采集</RouterLink
            ><button
              type="button"
              class="btn small-btn"
              :disabled="pending"
              :aria-label="`移除来源 ${source.name}`"
              @click="sources = sources.filter((d) => d.id !== source.id)"
            >
              移除
            </button>
          </li>
        </ul>
      </fieldset>
      <p class="wb-note">
        管理员可先进入来源设备“接入与采集”，配置 SSH
        并执行“立即采集”，再回到此处生成候选。这里不会自动执行采集，也不会复制来源凭据。
      </p>
      <div class="actions">
        <button
          class="btn primary"
          :disabled="pending || !siteId || !sources.length"
        >
          {{ pending ? "正在处理…" : "生成发现候选" }}
        </button>
      </div>
    </form>
    <p v-else class="panel-body muted">
      当前账号可查看候选与证据；生成、登记和关联需要操作员或管理员权限。
    </p>
  </section>
  <section
    v-if="runResult"
    class="panel section-gap"
    aria-label="本次发现运行结果"
  >
    <header class="panel-head">
      <div>
        <h2>本次运行结果</h2>
        <p>
          {{ runResult.cidr }} · {{ siteName(runResult.siteId) }} ·
          {{ formatTime(runResult.asOf, prefs.timezone) }}
        </p>
      </div>
      <span class="small muted">{{ runResult.id }}</span>
    </header>
    <div class="panel-body">
      <div class="discovery-run-counts">
        <span
          >来源 {{ runResult.sourcesUsed }} /
          {{ runResult.sourcesRequested }}</span
        ><span>检查证据 {{ runResult.observationsRead }}</span
        ><span>更新候选 {{ runResult.candidatesUpdated }}</span
        ><span>已有资产 {{ runResult.existingCount }}</span
        ><span>疑似重复 {{ runResult.duplicateCount }}</span
        ><span>冲突 {{ runResult.conflictCount }}</span>
      </div>
      <p class="small muted">
        计数只属于本次运行；没有有效证据不代表网段没有设备。
      </p>
      <p v-if="runResult.qualityFlags.length" class="notice">
        质量标记 · {{ runResult.qualityFlags.join(" · ") }}
      </p>
    </div>
    <div
      class="table-scroll"
      role="region"
      aria-label="来源读取结果"
      tabindex="0"
    >
      <table class="data-table">
        <thead>
          <tr>
            <th>来源</th>
            <th>结果</th>
            <th>来源观察时间</th>
            <th>接受行数 / 说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="source in runResult.sources" :key="source.deviceId">
            <td>
              <RouterLink
                :to="{
                  path: `/assets/${source.deviceId}`,
                  query: { tab: 'access' },
                }"
                >{{ source.deviceName }}</RouterLink
              >
            </td>
            <td :title="source.status">{{ sourceLabels[source.status] }}</td>
            <td class="mono small">
              {{ formatTime(source.observedAt, prefs.timezone) }}
            </td>
            <td>
              {{ source.acceptedCount }}
              <div class="secondary-line">{{ source.reason || "—" }}</div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
  <section class="panel section-gap">
    <header class="panel-head">
      <div>
        <h2>候选审核</h2>
        <p>同站点同 IP 更新一条候选；历史资产与连接保持独立。</p>
      </div>
      <select v-model="status" aria-label="候选状态" :disabled="pending">
        <option value="">全部状态</option>
        <option v-for="(label, key) in candidateLabels" :key="key" :value="key">
          {{ label }}
        </option>
      </select>
    </header>
    <QueryState
      :pending="list.isPending.value"
      :error="list.error.value"
      :empty="!list.data.value?.items.length"
      empty-title="当前范围尚无候选"
      empty-description="选择来源和准确 CIDR 生成候选。空列表只表示没有匹配的保留证据。"
      @retry="list.refetch()"
    >
      <div
        class="table-scroll"
        role="region"
        aria-label="发现候选列表"
        tabindex="0"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>地址 / 名称</th>
              <th>站点 / MAC</th>
              <th>审核状态 / 疑点</th>
              <th>最近来源观测</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in list.data.value?.items" :key="item.id">
              <td>
                <strong class="mono">{{ item.address }}</strong>
                <div class="secondary-line">
                  {{ item.name || "未提供名称" }}
                </div>
              </td>
              <td>
                {{ siteName(item.siteId) }}
                <div class="secondary-line mono">
                  {{ item.mac || "MAC 未提供" }}
                </div>
              </td>
              <td>
                <span class="tag" :title="item.status">{{
                  candidateLabels[item.status]
                }}</span>
                <p
                  v-for="reason in item.reasons"
                  :key="reason"
                  class="secondary-line"
                  :title="reason"
                >
                  {{ reasonLabel(reason) }}
                </p>
              </td>
              <td class="mono small">
                {{ formatTime(item.lastSeenAt, prefs.timezone) }}
                <div class="secondary-line">不是在线证明</div>
              </td>
              <td>
                <button
                  :data-review="item.id"
                  class="btn small-btn"
                  @click="review(item)"
                >
                  审核证据</button
                ><RouterLink
                  v-if="item.associatedDeviceId"
                  class="small"
                  :to="`/assets/${item.associatedDeviceId}`"
                  >查看关联资产</RouterLink
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </QueryState>
    <PagePager
      :page="list.page.value"
      :count="list.data.value?.items.length || 0"
      :previous="list.hasPrevious.value"
      :next="!!list.data.value?.nextCursor"
      :pending="list.isFetching.value"
      @back="list.back()"
      @forward="list.next()"
    />
  </section>
  <ModalDialog
    :open="!!selected"
    title="审核发现候选"
    wide
    @close="selected = null"
  >
    <div v-if="selected" class="form-body discovery-review">
      <h3>{{ selected.address }} · {{ selected.name || "未提供名称" }}</h3>
      <p class="small muted">
        {{ siteName(selected.siteId) }} ·
        {{ candidateLabels[selected.status] }} · 版本 {{ selected.revision }}
      </p>
      <p
        v-for="reason in selected.reasons"
        :key="reason"
        class="notice"
        :title="reason"
      >
        {{ reasonLabel(reason) }}
      </p>
      <p class="wb-note">
        关联只记录候选对应的已有资产，不修改目标地址、历史或凭据。共享
        MAC、名称、主机密钥都不能用于自动合并物理设备。
      </p>
      <dl class="wb-facts">
        <dt>首次来源观测</dt>
        <dd>{{ formatTime(selected.firstSeenAt, prefs.timezone) }}</dd>
        <dt>最近来源观测</dt>
        <dd>{{ formatTime(selected.lastSeenAt, prefs.timezone) }}</dd>
      </dl>
      <details
        v-for="(e, index) in selected.evidence"
        :key="`${e.sourceDeviceId}/${e.source}/${index}`"
        class="discovery-evidence"
        open
      >
        <summary>
          {{ e.sourceDeviceName }} · {{ e.source }} ·
          {{ formatTime(e.observedAt, prefs.timezone) }}
        </summary>
        <dl class="wb-facts">
          <dt>地址 / MAC</dt>
          <dd class="mono">{{ e.address }} / {{ e.mac || "未知" }}</dd>
          <dt>接口 / VLAN</dt>
          <dd>{{ e.interfaceName || "未知" }} / {{ e.vlan || "未知" }}</dd>
          <dt>邻居名称</dt>
          <dd>{{ e.name || "未提供" }}</dd>
          <dt>机箱 / 类型</dt>
          <dd>
            {{ e.chassisId || "未提供" }} / {{ e.chassisSubtype || "未提供" }}
          </dd>
          <dt>远端端口</dt>
          <dd>{{ e.portId || "未提供" }}</dd>
          <dt>ARP 表年龄</dt>
          <dd>
            {{
              e.ageMinutes === null
                ? "未提供"
                : `${e.ageMinutes} 分钟（来源返回）`
            }}
          </dd>
          <dt>TTL</dt>
          <dd>
            {{ e.ttlSeconds === null ? "未提供" : `${e.ttlSeconds} 秒` }} ·
            {{ evidenceExpiry(e) }}
          </dd>
          <dt>质量标记</dt>
          <dd>{{ e.qualityFlags.join(" · ") || "无额外标记" }}</dd>
        </dl>
        <RouterLink
          class="small"
          :to="{
            path: `/assets/${e.sourceDeviceId}`,
            query: { tab: 'access' },
          }"
          >查看来源设备</RouterLink
        >
      </details>
      <p v-if="!selected.evidence.length" class="notice">
        没有可显示的保留证据，请重新生成并核查来源。
      </p>
      <p v-if="error" class="wb-error" role="alert">{{ error }}</p>
      <RouterLink
        v-if="selected.associatedDeviceId"
        class="btn primary"
        :to="`/assets/${selected.associatedDeviceId}`"
        >查看已关联资产</RouterLink
      >
      <template v-else-if="auth.canWrite">
        <p v-if="selected.status === 'CONFLICT'" class="notice">
          存在身份线索冲突，不能直接登记新资产。请调查后明确关联已有资产；冲突证据会保留。
        </p>
        <form
          v-else
          data-register-candidate
          class="discovery-resolve"
          @submit.prevent="resolve('register')"
        >
          <h3>登记新资产</h3>
          <div class="form-grid">
            <div class="form-field">
              <label for="discovery-register-name">新资产名称</label
              ><input
                id="discovery-register-name"
                v-model="name"
                class="input"
                maxlength="120"
                required
                :disabled="pending"
              />
            </div>
            <div class="form-field">
              <label for="discovery-register-type">类型</label
              ><select
                id="discovery-register-type"
                v-model="type"
                class="input"
                :disabled="pending"
              >
                <option
                  v-for="item in [
                    'HOST',
                    'BMC',
                    'SWITCH',
                    'ROUTER',
                    'FIREWALL',
                  ]"
                  :key="item"
                  :value="item"
                >
                  {{ stateLabel(item) }}
                </option>
              </select>
            </div>
          </div>
          <label
            v-if="selected.status === 'POSSIBLE_DUPLICATE'"
            class="discovery-confirm"
            ><input
              id="discovery-register-confirm"
              v-model="independentConfirmed"
              type="checkbox"
              required
              :disabled="pending"
            />已核对重复疑点，确认登记为独立资产。</label
          >
          <p class="small muted">
            使用候选地址和站点，初始状态未知；不会复制来源凭据或启用采集。
          </p>
          <button class="btn primary" :disabled="pending">
            登记并进入接入配置
          </button>
        </form>
        <form
          data-link-candidate
          class="discovery-resolve"
          @submit.prevent="resolve('link')"
        >
          <h3>关联已有资产</h3>
          <div class="form-field">
            <label for="discovery-link-query">查找同站点资产</label
            ><input
              id="discovery-link-query"
              v-model.lazy="targetQuery"
              @keydown.enter.prevent="
                targetQuery = ($event.target as HTMLInputElement).value
              "
              class="input"
              placeholder="名称或 IP 前缀，回车查询"
              :disabled="pending"
            />
          </div>
          <div class="form-field">
            <label for="discovery-link-device">关联目标</label
            ><select
              id="discovery-link-device"
              v-model="targetId"
              class="input"
              required
              :disabled="pending || targets.isPending.value"
            >
              <option value="" disabled>明确选择一个同站点资产</option>
              <option
                v-for="device in targets.data.value?.items"
                :key="device.id"
                :value="device.id"
              >
                {{ device.name }} · {{ device.managementAddress }}
              </option></select
            ><small class="muted"
              >最多显示 20
              项，输入前缀缩小范围。关联不合并历史、不改变管理地址。</small
            >
            <p v-if="targets.error.value" class="wb-error">
              {{ targets.error.value.message }}
            </p>
          </div>
          <button class="btn" :disabled="pending || !targetId">确认关联</button>
        </form>
      </template>
      <button class="btn" :disabled="pending" @click="reread()">
        重新读取候选
      </button>
    </div>
  </ModalDialog>
</template>
<style scoped>
.discovery-source-fieldset {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 16px;
  display: grid;
  gap: 12px;
  min-width: 0;
}
.discovery-source-fieldset legend {
  font-size: 12px;
  padding: 0 5px;
}
.discovery-source-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 10px;
}
.discovery-source-list li {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
}
.discovery-source-list li > span {
  flex: 1;
  min-width: 160px;
}
.discovery-run-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
}
.discovery-review {
  display: grid;
  gap: 16px;
}
.discovery-evidence {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 12px 16px;
}
.discovery-evidence summary {
  cursor: pointer;
  font-weight: 600;
  padding-bottom: 12px;
}
.discovery-evidence > a {
  display: inline-block;
  margin-top: 12px;
}
.discovery-resolve {
  display: grid;
  gap: 12px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}
.discovery-confirm {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
