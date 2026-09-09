# iMana / SA5212M5：从 BMC 网页启用 SNMP 的证据与实现缺口

研究日期：2026-09-09。范围为现场 Huawei RH2288 V2、RH2288H V2 / RH2288H V2-12L（iMana U1029 7.35 / 7.38）及 Inspur SA5212M5（BMC 4.26.6）。本轮仅阅读已有代码、历史型号记录和厂商公开资料，没有连接、修改设备或尝试默认凭据。以下配置建议不是本轮真机成功记录。

## 1. 已确认的网页配置路径

| 设备 | 厂商证据 | 网页接入结论 | 仍需真机核对 |
| --- | --- | --- | --- |
| Inspur SA5212M5 | 精确型号用户手册 V2.5“SNMP”“SNMP 用户”“协议和端口”，印刷 p142、176、179–180 | 在“BMC 设置 → 服务设置”管理 SNMP 服务；Get/Set 服务默认启用 UDP 161。SNMP 用户可在 WEB GUI 创建；它与 IPMI/WEB/SSH 统一用户分开说明。支持 SHA/MD5 与 AES/DES，v1/v2c 默认关闭。 | 4.26.6 实际菜单、SNMP 用户创建入口、是否提供用户级只读权限、MIB 下载入口。文档 V2.5 发布晚于现场固件。 |
| Huawei RH2288 V2 / RH2288H V2 | RH2288 V2 产品册；华为旧版服务开关说明，适用名单明确包括这两个型号 | 产品支持 SNMPv3；旧 BMC 网页“Switch → Service”存在 SNMP 开关，可从已有网页登录会话检查。 | 7.35 / 7.38 的 SNMP 用户与本地用户关系、认证/加密算法、只读角色、实际 UDP 端口、MIB 文件。不能将新版 iBMC 流程当成已确认的 iMana 流程。 |

