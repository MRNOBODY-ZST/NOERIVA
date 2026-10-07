import { computed, ref } from "vue";
import { defineStore } from "pinia";
import {
  clearAuthorization,
  request,
  setAuthorization,
  setAccessToken,
} from "../services/api";
import type { Session } from "../services/types";
export const useSessionStore = defineStore("session", () => {
  const session = ref<Session | null>(null);
  const error = ref("");
  const pending = ref(false);
  let loginAttempt = 0;
  const canWrite = computed(
    () =>
      session.value?.roles.some((role) =>
        ["ADMIN", "OPERATOR"].includes(role),
      ) ?? false,
  );
  async function login(username: string, password: string) {
    const attempt = ++loginAttempt;
    error.value = "";
    pending.value = true;
    setAuthorization(username, password);
    try {
      const {
        accessToken,
        tokenType: _tokenType,
        ...metadata
      } = await request<Session>("/session");
      if (attempt !== loginAttempt) return;
      if (accessToken) setAccessToken(accessToken);
      session.value = metadata;
    } catch (cause) {
      if (attempt !== loginAttempt) return;
      clearAuthorization();
      session.value = null;
      error.value = cause instanceof Error ? cause.message : "登录失败";
    } finally {
      if (attempt === loginAttempt) pending.value = false;
    }
  }
  function logout() {
    loginAttempt++;
    clearAuthorization();
    session.value = null;
    error.value = "";
    pending.value = false;
  }
  return { session, error, pending, canWrite, login, logout };
});
