# 设备监测数据完整性与状态修复

研究/实机只读核对日期：2026-09-09。目标为 Dell S6100-ON（OS9 9.14(2.23)）、Cisco ASR1002-X（IOS XE 17.9.8）、Huawei iMana RH2288 V2 系列与 Inspur SA5212M5。原始响应保留在本地私有验收目录，不把账号、密钥或原始配置写入此文。

## 已定位的错误

1. `device_current` 保存的健康和 ONLINE 标志没有随时间失效；详情、筛选、总览统计直接读旧值，导致两天前成功的 iMana 仍显示正常。新的读时 SQL 对每个来源执行 180 秒新鲜度检查，再取当前来源的最严重健康状态。历史来源仍可查，其健康不能作为当前健康。没有新鲜来源时健康 UNKNOWN；存在启用连接的最近失败证据时可用性 OFFLINE，否则 UNKNOWN。从未有观测的 MISSING 设备计入未知，但不计入过期 STALE。新一轮重试 RUNNING 期间保留之前失败证据，成功后恢复 ONLINE。
2. SNMP 身份查询把一个可选对象的 `genErr` 当整批失败，并提前放弃后续列；同时只遍历 64 个实体。现在按真实 chassis class 选择身份，实体遍历上限 256，只给机箱查询序列号/版本/型号列。显式 agent error 采用有界二分隔离失败对象，认证失败与超时不会触发放大重试。
3. Dell 设备确实返回序列号 `NA`，它不是可恢复的序列号。实机提供 Service Tag，因此显示服务标签并保留 `serialKind=serviceTag`。固件版本和系统软件版本分别保存，缺失 FirmwareRev 时界面可显示标注清楚的系统软件版本，不能把硬件修订号当系统版本。
4. SNMP 驱动原来统一返回健康 UNKNOWN；Dell 只采 CPU/内存。Cisco ASR1002-X 的传感器类型枚举与旧通用 Cisco MIB 不同，错误使用 celsius(6) 会遗漏真实温度。

## 厂商格式与实现

### Dell S6100 OS9

