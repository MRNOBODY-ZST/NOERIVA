# 协议矩阵

通用协议能力、实验实现和实机型号验证分开。下面版本假设不代替适配器实现时的官方核验。

| 协议/接口 | 最低假设 | 成熟度 | 覆盖 | 验证 | 限制 |
|---|---|---|---|---|---|
| REST/JSON | versioned /api/v1; bounded DTOs | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | 控制接口、低频状态输入；非高容量审计二进制传输 |
| Prometheus metrics / VM native HTTP | query_range, JSON-line export/import; exact deployed versions in manifests | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | 命名指标与原始计数器汇总；浮点精度显式标记 |
| Kafka | KRaft; idempotent producer; acks=all | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | 低频状态/outbox；COMPACT 单 broker 不提供 HA |
| ClickHouse HTTP | typed versioned rollups; synchronous inserts; actual26.8 local integration | EXPERIMENTAL | PARTIAL | INTEGRATION_TESTED | 查询/写入限制与显式取消；非官方 Connect 高容量认证 |
| SSE | scoped snapshot-required invalidation feed | EXPERIMENTAL | PARTIAL | FIXTURE_TESTED | 有界通知；不是原始事件总线 |
| SNMPv3 / traps | v3 authPriv preferred; per-device MIB verification | DESIGNED | NONE | NOT_TESTED | 未知 Trap 必须保留 OID/varbinds；尚无实机适配器 |
| Redfish | HTTPS/OData schemas verified per BMC firmware | DESIGNED | NONE | NOT_TESTED | 仅读取；不提供带外电源写操作 |
| IPMI | Explicitly approved compatibility source only | DESIGNED | NONE | NOT_TESTED | 不视为所有 BMC 通用支持 |
| LLDP / CDP | Source-specific identity and observation timestamps | DESIGNED | NONE | NOT_TESTED | 当前图展示库存连接，不等于自动发现已实现 |
| NETCONF / RESTCONF / gNMI / OpenConfig | Read-only verified model/version | DESIGNED | NONE | NOT_TESTED | 按适配器核验；禁止默认设备写入 |
| Syslog UDP/TCP/TLS | RFC/vendor parser profile and timezone assumptions | DESIGNED | NONE | NOT_TESTED | UDP 接收前可能丢失，不能承诺零损失 |
| NetFlow v9 / IPFIX | Templates/options, sequence, exporter epoch, sampling | DESIGNED | NONE | NOT_TESTED | 模板与原始来源证据必须保留 |
| NetFlow v5 / sFlow | Only after approved initial adapter scope | DESIGNED | NONE | NOT_TESTED | 不将采样流量视为完整会话审计 |
| RADIUS / TACACS+ / VPN accounting | Vendor/version/session lifecycle verification | DESIGNED | NONE | NOT_TESTED | 身份与命令均需专门权限及时间不确定性 |
| DHCP / DNS logs | Server-specific lifecycle/parser contract | DESIGNED | NONE | NOT_TESTED | 当前地址不能替代历史归属 |
| Linux auditd/journald/auth/sudo | OS/parser versions and redaction policy | DESIGNED | NONE | NOT_TESTED | 不得泄漏敏感命令参数 |
| Windows Event Log / WEF | Windows version/event IDs/subscription policy | DESIGNED | NONE | NOT_TESTED | 未连接实际 Windows 主机 |
| Kubernetes audit / inventory | Audit policy/API version/RBAC verified | DESIGNED | NONE | NOT_TESTED | 应用 Helm 部署不等于 Kubernetes 监控已实现 |
| OTLP / OpenTelemetry | Collector/protocol compatibility pinned before enablement | DESIGNED | NONE | NOT_TESTED | 应用自身遥测与完整应用拓扑能力分开 |
| S3 ObjectStore | Provider/version/retention-lock behavior verified separately | DESIGNED | NONE | NOT_TESTED | S3 接口兼容不等于 WORM 已验证 |
| High-volume Protobuf / Kafka Streams | Checked-in descriptors and compatibility/late-data tests required | DESIGNED | NONE | NOT_TESTED | 不把当前低频 JSON 接入宣称为完整高容量链路 |
