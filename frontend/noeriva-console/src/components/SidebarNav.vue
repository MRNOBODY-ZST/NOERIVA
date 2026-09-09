<script setup lang="ts">
import { computed, type Component } from "vue";
import {
  LayoutGrid,
  Server,
  Network,
  Activity,
  Cable,
  TriangleAlert,
  History,
  ListFilter,
  ScanSearch,
  Files,
  Layers,
  Globe,
  ListChecks,
  Settings2,
  PanelLeftClose,
  PanelLeftOpen,
  LogOut,
} from "@lucide/vue";
import { useRoute } from "vue-router";
import { usePreferencesStore } from "../stores/preferences";
import { useSessionStore } from "../stores/session";
import { workspacePages } from "../router";
import { useAlertSummary } from "../services/alerts";
import { formatTime } from "../utils/format";
defineProps<{ mobile?: boolean; organizationName?: string }>();
const emit = defineEmits<{ navigate: []; logout: [] }>();
const prefs = usePreferencesStore(),
  route = useRoute(),
  auth = useSessionStore();
const { data: alerts, error: alertsError } = useAlertSummary();
const alertTitle = computed(() =>
  alertsError.value
    ? `告警队列 · 统计暂不可用${alerts.value ? `；上次成功 ${formatTime(alerts.value.asOf, prefs.timezone)}：${alerts.value.open} 项待确认，${alerts.value.acknowledged} 项已确认未恢复` : ""}`
    : alerts.value
      ? `告警队列 · ${alerts.value.open} 项待确认，${alerts.value.acknowledged} 项已确认未恢复`
      : "告警队列",
);
const alertLabel = computed(() =>
  alertsError.value
    ? "告警队列，统计暂不可用"
    : alerts.value
      ? `告警队列，${alerts.value.open} 项待确认`
      : "告警队列",
);
const icons: Record<string, Component> = {
  "/overview": LayoutGrid,
  "/assets": Server,
  "/topology": Network,
  "/monitoring": Activity,
  "/network": Cable,
  "/applications": Activity,
  "/alerts": TriangleAlert,
  "/incidents": History,
  "/events": ListFilter,
  "/investigator": ScanSearch,
  "/nat-audit": ListFilter,
  "/evidence": Files,
  "/configuration": Layers,
  "/collectors": Globe,
  "/checks": ListChecks,
  "/settings": Settings2,
};
const navigation = workspacePages.map((page) => ({
  ...page,
  icon: icons[page.path],
}));
const groups = [...new Set(navigation.map((page) => page.group))];
const initials = computed(
  () => auth.session?.username.slice(0, 2).toUpperCase() || "N",
);
function active(path: string) {
  if (path === "/assets") return /^\/(assets|devices)(\/|$)/.test(route.path);
  if (path === "/topology")
    return ["/topology", "/connections"].includes(route.path);
  return route.path === path || route.path.startsWith(`${path}/`);
}
</script>
<template>
  <aside class="sidebar" aria-label="主导航">
    <RouterLink
      to="/overview"
      class="brand workspace-brand"
      aria-label="NOERIVA 澄观运行总览"
      @click="emit('navigate')"
    >
      <svg class="brand-mark" viewBox="0 0 40 40" aria-hidden="true">
        <path
          d="M7 30V10h8l10 20h8V10M7 30h8M25 10h8"
          fill="none"
          stroke="currentColor"
          stroke-width="2.6"
          stroke-linejoin="miter"
        />
        <path
          d="M3 20h7M30 20h7"
          fill="none"
          stroke="currentColor"
          stroke-width="1.3"
        />
      </svg>
      <div class="brand-copy">
        <strong>NOERIVA</strong><small>澄观 · 基础设施运维</small>
      </div>
    </RouterLink>
    <div class="context-box">
      <Layers aria-hidden="true" :size="18" />
      <div>
        <strong :title="organizationName || '基础设施运行工作区'">{{
          organizationName || "基础设施运行工作区"
        }}</strong
        ><small
          >{{ auth.session?.organizationId }} ·
          {{
            auth.session?.mode === "DEMO" ? "合成演示环境" : "已连接环境"
          }}</small
        >
      </div>
    </div>
    <nav class="nav-scroll" aria-label="工作区页面" tabindex="0">
      <div v-for="group in groups" :key="group" class="nav-group">
        <div class="nav-label">{{ group }}</div>
        <div class="nav-list">
          <RouterLink
            v-for="item in navigation.filter((page) => page.group === group)"
            :key="item.path"
            :to="item.path"
            class="nav-link"
            :title="item.path === '/alerts' ? alertTitle : item.title"
            :aria-label="item.path === '/alerts' ? alertLabel : item.title"
            :class="{ 'section-active': active(item.path) }"
            :aria-current="active(item.path) ? 'page' : undefined"
            @click="emit('navigate')"
            ><component
              aria-hidden="true"
              :is="item.icon"
              :size="18"
              :stroke-width="1.65"
            /><span class="nav-copy">{{ item.title }}</span
            ><span
              v-if="item.path === '/alerts' && (alertsError || alerts?.open)"
              class="nav-count"
              :class="{ 'nav-count-unknown': !!alertsError }"
              aria-hidden="true"
              >{{ alertsError ? "?" : alerts?.open }}</span
            ></RouterLink
          >
        </div>
      </div>
    </nav>
    <div class="sidebar-footer">
      <span class="avatar">{{ initials }}</span>
      <div class="session-copy">
        <strong>{{ auth.session?.username }}</strong>
        <div class="small muted">{{ auth.session?.roles.join(" · ") }}</div>
      </div>
      <button
        class="icon-btn"
        aria-label="退出登录"
        title="退出登录"
        @click="emit('logout')"
      >
        <LogOut aria-hidden="true" :size="17" />
      </button>
      <button
        v-if="!mobile"
        class="icon-btn sidebar-collapse"
        :aria-label="prefs.collapsed ? '展开侧栏' : '收起侧栏'"
        :title="prefs.collapsed ? '展开侧栏' : '收起侧栏'"
        @click="prefs.collapsed = !prefs.collapsed"
      >
        <component
          aria-hidden="true"
          :is="prefs.collapsed ? PanelLeftOpen : PanelLeftClose"
          :size="17"
        />
      </button>
    </div>
  </aside>
</template>
