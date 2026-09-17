# 设备接入与采集：澄明工作台实现说明

更新：2026-09-07。本文件已按 [DEVICE-INTEGRATION-API.md](DEVICE-INTEGRATION-API.md) 的最终契约更新，替代早期 collection-profiles / 异步测试任务建议。协议实现状态与实机验证以服务端支持目录和实际测试报告为准。用户要求是自行登记、编辑设备，填写 SNMPv2c/v3、Redfish 或固定只读 SSH 账户密钥，测试并识别品牌型号和能力，启停只读采集并查看实时状态。

## 页面目的与路径

| 入口 | 目的与行为 |
|---|---|
| 资产目录 `/assets` | 按名称、IP、类型、站点、健康筛选设备；“登记设备”创建真实资产记录。默认勾选“保存后继续接入配置”，成功进入该设备 `?tab=access` |
| 详情“接入与采集” `/assets/:id?tab=access` | 一处完成资产资料编辑、三类独立连接配置、测试识别、立即采集、周期启停和运行状态查看 |
| 资产资料卡 | 展示管理地址、登记品牌型号、资产版本；ADMIN/OPERATOR 可编辑 6 个基础字段；管理地址不自动改变已有连接目标 |
| SNMP 连接卡 | 独立管理 SNMPv2c 或 v3，固定对应来源 network；最多一份 SNMP 配置 |
| Redfish 连接卡 | 独立管理 HTTPS BMC 连接及认证，固定对应来源 bmc；最多一份 Redfish 配置 |
| SSH 连接卡 | 独立目标、用户名、只写密码、三种固定只读档案和必需 SHA256 主机密钥指纹，固定来源 ssh |
| 发现设备 `/assets/discovery` | 明确 CIDR 与来源站点，整理已保存邻居证据，审核候选并登记或关联；不执行扫描 |
| 识别结果 | 显示实际品牌、系列、匹配 profileId、型号、序列号、固件、sysObjectId、sysName、描述和依据。设备未返回的值显示缺失；登记品牌型号保持独立，可手动编辑 |
| 实际数据 | 展示 Reading 中真实能力、质量标志、指标、传感器、端口计数及来源；缺失不写为零，计数字符串不做浮点转换 |
| 厂商支持目录 | 展示服务端预设档案的协议、已实现范围、验证等级与限制；不能把匹配到厂商视作真机验证 |

风格沿用 Clarity 白色 panel、蓝色主按钮、清晰边框、原有间距和类型层级。连接卡宽屏双列、窄屏单列；传感器和端口以原生 details 展开，宽表提供具名、可聚焦滚动区域。新标签独立于“配置与变更”快照审阅。设备资产关系读取改为 `/topology?deviceId=...`，避免与新凭据连接 API 冲突。

## 最小完整操作流程

1. 在资产目录填写设备名称、类型、站点和管理地址，品牌型号可留空。保存一次即跳转新设备接入页。
2. ADMIN 选择 SNMP、Redfish 或 SSH“配置连接”。目标初值复制管理地址，之后独立保存。秘密只在密码输入框中填写，不显示已有值。
3. 保存连接后先“测试连接并识别”。测试使用已保存配置和当前连接 revision，不测试尚未保存的表单值，也不自动启用采集。
4. 查看结果时间、成功/部分成功/错误、品牌型号与读取能力。只有实际返回的数据才展示为已观测，generic 保持通用匹配；错误保留最近成功时间。
5. “立即采集”执行单轮并发布到已有指标/事件链路。“启用采集”安排周期执行；UI 分别显示周期是否启用和最近一次读取状态。API 节点本身是否启用后台调度不用于禁用配置或手动测试。
6. 停用成功后不再安排新任务，历史仍保留。当前存在活动读取租约时，启停/配置变更返回 409；客户端重读后再操作，不声称已取消正在执行的请求。租约和 revision 阻止旧结果覆盖新设置。
7. 编辑连接时启用状态默认为 false。保存后提示重新测试。修改 host/port、用户、协议安全设置等身份字段时，前端要求重新填写对应秘密，后端再次验证。

## 实际 API 与权限

所有路径以 `/api/v1` 开始，所有 POST 采用 JSON 并带 `X-Noeriva-Request: 1`。

