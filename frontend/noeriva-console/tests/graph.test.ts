import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { describe, expect, it, vi } from "vitest";
import GraphPanel from "../src/components/GraphPanel.vue";
import type { Topology } from "../src/services/types";
import {
  topologyEdgeLabel,
  topologyLinks,
  topologyNodeColor,
  filterTopology,
  topologyNodeState,
} from "../src/utils/topology";

vi.stubGlobal("matchMedia", () => ({ matches: true }));
const topology: Topology = {
  asOf: "2026-09-06T00:00:00Z",
  qualityFlags: [],
  nodes: [
    { id: "r1", name: "core-router", type: "ROUTER", health: "HEALTHY" },
    { id: "s1", name: "edge-switch", type: "SWITCH", health: "WARNING" },
    { id: "b1", name: "bmc-host", type: "BMC", health: "UNKNOWN" },
    { id: "h1", name: "failed-host", type: "HOST", health: "CRITICAL" },
  ],
  edges: [],
};
function render(snapshot: Topology) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div/>" } }],
  });
  const wrapper = mount(GraphPanel, {
    props: { topology: snapshot },
    global: {
      plugins: [createPinia(), router],
      stubs: { TopologyCanvas: true, ChartCanvas: true },
    },
  });
  const canvas = () => wrapper.findComponent({ name: "TopologyCanvas" });
  const graph = () => canvas().props("graph") as Topology;
  return { wrapper, router, canvas, graph };
}
describe("topology graph controls", () => {
  const snapshot: Topology = {
    ...topology,
    nodes: [
      { ...topology.nodes[0]!, registered: true, vlanIds: [10, 20] },
      { ...topology.nodes[1]!, registered: true, vlanIds: [10] },
      {
        id: "terminal",
        name: "workstation",
        type: "HOST",
        health: "UNKNOWN",
        registered: false,
        vlanIds: [10],
        addresses: ["192.0.2.3"],
        confidence: "CORROBORATED",
      },
      {
        id: "neighbor",
        name: "gateway-neighbor",
        type: "HOST",
        health: "UNKNOWN",
        registered: false,
        vlanIds: [20],
      },
    ],
    edges: [
      {
        id: "physical",
        source: "r1",
        target: "s1",
        sourceInterface: "Te1",
        targetInterface: "Te2",
        kind: "PHYSICAL",
        provenance: "LLDP",
        observedAt: topology.asOf,
        vlanIds: [],
        inferred: false,
      },
      {
        id: "fdb",
        source: "s1",
        target: "terminal",
        sourceInterface: "Gi1",
        targetInterface: "",
        kind: "L2_INFERRED",
        provenance: "FDB",
        observedAt: topology.asOf,
        vlanIds: [10],
        inferred: true,
        confidence: "CORROBORATED",
        evidence: [
          {
            protocol: "FDB",
            sourceDeviceId: "s1",
            sourceInterface: "Gi1",
            sourceRef: "1.3.6.1.2.1.17",
            observedAt: topology.asOf,
            detail: "MAC learned on the same interface in two observations.",
          },
        ],
      },
      {
        id: "arp",
        source: "r1",
        target: "neighbor",
        sourceInterface: "Vlan20",
        targetInterface: "",
        kind: "L3_INFERRED",
        provenance: "ARP",
        observedAt: topology.asOf,
        vlanIds: [20],
        inferred: true,
      },
    ],
  };

  it("uses a dedicated network renderer with physical observations and L2 inference by default", async () => {
    const { wrapper, canvas, graph } = render(snapshot);
    try {
      expect(canvas().exists()).toBe(true);
      expect(graph().edges.map((edge) => edge.id)).toEqual(["physical", "fdb"]);
      const switches = wrapper.findAll('input[type="checkbox"]');
      await switches[1]!.setValue(true);
      expect(graph().edges.map((edge) => edge.id)).toEqual([
        "physical",
        "fdb",
        "arp",
      ]);
      await switches[0]!.setValue(false);
      expect(graph().edges.map((edge) => edge.id)).toEqual(["physical", "arp"]);
      expect(graph().nodes).toHaveLength(4);
    } finally {
      wrapper.unmount();
    }
  });
  it("filters VLAN evidence without inventing physical connections", async () => {
    expect(
      filterTopology(snapshot, true, true, "10").edges.map((edge) => edge.id),
    ).toEqual(["fdb"]);
    expect(
      filterTopology(snapshot, true, true, "20").nodes.map((node) => node.id),
    ).toEqual(["r1", "neighbor"]);
    expect(
      filterTopology(snapshot, true, true, "unknown").edges.map(
        (edge) => edge.id,
      ),
    ).toEqual(["physical"]);
    const { wrapper, graph } = render(snapshot);
    try {
      await wrapper
        .findAll("button")
        .find((button) => button.text() === "按 VLAN 分组")!
        .trigger("click");
      await wrapper
        .findAll("button")
        .find((button) => button.text().startsWith("VLAN 10"))!
        .trigger("click");
      expect(graph().edges.map((edge) => edge.id)).toEqual(["fdb"]);
      expect(graph().nodes.map((node) => node.id)).toContain("r1");
      await wrapper
        .findAll("button")
        .find((button) => button.text() === "全部分组")!
        .trigger("click");
      expect(graph().edges).toHaveLength(2);
    } finally {
      wrapper.unmount();
    }
  });
  it("keeps list evidence accessible and only opens registered devices", async () => {
    const { wrapper, canvas, router } = render(snapshot);
    const navigate = vi.spyOn(router, "push");
    try {
      canvas().vm.$emit("open", { dataType: "node", data: { id: "terminal" } });
      await nextTick();
      expect(navigate).not.toHaveBeenCalled();
      expect(wrapper.get(".graph-inspector").text()).toContain("尚未登记");
      expect(wrapper.get(".graph-inspector").find("a").exists()).toBe(false);
      canvas().vm.$emit("open", { dataType: "node", data: { id: "r1" } });
      await nextTick();
      expect(navigate).toHaveBeenCalledWith("/devices/r1");
      await wrapper
        .findAll("button")
        .find((button) => button.text() === "列表替代")!
        .trigger("click");
      await wrapper
        .findAll(".graph-list .graph-edge-button")
        .find((button) => button.text().includes("Gi1"))!
        .trigger("click");
      expect(wrapper.get(".graph-inspector").text()).toContain("二层接入推断");
      expect(wrapper.get(".graph-inspector").text()).toContain(
        "MAC learned on the same interface",
      );
      expect(wrapper.get(".graph-inspector").text()).toContain("edge-switch");
      expect(wrapper.get(".graph-inspector details").text()).toContain(
        "1.3.6.1.2.1.17",
      );
    } finally {
      wrapper.unmount();
    }
  });
  it("freezes the current force layout without switching to a circular layout", async () => {
    vi.stubGlobal("matchMedia", () => ({ matches: false }));
    const { wrapper, canvas } = render(snapshot);
    try {
      expect(canvas().props("physics")).toBe(true);
      await wrapper
        .findAll("button")
        .find((button) => button.text() === "固定布局")!
        .trigger("click");
      expect(canvas().props("physics")).toBe(false);
      expect(wrapper.text()).toContain("可自由拖拽节点");
      expect(wrapper.text()).not.toContain("圆形布局");
    } finally {
      wrapper.unmount();
      vi.stubGlobal("matchMedia", () => ({ matches: true }));
    }
  });
  it("does not update the canvas data when only observation timestamps change", async () => {
    const { wrapper, graph } = render(snapshot);
    try {
      const original = graph();
      await wrapper.setProps({
        topology: {
          ...snapshot,
          asOf: "2026-09-21T00:00:00Z",
          edges: snapshot.edges.map((edge) => ({
            ...edge,
            observedAt: "2026-09-21T00:00:00Z",
          })),
        },
      });
      expect(graph()).toBe(original);
    } finally {
      wrapper.unmount();
    }
  });
  it("does not color stale or unregistered last-known healthy nodes green", () => {
    const node = {
      ...topology.nodes[0]!,
      health: "HEALTHY",
      freshness: "STALE",
    };
    expect(topologyNodeState(node)).toBe("STALE");
    expect(topologyNodeColor(node)).toBe(
      topologyNodeColor({ ...node, health: "UNKNOWN", freshness: "FRESH" }),
    );
    expect(
      topologyNodeState({ ...node, freshness: "FRESH", registered: false }),
    ).toBe("UNKNOWN");
    expect(topologyNodeState({ ...node, availability: "OFFLINE" })).toBe(
      "OFFLINE",
    );
  });
  it("keeps parallel port observations distinct and hides opaque endpoint IDs", () => {
    const first = { ...snapshot.edges[0]!, id: "a", sourceInterface: "Te1" };
    const second = { ...first, id: "b", sourceInterface: "Te2" };
    expect(
      topologyLinks([first, second]).map((edge) => edge.lineStyle.curveness),
    ).toEqual([-0.18, 0.18]);
    expect(
      topologyLinks([second, first]).find((edge) => edge.id === "a")?.lineStyle
        .curveness,
    ).toBe(-0.18);
    expect(
      topologyEdgeLabel({
        sourceInterface: "b74c7604-7740-4be1-910a-ff471b19d812",
        targetInterface: "observed-a74c",
        kind: "PHYSICAL",
      }),
    ).toBe("物理连接");
    expect(
      topologyEdgeLabel({ sourceInterface: "Te0/3/0", targetInterface: "NA" }),
    ).toBe("Te0/3/0 ↔ 对端口未知");
  });
});
describe("dense graph labels", () => {
  const dense: Topology = {
    ...topology,
    nodes: [
      { ...topology.nodes[0]!, registered: true },
      { ...topology.nodes[1]!, registered: true },
      ...Array.from({ length: 20 }, (_, index) => ({
        id: `terminal-${index}`,
        name: `discovered-terminal-${index}`,
        type: "HOST",
        health: "UNKNOWN",
        registered: false,
      })),
    ],
    edges: Array.from({ length: 20 }, (_, index) => ({
      id: `fdb-${index}`,
      source: "s1",
      target: `terminal-${index}`,
      sourceInterface: `Gi1/0/${index + 1}`,
      targetInterface: "",
      kind: "L2_INFERRED",
      inferred: true,
      provenance: "FDB",
      observedAt: topology.asOf,
    })),
  };

  it("compacts graphs above 20 nodes and forwards selection for visible names and evidence", async () => {
    const { wrapper, canvas } = render(dense);
    try {
      expect(canvas().props("compact")).toBe(true);
      canvas().vm.$emit("select", {
        dataType: "node",
        data: { id: "terminal-0" },
      });
      await nextTick();
      expect(canvas().props("selectedId")).toBe("terminal-0");
      expect(wrapper.get(".graph-inspector").text()).toContain(
        "discovered-terminal-0",
      );
      canvas().vm.$emit("select", { dataType: "edge", data: { id: "fdb-1" } });
      await nextTick();
      expect(canvas().props("selectedEdgeId")).toBe("fdb-1");
      expect(wrapper.get(".graph-inspector").text()).toContain("Gi1/0/2");
      await wrapper.get('select[aria-label="拓扑标签"]').setValue("FULL");
      expect(canvas().props("compact")).toBe(false);
    } finally {
      wrapper.unmount();
    }
  });
  it("keeps smaller scopes labeled and preserves the explicit compact preference", async () => {
    const small = {
      ...dense,
      nodes: dense.nodes.slice(0, 20),
      edges: dense.edges.slice(0, 18),
    };
    const { wrapper, canvas } = render(small);
    try {
      expect(canvas().props("compact")).toBe(false);
      await wrapper.setProps({ topology: dense });
      expect(canvas().props("compact")).toBe(true);
      await wrapper.get('select[aria-label="拓扑标签"]').setValue("COMPACT");
      await wrapper.setProps({ topology: small });
      expect(canvas().props("compact")).toBe(true);
    } finally {
      wrapper.unmount();
    }
  });
});
