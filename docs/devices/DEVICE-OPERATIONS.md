# 设备接入、部署与真机验收

本轮已实现设备登记/资料编辑、通用 SNMP v2c/v3、HTTPS Redfish 与固定档案 SSH 只读驱动、自动身份候选、按实际返回对象识别能力，以及内置 Worker 定时采集和浏览器 SSE。厂商依据见 [SNMP 研究](SNMP-PROTOCOL-RESEARCH.md)、[BMC 研究](BMC-PROTOCOL-RESEARCH.md)，精确请求见 [设备 API](DEVICE-INTEGRATION-API.md)，模拟器与联调证据见 [设备接入验收](DEVICE-VERIFICATION.md)。具体设备型号、固件和未覆盖 OEM 扩展仍需真机验收；协议模拟器不是厂商认证，独立 Edge 采集代理尚未实现。

## 从前端接入

1. 用 ADMIN 登录，进入「资产目录」，点击「登记设备」。填写名称、类型、站点、管理地址；厂商和型号可以留空。登记后进入「接入与采集」。现有设备可从详情进入相同标签。
2. 设备资料使用「编辑资料」修改，独立 `inventoryRevision` 防止并发覆盖。OPERATOR 也可以编辑资料。修改资产管理地址不会重定向已保存连接，连接目标需单独编辑。
3. 添加 SNMP、Redfish 或 SSH。三套连接可以同时存在，也可只启用一种。用户名、community、认证口令、加密口令及 BMC HTTPS 密码由设备管理员提供，系统不能从 SNMPv3 发现算法或推导密钥。
4. 先点击「保存连接」，再点击「测试连接并识别」。查看厂商候选、sysObjectID/型号/序列号/固件及来源，确认目标正确。`PARTIAL` 表示部分资源可读；每项质量标识解释缺失，不能等同于认证失败。认证、TLS、网络或发布失败以 `ERROR` 和安全错误码说明。
5. 「立即采集」将数据写入接口目录、指标历史和状态事件；「启用采集」允许独立 Worker 周期读取，「停用采集」暂停后续周期读取。默认 60 秒，最短 15 秒。测试成功不会自动开启周期采集，也不会自动写指标。
6. `ADMIN` 可以保存/测试/采集/启停；`OPERATOR/VIEWER` 读取安全结果和实时状态。所有用户都看不到已存密钥。编辑时空密码保留原值；改变目标/端口/账号/版本/安全算法/context/TLS 信任身份/SSH 档案或主机密钥指纹时必须重填所需密码。

SNMP v3 支持 `noAuthNoPriv`、`authNoPriv`、`authPriv`；认证算法 SHA256/SHA512，以及显式旧固件兼容 SHA1/MD5；隐私算法 AES128、显式 DES。不支持未经验证的 AES256 扩展；没有自动降级。SNMP v2c community 和 v3 `noAuthNoPriv` 的安全属性按协议原样呈现。

Redfish 使用 HTTPS Basic 只读 GET。`SYSTEM` 使用 JVM 系统信任与主机名校验，`PINNED` 校验用户核对的叶证书 SHA-256 指纹与有效期。没有跳过 TLS 验证选项。相对链接只能在同一设备 `/redfish/v1` 资源树内，拒绝跨主机跳转；不硬编码 Systems/1。旧 iMana 不假定支持 Redfish，可选择下面的固定 SSH 档案；未实现 IPMI 回退或私有 KVM 控制。

SSH 支持 `HUAWEI_IMANA`、`DELL_OS9`、`CISCO_IOS_XE` 三个只读档案。填写账号、密码、端口与 OpenSSH SHA256 主机密钥指纹，身份核验发生在密码认证前。档案固定命令，不执行任意命令、设备配置、电源控制，也不读取运行主机 SSH 配置、代理或私钥。iMana 读取身份、健康与有单位传感器；交换机/路由器读取身份和 ARP/LLDP/CDP 邻居，接口计数仍需配置 SNMP。

## 查询语义与限制

