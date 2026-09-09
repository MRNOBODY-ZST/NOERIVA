import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { useSessionStore } from "../src/stores/session";
import {
  naturalNameSort,
  formatMetric,
  identityValue,
} from "../src/utils/format";
import SettingsPage from "../src/pages/SettingsPage.vue";
import DevicePage from "../src/pages/DevicePage.vue";
import DeviceConnectionEditor from "../src/components/DeviceConnectionEditor.vue";
import ChecksPage from "../src/pages/ChecksPage.vue";
import ConfigurationPage from "../src/pages/ConfigurationPage.vue";
import EventsPage from "../src/pages/EventsPage.vue";
import SidebarNav from "../src/components/SidebarNav.vue";
import { usePreferencesStore } from "../src/stores/preferences";
import App from "../src/App.vue";
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((run) => run());
  vi.unstubAllGlobals();
  vi.useRealTimers();
  localStorage.clear();
});
const settings = {
  revision: 3,
  organizationName: "Operations",
  timezone: "Asia/Shanghai",
  defaultCollectionIntervalSeconds: 120,
  defaultTimeoutMillis: 4000,
  defaultMaxInterfaces: 200,
  configurationSyncEnabled: true,
  configurationSyncIntervalSeconds: 1800,
  updatedAt: "2026-09-09T00:00:00Z",
  updatedBy: "admin",
  runtime: {
    mode: "CONNECTED",
    searchProvider: "ELASTICSEARCH",
    searchStatus: "AVAILABLE",
    searchLastIndexedAt: "2026-09-09T00:00:00Z",
    historyRetention: "NO_AUTOMATIC_DELETION",
  },
  account: { username: "admin", roles: ["ADMIN"], organizationId: "org" },
};
async function render(
  component: object,
  fetcher: (url: string, init: RequestInit) => Promise<Response>,
  props = {},
  path = "/settings",
  roles = ["ADMIN"],
) {
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
  vi.stubGlobal("fetch", (url: string, init: RequestInit = {}) =>
    fetcher(url, init),
  );
  const pinia = createPinia();
  useSessionStore(pinia).session = {
    username: "admin",
    organizationId: "org",
    roles,
    mode: "CONNECTED",
    timezone: "UTC",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/devices/:id", component: DevicePage },
      { path: "/:pathMatch(.*)*", component: { template: "<div/>" } },
    ],
  });
  await router.push(path);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = mount(component, {
    props,
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
      stubs: {
        ChartCanvas: true,
        GraphPanel: true,
        BandwidthHeatmap: true,
        DeviceSensors: true,
        SidebarNav: true,
        ModalDialog: {
          props: ["open"],
          template: '<div v-if="open"><slot/></div>',
        },
      },
    },
  });
  cleanup.push(() => {
    wrapper.unmount();
    client.clear();
  });
  await flushPromises();
  return { wrapper, router, client };
}
it("shares saved organization settings with the shell without polling every 20 seconds", async () => {
  vi.useFakeTimers();
  let settingsReads = 0;
  const { wrapper, client } = await render(
    App,
    async (url) => {
      if (url.endsWith("/settings")) settingsReads++;
      return Response.json(settings);
    },
    {},
    "/overview",
  );
  expect(wrapper.findComponent(SidebarNav).props("organizationName")).toBe(
    "Operations",
  );
  await vi.advanceTimersByTimeAsync(60_000);
  expect(settingsReads).toBe(1);
  client.setQueryData(["noeriva", "org", "admin", "/settings"], {
    ...settings,
    organizationName: "澄观工作区",
  });
  await flushPromises();
  expect(wrapper.findComponent(SidebarNav).props("organizationName")).toBe(
    "澄观工作区",
  );
});
it("labels snapshots with device, capture time and readable source and retains selection labels across pages", async () => {
  const snapshots = [
    {
      id: "snapshot1",
      deviceId: "a720db9a-test",
      title: "自动同步 · SNMP_DEVICE_BASELINE",
      source: "SNMP_DEVICE_BASELINE",
      capturedAt: "2026-09-09T01:00:00Z",
      sha256: "abcdef1234567890",
      redactedLines: 0,
    },
    {
      id: "snapshot2",
      deviceId: "a720db9a-test",
      title: "自动同步 · SSH_RUNNING_CONFIGURATION",
      source: "SSH_RUNNING_CONFIGURATION",
      capturedAt: "2026-09-09T02:00:00Z",
      sha256: "abcdef1234567890",
      redactedLines: 0,
    },
  ];
  const { wrapper } = await render(
    ConfigurationPage,
    async (url) => {
      if (url.includes("/configuration/snapshots"))
        return Response.json({
          items: url.includes("cursor=") ? [snapshots[1]] : [snapshots[0]],
          nextCursor: url.includes("cursor=") ? null : "page2",
        });
      if (url.endsWith("/devices/a720db9a-test"))
        return Response.json({
          id: "a720db9a-test",
          name: "Dell 核心交换机",
          type: "SWITCH",
        });
      return Response.json({ items: [] });
    },
    {},
    "/configuration",
  );
  expect(wrapper.get("tbody").text()).toContain("Dell 核心交换机");
  expect(wrapper.get("tbody").text()).not.toContain("a720db9a");
  expect(wrapper.get("#snapshot-before").text()).toContain(
    "Dell 核心交换机 · 09/09 09:00:00 · SNMP 设备状态基线",
  );
  await wrapper.get("#snapshot-before").setValue("snapshot1");
  await wrapper
    .findAll("button")
    .find((button) => button.text().includes("下一页"))!
    .trigger("click");
  await flushPromises();
  expect(wrapper.get("#snapshot-before").text()).toContain("09:00:00");
  expect(wrapper.get("#snapshot-after").text()).toContain("10:00:00");
  expect(wrapper.get("#snapshot-before").text()).not.toContain("snapshot1");
});
it("resolves only current event page devices and filters on their display names", async () => {
  const calls: string[] = [];
  const { wrapper } = await render(
    EventsPage,
    async (url) => {
      calls.push(url);
      if (url.includes("/events?"))
        return Response.json({
          items: [1, 2].map((id) => ({
            id: `e${id}`,
            deviceId: "device-uuid",
            kind: "DEVICE_HEALTH_CHANGED",
            message: "温度阈值已超限",
            source: "SNMP",
            severity: "WARNING",
            observedAt: "2026-09-09T01:00:00Z",
          })),
        });
      if (url.endsWith("/devices/device-uuid"))
        return Response.json({
          id: "device-uuid",
          name: "Cisco 汇聚路由器",
          type: "ROUTER",
        });
      throw Error(url);
    },
    {},
    "/events",
  );
  expect(wrapper.get("tbody").text()).toContain("Cisco 汇聚路由器");
  expect(wrapper.get("tbody").text()).not.toContain("device-uuid");
  expect(
    calls.filter((url) => url.endsWith("/devices/device-uuid")),
  ).toHaveLength(1);
  expect(calls.some((url) => /\/devices\?/.test(url))).toBe(false);
  await wrapper.get('[aria-label="筛选当前页事件"]').setValue("Cisco");
  expect(wrapper.findAll("tbody tr")).toHaveLength(2);
});
it("sorts interfaces naturally without mutating server data and scales operational units", () => {
  const ports = [{ name: "Te0/1/10" }, { name: "te0/1/2" }, { name: "Gi0/0" }];
  expect(naturalNameSort(ports).map((p) => p.name)).toEqual([
    "Gi0/0",
    "te0/1/2",
    "Te0/1/10",
  ]);
  expect(ports[0]!.name).toBe("Te0/1/10");
  expect(formatMetric(1500000, "bit/s")).toBe("1.50 Mbps");
  expect(formatMetric(2500, "W")).toBe("2.5 kW");
  expect(identityValue("NA")).toBe("未提供");
});
it("persists settings with the observed revision while viewer controls remain readonly", async () => {
  const posted: any[] = [];
  const { wrapper } = await render(SettingsPage, async (_url, init) => {
    if (init.method === "POST") {
      posted.push(JSON.parse(String(init.body)));
      return Response.json({ ...settings, ...posted.at(-1), revision: 4 });
    }
    return Response.json(settings);
  });
  await wrapper.get("#organization-name").setValue("Network operations");
  await wrapper.findAll("form")[0]!.trigger("submit");
  await flushPromises();
  expect(posted[0]).toMatchObject({
    revision: 3,
    organizationName: "Network operations",
    defaultCollectionIntervalSeconds: 120,
  });
  expect(posted[0]).not.toHaveProperty("runtime");
  expect(wrapper.text()).toContain("系统设置已保存");
  const viewer = await render(
    SettingsPage,
    async () => Response.json(settings),
    {},
    "/settings",
    ["VIEWER"],
  );
  expect(viewer.wrapper.get("fieldset").attributes("disabled")).toBeDefined();
  expect(
    viewer.wrapper
      .findAll("button")
      .some((button) => button.text() === "保存系统设置"),
  ).toBe(false);
});
it("new connections receive organization defaults without overwriting an existing connection", async () => {
  const props = {
    deviceId: "d1",
    slot: "snmp",
    managementAddress: "192.0.2.1",
    connection: null,
  };
  const fresh = await render(
    DeviceConnectionEditor,
    async () => Response.json(settings),
    props,
  );
  const freshModel = (fresh.wrapper.vm as any).form;
  expect(freshModel.intervalSeconds).toBe(120);
  expect(freshModel.timeoutMillis).toBe(4000);
  expect(freshModel.maxInterfaces).toBe(200);
  const old = await render(
    DeviceConnectionEditor,
    async () => Response.json(settings),
    {
      ...props,
      connection: {
        revision: 2,
        host: "192.0.2.1",
        intervalSeconds: 60,
        timeoutMillis: 3000,
        maxInterfaces: 64,
        username: "reader",
        snmpVersion: "3",
        securityLevel: "authPriv",
        authProtocol: "SHA256",
        privacyProtocol: "AES128",
        contextName: "",
        hasAuthPassword: true,
        hasPrivacyPassword: true,
      },
    },
  );
  expect((old.wrapper.vm as any).form.intervalSeconds).toBe(60);
});
it("uses device aggregate heatmap by default and preserves explicit interface deep links", async () => {
  const calls: string[] = [];
  const summary = {
    device: {
      id: "d1",
      name: "Switch",
      type: "SWITCH",
      capabilities: ["metrics"],
      health: "HEALTHY",
      availability: "ONLINE",
      siteName: "Site",
    },
    sources: [],
    activeAlerts: 0,
    coverage: 0,
  };
  const fetcher = async (url: string) => {
    calls.push(url);
    if (url.endsWith("/summary")) return Response.json(summary);
    if (url.endsWith("/interfaces"))
      return Response.json({
        items: [
          { id: "if10", name: "Te0/1/10", speedBps: "10000000000" },
          { id: "if2", name: "Te0/1/2", speedBps: "10000000000" },
        ],
      });
    if (url.includes("/metrics?")) return Response.json({ points: [] });
    if (url.includes("/heatmap?")) return Response.json({ cells: [] });
    throw Error(url);
  };
  const { wrapper, router } = await render(
    DevicePage,
    fetcher,
    {},
    "/devices/d1",
  );
  expect(
    calls.some((url) => url.includes("/devices/d1/bandwidth/heatmap?")),
  ).toBe(true);
  expect(calls.some((url) => url.includes("/interfaces/if10/bandwidth"))).toBe(
    false,
  );
  expect(wrapper.get("#heat-interface").element).toHaveProperty("value", "");
  expect(
    wrapper
      .findAll("#heat-interface option")
      .map((option) => option.attributes("value")),
  ).toEqual(["", "if2", "if10"]);
  await router.push("/devices/d1?tab=network&interfaceId=if10");
  await flushPromises();
  expect(wrapper.get('[data-interface-id="if10"]').classes()).toContain(
    "wb-select-row",
  );
});
it("searches interfaces and opens their owning device with interface context", async () => {
  const { wrapper, router } = await render(
    App,
    async (url) =>
      url.includes("/workspace/search")
        ? Response.json({
            assets: [],
            recentEvents: [],
            interfaces: [
              {
                id: "if8",
                deviceId: "router1",
                deviceName: "ASR",
                name: "Te0/1/0",
              },
            ],
            provider: "ELASTICSEARCH",
            status: "AVAILABLE",
          })
        : Response.json({}),
    {},
    "/overview",
  );
  await wrapper.get(".search-trigger").trigger("click");
  await wrapper.get('[aria-label="全局搜索"]').setValue("Te0");
  await new Promise((resolve) => setTimeout(resolve, 220));
  await flushPromises();
  const link = wrapper
    .findAll(".command-result")
    .find((row) => row.text().includes("Te0/1/0"));
  expect(link).toBeDefined();
  await link!.trigger("click");
  await flushPromises();
  expect(router.currentRoute.value.path).toBe("/devices/router1");
  expect(router.currentRoute.value.query).toMatchObject({
    tab: "network",
    interfaceId: "if8",
  });
});

