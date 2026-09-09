import { toValue, type MaybeRefOrGetter } from "vue";
import { queryString } from "./api";
import { useApiQuery } from "./queries";
import type { AlertSummary } from "./types";

// The shell and unscoped queue share the same organization/user query cache.
export function useAlertSummary(deviceId: MaybeRefOrGetter<string> = "") {
  return useApiQuery<AlertSummary>(
    () => `/alerts/summary${queryString({ deviceId: toValue(deviceId) })}`,
  );
}
