import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import CollectorsPage from "../src/pages/CollectorsPage.vue";
import DeviceCollection from "../src/components/DeviceCollection.vue";
import { useSessionStore } from "../src/stores/session";
const clean: (() => void)[] = [];
afterEach(() => {
  clean.splice(0).forEach((f) => f());
  vi.unstubAllGlobals();
});
async function render(component: object, props = {}) {
  for (const method of ["showModal", "close"] as const) {
    const original = Object.getOwnPropertyDescriptor(
      HTMLDialogElement.prototype,
      method,
    );
    Object.defineProperty(HTMLDialogElement.prototype, method, {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.toggleAttribute("open", method === "showModal");
      },
    });
    clean.push(() => {
      if (original)
        Object.defineProperty(HTMLDialogElement.prototype, method, original);
      else
        delete (
          HTMLDialogElement.prototype as unknown as Record<string, unknown>
        )[method];
    });
  }
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
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div />" } }],
  });
  await router.push("/collectors");
  await router.isReady();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const w = mount(component, {
    props,
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
    },
  });
  clean.push(() => {
    w.unmount();
    client.clear();
  });
  await flushPromises();
  return w;
}
it("does not present a worker without local spool as a measured empty queue or a null site", async () => {
  vi.stubGlobal("fetch", async () =>
    Response.json({
      items: [
        {
          id: "device-poller",
          name: "设备协议采集 Worker",
          siteId: null,
          status: "ONLINE",
          lastHeartbeat: "2026-09-06T10:00:00Z",
          lastSuccessfulCollection: null,
          queueBytes: 0,
          queueLimitBytes: 0,
          oldestQueuedAt: null,
          version: "0.1.0",
          capabilities: ["SNMP", "REDFISH"],
          source: "CONNECTED",
        },
      ],
    }),
  );
  const w = await render(CollectorsPage);
  expect(w.text()).toContain("不适用（无本地缓冲队列）");
  expect(w.text()).not.toContain("无积压记录");
  expect(w.find('[role="progressbar"]').exists()).toBe(false);
  await w
    .findAll("button")
    .find((b) => b.text() === "查看来源记录")!
    .trigger("click");
  const link = w
    .findAll("a")
    .find(
      (a) =>
        a.text().includes("检查") &&
        a.text().includes("监测来源") &&
        a.element.closest("dialog"),
    );
  expect(link?.attributes("href")).toBe("/monitoring");
  expect(link?.text()).toBe("检查监测来源");
});
it("explains protocol simulator verification without claiming hardware acceptance", async () => {
  vi.stubGlobal("fetch", async (url: string) => {
    if (url.endsWith("/device-support"))
      return Response.json({
        items: [
          {
            id: "generic",
            vendor: "Generic",
            family: "通用设备",
            protocols: ["SNMP", "REDFISH"],
            implemented: true,
            verification: "SIMULATOR_TESTED_HARDWARE_PENDING",
            notes: "成功读取对象范围",
          },
        ],
        protocols: ["SNMP", "REDFISH"],
        credentialStorageReady: true,
        collectorEnabled: true,
      });
    if (url.endsWith("/live")) return new Response("", { status: 503 });
    return Response.json({ items: [], asOf: "2026-09-06T10:00:00Z" });
  });
  const w = await render(DeviceCollection, {
    deviceId: "d",
    managementAddress: "192.0.2.1",
  });
  expect(w.text()).toContain("协议模拟器已验证 · 真机待验收");
  expect(w.find('[title="SIMULATOR_TESTED_HARDWARE_PENDING"]').exists()).toBe(
    true,
  );
});
