# 设备接入契约

本轮实现：手动新建与编辑资产、独立 SNMP/Redfish/SSH 连接及密钥、按实际返回识别能力、手动测试/采集、后台周期采集、实时只读展示，以及从已保存邻居证据生成、审核、登记或关联发现候选。只读取设备，不下发配置、不重启、不启用设备端协议。

## REST 接口（前缀 `/api/v1`）

- `GET /devices/{id}/management`：`{device, inventoryRevision}`。device 沿用旧 DTO；inventoryRevision 单独取资产表，不与遥测修订混用。
- `POST /devices/{id}/updates`：`{revision,name,type,siteId,vendor,model,managementAddress}`，返回相同 management。ADMIN/OPERATOR，CAS 冲突 409；目标地址修改不会重定向已保存连接。
- `GET /device-support`：`{items:[{id,vendor,family,protocols,implemented,verification,notes}],protocols,credentialStorageReady,collectorEnabled}`。支持状态必须区分文档、协议模拟器与真机。
- `GET /devices/{id}/connections`：`{items:[ConnectionView],asOf}`。ADMIN 可读取连接设置（含用户名及密钥存在标识），任何响应都不含明文或密文密钥。
- `POST /devices/{id}/connections/{slot}`：保存连接，ADMIN。slot=`snmp|redfish|ssh`；一台设备最多三个来源，分别固定映射 network/bmc/ssh。Body 见下方。
- `POST /devices/{id}/connections/{slot}/test`：`{revision}`；使用已保存配置只读探测，返回最新 ConnectionView。成功与失败均保留结果和时间，不以测试表示自动采集已启用。
- `POST /devices/{id}/connections/{slot}/collect`：`{revision}`；执行采集并发布已有指标/事件链路，返回最新 ConnectionView。
- `POST /devices/{id}/connections/{slot}/state`：`{revision,enabled}`；更新启停及版本。停用阻止后续调度，已发出的只读请求可能完成，但旧版本不能覆盖新状态。
- `GET /devices/{id}/collection`：所有读角色可读 `{items:[CollectionView],asOf}`，仅安全采集结果，不含连接用户名/密钥。
- `GET /devices/{id}/live`：带 Authorization 的 `text/event-stream`，事件 `collection`，正文等同 collection。浏览器 fetch 流带 Bearer，不将令牌写入 URL；限制并发、连接时长并支持取消。

## 保存连接

```json
{
  "revision": 0,
  "host": "192.0.2.10",
  "port": 161,
  "enabled": false,
  "intervalSeconds": 60,
  "timeoutMillis": 3000,
  "maxInterfaces": 128,
  "username": "monitor",
  "snmpVersion": "3",
  "securityLevel": "authPriv",
  "authProtocol": "SHA256",
  "privacyProtocol": "AES128",
  "contextName": "",
  "tlsMode": "SYSTEM",
  "certificateSha256": "",
  "sshProfile": null,
  "sshHostKeySha256": null,
  "secrets": {"community": null, "authPassword": "example-only", "privacyPassword": "example-only", "password": null}
}
```

密码示例仅说明结构。revision=0 用于首次创建；编辑提供当前 revision。secret 的 null/空值表示保留已有相同凭据类型的值，首次必需项不允许为空；变更 host、port、username、版本、安全算法、context、TLS 信任/证书指纹或 SSH 档案/主机密钥指纹时必须重新提供所需密钥，避免把旧密钥发送给另一个设备。secret 字段不接受掩码占位。协议不自动降级。支持 SNMP v2c 只读 community 及 v3 noAuthNoPriv/authNoPriv/authPriv；算法由驱动明确校验。Redfish 必须 HTTPS，SYSTEM 使用系统信任；PINNED 使用用户提供的 SHA-256 叶证书指纹校验，不提供跳过校验选项。SNMP、Redfish 和 SSH 账号按 slot 分开保存。非 SSH 配置的 sshProfile/sshHostKeySha256 归一化为 null；其余不活动协议字符串归一化为空串。身份比较仅使用当前模式有效字段，不因不活动字段的 null/空串差异要求重填。

`ConnectionView` 字段：`slot,protocol,revision,host,port,enabled,intervalSeconds,timeoutMillis,maxInterfaces,username,snmpVersion,securityLevel,authProtocol,privacyProtocol,contextName,tlsMode,certificateSha256,hasCommunity,hasAuthPassword,hasPrivacyPassword,hasPassword,status,lastAttemptAt,lastSuccessAt,nextPollAt,errorCode,errorMessage,lastReading,sshProfile,sshHostKeySha256`。

`CollectionView` 字段：`slot,protocol,revision,enabled,status,lastAttemptAt,lastSuccessAt,nextPollAt,errorCode,errorMessage,lastReading`。status=`NOT_TESTED|QUEUED|RUNNING|SUCCESS|PARTIAL|ERROR|DISABLED`。lastReading 沿用 `DeviceProtocol.Reading`：observedAt、identity、health、metrics、sensors、ports、capabilities、qualityFlags、facts。证据不足的设备身份保持 generic；读取成功的指标才进入能力清单。

Identity 字段：vendor/family/profileId/model/serialNumber/firmware/sysObjectId/sysName/description。
Sensor 字段：id/label/metric/unit/value/health/sourceRef。
Port 字段：key/name/macAddress/speedBps/adminStatus/operStatus/inOctets/outOctets/discontinuity/counterBits/sourceRef。

