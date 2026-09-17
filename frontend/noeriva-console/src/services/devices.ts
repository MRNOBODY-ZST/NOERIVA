import { computed, ref, watch, type MaybeRefOrGetter, toValue } from "vue";
import { openEventStream } from "./api";
import { useApiQuery } from "./queries";
import type { Device } from "./types";
import "../styles/workbench.css";
export type Slot = "snmp" | "redfish" | "ssh";
export type SshProfile = "HUAWEI_IMANA" | "DELL_OS9" | "CISCO_IOS_XE";
export interface Management {
  device: Device;
  inventoryRevision: number;
}
export interface Reading {
  observedAt: string;
  identity: {
    vendor: string | null;
    family: string | null;
    profileId: string | null;
    model: string | null;
    serialNumber: string | null;
    firmware: string | null;
    sysObjectId: string | null;
    sysName: string | null;
    description: string | null;
  };
  health: string;
  metrics: Record<string, number | null>;
  capabilities: string[];
  qualityFlags: string[];
  facts: Record<string, string>;
  sensors: {
    id: string;
    label: string;
    metric: string;
    unit: string;
    value: number | null;
    health: string;
    sourceRef: string;
  }[];
  ports: {
    key: string;
    name: string;
    macAddress: string | null;
    speedBps: string | null;
    adminStatus: string;
    operStatus: string;
    inOctets: string | null;
    outOctets: string | null;
    discontinuity: string | null;
    counterBits: number;
    sourceRef: string;
  }[];
}
export interface CollectionView {
  slot: Slot;
  protocol: string;
  revision: number;
  enabled: boolean;
  status: string;
  lastAttemptAt: string | null;
  lastSuccessAt: string | null;
  nextPollAt: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  lastReading: Reading | null;
}
export interface ConnectionView extends CollectionView {
  host: string;
  port: number;
  intervalSeconds: number;
  timeoutMillis: number;
  maxInterfaces: number;
  username: string;
  snmpVersion: string;
  securityLevel: string;
  authProtocol: string;
  privacyProtocol: string;
  contextName: string;
  tlsMode: string;
  certificateSha256: string;
  hasCommunity: boolean;
  hasAuthPassword: boolean;
  hasPrivacyPassword: boolean;
  hasPassword: boolean;
  sshProfile: SshProfile | null;
  sshHostKeySha256: string | null;
}
export interface CollectionSnapshot {
  items: CollectionView[];
  asOf: string;
}
export interface DeviceSupport {
  items: {
    id: string;
    vendor: string;
    family: string;
    protocols: string[];
    implemented: boolean;
    verification: string;
    notes: string;
  }[];
  protocols: string[];
  credentialStorageReady: boolean;
  collectorEnabled: boolean;
}
export const collectionLabels: Record<string, string> = {
  NOT_TESTED: "尚未测试",
  QUEUED: "等待执行",
  RUNNING: "正在读取",
  SUCCESS: "最近读取成功",
  PARTIAL: "部分读取成功",
  ERROR: "读取失败",
  DISABLED: "已停用",
};
export async function readCollectionStream(
  id: string,
  signal: AbortSignal,
  onData: (value: CollectionSnapshot) => void,
) {
  const response = await openEventStream(
    `/devices/${encodeURIComponent(id)}/live`,
    signal,
  );
  const reader = response.body!.getReader(),
    decoder = new TextDecoder();
  let buffer = "";
  const abort = () => {
    void reader.cancel().catch(() => {});
  };
  signal.addEventListener("abort", abort, { once: true });
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      if (buffer.length > 1_048_576) throw Error("实时消息超过上限");
      let boundary: RegExpExecArray | null;
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        const lines = frame.split(/\r?\n/);
        if (
          !lines.some(
            (l) => l.startsWith("event:") && l.slice(6).trim() === "collection",
          )
        )
          continue;
        const raw = lines
          .filter((l) => l.startsWith("data:"))
          .map((l) => l.slice(5).replace(/^ /, ""))
          .join("\n");
        const data = JSON.parse(raw);
        if (!data || !Array.isArray(data.items) || data.items.length > 3)
          throw Error("实时数据格式无效");
        if (!signal.aborted) onData(data);
        if (signal.aborted) break;
      }
    }
  } finally {
    signal.removeEventListener("abort", abort);
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
export function useDeviceCollection(id: MaybeRefOrGetter<string>) {
  const query = useApiQuery<CollectionSnapshot>(
    computed(() => `/devices/${encodeURIComponent(toValue(id))}/collection`),
  );
  const live = ref<CollectionSnapshot | null>(null),
    streaming = ref(false);
  watch(
    () => toValue(id),
    (deviceId, _, cleanup) => {
      live.value = null;
      streaming.value = false;
      let stopped = false,
        timer: ReturnType<typeof setTimeout> | undefined;
      const abort = new AbortController();
      const connect = async () => {
        try {
          await readCollectionStream(deviceId, abort.signal, (data) => {
            live.value = data;
            streaming.value = true;
          });
        } catch {
        } finally {
          if (!stopped) {
            streaming.value = false;
            void query.refetch();
            timer = setTimeout(() => void connect(), 5000);
          }
        }
      };
      void connect();
      cleanup(() => {
        stopped = true;
        clearTimeout(timer);
        abort.abort();
      });
    },
    { immediate: true },
  );
  const data = computed(() =>
    live.value &&
    (!query.data.value ||
      Date.parse(live.value.asOf) >= Date.parse(query.data.value.asOf))
      ? live.value
      : query.data.value,
  );
  return { ...query, data, streaming };
}
