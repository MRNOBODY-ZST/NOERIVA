import { computed, ref, watch, type MaybeRefOrGetter, toValue } from "vue";
import { useQueryClient } from "@tanstack/vue-query";
import { request, queryString, ApiError } from "./api";
import { useApiQuery } from "./queries";
import type { Page } from "./types";
import "../styles/workbench.css";
export interface Incident {
  id: string;
  title: string;
  severity: string;
  status: string;
  deviceId: string;
  alertId: string | null;
  assignee: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  revision: number;
  notes: { id: string; author: string; text: string; createdAt: string }[];
}
export type IncidentSummary = Omit<Incident, "notes"> & { noteCount: number };
export interface Evidence {
  id: string;
  deviceId: string;
  title: string;
  kind: string;
  source: string;
  observedAt: string;
  content?: string;
  provenance: string;
  sha256: string;
  integrity: string;
  createdBy: string;
  createdAt: string;
}
export interface Snapshot {
  id: string;
  deviceId: string;
  title: string;
  source: string;
  capturedAt: string;
  content?: string;
  provenance: string;
  sha256: string;
  redactedLines: number;
  createdBy: string;
  createdAt: string;
}
export interface ConfigurationDiff {
  deviceId: string;
  beforeId: string;
  afterId: string;
  added: number;
  removed: number;
  unchanged: number;
  lines: {
    kind: "CONTEXT" | "ADDED" | "REMOVED";
    beforeLine: number | null;
    afterLine: number | null;
    text: string;
  }[];
  asOf: string;
}
export interface CheckResult {
  id: string;
  checkId: string;
  definitionRevision: number;
  observedAt: string;
  status: string;
  latencyMs: number | null;
  message: string;
  source: string;
  provenance: string;
  receivedAt: string;
}
export interface CheckDefinition {
  id: string;
  deviceId: string;
  name: string;
  type: string;
  target: string;
  intervalSeconds: number;
  enabled: boolean;
  archived: boolean;
  provenance: string;
  execution: string;
  revision: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  lastResult: CheckResult | null;
}
export interface NetworkEvidence {
  id: string;
  deviceId: string;
  siteId: string;
  kind: string;
  privateIp: string;
  privatePort: number | null;
  publicIp: string | null;
  publicPort: number | null;
  protocol: string | null;
  validFrom: string;
  validTo: string;
  lifecycle: string;
  clockUncertaintyMs: number;
  source: string;
  provenance: string;
  receivedAt: string;
}
export interface InvestigationQuery {
  ip: string;
  port: number;
  protocol: string;
  at: string;
  direction: string;
}
export interface Investigation {
  id: string;
  query: InvestigationQuery;
  status: string;
  candidates: {
    nat: NetworkEvidence;
    leases: NetworkEvidence[];
    qualityFlags: string[];
  }[];
  qualityFlags: string[];
  asOf: string;
  mode: string;
}
export interface Audit {
  id: string;
  actor: string;
  action: string;
  resourceId: string;
  createdAt: string;
}
export interface MonitoringSource {
  deviceId: string;
  deviceName: string;
  deviceType: string;
  siteId: string;
  siteName: string;
  sourceId: string;
  kind: string;
  health: string;
  observedAt: string;
  freshness: string;
  metrics: Record<string, number | null>;
  sequence: number;
  epoch: string;
}
export interface WorkspaceInterface {
  id: string;
  deviceId: string;
  deviceName: string;
  siteId: string;
  siteName: string;
  name: string;
  macAddress: string | null;
  speedBps: string;
  adminStatus: string;
  operStatus: string;
  deviceLastSeen: string | null;
  deviceFreshness: string;
}
export function usePagedQuery<T>(
  path: MaybeRefOrGetter<string>,
  filters: MaybeRefOrGetter<
    Record<string, string | number | undefined | null>
  > = {},
) {
  const cursor = ref<string | null>(null),
    previous = ref<(string | null)[]>([]);
  watch(
    () => JSON.stringify([toValue(path), toValue(filters)]),
    () => {
      cursor.value = null;
      previous.value = [];
    },
  );
  const query = useApiQuery<Page<T>>(
    computed(
      () =>
        `${toValue(path)}${queryString({ ...toValue(filters), limit: 30, cursor: cursor.value })}`,
    ),
  );
  function next() {
    if (!query.isFetching.value && query.data.value?.nextCursor) {
      previous.value.push(cursor.value);
      cursor.value = query.data.value.nextCursor;
    }
  }
  function back() {
    if (!query.isFetching.value && previous.value.length)
      cursor.value = previous.value.pop() ?? null;
  }
  return {
    ...query,
    next,
    back,
    hasPrevious: computed(() => previous.value.length > 0),
    page: computed(() => previous.value.length + 1),
  };
}
export function useWorkbenchAction() {
  const pending = ref(false),
    error = ref(""),
    notice = ref("");
  const client = useQueryClient();
  async function run<T>(path: string, body: unknown): Promise<T | undefined> {
    pending.value = true;
    error.value = "";
    notice.value = "";
    try {
      const value = await request<T>(path, {
        method: "POST",
        body: JSON.stringify(body),
      });
      await client.invalidateQueries({ queryKey: ["noeriva"] });
      return value;
    } catch (cause) {
      error.value =
        cause instanceof ApiError && cause.status === 409
          ? "记录已经变化，请重新读取并检查当前版本后重试。"
          : cause instanceof Error
            ? cause.message
            : "操作未完成，请重试。";
      return undefined;
    } finally {
      pending.value = false;
    }
  }
  return { pending, error, notice, run };
}
export function downloadJson(filename: string, value: unknown) {
  downloadText(
    filename,
    JSON.stringify(value, null, 2),
    "application/json;charset=utf-8",
  );
}
export function downloadText(
  filename: string,
  value: string,
  type = "text/plain;charset=utf-8",
) {
  const url = URL.createObjectURL(new Blob([value], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function utcInputNow() {
  return new Date().toISOString().slice(0, 19);
}
export function fromUtcInput(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(value))
    throw new Error("请输入完整的 UTC 日期和时间。");
  const parsed = new Date(`${value}Z`);
  if (
    !Number.isFinite(parsed.getTime()) ||
    parsed.toISOString().slice(0, 19) !==
      (value.length === 16 ? `${value}:00` : value)
  )
    throw new Error("时间无效");
  return parsed.toISOString();
}
export const metricNames: Record<string, { name: string; unit: string }> = {
  cpu_percent: { name: "CPU 使用率", unit: "%" },
  memory_percent: { name: "内存使用率", unit: "%" },
  temperature_celsius: { name: "温度", unit: "°C" },
  power_watts: { name: "整机功率", unit: "W" },
  bandwidth_rx_bps: { name: "入站带宽", unit: "bit/s" },
  bandwidth_tx_bps: { name: "出站带宽", unit: "bit/s" },
};
export const outcomeNames: Record<string, string> = {
  CONFIRMED: "已确认端点映射",
  AMBIGUOUS: "存在歧义",
  INSUFFICIENT_EVIDENCE: "证据不足",
  NO_MATCH: "没有匹配记录",
};
