# SNMP 终端、转发表与 VLAN 证据

2026-09-09。采集器 `SnmpEndpointEvidence` 读取网络设备已有缓存，不向终端发送探测，不使用设备 SSH，不修改 SNMP 对象。每个 observation 保留 UTC 读取时间、完整实例 OID、原始枚举及归一化字段。拓扑/发现层决定去重、归属及可信度；一条 FDB 记录仅证明交换机在该方向学习到 MAC，可能经过交换机、聚合链路或虚拟化宿主机。

## 正式 MIB 依据与索引

| 表 | 列根 / 必须追加的实例索引 | 已实现语义 |
|---|---|---|
| ipNetToPhysical | `1.3.6.1.2.1.4.35.1.{4,6,7}`；`ifIndex.addressType.length.addressOctets` | 物理地址、类型、邻居状态；IPv4 与 IPv6 字节严格解码 |
| ipNetToMedia | `1.3.6.1.2.1.4.22.1.{2,4}`；`ifIndex.ipv4Octets` | 新版地址表没有可用行时读取旧 ARP MAC/类型 |
| dot1dBasePortIfIndex | `1.3.6.1.2.1.17.1.4.1.2.bridgePort` | 将 bridgePort 映射为 ifIndex，不假定二者相等 |
| dot1dTpFdb | `1.3.6.1.2.1.17.4.3.1.{2,3}.macOctets` | 端口、状态；缺少 Q-FDB 才使用，不猜默认 VLAN |
| dot1qTpFdb | `1.3.6.1.2.1.17.7.1.2.2.1.{2,3}.fdbId.macOctets` | 转发数据库、MAC、桥端口及状态 |
| dot1qVlanCurrent | `1.3.6.1.2.1.17.7.1.4.2.1.{3,4,5}.timeMark.vlanId` | FDB ID、当前出口端口集合、untagged 端口集合 |
| dot1qVlanStaticName | `1.3.6.1.2.1.17.7.1.4.3.1.1.vlanId` | VLAN 名称，可缺失 |
| cdsBindings | `1.3.6.1.4.1.9.9.380.1.4.1.1.{3,4,5,6,7,8}.vlanId.macOctets` | 地址类型、地址、接口、租约秒数、行状态及 DHCP option 12 主机名 |

IP-MIB 的 `.6` 是类型（other=1、invalid=2、dynamic=3、static=4、local=5），`.7` 才是邻居状态（reachable=1、stale=2、delay=3、probe=4、invalid=5、unknown=6、incomplete=7）。IPv4 不使用邻居不可达检测时可能返回 unknown，不能据此宣称不可达。没有读取 `.5` 的 sysUpTime 时间戳，也不把它当作 UTC 或终端最后在线时间。地址以原始二进制转换，不进行 DNS 查询。目前不接受 ipv4z/ipv6z/DNS 类型或不一致的长度，保留“不完整”标记。[官方 IP-MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/IP-MIB.my)

FDB 状态 other=1、invalid=2、learned=3、self=4、mgmt=5；端口 0 表示无法由此项确定出口。采集器保留状态和未映射端口，不伪造接口。桥端口仅通过 `dot1dBasePortIfIndex` 关联接口。 [官方 BRIDGE-MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/BRIDGE-MIB.my)

FDB ID 与 VLAN ID 独立，多个 VLAN 可以共享同一个 FDB；`vlanIds` 只从当前 VLAN 表连接得到，未知时为空数组。TimeMark 使用无符号 32 位完整索引。PortList 第一个字节最高位对应 bridge port 1，不对应 ifIndex；合法空 PortList 表示空集合，未返回值表示 `null`。当前实现限常规 IEEE VLAN 1–4094；扩展本地 VLAN 索引明确视为未支持的证据。[官方 Q-BRIDGE-MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/Q-BRIDGE-MIB.my)

