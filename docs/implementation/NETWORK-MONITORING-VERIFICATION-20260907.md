# 网络、Application 与 NAT 实机验收 · 2026-09-07

两台服务器 `192.168.4.62`、`192.168.4.63` 已部署并启用真实监测。验收时间为北京时间 13:52–13:54，未向目标环境注入 Mock 数据，也未清空设备 NAT 转换。

## 已启用能力

| 设备 / 模块 | 实际来源 | 验收结果 |
|---|---|---|
| Dell S6100-ON / OS9 9.14(2.23) | SNMPv3，只读 SHA/AES128，60 秒周期 | 81 个接口、CPU/内存传感器；1h/24h CPU、内存、收发带宽历史有效 |
| Cisco ASR1002-X / IOS XE 17.9.8 | SNMPv3，只读 SHA/AES128，60 秒周期 | 15 个接口、17 个传感器；1h/24h CPU、内存、收发带宽历史有效 |
| Cisco Application | 独立 NBAR 采集，ifIndex 8=Te0/1/0、9=Te0/3/0 | 60 秒周期；每接口 128 个协议行、IN/OUT 各一份，共 512 个方向观测；四组分别查得新鲜差分 |
| Cisco NAT 审计 | Loopback101 `192.168.253.1` → `.62:32055/UDP` | NetFlow v9 domain 200 / template 256；去重落库 19,239 条 CREATE/DELETE 事件；Kafka 有数据分区 offset 950/950、lag 0 |

两个设备的 SNMP 网络 ACL 已精确放行 `.62`、`.63`，并保留原有 `.61`。Cisco 只读视图已补齐必要标准与厂商子树。Cisco 上、下联原有 NBAR 配置沿用，NAT 日志导出目标已更新；两台设备均保存配置。

## 部署与测试证据

- Helm revision 7；控制 API 两副本、前端两副本分布两台节点，worker 单副本位于 `.62`。全部 5 个应用 Pod 就绪且重启计数为 0；5 个数据服务就绪。
- 后端镜像 `noeriva/control:20260907-monitoring-r3`，amd64 manifest `sha256:344d12dd55ae4632a695955b565a73e5ee72e558f986f22bb3a1ca84178a4dcb`。
- 前端镜像 `noeriva/console:20260907-monitoring-r2`，amd64 manifest `sha256:4e27cd439de35dadc5f6ccc39b8b234ca56b074886d4cc222d69b7a30df14e85`。
- MySQL schema V9；原有与新增共 8 条设备连接均启用、180 秒内均有成功读取，outbox 无待发布记录。Application 与 NAT 各自独立启用。
- 后端完整构建检查及后续定向回归累计 294 项：293 通过，1 项可选外部集群测试跳过；前端 80 项通过，构建成功。完整 production JAR 已验证启动及应用/NAT API，并启用 UDP 生命周期检查。
- 两台入口的 40 次真实混合查询，8 路并发、0 错误，P50 115.24 ms、P95 254.34 ms、最大 270.94 ms。此为有界冒烟负载，不是系统极限吞吐量。
- ComputerUse 实测两个入口登录和应用查询；应用接口/方向/名称筛选、NAT 协议/私网地址组合筛选、来源详情、设备独立入口及 24h 带宽图均正常。两浏览器验收页错误日志为空。

此次修复了三个验收中暴露的问题：production Kafka factory 与监听器的循环依赖；新接入设备的 24h 指标网格漏掉最新样本；首接口可能耗尽 NBAR 总预算。循环依赖曾导致 revision 4 启动失败，已回滚恢复采集后补生产上下文回归，再部署修复版本。NBAR 当前实机在 r2 已有双接口数据，公平预算修复用于避免首接口协议数量增长后饿死后续接口。

## 数据与能力边界

SNMP 保留 PARTIAL 及设备实际返回的 ENTITY/SENSOR 限制、弱接口身份等质量标记；缺失指标不补零。设备级接口合计可能包含逻辑与物理接口重叠。24h 查询非空不等于已经采集满 24 小时。

NBAR 当前达到 256 协议行总上限，界面显示 `NBAR_ROW_LIMIT`。公平预算保证所选启用接口有额度，仍是各接口按 OID 前缀的有界观测，不代表全部应用覆盖或轮转。NBAR 是接口/应用汇总，不提供客户端 IP、会话数或用户归因。

NAT 平台首条持久化事件为北京时间 13:41:25.977。开启前历史没有补采。13:52 验收时累计有 136 次未知模板观测，包含接收器更新后重学模板的阶段；报文序列无跳号、解析错误/队列丢弃为 0 也不能证明无丢失。UDP 无密码学认证、不保证完整捕获，界面与事件保留这两个质量标记。累计 accepted/persisted 是异步运行计数，重试和刷新时序可能使两者暂不相等；本记录的 19,239 为 ClickHouse FINAL 去重计数。

数据服务仍为单副本和本地磁盘；`.62` 是数据与 NAT 接收故障点。两台 HTTP 入口可用不等于完成数据服务高可用或故障切换验收。离线第三节点 `.61` 未操作。默认未设置自动删除 NAT/Application 历史，容量和保留策略需显式制定。

新模块接口以 [Application API](../devices/APPLICATION-MONITORING-API.md) 和 [NAT API](../devices/NAT-AUDIT-API.md) 为契约；当前生成 OpenAPI 尚未包含这两个模块。

## 复验与材料

安全传入 `NOERIVA_BOOTSTRAP_PASSWORD` 后运行 `scripts/verify-network-monitoring.py --output <路径>`。脚本只读，验证两设备新鲜指标历史、双接口双方向差分、NAT 来源/时钟/持久化及有界并发。独立开关的隔离行为由集成测试验证，实机验收读取启用状态，未为了测试主动停止 NAT 接收。

- [API 验收数据](network-monitoring-20260907/live-api-verification.json)
- [运行与存储证据](network-monitoring-20260907/storage-runtime-evidence.json)
- [构建与测试记录](network-monitoring-20260907/build-verification.json)
- [20 万条合成 NAT 查询基准](network-monitoring-20260907/synthetic-nat-query-benchmark.json)：时钟乱序，256 粒度与两阶段 FINAL 查询读取约 56 MB，未提高 128 MiB 上限；不是生产压测。
- [运维与停用说明](../operations/NETWORK-APPLICATION-NAT-MONITORING.md)
- [逐页功能与目的](../frontend/CLARITY-PAGE-PLAN.md)
