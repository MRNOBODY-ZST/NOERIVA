<script setup lang="ts">
import { computed, ref } from "vue";
import { RefreshCw } from "@lucide/vue";
import { useApiQuery } from "../services/queries";
import { queryString } from "../services/api";
import type { Device, Page, Topology } from "../services/types";
import QueryState from "../components/QueryState.vue";
import GraphPanel from "../components/GraphPanel.vue";
const scope = ref("");
const { data: devices } = useApiQuery<Page<Device>>("/devices?limit=100");
const { data, isPending, error, refetch } = useApiQuery<Topology>(
  computed(
    () =>
      `/topology${queryString({ view: "ALL", deviceId: scope.value, limit: 200 })}`,
  ),
);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>基础设施拓扑</h1>
      <p>从连接关系理解影响范围，保留每一条边的来源。</p>
    </div>
    <button class="btn" @click="refetch()">
      <RefreshCw aria-hidden="true" :size="14" />刷新
    </button>
  </div>
  <section class="panel">
    <header class="panel-head">
      <div>
        <h2>基础设施关系</h2>
        <p>物理观测与终端接入推断 · 当前快照最多 200 个节点</p>
      </div>
      <select v-model="scope" aria-label="限定设备邻域">
        <option value="">当前授权范围</option>
        <option
          v-for="device in devices?.items"
          :key="device.id"
          :value="device.id"
        >
          {{ device.name }} · 邻域
        </option>
      </select>
    </header>
    <QueryState
      :pending="isPending"
      :error="error"
      :empty="!data?.nodes.length"
      empty-title="尚无连接关系"
      empty-description="收到拓扑来源的有效关系观测后，连接会显示在这里。"
      @retry="refetch()"
      ><GraphPanel v-if="data" :topology="data"
    /></QueryState>
  </section>
</template>
