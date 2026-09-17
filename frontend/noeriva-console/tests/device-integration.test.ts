import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { useSessionStore } from "../src/stores/session";
import { setAccessToken, clearAuthorization } from "../src/services/api";
import { readCollectionStream } from "../src/services/devices";
import DeviceReading from "../src/components/DeviceReading.vue";
import DeviceSensors from "../src/components/DeviceSensors.vue";
import DeviceCollection from "../src/components/DeviceCollection.vue";
import DeviceManagement from "../src/components/DeviceManagement.vue";
import DeviceConnectionEditor from "../src/components/DeviceConnectionEditor.vue";
const clean: (() => void)[] = [];
it("annotates only explicitly excluded inactive interface sensors while retaining raw critical health", async () => {
  const reading = {
    observedAt: new Date().toISOString(),
    identity: {},
    health: "HEALTHY",
    metrics: {},
    ports: [],
    capabilities: [],
    qualityFlags: [],
    facts: { healthExcludedSensorIds: "s1" },
    sensors: [
      {
        id: "s1",
        label: "Tx Power inactive",
        metric: "power",
        value: 0,
        unit: "dBm",
        health: "CRITICAL",
        sourceRef: "snmp:1",
      },
      {
        id: "s2",
        label: "Temperature",
        metric: "temperature",
        value: 90,
        unit: "Cel",
        health: "CRITICAL",
        sourceRef: "snmp:2",
      },
    ],
  };
  vi.stubGlobal("fetch", async () =>
    Response.json({ items: [{ slot: "snmp", lastReading: reading }] }),
  );
  const detail = render(DeviceReading, { reading });
  const aggregate = render(DeviceSensors, { deviceId: "d1" });
  await flushPromises();
  for (const wrapper of [detail, aggregate]) {
    const rows = wrapper.findAll("tbody tr");
    const inactive = rows.find((row) =>
      row.text().includes("Tx Power inactive"),
    )!;
    const temperature = rows.find((row) => row.text().includes("Temperature"))!;
    expect(inactive.text()).toContain("严重");
    expect(inactive.text()).toContain("接口已管理关闭 · 低阈值仅保留证据");
    expect(temperature.text()).toContain("严重");
    expect(temperature.text()).not.toContain("仅保留证据");
  }
});
afterEach(() => {
  clean.splice(0).forEach((f) => f());
  vi.unstubAllGlobals();
  vi.useRealTimers();
  clearAuthorization();
  localStorage.clear();
});
function render(component: object, props: object, roles = ["ADMIN"]) {
  const pinia = createPinia();
  useSessionStore(pinia).session = {
    username: "admin",
    organizationId: "o",
    roles,
    mode: "CONNECTED",
    timezone: "UTC",
  };
  const client = new QueryClient();
  const wrapper = mount(component, {
    props,
    global: { plugins: [pinia, [VueQueryPlugin, { queryClient: client }]] },
  });
  clean.push(() => {
    wrapper.unmount();
    client.clear();
  });
  return wrapper;
}
it("edits inventory with its independent revision instead of telemetry revision", async () => {
  let submitted: any;
  const device = {
    id: "d1",
    name: "Before",
    type: "HOST",
    siteId: "s1",
    siteName: "Site",
    vendor: "",
    model: "",
    managementAddress: "192.0.2.1",
    health: "HEALTHY",
    availability: "ONLINE",
    lastSeen: null,
    revision: 400,
    capabilities: [],
  };
  vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
    if (url.endsWith("/updates")) {
      submitted = JSON.parse(String(init?.body));
      return Response.json({
        device: { ...device, ...submitted },
        inventoryRevision: 8,
      });
    }
    if (url.endsWith("/management"))
      return Response.json({ device, inventoryRevision: 7 });
    if (url.endsWith("/sites"))
      return Response.json({ items: [{ id: "s1", name: "Site" }] });
    throw Error(url);
  });
  const w = render(DeviceManagement, { deviceId: "d1" });
  await flushPromises();
  await w.get("[data-edit-device]").trigger("click");
  expect(w.get('#manage-type option[value="HOST"]').text()).toBe("主机");
  await w.get("#manage-name").setValue("After");
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(submitted).toMatchObject({ revision: 7, name: "After" });
  expect(w.text()).toContain("设备资料已保存");
});
it("writes fresh SNMP secrets once without echoing or persisting them", async () => {
  let submitted: any;
  vi.stubGlobal("fetch", async (_url: string, init?: RequestInit) => {
    submitted = JSON.parse(String(init?.body));
    return Response.json({
      slot: "snmp",
      revision: 1,
      hasAuthPassword: true,
      hasPrivacyPassword: true,
    });
  });
  const w = render(DeviceConnectionEditor, {
    slot: "snmp",
    deviceId: "d1",
    managementAddress: "192.0.2.1",
    connection: null,
  });
  await flushPromises();
  const auth = w.get('[name="authPassword"]');
  (auth.element as HTMLInputElement).value = "synthetic-auth-secret";
  await auth.trigger("input");
  const priv = w.get('[name="privacyPassword"]');
  (priv.element as HTMLInputElement).value = "synthetic-privacy-secret";
  await priv.trigger("input");
  await w.get("#snmp-username").setValue("monitor");
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(submitted).toMatchObject({
    revision: 0,
    snmpVersion: "3",
    authProtocol: "SHA256",
    privacyProtocol: "AES128",
    secrets: {
      authPassword: "synthetic-auth-secret",
      privacyPassword: "synthetic-privacy-secret",
    },
  });
  expect((auth.element as HTMLInputElement).value).toBe("");
  expect(w.html()).not.toContain("synthetic-auth-secret");
  expect(JSON.stringify(localStorage)).not.toContain("synthetic-auth-secret");
});
it("streams with Authorization and cancels its reader on abort", async () => {
  setAccessToken("synthetic-session-token");
  let seenUrl = "";
  let headers: Headers;
  let cancelled = false;
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      c.enqueue(
        new TextEncoder().encode(
          'event: collection\r\ndata: {"items":[],"asOf":"now"}\r\n\r\n',
        ),
      );
    },
    cancel() {
      cancelled = true;
    },
  });
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    seenUrl = url;
    headers = new Headers(init.headers);
    return new Response(stream, {
      headers: { "Content-Type": "text/event-stream" },
    });
  });
  const abort = new AbortController();
  const received: any[] = [];
  await readCollectionStream("d1", abort.signal, (v) => {
    received.push(v);
    abort.abort();
  });
  expect(received).toEqual([{ items: [], asOf: "now" }]);
  expect(seenUrl).toBe("/api/v1/devices/d1/live");
  expect(headers!.get("Authorization")).toBe("Bearer synthetic-session-token");
  expect(seenUrl).not.toContain("synthetic-session-token");
  expect(cancelled).toBe(true);
});

