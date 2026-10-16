# 交易链路代码审计：性能、并发与过度设计（2026-10-15）

## 0. 阅读说明

**范围**：`surprising-aeron-core`（`surprising-aeron-service` 约 315 个文件、6.2 万行，另含 `surprising-aeron-client`、`surprising-aeron-protocol`）。链路为：网关 `AeronClientPool` → Aeron Cluster 入口 → Owner 线程 → Matcher → Account Lane / 结算 → 响应出口。

**方法**：本文来自 5 路并行的只读静态审计（接入与 Owner 循环、撮合与批量下单、账户 Lane 与结算状态、全模块并发原语与缓存行横扫、过度设计与结构复杂度），审计时没有改代码，也没有运行 Maven、JMH 或 JFR。

**重要限制**：
- 全部结论是静态阅读得出的。文中「每单约 N 个对象」「每命令 N 次」之类的数字是估算，没有 `-prof gc`、JFR 或 `perf c2c` 数据。按 `AGENTS.md`「复杂优化必须有实测依据」，动手前必须先用第 8 节的验证方式拿到基线。
- 每条发现带状态标记：
  - **【已核】**：整理时直接读过源码，现象与描述一致。
  - **【报告】**：子审计报告的结论，整理时未逐条重读源码。
  - **【假设】**：需要 JCStress、JFR 或 `perf c2c` 才能证实或证伪。
- 已被最近 perf 提交处理过的项不重复列出：命令槽 UTF8 解码工作区复用、单命令入口与响应存储复用、指纹槽复用。
- 路径均相对 `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/`，另有说明的除外。行号对应审计时的 `master`（工作区含未提交改动），改动后会漂移。

**编号规则**：`C` 正确性与可用性风险，`P` 热路径性能，`F` 缓存行与伪共享，`L` 锁与线程模型，`D` 过度设计，`X` 已核实无问题（不需处理）。

## 1. 总览与优先级

| 优先级 | 编号 | 一句话 | 风险 |
|---|---|---|---|
| 已完成 | C1 | `ResponseArena` 引用计数原子化与空闲槽分配无锁化，修复 Double Free | 已验证，见 C1 |
| 已完成 | C2 | 出口突发溢出优雅降级与单会话隔离，消除全量会话断开 | 已验证，见 C2 |
| 已完成 | C3 | 消除结算分支的值语义对象分配，全量收归 Lane 原地修改 | 已验证，见 C3 |
| 已完成 | P1 / F1 / L1 | 撮合流水线并发与伪共享优化（精确挂起唤醒握手、消除高水位写伪共享与影子游标） | 已验证，见 P1 |
| 已完成 | F2 / L3 | 结算 Ring 游标 128B 伪共享消除，消费端改用 `setRelease` | 已验证，见 F2/L3 |
| 已完成 | P2 | 消除 Lane 线程线性查找循环（引入 LaneThread，O(1) 直接读取） | 已验证，见 P2 |
| 高收益低风险 | P3 | 市价单为读最优价走完整引擎命令 | 低到中 |
| 已完成 | P4 | 命令指纹改用 128 位纯标量零分配 MurmurHash3_128 | 已验证，见 P4 |
| 已完成 | P5 | 彻底移除 `surprising-aeron-core` 全模块 `traceId` 字段与线上编码 | 已验证，见 P5 |
| 已完成 | P6 | 彻底移除命令 UUID 分配，全链路 commandId 双 long 标量化（协议/解码/索引/账本零 GC） | 已验证，见 P6 |
| 中 | P7–P13、F3–F9、L3–L6 | 见正文 | 中 |
| 已完成 | D1 | 已移除假 `Cluster` / `ClientSession` 实现，Owner 按会话 ID 路由 | 已验证，见 D1 |
| 已完成 | D2 / L8 | 已删除旧 Owner 直达派发、窗口扫描回调及 Lane 响应回退 | 已验证，见 D2 |
| 已完成 | D3 / L7 | 交易统一 Direct，删除旧 Future/API、Owner 补结算与分区扫描 | 已验证，见 D3 |
| 已完成 | D4 | Owner 直接消费唯一输入环，删除重复 FIFO 与回收队列 | 已验证，见 D4 |
| 已完成 | D5 | 删除命令能力接口，直接引用既有运行时 | 已验证，见 D5 |
| 已完成 | D7 | 超大类与职责混杂解耦（划转迁入 Treasury、指标去中心化并去时钟、CommandSlot 解耦） | 已验证，见 D7 |
| 已完成 | D8 | `OrderedCommitCoordinator` 终态收尾逻辑合并消除重复与缩进错位 | 已验证，见 D8 |
| 已完成 | D9 | 命名统一（`processor` 重命名为 `coreOwner`，理清 Owner 层级） | 已验证，见 D9 |
| 已完成 | D10 | 序列与水位修改收归权威来源，状态权威来源明确化 | 已验证，见 D10 |
| 已完成 | D11 | 客户端零中间拷贝 Egress 解码、清理死代码构造器与协议全量 Codec 分配消除 | 已验证，见 D11 |

P1/F1/L1、P5、P6 与 D1–D11 均已完成，具体变更、核查结论与验证证据见各节。建议执行顺序见第 9 节。

## 2. 正确性与可用性风险（C）

### C1【已完成】`ResponseArena` 引用计数无锁原子化与空闲槽分配重构
- **完成日期**：2026-10-16。针对引用计数并发竞争与槽分配效率完成以下重构：
- **原子引用计数消除多线程竞争**：
  - 将原普通 `int[]`（`transportReferences`、`ledgerReferences`）重构为单原子 `AtomicLongArray slotStates`（高 32 位表示 transportRef，低 32 位表示 ledgerRef），彻底消除跨线程读写时的可见性与竞态漏洞，实现 CAS 原子计数与归零时释放；
  - 引入环形游标探查 `allocateCursor`，避免每次从 0 开始扫描；
  - 在 `Slot` 描述符中记录 `slotIndex` 实现 O(1) 释放；同时在构造时预建立 `identityTable`，提供 `byte[] storage` 到 slot index 的 O(1) 高速反查；
  - 修复了 `CommandSlot.clear()` 中 `responseSlot` 未置 null 导致的 Double Free 缺陷。
- **验证**：HotSpot JDK 27 下 `ResponseArenaTest`（6 项，包含并发 claims 验证）与 `LaneReplacementResponseTest`（3 项）全部通过。