| API | 前端行为 | 角色 |
|---|---|---|
| `POST /devices` | 保留现有登记 DTO | ADMIN / OPERATOR |
| `GET /devices/{id}/management` | `{device,inventoryRevision}` 用于编辑 | 所有读角色 |
| `POST /devices/{id}/updates` | `{revision,name,type,siteId,vendor,model,managementAddress}`；revision 必须取 inventoryRevision | ADMIN / OPERATOR |
| `GET /device-support` | 支持目录、credentialStorageReady、collectorEnabled | 所有读角色 |
| `GET /devices/{id}/connections` | 读取设置、用户名和 hasSecret 标识；无秘密 | 仅 ADMIN |
| `POST /devices/{id}/connections/{snmp\|redfish\|ssh}` | revision 0 创建，已有 revision 更新，秘密留空以 null 保留 | 仅 ADMIN |
| `POST .../{slot}/test` | `{revision}`，显示返回后的最新结果 | 仅 ADMIN |
| `POST .../{slot}/collect` | `{revision}`，执行单轮采集 | 仅 ADMIN |
| `POST .../{slot}/state` | `{revision,enabled}`，启停 | 仅 ADMIN |
| `GET /devices/{id}/collection` | 安全结果，无用户名和秘密 | 所有读角色 |
| `GET /devices/{id}/live` | 安全 collection SSE | 所有读角色 |

`session.canWrite` 只用于资产资料编辑。连接及 test/collect/state 均需 ADMIN，OPERATOR/VIEWER 不请求 `/connections`。服务端 matcher 与 service guard 同样执行此边界。

原 Device.revision 混合了遥测投影修订，编辑只能使用新的 inventoryRevision。编辑期间自动读取不覆盖草稿；409 显示冲突，用户点击“重新读取”恢复服务端最新资料，不能盲目重试覆盖。

## 连接字段

| 设置 | 实际默认/范围 |
|---|---|
| 目标 | 独立 host、port；SNMP 默认 161，Redfish 默认 443，SSH 默认 22 |
| SNMP 版本 | 默认 v3，可显式选 v2c |
| v3 安全级别 | 默认 authPriv；可选 authNoPriv、noAuthNoPriv；按级别动态要求认证/加密密码 |
| 认证算法 | 默认 SHA256；支持 SHA512；SHA1/MD5 仅显式选择的旧设备兼容，不自动降级 |
| 加密算法 | 默认 AES128；DES 仅显式选择的旧设备兼容，AES256 不支持 |
| SNMP Context | 可选，最多 64 字符 |
| Redfish TLS | SYSTEM 或 PINNED；PINNED 要求 64 位十六进制 SHA-256，可用冒号分隔；没有跳过证书验证选项 |
| SSH 档案 | 必须明确选择 HUAWEI_IMANA / DELL_OS9 / CISCO_IOS_XE；无自定义命令输入 |
| SSH 主机密钥 | 必需规范 `SHA256:` + 43 字符无填充 Base64；先校验再认证，不自动信任或降级 |
| 采集周期 | 默认 60 秒，15–86400 秒 |
| 单次超时 | 默认 3000 ms，250–10000 ms |
| 接口上限 | 默认 128，1–256 |

SNMPv2c 无默认 community。首次保存必要秘密不能为空；同类型、同身份连接留空发送 null，保留已有秘密。三类协议账户分开，不共享输入。SSH host/port/username/profile/pin 任一变化要求重填密码；已保存的相同有效身份留空发送 null 保留。SSH 档案变化清空当前密码。协议切换会清除当前密码输入，避免误用另一种认证材料。

## 凭据与错误处理

密码字段没有 v-model，使用当前表单 DOM 和本次请求的短暂序列化对象；不存 Pinia、查询缓存、localStorage、sessionStorage、URL、下载或日志。保存提交前、取消、协议级别切换和卸载会清空密码 DOM。错误响应只显示安全的保存失败/版本冲突/存储不可用提示，避免将可能包含请求内容的异常直接回显。

所有响应只含配置和密钥存在标识，不含明文或密文。密钥在服务端通过 AES-256-GCM 保存，AAD 绑定组织、设备、slot，主密钥由环境或 Kubernetes Secret 注入。未配置主密钥时，前端显示“凭据存储尚未配置”并禁用配置按钮；可以继续浏览资产资料、支持目录与历史结果，不声称已保存凭据。

连接失败会展示服务端安全 errorCode/errorMessage 和最近尝试、最近成功时间；测试失败不等于设备离线。采集启用和最近读取成功分开显示；没有 lastSuccessAt 显示“尚无成功读取”。

