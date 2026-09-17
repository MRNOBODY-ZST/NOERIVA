<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useQueryClient } from "@tanstack/vue-query";
import {
  Search,
  Sun,
  Moon,
  Menu,
  X,
  ChevronRight,
  Server,
  ArrowRight,
  Activity,
  Network,
  Fingerprint,
  Layers,
  CircleHelp,
} from "@lucide/vue";
import { useSessionStore } from "./stores/session";
import { usePreferencesStore } from "./stores/preferences";
import { useApiQuery } from "./services/queries";
import { queryString } from "./services/api";
import { useSystemSettings } from "./services/settings";
import type { WorkspaceSearch } from "./services/workspace";
import { workspacePages } from "./router";
import { stateLabel } from "./utils/format";
import SidebarNav from "./components/SidebarNav.vue";
import ModalDialog from "./components/ModalDialog.vue";
const auth = useSessionStore();
const prefs = usePreferencesStore();
const organizationSettings = useSystemSettings();
watch(
  () => organizationSettings.data.value?.timezone,
  (zone) => {
    if (zone) prefs.applyOrganizationTimezone(zone);
  },
  { immediate: true },
);
const queryClient = useQueryClient();
const route = useRoute();
const router = useRouter();
const username = ref("");
const password = ref("");
const mobile = ref<HTMLDialogElement>();
const searchOpen = ref(false);
const search = ref("");
const debounced = ref("");
const selected = ref(0);
let debounce: ReturnType<typeof setTimeout> | undefined;
watch(search, (value) => {
  clearTimeout(debounce);
  debounce = setTimeout(() => {
    debounced.value = value;
    selected.value = 0;
  }, 200);
});
const {
  data: searchResults,
  isPending: searchPending,
  error: searchError,
  refetch: searchRefetch,
} = useApiQuery<WorkspaceSearch>(
  computed(
    () =>
      `/workspace/search${queryString({ q: debounced.value.trim(), limit: 8 })}`,
  ),
  () => searchOpen.value && debounced.value.trim().length >= 2,
);
const results = computed(() => [
  ...workspacePages
    .filter((page) =>
      `${page.title} ${page.keywords}`
        .toLowerCase()
        .includes(search.value.trim().toLowerCase()),
    )
    .slice(0, 6)
    .map((page) => ({
      key: page.path,
      path: page.path,
      title: page.title,
      description: page.group,
      kind: "页面",
      icon: Layers,
    })),
  ...(debounced.value.trim().length >= 2
    ? (searchResults.value?.assets || []).map((device) => ({
        key: device.id,
        path: `/assets/${device.id}`,
        title: device.name,
        description: `${device.managementAddress} · ${stateLabel(device.type)} · ${device.siteName}`,
        kind: "资产",
        icon: Server,
      }))
    : []),
  ...(debounced.value.trim().length >= 2
    ? (searchResults.value?.interfaces || []).map((port) => ({
        key: port.id,
        path: `/devices/${encodeURIComponent(port.deviceId)}?tab=network&interfaceId=${encodeURIComponent(port.id)}`,
        title: port.name,
        description: `${port.deviceName} · ${port.macAddress || port.siteName || "设备接口"}`,
        kind: "端口",
        icon: Network,
      }))
    : []),
  ...(debounced.value.trim().length >= 2
    ? (searchResults.value?.recentEvents || []).map((event) => ({
        key: event.id,
        path: `/events?deviceId=${encodeURIComponent(event.deviceId)}&eventId=${encodeURIComponent(event.id)}`,
        title: event.message,
        description: `${event.kind} · ${event.source}`,
        kind: "事件",
        icon: Activity,
      }))
    : []),
]);
watch(results, () => {
  selected.value = Math.min(
    selected.value,
    Math.max(0, results.value.length - 1),
  );
});
async function login() {
  queryClient.clear();
  await auth.login(username.value, password.value);
  password.value = "";
}
async function logout() {
  searchOpen.value = false;
  mobile.value?.close();
  await queryClient.cancelQueries();
  queryClient.clear();
  auth.logout();
  password.value = "";
}
function keys(event: KeyboardEvent) {
  if (
    (event.ctrlKey || event.metaKey) &&
    event.key.toLowerCase() === "k" &&
    auth.session
  ) {
    event.preventDefault();
    searchOpen.value = !searchOpen.value;
  }
}
function searchKeys(event: KeyboardEvent) {
  const items = results.value;
  if (event.key === "ArrowDown") {
    event.preventDefault();
    selected.value = Math.max(
      0,
      Math.min(selected.value + 1, items.length - 1),
    );
  }
  if (event.key === "ArrowUp") {
    event.preventDefault();
    selected.value = Math.max(0, selected.value - 1);
  }
  if (event.key === "Enter" && items[selected.value]) {
    event.preventDefault();
    void router.push(items[selected.value]!.path);
    searchOpen.value = false;
  }
}
watch(
  () => route.fullPath,
  () => {
    mobile.value?.close();
    searchOpen.value = false;
  },
);
onMounted(() => window.addEventListener("keydown", keys));
onBeforeUnmount(() => {
  window.removeEventListener("keydown", keys);
  clearTimeout(debounce);
});
</script>
<template>
  <div v-if="!auth.session" class="login-page">
    <section class="login-story">
      <div class="brand">NOERIVA<span>澄观</span></div>
      <div>
        <h1>看清设备状态，<br />循着证据，理解变化。</h1>
        <p>
          从一次异常到一个端口，从设备连接到源头证据。在同一个工作空间，保持上下文。
        </p>
        <div class="login-lines">
          <div>
            <Activity aria-hidden="true" :size="17" /> 运行状态与数据新鲜度
          </div>
          <div>
            <Network aria-hidden="true" :size="17" /> 设备关系与连接路径
          </div>
          <div>
            <Fingerprint aria-hidden="true" :size="17" /> 有来源、可追溯的观测
          </div>
        </div>
      </div>
      <small>Infrastructure Operations & Evidence Platform</small>
    </section>
    <section class="login-form-area">
      <form class="login-form" @submit.prevent="login">
        <div>
          <h2>登录工作区</h2>
          <p>连接你的 NOERIVA 控制平面</p>
        </div>
        <div class="form-field">
          <label for="username">用户名</label
          ><input
            id="username"
            v-model="username"
            class="input"
            autocomplete="username"
            required
            autofocus
            placeholder="输入工作区账号"
          />
        </div>
        <div class="form-field">
          <label for="password">密码</label
          ><input
            id="password"
            v-model="password"
            class="input"
            type="password"
            autocomplete="current-password"
            required
            placeholder="输入密码"
          />
        </div>
        <p v-if="auth.error" class="danger-text small" role="alert">
          {{ auth.error }}
        </p>
        <button class="btn primary" :disabled="auth.pending">
          {{ auth.pending ? "正在连接…" : "进入工作区"
          }}<ArrowRight aria-hidden="true" v-if="!auth.pending" :size="16" />
        </button>
        <p class="small muted">
          使用已授权的工作区账号登录。刷新页面后需要重新登录。
        </p>
      </form>
    </section>
  </div>
  <div v-else class="app-layout" :class="{ collapsed: prefs.collapsed }">
    <a href="#main-content" class="skip-link">跳转到主要内容</a
    ><SidebarNav
      :organization-name="organizationSettings.data.value?.organizationName"
      @logout="logout"
    />
    <div class="workspace">
      <header class="topbar">
        <button
          class="icon-btn mobile-trigger"
          aria-label="打开主导航"
          @click="mobile?.showModal()"
        >
          <Menu aria-hidden="true" :size="20" />
        </button>
        <div class="topbar-breadcrumb">
          <Layers aria-hidden="true" :size="14" class="breadcrumb-home" /><span
            class="breadcrumb-home"
            >{{ route.meta.group || "工作空间" }}</span
          ><ChevronRight
            aria-hidden="true"
            :size="14"
            class="breadcrumb-home"
          /><span>{{ route.meta.title }}</span>
        </div>
        <div class="topbar-actions">
          <span
            v-if="auth.session.mode === 'DEMO'"
            class="tag demo desktop-only"
            >合成演示</span
          ><button
            class="search-trigger"
            aria-label="搜索资产、端口、IP、事件，快捷键 Command 或 Control K"
            @click="searchOpen = true"
          >
            <Search aria-hidden="true" :size="16" /><span
              >搜索资产、端口、IP、事件</span
            ><kbd>⌘ K</kbd></button
          ><button
            class="icon-btn"
            :aria-label="prefs.dark ? '切换浅色主题' : '切换深色主题'"
            @click="prefs.dark = !prefs.dark"
          >
            <component
              aria-hidden="true"
              :is="prefs.dark ? Sun : Moon"
              :size="17"
            />
          </button>
          <RouterLink
            to="/system"
            class="icon-btn desktop-only"
            aria-label="查看系统能力"
            title="系统能力"
            ><CircleHelp aria-hidden="true" :size="17"
          /></RouterLink>
        </div>
      </header>
      <main id="main-content" class="content" tabindex="-1">
        <RouterView />
        <footer class="page-footer">
          <span
            >NOERIVA · 澄观
            <span v-if="auth.session.mode === 'DEMO'"
              >／ 当前所有观测均为合成演示</span
            ></span
          ><span>20 秒轮询 · {{ prefs.timezone }}</span>
        </footer>
      </main>
    </div>
  </div>
  <dialog
    ref="mobile"
    class="modal mobile-drawer"
    aria-label="主导航"
    @click="
      (event) => {
        if (event.target === mobile) mobile?.close();
      }
    "
  >
    <div class="modal-head">
      <h2>工作空间</h2>
      <button class="icon-btn" aria-label="关闭主导航" @click="mobile?.close()">
        <X aria-hidden="true" :size="20" />
      </button>
    </div>
    <SidebarNav
      mobile
      :organization-name="organizationSettings.data.value?.organizationName"
      @navigate="mobile?.close()"
      @logout="logout"
    />
  </dialog>
  <ModalDialog
    :open="searchOpen"
    title="搜索资产、端口、IP、事件"
    @close="searchOpen = false"
  >
    <input
      v-model="search"
      class="input command-input"
      placeholder="搜索设备、端口、IP、事件或页面…"
      aria-label="全局搜索"
      maxlength="120"
      autofocus
      :aria-activedescendant="
        results[selected] ? `search-${selected}` : undefined
      "
      @keydown="searchKeys"
    />
    <div v-if="search.trim().length < 2" class="notice">
      输入至少 2 个字符以搜索资产、端口与事件，也可直接打开页面。
    </div>
    <p
      v-if="debounced.trim().length >= 2 && searchPending"
      class="small muted search-hint"
      role="status"
    >
      正在搜索工作区…
    </p>
    <div
      v-if="searchError && debounced.trim().length >= 2"
      class="error-state"
      role="alert"
    >
      <p>{{ searchError.message }}</p>
      <button class="btn small-btn" @click="searchRefetch()">重试搜索</button>
    </div>
    <div class="command-results">
      <RouterLink
        v-for="(item, index) in results"
        :id="`search-${index}`"
        :key="`${item.kind}-${item.key}`"
        :to="item.path"
        class="command-result"
        :class="{ selected: selected === index }"
        @click="searchOpen = false"
        ><component aria-hidden="true" :is="item.icon" :size="18" />
        <div>
          {{ item.title }}<small>{{ item.description }}</small>
        </div>
        <span class="search-kind">{{ item.kind }}</span
        ><ChevronRight aria-hidden="true" :size="16"
      /></RouterLink>
    </div>
    <p v-if="!results.length && !searchPending" class="empty-state">
      当前搜索范围内没有匹配项。
    </p>
    <p
      v-if="
        searchResults?.recentEventsStatus === 'UNAVAILABLE' &&
        debounced.trim().length >= 2
      "
      class="notice"
    >
      事件历史暂时不可用，资产与页面结果仍可查看。
    </p>
    <p
      v-if="
        searchResults?.recentEventsStatus === 'NOT_INDEXED' &&
        debounced.trim().length >= 2
      "
      class="notice"
    >
      当前搜索索引覆盖资产与端口。事件请在<RouterLink
        to="/events"
        @click="searchOpen = false"
        >事件流</RouterLink
      >按时间和设备查询。
    </p>
    <div class="panel-foot">
      <span>↑ ↓ 选择 · Enter 打开 · Esc 关闭</span
      ><span
        >{{ searchResults?.provider || "工作区搜索"
        }}<template v-if="searchResults?.status">
          · {{ searchResults.status }}</template
        ></span
      >
    </div>
  </ModalDialog>
</template>
