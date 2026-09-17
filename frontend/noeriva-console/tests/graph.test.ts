import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { init, use, setPlatformAPI } from "echarts/core";
import { GraphChart } from "echarts/charts";
import { TooltipComponent } from "echarts/components";
import { SVGRenderer } from "echarts/renderers";
import { describe, expect, it, vi } from "vitest";
import GraphPanel from "../src/components/GraphPanel.vue";
import ChartCanvas from "../src/components/ChartCanvas.vue";
import type { Topology } from "../src/services/types";
import {
  topologyEdgeLabel,
  topologyLinks,
  topologyNodeColor,
  filterTopology,
  topologyNodeState,
} from "../src/utils/topology";

use([GraphChart, TooltipComponent, SVGRenderer]);
setPlatformAPI({ measureText: (text) => ({ width: String(text).length * 7 }) });
vi.stubGlobal("matchMedia", () => ({ matches: true }));

const topology: Topology = {
  asOf: "2026-09-06T00:00:00Z",
  nodes: [
    { id: "r1", name: "core-router", type: "ROUTER", health: "HEALTHY" },
    { id: "s1", name: "edge-switch", type: "SWITCH", health: "WARNING" },
    { id: "b1", name: "bmc-host", type: "BMC", health: "UNKNOWN" },
    { id: "h1", name: "failed-host", type: "HOST", health: "CRITICAL" },
  ],
  edges: [],
  qualityFlags: [],
};

