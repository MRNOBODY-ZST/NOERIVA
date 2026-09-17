import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  clearAuthorization,
  request,
  setAuthorization,
  setAccessToken,
} from "../src/services/api";

afterEach(() => {
  clearAuthorization();
  vi.unstubAllGlobals();
});
describe("API authorization and failures", () => {
  it("sends in-memory authorization without browser persistence and clears it on logout", async () => {
    const seen: Headers[] = [];
    vi.stubGlobal("fetch", async (_url: string, options: RequestInit) => {
      seen.push(new Headers(options.headers));
      return new Response('{"ok":true}', { status: 200 });
    });
    setAuthorization("operator", "secret");
    await expect(request("/overview")).resolves.toEqual({ ok: true });
    expect(seen[0]?.get("Authorization")).toBe("Basic b3BlcmF0b3I6c2VjcmV0");
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    clearAuthorization();
    await request("/overview");
    expect(seen[1]?.get("Authorization")).toBeNull();
  });
  it("preserves an API failure and request ID instead of returning fabricated data", async () => {
    vi.stubGlobal(
      "fetch",
      async () =>
        new Response('{"detail":"Source unavailable","requestId":"req-7"}', {
          status: 503,
        }),
    );
    await expect(request("/devices")).rejects.toMatchObject({
      status: 503,
      requestId: "req-7",
      message: "Source unavailable",
    });
  });
  it("reports network failures as recoverable API errors", async () => {
    vi.stubGlobal("fetch", async () => {
      throw new TypeError("fetch failed");
    });
    await expect(request("/devices")).rejects.toBeInstanceOf(ApiError);
  });
});

describe("Bearer session and mutation contract", () => {
  it("replaces Basic credentials with a memory-only session token and attaches mutation protection", async () => {
    let seen: RequestInit | undefined;
    vi.stubGlobal("fetch", async (_url: string, options: RequestInit) => {
      seen = options;
      return new Response('{"id":"created"}', { status: 201 });
    });
    setAuthorization("admin", "secret");
    setAccessToken("opaque-test-token");
    await request("/devices", {
      method: "POST",
      body: JSON.stringify({ name: "host" }),
    });
    const headers = new Headers(seen?.headers);
    expect(headers.get("Authorization")).toBe("Bearer opaque-test-token");
    expect(headers.get("X-Noeriva-Request")).toBe("1");
    expect(headers.get("Content-Type")).toBe("application/json");
    expect(JSON.stringify({ ...localStorage })).not.toContain(
      "opaque-test-token",
    );
  });
});
