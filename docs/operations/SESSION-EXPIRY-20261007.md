# 登录失效立即回主页（2026-10-07）

## 修复行为

已登录的普通请求或 SSE 返回 HTTP 401 时，收到响应头即清除当前会话、取消请求、清理查询缓存和弹窗，并返回 `/overview` 登录页。无需等待错误响应正文读完，也不再呈现“数据暂时不可用”面板。

QueryState 对已持有的当前会话 401 增加恢复兜底。请求及错误记录发起时的会话版本；旧请求的迟到 401 不清除新登录，旧错误只触发一次当前数据重试。错误密码继续显示登录表单错误；403/503 保留会话、错误详情及手动重试。401 请求编号从 `X-Request-Id` 响应头保留，未消费正文以非阻塞方式取消。

在上一发布的生产资源中，模拟永不结束的 401 正文可复现停留在 `/monitoring`。新版本同一场景直接返回主页登录。未在可用 control 副本日志中找到用户提供的请求编号，因此这项复现不能证明该次请求的具体原因。

## 验证

| 检查 | 结果 |
| --- | --- |
| 前端完整 Vitest | 29 文件、171 测试通过 |
| Vue 类型检查及生产构建 | 通过 |
| 完整 Playwright Chromium | 21 流程通过 |
| 格式、Git diff 检查、独立只读代码审查 | 通过 |

浏览器使用生产资源和真实本地 demo API，验证资产、接口、监测、应用页面的 401 跳转、搜索关闭、再次登录、403/503 保留会话，以及正文不结束时的立即跳转。回归测试先确认相关失败，再验证修复。Browser plugin not available，沿用仓库 Playwright。日志、复现记录和截图位于仓库外 `/tmp/noeriva-expiry-redirect/`。

## 发布与公网验收

Helm revision 26 发布 console `noeriva/console:20261007-expiry-redirect-r1`。部署前渲染比较只改变 console 镜像；前后 values 只改变该镜像 tag，runtime Secret 数据摘要一致。control/worker 沿用 `20261007-square-matrix-r1`，调度设置保持不变。发布后 console 2/2、control 2/2、worker 1/1 就绪。

镜像由源节点 Docker 基于上一 console 镜像构建，仅替换生产静态资源。容器用户 `101:101` 和入口保持原配置，镜像内全部 45 个文件逐一匹配测试产物摘要。镜像已导入主节点 K3s，并保存到其启动导入目录；离线 worker 节点恢复后仍需导入新镜像，边界见 [故障恢复记录](OUTAGE-RECOVERY-20261007.md)。

console image ID：`795e3407c3ab585e914582fcab05d750e5885b6d61622686286a39e119c14550`。

公网 `https://noeriva.sagslab.org/overview` 返回 200。44 个静态资源摘要匹配，HTML 的三项自身资源引用与测试产物一致。公网发布的前端通过浏览器复验：API 请求在测试中路由到真实本地 demo API，注入正文不结束的 401 后返回公网 `/overview` 登录页，无错误面板或应用运行异常。这项测试验证公网前端处理逻辑，不代表使用生产账号登录的验收。

Cloudflare 追加的挑战及分析脚本仍被原 CSP 拦截，产生两条控制台信息；本次未改变 CSP。已打开的旧标签页需刷新一次才能加载新代码。

受限发布备份：源节点 `/var/backups/noeriva-expiry-redirect-20261007/`。构建上下文、摘要与镜像归档：`/opt/noeriva-expiry-redirect-20261007/`。凭据和 Secret 原文不进入 Git。
