# 能力矩阵

本表是 V5 必需范围与实际代码的对照，机器来源为 [capability-matrix.json](capability-matrix.json)。`EXPERIMENTAL / PARTIAL` 表示当前切片有代码和自动化验证；不表示实机支持或整个阶段完成。没有任何能力标记为 `SUPPORTED` 或 `DEVICE_VERIFIED`。`DESIGNED / NONE / NOT_TESTED` 保留完整后续范围。

`roadmapTier`、成熟度、实现覆盖和验证是独立轴；UI 原型覆盖另列。`OUT_OF_SCOPE` 需要明确原因与用户授权，本矩阵没有将任何 V5 必需域移出范围。完整逐项字段（协议假设、采集模式、事件、存储、权限、告警/审计、限制、测试证据）见机器表。

| 能力 | 阶段 | 路线层 | 成熟度 | 实现覆盖 | 验证 | UI |
|---|---:|---|---|---|---|---|
| 控制面设备与身份库存 | 1 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 登录、会话和权限边界 | 1 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 独立来源当前状态与新鲜度 | 1 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 接口元数据与设备归属 | 1 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 离散状态事件接入与可靠处理 | 1 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 事务变更与 outbox | 1 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 命名指标有界范围查询 | 2 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 可修正的带宽计数器汇总 | 2 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 七日时区带宽热力图 | 3 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 迟到数据的有界历史修复任务 | 2 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | NONE |
| 有界设备连接浏览 | 3 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 状态告警与乐观锁确认 | 1 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 采集器库存与状态 | 1 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| COMPACT 本地验证部署 | 0 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| Kubernetes / Helm 应用打包 | 0 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 物理服务器监控与关联 | 2 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 操作系统监测 | 2 | MVP | DESIGNED | NONE | NOT_TESTED | NONE |
| 虚拟机监控 | 11 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |
| 虚拟化宿主与集群 | 11 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |
| 带外管理与硬件健康 | 2 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 路由器深度监控 | 3 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 二三层交换机监控 | 3 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 防火墙与 NAT 网关健康 | 3 | MVP | DESIGNED | NONE | NOT_TESTED | NONE |
| 负载均衡设备 | 11 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |
| 无线控制器与接入点 | 11 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |
| Kubernetes 资源与工作负载 | 12 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |
| 存储阵列、SAN 与 NAS | 11 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |
| RAID 与本地磁盘 | 11 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |
| 供电与环境监测 | 11 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |
| OpenWrt / Linux 网络设备 | 3 | MVP | DESIGNED | NONE | NOT_TESTED | NONE |
| 应用与服务可观测性 | 12 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |
| 平台内部管理审计 | 1 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 网络设备登录与命令审计 | 5 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| 配置快照、变更与漂移审计 | 8 | PLANNED | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 服务器安全审计 | 5 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| Linux auditd/journald/auth/sudo | 5 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| Windows 安全日志 | 5 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| Kubernetes API 审计 | 5 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| AAA 认证授权记账 | 6 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| VPN 会话与分配 IP | 6 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| DHCP 租约与地址归属 | 6 | PLANNED | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| DNS 观测补充 | 6 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| NAT 生命周期与历史元组查询 | 7 | PLANNED | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| Flow 与连接元数据 | 7 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| 告警与事件处置审计 | 1 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 证据访问导出删除审计 | 10 | PLANNED | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| Edge 双向 TLS 注册与任务 | 2 | MVP | DESIGNED | NONE | NOT_TESTED | NONE |
| Edge 有界断网缓冲与补传 | 2 | MVP | DESIGNED | NONE | NOT_TESTED | NONE |
| Syslog、未知 Trap 与全局搜索 | 4 | PLANNED | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 跨源时态身份与证据关联 | 6 | PLANNED | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 跨站点合成可用性探测 | 2 | MVP | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 完整规则、事件、通知与静默 | 9 | PLANNED | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 报告、SLO 与容量规划 | 9 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| 证据校验、保留锁与法律保留 | 10 | PLANNED | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | PARTIAL |
| 分级保留、隐私与删除策略 | 10 | PLANNED | DESIGNED | NONE | NOT_TESTED | NONE |
| 版本化适配器与能力声明 | 0 | MVP | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | PARTIAL |
| 高可用、恢复、负载与分片验收 | 13 | FUTURE | DESIGNED | NONE | NOT_TESTED | NONE |

