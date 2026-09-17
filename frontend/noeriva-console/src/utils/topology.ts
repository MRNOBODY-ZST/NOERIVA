import type { Topology, TopologyEdge, TopologyNode } from "../services/types";

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
