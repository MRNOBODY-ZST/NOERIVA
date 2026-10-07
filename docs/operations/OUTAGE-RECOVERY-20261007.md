# 公网 502 恢复记录（2026-10-07）

## 故障与证据

公网 `https://noeriva.sagslab.org` 返回 HTTP 502。源主机、WireGuard 和 relay nginx 正常；直接访问集群 console Service 返回 200，control readiness 返回 UP。

`argus-worker`（192.168.4.63）自 9 月 28 日起不可达。原 Traefik Pod 留在该节点，替代 Pod 被调度到 `argus-primary`，但该节点没有 `rancher/mirrored-library-traefik:3.7.8` 镜像；Docker Registry 解析及访问失败，持续 ImagePullBackOff。Traefik 没有 ready endpoint，因此主节点和 WireGuard 地址的 80 端口拒绝连接。

NOERIVA 的 console/control 原配置要求严格跨节点分布（DoNotSchedule），离线节点同时阻止了第二副本在健康节点恢复。主节点磁盘仅使用 20%，不是磁盘耗尽。

## 恢复操作

1. 从官方 Docker Registry 下载原版本 Traefik 的 linux/amd64 OCI 镜像，核对每个 manifest/config/layer 的长度及 SHA256，通过已有 SSH 通道传到主节点并再次核对归档。
2. 使用 k3s containerd 导入镜像，只重建 ImagePullBackOff 的 Traefik Pod。镜像版本与现网声明相同。
3. 保存原 Helm values/manifest，以 `--reuse-values` 升级 NOERIVA 至 revision 23。渲染比较确认仅改变 control/console 的 `topologySpreadConstraints[0].whenUnsatisfiable`：DoNotSchedule → ScheduleAnyway。仍优先分散，但单节点可用时允许恢复第二副本。
4. 校验 runtime Secret 数据摘要前后一致。应用镜像、数据库和账号凭据均未更换。
5. 把镜像保存在 `/var/lib/rancher/k3s/agent/images/noeriva-traefik-3.7.8-amd64.tar`，供 K3s 启动导入；另保留受限维护备份。采用 [K3s 离线镜像目录机制](https://docs.k3s.io/installation/airgap)。

主节点备份目录：`/var/backups/noeriva-recovery-20261007/`，权限 0700，包含升级前 values/manifest、变更预览、凭据数据摘要和入口镜像。不要提交私密备份文件。

镜像：`docker.io/rancher/mirrored-library-traefik:3.7.8`。

- 平台 manifest：`sha256:54c598430bee8479c93179940c37f793cbe1e9e0405036b67ed11ba2f1fa252d`
- 上游多平台 index：`sha256:4299bbed850421258fc5448c2e0e6ad350981d4d335a68de11b92448aedbefe5`
- 归档 SHA256：`8bcca021b780ec7eb9c840547a8ed4363e0f1883540ac8bb6169a103591c5fa0`

## 恢复验收

2026-10-07 20:04–20:06（Asia/Shanghai）：

- Traefik 新实例 1/1 Running；console 2/2、control 2/2、worker 1/1 Available。
- 主节点 ingress、WireGuard 上游与 relay 本地 HTTPS 均返回 200。
- Cloudflare 公网首页、主 JS、查询 JS、CSS 和 `/health` 请求成功；浏览器实际呈现正常登录页。
- 公网无凭据 `/api/v1/session` 返回预期 401，认证仍生效。
- 经 WireGuard ingress 的认证 GET `/api/v1/session`、`/api/v1/workspace/overview`、`/api/v1/devices?limit=10` 均返回 200、CONNECTED。

## 剩余边界

`192.168.4.63` 仍无法经主节点 SSH 连接（No route to host），需要恢复该节点的供电/网络后才能恢复跨主机冗余。当前两个 API/console 副本同处主节点，不代表具备主机级故障容错。未强制删除失联节点上仍标记 Terminating 的旧 Pod，待节点恢复后由 Kubernetes 协调。

本次只修复部署可用性，保留 9 月 21 日已验证的应用镜像；未发布尚未完成验证的 ECharts/登录交互改版。
