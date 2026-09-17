import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { createMemoryHistory, createRouter } from "vue-router";
import { useSessionStore } from "../src/stores/session";
import ApplicationMonitoringPage from "../src/pages/ApplicationMonitoringPage.vue";
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((f) => f());
  vi.unstubAllGlobals();
});
const source = {
  deviceId: "cisco",
  deviceName: "Cisco ASR",
  siteId: "s1",
  revision: 7,
  enabled: false,
  intervalSeconds: 60,
  interfaceIndices: [8],
  maxRows: 128,
  status: "DISABLED",
  lastAttemptAt: null,
  lastSuccessAt: null,
  nextPollAt: null,
  errorCode: "",
  errorMessage: "",
  credentialRevision: 20,
  protocol: "CISCO_NBAR_SNMP",
  lastRowCount: 2,
  qualityFlags: ["NBAR_BASELINE_REQUIRED"],
};
const observation = {
  id: "o1",
  deviceId: "cisco",
  interfaceIndex: 8,
  interfaceName: "GigabitEthernet0/0/0",
  protocolIndex: 41,
  application: "tls",
  direction: "IN",
  observedAt: "2026-09-07T00:10:00Z",
  bytes: "18446744073709551610",
  packets: null,
  reportedBps: 0,
  derivedBps: null,
  derivedPacketsPerSecond: null,
  intervalSeconds: null,
  sourceEpoch: "epoch1",
  qualityFlags: ["NBAR_BASELINE_REQUIRED"],
};
async function render(
  roles = ["VIEWER"],
  row: Omit<typeof observation, "packets"> & {
    packets: string | null;
  } = observation,
) {
  const calls: { url: URL; method: string; body: any }[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    const parsed = new URL(url, "http://localhost"),
      body = init.body ? JSON.parse(String(init.body)) : null;
    calls.push({ url: parsed, method: init.method || "GET", body });
    if (init.method === "POST") {
      expect(new Headers(init.headers).get("X-Noeriva-Request")).toBe("1");
      return Response.json(source);
    }
    if (parsed.pathname.endsWith("/applications/sources"))
      return Response.json({
        items: [source],
        nextCursor: null,
        source: "MYSQL",
        mode: "CONNECTED",
      });
    if (parsed.pathname.endsWith("/applications/summary"))
      return Response.json({
        deviceId: "cisco",
        observedAt: observation.observedAt,
        freshness: "FRESH",
        sampleRows: 2,
        totalApplications: 1,
        qualityFlags: [],
        items: [
          {
            application: "tls",
            direction: "IN",
            interfaceIndices: [8],
            derivedBps: 123000,
            reportedBps: 0,
            observationCount: 1,
            qualityFlags: [],
          },
        ],
      });
    if (parsed.pathname.endsWith("/applications/observations"))
      return Response.json({
        items: [row],
        nextCursor: parsed.searchParams.has("cursor") ? null : "history-cursor",
        source: "CLICKHOUSE",
        mode: "CONNECTED",
        asOf: observation.observedAt,
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
    routes: [
      { path: "/:pathMatch(.*)*", component: ApplicationMonitoringPage },
    ],
  });
  await router.push("/applications?deviceId=cisco");
  await router.isReady();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = mount(ApplicationMonitoringPage, {
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
      stubs: {
        ChartCanvas: true,
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
it("preserves Counter64 precision, observed zero and missing derived rates with explicit source scope", async () => {
  const { wrapper: w, calls } = await render();
  expect(w.find("[data-application-edit]").exists()).toBe(false);
  await w.get("[data-application-query]").trigger("submit");
  await flushPromises();
  const row = w.get('[data-application-observation="o1"]');
  expect(row.get("[data-bytes]").text()).toBe("16 EiB");
  expect(row.get("[data-bytes]").attributes("title")).toBe(
    "18446744073709551610 bytes",
  );
  expect(row.get("[data-reported-rate]").text()).toBe("0 bps");
  expect(row.get("[data-derived-rate]").text()).toBe("—");
  expect(row.get("[data-packets]").text()).toBe("—");
  expect(w.text()).toContain("覆盖比例与采样率未提供");
  expect(w.text()).toContain("不提供客户端 IP、会话数或 NAT 映射");
  expect(calls.every((c) => c.method === "GET")).toBe(true);
});
it("scales cumulative bytes and groups packet counts while exposing exact audit values", async () => {
  const { wrapper } = await render(["VIEWER"], {
    ...observation,
    bytes: "21918098559",
    packets: "18446744073709551610",
  });
  await wrapper.get("[data-application-query]").trigger("submit");
  await flushPromises();
  const row = wrapper.get('[data-application-observation="o1"]');
  expect(row.get("[data-bytes]").text()).toBe("20.41 GiB");
  expect(row.get("[data-bytes]").attributes("title")).toBe("21918098559 bytes");
  expect(row.get("[data-packets]").text()).toBe("18,446,744,073,709,551,610");
  expect(row.get("[data-packets]").attributes("title")).toBe(
    "18446744073709551610 包",
  );
});
it("saves independent application revision and explicit ifIndices without touching SNMP settings", async () => {
  const { wrapper: w, calls } = await render(["ADMIN"]);
  await w.get('[data-application-edit="cisco"]').trigger("click");
  await w.get("#application-ifindices").setValue("8, 8");
  await w.get("[data-application-settings]").trigger("submit");
  await flushPromises();
  expect(w.text()).toContain("1–8 个互异");
  expect(calls.some((c) => c.method === "POST")).toBe(false);
  await w.get("#application-ifindices").setValue("8, 16");
  await w.get("#application-enabled").setValue(true);
  await w.get("[data-application-settings]").trigger("submit");
  await flushPromises();
  const mutation = calls.find((c) => c.method === "POST")!;
  expect(mutation.url.pathname).toBe(
    "/api/v1/applications/devices/cisco/settings",
  );
  expect(mutation.body).toEqual({
    revision: 7,
    enabled: true,
    intervalSeconds: 60,
    interfaceIndices: [8, 16],
    maxRows: 128,
  });
  expect(calls.some((c) => c.url.pathname.includes("/connections"))).toBe(
    false,
  );
});
it("allows manual application collection while its schedule is disabled", async () => {
  const { wrapper: w, calls } = await render(["ADMIN"]);
  await w.get('[data-application-collect="cisco"]').trigger("click");
  await flushPromises();
  const mutation = calls.find((c) => c.method === "POST")!;
  expect(mutation.url.pathname).toBe(
    "/api/v1/applications/devices/cisco/collect",
  );
  expect(mutation.body).toEqual({ revision: 7 });
  expect(w.text()).toContain("以实际状态与质量标记为准");
});
it("submits application search and direction together, clearing previous opaque cursors", async () => {
  const { wrapper: w, calls } = await render();
  await w.get("[data-application-query]").trigger("submit");
  await flushPromises();
  await w.get("[data-application-next]").trigger("click");
  await flushPromises();
  expect(
    calls
      .filter((c) => c.url.pathname.endsWith("/observations"))
      .at(-1)!
      .url.searchParams.get("cursor"),
  ).toBe("history-cursor");
  const input = w.get("#application-q");
  (input.element as HTMLInputElement).value = "tls";
  await input.trigger("input");
  expect(
    calls
      .filter((c) => c.url.pathname.endsWith("/observations"))
      .at(-1)!
      .url.searchParams.get("cursor"),
  ).toBe("history-cursor");
  await w.get("#application-direction").setValue("OUT");
  await w.get("[data-application-query]").trigger("submit");
  await flushPromises();
  expect(calls.at(-1)!.url.searchParams.get("q")).toBe("tls");
  expect(calls.at(-1)!.url.searchParams.get("direction")).toBe("OUT");
  expect(calls.at(-1)!.url.searchParams.has("cursor")).toBe(false);
});

it("loads application summary automatically without a manual history query", async () => {
  const { wrapper, calls } = await render();
  expect(
    calls.some((c) => c.url.pathname.endsWith("/applications/summary")),
  ).toBe(true);
  expect(
    calls.some((c) => c.url.pathname.endsWith("/applications/observations")),
  ).toBe(false);
  expect(wrapper.text()).toContain("应用带宽排行");
  expect(wrapper.text()).toContain("123 Kbps");
});
