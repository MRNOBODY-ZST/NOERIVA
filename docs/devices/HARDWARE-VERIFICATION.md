# 2026-09-07 六台真机接入与邻居发现验收

> 2026-09-09 更新：当前的 Dell S6100-ON / OS9 9.14(2.23) 与 Cisco ASR1002-X / IOS XE 17.9.8 已补充 SNMPv3、SSH 环境/配置及 LLDP 实机验证；iMana 7.38 与 Inspur SA5212M5 / BMC 4.26.6 已复核，iMana 7.35 离线状态已修复。范围及剩余限制见[设备监测完整性复核](MONITORING-INTEGRITY-20260909.md)。以下保留 09-07 的历史结果，不代表当前仍缺少 SNMP 密钥或邻居数据。支持目录的真机标记只对应明确列出的机型/固件/协议，iDRAC、iBMC、H3C 继续待验收。

本次在用户指定的六个管理地址上进行只读 SSH / HTTPS 操作，并在本机 Compose 平台完成登记、加密凭据保存、实际采集、状态投影、指标历史与周期调度验证。没有下发设备配置或电源操作。验收仅对应下面的型号、固件和实际读到的对象，不代表整个厂商系列认证。

## 设备结果

| 管理地址 | 实际型号 | 固件 | 已验证路径与内容 |
|---|---|---|---|
| 192.168.254.1 | Dell S6100-ON | 9.14(2.23) | SSH：身份、服务标签、ARP、LLDP；成功 |
| 192.168.254.3 | Cisco ASR1002-X | 17.09.08 | SSH：身份、机箱序列、ARP；部分成功，LLDP/CDP 命令未提供可识别结果 |
| 192.168.254.10 | Huawei Tecal RH2288 V2 | (U1029)7.35 | iMana SSH：产品身份、健康、99 项传感器；成功 |
| 192.168.254.11 | Huawei Tecal RH2288H V2 | (U1029)7.38 | iMana SSH：产品身份、健康、99 项传感器；成功 |
| 192.168.254.12 | Huawei Tecal RH2288H V2-12L | (U1029)7.38 | iMana SSH：产品身份、健康、101 项传感器；成功 |
| 192.168.254.20 | Inspur SA5212M5 | 4.26.6 (2020-11-11 14:21:18) | Redfish：身份、健康、41 项保留传感器/组件记录；部分成功，明确标记旧 Members、数字字符串与不可用读数 |

六台已启用 **60 秒周期采集**。读数中的成功时间和 Worker 心跳已更新，状态来源分别为 `ssh` 和 `bmc`。三台 iMana 的温度历史、Inspur 的温度和整机功率历史均可由 VictoriaMetrics 查询。温度为有效传感器最大值，不是进风温度；未返回的 CPU/内存利用率、交换机带宽保持缺失。

[机器可读的六机验收](../implementation/hardware-runtime-verification.json)由仓库内[只读验收脚本](../../scripts/verify-hardware-onboarding.py)生成，校验六个管理地址各仅有一个对应资产、连接启用、身份匹配、读数新鲜、来源发布和所声明指标存在历史值。脚本只查询本机平台 API，不连接设备、不更改配置。

## 实际格式修复

- Inspur 风扇 `Reading` 为字符串，现能保留 8 个 RPM 读数；无效十进制字符串仍不转为数值。`DISABLE` 温度通道不再显示伪造的 0°C，保留数量减少属于无效通道排除。
- iMana 采用交互 shell 和实际存在的 `ipmcget -d version` 命令；FRU 使用 Product Name/Product Serial，传感器按明确单位解析，未解码离散位保持未知。
- Dell 管理单元 Serial Number 为 NA，身份的 `serialKind=serviceTag` 明确标注服务标签来源；不取模块行作为机箱。
- ENTITY-MIB 固件字段 `.9` 与软件字段 `.10` 分开保存。这一修复经协议模拟器回归，本轮六台没有取得完整 SNMPv3 密钥，因此不宣称真机 SNMP 已验收。

