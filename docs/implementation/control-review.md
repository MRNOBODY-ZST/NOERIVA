# 控制面实现复核

2026-09-06。复核当前 `services/noeriva-control`、Compose、Helm 与 V5 P05–P06 / P64 / H10 部署边界。检查范围是本轮已有的组织隔离、身份认证、低频状态投影、结构化事件、控制事务、查询与 rollup 调度，不代表 V5 全部后续阶段已经实现。

## 已识别并落实的修正

| 问题 | 当前代码 / 配置处理 | 验证边界 |
|---|---|---|
| 同一设备的健康 host 覆盖 critical BMC | 从各来源状态取最高健康严重度，来源身份限制为五个受支持值 | 增加真实 MySQL 投影与重放测试 |
| 最大 sequence 在 ClickHouse revision 加一后溢出；过早时间不能写入 MySQL | 验证 sequence 上限、2000 年时间下限与未来 120 秒上限 | 策略单测已有；HTTP 验证由应用测试补充 |
| 事件数组允许 null 元素 | 元素标记 `@NotNull @Valid` | 接口测试覆盖 |
| ClickHouse HTTP 200 错误正文可能被当作成功 | 同步插入设置 `wait_end_of_query=1`，非空响应正文作为失败 | 数据库写入与故障注入仍应在联调中验证 |
| WARNING alert 后续 CRITICAL 未升级 | 升级严重度、递增 revision 并重新打开已确认告警 | 增加真实 MySQL 升级与乐观锁测试 |
| vmagent 未认证抓取受保护 actuator | 专用 METRICS 用户；本地生成只读密码挂载；Helm 使用现有 Secret | 实际 production API：metrics 抓取返回 200，读设备返回 403，匿名抓取返回 401 |
| 每次 rollup 扫描只处理首批接口 | 保存分页游标，每轮最多 32 个接口、每接口两个方向，顺序执行；禁用时不建调度器 | 不宣称长期积压、吞吐或故障恢复验证 |

## 持续检查项

1. **真实数据库发现的两处运行缺陷已修正。** 告警 UPSERT 显式限定 `alert.revision` 等字段，消除了 MySQL 1052 并恢复 WARNING / CRITICAL 投影；revision 分配改为单条 `INSERT ... ON DUPLICATE KEY UPDATE` 后在同一事务读取，消除了并发共享锁升级导致的 MySQL 1213。修复后 `MySqlRepositoryIntegrationTest` 的 9 个用例全部通过，无失败、错误或跳过。
2. **Rollup 完整性证明必须允许撤销。** 集成测试包含 `completeThrough=null` 和完整前缀 / 部分尾部两种纠正，当前两种情况均通过；旧 revision 不能恢复已撤销的完整区间。这样可防止查询错误选择尚不完整的汇总层。
3. **事件标识具有不可变身份前提。** 当前历史查询按组织与时间 / 设备约束之后再按 event ID 选择最新 tuple。采集方必须为不同观察使用不同 event ID，并在重试时保留设备、观察时间和内容；当前实现不提供跨存储的事件身份注册表。这个前提应保留在采集协议与复核清单中。
4. **生产数据平面由外部运维。** Helm 默认拒绝数据平面出站连接，部署方必须填写真实目的 selector / CIDR 与端口、TLS、证书、Secret，以及 MySQL / Kafka / Redis / VM / ClickHouse 的 HA、容量、备份恢复策略。渲染成功不等于已经部署或证明 HA。

## 已检查的有效边界

仓储查询绑定组织 ID，跨组织设备读取为空、写入失败；设备创建、outbox、审计在同一 R2DBC 事务中；告警确认使用组织与 revision 条件；设备行锁串行化来源替换。会话使用 Redis 中的摘要 token 和过期时间，鉴权读取当前账户状态。Kafka 消费失败进行有限重试后发送到持久 DLQ，隔离发送失败保留源消息重试；这仍要求生产运营监控与重放 DLQ。outbox 是至少一次发布，消费方必须按事件 ID 幂等处理。

Compose 仅绑定 loopback，数据卷保留；Helm 仅包含应用工作负载，含探针、资源界限、HPA、PDB、受限安全上下文与 NetworkPolicy。API 调度明确关闭，独立 worker 固定单副本、Recreate 更新且不受 HPA 影响；worker 只处理指定组织。Kubernetes、实际设备采集、NAT 中继、Ceph/WORM、生产恢复和容量测试均未在本轮执行。

MySQL 仓储集成测试使用独立 Testcontainers 实例及正式 Flyway migration，不依赖本地 COMPACT 业务数据。执行结果由测试报告记录，不能以此文档代替测试通过证据。
