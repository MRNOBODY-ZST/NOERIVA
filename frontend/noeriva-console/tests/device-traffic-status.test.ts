import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { VueQueryPlugin, QueryClient } from "@tanstack/vue-query";
import { createMemoryHistory, createRouter } from "vue-router";
import { useSessionStore } from "../src/stores/session";
import DeviceTrafficStatus from "../src/components/DeviceTrafficStatus.vue";
import { workspacePages } from "../src/router";
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((f) => f());
  vi.unstubAllGlobals();
});
it("uses safe source projections for independent states and preserves device context without credentials", async () => {
  const calls: string[] = [];
  vi.stubGlobal("fetch", async (url: string) => {
    calls.push(url);
    if (url.includes("/applications/sources"))
      return Response.json({
        items: [
          {
            deviceId: "cisco",
            status: "DISABLED",
            enabled: false,
            revision: 4,
            interfaceIndices: [8],
            maxRows: 128,
            lastSuccessAt: null,
            qualityFlags: [],
          },
        ],
        nextCursor: "more",
      });
    if (url.endsWith("/nat-audit/sources"))
      return Response.json({
        items: [
          {
            deviceId: "cisco",
            status: "RECEIVING",
            enabled: true,
            revision: 2,
            lastPacketAt: "2026-09-07T00:00:00Z",
            lastPersistedAt: null,
            qualityFlags: ["COMPLETENESS_NOT_GUARANTEED"],
          },
        ],
      });
    throw Error("Unsafe path requested");
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
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div />" } }],
  });
  await router.push("/assets/cisco");
  const client = new QueryClient();
  const w = mount(DeviceTrafficStatus, {
    props: { deviceId: "cisco" },
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
    },
  });
  cleanup.push(() => {
    w.unmount();
    client.clear();
  });
  await flushPromises();
  expect(w.text()).toContain("应用周期已停用");
  expect(w.text()).toContain("接收已启用");
  expect(w.get('a[href="/applications?deviceId=cisco"]').text()).toContain(
    "应用监测",
  );
  expect(w.get('a[href="/nat-audit?deviceId=cisco"]').text()).toContain(
    "NAT 审计",
  );
  expect(calls).toHaveLength(2);
  expect(calls.some((p) => p.includes("/connections"))).toBe(false);
  await w.setProps({ deviceId: "not-in-first-page" });
  expect(w.text()).toContain("不能据此判断未配置");
  expect(w.text()).not.toContain("应用周期已停用");
  expect(workspacePages.find((p) => p.path === "/applications")?.title).toBe(
    "应用监测",
  );
  expect(workspacePages.find((p) => p.path === "/nat-audit")?.title).toBe(
    "NAT 审计",
  );
});
