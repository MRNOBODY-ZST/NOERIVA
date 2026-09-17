# 192.168.4.62 / 192.168.4.63 部署记录

部署日期：2026-09-07。复用两机现有 K3s v1.36.4+k3s1，应用命名空间为 `noeriva-system`，Helm release 为 `noeriva`。本次没有安装第二套容器运行时，也没有连接或重置离线的 192.168.4.61。

## 访问与拓扑

| 入口 / 组件 | 配置 |
|---|---|
| 工作台入口 | http://192.168.4.62 / http://192.168.4.63 |
| 登录 | `admin`，沿用原 NOERIVA 工作台密码；服务器 SSH 密码不是工作台密码 |
| API、Console | 各 2 副本，按主机名分散到两节点 |
| Worker | 1 副本、Recreate 更新；六台设备每 60 秒只读采集 |
| 数据面 | MySQL、Redis、Kafka、ClickHouse、VictoriaMetrics 各 1 副本，固定在 62 |
| 存储 | local-path，5 个 PVC 申请共 191 GiB；该数字不是文件系统配额 |
| Kubernetes 节点名 | 62 为 `argus-primary`，63 为 `argus-worker`；保留原节点名 |
| 管理网络 | 192.168.254.0/24、192.168.4.0/24，允许 SNMP 161/UDP、HTTPS 443/TCP、SSH 22/TCP |

当前入口是局域网 HTTP，尚未配置可信 DNS/TLS。现有 Traefik 提供两 IP 的入口。双应用副本不代表整套系统高可用：数据库和 K3s server 位于 62，现有 Traefik 也未在本次改为多副本；未进行物理节点断电验收。

