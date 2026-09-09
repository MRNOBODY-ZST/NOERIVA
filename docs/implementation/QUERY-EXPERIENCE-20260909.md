# 查询体验修复（2026-09-09）

设备默认七日热力图使用 `GET /api/v1/devices/{id}/bandwidth/heatmap`，一次只读取授权设备的命名收/发总带宽 gauge，最多 2000 个均匀查询点，按当地时间划分 168 格。原单接口计数 rollup API 保留。响应 `interfaceId=__device__`、`statistic=sample_mean`，单元格为采样均值，coverage 为查询采样点覆盖，不能等同计数器有效持续时长。已经由采集器汇总的设备指标不会在查询层再次相加；逻辑/物理接口可能重叠，随响应标注 `INTERFACE_OVERLAP_POSSIBLE`。缺失仍为 null，DST 缺失/重复小时保留真实 UTC 区间。

Application 默认图读取最新一个真实采样批次，保留原历史/筛选功能。图表只采用验证过的 Counter64 差分速率；设备自报速率独立列出，原始累计字节不参与速率和。详见 [应用 API](../devices/APPLICATION-MONITORING-API.md)。

NAT 默认全保留历史、最近到最早，游标固定查询截止时间，仍可指定范围。新增按接收顺序排列的轻量索引及物化视图，先限定 128 候选再验证原表 earliest receipt，避免取消日期上限后变成全表扫描；超预算显式失败。部署必须完成新索引对现有历史的分批回填，详见 [NAT API](../devices/NAT-AUDIT-API.md)。

验证：noeriva-query 全模块 46 条测试（45 通过、1 原有可选跳过）；本轮最终 targeted 7 类 23 条全部通过，包括设备鉴权/汇总、NAT 7 条实际 ClickHouse 26.3.32.14 回归、应用历史 5 条与速率汇总 2 条。NAT 性能回归包含 200,000 条完整合成记录、十天跨度、出口时间完全打乱，验证 1 小时、24 小时、十天及全历史首屏/游标，并从 query_log 断言 receipt 候选阶段扫描小于 100,000 行，未读完整 200,000 行历史。NAT 回放覆盖原始接收位于范围外、重复候选空页仍可继续，游标冻结截止及跨租户拒绝。日志位于 `.local/query-experience-tests.log`。

Kubernetes kustomize 成功渲染 CH init-v4、005 索引配置和物化视图检查；compose 初始化 SQL 同步 003/004/005。回填脚本的 HTTP 故障/恢复验证确认：只记录成功确认的时间片，失败片段可重试，恢复跳过已确认片段，完全完成后重复运行无写请求，同步插入与预算正确传递。脚本不读 NAT payload。

这组测试中记录过一次 Netty ByteBuf 泄漏告警和本机 native DNS fallback，测试断言均通过；全量回归应继续留意是否复现，不关闭检测。真机上线由根任务单独验收，合成测试不代表无限查询能力或真实设备验收结果。

## 310 万行规模的只读验证与游标修复

现有数据按一小时窗口校验 canonical FINAL 与 receipt 的键，3,106,556 条 canonical 记录全部存在对应 receipt，缺失为 0；18 个回填批次的 query_log 均确认成功。保留逐片证据，不重复扫描已成功的窗口。最初较大的回填失败留下的重复索引不要求删除，API 以 canonical 原始接收和独占游标处理。

真实规模暴露 ClickHouse 26.3 的 local THROW 读取限制使用全范围估算，原本只读约 2,000 行的有序首屏被数百万行估算拒绝。receipt 查询现在使用 leaf THROW 的 200,000 行 / 128 MiB 读取限制，保留 4 秒 / 128 MiB 内存 / 2 MiB 结果限制；local BREAK 阈值设为无限，因此不会因 local BREAK 返回部分结果。刻意读取全历史做 hash 汇总仍由 leaf 限制抛错。canonical 查询的预算不变。

百万同键索引回归另发现 tuple cursor 不能在该版本剪掉整个重复块，改为等价的标量时间/ID 条件。测试覆盖同一接收时间的较小 ID、较旧时间和百万重复，游标仍严格排他。生产只读三页使用完整真实 payload 校验而不输出 payload，验证 150 条唯一、严格倒序事件；部署后的 HTTP API 验收由根任务继续执行。

证据：`.local/remediation-20260909/nat-index-readonly-verification.json`、`nat-actual-budget-verification.json`、`nat-query-fixed-tests.log`。该规模验证不是无限并发/容量承诺，稀疏过滤超出读取预算仍显式返回 503。


r1 的双节点真实 HTTP 验收确认 NAT 默认连续三页、指定时间和协议筛选、禁止跨设备/筛选/时间范围复用游标；Dell/Cisco 收发汇总热力图均为 168 格且 FRESH；Elasticsearch `Te0/3/0` 命中、当前接口 hydrate 与跳转路由正确。Application summary 暴露同类 ClickHouse 全历史估算拒绝（实际约 19k 行，估算 119 万超过 100 万限额）。r2 仅将 summary 改用原有限額的 leaf THROW 控制；生产只读 SQL 已验证 484 条最新批次、484 条差分速率、98 条正速率，30ms / 20,186 行 / 1.41 MB。HTTP 重验结果以 `http-query-verification.json` 为准。
