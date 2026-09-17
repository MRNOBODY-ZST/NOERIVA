export interface Session {
  accessToken?: string;
  tokenType?: string;
  username: string;
  organizationId: string;
  roles: string[];
  mode: "DEMO" | "CONNECTED";
  timezone: string | null;
}
export interface Device {
  id: string;
  name: string;
  type: string;
  siteId: string;
  siteName: string;
  vendor: string;
  model: string;
  managementAddress: string;
  health: string;
  availability: string;
  lastSeen: string | null;
  revision: number;
  capabilities: string[];
}
export interface Page<T> {
  items: T[];
  nextCursor?: string | null;
  asOf?: string;
  source?: string;
  mode?: string;
}
export interface Site {
  id: string;
  name: string;
  timezone: string | null;
}
export interface Overview {
  devices: number;
  critical: number;
  warning: number;
  healthy: number;
  unknown: number;
  stale: number;
  activeAlerts: number;
  collectors: number;
  asOf: string;
  mode: string;
}
export interface Source {
  sourceId: string;
  kind: string;
  observedAt: string | null;
  sequence: number;
  epoch: string;
  health: string;
  metrics: Record<string, number | null>;
  freshness: string;
}
export interface Quality {
  asOf: string | null;
  sourceFreshness: string;
  coverage: number;
  resolution: number;
  dataRevision: number | string;
  provisional: boolean;
  qualityFlags: string[];
}
export interface Summary extends Quality {
  device: Device;
  sources: Source[];
  activeAlerts: number;
}
export interface Interface {
  id: string;
  deviceId: string;
  name: string;
  macAddress: string;
  speedBps: string;
  adminStatus: string;
  operStatus: string;
}
export interface MetricSeries extends Quality {
  deviceId: string;
  metric: string;
  unit: string;
  from: string;
  to: string;
  source: string;
  points: { timestamp: string; value: number | null }[];
}
export interface HeatCell extends Omit<Quality, "resolution"> {
  date: string;
  hour: number;
  state: string;
  value: number | null;
  unit: string;
  aggregation: string;
  intervals: { from: string; to: string; durationSeconds: number }[];
}
export interface Heatmap extends Quality {
  deviceId: string;
  interfaceId: string;
  timezone: string;
  direction: string;
  statistic: string;
  unit: string;
  fromDate: string;
  toDate: string;
  source: string;
  cells: HeatCell[];
}
export interface TopologyNode {
  id: string;
  name: string;
  type: string;
  health: string;
  registered?: boolean;
  siteId?: string;
  availability?: string;
  freshness?: string;
  vlanIds?: number[];
  addresses?: string[];
  mac?: string | null;
  confidence?: string;
  qualityFlags?: string[];
  evidence?: TopologyEvidence[];
}
export interface TopologyEvidence {
  protocol: string;
  sourceDeviceId: string;
  sourceInterface: string | null;
  sourceRef: string | null;
  observedAt: string;
  detail: string;
}
export interface TopologyEdge {
  id: string;
  source: string;
  target: string;
  sourceInterface: string;
  targetInterface: string;
  kind: string;
  provenance: string;
  observedAt: string;
  inferred?: boolean;
  confidence?: string;
  vlanIds?: number[];
  qualityFlags?: string[];
  evidence?: TopologyEvidence[];
}
export interface Topology {
  nodes: TopologyNode[];
  edges: TopologyEdge[];
  asOf: string;
  qualityFlags: string[];
  view?: string;
  vlans?: {
    vlanId: number;
    name: string;
    nodeCount: number;
    edgeCount: number;
  }[];
}
export interface Event {
  id: string;
  deviceId: string;
  kind: string;
  severity: string;
  message: string;
  observedAt: string;
  ingestedAt: string;
  source: string;
  qualityFlags: string[];
}
export interface Alert {
  id: string;
  deviceId: string;
  deviceName: string;
  severity: string;
  state: string;
  title: string;
  openedAt: string;
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
  revision: number;
}
export interface AlertSummary {
  open: number;
  acknowledged: number;
  resolved: number;
  active: number;
  asOf: string;
}
export interface Collector {
  id: string;
  name: string;
  siteId: string;
  status: string;
  lastHeartbeat: string | null;
  lastSuccessfulCollection: string | null;
  queueBytes: number;
  queueLimitBytes: number;
  oldestQueuedAt: string | null;
  version: string;
  capabilities: string[];
  source: string;
}
export interface Capability {
  id: string;
  name: string;
  roadmapTier: string;
  maturity: string;
  coverage: string;
  verification: string;
  phase: number | string;
  notes: string;
}