当前实证与限制见 [查询切片报告](../implementation/query-report.md)、[澄明界面对照](../frontend/CLARITY-REFERENCE-AUDIT.md) 和根验证报告。所有实体/协议/API 的完整设计来源仍是 [V5 Master](../source-v5/NOERIVA_MASTER_PROMPT_V5.md)。新增工作流通过 26 项 HTTP / 真实 MySQL / 语义专项测试，工作区查询通过 11 项专项测试。验证仅覆盖已实现控制面切片；新增控制面工作流仍为部分实现，SNMP/Redfish 只读协议适配现已实现并通过真实 UDP/HTTPS 模拟器；仍不宣称真实硬件型号兼容、人员身份归因、证据 WORM 或生产高可用。

完整调查链仍需：用户 → VPN/AAA → 有效期地址归属 → NAT 区间 → Flow → 目的设备/接口/站点 → 原始证据。当前仅实现受权上报的 NAT 与地址租约区间查询；CONFIRMED 只确认保留记录内的端点映射。候选重叠、时钟不确定、缺少租约或仅有快照分别呈现，不推断现实人员身份。

## 本次设备接入覆盖边界

- **设备管理**：登记与独立 inventoryRevision 编辑；ADMIN 配置 SNMP/Redfish 独立连接、只写加密凭据、测试、立即采集与周期启停；所有读角色查看安全结果/SSE。编辑管理 IP 不迁移凭据，识别结果不自动覆盖登记资料。
- **协议适配**：九族目录已有只读代码；SNMP system/ENTITY/IF-MIB 与已审阅厂商传感器、标准 Redfish 身份/健康/传感器。Dell OS9 和 BMC 的 SNMP 私有传感器保持保守；无 Trap/Inform、LLDP/CDP、全 OEM、IPMI 或远程配置。详见[九族支持对照](../devices/DEVICE-SUPPORT.md)与两份官方来源研究：[SNMP](../devices/SNMP-PROTOCOL-RESEARCH.md)、[Redfish/BMC](../devices/BMC-PROTOCOL-RESEARCH.md)。
- **验证等级**：SNMP/Redfish 行为由 UDP/HTTPS 协议模拟器测试，协议域仍记 `FIXTURE_TESTED`；设备控制面 `INTEGRATION_TESTED` 指真实 MySQL/接口与隔离测试。支持目录原码 `SIMULATOR_TESTED_HARDWARE_PENDING` 不是完整矩阵的新枚举：机器表沿用原验证轴，并在相关项另记 `hardwareVerification:PENDING`。没有任何品牌、型号或固件完成 DEVICE_VERIFIED。
- **运行数据**：SNMP/Redfish 分别发布 network/bmc 来源，同名历史指标有单一 slot 归属，测试本身不发布；接口来源决定热图及修复任务的数据范围。内置 Worker 写入心跳并执行有租约的周期读取，无本地持久缓冲或完整 Edge 注册链路。

## 澄明工作台本次覆盖边界

- **事件处置**：真实持久化事件单、关联告警、负责人、状态、修订与处置说明；完整告警规则、通知、升级和静默仍未实现。
- **配置审阅**：人工导入脱敏快照、读取/比较时校验 SHA-256 与同设备逐行差异；未接入自动配置采集、完整厂商脱敏解析器、漂移策略或配置下发。
- **证据资料**：保存原文、读取/导出时校验 SHA-256、提供未签名清单及访问审计；对象存储、签名、保留锁、法律保留和删除工作流仍未实现。
- **合成探测**：保存定义、启停与受权来源上报结果、保留历史；结果绑定定义修订，编辑定义后清空当前结果；当前应用不包含网络执行器或调度器，登记定义不代表执行成功。
- **关联调查**：按 IP、端口、协议、方向、历史时刻查询 NAT 与租约候选；厂商事件解码、VPN/AAA 身份、Flow 和完整人员归因仍未实现。
- **工作区查询**：站点总计、优先巡检、监测来源和接口目录批量分页；搜索事件范围为最近 7 天最多 100 条，不是全历史全文检索。

路线层级保留 V5 原阶段，部分能力提前实现不改变其完整阶段规划。`implementedScope`、`implementedCollectionMethod`、`implementedStorageDestinations` 描述当前实现；原 `dataSources`、`normalizedContracts`、`storageDestinations` 保留完整目标范围，不能据此声称所有目标后端已接入。
