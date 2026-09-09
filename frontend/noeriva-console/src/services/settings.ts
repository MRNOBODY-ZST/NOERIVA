import type { MaybeRefOrGetter } from "vue";
import { useApiQuery } from "./queries";
export function useSystemSettings(enabled: MaybeRefOrGetter<boolean> = true) {
  return useApiQuery<SystemSettings>("/settings", enabled, {
    refetchInterval: false,
    staleTime: 300_000,
  });
}
export interface SystemSettings {
  revision: number;
  organizationName: string;
  timezone: string;
  defaultCollectionIntervalSeconds: number;
  defaultTimeoutMillis: number;
  defaultMaxInterfaces: number;
  configurationSyncEnabled: boolean;
  configurationSyncIntervalSeconds: number;
  updatedAt: string | null;
  updatedBy: string | null;
  runtime: {
    mode: string;
    searchProvider: string;
    searchStatus: string;
    searchLastIndexedAt: string | null;
    configurationCapture: string;
    historyRetention: string;
  };
  account: { username: string; roles: string[]; organizationId: string };
}
