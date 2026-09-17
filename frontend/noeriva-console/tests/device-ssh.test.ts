import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import DeviceConnectionEditor from "../src/components/DeviceConnectionEditor.vue";
import {
  readCollectionStream,
  type ConnectionView,
} from "../src/services/devices";
import { useSessionStore } from "../src/stores/session";
const pin = `SHA256:${"A".repeat(43)}`;
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((fn) => fn());
  vi.unstubAllGlobals();
  localStorage.clear();
});
async function editor(previous: object | null = null) {
  const pinia = createPinia();
  useSessionStore(pinia).session = {
    username: "admin",
    organizationId: "o",
    roles: ["ADMIN"],
    mode: "CONNECTED",
    timezone: "UTC",
  };
  const client = new QueryClient();
  const w = mount(DeviceConnectionEditor, {
    props: {
      deviceId: "d1",
      slot: "ssh",
      managementAddress: "192.0.2.1",
      connection: previous as ConnectionView | null,
    },
    global: { plugins: [pinia, [VueQueryPlugin, { queryClient: client }]] },
  });
  cleanup.push(() => {
    w.unmount();
    client.clear();
  });
  await flushPromises();
  return w;
}
it("writes only the fixed SSH profile, pinned host key and one transient password", async () => {
  let body: any;
  let path = "";
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    path = url;
    body = JSON.parse(String(init.body));
    return Response.json({ slot: "ssh", revision: 1, hasPassword: true });
  });
  const w = await editor();
  expect(w.get("#ssh-port").element).toHaveProperty("value", "22");
  expect(w.find("#redfish-tls").exists()).toBe(false);
  await w.get("#ssh-username").setValue("reader");
  await w.get("#ssh-profile").setValue("DELL_OS9");
  await w.get("#ssh-fingerprint").setValue(pin);
  expect(
    new RegExp(w.get("#ssh-fingerprint").attributes("pattern")!, "v").test(pin),
  ).toBe(true);
  const password = w.get('[name="password"]');
  (password.element as HTMLInputElement).value = "synthetic-ssh-secret";
  await password.trigger("input");
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(path).toBe("/api/v1/devices/d1/connections/ssh");
  expect(body).toMatchObject({
    revision: 0,
    host: "192.0.2.1",
    port: 22,
    enabled: false,
    username: "reader",
    sshProfile: "DELL_OS9",
    sshHostKeySha256: pin,
    secrets: {
      password: "synthetic-ssh-secret",
      community: null,
      authPassword: null,
      privacyPassword: null,
    },
  });
  expect(body).not.toHaveProperty("command");
  expect(password.element).toHaveProperty("value", "");
  expect(w.html()).not.toContain("synthetic-ssh-secret");
  expect(localStorage.length).toBe(0);
});
it("requires a SHA-256 host-key pin and never sends a blank or arbitrary fingerprint", async () => {
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  const w = await editor();
  await w.get("#ssh-username").setValue("reader");
  for (const invalid of ["", "accept-any", "SHA256:abc"]) {
    await w.get("#ssh-fingerprint").setValue(invalid);
    (w.get('[name="password"]').element as HTMLInputElement).value =
      "synthetic-ssh-secret";
    await w.get("form").trigger("submit");
    await flushPromises();
    expect(
      fetch.mock.calls.filter(([url]) => !String(url).endsWith("/settings")),
    ).toHaveLength(0);
    expect(w.text()).toContain("主机密钥 SHA-256 指纹");
  }
});
const connection = {
  slot: "ssh",
  protocol: "SSH",
  revision: 4,
  host: "192.0.2.1",
  port: 22,
  enabled: false,
  intervalSeconds: 60,
  timeoutMillis: 3000,
  maxInterfaces: 128,
  username: "reader",
  snmpVersion: "",
  securityLevel: "",
  authProtocol: "",
  privacyProtocol: "",
  contextName: "",
  tlsMode: "",
  certificateSha256: "",
  sshProfile: "CISCO_IOS_XE",
  sshHostKeySha256: pin,
  hasPassword: true,
  hasCommunity: false,
  hasAuthPassword: false,
  hasPrivacyPassword: false,
};
it("retains a saved SSH password only for the same effective identity", async () => {
  const sent: any[] = [];
  vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
    sent.push(JSON.parse(String(init.body)));
    return Response.json(connection);
  });
  const w = await editor(connection);
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(sent).toHaveLength(1);
  expect(sent[0].secrets.password).toBeNull();
  await w.get("#ssh-profile").setValue("DELL_OS9");
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(sent).toHaveLength(1);
  expect(w.text()).toContain("请重新填写SSH 密码");
  await w.get("#ssh-profile").setValue("CISCO_IOS_XE");
  await w.get("#ssh-fingerprint").setValue(`SHA256:${"B".repeat(42)}A`);
  await w.get("form").trigger("submit");
  await flushPromises();
  expect(sent).toHaveLength(1);
});
it("accepts the bounded three-slot safe collection event", async () => {
  const snapshot = {
    items: [{ slot: "snmp" }, { slot: "redfish" }, { slot: "ssh" }],
    asOf: "2026-09-07T00:00:00Z",
  };
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(`event: collection\ndata: ${JSON.stringify(snapshot)}\n\n`, {
        headers: { "Content-Type": "text/event-stream" },
      }),
  );
  const receive = vi.fn();
  await readCollectionStream("d1", new AbortController().signal, receive);
  expect(receive).toHaveBeenCalledWith(snapshot);
});

it("rejects more than three collection slots instead of widening the stream without a bound", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        `event: collection\ndata: ${JSON.stringify({ items: Array.from({ length: 4 }, () => ({ slot: "ssh" })) })}\n\n`,
        { headers: { "Content-Type": "text/event-stream" } },
      ),
  );
  await expect(
    readCollectionStream("d1", new AbortController().signal, vi.fn()),
  ).rejects.toThrow("实时数据格式无效");
});
