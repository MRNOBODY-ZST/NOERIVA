<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  DataSet,
  Network,
  type Node,
  type Edge,
  type IdType,
} from "vis-network/standalone";
import type { Topology } from "../services/types";
import { topologyNetworkData, topologyNetworkOptions } from "../utils/topology";

const props = defineProps<{
  graph: Pick<Topology, "nodes" | "edges">;
  dark: boolean;
  compact: boolean;
  selectedId: string;
  selectedEdgeId: string;
  physics: boolean;
  reducedMotion: boolean;
  label: string;
  active: boolean;
}>();
const emit = defineEmits<{
  select: [payload: { dataType: "node" | "edge"; data: { id: string } }];
  open: [payload: { dataType: "node" | "edge"; data: { id: string } }];
  fitStatus: [status: string];
}>();
const target = ref<HTMLDivElement>();
const fitState = ref("idle");
const initialFitState = ref("pending");
const nodes = new DataSet<Node>();
const edges = new DataSet<Edge>();
let network: Network | undefined;
let manualView = false;
let initialFitPending = true;
let revision = 0;
let graphStructure = "";
let currentDark = props.dark;
let hiddenView:
  { position: { x: number; y: number }; scale: number } | undefined;
const renderedNodes = new Map<IdType, string>();
const renderedEdges = new Map<IdType, string>();
function setFitState(status: string) {
  fitState.value = status;
  emit("fitStatus", status);
}
function userInteraction() {
  manualView = true;
  initialFitPending = false;
  initialFitState.value = "cancelled";
  revision++;
}
const frame = () =>
  new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