it("keeps read roles on safe endpoints and falls back to collection polling when SSE fails", async () => {
  vi.useFakeTimers();
  const urls: string[] = [];
  vi.stubGlobal("fetch", async (url: string) => {
    urls.push(url);
    if (url.endsWith("/device-support"))
      return Response.json({
        items: [],
        protocols: ["SNMP", "REDFISH"],
        credentialStorageReady: false,
        collectorEnabled: true,
      });
    if (url.endsWith("/collection"))
      return Response.json({ items: [], asOf: new Date().toISOString() });
    if (url.endsWith("/live")) return new Response("", { status: 503 });
    throw Error("Forbidden endpoint " + url);
  });
  const w = render(
    DeviceCollection,
    { deviceId: "d1", managementAddress: "192.0.2.1" },
    ["VIEWER"],
  );
  await flushPromises();
  expect(w.text()).toContain("管理员配置");
  expect(w.text()).toContain("轮询刷新");
  expect(urls.some((u) => u.endsWith("/connections"))).toBe(false);
  await vi.advanceTimersByTimeAsync(5000);
  expect(
    urls.filter((u) => u.endsWith("/collection")).length,
  ).toBeGreaterThanOrEqual(2);
});

const savedSnmp = {
  slot: "snmp",
  protocol: "SNMP",
  revision: 7,
  host: "192.0.2.1",
  port: 161,
  enabled: false,
  intervalSeconds: 60,
  timeoutMillis: 3000,
  maxInterfaces: 128,
  username: "monitor",
  snmpVersion: "3",
  securityLevel: "authPriv",
  authProtocol: "SHA256",
  privacyProtocol: "AES128",
  contextName: "",
  tlsMode: "",
  certificateSha256: "",
  hasCommunity: false,
  hasAuthPassword: true,
  hasPrivacyPassword: true,
  hasPassword: false,
  status: "NOT_TESTED",
  lastAttemptAt: null,
  lastSuccessAt: null,
  nextPollAt: null,
  errorCode: null,
  errorMessage: null,
  lastReading: null,
};
it("keeps existing secrets as null and requires new credentials after changing target", async () => {
  const submitted: any[] = [];
  vi.stubGlobal("fetch", async (_url: string, init?: RequestInit) => {
    submitted.push(JSON.parse(String(init?.body)));
    return Response.json(savedSnmp);
  });
  const w = render(DeviceConnectionEditor, {
    slot: "snmp",
    deviceId: "d1",
    managementAddress: "192.0.2.1",
    connection: savedSnmp,
  });
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(submitted[0]).toMatchObject({
    revision: 7,
    secrets: {
      community: null,
      authPassword: null,
      privacyPassword: null,
      password: null,
    },
  });
  await w.get("#snmp-host").setValue("192.0.2.2");
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(submitted).toHaveLength(1);
  expect(w.text()).toContain("请重新填写认证密码");
});
it("requires only the selected SNMP security-level secrets", async () => {
  const w = render(DeviceConnectionEditor, {
    slot: "snmp",
    deviceId: "d1",
    managementAddress: "192.0.2.1",
    connection: null,
  });
  await w.get("#snmp-version").setValue("2c");
  expect(w.find('[name="community"]').attributes()).toHaveProperty("required");
  expect(w.find('[name="authPassword"]').exists()).toBe(false);
  await w.get("#snmp-version").setValue("3");
  await w.get("#snmp-security").setValue("noAuthNoPriv");
  expect(w.findAll('input[type="password"]')).toHaveLength(0);
  await w.get("#snmp-security").setValue("authNoPriv");
  expect(w.findAll('input[type="password"]')).toHaveLength(1);
  expect(w.find('[name="authPassword"]').exists()).toBe(true);
});
it("sends Redfish trust pin and clears passwords after a failed save", async () => {
  let submitted: any;
  vi.stubGlobal("fetch", async (_url: string, init?: RequestInit) => {
    submitted = JSON.parse(String(init?.body));
    return Response.json(
      { message: "do not echo synthetic-redfish-secret" },
      { status: 400 },
    );
  });
  const w = render(DeviceConnectionEditor, {
    slot: "redfish",
    deviceId: "d1",
    managementAddress: "192.0.2.3",
    connection: null,
  });
  await w.get("#redfish-username").setValue("monitor");
  await w.get("#redfish-tls").setValue("PINNED");
  await w.get("#redfish-fingerprint").setValue("b".repeat(64));
  const password = w.get('[name="password"]');
  (password.element as HTMLInputElement).value = "synthetic-redfish-secret";
  await password.trigger("input");
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(submitted).toMatchObject({
    port: 443,
    tlsMode: "PINNED",
    certificateSha256: "b".repeat(64),
    secrets: { password: "synthetic-redfish-secret" },
  });
  expect(w.text()).toContain("连接保存失败");
  expect(w.text()).not.toContain("synthetic-redfish-secret");
  expect((password.element as HTMLInputElement).value).toBe("");
});
it.each([
  ["test", "测试连接并识别"],
  ["collect", "立即采集"],
  ["state", "启用采集"],
])(
  "posts %s with the saved connection revision and API request header",
  async (action, label) => {
    let captured: any;
    let requestHeaders: Headers | undefined;
    const connection = { ...savedSnmp, enabled: action === "collect" };
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      if (url.endsWith("/device-support"))
        return Response.json({
          items: [],
          protocols: ["SNMP", "REDFISH"],
          credentialStorageReady: true,
          collectorEnabled: false,
        });
      if (url.endsWith("/connections"))
        return Response.json({ items: [connection] });
      if (url.endsWith("/collection"))
        return Response.json({
          items: [connection],
          asOf: new Date().toISOString(),
        });
      if (url.endsWith("/live")) return new Response("", { status: 503 });
      if (url.endsWith("/" + action)) {
        captured = JSON.parse(String(init?.body));
        requestHeaders = new Headers(init?.headers);
        return Response.json(savedSnmp);
      }
      throw Error(url);
    });
    const w = render(DeviceCollection, {
      deviceId: "d1",
      managementAddress: "192.0.2.1",
    });
    await flushPromises();
    if (action === "test") {
      const collect = w.findAll("button").find((b) => b.text() === "立即采集")!;
      expect(collect.attributes("disabled")).toBeDefined();
      expect(collect.attributes("title")).toContain("先启用采集");
      expect(w.text()).toContain("手动测试仍会发起只读连接");
      await collect.trigger("click");
      expect(captured).toBeUndefined();
    }
    await w
      .findAll("button")
      .find((b) => b.text() === label)!
      .trigger("click");
    await flushPromises();
    expect(captured).toEqual(
      action === "state" ? { revision: 7, enabled: true } : { revision: 7 },
    );
    expect(requestHeaders!.get("X-Noeriva-Request")).toBe("1");
  },
);
it("cancels an idle SSE reader and stops reconnecting when the component unmounts", async () => {
  vi.useFakeTimers();
  let cancelled = false;
  let streamCalls = 0;
  const stream = new ReadableStream<Uint8Array>({
    cancel() {
      cancelled = true;
    },
  });
  vi.stubGlobal("fetch", async (url: string) => {
    if (url.endsWith("/device-support"))
      return Response.json({
        items: [],
        protocols: [],
        credentialStorageReady: true,
        collectorEnabled: false,
      });
    if (url.endsWith("/collection"))
      return Response.json({ items: [], asOf: new Date().toISOString() });
    if (url.endsWith("/live")) {
      streamCalls++;
      return new Response(stream, {
        headers: { "Content-Type": "text/event-stream" },
      });
    }
    throw Error(url);
  });
  const w = render(
    DeviceCollection,
    { deviceId: "d1", managementAddress: "192.0.2.1" },
    ["VIEWER"],
  );
  await flushPromises();
  w.unmount();
  await flushPromises();
  await vi.advanceTimersByTimeAsync(30000);
  expect(cancelled).toBe(true);
  expect(streamCalls).toBe(1);
});
it("accepts compact SSE event fields across chunk boundaries", async () => {
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      for (const part of [
        "event:collection\ndata:",
        '{"items":[],"asOf":"now"}\n',
        "\n",
      ])
        c.enqueue(new TextEncoder().encode(part));
      c.close();
    },
  });
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(stream, {
        headers: { "Content-Type": "text/event-stream" },
      }),
  );
  const received: any[] = [];
  await readCollectionStream("d1", new AbortController().signal, (v) =>
    received.push(v),
  );
  expect(received).toHaveLength(1);
});

