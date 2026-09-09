<script setup lang="ts">
import { ref } from "vue";
import { Globe, RefreshCw } from "@lucide/vue";
import { useApiQuery } from "../services/queries";
import type { Collector, Page } from "../services/types";
import { formatBytes, formatTime } from "../utils/format";
import { usePreferencesStore } from "../stores/preferences";
import QueryState from "../components/QueryState.vue";
import StatusBadge from "../components/StatusBadge.vue";
import ModalDialog from "../components/ModalDialog.vue";
const prefs = usePreferencesStore();
const { data, isPending, error, refetch } =
  useApiQuery<Page<Collector>>("/collectors");
const selected = ref<Collector | null>(null);
const usage = (collector: Collector) =>
  collector.queueLimitBytes > 0
    ? Math.min(
        100,
        Math.max(0, (collector.queueBytes / collector.queueLimitBytes) * 100),
      )
    : 0;
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>采集器</h1>
      <p>区分中央连接、站点采集和缓冲状态，核对数据连续性。</p>
    </div>
    <button class="btn" @click="refetch()">
      <RefreshCw aria-hidden="true" :size="14" />刷新
    </button>
  </div>
  <QueryState
    :pending="isPending"
    :error="error"
    :empty="!data?.items.length"
    empty-title="尚未登记采集器"
    @retry="refetch()"
    ><div class="collector-grid">
      <section
        v-for="collector in data?.items"
        :key="collector.id"
        class="panel collector-card"
      >
        <header class="section-heading">
          <div class="inline-actions">
            <Globe aria-hidden="true" :size="19" />
            <h2 class="mono">{{ collector.name }}</h2>
          </div>
          <StatusBadge :status="collector.status" />
        </header>
        <p class="small muted">
          {{ collector.siteId || "未限定站点" }} · {{ collector.version }} ·
          {{ collector.source }}
        </p>
        <dl class="collector-facts">
          <dt>中央连接状态</dt>
          <dd><StatusBadge :status="collector.status" /></dd>
          <dt>最近心跳</dt>
          <dd class="mono">
            {{ formatTime(collector.lastHeartbeat, prefs.timezone) }}
          </dd>
          <dt>最近成功采集</dt>
          <dd class="mono">
            {{ formatTime(collector.lastSuccessfulCollection, prefs.timezone) }}
          </dd>
          <dt>本地队列 / 容量</dt>
          <dd>
            <template v-if="collector.queueLimitBytes > 0">
              {{ formatBytes(collector.queueBytes) }} /
              {{ formatBytes(collector.queueLimitBytes) }}
            </template>
            <template v-else>不适用（无本地缓冲队列）</template>
          </dd>
          <dt>最早排队记录</dt>
          <dd>
            {{
              collector.queueLimitBytes <= 0
                ? "不适用"
                : collector.oldestQueuedAt
                  ? formatTime(collector.oldestQueuedAt, prefs.timezone)
                  : "未提供排队时间"
            }}
          </dd>
        </dl>
        <div
          v-if="collector.queueLimitBytes > 0"
          class="progress-track"
          role="progressbar"
          :aria-label="`${collector.name} 缓冲使用率`"
          :aria-valuenow="Math.round(usage(collector))"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <span :style="{ width: `${usage(collector)}%` }"></span>
        </div>
        <div class="section-gap legend-row">
          <span
            v-for="capability in collector.capabilities"
            :key="capability"
            class="tag"
            >{{ capability }}</span
          >
        </div>
        <div class="collector-footer">
          <p class="small muted">
            {{
              collector.source === "SIMULATED"
                ? "合成采集器样本"
                : "来源状态与设备状态分别记录"
            }}
          </p>
          <button class="btn small-btn" @click="selected = collector">
            查看来源记录
          </button>
        </div>
      </section>
    </div></QueryState
  >
  <div class="notice section-gap">
    <strong>连接正常不意味着所有来源均正常。</strong>
    采集器可达、设备可用性、队列积压与数据过期分别展示；心跳不等于采集成功。
  </div>
  <section class="panel section-gap">
    <header class="panel-head">
      <h2>采集与恢复检查</h2>
      <RouterLink class="small" to="/monitoring">检查监测来源 →</RouterLink>
    </header>
    <div class="table-scroll">
      <table class="data-table">
        <thead>
          <tr>
            <th>检查项</th>
            <th>判断依据</th>
            <th>下一步</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>来源过期</td>
            <td>最近成功采集时间与该来源的观测时间</td>
            <td>在监测页面检查来源覆盖</td>
          </tr>
          <tr>
            <td>缓冲积压</td>
            <td>队列占用、容量和最早排队记录</td>
            <td>核对站点连接和缓冲余量</td>
          </tr>
          <tr>
            <td>恢复后的连续性</td>
            <td>源时间、序列、epoch 与质量标记</td>
            <td>在设备事件中检查缺口和重复</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
  <ModalDialog
    :open="!!selected"
    title="采集器来源记录"
    @close="selected = null"
    ><div v-if="selected" class="form-body">
      <h3>{{ selected.name }}</h3>
      <pre class="raw-json">{{ JSON.stringify(selected, null, 2) }}</pre>
      <RouterLink
        class="btn"
        :to="{
          path: '/monitoring',
          query: selected.siteId ? { siteId: selected.siteId } : {},
        }"
        >{{ selected.siteId ? "检查站点监测来源" : "检查监测来源" }}</RouterLink
      >
    </div></ModalDialog
  >
</template>
