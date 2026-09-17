import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { useSessionStore } from "../src/stores/session";
import { usePreferencesStore } from "../src/stores/preferences";
import SidebarNav from "../src/components/SidebarNav.vue";
import AlertsPage from "../src/pages/AlertsPage.vue";

const cleanups: (() => void)[] = [];
afterEach(() => {
  cleanups.splice(0).forEach((fn) => fn());
  vi.unstubAllGlobals();
  localStorage.clear();
});
async function render(path = "/alerts", initialOpen = 0) {
  let open = initialOpen;
  let summaryFailure = false;
  const urls: string[] = [];
  vi.stubGlobal("fetch", async (url: string, options?: RequestInit) => {
    urls.push(url);
    const endpoint = new URL(url, "http://localhost");
    if (endpoint.pathname.endsWith("/summary") && summaryFailure)
      return Response.json({ message: "Summary unavailable" }, { status: 503 });
    if (endpoint.pathname.endsWith("/summary"))
      return Response.json({
        open,
        acknowledged: 2 + initialOpen - open,
        active: 2 + initialOpen,
        resolved: 5,
        asOf: "2026-09-09T00:00:00Z",
      });
    if (
      endpoint.pathname.endsWith("/acknowledge") &&
      options?.method === "POST"
    ) {
      open = 0;
      return Response.json({
        id: "alert",
        title: "Device unreachable",
        state: "ACKNOWLEDGED",
        revision: 2,
      });
    }
    const state = endpoint.searchParams.get("state");
    return Response.json({
      items:
        state === "OPEN" && !open
          ? []
          : [
              {
                id: "alert",
                deviceId: "device",
                deviceName: "Dell switch",
                severity: "CRITICAL",
                state,
                title: "Device unreachable",
                openedAt: "2026-09-09T00:00:00Z",
                acknowledgedBy: state === "OPEN" ? null : "admin",
                revision: 1,
              },
            ],
    });
  });
  const pinia = createPinia();
  usePreferencesStore(pinia).timezone = "UTC";
  useSessionStore(pinia).session = {
    username: "admin",
    organizationId: "org",
    roles: ["ADMIN"],
    mode: "CONNECTED",
    timezone: "UTC",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div/>" } }],
  });
  await router.push(path);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = mount(
    {
      components: { SidebarNav, AlertsPage },
      template: "<SidebarNav/><main><AlertsPage/></main>",
    },
    {
      global: {
        plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
        stubs: { ModalDialog: true },
      },
    },
  );
  cleanups.push(() => {
    wrapper.unmount();
    client.clear();
  });
  await flushPromises();
  return {
    wrapper,
    urls,
    router,
    client,
    failSummary: () => {
      summaryFailure = true;
    },
    recoverSummary: () => {
      summaryFailure = false;
    },
  };
}
it("uses the same open count in the shell and queue while acknowledged unresolved alerts remain visible", async () => {
  const { wrapper, urls } = await render();
  expect(wrapper.find(".nav-count").exists()).toBe(false);
  expect(wrapper.get('a[href="/alerts"]').attributes("aria-label")).toBe(
    "告警队列，0 项待确认",
  );
  expect(wrapper.text()).toContain("另有 2 项已确认未恢复");
  expect(wrapper.text()).not.toContain("当前队列没有异常");
  expect(urls.filter((url) => url.endsWith("/alerts/summary"))).toHaveLength(1);
  await wrapper.findAll(".alert-summary-item")[1]!.trigger("click");
  await flushPromises();
  expect(urls.some((url) => url.includes("state=ACKNOWLEDGED"))).toBe(true);
  expect(wrapper.get("main").text()).toContain("Device unreachable");
});
it("scopes queue counts to the selected device without changing the workspace sidebar scope", async () => {
  const { wrapper, urls } = await render(
    "/alerts?deviceId=device&state=ACKNOWLEDGED",
  );
  expect(
    urls.some((url) => url.endsWith("/alerts/summary?deviceId=device")),
  ).toBe(true);
  expect(urls.some((url) => url.endsWith("/alerts/summary"))).toBe(true);
  expect(wrapper.get("#alert-state").element).toHaveProperty(
    "value",
    "ACKNOWLEDGED",
  );
  expect(wrapper.get(".alert-summary").text()).toContain("当前设备");
});
it("acknowledging clears the open badge and preserves unresolved summary counts", async () => {
  const { wrapper } = await render("/alerts", 1);
  expect(wrapper.get(".nav-count").text()).toBe("1");
  await wrapper
    .findAll("main button")
    .find((button) => button.text().includes("确认异常"))!
    .trigger("click");
  await flushPromises();
  expect(wrapper.find(".nav-count").exists()).toBe(false);
  expect(wrapper.get(".alert-summary").text()).toContain("已确认未恢复3");
  expect(wrapper.text()).toContain("问题状态未被标记为恢复");
});
it.each([0, 3])(
  "marks retained counts as old after a failed refetch (previous open=%s), then clears the warning on recovery",
  async (initialOpen) => {
    const { wrapper, client, failSummary, recoverSummary } = await render(
      "/alerts",
      initialOpen,
    );
    const previousCounts = wrapper
      .findAll(".alert-summary-item strong")
      .map((item) => item.text());
    failSummary();
    await client.refetchQueries({
      queryKey: ["noeriva", "org", "admin", "/alerts/summary"],
    });
    await flushPromises();
    expect(wrapper.get(".nav-count").text()).toBe("?");
    expect(wrapper.get('a[href="/alerts"]').attributes("aria-label")).toBe(
      "告警队列，统计暂不可用",
    );
    expect(wrapper.get('a[href="/alerts"]').attributes("title")).toContain(
      "上次成功",
    );
    expect(wrapper.get('main [role="alert"]').text()).toContain("当前数量未知");
    expect(wrapper.get('main [role="alert"]').text()).toMatch(
      /09\/09.*00:00:00/,
    );
    expect(
      wrapper.findAll(".alert-summary-item strong").map((item) => item.text()),
    ).toEqual(previousCounts);
    expect(wrapper.get(".alert-summary").attributes("aria-label")).toBe(
      "上次成功告警统计",
    );
    expect(wrapper.get(".alert-summary").text()).toContain("上次统计");
    recoverSummary();
    await wrapper
      .findAll("button")
      .find((button) => button.text() === "重试统计")!
      .trigger("click");
    await flushPromises();
    expect(wrapper.find('main [role="alert"]').exists()).toBe(false);
    expect(wrapper.find(".nav-count").exists()).toBe(initialOpen > 0);
    if (initialOpen)
      expect(wrapper.get(".nav-count").text()).toBe(String(initialOpen));
    expect(wrapper.get(".alert-summary").attributes("aria-label")).toBe(
      "告警状态统计",
    );
  },
);
