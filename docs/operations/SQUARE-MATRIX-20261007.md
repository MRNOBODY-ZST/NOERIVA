# 连续方形热图与图表更新（2026-10-07）

## 用户可见变化

- 七日带宽改为单张连续正方形矩阵，可选择 7×7（49 格）或 28×28（784 格），默认 7×7；每格保持正方形，不按天拆卡片。时间从左到右、逐行向下。
- 使用 [Apache ECharts matrix-covariance 示例](https://echarts.apache.org/examples/en/editor.html?c=matrix-covariance) 的矩阵坐标和连续红蓝色阶。色阶标明当前有效读数范围；缺失、未来、部分覆盖单独标记。格子显示原始观测的真实区间均值，详情包含实际 UTC 边界、覆盖与质量信息。它不是相关性或协方差数据。
- 趋势统一为带缩放滑块的均值折线，保留缺口和孤立零值。应用排行可切换带宽/累计流量；站点健康和当前监测值以条形图呈现，完整数值表可展开查看。
- 已登录请求或实时连接返回 401 时取消当前会话请求、清空缓存和弹窗，回到 `/overview` 登录页。错误密码仍显示表单错误，403/503 保留会话。旧请求不会清除较新的登录。旧浏览器使用可清理监听的组合取消信号 fallback。

详细聚合口径和兼容契约见 [API-CONTRACT.md](../implementation/API-CONTRACT.md)。原 168 小时格仍由默认 `gridSize=0` 返回；查询预算未扩大。

## 验证

| 检查 | 结果 |
| --- | --- |
| Vue 类型检查、生产构建 | 通过 |
| 前端 Vitest | 28 文件，164 测试通过 |
| 后端热图与控制器相关回归 | 31 测试通过 |
| Playwright Chromium | 16 流程通过 |
| OpenAPI | 83 操作、74 路径、51 DTO 字段集合及引用通过 |
| Git diff 检查与独立代码审查 | 通过 |

浏览器测试运行生产资源，对接本地真实 demo API。Browser plugin not available，因此使用仓库 Playwright。桌面 1440×1000 与手机 390×1000 均验证 49/784 个有色格的数量、实际宽高相等、外框正方形、无横向溢出、行列选择和切换；同时验证应用统计、接口选择、资产删除、来源历史、拓扑交互及登录失效流程。截图、日志和报告保存在仓库外 `/tmp/noeriva-square-heatmap/`。

参考对照：沿用官方矩阵坐标和调色板；用真实时间格替代协方差对称矩阵，隐藏分类轴，增加尺寸菜单、质量标记和可键盘操作的详情入口。

## 发布与公网验收

发布至 `noeriva-system`，Helm revision 24 更新 control/worker，revision 25 更新 console。预览逐字段比较仅改变相应 Deployment 的镜像字段；发布前后 values 只改变两个镜像 tag，runtime Secret 数据摘要一致。保留恢复故障时的 ScheduleAnyway 调度设置。

两个运行时镜像基于源节点已验证的原镜像层，仅替换经过测试的 JAR/静态资源；容器用户、入口与运行配置符合原 runtime Dockerfile。源节点 Docker 构建并导入 K3s，镜像中的 JAR 摘要与本地一致，console 恰有全部 45 个构建文件且逐文件摘要一致。

| 产物 | SHA256 |
| --- | --- |
| control `20261007-square-matrix-r1` image ID | `eda7521e22c278bf6281f9c7748fe726fb11fd0ae1078826ba03bf05c5c9f4b1` |
| console `20261007-square-matrix-r1` image ID | `f2c99b673b04585b0ff471686753cf145d2b578c78ac85adccf6fe8936c4ef55` |
| JAR | `80e1df39da18713479b5cec04675cae17ce7d9c13dd749b15c89ae1046b2bf8c` |
| 主 JS `index-kL_VX7z2.js` | `4b6be3491e20a6bd196cbd92abd442f2bd124a17a1858eac99a5a9a98382d5ea` |

公网 `https://noeriva.sagslab.org/` 返回 200，实际浏览器显示正常登录页、无应用运行错误和横向溢出。44 个静态资源的 SHA256 均匹配测试产物，HTML 的三项自身资源引用一致。Cloudflare 给 HTML 追加挑战与分析脚本，因既有 CSP 被拦截并产生两条控制台信息；这些不是 NOERIVA 应用错误，HTML 因此不能直接比较整文件摘要。

真实设备（VICTORIAMETRICS）和接口（CLICKHOUSE:network）分别验证 7/28 两种矩阵均返回 49/784 格，旧默认契约仍为 168 格；control 2/2、console 2/2、worker 1/1 Available。

受限维护备份：源节点 `/var/backups/noeriva-square-heatmap-20261007/`。构建上下文和镜像归档位于 `/opt/noeriva-square-heatmap-20261007/`。数据和凭据备份不进入 Git。离线 worker 节点的边界沿用 [故障恢复记录](OUTAGE-RECOVERY-20261007.md)；该节点恢复后需导入这两个新镜像才能承载新版本。
