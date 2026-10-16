# 核心批次交易链路并发隔离与测试套件修复报告

## 1. 概述与核心背景

在 Surprising-EX 交易核心系统中，撮合（Matcher）与账户结算分片（Account Lane）采用了严格的无锁/单线程归属（Lane Ownership）并发架构：
- 每个 Account Lane 负责独立的用户账户分片，所有对分片内用户余额、持仓、预占（Reservation）、订单的写操作必须在对应的 Lane 线程中串行执行；
- 当 `accountLanesStarted == true`（后台 Lane Worker 启动运行）时，Owner 线程或其他外部线程严禁直接跨线程读写 Lane 内存。任何对 Lane 的访问必须通过安全派发通道（`dispatchAccountQuery` / `AccountLaneQueryTestSupport`）、带所有权交接的栅栏（`tryAcquireOwnerLaneAccess`）或快照物化机制（`tradingState()`）完成；
- 订单与预占在准入（Admission）后处于 `pendingReservation` 状态，在撮合完成并完成终态结算前，保持 Lane-owned，对外部视图透明隐藏。

前期在性能优化与批量改动过程中，部分测试直接从主线程侵入读写处于活跃运行状态的 Lane 状态，导致 `CoreOrderedOrderBatchTest` 与 `ClusterCommandPipelineTest` 触发了密集的线程所有权冲突（`IllegalStateException: account lane X is bound to another thread` / `active account access must run on its owning Lane thread`）。

本次任务彻底排查并根治了上述并发断言问题，坚决捍卫了 Lane 线程所有权与状态权威性，所有测试 100% 通过。

---

## 2. 关键源码修改与技术实现

### 2.1 状态变更与快照捕获收归 Lane 内部
在 [`TradingRuntimeState.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java) 中：
- 将 `putBalance`、`replaceReservation`、`removeReservation`、`replaceBalance`、`removeBalance`、`removePosition`、`putPosition` 中原本在 `onLane` 外部主线程调用的 `captureBefore` 安全收归到 `onLane` 内部执行；
- 杜绝了主线程在未进入 Lane Scope 前直接访问 Lane 内存导致跨线程竞争的风险，保证变更捕获与实际状态修改的严格原子性。

### 2.2 统一 Scoped Lane 读取与 pendingReservation 隔离一致性
在 [`TradingRuntimeState.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java) 的 `order(long orderId)` 与 `reservation(long orderId)` 中：
- 此前在 `scoped != null`（Lane 内部 Scope）时，直接返回了 `scoped.orders.get(orderId)` / `scoped.reservations.get(orderId)`，未对处于 `pendingReservation` 的未结算订单进行过滤，导致 Lane 查询暴露了内部暂态；
- 完善 `scoped != null` 时的检查：当 `scoped.pendingReservation(orderId)` 为 true 且不处于当前批次修改作用域时，统一返回 `null`，确保内部与外部在未完成结算时的一致不可见性。

### 2.3 测试通道规范化与故障注入所有权交接
1. **测试查询通道规范化**：
   - 将 [`AccountLaneQueryTestSupport.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/AccountLaneQueryTestSupport.java) 设为 `public`，供测试套件通过 `read(runtime, userId, supplier)` 向目标 Lane 派发异步查询，并在 Lane 内部安全读取；
   - 在 [`CoreOrderedOrderBatchTest.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/orchestration/CoreOrderedOrderBatchTest.java) 与 [`ClusterCommandPipelineTest.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/orchestration/ClusterCommandPipelineTest.java) 中，将主线程违规跨 Lane 调用的 `identities.findClientKey(...)`、`runtime.order(...)`、`runtime.reservation(...)`、`runtime.triggerOrder(...)` 全面重构为 `AccountLaneQueryTestSupport.read`。
2. **故意故障注入的所有权交接**：
   - 在 `spotAmendLaneFailureStopsTheBatchAndRecoversFromPriorSnapshotAndLog` 测试中，为故意构造加款溢出（`Long.MAX_VALUE`）测试异常恢复链路，使用 `runtime.acquireOwnerLaneAccess()` 和 `releaseOwnerLaneAccess()` 栅栏保护注入过程，使主线程合法获得临时写入所有权后再交还给 Lane Worker 执行撮合结算，触发预期的 `FatalMatchingDivergenceException` 与算术溢出。
3. **中断批次断言对齐**：
   - 修正 `closeDoesNotRollBackAnInterruptedBatchAfterObservedMatcherFact` 中对未结算项的错误断言，将其从 `isNotNull()` 修正为 `isNull()`，完全符合“中断停机后不回滚已结算事实、未结算项不凭空产生”的业务语义。

---

## 3. 测试验证范围与结果

构建与测试统一在 **HotSpot JDK 27**（`Amazon Corretto-27.0.0.33.1`）及 Maven 3.9.16 环境下执行。

| 测试类 / 测试套件 | 覆盖产品线 / 特性 | 运行测试数 | 结果 |
|---|---|---|---|
| `CoreOrderedOrderBatchTest` | 现货/衍生品 核心批次定序与持久化结算链路 | 42 | **100% PASS** (42/42) |
| `AccountLaneOwnershipTest` | Lane 线程绑定与越界访问屏障防御 | 4 | **100% PASS** (4/4) |
| `LaneClientIdentitiesTest` | ClientOrderId 索引的分片线程所有权机制 | 6 | **100% PASS** (6/6) |
| `ClusterCommandPipelineTest` | 6 大产品线全量命令管道、异步状态推进与故障恢复 | 320 | **100% PASS** (320/320) |
| `TradingRuntimeStateTest` | 交易运行时核心状态原子变更与内存快照 | 全量 | **100% PASS** |

### 未测范围与依据
- 跨进程集群 Raft 选举与物理网络故障注入：本次改动仅涉及节点内部线程模型（Owner / SettlementLaneWorker / Matcher）与状态隔离，不改动 Raft 协议契约；
- Wallet 资金充提外部 RPC：交易核心单元与集成测试环境不启动真实的外部 Wallet 守护进程。

---

## 4. 关键文件变更清单

- [`TradingRuntimeState.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java)：收归 `captureBefore` 到 `onLane` 内部；增强 `order`/`reservation` 的 scoped pending 状态过滤。
- [`AccountLaneQueryTestSupport.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/AccountLaneQueryTestSupport.java)：设为 public 提供标准 Lane 安全查询入口。
- [`CoreOrderedOrderBatchTest.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/orchestration/CoreOrderedOrderBatchTest.java)：修复 42 个用例的断言与注入通道，全部跑通。
- [`ClusterCommandPipelineTest.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/orchestration/ClusterCommandPipelineTest.java)：修复主线程违规跨 Lane 读状态的 4 处测试逻辑，320 个用例全部跑通。