describe("native graph symbols", () => {
  it("renders observed port labels and distinct parallel curves instead of internal edge UUIDs", () => {
    const edges = [
      {
        id: "observed-b74c7604-7740-4be1-910a-ff471b19d812",
        source: "r1",
        target: "s1",
        sourceInterface: "Te0/3/0",
        targetInterface: "Te1/5/1",
        kind: "PHYSICAL",
        provenance: "LLDP",
        observedAt: topology.asOf,
      },
      {
        id: "observed-a74c7604-7740-4be1-910a-ff471b19d812",
        source: "r1",
        target: "s1",
        sourceInterface: "Te0/3/1",
        targetInterface: "Te1/5/2",
        kind: "PHYSICAL",
        provenance: "CDP",
        observedAt: topology.asOf,
      },
    ];
    const wrapper = mount(GraphPanel, {
      props: { topology: { ...topology, edges } },
      global: {
        plugins: [
          createPinia(),
          createRouter({
            history: createMemoryHistory(),
            routes: [{ path: "/", component: { template: "<div/>" } }],
          }),
        ],
        stubs: { ChartCanvas: true },
      },
    });
    const chart = init(null, undefined, {
      renderer: "svg",
      ssr: true,
      width: 800,
      height: 460,
    });
    try {
      const option = wrapper.findComponent(ChartCanvas).props("option") as any;
      const curves = option.series[0].links.map(
        (edge: any) => edge.lineStyle.curveness,
      );
      expect(curves).toEqual([-0.18, 0.18]);
      chart.setOption(option);
      const svg = chart.renderToSVGString();
      expect(svg).toContain("Te0/3/0 ↔ Te1/5/1");
      expect(svg).toContain("Te0/3/1 ↔ Te1/5/2");
      expect(svg).not.toContain("observed-");
      expect(
        topologyLinks([...edges].reverse()).find(
          (edge) => edge.id === edges[0]!.id,
        )?.lineStyle.curveness,
      ).toBe(-0.18);
      const reverse = { ...edges[1]!, source: "s1", target: "r1" };
      const reversedLinks = topologyLinks([edges[0]!, reverse]);
      expect(reversedLinks[0]!.lineStyle.curveness).toBe(
        reversedLinks[1]!.lineStyle.curveness,
      );
    } finally {
      chart.dispose();
      wrapper.unmount();
    }
  });
  it("uses a readable relationship when endpoint identity is absent or an internal ID", () => {
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
  it.each([false, true])(
    "renders vector glyphs and state labels without image decoding (dark=%s)",
    (dark) => {
      localStorage.setItem("noeriva-theme", dark ? "dark" : "light");
      const wrapper = mount(GraphPanel, {
        props: { topology },
        global: {
          plugins: [
            createPinia(),
            createRouter({
              history: createMemoryHistory(),
              routes: [{ path: "/", component: { template: "<div />" } }],
            }),
          ],
          stubs: { ChartCanvas: true },
        },
      });
      const chart = init(null, undefined, {
        renderer: "svg",
        ssr: true,
        width: 800,
        height: 460,
      });
      try {
        chart.setOption(wrapper.findComponent(ChartCanvas).props("option"));
        const svg = chart.renderToSVGString();
        expect(svg).not.toContain("<image");
        expect(svg).toContain("core-router");
        expect(svg).toContain("警告");
        expect(svg).toContain("未知");
        for (const node of topology.nodes)
          expect(svg).toContain(`fill="${topologyNodeColor(node, dark)}"`);
        const option = wrapper
          .findComponent(ChartCanvas)
          .props("option") as any;
        expect(
          option.series[0].data.every(
            (node: any) => node.itemStyle.borderWidth === 0,
          ),
        ).toBe(true);
      } finally {
        chart.dispose();
        wrapper.unmount();
        localStorage.clear();
      }
    },
  );
});

describe("evidence-aware topology controls", () => {
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
  function render() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div/>" } }],
    });
    const wrapper = mount(GraphPanel, {
      props: { topology: snapshot },
      global: {
        plugins: [createPinia(), router],
        stubs: { ChartCanvas: true },
      },
    });
    const option = () =>
      wrapper.findComponent(ChartCanvas).props("option") as any;
    return { wrapper, router, option };
  }
  it("defaults to physical observations and dashed L2 inference; L3 can be independently enabled", async () => {
    const { wrapper, option } = render();
    try {
      expect(
        option().series[0].links.map((edge: any) => [
          edge.id,
          edge.lineStyle.type,
        ]),
      ).toEqual([
        ["physical", "solid"],
        ["fdb", "dashed"],
      ]);
      const switches = wrapper.findAll('input[type="checkbox"]');
      await switches[1]!.setValue(true);
      expect(option().series[0].links.map((edge: any) => edge.id)).toEqual([
        "physical",
        "fdb",
        "arp",
      ]);
      await switches[0]!.setValue(false);
      expect(option().series[0].links.map((edge: any) => edge.id)).toEqual([
        "physical",
        "arp",
      ]);
      expect(option().series[0].data).toHaveLength(4); // Unsupported physical edges are never invented for isolated discovered nodes.
    } finally {
      wrapper.unmount();
    }
  });
  it("groups shared VLAN members without inventing edges or assigning unknown links to a VLAN", async () => {
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
    const { wrapper, option } = render();
    try {
      await wrapper
        .findAll("button")
        .find((button) => button.text() === "按 VLAN 分组")!
        .trigger("click");
      await wrapper
        .findAll("button")
        .find((button) => button.text().startsWith("VLAN 10"))!
        .trigger("click");
      expect(option().series[0].links.map((edge: any) => edge.id)).toEqual([
        "fdb",
      ]);
      expect(option().series[0].data.map((node: any) => node.id)).toContain(
        "r1",
      );
      await wrapper
        .findAll("button")
        .find((button) => button.text() === "全部分组")!
        .trigger("click");
      expect(option().series[0].links).toHaveLength(2);
    } finally {
      wrapper.unmount();
    }
  });
  it("provides keyboard edge evidence and does not navigate discovered nodes to nonexistent device pages", async () => {
    const { wrapper, router } = render();
    const navigate = vi.spyOn(router, "push");
    try {
      wrapper
        .findComponent(ChartCanvas)
        .vm.$emit("open", { dataType: "node", data: { id: "terminal" } });
      await nextTick();
      expect(navigate).not.toHaveBeenCalled();
      expect(wrapper.get(".graph-inspector").text()).toContain("尚未登记");
      expect(wrapper.get(".graph-inspector").find("a").exists()).toBe(false);
      await wrapper
        .findAll("button")
        .find((button) => button.text() === "列表替代")!
        .trigger("click");
      await wrapper
        .get(".graph-list .graph-edge-button:nth-of-type(6)")
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
  it("keeps force physics enabled by default and removes the force engine in static mode", async () => {
    vi.stubGlobal("matchMedia", () => ({ matches: false }));
    const { wrapper, option } = render();
    try {
      expect(option().series[0].layout).toBe("force");
      expect(option().series[0].force.layoutAnimation).toBe(true);
      await wrapper
        .findAll("button")
        .find((button) => button.text() === "圆形排列")!
        .trigger("click");
      expect(option().series[0].force.layoutAnimation).toBe(false);
      expect(option().series[0].layout).toBe("circular");
      expect(option().series[0].data.every((node: any) => node.fixed)).toBe(
        true,
      );
    } finally {
      wrapper.unmount();
      vi.stubGlobal("matchMedia", () => ({ matches: true }));
    }
  });
  it("does not color a stale or unregistered last-known healthy node green", () => {
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
});

describe("dense graph label readability", () => {
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
  function render(snapshot = dense) {
    const wrapper = mount(GraphPanel, {
      props: { topology: snapshot },
      global: {
        plugins: [
          createPinia(),
          createRouter({
            history: createMemoryHistory(),
            routes: [
              { path: "/:pathMatch(.*)*", component: { template: "<div/>" } },
            ],
          }),
        ],
        stubs: { ChartCanvas: true },
      },
    });
    return {
      wrapper,
      option: () => wrapper.findComponent(ChartCanvas).props("option") as any,
    };
  }
  it("hides dense permanent terminal/port labels in real SVG without dropping graph data or tooltip details", () => {
    const { wrapper, option } = render();
    const chart = init(null, undefined, {
      renderer: "svg",
      ssr: true,
      width: 1000,
      height: 460,
    });
    try {
      const current = option();
      expect(current.series[0].data).toHaveLength(22);
      expect(current.series[0].links).toHaveLength(20);
      expect(
        current.series[0].data
          .filter((node: any) => node.label.show)
          .map((node: any) => node.id),
      ).toEqual(["r1", "s1"]);
      expect(
        current.series[0].links.every((edge: any) => !edge.label.show),
      ).toBe(true);
      expect(current.series[0].labelLayout.hideOverlap).toBe(true);
      chart.setOption(current);
      const svg = chart.renderToSVGString();
      expect(svg).not.toContain("discovered-terminal-");
      expect(svg).not.toContain("Gi1/0/");
      expect(
        current.tooltip.formatter({ dataType: "node", data: dense.nodes[2] }),
      ).toContain("discovered-terminal-0");
      expect(
        current.tooltip.formatter({ dataType: "edge", data: dense.edges[0] }),
      ).toContain("Gi1/0/1");
      expect(wrapper.text()).toContain("已精简标签");
    } finally {
      chart.dispose();
      wrapper.unmount();
    }
  });
  it("reveals a selected node or edge and offers an explicit complete-label mode", async () => {
    const { wrapper, option } = render();
    try {
      wrapper
        .findComponent(ChartCanvas)
        .vm.$emit("select", { dataType: "node", data: { id: "terminal-0" } });
      await nextTick();
      expect(
        option().series[0].data.find((node: any) => node.id === "terminal-0")
          .label.show,
      ).toBe(true);
      expect(wrapper.get(".graph-inspector").text()).toContain(
        "discovered-terminal-0",
      );
      wrapper
        .findComponent(ChartCanvas)
        .vm.$emit("select", { dataType: "edge", data: { id: "fdb-1" } });
      await nextTick();
      expect(
        option().series[0].links.find((edge: any) => edge.id === "fdb-1").label
          .show,
      ).toBe(true);
      expect(
        option().series[0].data.find((node: any) => node.id === "terminal-1")
          .label.show,
      ).toBe(true);
      expect(wrapper.get(".graph-inspector").text()).toContain("Gi1/0/2");
      await wrapper.get('select[aria-label="拓扑标签"]').setValue("FULL");
      expect(
        option().series[0].data.every((node: any) => node.label.show),
      ).toBe(true);
      expect(
        option().series[0].links.every((edge: any) => edge.label.show),
      ).toBe(true);
      expect(option().series[0].data).toHaveLength(22);
      expect(option().series[0].links).toHaveLength(20);
    } finally {
      wrapper.unmount();
    }
  });
  it("keeps up to 20 nodes fully labeled by default and automatically compacts a larger scope", async () => {
    const { wrapper, option } = render({
      ...dense,
      nodes: dense.nodes.slice(0, 20),
      edges: dense.edges.slice(0, 18),
    });
    try {
      expect(option().series[0].edgeLabel.show).toBe(true);
      expect(
        option().series[0].data.every((node: any) => node.label.show),
      ).toBe(true);
      await wrapper.setProps({ topology: dense });
      expect(option().series[0].edgeLabel.show).toBe(false);
      await wrapper.get('select[aria-label="拓扑标签"]').setValue("COMPACT");
      await wrapper.setProps({
        topology: {
          ...dense,
          nodes: dense.nodes.slice(0, 20),
          edges: dense.edges.slice(0, 18),
        },
      });
      expect(option().series[0].edgeLabel.show).toBe(false);
    } finally {
      wrapper.unmount();
    }
  });
});