- 接入结果中的 `identity` 是基于真实返回的候选，`capabilities` 仅表示成功读到的对象族。未知或冲突证据保留通用类型；不将企业 OID 根当作完整机型认证。
- 标准 Redfish CPU/内存库存不是利用率。温度主曲线是已观测温度传感器最大值，`facts` 记录口径和来源。整机功率只在单一系统/机箱、唯一可信候选和足够发现证据时输出；不相加 PSU 输入/输出，不把子系统功耗当整机功率。
- 同一设备的同一主曲线只有一个连接来源认领，防止不同连接交错覆盖。配置保存和停用会事务释放该来源归属，后续成功读取方可重新认领。`facts.publishedMetricIds` 显示该次实际发布的指标；来源独立读数始终保留在接入结果中。
- 带宽速率需要两次有序、连续的计数器样本。首次样本、重启、断点、缺失连续性证据、超长间隔和可疑速率保留缺失，不填 0。设备带宽是本次返回接口的求和，物理/逻辑接口可能重叠，不代表站点总流量。
- 历史热力图只跨有连续性证明的 64 位计数器样本汇总。缺断点/启动证据与 32 位样本隔离到独立 epoch，避免汇总出虚假值；原始观测仍可保存。VictoriaMetrics 的浮点计数器可能量化超过 53 bits 的整数，历史查询保留对应质量标识；接入面板保留十进制整数字符串。
- 接口稳定标识来自名称与 MAC 的组合，不只使用会变化的 ifIndex。完整接口目录快照可将本来源已缺席接口标为 NOT_PRESENT；受限/部分目录读取保留旧库存。不会删除已有接口历史。
- 采集请求最多执行 25 秒协议读取（外层 30 秒），Redfish 最多 80 GET、单响应 2MiB、128 传感器；SNMP 最多 2500 返回变量、用户指定最多 256 接口、整轮 128 传感器。SNMP 传感器表/总量触限或变量预算耗尽时不生成设备级单源聚合。SSH 最多 6 条固定命令、每条 256 KiB 输出、128 个传感器与 256 条邻居。并发及队列有界，一个目标超时不能无限占用线程。
- 手动任务启动后独立于 HTTP 消费者完成，离开页面不留下永久采集中状态；总任务时间有上限。数据库 90 秒租约和 revision 拒绝重复提交、旧任务结果。配置在活动租约中返回 409，刷新后重试；崩溃后到期连接可被 worker 接管。
- VM、Kafka、MySQL 不是一个分布式事务。发布失败可能已写入其中部分存储，API 明确报告 PUBLICATION_FAILED，不声称全局 exactly-once。失败保留最后成功证据，不清空历史，也不因超时断言设备离线。
- 浏览器通过带 Authorization 头的 fetch SSE 读取安全结果；3 秒刷新、每 API 副本最多 64 流、每流 10 分钟后重连，支持取消和轮询回退。令牌不进入 URL。这里的 SSE 不代表设备端已有 WebSocket 遥测或 Redfish EventService 订阅。

## 邻居发现与去重

进入「资产目录 → 发现设备」，选择本站点 1–8 台已成功采集 SSH 邻居的设备，填写明确的 `/24` 至 `/32` IPv4 CIDR。此操作筛选已保存的 ARP/LLDP/CDP 证据，不连接候选 IP。来源超过 15 分钟或 LLDP/CDP 的已知 TTL 到期不参与本轮；ARP 条目年龄另外保留。候选不等于在线设备，也不代表覆盖整个网段。

同址资产优先复用；同 MAC 多地址是弱重复线索，历史线索也可提示核对。地址内矛盾身份进入冲突状态，不能直接登记。管理员或操作员可登记明确的新设备，或把地址关联到同站点已有资产；关联不会合并资产、改写管理地址或搬移历史。发现流程的登记具备幂等与事务并发保护，旧手工登记入口仍可能创建同址资产，发现时会报告该冲突。完整字段、时间与边界见[发现 API](NETWORK-DISCOVERY-API.md)。

## 本地 Compose

```sh
python3 scripts/init-env.py
docker compose --env-file .env -f deploy/compose/compose.yaml up -d --build
```

初始化为新安装生成密码，为旧 `.env` 仅补充一次 `NOERIVA_CREDENTIAL_KEY`（Base64 的 32 个随机字节）；不替换已有密码或加密密钥。主密钥不能丢失，恢复数据库需要对应密钥。没有明文回退；正式轮换需先用旧密钥解密再用新密钥重加密，本版本没有自动密钥轮换 API。

