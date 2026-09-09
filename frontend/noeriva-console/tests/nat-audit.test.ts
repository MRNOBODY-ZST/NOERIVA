import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { createMemoryHistory, createRouter } from "vue-router";
import { useSessionStore } from "../src/stores/session";
import NatAuditPage from "../src/pages/NatAuditPage.vue";
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((f) => f());
  vi.unstubAllGlobals();
});
const source = {
  deviceId: "cisco",
  deviceName: "Cisco ASR",
  siteId: "s1",
  sourceAddress: "192.0.2.3",
  revision: 4,
  enabled: true,
  status: "DEGRADED",
  lastPacketAt: "2026-09-07T00:10:00Z",
  lastEventAt: null,
  lastPersistedAt: null,
  lastError: "UNKNOWN_TEMPLATE",
  templates: 2,
  received: 3,
  accepted: 0,
  persisted: 0,
  dropped: 0,
  sequenceGaps: 1,
  unknownTemplates: 1,
  parseErrors: 0,
  duplicatePackets: 0,
  restartCount: 0,
  updatedAt: "2026-09-07T00:10:00Z",
  qualityFlags: ["UDP_UNAUTHENTICATED", "COMPLETENESS_NOT_GUARANTEED"],
};
const event = {
  id: "e1",
  deviceId: "cisco",
  siteId: "s1",
  sourceAddress: "192.0.2.3",
  sourceDomain: 0,
  exporterEpoch: "boot-1",
  packetSequence: 1,
  templateId: 256,
  templateSha256: "sha",
  packetSha256: "sha",
  recordIndex: 0,
  eventType: "CREATE",
  protocol: 6,
  vrfId: null,
  privateIp: "192.0.2.9",
  privatePort: 0,
  publicIp: null,
  publicPort: null,
  destinationIp: null,
  destinationPort: null,
  translatedDestinationIp: null,
  translatedDestinationPort: null,
  poolId: null,
  deviceEventAt: null,
  exportedAt: "2026-09-07T00:10:00Z",
  receivedAt: "2026-09-07T00:10:01Z",
  qualityFlags: ["DEVICE_EVENT_TIME_MISSING"],
  provenance: "CISCO_NAT_HSL_V9",
};
async function render(roles = ["VIEWER"], post?: (body: any) => Response) {
  const calls: { url: URL; method: string; body: any }[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    const parsed = new URL(url, "http://localhost");
    const body = init.body ? JSON.parse(String(init.body)) : null;
    calls.push({ url: parsed, method: init.method || "GET", body });
    if (init.method === "POST") {
      expect(new Headers(init.headers).get("X-Noeriva-Request")).toBe("1");
      return post?.(body) || Response.json(source);
    }
    if (parsed.pathname.endsWith("/nat-audit/sources"))
      return Response.json({ items: [source] });
    if (parsed.pathname.endsWith("/nat-audit/events"))
      return Response.json({
        items: [event],
        nextCursor: parsed.searchParams.has("cursor") ? null : "opaque-cursor",
        asOf: event.receivedAt,
        mode: "CONNECTED",
      });
    if (parsed.pathname.endsWith("/devices"))
      return Response.json({
        items: [{ id: "cisco", name: "Cisco ASR", siteName: "Site" }],
      });
    throw Error(parsed.pathname);
  });
  const pinia = createPinia();
  useSessionStore(pinia).session = {
    username: "reader",
    organizationId: "o",
    roles,
    mode: "CONNECTED",
    timezone: "UTC",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:pathMatch(.*)*", component: NatAuditPage }],
  });
  await router.push("/nat-audit?deviceId=cisco");
  await router.isReady();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = mount(NatAuditPage, {
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
      stubs: {
        ModalDialog: {
          template: '<div v-if="open"><slot /></div>',
          props: ["open"],
        },
      },
    },
  });
  cleanup.push(() => {
    wrapper.unmount();
    client.clear();
  });
  await flushPromises();
  return { wrapper, calls };
}
it("keeps read roles safe, distinguishes NAT reception counters and automatically queries all retained history", async () => {
  const { wrapper: w, calls } = await render();
  expect(w.text()).toContain("NAT 审计");
  expect(w.text()).toContain("不代表丢失事件数");
  expect(w.text()).toContain("不保证完整接收");
  expect(w.find("[data-configure-nat]").exists()).toBe(false);
  expect(
    calls
      .find((c) => c.url.pathname.endsWith("/events"))!
      .url.searchParams.has("from"),
  ).toBe(false);
  await w.get("[data-nat-query]").trigger("submit");
  await flushPromises();
  const query = calls.find((c) => c.url.pathname.endsWith("/events"))!;
  expect(query.url.searchParams.get("deviceId")).toBe("cisco");
  expect(query.url.searchParams.get("limit")).toBe("30");
  expect(calls.every((c) => c.method === "GET")).toBe(true);
  expect(w.get('[data-nat-event="e1"]').text()).toContain("192.0.2.9:0");
  expect(w.get('[data-nat-event="e1"]').text()).toContain("设备时间缺失");
  expect(w.text()).toContain("单条创建或删除观测不等于完整会话");
});
it("allows custom multi-day history and resets opaque cursors when filters change", async () => {
  const { wrapper: w, calls } = await render();
  await w.get("#nat-range").setValue("custom");
  await w.get("#nat-from").setValue("2026-09-01T00:00:00");
  await w.get("#nat-to").setValue("2026-09-03T00:00:00");
  await w.get("[data-nat-query]").trigger("submit");
  await flushPromises();
  expect(
    calls
      .filter((c) => c.url.pathname.endsWith("/events"))
      .at(-1)!
      .url.searchParams.get("to"),
  ).toBe("2026-09-03T00:00:00.000Z");
  await w.get("#nat-to").setValue("2026-09-01T01:00:00");
  await w.get("[data-nat-query]").trigger("submit");
  await flushPromises();
  await w.get("[data-nat-next]").trigger("click");
  await flushPromises();
  expect(calls.at(-1)!.url.searchParams.get("cursor")).toBe("opaque-cursor");
  await w.get("#nat-protocol").setValue("17");
  await w.get("[data-nat-query]").trigger("submit");
  await flushPromises();
  expect(calls.at(-1)!.url.searchParams.get("protocol")).toBe("17");
  expect(calls.at(-1)!.url.searchParams.has("cursor")).toBe(false);
});
it("uses the independent source revision for state changes and refreshes on conflict", async () => {
  const { wrapper: w, calls } = await render(["ADMIN"], () =>
    Response.json(
      { code: "REVISION_CONFLICT", message: "版本冲突" },
      { status: 409 },
    ),
  );
  await w.get('[data-nat-state="cisco"]').trigger("click");
  await flushPromises();
  const mutation = calls.find((c) => c.method === "POST")!;
  expect(mutation.url.pathname).toBe("/api/v1/nat-audit/devices/cisco/state");
  expect(mutation.body).toEqual({ revision: 4, enabled: false });
  expect(w.text()).toContain("版本已变化");
  expect(
    calls.filter((c) => c.url.pathname.endsWith("/sources")).length,
  ).toBeGreaterThan(1);
});
it("keeps the current revision when reopening settings and sends blank source address as null", async () => {
  const { wrapper: w, calls } = await render(["ADMIN"]);
  for (let i = 0; i < 2; i++) {
    await w.get("[data-configure-nat]").trigger("click");
    await flushPromises();
    await w.get("#nat-source-address").setValue("");
    await w.get("[data-nat-settings]").trigger("submit");
    await flushPromises();
  }
  expect(calls.filter((c) => c.method === "POST").map((c) => c.body)).toEqual([
    { revision: 4, enabled: true, sourceAddress: null },
    { revision: 4, enabled: true, sourceAddress: null },
  ]);
});
