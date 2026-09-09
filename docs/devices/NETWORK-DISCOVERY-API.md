# 网络发现候选 API

实现范围：从平台已启用连接的 `lastReading.facts.neighborObservations` 读取邻居证据，生成候选、登记平台资产或关联既有资产。**不扫描、不连接候选地址、不接收凭据、不修改设备配置/历史/连接密钥**。本契约更新于 2026-09-09；设计背景见 [发现研究](NETWORK-DISCOVERY-RESEARCH.md)。

前缀 `/api/v1/discovery`。ADMIN/OPERATOR 可以 POST；ADMIN/OPERATOR/VIEWER 可以 GET。service 内重复检查角色和组织作用域。写请求沿用 `X-Noeriva-Request: 1`。CONNECTED/MySQL 必需；demo 返回 503 `DISCOVERY_UNAVAILABLE`。

## 创建运行

`POST /runs` → 200 `RunResult`，同步有界数据库工作，不发起网络操作。

```json
{"sourceDeviceIds":["gateway-id"],"cidr":"192.168.4.0/24","siteId":"default"}
```

sourceDeviceIds 为1–8个不同设备ID，均须属于当前组织和请求站点，否则整次404。cidr 必须是规范 literal IPv4 网络地址 `/24`–`/32`，不允许主机名、通配符、前导零或带主机位的网络；只用于证据过滤，不授权连接。siteId 必填。使用 literal 解析，无 DNS。IPv4范围仍拒绝未指定、loopback、link-local、multicast、保留广播目标；不会因为文字相似推断别的网段。

每设备只选一条启用来源，优先 SNMP，其次 SSH；先选择来源，再检查已保存证据。首选 SNMP 缺失、损坏或过期时，明确返回其状态，不回退到历史 SSH 证据。来源使用读取的 observedAt；仅 `now-15min < observedAt <= now` 有效。无启用来源/缺邻居字段（NO_SAVED_NEIGHBORS）、陈旧/未来读取、损坏或过大 JSON 分别跳过并计入 sources，其他有效来源可继续。每来源最多256行，任务最多2048行；仅允许名单字段投影，不能回传 raw CLI、密码或未知JSON属性。ARP ageMinutes 保留并作为老化线索；LLDP/CDP 的 ttlSeconds 若存在，证据 validUntil=observedAt+ttlSeconds，到期行不参与新候选；TTL 未提供明确标记未知。来源 SNMP/SSH 部分质量标志随运行返回。运行只刷新仍有效证据，不删除未再次出现的资产。

`RunResult` 全部字段必需（nullable 如下）：

```text
id:string(UUID), siteId:string, cidr:string, asOf:Instant,
sourcesRequested:int, sourcesUsed:int, observationsRead:int, candidatesUpdated:int,
existingCount:int, duplicateCount:int, conflictCount:int,
sources:SourceResult[], qualityFlags:string[]
SourceResult: deviceId:string, deviceName:string, status:USED|MISSING|STALE|INVALID,
 observedAt:Instant|null, acceptedCount:int, reason:string|null
```

计数针对本次更新的不同地址；一个地址可有多个证据，observationsRead 是检查的行数。没有有效行时返回0及原因，不宣称网段没有设备。运行结果存入数据库和审计；同一站点规范IP通过唯一键幂等更新候选，重复运行不会重复登记资产。

## 候选列表

`GET /candidates?siteId=default&status=&limit=50&cursor=` → `Page<Candidate>`。

siteId 可空（当前组织所有站点），status 可空或下述枚举；limit 1–100，cursor 为上页 nextCursor（UUID字典序）。

```text
Page: items:Candidate[], nextCursor:string|null, asOf:Instant, source:"DISCOVERY", mode:"CONNECTED"
Candidate: id:string(UUID), revision:long, address:string, siteId:string,
 name:string|null, mac:string|null,
 status:NEW|EXISTING|LINKED|REGISTERED|POSSIBLE_DUPLICATE|CONFLICT,
 reasons:string[], evidence:Evidence[], associatedDeviceId:string|null,
 firstSeenAt:Instant, lastSeenAt:Instant
Evidence: sourceDeviceId:string, sourceDeviceName:string, source:ARP|LLDP|CDP,
 observedAt:Instant, address:string, mac:string|null, interfaceName:string|null,
 vlan:string|null, name:string|null, chassisId:string|null, chassisSubtype:string|null,
 portId:string|null, ttlSeconds:long|null, ageMinutes:double|null,
 validUntil:Instant|null, qualityFlags:string[]
```

每候选最多16条证据，优先保留矛盾代表，再填充最新观察；字符串有限长，包含截断标志。证据只含允许名单字段，不包含凭据/SSH指纹/证书/raw CLI。安全 MAC 索引覆盖这些保留证据，因此摘要 mac 为 null 的冲突候选仍参与重复线索检索。超过证据上限明确标记 EVIDENCE_LIMIT，不宣称已列出全部身份线索。lastSeenAt 表示最近来源观察时间，不是“现在在线”。旧候选仍保留；UI 应显示该时间并避免在线断言。

