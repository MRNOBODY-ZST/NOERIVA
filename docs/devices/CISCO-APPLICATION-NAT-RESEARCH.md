# Cisco ASR1002-X：应用监测与 NAT 审计采集依据

研究日期：2026-09-07。目标机型/版本由主任务现场确认：ASR1002-X / IOS XE 17.09.08。本研究只读取公开协议资料和本机源码，没有连接设备、服务器或读取凭据。本文件是实施建议；真实支持状态须以目标设备的结构化响应和接收报文验收为准。

## 可持续的最小方案

1. Dell/Cisco 的基础监测沿用现有 SNMP 驱动。Cisco 的 Application 另外定时读取 NBAR MIB，独立配置、租约、采样与历史查询，不将应用统计伪装成逐连接流量。
2. NAT 审计使用独立、持续运行的 HSL / NetFlow v9 UDP 接收器，保存已接收的创建/删除/资源耗尽事件及来源质量。Kafka 承接可重放消息，ClickHouse 保存有界时间查询的数据；MySQL 保存组织、设备绑定和采集配置。具体分工等待主任务确认。
3. RESTCONF NAT 统计可作为独立的资源监测补充；当前转换表仅作快照。NBAR、资源统计、NAT 生命周期是不同证据，不相互补造缺失记录。

## Application：优先 SNMPv3 只读 MIB

Cisco 官方 MIB 为 `CISCO-NBAR-PROTOCOL-DISCOVERY-MIB`，根 `1.3.6.1.4.1.9.9.244`。AllStats entry `...244.1.2.1.1` 的索引为 `(ifIndex, protocolIndex)`：列 2 是名称，7/8 是输入/输出 Counter64 包数，9/10 是 Counter64 字节，11/12 是设备报告的 kbit/s，换算 bit/s 乘 1000。不要使用旧 Counter32，MIB 明确没有可靠溢出支持。接口状态 `...244.1.1.1.1.1`，最后启用时刻 `...244.1.1.1.1.2` 以 sysUpTime ticks 表示，禁用为零。[官方 MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-NBAR-PROTOCOL-DISCOVERY-MIB.my)

只在授权接口内 GET/GETBULK；不写 SNMP SET，不自动创建设备 Top-N 配置。显示设备报告速率和两次连续 Counter64 采样计算的速率时分别标明来源。sysUpTime、SNMP engine、最后启用时刻或应用索引映射改变后建立新计数区间，禁止跨复位求差。接口目录和采样截断必须可见；不能把未读到的应用算成零。不同接口可能观察同一流量，跨接口合计不能标成去重 WAN 总量。这些是本项目建议的计算约束。