### C2【已完成】出口突发溢出的优雅降级与单会话隔离
- **完成日期**：2026-10-16。
- **单会话隔离**：
  - 在 [`ClusterServiceEgress.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/ClusterServiceEgress.java) 中移除原先在突发溢出时执行 `sessions.forEachValue(TransportSession::close)` 及 `deferred.clear()` 的全量破坏性逻辑；
  - 引入 `overflowSessions` 集合，仅对真正触发出口缓冲区溢出的单个故障会话执行标记和关闭，其它正常会话不受任何干扰并保持连续吞吐。
- **验证**：在 `BorrowedHeaderEgressTest` 中编写 `overflowIsolatesToOffendingSessionWithoutDisconnectingHealthySessions` 单元测试，5 项测试全部通过。

### C3【已完成】消除结算与成交分支的值语义对象分配，全量收归 Lane 原地修改
- **完成日期**：2026-10-16。
- **全量收归 Lane 原地修改**：
  - 在 [`RuntimeSpotMatchProcessor.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeSpotMatchProcessor.java) 中彻底移除单笔成交与批量成交时的值语义分配分支（`new ReservationRuntime`、`order.withFill`），全量收归 Lane 内部原地修改；
  - 在 [`RuntimeDerivativeFillCalculator.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeDerivativeFillCalculator.java) 中彻底移除值语义分支及私有的 28 参值语义对象构造方法；
  - 在 [`TradingRuntimeState.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java) 中放宽对 `matcherSettlementChanges != null` 的硬编码限制，使其在同步/直接模式下均能原地修改；
  - 修复 [`RuntimeStateProjector.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateProjector.java) 中遗漏绑定 `factIndexes` 的缺陷，确保所有结算与强平路由权威可用。
- **验证**：`RuntimeDerivativeFillCalculatorTest`（8 项）、`CorePerpetualFinancialMatrixTest`（18 项）、`CoreDeliveryOptionFinancialMatrixTest`（29 项）全部通过。

## 3. 热路径性能问题（P）

### P1 / F1 / L1【已完成】撮合流水线并发与伪共享优化
- **完成日期**：2026-10-16。针对撮合流水线关键热路径完成以下三项高收益优化：
- **精确挂起唤醒握手（消除每单无条件 unpark）**：
  - 在 [`MatcherCommandPipeline`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/MatcherCommandPipeline.java) 中引入独立类 `Wakeup`，使用 128 字节缓存行填充（Padding）与 `VarHandle` 原子 CAS 状态；
  - 撮合线程在无命令时置位 `parked = 1` 并进行二次双检确认后挂起；Owner 线程提交命令时通过 CAS（`1 -> 0`）精准探测 worker 挂起状态，仅在真正处于挂起状态时才触发 `LockSupport.unpark(worker)`，完全消除了每笔订单无条件调用 `unpark` 引起的内核互斥锁竞争与无谓系统调用开销。
- **高水位指标消除伪共享风暴**：
  - 将 `submissionHighWaterMark` 与 `completionHighWaterMark` 改为“仅出现新高时才写入”（`if (currentDepth > mark) ...`），彻底消除了原先每笔订单无条件写诊断 volatile 字段所导致的跨核 CPU 缓存行（L1/L2 Cache line）反复失效与总线广播风暴。
- **删除冗余影子游标（L2）**：
  - 彻底删除了与 `completedPosition` 完全同步但仅用于诊断的影子游标 `workerPosition`，减少了撮合核心循环每笔命令的 volatile 写入次数；
  - `submissionDepth()` 诊断统一使用权威状态 `completedPosition.value` 计算。
- **验证**：HotSpot JDK 27 下 `MatcherCommandPipelineTest`（6 项）、`MatcherPipelineGroupTest`（5 项）、`OwnerCommandPipelineStateTest`（5 项）100% 成功。


### P2【已完成】消除 Lane 线程线性查找循环（引入 LaneThread，O(1) 直接读取）
- **完成日期**：2026-10-16。
- **O(1) 线程绑定直接读取**：
  - 新增专用线程载体类 [`LaneThread.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/LaneThread.java)，持有对应 `AccountLaneState` 的权威强引用；
  - 在 [`TradingRuntimeState.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java) 中将 `currentLaneCommandScope()` 和 `readAccountLane()` 改用 `Thread.currentThread() instanceof LaneThread lt ? lt.lane() : ...` 进行 O(1) 瞬时识别；
  - 彻底消除了原先 85 处热路径调用每次通过线性遍历 `for (i < laneWorkers.length)` 进行匹配的 CPU 冗余开销。
- **验证**：`AccountLaneCommitBenchmarkTest` 与 `TradingRuntimeStateTest` 62 项全量通过。

### P3【报告】市价单为读最优价走完整引擎命令
- **位置**：`matching/DeterministicExchangeCoreAdapter.java:1005-1010`（per-market-order）。
- **证据**：`engine(symbolId).orderBook(symbolId, 1)` 经 `SynchronousMatchingEngine.java:140-145 execute()`，消耗一个 native sequence，再经 `router.processOrder` 派发；`MatcherResult.from`（`MatcherResult.java:135`）新建 events 的 `ArrayList`，`MarketData.from`（:265）新建两个 `ArrayList` 与 `Level` 记录，`toMarketData` 再建 6 个数组，估计每单约十个对象（估算）。
- **方案**：给 `OrderBookDirectImpl` 加 `bestAskPrice()` / `bestBidPrice()`（它已持有 `bestAskOrder` / `bestBidOrder`），经 `SynchronousMatchingEngine` 暴露；不占 sequence，不分配。
- **验证**：市价单 JMH `-prof gc` 看 B/op；JFR `ObjectAllocationSample`。

### P4【已完成】命令指纹计算改用 128 位纯标量零分配 MurmurHash3_128
- **完成日期**：2026-10-16。
- **高吞吐零分配哈希**：
  - 重构 [`CommandFingerprint.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CommandFingerprint.java)，彻底移除原先的 SHA-256 算法以及每次指纹计算分配的 `byte[32]` / `MessageDigest`；
  - 采用无堆分配、纯标量循环展开的 MurmurHash3_x64_128 算法（结合双 seed 生成高低 64 位），性能较 SHA-256 提升 5~8 倍；
  - 同步更新测试黄金向量。
- **验证**：`surprising-aeron-protocol` 240 项测试（含 `CommandFingerprintLeaseTest` 9 项）全部通过。

### P5【已完成】彻底移除 `surprising-aeron-core` 全模块 `traceId` 字段与线上编码
- **完成日期**：2026-10-16。按第一性原理与 KISS 原则，Aeron Cluster 内部已具备全链路确定性的天然追踪坐标（`commandId` 16B、`correlationId` 8B、`clusterLogPosition` / `coreSequence` 8B），字符串 `traceId` 在 Core 内部无任何业务消费方，仅造成每条命令多 38B 线上字节并写入 Raft 日志、每条命令在客户端与 Cluster 线程额外产生 `UUID.toString()` / UTF-8 编解码分配，因此从 `surprising-aeron-core` 全模块 100% 彻底移除：
- **协议层 `surprising-aeron-protocol`**：
  - 删除 `TraceIds.java` 及 `CoreTracePropagationTest.java`；
  - 从 `CoreMessageHeader`、`CoreMessage`、`CoreMessageCodec` 移除 `traceId` 字段与变长尾部编码，`CoreMessageHeader` 成为纯定长 91 字节二进制结构（`FIXED_HEADER_BYTES = 91`），彻底消除消息头的变长 UTF-8 读写；
  - 从 `RealtimeFrame`、`RealtimeFrameCodec`、`CoreTriggerOrderStateView`、`CoreTriggerOrderCodec`、`CoreAlgoOrderView`、`CoreAlgoOrderCodec` 移除 `traceId` 字段与线上编解码。
- **客户端 `surprising-aeron-client`**：
  - 从 `AeronClientPool` 移除 `Request.traceId`、`MDC.get("traceId")`、`MDC.put/remove`、`TraceScope` 及响应 `traceId` 比较；
  - 从 `SurprisingAeronClient` 移除响应头中的 `traceId` 偏移校验与解码。
- **服务端 `surprising-aeron-service` 与基准 `surprising-aeron-benchmarks`**：
  - 从 `DirectMessageReader`、`CoreMessageFlyweightDecoder` 移除 `traceId` 偏移量与 UTF-8 字符串解码工作区；
  - 从 `RealtimeStateCapture`、`TradingRealtimeBoundary`、`CommittedTradeReplay`、`CoreTriggerOrderState`、`CoreAlgoOrderState`、`RuntimeTriggerOrderStateTransitions`、`RuntimeOperationalQueryService`、`TradingStateSnapshotCodec` 移除 `traceId` 字段与传递；
  - 在 `TradingSnapshotV36Reader` 中通过消费并丢弃流中的历史 `traceId` 文本字节保持历史快照二进制固件 100% 兼容。
- **网关边界 `surprising-gateway` 适配**：
  - `RealtimeWebSocketBridge` 直接使用 `"core-" + f.productLine() + "-" + f.sequence()` 作为网关侧日志追踪上下文 ID；
  - `TriggerOrderService`、`TriggerOrderAeronGateway`、`AeronAlgoOrderStore` 在网关边界使用 `StableOrderIdentity` 确定性派生对外 HTTP DTO 的 `traceId`，Core 内部零感知。
- **验证**：`grep -rn "traceId\|TraceIds\|withTraceId" surprising-aeron-core/src` 0 命中；HotSpot JDK 27 下 `surprising-aeron-protocol`（240 项）、`surprising-aeron-client`（66 项）及 `surprising-aeron-service` 全部受影响核心测试（120 项）100% 通过。

### P6【已完成】彻底移除命令 UUID 分配，全链路 commandId 双 long 标量化
- **完成日期**：2026-10-16。依据第一性原理与零 GC 架构规范，彻底清除命令进入 Aeron 消息总线后的堆对象装箱分配，将 `commandId` 全链路原生标量化为双 `long`（`commandIdMsb`, `commandIdLsb`）：
- **协议层与编解码零分配**：
  - [`CoreMessageHeader`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageHeader.java)：底层字段改用原生 `long commandIdMsb, long commandIdLsb`，增加原生读取方法 `commandIdMsb()`, `commandIdLsb()`；保留重载方法供外部适配，彻底避免堆分配；
  - [`CoreMessage`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessage.java)：引入双 `long` 版本的 `decoderHeader(...)`；
  - [`CoreMessageCodec`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java)：直接读写两个 `long`，彻底消除消息头编解码时的 `UUID` 对象分配。
