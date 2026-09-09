const labels: Record<string, string> = {
  HEALTHY: "正常",
  WARNING: "警告",
  CRITICAL: "严重",
  UNKNOWN: "未知",
  ONLINE: "在线",
  OFFLINE: "离线",
  FRESH: "新鲜",
  CURRENT: "新鲜",
  STALE: "过期",
  PARTIAL: "部分数据",
  MISSING: "缺失",
  FUTURE: "未来时段",
  DST_MISSING: "夏令时缺口",
  OBSERVED: "已观测",
  OBSERVED_ZERO: "观测为零",
  OPEN: "待处理",
  ACKNOWLEDGED: "已确认",
  RESOLVED: "已恢复",
  HOST: "主机",
  ENDPOINT: "发现终端",
  BMC: "带外管理",
  SWITCH: "交换机",
  ROUTER: "路由器",
  FIREWALL: "防火墙",
  UP: "启用",
  DOWN: "停用",
  DEMO: "合成演示",
  CONNECTED: "已连接",
  INFO: "信息",
  ERROR: "错误",
  SIMULATED: "合成来源",
};
export function stateLabel(value: string | null | undefined): string {
  return value ? labels[value.toUpperCase()] || value : "未知";
}
export function formatRate(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "—";
  if (Math.abs(value) >= 1e9) return `${(value / 1e9).toFixed(2)} Gbps`;
  if (Math.abs(value) >= 1e6) return `${(value / 1e6).toFixed(2)} Mbps`;
  if (Math.abs(value) >= 1e3) return `${Number((value / 1e3).toFixed(1))} Kbps`;
  return `${value} bps`;
}
export function formatLatency(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "—";
  return Math.abs(value) >= 1000
    ? `${Number((value / 1000).toFixed(2))} s`
    : `${Number(value.toFixed(2))} ms`;
}
export function formatTime(
  value: string | null | undefined,
  zone = "UTC",
): string {
  if (!value) return "尚无采集记录";
  const time = new Date(value);
  if (!Number.isFinite(time.getTime())) return "时间不可用";
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: zone,
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).format(time);
}
export function shortNumber(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value)
    ? "—"
    : new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 }).format(
        value,
      );
}
type CounterValue = string | number | bigint | null | undefined;
function exactCounter(value: CounterValue): bigint | null {
  if (typeof value === "bigint") return value >= 0n ? value : null;
  if (typeof value === "number")
    return Number.isSafeInteger(value) && value >= 0 ? BigInt(value) : null;
  if (typeof value === "string" && /^\d+$/.test(value)) return BigInt(value);
  return null;
}
export function formatBytes(value: CounterValue): string {
  const bytes = exactCounter(value);
  if (bytes === null) return "—";
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
  let divisor = 1n,
    unit = 0;
  while (bytes >= divisor * 1024n && unit < units.length - 1) {
    divisor *= 1024n;
    unit++;
  }
  if (!unit) return `${bytes} B`;
  const rounded = (bytes * 100n + divisor / 2n) / divisor;
  const fraction = String(rounded % 100n)
    .padStart(2, "0")
    .replace(/0+$/, "");
  return `${rounded / 100n}${fraction ? `.${fraction}` : ""} ${units[unit]}`;
}
export function formatCount(value: CounterValue): string {
  return exactCounter(value)?.toLocaleString("en-US") ?? "—";
}

const interfaceCollator = new Intl.Collator("en", {
  numeric: true,
  sensitivity: "base",
});
export function naturalNameSort<T extends { name: string }>(
  items: readonly T[],
): T[] {
  return [...items].sort((a, b) => interfaceCollator.compare(a.name, b.name));
}
export function formatMetric(
  value: number | null | undefined,
  unit = "",
): string {
  if (value == null || !Number.isFinite(value)) return "—";
  if (["bps", "bit/s"].includes(unit)) return formatRate(value);
  if (["W", "watts"].includes(unit) && Math.abs(value) >= 1000)
    return `${shortNumber(value / 1000)} kW`;
  return `${shortNumber(value)}${unit === "%" ? "" : " "}${unit === "Cel" ? "°C" : unit === "RPM" ? "转/分钟" : unit}`.trim();
}
export function identityValue(value: string | null | undefined): string {
  return !value ||
    /^(n\/?a|none|null|unknown|not (provided|available)|-)$/i.test(value.trim())
    ? "未提供"
    : value;
}
