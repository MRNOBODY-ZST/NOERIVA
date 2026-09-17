<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { useRouter } from "vue-router";
import {
  Maximize2,
  MousePointer2,
  List,
  ArrowUpRight,
  Network,
  Plus,
  Minus,
} from "@lucide/vue";
import type { EChartsCoreOption } from "echarts/core";
import type { Topology, TopologyNode, TopologyEdge } from "../services/types";
import { usePreferencesStore } from "../stores/preferences";
import { formatTime, stateLabel } from "../utils/format";
import {
  topologyEdgeLabel,
  topologyLinks,
  topologyNodeColor,
  topologyNodeState,
  topologyRelationLabel,
  topologyGroups,
  filterTopology,
  isInferred,
  confidenceLabel,
} from "../utils/topology";
import ChartCanvas from "./ChartCanvas.vue";
import StatusBadge from "./StatusBadge.vue";
import TopologyEvidenceList from "./TopologyEvidenceList.vue";
const props = defineProps<{ topology: Topology }>();
const prefs = usePreferencesStore(),
  router = useRouter();
const selectedId = ref(""),
  selectedEdgeId = ref("");
const list = ref(false),
  physics = ref(true),
  l2 = ref(true),
  l3 = ref(false),
  grouped = ref(false),
  vlan = ref("");
const labelMode = ref("AUTO");
const chart = ref<InstanceType<typeof ChartCanvas>>();
const fitStatus = ref("idle");
async function togglePhysics() {
  physics.value = !physics.value;
  await nextTick();
  if (!physics.value) await chart.value?.fit?.();
}
const reducedMotion = computed(
  () =>
    prefs.reducedMotion ||
    window.matchMedia("(prefers-reduced-motion: reduce)").matches,
);
const groups = computed(() => topologyGroups(props.topology));
const visible = computed(() =>
  filterTopology(
    props.topology,
    l2.value,
    l3.value,
    grouped.value ? vlan.value : "",
  ),
);
const compactLabels = computed(
  () =>
    labelMode.value === "COMPACT" ||
    (labelMode.value === "AUTO" && visible.value.nodes.length > 20),
);
const nodeColors = computed(() => [
  ...new Set(
    visible.value.nodes.map((node) => topologyNodeColor(node, prefs.dark)),
  ),
]);
const selected = computed(() =>
  visible.value.nodes.find((node) => node.id === selectedId.value),
);
const selectedEdge = computed(() =>
  visible.value.edges.find((edge) => edge.id === selectedEdgeId.value),
);
const relatedEdges = computed(() =>
  visible.value.edges.filter(
    (edge) =>
      edge.source === selectedId.value || edge.target === selectedId.value,
  ),
);
const physicalCount = computed(
  () => visible.value.edges.filter((edge) => !isInferred(edge)).length,
);
watch(groups, (items) => {
  if (
    vlan.value &&
    vlan.value !== "unknown" &&
    !items.some((item) => String(item.vlanId) === vlan.value)
  )
    vlan.value = "";
});
function selectNode(node: TopologyNode, open = false) {
  selectedId.value = node.id;
  selectedEdgeId.value = "";
  if (open && node.registered !== false)
    void router.push(`/devices/${node.id}`);
}
function selectEdge(edge: TopologyEdge) {
  selectedEdgeId.value = edge.id;
  selectedId.value = "";
}
function select(payload: unknown, open = false) {
  const event = payload as { dataType?: string; data?: { id?: string } };
  if (event.dataType === "edge") {
    const edge = visible.value.edges.find((item) => item.id === event.data?.id);
    if (edge) selectEdge(edge);
  } else if (event.dataType === "node") {
    const node = visible.value.nodes.find((item) => item.id === event.data?.id);
    if (node) selectNode(node, open);
  }
}
const nodeName = (id: string) =>
  props.topology.nodes.find((node) => node.id === id)?.name ||
  "当前范围外的设备";
