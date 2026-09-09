export type CandidateStatus =
  | "NEW"
  | "EXISTING"
  | "LINKED"
  | "REGISTERED"
  | "POSSIBLE_DUPLICATE"
  | "CONFLICT";
export interface NeighborEvidence {
  sourceDeviceId: string;
  sourceDeviceName: string;
  source: "ARP" | "LLDP" | "CDP";
  observedAt: string;
  address: string;
  mac: string | null;
  interfaceName: string | null;
  vlan: string | null;
  name: string | null;
  chassisId: string | null;
  chassisSubtype: string | null;
  portId: string | null;
  ttlSeconds: number | null;
  ageMinutes: number | null;
  validUntil: string | null;
  qualityFlags: string[];
}
export interface DiscoveryCandidate {
  id: string;
  revision: number;
  address: string;
  siteId: string;
  name: string | null;
  mac: string | null;
  status: CandidateStatus;
  reasons: string[];
  evidence: NeighborEvidence[];
  associatedDeviceId: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
}
export interface DiscoveryRun {
  id: string;
  siteId: string;
  cidr: string;
  asOf: string;
  sourcesRequested: number;
  sourcesUsed: number;
  observationsRead: number;
  candidatesUpdated: number;
  existingCount: number;
  duplicateCount: number;
  conflictCount: number;
  sources: {
    deviceId: string;
    deviceName: string;
    status: "USED" | "MISSING" | "STALE" | "INVALID";
    observedAt: string | null;
    acceptedCount: number;
    reason: string | null;
  }[];
  qualityFlags: string[];
}
export const candidateLabels: Record<CandidateStatus, string> = {
  NEW: "待审核",
  EXISTING: "已有同址资产",
  LINKED: "已关联",
  REGISTERED: "已登记",
  POSSIBLE_DUPLICATE: "疑似重复",
  CONFLICT: "身份线索冲突",
};
export const discoveryReasonLabels: Record<string, string> = {
  NEIGHBOR_EVIDENCE_ONLY: "仅有邻居观测证据",
  MAC_SHARED_BY_ADDRESSES: "同一 MAC 出现在多个地址",
  ADDRESS_HAS_MULTIPLE_MACS: "同一地址存在多个 MAC",
  CHASSIS_IDENTITY_CONFLICT: "机箱标识冲突",
  MULTIPLE_EXISTING_ASSETS: "已有多条同址资产",
  NAME_VARIANTS: "来源名称不一致",
};
export function canonicalDiscoveryCidr(value: string): boolean {
  const match =
    /^(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\/(2[4-9]|3[0-2])$/.exec(
      value,
    );
  if (!match) return false;
  const octets = match.slice(1, 5).map(Number),
    prefix = Number(match[5]);
  if (
    octets.some((n) => n > 255) ||
    octets[0] === 0 ||
    octets[0] === 127 ||
    octets[0]! >= 224 ||
    (octets[0] === 169 && octets[1] === 254)
  )
    return false;
  const address = octets.reduce((n, part) => n * 256 + part, 0);
  return address % 2 ** (32 - prefix) === 0;
}
