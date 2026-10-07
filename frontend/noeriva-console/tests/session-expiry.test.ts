import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import App from "../src/App.vue";
import { useSessionStore } from "../src/stores/session";
import type { Session } from "../src/services/types";
import {
  clearAuthorization,
  openEventStream,
  request,
  setAccessToken,
} from "../src/services/api";

const metadata: Session = {
  username: "viewer",
  organizationId: "default",
  roles: ["VIEWER"],
  mode: "CONNECTED",
  timezone: "UTC",
};
const cleanup: (() => void)[] = [];
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => (resolve = done));
  return { promise, resolve };
}
function unauthorized() {
  return new Response("", { status: 401 });
}
beforeEach(() => {
  for (const method of ["showModal", "close"] as const) {
    const descriptor = Object.getOwnPropertyDescriptor(
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
      if (descriptor)
        Object.defineProperty(HTMLDialogElement.prototype, method, descriptor);
      else Reflect.deleteProperty(HTMLDialogElement.prototype, method);
    });
  }
});
afterEach(() => {
  cleanup
    .splice(0)
    .reverse()
    .forEach((run) => run());
  clearAuthorization();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
});

async function workspace(authenticated = true) {
  const pinia = createPinia();
  const auth = useSessionStore(pinia);
  if (authenticated) {
    auth.session = { ...metadata };
    setAccessToken("expired-session");
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/:pathMatch(.*)*",
        component: { template: "<div>当前页面</div>" },
      },
    ],
  });
  await router.push(
    authenticated ? "/network?deviceId=private-device" : "/overview",
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = mount(App, {
    attachTo: document.body,
    global: {
      plugins: [pinia, router, [VueQueryPlugin, { queryClient: client }]],
    },
  });
  cleanup.push(() => {
    wrapper.unmount();
    client.clear();
    auth.$dispose();
  });
  await flushPromises();
  return { auth, router, client, wrapper };
}

describe("central authorization expiry", () => {
  it.each(["request", "stream"])(
    "clears the rejected authorization for %s",
    async (kind) => {
      let header: string | null = "unseen";
      vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
        header = new Headers(init.headers).get("Authorization");
        return unauthorized();
      });
      setAccessToken("expired-session");
      const expired =
        kind === "request"
          ? request("/devices")
          : openEventStream(
              "/stream/device-status",
              new AbortController().signal,
            );
      await expect(expired).rejects.toMatchObject({ status: 401 });
      await request("/probe").catch(() => {});
      expect(header).toBeNull();
    },
  );

  it.each(["request", "stream"])(
    "ignores a stale %s 401 after the same token was assigned to a new session",
    async (kind) => {
      const old = deferred<Response>();
      let header: string | null = null;
      vi.stubGlobal("fetch", (url: string, init: RequestInit) => {
        if (url.endsWith("/old")) return old.promise;
        header = new Headers(init.headers).get("Authorization");
        return Promise.resolve(Response.json({ ok: true }));
      });
      setAccessToken("reused-token");
      const previous =
        kind === "request"
          ? request("/old")
          : openEventStream("/old", new AbortController().signal);
      setAccessToken("reused-token");
      old.resolve(unauthorized());
      await expect(previous).rejects.toMatchObject({ status: 401 });
      await request("/probe");
      expect(header).toBe("Bearer reused-token");
    },
  );

  it("aborts other authenticated requests and event streams on expiry", async () => {
    const signals: AbortSignal[] = [];
    vi.stubGlobal("fetch", (url: string, init: RequestInit) => {
      if (url.endsWith("/expired")) return Promise.resolve(unauthorized());
      const signal = init.signal!;
      signals.push(signal);
      return new Promise<Response>((_resolve, reject) => {
        signal.addEventListener(
          "abort",
          () => reject(new DOMException("Aborted", "AbortError")),
          { once: true },
        );
      });
    });
    setAccessToken("expired-session");
    const pendingRequest = request("/slow").catch((error) => error);
    const pendingStream = openEventStream(
      "/stream/device-status",
      new AbortController().signal,
    ).catch((error) => error);
    await request("/expired").catch(() => {});
    expect(signals).toHaveLength(2);
    expect(signals.every((signal) => signal?.aborted)).toBe(true);
    await expect(pendingRequest).resolves.toMatchObject({ name: "AbortError" });
    await expect(pendingStream).resolves.toMatchObject({ name: "AbortError" });
  });
});