it("applies organization timezone only until a personal preference exists", () => {
  const prefs = usePreferencesStore(createPinia());
  prefs.applyOrganizationTimezone("UTC");
  expect(prefs.timezone).toBe("UTC");
  expect(localStorage.getItem("noeriva-timezone")).toBeNull();
  prefs.timezone = "Europe/Berlin";
  prefs.applyOrganizationTimezone("Asia/Singapore");
  expect(prefs.timezone).toBe("Europe/Berlin");
});
it("polls the current device header every 20 seconds while preserving the selected tab", async () => {
  vi.useFakeTimers();
  let reads = 0;
  const { wrapper, router } = await render(
    DevicePage,
    async (url) => {
      if (url.endsWith("/summary")) {
        reads++;
        return Response.json({
          device: {
            id: "d1",
            name: "Current device",
            type: "HOST",
            capabilities: [],
            health: reads === 1 ? "HEALTHY" : "UNKNOWN",
            availability: reads === 1 ? "ONLINE" : "OFFLINE",
            siteName: "Site",
            lastSeen:
              reads === 1 ? "2026-09-09T01:00:00Z" : "2026-09-09T01:01:00Z",
          },
          sources: [],
          activeAlerts: 0,
          coverage: 0,
        });
      }
      return Response.json({ items: [] });
    },
    {},
    "/devices/d1?tab=monitoring",
  );
  expect(wrapper.text()).toContain("09:00:00");
  await vi.advanceTimersByTimeAsync(20_000);
  await flushPromises();
  expect(reads).toBe(2);
  expect(wrapper.text()).toContain("09:01:00");
  expect(wrapper.text()).toContain("离线");
  expect(router.currentRoute.value.query.tab).toBe("monitoring");
});
it("runs a saved check with its revision and shows the real returned failure", async () => {
  const check = {
    id: "check1",
    deviceId: "d1",
    name: "Management HTTPS",
    type: "HTTPS",
    target: "https://192.0.2.1",
    intervalSeconds: 60,
    revision: 3,
    enabled: false,
    archived: false,
    provenance: "MANUAL",
    lastResult: null,
  };
  const result = {
    id: "result1",
    checkId: "check1",
    status: "FAIL",
    observedAt: "2026-09-09T00:00:00Z",
    source: "noeriva-native-https",
    message: "TLS_VERIFICATION_OR_HANDSHAKE_FAILED",
    definitionRevision: 3,
  };
  const posted: any[] = [];
  const { wrapper } = await render(
    ChecksPage,
    async (url, init) => {
      if (url.endsWith("/run")) {
        posted.push(JSON.parse(String(init.body)));
        return Response.json(result);
      }
      if (url.includes("/results")) return Response.json({ items: [result] });
      return Response.json({ items: [check] });
    },
    {},
    "/checks",
  );
  await wrapper
    .findAll("button")
    .find((button) => button.text() === "立即执行")!
    .trigger("click");
  await flushPromises();
  expect(posted).toEqual([{ revision: 3 }]);
  expect(wrapper.text()).toContain("探测已完成：失败");
  expect(wrapper.text()).toContain("TLS_VERIFICATION_OR_HANDSHAKE_FAILED");
});
