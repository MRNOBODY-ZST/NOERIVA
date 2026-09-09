import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useQuery } from "@tanstack/vue-query";
import { useSessionStore } from "../stores/session";
import { ApiError, request } from "./api";
export function useApiQuery<T>(
  path: MaybeRefOrGetter<string>,
  enabled: MaybeRefOrGetter<boolean> = true,
  options: { refetchInterval?: number | false; staleTime?: number } = {},
) {
  const auth = useSessionStore();
  return useQuery({
    queryKey: computed(() => [
      "noeriva",
      auth.session?.organizationId,
      auth.session?.username,
      toValue(path),
    ]),
    queryFn: ({ signal }) => request<T>(toValue(path), { signal }),
    enabled: computed(() => !!auth.session && toValue(enabled)),
    staleTime: options.staleTime ?? 15_000,
    refetchInterval: options.refetchInterval ?? 20_000,
    retry: (count, error) =>
      !(
        error instanceof ApiError && [401, 403, 404, 409].includes(error.status)
      ) && count < 1,
  });
}
