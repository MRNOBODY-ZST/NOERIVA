import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { useSessionStore } from "../src/stores/session";
import DevicesPage from "../src/pages/DevicesPage.vue";
import NetworkPage from "../src/pages/NetworkPage.vue";
const cleanups: (() => void)[] = [];
afterEach(() => {
  cleanups.splice(0).forEach((f) => f());
  vi.unstubAllGlobals();
});
const device = {
  id: "d1",
  name: "Router",
  type: "ROUTER",
  siteId: "s1",
  siteName: "Site",
  managementAddress: "192.0.2.1",
  capabilities: [],
  health: "UNKNOWN",
  availability: "UNKNOWN",
};
async function render(
  component: object,
  path: string,
  roles = ["ADMIN"],
  deleteStatus = 204,
) {
  const calls: { url: string; init: RequestInit }[] = [];
  let deleted = false;
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    calls.push({ url, init });
    const p = new URL(url, "http://localhost");
    if (init.method === "DELETE") {
      if (deleteStatus !== 204)
        return Response.json(
          { message: "设备正在采集，请稍后重试" },
          { status: deleteStatus },
        );
      deleted = true;
      return new Response(null, { status: 204 });
    }
    if (p.pathname.endsWith("/sites"))
      return Response.json({
        items: [
          { id: "s1", name: "Site" },
          { id: "s2", name: "Other" },
        ],
      });
    if (p.pathname.endsWith("/devices/d1")) return Response.json(device);
    if (p.pathname.endsWith("/devices"))
      return Response.json({ items: deleted ? [] : [device] });
    if (p.pathname.endsWith("/devices/d1/interfaces"))
      return Response.json({
        items: [
          { id: "if1", name: "eth1", speedBps: "1000" },
          { id: "if2", name: "eth2", speedBps: "1000" },
        ],
      });
    if (p.pathname.endsWith("/workspace/interfaces"))
      return Response.json({ items: [] });
    throw new Error(url);
  });
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
  const pinia = createPinia();
  useSessionStore(pinia).session = {
    username: "user",
    roles,
    mode: "CONNECTED",
    organizationId: "org",
    timezone: "UTC",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div />" } }],
  });
  await router.push(path);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchInterval: false } },
  });
  const wrapper = mount(component, {
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
    },
  });
  cleanups.push(() => {
    wrapper.unmount();
    client.clear();
  });
  await flushPromises();
  return { wrapper, router, calls };
}
it("requires an explicit administrator confirmation before removing an asset", async () => {
  const { wrapper, calls } = await render(DevicesPage, "/assets");
  await wrapper.get('[aria-label="删除 Router"]').trigger("click");
  expect(wrapper.text()).toContain("保留历史");
  expect(calls.some((c) => c.init.method === "DELETE")).toBe(false);
  await wrapper.get('dialog[aria-label="删除资产"] form').trigger("submit");
  await flushPromises();
  const deletion = calls.find((c) => c.init.method === "DELETE");
  expect(deletion?.url).toBe("/api/v1/devices/d1");
  expect(new Headers(deletion?.init.headers).get("X-Noeriva-Request")).toBe(
    "1",
  );
  expect(wrapper.find('[aria-label="删除 Router"]').exists()).toBe(false);
});
it.each([["VIEWER"], ["OPERATOR"]])(
  "does not expose deletion to %s",
  async (role) => {
    const { wrapper } = await render(DevicesPage, "/assets", [role]);
    expect(wrapper.find('[aria-label="删除 Router"]').exists()).toBe(false);
  },
);
it("supports device and interface selection and resets hidden URL scope", async () => {
  const { wrapper, router, calls } = await render(
    NetworkPage,
    "/network?siteId=s1&deviceId=d1&interfaceId=if1",
  );
  expect(
    (wrapper.get('[aria-label="接口设备"]').element as HTMLSelectElement).value,
  ).toBe("d1");
  await wrapper.get('[aria-label="选择网络接口"]').setValue("if2");
  await flushPromises();
  expect(router.currentRoute.value.query.interfaceId).toBe("if2");
  expect(
    calls.some(
      (c) => c.url.includes("interfaceId=if2") && c.url.includes("deviceId=d1"),
    ),
  ).toBe(true);
  await wrapper
    .findAll("button")
    .find((b) => b.text() === "重置")!
    .trigger("click");
  await flushPromises();
  expect(router.currentRoute.value.query).toEqual({});
  expect(
    (wrapper.get('[aria-label="接口设备"]').element as HTMLSelectElement).value,
  ).toBe("");
});

it("canceling deletion keeps the asset and a busy response remains reviewable", async () => {
  const { wrapper, calls } = await render(
    DevicesPage,
    "/assets",
    ["ADMIN"],
    409,
  );
  await wrapper.get('[aria-label="删除 Router"]').trigger("click");
  const modal = wrapper.get('dialog[aria-label="删除资产"]');
  await modal
    .findAll("button")
    .find((b) => b.text() === "取消")!
    .trigger("click");
  expect(calls.some((c) => c.init.method === "DELETE")).toBe(false);
  await wrapper.get('[aria-label="删除 Router"]').trigger("click");
  await modal.get("form").trigger("submit");
  await flushPromises();
  expect(modal.get('[role="alert"]').text()).toContain("正在采集");
  expect(wrapper.find('[aria-label="删除 Router"]').exists()).toBe(true);
});

it("changing site clears device and interface scope and browser navigation restores it", async () => {
  const { wrapper, router } = await render(
    NetworkPage,
    "/network?siteId=s1&deviceId=d1&interfaceId=if1",
  );
  await wrapper.get('[aria-label="接口站点"]').setValue("s2");
  await flushPromises();
  expect(router.currentRoute.value.query).toEqual({ siteId: "s2" });
  await router.push("/network?siteId=s1&deviceId=d1&interfaceId=if2");
  await flushPromises();
  expect(
    (wrapper.get('[aria-label="接口设备"]').element as HTMLSelectElement).value,
  ).toBe("d1");
  expect(
    (wrapper.get('[aria-label="选择网络接口"]').element as HTMLSelectElement)
      .value,
  ).toBe("if2");
});
