<script setup lang="ts">
import { computed, ref } from "vue";
import { useApiQuery } from "../services/queries";
import type { CollectionSnapshot } from "../services/devices";
import { usePreferencesStore } from "../stores/preferences";
import { formatMetric, formatTime, naturalNameSort } from "../utils/format";
import StatusBadge from "./StatusBadge.vue";
import QueryState from "./QueryState.vue";
const props = defineProps<{ deviceId: string }>();
const prefs = usePreferencesStore(),
  q = ref("");
const collection = useApiQuery<CollectionSnapshot>(
  () => `/devices/${encodeURIComponent(props.deviceId)}/collection`,
);
const sensors = computed(() =>
  naturalNameSort(
    (collection.data.value?.items || []).flatMap((source) =>
      (source.lastReading?.sensors || []).map((sensor) => ({
        ...sensor,
        name: sensor.label,
        key: `${source.slot}/${sensor.id}`,
        slot: source.slot,
        excludedFromDeviceHealth: (
          source.lastReading?.facts?.healthExcludedSensorIds || ""
        )
          .split(",")
          .map((id) => id.trim())
          .includes(sensor.id),
        observedAt: source.lastReading!.observedAt,
        fresh:
          Date.now() - Date.parse(source.lastReading!.observedAt) <= 180_000,
      })),
    ),
  ).filter((sensor) =>
    `${sensor.label} ${sensor.metric} ${sensor.slot}`
      .toLowerCase()
      .includes(q.value.toLowerCase()),
  ),
);
</script>
<template>
  <section class="panel wb-spaced">
    <header class="panel-head">
      <div>
        <h2>全部传感器</h2>
        <p>保留每个组件的真实读数与来源；旧观测不能代表当前健康。</p>
      </div>
      <button class="btn small-btn" @click="collection.refetch()">
        刷新传感器
      </button>
    </header>
    <div class="wb-tools">
      <input
        v-model="q"
        class="input grow"
        aria-label="筛选设备传感器"
        placeholder="筛选传感器名称、指标或采集来源"
      /><span class="small muted">{{ sensors.length }} 个匹配传感器</span>
    </div>
    <QueryState
      :pending="collection.isPending.value"
      :error="collection.error.value"
      :empty="!sensors.length"
      empty-title="当前没有匹配的传感器读数"
      empty-description="调整筛选，或在接入与采集中检查设备返回能力、权限与错误详情。"
      @retry="collection.refetch()"
    >
      <div
        class="table-scroll"
        style="max-height: 480px"
        tabindex="0"
        aria-label="设备传感器明细"
      >
        <table class="data-table">
          <thead>
            <tr>
              <th>组件 / 传感器</th>
              <th>指标</th>
              <th>读数</th>
              <th>健康 / 新鲜度</th>
              <th>采集时间</th>
              <th>来源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="sensor in sensors" :key="sensor.key">
              <td>{{ sensor.label }}</td>
              <td>{{ sensor.metric }}</td>
              <td>{{ formatMetric(sensor.value, sensor.unit) }}</td>
              <td>
                <StatusBadge
                  :status="sensor.fresh ? sensor.health : 'UNKNOWN'"
                /><span v-if="!sensor.fresh" class="secondary-line"
                  >观测已过期 · 当次 {{ sensor.health }}</span
                >
                <span
                  v-if="sensor.excludedFromDeviceHealth"
                  class="secondary-line"
                  >接口已管理关闭 · 低阈值仅保留证据</span
                >
              </td>
              <td class="small">
                {{ formatTime(sensor.observedAt, prefs.timezone) }}
              </td>
              <td class="small mono">
                {{ sensor.slot }} · {{ sensor.sourceRef }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </QueryState>
  </section>
</template>
