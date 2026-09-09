# SNMP 多厂商识别与采集研究

> 2026-09-09 更新：Dell S6100 环境传感器、Cisco ASR1002-X 专用枚举及当前健康新鲜度的最新依据与验收见 [设备监测数据完整性修复](MONITORING-INTEGRITY-20260909.md)。本文保留早期研究记录。

研究日期：2026-09-06。范围：Dell Networking OS9、Cisco IOS XE、H3C Comware、Huawei 网络设备，以及通用 SNMP fallback。第 1–8 节记录官方资料与研究建议；第 9 节记录已落地驱动及测试边界。没有连接真实设备或验证完整型号兼容性。协议数据契约见 [DEVICE-INTEGRATION-API.md](DEVICE-INTEGRATION-API.md)。

## 结论与范围

可以实现“用户提供目标及 SNMP 凭据后，自动读取身份、发现实际可读能力、匹配配置档并开始有界轮询”。不能自动推导 community、USM 密码、认证算法或解密密钥；SNMPv3 的 engine discovery 也不是算法协商。自动识别的是设备候选与可读对象，凭据来自用户授权的凭据库。协议模型依据 [RFC 3414 USM](https://www.rfc-editor.org/rfc/rfc3414.html) 和 [RFC 3416 PDU/操作](https://www.rfc-editor.org/rfc/rfc3416.html)。

建议第一层总是运行通用 system / IF-MIB 发现，第二层读取 ENTITY-MIB，第三层按企业根和正式产品映射选厂商 profile，第四层逐对象探测。任何一步不足时保留已经证实的通用能力。识别厂商、识别操作系统、发现对象可读、fixture 通过、实机型号验证是五种不同证据，不合并成“支持全部型号”。

## 1. 凭据与身份发现

### 1.1 最小读取及证据

| 对象 | 标量 OID | 用途 |
|---|---|---|
| sysDescr.0 | 1.3.6.1.2.1.1.1.0 | 原始系统描述，辅助提取 OS/版本候选 |
| sysObjectID.0 | 1.3.6.1.2.1.1.2.0 | 厂商为管理子系统分配的产品对象标识 |
| sysUpTime.0 | 1.3.6.1.2.1.1.3.0 | 管理子系统重初始化以来的 1/100 秒计时 |
| sysName.0 | 1.3.6.1.2.1.1.5.0 | 可修改的显示名称，不作为永久设备 ID |
| sysLocation.0 | 1.3.6.1.2.1.1.6.0 | 管理文本；不能据此自动赋予站点权限 |

上述 system 对象来自 [RFC 3418](https://www.rfc-editor.org/rfc/rfc3418.html)。sysObjectID 本身不证明硬件唯一身份，sysUpTime 也不一定等于整机开机时长。建议记录完整原值、读取时间、凭据引用版本、SNMP 版本、安全级别、contextName、engineID，以及每个对象读取结果；不要把凭据写入发现报告。

企业根已直接核对 [IANA Private Enterprise Numbers](https://www.iana.org/assignments/enterprise-numbers/enterprise-numbers)：

| 候选厂商 | 企业根 | 识别约束 |
|---|---|---|
| Cisco | 1.3.6.1.4.1.9 | 可识别 Cisco 候选；不能单凭根区分 IOS XE / IOS / NX-OS / IOS XR |
| Dell | 1.3.6.1.4.1.674 | Dell 广义企业空间，也覆盖其他产品；不能直接判定为 OS9 |
| Force10 / Dell Networking 候选 | 1.3.6.1.4.1.6027 | IANA 登记为 Force10 Networks；结合 Dell 产品 MIB、OS 证据与对象探测，不泛化至全部 Dell OS |
| H3C | 1.3.6.1.4.1.25506 | H3C 候选；Comware 版本与实体类型仍需另证 |
| Huawei | 1.3.6.1.4.1.2011 | Huawei 候选；不能把整个企业空间都当 VRP 交换机 |

匹配 OID 必须按整数分量和前缀边界，不能将字符串 `...9` 误匹配到 `...90`。产品级规则采用最长已审阅前缀；没有正式产品 OID 映射时只报告厂商候选。sysDescr 中的 “Cisco IOS XE”“Comware”“VRP”“Dell Networking OS/Force10”是启发式证据，可能不出现、格式变化或与其他证据冲突；不能据关键字直接声明操作系统或型号已验证。

### 1.2 ENTITY-MIB

通用实体表列根为 `1.3.6.1.2.1.47.1.1.1.1`，后续为列号和 entPhysicalIndex：

| 列号 | 对象 | 使用方式 |
|---|---|---|
| 2 / 3 | entPhysicalDescr / entPhysicalVendorType | 描述与厂商类型 OID |
| 4 / 5 / 6 | entPhysicalContainedIn / Class / ParentRelPos | 机箱、板卡、端口、传感器的实体树 |
| 7 | entPhysicalName | 可读名称 |
| 8 / 9 / 10 | HardwareRev / FirmwareRev / SoftwareRev | 版本分别保存 |
| 11 / 12 / 13 | SerialNum / MfgName / ModelName | 厂商、型号及非空序列号 |
| 16 | entPhysicalIsFRU | 可更换部件属性 |

根据 [RFC 6933 ENTITY-MIB](https://www.rfc-editor.org/rfc/rfc6933.html) 读取实际行；不能假设第一行或 index=1 是主机箱。ENTITY 的接口别名映射可用于关联 ifIndex，但不能把 entPhysicalIndex 与 ifIndex 直接相等。建议设备永久 UUID 与 observedIdentity 分开；缺序列号、堆叠、多管理地址、换板、engineID 变化需要身份冲突记录，不自动合并或覆盖资产。

### 1.3 自动凭据选择建议

只尝试管理员分配给该组织/站点/目标范围的凭据引用，按优先级、最大次数和退避执行；成功后绑定引用及版本，后续失败不要扫描整个凭据库。v2c 的成功响应仅能证明该 community 在该路径上可读，不能获得 v3 的密码认证保证。timeout 同时可能是 ACL、路由、丢包、目标离线或错凭据；UI 应显示“未收到响应”，不能确定为“密码错误”。

v3 保存 `securityName, securityLevel, authProtocol, privacyProtocol, authSecretRef, privacySecretRef, contextName`；engineID 发现及本地化交给库处理。engineID 未认证发现只作为待验证信息，在成功认证后确认；变更应触发重新发现/计数器断代。只做 GET/GETNEXT/GETBULK，不进行 SET 或远程配置。

## 2. 通用接口采集与计数器契约

下表来自 [RFC 2863 IF-MIB](https://www.rfc-editor.org/rfc/rfc2863.html)。常称 IF-X-MIB 的扩展实际是 IF-MIB 内的 ifXTable，根 `1.3.6.1.2.1.31.1.1.1`。所有表列 OID 必须追加实际发现的 ifIndex；不要统一追加 .0。

| 对象 | 列 OID | 类型/含义 |
|---|---|---|
| ifIndex / ifDescr / ifType | 1.3.6.1.2.1.2.2.1.1 / .2 / .3 | 当前 agent 的接口索引、描述、类型 |
| ifSpeed | 1.3.6.1.2.1.2.2.1.5 | Gauge32，bit/s；上限 4294967295 |
| ifPhysAddress | 1.3.6.1.2.1.2.2.1.6 | 原始物理地址，可能为空或非以太网格式 |
| ifAdminStatus / ifOperStatus | 1.3.6.1.2.1.2.2.1.7 / .8 | 管理与运行状态分别保存 |
| ifLastChange | 1.3.6.1.2.1.2.2.1.9 | 运行状态最近变更时的 sysUpTime |
| ifInOctets / ifOutOctets | 1.3.6.1.2.1.2.2.1.10 / .16 | Counter32，octets |
| ifInDiscards / ifInErrors | 1.3.6.1.2.1.2.2.1.13 / .14 | Counter32，包计数 |
| ifOutDiscards / ifOutErrors | 1.3.6.1.2.1.2.2.1.19 / .20 | Counter32，包计数 |
| ifName | 1.3.6.1.2.1.31.1.1.1.1 | 接口名，通常优先于 ifDescr 作显示名 |
| ifHCInOctets / ifHCOutOctets | 1.3.6.1.2.1.31.1.1.1.6 / .10 | Counter64，octets，优先采集 |
| ifHighSpeed | 1.3.6.1.2.1.31.1.1.1.15 | Gauge32，单位 1,000,000 bit/s |
| ifAlias | 1.3.6.1.2.1.31.1.1.1.18 | 管理员别名，不保证唯一或不可修改 |
| ifCounterDiscontinuityTime | 1.3.6.1.2.1.31.1.1.1.19 | 最近计数器不连续时的 sysUpTime |

**实现建议**：

- 每个接口保留 UUID、当前 ifIndex、名称/MAC/物理实体映射、identityRevision 和 agentEpoch。ifIndex 可能随重启/重配置变化；ifAlias 也不是身份主键。索引复用、实体更换或映射不明时开始新时间段，不将新端口计数器接到旧序列。
- 原始 Counter64 以无符号十进制字符串或无符号大整数保存，不经浮点数，也不把 Java signed long 负值当无效。先精确做差，再转速率；SNMP4J 类型参考 [Counter64 API](https://agentpp.com/doc/snmp4j/org.snmp4j/org/snmp4j/smi/Counter64.html)。
- 一个区间内 RX/TX 分别计算 `8 × deltaOctets / elapsedSeconds`。elapsed 使用采集端单调时钟；同时保存 UTC 的请求开始/结束、RTT、sysUpTime、计数宽度与 discontinuity 标记。原始计数是轮询时刻的读数，不是设备发送的 UTC 流量事件时间。
- 首次样本、失败间隔、身份/engine/uptime 断代、discontinuity 变化、非正时间差及异常回退均不生成零速率。TimeTicks 自身约 497 天回绕，应联合单调时间、engineBoots 与身份判断，不能把所有回绕都当重启。
- Counter32 fallback 仅在明确缺少 HC 对象且容量/采样间隔可保证至多一次回绕时做模差；否则输出原始值及 `RATE_UNAVAILABLE_COUNTER_WIDTH`。推导：1 Gbit/s 满速下 32 位 octets 约 34.36 秒回绕，60 秒轮询不能可靠还原；10 Gbit/s 约 3.44 秒。默认优先 Counter64。
- speed 是容量估计，优先正值 ifHighSpeed×1000000，再退回未饱和 ifSpeed；零/未知不算利用率。保留来源与单位，不能把 LAG、成员物理端口、VLAN 子接口全部相加当整机流量；识别分层后按明确范围展示。
- 同批读取不等于原子快照：记录轮询覆盖时间；计数器之前/之后检查 uptime/discontinuity 的变化，变化时丢弃受影响区间。采集时间过长或部分列失败标为 PARTIAL，保留缺失。
- UI/告警应分别展示 admin down、oper down、dormant、lowerLayerDown、unknown；SNMP 可达不等于所有接口正常。

### 通用 CPU/内存/温度 fallback

如果实际实现 HOST-RESOURCES-MIB，可读 `hrProcessorLoad 1.3.6.1.2.1.25.3.3.1.2`（每处理器最近一分钟负载百分比），以及 `hrStorageTable 1.3.6.1.2.1.25.2.3`；按 storage type 选 RAM，size/used 必须乘同一行 allocationUnits 才是字节。不可把 swap、磁盘、缓存或多个重叠池相加为物理内存。该 MIB 不保证网络设备实现。[RFC 2790](https://www.rfc-editor.org/rfc/rfc2790.html)

标准传感器可探测 `entPhySensorTable 1.3.6.1.2.1.99.1.1`。必须一并读取 type、scale、precision、value、operStatus、单位显示、timestamp/updateRate，按原始类型解释，只有类型确认为温度才生成 temperature_celsius。推荐归一化公式为 `raw × 10^(scaleExponent - precision)`；scale 枚举需要映射指数，不能直接把枚举数当指数。无效/不可用状态不产生正常值。[RFC 3433 ENTITY-SENSOR-MIB](https://www.rfc-editor.org/rfc/rfc3433.html)

## 3. 厂商 profile 与有证据的私有对象

以下均为列根，不是某台设备的实例 OID。不得硬编码常见示例索引；先读取/关联实体，再逐行探测。列存在与个别型号实现该列是不同结论。

### 3.1 Cisco IOS XE

[Cisco 官方 MIB 源库](https://github.com/cisco/cisco-mibs) 提供对象定义；具体平台/版本还要经过 [Cisco MIB Locator 说明](https://www.cisco.com/c/en/us/support/docs/ip/simple-network-management-protocol-snmp/15215-collect-cpu-util-snmp.html) 及设备实测。CPU 表按 cpmCPUTotalIndex，物理索引列负责关联 ENTITY，不能固定 index=1 或把不同 CPU 平均成整机 CPU。

令 P=`1.3.6.1.4.1.9.9.109.1.1.1.1`：

| 对象 | OID | 语义 |
|---|---|---|
| cpmCPUTotalPhysicalIndex | P.2 | 对应 entPhysicalIndex |
| cpmCPUTotal5secRev | P.6 | 最近 5 秒百分比；当前 MIB 已标 deprecated |
| cpmCPUTotal1minRev / 5minRev | P.7 / P.8 | 最近 1 分钟/5 分钟百分比 |
| cpmCPUMonInterval / TotalMonIntervalValue | P.9 / P.10 | 实际统计秒数及该窗 CPU 百分比；可读时优先于旧 5 秒列 |
| cpmCPUMemoryUsed / Free | P.12 / P.13 | CPU 范围内内存，MIB 单位 kilo-bytes |
| cpmCPUMemoryHCUsed / HCFree | P.17 / P.19 | 相应 64 位容量版本，仍为 kilo-bytes |

来自 [CISCO-PROCESS-MIB 正式定义](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-PROCESS-MIB.my)。内存利用率可按同一实体同口径 used/(used+free)；绝对字节转换因 kilo-bytes 的平台口径需与 CLI/具体版本确认，先保存原始单位，不能无证据按 bytes 展示。RP、ESP、IOSd 内存不是同一指标，支持差异参考 [ASR1000 MIB 规格矩阵](https://www.cisco.com/c/en/us/td/docs/routers/asr1000/mib/guide/asr1kmib.pdf)。

旧 `ciscoMemoryPoolUsed/Free` 为 `1.3.6.1.4.1.9.9.48.1.1.1.5/.6`，单位 bytes，按实际 pool index；不与上述 CPU system memory 混成一个总量。[Cisco 内存池说明](https://www.cisco.com/c/en/us/support/docs/ip/simple-network-management-protocol-snmp/15216-contiguous-memory.html)

温度候选 `entSensorValue 1.3.6.1.4.1.9.9.91.1.1.1.1.4`；相邻 .1/.2/.3/.5 为 type/scale/precision/status，同索引联读。Cisco SensorDataType 的 celsius=6，与标准 ENTITY-SENSOR-MIB 的温度枚举不同，解析器不能共用数值枚举。依据 [官方 OID 映射](https://raw.githubusercontent.com/cisco/cisco-mibs/main/oid/CISCO-ENTITY-SENSOR-MIB.oid) 和 [CISCO-ENTITY-SENSOR-MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-ENTITY-SENSOR-MIB.my)。

### 3.2 H3C Comware

HH3C-ENTITY-EXT-MIB 扩展实体表，企业根 25506；官方参考明确各实体支持项不同，noSuchName/noSuchInstance 可能表示该实体没有此项，不能视作全设备采集失败。[Comware 7 官方实体扩展参考](https://www.h3c.com/en/Support/Resource_Center/EN/Home/Security/00-Public/Reference_Guides/MIB_Companion/H3C_Products_MIB_Comware_7/02/202211/1719223_294551_0.htm)

令 H=`1.3.6.1.4.1.25506.2.6.1.1.1.1`：

| 对象 | OID | 已核对语义 |
|---|---|---|
| hh3cEntityExtPhysicalIndex | H.1 | 与物理实体关联 |
| hh3cEntityExtCpuUsage | H.6 | 0..100%，官方此版本统计窗 5 秒 |
| hh3cEntityExtMemUsage | H.8 | 实体内存百分比 |
| hh3cEntityExtMemSize | H.10 | Unsigned32，bytes，不能套到更大/新版内存列 |
| hh3cEntityExtTemperature | H.12 | 温度原始整数；65535 表示不支持 |

CPU/内存通常是 module 实体，不将机箱或风扇空列填零。温度数值 OID 和 sentinel 已有官方证据；本次取得的该参考表未明确写单位/缩放，启用 temperature_celsius 前仍要取得对应固件 MIB 的 UNITS/DESCRIPTION 或实机 CLI 对照，不能仅凭变量名称假定比例。Comware 5 与 7、HPE OEM 不因为 sysDescr 相似就共享全部 profile。

### 3.3 Huawei 网络设备

官方 S9300/S9300X V200R024C00 示例按 entPhysicalIndex 读取各组件，堆叠多个 MPU 分别对应各自实体，不能固定示例索引或将任一槽位当整机。[Huawei 官方 MIB Query Examples](https://info.support.huawei.com/enterprise/en/doc/EDOC1100410594/e704d4b7/mib-example)

令 W=`1.3.6.1.4.1.2011.5.25.31.1.1.1.1`：

| 对象 | OID | 已核对语义 |
|---|---|---|
| hwEntityCpuUsage | W.5 | 实体 CPU 百分比；本资料未明确统计窗 |
| hwEntityMemUsage | W.7 | 实体内存百分比 |
| hwEntityTemperature | W.11 | 实体温度，官方示例单位 °C |

以上 profile 以型号/版本和可读对象约束，不能扩展为整个 Huawei 企业根全型号支持。另一个官方 CPU 表 `1.3.6.1.4.1.2011.6.3.4` 的列为 hwCpuDevDuty / hwAvgDuty1min / hwAvgDuty5min，索引是 frame、slot、CPU 的组合；应作为独立表 profile，不能套用 entPhysicalIndex。[Huawei hwCpuDevTable](https://info.support.huawei.com/hedex/api/pages/EDOC1100363264/AEN0403J/06/resources/mib/yunshan/dc_8090_HUAWEI-DEVICE-MIB_mibtable_1.3.6.1.4.1.2011.6.3.4.html)

### 3.4 Dell Networking OS9

先保留通用 IF-MIB/ENTITY 采集。Dell 官方 OS9 产品资料列出标准 IF-MIB、ENTITY-MIB 以及 DELL-NETWORKING-CHASSIS/PRODUCTS/SYSTEM-COMPONENT 等模块，模块清单不能证明某型号所有对象可读。[S4048T-ON OS9 官方规范](https://i.dell.com/sites/doccontent/shared-content/data-sheets/en/Documents/dell-emc-networking-s4048T-on-spec-sheet.pdf)

官方 Force10 S-Series chassis OID 清单可核对以下候选；这些是旧 S 系列表，不是全 OS9 的通用私有表：

| 对象 | 列 OID |
|---|---|
| chStackUnitModelID / CodeVersion | 1.3.6.1.4.1.6027.3.10.1.2.2.1.7 / .10 |
| chStackUnitCpuUtil5Sec / 1Min / 5Min | 1.3.6.1.4.1.6027.3.10.1.2.9.1.2 / .3 / .4 |
| chStackUnitMemUsageUtil | 1.3.6.1.4.1.6027.3.10.1.2.9.1.5 |
| chStackUnitTemp | 1.3.6.1.4.1.6027.3.10.1.2.2.1.14 |

来源：[Dell 官方 S-Series OID 清单](https://www.dell.com/support/kbdoc/zh-cn/000182626/force10-s-%E7%B3%BB%E5%88%97-%E6%9C%BA%E7%AE%B1-mib-oids)。本次英文正文访问跳转登录；以上对象由官方已索引清单交叉核对，未取得对应固件 MIB 的完整 INDEX、单位和 sentinel 定义。实施时先取得用户所用 OS9 release 的 MIB 包并固定摘要，再启用归一化私有指标；不能仅依据表中的 Gauge32 或名字推定百分比/摄氏度。未取得定义时仍可运行通用接口采集，私有指标标 `MIB_DEFINITION_PENDING`。

## 4. SNMPv2c / v3 算法与兼容性

v2c 使用 community，没有认证加密保护；v3 要按用户指定的 noAuthNoPriv/authNoPriv/authPriv、USM 用户、认证/隐私算法及 context 运行。现代配置优先双方支持的 SHA-2 + AES128；旧设备兼容需要明确记录，不能自动降级或更改设备配置。AES128 CFB 定义于 [RFC 3826](https://www.rfc-editor.org/rfc/rfc3826.html)，SHA-2 USM 定义于 [RFC 7860](https://www.rfc-editor.org/rfc/rfc7860.html)。

| 范围 | 官方资料确认的边界 |
|---|---|
| Dell OS9 | 官方 OS9 命令列出 auth md5/sha，privacy des56/aes128；这里 sha 是 SHA-1，不能把支持 SHA-2 当 OS9 基线 |
| Cisco IOS XE | 官方配置指南从 17.10.1a 引入 SHA-2；FN72509 说明 17.11.1a 起默认不允许 MD5、DES、3DES，仍需核对具体平台/版本 |
| H3C Comware | 所查 2024 配置参考列出 md5/sha/sha224/256/384/512 与 des56/3des/aes128/192/256；FIPS 有不同集合，不能推广到所有旧 Comware |
| Huawei | 所查命令参考列出 sha/sha2-256、des56/aes128/aes256；示例与不同产品文档可能不同，必须按实际固件配置 |

官方来源：[Dell OS9 命令映射](https://www.dell.com/support/manuals/en-us/smartfabric-os10-emp-partner/techsheet-os10-5-x-os9-pub/simple-network-management-protocol?guid=guid-637b6e2b-2114-4a19-bf0b-eda8f0ff3b1a&lang=en-us)、[Cisco 弱算法变更 FN72509](https://www.cisco.com/c/en/us/support/docs/field-notices/725/fn72509.html)、[H3C SNMP 配置](https://www.h3c.com/cn/d_202401/2035102_30005_0.htm)、[Huawei USM 用户输出说明](https://info.support.huawei.com/hedex/api/pages/EDOC1100331435/AEM10132/04/resources/dc/display_snmp-agent_usm-user.html)。

实现映射建议：`SHA1 -> AuthSHA`、`SHA256 -> AuthHMAC192SHA256`，SHA384/512 分别采用 RFC 对应截断 HMAC 类；SHA256 不是 256 位 MAC。`AES128 -> PrivAES128`。AES192/256 还存在不同密钥扩展方案，不能只记录一个含混的 AES256 名称。SNMP4J 官方专门提供非标准 3DES key-extension 类，只有明确设备/profile 需要且测试通过才启用；不能仅因厂商为 Cisco 就静默使用它。[SNMP4J PrivAES](https://www.agentpp.com/doc/snmp4j/org.snmp4j/org/snmp4j/security/PrivAES.html)、[非标准 AES256 类说明](https://agentpp.com/doc/snmp4j/org.snmp4j/org/snmp4j/security/nonstandard/PrivAES256With3DESKeyExtension.html)

## 5. SNMP4J 集成建议与性能预算

[SNMP4J 官方站](https://www.snmp4j.org/) 提供 SNMP manager、异步请求、Trap/Inform 和 v3 安全实现。本次阅读的是官方 3.13.1 系列 API 页面；它是资料版本，不表示仓库已经引入、兼容 Java 25 或完成依赖安全审查。实现时固定依赖版本与校验，并以编译及协议测试确认。

官方 `SecurityProtocolSet.defaultSecurity` 不包含被该版本视为不安全的 MD5/SHA-1，不能只调用默认注册后就宣称兼容旧 OS9 的 SHA-1。仅对已配置凭据显式注册必要协议；不要全局启用 `any`。依据 [SecurityProtocolSet](https://agentpp.com/doc/snmp4j/org.snmp4j/org/snmp4j/security/SecurityProtocols.SecurityProtocolSet.html)。

建议以下为应用初始预算，数值不是厂商推荐吞吐：

- 一个采集器复用有限 transport/Snmp 会话，异步回调进入有界队列；WebFlux 请求线程不执行同步 WALK。每目标同一轮询不重叠，先设 1 个在途 PDU，全局在途 32，可按实测调整。
- 每请求 timeout 1500 ms、最多 1 次重试；目标一次发现 30 秒预算，表最多 4096 行/32768 varbinds，GETBULK maxRepetitions 初始 10，遇 tooBig 降到 5/1。完整超过预算标 PARTIAL/TRUNCATED 并保留 cursor/下次计划，不产生“完整采集”。
- 仅 WALK profile 指定的列，检查响应 OID 严格递增、仍在列根内，处理 endOfMibView/noSuchObject/noSuchInstance；错误列不拖垮其他列。稀疏表按实例 suffix 联接，禁止按返回行位置 zip。SNMP4J [TableUtils](https://agentpp.com/doc/snmp4j/org.snmp4j/org/snmp4j/util/TableUtils.html) 提供列/行数、边界、排序检查与行数限制；仍需外部总字节/时长预算。
- 异步响应成功/失败/取消都释放请求与 permit，清理超时任务；关闭时关闭 transport。参照 [Snmp API](https://agentpp.com/doc/snmp4j/org.snmp4j/org/snmp4j/Snmp.html)，不要为每个端口新建 socket。
- 库存/实体发现建议 15 分钟或身份变化时重读，计数器 60 秒并加抖动，传感器 60 秒；超时指数退避，短时失败不触发全表重新发现。容量与轮询周期仍要经真实机型和站点链路压测。
- 原始 SNMP 字符串有大小/编码边界，UI 纯文本；OID/表单不能携带任意可执行解析表达式。管理凭据只存 secret reference，日志不包含 community、密码、localized keys 或未经脱敏的完整 PDU。

## 6. Trap / Inform 与轮询的边界

Trap 是非确认通知，可能丢失、乱序、重复；Inform 要确认但仍会重试，也不是业务 exactly-once。它们可以降低异常发现延迟，不能替代周期读数、历史计数器或设备身份核对。格式及通知语义依据 [RFC 3416](https://www.rfc-editor.org/rfc/rfc3416.html)；v3 authoritative engine / timeliness 的两种方向需按 [RFC 3414](https://www.rfc-editor.org/rfc/rfc3414.html) 处理。

建议独立的有界通知入口保存 notificationOID、source address、v3 security/engine/context、sysUpTime、receivedAt 与 typed varbinds；经已登记身份和授权映射后才进入状态机。v2c source IP 和 community 不能当密码学身份，地址复用/NAT 不能自动映射租户。未知来源进入隔离队列，Trap 风暴有速率/队列上限，丢弃数量作为运行指标。

通用 linkUp/linkDown 可触发受限即时 poll 进行确认，不能因收到一条 Trap 直接宣布整机恢复。事件去重依据来源、engine/uptime、OID 与摘要等窗口条件，并保留去重计数；Inform 的传输确认与 Kafka/数据库持久化确认分开。厂商 CPU/温度 Trap 的阈值、恢复对和 varbind 依型号而异，需 profile 明确；轮询本身不配置 Trap 接收地址，也不擅自开启通知。

## 7. 可落地的 profile registry 契约建议

以下是待实现的数据契约，不代表已有 API：

```text
Profile:
  id, version, vendorCandidate, osFamilyCandidate
  sysObjectIdPrefixes[], sysDescrHints[], productMappings[]
  requiredEvidence[], optionalCapabilities[]
  tables[{mib, columnOids[], indexSchema, entityJoin,
          scalarOrColumn, syntax, unit, scaleRule, nullSentinels[]}]
  counterRules{width, direction, discontinuityOid, identityFields[]}
  sourceReferences[{url, mibRevision, artifactSha256}]
  supportClaims[{model, firmwareRange, verification, evidenceArtifact}]
  limits{timeoutMs, retries, maxRows, maxVarbinds, maxWalkMillis}

Discovery:
  targetId, collectorId, credentialRefVersion, startedAt, completedAt
  rawIdentity{sysObjectId, sysDescr, sysName, engineId, contextName}
  vendorCandidate, osFamilyCandidate, matchedProfile, profileVersion
  identityEvidence[], capabilities[{name, status, objectEvidence[], reason}]
  qualityFlags[], complete
```

建议 registry 首批为 `generic-snmp-system-ifmib`、`generic-entity-sensors`、`generic-host-resources`、`cisco-process-entity`、`h3c-comware-entity-ext`、`huawei-entity-extent`、`dell-os9-generic`。Dell 旧 S 系列私有表作为待补 MIB 的子 profile，不能默认启用。

能力结果建议 `OBSERVED / NOT_OBSERVED_OR_NOT_VISIBLE / ACCESS_DENIED / INVALID_VALUE / TIMED_OUT / PARTIAL`。noSuchObject 或空表可能源于 VACM 视图，不应直接永久判定“不支持”；有明确授权错误才标 ACCESS_DENIED。profile 官方定义完整度和设备验证状态独立：文档研究仅 `DOCUMENTED_CANDIDATE`，fixture 仅 `FIXTURE_TESTED`，设备验证限定型号+固件+认证组合+OID 集合，不是品牌级 DEVICE_VERIFIED。

优先实施顺序：凭据引用及只读连接检查 → 通用库存/接口与两次计数器采样 → 独立实体/来源 → 有官方单位的厂商扩展 → Trap/Inform。每一步都能给出部分可用结果，未知厂商仍可保留标准 MIB 能力。

## 8. 进入实现前的验收合同

1. 四厂商身份 fixture、未知厂商、冲突 sysDescr/sysObjectID、缺 ENTITY、堆叠与多 CPU；品牌命中不能直接变 DEVICE_VERIFIED。
2. v2c、SHA-1/AES128、SHA256/AES128，各自正确/错误凭据、VACM 隐藏列、USM 未知用户/时窗/engineID 变化；超时不能标定为密码错误，不做隐式降级。
3. HC Counter64 超过 signed long 和 JavaScript 安全整数、Counter32 回绕/多回绕不可判、uptime 回绕与重启、discontinuity 变化、ifIndex 复用、速度未知、物理/LAG 双计数、部分表与不递增 OID。
4. CPU 实体和统计窗不混用；内存 bytes/kilo-bytes 不混用；标准与 Cisco 传感器 type 枚举分开；H3C 65535 无效；Dell 缺定义时不出伪归一化指标。
5. 授权测试目标的实机读取至少覆盖实际型号+OS版本+v2c/v3组合，保存脱敏 OID 响应、CLI 对照、时钟/计数器测试和证据时间。模拟器通过仅验证解析与传输，不能代替这些设备证据。
6. 多目标并发、超时风暴、轮询错峰、表上限、设备 agent 负载、断连恢复；不为性能提升而全表无限 WALK 或无限重试。

资料访问限制：Dell 英文 KB 需要登录、Huawei 部分深链接正文渲染失败，但官方搜索索引返回了明确对象内容；相关段落已说明证据层级。没有用第三方 OID 库、论坛或转载替代未确认的单位/型号支持，也没有读取用户设备凭据或扫描网络。


## 9. 本轮已实现范围与可复现证据

实现为 `SnmpDriver` / `SnmpSession` / `SnmpProfiles`，固定使用 SNMP4J **3.13.1**。准确版本已核对 [官方 Javadoc](https://agentpp.com/doc/snmp4j/) 与 [Maven 发布 POM](https://repo.maven.apache.org/maven2/org/snmp4j/snmp4j/3.13.1/snmp4j-3.13.1.pom)。第 5 节的容量参数是研究建议；实际代码采用以下较小预算：总时间 **25 秒（含排队）**，最多 8 个阻塞读取线程、有界任务队列，每会话同时 1 个请求，无重试，单请求 timeout 为 100–5000 ms，GETBULK 最多 10 repetitions，总响应变量最多 **2500**，接口最多 **256**，ENTITY 和各传感器表最多 64 行，整轮最多返回 128 个传感器；按指标类别跟踪传感器完整性：温度表触限不阻止完整且唯一的 CPU/内存聚合；相应类别读取异常或触限时不聚合，128 传感器总上限触发时全部不聚合，UDP 消息最多 65507 bytes。一个目标一轮读取使用独立 transport/USM/engine cache，多个目标相同用户名也不会共用密钥；轮询不对主机名重新解析，网络连接只使用 TargetPolicy 已验证的 literal address。

- v2c；v3 `noAuthNoPriv`、`authNoPriv`、`authPriv`；认证 SHA1、SHA256、SHA512 与显式 MD5；加密 AES128、显式 DES。**AES256 明确拒绝**，不会隐式采用标准或非标准密钥扩展，也不会降级为 AES128/noAuth。弱算法只在用户明确配置时使用；这里的协议能力不代表某固件已启用该组合。
- 通用 system、有限 ENTITY 机箱身份、IF-MIB/ifXTable 与 ENTITY-SENSOR-MIB 摄氏温度。身份字段只从 chassis 类实体取得；多 chassis 会标记。接口按真实 ifIndex 关联列，`Port.key` 由名称和 MAC 摘要形成；缺身份、重复身份、Counter32 fallback、无 discontinuity、稀疏/无效读数均保留质量标识。Counter64/Counter32 为无符号十进制字符串，速度为 bit/s 字符串。`facts.sysUptimeTicks` 来自 sysUpTime.0；`snmpEngineId` / `snmpEngineBoots` 独立保存；`Port.discontinuity` 仅为 ifCounterDiscontinuityTime 原始 ticks，不拼接持续递增 uptime。速率由两次采样的上层服务计算，驱动不把累积 octets 当 bps。
- Huawei VRP 候选读取有官方单位的 entity CPU%、memory%、temperature°C；H3C Comware 25506 企业树读取 CPU%/memory%，不把尚未验证单位的私有温度当 Celsius。Cisco PROCESS / ENTITY-SENSOR 读取统计窗 CPU%、内存 used/(used+free)% 与 Celsius，内存来源同时标明真实 used/free OID。设备级 `cpu_percent`、`memory_percent`、`temperature_celsius` 只在该项恰有一个读数时形成，多实体保留独立 sensors。没有硬件健康对象时 health 为 UNKNOWN，SNMP 请求成功不等于整机健康。
- 2026-09-07 补证：Dell S6100 的 sysObjectID `1.3.6.1.4.1.6027.1.3.28` 已启用官方 CHASSIS-MIB 的三段处理器索引 CPU 五秒百分比及内存百分比。其余 Dell OS9/Force10 保持通用 profile；不启用单位未核验的私有温度。详见 [Dell/Cisco 监测核对](DELL-CISCO-MONITORING-RESEARCH.md)。H3C **历史 2011.10 企业子树未实现映射**；本轮 Comware 私有能力仅覆盖文档已核对的 25506 路径，不宣称所有历史系列。
- 全部只读，只有 GET/GETBULK；没有 SET、Trap/Inform 接收、自动猜测凭据、主机扫描或私有 IPMI/OEM 写操作。空表可能由 VACM/权限隐藏，不能自动宣布硬件不支持。库认证/网络异常不会回传密码或完整 PDU。

### BMC 候选与 Inspur 企业根补充

[IANA PEN 37945](https://www.iana.org/assignments/enterprise-numbers/?q=37945) 直接登记 Inspur(BeiJing) Electronic Information Industry Co.,Ltd，因此加入 `inspur-generic-snmp`；它只表明厂商候选，不证明某代 BMC 的私有传感器 OID 可用。

Huawei 官方资料明确存在 [iMana 200 / iBMC 管理器](https://info.support.huawei.com/hedex/api/pages/EDOC1000163559/YEI0812D/18/resources/en-us_topic_0000001193749380.html)，并描述 [iBMC 的 SNMP 接口](https://info.support.huawei.com/enterprise/en/doc/EDOC1100141208/7e6b976f/common-operation-interfaces)。Dell 提供 [iDRAC SNMP System Information Group](https://www.dell.com/support/manuals/en-us/idrac9-lifecycle-controller-v5.x-series/om_10.2_snmp_idrac/system-information-group?guid=guid-72e17d2b-c4c3-4cd7-92ef-01d6573e56a9)，H3C 提供 [HDM API 参考](https://www.h3c.com/en/Support/Resource_Center/EN/Home/Public/00-Public/Technical_Documents/Developer_Documents/API_References/H3C_HDM_Re-13774/202401/2017521_294551_0.htm)。这些来源核实产品名和接口存在，并不规定所有固件的 sysDescr 必须含该名字。

因此，匹配企业根后，sysDescr 的 iBMC / iMana / iDRAC / HDM / BMC 文字只产生显式 **candidate family**，使用通用 SNMP profile。Huawei 的 BMC 候选不会运行 VRP 私有传感器规则。缺标记时保留 OS unverified，不用整个 Huawei/Dell 企业根强行认作交换机或 Force10；未知企业根上的品牌文字不升级为已识别厂商。BMC 的详细传感器优先走独立的 [Redfish 适配与证据](BMC-PROTOCOL-RESEARCH.md)。

### 协议测试与常驻模拟器

`SnmpDriverTest` 使用 SNMP4J 编码的真实 UDP responder `SnmpUdpFixture`，不是 mock Java 返回值。原有协议回归覆盖 v2c 正/错 community，v3 SHA256/AES128 正/错 auth/priv、SHA1/DES、SHA512/AES128、MD5 authNoPriv、noAuthNoPriv、无符号 Counter64、Counter32 回退、稳定接口身份、稀疏表、不递增 OID、接口/ENTITY/2500 变量边界与整轮 128 传感器上限、标准与 Cisco 温度枚举/precision、H3C 单位空缺、Dell 保守范围、BMC/Inspur 候选与 HC 内存来源、取消无 dropped-error。所有厂商描述、索引、型号、序列号、密码和读数均为 **synthetic**；测试不是这些型号的实机支持证明。

运行专项：

```sh
./mvnw -pl services/noeriva-control -am -Dtest=SnmpDriverTest -Dsurefire.failIfNoSpecifiedTests=false test
```

常驻入口是 test source 的 `io.noeriva.control.devices.SnmpUdpFixture.main`。运行前由调用者在环境中提供 `SNMP_MOCK_COMMUNITY`、`SNMP_MOCK_AUTH_PASSWORD`、`SNMP_MOCK_PRIVACY_PASSWORD`，可选 `SNMP_MOCK_USERNAME`（默认 synthetic-user）、`SNMP_MOCK_PORT`（默认 1161）。main 仅打印端口及 synthetic/read-only 状态，不打印密钥。Java classpath 需包含 control 的 `target/test-classes`、`target/classes`、query 的 `target/classes` 及 Maven test 依赖。Docker Desktop 中仅对这一授权模拟器使用 `host.docker.internal:1161`，并由 TargetPolicy 的显式允许网段放行。

main 使用真实时钟每秒推进 sysUpTime 及 64 位计数器：RX 从 1,000,000 octets 每秒增加 125,000，TX 从 500,000 每秒增加 62,500，对应合成 1 Mbps / 0.5 Mbps。单元 fixture 保持确定性静态样本。常驻模拟器不代表真实设备、不能用于生产采集质量或跨地域并发能力证明。


2026-09-07 补充实现使用 `SnmpNbarDriver` 独立读取 NBAR，不加入基础 SNMP 调度开关；历史精度、租约、角色、行数及时间预算见 [应用监测 API](APPLICATION-MONITORING-API.md)。此前第 3.4 节的 Dell 私有 MIB 待取得结论已被本次官方 9.14.2.1 附件证据部分替代，只有明确核实的 S6100 子 profile 启用 CPU/内存。
