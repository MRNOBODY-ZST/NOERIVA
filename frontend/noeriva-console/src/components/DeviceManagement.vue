<script setup lang="ts">
import { computed, ref } from "vue";
import { useApiQuery } from "../services/queries";
import { request, ApiError } from "../services/api";
import { useSessionStore } from "../stores/session";
import { useQueryClient } from "@tanstack/vue-query";
import type { Management } from "../services/devices";
import type { Page, Site } from "../services/types";
import QueryState from "./QueryState.vue";
import { stateLabel } from "../utils/format";
const props = defineProps<{ deviceId: string }>();
const auth = useSessionStore(),
  client = useQueryClient();
const query = useApiQuery<Management>(
  computed(() => `/devices/${encodeURIComponent(props.deviceId)}/management`),
);
const sites = useApiQuery<Page<Site>>("/sites");
const editing = ref(false),
  pending = ref(false),
  error = ref(""),
  notice = ref("");
const form = ref({
  revision: 0,
  name: "",
  type: "HOST",
  siteId: "",
  vendor: "",
  model: "",
  managementAddress: "",
});
function edit() {
  const v = query.data.value;
  if (!v) return;
  form.value = {
    revision: v.inventoryRevision,
    name: v.device.name,
    type: v.device.type,
    siteId: v.device.siteId,
    vendor: v.device.vendor || "",
    model: v.device.model || "",
    managementAddress: v.device.managementAddress,
  };
  editing.value = true;
  error.value = "";
}
async function save() {
  pending.value = true;
  error.value = "";
  try {
    await request(`/devices/${encodeURIComponent(props.deviceId)}/updates`, {
      method: "POST",
      body: JSON.stringify(form.value),
    });
    editing.value = false;
    notice.value = "设备资料已保存，已有连接目标保持独立。";
    await client.invalidateQueries({ queryKey: ["noeriva"] });
  } catch (e) {
    error.value =
      e instanceof ApiError && e.status === 409
        ? "资产资料已被修改，请重新读取后编辑。"
        : e instanceof Error
          ? e.message
          : "保存失败";
  } finally {
    pending.value = false;
  }
}
</script>
<template>
  <section class="panel">
    <header class="panel-head">
      <div>
        <h2>资产资料</h2>
        <p>管理地址用于登记，采集连接单独配置。</p>
      </div>
      <button
        v-if="auth.canWrite"
        data-edit-device
        class="btn"
        :disabled="query.isPending.value"
        @click="edit"
      >
        编辑资料
      </button>
    </header>
    <QueryState
      :pending="query.isPending.value"
      :error="query.error.value"
      @retry="query.refetch()"
      ><div v-if="query.data.value && !editing" class="panel-body">
        <dl class="wb-facts">
          <dt>管理地址</dt>
          <dd class="mono">{{ query.data.value.device.managementAddress }}</dd>
          <dt>品牌 / 型号</dt>
          <dd>
            {{ query.data.value.device.vendor || "未记录" }} /
            {{ query.data.value.device.model || "未记录" }}
          </dd>
          <dt>资产版本</dt>
          <dd>{{ query.data.value.inventoryRevision }}</dd>
        </dl>
      </div></QueryState
    >
    <form v-if="editing" class="wb-form" @submit.prevent="save">
      <div class="form-grid">
        <div class="form-field">
          <label for="manage-name">设备名称</label
          ><input
            id="manage-name"
            v-model="form.name"
            class="input"
            maxlength="120"
            required
          />
        </div>
        <div class="form-field">
          <label for="manage-type">设备类型</label
          ><select id="manage-type" v-model="form.type" class="input">
            <option
              v-for="type in ['HOST', 'BMC', 'SWITCH', 'ROUTER', 'FIREWALL']"
              :key="type"
              :value="type"
            >
              {{ stateLabel(type) }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label for="manage-site">站点</label
          ><select
            id="manage-site"
            v-model="form.siteId"
            class="input"
            required
          >
            <option
              v-for="site in sites.data.value?.items"
              :key="site.id"
              :value="site.id"
            >
              {{ site.name }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label for="manage-address">管理地址</label
          ><input
            id="manage-address"
            v-model="form.managementAddress"
            class="input"
            maxlength="253"
            required
          />
        </div>
        <div class="form-field">
          <label for="manage-vendor">品牌</label
          ><input
            id="manage-vendor"
            v-model="form.vendor"
            class="input"
            maxlength="120"
          />
        </div>
        <div class="form-field">
          <label for="manage-model">型号</label
          ><input
            id="manage-model"
            v-model="form.model"
            class="input"
            maxlength="120"
          />
        </div>
      </div>
      <p v-if="error" class="wb-error" role="alert">{{ error }}</p>
      <div class="actions">
        <button type="button" class="btn" @click="editing = false">取消</button
        ><button
          type="button"
          class="btn"
          :disabled="pending"
          @click="
            async () => {
              await query.refetch();
              edit();
            }
          "
        >
          重新读取</button
        ><button class="btn primary" :disabled="pending">
          {{ pending ? "正在保存…" : "保存资料" }}
        </button>
      </div>
    </form>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  </section>
</template>
