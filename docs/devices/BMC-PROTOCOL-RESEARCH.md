# BMC 协议研究与实现边界

研究日期：2026-09-06。范围是 Dell iDRAC、Huawei iBMC/iMana、Inspur 与 H3C 服务器 HDM。依据为官方资料及 DMTF schema；**没有连接上述厂商真机**。本文中的服务器名、地址、传感器读数与测试证书均为 synthetic。模拟协议测试只能证明当前适配逻辑，不能替代型号、固件、权限组合的真机认证。

## 1. 接入结论

优先实现通用 Redfish，再按有版本证据的 OEM schema 扩展。SNMP 身份识别和 Redfish 读取使用独立凭据：SNMPv3 的认证密钥、隐私密钥不是 Redfish 密码。Huawei 的用户接口开关中 SNMP 与 Redfish 分别启用，因此同名用户也不能推定两个接口都可访问。[Huawei 用户接口设置](https://info.support.huawei.com/hedex/api/pages/EDOC1000163559/YEI0812D/18/resources/en-us_cliref_0000002026698281.html)

自动识别的起点为 `GET https://{host}:{port}/redfish/v1`。读取 `RedfishVersion`、`@odata.type`，沿 `Systems`、`Managers`、`Chassis` 中的 `@odata.id` 发现集合，再读取 `Members[].@odata.id` 和 `Members@odata.nextLink`。不能把 Dell 的 `System.Embedded.1`、Huawei 的 `1`、H3C 某版文档的 `1` 当成跨厂商固定 ID。根版本与每个资源的 schema 版本分别保存；字段存在性比厂商名更能确定实际能力。[DMTF Redfish 1.24.0 规范](https://www.dmtf.org/sites/default/files/standards/documents/DSP0266_1.24.0.html)

`404` 不等于设备故障：根不存在表示当前目标未提供可识别的 Redfish；已声明的子资源 `403/404` 表示权限或实现不完整。保留 `PARTIAL` 和具体质量标识，不补零、不声称所有传感器正常。没有通过协议读取时保持未验证身份。未经官方 MIB 验证的 OEM OID 不写进适配器。

## 2. 标准资源与精确字段

下表的 URI 模板用于理解，实际请求使用设备返回的链接。属性可因 schema 版本、硬件、许可证和账号权限缺失；`null` 是不可用，不是数值 0。

| 资源 | 常见 HTTP 资源 | 标准 JSON 字段与归一化 |
|---|---|---|
| 系统身份 | `/redfish/v1/Systems/{id}` | `Manufacturer`、`Model`、`SerialNumber`、`HostName`、`BIOSVersion`、`PowerState`；`Status.HealthRollup`/`Status.Health`；`ProcessorSummary.Count` 与 `MemorySummary.TotalSystemMemoryGiB` 是库存事实，不是利用率 |
| BMC 身份 | `/redfish/v1/Managers/{id}` | `ManagerType`、`Manufacturer`、`Model`、`FirmwareVersion`；BMC 固件与主机 BIOS 分开保存 |
| 旧温度 | `/redfish/v1/Chassis/{id}/Thermal` | `Temperatures[].ReadingCelsius` → `temperature`，单位 `Cel`；保留数组位置和 `MemberId`、`Name`、`Status` |
| 旧风扇 | 同上 | `Fans[].Reading` + `ReadingUnits`；`RPM` 保持转速，`Percent` 归一为 `%`，不能相互换算；旧 `FanName` 可作为 `Name` 的兼容回退 |
| 旧功率/电压 | `/redfish/v1/Chassis/{id}/Power` | `PowerControl[].PowerConsumedWatts`；`Voltages[].ReadingVolts`；`PowerSupplies[].PowerInputWatts`、`PowerOutputWatts`，较旧固件可能只有 `LastPowerOutputWatts`，名称及来源需保留 |
| 新通用传感器 | `/redfish/v1/Chassis/{id}/Sensors/{sensorId}` | `Reading`、`ReadingUnits`、`ReadingType`、`Status`；仅明确匹配的温度/功率/电压/电流/转速/百分比单位进入指标 |
| 新风扇 | `/redfish/v1/Chassis/{id}/ThermalSubsystem/Fans/{fanId}` | `SpeedPercent.Reading` 与可选 `SecondarySpeedPercent.Reading`，单位 `%`；通用 Fan schema 没有可推定的 `SpeedRPM` 字段 |
| 新温度集合 | `/redfish/v1/Chassis/{id}/ThermalSubsystem/ThermalMetrics` | `TemperatureReadingsCelsius[].Reading`；`PowerWatts.Reading` 是散热子系统功率，不是整机功率 |
| 新 PSU | `/redfish/v1/Chassis/{id}/PowerSubsystem/PowerSupplies/{id}/Metrics` | `InputPowerWatts.Reading`、`OutputPowerWatts.Reading`、`InputCurrentAmps.Reading`、`InputVoltage.Reading` |

字段依据：[ComputerSystem schema](https://redfish.dmtf.org/schemas/v1/ComputerSystem.json)、[Manager schema](https://redfish.dmtf.org/schemas/v1/Manager.json)、[Thermal v1.7.3](https://redfish.dmtf.org/schemas/v1/Thermal.v1_7_3.json)、[Power v1.7.3](https://redfish.dmtf.org/schemas/v1/Power.v1_7_3.json)、[Sensor schema](https://redfish.dmtf.org/schemas/v1/Sensor.json)、[Fan v1.5.0](https://redfish.dmtf.org/schemas/v1/Fan.v1_5_0.json)、[ThermalMetrics v1.3.3](https://redfish.dmtf.org/schemas/v1/ThermalMetrics.v1_3_3.json)、[PowerSupplyMetrics v1.2.0](https://redfish.dmtf.org/schemas/v1/PowerSupplyMetrics.v1_2_0.json)。

DMTF 已将旧 `Thermal`、`Power` schema 标为弃用，但存量 BMC 仍可能只返回这两类资源；不能因新版标准弃用就停止兼容。新实现也不能假设旧资源一定存在。[Thermal schema 状态](https://redfish.dmtf.org/schemas/v1/Thermal.json)、[Power schema 状态](https://redfish.dmtf.org/schemas/v1/Power.json)

CPU/内存使用率、磁盘繁忙度、业务网络流量不保证由标准 BMC 提供。读取到库存总量只显示库存；后续有确切 OEM/TelemetryService 映射及固件 fixture 后才能增加对应利用率能力。多个系统共用一个 BMC 时，当前身份展示采用首个系统并标记 `MULTIPLE_SYSTEMS_FIRST_IDENTITY`，传感器仍保留完整资源路径；不能把整个机箱的值冒充每台主机的值。

## 3. 厂商差异与资料证据

### Dell iDRAC

Dell 的 iDRAC9 Redfish 文档明确提供 ComputerSystem、Manager、Power、Thermal 和事件服务。iDRAC7/8 也有对应固件系列 Redfish 指南，因此不能简单按代际断定全部支持或全部不支持；旧设备先探测根与实际资源。已查文档证明相应版本存在接口，不证明本系统已覆盖该代所有固件。[iDRAC9 ComputerSystem](https://www.dell.com/support/manuals/en-us/idrac9-lifecycle-controller-v3.1-series/idrac_3.18.18.18_redfishapiguide/computersystem?guid=guid-071f0516-1b31-4a4b-90ab-4f9bfcc5db4a&lang=en-us)、[iDRAC7/8 2.40.40.40 指南示例](https://www.dell.com/support/manuals/en-us/idrac7-8-lifecycle-controller-v2.40.40.40/redfish%202.40.40.40/examples?guid=guid-552c8d22-6273-47c0-a6bd-7c751c4e7c7d&lang=en-us)

识别使用系统 `Manufacturer` 与管理器 `Model` 的组合：Dell 厂商证据 + iDRAC 模型证据才能把 family 归为 iDRAC；只有厂商证据时 family 仍为 Redfish。DellAttributes、DellManager 等 OEM 资源需要针对对应 firmware/schema 单独实现，本轮不遍历任意 `Oem` 对象，也不推断数字字段含义。[iDRAC9 API 指南 PDF](https://dl.dell.com/topicspdf/idrac9-lifecycle-controller-v33-series_api-guide_en-us.pdf)

文档明确的推送操作为 `GET /redfish/v1/EventService`、`POST /redfish/v1/EventService/Subscriptions`、`DELETE /redfish/v1/EventService/Subscriptions/{id}`。创建订阅会改变设备配置并需要可达的回调地址，当前只读采集不自动注册，也不 PATCH `ServiceEnabled`。[Dell Eventing operations](https://www.dell.com/support/manuals/en-us/idrac9-lifecycle-controller-v3.1-series/idrac_3.18.18.18_redfishapiguide/eventing-operations?guid=guid-728b4952-ab05-470d-88db-f5212c6bffba&lang=en-us)

### Huawei iBMC 与 iMana

Huawei 官方 iBMC Cmdlets 使用 Redfish 管理多个产品系列，是通用资源路线的官方实现证据；支持列表不能推广到全部 Huawei 老服务器。[Huawei 官方 iBMC Cmdlets](https://github.com/Huawei/Huawei-iBMC-Cmdlets)

官方 iBMC 白皮书列出 Redfish 1.0.2 和示例 `/redfish/v1/Systems/1/Processors/1`，说明旧版标准资源仍是现实兼容目标。[iBMC300 白皮书](https://e.huawei.com/marketingcloud/pep/asset/20000001/Material/9eb5b6bc80e64703b63bda225026151f/M3T1A590N1101906274317148391/%E6%9C%8D%E5%8A%A1%E5%99%A8%20iBMC%20%E6%99%BA%E8%83%BD%E7%AE%A1%E7%90%86%E7%B3%BB%E7%BB%9F%20%E7%99%BD%E7%9A%AE%E4%B9%A6-iBMC300.pdf)

系统 ID 会随机架、刀片等产品变化；部分资源还限定带内客户端权限。例如官方 `Hostlog` 接口使用 `/redfish/v1/Systems/{system_id}/LogServices/Hostlog`，有专用带内访问限制，普通远端用户可得到 404。不能把任意厂家内部日志 URI 当成所有账号可读资源。[Huawei Hostlog 接口与限制](https://support.huawei.com/enterprise/zh/doc/EDOC1100372764/18bfdbec)

本次没有取得足够官方、公开且能定位版本的 iMana Redfish API 证据，因此 **不把 iMana 自动当作 iBMC/Redfish**。没有根服务时标记 Redfish 未提供；SNMP/IPMI 是否可用仍须依据该型号官方手册与实际探测。不得自动降低 SNMP 安全级别、改用明文 HTTP 或猜 OEM OID。Huawei OEM 字段的大小写、路径与语义需要指定固件样本；本轮只采标准字段。Huawei EventService 订阅的型号/固件覆盖尚未完成独立验证，不列为已实现事件推送能力。

### Inspur BMC

Inspur 官方 BMC 用户手册列出 Redfish，并提示详细 API 操作参考单独的 Redfish 用户手册；另一份官方系统管理资料列出 Redfish 1.0.2、SNMP 与 IPMI。因此可实现标准 Redfish 探测，但不能凭这一描述硬编码 `/Systems/Self` 或宣称 OEM CPU/内存指标可用。[Inspur BMC 用户手册 V2.7](https://en.inspur.com/eportal/fileDir/active_download/platformBookZh/6680/Inspur%C2%A0Server%C2%A0BMC%C2%A0User%C2%A0Manual%C2%A0V2.7.pdf)、[Inspur 系统管理资料](https://www.inspur.com/eportal/fileDir/en/resource/cms/2023/01/2023011722552671717.pdf)

本轮 profile 以标准 `Manufacturer` 为厂商证据，OEM 扩展保持未验证。资料中的 SNMP trap、邮件、syslog 告警不等于已证明 Redfish EventService 推送；必须按固件实际发现并另行验证订阅。没有对应固件 MIB/API 文件时保留通用适配能力，不填猜测的 OID、机型 ID 或私有 JSON 字段。

### H3C HDM

官方 HDM Redfish API Reference 6W101 以 Redfish 1.5.0 / schema 2017.3 为基础，描述 HDM 3.41/6.06 以后相应系列。文档中的 Systems ID 为 `1` 是该版本约定，运行时仍从集合发现。部分旧示例的 `Members` 使用字符串链接数组，本驱动兼容这类链接并加 `LEGACY_STRING_MEMBER_LINK` 标识，避免静默假设现代结构。[H3C HDM Redfish API Reference](https://www.h3c.com/en/Support/Resource_Center/EN/Home/Public/00-Public/Technical_Documents/Developer_Documents/API_References/H3C_HDM_Re-13774/202401/2017521_294551_0.htm)

官方事件日志示例验证了 Basic 认证、LogServices 集合与日志读取；另一份配置示例包括订阅事件日志流程。当前驱动只实现库存和传感器读取，不把文档存在的订阅能力显示成已接入事件。[H3C HDM 事件日志读取示例](https://www.h3c.com/en/d_202312/2005699_294551_0.htm)、[H3C 事件日志与订阅示例](https://www.h3c.com/en/d_202505/2450512_294551_0.htm)

OEM/MIB 应取与固件匹配的官方发布包。例如 H3C HDM 发布资源同时提供 API 参考和 `mib.zip`，不能由其他型号的企业 OID 推断该型号传感器表。[H3C 官方 HDM 发布附件](https://wwwsg.h3c.com/en/BizPortal/DownLoadAccessory/en_AccessoryDetail.aspx?ID=ff6627bc-2f8c-44fa-a496-1c33abfeabfa)

## 4. 身份认证、TLS 与事件通道

Redfish 标准的会话方式是向发现的 Sessions 集合 POST `{UserName, Password}`，保存响应的 `X-Auth-Token` 与 `Location`，后续带 token，请求结束删除自己创建的 session；必须控制会话数量并避免每个传感器创建会话。当前 `RedfishDriver` 选择 HTTPS Basic，只有 TLS 校验成功才发送凭据，不创建服务器会话，不调用 Actions，也不自动降级。需要 session-only 的固件应明确显示认证不兼容，再增加有测试的会话实现。[DMTF 认证与会话规范](https://www.dmtf.org/sites/default/files/standards/documents/DSP0266_1.24.0.html)

`SYSTEM` 使用系统信任链及原始 host 的 HTTPS 主机名验证；TCP 连接强制指向上游 TargetPolicy 核验过的 address，消除验证后再次 DNS 解析改变目标的问题。`PINNED` 校验用户录入的叶证书 SHA-256 和证书有效期，是显式指纹信任模式，不是跳过验证。两种模式仅启用 TLS 1.2/1.3。设备返回的所有请求链接必须同 origin、位于 `/redfish/v1`，禁止重定向、路径穿越、用户信息、片段和跨主机链接。

标准事件可采用 EventService 推送或可选 SSE；是否提供取决于服务实际资源与版本。最新规范的可选 WebSocket inbound 也不表示设备提供统一 JSON 遥测：相关属性的 schema 定义它承载什么内容，可能是控制台流。产品浏览器实时展示由 NOERIVA 的认证 SSE 提供，不声称等于设备侧 WebSocket。[DMTF 事件、SSE 与 WebSocket 规范](https://www.dmtf.org/sites/default/files/standards/documents/DSP0266_1.24.0.html)

其他 REST/WebSocket 来源应采用独立 adapter，明确 method/URI/认证、JSON Pointer、单位、时间戳、子协议、帧大小与重连策略。不能把任意远程 JSON 当作已验证指标，不能执行用户提供的 JavaScript 转换，也不能复用 SNMP 密钥猜测 HTTP 认证。本轮没有实现任意 REST/WebSocket 插件执行器。

## 5. Java 25 / WebFlux 实现契约

已与控制面协调的边界是 `DeviceProtocol.read(Target, Secrets) -> Mono<Reading>`。协议驱动不持久化凭据，不写设备配置，不决定组织或设备归属；控制面负责凭据保险库、目标 CIDR 策略、周期调度租约、修订检查、Kafka/指标发布及浏览器实时结果。

`RedfishDriver` 使用专用有界 Reactor 调度器运行固定地址 JSSE HTTPS 读取，避免阻塞 WebFlux 事件循环。每次扫描最多 80 GET、128 个传感器、512 个调度资源、每个集合/数组最多 256 个元素、单响应 2 MiB、总读取 25 秒；还限制 JSON 深度、头大小和单次 socket 超时。取消或超时关闭活动 socket。以上数值为本产品预算，不是厂商极限。

指标只来自白名单标准字段；`sourceRef` 保留 URI 与 JSON Pointer。`temperature_celsius` 摘要为本次成功采集温度传感器的最大值，facts 保存 `temperatureSummary` 和 `temperatureSource`，不是平均值或特指进风口。`power_watts` 只在系统与机箱范围唯一、PowerControl/EnvironmentMetrics 整体功率候选唯一且发现过程完整时发布；多个功率域、重复候选或不完整范围均不生成整机功率摘要。PSU 输入/输出与散热功率只保留独立传感器，不相加。标准 `OK` 映射为平台 `HEALTHY`，缺失健康状态保持 `UNKNOWN`。系统 CPU 数量、内存容量保存为 facts，利用率没有证据时保持缺失。设备报错、权限不足、未知单位、成员/请求预算耗尽均提供质量标识；失败不覆盖历史为零。身份的 `profileId` 表示当前识别路由，不表示该厂商所有 OEM 功能均已验证。

## 6. Synthetic 样例与验证范围

以下片段是模拟返回，地址可使用 RFC 5737 文档网段；不包含生产密钥。

```json
{
  "@odata.id": "/redfish/v1/Chassis/synthetic-enclosure/Sensors/inlet",
  "@odata.type": "#Sensor.v1_0_0.Sensor",
  "Id": "inlet",
  "Name": "SYNTHETIC Inlet",
  "ReadingType": "Temperature",
  "ReadingUnits": "Cel",
  "Reading": 23.5,
  "Status": {"Health": "OK", "State": "Enabled"}
}
```

自动化测试使用本地 HTTPS 服务与明确标记的测试证书，覆盖五种身份、旧 Thermal/Power、新 Sensor/Subsystem、分页、空读数与单位、TLS 主机名、证书指纹/有效期、认证失败、同源链接/编码穿越、重定向和响应/请求/传感器预算。最终通过项以 Maven 实际报告为准；没有真机支持结论。后续真机验收应按每个型号/固件记录只读角色、Redfish 根版本、资源 schema、脱敏响应、支持能力、不可用原因与时钟偏差；新增 OEM 映射先有官方版本资料和脱敏 fixture，再写驱动。

验证记录：`RedfishDriverTest` 的首次 17 项在空驱动阶段失败；实现后通过。正常取消的 dropped-error 与非 literal address 两项分别红→绿；摘要口径、健康枚举和空身份资源共五项也先观察到失败，再修复。当前专项结果应查看 `services/noeriva-control/target/surefire-reports/TEST-io.noeriva.control.devices.RedfishDriverTest.xml`。

本地常驻模拟器入口为测试类 `RedfishDriverTest.main`，必须提供环境变量 `NOERIVA_MOCK_REDFISH_PASSWORD`。它输出 `SYNTHETIC_ONLY`、端口、SHA-256 指纹和测试用户名，不输出密码；临时绑定供本机 Docker 验证，完成后结束该进程。测试 PKCS12 文件仅含明确标记的 synthetic 证书和密钥，不得用于生产 TLS。