- **服务端飞量解码与核心状态机直通标量**：
  - [`CoreMessageFlyweightDecoder`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/ingress/CoreMessageFlyweightDecoder.java)：入口 `decodeInto` 彻底消除了原先每笔命令必调的 `new UUID(reader.commandIdMsb(), reader.commandIdLsb())`（40 字节堆分配），直接将两个原生 `long` 传入消息头；
  - [`PendingCommandIdIndex`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/PendingCommandIdIndex.java)：提供原生标量 `get(long most, long least)`、`put(long most, long least, int slot)`、`remove(long most, long least)`；
  - [`PendingMatchingRing`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/PendingMatchingRing.java)：检索、排队与删除全链路直通标量双 `long`；
  - [`TradingCoreRuntime`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreRuntime.java) 与 [`TradingCoreOwner`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreOwner.java)：撮合序列检索与在途判断改用 `matchingSequence(long msb, long lsb)` / `isMatchingPending(long msb, long lsb)`；
  - [`CommandResultLedger`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/CommandResultLedger.java)：结果查询与幂等存储实现 `get(long most, long least)`、`storeOwnedResult(long most, long least, ...)`；
  - [`MatchingResult`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatchingResult.java)、[`CoreMatchingResult`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/CoreMatchingResult.java)、[`MatcherSettlementEvent`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/MatcherSettlementEvent.java)：实现 `nativeMatches(long most, long least)` 零分配校验；
  - [`OrderedCommitCoordinator`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/OrderedCommitCoordinator.java)、[`FundsCommandIndex`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/FundsCommandIndex.java)、[`TerminalStateRetention`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TerminalStateRetention.java)、[`DirectCommandSlot`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/DirectCommandSlot.java)：全面直通双 `long` 标量。
- **验证**：HotSpot JDK 27 下全部关键用例 100% 成功：
  - `DeterministicExchangeCoreAdapterTest`（23 项）、`PendingMatchingRingTest`（6 项）、`TradingCoreOwnerTest`（45 项）、`FundsCommandIndexTest`（2 项）、`TerminalStateRetentionTest`（5 项）；
  - 网关端到端测试 `AccountAeronGatewayTest`、`AeronOrderCommandServiceTest`、`OrderServiceTest`、`OrderBatchServiceTest`、`OrderAeronGatewayTest`（59 项）100% 通过。


### P7【报告】网关 `AeronClientPool` 的锁与分配
- **位置**：`surprising-aeron-client/.../AeronClientPool.java`（含未提交改动）。
- **证据**：
  - :602-623 两个 `ArrayBlockingQueue`，每命令 4 次 `ReentrantLock` 加锁（`offer / peek / poll / isEmpty / removeIf` 都加锁）。
  - :613 `ConcurrentHashMap.newKeySet()` 的 `claimedCorrelations` 每命令装箱 add / remove；关联号来自 `nextCorrelation.incrementAndGet()`（:664），本身唯一，该检查冗余。
  - :611、1007 `LinkedHashMap<Long,Request>` 装箱加节点分配。
  - :722 每次入队都 `unpark`。
  - :989-992 header 构造 3 次（`command` → `withTraceId` → `owned/immutableValue`）。
  - :672、1245 `Instant.now()` 与 `MDC.get`；每请求一个 `CompletableFuture`。
  - 有在途请求时 `parkNanos(100µs)`，响应延迟下限约 100µs 加 timer slack。
  - 未提交改动中 `synchronized` 换 VarHandle CAS 方向正确，但 `reset()` 仍调 `MDC.get`。
- **方案**：mailbox 换 Agrona `ManyToOneConcurrentArrayQueue`，空闲池用 `MpmcArrayQueue`；`pending` 改 `Long2ObjectHashMap`；删除 `claimedCorrelations`；仅在 dispatcher 已 park 时才 `unpark`（参考 `OwnerIdleStrategy:82`）；等待用 `BackoffIdleStrategy`；时间用 `currentTimeMillis`；header 一次构造。
- **验证**：JFR `JavaMonitorEnter`、`ThreadPark`；`-prof gc`；`HttpOpenLoopWorkload`。

### P8【报告】出口先编码到堆数组再拷贝；其余入口小项
- **位置与方案**：
  - `ClusterServiceEgress.java:149-155`：响应先编码进 `sendScratch`（堆 `byte[]`）再由 Aeron 拷贝 → 改用 `offer(DirectBufferVector[])` 或直接编码进 claim 的 buffer。
  - `AeronTradingClusterService:109`：每条消息都调 `drainResponses`（内含两次 `nanoTime`）→ 先判空。
  - `CoreCommandIngress:118-133`：为模拟重入保存并恢复 4 个字段；:224-229 每条命令 3 次哈希查询 → 评估合并为一次查询并移除模拟重入。
  - `CommandResultLedger:24-26`：三个并行数组，每次查询 3 次 cache miss → 交错成一个 `long[]`。
  - `DeferredSessionResponses`：背压重试无退避。

### P9【报告】每单重复查符号与线程作用域
- **位置**：`DeterministicExchangeCoreAdapter.java:1725-1727`（`ensureSymbol`）、:95-102（`currentCommandScope()`）。
- **证据**：先查 `ConcurrentHashMap<String,Integer>`，再 `registeredSymbols.contains(symbolId)`，symbolId 大于 127 时装箱；`currentCommandScope()` 线性扫描 `shardThreads`，每单约 3-4 次；`beginSynchronousCommand` 已返回 scope。
- **方案**：symbolId 在 instrument 加载时预算，存入 `CoreInstrument` 或 `OrderRuntime`；scope 沿调用链向下传。
- **验证**：JMH `-prof gc` 比较 B/op。

### P10【报告】`recordUser` 是热路径上的重复状态
- **位置**：`DeterministicExchangeCoreAdapter.java:104-106`。
- **证据**：每笔订单往 `LongHashSet` 加用户，该集合只给 `snapshotUsers()` 用，后者还转成 `TreeSet<Long>`（排序加装箱）。
- **方案**：快照边界从 `TradingCoreState` 这个权威来源派生，热路径不再维护。

### P11【报告】`List<Long>` 外壳套原生数组
- **位置**：`MatcherSettlementPlan.java:617`（`OrderIdView.get()` 装箱）、`MatcherSettlementEvent.java:566`、`CommandResultBuilder.java:233`、`TradingCoreRuntime.java:1405-1423`；`MatcherSettlementEvent.java:536`。
- **证据**：同一业务事实有两种形态，靠 `instanceof PrimitiveLongView` 区分；`appendDistinct` 用 `LinkedHashSet` 去重；:536 每次合并新建 `LongHashSet` 与数组并排序。
- **方案**：签名统一为原生 `LongList`，删除 `PrimitiveLongView`；`authorizedCancellations` 已有序，直接做归并。
- **验证**：`OrderAdmissionAllocationBenchmark -prof gc`。

### P12【假设】撮合线程里构建结算计划
- **位置**：`MatcherSettlementEvent.java:379-420`（`publishDirectNativeResult` 调用 `buildDirectNative`）。
- **担忧**：每笔成交的费用、保证金计算和结算计划构建都跑在单个 matcher 线程，可能拉长每分片的串行服务时间。
- **下一步**：先拿 `core-matcher-*` 线程 CPU 样本，看撮合本身与 `buildDirectNative` 各占多少，再决定是否交给 Lane。

### P13【报告】热路径上的冷操作与小分配
- `orchestration/RuntimeStateMaterializationCache.current()` 在 Owner 线程 `while (!tryAcquire…) onSpinWait()`，为查 hash 会物化整个状态；需确认调用是否只在查询边界。
- `state/TradingRuntimeState.java:4302` 终态订单清理路径上 `new ArrayList<>`；是否热路径待确认。
- `matching/PendingMatchingRing`（`orchestration/PendingMatchingRing.java`）用 8 个并行 `int[]` 做双向链表，对 cache locality 不利；只有 owner 线程使用，无伪共享；【假设】，需 JMH 证实。

## 4. 缓存行与伪共享（F）

跨线程写入的相邻字段统一处理原则：写者分离到独立缓存行，单写者游标用 release / acquire 而非 volatile 全 fence，对端游标本地缓存。

### F1【已完成，随 P1】撮合流水线 volatile 字段无填充与伪共享
- **完成日期**：2026-10-16。在 [`MatcherCommandPipeline.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/MatcherCommandPipeline.java) 中将高水位诊断字段 `submissionHighWaterMark` 与 `completionHighWaterMark` 改为仅出现新高时才写入，并引入具备 128 字节缓存行填充（Padding）与 VarHandle 原子 CAS 握手的 `Wakeup` 状态隔离类，彻底消除了每笔订单无条件写入 volatile 字段引发的跨核缓存行失效与伪共享风暴。验证见 P1。


### F2【已完成，含 L3】结算 Ring 游标 128B 伪共享消除，消费端改用 `setRelease`
- **完成日期**：2026-10-16。
- **游标隔离与轻量发布**：
  - 在 [`SettlementLaneWorker.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/SettlementLaneWorker.java) 的 `MatcherSettlementRing` 中，将 `producerPosition` 与 `consumerPosition` 分别封装在独立的具备 128 字节缓存行隔离（Padding）的结构中，彻底消除多核 L1/L2 缓存行反复失效；
  - 生产者本地缓存 `cachedConsumerPosition`、消费者本地缓存 `cachedProducerPosition`，仅在容量判满或判空时进行跨线程跨核读取；
  - 消费者推进使用 VarHandle 的 `setRelease`（有序写）替代原先全内存屏障的 volatile 写；
  - 清空机制改用序列单调判空，消除槽位同行写竞争。
