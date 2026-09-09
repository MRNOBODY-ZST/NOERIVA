import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import IncidentsPage from "../src/pages/IncidentsPage.vue";
import { useSessionStore } from "../src/stores/session";
import type { Incident } from "../src/services/workbench";

const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((run) => run());
  vi.unstubAllGlobals();
  localStorage.clear();
});

async function render() {
  for (const method of ["showModal", "close"] as const) {
    const original = Object.getOwnPropertyDescriptor(
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
      if (original)
        Object.defineProperty(HTMLDialogElement.prototype, method, original);
      else
        delete (
          HTMLDialogElement.prototype as unknown as Record<string, unknown>
        )[method];
    });
  }
  const existing: Incident = {
    id: "existing",
    title: "Existing incident",
    severity: "WARNING",
    status: "OPEN",
    deviceId: "device-one",
    alertId: "alert-one",
    assignee: null,
    createdBy: "admin",
    createdAt: "2026-09-06T00:00:00Z",
    updatedAt: "2026-09-06T00:00:00Z",
    revision: 1,
    notes: [],
  };
  const records = new Map([[existing.id, existing]]);
  vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
    const path = new URL(url, "http://localhost").pathname;
    if (path === "/api/v1/devices")
      return Response.json({ items: [], nextCursor: null });
    if (path === "/api/v1/workbench/incidents") {
      if (init?.method === "POST") {
        const value: Incident = {
          ...existing,
          ...JSON.parse(String(init.body)),
          id: "created",
        };
        records.set(value.id, value);
        return Response.json(value, { status: 201 });
      }
      return Response.json({
        items: [...records.values()].map(({ notes, ...record }) => ({
          ...record,
          noteCount: notes.length,
        })),
        nextCursor: null,
      });
    }
    if (path.startsWith("/api/v1/workbench/incidents/"))
      return Response.json(records.get(path.split("/").at(-1)!));
    throw new Error(`Unexpected request: ${path}`);
  });
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
    routes: [
      { path: "/incidents/:id?", component: IncidentsPage },
      { path: "/:pathMatch(.*)*", component: { template: "<div />" } },
    ],
  });
  await router.push(
    "/incidents?deviceId=device-one&alertId=alert-one&create=1",
  );
  const queryClient = new QueryClient();
  const wrapper = mount(IncidentsPage, {
    attachTo: document.body,
    global: { plugins: [pinia, router, [VueQueryPlugin, { queryClient }]] },
  });
  cleanup.unshift(() => {
    wrapper.unmount();
    queryClient.clear();
  });
  await flushPromises();
  expect((wrapper.get("dialog").element as HTMLDialogElement).open).toBe(true);
  expect(
    (wrapper.get("#incident-alert").element as HTMLInputElement).value,
  ).toBe("alert-one");
  return { wrapper, router };
}

describe("incident create navigation is consumed once", () => {
  it("closes after saving and keeps subsequent detail navigation outside the creation form", async () => {
    const { wrapper, router } = await render();
    await wrapper.get("#incident-title").setValue("Saved from alert");
    await wrapper.get("dialog form").trigger("submit");
    await flushPromises();
    await vi.waitFor(() =>
      expect(wrapper.find("#update-title").exists()).toBe(true),
    );
    expect(
      (wrapper.get("#update-title").element as HTMLInputElement).value,
    ).toBe("Saved from alert");
    expect((wrapper.get("dialog").element as HTMLDialogElement).open).toBe(
      false,
    );
    expect(router.currentRoute.value.query).toEqual({
      deviceId: "device-one",
      alertId: "alert-one",
      id: "created",
    });
    await wrapper
      .findAll(".wb-source-button")
      .find((button) => button.text() === "Existing incident")!
      .trigger("click");
    await flushPromises();
    expect((wrapper.get("dialog").element as HTMLDialogElement).open).toBe(
      false,
    );
  });

  it.each(["cancel button", "dialog cancel"])(
    "does not reopen after %s and a later detail navigation",
    async (method) => {
      const { wrapper, router } = await render();
      if (method === "cancel button")
        await wrapper
          .findAll("dialog button")
          .find((button) => button.text() === "取消")!
          .trigger("click");
      else await wrapper.get("dialog").trigger("cancel");
      await flushPromises();
      expect((wrapper.get("dialog").element as HTMLDialogElement).open).toBe(
        false,
      );
      await wrapper
        .findAll(".wb-source-button")
        .find((button) => button.text() === "Existing incident")!
        .trigger("click");
      await flushPromises();
      expect((wrapper.get("dialog").element as HTMLDialogElement).open).toBe(
        false,
      );
      expect(router.currentRoute.value.query.create).toBeUndefined();
    },
  );
});
