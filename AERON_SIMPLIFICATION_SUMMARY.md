# Aeron 模块过度设计精简总结

## 1. 概述与核心目标

根据架构极简与单一职责原则（KISS），针对 Aeron 模块中历史遗留的过度设计和多分支维护负担进行了彻底删减与收敛：
1. **单一生产执行模式**：彻底废除 `FUSED`（单线程融合/内联）模式，固定为生产权威的 `PIPELINED`（多阶段异步流水线）模式；删除所有启动模式参数、配置项、内联分支及相关死代码。
2. **单一线程空闲等待策略**：彻底废除 `ADAPTIVE`、`BLOCKING`、`PARKING`、`YIELDING` 等多种等待策略及切换配置，固定为纯 `BUSY_SPIN`（忙等自旋）模式；彻底删除复杂的 `LockSupport.park`/`unpark`、带有伪共享填充的 `Wakeup` 状态类以及跨线程原子唤醒 CAS 状态机。
3. **消除伪共享多层继承 Padding 污染**：将模仿 Disruptor 大量堆叠的 20 余个抽象 padding 继承类（如 `*LhsPadding`、`*MidPadding`、`*RhsPadding`）彻底拍平为单一实体类，清除 200 余个毫无业务意义的 `long p00..p24` 填充垃圾变量。
4. **清理同步死分支与死方法**：在流水线模式下，彻底清除 `TriggerOrderCommands`、`TradingCoreRuntime`、`CoreMatchingFlow`、`RuntimeDerivativeLiquidationProcessor` 中冗余的 `!asynchronousCommands()` 同步死分支，连根拔起 8 个无外部引用的纯同步长调用链。
5. **安全与兼容**：全流程在独立 worktree 中完成编译验证后安全合入主分支，保留本地正在进行的所有未提交改动；全工程编译（`test-compile`）一次性通过，不运行额外测试。

---

## 2. 删减与重构明细

### 2.1 执行模式（FUSED → PIPELINED）彻底收敛
- **删除模式枚举与配置**：
  - 删除了 [`TradingExecutionMode.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/config/TradingExecutionMode.java)。
  - 从 [`CoreRuntimeConfiguration.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/config/CoreRuntimeConfiguration.java) 和 [`application.yml`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/resources/application.yml) 中彻底删除了 `execution-mode` 相关配置项。
- **清除核心运行态分支**：
  - [`TradingCoreOwner.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreOwner.java)：删除了 `fusedDecoder` 状态机、`progressFusedCommands` 及所有 `fused` 逻辑分支，固定直接推进窗口流水线。
  - [`TradingCoreRuntime.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java)：删除了 `enableFusedExecution`，`activate()` 时无条件启动独立 Matcher 线程和 Account 泳道。
  - [`TradingAccountRuntime.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingAccountRuntime.java)：删除了 `fusedExecutionActive` 标志与冗余分支。
  - [`MatcherSettlementDispatcher.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/MatcherSettlementDispatcher.java) & [`MatcherSettlementEvent.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/settlement/MatcherSettlementEvent.java)：删除了 `owner.isFusedExecution()` 内联结算和 `tryApplyInline`。
  - [`OrderBatchExecutor.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OrderBatchExecutor.java) & [`CoreMatchingFlow.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/CoreMatchingFlow.java)：删除了内联准入 `executeInlineIfReady` 检查。
  - [`OwnerCommandPipelineState.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/OwnerCommandPipelineState.java)：移除了单模式/融合模式的 null window 判断，固定创建 `ClusterCommandWindow`。
- **清除死代码与测试**：
  - 删除全工程零引用的 [`CoreMatcherTransition.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMatcherTransition.java) 与 [`CoreFundsPostingView.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreFundsPostingView.java)。
  - 删除双模式对比测试 [`ExecutionModeComparisonTest.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/src/test/java/com/surprising/aeron/service/core/ExecutionModeComparisonTest.java)。
  - 清理基准测试与脚本：`c2c-fused-live.py`、`c2c-business-finish-live.py`、`qualify-linear-perpetual.sh`、`qualify-linear-perpetual-scale.sh` 等。

---

### 2.2 等待策略（固定 BUSY_SPIN）彻底收敛
- **删除配置与枚举**：
  - 从 [`CoreRuntimeConfiguration.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/config/CoreRuntimeConfiguration.java) 和 `application.yml` 中彻底删除了 `wait-strategy`、`WaitStrategy` 枚举以及 `settlement-spin-limit` 属性。
