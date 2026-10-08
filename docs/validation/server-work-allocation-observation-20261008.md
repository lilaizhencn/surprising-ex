# 2026-10-08 测试服务器：不必要的工作与分配观察

本轮只读观察现有运行程序，没有调整配置、写资金、额外发压、部署或重启。
结论为**部分验证：已定位优化候选，未测量修改后的收益**。
相比上一轮，本轮补充了 price、maker、lifecycle 的 JFR，发现做市重复计算、
订单列表逐项复制、行情重复解析及正常过滤异常等具体开销。

## 环境、计划与有效性

- 测试服务器 12 vCPU、约 47 GiB RAM，HotSpot OpenJDK 27、Maven 3.8.7。
- 只运行 LINEAR_PERPETUAL，现有 BTC/ETH/SOL 三合约做市；SPOT 保持停止。
- 五个应用仍是 `292e4e041d26e8929d890366a2cd708e063294f7`。
  Core 实际打开 `20261006-admin-config` 的旧 JAR，commit 未确认；
  SHA-256 为 `4938f1b9bafaeec868e1b836d55ac4cb8590fdb151271bf76e3de9f5cfaff508`。
  `/opt/surprising-ex` 软链接指向新目录，不能据此认定 Core 已更新。
- 采集前计划写入 `/var/backups/surprising/work-allocation-audit-20261008T151253Z/plan.json`。
  无额外提交，in-flight 256 的发压上限不适用；没有修改现有业务窗口。
- 主系统窗口为 UTC 15:13:01–15:16:12，即北京时间 23:13:01–23:16:12。
  前后各计划等待 30 秒，包含读取 `/proc` 的实际测量窗口约 32 秒。
- 六个应用各录制 60 秒 `settings=profile` JFR，单文件上限 16 MiB；
  分别启动，起止按每个文件的 summary，不将其视作严格同时的容量测量。
  另预写计划，对 price 补录 10 秒、8 MiB 上限的异常确认。
- 七份录制合计 11,870,853 bytes，均正常结束；主录制及补录未发现 DataLoss。
  所有原 PID 保留，五应用健康检查 UP，原有服务和做市继续运行。
- 本地源码审阅基于 master `d998c63d`；并行任务的未提交修改未部署，未纳入运行收益。
  对照 commit：不适用，本轮没有检出、重跑历史版本或做版本性能对照。

逐进程 PID、实际 JAR、JVM 参数、时间、线程、堆、GC、热点、异常类别、指标增量、
JFR summary/view、文件大小和 SHA-256 见[汇总 JSON](server-work-allocation-observation-20261008.json)。

## 当前整体状态

两个无 profiler 窗口 CPU idle 为 59.35% / 59.61%，iowait 约 1.55%，steal 为 0。
内存可用约 31.5–31.7 GiB，根盘剩余约 174 GiB，`/dev/shm` 剩余约 21 GiB。
没有当前整机资源耗尽的证据。优化应减少业务热路径的浪费，而非先压低堆、线程或业务频率。

下表 CPU 100% 表示一个逻辑核，范围来自前后两个无 profiler 窗口。
分配率来自各进程 JFR 的 ThreadAllocationStatistics，同一线程首末两次观测、约 59 秒的增量；
**不完整覆盖短命线程和虚拟线程，不能当作精确全进程总量**。

| 程序 | CPU / 单核 | 可观测平台线程分配 MiB/s |
| --- | ---: | ---: |
| Gateway | 123.80–124.06% | 8.87 |
| realtime | 99.26–115.39% | 10.23 |
| Core | 105.87–114.92% | 2.57 |
| price | 56.91–62.08% | 5.28 |
| maker | 45.66–57.90% | 24.65 |
| lifecycle | 27.57–36.10% | 1.40 |
| Kafka | 19.45–22.13% | 未录制 |