// Only graph structure/state changes restart layout; observation timestamps stay live in the inspector.
const graphSignature = computed(() =>
  JSON.stringify({
    nodes: visible.value.nodes.map(({ evidence: _evidence, ...node }) => node),
    edges: visible.value.edges.map(
      ({ observedAt: _time, evidence: _evidence, ...edge }) => edge,
    ),
  }),
);
const graphData = computed(() => JSON.parse(graphSignature.value) as Topology);
const shapeTypes = [
  { type: "ROUTER", label: "路由器", symbol: "circle", points: "" },
  {
    type: "SWITCH",
    label: "交换机",
    symbol: "path://M32 0L62 23L51 59H13L2 23Z",
    points: "16,0 31,11.5 25.5,29.5 6.5,29.5 1,11.5",
  },
  {
    type: "BMC",
    label: "带外管理",
    symbol: "triangle",
    points: "16,1 31,29 1,29",
  },
  {
    type: "HOST",
    label: "主机 / 终端",
    symbol: "rect",
    points: "1,1 31,1 31,31 1,31",
  },
  {
    type: "FIREWALL",
    label: "防火墙",
    symbol: "diamond",
    points: "16,0 32,16 16,32 0,16",
  },
  {
    type: "UNKNOWN",
    label: "未分类终端",
    symbol: "roundRect",
    points: "1,1 31,1 31,31 1,31",
  },
];
const nodeShape = (type: string) =>
  shapeTypes.find((shape) => shape.type === type) || shapeTypes[5]!;
