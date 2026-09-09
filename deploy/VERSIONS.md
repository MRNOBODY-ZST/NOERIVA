# 部署版本核验

核验日期：2026-09-06。这些是当前实现选型，不是已验证的生产性能或全部生态兼容性声明。Compose 基础设施镜像已通过 `docker buildx imagetools inspect` 确认标签存在与多架构清单，并锁定 index digest；本机 Docker Engine 是 `linux/arm64`。amd64 清单存在不等于已在 amd64 主机运行验证。

| 组件 | 选择 | 官方依据和边界 |
|---|---|---|
| Java | JDK 25 LTS | [Temurin 发布](https://adoptium.net/temurin/releases?version=25)；本机为 Oracle 25 GA；OCI 实际运行核验为 Temurin 25.0.4+7 LTS，Dockerfile 锁定已验证的 JDK/JRE 镜像 digest |
| Spring Boot/WebFlux | 4.1.1 / Framework 7.0.9 | [系统要求](https://docs.spring.io/spring-boot/system-requirements.html)支持 Java 17–26，Maven 3.6.3+；其余 Reactor/Security/Netty 依 Boot BOM |
| Kafka broker | 4.3.1 | [Apache 支持的发行版](https://kafka.apache.org/community/downloads/)，KRaft；本地单节点 combined role 仅用于验证 |
| Spring Kafka / clients | Boot BOM | [Spring 官方兼容矩阵](https://spring.io/projects/spring-kafka/)将 Spring Kafka 4.1.x、clients 4.2.x 对应 Boot 4.1.x；不能仅因 broker 是 4.3.1 就强行覆盖客户端 |
| MySQL | 8.4.11 LTS | [GA 下载页](https://dev.mysql.com/downloads/mysql/8.4.html)和已存在 Docker 清单；8.4.12 出现在[预发布也可更新的发行说明](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/)，不据此自动升级 |
| Redis | 8.2.9 | [发行包清单](https://download.redis.io/releases/)；[8.2 为 Extended release](https://redis.io/docs/latest/operate/oss_and_stack/install/version-mgmt/)，本实现只依赖基本可重建缓存操作 |
| ClickHouse | 26.8.2.7 | [官方包仓库](https://packages.clickhouse.com/)、[26.8 LTS 发布](https://presentations.clickhouse.com/2026-release-26.8/)；生产 Keeper 与 server 使用匹配版本 |
| VM / vmagent | 1.151.0 | [官方变更记录](https://docs.victoriametrics.com/victoriametrics/changelog/)于 2026-08-31 发布的社区版；1.148.x 后续 LTS 修复线属于 Enterprise，未选择它 |
| Grafana OSS | 13.2.1 | [官方 OSS 下载](https://grafana.com/grafana/download?edition=oss)，仅可选运维观察入口 |
| OTel Collector Contrib | 0.159.0 | [官方发行版](https://github.com/open-telemetry/opentelemetry-collector-releases/releases/tag/v0.159.0)；已选择并锁定发行版，较新 0.160.0 未自动跟进；本地 debug exporter 不充当归档 |
| Kubernetes/Helm | chart ≥1.35；本机 kubectl 1.36.1 / Helm 4.2.4 | [Kubernetes 发布页](https://kubernetes.io/releases/)当前维护 1.35/1.36/1.37，1.36 当前补丁 1.36.4；[Helm 支持范围](https://helm.sh/docs/topics/version_skew/)。图表使用稳定 API，静态渲染不是集群联调，命名空间 Pod Security 规则固定为 v1.35 |

本机还有 Docker 29.6.2 / Desktop 4.85.0、Compose 5.3.1、Node 24.10.0、npm 11.6.1。未发现全局 Maven，项目使用 Wrapper。前端精确版本以 package manifest 和 pnpm lockfile 为准。

## Kubernetes 实施依据

- [探针](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)：启动完成前不执行 liveness/readiness；liveness 只反映进程是否需要重启，不能把共享依赖故障转成重启风暴。
- [HPA](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/)：CPU 利用率需要资源 requests 和可用指标 API；配置稳定窗口减少反复缩容。
- [PDB](https://kubernetes.io/docs/tasks/run-application/configure-pdb/)：按应用副本设置自愿驱逐预算；并不提供数据库一致性或替代副本策略。
- [NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/)：需要实现策略的网络插件；同时满足来源出口与目的入口规则。没有加密保证。
- [VM 查询上限](https://docs.victoriametrics.com/victoriametrics/index.html)：部署限定查询超时、并发、候选 series 和单 series 样本数；这些是验证配置，不是性能目标。

官方文档与镜像存在检查仍需补充运行测试、锁文件解析、启动日志及恢复验证。生产必须按安装时点复核支持生命周期和所有供应商许可功能。