应用分散约束使用 `DoNotSchedule` 与 `matchLabelKeys: [pod-template-hash]`，按同一版本统计副本，避免滚动更新结束后两个新 Console 留在同一节点。该字段的用法见 [Kubernetes 官方拓扑分散文档](https://kubernetes.io/docs/concepts/scheduling-eviction/topology-spread-constraints/)。

## 镜像与部署文件

| 组件 | 版本 |
|---|---|
| API / Worker | `noeriva/control:20260907-k8s` |
| Console | `noeriva/console:20260907-k8s` |
| MySQL | 8.4.11 |
| Redis | 8.2.9-alpine |
| Kafka | 4.3.1 |
| ClickHouse | 26.3.32.14 LTS |
| VictoriaMetrics | v1.151.0 |

全部使用实际 linux/amd64 镜像。服务器直连镜像仓库超时，因此通过 SHA-256 校验的离线归档导入 K3s containerd；应用镜像导入两节点，数据镜像导入 62。

62 的 Xeon E5-2650 v2 不支持 AVX2。ClickHouse 26.8.2.7 的 amd64 镜像启动出现 `Illegal instruction`，其[对应版本官方说明](https://github.com/ClickHouse/ClickHouse/blob/v26.8.2.7-lts/docker/server/README.md)要求 x86-64-v3。改用 [26.3.32.14 的 x86-64-v2 构建](https://github.com/ClickHouse/ClickHouse/blob/v26.3.32.14-lts/cmake/cpu_features.cmake)，完成实际启动与表结构检查。调整时新 CH 尚未创建数据；历史通过 JSONEachRow 逻辑导入，没有向旧版本复制 26.8 的数据分片。

维护入口：

- 应用配置：[values-two-node.yaml](../../deploy/helm/noeriva/values-two-node.yaml)。
- 数据服务、初始化与网络隔离：[two-node/README.md](../../deploy/kubernetes/two-node/README.md)。
- 局域网路由：[lan-ingress.yaml](../../deploy/kubernetes/lan-ingress.yaml)。
- 应用运行镜像：[Dockerfile.control-runtime](../../deploy/docker/Dockerfile.control-runtime)、[Dockerfile.console-runtime](../../deploy/docker/Dockerfile.console-runtime)。
- 远端清单：62 的 `/opt/noeriva-deploy-20260907/deploy/`，仅 root 可进入父目录。

在 62 上维护时，先设置 `KUBECONFIG=/etc/rancher/k3s/k3s.yaml`。应用升级使用上述 Helm values；数据面使用 `kubectl apply -k deploy/kubernetes/two-node`。不要把本地 Compose 的 CH 26.8 镜像覆盖到这台旧 CPU 上。

## 迁移范围与核对

只迁移 default 站点的六台真机：Dell OS9 .254.1、Cisco IOS XE .254.3、iMana .254.10/.11/.12、Inspur .254.20。本机 Mock、压测资产、测试工作单均未导入。

| 内容 | 恢复时核对结果 |
|---|---|
| 设备、当前状态、来源、加密连接 | 各 6 行 |
| 发现候选 / MAC 声明 | 各 26 行 |
| 发现运行 / 已有关联 | 各 1 行 |
| 相关审计 | 42 行，后续测试与启用会增加审计 |
| 历史指标 | 1,990 个标签、时间戳、数值组合全部一致 |
| 历史事件 | 2,388 行，完整行内容全部一致 |
| 导出文件 | 13 个文件逐个 SHA-256 校验 |

SQL 使用单事务与完整列 INSERT，未关闭外键、未覆盖目标冲突记录。保留原组织、设备 ID、slot、凭据密文和 source epoch；原 vault key 从本机安全配置移入 Kubernetes Secret。另行比对六条连接的密文与 epoch 摘要一致。恢复后各执行一次测试，sequence 从 399 增为 400，符合每次领取采集租约递增的实现。

本机六条连接已停用，目标启用唯一 Worker。新 Kafka 建立三个版本化主题，各 3 分区、RF1；不复制本机 Kafka offset 或宣称完成整个平台的全量克隆。

两端已逐台验证实际设备读取。Dell 和三台 iMana 为 SUCCESS；Cisco 与 Inspur 为 PARTIAL，与原能力边界一致。iMana 返回 99 / 99 / 101 项传感器，Inspur 返回 41 项。交换机 SSH 身份与邻居信息不等于已获得带宽数据；页面继续如实显示缺少带宽观测。

## 验收材料与清理边界

验收包括两 IP 登录、16 个页面的实际数据或合理空状态、历史查询、SSE、并发只读请求、Pod 分布与数据服务网络隔离。Computer Use 使用 Edge 完成两入口登录，最终浏览器错误日志为空。API/Console 各 2 副本分别位于两节点，Worker 为 1 副本；Kafka 三分区消费 lag 均为 0，未发布 outbox 为 0。

- [部署、迁移与清理验收](../implementation/two-node-deployment-verification.json)。
- [数据服务、镜像与 15 项网络隔离检查](../implementation/two-node-data-plane-verification.json)。
- [首次完整 API 验收](../implementation/two-node-api-verification.json)：两入口各 100 个 HTTP 200，其中并发 16、共 160 次混合读全部成功。首次延迟含每请求重复创建 Python HTTPS handler 的客户端开销，不能作为后端延迟。
- [修正客户端计时后的复测](../implementation/two-node-api-verification-client-warmed.json)：预先初始化每线程连接工具，160 次混合读、16 并发全部成功，P50 81.72 ms、P95 217.24 ms、最大 430.86 ms，总读取阶段约 972 ms。计时包含请求、网络、读取正文和客户端断言，不是服务器独立耗时；没有预热 HTTP 查询。

小规模六设备读请求测试只用于部署冒烟，不代表生产容量或长期性能承诺。

62/63 上的六项旧 Argus 应用及 Service、旧 Ingress、`argus-data` 与 `argus-observability` 命名空间已删除。两节点不存在旧 Argus Pod。离线 61 的 `argus-edge` 注册对象及其依赖不在本次主机授权范围，因此保留；没有执行会一并移除 Edge 的整套 `helm uninstall argus`，相应 Helm 历史仍保留。

旧配置和卷归档位于 62 的 `/var/backups/noeriva-replacement-20260907/`，父目录 0700、文件 0600。MySQL 归档 78,422,630,400 字节，Redis 归档 51,916,800 字节；完整目录读取及两遍独立 SHA-256 一致，已写 `OLD_DATA_BACKUP_VERIFIED`、`volumes.sha256`、`backup-verification-manifest.json`。旧 MySQL/Redis PV 为 Retain/Released，原目录作为额外恢复材料保留。已验证归档完整性，未执行恢复演练。恢复时按备份说明与原 MySQL 镜像操作，并排除运行时 `mysql.sock` 链接；仍需核对密码配置、节点与卷绑定。

新六设备迁移文件在 `/opt/noeriva-deploy-20260907/selected-hardware-export/`，属于仅 root 可读的运维数据，不进入仓库。保留原 `NOERIVA_CREDENTIAL_KEY` 是恢复加密设备凭据的必要条件；应用副本和同机备份不能替代独立灾备。
