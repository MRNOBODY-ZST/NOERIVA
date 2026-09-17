# 本地部署验证记录

2026-09-06，当前 Docker Desktop 的 `linux/arm64` 引擎，14 CPU、8,320,954,368 字节可用内存。已启动仓库的 COMPACT 数据组件及 control / worker / console，没有执行 Kubernetes 安装或外部生产切换。

最终镜像在后端 66 项验证及前端图谱修正后重新构建并启动；容器镜像标识、身份、只读限制与采集结果记录于 [deployment-smoke.json](../docs/implementation/deployment-smoke.json)。

| 检查 | 实际结果 |
|---|---|
| OCI 镜像清单 | Compose 中 8 个基础设施/可选观察组件镜像均解析成功，锁定 index digest；amd64 与 arm64 清单存在 |
| Compose 配置 | 所有 profile 的 `docker compose ... --profile observability config --quiet` 退出 0 |
| MySQL / Redis / Kafka / ClickHouse / VM / vmagent | 六个常驻服务均为 `running` 且 `healthy` |
| 应用 OCI 构建 | control 与 console 均构建成功；Temurin 实测为 25.0.4+7 LTS，nginx 实测为 1.29.8；四个构建 / 运行基础镜像均锁定已解析的 digest；后端与前端使用 BuildKit 依赖缓存 |
| 应用启动 / 数据迁移 | control、worker、console 均运行；API18080 与 worker 内网 readiness 返回 UP，console18000 `/health` 返回 200；Flyway V1/V2/V3 全部成功 |
| 代理与会话 | 通过 console18000 认证 session 返回 CONNECTED 与 bearer token；后续 bearer 设备读取返回 200；没有在日志记录密钥或 token |
| 容器限制 | control / worker 使用 UID10001，console 使用 UID101；三个容器均启用只读 root filesystem、ALL capabilities drop 与 no-new-privileges；worker 单实例启用 rollup，API 关闭 |
| Kafka 初始化 | 初始化容器退出 0；`noeriva.events.v1`、`noeriva.events.dlq.v1`、`noeriva.control.v1` 均存在 |
| ClickHouse 初始化 | `metric_rollups`、`control_events` 均为 `ReplacingMergeTree`；没有表 TTL |
| 数值采集链路 | VM `/api/v1/query` 返回 ClickHouse、VM、vmagent 的 `up=1`；应用启动后 control 与 worker 也都返回 `up=1`，确认受认证抓取及 remote-write 已可见 |
| metrics 专用权限 | 容器运行的 production API18080：metrics 访问 `/actuator/prometheus` 返回 200，访问设备 API 返回 403；匿名抓取返回 401 |
| nginx 响应 | 配置语法检查通过；首页、SPA 深层路径和 JS asset 返回 200 且带 CSP / nosniff；HTML 不缓存，hash JS 具备 immutable 策略；使用[官方 header inheritance 语义](https://nginx.org/en/docs/http/ngx_http_headers_module.html#add_header_inherit)修复 location 覆盖父级安全头的问题 |
| 合成状态链路 | Compose 切换后[业务 smoke](../docs/implementation/production-smoke.json)通过：事务创建、接口归属、Kafka 接收、MySQL 投影、重放幂等、ClickHouse 历史、告警确认、缺失 heatmap 保持 null |
| 合成历史补算 | [repair job smoke](../docs/implementation/rollup-job-smoke.json)通过：三小时前的一小时 VM 原始数据经持久任务与独立 worker 写出 12 个 ClickHouse bucket；历史一小时 heatmap 为 800 bps，其余 167 格保持 missing / future |
| 主机端口 | 所有发布的基础设施端口仅绑定 `127.0.0.1` |
| 本地密钥 | `.env` 权限 `0600`，8 个不同值，被 git 忽略；重复执行初始化脚本保留现有文件；专用 metrics 密码文件位于 `0700` 的 `.local` 目录，通过只读挂载供非 root vmagent 使用 |
| Helm | `helm lint ... --strict` 退出 0；默认渲染 control/console/worker 三个 Deployment、三个 NetworkPolicy；worker 固定一副本且 Recreate；关闭 worker、启用 Ingress、关闭 HPA 的分支均成功渲染 |
| Kustomize | namespace 配置成功渲染三个命名空间 |

本记录验证镜像构建、本地应用启动、迁移、代理认证和指标链路，不宣称浏览器、生产 Helm 安装、amd64 运行、HA 故障切换、吞吐、备份恢复或设备采集验证已经完成。Grafana 与 OTel 镜像已解析和配置已提供，但本轮未启动这两个可选服务。业务闭环由仓库应用测试及其独立运行记录补充。

已停止的主机进程仅为本任务确认的 production Java PID91446，以释放18080给 Compose；demo18081仍运行且 readiness 为200。未停止其他应用。构建日志保存在被 git 忽略的 `.local/image-build.log`、`.local/image-build-final.log` 和 `.local/console-header-build.log`。

首次 ClickHouse 启动时只包含 metric_rollups；control_events 在同一个本地验证实例中通过 `CREATE TABLE IF NOT EXISTS` 补齐。新建环境会依次执行两个初始化 SQL 文件。

COMPACT 数据卷被保留供应用联调。普通停止使用 `docker compose --env-file .env -f deploy/compose/compose.yaml down`；删除卷须作为单独的数据删除操作。
