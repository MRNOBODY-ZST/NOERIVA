# Dell OS9 / Cisco IOS XE 完整监测依据与边界

> 2026-09-09 更新：Dell S6100 环境传感器、Cisco ASR1002-X 专用枚举及当前健康新鲜度的最新依据与验收见 [设备监测数据完整性修复](MONITORING-INTEGRITY-20260909.md)。本文保留早期研究记录。

核对日期：2026-09-07。目标型号为 Dell S6100-ON，OS9 9.14(2.23)，以及 Cisco ASR1002-X，IOS XE 17.09.08。此文区分官方对象定义、当前实现与真机证据；产品 MIB 中存在对象不等于该设备的 SNMP view 允许读取全部对象。没有执行任何网络发现或扫描。

## 官方身份和传感器依据

| 对象 | 定义及实现选择 | 依据 |
|---|---|---|
| Dell S6100 sysObjectID | `1.3.6.1.4.1.6027.1.3.28`；由 dellNet → products1 → SSeries3 → s6100 28 逐级推导，匹配后启用 OS9 chassis 候选布局 | [Dell 官方 Legacy OS9 MIB 库](https://www.dell.com/support/kbdoc/en-us/000181922/dell-networking-mibs) |
| Dell CPU entry | `1.3.6.1.4.1.6027.3.26.1.4.4.1`；后缀为 `(deviceType,deviceIndex,processorIndex)`，三个索引不能缩成两个 | 同上，DELL-NETWORKING-CHASSIS-MIB |
| Dell CPU `.1` / `.4` / `.5` | Gauge32 百分比，分别为最近5秒/1分钟/5分钟；当前选择 `.1`，保留处理器索引与5秒标签 | 同上 |
| Dell memory `.6` | Gauge32，单位 percent；它是内存使用率，不能作为 module temperature | 同上 |
| Dell stack temperature | `1.3.6.1.4.1.6027.3.26.1.3.4.1.13` 的本次官方 MIB 未声明 UNITS；不直接转换摄氏度 | 同上；温度优先使用有类型/scale/precision的标准 ENTITY-SENSOR |
| Cisco ASR1002-X sysObjectID | `1.3.6.1.4.1.9.1.1525` | [Cisco 官方 PRODUCTS OID 清单](https://raw.githubusercontent.com/cisco/cisco-mibs/main/oid/CISCO-PRODUCTS-MIB.oid) |
| Cisco PROCESS entry | `1.3.6.1.4.1.9.9.109.1.1.1.1`；`.2` 关联 entPhysicalIndex，`.10` 与 `.9` 分别为 CPU 窗口值和秒数；无窗口时回退 `.6` 五秒 CPU | [Cisco PROCESS-MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-PROCESS-MIB.my) |
| Cisco memory | `.17/.19` 为 HC used/free；优先于 `.12/.13` 的32位 used/free；这些是容量 gauge，不能计算增长速率。以 used/(used+free) 得到百分比，并记录实际两个来源 OID | 同上 |
| Cisco / 标准温度 | Cisco 类型 celsius=6，标准 celsius=8，必须按各自 scale、precision、value、status 解释，不可共享枚举 | [Cisco ENTITY-SENSOR-MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-ENTITY-SENSOR-MIB.my)、[RFC 3433](https://www.rfc-editor.org/rfc/rfc3433.html) |

Dell 官方页面提供的[公开下载附件](https://supportkb.dell.com/attachment/ka06P000000sxzWQAQ/Current_MIBs_pkb_en_US_1.zip) 中，本次读取的是 `DELL-NETWORKING-MIB-9.14.2.1.zip` 内的 CHASSIS、PRODUCTS、SMI 与 TC 文件。外层附件 SHA-256 为 `58eed178b5ad7a5f50e1f9f519ad9d5634637365014cb53e95df0247a08f1aeb`，CHASSIS 文件为 `bc62511d6876ff752b9a7a6bf9c7e60e9f9af3615376c5a517631077dbf5f2af`。该版本不是目标设备的精确 `.23` 修订，因此仍需真实 OID 返回验证。旧 Argus 的 `.6.2.1`“温度”映射与此官方定义冲突，本次不继承。

[Cisco ASR1000 MIB 平台说明](https://www.cisco.com/c/en/us/td/docs/routers/asr1000/mib/guide/asr1kmib/asr1mib3.html) 说明平台与 RP/ESP/IOSd 的对象支持存在差别；[17.9 发布说明](https://www.cisco.com/c/en/us/td/docs/routers/asr1000/release/notes/xe-17-9/asr1000-rel-notes-xe-17-9.html) 确认版本系列，不证明每个私有 OID 在具体 view 中可访问。多 CPU/多温度实体保持独立读数，不能取第一个或求未经定义的平均值作为整机值。

## 标准接口与速率链路

IF-MIB/ifX 的键为 ifIndex；接口名称与 MAC 用于平台稳定身份，原始 ifIndex 保留在 sourceRef。HC in/out octets 分别是 `1.3.6.1.2.1.31.1.1.1.6` / `.10`，单位 bytes；discontinuity 为同表 `.19` 的 sysUpTime 时间刻度。ifHighSpeed `.15` 的单位为百万 bit/s，回退 ifSpeed 为 bit/s。应按 `[0,2^64−1]` 无符号整数保存 HC 值。[RFC 2863](https://www.rfc-editor.org/rfc/rfc2863.html)

本次复查链路为 `SnmpDriver → DeviceCounters → DevicePublisher → VictoriaMetrics raw counters → RollupWorker/ClickHouse → heatmap`，source_id=network 已贯通。最新 Reading 中原始计数为字符串，可保留精度。相邻基线需要同接口身份、相同 counterBits/discontinuity、连续 uptime/engine 和合理时间间隔；下降没有回绕证据时保守判为 reset。32位计数在高速接口或长采样间隔中存在多次回绕歧义，不能假定只回绕一次。全接口求和仅表示返回接口之和，逻辑/物理/LAG 流量可能重复，并非站点吞吐量。

当前配置允许 maxInterfaces=1–256，但单轮2500个返回变量、25秒是总限额。64为 ENTITY 和单传感器表行数上限；不能混为设备接口总数。256接口的主表8列加索引约2305变量，再加64实体的属性即可能超过预算，必须显示部分状态；真机先用128并核对实际接口总数。本次范围内不扩大无界 walk。

以下是仍需独立实现/验收的历史能力，不能据最新读取成功宣称完成：

- 多实体传感器只保留在 lastReading.sensors，尚无每个传感器的独立历史查询；当前 VM 仅发布符合聚合规则的设备指标。
- VictoriaMetrics 官方说明保留最多12位有效数字；把 Counter64 作为数字写入后，历史并非任意64位计数的逐位精确副本。VM raw source 必须保留浮点来源质量标记，不把最新 JSON 的精度保证套用到 VM 历史。[VictoriaMetrics 数据概念](https://docs.victoriametrics.com/victoriametrics/keyconcepts/)
- RollupScheduler 当前每轮32接口并只补算最近15分钟；大库存或慢 provider 可能超出覆盖窗口。持久化 per-interface watermark 与积压恢复是后续有界调度任务。
- ifType、ifStack/LAG、packet/error/discard、每接口利用率及单实体容量历史尚未完整建模。接口速率图不能自动解释为整机上联利用率。

## NBAR 独立应用采集

官方 [CISCO-NBAR-PROTOCOL-DISCOVERY-MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-NBAR-PROTOCOL-DISCOVERY-MIB.my) 定义 AllStats entry `1.3.6.1.4.1.9.9.244.1.2.1.1`，索引 `(ifIndex,protocolIndex)`；列2名称、7/8 HC packets、9/10 HC bytes、11/12速率。速率单位为 kilo bits/s，乘1000后单独作为 reportedBps；平台相邻计数差分为 derivedBps。32位对象不提供可信溢出处理，本实现不回退到它们。

Status entry `1.3.6.1.4.1.9.9.244.1.1.1.1` 的 `.1` 是协议发现开关，`.2` 是最近启用时的 sysUpTime（关闭时0），不是最后采样时间。驱动只读取，不启用该开关。选定接口后在名称列下 walk 单个 protocolIndex 后缀，避免整表扫描或只取 top-N 破坏计数基线。详细公开 DTO、独立状态、租约、持久历史和测试口径见 [APPLICATION-MONITORING-API.md](APPLICATION-MONITORING-API.md)。

NBAR 不是 NetFlow 或 NAT 会话审计，不含客户端IP、五元组或会话数。基础 SNMP 与 NBAR 平台开关独立；NBAR 复用已加密 SNMP 凭据，真实设备仍需针对 system/IF/ifX/NBAR OID 的 read view 与网络 ACL。真实采集使用管理员确认的 ifIndex，不能按界面顺序或资产 UUID 猜测。