- 同站点同规范IP合并候选记录，**不是物理资产合并**。不同站点隔离。
- 同MAC多IP：`POSSIBLE_DUPLICATE` + `MAC_SHARED_BY_ADDRESSES`，不自动关联资产。
- 旧候选保留的历史 MAC 证据也可触发弱疑似重复；这不表示两个地址现在同时使用该 MAC。列表和证据必须显示 lastSeenAt/observedAt 年龄。新一轮同IP矛盾判定只合并仍新鲜且未到TTL的旧观察与本轮有效观察。
- 同IP同时出现不同非空MAC或不同LLDP chassis，或已有多条同址资产：`CONFLICT`，原因 `ADDRESS_HAS_MULTIPLE_MACS` / `CHASSIS_IDENTITY_CONFLICT` / `MULTIPLE_EXISTING_ASSETS`。
- 已有唯一同站点同managementAddress资产：`EXISTING`，associatedDeviceId 指向它。明确登记/关联后的候选为 REGISTERED/LINKED；弱重复线索继续保留在 reasons。
- 基础理由 `NEIGHBOR_EVIDENCE_ONLY`；名字、MAC、IP、LLDP标识不证明同一物理机，不按SSH key或证书自动合并。名字冲突仅提示 `NAME_VARIANTS`。

## 登记与关联

`POST /candidates/{id}/register` → 200 `Candidate`：

```json
{"revision":1,"name":"新设备","type":"HOST"}
```

name 1–120，type=`HOST|BMC|SWITCH|ROUTER|FIREWALL`。事务锁定候选与站点，重新检查同址资产；存在唯一资产则返回关联结果，不再创建。已关联候选重复登记返回当前关联结果，即使旧revision也不会重复创建。未关联且revision过期返回409。CONFLICT 候选不能直接登记；应调查后明确关联已有资产。POSSIBLE_DUPLICATE 可以由操作者明确登记为独立资产，保留重复线索。新资产使用候选IP/站点，健康/可用性未观测；不复制来源设备凭据，不启用采集。

`POST /candidates/{id}/link` → 200 `Candidate`：

```json
{"revision":1,"deviceId":"existing-device-id"}
```

目标必须同组织同站点；只关联候选，不改目标管理地址、类型、历史或凭据。CONFLICT 可由操作者显式选择关联并保留冲突证据。重复关联同一目标幂等；已关联另一目标返回409 `CANDIDATE_ALREADY_ASSOCIATED`。未关联时CAS检查revision。

## 错误与前端流程

- 400：标准验证失败/非法CIDR、来源重复、非法status/cursor/type。
- 403 `DISCOVERY_WRITE_FORBIDDEN` / `DISCOVERY_READ_FORBIDDEN`：service角色检查拒绝。
- 404 `NOT_FOUND`：站点、来源设备、候选或关联目标不在作用域内。
- 409 `REVISION_CONFLICT`、`CANDIDATE_CONFLICT`、`CANDIDATE_ALREADY_ASSOCIATED`。
- 429 `DISCOVERY_CAPACITY`：每实例同时最多4个数据库发现请求，或同MAC关联候选/同址资产超过1024条的有界审查窗口。
- 503 `DISCOVERY_UNAVAILABLE`：缺少连接数据库/事务。
- 503 `DISCOVERY_TIMEOUT`：包含事务的总执行超过10秒，取消并回滚未完成的写入。

来源读取、同址资产和相关候选均使用批量查询；候选每32行批量写入。新候选不会连接网络；登记和关联按站点事务锁串行，同一候选的并发登记只创建一个资产。旧人工设备登记入口仍允许重复管理地址，因此这一保证限于发现工作流；每次发现登记仍重新检查已存在的同址资产。

前端从同站点设备中选择1–8个观察者；来源可以预先由管理员在“接入与采集”配置并启用 SNMP（或显式选择 SSH）后执行“立即采集”。发现页面本身**不调用任何协议 collect**，OPERATOR 无需查看 ADMIN 专用连接设置；runs 明确返回 MISSING/STALE 来提示管理员刷新来源。普通读角色只看安全候选证据。点击登记/关联后打开实际资产详情；误关联不能借此偷偷修改历史，撤销/资产合并不在本轮范围。

## SNMP 端点表与拓扑（2026-09-09）

保存的 `addressObservations`（IP-MIB/RFC1213）和 `dhcpObservations`（DHCP Snooping）现可直接进入同一个组织/站点/地址候选工作流。有效记录按 `ARP`/`DHCP` 来源展示，保留采集时间与接口/VLAN；本机、无效、未完成的邻居及到期租约不进入候选。每次仍最多 256 条/来源和 2048 条，总读取及写入预算不变；不自动扫描或注册。拓扑则只读派生节点和关联，详细边界见 [TOPOLOGY-CORRELATION.md](TOPOLOGY-CORRELATION.md)。