async function fitNetwork() {
  const currentRevision = ++revision;
  const current = network;
  if (!current || !nodes.length || !target.value?.clientWidth) return;
  setFitState("fitting");
  current.fit({ animation: false });
  // Include labels and correct for their zoom-dependent size using the public canvas API.
  for (let attempt = 0; attempt < 3; attempt++) {
    await frame();
    if (network !== current || revision !== currentRevision || !target.value)
      return;
    current.redraw();
    const boxes = nodes.getIds().map((id) => current.getBoundingBox(id));
    const bounds = {
      left: Math.min(...boxes.map((box) => box.left)),
      right: Math.max(...boxes.map((box) => box.right)),
      top: Math.min(...boxes.map((box) => box.top)),
      bottom: Math.max(...boxes.map((box) => box.bottom)),
    };
    const first = current.canvasToDOM({ x: bounds.left, y: bounds.top });
    const last = current.canvasToDOM({ x: bounds.right, y: bounds.bottom });
    const width = target.value.clientWidth,
      height = target.value.clientHeight;
    const padding = Math.min(32, width / 10, height / 10);
    if (
      first.x >= padding &&
      first.y >= padding &&
      last.x <= width - padding &&
      last.y <= height - padding
    ) {
      setFitState("done");
      return;
    }
    const ratio = Math.min(
      (width - 2 * padding) / (last.x - first.x),
      (height - 2 * padding) / (last.y - first.y),
    );
    current.moveTo({
      position: {
        x: (bounds.left + bounds.right) / 2,
        y: (bounds.top + bounds.bottom) / 2,
      },
      scale: Math.max(0.005, current.getScale() * Math.min(1, ratio) * 0.96),
      animation: false,
    });
  }
  setFitState("limited");
}
function fit() {
  userInteraction();
  return fitNetwork();
}
function zoom(factor: number) {
  userInteraction();
  if (!network) return;
  network.moveTo({
    scale: Math.max(0.005, Math.min(3, network.getScale() * factor)),
    animation: false,
  });
}
async function finishInitialFit() {
  if (!initialFitPending || manualView) return;
  initialFitPending = false;
  initialFitState.value = "fitting";
  await fitNetwork();
  if (!manualView) initialFitState.value = fitState.value;
}
function select(event: { nodes: IdType[]; edges: IdType[] }, open = false) {
  const id = event.nodes[0] ?? event.edges[0];
  if (id === undefined) return;
  const payload = {
    dataType: event.nodes.length ? ("node" as const) : ("edge" as const),
    data: { id: String(id) },
  };
  if (open) emit("open", payload);
  else emit("select", payload);
}
function syncSelection() {
  if (!network) return;
  if (props.selectedId && nodes.get(props.selectedId))
    network.selectNodes([props.selectedId], false);
  else if (props.selectedEdgeId && edges.get(props.selectedEdgeId))
    network.selectEdges([props.selectedEdgeId]);
  else network.unselectAll();
}
function changedItems<T extends Node | Edge>(
  items: T[],
  previous: Map<IdType, string>,
) {
  const updates = items.filter(
    (item) => previous.get(item.id!) !== JSON.stringify(item),
  );
  for (const item of updates) previous.set(item.id!, JSON.stringify(item));
  return updates;
}
function updateData() {
  const data = topologyNetworkData(
    props.graph,
    props.dark,
    props.compact,
    props.selectedId,
    props.selectedEdgeId,
  );
  const nextStructure = JSON.stringify({
    nodes: data.nodes.map((node) => node.id),
    edges: data.edges.map((edge) => [edge.id, edge.from, edge.to]),
  });
  const structureChanged = nextStructure !== graphStructure;
  graphStructure = nextStructure;
  const nodeIds = new Set(data.nodes.map((node) => node.id));
  const edgeIds = new Set(data.edges.map((edge) => edge.id));
  const deletedEdges = edges.getIds().filter((id) => !edgeIds.has(id));
  const deletedNodes = nodes.getIds().filter((id) => !nodeIds.has(id));
  edges.remove(deletedEdges);
  nodes.remove(deletedNodes);
  deletedEdges.forEach((id) => renderedEdges.delete(id));
  deletedNodes.forEach((id) => renderedNodes.delete(id));
  const changedNodes = changedItems(data.nodes, renderedNodes);
  const changedEdges = changedItems(data.edges, renderedEdges);
  if (changedNodes.length) nodes.update(changedNodes);
  if (changedEdges.length) edges.update(changedEdges);
  if (network && currentDark !== props.dark) {
    const { nodes: nodeOptions, edges: edgeOptions } = topologyNetworkOptions(
      props.dark,
    );
    network.setOptions({ nodes: nodeOptions, edges: edgeOptions });
    currentDark = props.dark;
  }
  syncSelection();
  if (!network) return;
  if (structureChanged) {
    if (!manualView) {
      initialFitPending = true;
      initialFitState.value = "pending";
    }
    if (props.physics) {
      network.setOptions({ physics: { enabled: true } });
      network.stabilize(150);
    } else {
      network.setOptions({ physics: { enabled: false } });
      void finishInitialFit();
    }
  } else if (changedNodes.length || changedEdges.length) {
    // Label, status and selection updates must not restart an already positioned graph.
    network.stopSimulation();
  }
}
defineExpose({ fit, zoom });
onMounted(() => {
  if (!target.value) return;
  updateData();
  network = new Network(
    target.value,
    { nodes, edges },
    topologyNetworkOptions(props.dark),
  );
  network.on("click", (event) => select(event));
  network.on("doubleClick", (event) => select(event, true));
  network.on("dragStart", userInteraction);
  network.on("zoom", userInteraction);
  network.on("stabilizationIterationsDone", () => {
    if (props.reducedMotion || !props.physics) {
      network?.setOptions({ physics: { enabled: false } });
      void finishInitialFit();
    }
  });
  network.on("stabilized", () => void finishInitialFit());
  syncSelection();
});
watch(
  () => [
    props.graph,
    props.dark,
    props.compact,
    props.selectedId,
    props.selectedEdgeId,
  ],
  updateData,
);
watch(
  () => [props.physics, props.reducedMotion],
  () => {
    if (!network) return;
    network.setOptions({
      physics: { enabled: props.physics && !props.reducedMotion },
    });
    if (props.physics && !props.reducedMotion) network.startSimulation();
    else void finishInitialFit();
  },
);
watch(
  () => props.active,
  async (active) => {
    if (!network) return;
    if (active === false) {
      hiddenView = {
        position: network.getViewPosition(),
        scale: network.getScale(),
      };
      return;
    }
    await nextTick();
    if (!network) return;
    network.setSize("100%", "100%");
    if (hiddenView) network.moveTo({ ...hiddenView, animation: false });
    network.redraw();
    if (initialFitPending) void finishInitialFit();
  },
);
onBeforeUnmount(() => {
  revision++;
  network?.destroy();
  network = undefined;
});
</script>
<template>
  <div
    ref="target"
    class="topology-canvas"
    :class="{ 'topology-canvas-dark': dark }"
    role="img"
    tabindex="0"
    :aria-label="label"
    :data-graph-fit="fitState"
    :data-initial-graph-fit="initialFitState"
    @pointerdown="userInteraction"
    @wheel.passive="userInteraction"
    @keydown="userInteraction"
  />
</template>
<style scoped>
.topology-canvas {
  width: 100%;
  height: 520px;
  min-width: 0;
  background: #fff;
  border-radius: 8px;
}
.topology-canvas-dark {
  background: #14202e;
}
.topology-canvas:focus-visible {
  outline: 2px solid var(--accent, #6b91bf);
  outline-offset: 2px;
}
.topology-canvas :deep(.vis-tooltip) {
  max-width: 340px;
  white-space: pre-line;
  padding: 9px 12px;
  border: 1px solid #aab9c9;
  border-radius: 6px;
  background: #fff;
  color: #24384f;
  font:
    12px/1.65 system-ui,
    sans-serif;
  box-shadow: 0 4px 18px #17263815;
}
.topology-canvas-dark :deep(.vis-tooltip) {
  background: #1b2938;
  color: #e4edf7;
  border-color: #667d98;
}
@media (max-width: 700px) {
  .topology-canvas {
    height: 420px;
  }
}
</style>