- **移除伪共享填充与线程阻塞唤醒状态机**：
  - [`OwnerIdleStrategy.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/bootstrap/OwnerIdleStrategy.java)：
    - 删除了三层伪共享缓存行填充类 `WakeupLhsPadding`、`Wakeup`（数十个 long 填充字段）、`WakeupRhsPadding`。
    - 删除了 `VarHandle PARK_REQUESTED` 及基于 `LockSupport.parkNanos` 的自适应休眠唤醒逻辑。
    - 简化为纯 busy spin：无任务直接 `Thread.onSpinWait()`，`signal()` 和 `bindOwner()` 留作安全空操作。
  - [`SettlementLaneWorker.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/bootstrap/account/SettlementLaneWorker.java)：
    - 删除了 `waitStrategy`、`spinLimit`、`idleSpins`、`parkUntilWork`、`unparkIfParked` 和 `isParked` 状态。
    - 工作线程循环在无待办任务时直接调用 `Thread.onSpinWait()`，避免任何休眠切换开销。
  - [`SystemLedgerLane.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/treasury/SystemLedgerLane.java)：
    - 删除了 `waitStrategy`、`parked` 标记及所有的 `LockSupport.parkNanos`。
    - 财库账本推进循环无工作时直接 `Thread.onSpinWait()`。
  - [`MatcherCommandPipeline.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/pipeline/MatcherCommandPipeline.java)：
    - 删除了 `waitStrategy`、`Wakeup` 伪共享填充类和 `VarHandle PARK_REQUESTED`。
    - 移除了发布任务时的 `unpark` 跨线程通知逻辑，Matcher 工作循环无工作时直接 `Thread.onSpinWait()`。
  - 构造调用链精简：[`MatcherPipelineGroup.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/pipeline/MatcherPipelineGroup.java)、[`AccountLaneLifecycle.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/bootstrap/account/AccountLaneLifecycle.java)、[`TradingOwnerLoop.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/bootstrap/TradingOwnerLoop.java) 等移除了传递 waitStrategy 的多余重载。

---

### 2.3 拍平多层伪共享继承 Padding 抽象类
针对全工程模仿 Disruptor 风格堆叠的大量冗余 Padding 继承类进行全面拍平，消除了过度工程化产生的噪音：
- [`OwnerIngressRing.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/support/ingress/OwnerIngressRing.java)：
  - 彻底清除环结构中遗留的 30 个显式缓存行填充变量（`p01..p0f`、`p11..p1f`）及 38 行多余 Javadoc，恢复纯粹简洁的字段布局。
- [`SettlementLaneWorker.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/bootstrap/account/SettlementLaneWorker.java)：
  - 拍平外层类的 8 个多层继承抽象类（`SettlementLaneWorkerLhsPadding`、`HandoffRequested`、`MidPadding1`、`HandoffCompleted`、`MidPadding2`、`HandoffResumed`、`MidPadding3`、`Parked`、`RhsPadding`）及残留的 `isParked()` 死方法。
  - 拍平内部邮箱游标类：删除 `PaddedSequenceLhsPadding`、`PaddedSequenceValue`，合并为单一 `PaddedSequence`。
  - 拍平内部结算环：删除 `MatcherSettlementRingLhsPadding`、`MatcherSettlementRingProducerPosition`、`MatcherSettlementRingMidPadding`、`MatcherSettlementRingConsumerPosition`、`MatcherSettlementRingRhsPadding` 5 个继承类及 45 个 `long p00..p2e` 变量，合并为单一 `MatcherSettlementRing`，VarHandle 直接定位当前类。
- [`TradingAccountRuntime.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingAccountRuntime.java)：
  - 拍平提交槽类：删除 `PaddedCommittedSequenceLhsPadding`、`PaddedCommittedSequenceValue`，合并为单一 `PaddedCommittedSequenceSlot`。
- [`SystemLedgerLane.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/treasury/SystemLedgerLane.java)：
  - 拍平内部序号类：删除 `SequenceLhsPadding`、`SequenceValue`，合并为单一 `PaddedSequence`。
- [`LaneSequenceQueue.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/support/concurrent/LaneSequenceQueue.java)：
  - 拍平游标类：删除 `PaddedSequenceLhsPadding`、`PaddedSequenceValue`，合并为单一 `PaddedSequence`。
