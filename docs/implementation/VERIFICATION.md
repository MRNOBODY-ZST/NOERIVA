# 首版交付验证

本文件保留首版及早期缺陷回归的历史验证记录。后续澄明工作台重构的逐页验收见 `docs/frontend/CLARITY-VERIFICATION.md`；下表测试计数不代表后续版本的最终计数。

日期：2026-09-06。工作目录：`/Users/hades/NOERIVA`。依据 V5 主文档要求，交付首个可运行设备运营与查询纵向切片，以及可复用的 Kubernetes 应用部署。完整 Phase 0–13 范围见能力矩阵；本报告不把规划项计为已实现。

## 自动化与实际环境结果

| 验证 | 结果 | 证据与边界 |
|---|---|---|
| Maven `verify` | 70 测试，0 失败、0 错误、0 跳过；可执行 JAR 构建成功 | [逐测试套件统计](backend-test-results.json)。查询 40 项，控制面 30 项；此次显式启用真实 VM/CH 集成测试。 |
| MySQL 集成 | 14 测试通过 | MySQL 8.4.11 Testcontainers，验证 V1–V3、事务/组织隔离、重复投影、告警修订、并发汇总版本、覆盖撤销、补算队列/租约/审计。 |
| 查询算法与 HTTP 适配器 | 通过 | UInt64、重置/回绕/间隙、原始时间、完整替换、DST 23/25 小时、半小时时区、168 格、限流释放和 CH HTTP 200 内部失败；见[查询报告](query-report.md)。 |
| 前端 | 16 单元/组件测试、6 真实 API 浏览器流程通过；类型检查与生产构建通过 | [浏览器报告](../frontend/VISUAL-REVIEW.md)。演示数据由真实后端的显式 DEMO profile 提供，浏览器没有接口拦截或错误时伪造数据。 |
| 响应式与可访问性复核 | 390/768/1280/1440/1920 宽度，无测得页面横向溢出；桌面行高 44px | [截图与测量](../frontend/screenshots/)、[可访问性说明](../frontend/ACCESSIBILITY-REVIEW.md)。包含明暗模式复核，不声明完整 WCAG 认证。 |
| 容器控制台真实数据查询 | PASS | [浏览器记录](../frontend/connected-browser-smoke.json)：CONNECTED 登录、库存前缀筛选/设备跳转、ClickHouse 800 bps 热力图与可访问表格一致，无页面/控制台错误、无认证持久存储。只读操作。 |
| 状态接入全链路 | PASS | [结果](production-smoke.json)：OCI API → Kafka → ClickHouse 历史/MySQL 当前状态，实际登录、登记接口、重复接收、告警确认、未采集热力图为 null。输入明确标为 synthetic。 |
| 历史补算全链路 | PASS | [结果](rollup-job-smoke.json)：三小时前的一小时原始 VM counter → MySQL 持久任务 → 独立 worker 容器 → 12 个 CH 汇总桶 → 一个 800 bps 热力图格，其他 167 格保持缺失/未来。 |
| OCI / COMPACT | 镜像构建与应用运行通过 | 非 root、只读根文件系统、撤销 capabilities；API/worker 就绪、控制台和受保护指标正常；[部署验证](../../deploy/VALIDATION.md)。 |
| Kubernetes 清单 | Helm 严格 lint、默认/Ingress/禁用 worker 分支渲染、namespace Kustomize 通过 | 静态清单验证，未连接真实 Kubernetes 集群。生产镜像仓库、Secret、TLS、CNI 和外部数据服务仍需由部署环境提供。 |

普通 `./mvnw verify` 不需要既有 COMPACT，但需要 Docker 启动 MySQL 测试容器；只有显式 `NOERIVA_LIVE_QUERY_TEST=true` 并提供正确 VM/CH 端点与凭据时，才执行额外的实际指标引擎测试。它使用唯一合成命名空间，不删除已有记录。真实引擎可能在接收后延迟可见，测试在 25 秒内检查原始样本就绪，再只执行一次补算。

## 性能证据

