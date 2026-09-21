import type { Topology, TopologyEdge, TopologyNode } from "../services/types";
import type { Node, Edge, Options } from "vis-network/standalone";
import { stateLabel } from "./format";

const relationNames: Record<string, string> = {
  PHYSICAL: "物理连接",
  LOGICAL: "逻辑关系",
  MANAGEMENT: "管理关系",
  LLDP: "LLDP 邻居",
  CDP: "CDP 邻居",
  L2_INFERRED: "二层接入推断",
  L3_INFERRED: "三层邻居推断",
};
export function topologyRelationLabel(edge: Partial<TopologyEdge>): string {
  return (
    relationNames[edge.kind || ""] ||
    (isInferred(edge) ? "推断关系" : "已观测关系")
  );
}
export function isInferred(edge: Partial<TopologyEdge>) {
  return edge.inferred === true || edge.kind?.endsWith("_INFERRED") === true;
}
export function confidenceLabel(value?: string) {
  return (
    (
      {
        OBSERVED: "协议直接观测",
        CORROBORATED: "多项证据印证",
        UNCONFIRMED: "尚待确认",
        CONFLICT: "证据冲突",
      } as Record<string, string>
    )[value || ""] || "未提供置信说明"
  );
}
export function topologyNodeState(node: TopologyNode) {
  if (node.availability === "OFFLINE") return "OFFLINE";
  if (node.freshness && !["FRESH", "CURRENT"].includes(node.freshness))
    return node.freshness;
  if (node.registered === false || node.confidence === "CONFLICT")
    return "UNKNOWN";
  return node.health;
}
export function topologyNodeColor(node: TopologyNode, dark = false) {
  const state = topologyNodeState(node);
  if (["CRITICAL", "OFFLINE", "ERROR"].includes(state))
    return dark ? "#f07579" : "#c94d55";
  if (state === "WARNING") return dark ? "#f1a65b" : "#d77b2f";
  if (state === "HEALTHY") return dark ? "#65c79a" : "#32946a";
  return dark ? "#e0cb6c" : "#c5a335";
}
export function topologyGroups(topology: Topology) {
  const ids = new Set<number>(topology.vlans?.map((vlan) => vlan.vlanId) || []);
  topology.nodes.forEach((node) => node.vlanIds?.forEach((id) => ids.add(id)));
  topology.edges.forEach((edge) => edge.vlanIds?.forEach((id) => ids.add(id)));
  return [...ids]
    .filter((id) => id >= 1 && id <= 4094)
    .sort((a, b) => a - b)
    .map((vlanId) => ({
      vlanId,
      name:
        topology.vlans?.find((vlan) => vlan.vlanId === vlanId)?.name ||
        `VLAN ${vlanId}`,
      nodeCount: topology.nodes.filter((node) => node.vlanIds?.includes(vlanId))
        .length,
    }));
}
export function filterTopology(
  topology: Topology,
  l2: boolean,
  l3: boolean,
  vlan: string,
) {
  const allowed = topology.edges.filter(
    (edge) => !isInferred(edge) || (edge.kind === "L3_INFERRED" ? l3 : l2),
  );
  if (!vlan) return { nodes: topology.nodes, edges: allowed };
  const vlanId = Number(vlan);
  const edges = allowed.filter((edge) =>
    vlan === "unknown" ? !edge.vlanIds?.length : edge.vlanIds?.includes(vlanId),
  );
  const endpoints = new Set(
    edges.flatMap((edge) => [edge.source, edge.target]),
  );
  const nodes = topology.nodes.filter(
    (node) =>
      endpoints.has(node.id) ||
      (vlan === "unknown"
        ? !node.vlanIds?.length
        : node.vlanIds?.includes(vlanId)),
  );
  return { nodes, edges };
}
function portLabel(value: string | undefined) {
  const port = value?.trim();
  return port &&
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      port,
    ) &&
    !/^observed-/i.test(port) &&
    !/^(unknown|n\/?a|null|undefined|-)$/i.test(port)
    ? port
    : "";
}
export function topologyEdgeLabel(edge: Partial<TopologyEdge>): string {
  const source = portLabel(edge.sourceInterface);
  const target = portLabel(edge.targetInterface);
  if (source || target)
    return `${source || "本端口未知"} ↔ ${target || "对端口未知"}`;
  return topologyRelationLabel(edge);
}

