import { createRouter, createWebHistory } from "vue-router";

export const workspacePages = [
  {
    path: "/overview",
    title: "运行总览",
    group: "工作空间",
    keywords: "澄明 工作台 健康 值守",
  },
  {
    path: "/assets",
    title: "资产目录",
    group: "基础设施",
    keywords: "设备 名称 IP 型号 发现 候选 重复",
  },
  {
    path: "/topology",
    title: "基础设施拓扑",
    group: "基础设施",
    keywords: "连接 关系 端口",
  },
  {
    path: "/monitoring",
    title: "监测与传感器",
    group: "基础设施",
    keywords: "温度 功率 CPU 内存 趋势",
  },
  {
    path: "/network",
    title: "网络接口",
    group: "基础设施",
    keywords: "接口 VLAN LACP STP BGP 光模块",
  },
  {
    path: "/applications",
    title: "应用监测",
    group: "基础设施",
    keywords: "Cisco NBAR 应用 流量 SNMP ifIndex 采集",
  },
  {
    path: "/alerts",
    title: "告警队列",
    group: "运行响应",
    keywords: "异常 确认 恢复",
  },
  {
    path: "/incidents",
    title: "事件工作台",
    group: "运行响应",
    keywords: "处置 时间线 假设",
  },
  {
    path: "/events",
    title: "事件流",
    group: "运行响应",
    keywords: "历史 来源 观测",
  },
  {
    path: "/investigator",
    title: "关联调查",
    group: "审计与治理",
    keywords: "NAT IP 端口 协议 归属",
  },
  {
    path: "/nat-audit",
    title: "NAT 审计",
    group: "审计与治理",
    keywords: "Cisco HSL 事件 转换 创建 删除 接收",
  },
  {
    path: "/evidence",
    title: "证据资料",
    group: "审计与治理",
    keywords: "来源 原始 记录 导出",
  },
  {
    path: "/configuration",
    title: "配置与变更",
    group: "审计与治理",
    keywords: "快照 差异 审阅",
  },
  {
    path: "/collectors",
    title: "采集器",
    group: "平台",
    keywords: "站点 心跳 队列 来源",
  },
  {
    path: "/checks",
    title: "合成探测",
    group: "平台",
    keywords: "TCP HTTP HTTPS TLS DNS",
  },
  {
    path: "/settings",
    title: "系统设置",
    group: "平台",
    keywords: "主题 密度 时区 设置 密码 采集 默认 搜索 索引 同步",
  },
];
const meta = (path: string) =>
  workspacePages.find((page) => page.path === path)!;
export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: "/", redirect: "/overview" },
    {
      path: "/overview",
      component: () => import("./pages/OverviewPage.vue"),
      meta: meta("/overview"),
    },
    {
      path: "/assets",
      alias: "/devices",
      component: () => import("./pages/DevicesPage.vue"),
      meta: meta("/assets"),
    },
    {
      path: "/assets/discovery",
      component: () => import("./pages/DiscoveryPage.vue"),
      meta: { title: "发现设备", group: "基础设施" },
    },
    {
      path: "/assets/:id",
      alias: "/devices/:id",
      component: () => import("./pages/DevicePage.vue"),
      meta: { title: "设备详情", group: "基础设施" },
    },
    {
      path: "/topology",
      alias: "/connections",
      component: () => import("./pages/ConnectionsPage.vue"),
      meta: meta("/topology"),
    },
    {
      path: "/monitoring",
      component: () => import("./pages/MonitoringPage.vue"),
      meta: meta("/monitoring"),
    },
    {
      path: "/network",
      component: () => import("./pages/NetworkPage.vue"),
      meta: meta("/network"),
    },
    {
      path: "/applications",
      component: () => import("./pages/ApplicationMonitoringPage.vue"),
      meta: meta("/applications"),
    },
    {
      path: "/nat-audit",
      component: () => import("./pages/NatAuditPage.vue"),
      meta: meta("/nat-audit"),
    },
    {
      path: "/alerts",
      component: () => import("./pages/AlertsPage.vue"),
      meta: meta("/alerts"),
    },
    {
      path: "/incidents/:id?",
      component: () => import("./pages/IncidentsPage.vue"),
      meta: meta("/incidents"),
    },
    {
      path: "/events",
      component: () => import("./pages/EventsPage.vue"),
      meta: meta("/events"),
    },
    {
      path: "/investigator",
      component: () => import("./pages/InvestigatorPage.vue"),
      meta: meta("/investigator"),
    },
    {
      path: "/evidence",
      component: () => import("./pages/EvidencePage.vue"),
      meta: meta("/evidence"),
    },
    {
      path: "/configuration",
      component: () => import("./pages/ConfigurationPage.vue"),
      meta: meta("/configuration"),
    },
    {
      path: "/collectors",
      component: () => import("./pages/CollectorsPage.vue"),
      meta: meta("/collectors"),
    },
    {
      path: "/checks",
      component: () => import("./pages/ChecksPage.vue"),
      meta: meta("/checks"),
    },
    {
      path: "/settings",
      component: () => import("./pages/SettingsPage.vue"),
      meta: meta("/settings"),
    },
    {
      path: "/system",
      component: () => import("./pages/SystemPage.vue"),
      meta: { title: "系统能力", group: "平台" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/overview" },
  ],
});
