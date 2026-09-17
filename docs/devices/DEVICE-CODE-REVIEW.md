# 设备采集实现独立审查

审查日期：2026-09-06。审查者为独立后端子任务，主要实现者为根任务。范围：DeviceAccessStore / Service / Controller、DeviceCounters、DevicePublisher、TargetPolicy、CredentialVault、DeviceCollectionScheduler、V6 迁移、认证/请求限流及部署配置。最终有界复审已完成：在本轮审查范围内未发现新的阻断问题，R1–R5 与 Redfish 互审问题均已修复并取得对应测试证据。下文保留原始问题和复现步骤，便于追溯。

本次未运行共享 Maven，未修改这些核心实现文件。使用只读源码检查与 `.local/device-review/CounterScopeRepro.java` 独立 Java 程序检查跨层语义；另由 SNMP 子任务互审 Redfish，Redfish 驱动自己的修复只写入其所属文件。

## 最终验证记录

复审直接读取最新 Surefire XML 并核对相应实现及测试断言。当前报告集合共 **181 项，0 failures / 0 errors / 0 skipped**；这是完整 verify 后合并各专项重跑的最终报告集合，不是声称一次命令执行了全部 181 项。审查者本轮没有重复运行共享 Maven。

| 证据 | 结果 | 对应审查结论 |
|---|---:|---|
| `DeviceCountersTest` | 5/5 | R1 缺失断点、重启、32 位歧义及历史区间拒绝 |
| `DeviceAccessIntegrationTest` | 9/9，真实 MySQL | R2 指标接管、R3 HTTP 消费者取消、R4 完整/部分接口目录，以及租户、密文与 lease/CAS |
| `DeviceSafetyTest` | 6/6 | R5 AWS IPv6 metadata 与新增 Google 精确地址拒绝；邻近合法地址保留 |
| `RedfishDriverTest` | 30/30，真实本地 HTTPS | 不可用功率摘要回归、TLS/认证/路径/预算/归一化 |
| `SnmpDriverTest` | 17/17，真实本地 UDP/USM | 协议隔离、认证、单位、边界、来源与取消 |

测试源码与 XML 分别位于 `services/noeriva-control/src/test/java/io/noeriva/control/devices/` 和 `services/noeriva-control/target/surefire-reports/`。其余套件位于 query/control 的 Surefire 报告目录。

已读取 [设备运行验证记录](/Users/hades/NOERIVA/docs/implementation/device-runtime-verification.json)：真实 UDP/HTTPS 传输使用 synthetic fixture；VM 已保存 SNMP CPU/内存/温度和 Redfish 功率/温度，ClickHouse 返回接口热力图；160 次、16 并发本地状态读取 0 错误，p95 83.29ms。该文件明确 `realHardwareTested=false`，性能数字只覆盖这次本地样本，不是生产容量认证。前端 57 项和 Computer Use 流程由根任务执行，本审查没有重复接管浏览器。

## 已关闭的问题

### R1 / P1：原始计数器发布缺少与当前速率相同的有效性约束

位置：[DevicePublisher.java](/Users/hades/NOERIVA/services/noeriva-control/src/main/java/io/noeriva/control/devices/DevicePublisher.java)、[DeviceCounters.java](/Users/hades/NOERIVA/services/noeriva-control/src/main/java/io/noeriva/control/devices/DeviceCounters.java)、[CounterRollup.java](/Users/hades/NOERIVA/services/noeriva-query/src/main/java/io/noeriva/query/CounterRollup.java)。

复现：两个相隔 60 秒的 synthetic 样本，计数 1000→61000、接口速率 1Gbps。第一种缺少 `ifCounterDiscontinuityTime`，第二种是 32 位计数器。当前速率拒绝两者并返回 `COUNTER_RATE_INCOMPLETE`，但原发布逻辑把 raw 两点放在相同 source epoch；CounterRollup 只知道 epoch、间隔和差值，给出 60 秒有效时长与 60000 有效字节。32 位在这一时间/带宽组合中可能发生完整回绕，正差仍不能证明连续性。

独立程序的原始输出：

