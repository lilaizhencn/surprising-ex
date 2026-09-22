# Aeron 本机压测诊断

执行前遵循仓库根目录的 [AGENTS-performance.md](../../AGENTS-performance.md)，采集前锁定条件并追加到 [PERFORMANCE_VALIDATION.md](../../PERFORMANCE_VALIDATION.md)。正式吞吐与 JFR 诊断分别采集。

## 饱和判定的证据边界

`summarize-aeron-async-stage.py` 将负载事实放在 `loadEvidence`：是否达到窗口上限、是否观察到窗口背压、等待窗口的时间比例，以及队列证据是否只有高水位。峰值不代表持续积压，BUSY_SPIN 的高 CPU 不代表业务处理饱和。

`saturationGate.status` 为 `UNCONFIRMED`（尚不能确认饱和）或 `INVALID`（客户端完成性检查失败或缺失）。当前采集缺少持续队列占用、有效处理/空转/依赖等待的区分和递增负载下的吞吐平台证据，因此四种 stage 均不能输出饱和 `PASS`；`UNCONFIRMED` 也不表示已经证明未饱和。该判定不代替热控、GC、资金和恢复验收。

Lane 字段改为 `executionWallNanos`、`executionWallRatio` 和 `laneExecutionWallRatioMin/Avg`；它们衡量业务执行区间的墙钟时间，可能包含抢占及 GC，不再命名为 `useful`。历史报告保持原样，读取新报告的工具应使用新字段；不再输出旧的 CPU 阈值判定 `thresholds`。

判定回归：`python3 -B -m unittest discover -s surprising-aeron-core/surprising-aeron-benchmarks/bin/tests -v`（仓库根目录运行）。

## UDP 接收缓冲单因素对照

`bin/qualify-aeron-async-stages.sh` 支持 `ASYNC_SOCKET_RCVBUF_BYTES`：

- 未设置或空值：保留 Aeron 默认值。
- 设置为正整数字节数：同时向节点和客户端 JVM 传入 `-Daeron.socket.so_rcvbuf`，在 `stage-config.txt` 记录请求值。最大值为 Java `int` 上限；操作系统仍可能限制或拒绝实际缓冲大小。
- 不修改系统全局 socket 参数。节点和客户端都需要生效；检查实际 socket 接收缓冲，不能只检查启动参数。

例如，在已锁定的相同负载命令前增加 `ASYNC_SOCKET_RCVBUF_BYTES=4194304`，将接收缓冲请求值设为 4MiB；再使用空值恢复默认做反转验证。生产进程可使用 Aeron 原生 JVM 参数，无需新增业务层配置或线程。

2026-09-22 的本机诊断中，4MiB 轮稳定窗口未观察到接收缓冲溢出丢包和节点 NAK，但批量撤单 P99 仍为 24.38ms。因此该设置是缓解缓冲溢出的手段，**尚不是主要 P99 长尾的修复**；完整 A/B/A 证据与限制见性能记录。不能据此修改全产品默认值或宣称吞吐提升。
