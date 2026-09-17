import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useQueries, useQueryClient } from "@tanstack/vue-query";
import { useSessionStore } from "../stores/session";
import { request } from "./api";
import type { Device } from "./types";

// Resolve only visible page references and reuse the organization-scoped device cache.
export function useDeviceNames(ids: MaybeRefOrGetter<readonly string[]>) {
  const auth = useSessionStore();
  const client = useQueryClient();
  const keys = computed(() => [
    "noeriva",
    auth.session?.organizationId,
    auth.session?.username,
  ]);
  const visible = computed(() =>
    [...new Set(toValue(ids).filter(Boolean))].slice(0, 60),
  );
  function cachedDevice(id: string): Device | undefined {
    for (const [, value] of client.getQueriesData({ queryKey: keys.value })) {
      const data = value as
        | {
            id?: string;
            name?: string;
            type?: string;
            device?: Device;
            items?: Device[];
          }
        | undefined;
      if (!data) continue;
      if (data.id === id && data.name && data.type) return data as Device;
      if (data.device?.id === id) return data.device;
      const item = data.items?.find(
        (device) => device.id === id && device.name && device.type,
      );
      if (item) return item;
    }
  }
  const devices = useQueries({
    queries: computed(() =>
      visible.value.map((id) => ({
        queryKey: [...keys.value, `/devices/${encodeURIComponent(id)}`],
        queryFn: ({ signal }: { signal: AbortSignal }) =>
          request<Device>(`/devices/${encodeURIComponent(id)}`, { signal }),
        initialData: () => cachedDevice(id),
        enabled: !!auth.session,
        staleTime: 300_000,
        retry: false,
      })),
    ),
  });
  const names = computed(
    () =>
      new Map(
        devices.value.flatMap((result) =>
          result.data?.id && result.data.name
            ? [[result.data.id, result.data.name] as const]
            : [],
        ),
      ),
  );
  return (id: string) => names.value.get(id) || "关联设备（名称未加载）";
}
