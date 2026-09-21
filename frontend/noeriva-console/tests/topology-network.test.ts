import { describe, expect, it } from "vitest";
import * as topology from "../src/utils/topology";
import type { Topology } from "../src/services/types";

const graph: Topology = {
  asOf: "2026-09-21T00:00:00Z",
  qualityFlags: [],
  nodes: [
    { id: "router", name: "core-router", type: "ROUTER", health: "HEALTHY" },
    {
      id: "host",
      name: "discovered-host",
      type: "HOST",
      health: "HEALTHY",
      registered: false,
    },
  ],
  edges: [
    {
      id: "observed-1",
      source: "router",
      target: "host",
      kind: "PHYSICAL",
      sourceInterface: "Te1",
      targetInterface: "Te2",
      observedAt: "2026-09-21T00:00:00Z",
      provenance: "LLDP",
    },
    {
      id: "inferred-2",
      source: "host",
      target: "router",
      kind: "L2_INFERRED",
      sourceInterface: "Gi1",
      targetInterface: "Gi2",
      observedAt: "2026-09-21T00:00:00Z",
      provenance: "FDB",
    },
  ],
};

describe("vis-network topology adapter", () => {
  it("uses the Les Miserables ForceAtlas2 preset and lets the caller control fitting", () => {
    const options = topology.topologyNetworkOptions?.(false);
    expect(options?.nodes).toMatchObject({ shape: "dot", size: 16 });
    expect(options?.physics).toMatchObject({
      forceAtlas2Based: {
        gravitationalConstant: -26,
        centralGravity: 0.005,
        springLength: 230,
        springConstant: 0.18,
      },
      maxVelocity: 146,
      solver: "forceAtlas2Based",
      timestep: 0.35,
      stabilization: { iterations: 150, fit: false },
    });
  });
  it("keeps inferred edges distinct, parallel edges separate and graph labels readable", () => {
    const data = topology.topologyNetworkData?.(graph, false, false, "", "");
    expect(data?.nodes[0]).toMatchObject({
      id: "router",
      label: "core-router",
      color: { background: topology.topologyNodeColor(graph.nodes[0]!) },
    });
    expect(data?.nodes[1]?.title).toContain("尚未登记");
    expect(data?.edges.map((edge) => edge.dashes)).toEqual([false, true]);
    expect(data?.edges[0]?.label).toBe("Te1 ↔ Te2");
    expect(data?.edges[0]?.smooth).toMatchObject({ enabled: true });
    expect(data?.edges[1]?.smooth).toMatchObject({ enabled: true });
    expect(data?.nodes.every((node) => !node.fixed)).toBe(true);
  });
  it("compacts terminal labels without losing tooltip evidence, then reveals selected endpoints", () => {
    const compact = topology.topologyNetworkData?.(graph, true, true, "", "");
    expect(compact?.nodes.map((node) => node.label)).toEqual([
      "core-router",
      "",
    ]);
    expect(compact?.edges.every((edge) => edge.label === "")).toBe(true);
    expect(compact?.edges[1]?.title).toContain("非物理直连证明");
    const selected = topology.topologyNetworkData?.(
      graph,
      true,
      true,
      "",
      "inferred-2",
    );
    expect(selected?.nodes[1]?.label).toBe("discovered-host");
    expect(selected?.edges[1]?.label).toBe("Gi1 ↔ Gi2");
    expect(selected?.nodes[0]?.color).toMatchObject({
      background: topology.topologyNodeColor(graph.nodes[0]!, true),
    });
  });
});