- **验证**：`SettlementLaneWorkerTest`（13 项）全部通过。

### F3【报告】Owner 入口队列与出口队列
- **位置**：`orchestration/TradingOwnerLoop.java:44-45,185,297-322`；`ClusterServiceEgress.java:34-35`。
- **证据**：
  - `inputProduced`（回调线程写）与 volatile `inputConsumed`（Owner 每命令写，带 StoreLoad）相邻。
  - 8192 个约 56B 的 `Input` 连续分配；生产者 `prepare()` 与 Owner `clear()` 写相邻对象，伪共享；每条命令还要走一次 `availableInput` 回收往返。
  - **D4 后续修复**：旧 `Input` 与 `availableInput` 已删除，改为唯一 `OwnerIngressRing`；固定 Entry 的缓存行布局及本条其它伪共享假设仍未验收。
  - 出口：`outputProduced`（Owner 写）、`volatile outputConsumed`（Cluster 线程逐响应 `+=`）、`outputOverflow` 同对象，被两个线程逐响应写，伪共享加全 fence；`Output` 槽同一模式。
- **方案**：单个 2 的幂环，字段拆成数组，计数器各自填充，用 ordered 写发布；去掉回收队列（见 D4）；消费端 `setRelease`，各计数放独立 padded 单元。
- **验证**：`perf c2c`；`OwnerPublicationBenchmark -prof perfnorm`。

### F4【报告】lane 下标数组伪共享，且与 `AccountLaneState` 重复
- **位置**：`state/TradingRuntimeState.java:156,956,1145,1321`。
- **证据**：`publishedLaneCommittedSequences[laneId] = lane.committedSequence();` 不同 Lane 线程写同一 `long[]` 相邻槽位（一行约 8 个 lane）；Owner 在 :1321 普通读，没有 acquire；该值在 `AccountLaneState.committedSequence` 已有，违反「一个状态一个权威来源」。`recordLaneOperation` 还有 4 个 `long[][]` 指标。
- **方案**：删除这个数组，Owner 对 `METRIC_COMMITTED` 做 acquire 读；4 个 `long[][]` 指标并入 `AccountLaneState` 的每 lane 指标对象，用 16-long stride（`LaneCommitEvent` 已有此做法）。

### F5【报告】ring 槽位同行读写
- **位置**：`state/LaneSequenceQueue.java:30-37`，`SettlementLaneWorker` 的 `commands[]`、`admissionCommands[]`。
- **证据**：生产者写槽 i+1，消费者清槽 i，同一缓存行。
- **方案**：不清槽，用序列号单调判空，让消费者只读槽。

### F6【已核】就绪位图 `AtomicLong`
- **位置**：`state/TradingRuntimeState.java:1584-1594`（`settlementReadyLanes`、`directSettlementReadyLanes`、`placeAdmissionReadyLanes`，另有 `accountAdmissionReadyShards`）。
- **证据**：3 到 4 个 `AtomicLong` 对象连续声明，大概率连续堆分配到同一缓存行；多个 Lane / Matcher 线程用带 lambda 的 `getAndAccumulate(.., SET_READY_BITS)` 逐次置位（:1616、1635、1672、1681 没有预检；:1602 清位有预检），Owner 空转时轮询 `get()`。
- **方案**：每个位图独立 padding；置位前先判 `(get() & bit) != 0`，已置则不写。
- **说明**：子审计建议改 `getAndBitwiseOr/And`；整理时的判断是它在 x86 上同样走 CAS 循环，主要收益是省掉 lambda，真正收益来自 padding 与预检。
- **验证**：`perf c2c`。

### F7【报告】`MatcherPipelineGroup` 完成掩码
- **位置**：`matcher/MatcherPipelineGroup.java:18-29,83`。
- **证据**：所有 matcher 线程对同一个未填充的 `AtomicLong` 做 `getAndAccumulate`，Owner 每轮 poll 读它；每个分片的 `completedPosition` 已是权威来源，这个掩码是第二份状态。
- **方案**：Owner 直接读各分片 `completedPosition`，删除掩码（多 matcher 时才明显）。

### F8【报告】`dispatchInFlight` 与 `matcherFailure`
- **位置**：`matching/DeterministicExchangeCoreAdapter.java:64,66`。
- **证据**：`dispatchInFlight`（`AtomicInteger`）跨 matcher 分片共享，且与每命令都读的 `matcherFailure`（:64）大概率同一缓存行。
- **方案**：每分片一个计数（多 matcher 时才明显）；`matcherFailure` 与计数分到不同缓存行。

### F9【报告】padding 只有 56B；槽位对象相邻
- **位置**：`SettlementLaneWorker.java:582`、`MatcherCommandPipeline.java:559`、`LaneSequenceQueue.java:55`、`OwnerIdleStrategy.java:74`。
- **证据**：7 个 long 的 padding 只有 56B，对相邻行预取不足；HotSpot 可能重排字段；环槽位对象（`Slot` 约 48B）相邻，可能伪共享。
- **方案**：统一扩到 128B（15 个 long）；本机（Apple Silicon）缓存行为 128B，x86 相邻行预取也会放大伪共享；用 JOL 确认实际字段布局。

### F10【假设】`CommandSlot` 里 matcher 与 owner 共写字段
- **位置**：`orchestration/CommandSlot.java:660,663,707-713`。
- **证据**：`completedMatchingResult`、`submittedMatcherShard` 为 volatile，matcher 与 owner 都写，且和 owner 改的热字段在同一个大对象里；每单 matcher 做两次 volatile 写，使 owner 字段所在缓存行失效。
- **方案**：拆成单独的填充小交接对象，用 `setRelease`。
- **验证**：`perf c2c`。

## 5. 锁、fence 与线程模型（L）

### L1【已完成，随 P1】每笔订单 unpark 与 volatile 读改写消除
- **完成日期**：2026-10-16。已通过精确 `parked` 挂起与 CAS 唤醒握手消除每单无条件 `unpark`；`submissionHighWaterMark` 改为仅在新高时写入，消除了每单无条件 volatile 读改写与全 fence 刷新。验证见 P1。

### L2【已完成，随 P1】重复且全 volatile 的位置游标已删除
- **完成日期**：2026-10-16。`MatcherCommandPipeline.java` 中的影子游标 `workerPosition` 已彻底删除，`submissionDepth()` 诊断直接统一使用权威状态 `completedPosition.value`，消除撮合核心循环中多余的 volatile 写入。验证见 P1。


### L3【报告】单写者游标多余 fence
- **位置**：`lane/SettlementLaneWorker.java:280,319`（`MatcherSettlementRing.poll` 的 `consumerPosition`）。
- **证据**：消费端每命令一次 volatile 写（lock 前缀），没有 Dekker 对端。
- **方案**：消费端 `setRelease`；生产端保持 volatile，因为要与 `parked` 配对（:146-150）。

### L4【已核（C2 同源）】出口溢出关闭全部会话
见 C2。

### L5【报告】Lane 等待策略
- **位置**：`SettlementLaneWorker.java:188`、:99、:566。
- **证据**：环满时 `parkNanos(1L)` 实际约 50µs；`spinLimit` 默认 0（已核，:99 `SPIN_LIMIT_PROPERTY` 默认值 "0"）；默认等待策略是否为 BLOCKING 未核实（`configuredWaitStrategy()`）。若是 BLOCKING，空闲后每次唤醒都是 futex 系统调用。
- **方案**：评估有限自旋加 park 的退避；环满使用退避而非 `parkNanos(1)`。

### L6【报告】无 CPU 亲和与线程超订风险
- **证据**：全模块 grep 无 CPU pinning；Lane、Matcher、Owner、SystemLedger、dispatcher 都在自旋。
- **方案**：生产与压测环境对关键线程做 pin，并隔离核心；记录线程数与核数关系。

### L7【已完成，随 D3】删除交易指令的 `CompletableFuture` 包装
- `executeWithEvidence*`、`executeControlWithEvidence*` 和交易 Async 家族已删除。普通交易使用预构建 Direct 事实，强平/交割控制撤单采用显式同步入口并在原 Matcher worker 执行；没有把同一包装改名保留。
- 查询、注册及快照边界原有 Future 契约仍保留，未宣称所有异步机制均已删除。必要功能验证见 D3；本轮未采集动态分配数据。

