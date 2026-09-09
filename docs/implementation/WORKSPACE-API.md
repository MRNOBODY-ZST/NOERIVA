# 澄明工作台只读聚合 API

所有接口位于 `/api/v1/workspace`，使用现有会话鉴权，组织范围仅由登录身份决定；ADMIN、OPERATOR、VIEWER 可读。CONNECTED 查询读取持久化当前状态，不对每台设备发起 VictoriaMetrics 请求；DEMO 明确返回合成来源。

## 监测来源目录

`GET /monitoring?limit=50&cursor=&q=&siteId=&deviceId=&freshness=`

`limit` 1–100；`q` 最多 120 字符，为设备名称、管理地址或来源 ID 的字面前缀；`siteId`、`deviceId` 最多 64 字符；freshness 为 FRESH / STALE（空值不过滤）。cursor 是不透明游标。按设备 ID、来源 ID 排序，每页计数单位是采集来源，一个来源包含多个指标。

```ts
interface MonitoringSource {
  deviceId: string; deviceName: string; deviceType: string;
  siteId: string; siteName: string; sourceId: string; kind: string;
  health: string; observedAt: string; freshness: 'FRESH' | 'STALE';
  metrics: Record<string, number>; sequence: number; epoch: string;
}
interface Page<T> {
  items: T[]; nextCursor: string | null; asOf: string;
  source: 'MYSQL_CURRENT' | 'SIMULATED'; mode: 'CONNECTED' | 'DEMO';
}
```

无采集来源的资产不产生伪传感器行；空列表与提供方错误分别呈现。新鲜度以响应 asOf 为基准，超过 180 秒为 STALE。kind 是最近投影事件的种类（如 DeviceSummaryObserved），不是协议适配器认证或传感器类别。指标键及单位沿用接入契约：cpu_percent / memory_percent 为 %，temperature_celsius 为 °C，power_watts 为 W，bandwidth_rx_bps / bandwidth_tx_bps 为 bit/s。

此目录给出独立 sourceId 的当前值。已有 `GET /api/v1/devices/{id}/metrics` 按设备和指标查询历史，没有 sourceId 参数；调用方必须区分所选来源当前值与设备级历史，不能据目录选择宣称历史来自该 sourceId。

## 网络接口目录

`GET /interfaces?limit=50&cursor=&q=&siteId=&deviceId=`

范围和分页限制同上。q 匹配设备名称、管理地址或接口名称的字面前缀。全局目录按接口名称不区分大小写的字母序，再按 ID 排序；数字按字面字符排列（例如端口 10 在 2 前），设备详情仍采用数字自然序。接口游标最长 4096 字符，绑定登录组织及 q/siteId/deviceId，改变范围必须从第一页开始；升级前的 UUID 游标不能继续使用。MySQL V12 自动生成并索引名称前 512 字符的小写排序键，采集更新或手工改名自动更新索引；超过该长度时用 ID 消除同键歧义。每页仍有 LIMIT 和 3 秒数据库预算，不拉全表在应用内排序。

```ts
interface WorkspaceInterface {
  id: string; deviceId: string; deviceName: string;
  siteId: string; siteName: string; name: string; macAddress: string | null;
  speedBps: string; adminStatus: string; operStatus: string;
  deviceLastSeen: string | null; deviceFreshness: 'FRESH' | 'STALE' | 'MISSING';
}
```

设备最近观测时间不等同于接口观测时间，因此字段特意使用 device 前缀。UNKNOWN 状态不可展示为健康在线。

## 运行总览

`GET /overview?siteId=`

```ts
interface WorkspaceOverview {
  totals: { devices:number; critical:number; warning:number; healthy:number; unknown:number;
    stale:number; activeAlerts:number; collectors:number; asOf:string; mode:string };
  siteHealth: Array<{siteId:string; siteName:string; timezone:string | null;
    devices:number; critical:number; warning:number; healthy:number; unknown:number; stale:number}>;
  priorityDevices: Device[]; // 最多10，CRITICAL → WARNING → UNKNOWN，站点范围内
  trafficSource: MonitoringSource | null; // 站点内最新带宽观测来源，用于单设备流量图，不是全站聚合
  recentEvents: Event[]; // 最近7天最多5条（有站点筛选时为空并标记NOT_REQUESTED）
  recentEventsStatus: 'AVAILABLE' | 'UNAVAILABLE' | 'NOT_REQUESTED';
  asOf:string; mode:'CONNECTED'|'DEMO'; qualityFlags:string[];
}
```

总计、站点计数、异常设备使用批量 SQL。`activeAlerts` 计入尚未 RESOLVED 的告警，包括 ACKNOWLEDGED，不能标成待确认数。这里的 `totals.stale` 和 `siteHealth[].stale` 仅计入存在历史来源观测且所有来源最近观测均早于响应 `asOf - 180 秒` 的资产；从未观测过的资产属于 MISSING，不计为数据过期，初始健康状态仍是 UNKNOWN。此口径在 `/workspace/overview` 与 `/overview` 中保持一致。事件历史提供方故障不会伪装成零事件：状态为 UNAVAILABLE 且 qualityFlags 含 HISTORY_UNAVAILABLE。站点筛选时不把其他站点事件混入结果；事件页可按设备查询。站点是组织内查询条件，不是细粒度授权边界；页面另行请求的全工作区 OPEN 告警队列不属于此聚合响应。

## 统一搜索数据

`GET /search?q=compute&limit=10`，q 去除首尾空白后 2–120 字符，limit 1–20。

```ts
interface WorkspaceSearch {
  assets: Device[]; recentEvents: Event[];
  query: string; eventScanLimit: 100; eventWindowFrom: string; eventWindowTo: string;
  eventScope: 'RECENT_7_DAYS_MAX_100'; recentEventsStatus:'AVAILABLE'|'UNAVAILABLE';
  asOf:string; mode:'CONNECTED'|'DEMO'; qualityFlags:string[];
}
```

资产按名称/管理地址字面前缀查询。事件是最近7天最多100条中的事件类型 kind、消息或设备 ID 不区分大小写包含匹配，不匹配设备名称；每组结果最多limit条。它不是全历史全文检索，也不搜索人工事件单，前端必须显示事件搜索范围。页面名称跳转由前端本地路由索引补充。无事件命中不代表全历史没有该事件。

## 查询预算与索引

工作台 MySQL 只读查询设置 `MAX_EXECUTION_TIME(3000)`，在现有 64 并发/12 秒 API 准入预算内执行。source_current 增加按组织和观测时间的索引；设备名称和管理地址增加按组织的前缀查询索引。分页最多读取 limit+1 行，概览异常列表最多 10 台，流量来源最多 1 条。站点目录最多 1000 个，totals 独立聚合不受目录上限影响。MySQL 提示语义见[MySQL 8.4 官方说明](https://dev.mysql.com/doc/refman/8.4/en/optimizer-hints.html#optimizer-hints-execution-time)。