控制面和 worker 共享密钥，只有 worker 设置 `NOERIVA_DEVICE_COLLECTOR_ENABLED=true`。`NOERIVA_DEVICE_ALLOWED_CIDRS` 默认 RFC1918 和 IPv6 ULA，建议收窄到管理网段。每次请求重新解析域名，所有解析结果都必须落入允许网段，拒绝链路本地/元数据、多播和通配地址；驱动连接已核验的固定地址。服务不会扫描网段，只读取前端登记的目标。

启用内置 Worker 后，「采集器」页面会显示「设备协议采集 Worker」的心跳及本进程最近成功采集时间。状态由 Worker 直接写入数据库，`GET /collectors` 负责查询展示。其缓冲容量为未启用，不表示已具备 Edge 离线磁盘缓冲；独立 Edge 的注册、心跳/缓冲上报接口和代理适配仍待实现。

默认 Compose 仅在本机开放控制台/API。Docker Desktop 测试宿主机模拟器可使用 `host.docker.internal`；真实设备填其管理网络 IP/DNS。跨网络路由、设备只读账号权限、ACL 和设备端协议开关由环境管理员配置。

## Kubernetes

[部署指南](../../deploy/README.md) 的现有 Secret 额外需要 `NOERIVA_CREDENTIAL_KEY`。API 和 worker 使用同一个 Secret，密钥不进入 values、ConfigMap、镜像或数据库。Chart 使用必需 secretKeyRef，缺少键时 Pod 不会启动成明文模式。

生产 values 的示例（地址需替换成实际授权网段）：

```yaml
runtime:
  existingSecret: noeriva-runtime
  deviceAllowedCidrs:
    - 10.30.0.0/24
networkPolicy:
  deviceEgress:
    - to:
        - ipBlock: {cidr: 10.30.0.0/24}
      ports:
        - {protocol: UDP, port: 161}
        - {protocol: TCP, port: 443}
        - {protocol: TCP, port: 22}
```

该规则分别允许 API 手动探测和 worker 周期探测；自定义端口要同步修改。`dataPlaneEgress` 仍负责 DB/Redis/Kafka/VM/ClickHouse。DNS 由独立规则允许；空 `deviceEgress` 拒绝设备出站。

API 可按 HPA 扩展；设备读取使用数据库租约避免跨副本重复执行。当前 Chart 为同一组织部署单副本综合 worker（同时执行现有汇总），没有据此声称多组织自动分片或生产高可用已验收。跨组织运行应显式部署各组织 worker。实际吞吐量需在用户设备延迟、接口数量、数据平面容量下测量。

## 真机验收清单

已获得六台指定设备的只读返回，具体型号、平台采集和历史查询结果见[六机验收](HARDWARE-VERIFICATION.md)与[真机格式记录](HARDWARE-FORMAT-NOTES.md)。H3C、Dell iDRAC 及其他机型尚待提供。继续逐台记录以下信息，不把原始密码放进文档：

| 验收项 | 所需证据 |
|---|---|
| 型号/固件/身份 | 厂商页面确认的型号/版本与系统返回的候选、sysObjectID 或 Redfish schema |
| 凭据权限 | 只读账号、SNMP版本/算法/context、HTTPS信任方式；正确与错误凭据各一次 |
| 单位与对象 | 对照设备管理页核实温度、风扇、功率、CPU/内存利用率、接口速率与状态 |
| 返回差异 | 脱敏的实际 JSON / MIB OID、缺失字段、null、权限403、分页、多实体 |
| 生命周期 | 首次采集、连续两轮、暂停/恢复、重启后的计数器与断点、接口索引变化 |
| 历史与租约 | 当前来源、VM趋势、network/bmc接口汇总、手动与后台并发、失败保留证据 |
| 浏览器权限 | 管理员可配置；其他角色无法读取连接账号/密钥；只读实时视图正常 |

目前不自动注册 Trap、Redfish webhook/SSE 订阅，未实现 NETCONF/RESTCONF、私有 WebSocket、自动写入全网拓扑、BIOS/电源操作或配置下发。这些能力需要单独的模型/固件证据、权限与实现验收。
