# NOERIVA · 澄观

基于 [V5 架构规范](docs/source-v5/NOERIVA_MASTER_PROMPT_V5.md) 实现的基础设施值守与查询系统。在首个设备运营与查询切片基础上，已按 B / Clarity「澄明工作台」扩展为 **16 个主导航页面**：统一巡检、监测/接口目录、事件处置、时态端点调查、证据与配置审阅、探测结果管理，以及 Docker / Kubernetes 应用部署。完整 Phase 0–13 的领域范围保留在[能力矩阵](docs/capabilities/CAPABILITY-MATRIX.md)，通用协议实现、厂商扩展和具体机型验收分别记录。

## 已实现

- **Java 25 / Spring Boot 4.1.1 / WebFlux**：无状态 API、基于身份的组织隔离、角色授权、短期不透明会话、当前权限重新校验、请求 ID、受控并发和超时。
- **MySQL / R2DBC**：设备、站点、接口、独立来源检查点、告警乐观锁、事务 outbox、内部操作审计。当前状态查询不扫描历史指标。
- **Kafka**：低频状态事件以 `acks=all`、幂等生产者接收；响应区分持久接收与可查询。重放按来源 epoch/sequence 幂等投影；外部 sink 不冒充全局 exactly-once。
- **VictoriaMetrics + ClickHouse**：数值指标独立写入 VM；Java 后台任务读取原始计数器，生成可替换的 5 分钟汇总。热力图固定 168 格，区分真实零、缺失、部分、未来、夏令时缺失/重复小时。支持时区、计数器重置、大整数、迟到修正与覆盖证明。单接口热图使用计数器时间加权汇总；默认设备总带宽热图使用设备速率样本均值，两种覆盖口径不同。
- **Vue 3 / TypeScript / TanStack Query / Pinia / ECharts / Tailwind**：中文 B/Clarity 控制台，设备筛选/分页/登记、详情/趋势/热力图、连接图、事件、告警确认和采集器状态，包含暗色与移动布局。
- **澄明工作台后端**：事件单与备注/修订、不可覆盖证据内容与 SHA-256/访问审计、服务端脱敏配置快照/差异、探测定义与带版本结果、站点隔离的 NAT/地址租约有效区间调查。查询明确区分确认、歧义、证据不足和无匹配；已实现有界 TCP/HTTP/HTTPS/DNS/TLS 探测执行；签名与 WORM 仍需单独接入。
- **设备协议接入**：通过「登记设备」和「编辑资料」管理资产，独立加密 SNMP v2c/v3、HTTPS Redfish 与 SSH 凭据；通用驱动已实现身份候选/能力发现、只读测试，内置 Worker 执行定时采集并记录心跳，浏览器通过 SSE 更新状态。厂商和协议边界见[设备接入指南](docs/devices/DEVICE-OPERATIONS.md)，模拟器与联调证据见[设备接入验收](docs/devices/DEVICE-VERIFICATION.md)；具体机型、固件和 OEM 扩展仍需逐项验收。
- **SNMP 拓扑发现**：关联 LLDP/CDP、ARP、DHCP Snooping、FDB 与 VLAN；保留已登记资产身份，按证据归并发现终端，分别展示真实邻接、推断接入与三层邻居。支持 VLAN 分组和逐边证据，设置全局处理与输出上限；同网段不自动等于物理直连。协议范围见 [SNMP 终端证据](docs/devices/SNMP-ENDPOINT-EVIDENCE.md)。
- **告警数量一致性**：侧栏显示待确认数量，队列独立显示已确认未恢复与已恢复记录；数据库精确统计不受列表分页限制。确认操作不代表故障恢复。
- **部署**：Compose COMPACT；Helm 独立 API、控制台和单副本后台 worker，包含探针、HPA/PDB、资源限制、TLS Ingress 配置、NetworkPolicy 和现有 Secret 引用。

每页的目的、字段、操作、接口与验收标准见[逐页规划](docs/frontend/CLARITY-PAGE-PLAN.md)，设计对照见[澄明参考审阅](docs/frontend/CLARITY-REFERENCE-AUDIT.md)，本轮 Computer Use、自动化与部署结果见[澄明重构验收](docs/frontend/CLARITY-VERIFICATION.md)。

## 本地完整运行

需要 Docker（建议给 Docker 至少 8 GiB 内存）与 Compose。首轮构建会下载固定版本依赖。

```sh
python3 scripts/init-env.py
docker compose --env-file .env -f deploy/compose/compose.yaml up -d --build
```

