<script setup lang="ts">
import { ref } from "vue";
import { Search, Download, Route as RouteIcon } from "@lucide/vue";
import { request } from "../services/api";
import {
  utcInputNow,
  fromUtcInput,
  downloadJson,
  outcomeNames,
  type Investigation,
  type NetworkEvidence,
} from "../services/workbench";
import { usePreferencesStore } from "../stores/preferences";
import { formatTime } from "../utils/format";
import ModalDialog from "../components/ModalDialog.vue";
const prefs = usePreferencesStore(),
  ip = ref("198.51.100.26"),
  port = ref(54021),
  protocol = ref("TCP"),
  at = ref(utcInputNow()),
  direction = ref("PUBLIC_TO_PRIVATE");
const result = ref<Investigation | null>(null),
  pending = ref(false),
  error = ref(""),
  raw = ref<NetworkEvidence | null>(null);
async function investigate() {
  pending.value = true;
  error.value = "";
  result.value = null;
  try {
    result.value = await request<Investigation>("/workbench/investigations", {
      method: "POST",
      body: JSON.stringify({
        ip: ip.value,
        port: Number(port.value),
        protocol: protocol.value,
        at: fromUtcInput(at.value),
        direction: direction.value,
      }),
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : "查询未完成";
  } finally {
    pending.value = false;
  }
}
const descriptions: Record<string, string> = {
  CONFIRMED:
    "唯一完整映射与同一站点内的历史地址租约覆盖查询时刻，当前保留记录内没有时钟歧义。",
  AMBIGUOUS: "多个候选、重叠租约或时钟不确定区间均可能匹配，不能强行选定归属。",
  INSUFFICIENT_EVIDENCE: "已找到映射，但生命周期或历史地址归属证据不足。",
  NO_MATCH:
    "当前保留的时态记录没有匹配端点；此结果不表示真实环境从未发生连接。",
};
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>关联调查</h1>
      <p>通过 IP、端口、协议与历史有效区间关联映射和地址归属。</p>
      <p class="small">
        <RouterLink to="/nat-audit">查看 NAT 接收事件 ↗</RouterLink> ·
        接收事件不会自动生成完整区间或历史租约。
      </p>
    </div>
  </div>
  <section class="panel">
    <form class="wb-form" @submit.prevent="investigate">
      <div class="form-grid wb-investigation-fields">
        <div class="form-field">
          <label for="investigation-direction">查询方向</label
          ><select
            id="investigation-direction"
            v-model="direction"
            class="input"
          >
            <option value="PUBLIC_TO_PRIVATE">公网 → 私网</option>
            <option value="PRIVATE_TO_PUBLIC">私网 → 公网</option>
          </select>
        </div>
        <div class="form-field">
          <label for="investigation-ip">IP 地址</label
          ><input
            id="investigation-ip"
            v-model="ip"
            class="input mono"
            required
            maxlength="64"
            autocomplete="off"
            placeholder="IPv4 或 IPv6"
          />
        </div>
        <div class="form-field">
          <label for="investigation-port">端口</label
          ><input
            id="investigation-port"
            v-model="port"
            class="input"
            type="number"
            min="1"
            max="65535"
            required
          />
        </div>
        <div class="form-field">
          <label for="investigation-protocol">协议</label
          ><select id="investigation-protocol" v-model="protocol" class="input">
            <option>TCP</option>
            <option>UDP</option>
          </select>
        </div>
        <div class="form-field">
          <label for="investigation-at">查询时刻 · UTC</label
          ><input
            id="investigation-at"
            v-model="at"
            class="input"
            type="datetime-local"
            step="1"
            required
          />
        </div>
      </div>
      <div class="wb-inline">
        <span class="small muted" style="flex: 1"
          >按历史区间查询，不用当前 IP 归属替代过去的证据。</span
        ><button class="btn primary" :disabled="pending">
          <Search :size="14" />{{ pending ? "正在关联…" : "查询证据" }}
        </button>
      </div>
    </form>
  </section>
  <p v-if="error" class="wb-error wb-spaced" role="alert">{{ error }}</p>
  <div
    v-if="!result && !error && !pending"
    class="wb-result-banner"
    style="padding: 48px 20px"
  >
    <RouteIcon :size="26" />
    <div>
      <h2>从一个带时间的端点开始</h2>
      <p class="muted">
        输入端点与 UTC 时刻，查看 NAT 映射、地址租约、来源与不确定性。
      </p>
    </div>
  </div>
  <div v-if="result" aria-live="polite">
    <div class="wb-result-banner">
      <RouteIcon :size="24" />
      <div>
        <h2>
          {{ result.status }} ·
          {{ outcomeNames[result.status] || result.status }}
        </h2>
        <p class="muted">{{ descriptions[result.status] }}</p>
        <p class="small mono wb-spaced">
          {{ result.query.ip }}:{{ result.query.port }} /
          {{ result.query.protocol }} · {{ result.query.at }} ·
          {{ result.query.direction }}
        </p>
      </div>
    </div>
    <section
      v-for="(candidate, index) in result.candidates"
      :key="candidate.nat.id"
      class="panel wb-spaced"
    >
      <header class="panel-head">
        <h2>
          候选 {{ index + 1 }} · {{ candidate.nat.privateIp }}:{{
            candidate.nat.privatePort
          }}
        </h2>
        <span class="small">{{
          candidate.nat.provenance === "SYNTHETIC" ? "合成测试记录" : "来源记录"
        }}</span>
      </header>
      <div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>证据链</th>
              <th>端点 / 对象</th>
              <th>有效区间 {{ prefs.timezone }}</th>
              <th>来源与质量</th>
              <th>原始记录</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>NAT 映射</td>
              <td class="mono">
                {{ candidate.nat.publicIp }}:{{ candidate.nat.publicPort }}
                <p class="secondary-line">
                  → {{ candidate.nat.privateIp }}:{{
                    candidate.nat.privatePort
                  }}
                </p>
              </td>
              <td class="small mono">
                {{ formatTime(candidate.nat.validFrom, prefs.timezone) }}
                <p>
                  至 {{ formatTime(candidate.nat.validTo, prefs.timezone) }}
                </p>
              </td>
              <td>
                {{ candidate.nat.source }}
                <p class="secondary-line">
                  {{ candidate.nat.lifecycle }} · ±{{
                    candidate.nat.clockUncertaintyMs
                  }}
                  ms
                </p>
              </td>
              <td>
                <button class="btn small-btn" @click="raw = candidate.nat">
                  查看来源
                </button>
              </td>
            </tr>
            <tr v-for="lease in candidate.leases" :key="lease.id">
              <td>地址分配</td>
              <td>
                <span class="mono">{{ lease.privateIp }}</span>
                <p class="secondary-line">
                  <RouterLink :to="`/devices/${lease.deviceId}`"
                    >关联设备 ↗</RouterLink
                  >
                </p>
              </td>
              <td class="small mono">
                {{ formatTime(lease.validFrom, prefs.timezone) }}
                <p>至 {{ formatTime(lease.validTo, prefs.timezone) }}</p>
              </td>
              <td>
                {{ lease.source }}
                <p class="secondary-line">
                  {{ lease.lifecycle }} · ±{{ lease.clockUncertaintyMs }} ms
                </p>
              </td>
              <td>
                <button class="btn small-btn" @click="raw = lease">
                  查看来源
                </button>
              </td>
            </tr>
            <tr v-if="!candidate.leases.length">
              <td>地址分配</td>
              <td colspan="4">缺少匹配的历史租约，无法确定归属。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="candidate.qualityFlags.length" class="wb-note">
        质量标记：{{ candidate.qualityFlags.join(" · ") }}
      </div>
    </section>
    <div class="wb-grid wb-spaced">
      <section class="panel">
        <header class="panel-head"><h2>查询解释与质量</h2></header>
        <div class="panel-body">
          <dl class="wb-facts">
            <dt>关联规则</dt>
            <dd>IP、端口、协议、有效区间和有界时钟误差</dd>
            <dt>候选数量</dt>
            <dd>{{ result.candidates.length }}</dd>
            <dt>质量标记</dt>
            <dd>{{ result.qualityFlags.join(" · ") || "无附加标记" }}</dd>
            <dt>查询记录</dt>
            <dd class="mono">{{ result.id }}</dd>
            <dt>未知信息</dt>
            <dd>现实人员身份、载荷、完整 URL、用户意图</dd>
          </dl>
        </div>
      </section>
      <section class="panel">
        <header class="panel-head"><h2>查询输出</h2></header>
        <div class="panel-body">
          <p class="small muted">
            导出本次实际提交的查询定义、结果和来源引用。
          </p>
          <button
            class="btn wb-spaced"
            @click="downloadJson(`investigation-${result.id}.json`, result)"
          >
            <Download :size="14" />导出查询结果
          </button>
        </div>
      </section>
    </div>
  </div>
  <ModalDialog :open="!!raw" title="时态来源记录" wide @close="raw = null"
    ><div class="wb-form">
      <pre class="wb-raw">{{ JSON.stringify(raw, null, 2) }}</pre>
    </div></ModalDialog
  >
</template>
