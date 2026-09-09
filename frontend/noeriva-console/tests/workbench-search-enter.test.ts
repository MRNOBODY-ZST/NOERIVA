import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import NetworkPage from "../src/pages/NetworkPage.vue";
import MonitoringPage from "../src/pages/MonitoringPage.vue";
import EvidencePage from "../src/pages/EvidencePage.vue";
import ChecksPage from "../src/pages/ChecksPage.vue";
import { useSessionStore } from "../src/stores/session";
import type {
  CheckDefinition,
  Evidence,
  MonitoringSource,
  WorkspaceInterface,
} from "../src/services/workbench";

const at = "2026-09-06T00:00:00Z";
const network: WorkspaceInterface = {
  id: "interface-one",
  deviceId: "device-one",
  deviceName: "Router",
  siteId: "site-one",
  siteName: "Site",
  name: "Baseline entry",
  macAddress: null,
  speedBps: "1000000000",
  adminStatus: "UP",
  operStatus: "UP",
  deviceLastSeen: at,
  deviceFreshness: "FRESH",
};
const monitoring: MonitoringSource = {
  deviceId: "device-one",
  deviceName: "Baseline entry",
  deviceType: "ROUTER",
  siteId: "site-one",
  siteName: "Site",
  sourceId: "source-one",
  kind: "SNMP",
  health: "HEALTHY",
  observedAt: at,
  freshness: "FRESH",
  metrics: {},
  sequence: 1,
  epoch: "epoch-one",
};
const evidence: Evidence = {
  id: "evidence-one",
  deviceId: "device-one",
  title: "Baseline entry",
  kind: "NOTE",
  source: "fixture",
  observedAt: at,
  provenance: "SYNTHETIC",
  sha256: "a".repeat(64),
  integrity: "UNSIGNED",
  createdBy: "admin",
  createdAt: at,
};
const check: CheckDefinition = {
  id: "check-one",
  deviceId: "device-one",
  name: "Baseline entry",
  type: "TCP",
  target: "192.0.2.1:443",
  intervalSeconds: 60,
  enabled: true,
  archived: false,
  provenance: "SYNTHETIC",
  execution: "COLLECTOR_REPORTED",
  revision: 1,
  createdBy: "admin",
  createdAt: at,
  updatedAt: at,
  lastResult: null,
};
const cases = [
  {
    name: "network",
    component: NetworkPage,
    endpoint: "/workspace/interfaces",
    label: "接口前缀搜索",
    row: network,
  },
  {
    name: "monitoring",
    component: MonitoringPage,
    endpoint: "/workspace/monitoring",
    label: "监测来源前缀搜索",
    row: monitoring,
  },
  {
    name: "evidence",
    component: EvidencePage,
    endpoint: "/workbench/evidence",
    label: "证据标题前缀",
    row: evidence,
  },
  {
    name: "checks",
    component: ChecksPage,
    endpoint: "/workbench/checks",
    label: "检查名称前缀搜索",
    row: check,
  },
];
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((run) => run());
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe.each(cases)("$name prefix search", (page) => {
  it("submits the current input on Enter without per-keystroke queries and retains change-on-blur", async () => {
    const searches: (string | null)[] = [];
    vi.stubGlobal("fetch", async (url: string) => {
      const request = new URL(url, "http://localhost");
      if (request.pathname === `/api/v1${page.endpoint}`) {
        const q = request.searchParams.get("q");
        searches.push(q);
        return Response.json({
          items: !q || "baseline entry".startsWith(q) ? [page.row] : [],
          nextCursor: null,
        });
      }
      if (
        [
          "/api/v1/sites",
          "/api/v1/devices",
          "/api/v1/workbench/audit",
        ].includes(request.pathname)
      )
        return Response.json({ items: [], nextCursor: null });
      throw new Error(`Unexpected request: ${request.pathname}`);
    });
    const pinia = createPinia();
    useSessionStore(pinia).session = {
      username: "viewer",
      organizationId: "default",
      roles: ["VIEWER"],
      mode: "CONNECTED",
      timezone: "UTC",
    };
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/:pathMatch(.*)*", component: { template: "<div />" } },
      ],
    });
    await router.push(`/${page.name}`);
    const queryClient = new QueryClient();
    const wrapper = mount(page.component, {
      global: { plugins: [pinia, router, [VueQueryPlugin, { queryClient }]] },
    });
    cleanup.push(() => {
      wrapper.unmount();
      queryClient.clear();
    });
    await flushPromises();
    expect(wrapper.text()).toContain("Baseline entry");
    expect(searches).toEqual([null]);
    const input = wrapper.get(`input[aria-label="${page.label}"]`);
    (input.element as HTMLInputElement).value = "zz-cua-no-result";
    await input.trigger("input");
    await flushPromises();
    expect(searches).toEqual([null]);
    expect(wrapper.text()).toContain("Baseline entry");

    await input.trigger("keydown", { key: "Enter" });
    await flushPromises();
    expect(searches).toEqual([null, "zz-cua-no-result"]);
    expect(wrapper.text()).not.toContain("Baseline entry");

    (input.element as HTMLInputElement).value = "baseline";
    await input.trigger("input");
    await flushPromises();
    expect(searches).toEqual([null, "zz-cua-no-result"]);
    // Native text inputs emit change when an edited value loses focus.
    await input.trigger("change");
    await input.trigger("blur");
    await flushPromises();
    expect(searches).toEqual([null, "zz-cua-no-result", "baseline"]);
    expect(wrapper.text()).toContain("Baseline entry");
  });
});