来源：[Inspur SA5212M5 用户手册 V2.5](https://www.inspur.com/eportal/fileDir/active_download/userBook/SA5212M5/%E6%B5%AA%E6%BD%AE%E8%8B%B1%E4%BF%A1%E6%9C%8D%E5%8A%A1%E5%99%A8%20SA5212M5%20%E7%94%A8%E6%88%B7%E6%89%8B%E5%86%8C%20V2.5.pdf)、[官方型号下载页](https://www.inspur.com/eportal/ui?pageId=2367231&product_id=4191&struts.portlet.action=%2Fportlet%2Fdownload-front%21toView.action)、[Huawei RH2288 V2 产品册](https://enterprise.huawei.com/ucmf/groups/public/documents/enterprise_webasset/hw_143243.pdf)、[Huawei 旧款 WebUI 服务开关说明](https://www.huawei.com/en/psirt/security-advisories/2015/hw-377648)。最后一个来源的原用途是历史漏洞规避，此处仅引用型号及菜单存在，**不照抄关闭其他服务的操作**。

## 2. 不使用 SSH 的接入流程

1. 用用户授权的现有 BMC 网页账号登录管理页面，读取 SNMP 服务、用户及权限配置。网页账号用于配置 BMC；除非该固件明确共享账号，不把网页密码自动复制成 USM 认证/隐私密钥。
2. SA5212M5 优先创建专用 SNMPv3 用户，并按真实界面选择只读权限；已有服务/算法满足要求时无需重置。推荐配置为 `authPriv`，设备界面 `SHA` 对应系统 `SHA1`，`AES` 对应 `AES128`。这是现有驱动的算法映射，不是自动协商。若 UI 不提供用户级只读字段，记录“远端账户权限未核实”；本系统不发送 SET 并不能证明设备侧账号没有写权限。
3. iMana 先检查旧网页 `Switch → Service`，再根据真实页面确定用户和算法。现有公开证据不足以确认“root 的网页密码同时是认证密码和隐私密码”，因此不据此批量尝试，也不依据新版 iBMC 的相似版本号设置旧 iMana。
4. 从实际采集节点以明确参数做一次只读身份 GET；成功后对已核实的表做有界 GETBULK，留存 OID、ASN.1 类型、原值、索引及质量标记。登录失败与不可读 MIB 分开诊断。不要扫描整个私有企业树内可能含账户配置的对象。
5. 私有传感器必须先取得对应固件的 MIB（设备网页的帮助/下载入口、该型号官方固件附带文件或厂商支持渠道）。校验 MODULE-IDENTITY、REVISION、表索引、单位及状态枚举，然后用真实只读返回做固定测试样本。仅当新 SNMP 源完整满足已验收字段时替换旧来源；暂缺项应显示真实缺口。

Inspur 的 Trap 告警配置与 Get/GetBulk 轮询属于不同功能。用户只要求采集时，不需要为其设置 Trap 目标，也不需要打开明文 v1/v2c。手册 §6.12.1 提供的 MIB 用于 Trap 解析，不能自动认定包含所有轮询传感器对象。[同型号手册 §6.12.1](https://www.inspur.com/eportal/fileDir/active_download/userBook/SA5212M5/%E6%B5%AA%E6%BD%AE%E8%8B%B1%E4%BF%A1%E6%9C%8D%E5%8A%A1%E5%99%A8%20SA5212M5%20%E7%94%A8%E6%88%B7%E6%89%8B%E5%86%8C%20V2.5.pdf)

## 3. 可精确列出的 OID 与适用边界

以下标准对象已在现有驱动实现；精确编号不等于两个 BMC 固件保证实现。返回 `noSuchObject` / `noSuchInstance` 应记录不可用，不写零。

| 用途 | OID | 解读方式 |
| --- | --- | --- |
| 系统描述 | `1.3.6.1.2.1.1.1.0` | 字符串，保留实际描述；只按已审核模式提取型号/版本 |
| 产品标识 | `1.3.6.1.2.1.1.2.0` | OBJECT IDENTIFIER；匹配厂商候选，不证明私有表布局 |
| 代理运行时间 | `1.3.6.1.2.1.1.3.0` | TimeTicks，百分之一秒；不是主机开机时间或健康状态 |
| 设备名 | `1.3.6.1.2.1.1.5.0` | DisplayString；不是不可变身份 |
| 实体类型 / 名称 | `1.3.6.1.2.1.47.1.1.1.1.5.i` / `.7.i` | 按 entPhysicalIndex 关联机箱实体和传感器名称 |
| 固件 / 软件 / 序列号 / 型号 | `1.3.6.1.2.1.47.1.1.1.1.{9,10,11,13}.i` | 当前驱动读取 chassis 实体；空、NA 等占位不当作有效资产号 |
| 标准传感器类型 / 倍率 / 精度 / 原值 / 有效状态 | `1.3.6.1.2.1.99.1.1.1.{1,2,3,4,5}.i` | normalized = value × 10^((scale−9)×3−precision)；例如 Celsius=8、W=6、RPM=10。operStatus=ok 只证明读数有效，不能直接判整机健康 |

标准定义及先前核对来源见 [SNMP-PROTOCOL-RESEARCH.md](SNMP-PROTOCOL-RESEARCH.md) 的标准身份、ENTITY、传感器章节。本次厂商资料没有给出**上述两代 BMC 私有传感器表的可靠数值 OID/单位/状态枚举**，因此没有凭第三方 OID 库或另一代固件补造映射。

重要排除项：

- 现有 Huawei `1.3.6.1.4.1.2011.5.25.31.1.1.1.1` 是已核对的网络 VRP entity-extent 表，不能给 iMana 套用。
- 官方新一代 xFusion iBMC API 文档出现 `1.3.6.1.4.1.58132.2.235.1.1.26...` 温度树，其文档标题明确 **Server (V6 or Later)**。不能替换企业号后把它当成 RH2288 V2 iMana 的已确认 MIB。[xFusion V6+ SNMP API，§5.24](https://www.xfusion.com/wp-content/uploads/2025/11/Server-V6-or-Later-iBMC-SNMP-API-Description-.pdf)
- Huawei 公共 `Config → System Settings` 页面明确写的是 **iBMC**（v3 默认启用且不可关闭，SHA1/AES 等），不是旧 iMana 7.38 的版本证据。[Huawei System Settings](https://info.support.huawei.com/hedex/api/pages/EDOC1000053358/YEF0907R/25/resources/en-us_concept_0000001990220320.html)
- Inspur `1.3.6.1.4.1.37945` 在当前代码中只是企业号候选；本次没有从 SA5212M5 官方 MIB 证实其 sysObjectID 或传感器根。其它 Inspur 存储/网络产品 MIB 不能替代 SA5212M5 BMC MIB。

## 4. 当前驱动的明确缺口

| 代码 | 现状 | 接入后需要补充 |
| --- | --- | --- |
| `SnmpProfiles.identify` | iMana/iBMC 仅 `huawei-bmc-generic-snmp`；Inspur 仅 `inspur-generic-snmp`；均没有 BMC 私有 sensor 分支 | 用真实 sysObjectID、型号和固件限定 profile；未知企业号保留 Unknown，不能靠品牌字符串强认 |
| `SnmpProfiles.sensors` | 两者只读标准 ENTITY-SENSOR 表；支持温度、功率、电压、电流、风扇转速 | 私有温度/风扇/电源/CPU/DIMM/磁盘/电池/离散告警表；明确每种状态、缺席、禁用和 unavailable 的语义 |
| `SnmpDriver` | 标准机箱实体提供型号/固件/序列号；标准表不存在时身份字段可能缺 | 固件专用资产表、机箱健康、BMC 自检/SEL 事件等；记录来源 OID，不能从请求成功推导 HEALTHY |
| `SnmpEnvironment` | 私有环境与阈值现只覆盖已核对 Dell/Cisco；BMC 没有私有健康映射 | 按实际 MIB 处理告警等级、传感器状态和阈值；不得默认“0 正常”或套用其它厂商 1=OK |
| `SnmpSession` | SNMPv2c/v3；GET/GETBULK；SHA1/MD5/SHA256/SHA512，AES128/DES；显式 contextName；每表索引 arity 1–3、组件至 signed-int 上限 | 若 BMC MIB 使用 OCTET STRING/复合可变长索引须新增专用有界解析；不是修改 OID 字符串就能复用整数表 |
| 采集预算 | 每轮 25s、2500 返回变量、128 传感器上限 | 需实测完整 BMC 表预算。原 SSH iMana 约 99–101 传感器是比对基线，不表示 SNMP 必定提供同样条数；超限必须显式 PARTIAL，不能静默截断 |

本次只产出研究与接入契约，不改变驱动或设备配置。后续验收应区分 SNMP 身份成功、私有传感器成功、只读权限已核实和完整覆盖四件事；记录每台机器的真实差异。

## 5. 后续现场网页证据（与公开资料分开）

2026-09-09 主代理通过用户授权的 WebUI 登录 iMana `.11` 后报告：“配置 → 服务 → SNMP 配置”中 v3 已开启、v1/v2 已关闭、算法 SHA/AES；本地用户 root 与 argusro 存在。此事实补充了旧版 UI/算法缺口，尚不单独证明 argusro 的 USM 凭据有效或其 SNMP 权限只读；应等待该账户的只读身份请求及 MIB 返回结果。本子任务没有登录此设备。

## 6. 后续只读协议核验与实现（更新前述研究缺口）

主代理随后取得 iMana `.11` 的只读 SNMP 返回，保存于私有 `.local/snmp-preferred-20260909/imana-11-{walk,sensors,firmware}.json`。下表是**现场证据**，不是将 V6+ 手册无条件套用到 V2。这里只使用有实际返回、已有传感器单位记录或网页交叉验证的字段；其余状态保留原始值。

令 `R=1.3.6.1.4.1.2011.2.235.1.1`：

| 对象 | 现场含义与约束 |
| --- | --- |
| `sysObjectID=1.3.6.1.4.1.2011.2.235` | 实际 sysDescr 是 `Hardware management system`，没有 iMana 字样；新增精确产品根 profile `huawei-server-bmc`，私有数值规则再限制已核验 RH2288 V2 / RH2288H V2 型号 |
| `R.1.6.0` / `R.1.7.0` | RH2288H V2-12L 型号 / 机箱序列号，与已有资产记录对应 |
| `R.1.1.0` | 本机值 1，网页同时显示紧急/严重/轻微均为 0、绿色正常；只将 1 映射 HEALTHY，未核验的其它值 UNKNOWN |
| `R.1.13.0` | 整机瞬时功率，SNMP 约 159、网页约 162 W，单位已由“电源管理 → 功率统计”核实；不是 PSU 读数简单相加 |
| `R.11.50.1.{1,2,4}.x` | 固件名称 / 类型 / 版本。Active iMana=7.38、Backup iMana=7.23，两者类型均为 1；因此取明确名为 Active iMana 的记录，不能任选首个 type=1 |
| `R.13.50.1.{1,2,3,4,5,6,7,8,9,10}.x` | 传感器名称 / 读数 / 上不可恢复、上严重、上轻微、下不可恢复、下严重、下轻微阈值 / 原始状态 / IPMI 类型 |

`x` 是完整长度前缀 OCTET STRING 索引：传感器为 17 字节（含尾部零），固件随名称长度变化。新增 `walkOctetStringIndex` 按前缀长度、0–255 字节及总长上限校验，使用完整索引关联列。固件整树默认 GETBULK 在老代理超时；逐列 `maxRepetitions=1` 成功，代码对固件采用此值；私有列 GET 每批最多 8 项。传感器列继续有界批取，不读取账号配置分支。

本次 `.13` 返回 99 行，每行 12 列：18 个数值、81 个不可用或离散状态。已核对的数值单位来自同机型此前只读传感器记录和当前网页：类型 1 温度、类型 4 风扇 RPM、名为 PowerN 的类型 9 功率 W。CPU DTS 原值 −59 / −61 是相对温度，使用 `temperature_margin_celsius`，不进入最高绝对温度概览。其它不明类型以状态项保留，不造单位。`na` 保持 null，实际 0 保持 0。

原始状态是 `0x00c0` / `0x8000` / `0x8040` 等 IPMI 位域，**没有当作简单枚举解码**。`huaweiSensorEvidence` 保存原始读数、类型、状态和数值阈值；只有已提供阈值且读数有效的传感器才进行上下限比较。整机 HEALTHY 使用独立 `R.1.1.0` 依据，采集不完整或未知全局码保持 UNKNOWN；实际阈值报警仍优先显示 WARNING / CRITICAL。

实现位于 `SnmpHuaweiBmc`、`SnmpProfiles`、`SnmpEnvironment`，身份 fallback 由 `SnmpDriver` 接入。它补充了第 4 节的 iMana 缺口；Inspur SA5212M5 私有 SNMP MIB 仍需要其本机证据，不能把该实现扩展为 Inspur 支持。

## 7. 上线后空值和请求预算修复

首轮上线实测 `.11` 在完整采集时约 15.2 秒，出现超时时可接近 25 秒预算；`.12` 约 17.7 秒并被错误标为缺关键列。只读 API 证据显示 `.12` 的 CPU2/DIMM1 相关 3 个 reading、16 个 status 没有文本值。主代理随后做精确 GET，确认 CPU2 DTS 的 reading/status 是**合法空 OCTET STRING**，type=1 仍存在；它们不是 noSuchInstance 或 GET 失败。通用 `text()` 把空字符串折叠成 null，导致私有解析误判字段缺失。

修复后的规则：

- 判断 VarBind 是否存在及类型是否合法；空 OCTET STRING 与普通 ASN.1 NULL 都属于已返回的不可用值。`huaweiSensorEvidence` 记录 `readingAvailability` / `statusAvailability` 为 `VALUE`、`RETURNED_UNAVAILABLE`、`RETURNED_NULL` 或 `MISSING`，保留实际空字符串/null。`noSuchObject` / `noSuchInstance` / `endOfMibView` 不当成合法传感器值。
- 关键 reading/status/type 真正缺失、类型不符、超时或表不完整时仍显示采集不完整。可选阈值未实现、`na` 或空值不覆盖完整且已核验的 systemHealth=1。空的叶子传感器仍是 null/UNKNOWN，不伪造数值或叶子健康。
- 传感器采用按列 `GETBULK maxRepetitions=20`，先读取 name/reading/status/type，再读取可选阈值。每列最多 128 行，沿用总变量、OID 增序、索引和 25 秒截止限制；只扩展 Huawei 专用 nullable 列读取入口，普通邻居和标准表读取语义不变。固件仍用已验证的小包方式。

11 项 Huawei UDP 回归涵盖真实 99 行回放、空字符串、合法 NULL 后继续读取、noSuchInstance、真正丢失 type、未实现可选阈值和请求数上限。99 行回放要求整轮请求数不超过 90，防止回退为超过 110 个长索引 GET 批次。与 SNMP Driver、Neighbors、NBAR、Environment 合计 **59 项通过，0 失败/错误**。本记录的实际性能改善仍需新版本部署后的设备采样计时确认；不能用本地 UDP fixture 的毫秒耗时代替真机耗时。