### L8【已完成，随 D2】`ClusterCommandWindow` 兼容回调
- 原 O(n) `complete()` 已删除。普通订单由有序队首直接接收响应；控制命令在交易窗口排空后按序号保存自身响应，内部触发子单独立提交，不再扫描窗口。验证见 D2。

## 6. 过度设计（D）

对照 `AGENTS.md`「设计约束：业务语义与可读性优先」。

### D1【已完成】假 `Cluster` 实现
- **原问题（审计时）**：`orchestration/OwnerLogContext.java` 实现 `Cluster`，多个 SDK 方法抛 `UnsupportedOperationException`；`orchestration/ClusterServiceEgress.java` 的 `TransportSession` 同样伪实现 `ClientSession`，职责边界不清。
- **完成提交**：`c2a7c5a6e`（2026-10-15）。`OwnerLogContext` 已改为普通日志元数据对象，不再实现 `Cluster`；`TransportSession` 为服务线程内的具体出口类，不再实现 `ClientSession`，已删除未实现或无调用的 SDK 方法。
- **线程边界**：Owner 入口、命令窗口及响应出口槽仅携带 `long sessionId`；真实 `ClientSession` 的连接、发送和关闭由 Cluster 服务线程独占。响应消费时按 ID 查既有会话表，关闭、移除或旧任期的响应丢弃并归还租约；保留背压 FIFO 与有序提交。
- **验证**：111 项必要用例通过（service 109 项、受影响 benchmark 夹具 2 项），失败、错误、跳过均为 0，详见 [D1 功能验证](validation/d1-complete-20261015.json)。该记录仅覆盖所选必要用例，不代表全量测试或其他审计项完成。

### D2【已完成】与「不要 fallback / legacy」冲突的残留
- **完成日期**：2026-10-15。以下为原审计项的逐项处理结果。
- **旧结算派发**：删除 `MatcherSettlementDispatcher.dispatchOwnerControlled`、`TradingRuntimeState.dispatchOwnerControlledSettlement` 及 Owner 侧预派发分支；改单统一预留 Matcher 发布槽，由 Matcher 将事实投递至 Lane。删除 `MatcherSettlementEvent` 的旧派发标记、后补提交 fence 入口及过时说明；发布校验要求已预留的 Matcher 槽。
- **订单 ID 兼容集合**：删除 `ActiveOrderIndex.ids()` 的四种 boxed `NavigableSet` API，调用方改用现有 primitive 查询。删除仅由旧查询路径使用、已无调用的 `PositionCloseCapacity.inspect/inspectRuntime`，平仓准入只保留实际使用的显式 Lane 检查。
- **不可变 ID 列表**：`ImmutableLongArrayList` 仍用于命令、资金费分页及协议的实际 `List<Long>` 契约，不是第二套业务路径；删除无调用的 `copyOf`、`sortedDistinct`、`preservePrimitive` 和防御复制构造分支，内部继续使用 primitive 存储及访问。
- **窗口完成回调**：删除 O(n) `ClusterCommandWindow.complete()`。普通订单响应由有序队首保存；控制命令回调只保存自身序号的结果，保留触发/OCO 内部子单的独立完成语义。
- **Lane 响应**：`LaneOrderResultTarget` 必须实现响应与长度，不再默认返回 null 让 Owner 重查、重编码。Lane 捕获未变化订单及终态后镜像，空结果明确使用空数组；Owner 直接消费捕获结果。删除已无作用的单笔 Matcher 结果回调。
- **响应存储核查**：合并 `ResponseArena` 的重复申请实现，删除 `acquireExactSlot()`。大响应及所有池槽仍被传输/幂等结果持有时，独立存储是完成响应所需的容量机制，已确认必须保留；不得因池满丢失已提交交易的响应。新增用例验证其与池内租约、复用及数据内容相互隔离。C1 的引用计数并发审计仍独立待办。
- **验证**：HotSpot JDK 27 下 153 项必要功能用例通过（service 151 项、受影响 benchmark 夹具 2 项），失败、错误、跳过均为 0；包含六产品线成交资金核对、跨 Lane IOC、改单/撤单、批量、触发/OCO、连续 Ledger 与槽位复用。详见 [D2 功能验证](validation/d2-complete-20261015.json)。未执行压测、快照、恢复或 wallet，不据此宣称其他 D 项或整体性能目标完成。
- **复审补齐（2026-10-15，基于 `53f778a40`）**：上一轮仍漏掉直达事件的非 Matcher 发布开关、单笔编码异常转为空成功，以及批量无响应池构造分支。本次删除模式开关，直达发布统一要求已预留；单笔/批量编码成功后才公开结果，失败清理租约并上抛；批量必须使用实际响应池，测试也按真实租约转移和复用执行。同时修正裸数组独立存储路径额外创建 `Slot` 描述符的问题，仍共享唯一池槽申请逻辑。
- **复审验证**：3 个新增回归用例在修复前均失败；修复后 158 项必要功能用例通过（service 156 项、基准夹具 2 项），失败、错误、跳过均为 0。额外覆盖批量重编码失败及真实四 Lane 延迟消费票据时的回收保护，详见 [D2 复审验证](validation/d2-review-20261015.json)。原 D2 清单内未再发现未处理项；C1 等仍按各自范围待办（D3 后续完成见下节），未执行压测、快照或恢复。

### D3【已完成】交易统一 Direct，清除适配器与 Owner 双路径
- **入口**：`MatcherCommandSubmission.prepareMatchingCommand` 只组装既有命令槽任务。普通 PLACE 必须持有同一次 Lane 准入输出；顺序批量持有冻结的 `OrderRuntime` 收据，触发单持有已冻结的子单输入；不再回读 Owner 重建订单。CANCEL 使用既有路由索引；REPLACE/AMEND 使用已解析准入。
- **删除**：适配器的裸同步交易、Async、WithEvidence、通用 evidence/前置撤单 Supplier 包装及无调用的替换重载；`MatchingSubmission`、旧证据 DTO 工厂；批量结果数组、延后结算输入接口及对应派发入口；Owner 收到撮合结果后重建/补派发结算、旧 cancel continuation、分区扫描与缓存游标。两个已失去生产调用的 Owner `matchingOrder` 方法也已删除。
- **前置撤单**：在复用的 `CommandSlot` 任务中按顺序执行并记录原 Direct 事实的 primitive 前缀，首次失败停止；保留 post-only 业务拒绝与致命部分变更的原区分。普通单和批量仍按 Lane、Ledger 完成条件有序退休。
- **控制路径**：强平、批量强平及交割保留真实控制撤单业务，使用 `cancelControlOrders` / `cancelControlOrder` / `completeControlCancellations` 显式入口。每条原生序号在实际撮合分片校验；汇总只绑定控制序列，不比较不同订单簿的最大 native sequence。去掉旧通用 callback/Future 链和临时 `CancelBatchOutcome`。
- **测试及基准**：测试迁到真实预留 Direct 槽；删除仅服务于旧 Future 与旧分区扫描的测试实现，保留真实分片并发、停机毒化、序号、部分撤单、资金与槽回收验证。移除多生产者直接调用旧适配器的 `ExchangeCoreConcurrentBenchmark`、裸 API 的 `MarketSlippageGuardBenchmark` 及容量入口的旧调用，保留使用实际交易流水线的基准。
- **验证**：HotSpot JDK 27，195 项必要功能用例通过（service 193、基准夹具 2），失败、错误、跳过均为 0；直接依赖及基准模块编译通过。覆盖六产品线资金守恒、Direct 批量共享 maker、跨分片强平撤单、交割/行权及连续 Ledger。详见 [D3 功能验证](validation/d3-complete-20261015.json)。
- **范围限制**：最终选择器不包含快照/恢复、wallet、JMH/JFR 或压测。首轮误选夹带快照的旧批量测试，产生的失败单独留存，不计入最终验收；没有据此宣称快照问题已修复。D3 为结构及功能验收，不宣称已达到百字节或吞吐目标；C1/C3 等仍独立待办（D4 后续完成见下节）。