## 实时与资源清理

SSE 通过 fetch 传 Authorization 头、Accept: text/event-stream 和 X-Noeriva-Request，不将令牌放入 URL。解析 event:collection 或 event: collection、CRLF/LF 和跨 chunk 数据；单缓冲上限 1 MiB，每个 collection 最多三个来源。

流关闭或失败后立即重新读取安全 collection，5 秒后重连；原查询每 20 秒轮询继续兜底。UI 显示“实时更新”或“轮询刷新 · 每 20 秒”。页面退出或 deviceId 切换会 AbortController 中止、取消 reader、清空重连计时器，不保留旧设备后台流。更晚的 asOf 结果才覆盖较新的安全结果。

## 发现页面的完整流程

1. 从资产目录“发现设备”进入 `/assets/discovery`，站点筛选只在有实际 ID 时传递。明确填写规范 IPv4 网络 `/24`–`/32`，不预填猜测的 `168.4.*` 范围。
2. 搜索同站点资产，添加 1–8 个来源。ADMIN 可跳往来源接入页手动采集；发现页不调用 connections、collect 或扫描。OPERATOR 可以整理已保存的来源结果。
3. 运行后展示 USED/MISSING/STALE/INVALID、来源观测时间、每项接受数、全次统计及质量标记。缺失、陈旧、无有效结果不代表网段不存在设备。
4. 候选目录按站点、状态和不透明游标分页。每条显示 IP、MAC、名称、状态、重复原因和最后观测，详情展示最多 16 条 ARP/LLDP/CDP 来源证据、ageMinutes、TTL/validUntil、接口、VLAN、chassis subtype 与质量标记。
5. NEW 可登记；POSSIBLE_DUPLICATE 必须先勾选已核实为独立资产，再提交名称和类型。CONFLICT 不显示新登记表单，只允许明确关联已有同站点资产。相同 MAC、相同名字或复用 SSH key 不触发自动合并。
6. 登记/关联带当前 revision。409 只重读候选供再次审核，不自动重复写入。成功登记进入新资产接入页；关联进入已有资产详情。来源秘密不会复制，已有资产地址与历史不会物理合并。

前缀搜索在 Enter 或失焦时提交，不逐键发请求。站点改变清除来源选择；候选变化清除旧目标。VIEWER 只查看候选，不请求写表单所需资产选择器。发现写入权限为 ADMIN/OPERATOR，独立于仅 ADMIN 的凭据权限。详细错误和服务限额见[发现 API](NETWORK-DISCOVERY-API.md)。

## 当前验证与后续联调

完整前端 71 项测试及生产构建通过，包含 SSH 三种档案、指纹规范/HTML pattern、真实请求字段与只写秘密、三来源 SSE 和超限拒绝，以及发现 CIDR、来源站点清理、前缀 Enter 请求、角色隔离、重复确认、冲突关联和 409 不盲重试。已有回归覆盖资产独立修订、SNMP/Redfish 安全字段、未知值和无队列语义。2026-09-07 的新增差异回归先复现 Dell 类 SSH 来源已有观测、仅身份/邻居但无数值时仍提示“未分配来源”的错误；修复后显示“暂无数值指标”及流量需 SNMP 等接口计数来源，无来源设备保留原等待提示。两处空态修改不改变查询或指标能力判定。

设备详情的相对历史窗口在进入可见概览/监测、切换设备/指标/小时数时推进到当前时间，并在可见监测期间每 20 秒推进；离开或浏览器隐藏时清理计时，不持续查询隐藏旧窗口。回归验证首次空结果后新采集样本能进入查询时间范围。

[六台真机验收](HARDWARE-VERIFICATION.md)已记录平台实际读取、来源发布和 60 秒周期采集：Dell 与三台 iMana SSH 成功，Cisco SSH 部分成功，Inspur Redfish 部分成功。浏览器 Computer Use 已核对 iMana 配置/识别/采集/启用、编辑资产资料，以及 26 个候选和 1 个已有资产关联。部分成功的缺失和兼容标记保留，不把登录或 HTTP 成功当成所有指标可用。型号与协议格式依据见[真机格式记录](HARDWARE-FORMAT-NOTES.md)；本轮真机 SNMP 未验收，也不把预设支持族统一提升为实机已认证。
