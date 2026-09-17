# 设备协议支持范围与硬件待验收清单

更新：2026-09-07。当前已实现 SNMPv2c/v3、标准 Redfish 与三种固定档案 SSH 的只读连接、身份发现、有界采集和发布链路。九族目录中的 `implemented:true` 表示这些代码路径可运行；`SIMULATOR_TESTED_HARDWARE_PENDING` 是当前预设族级返回值；具体型号的外部预检、平台测试和平台采集另行记录。Inspur SA5212M5 已有平台 Redfish 成功读取，状态为 PARTIAL 并保留兼容/缺失标记，这不使整个 Inspur 族升级。它不等于品牌全系兼容、`SUPPORTED` 或 `DEVICE_VERIFIED`。

本表以 `DeviceAccessService` 的支持目录、`SnmpProfiles` / `SnmpDriver` 和 `RedfishDriver` 实际代码为准。协议依据见两份附官方来源的研究：[SNMP 多厂商研究](SNMP-PROTOCOL-RESEARCH.md)与[Redfish / BMC 研究](BMC-PROTOCOL-RESEARCH.md)。研究中的“建议”“待实现”不自动进入本表；精确 REST 字段与角色见[设备接入 API](DEVICE-INTEGRATION-API.md)。

## 九族支持对照

所有行都为部分实现，不能用目录族概括全部真实型号的验证状态；本次型号级证据见后表。目录 ID 是供界面说明范围的预设族；实际 Reading.profileId 按成功读取证据决定，SNMP 的通用候选 profileId 不必与目录 ID 相同。企业 OID 根只能确认厂商候选，sysDescr 文字只提供系列线索。

| 目录族 / ID | 本次协议与识别依据 | 已实现读取范围 | 明确保留的边界 |
|---|---|---|---|
| Dell iDRAC / `dell-idrac` | SNMP 企业根 674/6027 + iDRAC 描述候选；Redfish Manufacturer 为 Dell，并以 Manager.Model 是否含 iDRAC 区分 family | SNMP 通用 system/ENTITY/IF-MIB、标准温度；Redfish 标准身份、健康、温度、风扇、电源与支持的标准资源 | SNMP 使用 `dell-bmc-generic-snmp`；Redfish 品牌 profile 命中不证明 Manager 是 iDRAC，family 可保留 REDFISH。未实现任意 Dell OEM 指标、远程控制和事件订阅 |
| Dell Networking OS9 / `dell-os9` | SNMP 674/6027 企业根，OS9/9.x 描述仅为系列候选；SSH `DELL_OS9` 固定身份和邻居读取 | `dell-generic-snmp`：通用身份、实体、接口状态/容量/计数器和标准温度；SSH 固定 version/inventory/ARP/LLDP，只保存安全字段 | 不把所有 Force10/Dell 设备认作 OS9；未启用私有 CPU/内存/温度表。OS9 常见旧算法需按实际固件显式配置 |
| Huawei iBMC / `huawei-ibmc` | SNMP 2011 企业根 + iBMC 描述；Redfish Huawei 厂商，iBMC Manager.Model 才细分 IBMC family | SNMP BMC 通用候选；Redfish 标准 Systems/Managers/Chassis、健康与传感器 | SNMP BMC 候选不运行 VRP 私有传感器规则；OEM 层级与具体机型资源未全覆盖 |
| Huawei iMana / `huawei-imana` | SNMP 2011 企业根 + iMana 描述；SSH `HUAWEI_IMANA` 交互式固定命令 | `huawei-bmc-generic-snmp` 通用读取；SSH 产品身份、健康、温度/风扇/功率/电压/电流和未解码离散状态 | 不假定旧 iMana 存在 Redfish，不以 iBMC 文档代替 iMana 型号证据；无私有 IPMI 驱动或任意 OEM 命令执行 |
| Cisco IOS XE / `cisco-ios-xe` | SNMP 企业根 9；IOS XE 描述为系列候选；SSH `CISCO_IOS_XE` 固定身份、ARP、LLDP/CDP | `cisco-process-entity`：通用身份/接口、PROCESS-MIB 实体 CPU、同实体 used/free 内存比例、Cisco 与标准温度表的独立类型/缩放；SSH 固定邻居证据投影 | 不据企业根区分全部 IOS/IOS XE/NX-OS/IOS XR；CPU 优先实际 MonInterval，缺少时回退旧 5 秒列。SHA-2 可用性依固件，无 BGP/VRF/路由邻居采集 |
| Inspur BMC / `inspur-bmc` | SNMP IANA PEN 37945 + BMC 描述候选；Redfish Manufacturer 含 Inspur | `inspur-generic-snmp` 通用读取；Redfish 标准身份、健康、温度、风扇、电源；已识别 Inspur 分支兼容受限数字字符串和 DISABLE 状态 | SA5212M5 / 4.26.6 有平台读取证据；其他型号仍待验收。PEN 不能证明私有 OID；不固定 Systems/Self 等实例路径，不假设所有固件 OEM 字段 |
| H3C HDM / `h3c-hdm` | SNMP 25506 企业根 + HDM 描述；Redfish H3C 厂商，HDM Manager.Model 才细分 HDM family | SNMP BMC 通用候选；Redfish 标准资源及可识别的旧 Members 字符串链接（带质量标记） | 不使用 Comware 私有 CPU/内存规则采集 HDM；不自动升级旧固件为完整 Redfish 兼容，无 HDM OEM 写操作 |
| H3C Comware / `h3c-comware` | SNMP 企业根 25506，Comware 描述为系列候选 | `h3c-entity-extent`：通用身份/接口、已审阅实体 CPU/内存百分比、可读标准温度 | 历史 2011.10 企业子树未映射；私有 temperature.12 的全代际单位未证明，不能直接转换摄氏度；无 VLAN/LAG/STP/LLDP/CDP 发现 |
| Generic 通用设备 / `generic` | 不足以匹配厂商时保持 `generic-snmp` 或 `redfish-generic` | SNMP system/IF-MIB/ENTITY 及标准温度；Redfish 标准资源，能力仅按成功读取对象列出 | 品牌文字不覆盖未知企业根证据；空表可因视图或权限隐藏，不等于永久不支持。未实现通用 HOST-RESOURCES CPU/内存、通用任意 SSH 或 exporter 采集 |

