<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from "vue";
import { queryString } from "../services/api";
import { useApiQuery } from "../services/queries";
import type { Device, Page } from "../services/types";
const props = withDefaults(
  defineProps<{
    modelValue: string;
    id?: string;
    siteId?: string;
    optional?: boolean;
    ariaLabel?: string;
  }>(),
  { optional: false, ariaLabel: "选择关联设备" },
);
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const text = ref(""),
  query = ref("");
let timer: ReturnType<typeof setTimeout> | undefined;
watch(text, (v) => {
  clearTimeout(timer);
  timer = setTimeout(() => (query.value = v), 200);
});
onBeforeUnmount(() => clearTimeout(timer));
const { data, error, isPending } = useApiQuery<Page<Device>>(
  computed(
    () =>
      `/devices${queryString({ q: query.value, siteId: props.siteId, limit: 20 })}`,
  ),
);
</script>
<template>
  <div class="wb-device-picker">
    <input
      v-model="text"
      class="input"
      placeholder="按设备名称或 IP 前缀查找"
      :aria-label="optional ? '查找接口设备' : '查找关联设备'"
    /><select
      :id="id"
      class="input"
      :value="props.modelValue"
      :required="!optional"
      :aria-label="ariaLabel"
      @change="
        emit('update:modelValue', ($event.target as HTMLSelectElement).value)
      "
    >
      <option value="" :disabled="!optional">
        {{ optional ? "全部设备" : "选择关联设备" }}
      </option>
      <option
        v-if="modelValue && !data?.items.some((d) => d.id === modelValue)"
        :value="modelValue"
      >
        已选设备 · {{ modelValue }}
      </option>
      <option v-for="d in data?.items" :key="d.id" :value="d.id">
        {{ d.name }} · {{ d.siteName }}
      </option></select
    ><small v-if="error" class="danger-text">{{ error.message }}</small
    ><small v-else-if="isPending" class="muted">正在查找设备…</small
    ><small v-else class="muted">显示最多 20 项，输入前缀可缩小范围。</small>
  </div>
</template>