- [`MatcherCommandPipeline.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/pipeline/MatcherCommandPipeline.java)：
  - 拍平游标类：删除 `PaddedSequenceLhsPadding`、`PaddedSequenceValue`，合并为单一 `PaddedSequence`。
- [`ClusterServiceEgress.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/support/egress/ClusterServiceEgress.java)：
  - 拍平输出游标类：删除 `PaddedOutputProducedPadding`、`PaddedOutputProducedValue`、`PaddedOutputConsumedPadding`、`PaddedOutputConsumedValue` 4 个中间继承类，合并为单一 `PaddedEgressOffsets`。

---

### 2.4 清理同步死分支与长调用链死代码
由于流水线模式下 `asynchronousCommands()` 恒为 true，同步调用分支纯属冗余死代码。本次全面拔除了以下同步死分支与死方法：
- [`TriggerOrderCommands.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/trigger/command/TriggerOrderCommands.java)：
  - 拍平 `advanceCandidate` 与 `submitMutation` 为直接异步派发。
  - 拍平 `executeUpsertAlgoOrder`、`executePlaceTriggerOcoPair`、`executePlaceTriggerOrder`、`executeCancelTriggerOrder`、`executeClaimTriggerOrder`、`executeCompleteTriggerOrder`、`executeUpdateTriggerTrailing`、`executeExpireTriggerOrder`、`executeRetryTriggerOrder`、`executeTriggerOrder(CoreMessage)` 为直接异步调用。
  - 彻底删除 6 个零外部引用的同步 runtime 方法：`cancelTriggerOrderRuntime`、`claimTriggerOrderRuntime`、`completeTriggerOrderRuntime`、`expireTriggerOrderRuntime`、`updateTriggerTrailingRuntime`、`retryTriggerOrderRuntime`。
  - 彻底删除同步 OCO 撤销及分页死代码：`cancelOcoSiblings`、`OcoCancellationPage`、`ocoSiblingPage`、`executeTriggerOrder(long, ...)`、`cancelTriggersForClosedPositions`。
- [`TradingCoreRuntime.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java)：
  - 彻底删除零引用的死方法 `cancelAllOcoSiblings`。
  - 彻底删除长达 70 行的同步订单预留死方法 `reservePlaceOrderRuntime`。
  - 拍平完成阶段中的 3 处同步分支：平仓触发单 `cancelTriggersForClosedPositions` 分支、时间戳校验 `stampOrderChangesRuntime` 分支、泳道变更分发 `stageLaneMutation` 分支。
  - 拍平划转查询中的同步分支，直接返回异步续步 `beginPendingTransferQuery`。
  - 拍平 `applyClusterCommand` 中的异步作用域判断。
  - 拍平 `deferFinalizationRejection` 中的非异步判断。
  - 私有化静态工具方法：将静态文本哈希工具 `mixText` 从 `public` 收敛为 `private`。
- [`RuntimeDerivativeLiquidationProcessor.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/liquidation/RuntimeDerivativeLiquidationProcessor.java)：
  - 删除 `prepareAction` 中 `!runtime.asynchronousCommands()` 的同步读取强平死分支。
- [`CoreMatchingFlow.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/CoreMatchingFlow.java)：
  - 拍平 `submitMatching`，去掉多余的作用域退出控制。
  - 删除 `submitMatchingInCommandScope` 中 `!owner.runtimeState().asynchronousCommands()` 的死分支。
- 基准脚本收敛：
  - 收敛 [`qualify-aeron-async-stages.sh`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh)，固定为单一 `PIPELINED` 执行模式。

---

### 2.6 内联后残留死代码与死字段的深度清理
- **清理背景与目标**：
  在 `TradingOwnerLoop` 完成去线程化与内联重构后，系统不再存在独立的 `trading-owner` 平台线程，原有跨线程边界协调、死等超时参数以及死字段彻底失效。本次在 `master` 分支完成全面拔除：