打开 **[控制台](http://127.0.0.1:18000)**。用户名 `admin`，初始密码在本机 `.env` 的 `NOERIVA_BOOTSTRAP_PASSWORD` 字段中；该文件未纳入 Git。生产连接模式初始为空；从「登记设备」添加资产后，在「接入与采集」配置协议凭据，测试识别并启用采集，便可产生实际读数和状态事件。初始化不会自动连接或写入真实设备。

独立 `worker` 执行设备只读周期采集、原生探测调度、独立应用采集、搜索索引更新、有限的近期汇总和已排队的补算任务，API 实例不执行调度。数据库数据位于命名卷。普通停止命令保留数据：

```sh
docker compose --env-file .env -f deploy/compose/compose.yaml down
```

### 先查看交互演示

内置演示数据只存在于明确的 `demo` 后端配置中；CONNECTED 环境也可通过下方 Mock 脚本显式导入有来源标识的合成记录。UI 不会在真实 API 失败时偷偷替换成假数据。需要 JDK 25、Node 24；使用仓库自带 Maven wrapper 与固定 pnpm。

```sh
./mvnw -DskipTests package
python3 scripts/run-local.py demo
```

另开终端：

```sh
cd frontend/noeriva-console
npx --yes pnpm@11.19.0 install --frozen-lockfile
NOERIVA_API_PROXY=http://127.0.0.1:18081 npx --yes pnpm@11.19.0 dev --port 5174
```

打开 [演示控制台](http://127.0.0.1:5174)，使用 `admin / noeriva-local-demo`，或 `viewer / noeriva-local-demo` 验证只读权限。这些是公开的演示凭据，仅适用于 `demo` 配置。真实环境必须使用生成的独立凭据和 HTTPS。

## Kubernetes

完整操作步骤、Secret 键名、独立 worker、网络策略以及 HA_PERF 数据平面参考见 [部署指南](deploy/README.md)。生产数据库由受管理服务或 Operator 运维，应用图表通过外部端点连接；COMPACT 的单实例数据库不代表 HA 集群。

```sh
helm lint deploy/helm/noeriva -f deploy/helm/noeriva/ci-values.yaml --strict
helm template noeriva deploy/helm/noeriva -f deploy/helm/noeriva/ci-values.yaml
```

`ci-values.yaml` 仅用于静态验证。真实部署需要自己的镜像仓库/摘要、数据库端点、TLS、网络选择器和现有 Secret，然后执行：

```sh
helm upgrade --install noeriva deploy/helm/noeriva \
  --namespace noeriva-system --create-namespace \
  --values /secure/noeriva-production.yaml
```

2026-09-07 已在用户授权的两机 K3s 部署当前系统：[192.168.4.62](http://192.168.4.62) / [192.168.4.63](http://192.168.4.63)。API/Console 各两副本，Worker 单副本；2026-09-07 初始部署的五项数据服务固定在 62，2026-09-09 增加 Elasticsearch 后为六项。六台真机、发现候选与对应历史已迁移并验收，旧两机 Argus 服务已备份后清理。此次使用局域网 HTTP，数据面仍为单实例；具体配置、CPU 兼容镜像、备份路径和验收边界见[两节点部署记录](docs/operations/TWO-NODE-DEPLOYMENT.md)。未启用证据 TTL 删除或设备配置下发。

2026-09-09 按用户要求将设备采集改为 SNMP 优先，已停用部署环境的设备 SSH 连接；Inspur 保留 Redfish HTTPS。当前协议、离线设备状态与验收限制见 [SNMP 迁移记录](docs/implementation/SNMP-PREFERRED-MIGRATION-20260909.md)，告警和拓扑更新见[本轮修复记录](docs/implementation/TOPOLOGY-ALERT-REMEDIATION-20260909.md)。

## 验证与性能

```sh
./mvnw verify                         # 包含真实 Testcontainers MySQL 集成测试，需要 Docker
cd frontend/noeriva-console
npx --yes pnpm@11.19.0 test
npx --yes pnpm@11.19.0 build
```

启动真实 COMPACT 后，在仓库根目录运行：

```sh
python3 scripts/smoke-production.py
python3 scripts/smoke-rollup-job.py
python3 scripts/benchmark.py --requests 1200 --concurrency 32
```

`smoke-production.py` 会添加带 `synthetic-smoke-` 标记的测试设备，验证持久接入、重放、历史与告警；`smoke-rollup-job.py` 添加合成 counter，验证历史补算任务到热力图的完整链路。压测只接受 loopback 目标；`--seed-devices 1000` 可明确添加 1,000 个合成资产。

首轮本地测试：1,003 个资产、32 并发，混合查询两轮共 2,400 请求全部返回 200；p95 为 72.65 / 79.55 ms，约 1,022 / 933 请求/秒。查询组合是首 100 台设备、设备当前概要和告警。**这不是长历史、持续采集、故障条件下的生产容量验收。** 硬件、查询组合及限制见[首轮测试记录](docs/implementation/benchmark-smoke.json)和[首轮切片验证](docs/implementation/VERIFICATION.md)。

本轮澄明工作台另测 6 个目录/工作流列表：640 次请求、32 并发全部 200，p95 172.34 ms，约 375.38 请求/秒，持续约 1.7 秒。运行 `python3 scripts/benchmark-workbench.py --requests 640 --concurrency 32` 可复现查询组合；[原始结果](docs/implementation/clarity-benchmark.json)及[本轮验收](docs/frontend/CLARITY-VERIFICATION.md)记录了范围。这是本机 Docker 与少量工作流 Mock 内容的有界查询冒烟，不能换算为生产容量或 SLO。

### 澄明工作台 Mock 数据

在本机 CONNECTED 环境运行以下脚本，可复用带独立标记的测试数据：

```sh
python3 scripts/mock-local.py
python3 scripts/refresh-mock-telemetry.py
python3 scripts/mock-workbench.py
```

这些脚本只访问固定的本机接口，不连接登记的设备/探测目标；工作流样本包括事件单、证据、前后配置快照、通过/失败/未知探测和四种调查结果。调查使用 `198.51.100.26`、TCP，端口 `54021`/`54022`/`54023`/`54024` 分别演示确认/歧义/证据不足/无匹配，查询时间应在 `.local/mock-workbench-fixture.json` 的记录区间内。原始六小时遥测不覆盖重写；刷新脚本只追加其后两天内的确定性样本。密码只从被忽略的 `.env` 读取，不写入报告。

已新增「资产目录 → 发现设备」：按明确 CIDR 筛选已采集的 ARP/LLDP/CDP 与 DHCP Snooping 证据，检查同址资产、共享 MAC 与身份冲突，再登记或关联。该流程不主动扫描候选地址。六台指定真机曾完成只读接入及对应历史查询验收，当前在线状态以最新观测为准；离线的 iMana 保持离线状态。具体协议与型号见[真机验收](docs/devices/HARDWARE-VERIFICATION.md)。参见[发现 API 与去重边界](docs/devices/NETWORK-DISCOVERY-API.md)、[真实返回格式](docs/devices/HARDWARE-FORMAT-NOTES.md)。

## 开发与运行契约

- [逐页功能规划](docs/frontend/CLARITY-PAGE-PLAN.md)、[工作台写入/调查契约](docs/implementation/WORKBENCH-API.md)、[有界聚合查询](docs/implementation/WORKSPACE-API.md)
- [API 契约](docs/implementation/API-CONTRACT.md)、[OpenAPI](schemas/api/openapi.json)
- [实际架构](docs/architecture/NOERIVA-ARCHITECTURE.md)、[数据流](docs/architecture/DATA-FLOW.md)
- [查询算法/数据覆盖及限制](docs/implementation/query-report.md)
- [能力矩阵](docs/capabilities/CAPABILITY-MATRIX.md)、[协议矩阵](docs/capabilities/PROTOCOL-MATRIX.md)、[版本与官方来源](deploy/VERSIONS.md)
- [运维与恢复](docs/operations/RUNBOOK.md)

通用 SNMP/Redfish、iMana/OS9/IOS XE 固定只读 SSH 档案和内置设备协议 Worker 已实现。Worker 将心跳及本进程最近成功采集时间写入采集器状态，由 `GET /collectors` 和「采集器」页面展示；当前部署已停用设备 SSH，使用启用的 SNMP/Redfish 来源；内置 Worker 没有 Edge 离线磁盘缓冲。

已实现原生 TCP/HTTP/HTTPS/DNS/TLS 探测的立即执行、周期调度和跨副本租约。启用的 `MANUAL` 检查定义由独立 Worker 按周期执行；`SYNTHETIC` 样本仅展示上报结果，不会触发真实网络探测。

Cisco NAT HSL NetFlow v9 接收、Kafka/ClickHouse 持久化与有界历史查询，以及独立 SNMP NBAR 应用采集、差分速率和排行图已实现并完成指定真机验收。当前 NAT 解码范围为已验证的固定长度 v9 模板，不支持通用 IPFIX v10 或任意厂商 Flow 格式；UDP 不保证完整捕获。Elasticsearch 已用于设备和接口搜索，命中后校验当前组织与资产归属，支持定位设备接口；事件历史保留独立查询。

仍需完成独立 Edge 的注册、心跳与离线缓冲上报、未验收机型/固件及 OEM 扩展、通用 Flow/IPFIX 与人员身份历史证据、细粒度站点授权、S3 证据存储/签名/WORM 与保留治理、通知/SLO，以及生产故障恢复和长期容量验收。工作台时态调查仍根据导入时固化的站点、地址和有效区间证据判断，不独立证明历史位置、VRF 或人员身份。当前拓扑发现也不等于自动建立完整历史租约证据链。