## SSH 固定只读档案

SSH 默认端口 22，username 必填且不含控制字符，密码写入 `secrets.password`。`sshProfile` 仅允许 `HUAWEI_IMANA`、`DELL_OS9`、`CISCO_IOS_XE`，不接受自定义命令。`sshHostKeySha256` 必填：OpenSSH `SHA256:` 加 43 字符无填充 Base64，必须规范编码且解码后恰为 32 字节（总长 50）；先核对主机密钥，再发送密码。不能自动接受未知 key、在失败后降低算法要求或用相同 key 自动合并设备。新 SSH 配置需密码；既有同身份配置可用 null 保留，host/port/username/profile/pin 任一变化需重填。

请求仍使用上方统一保存 DTO，SSH 的 snmpVersion/securityLevel/authProtocol/privacyProtocol/contextName/tlsMode/certificateSha256 可空且存为规范空串；传入 `sshProfile` 和 `sshHostKeySha256`。`ConnectionView.protocol` 为 `SSH`，安全 `CollectionView` 不包含这两项设置或用户名。三类连接的 revision 与密钥独立，test/collect/state 仍仅 ADMIN。

iMana 使用经审阅的固定交互式身份、健康与传感器命令；Dell OS9 和 Cisco IOS XE 使用固定身份及 ARP/LLDP/CDP 查询。具体品牌和固件匹配以读取证据为准。iMana 温度、风扇、功率、电压、电流分别保留单位；无值保持 null；未解码离散状态不解释成零。设备级温度仅取可用温度最大值，不把多个电源读数相加为整机功率。ARP/LLDP/CDP 的安全投影存于 `Reading.facts.neighborObservations` JSON 字符串，不保存任意原始命令内容。

## 候选发现与登记

精确 DTO、所有状态、限额和去重边界见[网络发现契约](NETWORK-DISCOVERY-API.md)。新增四个接口：

| 路径 | 请求 / 响应 | 权限 |
|---|---|---|
| `POST /discovery/runs` | `{sourceDeviceIds,cidr,siteId}` → RunResult | ADMIN / OPERATOR |
| `GET /discovery/candidates` | siteId/status/cursor/limit → Page<Candidate> | 三种读角色 |
| `POST /discovery/candidates/{id}/register` | `{revision,name,type}` → Candidate | ADMIN / OPERATOR |
| `POST /discovery/candidates/{id}/link` | `{revision,deviceId}` → Candidate | ADMIN / OPERATOR |

运行发现不发起扫描或协议请求，也不读取凭据。用户明确输入规范 IPv4 CIDR `/24`–`/32`，选择 1–8 个同站点来源；服务只处理已保存且少于 15 分钟的 SSH 邻居证据，每来源最多 256 条、每次最多 2048 条。管理员可先在来源设备接入页“立即采集”，之后回到发现页运行；OPERATOR 无需读取 ADMIN 设置。

相同组织/站点/IP 复用候选；每个候选最多 16 条证据。已登记唯一同地址资产显示 EXISTING；同 MAC 多地址只产生 POSSIBLE_DUPLICATE；身份冲突显示 CONFLICT，必须人工审核。登记新资产不会复制来源凭据或自动启用采集；关联已有资产不会改目标地址或合并历史。候选时间是来源观测时间，ARP age 和 TTL 独立展示，均不等于目标在线证明。旧 revision 返回 409，不盲重试；每实例 4 个操作和关联窗口 1024 行上限，超限 429；包括事务的 10 秒预算超限返回 503。

## 安全、调度和部署

凭据使用 AES-256-GCM，加密 AAD 绑定组织、设备及 slot；主密钥来自环境/Secret，数据库和备份不保存主密钥。缺少密钥时凭据保存不可用，不能降级明文。错误、审计和日志不包含凭据。组织和角色在服务端校验。

外连目标在每次读取前重新解析并与管理员配置的 CIDR 范围比对，阻止元数据、多播和未授权目标。驱动连接已核验地址，Redfish 相对链接限制到同一设备 `/redfish/v1` 树，禁止跨主机重定向。正常请求有时间、页数、成员数和响应体上限；一个失败设备不占用无限线程。

控制面负责配置及手动测试，独立 worker 扫描到期连接。数据库租约和 revision 防止多副本重复提交与旧配置结果覆盖。标准只读采集沿用 Kafka、VictoriaMetrics 和接口目录；失败不清空历史、不写零值、不声称设备离线。使用 SSE 提供浏览器实时展示，设备侧 SNMP/Redfish/SSH 不等同于浏览器 WebSocket。

## 验证证据口径

协议模拟器、外部 CLI 预检与平台内测试/采集分别记录。[六台真机验收](HARDWARE-VERIFICATION.md)已记录 Dell S6100-ON 和三台 Huawei iMana 的平台 SSH 成功、Cisco ASR1002-X 的 SSH 部分成功（未获得可识别 LLDP/CDP）、Inspur SA5212M5 的 Redfish 部分成功（兼容格式与缺失质量标记），以及六台 60 秒周期采集。平台读取成功不等于所有指标可用；本轮没有完成真机 SNMP 验收。具体格式和固件见[真机格式记录](HARDWARE-FORMAT-NOTES.md)；支持目录枚举仍保留族级 `SIMULATOR_TESTED_HARDWARE_PENDING`，不据具体型号给整族升级。