GC 主录制中 Core 0 次，其余应用各 1 次，maker 2 次。
最大观测 GC phase pause 为 price 0.737 ms；不能把 ZGC 并发周期约一秒当作停顿。
没有证据表明本轮主要问题是 GC 长停顿；短窗口不能证明没有内存泄漏。

Kafka 57 topics / 57 partitions，全为一个分区，19 个 Stable 组及 5 个历史 Empty 组。
活跃且有已提交 offset 的组总 lag 为 4：ADL mark-price 3，maker quote-wakeup 1；
K 线 Streams 有已提交位点的分区 lag 为 0。
8 个活跃 topic/组组合没有已提交位点，不能将这些积压填成 0。
历史空消费组的 lag 不等于当前应用积压。

约 200 秒指标窗口中 WebSocket 保持 3 个连接、16 个订阅，fanout 约 19.83 messages/s，
观察端点队列深度为 0，backpressure rejection 增量 0。
但 realtime transport dropped **增加 2**，input dropped 和 failures 增量 0。
现有 counter 没有记录 publication 返回码，尚不能区分连接变化与背压，需保留为后续检查项；
不能仅凭健康检查 UP 宣称推送完全无丢失。

## 新发现：优先减少的工作

### 1. 做市报价重复计算、代理调用与逐项复制

这是本轮最高的单应用平台线程分配率：24.65 MiB/s。
ObjectAllocationSample 权重归因中，`liquidityQuoteReplacement` 约 106.90 MiB，
配置 `getQuoting` 约 48.06 MiB，`rememberOrder` 约 38.89 MiB。
这些是采样权重，不是三个方法的精确分配总量，不能直接承诺相应节省。

实际栈包括：

```text
BigInteger.multiplyByInt / valueOf
  -> MarketMakerService.lambda$liquidityQuoteReplacement$0
CglibAopProxy$DynamicAdvisedInterceptor.intercept
  -> MarketMakerProperties$$SpringCGLIB$$0.getQuoting
  -> liquidityQuoteReplacement
List.copyOf / ArrayList.toArray
  -> MarketMakerService.lambda$rememberOrder$0
```

代码中，每张旧单扫描目标报价时，先做 BigInteger 乘法，再检查订单 level 前缀；
同一轮的滑点范围和配置值被反复读取、反复计算。
`@Validated` 的配置 bean 实际经过 CGLIB，CPU 样本也有 MethodValidationInterceptor；
这些 getter 不是没有成本的字段读取。
批量改单完成后，每个 item 都 `forgetOrder` 再 `rememberOrder`，反复过滤、复制整份列表。

最小改动建议：

- 每轮读取一次完整、已校验的配置快照；保留后台保存和安装边界的完整校验。
- 先匹配 side/level，再计算必要的价格条件；买卖两侧滑点界限每轮各算一次，
  保留精确算术和溢出保护，不能直接替换成浮点数。
- 一批确认成功的改单/撤单/补单更新，一次合并到现有订单快照；
  保留未确认结果的失效查询逻辑、每个 item 的状态核验和并发所有权。
- 如列表扫描仍是瓶颈，再验证本轮临时 level 索引；不引入另一份长期订单权威状态。

做市已经保留符合目标的旧单，不是每轮整本撤掉重铺，不能用跳过必要订单维护来降低 CPU。

源码：[MarketMakerService](../../surprising-maker/src/main/java/com/surprising/marketmaker/provider/service/MarketMakerService.java)，
[MarketMakerProperties](../../surprising-maker/src/main/java/com/surprising/marketmaker/provider/config/MarketMakerProperties.java)。
验证需覆盖冷热报价、部分成交、改单不确定、盘口不清空、post-only、预算/持仓限制、
并发配置更新；增加对应路径 JMH/JFR，并核对订单和资金，而非只测列表处理。

### 2. 外部行情重复解析和正常过滤异常

