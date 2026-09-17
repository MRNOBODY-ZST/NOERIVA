# Dell / Cisco 网络、应用与 NAT 监测

## 部署与模块

目标服务器为 `192.168.4.62`、`192.168.4.63`，沿用 `noeriva-system` 双节点 K3s。控制 API 与前端各两个副本；采集 worker 固定在 `.62`。设备基础 SNMP、NBAR 应用采集和 NAT 日志接收具有各自的开关和状态。

| 模块 | 目的 | 来源 | 数据边界 |
|---|---|---|---|
| Dell / Cisco SNMP | 接口状态、累计收发字节、可验证 CPU/内存/温度及带宽趋势 | 只读 SNMPv3 SHA/AES128，60 秒周期 | 设备未提供的值不补零；不把逻辑接口求和声称为站点吞吐 |
| Cisco Application | 上联及下联接口的应用协议流量 | NBAR，接口 `8=Te0/1/0`、`9=Te0/3/0`，60 秒周期 | 应用/接口汇总；没有客户端 IP、五元组、会话数或用户归因 |
| Cisco NAT 审计 | 查找转换创建、删除、绑定及地址池事件 | HSL NetFlow v9 UDP → Kafka → ClickHouse | 记录观察到的事件；UDP 和模板丢失不允许宣称完整捕获 |

页面设计与权限分别见 [逐页规划](../frontend/CLARITY-PAGE-PLAN.md)、[Application API](../devices/APPLICATION-MONITORING-API.md)、[NAT API](../devices/NAT-AUDIT-API.md)。新页面为 `/applications` 和 `/nat-audit`，设备详情也提供独立入口。配置写入仅 ADMIN，已有读取角色可查询。

## 实机配置与确认依据

Dell S6100-ON / OS9 `9.14(2.23)` 与 Cisco ASR1002-X / IOS XE `17.9.8` 的原有 SNMPv3只读账号已验证并存入 NOERIVA 加密凭据库；不在文档中记录口令。初次超时由两个旧监测 ACL 仅允许 `.61` 引起。

本次仅在 Dell `ARGUS_SNMP` 与 Cisco `ARGUS_NBAR_SNMP` 中增加 `.62`、`.63` 精确来源。Cisco 只读视图增补 system、interfaces、ifX、ENTITY、标准/Cisco SENSOR、PROCESS 子树；保留原有 NBAR 子树。两台均执行了配置保存。

Cisco 上、下联接口原先都已配置 `ip nbar protocol-discovery`，本次沿用。索引 7 是内部 `Cr0/0/6`，不可凭排列将其当上联。所选接口必须使用实测 ifIndex。

Cisco 当前导出配置：

```text
ip nat log translations flow-export v9 udp destination 192.168.4.62 32055 source Loopback101
```

导出来源 `192.168.253.1` 属于已存在的 Loopback101。服务器地址经现有全局路由到达；管理口 Gi0 属于 Mgmt-intf VRF，故本次没有将其作为全局导出来源。旧的 `.62:2055` 导出目标已移除，原 NAT 转发配置保持原状。实机不接受官方部分 17.x 文档中的 `global-on` 子命令；destination 配置后已收到 v9 数据及模板，未强行设置该选项。

真实 source-domain 为 200；模板 256 的 38 字节记录含字段 8、225、12、226、7、227、11、228、234、4、230、323。模板 257 为地址绑定，258 为池耗尽，259 含端口块字段，不能误解为单条完整会话。验收时完整模板与原始包存放于受限临时目录；验收后清理原始抓包与临时凭据，仓库仅保留脱敏同形测试数据。字段依据来自 [Cisco HSL 文档](https://www.cisco.com/c/en/us/td/docs/routers/ios/config/17-x/ip-addressing/b-ip-addressing/m_iadnat-hsl-vrf.html) 与 [Cisco NBAR MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-NBAR-PROTOCOL-DISCOVERY-MIB.my)。

## 接收与持久化

Helm `worker.natReceiver` 开启后生成 UDP NodePort 32055，容器端口2055。`externalTrafficPolicy: Local` 保留真实来源，NetworkPolicy 仅允许 `192.168.253.1/32`，数据库再将来源唯一绑定到组织与已注册 Cisco 资产。故 `.63:32055` 不是备用接收入口；切换节点必须同步调整 collector 绑定、导出目标与网络策略。

NAT 先取得 `noeriva.nat.v1` 的 Kafka 确认，再由独立消费者持久化 `nat_audit_events`。NBAR 的协议行总预算为 256，必须不少于选定接口数；按剩余已启用接口分配额度，前序未用额度可供后序使用。当前双接口每轮各 128 个协议行，仍按 OID 前缀截断，显示 `NBAR_ROW_LIMIT`，不代表全协议覆盖或轮转。NBAR 按有界批次同步保存 `application_observations` 后推进采集基线。两张 ClickHouse 表和 Kafka 都不默认自动删除历史；容量与保留策略由管理员明确制定。MySQL V8/V9 只保存配置、状态和应用基线。

控制节点 `.62`、单副本数据服务与本地磁盘仍是当前可用性限制。API/前端双副本不等于数据服务高可用；NAT UDP 在切换/模板恢复期间也可能缺记录。设备时间、接收时间、未知模板、序列跳变、解析失败和排队丢弃均按实际证据显示。

## 验证与恢复

`NOERIVA_BOOTSTRAP_PASSWORD` 由调用者安全传入后运行 `scripts/verify-network-monitoring.py --output <报告路径>`。它只读取两台部署，检查 SNMP 新鲜度、NBAR 双接口差分、NAT 持久化/事件类型和有界并发查询；不会生成设备流量或清空 NAT 表。ComputerUse 另外验证页面与筛选行为。最终执行结果见 [2026-09-07 实机验收记录](../implementation/NETWORK-MONITORING-VERIFICATION-20260907.md)。

需要停用时，分别使用应用监测、NAT 审计页面的独立开关；应用开关只控制 NOERIVA 采集调度。NAT 关闭平台接收后，路由器仍可能发送 UDP，若需停止网络发送，移除上面的精确 destination 命令并保存配置。不要清空正在使用的 NAT 转换表。撤销本次 SNMP 网络许可时只删除新加的 `.62`、`.63` 规则，保留 `.61` 及其他既有配置。