[Dell 官方 OS9 MIB 下载入口](https://www.dell.com/support/kbdoc/en-us/000181922/dell-networking-mibs)提供的 `DELL-NETWORKING-CHASSIS-MIB` 定义以下对象。此次实机读取与定义相符，所有实例索引来自遍历，无硬编码某个端口或电源槽位。

| 数据 | OID / 来源 | 处理 |
|---|---|---|
| 系统软件版本、服务标签 | `1.3.6.1.4.1.6027.3.26.1.3.4.1.10` / `.23` | 只在唯一 stack unit 时作为设备候选；服务标签与序列号区分 |
| 单元状态、温度 | 同表 `.8` / `.13` | `.8` 的 ok/unitDown/配置不匹配分别解释；`.13` 与同一实机 CLI 的 `C` 数值对应，限定 S6100 profile |
| PSU 状态、功耗 | `1.3.6.1.4.1.6027.3.26.1.4.6.1.4` / `.10` | 三段索引 `(deviceType,deviceIndex,psuIndex)`；功耗单位 W；同一设备且完整返回的 PSU 功耗求和 |
| 风扇状态 | `1.3.6.1.4.1.6027.3.26.1.4.7.1.4` | up/down/absent 分开解释，缺席不虚构转速 |
| 扩展温度、风扇 RPM | SSH `show environment` | thermal/module 温度、单元温度、风扇和 PSU 风扇转速均保留独立传感器 |

实机只读快照中，PSU 功耗为 98 W、120 W；相邻 CLI 快照为 98 W、119 W，差异来自不同采样时刻。设备合计表示“已观测 PSU 功耗合计”，不是额定容量或 AvgPower。Dell CLI 文档明确温度、RPM 及电源 Power/AvgPower 是不同字段。[Dell S6100 show environment](https://www.dell.com/support/manuals/en-us/dell-emc-os-9/s6100-on-9.14.2.6-cli-pub/show-environment?guid=guid-06672f79-4794-4141-9f32-b5b61ba9290e&lang=en-us)

### Cisco ASR1002-X

**平台专用枚举覆盖原通用 Cisco 映射。** [Cisco ASR1000 MIB Specifications，Table 3-47](https://www.cisco.com/c/en/us/td/docs/routers/asr1000/mib/guide/asr1kmib/asr1mib3.html)规定此平台温度 celsius(8)、直流电压 voltsDC(4)、电流 amperes(5)、光功率 dBm(14)。真实 ASR1002-X 返回与此一致；按其 `sysObjectID=1.3.6.1.4.1.9.1.1525` 选择独立 profile。其他未经实测的 Cisco 产品保留通用 MIB 布局，不能仅凭厂商混用枚举。

实机 `entSensorType` 有 108 行，含 25 个温度、55 个直流电压、2 个交流电压、10 个电流和16个光功率；原来 64 行上限会截断。读取上限改为 256 个传感器类型、512 个阈值，单轮仍限制总共 2500 个返回变量和25秒。量值按 `raw × 10^((scale−9)×3−precision)` 归一化。例如 1498、milli scale、precision 0 得 1.498 V；光功率 −189、precision 1 得 −18.9 dBm。

阈值表 `1.3.6.1.4.1.9.9.91.1.2.1.1.5`（evaluation）和 `.2`（severity）具有两段索引 `(entityIndex,thresholdIndex)`，实机各 284 行。告警按设备返回的 evaluation 与 severity 解释，minor/major 为 WARNING，critical 为 CRITICAL。传感器 `operStatus=ok` 仅证明可读取数值，不能据此判断硬件健康。阈值不完整时禁止从部分 clear 记录得出整体正常。每个传感器保留其实体名、源 OID 与健康依据。[Cisco ENTITY-SENSOR-MIB 正式定义](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-ENTITY-SENSOR-MIB.my)

SSH `show environment all` 的 Normal/Warning/Critical 与数值单位独立解析；mV 转 V，Celsius 为温度。Fan Speed 65% 是风扇控制状态文本，不能将其错误解释为故障或系统 CPU。CLI 未提供直接功耗时不将 `I × V` 冒充实测整机功耗。

### Huawei iMana 与 Inspur

iMana 已验证的只读命令为 `ipmcget -d version`、`ipmcget -d fruinfo`、`ipmcget -d health`、`ipmcget -t sensor -d list`。本次基线分别返回99/99/101个传感器，单位包含温度、RPM、电压、电流、瓦特和离散状态。`Power1`/`Power2` 独立返回的瓦特值现在同时发布“已观测编号 PSU 功耗合计”；有任一电源读数缺失时不生成合计。传感器 cr/uc/lc/nr 等状态参与实际健康，缺数值不能伪造正常数值。系统正常消息本身也不能覆盖已观测的 critical 传感器。

华为的正式检查流程分别读取 health 和 healthevents，说明健康与具体告警是分开的；当前仅将已验证命令与传感器状态用于本型号，未对旧 iMana 无证据开启新的写命令。[Huawei 服务器检查流程](https://info.support.huawei.com/enterprise/en/doc/EDOC1100118955/37aff47a/checking-the-server)

Inspur SA5212M5 基线已返回41个传感器、唯一 chassis `PowerConsumedWatts` 及温度最大值。其旧 Redfish 实现的字符串 Members、数字字符串 RPM、禁用组件已被兼容并明确标记；禁用或缺读数的组件不会填零。正式资源定义中 `PowerConsumedWatts` 是当前功耗，`PowerCapacityWatts` 是容量，二者不能混用。[Inspur Redfish 用户手册 V1.2](https://www.inspur.com/eportal/fileDir/active_download/platformBookZh/6747/%E6%B5%AA%E6%BD%AE%E8%8B%B1%E4%BF%A1%E6%9C%8D%E5%8A%A1%E5%99%A8%20Redfish%E7%94%A8%E6%88%B7%E6%89%8B%E5%86%8C%20V1.2.pdf)

BMC 的 CPU 个数、内存容量不等于宿主机 CPU/内存使用率；后者需要 OS agent 或实际提供对应使用率的管理接口。保留缺失比用硬件容量制造使用率更符合协议数据。多传感器温度趋势为“已观测温度最大值”，不是平均温度或指定入口温度。

## 事件、配置与拓扑契约

- 采集事件消息包含协议、健康、异常传感器名称/数值、接口 UP 总数与管理UP但运行DOWN的端口，以及部分数据原因。告警标题携带具体来源证据，恢复时关闭相同来源告警，再次发生时重新开启并更新原因。
- 采集失败单独写入 `DeviceCollectionFailed` 事件与按连接去重的告警；重试不会每分钟复制同一告警，恢复写 `DeviceCollectionRecovered`。失败不会进入成功来源投影，也不会更新时间为在线。
- `show running-config` 作为独立配置会话的只读白名单，未混入周期遥测的原始 facts。iMana/Redfish 不伪造网络 running config；配置同步应说明观测快照的类型。保存前脱敏由配置同步模块负责。
- SSH 邻居已提供 `neighborObservations`，包含 LLDP/CDP/ARP 来源、local/remote port、chassis subtype、TTL/age。相同 MAC 与管理IP可跨网段定位，ARP 仅是可达邻居证据，不能等同 LLDP 物理链路。

## 验证

已执行设备/状态相关79项测试（SNMP UDP/USM、SSH真实加密交互fixture、MySQL 8.4.11隔离容器、状态投影与工作区统计），通过。之后补充 iMana异常传感器覆盖系统正常、重试期间持续离线两条断言，随最终全量验证运行。测试数据为合成值和实测格式抽取，不访问或修改真实设备配置。最终生产采集结果及 UI 验收以本次总验收报告为准。


### 2026-09-09 上线复核补充

首次上线复核发现 ASR 同时提供厂商和标准 ENTITY-SENSOR 表，同一 `entPhysicalIndex` 重复进入传感器清单，触发128项上限。现在保留厂商数值和阈值映射，标准表只补充缺少可用厂商读数的实体；同一传感器的多个阈值合并严重度，不作为重复数值传感器。真实 -5V 电源轨的 -5.223V/-5.242V 为有效有符号电压，不能因负数而丢弃。

Cisco 的36条低阈值触发来自18个光模块偏置/收发光功率传感器。实机 ENTITY 映射证明这些传感器与各自物理端口处于同一光模块容器；各容器只有一个物理端口，默认 `entAliasMappingIdentifier` 精确关联 ifIndex 1–6，六个接口 `ifAdminStatus=down(2)`。对应触发关系均为 `lessOrEqual(2)`。只有这些已证实管理关闭端口的低光功率/偏置条件不计入整机健康，原始 sensor.health 与阈值计数仍保留。UP 接口、高阈值、温度、电压、映射缺失或多个端口的歧义均不会被排除。`healthExcludedSensorIds` 和 `inactiveInterfaceThresholdEvidence` 提供界面与事件解释。实体关联依据：[IETF ENTITY-MIB RFC 4133](https://www.rfc-editor.org/rfc/rfc4133.html)。低阈值关系与 evaluation true/false 依据：[Cisco 官方 CISCO-ENTITY-SENSOR-MIB](https://raw.githubusercontent.com/cisco/cisco-mibs/main/v2/CISCO-ENTITY-SENSOR-MIB.my)。

04:06 UTC 第二轮只读API验收中675项通过，剩余2项仅为上述Cisco传感器截断/温度缺失；离线iMana状态、五台在线设备配置同步、Dell最新VictoriaMetrics温度/功耗和两条LLDP边均已通过。第二轮补丁的最终验收以更新后的上线报告为准。


### r2 最终上线验收

2026-09-09 04:25:54–04:25:55 UTC，r2 676 项只读 API/数据断言全部通过。Cisco 当前110个唯一传感器：25温度、57电压、10电流、16光功率及CPU/内存各1项；无 SENSOR_LIMIT 或 SENSOR_VALUE_INVALID，实际 -5.223V/-5.243V 电压轨保留。18个光口原始 CRITICAL 阈值对应已证实关闭的 ifIndex 1–6，在保留传感器证据的同时不计整机故障；SNMP来源与整机均为 HEALTHY。其他可选查询的 SNMP_AGENT_ERROR 与无MAC接口的弱身份标记继续透明保留。

离线 iMana 最后成功时间仍为09-07 06:41:49 UTC，当前 UNKNOWN/OFFLINE；其配置状态为 ERROR。另五台在线设备配置为 SUCCESS/UNCHANGED，Dell 当前 VictoriaMetrics 温度26°C、功耗217W均为新鲜真实样本。两条 Dell↔Cisco LLDP 邻接均有对应最新观测证据；支持目录仅对已列明的四类机型、固件与实际协议显示真机验证。

配置快照是持续保存的证据，部署不会强制同步；验收按组织配置的同步周期检查有效性，当前观测与趋势则必须晚于本轮部署开始时间。完整断言结果保存在本地私有 `device-integrity-r2-final.json`（0600），不包含会话令牌、连接密钥或配置正文。