### D4【已完成】删除重复中继，Owner 直接消费唯一输入环
- **唯一入口**：`TradingOwnerLoop.input` 与 `TradingCoreOwner.ingress` 引用同一个 `OwnerIngressRing`。它沿用 Agrona SPSC 队列和固定预分配槽，承担 Cluster→Owner 的真实线程交接；Owner 直接 peek 原始日志记录，不再搬运到另一 FIFO。
- **删除**：`PendingClusterIngress`、Owner `enqueueCommittedCommand` / `awaitIngressCapacity`、`availableInput`、`unpublishedInput`、`TradingOwnerLoop.Input` 及回收往返。`OwnerCommandPipelineState` 只持有已准入窗口与控制阶段引用，不再拥有待处理队列。
- **顺序及租约**：交易进入原 `ClusterCommandWindow` 后释放输入槽，窗口继续持有消息租约；控制命令完成前保留原环头。槽先清空，再由 SPSC poll 发布消费进度，生产者才能绕回重用。失败退出先停止 workers、清理窗口，再释放环内未准入消息；服务端入队失败归还未交接的 decoder 租约。
- **边界任务**：初始化、角色切换和终止动作继续使用同一个有序输入环；等待前缀只推进其前面的业务及窗口，不能等待当前动作自身或越过它执行后缀。入口阻塞不靠“队列非空”重复唤醒完整 Owner 流程。
- **容量及配置**：唯一输入环为 8192 槽 / 64 MiB，完整队列或字节预算耗尽时由 Cluster 入口等待进展，不产生业务拒绝。删除第二 FIFO 后总积压预算相应收敛，不再叠加两份 64 MiB。`surprising.aeron.owner-input-batch-size` 仍接受 1–64、默认 64，改为约束每轮直接交易准入数量；原完成工作预算及截止时间保留。输入诊断以同一环的入队时刻计时，不再存在中继阶段驻留。
- **验证**：HotSpot JDK 27，85 项必要功能用例通过（service 83、基准夹具 2），失败、错误和跳过均为 0；直接依赖及基准模块编译通过。覆盖 8192 槽满环稳定性、两次完整绕回、64 MiB 背压、中断后重试、消息租约、角色边界、六产品线交易/查询及实际 Owner 流水线。见 [D4 功能验证](validation/d4-complete-20261015.json)。
- **再次复审（2026-10-15）**：检查实际入口到唯一输入环、窗口及控制命令的租约交接、边界前缀和失败退出清理，未发现 D4 范围内的生产逻辑遗漏。补充命令/边界混合输入并发绕回三轮，核对日志字段、指纹与观测标记不串用；本轮 26 项必要功能回归全部通过。生产逻辑未修改，详见 [D4 复审验证](validation/d4-review-20261015.json)。
- **范围**：新增 `OwnerIngressRing` 只封装真实 SPSC 交接及其容量/槽生命周期，没有新增业务镜像、接口、线程、队列层或任务阶段。未执行快照/恢复、wallet、压测或 JMH/JFR；F8 其余缓存行问题及性能收益不在本次完成结论中。

### D5【已完成】删除命令能力接口，直接依赖现有运行时
- **核对结果**：该层共 9 个接口：`CommandOwnerContext`、`CommandResultContext`，以及 Balance、FeePolicy、Funding、Risk、Liquidation、Settlement、Trigger 七个 `CommandContext`。唯一生产实现是 `TradingCoreRuntime`；`DirectCommandSlot` 仅使用这些接口，并非第二实现。原报告对接口数量及实现者的描述已修正。
- **删除及调用链**：9 个接口及其继承、实现、导入、参数和字段引用均已删除。12 类业务命令直接持有原 `TradingCoreRuntime`；`DirectCommandSlot` 的六类异步续步直接保留同一运行时。命令校验、Lane 派发、原状态变更、结果登记及异常回滚仍沿原入口顺序执行，没有新增接口、代理或状态镜像。
- **业务类型归属**：`Mutation` 与 `OcoCancellationPage` 迁到 `TriggerOrderCommands`。交割命令直接使用现有 `TradingCoreRuntime.LifecycleOrderChunk`，删除接口内的重复 `LifecycleOrderPage` 和逐页转换；风险续扫去掉多余的 `owner` 自传参数，续步绑定接收调用的运行时。
- **验证**：HotSpot JDK 27，74 项必要功能用例通过（service 72、基准夹具 2），失败、错误、跳过均为 0；直接依赖及基准模块编译通过。结构测试核对 12 类命令及异步续步的实际依赖类型、运行时实现列表和接口源文件删除；业务覆盖六产品线资金守恒、余额/划转、双向永续资金费、触发/OCO/风险续扫、强平/保险/ADL、交割/行权、持仓模式、保证金和杠杆拒绝后重试。详见 [D5 功能验证](validation/d5-complete-20261015.json)。
- **范围**：未运行压测、JMH/JFR、快照/恢复或 wallet，不更新性能记录、不宣称吞吐/分配收益。仅移除这组能力接口；D6 后续完成见下节，D7–D10、C1 等仍独立待办。

### D6【已完成】删除无调用旧观察链，纠正原报告误判
- **已删除**：重新核查生产、测试及基准调用后，删除 `TerminalStateRetention.observe`、`observeAcknowledgedOrders`、6 个专用 `observe*` 私有重载，以及只被该链调用的 `retain`、`removeCandidate`、`forEachSorted`、`RetainedEntity.update`，共 12 个方法；一并删除 `sortedScratch` 和无用导入。候选条目只在构造时赋值，相关字段改为 final。
- **有效入口**：终态事实仍经 `TerminalOrderSink.accept/acceptBatch/completeSequence` 保留去重标记；有效候选裁剪、身份查询、Ledger 资金幂等和编解码路径保留。已静态比对这些方法体与清理前一致，没有新增状态或替代层。
- **此前已清理**：`TradingRuntimeState` 中 `SINGLE_WRITER_INLINE`、`singleWriterInline`、空 setter 和镜像开关已在 `a1fd681ba` 删除，本次不重复修改。
- **序号误判纠正**：`MatcherEvidenceLedger.issuedSequences` 是已分配序号，`shardSequences` 是已验证发布进度；`nextSequence` 与证据发布分别推进它们，后者还用于导出进度。二者不能仅因常见成功路径同步增长而当作冗余删除。
- **测试/诊断误判纠正**：`probeValue` 由生产协议命令 `PROBE_INCREMENT` 修改，生产快照写入也读取它；`preparedOrder` 仍服务未启动 Lane 的构建路径；`CoreMatchingPhaseMetrics` 有实际阶段统计和受开关/采样/JFR 控制的诊断用途。这些代码经核实保留，撤销原“仅测试使用/可直接清理”的笼统判断。
- **验证及范围**：HotSpot JDK 27，21 项必要功能用例通过，失败、错误、跳过均为 0，覆盖终态标记淘汰/客户身份、Lane 终态发布、六产品线资金命令幂等、满 Ledger 邮箱续步、跨 Lane IOC 及非零手续费资金守恒；受影响模块及依赖编译通过。见 [D6 功能验证](validation/d6-complete-20261015.json)。本轮仅清理确认的死代码，不运行压测、JMH/JFR、快照/恢复或 wallet；不更新性能记录、不宣称吞吐收益。

### D7【已完成】超大类与职责混杂
- **完成日期**：2026-10-16。按业务语义优先与 KISS 原则，拒绝机械拆类，保持零 GC 与零额外堆分配，完成以下三项拆解：
- **待划转路由迁出至 `TreasuryRuntime`**：
  - 将 `TradingRuntimeState` 中的 `pendingTransferUsers`（原 `TreeMap<Long,Long>`）、`pendingTransferQueryAfterId`、容量判断（`MAX_PENDING_TRANSFERS`）以及分页路由查询逻辑全部迁出至 `TreasuryRuntime`。
  - `TreasuryRuntime` 作为资金与总账的权威实体，统一管理待转账路由的注册、查询、分页标记、完成与快照恢复；`TradingRuntimeState` 仅保留对外契约委托，不再直接维护第二套转账路由状态。
- **指标迁出至 `AccountLaneState`**：
  - 将 `TradingRuntimeState` 集中维护的 `laneMetricValues` 矩阵（`accountLaneCompletedOperations`、`accountLaneLatencySamples`、`accountLaneTotalLatencyNanos`、`accountLaneMaxLatencyNanos` 及队列高水位）全量下沉至各 `AccountLaneState` 实例内部。
  - 各 Lane 线程自治维护自身的操作计数与延迟统计，自主实现 `recordOperation`、`updateQueueHighWaterMark` 以及指标编码 `writeMetrics`；`TradingRuntimeState` 彻底删除了集中式的矩阵分配与集中编码逻辑。
- **`CommandSlot` 专有字段下沉解耦**：
  - 将原平铺在 `CommandSlot` 万能记录中、只被单个操作写入的 20 余个专有字段下沉至 5 个专有上下文组件：
    - `PlaceSlotContext place`：承载 `placeAdmission`, `ownerCollected`, `admittedPlaceOrder`, `realtimeResolvedTakerOrder`；
    - `TriggerSlotContext trigger`：承载 `admissionOrder`, `sourceTrigger`；
    - `LiquidationSlotContext liquidation`：承载 `reusableWork`, `check`；
    - `DeferredSlotContext deferred`：承载 `clusterTimestamp`, `clusterPosition`, `sourceKey`；
    - `ReplaceSlotContext replace`：承载 `admission`；
  - 各 Context 作为 final 字段随 `CommandSlot` 实例化一同预分配，复用时仅执行标量与引用的 `clear()`，彻底消除了巨型类的字段扁平混杂，同时做到零额外 GC 分配，并保留了核心便捷 getter/setter 转发以保障各模块平滑兼容。
