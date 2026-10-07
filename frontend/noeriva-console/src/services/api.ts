const base = import.meta.env.VITE_API_BASE || "/api/v1";
let authorization: string | null = null;
let authorizationVersion = 0;
let authorizationAbort = new AbortController();
const expiryListeners = new Set<() => void>();

function updateAuthorization(value: string | null) {
  authorizationAbort.abort();
  authorizationAbort = new AbortController();
  authorization = value;
  authorizationVersion++;
}

export function onAuthorizationExpired(listener: () => void) {
  expiryListeners.add(listener);
  return () => expiryListeners.delete(listener);
}

function expireAuthorization(version: number, authenticated: boolean) {
  // A late response from a previous login must not erase a newer session,
  // even when the server issued the same token for both logins.
  if (!authenticated || version !== authorizationVersion) return false;
  clearAuthorization();
  for (const listener of expiryListeners) listener();
  return true;
}

function requestSignal(signal?: AbortSignal | null) {
  const noCleanup = () => {};
  if (!authorization) return { signal, cleanup: noCleanup, manual: false };
  if (!signal)
    return {
      signal: authorizationAbort.signal,
      cleanup: noCleanup,
      manual: false,
    };
  if (typeof AbortSignal.any === "function")
    return {
      signal: AbortSignal.any([signal, authorizationAbort.signal]),
      cleanup: noCleanup,
      manual: false,
    };

  const combined = new AbortController();
  const listeners: [AbortSignal, () => void][] = [];
  const cleanup = () => {
    for (const [source, listener] of listeners)
      source.removeEventListener("abort", listener);
    listeners.length = 0;
  };
  for (const source of [signal, authorizationAbort.signal]) {
    if (source.aborted) {
      combined.abort(source.reason);
      cleanup();
      break;
    }
    const abort = () => {
      combined.abort(source.reason);
      cleanup();
    };
    source.addEventListener("abort", abort, { once: true });
    listeners.push([source, abort]);
  }
  return { signal: combined.signal, cleanup, manual: true };
}

function streamWithCleanup(response: Response, cleanup: () => void) {
  const reader = response.body!.getReader();
  let finished = false;
  let cancelled = false;
  const finish = () => {
    if (finished) return;
    finished = true;
    cleanup();
    reader.releaseLock();
  };
  const body = new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const chunk = await reader.read();
        if (cancelled || finished) return;
        if (chunk.done) {
          finish();
          controller.close();
        } else controller.enqueue(chunk.value);
      } catch (error) {
        if (cancelled || finished) return;
        finish();
        controller.error(error);
      }
    },
    async cancel(reason) {
      cancelled = true;
      try {
        await reader.cancel(reason);
      } finally {
        finish();
      }
    },
  });
  return new Response(body, {
    status: response.status,
    statusText: response.statusText,
    headers: response.headers,
  });
}
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public requestId?: string,
    public readonly generation = authorizationVersion,
    public readonly authenticated = !!authorization,
  ) {
    super(message);
    this.name = "ApiError";
  }
}
export function recoverAuthorizationError(error: unknown) {
  return (
    error instanceof ApiError &&
    error.status === 401 &&
    expireAuthorization(error.generation, error.authenticated)
  );
}
function rejectUnauthorized(
  response: Response,
  version: number,
  authenticated: boolean,
): never {
  const error = new ApiError(
    "登录已失效或凭据不正确，请重新登录。",
    401,
    response.headers.get("X-Request-Id") || undefined,
    version,
    authenticated,
  );
  // Authentication is decided by the headers; a stalled error body must not
  // keep the rejected session on screen or attach it to a later login.
  recoverAuthorizationError(error);
  void response.body?.cancel().catch(() => {});
  throw error;
}
export function setAuthorization(username: string, password: string) {
  updateAuthorization(
    `Basic ${btoa(String.fromCharCode(...new TextEncoder().encode(`${username}:${password}`)))}`,
  );
}
export function setAccessToken(token: string) {
  updateAuthorization(`Bearer ${token}`);
}
export function clearAuthorization() {
  updateAuthorization(null);
}
export async function openEventStream(path: string, signal: AbortSignal) {
  const version = authorizationVersion;
  const authenticated = !!authorization;
  const headers = new Headers({
    Accept: "text/event-stream",
    "X-Noeriva-Request": "1",
  });
  if (authorization) headers.set("Authorization", authorization);
  const scoped = requestSignal(signal);
  try {
    const response = await fetch(`${base}${path}`, {
      headers,
      signal: scoped.signal,
      credentials: "omit",
      cache: "no-store",
    });
    if (response.status === 401)
      rejectUnauthorized(response, version, authenticated);
    if (!response.ok) {
      throw new ApiError(
        "实时连接暂时不可用。",
        response.status,
        response.headers.get("X-Request-Id") || undefined,
        version,
        authenticated,
      );
    }
    if (
      !response.headers.get("Content-Type")?.includes("text/event-stream") ||
      !response.body
    )
      throw new ApiError("实时响应格式无效。", 502);
    return scoped.manual
      ? streamWithCleanup(response, scoped.cleanup)
      : response;
  } catch (error) {
    scoped.cleanup();
    throw error;
  }
}
export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const version = authorizationVersion;
  const authenticated = !!authorization;
  const headers = new Headers(options.headers);
  const scoped = requestSignal(options.signal);
  headers.set("Accept", "application/json");
  if (authorization) headers.set("Authorization", authorization);
  if (options.body) {
    headers.set("Content-Type", "application/json");
    headers.set("X-Noeriva-Request", "1");
  }
  try {
    let response: Response;
    try {
      response = await fetch(`${base}${path}`, {
        ...options,
        headers,
        signal: scoped.signal,
        credentials: "omit",
      });
    } catch (error) {
      if (
        scoped.signal?.aborted ||
        (error instanceof Error && error.name === "AbortError")
      )
        throw error;
      throw new ApiError("无法连接 API，请检查服务状态后重试。", 0);
    }
    if (response.status === 401)
      rejectUnauthorized(response, version, authenticated);
    if (!response.ok) {
      const body = (await response.json().catch(() => ({}))) as {
        message?: string;
        detail?: string;
        requestId?: string;
      };
      const fallback =
        response.status === 403
          ? "当前账号没有执行此操作的权限。"
          : "请求失败，请重试。";
      throw new ApiError(
        body.message || body.detail || fallback,
        response.status,
        body.requestId || response.headers.get("X-Request-Id") || undefined,
        version,
        authenticated,
      );
    }
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  } finally {
    scoped.cleanup();
  }
}
export function queryString(
  values: Record<string, string | number | undefined | null>,
): string {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "")
      params.set(key, String(value));
  });
  const query = params.toString();
  return query ? `?${query}` : "";
}
