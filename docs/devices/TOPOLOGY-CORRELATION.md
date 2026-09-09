# SNMP 证据拓扑与发现关联

拓扑从已保存、启用且新鲜的采集结果派生。查询不会访问设备、开启 SSH、主动扫描、写入 CMDB 或自动注册终端。未登记节点展示证据和注册入口，注册仍走已有 discovery 审核流程。

## 协议事实与系统推断的界线

| 事实 | 标准含义 | 本系统允许的关系 |
| --- | --- | --- |
| LLDP/CDP 邻居及本地/远端端口 | 邻居协议直接通告 | `PHYSICAL`，观测证据 |
| ARP / IP-MIB 地址邻居缓存 | IP 与链路层地址的缓存关联 | `L3_INFERRED`，不能证明线缆或终端在线 |
| BRIDGE / Q-BRIDGE FDB | 在某桥端口学习到源 MAC | `L2_INFERRED`，仍可能经过未被发现的桥/AP/虚拟交换机 |
| DHCP Snooping 绑定 | 交换机观测的地址、MAC、接口、VLAN 和租期绑定 | `L2_INFERRED`，不等同全网 DHCP 服务器租约 |
| VLAN 表 | VLAN 与 FDB、端口集合的映射 | 成员分组；不用于推断相同网段就是物理连接 |

