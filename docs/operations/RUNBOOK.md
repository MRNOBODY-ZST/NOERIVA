# NOERIVA 首版运行手册

适用范围：本仓库的设备运营、状态事件、指标查询与带宽汇总切片。COMPACT 是单机集成环境，生产应用通过 Helm 连接独立运维的数据服务。示例命令在仓库根目录执行。

## 启停与健康检查

```sh
python3 scripts/init-env.py
docker compose --env-file .env -f deploy/compose/compose.yaml up -d --build
docker compose --env-file .env -f deploy/compose/compose.yaml ps
curl --fail http://127.0.0.1:18080/actuator/health/readiness
curl --fail http://127.0.0.1:18000/health
docker compose --env-file .env -f deploy/compose/compose.yaml logs --tail=100 control worker
```

普通停止使用 `docker compose --env-file .env -f deploy/compose/compose.yaml down`，保留命名卷。不要把 `down -v` 用作重启或修复操作。`.env` 由脚本以 0600 创建，不覆盖已有凭据；不要输出、提交它或把展开后的 Compose 配置写入工单。控制台管理员密码见本机 `.env`，演示凭据只用于显式 `demo` 配置。

`control` 的汇总调度关闭，`worker` 单独启用。一个组织指定一个 worker；不要用 API 的 HPA 扩展后台调度。worker 无主机端口，启动后可从容器日志、受保护指标和补算任务状态观察进度。数值指标写入 VictoriaMetrics；低频状态事件经 Kafka 写入 ClickHouse 历史并投影到 MySQL。设备当前状态读 MySQL，不依赖历史扫描。

## 登记与数据接入

1. 用管理员账号登录，同源控制台会在内存中保存最长一小时的临时 Bearer token。刷新页面后重新登录。身份来自服务器，客户端不能自行选择组织。
2. 在设备页登记资产；接口可通过 `POST /api/v1/devices/{id}/interfaces` 登记。新资产为 UNKNOWN，直到存在真实观测。
3. 采集账号仅调用 `POST /api/v1/ingest/batches`。每批最多 500 条；重试保留事件 ID、epoch、sequence 和原始观测时间。来源当前限制为 `primary/host/bmc/network/system`。只有 Kafka 确认接收后响应 `202 DURABLY_QUEUED`；此时查询投影可能尚未追上。超时或部分失败应以相同标识重试。
4. 数值指标按 [查询报告](../implementation/query-report.md) 的名称和标签写入 VM。不要将高频采样写入状态事件，也不要把查询时间替代来源采样时间。当前版本没有真实 SNMP/Redfish/Agent 适配器，应先在测试环境验证自己的采集程序。

详细 DTO 与限制见 [API 契约](../implementation/API-CONTRACT.md) 和 [OpenAPI](../../schemas/api/openapi.json)。内部自动化的 JSON 写请求需要 `X-Noeriva-Request: 1`；不得把管理员密码传给第三方前端或存入浏览器持久存储。

## 指标补算

近期调度只处理已关闭、延迟 90 秒的最近窗口。长期停机、三小时以前的迟到数据等需要明确补算。管理员通过 `POST /api/v1/query-jobs` 提交如下请求，随后用返回的 ID 查询 `GET /api/v1/query-jobs/{id}`：

```json
{
  "deviceId": "registered-device-id",
  "interfaceId": "registered-interface-id",
  "from": "2026-09-01T00:00:00Z",
  "to": "2026-09-02T00:00:00Z",
  "direction": "rx"
}
```

每个请求最多一天，起止必须 UTC 五分钟对齐，结束不能在未来；上例只有在对应原始数据仍存在时才能成功。组织最多 100 个活跃任务。任务状态为 PENDING/RUNNING/SUCCEEDED/FAILED，返回已写桶数和有限错误码。先验证小窗口，按天、方向逐项扩展，不要无界批量排队。

worker 在数据库中领取租约；崩溃后的 RUNNING 任务在两分钟租约过期后可恢复，最多领取三次。正常运行的源不可见、查询超时等错误会记录 FAILED，不会伪造零值或无限重试。排除原因后重新提交相同窗口即可生成更高修订的完整替换桶。ClickHouse 写入确认之前不推进 MySQL 检查点；同一窗口只读最新修订。成功处理与完整覆盖分开记录，部分数据不能证明完整性。自动发现全部历史脏窗口仍未实现。

## 故障定位与恢复

