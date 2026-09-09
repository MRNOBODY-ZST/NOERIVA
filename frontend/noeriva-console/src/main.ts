import { createApp } from "vue";
import { createPinia } from "pinia";
import { VueQueryPlugin, QueryClient } from "@tanstack/vue-query";
import App from "./App.vue";
import { router } from "./router";
import "./styles/base.css";
export const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: true } },
});
createApp(App)
  .use(createPinia())
  .use(VueQueryPlugin, { queryClient })
  .use(router)
  .mount("#app");
