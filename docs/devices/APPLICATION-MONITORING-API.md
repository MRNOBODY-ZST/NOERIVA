# 独立 NBAR 应用监测 API

2026-09-09 实现契约。所有路径位于 `/api/v1`。读取允许 ADMIN、OPERATOR、VIEWER；写入与手动采集仅 ADMIN，服务层再次检查组织范围。当前提供 Cisco NBAR 协议发现的接口/应用汇总，不提供客户端 IP、五元组、会话数或 NetFlow 流记录。未读取、缺失和首次基线不补零。

| 方法与路径 | 请求 | 返回 |
|---|---|---|
| GET `/applications/sources` | `limit` 默认 50、1–200；`cursor` 可选 | `Page<Source>`，按设备 ID 升序；只列已有 SNMP 槽的同组织资产 |
| POST `/applications/devices/{id}/settings` | `SettingsInput` | `Source` |
| POST `/applications/devices/{id}/state` | `{revision,enabled}` | `Source` |
| POST `/applications/devices/{id}/collect` | `{revision}` | `Source`；完成有界单次采集，可在应用周期关闭时执行 |
| GET `/applications/observations` | 必需 `deviceId`；可选 `interfaceIndex` 正整数、`direction=IN|OUT`、`q` 应用名包含查询≤80字符、`from`/`to` ISO Instant（默认最近1小时，跨度≤7天）、`limit` 默认100、1–500、`cursor` | `Page<Observation>`，时间及 ID 降序 |

`Page<T> = {items:T[],nextCursor:string|null,asOf:Instant,source:string,mode:string}`。游标是不透明字符串，绑定组织及筛选条件；改变筛选必须清空游标。sources 来源 MYSQL，observations 来源 CLICKHOUSE，mode=CONNECTED。无连接数据库返回 503，不生成模拟成功。

`SettingsInput = {revision:number,enabled:boolean,intervalSeconds:number,interfaceIndices:number[],maxRows:number}`。首次保存 revision=0；其后提交当前 revision。intervalSeconds=30–3600；interfaceIndices 必须为 1–8 个互异正整数（不自动取接口前 N 个）；maxRows=1–256，且必须满足 `maxRows >= interfaceIndices.length`，否则服务端返回 400。maxRows 为本轮所有选定接口的协议行总限额，每行含 IN/OUT 两个方向，最多生成 512 个方向观测行。保存/启停成功 revision 加一；409 表示版本变化或正在采集，不自动重试写操作。

本轮协议行余量按剩余已启用协议发现的选定接口公平分配；先读取的接口未用完的额度可给后续接口，总额仍不超过 maxRows。每个接口按协议表 OID 前缀有界读取，超出该接口本轮额度时截断并暴露 `NBAR_ROW_LIMIT`。这不保证读取全部协议，也不提供跨轮次轮转覆盖；未返回的协议不能解释为零流量。

`Source = {deviceId:string,deviceName:string,siteId:string,revision:number,enabled:boolean,intervalSeconds:number,interfaceIndices:number[],maxRows:number,status:string,lastAttemptAt:Instant|null,lastSuccessAt:Instant|null,nextPollAt:Instant|null,errorCode:string,errorMessage:string,credentialRevision:number,protocol:string,lastRowCount:number,qualityFlags:string[]}`。

protocol 固定 CISCO_NBAR_SNMP；status 为 NOT_CONFIGURED、QUEUED、DISABLED、RUNNING、SUCCESS、PARTIAL、ERROR。未配置应用时 revision=0、enabled=false、intervalSeconds=60、interfaceIndices=[]、maxRows=128，credentialRevision 是当前 SNMP 槽版本。响应不含主机凭据、用户名、community、密文或口令。

`Observation = {id:string,deviceId:string,interfaceIndex:number,interfaceName:string,protocolIndex:number,application:string,direction:string,observedAt:Instant,bytes:string|null,packets:string|null,reportedBps:number|null,derivedBps:number|null,derivedPacketsPerSecond:number|null,intervalSeconds:number|null,sourceEpoch:string,qualityFlags:string[]}`。

原始 bytes/packets 是无符号 Counter64 十进制字符串；reportedBps 来自设备 NBAR 速率字段的 kilo bits/s ×1000，不能把它标为平台差分速率，也不臆定设备使用五分钟窗口。derivedBps 和 derivedPacketsPerSecond 仅由相邻成功持久化样本算出，intervalSeconds 是两样本实际观测间隔。首次、长间隔、重启/配置版本/接口身份/协议名变化、协议发现重新开启、缺失 HC 计数或计数下降时派生值为 null，并给出质量标记。Counter64 下降保守视为重置；没有外部证据不猜测真实回绕。