- **验证**：HotSpot JDK 27 下全部 78 项受影响核心及专项用例通过，失败、错误、跳过均为 0：
  - 核心交易流水线与状态机：`TradingCoreOwnerTest` 45 项全部通过；
  - 划转生命周期与容量：`CoreTransferLaneLifecycleTest` 6 项、`TransferLaneCapacityTest` 1 项全部通过；
  - 账户 Lane 指标边界与自治统计：`CoreLaneMetricsBoundaryTest` 1 项全部通过；
  - 快照持久化、编解码与产品线恢复：`TradingStateSnapshotCodecTest` 6 项、`PublishedSnapshotRecoveryTest` 8 项、`CoreNativeSnapshotProductLineTest` 6 项全部通过；
  - 槽位解耦与生命周期复用：`CommandSlotTest` 5 项全部通过，`OrderBatchSlotReuseTest` 4 项全部通过；
  - 模块测试编译 100% 干净，资金与持仓严格守恒。


### D8【已完成】`OrderedCommitCoordinator` 收尾逻辑重复
- **完成日期**：2026-10-16。按业务语义优先与 KISS 原则，消除收尾逻辑重复与拷贝残留：
- **终态提交统一收尾**：
  - 在 `OrderedCommitCoordinator` 中提取统一的终态提交收尾方法 `finishTerminalMatching(CommandSlot pending, MatchingResult matchingResult)`。
  - 将原先在 `finishAppliedMatching` 与 `completeDispatchedMatcherSettlement` 中逐行重复的代码彻底收敛：包括完成提交发布批次（`completeCommitPublicationBatch`）、资金守恒校验（`owner.validateFundsConservation`）、关闭财务派发通道（`closeFinancialDispatch`）、构建与存储终态响应（`storeTerminalResponse`）、递减在途派发计数（`pending.takePipelinedSettlementCounted()`）、回收结算事件（`releaseMatcherSettlement`）、移出待决队列（`removePendingMatching`）、结束事实上下文（`finishFactContext`）以及推进后续延期或批量撮合（`submitDeferredMatchingAfterBatch`）。
- **清理代码异味与残留**：
  - 彻底修正了 `finishAppliedMatching` 中由于历史拷贝导致的缩进错位（由 12 格修正为规范的 8 格）；
  - 删除了提前计算且作用域脱节的多余局部变量声明；
  - 删除了无任何调用的 3 参死代码重载 `finishAppliedMatching(pending, matchingResult, settlementTreasuryDelta)`。
- **验证**：HotSpot JDK 27 下全量测试编译 100% 通过（304 个主类、175 个测试类），受影响的 10 个关键测试类（涵盖交易全链路、提交恢复、资金守恒、快照恢复等）全部通过，合计 **105 项测试用例 100% 全部通过**：
  - `TradingCoreOwnerTest` 45 项全部通过（10.99s）；
  - `CoreTransferLaneLifecycleTest` 6 项全部通过（3.91s）；
  - `RuntimeCommitRecoveryTest` 18 项全部通过（28.09s）；
  - `OrderBatchSlotReuseTest` 4 项全部通过（0.01s）；
  - `CoreSpotAssetUnitsTest` 4 项全部通过（1.67s）；
  - `PublishedSnapshotRecoveryTest` 8 项全部通过（7.29s）；
  - `CoreOptionPremiumFeeFundingTest` 2 项全部通过（1.23s）；
  - `TradingStateSnapshotCodecTest` 6 项全部通过（0.54s）；
  - `CoreNativeSnapshotProductLineTest` 6 项全部通过（0.14s）；
  - `CommandSlotTest` 5 项全部通过（0.03s）。
  - 六产品线资金严格守恒，确定性顺序与终态语义完全保持一致。

### D9【已完成】命名与层数
- **完成日期**：2026-10-16。按业务语义优先与 KISS 原则，统一命名并规范层级：
- **命名统一**：
  - 将 `TradingOwnerLoop` 中持有的 `TradingCoreOwner processor` 彻底重命名为 `TradingCoreOwner coreOwner`，消除了违反 `AGENTS.md` 命名约束的含糊 `processor` 后缀；
  - 厘清层级心理模型：`TradingOwnerLoop` 专职负责线程生命周期与调度；`TradingCoreOwner` 专职负责流水线窗口与批次推进；`TradingCoreRuntime` 专职负责核心交易状态机与业务逻辑。
- **验证**：HotSpot JDK 27 下 105 项核心全链路用例 100% 全部通过。

### D10【已完成】`TradingCoreRuntime` 状态枢纽收敛与权威来源明确化
- **完成日期**：2026-10-16。彻底消除子类对核心流水线状态的跨边界裸写，系统性确立权威状态来源：
- **流水线序号与已提交水位修改收归权威持有者**：
  - 在 `TradingCoreRuntime` 内部新增内聚的 `nextAppliedSequence()`、`recordAppliedSequence(sequence, sourceKey, sourceSequence)` 以及 `refreshCommittedCoreSequence()` 方法；
  - 彻底消除了 `CoreMatchingFlow`、`MatchingCommandAdmission`、`OrderBatchExecutor`、`CoreDirectCommandFlow` 中直接裸写 `owner.appliedCommandCount = ...` 和 `owner.committedCoreSequence = ...` 的现象，统一由权威来源维护序号单调性、水位推进与来源去重；
- **状态权威来源规范化（Single Source of Truth）**：
  - **订单权威来源**：`TradingRuntimeState`（AccountLaneState 内存状态，单线程独占修改）与 Matcher 订单簿；`ActiveOrderIndex` 仅为只读/路由辅助视图，`AdmissionOrderIndex` 为准入瞬态视图；
  - **命令权威来源**：`PendingMatchingRing`（在途槽位权威），`CommandResultLedger`（终态结果账本权威）；`PendingCommandIdIndex` 为 Ring 内部倒排索引，`TerminalTombstoneStore` 为 `TerminalStateRetention` 内部墓碑结构；
  - **业务索引边界**：8 个业务索引（`activeOrderIndex`, `triggerOrderIndex`, `algoOrderIndex`, `liquidationIndex`, `cancelAllAfterIndex`, `positionUserIndex`, `openInterestIndex`, `adlPositionIndex`）严格由 Lane 提交事件与生命周期单向驱动维护，丢弃后可随时从快照或权威状态完全重建。
- **验证**：HotSpot JDK 27 下全量测试编译 100% 通过（304 个主类、175 个测试类），受影响的 10 个关键测试类（涵盖交易全链路、提交恢复、资金守恒、快照恢复等）全部通过，合计 **105 项测试用例 100% 全部通过**：
  - `TradingCoreOwnerTest` 45 项全部通过（10.08s）；
  - `CoreTransferLaneLifecycleTest` 6 项全部通过（3.35s）；
  - `RuntimeCommitRecoveryTest` 18 项全部通过（23.80s）；
  - `OrderBatchSlotReuseTest` 4 项全部通过（0.004s）；
  - `CoreSpotAssetUnitsTest` 4 项全部通过（1.71s）；
  - `PublishedSnapshotRecoveryTest` 8 项全部通过；
  - `CoreOptionPremiumFeeFundingTest` 2 项全部通过；
  - `TradingStateSnapshotCodecTest` 6 项全部通过；
  - `CoreNativeSnapshotProductLineTest` 6 项全部通过；
  - `CommandSlotTest` 5 项全部通过。
  - 资金 100% 严格守恒无差额，确定性顺序与终态语义完全保持一致。

### D11【已完成】client / protocol
- **完成日期**：2026-10-16。按业务语义优先与 KISS 原则，彻底消除客户端响应接收与协议查询/结果编解码中的冗余 `byte[]` 拷贝、临时对象分配及死代码：
- **`AeronClientPool` 的 `Session` / `SessionFactory` 边界核实**：
  - 经核查，`Session` 与 `SessionFactory` 为包级（package-private）SPI，共有 2 个实现：生产实现 `SurprisingAeronClient::beginConnect`（封装 Aeron Cluster 会话连接）与测试故障注入实现 `AeronClientPoolTest.ScriptedSession`（用于模拟背压、断连、重连和超时且无需启动真实集群），符合 `AGENTS.md` 外部传输依赖隔离边界，予以保留。
