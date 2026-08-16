# W3/W5 LINEAR_PERPETUAL 本地运行束

此目录提供单次运行独占的 `LINEAR_PERPETUAL` 三 Member Core 与外围全栈编排。它只在任务 worktree
中运行，拒绝主 worktree、其他 ProductLine、wallet、端口复用、并发栈和不匹配的 PID/container/volume
所有权。每次运行必须给出安全的 `RUN_ID`；Compose project、数据库、进程目录、日志、PID、标签和清单均由
该 ID 派生。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
RUN_ID=my-linear-perp PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false ./run.sh dry-run
RUN_ID=my-linear-perp PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false ./run.sh up
RUN_ID=my-linear-perp PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false ./run.sh status
RUN_ID=my-linear-perp PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false ./run.sh down
```

`up` 是真实运行入口：要求 `surprising/aeron-core:local` 和各模块已经由 JDK 25 打包，启动 PostgreSQL、
Kafka、迁移、三个 Core Member、Exporter/Projector、Instrument/Price/Order/Matching/Trigger/Risk/
Funding/Liquidation/Insurance/ADL、Gateway，全部就绪后最后启动 Maker。Topic 列表在每次运行时从
`ProductTopicNames` 源码顺序派生；Kafka 禁止自动建 Topic。`run` 在前台保持该栈并通过 trap 做相同的
精确清理。

`smoke` 使用真实 PostgreSQL/Kafka/Core 容器和轻量本地健康进程验证同一启动顺序、readiness、标签、
manifest 与清理路径；它不会把这些健康进程当作真实业务服务。短验证命令为：

```bash
RUN_ID=plan14-smoke PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false ./run.sh smoke
RUN_ID=plan14-cleanup-test PRODUCT_LINE=LINEAR_PERPETUAL bash tests/ownership-safe-cleanup.sh
```

运行状态写入 `${RUNTIME_ROOT:-$TMPDIR/surprising-w3-w5-runtime}/runs/$RUN_ID/`，包括 `owner`、`pids/`、
`logs/`、`ready.tsv` 以及变更前、运行中、清理后的 ownership manifest。`down` 只终止带该 run marker 的
已记录 PID，并且只操作同时匹配 Compose project 与 run label 的容器。运行声明资源前会保存主 worktree
的原始 index SHA-256 与 tracked porcelain-v2 状态；清理后逐字节复核，任何差异都会拒绝成功。共享
`.omo` 中其他并行任务的未跟踪证据不属于该保护指纹。

默认清理保留本次运行的 volumes，绝不删除外部 volume。仅当调用者显式设置 `TASK_RUN_FRESH=true` 时，
才删除这个 Compose project 自己且标签匹配的 volumes。禁止使用广域 `pkill`、Docker prune 或全局卷删除。
