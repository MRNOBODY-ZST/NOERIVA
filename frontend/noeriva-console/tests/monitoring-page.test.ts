import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import MonitoringPage from "../src/pages/MonitoringPage.vue";
import MonitoringComparison from "../src/components/MonitoringComparison.vue";
import ChartCanvas from "../src/components/ChartCanvas.vue";
import { useSessionStore } from "../src/stores/session";
import type { MonitoringSource } from "../src/services/workbench";
import { monitoringSourceKey } from "../src/utils/monitoringComparison";

const sources: MonitoringSource[] = [
  {
    deviceId: "a",
    deviceName: "Router",
    deviceType: "ROUTER",
    siteId: "lab",
    siteName: "Lab",
    sourceId: "snmp",
    kind: "SNMP",
    health: "HEALTHY",
    observedAt: "2026-09-21T10:00:00Z",
    freshness: "FRESH",
    sequence: 1,
    epoch: "fixture",
    metrics: { temperature_celsius: -12, cpu_percent: 90 },
  },
  {
    deviceId: "b",
    deviceName: "Router",
    deviceType: "ROUTER",
    siteId: "lab",
    siteName: "Lab",
    sourceId: "snmp",
    kind: "SNMP",
    health: "HEALTHY",
    observedAt: "2026-09-20T10:00:00Z",
    freshness: "STALE",
    sequence: 1,
    epoch: "fixture",
    metrics: { temperature_celsius: 0 },
  },
  {
    deviceId: "c",
    deviceName: "Router",
    deviceType: "ROUTER",
    siteId: "lab",
    siteName: "Lab",
    sourceId: "snmp",
    kind: "SNMP",
    health: "UNKNOWN",
    observedAt: "2026-09-21T10:00:00Z",
    freshness: "FRESH",
    sequence: 1,
    epoch: "fixture",
    metrics: { temperature_celsius: null },
  },
];
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((run) => run());
  vi.unstubAllGlobals();
  localStorage.clear();
});

async function render(historyValues: (number | null)[] = [0]) {
  const requests: URL[] = [];
  vi.stubGlobal("fetch", async (url: string) => {
    const parsed = new URL(url, "http://localhost");
    requests.push(parsed);
    if (parsed.pathname.endsWith("/sites")) return Response.json({ items: [] });
    if (parsed.pathname.endsWith("/workspace/monitoring"))
      return Response.json({
        items: parsed.searchParams.has("cursor")
          ? [{ ...sources[0], deviceId: "next" }]
          : sources,
        nextCursor: parsed.searchParams.has("cursor") ? null : "next-page",
      });
    if (parsed.pathname.endsWith("/metrics"))
      return Response.json({
        deviceId: parsed.pathname.split("/").at(-2),
        metric: parsed.searchParams.get("metric"),
        unit: parsed.searchParams.get("metric") === "cpu_percent" ? "%" : "°C",
        source: "VICTORIAMETRICS",
        resolution: 3600,
        coverage: 1,
        dataRevision: 1,
        points: historyValues.map((value, index) => ({
          timestamp: new Date(
            Date.parse("2026-09-21T10:00:00Z") + index * 3600000,
          ).toISOString(),
          value,
        })),
      });
    throw Error(`Unexpected request: ${url}`);
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
    routes: [{ path: "/:pathMatch(.*)*", component: MonitoringPage }],
  });
  await router.push("/monitoring");
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = mount(MonitoringPage, {
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient }]],
      stubs: { ChartCanvas: true },
    },
  });
  cleanup.push(() => {
    wrapper.unmount();
    queryClient.clear();
  });
  await flushPromises();
  await vi.waitFor(() => expect(wrapper.text()).toContain("当前值对比"));
  return {
    wrapper,
    metrics: () => requests.filter((url) => url.pathname.endsWith("/metrics")),
  };
}

