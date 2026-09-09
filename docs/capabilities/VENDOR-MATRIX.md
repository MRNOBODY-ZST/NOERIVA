# 厂商与型号矩阵

当前没有 `DEVICE_VERIFIED` 厂商/型号组合。模拟命名或协议设计不能视为验证。初始厂商域全部保留，实际支持需记录型号、固件、协议、权限、采集覆盖和验收证据。

| 厂商/目标 | 范围 | 计划协议 | 状态 | 实机验证限制 |
|---|---|---|---|---|
| Cisco ASR1002-X / IOS XE | 路由、NAT、IPFIX、VPN、AAA | SNMPv3 / Syslog / IPFIX / approved APIs | DESIGNED / NONE / NOT_TESTED | 未核验具体 IOS XE、许可证与导出配置 |
| Cisco switching | 交换与邻居 | SNMPv3 / LLDP/CDP | DESIGNED / NONE / NOT_TESTED | 未核验型号/MIB/固件 |
| Dell switches / servers | 交换机、服务器、BMC | SNMPv3 / Redfish | DESIGNED / NONE / NOT_TESTED | 没有实机或安全捕获数据验收 |
| Lenovo switches / servers | 交换机、服务器、BMC | SNMPv3 / Redfish | DESIGNED / NONE / NOT_TESTED | 没有实机或安全捕获数据验收 |
| Generic Redfish BMC | 带外健康与硬件库存 | HTTPS / Redfish | DESIGNED / NONE / NOT_TESTED | 遵循协议不等于全部字段或厂商支持 |
| OpenWrt / Linux appliances | 路由与主机指标 | SNMPv3 / exporter / approved API | DESIGNED / NONE / NOT_TESTED | 没有已验证系统版本组合 |
| Linux hosts | 指标与主机审计 | node_exporter / OTel / auditd / journald | DESIGNED / NONE / NOT_TESTED | 未验证具体发行版/内核 |
| Windows hosts | 指标与安全审计 | Windows exporter / Event Log | DESIGNED / NONE / NOT_TESTED | 未验证具体 Windows 版本 |
| VMware / Hyper-V / other hypervisor | 虚拟化 | 未来获准的厂商 API | DESIGNED / NONE / NOT_TESTED | 初始供应商尚未选定 |
| Storage / UPS / PDU / wireless vendors | 存储、供电、无线 | 协议和厂商适配器 | DESIGNED / NONE / NOT_TESTED | 每个型号/固件/协议须独立验证 |