独立 MySQL 配置、乐观版本和租约使应用监测不依赖基础 SNMP 槽的 enabled；仍复用同组织同设备 `snmp` 槽的加密凭据、已验证目标及安全算法。凭据版本变化使应用基线失效。应用开关只控制平台调度；驱动只发 GET/GETBULK，不设置路由器 `cnpdStatusPdEnable`。被选接口未启用协议发现时记录 NBAR_DISABLED，不伪装为零流量。

协议读取总预算25秒、2500个返回变量；每实例应用服务最多4轮并发、整体45秒，数据库租约90秒阻止跨副本重复调度。ClickHouse 同步批量成功后才推进 MySQL 基线。观察行按确定 ID 写入 ReplacingMergeTree，查询显式去重；不把写入超时当成功。无法确认存储的采集返回 ERROR/PUBLICATION_FAILED，先前已保存历史仍可查询。暂不提供自动无界重试或无限本地队列。

应用历史表默认不配置 TTL 或自动删除。管理员应根据实际容量、备份和保留要求另行制定策略，不能把查询最多七天的单次窗口误解为只保存七天。

主要质量标记：NBAR_BASELINE_REQUIRED、NBAR_COUNTER_RESET、NBAR_GAP、NBAR_COUNTER_UNAVAILABLE、NBAR_DISABLED、NBAR_ENABLE_TIME_UNAVAILABLE、NBAR_INTERFACE_IDENTITY_UNAVAILABLE、NBAR_ROW_LIMIT、NBAR_UNSUPPORTED、SNMP_TIMEOUT、SNMP_VARIABLE_BUDGET、SNMP_NON_INCREASING_OID。列表可追加驱动明确的部分读取原因。lastRowCount 为成功保存的方向行数，不是流数或会话数。

错误响应沿用 `{code,message,...}`：400 参数/游标不合法；403 角色不足；404 跨组织或设备/必需 SNMP 槽不存在；409 配置变化/租约冲突；429 应用并发已满；503 数据库/历史服务不可用。协议失败通常作为单次 Source.status=ERROR 返回，安全原因不含凭据或原始 PDU。

实现顺序与验收：先真实 UDP 模拟器验证 NBAR 复合索引、v3、HC 精度、限额和禁用状态；再验证派生速率断代及 MySQL 租约/版本/隔离；最后通过真实 ClickHouse 验证持久历史、去重、时间窗口/筛选/分页。官方对象依据见 [Cisco 原始 MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-NBAR-PROTOCOL-DISCOVERY-MIB.my)。具体 ASR1002-X / IOS XE 17.09.08 是否允许这些 OID 仍以真实采集和 ACL 证据验收。

## 默认应用分布图

`GET /applications/summary?deviceId=...&interfaceIndex=8&direction=IN&q=https&limit=12`。必填设备，其余筛选可选，limit 1–30。服务先定位设备最新一次采样，再在该批次内筛选并按应用/方向汇总所选接口。不会因搜索旧应用而回退到旧批次，不会把 Counter64 累计字节当作带宽。derivedBps 和 reportedBps 分开返回；某组任一成员缺少派生速率时整组 derivedBps 为 null，保留 RATE_INCOMPLETE。跨接口合计附 INTERFACE_OVERLAP_POSSIBLE，不能称为去重后的端到端流量。

返回 `{deviceId,asOf,observedAt,source:'CLICKHOUSE',mode:'CONNECTED',freshness,rateBasis:'COUNTER_DELTA_PER_SECOND',interfaceIndices,sampleRows,totalApplications,truncated,qualityFlags,items}`；每项 `{application,direction,interfaceIndices,derivedBps,reportedBps,observationCount,qualityFlags}`。items 按派生速率降序，缺失值末尾；totalApplications 是筛选后应用/方向分组数。最多读取最新批次 512 条方向行，返回 top limit，并用 truncated 说明图表截断。采样超过 max(180秒,3倍采集间隔) 标为 STALE；无匹配数据 observedAt=null、freshness=MISSING、items=[]。历史查询仍保留于 observations 端点。

The latest-distribution query retains a 1,000,000-row / 64 MiB read, 128 MiB memory, five-second execution and 4 MiB result budget. On ClickHouse 26.3 the summary uses leaf THROW read guards and unlimited local BREAK thresholds so the latest ordered subquery is not rejected by an estimate of the entire retained history. Local BREAK cannot truncate results because its thresholds are unlimited; actual read overflow still fails. This affects the summary query only. The regression contains 1,200,000 historical observations and verifies latest-only rates, cross-organization isolation, absent-app filtering, a bounded physical scan and deliberate read-overflow failure.