Huawei 网络 VRP 不单列为第十个目录族：SNMP 对 2011 根且描述含 VRP 的候选另有 `huawei-entity-extent`，读取已审阅 CPU/内存百分比及摄氏温度；没有 VRP 或 BMC 线索时为 `huawei-generic-snmp`。该对象探测路径已有合成 UDP 覆盖，真实产品与固件仍待验收。[SNMP 实现与官方单位依据](SNMP-PROTOCOL-RESEARCH.md#9-本轮已实现范围与可复现证据)

## 共同读取口径

**SNMP** 使用只读 GET/GETBULK，不执行 SET。v2c 使用用户指定 community；v3 支持 noAuthNoPriv/authNoPriv/authPriv，认证 SHA256、SHA512、SHA1、MD5，加密 AES128 或 DES。默认 SHA256/AES128；旧算法只能显式选择，无猜密钥、逐算法试探、降级、自动改设备配置或网络扫描。新 auth/priv 密码至少 8 字符，AES192/AES256/3DES 未实现。USM engine discovery 不是算法协商。[USM、SHA-2 与厂商固件证据](SNMP-PROTOCOL-RESEARCH.md#4-snmpv2c--v3-算法与兼容性)

接口按真实索引联接稀疏列，优先高容量计数器；原始 Counter64 为精确十进制字符串，缺失值保留 null。IF-MIB speed 是容量，不能当流量。当前最多配置 256 个接口，默认 128；传感器结果总上限 128，触限保留质量标记，不能用截断结果生成单一设备摘要。至少两个连续、可比较样本才生成速率；首次基线、重启、discontinuity 变化、缺失、异常回退和多回绕风险不补零。当前设备带宽摘要为返回接口速率之和，可能同时包含逻辑与物理接口，不是去重后的整机或站点吞吐。接口热图和修复任务按目录中已授权的 source_id 查询，SNMP 来源为 network。

ENTITY-MIB 的 firmware 使用 entPhysicalFirmwareRev（`.9`），softwareVersion 单列保存在 facts（`.10`），缺失固件不以软件版本冒充。SNMP 同一指标仅在未截断且恰好一条可用传感器时给出设备级摘要，多实体继续保留逐传感器结果；SNMP 可读本身不证明整机健康，缺少健康语义保持 UNKNOWN。标准 ENTITY-SENSOR 与 Cisco 私有表的温度类型枚举分别处理；没有审阅过单位的私有数值不做猜测归一化。

**Redfish** 从 `/redfish/v1` 沿实际同源链接发现 Systems/Managers/Chassis，读取旧 Thermal/Power 与本次已支持的新 Sensor、ThermalSubsystem/ThermalMetrics、PowerSubsystem/PowerSupply/Metrics 等标准资源。HTTPS Basic 账户来自独立 Redfish 凭据；SYSTEM 校验信任链及主机名，PINNED 校验叶证书 SHA-256 及有效期。目标 DNS 解析后按允许网段审核并固定地址，不能跟随外站链接、重定向或路径穿越。没有不校验证书模式，没有 Redfish SessionService 认证适配。[标准资源与 TLS 依据](BMC-PROTOCOL-RESEARCH.md)

Redfish 温度摘要为成功读取温度传感器的最大值，facts 中保留摘要与来源；不称平均或进风温度。整机 power_watts 仅在一个 System、一个 Chassis、唯一明确总功率候选且发现完整时发布；多机箱、多域、重复冲突或缺失时不生成整机功率。PSU 输入/输出功率、ThermalMetrics 功率分别呈现，不相加。风扇 RPM 与百分比不互换；CPU 数量、内存容量是库存事实，不伪造利用率。组件仅有健康时传感器值可为 null。单轮有请求数、响应大小、资源、传感器和时间预算，超限/无权/缺失资源产生质量标记。

**SSH** 仅允许 `HUAWEI_IMANA`、`DELL_OS9` 和 `CISCO_IOS_XE` 固定只读档案；每次请求使用已审核目标地址，强制规范 OpenSSH SHA256 主机密钥指纹，校验成功后才密码认证。使用默认现代协商，不接受任意命令、自动信任或自动算法降级。iMana 旧固件使用 shell 提示符就绪后的固定交互命令，数值与单位分离解释；`na` 保留缺失，离散位未解码时保持 UNKNOWN。温度摘要为可用温度最大值，多个 Power 项不相加为整机功率。Dell/Cisco 的 ARP/LLDP/CDP 以允许字段投影写入 facts，保留年龄/TTL；不把原始 CLI、SNMP 用户秘密或配置内容写入结果。[实际型号格式](HARDWARE-FORMAT-NOTES.md)与[旧设备接入研究](LEGACY-HARDWARE-ONBOARDING.md)

发现流程只消费这些已保存证据，不主动扫描。明确 CIDR、同站点来源、15 分钟来源新鲜度、TTL 和有界候选审核详见[发现契约](NETWORK-DISCOVERY-API.md)。相同组织/站点/IP 复用候选，相同 MAC 多地址产生疑点；人工登记/关联不会物理合并历史、移动凭据或变更已有资产地址。来源刚刚读到 ARP 表不代表表内每个 IP 在线。

## 从配置到数据的边界

- 基础资料由 ADMIN/OPERATOR 编辑，使用 inventoryRevision。连接与凭据、测试、立即采集、周期启停仅 ADMIN；所有读角色可查看安全 collection/SSE。修改管理地址不改变连接目标或转移秘密。
- 秘密以 AES-256-GCM 加密并绑定组织、设备、slot；响应只有 hasSecret 标志，不含明文/密文。相同有效身份留空保留，变更目标或安全身份要求重填。服务端主密钥未配置时不能保存连接。
- “测试”保存安全识别结果，不写时序/状态发布链路；“立即采集”和 Worker 周期任务才发布。SNMP、Redfish 与 SSH slot 分别使用 network / bmc / ssh。系统按组织/设备/指标绑定历史发布归属，Reading.facts.publishedMetricIds 给出实际发布项，避免多协议覆盖同名序列。
- 识别身份与登记资料独立，不自动覆盖名称/品牌/型号或合并设备。一个设备可以保存三个 slot；如业务将 host/BMC 分别登记，关系仍需已有关系证据，不凭地址猜测自动关联。
- 启用状态、执行状态、成功观测时间和设备健康是不同字段。部分成功保留缺失与质量标记；失败保留上次成功记录原时间。周期执行依赖 Worker 及数据库租约，API 节点的可用性不证明采集持续工作。内置 Worker 提供心跳，不提供外部 Edge 注册或本地持久缓冲队列。

## 已有验证与仍需硬件证明

| 证据 | 已覆盖 | 尚不能证明 |
|---|---|---|
| `SnmpDriverTest` / `SnmpUdpFixture` | 真实 UDP 与 SNMP4J USM/编码/解码：安全组合正反例、候选识别、稀疏/异常表、计数器、单位、接口/ENTITY/变量/128 传感器预算及取消、ENTITY 固件与软件字段区分 | 真实厂商固件的 MIB 实现、算法可用性、读取权限、性能与全部型号 |
| `RedfishDriverTest` | 本地真实 HTTPS：四厂商与 Generic 合成身份、旧/新资源、TLS 信任/固定地址/证书、错误认证、同源约束、限额、缺失/部分结果与指标口径 | 真实 BMC 的资源差异、OEM 全覆盖、网络延迟/负载与所有固件 |
| `SshDriverTest` 等 SSH fixture | 固定只读档案、主机密钥先于密码、身份匹配、传感器和邻居解析及请求边界 | 外部 CLI 预检不替代平台 SSH 端到端、周期任务和真实型号长期验收 |
| `DeviceAccessIntegrationTest`、`DeviceSafetyTest`、`DeviceCountersTest` | 真实 MySQL 连接/凭据隔离与版本、单一指标归属、租约/发布保护、加密与目标审核、计数器连续性 | 生产 Kubernetes 容量、跨站点断网补传、完整灾难恢复或实机认证 |
| 控制台 `device-integration.test.ts`、`device-ssh.test.ts`、`discovery-workflow.test.ts` 等 | 请求字段/权限、资产独立版本、秘密不回显与持久化、条件校验、SSE 清理/兜底、未知值与队列语义 | 浏览器自动化之外的真实硬件运行效果 |

协议模拟器 fixture 使用隔离的本地凭据；本次格式回归也包含按授权真机响应形态制作的脱敏样例。格式 fixture 通过本身不等于实际硬件端到端通过。Computer Use 接入链路、持续轮询、容器/Kubernetes 模板与验收结果由[设备操作文档](DEVICE-OPERATIONS.md)和最终验收报告记录，本表不提前替代这些证据。

实机提升验证等级时须保存具体型号、固件、SNMP 安全组合/Redfish 信任方式、已授权目标、实际对象/资源、脱敏原始响应、采集时间和对照读数，并测试错误凭据、部分权限、重启/计数器变化、启停与长时间轮询。一次厂商匹配不能给整族升级。

Trap/Inform、通用 SNMP LLDP/CDP 或自动拓扑建图、NETCONF/gNMI、BGP/VRF、VLAN/LAG/STP、全 OEM 传感器、IPMI、主机 OS/exporter、远程配置/电源操作、Redfish EventService 订阅、外部 Edge 缓冲均未由本次适配完成。目标架构范围仍保留在[完整能力矩阵](../capabilities/CAPABILITY-MATRIX.md)。

## 本次型号与协议证据

以下状态只对应实际型号、固件和读取路径。[六台真机验收](HARDWARE-VERIFICATION.md)在外部 CLI 预检之外，已记录平台实际读取、来源/历史发布和六台 60 秒周期采集。SSH key 曾在不同序列设备间复用，不可作为自动物理合并键。

| 型号 / 固件 | 协议 | 已有证据 | 本表不提前声明 |
|---|---|---|---|
| Inspur SA5212M5 / BMC 4.26.6，Redfish 1.0.2 | Redfish | 平台 Redfish 部分成功；41 项保留传感器/组件、8 个风扇 RPM、温度/功率历史，保留旧格式/缺失标记 | 全 Inspur OEM、所有固件或整族 DEVICE_VERIFIED |
| Dell S6100-ON / OS9 9.14(2.23) | SSH | 平台 SSH 成功；身份、服务标签、ARP/LLDP，来源与周期更新 | 数值带宽、SNMP 凭据有效或所有 OS9 机型 |
| Cisco ASR1002-X / IOS XE 17.09.08 | SSH | 平台 SSH 部分成功；身份/机箱序列/ARP；LLDP/CDP 未提供可识别结果 | 所有邻居命令有效、数值带宽或存在 Redfish |
| Huawei Tecal RH2288 V2 / iMana 7.35 | SSH | 平台交互式 SSH 成功；产品身份/健康、99 项传感器、温度历史 | Redfish 或所有 iMana |
| Huawei Tecal RH2288H V2 / iMana 7.38 | SSH | 平台交互式 SSH 成功；产品身份/健康、99 项传感器、温度历史 | 自动资产合并或所有型号兼容 |
| Huawei Tecal RH2288H V2-12L / iMana 7.38 | SSH | 平台交互式 SSH 成功；产品身份/健康、101 项传感器、温度历史；旧 HTTPS 默认握手失败 | 降低 TLS 策略后的兼容承诺或所有型号兼容 |

实际格式、身份依据、质量标记及官方定义见[真机格式记录](HARDWARE-FORMAT-NOTES.md)。平台操作与周期采集、26 候选/1 关联和测试统计见上述六机验收报告；族级目录仍保留原验证枚举，不把上述具体证据扩展成全族支持。
