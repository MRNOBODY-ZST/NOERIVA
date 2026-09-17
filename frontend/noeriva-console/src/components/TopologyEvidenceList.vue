<script setup lang="ts">
import type { TopologyEvidence, TopologyNode } from "../services/types";
import { usePreferencesStore } from "../stores/preferences";
import { formatTime } from "../utils/format";
defineProps<{ evidence: TopologyEvidence[]; nodes: TopologyNode[] }>();
const prefs = usePreferencesStore();
</script>
<template>
  <ol class="topology-evidence" aria-label="关系证据">
    <li
      v-for="(item, index) in evidence"
      :key="`${item.protocol}-${item.sourceRef}-${index}`"
    >
      <strong>{{ item.protocol }}</strong>
      <p>{{ item.detail }}</p>
      <p class="muted">
        {{
          nodes.find((node) => node.id === item.sourceDeviceId)?.name ||
          "来源设备"
        }}<span v-if="item.sourceInterface"> · {{ item.sourceInterface }}</span>
      </p>
      <p class="muted">{{ formatTime(item.observedAt, prefs.timezone) }}</p>
      <details v-if="item.sourceRef">
        <summary>原始证据定位</summary>
        <code>{{ item.sourceRef }}</code>
      </details>
    </li>
  </ol>
</template>
