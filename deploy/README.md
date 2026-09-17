# 部署与运行边界

`compose/compose.yaml` 是 V5 H10 的 COMPACT 本地验证配置：MySQL、Kafka KRaft、ClickHouse、VictoriaMetrics 均为单实例。它们不能证明生产可用性、容量、RPO 或 RTO。没有自动执行 Kubernetes 部署。

## 本机 COMPACT

在仓库根目录生成本地 `.env` 后运行：

```sh
python3 scripts/init-env.py
docker compose --env-file .env -f deploy/compose/compose.yaml up -d --build
docker compose --env-file .env -f deploy/compose/compose.yaml ps
docker compose --env-file .env -f deploy/compose/compose.yaml logs --tail=100 control
```

所有主机端口均绑定 `127.0.0.1`。默认应用控制台为 `http://127.0.0.1:18000`，API 为 `http://127.0.0.1:18080`。通过同源控制台访问 API。Kafka 内部为 `kafka:9092`，主机客户端使用 `127.0.0.1:19092`，控制器端口不暴露。Redis 是可重建缓存；Kafka 禁止自动创建未知主题，只初始化版本化主题。

`worker` 复用 control 镜像但单独运行，只有该进程启用 `NOERIVA_ROLLUP_ENABLED=true`。API 的调度器明确关闭；worker 不发布主机端口。`NOERIVA_ORGANIZATION_ID` 默认 `default`，每个组织运行一个指定 worker，不要对它使用 Compose 扩容。采集与历史补算需要该 worker 保持运行。

本地环境文件必须包含 `NOERIVA_DB_PASSWORD`、`NOERIVA_DB_ROOT_PASSWORD`、`NOERIVA_REDIS_PASSWORD`、`NOERIVA_CLICKHOUSE_PASSWORD`、`NOERIVA_BOOTSTRAP_PASSWORD`、`NOERIVA_COLLECTOR_PASSWORD`、`NOERIVA_GRAFANA_PASSWORD`、`NOERIVA_METRICS_PASSWORD`。不要提交环境文件或把完整 `docker compose config` 输出存入日志。Compose 使用不同数据库普通用户与 root 密码；生产必须进一步拆分迁移、读写、查询权限。

初始化脚本同时准备 `.local/metrics-password`，供 vmagent 以 `metrics` 专用账号读取受保护的 Actuator 指标。父目录权限为 `0700`，单文件只读挂载可被容器的非 root UID 读取；它与 `.env` 一样被 git 忽略。该账号只有 METRICS 权限，不能访问控制台或采集 API。生产采集器应从独立 Secret 挂载 `password_file`，与应用 bootstrap 的 `NOERIVA_METRICS_PASSWORD` 保持一致，或使用组织已批准的指标认证机制。

运行可选 Grafana 与 OpenTelemetry Collector：

```sh
docker compose --env-file .env -f deploy/compose/compose.yaml --profile observability up -d
```

Grafana 位于 `http://127.0.0.1:13000`，数据源和运行指标仪表盘已预配。OTel Collector 只把诊断摘要输出至本地日志，不提供持久追踪存储。vmagent 的持久队列限定为每个远端 1 GB，应监控丢弃和待发字节；队列不能替代证据归档。

停止时使用 `down` 保留命名卷；`down -v` 会删除数据库和历史，不属于普通停止步骤。Kafka 不配置自动年龄/容量删除，ClickHouse 不设置 TTL，VM 的本地数值指标配置为 100 年保留。磁盘仍会增长，必须监控剩余空间；生产保留策略需要单独验证和批准。

## Kubernetes 应用图表

Helm 图表部署 `control`、`console` 和默认启用的独立 `worker`。生产 MySQL、Kafka、ClickHouse、VictoriaMetrics、Redis 等使用受管理服务或成熟 Operator；这些选项由外部端点连接，应用代码不依赖运行位置。

worker 使用与 API 相同的 control 镜像、ConfigMap 与现有 Secret，固定单副本、`Recreate` 更新，不使用 HPA，也不创建公网或集群 Service。`runtime.organizationId` 指定处理的组织；API 扩缩容不会增加调度器。滚动变更 worker 时会短暂停止调度，持久化任务由恢复后的进程继续处理。每个组织只部署一个启用 worker 的 release；多组织调度与分布式租约尚不是此图表的功能。设置 `worker.enabled=false` 可以关闭调度。监控系统通过 Pod 发现抓取 worker 的受保护指标端口。

