# 拓扑发现与告警队列一致性（2026-09-09）

## 告警数量的含义

现场修复前，运行总览 `activeAlerts=2`，两条记录均为 `ACKNOWLEDGED`，而告警队列默认查询 `OPEN`，因此侧栏数字与空队列同时出现。其中一条是离线 iMana 的 SNMP 采集告警，另一条是停用 SSH 前留下的历史采集告警。这不等于存在两个待确认错误。

新增 `GET /api/v1/alerts/summary?deviceId=`，按组织和可选设备精确返回 `open`、`acknowledged`、`resolved`、`active` 与 `asOf`；`active=open+acknowledged`。生产统计直接聚合状态索引，不受列表的 50 条分页影响。指定不属于当前组织的设备返回 404。侧栏徽标使用 `open`，队列保留已确认和已恢复的记录，不将“确认”写成“故障恢复”。

## SNMP 证据与权限

继续使用现有 SNMPv3 只读账号；没有使用设备 SSH。Dell 现场返回 41 条旧 ARP、45 条 ipNetToPhysical、43 条 FDB/Q-FDB、5 条 bridgePort 到 ifIndex 映射和 5 项 VLAN 映射。不同表中同一条证据需要合并，不相加当作设备数量。

Cisco 的原 `ARGUS-NBAR` 只读 view 没有 IP、BRIDGE 与 DHCP Snooping MIB。在证书 SHA-256 固定校验后的 HTTPS 管理接口中增补 `1.3.6.1.2.1.4`、`1.3.6.1.2.1.17`、`1.3.6.1.4.1.9.9.380` 三个读取范围，保留原账号、安全等级与来源 ACL。配置保存返回 `[OK]`，随后读取 view 确认生效。授权范围增加并不证明具体机型一定实现全部表。

FDB ID 与 VLAN ID 需要通过映射表关联，同一 FDB 可以被多个 VLAN 共享；bridgePort 也需要转换为 IF-MIB ifIndex，不能把索引直接当成接口或 VLAN。依据：[IETF RFC 4363](https://datatracker.ietf.org/doc/html/rfc4363)。DHCP Snooping 绑定的 MAC、IP、VLAN 与接收接口可以作为关联证据，但存在绑定并不证明终端仍在线。参考：[Cisco DHCP Snooping 操作指南](https://www.cisco.com/c/en/us/support/docs/ip/dynamic-host-configuration-protocol-dhcp-dhcpv6/217055-operate-and-troubleshoot-dhcp-snooping.html)。

## 发布与验收

2026-09-09 15:57（Asia/Shanghai）开始滚动发布，Helm revision 13 部署成功；控制服务与前端在 `.62`、`.63` 各一个副本，采集 worker 在 `.62`。控制服务及 worker 镜像为 `20260909-topology-r1`。最终 Helm revision 17，前端镜像为 `20260909-topology-r4`，入口 `index-g0W6nUfe.js`，两节点均返回同一版本。仅更新应用，保留现有数据服务、卷、账号与采集记录。

- 后端 `./mvnw -q verify`：418 项中 417 项通过、0 失败；1 项需要 `NOERIVA_LIVE_QUERY_TEST` 环境的外部指标管道测试按条件跳过。真实 MySQL / Redis / ClickHouse 等集成用例包含在本轮执行中。
- 前端 21 个文件、111 项单元测试通过；类型检查与生产构建通过。使用隔离的真实 demo API 和生产构建运行 7 项 Playwright 流程，全部通过。测试启动使用 build + preview，避免开发服务器首次依赖优化引发整页刷新和内存会话丢失。
- OpenAPI：82 个操作、74 条路径、51 组 DTO 字段精确校验通过。
- 双节点现场 API：36 项检查通过，包括告警计数、物理默认类型、VLAN 筛选、唯一节点 ID、合法连接端点、证据来源、离线资产保留和 SSH 连接禁用状态。
- 15:59 快照：51 个节点、2 条邻居协议观测连接、44 条二层推断；启用三层关联时共有 88 条关系。VLAN 为 1、3、4、7、254，VLAN 4 的默认视图为 35 个节点、34 条二层推断。数量随真实缓存和采样变化，不作为固定资产总数。
- 告警现场状态：`open=0`、`acknowledged=2`、`resolved=5`。Computer Use 确认侧栏没有旧的 2 个 Error 徽标，空队列明确提示另有 2 条已确认未恢复；切换状态可读到两条原记录。
- Computer Use 已检查 VLAN 分组、列表替代、接入端口与原始 OID 展开、未登记终端不跳转到无效设备详情，以及默认启用力导向、二层推断开启和三层推断关闭。
- 真实采集验收：Dell 最新地址/FDB/VLAN 为 46/44/5，Cisco 地址为 6；原有接口、传感器、温度、功率与健康采集保留。Cisco 仍没有 FDB/VLAN/DHCP Snooping 实机条目，空表不会生成虚构记录。

密集图在超过 20 个节点时自动收起终端名称和端口文字，悬停、选择、列表与检查器保留完整信息，并提供自动/精简/完整标签选项。“适应视图”使用公开 SVG 几何边界和 `graphRoam` 平移缩放，有界校正节点形状与位置的缩放差异。51 节点浏览器场景在 846×460 画布中达到 0 个节点形状越界、至少 24 px 留白，手动缩放后刷新保留视角。默认使用物理力导向；切换“圆形排列”使用原生静态圆形布局，拖拽沿圆环调整，不承诺冻结原力导向坐标。

最终 Computer Use 现场复验：1303×460 画布中 51 个节点，默认物理布局自动适配及圆形布局手动适配均为 0 个节点形状越界；告警仍为 0 项待确认、2 项已确认未恢复。两节点最终 36 项 API 检查再次全部通过。

发布期间，严格构建权限导致一个候选前端镜像的首页无法由非 root Nginx 读取；健康检查阻止其接流量，发布自动回滚。两个前端 Dockerfile 已显式将静态资源与启动文件归属 UID/GID 101，修正镜像通过非 root 容器首页与 JS 的 HTTP 读取测试后成功发布。

协议依据、查询预算与算法局限详见 [关联算法](../devices/TOPOLOGY-CORRELATION.md) 和 [SNMP 表支持](../devices/SNMP-ENDPOINT-EVIDENCE.md)。原始网络清单、浏览器测试痕迹、部署临时材料及凭据文件均位于 Git 忽略目录，不纳入提交。
