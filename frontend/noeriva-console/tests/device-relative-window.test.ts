import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import DevicePage from "../src/pages/DevicePage.vue";
import { useSessionStore } from "../src/stores/session";
const start = Date.parse("2026-09-06T12:59:00Z");
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((fn) => fn());
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
async function render(tab = "monitoring") {
  vi.useFakeTimers({
    toFake: [
      "Date",
      "setTimeout",
      "clearTimeout",
      "setInterval",
      "clearInterval",
    ],
  });
  vi.setSystemTime(start);
  const requests: URL[] = [];
  let observedAt: number | null = null;
  vi.stubGlobal("fetch", async (url: string) => {
    const path = new URL(url, "http://localhost");
    if (path.pathname.endsWith("/summary"))
      return Response.json({
        device: {
          id: path.pathname.split("/").at(-2),
          name: "BMC",
          type: "BMC",
          siteId: "site",
          siteName: "Site",
          capabilities: ["metrics"],
          health: "UNKNOWN",
          availability: "UNKNOWN",
          lastSeen: null,
          revision: 1,
          managementAddress: "192.0.2.1",
        },
        sources: [],
        activeAlerts: 0,
        sourceFreshness: "MISSING",
        qualityFlags: [],
        coverage: 0,
      });
    if (path.pathname.endsWith("/interfaces"))
      return Response.json({ items: [] });
    if (path.pathname.endsWith("/metrics")) {
      requests.push(path);
      const from = Date.parse(path.searchParams.get("from")!),
        to = Date.parse(path.searchParams.get("to")!);
      return Response.json({
        source: "VictoriaMetrics",
        unit: "°C",
        coverage: 1,
        points:
          observedAt !== null && observedAt >= from && observedAt <= to
            ? [{ timestamp: new Date(observedAt).toISOString(), value: 23.5 }]
            : [],
      });
    }
    throw new Error(`Unexpected request ${url}`);
  });
  const pinia = createPinia();
  useSessionStore(pinia).session = {
    username: "reader",
    organizationId: "o",
    roles: ["VIEWER"],
    mode: "CONNECTED",
    timezone: "UTC",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/assets/:id", component: { template: "<div />" } },
      { path: "/:pathMatch(.*)*", component: { template: "<div />" } },
    ],
  });
  await router.push({ path: "/assets/d1", query: { tab } });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const w = mount(DevicePage, {
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
      stubs: {
        ChartCanvas: true,
        BandwidthHeatmap: true,
        GraphPanel: true,
        DeviceCollection: true,
        DeviceManagement: true,
      },
    },
  });
  const close = () => {
    w.unmount();
    client.clear();
  };
  cleanup.push(close);
  await flushPromises();
  return {
    w,
    router,
    requests,
    publish: (at: number) => {
      observedAt = at;
    },
    close,
  };
}
it("opens a fresh relative window after collection instead of retaining the device creation time", async () => {
  const { w, router, requests, publish } = await render();
  expect(w.text()).toContain("该指标没有可用样本");
  await router.push({ query: { tab: "access" } });
  await flushPromises();
  vi.setSystemTime(start + 60_000);
  publish(Date.now());
  const before = requests.length;
  await router.push({ query: { tab: "monitoring" } });
  await flushPromises();
  expect(requests.length).toBeGreaterThan(before);
  expect(requests.at(-1)!.searchParams.get("to")).toBe(
    new Date(start + 60_000).toISOString(),
  );
  expect(w.text()).not.toContain("该指标没有可用样本");
  expect(w.get('[aria-label="趋势数据表"]').text()).toContain("23.5");
});
it("advances on metric, duration, overview and device changes", async () => {
  const { w, router, requests } = await render();
  vi.setSystemTime(start + 5_000);
  await w.get('[aria-label="监测指标"]').setValue("power_watts");
  await flushPromises();
  expect(requests.at(-1)!.searchParams.get("to")).toBe(
    new Date(start + 5_000).toISOString(),
  );
  vi.setSystemTime(start + 10_000);
  await w.get('[aria-label="趋势时间范围"]').setValue("1");
  await flushPromises();
  const hour = requests.at(-1)!;
  expect(
    Date.parse(hour.searchParams.get("to")!) -
      Date.parse(hour.searchParams.get("from")!),
  ).toBe(3_600_000);
  expect(hour.searchParams.get("to")).toBe(
    new Date(start + 10_000).toISOString(),
  );
  vi.setSystemTime(start + 15_000);
  await router.push({ query: { tab: "overview" } });
  await flushPromises();
  expect(requests.at(-1)!.searchParams.get("to")).toBe(
    new Date(start + 15_000).toISOString(),
  );
  vi.setSystemTime(start + 18_000);
  await router.push({ path: "/assets/d2", query: { tab: "monitoring" } });
  await flushPromises();
  expect(requests.at(-1)!.pathname).toContain("/d2/metrics");
  expect(requests.at(-1)!.searchParams.get("to")).toBe(
    new Date(start + 18_000).toISOString(),
  );
});
it("moves the visible window every twenty seconds and stops metrics while hidden or unmounted", async () => {
  const { w, router, requests, publish, close } = await render();
  publish(start + 15_000);
  const initialCount = requests.length;
  await vi.advanceTimersByTimeAsync(20_000);
  await flushPromises();
  expect(requests).toHaveLength(initialCount + 1);
  expect(requests.at(-1)!.searchParams.get("to")).toBe(
    new Date(start + 20_000).toISOString(),
  );
  expect(w.get('[aria-label="趋势数据表"]').text()).toContain("23.5");
  await router.push({ query: { tab: "info" } });
  await flushPromises();
  const before = requests.length;
  await vi.advanceTimersByTimeAsync(60_000);
  await flushPromises();
  expect(requests).toHaveLength(before);
  close();
  cleanup.length = 0;
  expect(vi.getTimerCount()).toBe(0);
});

it("pauses a browser-hidden monitoring window and resumes at the current time", async () => {
  const original = Object.getOwnPropertyDescriptor(document, "visibilityState");
  const { requests } = await render();
  cleanup.push(() => {
    if (original) Object.defineProperty(document, "visibilityState", original);
    else
      delete (document as unknown as Record<string, unknown>).visibilityState;
  });
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "hidden",
  });
  document.dispatchEvent(new Event("visibilitychange"));
  await flushPromises();
  const before = requests.length;
  await vi.advanceTimersByTimeAsync(40_000);
  await flushPromises();
  expect(requests).toHaveLength(before);
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "visible",
  });
  document.dispatchEvent(new Event("visibilitychange"));
  await flushPromises();
  expect(requests.at(-1)!.searchParams.get("to")).toBe(
    new Date(start + 40_000).toISOString(),
  );
});