Cisco DHCP snooping 的索引是 VLAN 与固定六字节 MAC，无额外 MAC 长度前缀。地址按 InetAddressType 解码；`leaseSeconds` 是 MIB 返回的租约秒数，不推导绝对过期时间。仅 active=1 表示活动绑定，destroy=6 保留为不同状态。这是 snooping 学习表，不是所有 DHCP 服务器的全部租约；采集器不执行其支持的删除操作。[官方 CISCO-DHCP-SNOOPING-MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-DHCP-SNOOPING-MIB.my)

Dell OS9 官方说明分别使用 dot1d/dot1q 转发表读取 MAC 与桥端口，并提示聚合端口需要独立映射。实现使用标准桥端口到 ifIndex 连接，缺失映射保留未知；没有借用 OS10 或 PowerConnect 的 DHCP 私有 OID 作为 OS9 支持证据。[Dell OS9 SNMP MAC 表说明](https://www.dell.com/support/manuals/en-us/dell-emc-os-9/s3048-on-9.14.2.6-config-pub/fetch-dynamic-mac-entries-using-snmp?guid=guid-22ee2c4c-3a36-4374-b9f8-817070b8ea4f&lang=en-us)

## 发布契约

`Reading.facts` 的值均为字符串，其中四个字段是 JSON 数组：

- `addressObservations`：source、address、mac、interfaceIndex/name、entryType/rawEntryType、neighborState/rawNeighborState、observedAt、sourceRef/sourceRefs。
- `forwardingObservations`：source、mac、bridgePort、interfaceIndex/name、fdbId、vlanIds、entryStatus/rawEntryStatus、observedAt、sourceRef/sourceRefs。
- `vlanObservations`：vlanId、name、fdbId、timeMark、egressBridgePorts、untaggedBridgePorts、observedAt、sourceRef/sourceRefs。
- `dhcpObservations`：source、mac、address、interfaceIndex/name、vlanId、leaseSeconds、rowStatus/rawRowStatus、hostname、observedAt、sourceRef/sourceRefs。

`addressTableStatus`、`forwardingTableStatus`、`vlanTableStatus`、`dhcpTableStatus` 分别取：

- `OBSERVED`：本次有证据且未触发列缺失、超限或协议错误。
- `EMPTY_OR_UNSUPPORTED`：没有可用观察。SNMP 的空表、未实现和受 view 限制不能仅靠该响应区分。
- `INCOMPLETE`：有界截断、关键列缺失、无效索引或读取失败；保留已取得的原始证据。
- `SKIPPED_BUDGET`：本轮剩余预算不足，或前序请求已经出现停止条件。
- `NOT_APPLICABLE`：BMC 不执行网络端点采集；未核验 DHCP MIB 的厂商不推断支持。

采集器保留 LOCAL/INVALID 地址项、INVALID/INCOMPLETE 邻居状态和 SELF/INVALID FDB 项以便审计，消费者不能用这些状态生成活动终端。缺少类型或 FDB 状态时保留 UNKNOWN 并标不完整。多播、全零 MAC，无效或零/回环/多播 IP，不一致索引不能产生终端证据。单条缓存项本身不能证明终端目前在线。

## 性能与隔离

采集在身份、传感器、接口计数器及邻居之后执行，仅明确网络 profile 触发：Dell S6100 OS9 / 有 OS9 描述的 Dell、Cisco、Huawei VRP，以及明确 Comware 的 H3C。iMana/iBMC/iDRAC/Inspur BMC 不执行这些表遍历。

辅助阶段最多 5 秒和 1100 个返回变量，受原会话 25 秒、2500 变量总上限约束，并留至少 500 ms 供已取得的监测结果发布。表按 GETBULK 增序游标分包读取；行数由剩余预算计算，最多每表 128 行。多读一条确认超限，输出 `SNMP_ENDPOINT_ROW_LIMIT`，绝不把截断结果描述为完整网络清单。大表当前没有跨轮次续传，不能宣称已覆盖 128 条以外的全部终端。

GET 不超过 40 个 OID，GETBULK 每请求最多 10 个返回变量，无重试。触发 timeout、认证/访问拒绝、变量预算或截止时间时停止后续辅助表。可选证据的预算耗尽不会抹去已读到的温度、功率和健康结果。

## 实机与回归证据

2026-09-09 通过部署服务器发起 SNMPv3 authPriv 只读探测，凭据由已有 Kubernetes Secret 临时生成 0600 snmp.conf，未写入报告或源代码：

- Dell S6100 OS9：legacy ARP 41 行，ipNetToPhysical 45 行，FDB/Q-FDB 各 43 行，桥端口映射 5 行，当前 VLAN/FDB 和名称各 5 行。该数据支持 192.168.4.* 终端的 MAC/IP/学习路径关联。
- Cisco ASR1002-X IOS XE：原 view 未包含 IP/BRIDGE/DHCP，最初 NoSuchObject 不能证明不支持。经授权通过 HTTPS 扩展只读 view 后，legacy ARP / ipNetToPhysical 各 6 行；BRIDGE NoSuchInstance、Q-BRIDGE NoSuchObject、DHCP snooping endOfMibView，尚无这些表的实机条目证明。
- 私有原始记录：`.local/topology-20260909/endpoint-tables.json` 与 `endpoint-cisco-expanded.json`，权限 0600。不把原始网络清单提交到公开文档。

新增 10 项真实 UDP 协议测试覆盖共享 FDB、桥端口/接口不同、无符号 TimeMark、IPv4/IPv6、状态语义、旧表 fallback、DHCP 索引、非法输入、超限、BMC 零请求、空表、超时及辅助预算恢复；加上原 59 项 SNMP 测试，69 项全部通过。结果 `.local/topology-20260909/snmp-regression-summary.json`，最新部署采样已通过下述发布验收。

全量集成回归后补充：Huawei 功能测试曾在首次 system GET 因 150 ms 请求预算超时，尚未进入传感器解析。将该功能夹具的请求预算独立设为 1000 ms，并增加 250 ms 首次响应延迟测试，验证原 150 ms 配置仍返回 SNMP_TIMEOUT、1000 ms 配置可保留完整健康与数值。生产协议超时和原断言均未放宽。最新 SNMP 定向组 70 项全部通过，日志 `maven-udp-stability-2.log`。

## 发布验收

版本 `20260909-topology-r1` 于 2026-09-09 07:57:22 UTC 开始滚动部署。通过平台只读 API 核验新 worker 的部署后样本；首次读取 Dell 仍为旧样本，等待下一采集周期后全部通过，没有将滚动更新暂态视为最终结果。

| 设备范围 | 最新样本 UTC | 地址 / FDB / VLAN 条目 | 表状态 | 接口 / 传感器 | 会话返回变量 / 耗时 |
|---|---|---|---|---|---|
| Dell S6100 OS9 | 07:59:17 | 46 / 44 / 5 | 三表均 `OBSERVED`；DHCP `NOT_APPLICABLE` | 81 / 12 | 1297 / 1018 ms |
| Cisco ASR1002-X IOS XE | 07:59:16 | 6 / 0 / 0 | 地址 `OBSERVED`；FDB、VLAN、DHCP `EMPTY_OR_UNSUPPORTED` | 15 / 110 | 1775 / 632 ms |

所有发布证据时间均晚于部署起点；桥端口到 ifIndex、FDB 到 VLAN 的来源 OID 连接检查通过，未新增端点超限、关键列缺失或无效证据标记。两台设备均保留 `HEALTHY`、接口带宽、CPU 和内存监测；Dell 最新温度为 26°C、功率为 218 W。上述是同次采样的观测值，不代表固定运行值。表条目数包含保留的原始状态，不等同于去重后的在线终端数。

端点专项验收报告为 `.local/topology-20260909/endpoint-acceptance-r1.json`，监测数据保留报告为 `telemetry-preservation-r1.json`，均为 0600 私有文件。本文仅记录聚合计数，不包含原始 IP/MAC 清单。主任务另完成 36 项 API 验收及 Computer Use 页面验证；本专项验收全程使用平台 GET，没有连接设备 SSH。
