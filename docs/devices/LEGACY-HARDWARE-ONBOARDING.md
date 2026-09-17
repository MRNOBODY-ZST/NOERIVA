# 旧固件设备只读接入预研

研究日期：2026-09-07。本文基于现有代码、官方协议资料与主任务反馈；本代理没有连接真实设备。完成预研后，按主任务授权实现了第 7 节的 SSH 适配器。文中不记录真实 IP、用户名、口令、序列号或私钥。第 2 节中的固定查询清单已用于对应档案，其他候选协议或扩展仍属研究建议。

## 1. 本次已知条件与优先路线

下表的“现场反馈”来自主任务的只读探测反馈，并非本文作者独立复测，也不等于完整采集验收。

| 对象 | 主任务现场反馈 | 最小接入路线 |
|---|---|---|
| Dell S6100-ON | OS9 9.14(2.23)，SSH 可登录 | 固定 SSH 查询身份、ARP、LLDP；获得单独 SNMP 配置后继续用现有 SNMP 驱动采接口计数 |
| Cisco ASR1002-X | IOS XE 17.09.08，SSH 可登录 | 固定 SSH 查询身份、ARP、LLDP/CDP；现有 Cisco SNMP profile 仍需实际 SNMP 凭据与权限 |
| 三台 Huawei iMana | SSH 登录成功；remote exec 命令未执行，返回 `iMana:/->` 提示符；默认 Python TLS 握手失败 | 增加有界交互 shell 状态机和帮助确认后的 `ipmcget` 查询；TLS 失败先分型，不尝试关闭证书验证来“修复” |
| Inspur BMC | SSH 为 AMI 自定义 CLI，帮助中有 `mc`、`fru`、`sensor`；HTTPS Redfish 1.0.2 可通过 TLS 1.2 访问，证书有效期至 2037 | 优先用现有标准 Redfish。AMI CLI 不能当 Linux shell 或直接当 ipmitool 命令语法使用 |

本批设备的系统默认 SSH 算法均已连接成功，**目前没有启用旧 SSH 算法的证据需求**。TLS 1.2 握手可用和证书未过期也不等于证书已受信任：Redfish 仍须完成 SYSTEM 的信任链/主机名验证，或显式 PINNED 指纹验证。

三台 iMana 有共享 RSA host key 的现场现象，部分设备也共享 ECDSA host key。**host key 不能作为设备强唯一身份**。它用于在指定管理端点上验证 SSH 对端；平台资产 ID 与组织/站点为归属主键，序列号、FRU 产品信息、BMC MAC、型号作为带来源的交叉证据。遇到重复或缺失身份不得静默合并设备。

## 2. 已核对的 SSH 查询命令

只执行该型号 profile 中的固定命令；权限不足、未支持、功能未开启、空表、输出截断分别记录。不要因文档示例出现 `conf` 提示符而进入配置模式，也不要为获取数据自动启用 LLDP/CDP、进入 `enable`、修改 SNMP 或运行全量 `show tech-support`。

### Dell S6100-ON / OS9

