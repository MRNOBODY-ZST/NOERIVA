<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ApiError, recoverAuthorizationError } from "../services/api";
const props = defineProps<{
  pending?: boolean;
  error?: Error | null;
  empty?: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
}>();
const emit = defineEmits<{ retry: [] }>();
const unauthorized = computed(
  () => props.error instanceof ApiError && props.error.status === 401,
);
const returningToLogin = ref(false);
watch(
  () => props.error,
  (error) => {
    if (!(error instanceof ApiError) || error.status !== 401) return;
    returningToLogin.value = recoverAuthorizationError(error);
    // An old request's rejection must not expire a newer login. Recover its
    // data once when the error changes, using the current authorization.
    if (!returningToLogin.value) emit("retry");
  },
  { immediate: true },
);
</script>
<template>
  <div
    v-if="unauthorized"
    class="loading-state"
    role="status"
    aria-live="polite"
  >
    <span>{{
      returningToLogin ? "正在返回首页登录…" : "正在恢复当前会话的数据…"
    }}</span>
  </div>
  <div v-else-if="error" class="error-state" role="alert">
    <strong>{{
      error instanceof ApiError && error.status === 403
        ? "访问受限"
        : "数据暂时不可用"
    }}</strong>
    <p>{{ error.message }}</p>
    <small v-if="error instanceof ApiError && error.requestId"
      >请求编号 {{ error.requestId }}</small
    >
    <button class="btn" @click="$emit('retry')">重试请求</button>
  </div>
  <div
    v-else-if="pending"
    class="loading-state"
    role="status"
    aria-live="polite"
  >
    <div class="skeleton"></div>
    <div class="skeleton"></div>
    <div class="skeleton short"></div>
    <span>正在读取数据…</span>
  </div>
  <div v-else-if="empty" class="empty-state">
    <strong>{{ emptyTitle || "当前范围内没有数据" }}</strong>
    <p>
      {{ emptyDescription || "调整筛选条件，或等待已配置的数据源产生观测。" }}
    </p>
  </div>
  <slot v-else></slot>
</template>