```sh
helm lint deploy/helm/noeriva -f deploy/helm/noeriva/ci-values.yaml --strict
helm template noeriva deploy/helm/noeriva -n noeriva-system \
  -f deploy/helm/noeriva/ci-values.yaml > /tmp/noeriva-rendered.yaml
kubectl kustomize deploy/kubernetes/namespaces
```

`ci-values.yaml` 只用于静态渲染，使用保留的无效域名和示例地址，不能作为生产配置。真实部署值必须设置受管理数据端点、已发布镜像摘要、现有 Secret、实际网络策略选择器/CIDR、Ingress 类和 TLS Secret。不要把真实密钥放进 `values.yaml`；图表通过 `envFrom.secretRef` 引用现有 Secret，键名包括：

```text
SPRING_R2DBC_PASSWORD
SPRING_FLYWAY_PASSWORD
SPRING_DATA_REDIS_PASSWORD
NOERIVA_CLICKHOUSE_PASSWORD
NOERIVA_BOOTSTRAP_PASSWORD
NOERIVA_COLLECTOR_PASSWORD
NOERIVA_METRICS_PASSWORD
```

Secret 中也可补充 Kafka TLS/SASL 参数，受管理服务 CA 可通过镜像系统信任库或组织受控证书挂载机制提供；默认 Java TLS 校验不禁用。TLS 信任链和 SAN 必须匹配实际域名。Kafka `SSL`、Redis TLS、数据库/HTTP TLS 是部署值的明确选择；NetworkPolicy 只控制连接，不能替代加密。

图表包含独立启动/就绪/存活探针、45 秒终止窗口、资源请求/限制、主机拓扑分散、无特权只读容器、无默认 ServiceAccount Token、HPA、PDB 和默认限制流量的 NetworkPolicy。HPA 需要可用的 metrics-server；CPU 利用率依赖 CPU requests。PDB 只约束自愿驱逐，不解决主机故障、扩缩容或滚动部署所有情形。只有一个副本时需调整 PDB，生产默认两个应用副本。DNS 规则假设 kube-system DNS；使用 NodeLocal DNS 时按实测地址修改。

NetworkPolicy 需要支持它的 CNI。默认没有数据平面出口，必须按真实端点写入 `networkPolicy.dataPlaneEgress`。生产 ingress 默认关闭；启用必须填写 TLS Secret。不要把 Actuator 暴露到公共入口。

## HA_PERF 生产参考与验收

H10 参考：MySQL 三成员单主组和冗余 Router；Kafka 三 broker 与三个独立 controller；VM 两 vminsert、两 vmselect、三 vmstorage、样本复制二；ClickHouse 一 shard、两 replica、三 Keeper；Redis 主从与受监控故障转移。副本须跨真实故障域，并按负载选择是否需要这些数量。

严格 ClickHouse 证据写入需验证 quorum。两个副本、quorum 二时，失去一个副本会暂停严格写入，由 Kafka 保留待处理数据；不能静默降低持久性。VM 社区版不能假装支持 Enterprise internode mTLS，应由受验证的基础设施提供加密私网。副本不是备份，必须独立执行 MySQL、ClickHouse/Keeper、VM 及对象存储恢复演练。

生产放行还需要：真实硬件容量与 CPU 架构核查、镜像签名/漏洞扫描、密钥轮换、最小权限、网络策略实测、流量/磁盘预算、恢复和故障注入、设备验证与足够时长的运行验证。COMPACT 未提供 Ceph、对象锁或完整 Elasticsearch 文本搜索，相关能力必须保留未实现状态。

版本和官方依据见 [VERSIONS.md](VERSIONS.md)。

## 设备只读采集

现有 Secret 还必须提供 `NOERIVA_CREDENTIAL_KEY`（32个随机字节的Base64）；API和worker共享同一密钥。Chart使用必需secretKeyRef，密钥不写入ConfigMap。为旧本地Compose安装运行 `python3 scripts/init-env.py` 可补充此键而不更改原密码。

配置 `runtime.deviceAllowedCidrs` 收窄设备地址范围，`networkPolicy.deviceEgress` 按需开放授权管理网段的 UDP/161（SNMP）、TCP/443（Redfish）、TCP/22（SSH）或实际端口；空规则默认拒绝设备出站。API 负责手动测试，worker 设置 `NOERIVA_DEVICE_COLLECTOR_ENABLED=true` 执行周期任务。SSH 使用镜像内 Java 驱动及平台保存的主机密钥指纹，不依赖宿主机 SSH 配置。完整接入、证书、故障和真机验收步骤见 [设备操作指南](../docs/devices/DEVICE-OPERATIONS.md)。