```text
bits=64 discontinuity=null currentMetrics={} flags=[COUNTER_RATE_INCOMPLETE] rollupValidSeconds=60.0 rollupDelta=60000
bits=32 discontinuity=0 currentMetrics={} flags=[COUNTER_RATE_INCOMPLETE] rollupValidSeconds=60.0 rollupDelta=60000
```

**已修复并验证。** `DevicePublisher.rawEpoch` 只对 64 位、存在 discontinuity 且有 uptime 或 engine 证据的计数器维持连续 epoch，其余使用本次 lease 隔离；当前速率也要求连续性证据。`rawSamplesWithoutA64BitContinuityProofNeverBecomeValidHistoricalIntervals` 已通过：缺少证据时历史有效时长为 0，并标记 SOURCE_RESTART；32 位样本的 epoch 分离。上述输出仅记录旧实现缺陷。

### R2 / P1：指标 first-writer 绑定无法在停用或改配置后接管

位置：[DeviceAccessStore.java](/Users/hades/NOERIVA/services/noeriva-control/src/main/java/io/noeriva/control/devices/DeviceAccessStore.java) 的 `metricOwnership`、`state` 与 `save`。

复现步骤：SNMP 首先认领 `temperature_celsius` → 停用 SNMP → Redfish 成功读取温度。原 `INSERT IGNORE` 永久保留 SNMP slot 所有权，Redfish 的 `lastReading.metrics` 有值，而发布器过滤它，VM/current 不再更新。旧 API 没有重新绑定入口，配置保存也未释放绑定。

**已修复并验证。** `state(false)` 与成功 `save` 在事务中释放该 slot 绑定，运行中的同连接仍受 lease/CAS 保护。真实 MySQL 测试 `metricBindingsAvoidTwoProtocolSourcesOverwritingTheSameNamedSeries` 已通过：原来源认领温度时另一来源只取得功率；停用原来源后，另一来源可同时取得温度与功率。并发保存与过期 lease 写回保护也在同套件通过。

### R3 / P2：消费者取消导致手动测试永久显示 RUNNING

位置：[DeviceAccessService.java](/Users/hades/NOERIVA/services/noeriva/control/devices/DeviceAccessService.java) 的 `poll`。

复现步骤：disabled 连接手动测试取得 lease 后，浏览器离开或取消订阅。Reactive cancellation 不进入 `onErrorResume`，因此 `finish` 不执行；90 秒 lease 到期后数据库仍为 RUNNING，disabled 连接不会进入 due 队列，状态不会自行修复。

**已修复并验证。** 已开始的有界 poll 通过缓存 Mono 独立于 HTTP 消费者完成，保留 65 秒总预算。真实 MySQL 回归 `navigatingAwayDoesNotLeaveAStartedManualReadPermanentlyRunning` 在取消 HTTP 消费者后提供设备响应，验证状态最终为 SUCCESS。驱动自身显式取消的 HTTPS/UDP 测试也通过；lease 过期后的旧任务写回被数据库测试拒绝。这里不把 lease 测试扩大为完整工作器故障演练。

### R4 / P2：消失接口保留旧 UP 状态而无观测时间

位置：[DeviceAccessStore.java](/Users/hades/NOERIVA/services/noeriva-control/src/main/java/io/noeriva/control/devices/DeviceAccessStore.java) 的 `ports`。

复现步骤：首轮完整目录为 eth0 + eth1，二者 UP；设备随后删除 eth1；下一轮完整目录仅 eth0。原逻辑只 UPSERT 当前接口，eth1 的旧 payload 持续为 UP，查询目录无法判断这个状态已经不再被观测。

**已修复并验证，采用完整快照退休、部分结果保留历史的保守策略。** 只有存在 `interface-counters`、本轮接口非空且没有质量缺失/上限标识时，事务把同来源缺席接口标为 `NOT_PRESENT`，保留历史记录；partial 保留最后已知库存。真实 MySQL 回归 `completeInterfaceSnapshotMarksMissingPortsWhilePartialReadsPreserveLastKnownInventory` 已验证两种情况：部分读取不移除 eth1；完整读取把它标为 NOT_PRESENT。当前策略对空集和任何 partial 保守保留历史，不能把保留行解释为本轮已重新观测。