| 现象 | 检查与处理 |
|---|---|
| HTTP 429 | 检查对应 API/图表/接入的并发和超时，尊重 `Retry-After` 并带抖动退避；降低客户端扇出。不要直接无限扩大线程池和队列。 |
| HTTP 503 / 图表提供者失败 | 检查 VM/ClickHouse 连通性、查询预算、负载、认证与权限。保留错误和请求 ID；不要转换为正常空结果。 |
| 已接收事件没有成为当前状态 | 检查 Kafka consumer lag、control/worker 日志、CH 插入错误和 MySQL 锁/连接池。旧 epoch/sequence 被拒绝更新是正常幂等行为，历史可能仍保留。 |
| 某来源过期但其他来源正常 | 比较来源自己的 `observedAt` 与 epoch/sequence；分别排查采集。新鲜 host 不会擦除陈旧 BMC 的异常。 |
| 热力图为空或 PARTIAL | 核查接口标识、`source_id`、时间范围和原始 counter；检查 worker、补算状态和覆盖证明。未采集为 null，不能当成 0 带宽。 |
| worker 重启后任务仍 RUNNING | 等待租约过期并查看重领次数。达到上限记录 LEASE_EXHAUSTED；先处理根因，再提交新的受限任务。 |
| 登录失败或实例切换后 token 失效 | 检查 Redis 和数据库用户状态。token 有期限；服务器会重查权限，角色撤销不依赖 token 到期。Redis 故障不绕过认证。 |

Kafka 消费失败经过四次有限重试后写入 `noeriva.events.dlq.v1`，隔离发布失败时不应确认原消息。需要监控 DLQ 新增、消费者积压和 outbox 最老未发布记录。隔离记录应保留原事件与异常上下文，限制访问；先验证修复并保留备份，再由受控运维流程按原 ID 重放到原主题。本版不包含自动 DLQ 重放按钮，不要盲目重置整个 consumer group offset 或清空主题。MySQL/CH 是分别提交的幂等 sink，不能按“全局 exactly-once”推断状态。

## Kubernetes 上线

按 [部署指南](../../deploy/README.md) 配置镜像摘要、外部数据库、Secret、TLS、数据出口 NetworkPolicy 与观测系统。先在隔离 namespace 完成真实 CNI、DNS、TLS、连接池、探针和终止行为验证，再接入流量。MySQL Flyway 在应用启动时执行 V1–V3 并使用迁移锁；ClickHouse 数据库和表由 [指标汇总 SQL](../../deploy/compose/clickhouse/initdb/001-metric-rollups.sql) 和 [事件 SQL](../../deploy/compose/clickhouse/initdb/002-control-events.sql) 预建。部署账号要有适合迁移的权限，生产需另行拆分迁移与运行账号。

```sh
helm lint deploy/helm/noeriva -f deploy/helm/noeriva/ci-values.yaml --strict
helm upgrade --install noeriva deploy/helm/noeriva \
  --namespace noeriva-system --create-namespace \
  --values /secure/noeriva-production.yaml
kubectl -n noeriva-system rollout status deployment/noeriva-control
kubectl -n noeriva-system rollout status deployment/noeriva-console
kubectl -n noeriva-system rollout status deployment/noeriva-worker
```

上面假定 release 名为 `noeriva` 且没有覆盖资源名。验证稳定后再进行副本扩缩容和故障域演练。Helm 回滚只回滚应用清单，不回滚数据库迁移；升级前验证旧应用与新 schema 的兼容性并准备备份。不要以两个 API 副本或一次 lint 通过来声明生产高可用。

## 监控、凭据与备份

`/actuator/prometheus` 由专用 METRICS 账号保护；该账号不能访问设备 API。观测 HTTP p95/p99、429/5xx、JVM 内存/GC、DB 锁和连接池、Kafka lag/DLQ/outbox、VM 查询/写入失败、CH 资源预算与磁盘、worker 任务状态。Grafana 预置面板提供起点，完整 SLO/通知验收仍需补齐。

bootstrap 用户只在不存在时插入；**修改 Secret 或 `.env` 不会自动更新数据库中已有用户的密码**。轮换应先在受控管理流程中生成 BCrypt 散列并更新目标用户，再同步应用/采集器 Secret 和重启需要读取启动凭据的组件；不要明文写入数据库或工单。当前没有用户管理/密码轮换 UI。每次 API 认证重新检查用户记录，令牌存储在 Redis；对人员离职等情形应先撤销账号或权限。

本次未开启证据 TTL 删除。VM 本地长保留、Kafka 无年龄/容量删除和 CH 无 TTL 都会增长磁盘；监控容量并制定经过验证的保留方案。副本不是备份。生产必须单独备份并演练恢复 MySQL、Kafka/事件重放起点、CH/Keeper、VM 和部署 Secret；记录实际 RPO/RTO。本仓库没有提供已验收的全系统灾难恢复、对象锁或 WORM 证据能力。