格式样例与字段语义见[真实返回格式](HARDWARE-FORMAT-NOTES.md)。SSH key 与 HTTPS 证书指纹从本轮首次连接记录固定，属于首次信任记录；没有宣称已通过设备控制台进行带外核验。三个不同序列号的 iMana 存在主机密钥复用，不能据此合并资产。

## 192.168.4.* 的发现与去重

用户给出的 `168.4.*` 尚未补充完整 CIDR；本轮先使用已获授权 Dell 返回中实际存在的 `192.168.4.0/24` 证据，**未扫描或登录这些候选 IP**。

浏览器选择 Default site、Dell 来源与该 CIDR 后，成功生成 **26 个候选**。来源读取共含 45 条邻居记录，本 CIDR 接受 26 条 ARP 记录。页面展示源设备、接口/VLAN、MAC、来源时间和 ARP 年龄；不把候选标为在线，也不宣称整个网段已被穷举。

为核对地址别名，额外在已授权 Dell 上读取 `show ip interface brief`，明确得到 `Vlan 4 / 192.168.4.1` 与 `Vlan 254 / 192.168.254.1`。随后通过浏览器“审核证据 → 关联已有资产”，将 `192.168.4.1` 关联到既有 Dell 资产。平台原管理地址保持不变，未新增同址资产、复制凭据或合并历史；其余 25 个候选保留待审核。

[发现运行与关联证据](../implementation/hardware-discovery-verification.json)、[发现 API](NETWORK-DISCOVERY-API.md)和[20 项数据库验收](NETWORK-DISCOVERY-VERIFICATION.md)记录确切语义。发现入口内部登记具备幂等和并发事务保护；旧手工登记入口可能创建重复地址，发现时会报告冲突，不能据此声称所有写入入口具备全局唯一约束。

## 验证证据

- `./mvnw -B verify`：构建成功，230 项中 **229 通过、1 项跳过**，0 失败/错误。跳过项是显式需要 `NOERIVA_LIVE_QUERY_TEST` 的 `LiveMetricPipelineTest`；六机历史查询另经真实运行 API 验证。[各测试套件统计](../implementation/hardware-backend-tests.json)
- 前端 71 项组件测试、类型检查和生产构建通过；浏览器 Computer Use 已实测 iMana 配置、识别、立即采集、启用周期、编辑设备资料，以及发现候选和关联既有资产。
- 真实采集持续运行时，160 次设备状态查询、16 并发、0 错误，中位 23.59 ms，P95 85.58 ms，最大 106.63 ms。[本机测量记录](../implementation/hardware-state-load.json)；这是本机小规模证据，不是生产容量承诺。
- Control、Worker、Console 镜像已重建并在本地 Compose 启动；Flyway V7 已应用，readiness 为 UP。Kubernetes Chart 已完成严格 lint 和渲染，设备出站示例含 UDP 161、TCP 443、TCP 22；未向真实 Kubernetes 集群部署。

## 后续验收边界

本次提供的账号密码已用于 SSH/Web。设备中可见的 SNMPv3 用户不同，需要另行提供其认证/加密密码和明确算法，才能完成真实接口计数、带宽和 SNMP 私有传感器验收。H3C、Dell iDRAC 和 Huawei iBMC 没有本轮真机目标；其现有协议适配和模拟器结果不能替代现场验收。

临时明文凭据文件已在接入完成后删除；检查了 266 个源码、脚本、部署及文档文件，未发现本轮设备口令。运行所需凭据仅保留平台加密存储，主密钥仍按部署契约置于本地 Secret/环境。

设备端私有 WebSocket、NETCONF/RESTCONF、IPMI、Trap/Redfish 事件订阅、自动全网拓扑写入不在本轮已验证路径中。浏览器实时展示使用平台 fetch SSE。通过当前 ARP/LLDP/CDP 候选流程可以减少重复登记，不能仅凭 MAC 或名称自动确定所有物理设备的唯一身份。
