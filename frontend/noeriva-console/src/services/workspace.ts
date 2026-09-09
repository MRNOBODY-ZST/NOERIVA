import type { Device, Event, Overview } from "./types";
export interface WorkspaceOverview {
  totals: Overview;
  trafficSource: {
    deviceId: string;
    deviceName: string;
    sourceId: string;
    kind: string;
    observedAt: string;
    freshness: string;
    metrics: Record<string, number>;
  } | null;
  siteHealth: Array<{
    siteId: string;
    siteName: string;
    timezone: string | null;
    devices: number;
    critical: number;
    warning: number;
    healthy: number;
    unknown: number;
    stale: number;
  }>;
  priorityDevices: Device[];
  recentEvents: Event[];
  recentEventsStatus: "AVAILABLE" | "UNAVAILABLE" | "NOT_REQUESTED";
  asOf: string;
  mode: "CONNECTED" | "DEMO";
  qualityFlags: string[];
}
export interface WorkspaceSearch {
  interfaces?: {
    id: string;
    deviceId: string;
    deviceName: string;
    name: string;
    macAddress?: string;
    siteName?: string;
  }[];
  provider?: string;
  status?: string;
  indexedAt?: string | null;
  assets: Device[];
  recentEvents: Event[];
  query: string;
  eventScanLimit: number;
  eventWindowFrom: string;
  eventWindowTo: string;
  eventScope: "RECENT_7_DAYS_MAX_100" | "ENTITY_DIRECTORY";
  recentEventsStatus: "AVAILABLE" | "UNAVAILABLE" | "NOT_INDEXED";
  qualityFlags: string[];
  asOf: string;
  mode: "CONNECTED" | "DEMO";
}