price 主录制 60 秒共有 **15,210 次 IgnoredPayloadException，约 253.5 次/s**；
其中 validateWebSocketAncestry 12,509 次，toSourceQuote 2,701 次。
独立 10 秒确认又有 2,776 次同类异常。
分配样本有 JSON parser/tree、正则编译、Throwable 栈构建。

只读数据库确认 BINANCE/BYBIT/OKX 各 3 个 source，共享各自 1 个 URL。
`handlePayload` 对连接内每个 source 都调用 `parseWebSocketPayload`，
后者重新 `readTree(payload)`，再用异常忽略不属于该 source 的币对。
`sameInstrument` 每次还调用 `String.replaceAll` 编译同一个正则。

最小改动：每帧解析一次，按协议 channel/instrument 定位订阅，再交给对应源做价格转换；
正常的确认、心跳、其他币对消息用显式过滤结果返回，不生成异常。
规范化配置币对一次，消息币对用等价的简单规范化或预编译正则。
仍必须保留 OKX channel/arg/data 一致性、币对隔离、XBT/BTC 规则和行情新鲜度检查。

源码：[ExternalSpotWebSocketManager](../../surprising-price/surprising-price-provider/src/main/java/com/surprising/price/index/client/ExternalSpotWebSocketManager.java)，
[ExternalSpotPriceClient](../../surprising-price/surprising-price-provider/src/main/java/com/surprising/price/index/client/ExternalSpotPriceClient.java)。
验证需要真实多币共享连接的消息样本、确认/心跳/错币对/坏包、重订阅与源配置热更新，
不能只把异常栈关闭后声称消除了重复解析。

### 3. Aeron dispatcher 每轮临时对象

Gateway 中 `dispatchResponses` 迭代器采样权重 29.22 MiB，
`expireQueued` 捕获 lambda 16.68 MiB；price 和 lifecycle 也采到同一路径。
代码每轮都创建 pending 迭代器及 removeIf 函数，即使容器为空。
现有 master 的空闲退避会减少执行频率，但尚未消除这两个创建点。

先判空，再进入回执遍历及排队超时移除；必要时按已有最早 deadline 安排检查。
保留 session.pollEgress、keepalive、远端回执、提交唤醒及超时/未知结果边界，
不能因为没有 pending 就跳过全部 Aeron 服务工作。

源码：[AeronClientPool](../../surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java)。
验证需覆盖空闲、突发提交、超时、断连重连、迟到回执、关闭，以及 256 in-flight 下尾延迟和分配。

### 4. 做市 HTTP 连接复用探测

maker 60 秒共有 2,489 次 SocketTimeoutException，约 41.5 次/s。
补看深层栈为 `sun.net.www.http.HttpClient.available -> HttpClient.New ->
HttpURLConnection -> feign.Client$Default`。
这是 JDK HTTP 连接可用性探测产生的异常，不足以证明 2,489 笔业务请求超时。

后续可验证显式连接池 HTTP transport，避免频繁探测和对象创建；
不能靠增加下单超时、自动重试资金命令或吞掉真实错误处理。
收益尚未测量，优先级低于报价计算和行情解析。
深层栈已保存在 JSON，不能把所有网络等待都归咎于服务端处理慢。

## 已有发现仍存在，以及已有修复待部署

realtime 的 `committed-trade-export` 在两窗口各占约 50.11% / 52.04% 单核 CPU。
606 个 Java execution samples 包含 `CommittedTradeReplay.apply`，
并集中于 commitReadyMatching、advanceMatchingProgress、pendingAt 等。
当前 master 仍在 matching 无进展时 `Thread.onSpinWait()`。
这是上一轮已定位、这轮再次确认的候选：在等待结算完成的边界做有界退避/完成唤醒，
保持重放顺序、结算完整性、可靠导出和 checkpoint，不删除重放业务。

以下优化已在本地 master，服务器仍运行旧版本，**本轮不能认定其没有收益或已生效**：