const legendTypes = computed(() =>
  shapeTypes.filter((shape) =>
    visible.value.nodes.some(
      (node) => nodeShape(node.type).type === shape.type,
    ),
  ),
);
const colorLegend = computed(() =>
  [
    { state: "CRITICAL", label: "严重 / 离线" },
    { state: "WARNING", label: "警告" },
    { state: "UNKNOWN", label: "未知 / 过期 / 未监测" },
    { state: "HEALTHY", label: "正常" },
  ].map((item) => ({
    ...item,
    color: topologyNodeColor(
      { id: "", name: "", type: "", health: item.state },
      prefs.dark,
    ),
  })),
);
const option = computed<EChartsCoreOption>(() => ({
  animation: false,
  tooltip: {
    trigger: "item",
    hideDelay: 0,
    renderMode: "richText",
    backgroundColor: prefs.dark ? "#1b2938" : "#fff",
    borderColor: prefs.dark ? "#667d98" : "#8091a5",
    textStyle: { color: prefs.dark ? "#e4edf7" : "#172538" },
    formatter: (value: unknown) => {
      const item = value as {
        dataType: string;
        data: TopologyNode & TopologyEdge;
      };
      return item.dataType === "node"
        ? `${item.data.name}\n${item.data.registered === false ? "已发现 · 尚未登记" : stateLabel(topologyNodeState(item.data))}`
        : `${topologyEdgeLabel(item.data)}\n${topologyRelationLabel(item.data)}${isInferred(item.data) ? " · 非物理直连证明" : ""}`;
    },
  },
  series: [
    {
      type: "graph",
      layout: reducedMotion.value || !physics.value ? "circular" : "force",
      roam: true,
      draggable: true,
      force: {
        repulsion: 700,
        gravity: 0.06,
        edgeLength: [120, 200],
        layoutAnimation: physics.value && !reducedMotion.value,
      },
      scaleLimit: { min: 0.05, max: 3 },
      symbolSize: 44,
      label: {
        show: true,
        position: "bottom",
        color: prefs.dark ? "#c8d5e5" : "#33485e",
        fontSize: 12,
        distance: 9,
      },
      labelLayout: { hideOverlap: compactLabels.value },
      lineStyle: {
        color: prefs.dark ? "#8598af" : "#74879b",
        opacity: 1,
        width: 1.5,
        curveness: 0,
      },
      edgeLabel: {
        show: !compactLabels.value,
        fontSize: 10,
        color: prefs.dark ? "#b0bfd0" : "#536579",
        formatter: (value: unknown) =>
          topologyEdgeLabel((value as { data: TopologyEdge }).data),
      },
      emphasis: {
        focus: compactLabels.value ? "self" : "adjacency",
        label: { show: true },
        edgeLabel: { show: true },
        lineStyle: { width: 3 },
        itemStyle: { borderWidth: 0 },
      },
      data: graphData.value.nodes.map((node) => ({
        ...node,
        fixed: !physics.value,
        symbol: nodeShape(node.type).symbol,
        symbolKeepAspect: true,
        label: {
          show:
            !compactLabels.value ||
            node.registered !== false ||
            node.id === selectedId.value ||
            node.id === selectedEdge.value?.source ||
            node.id === selectedEdge.value?.target,
          formatter: `${node.name}\n{state|${node.registered === false ? "发现终端 · 尚未登记" : `${stateLabel(node.type)} · ${stateLabel(topologyNodeState(node))}`}}`,
          rich: {
            state: {
              fontSize: 11,
              color: prefs.dark ? "#a1b2c5" : "#607187",
              lineHeight: 20,
            },
          },
        },
        itemStyle: {
          color: topologyNodeColor(node, prefs.dark),
          borderWidth: 0,
        },
        symbolSize: node.type === "ROUTER" ? 48 : 44,
      })),
      links: topologyLinks(graphData.value.edges).map((edge) => ({
        ...edge,
        label: {
          show: !compactLabels.value || edge.id === selectedEdgeId.value,
        },
      })),
    },
  ],
}));
</script>
<template>
  <div class="graph-toolbar">
    <span class="small muted"
      ><Network aria-hidden="true" :size="14" style="display: inline" />
      {{ visible.nodes.length }} 个节点 · {{ physicalCount }} 条观测 ·
      {{ visible.edges.length - physicalCount }} 条推断</span
    >
    <div class="inline-actions" style="margin-left: auto">
      <button
        class="btn small-btn"
        :disabled="list || reducedMotion"
        :aria-pressed="physics && !reducedMotion"
        :title="
          reducedMotion
            ? '已遵循减少动态效果偏好，使用静态布局'
            : physics
              ? '切换到静态圆形布局，停止物理模拟'
              : '启用力导向布局'
        "
        @click="togglePhysics"
      >
        {{ reducedMotion ? "静态布局" : physics ? "圆形排列" : "启用物理" }}
      </button>
      <button
        class="icon-btn"
        aria-label="放大连接图"
        :disabled="list"
        @click="chart?.zoom(1.2)"
      >
        <Plus aria-hidden="true" :size="15" />
      </button>
      <button
        class="icon-btn"
        aria-label="缩小连接图"
        :disabled="list"
        @click="chart?.zoom(0.8)"
      >
        <Minus aria-hidden="true" :size="15" />
      </button>
      <button class="btn small-btn" :disabled="list" @click="chart?.fit()">
        <Maximize2 aria-hidden="true" :size="14" />适应视图
      </button>
      <button class="btn small-btn" :aria-pressed="list" @click="list = !list">
        <List aria-hidden="true" :size="14" />{{
          list ? "图形视图" : "列表替代"
        }}
      </button>
    </div>
  </div>
  <div class="graph-filters" aria-label="拓扑关系与分组">
    <span class="small">显示当前范围内的物理连接</span>
    <label><input v-model="l2" type="checkbox" />终端接入推断</label>
    <label><input v-model="l3" type="checkbox" />三层邻居推断</label>
    <select v-model="labelMode" aria-label="拓扑标签" :disabled="list">
      <option value="AUTO">标签 · 自动</option>
      <option value="COMPACT">标签 · 精简</option>
      <option value="FULL">标签 · 完整</option>
    </select>
    <button
      class="btn small-btn"
      :aria-pressed="grouped"
      @click="grouped = !grouped"
    >
      按 VLAN 分组
    </button>
  </div>
  <div v-if="grouped" class="graph-vlan-groups" aria-label="VLAN 分组">
    <button
      class="btn small-btn"
      :aria-pressed="vlan === ''"
      @click="vlan = ''"
    >
      全部分组
    </button>
    <button
      v-for="group in groups"
      :key="group.vlanId"
      class="btn small-btn"
      :aria-pressed="vlan === String(group.vlanId)"
      @click="vlan = String(group.vlanId)"
    >
      {{
        group.name === `VLAN ${group.vlanId}`
          ? group.name
          : `VLAN ${group.vlanId} · ${group.name}`
      }}<span class="muted">{{ group.nodeCount }} 节点</span>
    </button>
    <button
      class="btn small-btn"
      :aria-pressed="vlan === 'unknown'"
      @click="vlan = 'unknown'"
    >
      VLAN 未知
    </button>
    <p class="small muted">
      按当前快照的 VLAN 证据分组，多 VLAN 设备可属于多个组；未回传 VLAN
      的关系归入未知。
    </p>
  </div>
  <div class="graph-legend" aria-label="拓扑图例">
    <span v-for="shape in legendTypes" :key="shape.type"
      ><svg viewBox="0 0 32 32" width="17" height="17" aria-hidden="true">
        <circle v-if="shape.symbol === 'circle'" cx="16" cy="16" r="14" />
        <polygon v-else :points="shape.points" /></svg
      >{{ shape.label }}</span
    >
    <span v-for="item in colorLegend" :key="item.state"
      ><i
        class="graph-color-dot"
        :style="{ backgroundColor: item.color }"
        aria-hidden="true"
      />{{ item.label }}</span
    >
    <span><i class="graph-line-key" aria-hidden="true" />协议观测</span
    ><span
      ><i class="graph-line-key inferred" aria-hidden="true" />推断关系</span
    >
  </div>
  <p class="graph-scope-note">
    实线表示已观测关系；虚线根据 ARP / DHCP / FDB
    等证据推断接入或邻居关系，可能经过下游交换机，不等于物理直连。颜色表示当前状态，发现终端尚无健康监测。
  </p>
  <p v-if="compactLabels && !list" class="graph-scope-note">
    已精简标签：保留受管设备名称，收起端口文字与发现终端名称；悬停或选择可查看完整信息。自动模式在超过
    20 个节点时生效，也可切换完整标签。
  </p>
  <p v-if="!visible.edges.length" class="graph-scope-note">
    当前范围没有符合筛选的连线；保留已知节点，不补造连接。
  </p>
  <p v-if="(!physics || reducedMotion) && !list" class="graph-scope-note">
    当前使用静态圆形布局；拖拽可沿圆环调整节点，停止物理模拟。
  </p>
  <p
    v-if="fitStatus === 'limited' && !list"
    class="graph-scope-note"
    role="status"
  >
    当前缩放范围仍无法完整容纳节点；可选择 VLAN
    缩小范围，或使用列表查看全部设备。
  </p>
  <div class="graph-layout">
    <div class="graph-area">
      <div
        v-if="list"
        class="panel-body graph-list"
        aria-label="可访问的设备连接列表"
      >
        <button
          v-for="node in visible.nodes"
          :key="node.id"
          class="btn graph-node-button"
          @click="selectNode(node)"
        >
          <i
            class="graph-color-dot"
            :style="{ backgroundColor: topologyNodeColor(node, prefs.dark) }"
            aria-hidden="true"
          />{{ node.name
          }}<span v-if="node.registered === false" class="small muted"
            >未登记</span
          ><StatusBadge
            :status="topologyNodeState(node)"
            style="margin-left: auto"
          />
        </button>
        <h3 class="small section-gap">连接关系</h3>
        <button
          v-for="edge in visible.edges"
          :key="edge.id"
          class="btn graph-edge-button"
          @click="selectEdge(edge)"
        >
          <span>{{ nodeName(edge.source) }} ↔ {{ nodeName(edge.target) }}</span
          ><span class="small muted"
            >{{ topologyEdgeLabel(edge) }} ·
            {{ topologyRelationLabel(edge) }}</span
          >
        </button>
        <p v-if="!visible.nodes.length" class="small muted">
          当前 VLAN 分组没有节点。
        </p>
      </div>
      <ChartCanvas
        v-else-if="visible.nodes.length"
        ref="chart"
        :option="option"
        :graph-node-colors="nodeColors"
        :auto-fit-graph="visible.nodes.length > 20"
        preserve-layout
        label="设备连接图；实线为观测，虚线为推断。可拖拽、缩放，或使用列表替代进行键盘操作。"
        height="460px"
        @select="select($event)"
        @open="select($event, true)"
        @fit-status="fitStatus = $event"
      />
      <div v-else class="empty-state">
        <strong>当前 VLAN 分组没有节点</strong>
        <p>选择其他分组查看当前快照中的设备。</p>
      </div>
      <div class="chart-footnote">
        <MousePointer2
          aria-hidden="true"
          :size="12"
          style="display: inline"
        />拖拽调整位置 · 滚轮缩放 · 单击节点或连线查看证据 ·
        双击已登记设备打开详情
      </div>
    </div>
    <aside class="graph-inspector" aria-live="polite">
      <template v-if="selected">
        <div class="small muted" style="margin-bottom: 10px">
          {{ selected.registered === false ? "发现终端检查器" : "设备检查器" }}
        </div>
        <h3>{{ selected.name }}</h3>
        <dl>
          <div class="key-value">
            <dt>类型</dt>
            <dd>{{ stateLabel(selected.type) }}</dd>
          </div>
          <div class="key-value">
            <dt>当前状态</dt>
            <dd><StatusBadge :status="topologyNodeState(selected)" /></dd>
          </div>
          <div v-if="selected.availability" class="key-value">
            <dt>可用性</dt>
            <dd>{{ stateLabel(selected.availability) }}</dd>
          </div>
          <div class="key-value">
            <dt>VLAN</dt>
            <dd>{{ selected.vlanIds?.join("、") || "未提供" }}</dd>
          </div>
          <div v-if="selected.addresses?.length" class="key-value">
            <dt>地址</dt>
            <dd>{{ selected.addresses.join("、") }}</dd>
          </div>
          <div v-if="selected.mac" class="key-value">
            <dt>MAC</dt>
            <dd class="mono">{{ selected.mac }}</dd>
          </div>
          <div v-if="selected.confidence" class="key-value">
            <dt>证据状态</dt>
            <dd>{{ confidenceLabel(selected.confidence) }}</dd>
          </div>
        </dl>
        <RouterLink
          v-if="selected.registered !== false"
          class="btn w-full"
          :to="`/devices/${selected.id}`"
          >打开设备详情<ArrowUpRight aria-hidden="true" :size="14"
        /></RouterLink>
        <p v-else class="small muted">
          从网络证据发现，尚未登记为受管资产；地址不代表管理接口，健康与可用性尚待验证。
        </p>
        <p v-if="selected.qualityFlags?.length" class="small muted section-gap">
          质量标记：{{ selected.qualityFlags.join(" · ") }}
        </p>
        <div class="section-gap">
          <h3 class="small">当前视图关系 · {{ relatedEdges.length }}</h3>
          <button
            v-for="edge in relatedEdges"
            :key="edge.id"
            class="btn graph-edge-button"
            @click="selectEdge(edge)"
          >
            <span>{{ topologyEdgeLabel(edge) }}</span
            ><span class="small muted"
              >{{ topologyRelationLabel(edge) }} ·
              {{
                nodeName(
                  edge.source === selected.id ? edge.target : edge.source,
                )
              }}</span
            >
          </button>
        </div>
        <TopologyEvidenceList
          v-if="selected.evidence?.length"
          :evidence="selected.evidence"
          :nodes="topology.nodes"
        />
      </template>
      <template v-else-if="selectedEdge">
        <div class="small muted" style="margin-bottom: 10px">连线检查器</div>
        <h3>
          {{ nodeName(selectedEdge.source) }} ↔
          {{ nodeName(selectedEdge.target) }}
        </h3>
        <dl>
          <div class="key-value">
            <dt>关系</dt>
            <dd>{{ topologyRelationLabel(selectedEdge) }}</dd>
          </div>
          <div class="key-value">
            <dt>端口</dt>
            <dd>{{ topologyEdgeLabel(selectedEdge) }}</dd>
          </div>
          <div class="key-value">
            <dt>VLAN</dt>
            <dd>{{ selectedEdge.vlanIds?.join("、") || "未提供" }}</dd>
          </div>
          <div class="key-value">
            <dt>证据状态</dt>
            <dd>{{ confidenceLabel(selectedEdge.confidence) }}</dd>
          </div>
          <div class="key-value">
            <dt>观测时间</dt>
            <dd>{{ formatTime(selectedEdge.observedAt, prefs.timezone) }}</dd>
          </div>
        </dl>
        <p v-if="isInferred(selectedEdge)" class="small muted">
          此关系为推断，不能单独证明物理直连；请结合来源、时间与 VLAN 证据核实。
        </p>
        <p
          v-if="selectedEdge.qualityFlags?.length"
          class="small muted section-gap"
        >
          质量标记：{{ selectedEdge.qualityFlags.join(" · ") }}
        </p>
        <TopologyEvidenceList
          v-if="selectedEdge.evidence?.length"
          :evidence="selectedEdge.evidence"
          :nodes="topology.nodes"
        />
        <p v-else class="small muted section-gap">
          来源：{{ selectedEdge.provenance || "未提供细分证据" }}
        </p>
      </template>
      <div v-else>
        <Network
          aria-hidden="true"
          :size="25"
          class="faint"
          style="margin: 4px 0 16px"
        />
        <h3>从设备或连线开始</h3>
        <p class="small muted" style="margin-top: 10px">
          选择节点检查状态，选择连线查看端口、VLAN
          与证据。列表替代支持键盘操作。
        </p>
        <p class="small muted" style="margin-top: 24px">
          推断不会自动写入资产，也不表示流量一定经过该路径。
        </p>
      </div>
    </aside>
  </div>
  <div class="panel-foot">
    <span>关系快照 {{ formatTime(topology.asOf, prefs.timezone) }}</span
    ><span>{{ topology.qualityFlags?.join(" · ") || "无附加质量标记" }}</span>
  </div>
</template>