describe("monitoring chart interaction", () => {
  it("shows current-page sources, keeps details collapsed and opens history through the keyboard selector", async () => {
    const { wrapper, metrics } = await render();
    expect(
      wrapper.get("[data-monitoring-details]").attributes("open"),
    ).toBeUndefined();
    expect(metrics()).toEqual([]);
    await wrapper
      .get('select[aria-label="当前值对比指标"]')
      .setValue("temperature_celsius");
    expect(wrapper.text()).toContain("2 个有效读数 · 1 个缺失 · 1 个过期");
    expect(wrapper.get('[aria-label="缺失的当前读数"]').text()).toContain(
      "暂无有效读数",
    );
    expect(wrapper.get('[aria-label="缺失的当前读数"]').text()).toContain(
      "[c]",
    );
    await wrapper
      .get('select[aria-label="选择监测来源并查看趋势"]')
      .setValue(monitoringSourceKey(sources[1]!));
    await flushPromises();
    expect(metrics().at(-1)!.pathname).toBe("/api/v1/devices/b/metrics");
    expect(metrics().at(-1)!.searchParams.get("metric")).toBe(
      "temperature_celsius",
    );
    expect(wrapper.text()).toContain("Router · 历史趋势");
    expect(wrapper.text()).toContain("每 1 小时均值");
    expect(wrapper.text()).not.toContain("当前时间窗没有历史样本");
    expect(wrapper.text()).toContain("过期");
    expect(wrapper.find('a[href="/devices/b?tab=monitoring"]').exists()).toBe(
      true,
    );
  });

  it("opens the same metric from a bar click, uses hourly/daily budgets and clears history after pagination", async () => {
    const { wrapper, metrics } = await render();
    const comparison = wrapper.getComponent(MonitoringComparison);
    comparison.getComponent(ChartCanvas).vm.$emit("select", {
      data: { sourceKey: monitoringSourceKey(sources[0]!) },
    });
    await flushPromises();
    expect(metrics().at(-1)!.pathname).toBe("/api/v1/devices/a/metrics");
    expect(metrics().at(-1)!.searchParams.get("metric")).toBe("cpu_percent");
    for (const [hours, points] of [
      [24, 25],
      [168, 8],
    ]) {
      await wrapper
        .get('select[aria-label="监测时间范围"]')
        .setValue(String(hours));
      await flushPromises();
      const request = metrics().at(-1)!;
      expect(request.searchParams.get("points")).toBe(String(points));
      expect(
        Date.parse(request.searchParams.get("to")!) -
          Date.parse(request.searchParams.get("from")!),
      ).toBe(hours! * 3600000);
    }
    const history = wrapper.findAllComponents(ChartCanvas).at(-1)!;
    const option = history.props("option") as any;
    expect(option.series[0].connectNulls).toBe(false);
    expect(
      option.xAxis.axisLabel.formatter(Date.parse("2026-09-21T10:00:00Z")),
    ).toBe("09/21");
    await wrapper
      .findAll("button")
      .find((button) => button.text() === "下一页")!
      .trigger("click");
    await flushPromises();
    expect(wrapper.text()).not.toContain("Router · 历史趋势");
    expect(
      wrapper.getComponent(MonitoringComparison).props("sources"),
    ).toHaveLength(1);
  });

  it("shows missing metric state explicitly and permits history selection when current reading is null", async () => {
    const wrapper = mount(MonitoringComparison, {
      props: {
        sources: [sources[2]!],
        metric: "temperature_celsius",
        dark: true,
      },
      global: { stubs: { ChartCanvas: true } },
    });
    cleanup.push(() => wrapper.unmount());
    expect(wrapper.findComponent(ChartCanvas).exists()).toBe(false);
    expect(wrapper.text()).toContain("没有有效读数");
    await wrapper
      .get("#monitoring-source-select")
      .setValue(monitoringSourceKey(sources[2]!));
    expect(wrapper.emitted("select")?.[0]).toEqual([
      sources[2],
      "temperature_celsius",
    ]);
  });

  it("shows an explicit empty history state for timestamped buckets with only null readings", async () => {
    const { wrapper, metrics } = await render([null, null]);
    await wrapper
      .get('select[aria-label="选择监测来源并查看趋势"]')
      .setValue(monitoringSourceKey(sources[0]!));
    await flushPromises();
    expect(metrics()).toHaveLength(1);
    expect(wrapper.text()).toContain("当前时间窗没有历史样本");
    expect(wrapper.findAllComponents(ChartCanvas)).toHaveLength(1);
  });
});
