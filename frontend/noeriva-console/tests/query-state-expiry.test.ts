import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import QueryState from "../src/components/QueryState.vue";
import {
  ApiError,
  clearAuthorization,
  openEventStream,
  request,
  setAccessToken,
} from "../src/services/api";

afterEach(() => {
  clearAuthorization();
  vi.unstubAllGlobals();
});

describe("authorization errors held by query state", () => {
  it.each(["request", "stream"])(
    "retries a late %s 401 once without clearing a newer login",
    async (kind) => {
      let reply!: (response: Response) => void;
      let header: string | null = null;
      vi.stubGlobal("fetch", (url: string, init: RequestInit) => {
        if (url.endsWith("/old"))
          return new Promise<Response>((resolve) => {
            reply = resolve;
          });
        header = new Headers(init.headers).get("Authorization");
        return Promise.resolve(Response.json({ ok: true }));
      });
      setAccessToken("old-session");
      const pending = (
        kind === "request"
          ? request("/old")
          : openEventStream("/old", new AbortController().signal)
      ).catch((error: unknown) => error);
      setAccessToken("new-session");
      reply(new Response("", { status: 401 }));
      const error = (await pending) as ApiError;
      const wrapper = mount(QueryState, { props: { error } });
      try {
        await flushPromises();
        expect(wrapper.find('[role="alert"]').exists()).toBe(false);
        expect(wrapper.emitted("retry")).toHaveLength(1);
        await wrapper.setProps({ pending: true });
        await wrapper.setProps({ pending: false, error });
        expect(wrapper.emitted("retry")).toHaveLength(1);
        await request("/probe");
        expect(header).toBe("Bearer new-session");
        await wrapper.setProps({ error: null });
        expect(wrapper.find('[role="status"]').exists()).toBe(false);
      } finally {
        wrapper.unmount();
      }
    },
  );

  it.each([403, 503])(
    "retains the usual HTTP %s error and manual retry action",
    async (status) => {
      setAccessToken("active-session");
      let header: string | null = null;
      vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
        header = new Headers(init.headers).get("Authorization");
        return Response.json({ ok: true });
      });
      const wrapper = mount(QueryState, {
        props: {
          error: new ApiError("数据读取失败", status, "retained-error"),
        },
      });
      try {
        expect(wrapper.get('[role="alert"]').text()).toContain(
          "retained-error",
        );
        expect(wrapper.emitted("retry")).toBeUndefined();
        await wrapper.get("button").trigger("click");
        expect(wrapper.emitted("retry")).toHaveLength(1);
        await request("/probe");
        expect(header).toBe("Bearer active-session");
      } finally {
        wrapper.unmount();
      }
    },
  );
});