| 提交 | 已修改内容 |
| --- | --- |
| edbf4b3f | realtime sender 唤醒、客户端空闲退避 |
| 701941ad / 18034f7f | price / lifecycle 按配置 deadline 调度，减少空 tick |
| a0781277 | Archive 边界复用、term 单次选择 |
| 8a8ce3cb | Gateway 同产品共享命令 MediaDriver、协议/Redis/列表分配减少 |
| 9954ce73 / 99055424 | ByteBuffer 解码、接收包外层复制减少 |
| 53799966 / f286c13f | Lua 多更新 pipeline、导出批量合并与空 catalog 查询跳过 |
| d998c63d | 相同订阅基线共享 depth 消息 |

后续应先在当前 master 验证这些路径，再部署并重新观察；不能叠加推算整机节省。

Redis 本轮约 8.37% 单核 CPU，约 3,248 commands/s（包含 Lua 内部命令），
数据库约 95.24 committed transactions/s，没有 pg_stat_statements 扩展，
不能把全部事务归给单一模块。配置每秒加载完整 JSON 后才检查 version 的代码仍存在，
可进一步改为版本未变不反序列化，但当前证据不足以把它列为首要热点。
Redis 只有约 20 MiB 数据也不意味着所有命令都多余，原子版本/fence/过期边界必须保留。

## 复现、清理与未测边界

采集与聚合脚本：服务器 `/var/backups/surprising/work-allocation-audit-run-20261008.py`、
`work-allocation-analyze-20261008.py`、`work-allocation-finalize-20261008.py`。
主要命令：

```bash
jcmd "$pid" Thread.print -l
jcmd "$pid" GC.heap_info
jcmd "$pid" JFR.start name=workAllocationAudit settings=profile \
  duration=60s maxsize=16m filename="$file"
jfr summary "$file"
jfr view --width 160 hot-methods "$file"
jfr view --width 160 allocation-by-site "$file"
jfr print --json --events jdk.ThreadAllocationStatistics,jdk.ObjectAllocationSample,jdk.ExecutionSample "$file"
jfr print --stack-depth 24 --events jdk.JavaExceptionThrow "$maker_file"
jcmd "$pid" JFR.check
```

系统采样使用 `/proc/stat`、各 PID/task stat、smaps_rollup、meminfo、PSI；
业务使用本机 actuator metrics 前后增量；基础设施使用 Redis INFO、PostgreSQL pg_stat_database，
Kafka CLI describe groups/state/topics。实际完整 collector 在归档前锁定，路径不是可重放的旧负载。

JFR JSON 默认打印 5 层栈，本轮对 maker 异常另打印 24 层；
对象权重、Java execution 栈、OS CPU 各自说明口径，不将采样权重当精确 B/op。
CPU 确认窗口可能与本轮离线 JFR 分析进程重叠，属于同机干扰；应用已无录制，
但不能据此将第二窗口当作无其他工作负载的纯净性能对照。
最终 heap_info 在主窗口之后另读，标有独立时间，不与主窗口分配率计算存活增长斜率。
API 吞吐/p99、完整订单终态、资金守恒、快照恢复、NMT/native 增长和长稳不适用本轮只读诊断，
未验证这些内容，也没有声称容量通过或无泄漏。
采样期间有 JIT/GC 和多个应用共同运行；profiling CPU 不作为主 CPU 结果。

原始 JFR、事件、线程/堆文本及临时输出在汇总校验后仅清理本轮目录内文件，
共 98 个文件、1,061,192,743 bytes（主要是展开后的 JFR JSON，录制本身约 11.32 MiB）。
时间和存活 PID 复查见 JSON cleanup。最终堆读取另存汇总，不额外生成原始文件。
校验、聚合、plan 和本报告保留，
已删除路径仅供历史定位。原有应用、Core Archive、业务 checkpoint、Kafka topic 未清理或修改。
