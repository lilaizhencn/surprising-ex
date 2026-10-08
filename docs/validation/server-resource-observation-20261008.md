# 2026-10-08 测试服务器资源观察

本轮为只读运行诊断，定位资源优化机会，不是容量、延迟或长稳验收。
没有改变业务配置、停止做市或重启应用。

## 环境与采集范围

- 服务器 `surprising-ex`，12 vCPU、约 47 GiB 内存，HotSpot OpenJDK 27、Maven 3.8.7。
- 仅运行 U 本位永续及 BTC 604、ETH 653、SOL 866 的现有做市，不额外发压。
- 五个应用代码为 `292e4e041d26e8929d890366a2cd708e063294f7`；本地 master 的后续部署记录不改变应用二进制。
- Core 保留 PID 2100963，实际打开的 JAR 位于 `20261006-admin-config` 旧目录，commit 无法确认；二进制 SHA-256 已记录，不能把五应用的新 commit 当成 Core 版本。
- 先做无 profiler 的 30 秒线程/进程采样，再分别给 Gateway、realtime、Core 录制 60 秒 JFR，最后再次进行 30 秒无 profiler 的 CPU 采样。
- JFR 使用 `settings=profile`、单进程最大 24 MiB。实际三份共约 4.9 MiB，均自动结束，无 DataLoss 事件。
- 原始路径、PID、JVM 参数、采样窗口、热点、分配、GC、校验及清理状态见 [汇总 JSON](server-resource-observation-20261008.json)。

## 实测状态

初查 load average 约 26，CPU PSI 有明显等待。后续两个无 profiler 窗口中，
CPU idle 分别为 59.44%、61.06%，iowait 约 1.4%，steal 为 0；
不能用初始负载直接推断整机持续满载。内存可用约 29 GiB，未发现当前换入换出压力。

下表 CPU 的 100% 表示一个逻辑核，范围来自两个 30 秒无 profiler 窗口；
PSS 为第一次窗口结束时的物理内存分摊，不能与 Java heap used 混用。

| 进程 | CPU / 单核 | PSS MiB | 线程数 |
| --- | ---: | ---: | ---: |
| Gateway | 130–132% | 4648 | 98 |
| realtime | 97–107% | 2665 | 99 |
| Core | 115–124% | 2925 | 60 |
| price | 53–61% | 2318 | 104 |
| maker | 39–45% | 2375 | 84 |
| derivatives-lifecycle | 32–39% | 1107 | 77 |
| Kafka | 21–28% | 943 | 120 |

五应用健康检查均为 UP，三个合约最新 K 线距检查时间约 57 秒，每根有 6 笔成交。
19 个活跃消费组均有分区分配；有已提交位点的组累计观察到 1 条 offset lag，
来自 maker quote wakeup，K 线 Streams lag 为 0。
部分 snapshot/lifecycle topic 的消费组没有提交过位点，未将其积压假定为 0。
PostgreSQL 观察到约 23 个 idle JDBC 连接，Redis 数据约 19.5 MiB；它们不是本轮主要资源热点。

## 优化顺序及依据

### 1. 成交导出的撮合等待

`CommittedTradeReplay.apply` 在 realtime 进程恢复一套独立 `TradingCoreRuntime`，
重放已提交命令生成可靠成交与订单投影。重放未完成时循环调用
`commitReadyMatching`，无进展只调用 `Thread.onSpinWait()`。
实际 `committed-trade-export` 线程占约 0.516 核，另有 replay matcher 和账户 lane。
JFR 中 `advanceMatchingProgress`、`PendingMatchingRing.pendingAt`、
`dispatchReadyPlaceSettlements` 等是主要热点；这不是恢复历史 K 线的 Kafka 积压。

优先在重放等待边界加入有界退避或完成唤醒，减少无进展时重复扫描；
必须保留提交位置、完整消息边界、确定性、可靠事件捕获及 checkpoint 约束。
不应直接关闭重放、丢弃命令或绕过结算完成条件。
这比立即更换整个成交事实持久化架构更适合先验证。

源码入口：[CommittedTradeReplay](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/CommittedTradeReplay.java)、
[CommittedTradeExporter](../../surprising-realtime/surprising-realtime-provider/src/main/java/com/surprising/realtime/provider/export/CommittedTradeExporter.java)。

### 2. Gateway 的重复驱动与短周期轮询

