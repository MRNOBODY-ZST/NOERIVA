# 数据源与存储归属

同一观测不应默认同步写入全部引擎。当前切片与目标架构分开列出；原始证据、保留和检索覆盖必须显式声明。

| 数据源/类别 | 主路径与权威归属 | 当前状态 | 必须保留的质量/安全语义 |
|---|---|---|---|
| 控制配置、权限、告警处置 | MySQL + 同事务 outbox | 实验性局部实现 | 组织范围、已提交版本、审计、不读落后副本伪装回退 |
| 低频当前状态观察 | HTTP→Kafka→MySQL checkpoint / ClickHouse history; Redis可重建 | 实验性局部实现 | 原始观测时间、源epoch/sequence、拒绝旧覆盖、独立host/BMC新鲜度 |
| 数值热指标 | vmagent→VictoriaMetrics | 实际本地引擎查询已验证；实机源未验证 | 有界标签/队列、原始时间、覆盖率与浮点精度 |
| 带宽汇总 | VM raw export→bounded Java worker→ClickHouse | 实际引擎链路已验证 | 完整替换revision、有效时长、源时间、部分缺口与校正 |
| Syslog / unknown traps / text | Kafka→ClickHouse; selective redacted Elasticsearch | DESIGNED | 保留原始OID/varbinds、解析器版本、明确文本检索期限 |
| Flow / NAT / identity histories | Kafka→typed ClickHouse lifecycle/intervals | DESIGNED | 正确时态、源namespace、candidate generation、不能假NO_MATCH |
| 配置与原始证据 | 受支持S3 ObjectStore; MySQL metadata/manifests | DESIGNED | PENDING与VERIFIED分开、SHA256、原始批次位置、hold/保留规则 |
| Redis缓存与SSE | 仅可重建当前表示和有限通知 | 实验性局部实现 | 不得成为唯一审计真相；权限撤销缓存命中仍须拒绝 |
| synthetic demo | 显式demo profile下确定性数据 | 自动化测试 | SIMULATED标记；不能充当真实设备或生产性能证据 |
| application self-observability | Actuator/Micrometer/OTel/Grafana | 部署与接口骨架；按运行验证报告列证据 | 健康、延迟、队列、错误、缺口；不得暴露源凭证 |