export function topologyLinks(edges: readonly TopologyEdge[]) {
  const groups = new Map<string, TopologyEdge[]>();
  for (const edge of edges) {
    const pair = JSON.stringify([edge.source, edge.target].sort());
    const group = groups.get(pair) || [];
    group.push(edge);
    groups.set(pair, group);
  }
  const curves = new Map<string, number>();
  for (const group of groups.values()) {
    group.sort(
      (a, b) =>
        topologyEdgeLabel(a).localeCompare(topologyEdgeLabel(b), "en", {
          numeric: true,
        }) || a.id.localeCompare(b.id),
    );
    const step = Math.min(0.36, 0.9 / Math.max(1, group.length - 1));
    group.forEach((edge, index) => {
      const orientation = edge.source <= edge.target ? 1 : -1;
      curves.set(
        edge.id,
        (index - (group.length - 1) / 2) * step * orientation,
      );
    });
  }
  return edges.map((edge) => ({
    ...edge,
    name: topologyEdgeLabel(edge),
    lineStyle: {
      curveness: curves.get(edge.id) || 0,
      type: isInferred(edge) ? "dashed" : "solid",
      opacity: isInferred(edge) ? 0.72 : 1,
    },
  }));
}

// ForceAtlas2 parameters from the official vis-network Les Miserables example.
export function topologyNetworkOptions(dark: boolean): Options {
  return {
    layout: { randomSeed: 42 },
    nodes: {
      shape: "dot",
      size: 16,
      borderWidth: 0,
      borderWidthSelected: 2,
      font: {
        size: 13,
        face: "system-ui, sans-serif",
        color: dark ? "#dbe6f2" : "#33485e",
        strokeWidth: 3,
        strokeColor: dark ? "#14202e" : "#ffffff",
      },
    },
    edges: {
      width: 1.2,
      color: {
        color: dark ? "#7c93ab" : "#8294a8",
        highlight: dark ? "#c3d5eb" : "#435d79",
        hover: dark ? "#c3d5eb" : "#435d79",
        inherit: false,
      },
      font: {
        size: 10,
        face: "system-ui, sans-serif",
        color: dark ? "#c0cede" : "#52677e",
        strokeWidth: 4,
        strokeColor: dark ? "#14202e" : "#ffffff",
        align: "middle",
      },
      selectionWidth: 1.8,
    },
    interaction: {
      hover: true,
      tooltipDelay: 180,
      dragNodes: true,
      dragView: true,
      zoomView: true,
      selectConnectedEdges: false,
      keyboard: { enabled: true, bindToWindow: false, autoFocus: false },
    },
    physics: {
      forceAtlas2Based: {
        gravitationalConstant: -26,
        centralGravity: 0.005,
        springLength: 230,
        springConstant: 0.18,
      },
      maxVelocity: 146,
      solver: "forceAtlas2Based",
      timestep: 0.35,
      // Fit is controlled by the component so late stabilization cannot undo manual navigation.
      stabilization: { iterations: 150, fit: false },
    },
  };
}

export function topologyNetworkData(
  graph: Pick<Topology, "nodes" | "edges">,
  dark: boolean,
  compact: boolean,
  selectedId: string,
  selectedEdgeId: string,
): { nodes: Node[]; edges: Edge[] } {
  const selectedEdge = graph.edges.find((edge) => edge.id === selectedEdgeId);
  return {
    nodes: graph.nodes.map((node) => {
      const color = topologyNodeColor(node, dark);
      const showLabel =
        !compact ||
        node.registered !== false ||
        node.id === selectedId ||
        node.id === selectedEdge?.source ||
        node.id === selectedEdge?.target;
      return {
        id: node.id,
        label: showLabel ? node.name : "",
        title: `${node.name}\n${stateLabel(node.type)} · ${node.registered === false ? "已发现 · 尚未登记" : stateLabel(topologyNodeState(node))}`,
        color: {
          background: color,
          border: dark ? "#e2edfa" : "#263e58",
          highlight: {
            background: color,
            border: dark ? "#e2edfa" : "#263e58",
          },
          hover: { background: color, border: dark ? "#e2edfa" : "#263e58" },
        },
      };
    }),
    edges: topologyLinks(graph.edges).map((edge) => {
      const curve = edge.lineStyle.curveness;
      return {
        id: edge.id,
        from: edge.source,
        to: edge.target,
        label:
          !compact || edge.id === selectedEdgeId ? topologyEdgeLabel(edge) : "",
        title: `${topologyEdgeLabel(edge)}\n${topologyRelationLabel(edge)}${isInferred(edge) ? " · 非物理直连证明" : ""}`,
        dashes: isInferred(edge),
        color: { opacity: isInferred(edge) ? 0.5 : 0.8 },
        smooth: curve
          ? {
              enabled: true,
              type: curve > 0 ? "curvedCW" : "curvedCCW",
              roundness: Math.abs(curve),
            }
          : { enabled: true, type: "dynamic", roundness: 0.5 },
      };
    }),
  };
}
