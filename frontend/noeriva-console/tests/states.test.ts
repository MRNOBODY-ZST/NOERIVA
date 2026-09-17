import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import QueryState from "../src/components/QueryState.vue";
import StatusBadge from "../src/components/StatusBadge.vue";
import { ApiError } from "../src/services/api";

describe("source and permission state presentation", () => {
  it("renders a permission failure distinctly from an empty query with a retry action", async () => {
    const wrapper = mount(QueryState, {
      props: {
        error: new ApiError("当前角色不可读取。", 403, "req-denied"),
        empty: true,
      },
    });
    expect(wrapper.get('[role="alert"]').text()).toContain("访问受限");
    expect(wrapper.text()).toContain("req-denied");
    expect(wrapper.text()).not.toContain("当前范围内没有数据");
    await wrapper.get("button").trigger("click");
    expect(wrapper.emitted("retry")).toHaveLength(1);
  });
  it("shows last-known source problems with explicit noncolor text", () => {
    const stale = mount(StatusBadge, { props: { status: "STALE" } });
    const unknown = mount(StatusBadge, { props: { status: null } });
    expect(stale.text()).toBe("过期");
    expect(unknown.text()).toBe("未知");
    expect(unknown.classes()).not.toContain("good");
  });
});