it("preserves Redfish credentials with canonical inactive SNMP fields", async () => {
  let submitted: any;
  const connection = {
    ...savedSnmp,
    slot: "redfish",
    protocol: "REDFISH",
    port: 443,
    snmpVersion: "",
    securityLevel: "",
    authProtocol: "",
    privacyProtocol: "",
    contextName: "",
    tlsMode: "SYSTEM",
    hasAuthPassword: false,
    hasPrivacyPassword: false,
    hasPassword: true,
  };
  vi.stubGlobal("fetch", async (_url: string, init?: RequestInit) => {
    submitted = JSON.parse(String(init?.body));
    return Response.json(connection);
  });
  const w = render(DeviceConnectionEditor, {
    slot: "redfish",
    deviceId: "d1",
    managementAddress: "192.0.2.1",
    connection,
  });
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(submitted?.secrets.password).toBeNull();
  expect(w.find('[name="password"]').attributes("required")).toBeUndefined();
});
it.each([
  ["snmp-port", "162"],
  ["snmp-context", "another-context"],
])(
  "requires replacement secrets after changing %s",
  async (selector, value) => {
    const w = render(DeviceConnectionEditor, {
      slot: "snmp",
      deviceId: "d1",
      managementAddress: "192.0.2.1",
      connection: savedSnmp,
    });
    await w.get("#" + selector).setValue(value);
    expect(w.get('[name="authPassword"]').attributes()).toHaveProperty(
      "required",
    );
  },
);
it("requires replacement Redfish password when the TLS pin changes", async () => {
  const connection = {
    ...savedSnmp,
    slot: "redfish",
    protocol: "REDFISH",
    port: 443,
    snmpVersion: "",
    securityLevel: "",
    authProtocol: "",
    privacyProtocol: "",
    contextName: "",
    tlsMode: "PINNED",
    certificateSha256: "a".repeat(64),
    hasAuthPassword: false,
    hasPrivacyPassword: false,
    hasPassword: true,
  };
  const w = render(DeviceConnectionEditor, {
    slot: "redfish",
    deviceId: "d1",
    managementAddress: "192.0.2.1",
    connection,
  });
  expect(w.get('[name="password"]').attributes("required")).toBeUndefined();
  await w.get("#redfish-fingerprint").setValue("b".repeat(64));
  expect(w.get('[name="password"]').attributes()).toHaveProperty("required");
});
it("offers only implemented SNMP algorithms and rejects short new v3 passphrases locally", async () => {
  const submissions: string[] = [];
  vi.stubGlobal("fetch", async (url: string) => {
    submissions.push(url);
    return Response.json(savedSnmp);
  });
  const w = render(DeviceConnectionEditor, {
    slot: "snmp",
    deviceId: "d1",
    managementAddress: "192.0.2.1",
    connection: null,
  });
  expect(
    w
      .get("#snmp-auth")
      .findAll("option")
      .map((o) => o.attributes("value")),
  ).toEqual(["SHA256", "SHA512", "SHA1", "MD5"]);
  expect(
    w
      .get("#snmp-privacy")
      .findAll("option")
      .map((o) => o.attributes("value")),
  ).toEqual(["AES128", "DES"]);
  await w.get("#snmp-username").setValue("monitor");
  const auth = w.get('[name="authPassword"]');
  (auth.element as HTMLInputElement).value = "short";
  await auth.trigger("input");
  const priv = w.get('[name="privacyPassword"]');
  (priv.element as HTMLInputElement).value = "long-enough";
  await priv.trigger("input");
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(submissions.filter((url) => !url.endsWith("/settings"))).toEqual([]);
  expect(w.text()).toContain("至少 8 个字符");
});

