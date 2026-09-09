<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { usePreferencesStore } from "../stores/preferences";
import { useSessionStore } from "../stores/session";
import { useWorkbenchAction } from "../services/workbench";
import { request } from "../services/api";
import { useSystemSettings, type SystemSettings } from "../services/settings";
import { formatTime } from "../utils/format";
import QueryState from "../components/QueryState.vue";
import StatusBadge from "../components/StatusBadge.vue";
const prefs = usePreferencesStore(),
  auth = useSessionStore();
const admin = computed(() => auth.session?.roles.includes("ADMIN"));
const settings = useSystemSettings();
const action = useWorkbenchAction();
const draft = ref<SystemSettings | null>(null),
  dirty = ref(false),
  notice = ref("");
watch(
  settings.data,
  (value) => {
    if (value && !dirty.value) draft.value = { ...value };
  },
  { immediate: true },
);
const zones = [
  "Asia/Shanghai",
  "UTC",
  "Asia/Singapore",
  "America/New_York",
  "Europe/Berlin",
];
async function save() {
  if (!draft.value) return;
  const value = await action.run<SystemSettings>("/settings", {
    revision: draft.value.revision,
    organizationName: draft.value.organizationName,
    timezone: draft.value.timezone,
    defaultCollectionIntervalSeconds:
      draft.value.defaultCollectionIntervalSeconds,
    defaultTimeoutMillis: draft.value.defaultTimeoutMillis,
    defaultMaxInterfaces: draft.value.defaultMaxInterfaces,
    configurationSyncEnabled: draft.value.configurationSyncEnabled,
    configurationSyncIntervalSeconds:
      draft.value.configurationSyncIntervalSeconds,
  });
  if (value) {
    draft.value = { ...value };
    dirty.value = false;
    notice.value =
      "系统设置已保存。采集默认值用于新建连接；已保存的设备连接保持各自设置。";
  }
}
function discard() {
  if (settings.data.value) draft.value = { ...settings.data.value };
  dirty.value = false;
}
async function reindex() {
  const value = await action.run<{ status: string }>("/search/reindex", {});
  if (value) {
    notice.value = `搜索索引更新：${value.status}。刷新状态可查看最后索引时间。`;
    await settings.refetch();
  }
}
const currentPassword = ref(""),
  newPassword = ref(""),
  confirmPassword = ref(""),
  passwordError = ref(""),
  passwordNotice = ref(""),
  passwordPending = ref(false);
