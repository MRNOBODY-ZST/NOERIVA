<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { RefreshCw } from "@lucide/vue";
import { usePagedQuery, type WorkspaceInterface } from "../services/workbench";
import { useApiQuery } from "../services/queries";
import type { Page, Site } from "../services/types";
import { usePreferencesStore } from "../stores/preferences";
import { formatTime, formatRate } from "../utils/format";
import QueryState from "../components/QueryState.vue";
import StatusBadge from "../components/StatusBadge.vue";
import PagePager from "../components/PagePager.vue";
const route = useRoute(),
  prefs = usePreferencesStore(),
  q = ref(""),
  siteId = ref(String(route.query.siteId || ""));
const sites = useApiQuery<Page<Site>>("/sites");
const list = usePagedQuery<WorkspaceInterface>(
  "/workspace/interfaces",
  computed(() => ({
    q: q.value,
    siteId: siteId.value,
    deviceId: String(route.query.deviceId || ""),
  })),
);
watch(
  () => route.query.siteId,
  (value) => {
    siteId.value = String(value || "");
  },
);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>网络接口</h1>
      <p>按接口名称字母顺序排列，跨页查看接口状态、设备归属与带宽历史。</p>
    </div>
    <button class="btn" @click="list.refetch()">
      <RefreshCw :size="14" />刷新
    </button>
  </div>
  <section class="panel">
    <div class="wb-tools">
      <input
        v-model.lazy="q"
        @keydown.enter.prevent="q = ($event.target as HTMLInputElement).value"
        class="input grow"
        aria-label="接口前缀搜索"
        placeholder="接口、设备或 IP 前缀，回车查询"
      /><select v-model="siteId" class="input" aria-label="接口站点">
        <option value="">全部站点</option>
        <option
          v-for="site in sites.data.value?.items"
          :key="site.id"
          :value="site.id"
        >
          {{ site.name }}
        </option></select
      ><button
        class="btn"
        @click="
          q = '';
          siteId = '';
        "
      >
        重置
      </button>
    </div>
    <QueryState
      :pending="list.isPending.value"
      :error="list.error.value"
      :empty="!list.data.value?.items.length"
      empty-title="当前范围没有接口"
      empty-description="调整筛选范围，或联系管理员导入接口记录。"
      @retry="list.refetch()"
      ><div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>接口 / 设备</th>
              <th>站点</th>
              <th>管理状态</th>
              <th>运行状态</th>
              <th>标称速率</th>
              <th>MAC</th>
              <th>设备最近观测</th>
              <th>查看</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in list.data.value?.items || []" :key="item.id">
              <td>
                <strong class="mono">{{ item.name }}</strong>
                <p class="secondary-line">
                  <RouterLink
                    :to="`/devices/${item.deviceId}?tab=network&interfaceId=${encodeURIComponent(item.id)}`"
                    >{{ item.deviceName }}</RouterLink
                  >
                </p>
              </td>
              <td>{{ item.siteName }}</td>
              <td><StatusBadge :status="item.adminStatus" /></td>
              <td><StatusBadge :status="item.operStatus" /></td>
              <td>
                {{
                  item.speedBps === "0"
                    ? "未知"
                    : formatRate(Number(item.speedBps))
                }}
              </td>
              <td class="mono small">{{ item.macAddress || "—" }}</td>
              <td class="small">
                {{ formatTime(item.deviceLastSeen, prefs.timezone) }}
                <div><StatusBadge :status="item.deviceFreshness" /></div>
              </td>
              <td>
                <RouterLink
                  :to="`/devices/${item.deviceId}?tab=monitoring&interfaceId=${encodeURIComponent(item.id)}`"
                  >带宽历史 ↗</RouterLink
                >
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
  <div class="wb-grid wb-spaced">
    <p class="wb-note">
      标称速率是接口容量，设备最近观测时间用于判断设备数据新鲜度。实际流量、覆盖率和计数器质量可在设备监测中查看。
    </p>
    <section class="panel">
      <header class="panel-head"><h2>关系上下文</h2></header>
      <div class="panel-body">
        <RouterLink to="/topology">打开基础设施拓扑 ↗</RouterLink>
        <p class="wb-spaced">
          <RouterLink
            :to="{
              path: '/applications',
              query: route.query.deviceId
                ? { deviceId: String(route.query.deviceId) }
                : {},
            }"
            >打开独立应用监测 ↗</RouterLink
          >
        </p>
        <p class="small muted wb-spaced">
          关系来源和观测时间与接口状态分别保留。
        </p>
      </div>
    </section>
  </div>
</template>
