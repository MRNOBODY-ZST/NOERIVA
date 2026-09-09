import { mount, flushPromises } from "@vue/test-utils";
import { it, expect, vi, afterEach } from "vitest";
import ModalDialog from "../src/components/ModalDialog.vue";
afterEach(() => {
  delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>)
    .showModal;
  vi.restoreAllMocks();
});
it("opens a dialog when a direct navigation supplies an initially true open prop", async () => {
  const show = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  });
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    value: show,
    configurable: true,
  });
  const wrapper = mount(ModalDialog, {
    props: { open: true, title: "Create incident" },
    attachTo: document.body,
  });
  try {
    await flushPromises();
    expect(wrapper.get("dialog").attributes()).toHaveProperty("open");
    expect(show).toHaveBeenCalledOnce();
  } finally {
    wrapper.unmount();
  }
});
