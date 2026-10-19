# 交易主链路架构与基于 Aeron 特性的极致精简优化分析及落地报告

> **分析与实施背景**：结合金融高频交易系统（HFT / Low-Latency Matching Engine）的最佳实践以及 Aeron / Mechanical Sympathy 核心哲学，全面穿透审查当前交易所后端交易主链路，剖析并彻底解决项目中的**内存堆分配（GC 诱因）、跨核缓存行失效（MESI 颠簸）、锁竞争与队列开销**。

---

## 一、交易主链路全景拓扑与现状剖析

当前交易主链路从网络报文入站到结果广播，包含以下核心阶段：

```
[客户端报文]
    │
    ▼ (UDP/Raft 日志)
[Aeron CM / Archive]
    │
    ▼ (Aeron Service 线程)
[onSessionMessage] ── (解码 CoreMessage)
    │
    ▼ (跨线程 OwnerIngressRing)
[TradingCoreOwner 线程] ── (两阶段流水线: 退休队首 / 准入新单)
    │
    ├── 撮合类命令 ──► [core-matcher-x 线程] ──► (LMAX 订单簿撮合计算)
    │                        │
    │                        ▼ (跨线程结算事件环)
    └── 资金/结算类 ──► [core-settlement-lane-x 线程] ──► (用户账户/持仓扣减)
                             │
                             ▼ (跨线程无锁环)
                    [core-system-ledger-lane 线程] ──► (平台手续费/保险基金总账 + 逐笔资金守恒校验 + 全局持仓索引)
                             │
                             ▼ (跨线程 RealtimeOutbox)
                    [realtime-aeron-sender 线程] ──► (Aeron IPC 广播给下游)
```

---

## 二、核心瓶颈定位与彻底解决详情

### 1. P0-1：消除 `OrderBatchExecutor` 批量下单与撤单的 Lambda 闭包堆分配（100% Zero-GC）
- **根因定位**：
  - 单笔订单在 `CommandSlot` 中已实现 `PlaceMatching` / `CancelMatching` 复用，但批量执行器 [`OrderBatchExecutor.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/OrderBatchExecutor.java) 在 `submitPipelinedPlaceBatch` 和 `submitCancelBatchChunk` 中遗留了两个捕获 4~6 个局部变量的 Lambda 闭包。
  - 在高频批量做市场景下，每次批量提交都会在堆上分配匿名内部类实例，引发年轻代 GC 压力与长尾毛刺。
- **落地改造**：
  - 在 [`OrderBatchExecutor.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/OrderBatchExecutor.java) 中新增静态可复用内部类 `PipelinedPlaceBatchSubmission` 与 `CancelBatchChunkSubmission`（提供 `prepare(...)` 与 `clear()` 方法）；
  - 将其预分配并绑定至 [`OrderBatchPending.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/OrderBatchPending.java) 槽位生命周期中，在 `OrderBatchPending.clear()` 时同步清理引用，实现批量撮合提交路径 **0 闭包分配**。

### 2. P0-2：全链路推广游标本地缓存（Cached Cursor Pattern）与缓存行填充
- **根因定位**：
  - 在多核高频流水线中，生产端与消费端若在每次循环中无条件读取对端线程写入的 `volatile` 游标，会导致 CPU L1/L2 缓存行频繁失效（MESI 总线风暴）。
- **落地改造**：
  1. **账户结算环 [`SettlementLaneWorker.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/SettlementLaneWorker.java)**：
     - 生产端引入 `cachedConsumerSequence` 与 `cachedAdmissionConsumerSequence`，仅当本地计算的队列深度触及容量上限时才跨核读取 `consumerSequence.value` / `admissionConsumerSequence.value`；
     - 消费端 `run()` 循环引入本地 `cachedProducerSeq` 与 `cachedAdmissionProducerSeq`，在批量连续消费积压任务时完全免除对 `producerSequence.value` 的重复跨核读取。
  2. **撮合通道 [`MatcherCommandPipeline.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/MatcherCommandPipeline.java)**：
     - Owner 消费侧引入 `cachedCompletedPosition`，在 `completedMatchingSequence()`、`pollResult()` 与 `skipReadyDirectSlots()` 中消除对 Matcher 线程 `completedPosition.value` 的高频重复跨核读取；
     - Matcher 消费侧引入 `cachedSubmitted` 与 `cachedConsumed`，特别消除了原先每处理一笔订单都为了计算 `completionHighWaterMark` 而跨核读取 Owner 线程 `consumedPosition.value` 的严重缓存行冲突（仅在 `position - cachedConsumed > completionHighWaterMark` 时刷新）。

### 3. P1-1 & P1-2：锁竞争消除与 `SystemLedgerLane` 零伪共享 RingBuffer 升级
- **根因定位**：
  - [`DeterministicExchangeCoreAdapter.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java) 中 `activated()` 状态查询使用了 `synchronized` 重量级方法锁；同时其热路径 `ensureSymbol(int)` 已经通过每个 Matcher 线程独占的 `MatcherCommandScope.registeredSymbols`（`IntHashSet`）走无锁单线程快路径。
  - [`SystemLedgerLane.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/SystemLedgerLane.java) 承担着逐笔成交的平台手续费归并、资金守恒校验（`funds.requireConserved`）与全局持仓索引更新（`OpenInterestIndex` / `PositionUserIndex` / `AdlPositionIndex`），并由 20+ 个类的线程安全契约强绑定。但其原先基于 `OneToOneConcurrentArrayQueue`，每次循环执行 `isEmpty()` + `peek()` + `poll()` 会产生多达 4~5 次 volatile 读，且在等待 AccountLane 完成（`!entry.ready()`）期间反复重读队列头。
- **落地改造**：
  - 将 `DeterministicExchangeCoreAdapter.activationComplete` 升级为 `volatile boolean`，移除 `activated()` 的 `synchronized` 监视器锁；
  - 将 [`SystemLedgerLane.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/SystemLedgerLane.java) 内部队列重构为带左右各 120 字节缓存行填充（`PaddedSequence`）的单生产者单消费者位掩码 RingBuffer，配合生产端 `cachedConsumerSequence`、消费端 `cachedProducerSeq` 以及队首元素寄存器驻留（`Entry head`），在等待账户结算就绪期间实现 **0 队列访问**，并在 `BLOCKING` 策略下支持精确 `unpark` 唤醒。

