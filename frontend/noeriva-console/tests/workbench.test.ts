import { afterEach, describe, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { useSessionStore } from "../src/stores/session";
import { fromUtcInput } from "../src/services/workbench";
import InvestigatorPage from "../src/pages/InvestigatorPage.vue";
import ChecksPage from "../src/pages/ChecksPage.vue";
import IncidentsPage from "../src/pages/IncidentsPage.vue";
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((f) => f());
  vi.unstubAllGlobals();
  localStorage.clear();
});
async function render(
  component: object,
  fetcher: (url: string, init?: RequestInit) => Promise<Response>,
) {
  vi.stubGlobal("fetch", fetcher);
  const pinia = createPinia();
  useSessionStore(pinia).session = {
    username: "admin",
    organizationId: "default",
    roles: ["ADMIN"],
    mode: "CONNECTED",
    timezone: "UTC",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div />" } }],
  });
  await router.push("/");
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retryDelay: 0 } },
  });
  const wrapper = mount(component, {
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient }]],
      stubs: { ModalDialog: { template: "<div />" } },
    },
  });
  cleanup.push(() => {
    wrapper.unmount();
    queryClient.clear();
  });
  await flushPromises();
  return wrapper;
}
describe("workbench content and state integrity", () => {
  it("rejects calendar overflow instead of investigating a different date", () => {
    expect(() => fromUtcInput("2026-02-31T10:00")).toThrow();
    expect(fromUtcInput("2026-02-28T10:00")).toBe("2026-02-28T10:00:00.000Z");
  });
  it("keeps the submitted endpoint attached to results after input edits", async () => {
    const wrapper = await render(InvestigatorPage, async (_url, init) => {
      const query = JSON.parse(String(init?.body));
      return Response.json({
        id: "query-one",
        query,
        status: "NO_MATCH",
        candidates: [],
        qualityFlags: [],
        asOf: query.at,
        mode: "CONNECTED",
      });
    });
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("198.51.100.26:54021");
    await wrapper.get("#investigation-ip").setValue("198.51.100.99");
    expect(wrapper.text()).toContain("198.51.100.26:54021");
    expect(wrapper.text()).not.toContain("198.51.100.99:54021");
  });
  it("does not render an unobserved check as passing or zero latency", async () => {
    const wrapper = await render(ChecksPage, async () =>
      Response.json({
        items: [
          {
            id: "check-one",
            deviceId: "d1",
            name: "Unobserved check",
            target: "192.0.2.1:443",
            type: "TCP",
            intervalSeconds: 60,
            enabled: true,
            archived: false,
            revision: 1,
            provenance: "MANUAL",
            lastResult: null,
          },
        ],
        nextCursor: null,
      }),
    );
    await vi.waitFor(() => expect(wrapper.text()).toContain("尚无有效结果"));
    expect(wrapper.text()).toContain("等待上报");
    expect(wrapper.text()).not.toContain("0 ms");
  });
  it("reloads editor fields when switching two incidents at the same revision", async () => {
    const incident = (id: string) => ({
      id,
      title: id === "one" ? "First incident" : "Second incident",
      severity: "WARNING",
      status: "OPEN",
      deviceId: "d1",
      assignee: null,
      revision: 1,
      notes: [],
      createdAt: "2026-09-06T00:00:00Z",
      updatedAt: "2026-09-06T00:00:00Z",
      createdBy: "admin",
    });
    const wrapper = await render(IncidentsPage, async (url) =>
      Response.json(
        url.includes("/incidents?")
          ? { items: [incident("one"), incident("two")] }
          : incident(url.endsWith("/one") ? "one" : "two"),
      ),
    );
    await vi.waitFor(() => expect(wrapper.text()).toContain("First incident"));
    await wrapper
      .findAll("button")
      .find((b) => b.text() === "First incident")!
      .trigger("click");
    await flushPromises();
    expect(
      (wrapper.get("#update-title").element as HTMLInputElement).value,
    ).toBe("First incident");
    await wrapper
      .findAll("button")
      .find((b) => b.text() === "Second incident")!
      .trigger("click");
    await flushPromises();
    expect(
      (wrapper.get("#update-title").element as HTMLInputElement).value,
    ).toBe("Second incident");
  });
});
