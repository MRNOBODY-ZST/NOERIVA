import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DataSet, Node, Edge, Options } from "vis-network/standalone";
import type { Topology } from "../src/services/types";
import TopologyCanvas from "../src/components/TopologyCanvas.vue";

const instances = vi.hoisted(() => [] as any[]);
vi.mock("vis-network/standalone", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("vis-network/standalone")>();
  return {
    ...actual,
    // jsdom has no canvas renderer; keep real DataSets and isolate the network integration boundary.
    Network: class {
      events = new Map<string, (...args: any[]) => void>();
      fit = vi.fn();
      destroy = vi.fn();
      stopSimulation = vi.fn();
      startSimulation = vi.fn();
      stabilize = vi.fn();
      setOptions = vi.fn();
      moveTo = vi.fn();
      redraw = vi.fn();
      selectNodes = vi.fn();
      selectEdges = vi.fn();
      unselectAll = vi.fn();
      getScale = () => 1;
      getViewPosition = () => ({ x: 20, y: 30 });
      setSize = vi.fn();
      getBoundingBox = () => ({ left: -50, top: -50, right: 50, bottom: 50 });
      canvasToDOM = ({ x, y }: { x: number; y: number }) => ({
        x: x + 400,
        y: y + 260,
      });
      on = (name: string, callback: (...args: any[]) => void) =>
        this.events.set(name, callback);
      constructor(
        _target: HTMLElement,
        public data: { nodes: DataSet<Node>; edges: DataSet<Edge> },
        public options: Options,
      ) {
        instances.push(this);
      }
    },
  };
});
const graph: Topology = {
  asOf: "2026-09-21T00:00:00Z",
  qualityFlags: [],
  nodes: [
    { id: "a", name: "router", type: "ROUTER", health: "HEALTHY" },
    { id: "b", name: "switch", type: "SWITCH", health: "WARNING" },
  ],
  edges: [
    {
      id: "ab",
      source: "a",
      target: "b",
      kind: "PHYSICAL",
      sourceInterface: "Te1",
      targetInterface: "Te2",
      provenance: "LLDP",
      observedAt: "2026-09-21T00:00:00Z",
    },
  ],
};
function render(reducedMotion = false) {
  const wrapper = mount(TopologyCanvas, {
    props: {
      graph,
      active: true,
      dark: false,
      compact: false,
      selectedId: "",
      selectedEdgeId: "",
      physics: true,
      reducedMotion,
      label: "设备连接图",
    },
  });
  return { wrapper, network: instances.at(-1)! };
}
beforeEach(() => {
  instances.length = 0;
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
    callback(0);
    return 1;
  });
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(800);
  vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(520);
});
afterEach(() => vi.restoreAllMocks());

