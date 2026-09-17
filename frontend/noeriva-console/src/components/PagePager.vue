<script setup lang="ts">
defineProps<{
  page: number;
  count: number;
  previous: boolean;
  next: boolean;
  pending?: boolean;
}>();
defineEmits<{ back: []; forward: [] }>();
</script>
<template>
  <footer class="wb-pagination">
    <span>第 {{ page }} 页 · 当前 {{ count }} 项</span>
    <div>
      <button
        class="btn small-btn"
        :disabled="!previous || pending"
        @click="$emit('back')"
      >
        上一页</button
      ><button
        class="btn small-btn"
        :disabled="!next || pending"
        @click="$emit('forward')"
      >
        下一页
      </button>
    </div>
  </footer>
</template>
