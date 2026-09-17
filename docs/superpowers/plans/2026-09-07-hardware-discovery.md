# 真机只读接入与邻居发现实施计划

**目标：** 接通用户指定的六台设备，在前端提供旧 iMana 的 SSH 采集，以及基于网关邻居证据的候选发现、重复提示和登记/关联流程。

**结构：** 现有设备连接增加独立 `ssh` slot，沿用加密凭据、目标 CIDR、数据库租约、周期 worker 和采集发布链路。发现模块只消费已授权来源设备返回的 ARP/LLDP 观测；候选与资产独立，审核后登记或关联，不修改设备配置、不合并删除历史。

## 执行约束

- 真机范围是 `192.168.254.1/.3/.10/.11/.12/.20`；其他地址只作为来源表中的观测候选，不主动探测。用户写的 `168.4.*` 已请求准确 CIDR；可以先展示来源中的 `192.168.4.*` 证据。
- SSH 密码在核验固定目标的 SHA-256 host key 后发送；固定只读命令档案，不接受任意命令，不静默降级。首次记录的 host key / TLS 证书指纹明确为本次首次连接观测，不声称带外验证。
- SSH host key、证书、IP、MAC、名称与企业 OID 不是设备唯一身份。真实 iMana 共享 SSH key，Inspur 的同 MAC 对应多个地址，均不可直接物理合并。
- 非法或过期观测不进入可自动登记结果；空值与错误不补零。限制连接、命令数、输出大小、候选数、时间与线程队列。
- 保留现有工作区及数据，不提交整个未跟踪目录。密码只进入被忽略的本地任务文件和系统加密凭据，不写报告或测试 fixture。

## 任务与职责

1. **真机预检与基线（主任务）**：只读 TCP/SSH/HTTPS、型号固件与实际 CLI/JSON 结构；证据保存在 `.local/hardware`，脱敏摘要单独记录。先按准确管理地址核查已有资产，避免重复登记。
2. **SSH 协议（backend_workbench）**：新增驱动与解析器，扩展 `DeviceProtocol`、`DeviceAccessModels`、`DeviceAccessService/Store` 和 Worker；`sshProfile` 为 `HUAWEI_IMANA/DELL_OS9/CISCO_IOS_XE`，`sshHostKeySha256` 使用 OpenSSH 格式；新增字段保持旧连接兼容。真实本地 SSH server 测试 host-key 错误先于认证、错误密码、交互提示符、分页、预算、取消及解析。
3. **候选与去重（inventory_queries）**：新增 discovery 模块和 V7 表。`POST /discovery/runs` 读取最多八个同站点已保存来源，CIDR 过滤；列表分页；`register` 原子登记，`link` 只关联。SQL 验证组织/站点隔离、重复运行幂等、同 MAC 多地址、过期数据、矛盾线索、并发登记与角色约束。
4. **前端（frontend_clarity）**：第三种 SSH 连接表单、密码保留/重填规则、固定档案与指纹；资产目录“发现设备”入口，来源选择、CIDR、候选证据、重复疑点、登记/关联。关键行为测试、类型与构建通过。
5. **整体验证（主任务）**：合并 API 权限和 schema，构建并更新 Compose；通过系统采集六台真机，Computer Use 检查真实读数、错误、周期更新及发现审核。前后端回归、Helm 检查；记录已验收型号和剩余 SNMP 凭据依赖，不把 SSH 可登录写成 SNMP 已验收。

共享接口：SSH `Reading.facts.neighborObservations` 为 JSON 数组字符串；条目含 `address, mac, source, interfaceName, vlan, name, chassisId, chassisSubtype, portId, ttlSeconds, ageMinutes`，缺失字段为空，来源时间取 Reading.observedAt。发现精确 API 单独维护在 `docs/devices/NETWORK-DISCOVERY-API.md`。
