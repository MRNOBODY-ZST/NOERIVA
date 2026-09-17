# 六台真机的选择性迁移导出

此脚本仅导出显式六台资产、26 个候选、1 个关联及指定发现运行，不是全库备份。不读取 `.env`，不连接设备或远程服务器，不停用连接、不修改源库、不导 Kafka/offset/队列、不导入用户，也不复制 vault key。

根代理应把 `.env` 所需的 `NOERIVA_DB_ROOT_PASSWORD`、`NOERIVA_CLICKHOUSE_PASSWORD` 读取到子进程环境，禁止写入命令参数或日志。目标 API/worker 必须另外使用原 `NOERIVA_CREDENTIAL_KEY`。保留原组织、设备 ID、slot、epoch、sequence 与密文，不改写 AAD。

先通过本机 API 停用这六条连接，等待无 RUNNING/租约、相关 outbox 排空，暂停对它们的手动操作。输出目录必须尚不存在，其父目录须已准备好；脚本创建 0700 目录、0600 文件，临时 SQL 也只写在这个目录内。

```sh
python3 scripts/export-selected-hardware.py --self-test
python3 scripts/export-selected-hardware.py \
  --assets .local/hardware/assets.json \
  --candidate-ids .local/remote-deploy/candidate-ids.json \
  --run-ids .local/remote-deploy/run-ids.json \
  --output .local/remote-deploy/selected-hardware-export
```

两个 ID 文件均为规范 UUID 字符串数组。候选成员由显式列表确定，因为 discovery_run 不保存 candidateIds。脚本验证 default/default 归属、候选 evidence/source/关联 ID 不越出六台（null 允许），验证资产地址与连接档案、26 个候选和恰 1 个关联。发现额外 connection、关联汇总/工作台/拓扑数据或未发布 outbox 会失败，要求人工处理范围，不静默丢弃。

编号 SQL 文件按外键顺序只含全列 INSERT，无 DDL、GTID、用户、outbox 或自动冲突覆盖。目标须完成相同 Flyway V1–V7，并已有 default 组织和 default 站点；相关业务表应为空。保持目标 worker 关闭。将编号 SQL 按文件名顺序，在同一个 MySQL 会话内执行：`SET time_zone='+00:00'; START TRANSACTION;` → 所有编号 SQL → `COMMIT;`。任何错误立即中止并回滚，不使用 `--force`。不要关闭外键检查来掩盖错误。导出的连接均停用，验收后由操作员重新启用。

`metrics.jsonl` 使用固定本机 VM 的 `/api/v1/export`，按 default 与六个 device_id 精确筛选 noeriva 指标；逐行复核标签、数值和时间。`events.jsonl` 使用固定本机 CH 的 `control_events FINAL`，按相同资产筛选，保留全部列和 UTC 时间。HTTP 禁止代理与重定向，单个文件上限 256 MiB；超限失败，不截断为成功。目标分别以 `/api/v1/import` 和 `INSERT INTO noeriva.control_events FORMAT JSONEachRow` 恢复，CH 使用 `date_time_input_format=best_effort`。不要把 JSONL 内容打印到终端。

`manifest.json` 仅在所有文件完成、导出前后各选择表完整行指纹一致后写出；只有计数、文件字节数/SHA-256、导出时间与历史时间窗，不包含 ID、设备内容或密钥。VM 时间窗为 Unix 毫秒，CH 为 UTC 字符串。失败留下的目录没有完成 manifest，不能作为可恢复导出；保留核查后使用新目录重试。恢复前核对文件 hash，恢复后核对计数、六台原始 ID/凭据可用性、26 候选/1 关联及固定历史窗，再启用单个目标 worker。新 Kafka 流从恢复后采集开始，不宣称保留旧 Kafka 历史或全局一致备份。
