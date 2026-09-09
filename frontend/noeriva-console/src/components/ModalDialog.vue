<script setup lang="ts">
import { nextTick, ref, watch } from "vue";
import { X } from "@lucide/vue";
const props = defineProps<{ open: boolean; title: string; wide?: boolean }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
watch(
  () => props.open,
  async (open) => {
    await nextTick();
    if (open && !dialog.value?.open) dialog.value?.showModal();
    else if (!open && dialog.value?.open) dialog.value.close();
  },
  { immediate: true },
);
</script>
<template>
  <dialog
    ref="dialog"
    class="modal"
    :class="{ wide }"
    :aria-label="title"
    @cancel.prevent="emit('close')"
    @click="
      (event) => {
        if (event.target === dialog) emit('close');
      }
    "
  >
    <div class="modal-head">
      <h2>{{ title }}</h2>
      <button class="icon-btn" aria-label="关闭对话框" @click="emit('close')">
        <X aria-hidden="true" :size="19" />
      </button>
    </div>
    <slot></slot>
  </dialog>
</template>
