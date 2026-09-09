import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import DiscoveryPage from "../src/pages/DiscoveryPage.vue";
import { useSessionStore } from "../src/stores/session";
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((fn) => fn());
  vi.unstubAllGlobals();
});
const candidate = {
  id: "c1",
  revision: 7,
  address: "192.0.2.3",
  siteId: "s1",
  name: "Neighbor",
  mac: "00:11:22:33:44:55",
  status: "POSSIBLE_DUPLICATE",
  reasons: ["NEIGHBOR_EVIDENCE_ONLY", "MAC_SHARED_BY_ADDRESSES"],
  associatedDeviceId: null,
  firstSeenAt: "2026-09-07T00:00:00Z",
  lastSeenAt: "2026-09-07T00:00:00Z",
  evidence: [
    {
      sourceDeviceId: "gateway",
      sourceDeviceName: "Gateway",
      source: "ARP",
      observedAt: "2026-09-07T00:00:00Z",
      address: "192.0.2.3",
      mac: "00:11:22:33:44:55",
      interfaceName: "Vlan10",
      vlan: "10",
      name: null,
      chassisId: null,
      chassisSubtype: null,
      portId: null,
      ttlSeconds: null,
      ageMinutes: 3,
      validUntil: null,
      qualityFlags: [],
    },
  ],
};
async function render(
  roles = ["OPERATOR"],
  row: any = candidate,
  post?: (path: string, body: any) => Response,
) {
  for (const method of ["showModal", "close"] as const) {
    const old = Object.getOwnPropertyDescriptor(
      HTMLDialogElement.prototype,
      method,
    );
    Object.defineProperty(HTMLDialogElement.prototype, method, {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.toggleAttribute("open", method === "showModal");
      },
    });
    cleanup.push(() => {
      if (old) Object.defineProperty(HTMLDialogElement.prototype, method, old);
      else
        delete (
          HTMLDialogElement.prototype as unknown as Record<string, unknown>
        )[method];
    });
  }
  const requests: { path: string; method: string; body: any }[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    const path = new URL(url, "http://localhost"),
      method = init.method || "GET",
      body = init.body ? JSON.parse(String(init.body)) : null;
    requests.push({ path: path.pathname + path.search, method, body });
    if (method === "POST") {
      expect(new Headers(init.headers).get("X-Noeriva-Request")).toBe("1");
      if (post) return post(path.pathname, body);
      return Response.json({
        id: "run1",
        siteId: "s1",
        cidr: "192.0.2.0/24",
        asOf: "2026-09-07T00:10:00Z",
        sourcesRequested: 1,
        sourcesUsed: 0,
        observationsRead: 0,
        candidatesUpdated: 0,
        existingCount: 0,
        duplicateCount: 0,
        conflictCount: 0,
        sources: [
          {
            deviceId: "gateway",
            deviceName: "Gateway",
            status: "STALE",
            observedAt: "2026-09-06T00:00:00Z",
            acceptedCount: 0,
            reason: "READING_STALE",
          },
        ],
        qualityFlags: [],
      });
    }
    if (path.pathname.endsWith("/sites"))
      return Response.json({
        items: [
          { id: "s1", name: "Site One" },
          { id: "s2", name: "Site Two" },
        ],
      });
    if (path.pathname.endsWith("/devices"))
      return Response.json({
        items: [
          {
            id: "gateway",
            name: "Gateway",
            managementAddress: "192.0.2.1",
            siteId: "s1",
            siteName: "Site One",
          },
          {
            id: "existing",
            name: "Existing",
            managementAddress: "192.0.2.9",
            siteId: "s1",
            siteName: "Site One",
          },
        ],
      });
    if (path.pathname.endsWith("/discovery/candidates"))
      return Response.json({
        items: [row],
        nextCursor: null,
        asOf: "2026-09-07T00:10:00Z",
        source: "DISCOVERY",
        mode: "CONNECTED",
      });
    throw Error(url);
  });
  const pinia = createPinia();
  useSessionStore(pinia).session = {
    username: "user",
    organizationId: "org",
    roles,
    mode: "CONNECTED",
    timezone: "UTC",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div/>" } }],
  });
  await router.push("/assets/discovery");
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const w = mount(DiscoveryPage, {
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
    },
  });
  cleanup.push(() => {
    w.unmount();
    client.clear();
  });
  await flushPromises();
  return { w, router, requests };
}
it("requires an explicit canonical CIDR and generates candidates only from saved source facts", async () => {
  const { w, requests } = await render();
  await w.get("#discovery-site").setValue("s1");
  await flushPromises();
  await w.get("#discovery-source").setValue("gateway");
  await w.get("[data-add-source]").trigger("click");
  for (const invalid of ["168.4.*", "192.0.2.3/24"]) {
    await w.get("#discovery-cidr").setValue(invalid);
    await w.get("[data-run-discovery]").trigger("submit");
    await flushPromises();
    expect(requests.filter((r) => r.method === "POST")).toHaveLength(0);
  }
  await w.get("#discovery-cidr").setValue("192.0.2.0/24");
  await w.get("[data-run-discovery]").trigger("submit");
  await flushPromises();
  expect(requests.filter((r) => r.method === "POST")).toEqual([
    {
      path: "/api/v1/discovery/runs",
      method: "POST",
      body: {
        sourceDeviceIds: ["gateway"],
        cidr: "192.0.2.0/24",
        siteId: "s1",
      },
    },
  ]);
  expect(w.text()).toContain("来源已过期");
  expect(w.text()).toContain("没有有效证据不代表网段没有设备");
  expect(requests.some((r) => /connections|collect|scan/.test(r.path))).toBe(
    false,
  );
});
it("never auto-merges a shared MAC and requires explicit review before independent registration", async () => {
  const { w, router, requests } = await render(
    ["OPERATOR"],
    candidate,
    (_path, body) =>
      Response.json({
        ...candidate,
        revision: body.revision + 1,
        status: "REGISTERED",
        associatedDeviceId: "new-device",
      }),
  );
  expect(requests.some((r) => r.method === "POST")).toBe(false);
  await w.get('[data-review="c1"]').trigger("click");
  await flushPromises();
  expect(w.text()).toContain("同一 MAC 出现在多个地址");
  expect(w.text()).toContain("ARP 表年龄");
  await w.get("[data-register-candidate]").trigger("submit");
  await flushPromises();
  expect(requests.some((r) => r.method === "POST")).toBe(false);
  await w.get("#discovery-register-confirm").setValue(true);
  await w.get("#discovery-register-name").setValue("Independent device");
  await w.get("[data-register-candidate]").trigger("submit");
  await flushPromises();
  expect(requests.find((r) => r.method === "POST")).toMatchObject({
    path: "/api/v1/discovery/candidates/c1/register",
    body: { revision: 7, name: "Independent device", type: "HOST" },
  });
  expect(router.currentRoute.value.path).toBe("/assets/new-device");
});
it("shows conflict evidence and links only through an explicit same-site asset choice", async () => {
  const { w, requests, router } = await render(
    ["ADMIN"],
    {
      ...candidate,
      status: "CONFLICT",
      reasons: ["ADDRESS_HAS_MULTIPLE_MACS"],
    },
    (_path, body) =>
      Response.json({
        ...candidate,
        status: "LINKED",
        associatedDeviceId: body.deviceId,
      }),
  );
  await w.get('[data-review="c1"]').trigger("click");
  await flushPromises();
  expect(w.find("[data-register-candidate]").exists()).toBe(false);
  expect(w.text()).toContain("同一地址存在多个 MAC");
  expect(requests.some((r) => r.path.includes("/devices?siteId=s1"))).toBe(
    true,
  );
  await w.get("#discovery-link-device").setValue("existing");
  await w.get("[data-link-candidate]").trigger("submit");
  await flushPromises();
  expect(requests.find((r) => r.method === "POST")).toMatchObject({
    path: "/api/v1/discovery/candidates/c1/link",
    body: { revision: 7, deviceId: "existing" },
  });
  expect(router.currentRoute.value.path).toBe("/assets/existing");
});
it("lets VIEWER inspect evidence without source selection, registration or link writes", async () => {
  const { w, requests } = await render(["VIEWER"]);
  expect(w.find("[data-run-discovery]").exists()).toBe(false);
  await w.get('[data-review="c1"]').trigger("click");
  await flushPromises();
  expect(w.find("[data-register-candidate]").exists()).toBe(false);
  expect(w.find("[data-link-candidate]").exists()).toBe(false);
  expect(w.text()).toContain("Gateway");
  expect(requests.every((r) => r.method === "GET")).toBe(true);
  expect(requests.some((r) => /connections|\/devices\?/.test(r.path))).toBe(
    false,
  );
});
it("keeps a rejected candidate visible and does not retry a conflicting write automatically", async () => {
  const { w, requests } = await render(
    ["OPERATOR"],
    { ...candidate, status: "NEW", reasons: [] },
    () =>
      Response.json(
        { code: "REVISION_CONFLICT", message: "Changed" },
        { status: 409 },
      ),
  );
  await w.get('[data-review="c1"]').trigger("click");
  await flushPromises();
  await w.get("[data-register-candidate]").trigger("submit");
  await flushPromises();
  expect(requests.filter((r) => r.method === "POST")).toHaveLength(1);
  expect(w.text()).toContain("候选已变化");
  expect(w.find("[data-register-candidate]").exists()).toBe(true);
});

it("resets selected sources when switching site and keeps prefix queries explicit", async () => {
  const { w, requests } = await render();
  await w.get("#discovery-site").setValue("s1");
  await flushPromises();
  await w.get("#discovery-source").setValue("gateway");
  await w.get("[data-add-source]").trigger("click");
  expect(w.findAll(".discovery-source-list li")).toHaveLength(1);
  const input = w.get("#discovery-source-query");
  (input.element as HTMLInputElement).value = "Gateway";
  await input.trigger("input");
  await flushPromises();
  expect(requests.some((r) => r.path.includes("q=Gateway"))).toBe(false);
  await input.trigger("keydown", { key: "Enter" });
  await flushPromises();
  expect(
    requests.some(
      (r) => r.path.includes("siteId=s1") && r.path.includes("q=Gateway"),
    ),
  ).toBe(true);
  await w.get("#discovery-site").setValue("s2");
  await flushPromises();
  expect(w.findAll(".discovery-source-list li")).toHaveLength(0);
  await w.get("[data-run-discovery]").trigger("submit");
  await flushPromises();
  expect(requests.some((r) => r.method === "POST")).toBe(false);
});