FDB 的桥端口须经 `dot1dBasePortIfIndex` 映射到接口。FDB 索引不一定是 VLAN ID：`dot1qVlanFdbId` 允许共享学习域。本系统保留已观测 VLAN 集合，不选择一个假定的 VLAN。来源：[RFC 4188](https://www.rfc-editor.org/rfc/rfc4188.html)、[RFC 4363](https://www.rfc-editor.org/rfc/rfc4363.html)。

IP-MIB 的 `LOCAL` 是设备自身地址，`INVALID` 和 `INCOMPLETE` 不能作为有效邻居。缓存 `STALE` 或 `UNKNOWN` 不等于主机离线，也不保证在线；采集时间不是最近一次主机响应的时间。来源：[RFC 4293](https://www.rfc-editor.org/rfc/rfc4293.html)。DHCP Snooping 可见范围受设备功能、可信接口和配置影响，空表不能证明没有终端。[Cisco DHCP Snooping 文档](https://www.cisco.com/c/en/us/td/docs/switches/lan/c9000/sec-crypto/fhs-sisf/fhs-and-sisf-configuration-guide/dhcp-snooping.html)。

## 关联步骤

1. 从授权组织/站点的资产构建地址索引，包含离线资产。规范化 IPv4/IPv6 字面量，不执行 DNS；回环、未指定、链路本地和组播地址不能成为匹配目标。只有启用的首选采集来源才提供当前 MAC、端口和邻居证据；SNMP 优先，首选源缺失或过期不会回退到保存的 SSH。
2. 当前事实和采集结果都必须在过去 180 秒内，已知 LLDP TTL、DHCP 租期另外验证。有效单播 MAC 标准化；明确的 SNMP chassis MAC 必须具有实际来源 OID，不从 SNMP engine ID 或相近接口 MAC 推测。
3. 先处理 LLDP/CDP。唯一的地址、名称或 MAC 识别到资产时沿用资产 ID；多个资产标记身份冲突，不任意挑选。未知邻居创建确定性发现节点。真实 chassis MAC 相同的 FDB/ARP 后续证据复用该节点。
4. 端点按组织、站点、MAC 和已知 VLAN 学习域分组。不同 VLAN 的相同 MAC 不直接合并。无 VLAN 的 ARP 只有在该 MAC 存在唯一已观测 VLAN 域时才能关联；多域时保留未知域与 `VLAN_DOMAIN_AMBIGUOUS`。
5. FDB 仅消费 `LEARNED` 行。没有真实接口映射时保留节点并标记 `INTERFACE_MAPPING_MISSING`。已观测邻居连接上的上联学习不会成为终端接入边，标记 `UPLINK_LEARNING_SUPPRESSED`。多个未排除的接入路径标记 `MULTIPLE_ACCESS_PATHS`，不任意选一条。
6. 唯一的非上联 FDB/DHCP 端口形成二层推断；ARP + FDB 可标记 `CORROBORATED`，单一证据为 `UNCONFIRMED`。两者都不是确认线缆。ARP 单独形成三层关联，默认界面关闭此层。
7. 相同域内同 IP 不同 MAC、资产标识互相矛盾、疑似代理 ARP 的路由器 MAC，以及共享虚拟路由 MAC 标记 `CONFLICT`，抑制不可靠的推断边。多 IP 本身只标记 `MULTIPLE_ADDRESSES`，不自动判定攻击或错误。

共享 VRRP MAC 是虚拟路由身份，不能当作唯一物理设备；实现识别标准 VRRP IPv4/IPv6 前缀及常见 HSRP 前缀。[RFC 9568 §7.3](https://www.rfc-editor.org/rfc/rfc9568.html#section-7.3)。以上去重、置信度和连线抑制是本系统的保守推断策略，并非协议保证能够证明直接线缆。

## 展示与状态

`GET /api/v1/topology?view=PHYSICAL|ALL&deviceId=&siteId=&vlanId=&limit=200`。

API 默认 `PHYSICAL`，严格只返回物理关系；前端请求 `ALL`，默认显示物理及二层推断，三层关联由独立开关控制。旧手工逻辑关系仅在 ALL 中可见。发现节点没有关联边时仍保留。图中几何形状用于设备类型，颜色来自真实资产状态；发现节点为 `UNKNOWN`，`FRESH` 只表示证据新鲜。

已登记但离线的 BMC 会按管理地址被复用，保持原有 `OFFLINE/UNKNOWN` 与最后观测时间，不会因为交换机缓存该地址而变成在线。发现名称使用邻居名或 MAC 尾部标签，地址在独立 `addresses` 字段，不追加到设备名称。

节点、边均携带 `confidence`、`qualityFlags`、`vlanIds` 和最多 8 条 `evidence`（协议、来源设备、接口、OID、时间、说明）。`registered=false` 节点不得跳到不存在的设备详情。VLAN 可多选归属，空数组表示未知，绝不伪造 VLAN 0。`Topology.vlans` 的计数只针对本次真正返回的节点/边，不能当作组织全量统计。未观测 egress/untagged 集合不会被补成空端口列表或虚构 trunk/access 配置。

## 查询预算和局限

| 层级 | 上限/策略 |
| --- | --- |
| 资产候选 | 512；请求设备优先；有超限标记 |
| 首选来源 | 128；SNMP 优先；每条传输最多 256 KiB |
| 每事实表 | 512 行；JSON 最大深度 12，单字符串 4096 字符 |
| 全局端点事实 | 16,384；端点 4,096；内部边 8,192 |
| 返回 | 200 节点、400 边；稳定 ID 与排序；只返回存在的端点 |
| SQL | 3 秒；先有界物化设备键再 JOIN JSON，避免大 JSON 参与 filesort |
| 请求 | 每实例 2 个并发；超额返回 `429 TOPOLOGY_CAPACITY`；总超时 8 秒 |
| discovery 写候选 | 原有最多 8 个来源、256 事实/来源、2048/次、10 秒；不自动注册 |

地址/MAC/域索引关联避免事实逐行扫描整个资产表。超限、无法解析、权限不可见、表未支持和采集预算不足均返回质量标志。未取得 LLDP/CDP 的中间交换机或桥仍可能存在，因此 FDB 推断边不能宣称为完整物理拓扑。不同站点/VLAN 的地址重复不会被当作同一设备；无 VLAN 事实无法可靠区分多个未标识 VRF，此时不会强行选择已知域。

旧持久 LLDP 缓存仅作兼容投影，后台最多 128 条、每条 256 KiB；当前查询不依赖旧 `observed-*` 行，而从当前启用证据重建连接，避免禁用来源残留边。

## 回归覆盖

`TopologyInferenceTest` 覆盖 ARP/FDB/VLAN 联合、共享 FDB、多 VLAN MAC、重复 IP、VRRP/代理 ARP、未知 LLDP 与 FDB 去重、上联抑制、多交换机路径、离线资产复用、DHCP 到期、无接口映射、IPv6 等价、租户/站点/设备范围和输出预算。`WorkbenchIntegrationTest` 使用真实 MySQL 验证首选来源、超大 JSON、站点/设备授权和不写 CMDB。`DiscoveryIntegrationTest` 验证新的 SNMP ARP/DHCP 事实进入现有去重工作流，并排除本机/过期记录。UDP 驱动回归与具体 OID、设备覆盖另见 `SNMP-ENDPOINT-EVIDENCE.md`。