本机 macOS 26.6.2、arm64、14 个逻辑 CPU，Java API 在主机运行，数据服务位于 Docker COMPACT。1,003 个合成资产；32 个并发客户端；设备前 100 行、一个设备当前概要、告警列表的混合读取。两轮各 1,200 请求全部 HTTP 200。

| 轮次 | 请求/秒 | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| first_pass | 1,021.61 | 18.78 ms | 72.65 ms | 79.52 ms |
| warm_pass | 932.94 | 16.64 ms | 79.55 ms | 85.84 ms |

[原始结果](benchmark-smoke.json)保留查询组合、字节数和环境。first_pass 不是受控冷缓存实验；两轮持续时间短，没有持续写入、长历史、多组织混合流量、故障注入或长期稳定性。这是本地并发冒烟基线，不能用于承诺生产容量或延迟 SLO。容器启动后的功能链路另有独立验证，不能把主机 JVM 的性能数字当作容器配额下的容量。

实现的性能控制包括：当前状态独立表、keyset 分页、批量关联、组织与时间约束、指标名称白名单、查询点数和服务端预算、请求/图表/接入/补算独立准入、有限线程与队列、取消与超时清理、Kafka 幂等接收，以及 CH 完整桶按最新修订选取。Redis 当前保存不透明会话，不宣称已实现完整查询缓存体系。

## 复验入口

```sh
./mvnw verify
python3 scripts/smoke-production.py
python3 scripts/smoke-rollup-job.py
python3 scripts/benchmark.py --requests 1200 --concurrency 32
```

后三个命令需要本机 COMPACT 已运行及本机 `.env`。两个 smoke 脚本会添加清晰标记的合成资产/观测，基准默认不添加资产；`--seed-devices 1000` 是显式添加选项。状态输入与数值输入均不是实际设备。浏览器与 Helm 的完整复验命令分别见前端报告和部署指南。CI 已配置，但本次没有运行远端 GitHub Actions，不能把本地通过写成云端 CI 已通过。

## 仍需验收的范围

- 真实 Edge 身份与采集器、SNMP/Redfish/厂商适配器及设备型号/固件验证。
- NAT/Flow/身份时态关联、完整调查流程、选择性 Elasticsearch、S3 原始证据/签名、对象锁和保留治理。
- 完整告警规则、外部通知/SLO、多组织后台编排、自动历史缺口发现、小时级/其他指标汇总及完整导出任务。
- 生产集群部署、硬件容量、长时间并发压测、服务故障/网络分区、数据恢复与 RPO/RTO。

数据库、主题与卷保留本次测试数据；未执行删除数据的 TTL、生产发布或真实设备操作。后续上线和恢复按[运维手册](../operations/RUNBOOK.md)执行。

## Computer Use 缺陷回归（2026-09-06）

在用户的 Edge 控制台与原生鼠标操作中复现并修复两个问题：未分配 metrics 能力的库存设备不再请求趋势接口，显示“等待监测来源”；VM 缓存对齐和毫秒精度差异不再把六小时查询推到原始范围外。范围校验、点数预算和真实提供者错误仍保留。新增四个前端及四个查询回归用例，当前后端 70 项、前端 16 项通过。

使用明确标为 Mock 的设备验证了搜索/导航、CPU 六小时与内存一小时趋势、RX/TX 与时区切换、热力图时段点击、接口页、拓扑列表与来源检查、事件来源弹窗，以及告警从 OPEN 到 ACKNOWLEDGED 的确认与保留。连接环境的 TX 热力图原生点击显示 2026-09-06 04:00–05:00，15.27 Mbit/s、100% 覆盖。检查结束时没有捕获到浏览器控制台错误。此次 Computer Use 覆盖桌面 Edge；未追加完整移动端或跨浏览器验证。

[Mock 初始化脚本](../../scripts/mock-local.py)可重复使用同一个合成设备、八条六小时数值序列和两个持久补算任务。它不会访问真实设备、删除现有数据或把已确认告警重新打开；本地标识与进度保存在被忽略的 `.local/mock-cua-fixture.json`。Mock 来源为一次性合成观测，过期标记仍按实际观测时间计算。