线程转储确认 Gateway JVM 中有 5 个 SHARED MediaDriver：
4 个分别属于 maintenance、trigger、order、account 连接池，另 1 个承担应用 realtime。
四个回执 dispatcher 合计约 0.337 核；单个 100 微秒空闲等待不断唤醒。
`AeronClientPool.sharedMediaDriver()` 当前只在一个连接池内部复用驱动，
每个连接池仍创建自己的目录及驱动。

可先减少 dispatcher 无工作时的唤醒频率；队列提交已有 `unpark`，
但远端回执仍需轮询，退避上限必须结合尾延迟、超时、keepalive 和重连验证。
随后评估同一产品、同一 JVM 内复用 MediaDriver，保留不同业务的 session、
控制通道容量、产品线隔离及明确的驱动关闭所有权。

源码入口：[AeronClientPool](../../surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java)，
Gateway 内 `AccountAeronGateway`、`OrderAeronGateway`、
`TriggerOrderAeronGateway`、`MaintenanceAeronGateway`，
以及 `GatewayMediaDriverConfiguration`。

### 3. 行情帧与读视图的分配

JFR ThreadAllocationStatistics 的可观测平台线程分配率约为：
Gateway 7.24 MiB/s、realtime 8.47 MiB/s、Core 2.01 MiB/s。
realtime 中 exporter 约 3.10 MiB/s，router 约 2.67 MiB/s，
receiver 约 0.93 MiB/s；热点包括 byte[] 复制、字符串编码、RealtimeFrame 构造。
可针对重复编码、解码和临时集合做局部减少，保留不可变帧及跨线程所有权边界。
这些不是精确全进程分配总量：统计未覆盖仅出现一次的线程及已结束的虚拟线程。

### 4. 内存弹性排在 CPU 等待优化之后

Gateway 的 ZHeap capacity 约 3862 MiB，前后 used 为 352/1324 MiB，
其中缓存页分别约 3510/2538 MiB。较大的 RSS/PSS 不等于同等大小的存活业务对象。
60 秒 JFR 中 Gateway 未记录 GC；realtime/Core 各 1 次 GC，
最大观测停顿分别约 0.210/0.592 ms，没有证据说明 GC 停顿是本轮主要瓶颈。

内存仍有余量，先评估 ZGC 缓存页回收及稳定负载下的堆容量，
不依据短时 used 数字直接降低 Core/maker 堆上限。
Kafka 约 0.21–0.28 核，本轮也没有把减少 broker 线程数作为首要动作。

## 复现与边界

服务器采集脚本为 `/var/backups/surprising/resource-audit-run.py`，
计划在采集前写入该轮目录的 `plan.json`。核心命令如下，PID 和目录按实际运行替换：

```bash
/usr/lib/jvm/java-27-openjdk-amd64/bin/jcmd "$pid" Thread.print -l
/usr/lib/jvm/java-27-openjdk-amd64/bin/jcmd "$pid" GC.heap_info
/usr/lib/jvm/java-27-openjdk-amd64/bin/jcmd "$pid" JFR.start \
  name=resourceAudit settings=profile duration=60s maxsize=24m filename="$jfr_path"
/usr/lib/jvm/java-27-openjdk-amd64/bin/jfr summary "$jfr_path"
/usr/lib/jvm/java-27-openjdk-amd64/bin/jfr view --width 160 hot-methods "$jfr_path"
/usr/lib/jvm/java-27-openjdk-amd64/bin/jfr view --width 160 allocation-by-site "$jfr_path"
```

系统证据使用 `/proc/stat`、`/proc/<pid>/stat`、`smaps_rollup`、线程 stat、PSI、
`vmstat 5 7`、`iostat -x 5 3`；Kafka lag 使用 Admin API 的 group offsets 与
read-committed end offsets。CPU、线程和 PSS 采集并非严格同时，结果用于诊断而非容量计算。

结论为部分验证：确认了优化候选及实际运行开销，未实施优化，也未测优化收益。
未采集 API 吞吐/p99、NMT 类别、长稳、强平资金费触发或快照恢复；
没有写资金和交易数据，不能把短时 JFR 当作无泄漏或业务容量证明。
后续若修改这些路径，需要按受影响范围进行回归、JMH/JFR、资金及恢复验证。

采样清理已完成：三个 `resourceAudit` 录制均自动停止并用 `JFR.check` 复查，
原始 JFR、线程/堆文本及中间事件转储共清理 36 个文件；校验、summary、热点和聚合指标已入档。
JSON 中已删除文件路径仅作历史定位，原应用 JVM 全部保留。