it("renders nullable port capacity and sensor values as missing, never zero", () => {
  const reading = {
    observedAt: "2026-09-06T00:00:00Z",
    identity: {
      vendor: null,
      family: null,
      profileId: null,
      model: null,
      serialNumber: null,
      firmware: null,
      sysObjectId: null,
      sysName: null,
      description: null,
    },
    health: "UNKNOWN",
    metrics: {},
    capabilities: [],
    qualityFlags: [],
    facts: {},
    sensors: [
      {
        id: "sensor",
        label: "Temperature",
        metric: "temperature",
        unit: "Cel",
        value: null,
        health: "UNKNOWN",
        sourceRef: "test",
      },
    ],
    ports: [
      {
        key: "port",
        name: "eth0",
        macAddress: null,
        speedBps: null,
        adminStatus: "UNKNOWN",
        operStatus: "UNKNOWN",
        inOctets: null,
        outOctets: null,
        discontinuity: null,
        counterBits: 64,
        sourceRef: "test",
      },
    ],
  };
  const w = render(DeviceReading, { reading });
  expect(w.text()).toContain("未知");
  expect(w.text()).toContain("缺失");
  expect(w.text()).not.toContain("0 bit/s");
});

it("labels health-only component records as status items while numeric null remains missing", () => {
  const w = render(DeviceReading, {
    reading: {
      observedAt: "2026-09-06T12:00:00Z",
      identity: {},
      health: "HEALTHY",
      metrics: {},
      ports: [],
      capabilities: [],
      qualityFlags: [],
      facts: {},
      sensors: [
        {
          id: "status",
          label: "Power supply",
          metric: "componentHealth",
          unit: "state",
          value: null,
          health: "HEALTHY",
          sourceRef: "/PowerSupply/1#/Status/Health",
        },
        {
          id: "temp",
          label: "Temperature",
          metric: "temperature",
          unit: "°C",
          value: null,
          health: "UNKNOWN",
          sourceRef: "/Sensors/1",
        },
      ],
    },
  });
  const rows = w.findAll('[aria-label="实际传感器读数"] tbody tr');
  expect(rows[0]!.findAll("td")[2]!.text()).toBe("状态项");
  expect(rows[1]!.findAll("td")[2]!.text()).toContain("缺失");
  expect(rows[1]!.findAll("td")[2]!.text()).not.toContain("状态项");
});
it("renders readable metric names and sensor units while preserving raw identifiers", () => {
  const w = render(DeviceReading, {
    reading: {
      observedAt: "2026-09-07T00:00:00Z",
      identity: {},
      health: "UNKNOWN",
      metrics: { temperature_celsius: 23.5, power_watts: 123 },
      ports: [],
      capabilities: [],
      qualityFlags: ["INSPUR_NUMERIC_STRING"],
      facts: {},
      sensors: [
        {
          id: "temp",
          label: "Intake",
          metric: "temperature_celsius",
          unit: "Cel",
          value: 23.5,
          health: "UNKNOWN",
          sourceRef: "ssh:sensor/1",
        },
        {
          id: "fan",
          label: "FAN1",
          metric: "fan_rpm",
          unit: "RPM",
          value: 2600,
          health: "HEALTHY",
          sourceRef: "ssh:sensor/2",
        },
        {
          id: "voltage",
          label: "Rail",
          metric: "voltage_volts",
          unit: "V",
          value: 12.1,
          health: "HEALTHY",
          sourceRef: "ssh:sensor/3",
        },
      ],
    },
  });
  expect(w.text()).toContain("温度");
  expect(w.text()).toContain("功率");
  expect(w.text()).toContain("风扇转速");
  expect(w.text()).toContain("电压");
  expect(w.text()).toContain("°C");
  expect(w.text()).toContain("转/分钟");
  expect(w.find('[title="temperature_celsius"]').exists()).toBe(true);
  expect(w.find('[title="fan_rpm"]').exists()).toBe(true);
  expect(w.text()).toContain("INSPUR_NUMERIC_STRING");
});

it("leaves undecoded SSH discrete states separate from missing numeric measurements", () => {
  const w = render(DeviceReading, {
    reading: {
      observedAt: "2026-09-07T00:00:00Z",
      identity: {},
      health: "UNKNOWN",
      metrics: {},
      ports: [],
      capabilities: [],
      qualityFlags: [],
      facts: {},
      sensors: [
        {
          id: "state",
          label: "Power State",
          metric: "sensor_state",
          unit: "discrete",
          value: null,
          health: "UNKNOWN",
          sourceRef: "ssh:sensor/1",
        },
        {
          id: "amps",
          label: "Current",
          metric: "current_amps",
          unit: "A",
          value: null,
          health: "UNKNOWN",
          sourceRef: "ssh:sensor/2",
        },
      ],
    },
  });
  expect(w.text()).toContain("离散状态项（未解码）");
  expect(w.text()).toContain("电流");
  const rows = w.findAll('[aria-label="实际传感器读数"] tbody tr');
  expect(rows[1]!.findAll("td")[2]!.text()).toContain("缺失");
  expect(rows[1]!.findAll("td")[2]!.text()).not.toContain("状态项");
});