| 查询 | 目的与解析边界 | 官方依据 |
|---|---|---|
| `show version` | 解析 System Type、Application Software Version、uptime；区分底层 Operating System Version 与 OS9 应用软件版本 | [S6100 9.14.2.5 show version](https://www.dell.com/support/manuals/en-us/dell-emc-os-9/s6100-on-9.14.2.5-cli-pub/show-version?guid=guid-c13e3d17-f476-4c42-9c42-f69571ad31ad&lang=en-us) |
| `show inventory` | 读取机箱/组件型号与序列信息，保留 unit/slot；不能把首个光模块序列号当机箱序列号 | [S6100 show inventory](https://www.dell.com/support/manuals/en-us/dell-emc-os-9/s6100-on-9.14.2.5-cli-pub/show-inventory?guid=guid-260b1ab1-df58-4dd5-aab1-49dfcf0eba8f&lang=en-us) |
| `show arp` | 读取 IPv4、MAC、Age(min)、Interface、VLAN。`show arp vrf <name>` 仅在已经识别且参数严格校验的 VRF 上执行 | [S6100 show arp](https://www.dell.com/support/manuals/en-us/dell-emc-os-9/s6100-on-9.14.2.5-cli-pub/show-arp?guid=guid-45c568a9-0cbc-4794-97e9-05121c68bc18&lang=en-us) |
| `show lldp neighbors detail` | 读取本地端口、远端 chassis/port ID 及 subtype、管理地址、TTL；一个本地接口可以有多个邻居 | [S6100 LLDP 命令与版本范围](https://www.dell.com/support/manuals/en-us/dell-emc-os-9/s6100-on-9.14.2.5-cli-pub/show-lldp-neighbors?guid=guid-8e2ab7e1-f319-43f9-beb7-5bfbbaf05e27) |

这些资料对应同平台 9.14.2.x，但不能单凭文档保证 9.14(2.23) 所有输出字段相同；解析器需按本次脱敏实机输出建 fixture。Dell 的 `show arp` 若返回 ARP 清理仍在进行的提示，应记瞬时不可用；不能当零条。LLDP 用 detail 避免仅依赖摘要列与截断的名称。当前未将 Dell CDP 列入白名单，不能把 Cisco 的命令直接平移。

### Cisco ASR1002-X / IOS XE

| 查询 | 目的与解析边界 | 官方依据 |
|---|---|---|
| `show version`、`show inventory` | 系统软件、硬件型号与组件 PID/VID/SN，机箱与可替换组件分开 | [ASR1000 官方硬件报告功能](https://www.cisco.com/c/en/us/td/docs/routers/asr1000/install/guide/asr1routers/asr-1000-series-hig/asr-hig-hrd.html) |
| `show ip arp` | IPv4 邻居缓存，保留接口、年龄、状态，`Incomplete` 不是 MAC 地址 | [IOS XE ARP 查询命令](https://www.cisco.com/c/en/us/td/docs/ios-xml/ios/ipaddr/command/ipaddr-xe-3se-3850-cr-book/ipaddr-xe-3se-3850-cr-book_chapter_010.pdf) |
| `show lldp neighbors detail` | LLDP 的远端声明、本地接口、管理地址；该能力是否启用须从结果判断 | [IOS XE 17.x LLDP 指南](https://www.cisco.com/c/en/us/td/docs/routers/ios/config/17-x/application-services/b-application-services/m_ce-lldp-multivend.html) |
| `show cdp neighbors detail` | CDP 设备 ID、平台、软件版本、地址、本地接口和远端端口 | [Cisco CDP 命令参考](https://www.cisco.com/c/en/us/td/docs/ios-xml/ios/cdp/command/cdp-xe-3se-3850-cr-book.pdf) |

IOS XE 资料包含跨平台命令；ASR1002-X 17.09.08 的命令权限、VRF 扩展、空表提示和行格式仍以本次实际只读回包为验收条件。不要为 ARP 探测清缓存，不发送 ping 扩充缓存，也不把 `show ip arp` 当作所有 VRF 的完整目录。[Cisco ASR1000 ARP 指南](https://www.cisco.com/c/en/us/td/docs/ios-xml/ios/ipaddr_arp/configuration/xe-16/arp-xe-16-book/arp-config-arp.html)

### Huawei iMana 交互 CLI

主任务后续已验证本批 iMana 7.35/7.38 使用 `ipmcget -d version`、`ipmcget -d fruinfo`、`ipmcget -d health`、`ipmcget -t sensor -d list`；四条命令构成本批只读 profile。表格 `degrees C`、`RPM`、`Watts` 有明确单位，`na` 保持缺失，`discrete`/`unspecified` 与状态位 `0x8000` 不猜测为数值指标或健康。

官方当前 iBMC 文档区分查询程序 `ipmcget` 与配置程序 `ipmcset`，提供 `help` 和 `[command] --help`。官方当前服务器维护文档使用 `ipmcget -d ver`、`ipmcget -d health`、`ipmcget -d healthevents`。这些是**核对旧 iMana 帮助的候选项**，不是已证明适用于全部 iMana 固件的承诺；不能把新 iBMC 的 `-d ver` 强行套用旧版，也不能在未支持的命令返回空输出时标成功。[Huawei CLI 语法](https://info.support.huawei.com/hedex/api/pages/EDOC1000163559/YEI0812D/19/resources/en-us_concept_0000001989983616.html)、[Huawei help](https://info.support.huawei.com/hedex/api/pages/EDOC1000053358/YEF0907R/25/resources/en-us_cliref_0000001990378608.html)、[Huawei 版本/健康只读检查示例](https://info.support.huawei.com/enterprise/en/doc/EDOC1100118955/37aff47a/checking-the-server)

首次型号接入流程建议：通过帮助确认数据项后固化档案。当前已确认的 iMana 档案运行流程为：建立 SSH session → 请求 shell/PTY → 有界读取登录 banner 与完整提示符 → 逐个发送四条固定查询 → 等待整行 `iMana:/->` 提示符 → 关闭 channel/session。每轮采集不再额外请求帮助。远程 exec 接受但不执行的现象必须有 fixture，不能把登录 banner 解析成命令成功。不执行文档或设备输出里出现的任意命令。`ipmcset`、升级、重启、清日志、风扇调速不在采集白名单。

## 3. iMana HTTPS / IPMI 替代方案

### HTTPS

目前没有取得可准确绑定这三台 iMana 型号和固件的官方 HTTPS API 文档，因此不能给出经验证的私有登录 URI、cookie 字段、CSRF 流程或传感器接口。iBMC Redfish 的官方实现不能推广为 iMana 已提供 Redfish。Huawei 官方下载说明本身要求按计算节点型号获取 iMana 200 软件，说明“iMana”名称不足以确定接口版本。[Huawei 按型号获取 iMana 200 软件](https://info.support.huawei.com/hedex/api/pages/EDOC1000053358/YEF0907R/25/resources/en-us_topic_0284913546.html)、[Huawei iBMC 官方 Redfish 工具](https://github.com/Huawei/Huawei-iBMC-Cmdlets)

一次标准 `GET /redfish/v1` 是可辨认的服务探测入口；但 TLS 未握手成功时，尚不能得出根资源不存在或密码错误的结论。若 Web UI 只能通过老 TLS 使用，先获取设备型号、完整固件与确切协议/密码套件诊断。显式旧版 TLS 客户端也不会使不存在的 Redfish 出现。标准 Redfish 的 Sessions 认证与旧 Web UI 表单/cookie 登录是不同接口，不能猜 `/api/session` 或 CGI 路径。[DMTF Redfish 服务与认证规范](https://www.dmtf.org/sites/default/files/standards/documents/DSP0266_1.24.0.html)

### IPMI 备用读取

只有当该型号资料、设备本地帮助或受控探测证实 IPMI-over-LAN 已启用且账号具有该接口权限时，才使用该路线。Web/SSH 登录成功不证明 IPMI 或 SNMP 可用。选用 IPMI 2.0 RMCP+ 的 `lanplus`，显式保存 cipher suite 与 privilege；当前 ipmitool 手册默认 cipher 17，旧版工具和固件可能不同，不能依赖客户端默认值，也不自动降为 cipher 0、无认证或 `lan`。[ipmitool 官方手册](https://github.com/ipmitool/ipmitool/blob/master/doc/ipmitool.1.in)

首批仅保留 `mc info`、`chassis status`、`fru print`、`sensor list`、`sdr elist` 和有条数预算的 `sel list`；分别提供 BMC 身份、机箱状态、FRU、传感器与历史事件。旧固件 cipher 3 等兼容方案必须有明确记录和实际协商证据。OEM `raw` 命令没有文档与响应 fixture 时不采用。传感器 N/A、离散状态、缺失单位不转为零；SEL 的时间缺失/时钟偏差单独标明，不能按本次采集时间假装事件刚发生。

进程调用采用固定可执行文件与参数数组，密码由受控子进程环境 `IPMI_PASSWORD` + `-E` 或权限 0600 临时文件 `-f` 传入；不使用 `-P <password>` 进入进程参数。显式设置 `-L USER` 作为所需权限起点，权限不足返回具体限制，避免误用工具版本不同的默认 ADMINISTRATOR。工具是独立适配器，不把 AMI SSH 的 `mc` 命令与它混淆。[ipmitool 官方使用说明](https://github.com/ipmitool/ipmitool)

Inspur 官方资料对 IPMI/Redfish 的支持属于对应产品手册范围，不应推广所有世代。当前本批 Inspur 已反馈 Redfish 1.0.2 可达，所以无须先增加私有 Web 爬取或 AMI shell 控制。优先读取根实际链接中的 Systems/Managers/Chassis、旧 Thermal/Power；仅发布实际读取的标准能力。OEM CPU/内存利用率、事件订阅仍需各自证据。[Inspur BMC V2.7 手册](https://en.inspur.com/eportal/fileDir/active_download/platformBookZh/6680/Inspur%C2%A0Server%C2%A0BMC%C2%A0User%C2%A0Manual%C2%A0V2.7.pdf)

## 4. 现有驱动与旧固件的具体差距

以下是预研开始时的源代码边界；“已处理”项反映本次实现结果，其余仍是待型号证据支持的扩展，不代表已在真实设备复现。

| 当前代码 | 旧固件可能出现的结果 | 最小后续改动建议 |
|---|---|---|
| `RedfishHttps` 仅启用 TLS 1.2/1.3，SYSTEM 信任链+主机名，PINNED 叶证书 SHA-256+期限校验 | 旧协议/弱算法握手失败；PINNED 不能放行过期证书或启用老 TLS | 将协议协商与证书失败的安全诊断分开；保持默认策略，旧 TLS 必须独立显式兼容配置 |
| `RedfishDriver` 只请求 `/redfish/v1`，所有 3xx 拒绝 | 仅 `/redfish/v1/` 有效或返回规范化 redirect 的服务可能无法识别 | 可增加最多一次固定 `/redfish/v1/` 尝试，只允许同 origin、相同服务根、无 user-info/query/fragment；不开放通用 redirect |
| Redfish 目前只做 HTTPS Basic | 只接受标准 session 的服务可能返回 401 | 按固件证据增加独立 session 模式，发现 Sessions 集合、请求内复用 token、清理自己创建的会话；失败不尝试私有 Web 登录 |
| SnmpSession 为 v2c/v3，GET 每批 40，GETBULK repetitions 最大 10，2500 varbind/25 秒总预算 | 旧代理 `tooBig`、GETBULK 不完整或缺列；已有 PARTIAL 标识 | 明确 `tooBig` 时有界降批，必要时 profile 固定 GETNEXT；保留同一总预算，不自动改凭据、安全级别或 SNMP 版本 |
| `SnmpProfiles` 中 Dell/Force10 是通用 profile；Huawei iMana/iBMC 使用通用 BMC profile | 标准身份/IF/ENTITY 可采，但私有 CPU、内存、温度可能缺失 | 凭实际型号/固件官方 MIB 建独立扩展，不使用 Huawei VRP 私有 OID 代替 iMana 传感器 |
| SnmpDriver 原来用 ENTITY-MIB `.10` 填 `Identity.firmware` | `.10` 是 software revision；只填 firmware revision 的代理可能显示空，或把 OS 版本当固件 | 主任务已处理：分读 `.9` firmware 与 `.10` software，保留来源，软件版本进入 facts；对应回归已由主任务验证 |
| DeviceProtocol.Reading 是 identity、metrics、sensors、ports、capabilities、qualityFlags、facts | 原来没有 SSH/IPMI transport 和邻居观察契约 | SSH transport 已实现。现阶段用固定字段的有界 `neighborObservations` JSON 数组字符串兼容现有 facts；发现模块投影该字段并单独存储候选。未冒充接口计数；IPMI 尚未实现 |

固件列依据：[RFC 6933 ENTITY-MIB](https://www.rfc-editor.org/rfc/rfc6933.html)。现有 SNMP 与 Redfish 的更完整边界见本目录 `SNMP-PROTOCOL-RESEARCH.md`、`BMC-PROTOCOL-RESEARCH.md`；本次不重复宣称模拟测试为真机认证。

## 5. 显式兼容模式与安全边界

SSH 的 KEX、服务器 host-key 算法、cipher、MAC、用户公钥认证算法是不同维度。必须按实际协商错误选择每台设备的最小算法扩展，不能全局启用所有老算法；密码认证不需要顺带更改用户公钥认证算法。采用平台保存的端点 host-key 指纹验证，不使用 `StrictHostKeyChecking=no` 或丢弃 known_hosts。当前六台设备默认 SSH 已通，不需这类扩展。[OpenSSH 官方 legacy 说明](https://www.openssh.org/legacy.html)

TLS 协商版本/密码套件与证书信任是不同问题。不要通过 trust-all、关闭 hostname 校验或全局清空 `jdk.tls.disabledAlgorithms` 处理旧 iMana。若最后确认只有旧协议可用，建议将兼容运行时隔离在单独采集器进程/容器，限制到已授权端点，固定证书信任，配置记录精确允许的协议/算法与停用日期；控制面的 JVM 与普通设备采集保持默认安全配置。JDK 的 disabledAlgorithms 可以继续阻止通过 SSLParameters 重新启用的算法，单改 enabledProtocols 不保证兼容。[Java 25 JSSE 算法约束](https://docs.oracle.com/en/java/javase/25/security/java-secure-socket-extension-jsse-reference-guide.html)

SSH 已继承现有 TargetPolicy 的固定解析地址与组织隔离，远端邻居返回的新地址只作为数据展示，不能自动成为下一跳扫描目标。工作线程采用有界队列；每台设备同一时刻一个协议任务，当前 SSH 最多 6 个命令、每命令 256 KiB、总计最多 1.5 MiB 命令输出、25 秒硬期限。这些是实现预算，未经吞吐性能基准，并非厂商限额。取消时关闭 channel/session/client；令牌租约、配置修订与持久化审计复用控制面。

## 6. 数据目的、最小扩展与验收

ARP 是某个观察设备、接口和 VRF 在某时刻的 IP→MAC 缓存；LLDP/CDP 是邻居自行宣告的链路信息。它们支持资产线索与拓扑核对，不单独证明 NAT 归属、终端所有权或全网完整性。建议记录 `observingDeviceId/siteId/observedAt/protocol/sourceRef`；ARP 另存 IP、MAC、interface、VRF、age、状态，LLDP/CDP 另存本地端口、远端 chassis/port subtype+值、系统名、管理地址、TTL。缺失项为 null，分页/输出截断为 PARTIAL，未知端口不强行 JOIN。

实施优先级：

1. 用已能登录的 SSH 建身份与固定查询解析器；先覆盖 OS9/IOS XE 和 iMana 的交互式 shell 差异，不提供任意命令执行 API。
2. 通过现有 Redfish 接入本批 Inspur，按真实响应补仅必要的兼容 fixture；保持标准 TLS 与可审查的证书信任。
3. SNMP 等待单独账号/USM/community 配置；用标准 MIB 验证身份、端口计数与单位后再启用连续采集。SSH/Web 账号不能推导 SNMP 密钥。
4. iMana 的 IPMI 或私有 HTTPS 仅在 SSH 无法覆盖必要读数、且型号/固件资料与实际协议已确认后再扩展。

验收需覆盖：交互提示符与忽略 exec、banner/ANSI/分页、权限拒绝、命令未支持、真正空表、重复名称、共享 host key、跨 VRF 同 IP、同端口多邻居、过期 TTL、部分输出与硬超时、取消后任务完成状态、所有日志不包含凭据。真实结果保存到受控证据位置，公开 parser fixture 使用替换后的 synthetic 型号标识/序列号/文档网段，清晰标记 `SYNTHETIC`。官方文档确认命令存在；只有该设备版本真实回包和驱动归一化结果共同验证后，能力状态才能提升为真机已验证。

## 7. 已实施的 SSH 档案与验证范围

实现为纯 Java `SshDriver` / `SshSession` / `SshProfiles`，使用 Apache MINA SSHD 2.19.0。版本来源为 [Apache 官方下载页](https://mina.apache.org/sshd-project/downloads.html) 与 [2.19.0 发布页](https://mina.apache.org/sshd-project/download_2.19.0.html)。客户端显式覆盖默认主机验证器、身份来源和 SSH 配置解析，避免读取宿主用户的 SSH key、agent 或 config；库配置依据 [官方 client setup](https://github.com/apache/mina-sshd/blob/sshd-2.19.0/docs/client-setup.md)。

连接存储为 `slot=ssh`、`protocol=SSH`、独立观测 `source=ssh`。新增末尾字段 `sshProfile` 和 `sshHostKeySha256`；原 SNMP/Redfish JSON 缺少这两个字段时保持 null。SSH 必须提供用户名、密码和规范 `SHA256:<43 字符无填充 base64>` 主机公钥指纹；host、port、username、profile 或 pin 改变均要求重填密码。只连接已核验的 `Target.address`，原 DNS 名不再解析。先验证主机密钥，再仅尝试一次 password 认证；没有 trust-all、自动算法降级、任意命令或跳板代理入口。

当前算法范围为 AES CTR/GCM 或 ChaCha20、SHA-2 MAC、非 SHA-1 KEX、非 `ssh-rsa`/`ssh-dss` 签名；RSA 主机公钥仍可使用 RSA-SHA2 签名。CBC-only 对端的真实本地 SSH 模拟回归证明会在密码发送前拒绝。实际设备若只有其他算法，返回失败并单独评估，不能偷偷扩大该范围。

执行预算包含排队时间：最多 8 个工作线程、每线程有界 32 待执行任务、25 秒总期限、最多 6 条固定命令、每条 256 KiB、最多 64 次翻页。Huawei、Dell、Cisco 档案实际分别使用 4、4、5 条命令。每次只发固定分页空格；登录与命令响应都须出现正确完整提示符。输出在解析前消除终端控制序列并屏蔽已知密码原文；API 仅返回结构化字段，不返回原始 CLI。

iMana 保留最多 128 个传感器：温度 `temperature_celsius/Cel`、风扇 `fan_rpm/RPM`、功率 `power_watts/W`、电压 `voltage_volts/V`、电流 `current_amps/A`；离散或 unspecified 项为 `sensor_state`、null 数值及 UNKNOWN 状态。`na` 不转成零。摘要温度为本轮可用温度传感器最大值，facts 说明口径；Power1/Power2 不相加成机箱功率。身份使用 FRU 产品序列号，并记录 `serialKind=productSerial`。

Dell inventory 只从表头对应列和带 `*` 的管理单元行取序列号；序列号为 NA 时才回退到该行的 7 字符 Svc Tag，并记录 `serialKind=serviceTag`。LLDP 的 `Information valid for next … seconds` 优先于原始 `Remote TTL`，0 秒保持过期证据。ARP/LLDP/CDP 合计最多 256 条完整候选，超限产生 `SSH_NEIGHBOR_LIMIT`；未知或缺失字段为 null，本地接口没有证据时不补造。使用文档网段和 synthetic 标识的测试覆盖各厂商的行格式。

验收证据分层：`SshDriverTest` 使用真实本地 SSH server 验证协商、pin 在密码前检查、认证次数、交互提示符/分页、超限/超时与取消；`SshProfileTest` 验证脱敏厂商解析；`SshConfigurationTest` 验证老 JSON 兼容与身份变更；`DeviceAccessIntegrationTest` 使用真实 MySQL 验证加密保存、修订及三来源隔离。该证据不代替主任务的真机平台采集与 Computer Use 验收；没有把整个厂商系列标为已获硬件认证。
