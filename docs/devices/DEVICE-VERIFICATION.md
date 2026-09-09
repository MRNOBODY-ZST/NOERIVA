# 设备接入验收记录

日期：2026-09-06，Asia/Shanghai。本轮交付已部署到本机 [澄明工作台](http://127.0.0.1:18000)。设备管理、SNMP v2c/v3、HTTPS Redfish、自动身份候选、周期只读采集及浏览器 SSE 已接通。**未连接真实设备，也未部署生产 Kubernetes 集群。** 用户后续可以用 iMana、Cisco IOS XE、Inspur、H3C、Dell iDRAC 和 Network OS9 逐台验收。

## 可复核结果

| 项目 | 实际结果与范围 |
|---|---|
| 后端 | Maven 全量 `verify` 后，对后续修复运行专项回归；当前 Surefire 合并记录 **181 tests / 0 failures / 0 errors / 0 skipped**。包含真实 MySQL，以及单独启用的本机 VictoriaMetrics / ClickHouse 测试。不是声称一次命令同时运行全部 181 项。 |
| SNMP | **17 项**真实 UDP/SNMP4J 测试：v2c、v3、认证与加密、错误凭据、厂商候选、计数器、稀疏表、单位、预算、取消。使用合成身份与读数。 |
| Redfish | **30 项**本机真实 HTTPS 测试：厂商合成响应、旧/新资源、TLS、身份与传感器、错误认证、同源约束、限额、缺失与不可用功率。 |
| 安全与生命周期 | Vault/目标审核 6 项、计数器 5 项、连接 SQL 集成 9 项；覆盖组织隔离、密文认证、版本冲突、租约、取消后收尾、来源接管、完整与部分接口目录。 |
| 前端 | **57 项**测试通过，`vue-tsc` 与 Vite 构建通过。新设备进入监测页、可见页周期更新时间窗口、离开页清理均有回归。 |
| 实际数据链路 | 两台通过 Computer Use 登记的 Mock 设备，经协议读取 → Worker / 手动采集 → MySQL / Kafka / VictoriaMetrics → 页面；SNMP 接口热力图确认 `CLICKHOUSE:network`，168 个格子保留质量标记。 |
| 本机有界查询冒烟 | 周期 SNMP 采集期间，状态接口 **160 请求、16 并发、0 错误**；中位 32.57ms，p95 **83.29ms**，最大 92.48ms。这是本机短时特定查询组合，不是生产吞吐或 SLO 认证。 |
| Compose | 最终 control、worker、console 镜像构建和重建成功，已有数据卷保留；API readiness `UP`，Worker 心跳与采集记录可见。 |
| Kubernetes | Helm 严格 lint 和模板渲染通过。配置包含 Secret 凭据密钥引用、设备网段与 egress、独立 Worker。尚未执行集群部署、故障转移或长期容量验证。 |

机器记录：[后端套件](../implementation/device-backend-tests.json)、[运行链路与查询冒烟](../implementation/device-runtime-verification.json)、[最终界面复验](../implementation/device-final-ui-verification.json)。独立审查发现的问题已修复并闭环，见[代码复审](DEVICE-CODE-REVIEW.md)。

## Computer Use 实际操作

通过现有浏览器界面操作本机工作台，没有向真实设备发送请求：

1. 登记 SNMP Mock，配置 v3 SHA256 / AES128，保存、测试、立即采集、启用 15 秒轮询。识别 Huawei VRP 合成身份，CPU 17%、内存 42%、温度 36°C；首次流量为空并标识基线，连续采样后出现约 1Mbps 接收、0.5Mbps 发送。
2. 编辑设备名称和登记品牌，资产版本独立递增；采集配置与历史继续保留。最终名称为 `Mock-SNMP-已编辑-0906`。
3. 登记 `Mock-Redfish-协议验收-0906`，配置 HTTPS 与证书指纹，识别 Dell / iDRAC 合成身份；温度 23.5°C、整机功率 180W，8 项传感器结果。模拟器故意缺失一个温度读数，页面保持 `PARTIAL / READING_UNAVAILABLE`，没有补零。
4. 用错误的合成密码测试，页面显示 `AUTHENTICATION_FAILED`；恢复后采集成功。再次编辑时密码为空，保存后仍可采集，验证同身份留空保留密钥。接口响应及数据库专项检查未发现测试明文凭据被回显或明文存储。
5. 设备详情中的 SSE 状态、采集时间和按钮状态随任务更新。Redfish 在最终界面启用 60 秒周期采集，版本 5；21:20:26 观察到后台再次成功读取。两个 Mock 均保留用于查看。
6. 桌面布局与 390×844 窄屏检查：接入页面和连接表单无水平溢出。最终浏览器 warning/error 日志为空。

### 本轮真实浏览器发现并修复的问题

- **新采集后趋势仍为空**：详情页查询结束时间原先冻结于进入页面时。现已在进入监测页、切换设备/指标/时间范围及页面可见时每 20 秒推进；隐藏和离开时停止。CUA 于 21:18:18 采集新数据后进入监测页，无需点击刷新即可显示 23.5°C / 180W，趋势表包含新时间窗口内的数值。趋势表时间是查询采样网格，原始观测时间另有展示。
- **设备详情刷新 404**：原 Nginx `/assets/` 静态资源规则截获了同名资产路由。Vite 资源改至 `/static/`；SPA 路由返回入口 HTML 且不缓存，带哈希静态资源长期缓存，缺失静态文件仍返回 404。部署后设备详情直接刷新恢复登录并返回原设备；资产目录、旧设备别名和事件深链接的服务端路径检查通过。
- **健康与数值混淆**：仅有组件健康的项显示状态项，缺失数字保持缺失；登记类型显示中文。模拟器的警告与缺失是有意的测试条件。

## 复现与后续真机验收

后端测试使用仓库 Maven wrapper；设备协议测试使用本地 UDP / HTTPS fixture。全量单元与 SQL 验证需要测试环境 Docker 可用，真实 VM / CH 测试另按既有 opt-in 参数执行。前端在 `frontend/noeriva-console` 运行 `pnpm test` 与 `pnpm build`。

当前 Mock 运行链路的只读验证脚本：

```sh
python3 scripts/verify-device-integration.py \
  --snmp-device 982b8ca9-94af-435b-ac9f-0fa4557a9f46 \
  --redfish-device 4abf9a96-f127-4cf9-bc6d-1f26a21fadff
```

脚本仅允许 `Mock-` 名称，读取本机 `.env` 登录，报告不记录口令或令牌。两个协议模拟器为本次任务启动的宿主机进程，非 Compose 自启服务；终止它们或重启宿主机后，Mock 连接会保留最后成功观测并报告不可达，需重新启动 fixture 后继续测试。

各页面目的、字段、操作与验收标准见[15 页功能规划](../frontend/CLARITY-PAGE-PLAN.md)。接入方式、Kubernetes 参数及逐台验收表见[设备操作指南](DEVICE-OPERATIONS.md)。每个厂商的已实现对象、固件限制和官方研究依据见[支持范围](DEVICE-SUPPORT.md)。

当前浏览器实时更新使用 SSE；未实现设备私有 WebSocket、NETCONF/SSH、IPMI、Trap、Redfish EventService 订阅或任意 OEM 全覆盖。具体型号/固件只有在核对真实返回、读数单位、权限、连续采集与异常恢复后，才可提升为真机验证状态。