---

## 三、验证与全链路压测对比结果

### 1. 单元测试与集成测试
- 运行 `surprising-aeron-service` 核心测试套件（含 `SystemLedgerLaneTest`、`SystemLedgerControlTest`、`MatcherTreasurySettlementTest`、`OrderBatchSlotReuseTest`、`CoreOrderedOrderBatchTest`、`SettlementLaneWorkerTest`、`MatcherCommandPipelineTest`、`TradingCoreOwnerTest`、`ClusterCommandPipelineTest`），**449 个测试用例 100% 通过（0 Failures, 0 Errors）**。

### 2. 四阶段全链路极限异步压测（`qualify-aeron-async-stages.sh`, Window=256, FILL_HEAVY）

| 压测阶段 (Stage) | 优化前吞吐 (`businessOpsPerSec`) | 优化后吞吐 (`businessOpsPerSec`) | 吞吐提升幅度 | 优化后 P99 延迟 | 资金守恒与一致性核验 |
| :--- | ---: | ---: | ---: | ---: | :--- |
| **`end_to_end`** | 103,759.29 ops/s | **136,175.67 ops/s** | **+31.24%** | 135,266 us (原 198,279 us, **-31.8%**) | `mixedVerify=PASS fundsDiff=0` |
| **`lane`** | 188,835.75 ops/s | **229,126.47 ops/s** | **+21.34%** | **52,396 us** (原 119,436 us, **-56.1%**) | `mixedVerify=PASS fundsDiff=0` |
| **`matcher`** | 201,603.41 ops/s | **228,955.34 ops/s** | **+13.57%** | 86,179 us (原 119,613 us, **-27.9%**) | `mixedVerify=PASS fundsDiff=0` |
| **`owner`** | 49,336.42 ops/s | **52,809.98 ops/s** | **+7.04%** | 250,609 us | `mixedVerify=PASS fundsDiff=0` |
