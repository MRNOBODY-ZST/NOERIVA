const base = import.meta.env.VITE_API_BASE || "/api/v1";
let authorization: string | null = null;
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public requestId?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}
export function setAuthorization(username: string, password: string) {
  authorization = `Basic ${btoa(String.fromCharCode(...new TextEncoder().encode(`${username}:${password}`)))}`;
}
export function setAccessToken(token: string) {
  authorization = `Bearer ${token}`;
}
export function clearAuthorization() {
  authorization = null;
}
export async function openEventStream(path: string, signal: AbortSignal) {
  const headers = new Headers({
    Accept: "text/event-stream",
    "X-Noeriva-Request": "1",
  });
  if (authorization) headers.set("Authorization", authorization);
  const response = await fetch(`${base}${path}`, {
    headers,
    signal,
    credentials: "omit",
    cache: "no-store",
  });
  if (!response.ok) throw new ApiError("实时连接暂时不可用。", response.status);
  if (
    !response.headers.get("Content-Type")?.includes("text/event-stream") ||
    !response.body
  )
    throw new ApiError("实时响应格式无效。", 502);
  return response;
}
export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (authorization) headers.set("Authorization", authorization);
  if (options.body) {
    headers.set("Content-Type", "application/json");
    headers.set("X-Noeriva-Request", "1");
  }
  let response: Response;
  try {
    response = await fetch(`${base}${path}`, {
      ...options,
      headers,
      credentials: "omit",
    });
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") throw error;
    throw new ApiError("无法连接 API，请检查服务状态后重试。", 0);
  }
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as {
      message?: string;
      detail?: string;
      requestId?: string;
    };
    const fallback =
      response.status === 401
        ? "登录已失效或凭据不正确，请重新登录。"
        : response.status === 403
          ? "当前账号没有执行此操作的权限。"
          : "请求失败，请重试。";
    throw new ApiError(
      body.message || body.detail || fallback,
      response.status,
      body.requestId || response.headers.get("X-Request-Id") || undefined,
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
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