### R5 / P2：默认 IPv6 私网范围包含 AWS 元数据端点

位置：[TargetPolicy.java](/Users/hades/NOERIVA/services/noeriva-control/src/main/java/io/noeriva/control/devices/TargetPolicy.java)。

AWS 官方明确 IPv6 IMDS 地址是 `fd00:ec2::254`。[AWS 访问实例元数据](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html)

该地址不是 link-local，且落在默认 `fc00::/7` 中。独立程序仅调用 `validateResolved`，没有访问实际端点，输出 `metadata target accepted=fd00:ec2:0:0:0:0:0:254`。因此“云 metadata 始终拒绝”的目标边界没有完全实现。当前协议只支持 SNMP 和严格的 HTTPS Redfish 树，审查没有证明可通过它们取得元数据内容，不应夸大为已验证的信息泄露。

**已修复并验证。** TargetPolicy 显式拒绝 `fd00:ec2::/32`，对应默认私网和宽 CIDR 回归通过。根任务另外增加 `fd20:ce::254` 精确地址拒绝；`googleMetadataIsDeniedWithoutBlockingOtherPrivateIpv6Devices` 验证宽/私网配置均拒绝该地址，邻近 `fd20:ce::255` 仍可通过允许网段策略。最新 DeviceSafetyTest 6/6 通过；检查没有访问真实 metadata 服务。

## Redfish 互审闭环

SNMP 子任务发现 PowerControl 或 EnvironmentMetrics 的功率子对象被 `Status.State=Disabled/Absent` 排除出 sensors 后，旧 `powerCandidate` 仍接收该数字。因此单系统单机箱仍可能发布不可用的 `power_watts`。

独立 Java 复现原结果为 `power_watts=500` 并触发断言失败；最小修复改为候选只能来自已通过数值/状态/上限检查的同一 sensor source。将新类单独编译到 `.local/device-review/classes`，未触碰共享 target，同一复现通过且摘要只剩温度。**已修复并验证。** 新增的 `unavailableLegacyPowerControlCannotBecomeWholeDeviceSummary` 与 `unavailableEnvironmentPowerCannotBecomeWholeDeviceSummary` 均已通过；最新 RedfishDriverTest 为 30/30，0 failures/errors/skips。

## 未发现明确绕过的边界

- Vault 使用随机 12 字节 nonce 的 AES-256-GCM，AAD 绑定组织/设备/slot；存储 DTO 与 API view 分离，密文/明文未出现在响应 DTO。
- 连接管理路由与 service 双重要求 ADMIN；用户可见 collection 不含设置用户名和密钥；组织由已认证 principal 取值，数据库绑定组织与设备。
- 配置 CAS、lease token 与 revision 联合检查阻止同连接重复采集与已过期任务写回连接状态。跨外部系统发布仍非原子事务，错误明确提示可能部分写入；不能把连接 ERROR 理解为所有存储都回滚。
- TargetPolicy 检查全部 DNS 返回值与 CIDR，拒绝 link-local、multicast、wildcard 等目标；R5 对已识别的 AWS 保留段及 Google 精确地址增加拒绝，已通过专项测试；不扩大声称为所有未来云平台地址的完整清单。协议驱动连接核验后的 literal 地址，不再二次 DNS。当前 Redfish SYSTEM 校验原始 host；PINNED 校验叶指纹与有效期。
- 请求/传感器/变量/JSON/TLS/时间预算有明确上限；设备读取不运行在 WebFlux 事件循环；SSE 总并发和连接时长有界。
- Helm 为控制和工作器配置设备 egress 入口与凭据 Secret 引用；默认空设备 egress 需运维显式配置后才可访问硬件，这一部署依赖应保留在说明中。

“未发现”仅限当前检查路径，不代表渗透测试、全部故障注入或真机兼容性验收。真机、Kubernetes 集群和最终端到端结果应由对应实际执行报告提供证据。