describe("topology canvas lifecycle", () => {
  it("forwards node, edge and double-click identities and prioritizes nodes over connected edges", () => {
    const { wrapper, network } = render();
    try {
      network.events.get("click")({ nodes: ["a"], edges: ["ab"] });
      network.events.get("click")({ nodes: [], edges: ["ab"] });
      network.events.get("doubleClick")({ nodes: ["b"], edges: [] });
      expect(wrapper.emitted("select")).toEqual([
        [{ dataType: "node", data: { id: "a" } }],
        [{ dataType: "edge", data: { id: "ab" } }],
      ]);
      expect(wrapper.emitted("open")).toEqual([
        [{ dataType: "node", data: { id: "b" } }],
      ]);
    } finally {
      wrapper.unmount();
    }
    expect(network.destroy).toHaveBeenCalledOnce();
  });
  it("fits once after stabilization and does not undo user zoom on refresh or appearance changes", async () => {
    const { wrapper, network } = render();
    try {
      network.events.get("stabilized")();
      await flushPromises();
      expect(network.fit).toHaveBeenCalledOnce();
      expect(wrapper.attributes("data-initial-graph-fit")).toBe("done");
      wrapper.vm.zoom(1.2);
      const viewport = network.moveTo.mock.calls.at(-1);
      const updates = vi.spyOn(network.data.nodes, "update");
      await wrapper.setProps({
        graph: {
          ...graph,
          edges: graph.edges.map((edge) => ({
            ...edge,
            observedAt: "2026-09-22T00:00:00Z",
          })),
        },
      });
      expect(updates).not.toHaveBeenCalled();
      await wrapper.setProps({ dark: true, selectedId: "a" });
      network.events.get("stabilized")();
      await flushPromises();
      expect(instances).toHaveLength(1);
      expect(network.fit).toHaveBeenCalledOnce();
      expect(network.moveTo.mock.calls.at(-1)).toBe(viewport);
      expect(wrapper.attributes("data-initial-graph-fit")).toBe("cancelled");
    } finally {
      wrapper.unmount();
    }
  });
  it("cancels delayed automatic fitting when the user starts dragging", async () => {
    const { wrapper, network } = render();
    try {
      await wrapper.trigger("pointerdown");
      network.events.get("stabilized")();
      await flushPromises();
      expect(network.fit).not.toHaveBeenCalled();
      expect(wrapper.attributes("data-initial-graph-fit")).toBe("cancelled");
    } finally {
      wrapper.unmount();
    }
  });
  it("freezes physics without fixing node coordinates and stabilizes reduced motion without visible simulation", async () => {
    const { wrapper, network } = render(true);
    try {
      network.events.get("stabilizationIterationsDone")();
      await flushPromises();
      expect(network.setOptions).toHaveBeenCalledWith({
        physics: { enabled: false },
      });
      expect(network.data.nodes.get().every((node: Node) => !node.fixed)).toBe(
        true,
      );
      expect(wrapper.attributes("data-initial-graph-fit")).toBe("done");
      await wrapper.setProps({ reducedMotion: false, physics: false });
      expect(network.setOptions).toHaveBeenLastCalledWith({
        physics: { enabled: false },
      });
    } finally {
      wrapper.unmount();
    }
  });
  it("reconciles removed nodes and edges in the existing datasets", async () => {
    const { wrapper, network } = render();
    try {
      const nodeData = network.data.nodes,
        edgeData = network.data.edges;
      await wrapper.setProps({
        graph: { nodes: [graph.nodes[0]!], edges: [] },
      });
      expect(network.data.nodes).toBe(nodeData);
      expect(network.data.edges).toBe(edgeData);
      expect(nodeData.getIds()).toEqual(["a"]);
      expect(edgeData.getIds()).toEqual([]);
      expect(network.stabilize).toHaveBeenCalledWith(150);
    } finally {
      wrapper.unmount();
    }
  });
  it("keeps a frozen graph frozen when its live scope adds a device", async () => {
    const { wrapper, network } = render();
    try {
      await wrapper.setProps({ physics: false });
      network.setOptions.mockClear();
      await wrapper.setProps({
        graph: {
          ...graph,
          nodes: [
            ...graph.nodes,
            { id: "c", name: "new-host", type: "HOST", health: "UNKNOWN" },
          ],
        },
      });
      expect(network.setOptions).not.toHaveBeenCalledWith({
        physics: { enabled: true },
      });
      expect(network.stabilize).not.toHaveBeenCalled();
      expect(network.data.nodes.getIds()).toEqual(["a", "b", "c"]);
    } finally {
      wrapper.unmount();
    }
  });

  it("restores canvas size and manual view immediately when returning from the list", async () => {
    const { wrapper, network } = render();
    try {
      await wrapper.setProps({ active: false });
      await wrapper.setProps({ active: true });
      await flushPromises();
      expect(network.setSize).toHaveBeenCalledWith("100%", "100%");
      expect(network.moveTo).toHaveBeenCalledWith({
        position: { x: 20, y: 30 },
        scale: 1,
        animation: false,
      });
    } finally {
      wrapper.unmount();
    }
  });
});