describe("authorization cancellation without native AbortSignal.any", () => {
  beforeEach(() => {
    const descriptor = Object.getOwnPropertyDescriptor(AbortSignal, "any");
    Object.defineProperty(AbortSignal, "any", {
      configurable: true,
      value: undefined,
    });
    cleanup.push(() => {
      if (descriptor) Object.defineProperty(AbortSignal, "any", descriptor);
      else Reflect.deleteProperty(AbortSignal, "any");
    });
    setAccessToken("fallback-session");
  });

  it.each(["request", "stream"])(
    "invalidates a %s 401 and removes both sources' listeners",
    async (kind) => {
      const caller = new AbortController();
      const removed = vi.spyOn(caller.signal, "removeEventListener");
      const signals: AbortSignal[] = [];
      const headers: (string | null)[] = [];
      vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
        if (init.signal) signals.push(init.signal);
        headers.push(new Headers(init.headers).get("Authorization"));
        return unauthorized();
      });
      const expired =
        kind === "request"
          ? request("/expired", { signal: caller.signal })
          : openEventStream("/expired", caller.signal);
      await expect(expired).rejects.toMatchObject({ status: 401 });
      expect(signals[0]!.aborted).toBe(true);
      expect(removed).toHaveBeenCalledWith("abort", expect.any(Function));
      await request("/probe").catch(() => {});
      expect(headers).toEqual(["Bearer fallback-session", null]);
    },
  );

  it.each([200, 503])(
    "releases completed request listeners after HTTP %s",
    async (status) => {
      const caller = new AbortController();
      let combined!: AbortSignal;
      vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
        combined = init.signal!;
        return Response.json({ ok: true }, { status });
      });
      const pending = request("/complete", { signal: caller.signal });
      if (status === 200) await expect(pending).resolves.toEqual({ ok: true });
      else await expect(pending).rejects.toMatchObject({ status });
      caller.abort();
      expect(combined.aborted).toBe(false);
      clearAuthorization();
      expect(combined.aborted).toBe(false);
    },
  );

  it("keeps cancellation attached until the JSON body finishes", async () => {
    const caller = new AbortController();
    let combined!: AbortSignal;
    let body!: ReadableStreamDefaultController<Uint8Array>;
    vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
      combined = init.signal!;
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          body = controller;
        },
      });
      combined.addEventListener(
        "abort",
        () => body.error(new DOMException("Aborted", "AbortError")),
        { once: true },
      );
      return new Response(stream, {
        headers: { "Content-Type": "application/json" },
      });
    });
    const pending = request("/slow-body", { signal: caller.signal }).catch(
      (error) => error,
    );
    await flushPromises();
    caller.abort();
    expect(combined.aborted).toBe(true);
    await expect(pending).resolves.toMatchObject({ name: "AbortError" });
  });

  it("keeps a returned SSE response cancellable and removes listeners on abort", async () => {
    const caller = new AbortController();
    const removed = vi.spyOn(caller.signal, "removeEventListener");
    let combined!: AbortSignal;
    vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
      combined = init.signal!;
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          combined.addEventListener(
            "abort",
            () => controller.error(new DOMException("Aborted", "AbortError")),
            { once: true },
          );
        },
      });
      return new Response(stream, {
        headers: { "Content-Type": "text/event-stream" },
      });
    });
    const response = await openEventStream("/live", caller.signal);
    expect(removed).not.toHaveBeenCalled();
    const reader = response.body!.getReader();
    const pending = reader.read().catch((error) => error);
    caller.abort();
    expect(combined.aborted).toBe(true);
    await expect(pending).resolves.toMatchObject({ name: "AbortError" });
    expect(removed).toHaveBeenCalledWith("abort", expect.any(Function));
    reader.releaseLock();
  });

  it("cancels the underlying SSE body and releases listeners when its reader is cancelled", async () => {
    const caller = new AbortController();
    let combined!: AbortSignal;
    let cancelled: unknown;
    vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
      combined = init.signal!;
      const stream = new ReadableStream<Uint8Array>({
        cancel(reason) {
          cancelled = reason;
        },
      });
      return new Response(stream, {
        headers: { "Content-Type": "text/event-stream" },
      });
    });
    const response = await openEventStream("/live", caller.signal);
    const reader = response.body!.getReader();
    await reader.cancel("reader closed");
    expect(cancelled).toBe("reader closed");
    caller.abort();
    expect(combined.aborted).toBe(false);
    clearAuthorization();
    expect(combined.aborted).toBe(false);
    reader.releaseLock();
  });

  it("preserves SSE frames and releases listeners after the body ends", async () => {
    const caller = new AbortController();
    let combined!: AbortSignal;
    vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
      combined = init.signal!;
      return new Response("event: collection\ndata: {}\n\n", {
        headers: { "Content-Type": "text/event-stream" },
      });
    });
    const response = await openEventStream("/live", caller.signal);
    await expect(response.text()).resolves.toBe(
      "event: collection\ndata: {}\n\n",
    );
    caller.abort();
    expect(combined.aborted).toBe(false);
    clearAuthorization();
    expect(combined.aborted).toBe(false);
  });

  it("preserves an already-aborted caller signal", async () => {
    const caller = new AbortController();
    caller.abort();
    vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
      if (init.signal?.aborted) throw init.signal.reason;
      return Response.json({ ok: true });
    });
    await expect(
      request("/cancelled", { signal: caller.signal }),
    ).rejects.toMatchObject({ name: "AbortError" });
  });
});