async function password() {
  passwordError.value = "";
  passwordNotice.value = "";
  if (new TextEncoder().encode(newPassword.value).length > 72) {
    passwordError.value = "新密码不能超过 72 个 UTF-8 字节。";
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    passwordError.value = "两次新密码不一致。";
    return;
  }
  passwordPending.value = true;
  try {
    const result = await request<{ reauthenticate?: boolean }>(
      "/settings/password",
      {
        method: "POST",
        body: JSON.stringify({
          currentPassword: currentPassword.value,
          newPassword: newPassword.value,
        }),
      },
    );
    passwordNotice.value = "密码已更新，请重新登录。";
    if (result.reauthenticate) auth.logout();
  } catch (e) {
    passwordError.value = e instanceof Error ? e.message : "密码更新失败";
  } finally {
    currentPassword.value = "";
    newPassword.value = "";
    confirmPassword.value = "";
    passwordPending.value = false;
  }
}
function reset() {
  prefs.reset();
  prefs.reducedMotion = false;
  notice.value = "界面偏好已恢复默认。";
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>系统设置</h1>
      <p>管理工作区、采集默认值、配置同步、搜索和个人偏好。</p>
    </div>
    <button
      class="btn"
      :disabled="settings.isFetching.value"
      @click="settings.refetch()"
    >
      刷新系统状态
    </button>
  </div>
  <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  <p v-if="action.error.value" class="wb-error" role="alert">
    {{ action.error.value }}
  </p>
  <QueryState
    :pending="settings.isPending.value"
    :error="settings.error.value"
    @retry="settings.refetch()"
  >
    <form
      v-if="draft"
      class="panel"
      @submit.prevent="save"
      @input="dirty = true"
      @change="dirty = true"
    >
      <header class="panel-head">
        <div>
          <h2>工作区与采集策略</h2>
          <p>
            配置版本 {{ draft.revision }} ·
            {{ formatTime(draft.updatedAt, prefs.timezone) }} ·
            {{ draft.updatedBy || "初始配置" }}
          </p>
        </div>
        <span v-if="!admin" class="tag">只读权限</span>
      </header>
      <div class="panel-body">
        <fieldset
          :disabled="!admin || action.pending.value"
          style="border: 0; padding: 0; margin: 0"
        >
          <div class="form-grid">
            <div class="form-field">
              <label for="organization-name">工作区名称</label
              ><input
                id="organization-name"
                v-model="draft.organizationName"
                class="input"
                required
                maxlength="120"
              />
            </div>
            <div class="form-field">
              <label for="organization-timezone">组织默认时区</label
              ><select
                id="organization-timezone"
                v-model="draft.timezone"
                class="input"
              >
                <option v-for="zone in zones" :key="zone">{{ zone }}</option>
              </select>
            </div>
            <div class="form-field">
              <label for="default-interval">新连接采集周期 · 秒</label
              ><input
                id="default-interval"
                v-model.number="draft.defaultCollectionIntervalSeconds"
                class="input"
                type="number"
                min="15"
                max="86400"
                required
              />
            </div>
            <div class="form-field">
              <label for="default-timeout">新连接请求超时 · 毫秒</label
              ><input
                id="default-timeout"
                v-model.number="draft.defaultTimeoutMillis"
                class="input"
                type="number"
                min="250"
                max="10000"
                required
              />
            </div>
            <div class="form-field">
              <label for="default-interfaces">新连接接口数量上限</label
              ><input
                id="default-interfaces"
                v-model.number="draft.defaultMaxInterfaces"
                class="input"
                type="number"
                min="1"
                max="256"
                required
              />
            </div>
            <div class="form-field">
              <label for="config-sync-interval">配置同步周期 · 秒</label
              ><input
                id="config-sync-interval"
                v-model.number="draft.configurationSyncIntervalSeconds"
                class="input"
                type="number"
                min="300"
                max="86400"
                required
              />
            </div>
          </div>
          <label class="wb-inline wb-spaced"
            ><input
              v-model="draft.configurationSyncEnabled"
              type="checkbox"
            />自动同步设备只读配置快照</label
          >
          <p class="wb-note">
            同步使用设备已保存的连接，捕获配置后脱敏存储。设备不可达或未支持的读取能力会在“配置与变更”显示原因。
          </p>
        </fieldset>
        <div v-if="admin" class="wb-inline wb-spaced">
          <button
            class="btn primary"
            :disabled="!dirty || action.pending.value"
          >
            保存系统设置</button
          ><button
            type="button"
            class="btn"
            :disabled="!dirty || action.pending.value"
            @click="discard"
          >
            放弃未保存修改</button
          ><span v-if="dirty" class="small muted">有未保存修改</span>
        </div>
      </div>
    </form>
    <div v-if="settings.data.value" class="wb-grid wb-spaced">
      <section class="panel">
        <header class="panel-head">
          <h2>搜索与数据</h2>
          <StatusBadge :status="settings.data.value.runtime.searchStatus" />
        </header>
        <div class="panel-body">
          <dl class="wb-facts">
            <dt>搜索引擎</dt>
            <dd>{{ settings.data.value.runtime.searchProvider }}</dd>
            <dt>最近索引</dt>
            <dd>
              {{
                formatTime(
                  settings.data.value.runtime.searchLastIndexedAt,
                  prefs.timezone,
                )
              }}
            </dd>
            <dt>历史保留</dt>
            <dd>
              {{
                settings.data.value.runtime.historyRetention ===
                "NO_AUTOMATIC_DELETION"
                  ? "全部保留，无自动删除策略"
                  : settings.data.value.runtime.historyRetention
              }}
            </dd>
            <dt>配置捕获</dt>
            <dd>只读采集，保存前脱敏</dd>
          </dl>
          <div class="wb-inline wb-spaced">
            <button
              v-if="admin"
              class="btn"
              :disabled="action.pending.value"
              @click="reindex"
            >
              更新搜索索引</button
            ><RouterLink to="/configuration" class="btn"
              >配置同步状态</RouterLink
            ><RouterLink to="/system" class="btn">系统能力</RouterLink>
          </div>
        </div>
      </section>
      <section class="panel">
        <header class="panel-head"><h2>账号与工作区</h2></header>
        <div class="panel-body">
          <dl class="wb-facts">
            <dt>账号</dt>
            <dd>{{ auth.session?.username }}</dd>
            <dt>组织</dt>
            <dd>{{ auth.session?.organizationId }}</dd>
            <dt>角色</dt>
            <dd>{{ auth.session?.roles.join(" · ") }}</dd>
            <dt>运行模式</dt>
            <dd>
              {{ auth.session?.mode === "DEMO" ? "合成演示" : "已连接控制面" }}
            </dd>
          </dl>
        </div>
      </section>
    </div>
  </QueryState>
  <div class="wb-grid wb-spaced">
    <section class="panel">
      <header class="panel-head"><h2>个人界面偏好</h2></header>
      <div class="panel-body">
        <div class="wb-settings-row">
          <label for="settings-theme">颜色主题</label
          ><select
            id="settings-theme"
            class="input"
            :value="prefs.dark ? 'dark' : 'light'"
            @change="
              prefs.dark = ($event.target as HTMLSelectElement).value === 'dark'
            "
          >
            <option value="light">浅色</option>
            <option value="dark">深色</option>
          </select>
        </div>
        <div class="wb-settings-row">
          <label for="settings-density">表格密度</label
          ><select
            id="settings-density"
            class="input"
            :value="prefs.compact ? 'compact' : 'comfortable'"
            @change="
              prefs.compact =
                ($event.target as HTMLSelectElement).value === 'compact'
            "
          >
            <option value="comfortable">标准</option>
            <option value="compact">紧凑</option>
          </select>
        </div>
        <div class="wb-settings-row">
          <label for="settings-timezone">个人显示时区</label
          ><select
            id="settings-timezone"
            v-model="prefs.timezone"
            class="input"
          >
            <option v-for="zone in zones" :key="zone">{{ zone }}</option>
          </select>
        </div>
        <div class="wb-settings-row">
          <div>
            <label for="settings-motion">减少界面动画</label>
            <p>同时尊重系统的减少动态效果偏好</p>
          </div>
          <input
            id="settings-motion"
            v-model="prefs.reducedMotion"
            type="checkbox"
          />
        </div>
        <button class="btn wb-spaced" @click="reset">重置界面偏好</button>
        <p class="wb-note">个人偏好保存在本浏览器，不影响其他成员。</p>
      </div>
    </section>
    <section class="panel">
      <header class="panel-head">
        <h2>修改登录密码</h2>
        <p>更新后所有已有会话失效，请使用新密码重新登录。</p>
      </header>
      <form class="wb-form" @submit.prevent="password">
        <div class="form-field">
          <label for="settings-current-password">当前密码</label
          ><input
            id="settings-current-password"
            v-model="currentPassword"
            type="password"
            class="input"
            autocomplete="current-password"
            required
          />
        </div>
        <div class="form-field">
          <label for="settings-new-password">新密码</label
          ><input
            id="settings-new-password"
            v-model="newPassword"
            type="password"
            class="input"
            autocomplete="new-password"
            minlength="12"
            maxlength="72"
            required
          />
        </div>
        <div class="form-field">
          <label for="settings-confirm-password">确认新密码</label
          ><input
            id="settings-confirm-password"
            v-model="confirmPassword"
            type="password"
            class="input"
            autocomplete="new-password"
            required
          />
        </div>
        <p v-if="passwordError" class="wb-error" role="alert">
          {{ passwordError }}
        </p>
        <p v-if="passwordNotice" class="notice" role="status">
          {{ passwordNotice }}
        </p>
        <button class="btn primary" :disabled="passwordPending">
          {{ passwordPending ? "正在更新…" : "更新密码" }}
        </button>
      </form>
    </section>
  </div>
</template>
