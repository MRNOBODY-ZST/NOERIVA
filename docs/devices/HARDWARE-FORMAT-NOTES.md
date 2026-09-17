# 2026-09-07 真机返回格式补充

本记录来自用户指定目标的只读 SSH / HTTPS 读取。口令、完整 CLI 和原始 JSON 不随本文分发；本机任务证据位于被忽略的 `.local/hardware`。这些结论只对应表内型号和版本，不表示厂商全系认证。

| 设备族 | 本次实际型号 / 版本 | 接入观察 |
|---|---|---|
| Dell OS9 | S6100-ON / 9.14(2.23) | SSH 默认协商可用；`show version`、`show inventory`、`show snmp user`、`show arp`、`show lldp neighbors detail` 可读；`show ip arp` 在此设备返回语法错误 |
| Cisco IOS XE | ASR1002-X / 17.09.08 | SSH 默认协商可用；版本、SNMP 用户安全算法、ARP 可读；不能把 HTTPS 端口开放解释为存在 Redfish |
| Huawei iMana | Tecal RH2288 V2 / 7.35 | SSH 交互提示符 `iMana:/->`；远程 exec 命令未被执行，须在 shell 就绪后发固定命令 |
| Huawei iMana | Tecal RH2288H V2 / 7.38 | 同上；实体序列号与另两台不同，主机密钥却存在复用 |
| Huawei iMana | Tecal RH2288H V2-12L / 7.38 | 同上；旧 HTTPS 默认握手失败，SSH 可作为读取路径，不修改全局 TLS 策略 |
| Inspur BMC | SA5212M5 / 4.26.6，Redfish 1.0.2 | TLS 1.2；Redfish 标准身份、温度、电压、电源可读，存在下面三类旧固件格式差异 |

## Inspur Redfish

SA5212M5 的风扇使用明确单位 `RPM`，但 `Reading` 的 JSON 类型是字符串。下面是只保留形态的样例：

```json
{"Name":"FAN_0_Front","Status":{"State":"Enabled","Health":"OK"},"Reading":"4224","ReadingUnits":"RPM"}
```

驱动在已发现 Manufacturer 为 Inspur 时，允许长度和语法受限的十进制字符串，增加 `INSPUR_NUMERIC_STRING` 质量标记。未知品牌不自动把字符串强制转换；`NaN`、无单位内容和非数值保留错误/缺失。风扇 RPM 不换成百分比；私有 `Oem.Inspur.SpeedRatio` 本轮未作为标准数值读取。

未安装的某些温度通道返回以下形态：

```json
{"Name":"RAID0_Temp","Status":{"State":"DISABLE","Health":"NA"},"ReadingCelsius":0}
```

该非标准状态不能被当成启用、正常、0°C。Inspur 兼容分支将 `DISABLE` 排除出有效读数并保留 `READING_UNAVAILABLE`；标准 `Absent/Disabled/UnavailableOffline` 同样不可用于温度和功率汇总。

本机固件的部分 `Members` 为字符串链接；现有同源资源发现已能处理，保留 `LEGACY_STRING_MEMBER_LINK`。这些质量标记说明兼容转换和缺失，不等同于认证失败或整机硬件故障。温度摘要始终标为“已采集温度最大值”，不是进风温度。

已添加真实本地 HTTPS 回归覆盖数字字符串、非法字符串、关闭通道及通用品牌不强制转换。

## iMana SSH

本次实际帮助和读取确认旧固件使用：

```text
ipmcget -d version
ipmcget -d fruinfo
ipmcget -d health
ipmcget -t sensor -d list
```

传感器是竖线分隔表，列含 name、value、unit、status 与阈值。`na` 保持缺失；`degrees C`、RPM、Volts、Watts 等分别解释。`unspecified` 和 `discrete` 不能按数值利用率处理，十六进制状态位没有解码证据时保持未知。整机健康使用明确的 `health` 输出，不从 SSH 登录成功推导健康。

FRU 同时有 Board Serial Number 与 Product Serial Number；产品身份使用后者，不能取第一个出现的序列字段。三台不同产品序列的 BMC 复用 SSH key，说明 key/证书适合核验连接目标的连续性，不能作为自动合并资产的唯一键。

## OS9 / IOS XE 与邻居证据

Dell 管理单元的 Inventory Serial Number 为 `NA`，另有 Svc Tag；如用服务标签作身份，应明确 `serialKind=serviceTag`。不能选模块行、把 `NA` 当唯一序列或把索引 1 一律认为机箱。

Dell 的 ARP 表返回了 `192.168.4.*` 候选、接口与 VLAN；还存在同一 MAC 对应多个 IP。LLDP 给出 chassis ID subtype、端口、管理地址和 TTL。共享 MAC 或相同名称仅产生重复疑点，不自动合并主机、BMC、虚拟接口和地址别名。来源表的新鲜读取也不能证明每个 ARP 地址此刻在线，须同时展示 ageMinutes。

本次已读取到的 SNMP 用户与用户提供的 SSH 用户不同。SNMP 用户存在和算法可见不证明已取得其认证/加密口令，也不证明所有 MIB 视图权限；本轮不读取配置中的密钥、不开启 SNMP、不尝试推导口令。

另在审阅中纠正 ENTITY-MIB 身份字段：`.9` 是 `entPhysicalFirmwareRev`，`.10` 是 `entPhysicalSoftwareRev`。驱动分别写 `Identity.firmware` 与 `facts.softwareVersion`；没有固件值时不拿软件版本冒充。[RFC 6933 定义](https://www.rfc-editor.org/rfc/rfc6933.html)

发现流程的确切 API 与去重边界见[候选发现契约](NETWORK-DISCOVERY-API.md)，官方资料与后续协议选择见[旧硬件接入研究](LEGACY-HARDWARE-ONBOARDING.md)。
