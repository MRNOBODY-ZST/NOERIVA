import { ref, watch } from "vue";
import { defineStore } from "pinia";
const timezones = [
  "UTC",
  "Asia/Shanghai",
  "Asia/Singapore",
  "America/New_York",
  "Europe/Berlin",
];
function stored(key: string, fallback: string) {
  try {
    return localStorage.getItem(key) || fallback;
  } catch {
    return fallback;
  }
}
function persist(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* Preferences still work when browser storage is unavailable. */
  }
}
export const usePreferencesStore = defineStore("preferences", () => {
  const dark = ref(stored("noeriva-theme", "light") === "dark");
  const compact = ref(stored("noeriva-density", "comfortable") === "compact");
  const collapsed = ref(false);
  const reducedMotion = ref(stored("noeriva-motion", "system") === "reduced");
  watch(
    reducedMotion,
    (value) => {
      document.documentElement.dataset.motion = value ? "reduced" : "system";
      persist("noeriva-motion", value ? "reduced" : "system");
    },
    { immediate: true },
  );
  const savedTimezone = stored("noeriva-timezone", "");
  let applyingOrganizationTimezone = false;
  const timezone = ref(
    timezones.includes(savedTimezone) ? savedTimezone : "Asia/Shanghai",
  );
  watch(
    dark,
    (value) => {
      document.documentElement.dataset.theme = value ? "dark" : "light";
      document
        .querySelector('meta[name="theme-color"]')
        ?.setAttribute("content", value ? "#111923" : "#f3f6fa");
      persist("noeriva-theme", value ? "dark" : "light");
    },
    { immediate: true },
  );
  watch(
    compact,
    (value) => {
      document.documentElement.dataset.density = value
        ? "compact"
        : "comfortable";
      persist("noeriva-density", value ? "compact" : "comfortable");
    },
    { immediate: true },
  );
  watch(
    timezone,
    (value) => {
      if (!applyingOrganizationTimezone) persist("noeriva-timezone", value);
    },
    { flush: "sync" },
  );
  function applyOrganizationTimezone(zone: string) {
    if (stored("noeriva-timezone", "") || !timezones.includes(zone)) return;
    applyingOrganizationTimezone = true;
    timezone.value = zone;
    applyingOrganizationTimezone = false;
  }
  function reset() {
    dark.value = false;
    compact.value = false;
    timezone.value = "Asia/Shanghai";
    collapsed.value = false;
  }
  return {
    dark,
    compact,
    collapsed,
    timezone,
    reducedMotion,
    applyOrganizationTimezone,
    reset,
  };
});
