import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import DevicePage from "../src/pages/DevicePage.vue";
import { useSessionStore } from "../src/stores/session";
import type { Summary } from "../src/services/types";

const unobserved: Summary = {
  device: {
    id: "uncollected-host",
    name: "Uncollected host",
    type: "HOST",
    siteId: "default",
    siteName: "Default site",
    vendor: "",
    model: "",
    managementAddress: "192.0.2.50",
    health: "UNKNOWN",
    availability: "UNKNOWN",
    lastSeen: null,
    revision: 1,
    capabilities: [],
  },
  sources: [],
  activeAlerts: 0,
  asOf: null,
  sourceFreshness: "UNKNOWN",
  coverage: 0,
  resolution: 300,
  dataRevision: 0,
  provisional: false,
  qualityFlags: ["SOURCE_MISSING"],
};
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((run) => run());
  vi.unstubAllGlobals();
  localStorage.clear();
});

async function renderDevice(
  summary: Summary | Promise<Summary>,
  providerFailure = false,
) {
  const requests: string[] = [];
  vi.stubGlobal("fetch", async (url: string) => {
    requests.push(url);
    if (url.endsWith("/summary")) return Response.json(await summary);
    if (url.endsWith("/interfaces")) return Response.json({ items: [] });
    if (url.includes("/metrics?")) {
      if (providerFailure)
        return Response.json(
          {
            detail: "Metric provider is unreachable",
            requestId: "provider-503",
          },
          { status: 503 },
        );
      return Response.json({
        ...unobserved,
        deviceId: "uncollected-host",
        metric: "cpu_percent",
        unit: "%",
        from: "",
        to: "",
        source: "VICTORIAMETRICS",
        points: [],
      });
    }
    throw new Error(`Unexpected test request: ${url}`);
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
      { path: "/devices/:id", component: DevicePage },
      { path: "/:pathMatch(.*)*", component: { template: "<div />" } },
    ],
  });
  await router.push("/devices/uncollected-host");
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retryDelay: 0 } },
  });
  const wrapper = mount(DevicePage, {
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient }]],
      stubs: {
        ChartCanvas: true,
        BandwidthHeatmap: true,
        GraphPanel: true,
        EventsPage: true,
      },
    },
  });
  cleanup.push(() => {
    wrapper.unmount();
    queryClient.clear();
  });
  await flushPromises();
  return {
    wrapper,
    metricRequests: () => requests.filter((url) => url.includes("/metrics?")),
  };
}

describe("device monitoring availability", () => {
  it("waits for summary capabilities before querying metrics", async () => {
    let resolve!: (summary: Summary) => void;
    const pending = new Promise<Summary>((done) => {
      resolve = done;
    });
    const { metricRequests } = await renderDevice(pending);
    try {
      expect(metricRequests()).toEqual([]);
    } finally {
      resolve(unobserved);
      await flushPromises();
    }
  });

  it("shows an explicit waiting state without metric requests for an unassigned device", async () => {
    const { wrapper, metricRequests } = await renderDevice(unobserved);
    await vi.waitFor(() => expect(wrapper.text()).toContain("等待监测来源"));
    expect(wrapper.text()).toContain("尚未分配监测来源");
    expect(wrapper.text()).toContain("等待受信任来源提供有效观测");
    expect(metricRequests()).toEqual([]);
    expect(
      wrapper.get('select[aria-label="监测指标"]').attributes(),
    ).toHaveProperty("disabled");
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain("数据暂时不可用");
  });

  it("recognizes an observed SSH source that provides identity and neighbors without numeric metrics", async () => {
    const observedAt = "2026-09-07T01:30:00Z";
    const { wrapper, metricRequests } = await renderDevice({
      ...unobserved,
      device: {
        ...unobserved.device,
        type: "SWITCH",
        availability: "ONLINE",
        lastSeen: observedAt,
        capabilities: ["inventory", "neighbors"],
      },
      sources: [
        {
          sourceId: "ssh",
          kind: "DeviceSummaryObserved",
          observedAt,
          sequence: 1,
          epoch: "ssh-observed",
          health: "UNKNOWN",
          metrics: {},
          freshness: "FRESH",
        },
      ],
      sourceFreshness: "FRESH",
      coverage: 1,
      qualityFlags: [],
    });
    await vi.waitFor(() => expect(wrapper.text()).toContain("暂无数值指标"));
    expect(wrapper.text()).toContain("已有来源观测，当前未提供数值指标");
    expect(wrapper.text()).toContain(
      "当前已接入来源，但现有读取尚未提供可查询的数值指标",
    );
    expect(wrapper.text()).toContain(
      "若需网络流量，请配置并采集 SNMP 等支持接口计数的来源",
    );
    expect(wrapper.text()).not.toContain("尚未分配监测来源");
    expect(wrapper.text()).not.toContain("等待受信任来源");
    expect(metricRequests()).toEqual([]);
    expect(
      wrapper.get('select[aria-label="监测指标"]').attributes(),
    ).toHaveProperty("disabled");
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  });

  it("still queries an assigned metric source when current observations are absent", async () => {
    const { wrapper, metricRequests } = await renderDevice({
      ...unobserved,
      device: { ...unobserved.device, capabilities: ["metrics"] },
    });
    await vi.waitFor(() =>
      expect(wrapper.text()).toContain("该指标没有可用样本"),
    );
    expect(metricRequests().length).toBeGreaterThan(0);
    expect(
      wrapper.get('select[aria-label="监测指标"]').attributes(),
    ).not.toHaveProperty("disabled");
  });

  it("preserves genuine provider failures and their request identifiers", async () => {
    const { wrapper } = await renderDevice(
      {
        ...unobserved,
        device: { ...unobserved.device, capabilities: ["metrics"] },
      },
      true,
    );
    await vi.waitFor(() =>
      expect(wrapper.get('[role="alert"]').text()).toContain(
        "Metric provider is unreachable",
      ),
    );
    expect(wrapper.get('[role="alert"]').text()).toContain("provider-503");
    expect(wrapper.get('[role="alert"]').text()).toContain("数据暂时不可用");
  });
});