- **具体清理成果**：
  1. **彻底删除孤立死类 `OwnerIdleStrategy.java` 与对应测试**：
     - 删除 [`OwnerIdleStrategy.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/bootstrap/OwnerIdleStrategy.java) 与 `OwnerIdleStrategyTest.java`（生产代码 0 引用，退避策略已统一固定为忙等自旋）。
     - 同步更新工程架构文档与 README 中的陈旧流程图。
  2. **拔除 `TradingOwnerLoop.stopping` volatile 死字段**：
     - 全工程只有赋值而零读取，彻底删除。
  3. **拔除 `OwnerIngressRing` 的 `Runnable action` 及关联僵尸防御代码**：
     - 在 [`OwnerIngressRing.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/support/ingress/OwnerIngressRing.java) 中移除 `Entry.action` 字段与 `offer(...)` 中的 `action` 参数。
     - 在 [`TradingCoreOwner.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreOwner.java) 中拔除 4 处针对 `next.action() != null` 的僵尸判断（L191, L423, L466, L479）。
     - 同步精简 `TradingOwnerLoop.enqueue`、`ClusteredBatchTradingBenchmark`、`TradingOwnerTestSupport`、`TradingOwnerInputReuseTest`。
     - 重构 [`OwnerIngressRingTest.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/support/ingress/OwnerIngressRingTest.java)：删除针对旧 boundary 动作的专有测试，保留多圈并发纯业务命令入队及按序完成断言。
  4. **消除快照与空闲推进的重复冗余调用**：
     - 移除 `TradingOwnerLoop.captureSnapshot` 与 `captureSnapshotSections` 外部冗余的 `coreOwner.finishCommands()`（内部 `finishSnapshotPrefix` 已严格包含）。
     - 明确 `drainResponses` 职责为“推进 Core 流水线提交并排空 egress 响应”，并将 `AeronTradingClusterService.doBackgroundWork` 简化为单行调用 `owner.drainResponses()`，消除每轮连续调用两次 `pollCommands()` 的重复冗余开销。
  5. **清除废弃配置 `ownerRoleChangeTimeoutSeconds`**：
     - 在 [`CoreRuntimeConfiguration.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/config/CoreRuntimeConfiguration.java) 中移除字段及取值范围校验。
     - 在 `application.yml` 中移除该配置项，并同步更新配置测试 `CoreRuntimeConfigurationTest` 与各模块 README。
  6. **更新基准脚本与监控**：
     - 更新 `summarize-aeron-async-stage.py`，支持匹配内联后的 Cluster 服务线程，消除对独立 `trading-owner` 线程名的单一硬编码依赖。

---

## 3. 本地 Aeron 单节点线性永续（LINEAR_PERPETUAL）压测比对（lane=1 vs lane=2）
- **架构内联背景**：
  - 原先 `AeronTradingClusterService` 运行于 Aeron 容器线程（`ClusteredServiceAgent`），收到消息后经 `OwnerIngressRing` 队列跨核交接给独立的 `trading-owner` 后台线程。
  - 该交接模式不仅增加了额外的线程上下文切换与跨核伪共享/缓存行颠簸（Cache Line Bouncing），而且快照 `captureSnapshot`、角色切换 `onRoleChange` 和服务停止 `terminate` 都被迫通过 `boundary(...)`、`CompletableFuture` 跨线程等待，存在 30 秒超时死等的风险。
- **重构与消除**：
  - [`TradingOwnerLoop.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/bootstrap/TradingOwnerLoop.java)：
    - 移除 `implements Runnable`，删除 `ownerThread`，不再启动后台线程。
    - `start`：在当前 Aeron 容器线程同步初始化 `TradingCoreOwner`、恢复快照、激活流水线，并直接绑定当前线程的 CPU affinity。
    - `enqueue`：入队后立即在当前线程调用 `coreOwner.pollCommands()` 推进流水线准入；若队列满在当前线程自旋排空推进，消除跨线程协调。
    - `drainResponses`：在排水响应前自动检查并推进 `pollCommands()`，收敛流水线终态并生成响应。
    - 同步快照与角色切换：彻底删除 `boundary(...)`、`CompletableFuture` 及 30 秒阻塞死等，所有操作在当前线程同步完成。
    - 优雅终止：`terminate()` 直接同步关闭 `coreOwner` 并释放 CPU 亲和性与输入环。
  - [`AeronTradingClusterService.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/controller/AeronTradingClusterService.java)：
    - 在 `doBackgroundWork(long nowNs)` 中，在空闲轮询时推进 `owner.pollCommands()` 并排空响应，与 Aeron 空闲策略无缝闭环。
- **代码收敛收益**：
  - `TradingOwnerLoop.java` 净减少 **81 行** 代码，消除多线程交接瓶颈与等待死锁风险。

---

## 3. 本地 Aeron 单节点线性永续（LINEAR_PERPETUAL）压测比对（lane=1 vs lane=2）

在内联重构完成后，遵循 [AGENTS-performance.md](file:///Users/atomex/Desktop/surprising/surprising-ex/AGENTS-performance.md) 规范，在当前 `master` 代码基线与本地环境执行了线性永续（`LINEAR_PERPETUAL`）单节点在 `accountLanes = 1` 与 `accountLanes = 2` 下的权威基准测试与端到端撮合压测比对。

### 3.1 测试环境与参数锁定
- **硬件与操作系统**：Apple M 系列 (macOS / arm64)，JDK 27 (HotSpot 64-Bit Server VM, 27+33-FR)
- **JVM 参数**：`--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -XX:+UseG1GC`
- **业务产品线**：`LINEAR_PERPETUAL`（线性永续合约）
- **核心拓扑**：单节点 Aeron 容器内联执行、单 Matcher（`matching-engines = 1`）、全局在途窗口 256（`in-flight = 256`）

---

### 3.2 压测数据对比

| 压测场景与指标 | `accountLanes = 1` | `accountLanes = 2` | 差异对比与性能分析 |
| :--- | :---: | :---: | :--- |
| **【端到端】订单流撮合耗时** (10,000 cycles, 20,000 orders) | **0.901 秒** | 1.015 秒 | `lane = 1` 整体耗时减少 **11.2%** |
| **【端到端】订单吞吐量 (orders/sec)** | **22,198.02 orders/s** | 19,711.56 orders/s | `lane = 1` 吞吐提升 **+12.6%** |
| **【端到端】撮合周期吞吐 (cycles/sec)** | **11,099.01 cycles/s** | 9,855.78 cycles/s | `lane = 1` 周期吞吐提升 **+12.6%** |
| **【端到端】延迟 p50** | **39 µs** | 40 µs | 基本持平 |
| **【端到端】延迟 p95** | **80 µs** | 92 µs | `lane = 1` p95 延迟降低 **13.0%** |
| **【端到端】延迟 p99** | **123 µs** | 195 µs | `lane = 1` p99 尾延迟降低 **36.9%** |
| **【端到端】延迟 Max** | **7,737 µs** | 12,734 µs | `lane = 1` 最大抖动显著更小 |
| **【JMH】常驻盘口异步并发挂撤单 (`denseResidentBookAsyncPlaceCancel`)** | **169,405.11 ops/s** | 156,080.93 ops/s | `lane = 1` 吞吐提升 **+8.5%** |
| **【JMH】成交全量清算结算 (`tradingCommitFullFill`)** | 973.66 µs/op | **832.65 µs/op** | `lane = 2` 在多用户持仓清算多核并行上快 **14.5%** |

---

### 3.3 核心发现与架构结论
1. **单 Matcher 拓扑下单 Lane 吞吐更高、尾延迟更低**：
   - 在单节点单 Matcher 下，`accountLanes = 1` 在端到端订单撮合（+12.6%）及盘口异步并发挂撤单（+8.5%）场景下均表现出更优的吞吐量与显著更低的尾部延迟（p99 从 195µs 降至 123µs）。
   - **机理剖析**：单 Lane 运行时完全消除了跨 Lane 线程的哈希散列路由、二次任务分发队列同步、以及完成位掩码（`readyMask`）聚合开销，拥有最高的 CPU L1/L2 缓存局部性。
2. **多 Lane 优势场景集中在重度资金清算阶段**：
   - 当遇到深度成交全量结算（双方持仓变动、保证金计算及强平风险扫描）时，`lane = 2` 借助 2 个独立的结算 Worker 并行处理两边用户的账户状态，结算单次操作耗时降低约 14.5%。
3. **内联重构验证**：
   - 内联 `TradingOwnerLoop` 后，Aeron 容器服务线程直接驱动命令处理，消除了独立 Owner 线程的队列交接，整体命令流转零死锁、零在途积压（`pendingMatching = 0`，`unfinished = 0`，`fundsDiff = 0`）。

---

## 4. 验证与提交记录

- **独立 Worktree 隔离验证**：
  - 在独立分支 `simplify/aeron-inline-owner` 完成所有内联修改与测试验证，无缝 Fast-forward 合入 `master` 并安全清理工作树。
- **全量编译与定向测试**：
  - `TradingOwnerInputReuseTest`（16,384 条跨环连续入队）、`OwnerLogContextTest`、`ProductionSourceBoundaryTest`、`TradingCpuAffinityTest` 100% 通过。
- **压测工具链兼容**：
  - 调整 `LinearPerpetualBenchmarkSupport` 支持 `accountLanes = 1`，保持系统契约完备。
- **提交与推送**：
  - 内联提交 `1ec0201c3`、基准测试工具与总结更新。

---

## 5. 消除过度设计：彻底拔除中间人 `TradingOwnerLoop` 与重构 `ClusterServiceEgress`

### 5.1 背景与设计异味根除
内联重构后，系统已消除了独立的 `trading-owner` 平台线程，但在代码结构上仍遗留了多线程时代的“防御性过度设计”与“中间人壳类”：
1. **中间人包装异味（Middle Man Smell）**：
   - `TradingOwnerLoop` 本是独立线程循环的载体。内联后无独立线程无循环，所有公共方法（`enqueue`、`drainResponses`、`onRoleChange`、`captureSnapshotSections`、`terminate`）纯粹是将调用转发给 `TradingCoreOwner` 或 `ClusterServiceEgress`，多了一层无谓调用栈与 311 行纯胶水代码。
2. **多线程并发屏障与冗余字段**：
   - `ClusterServiceEgress` 内部依然保留了多线程时代用于跨核通信的 `PaddedEgressOffsets`（10 个 volatile 计数器与缓存行伪共享 padding）、8192 槽的大容量输出环以及并发去重集合。在单线程内联模式下，这些 volatile 与屏障纯属浪费。

### 5.2 重构措施
1. **精简 `ClusterServiceEgress` 为单线程原位直发内核**：
   - 彻底删除 `PaddedEgressOffsets` 内部类及其全部 10 个 volatile 字段与 padding；
   - 输出环改用原位普通游标 `long producedCount, consumedCount`，槽位精简为 2048；
   - 移除无用的并发会话去重集合与分频消费字节核算，改用单线程直接原地推进与慢会话轮询。
2. **彻底拔除 `TradingOwnerLoop` 类**：
   - 物理删除 `TradingOwnerLoop.java`（净减少 311 行冗余代码）；
   - 由 Aeron 集群服务主适配器 `AeronTradingClusterService` 直接持有 `TradingCoreOwner` 与 `ClusterServiceEgress`；
   - `AeronTradingClusterService` 统一管理生命周期、CPU 亲和性绑定、角色切换、直接入队与快照发布。
3. **全面适配测试与基准**：
   - 适配 `CoreConfigurationTest`、`OwnerSessionBoundaryTest`、`OwnerLogContextTest`、`ProductionSourceBoundaryTest`、`TradingOwnerInputReuseTest`、`SurprisingCoreApplicationTest`、`TradingCpuAffinityTest`；
   - 适配基准测试 `InProcessTradingAllocationBenchmark`；
   - 修复全部测试并全量绿色通过。

### 5.3 全量验证结果
- **全模块集成测试**：
  - 执行命令：`mvn test -pl surprising-aeron-core/surprising-aeron-service`
  - 测试规模：**1601 个测试全部运行，0 失败，0 错误，1 个合法跳过（1601 run, 0 failures, 0 errors, 1 skipped）**，构建耗时 11 分 45 秒，结果为 `BUILD SUCCESS`。
  - 核心套件：`TradingCoreOwnerTest`（45 项全绿）、`ClusterCommandPipelineTest`（326 项全绿）、`CorePerpetualFinancialMatrixTest`、`RuntimeCommitRecoveryTest`（18 项全绿）、`ProductionSourceBoundaryTest`（8 项全绿）、`CoreFundsLedgerIndexTest`（10 项全绿）等全部 100% 通过。
- **资金正确性与恢复语义**：
  - 完整保持了单线程内联模式下的借槽归还租赁契约、集群状态机快照与增量回放、无缝零丢包的资金一致性。



