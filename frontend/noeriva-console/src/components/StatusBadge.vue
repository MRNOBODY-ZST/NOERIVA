<script setup lang="ts">
import { computed } from "vue";
import { stateLabel } from "../utils/format";
const props = defineProps<{ status?: string | null; label?: string }>();
const tone = computed(() => {
  const value = (props.status || "UNKNOWN").toUpperCase();
  return ["HEALTHY", "ONLINE", "UP", "FRESH", "CURRENT", "RESOLVED"].includes(
    value,
  )
    ? "good"
    : ["CRITICAL", "ERROR", "OFFLINE", "DOWN"].includes(value)
      ? "bad"
      : ["WARNING", "STALE", "PARTIAL", "OPEN"].includes(value)
        ? "warn"
        : "neutral";
});
</script>
<template>
  <span class="status" :class="tone"
    ><span class="status-dot" aria-hidden="true"></span
    >{{ label || stateLabel(status) }}</span
  >
</template>
