import { computed, ref, shallowRef } from "vue";
import { ApiError, queryString } from "./api";
import { useApiQuery } from "./queries";
import { fromUtcInput } from "./workbench";
import type { Page } from "./types";

export interface NatSource {
  deviceId: string;
  deviceName: string;
  siteId: string;
  sourceAddress: string;
  revision: number;
  enabled: boolean;
  status: "DISABLED" | "WAITING" | "RECEIVING" | "DEGRADED" | "STALE";
  lastPacketAt: string | null;
  lastEventAt: string | null;
  lastPersistedAt: string | null;
  lastError: string | null;
  templates: number;
  received: number;
  accepted: number;
  persisted: number;
  dropped: number;
  sequenceGaps: number;
  unknownTemplates: number;
  parseErrors: number;
  duplicatePackets: number;
  restartCount: number;
  updatedAt: string;
  qualityFlags: string[];
}
export interface NatEvent {
  id: string;
  deviceId: string;
  siteId: string;
  sourceAddress: string;
  sourceDomain: number;
  exporterEpoch: string;
  packetSequence: number;
  templateId: number;
  templateSha256: string;
  packetSha256: string;
  recordIndex: number;
  eventType: "CREATE" | "DELETE" | "POOL_EXHAUSTED";
  protocol: number | null;
  vrfId: number | null;
  privateIp: string | null;
  privatePort: number | null;
  publicIp: string | null;
  publicPort: number | null;
  destinationIp: string | null;
  destinationPort: number | null;
  translatedDestinationIp: string | null;
  translatedDestinationPort: number | null;
  poolId: number | null;
  deviceEventAt: string | null;
  exportedAt: string;
  receivedAt: string;
  qualityFlags: string[];
  provenance: "CISCO_NAT_HSL_V9";
}
export interface ApplicationSource {
  deviceId: string;
  deviceName: string;
  siteId: string;
  revision: number;
  enabled: boolean;
  intervalSeconds: number;
  interfaceIndices: number[];
  maxRows: number;
  status: string;
  lastAttemptAt: string | null;
  lastSuccessAt: string | null;
  nextPollAt: string | null;
  errorCode: string;
  errorMessage: string;
  credentialRevision: number;
  protocol: string;
  lastRowCount: number;
  qualityFlags: string[];
}
export interface ApplicationObservation {
  id: string;
  deviceId: string;
  interfaceIndex: number;
  interfaceName: string;
  protocolIndex: number;
  application: string;
  direction: "IN" | "OUT";
  observedAt: string;
  bytes: string | null;
  packets: string | null;
  reportedBps: number | null;
  derivedBps: number | null;
  derivedPacketsPerSecond: number | null;
  intervalSeconds: number | null;
  sourceEpoch: string;
  qualityFlags: string[];
}
export const trafficStatus: Record<string, string> = {
  NOT_CONFIGURED: "尚未配置",
  QUEUED: "等待采集",
  DISABLED: "已停用",
  RUNNING: "正在采集",
  SUCCESS: "最近采集成功",
  PARTIAL: "部分采集成功",
  ERROR: "采集失败",
  WAITING: "等待首个报文",
  RECEIVING: "正在接收",
  DEGRADED: "接收存在缺口",
  STALE: "超过 120 秒未收到报文",
};
export const qualityNames: Record<string, string> = {
  NBAR_BASELINE_REQUIRED: "等待相邻样本建立差分基线",
  NBAR_COUNTER_RESET: "计数下降或基线重置",
  NBAR_GAP: "样本间隔过长",
  NBAR_COUNTER_UNAVAILABLE: "高容量计数缺失",
  NBAR_DISABLED: "设备接口未启用协议发现",
  NBAR_ENABLE_TIME_UNAVAILABLE: "协议发现启用时间未知",
  NBAR_INTERFACE_IDENTITY_UNAVAILABLE: "接口身份不完整",
  NBAR_ROW_LIMIT: "已达协议行上限",
  NBAR_UNSUPPORTED: "设备未提供所需 NBAR 对象",
  SNMP_TIMEOUT: "SNMP 读取超时",
  SNMP_VARIABLE_BUDGET: "达到变量预算",
  SNMP_NON_INCREASING_OID: "设备返回非递增对象",
  UDP_UNAUTHENTICATED: "UDP 来源未经密码学认证",
  COMPLETENESS_NOT_GUARANTEED: "不保证完整接收",
};
export const qualityText = (flags: string[]) =>
  flags
    .map((f) => (qualityNames[f] ? `${qualityNames[f]} (${f})` : f))
    .join(" · ");
export const endpoint = (ip: string | null, port: number | null) =>
  ip === null && port === null ? "—" : `${ip ?? "—"}:${port ?? "—"}`;
export const initialWindow = (minutes = 15) => {
  const end = Date.now();
  return {
    from: new Date(end - minutes * 60_000).toISOString().slice(0, 19),
    to: new Date(end).toISOString().slice(0, 19),
  };
};
export function validateWindow(from: string, to: string, maxHours: number) {
  const start = fromUtcInput(from),
    end = fromUtcInput(to),
    duration = Date.parse(end) - Date.parse(start);
  if (duration <= 0 || duration > maxHours * 3_600_000)
    throw Error(`结束时间须晚于开始时间，单次查询最长 ${maxHours} 小时。`);
  return { from: start, to: end };
}
export function actionError(error: unknown) {
  if (error instanceof ApiError && error.status === 409)
    return "版本已变化或采集正在执行，已刷新来源；请重新载入配置后重试。";
  return error instanceof Error ? error.message : "操作未完成，请重试。";
}
type Filters = Record<string, string | number | undefined>;
export function useTrafficHistory<T>(path: string) {
  const filters = shallowRef<Filters | null>(null),
    cursor = ref<string | null>(null),
    previous = ref<(string | null)[]>([]);
  const query = useApiQuery<Page<T>>(
    computed(
      () =>
        `${path}${queryString({ ...filters.value, limit: 30, cursor: cursor.value })}`,
    ),
    () => filters.value !== null,
    { refetchInterval: false },
  );
  function submit(value: Filters) {
    const same =
      cursor.value === null &&
      JSON.stringify(filters.value) === JSON.stringify(value);
    cursor.value = null;
    previous.value = [];
    filters.value = { ...value };
    if (same) void query.refetch();
  }
  function clear() {
    filters.value = null;
    cursor.value = null;
    previous.value = [];
  }
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
    filters,
    submit,
    clear,
    next,
    back,
    page: computed(() => previous.value.length + 1),
    hasPrevious: computed(() => previous.value.length > 0),
  };
}

export interface ApplicationSummary {
  deviceId: string;
  asOf: string;
  observedAt: string | null;
  source: string;
  mode: string;
  freshness: string;
  rateBasis: string;
  interfaceIndices: number[];
  sampleRows: number;
  totalApplications: number;
  truncated: boolean;
  qualityFlags: string[];
  items: {
    application: string;
    direction: "IN" | "OUT";
    interfaceIndices: number[];
    derivedBps: number | null;
    reportedBps: number | null;
    observationCount: number;
    qualityFlags: string[];
  }[];
}