ASR 的官方 MIB 指南列出 NBAR，但特性是否在该镜像/许可/协议包中可用仍要现场核验；Top-N 表还会遗漏 `unknown`，因此本方案读取 AllStats。NBAR 的协议分类也不能提供 URL、访问者身份或逐会话数。[ASR MIB 说明](https://www.cisco.com/c/en/us/td/docs/routers/asr1000/mib/guide/asr1kmib/asr1mib3.html)

主任务已观察到真实 `show ip nbar protocol-discovery` 统计；CLI 输出已分页截断，仅能证明有数据，不能证明采集完整。可用于对照的只读命令为 `show ip nbar protocol-pack active detail`、`show ip nbar protocol-discovery interface <interface>`。不执行 `clear ip nbar protocol-discovery`。[Cisco Protocol Discovery](https://www.cisco.com/c/en/us/td/docs/ios-xml/ios/qos_nbar/configuration/xe-16/qos-nbar-xe-16-book/nbar-protocl-discvry.html)

17.9.1 发布目录中的 `Cisco-IOS-XE-nbar.yang` 是对 native 配置的 augment，不能把存在该模块当成存在 NBAR 统计 RESTCONF 接口。没有核实的 `nbar-oper` 路径不写进驱动。[Cisco 17.9.1 NBAR 模型](https://raw.githubusercontent.com/YangModels/yang/main/vendor/cisco/xe/1791/Cisco-IOS-XE-nbar.yang)

## NAT：官方结构化资源与限制

17.9.1 Cisco NAT operational YANG 模型 `Cisco-IOS-XE-nat-oper` revision `2019-05-01` 提供以下只读资源；仍须先检查设备 YANG library 是否实际广告该模块。

| HTTPS GET 资源 | 内容与边界 |
| --- | --- |
| `/restconf/data/Cisco-IOS-XE-nat-oper:nat-data/ip-nat-statistics` | `initialized`、`entries`、`statics`、`flows`、`hits`、`misses`、`entry-timeouts`、方向丢包等。uint64 可为 JSON 十进制字符串；不是 NAT 事件。 |
| `/restconf/data/Cisco-IOS-XE-nat-oper:nat-data/ip-nat-translation` | IPv4 当前表，包含四种地址/端口、`vrfid`、`protocol`、`flags`、`application-type`、`vrf-name`。键为 inside-local 地址、outside-local 地址、两本地端口、VRF 和协议。没有创建/删除时刻；`application-type` 也不是 NBAR 应用名称。 |

来源：[Cisco 17.9.1 NAT operational 模型](https://raw.githubusercontent.com/YangModels/yang/main/vendor/cisco/xe/1791/Cisco-IOS-XE-nat-oper.yang)。使用 `Accept: application/yang-data+json`，固定已核验地址且校验证书，不沿用旧系统允许 HTTP 的客户端约束。快照间出现再消失的短会话不可恢复；不应对全表无限轮询。

历史 ASR MIB 指南把 `CISCO-IETF-NAT-MIB` 列为 unsupported，同时旧 IOS XE 发行说明存在传统 NAT 不填充 MIB 的缺陷。因此不能仅因对象名称存在便选择 SNMP 会话审计；本方案以现场可验的 HSL 为主。[ASR MIB 支持范围](https://www.cisco.com/c/en/us/td/docs/routers/asr1000/mib/guide/asr1kmib/asr1mib3.html)，[历史 NAT MIB caveat](https://www.cisco.com/c/en/us/td/docs/routers/asr1000/release/notes/xe-16-11/asr1000-rel-notes-xe-16-11.html)

## HSL 命令与模板

以下是配置模板，不是本研究已经执行的命令。保留实际 source interface 和原有 VRF 范围；不能直接复制示例接口。

```text
ip nat log translations flow-export v9 udp destination <collector-ip> <udp-port> source <interface-type> <interface-number>
ip nat log translations flow-export v9 global-on
```

需要特定 VRF 时按设备支持语法指定该 VRF；官方说明指定 VRF 会改变其它 VRF 的日志启用范围，主任务应先读取当前配置。`show flow exporter` 为空不能证明传统 NAT HSL 未配置。读取仅限 NAT logging 配置行，避免整份 running-config 中的凭据。

| HSL 字段 ID | 解释/长度 |
| --- | --- |
| 8 / 225 | 原始 / 转换后源 IPv4，4 bytes |
| 12 / 226 | 原始 / 转换后目的 IPv4，4 bytes |
| 7 / 227 | 原始 / 转换后源端口，2 bytes |
| 11 / 228 | 原始 / 转换后目的端口，2 bytes |
| 234 | VRF，uint32 |
| 4 | 协议，uint8 |
| 230 | 事件，uint8；1 添加、2 删除、3 地址池耗尽 |
| 323 | Unix 毫秒，uint64；官方注明部分版本不提供 |
| 283 | 地址池 ID，uint32，资源耗尽模板 |

以上来自 [IOS XE 17.x NAT HSL 官方章节](https://www.cisco.com/c/en/us/td/docs/routers/ios/config/17-x/ip-addressing/b-ip-addressing/m_iadnat-hsl-vrf.html)。首轮支持范围建议限定实际验证的 IPv4 HSL 模板，不能把 IETF IPFIX v10 的任意企业字段或 IPv6 NAT64 自动视为兼容。绑定、会话、池耗尽可能使用不同模板；目的字段缺失必须保留为未知，不复制源字段补齐。

CGN 模式的目的日志和端口块分配是另外的配置与语义；不能为了接收日志切换 NAT 模式。分配到端口块只证明分配，不证明每个端口发生连接。[Cisco CGN](https://www.cisco.com/c/en/us/td/docs/routers/ios/config/17-x/ip-addressing/b-ip-addressing/m_iadnat-cgn.html)

## 接收与审计保证

NetFlow v9 Header 包含 sysUpTime、导出 Unix 秒、序列号和 Source ID。序列号按导出包而不是数据记录递增；缓存必须按 exporter address + Source ID + 模板 ID 隔离，并在重启/过期后重新验证。UDP 可能丢失、重复、乱序；来源 IP 限制不是密码学鉴别。协议本身不能保证零丢失。[RFC 3954](https://www.rfc-editor.org/rfc/rfc3954.html)

项目建议：受信设备绑定后才分配缓存；限制数据报、字段/模板数、模板寿命、队列、批大小和写库并发。保存接收时间、设备事件时间（可空）、导出时间、域/VRF、模板哈希、序列/重启 epoch、数据报 SHA-256 和记录序号。数据报原文仅在严格授权的证据存储中保留，不在普通 API 展示。没有 323 时不把接收时间冒充设备事件时间。

记录 `NO_TEMPLATE`、`MALFORMED`、`SEQUENCE_GAP_OR_REORDER`、`EXPORTER_RESTART`、`QUEUE_DROPPED`、`PERSIST_FAILED` 和接收静默，来源页面显示累计计数和最近状态。必须区分进入内存、Kafka 持久确认、ClickHouse 可查询。Kafka ack 之前的故障和 UDP 到达前丢失都不在持久保证内；重启/模板等待造成的盲区必须披露。source filter、组织归属、API 权限、查询时间范围及游标校验属于服务端约束。

首轮产品口径：可审查“系统保留的 NAT 创建/删除事件”，不自动宣称一条连接从开始到结束全程完整；NO_MATCH 仅表示保留记录未命中。即使创建和删除都存在，期间存在丢包/重启/重复映射也不能自动认定唯一会话。仅有 NAT/ARP/NBAR 不能认定个人或主机产权；地址分配证据需要独立可靠来源。

## 现有代码的兼容缺口

- NOERIVA `SnmpSession.walk` 目前仅接收单下标，不能直接遍历 NBAR 双索引；可在授权 ifIndex 子树下遍历应用索引再批量 GET 其余列。
- `WorkbenchModels.NetworkEvidence` 仅接受 MANUAL/SYNTHETIC，validTo 必填且记录不可修改；没有 VRF、目标四元组、exporter domain、模板与丢包状态。它适合手工/样例的闭合区间，不应承载连续 NAT 原始事件。真实 NAT 使用新领域接口，后续有充分证据再桥接调查。
- `HistoryStore.control_events` 是一般告警/状态事件结构，不适合将高基数 NAT 字段塞进 message。新 NAT 事件需要专用表和查询索引，不逐事件创建 MySQL 告警/outbox。
- 本机旧 Argus 容器无 compose 标签；仅文件名搜索定位到 `/Users/hades/Desktop/Argus`，未读取 env/Secret。它的 `CiscoNbarSnmpCollector` 可参考 MIB 映射，但缺少完整复位证据。`CiscoNatHslParser` 丢弃 header seq/uptime 和 VRF，323 强制必填，生成 ID 未包含协议等所有区分字段；`NetflowV9TemplateCache` 无上限/过期。因此只参考协议形态，不直接复制其可靠性保证。

## 必须完成的验收

1. 真实 SNMP MIB 两轮采样、接口选择、CLI 同时窗对照；复位、未启用、截断与负差值回归。
2. 真实受限 HSL 抓包确认 source address/domain、各 template ID、字段长度及 323；先脱敏生成 synthetic fixture，不把真实元组写进测试源码。
3. 本地真实 UDP 验证错误来源、模板缺失/复用/过期、短包/零长度/乱序/重启/重复、字段缺失、队列满、数据库故障与重放幂等。
4. 真实 Kafka→ClickHouse→按组织查询、VRF/协议/端口区分、时间/游标上限、跨组织拒绝；receiver 重启后源质量明确。
5. 62/63 部署验收需保留 UDP 原始源地址和稳定落点，禁止把同一流负载分散到不共享模板的 receiver。双节点存储本身不等于接收与历史都具有高可用性；现场以可观测故障行为为准。