describe("expired workspace", () => {
  it.each(["request", "stream"])(
    "returns %s 401 to the home login and clears queries and open dialogs",
    async (kind) => {
      vi.stubGlobal("fetch", async (url: string) =>
        url.endsWith("/expired")
          ? unauthorized()
          : Response.json({ timezone: "UTC" }),
      );
      const { auth, router, client, wrapper } = await workspace();
      client.setQueryData(["private-result"], { secret: "old workspace data" });
      let querySignal!: AbortSignal;
      const pending = client
        .fetchQuery({
          queryKey: ["private-pending"],
          queryFn: ({ signal }) => {
            querySignal = signal;
            return new Promise(() => {});
          },
        })
        .catch(() => {});
      await wrapper.get(".search-trigger").trigger("click");
      await wrapper.get('[aria-label="打开主导航"]').trigger("click");
      expect(wrapper.findAll("dialog[open]")).toHaveLength(2);
      const expired =
        kind === "request"
          ? request("/expired")
          : openEventStream("/expired", new AbortController().signal);
      await expect(expired).rejects.toMatchObject({ status: 401 });
      expect(auth.session).toBeNull();
      await flushPromises();
      expect(wrapper.find(".login-page").exists()).toBe(true);
      expect(wrapper.find(".app-layout").exists()).toBe(false);
      expect(wrapper.findAll("dialog[open]")).toHaveLength(0);
      expect(router.currentRoute.value.fullPath).toBe("/overview");
      expect(querySignal.aborted).toBe(true);
      expect(client.getQueryData(["private-result"])).toBeUndefined();
      expect(client.getQueryData(["private-pending"])).toBeUndefined();
      await pending;
    },
  );

  it.each([403, 503])(
    "keeps the session, route and cache after HTTP %s",
    async (status) => {
      vi.stubGlobal("fetch", async (url: string) =>
        url.endsWith("/failed")
          ? new Response("", { status })
          : Response.json({ timezone: "UTC" }),
      );
      const { auth, router, client } = await workspace();
      client.setQueryData(["private-result"], "retained");
      await expect(request("/failed")).rejects.toMatchObject({ status });
      await expect(
        openEventStream("/failed", new AbortController().signal),
      ).rejects.toMatchObject({ status });
      expect(auth.session?.username).toBe("viewer");
      expect(router.currentRoute.value.fullPath).toBe(
        "/network?deviceId=private-device",
      );
      expect(client.getQueryData(["private-result"])).toBe("retained");
    },
  );

  it("keeps wrong login credentials as an inline error", async () => {
    vi.stubGlobal("fetch", async () => unauthorized());
    const { wrapper, auth, router } = await workspace(false);
    await wrapper.get("#username").setValue("viewer");
    await wrapper.get("#password").setValue("wrong-password");
    await wrapper.get("form.login-form").trigger("submit");
    await flushPromises();
    expect(auth.session).toBeNull();
    expect(wrapper.get('[role="alert"]').text()).toContain("请重新登录");
    expect(
      wrapper.get<HTMLButtonElement>(".login-form button").element.disabled,
    ).toBe(false);
    expect(router.currentRoute.value.fullPath).toBe("/overview");
  });

  it("ignores concurrent old-session 401 responses after a new login", async () => {
    const replies = [deferred<Response>(), deferred<Response>()];
    vi.stubGlobal("fetch", (url: string) => {
      if (url.includes("/old/"))
        return replies[Number(url.split("/").at(-1))]!.promise;
      if (url.endsWith("/session"))
        return Promise.resolve(
          Response.json({
            ...metadata,
            username: "new-user",
            accessToken: "new-session",
          }),
        );
      return Promise.resolve(Response.json({ timezone: "UTC" }));
    });
    const { auth, router, client } = await workspace();
    const old = replies.map((_, i) => request(`/old/${i}`).catch(() => {}));
    replies[0]!.resolve(unauthorized());
    await old[0];
    await flushPromises();
    expect(auth.session).toBeNull();
    await auth.login("new-user", "new-password");
    client.setQueryData(["new-private"], "new data");
    await router.push("/assets");
    replies[1]!.resolve(unauthorized());
    await old[1];
    expect(auth.session?.username).toBe("new-user");
    expect(client.getQueryData(["new-private"])).toBe("new data");
    expect(router.currentRoute.value.path).toBe("/assets");
  });

  it("does not retain the app's expiry listener after unmount", async () => {
    vi.stubGlobal("fetch", async (url: string) =>
      url.endsWith("/expired")
        ? unauthorized()
        : Response.json({ timezone: "UTC" }),
    );
    const { wrapper, auth, router } = await workspace();
    wrapper.unmount();
    const pathAfterUnmount = router.currentRoute.value.fullPath;
    await request("/expired").catch(() => {});
    expect(auth.session?.username).toBe("viewer");
    expect(router.currentRoute.value.fullPath).toBe(pathAfterUnmount);
  });

  it("does not let an old failed login clear a newer successful session", async () => {
    const first = deferred<Response>();
    let attempts = 0;
    vi.stubGlobal("fetch", () =>
      ++attempts === 1
        ? first.promise
        : Promise.resolve(
            Response.json({
              ...metadata,
              username: "new-user",
              accessToken: "new-session",
            }),
          ),
    );
    const auth = useSessionStore(createPinia());
    cleanup.push(() => auth.$dispose());
    const oldLogin = auth.login("old-user", "wrong-password");
    await auth.login("new-user", "correct-password");
    first.resolve(unauthorized());
    await oldLogin;
    expect(auth.session?.username).toBe("new-user");
    expect(auth.error).toBe("");
  });

  it("does not let an old successful login replace a newer successful session", async () => {
    const first = deferred<Response>();
    let attempts = 0;
    vi.stubGlobal("fetch", () =>
      ++attempts === 1
        ? first.promise
        : Promise.resolve(
            Response.json({
              ...metadata,
              username: "new-user",
              accessToken: "new-session",
            }),
          ),
    );
    const auth = useSessionStore(createPinia());
    cleanup.push(() => auth.$dispose());
    const oldLogin = auth.login("old-user", "old-password");
    await auth.login("new-user", "new-password");
    first.resolve(
      Response.json({
        ...metadata,
        username: "old-user",
        accessToken: "old-session",
      }),
    );
    await oldLogin;
    expect(auth.session?.username).toBe("new-user");
    expect(auth.pending).toBe(false);
  });

  it("does not restore a late login response after logout", async () => {
    const reply = deferred<Response>();
    vi.stubGlobal("fetch", () => reply.promise);
    const auth = useSessionStore(createPinia());
    cleanup.push(() => auth.$dispose());
    const login = auth.login("viewer", "password");
    auth.logout();
    reply.resolve(Response.json({ ...metadata, accessToken: "old-session" }));
    await login;
    expect(auth.session).toBeNull();
    expect(auth.pending).toBe(false);
    expect(auth.error).toBe("");
  });
});