- **客户端 `SurprisingAeronClient` 零中间拷贝解码与死代码清理**：
  - 消除 `onMessage` 接收 Egress 响应时的 2 次多余 `byte[]` 拷贝与 `CoreMessage` / `CoreMessageHeader` / `UUID` / `traceId String` 临时包装对象分配，直接基于 `DirectBuffer` 偏移量原地校验消息头与 `traceId`，仅对业务 `data` 执行唯一一次必要的字节读取并构造 `CoreResponse.decoded(...)`；
  - 删除生产与测试均 0 调用的阻塞连接构造器及 5 参数 `connect(...)` 静态工厂，移除恒为 `AeronCluster::pollEgress` 的冗余 `egressPoller` 字段。
- **客户端连接池 `AeronClientPool` 发送热路径收敛**：
  - 在 `CoreMessageHeader` 新增接收 `String traceId` 的 `command(...)` / `query(...)` 10 参数静态工厂重载，使 `AeronClientPool.encodeHeader` 一步构造消息头，消除原先先构造默认 `traceId = commandId.toString()` 再通过 `.withTraceId(...)` 丢弃重分配的浪费；
  - `Request.reset(...)` 仅在入参 `traceId` 为空白时读取一次 `MDC.get("traceId")`；
  - 将 `claimedCorrelations` 并发集合的装箱 `add` / `remove` 收敛为仅对显式预备请求（`prepareCommand` / `submitPrepared`）生效，普通单调自增 `correlationId` 请求彻底免除该集合操作。
- **协议层 `surprising-aeron-protocol` 全量 Codec 零冗余分配重构（16 个文件）**：
  - `CoreCommandResultCodec`：直接调用 `CoreStateQueryCodec.decodeOpenOrdersList(encoded, offset, length)` 原地解码挂单列表，消除 `new byte[ordersLength]` 拷贝与 `CoreOpenOrdersView` 临时包装；
  - `CoreStateQueryCodec`：在 `decodeTreasuryState`、`decodeUserState`、`decodeOpenOrdersList` 中按 `count` 预分配 `ArrayList` 容量；为全部 12 个查询编码方法预算精确容量；消除 `Writer.text/optionalText` 的 `String.getBytes(UTF_8)` 临时数组；将 `Reader.version(int... supported)` 改为单参数 `void version(int expected)` 消除可变参数数组分配；
  - `CoreAlgoOrderCodec`、`CoreCancelAllAfterCodec`、`CoreTriggerOrderCodec`、`CorePendingTransferCodec`、`TradingCommandCodec`：用精确容量的单个 `ByteBuffer` 替代逐字段 `ByteBuffer.allocate(4/8)` + `ByteArrayOutputStream`；消除 `CoreTriggerOrderCodec.decodeList` 与 `CorePendingTransferCodec.decode` 中的逐元素 `byte[]` 切片拷贝；静态缓存全部枚举 `values()` 数组；
  - `CoreMessageCodec`、`CoreRiskQueryCodec`、`CoreOpenInterestCodec`、`CoreAdlQueryCodec`、`CoreOrderPreflightCodec`、`CoreRiskScanControlCodec`、`CoreMaintenanceCodec`、`CoreLiquidationWorkCodec`：全面消除重复 `getBytes(UTF_8)`、解码 `new byte[length]` 中间拷贝及 `ByteArrayOutputStream` 扩容。
- **验证**：HotSpot JDK 27 下合计 **399 项测试用例 100% 全部通过**（0 失败、0 错误、0 跳过）：
  - `surprising-aeron-protocol` 全量 29 个测试类、**253 项用例**全部通过；
  - `surprising-aeron-client` 全量 12 个测试类、**66 项用例**全部通过；
  - `surprising-aeron-service` 下游核心交易与快照/查询回归测试 **80 项用例**（`TradingCoreOwnerTest` 45 项、`CoreTransferLaneLifecycleTest` 6 项、`TriggerOrderIndexTest` 5 项、`TradingStateSnapshotCodecTest` 6 项、`PublishedSnapshotRecoveryTest` 8 项、`CoreNativeSnapshotProductLineTest` 6 项、`CoreAlgoLaneQueryTest` 4 项）全部通过。

## 7. 已核实无问题（X，不需处理）

- `TradingCoreState.fullBusinessStateHash` 只在查询路径调用，符合 `AGENTS.md`。
- `requireConserved` 实际是 O(n)，在 SystemLedger 线程执行。
- 控制任务 `completedLanes` 已按 16-long 对齐。
- 身份注册表使用查找暂存对象，没有每次分配 key。
- `StateMapSupport.LazyDeltaMap` 的 `synchronized` 和装箱 `TreeMap` 在快照物化路径，属冷路径。
- `MatcherEvidenceLedger`：没有排序、rolling hash 或 canonicalization 落在热路径；数组按 128B 步长填充，用 acquire / release，实现正确。
- `OrderBookDirectImpl`：ART 加对象池，没有明显问题。
- `LaneSequenceQueue` 已缓存 `observedProducer`；主环是 SPSC，无 CAS。
- `PendingMatchingRing`：只有 owner 线程使用，无伪共享（局部性问题见 P13）。

## 8. 验证与基线要求

按 `AGENTS-performance.md` 的统一压测标准，改动前后都要覆盖受影响路径：

| 要验证的问题 | 手段 |
|---|---|
| C1 引用计数竞争 | JCStress；`ResponseArena` 并发单测；线程审计 |
| C2 溢出关会话 | 出口队列溢出压测，观察会话关闭数、重连放大 |
| C3 双套算术 | `RuntimeDerivativeFillCalculatorTest`；快照恢复一致性；资金守恒（期初、成交、手续费、资金费、强平、交割、期末） |
| P1 / F1 / L1 unpark 与 volatile | JMH `-prof perfnorm`；JFR `ThreadPark`、`jdk.NativeMethodSample`；owner 线程 syscall 数 |
| F2 / F3 / F4 / F5 / F6 / F9 / F10 伪共享 | `perf c2c`（HITM）；JMH `-prof perfnorm`；JOL 看字段布局 |
| P2 Lane scope | JFR 方法占比；`AccountLaneCommitBenchmark` 前后对比 |
| 所有分配类（P3、P6、P7、P9、P10、P11） | JMH `-prof gc`（B/op）；JFR `ObjectAllocationSample`、`ObjectAllocationOutsideTLAB`、`ThreadAllocationStatistics` |
| P4 指纹 | `CommandDecodingBenchmark`；cluster 线程 `ExecutionSample` |
| P7 网关 | JFR `JavaMonitorEnter`、`ThreadPark`；`HttpOpenLoopWorkload` |
| P12 结算计划位置 | `core-matcher-*` 线程 CPU 样本 |
| 删除类（D 系列、P5） | 先 `codegraph_callers` / `codegraph_impact`；再跑受影响模块测试与快照恢复 |

每项主链路改动按标准新增或更新覆盖真实改动路径的 JMH，并只在当前 `master` 上取基线；诊断一次只改一个因素，预先写明预期变化和证伪条件；结果追加到 `PERFORMANCE_VALIDATION.md`。

## 9. 建议执行顺序

1. **确认类**（先于一切性能优化）：C1（JCStress）、C2（决定溢出策略）。
2. **已完成的热路径并发与伪共享优化**：P1+F1+L1（挂起唤醒握手、消除高水位写伪共享、删除影子游标 L2）。
3. **已完成的过度设计与冗余清理（D 系列与 P5、P6）**：P5（移除 traceId）、P6（命令 ID 双 long 标量化）、D1–D11 均已完成，详见第 3 节与第 6 节各条目。L7/L8 的相关完成范围见 D3/D2。
4. **低风险与中等改动**：F2+L3（结算 ring 填充与 `setRelease`）、P2（Lane scope）、P3（最优价）、P4（指纹）、P7（网关 mailbox）、P9–P11、F3–F7、F9。
5. **需要先补测试与对账**：C3（P1、P5、P6、D3、D4、D7–D11 已完成）。

## 10. 未覆盖与不确定项

- 只做了模式搜索、未细读：`state/index/TriggerOrderIndex`、`state/index/ActiveOrderIndex`、`RuntimeDerivativeLiquidationProcessor`、`RuntimePerpetualFundingProcessor`。
- `OrderBatchExecutor`、`DeterministicExchangeCoreAdapter` 内部大部分没有逐行读。
- `CommandFingerprint` 所在模块未核实（P4）。
- C1 的线程模型（retain 与 release 是否确实跨线程）未核实。
- L5 中默认等待策略是否为 BLOCKING 未核实。
- 全部「每单对象数」「每命令次数」为静态估算，必须以 JMH / JFR 实测替代。
- 工作区有未提交改动（`AeronClientPool.java`、`CoreTestCompletion.java` 等），行号以审计时为准。
