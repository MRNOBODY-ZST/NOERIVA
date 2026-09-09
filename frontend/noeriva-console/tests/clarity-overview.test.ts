import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import OverviewPage from "../src/pages/OverviewPage.vue";
import { useSessionStore } from "../src/stores/session";
import type { WorkspaceOverview } from "../src/services/workspace";
const base: WorkspaceOverview = {
  totals: {
    devices: 25,
    healthy: 20,
    critical: 1,
    warning: 2,
    unknown: 2,
    stale: 3,
    activeAlerts: 2,
    collectors: 1,
    asOf: "2026-09-06T08:00:00Z",
    mode: "CONNECTED",
  },
  siteHealth: [
    {
      siteId: "a",
      siteName: "Site A",
      timezone: "UTC",
      devices: 25,
      critical: 1,
      warning: 2,
      healthy: 20,
      unknown: 2,
      stale: 3,
    },
  ],
  priorityDevices: [],
  recentEvents: [],
  recentEventsStatus: "AVAILABLE",
  trafficSource: null,
  asOf: "2026-09-06T08:00:00Z",
  mode: "CONNECTED",
  qualityFlags: [],
};
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((run) => run());
  vi.unstubAllGlobals();
  localStorage.clear();
});
async function render(overview: WorkspaceOverview) {
  const requests: string[] = [];
  vi.stubGlobal("fetch", async (url: string) => {
    requests.push(url);
    if (url.includes("/workspace/overview")) return Response.json(overview);
    if (url.endsWith("/sites"))
      return Response.json({ items: [{ id: "a", name: "Site A" }] });
    if (url.includes("/alerts?")) return Response.json({ items: [] });
    if (url.includes("/metrics?"))
      return Response.json({
        points: [{ timestamp: "2026-09-06T08:00:00Z", value: 42 }],
        unit: "bit/s",
      });
    throw new Error(`Unexpected request ${url}`);
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
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div/>" } }],
  });
  await router.push("/overview");
  const client = new QueryClient();
  const wrapper = mount(OverviewPage, {
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
      stubs: { ChartCanvas: true },
    },
  });
  cleanup.push(() => {
    wrapper.unmount();
    client.clear();
  });
  await flushPromises();
  return { wrapper, requests };
}
describe("Clarity aggregate dashboard", () => {
  it("uses aggregate totals and makes no metric request without a source", async () => {
    const { wrapper, requests } = await render(base);
    expect(wrapper.find(".clarity-stats").text()).toContain("25");
    expect(wrapper.text()).toContain("等待带宽观测");
    expect(requests.filter((url) => url.includes("/metrics?"))).toEqual([]);
  });
  it("queries only the available traffic direction and identifies the source", async () => {
    const { wrapper, requests } = await render({
      ...base,
      trafficSource: {
        deviceId: "router-a",
        deviceName: "Core A",
        sourceId: "source-a",
        kind: "SNMP",
        observedAt: base.asOf,
        freshness: "STALE",
        metrics: { bandwidth_rx_bps: 42 },
      },
    });
    const metricRequests = requests.filter((url) => url.includes("/metrics?"));
    expect(metricRequests).toHaveLength(1);
    expect(metricRequests[0]).toContain("metric=bandwidth_rx_bps");
    expect(wrapper.text()).toContain("Core A");
    expect(wrapper.text()).toContain("单一来源");
    expect(wrapper.text()).toContain("过期");
  });
  it("does not turn a failed history provider into a zero-event claim", async () => {
    const { wrapper } = await render({
      ...base,
      recentEventsStatus: "UNAVAILABLE",
      qualityFlags: ["HISTORY_UNAVAILABLE"],
    });
    expect(wrapper.text()).toContain("事件历史暂时不可用");
    expect(wrapper.text()).not.toContain("最近 7 天内没有事件记录");
  });
  it("sends site scope to the aggregate endpoint while labeling the global alert rail", async () => {
    const { wrapper, requests } = await render(base);
    await wrapper.get('select[aria-label="站点范围"]').setValue("a");
    await flushPromises();
    expect(
      requests.some((url) => url.includes("/workspace/overview?siteId=a")),
    ).toBe(true);
    expect(wrapper.text()).toContain("全工作区 · 最近 5 条待确认");
  });
});
