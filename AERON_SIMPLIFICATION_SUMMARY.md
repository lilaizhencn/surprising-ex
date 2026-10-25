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
针对全工程模仿 Disruptor 风格堆叠的大量冗余 Padding 继承类进行全面拍平，消除了过度工程化产生的噪音，代码净减少 400 余行：
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
由于流水线模式下 `asynchronousCommands()` 恒为 true，同步调用分支纯属冗余死代码。本次全面拔除了以下同步死分支与死方法（代码净减少 423 行）：
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
- [`RuntimeDerivativeLiquidationProcessor.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/liquidation/RuntimeDerivativeLiquidationProcessor.java)：
  - 删除 `prepareAction` 中 `!runtime.asynchronousCommands()` 的同步读取强平死分支。
- [`CoreMatchingFlow.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/CoreMatchingFlow.java)：
  - 拍平 `submitMatching`，去掉多余的作用域退出控制。
  - 删除 `submitMatchingInCommandScope` 中 `!owner.runtimeState().asynchronousCommands()` 的死分支。

---

## 3. 验证结果

- **独立 Worktree 隔离验证**：
  - 在独立目录与分支 `simplify/aeron-cleanup` 中分批完成重构，每批次均通过编译。
- **主工作区干净合入**：
  - 通过补丁机制安全合入主分支工作区，**完全保留主工作区未提交的原有业务修改**。
- **编译检查**：
  - `mvn -q -o test-compile -DskipTests` 全工程一次性编译成功，退出码为 0。
- **运行规范**：
  - 严格遵循指令：**改完之后不运行测试，编译没问题即可**。
- **代码收敛统计**：
  - 累计净删除 **900 余行**冗余代码，消灭 20 余个多层继承类和数百个伪共享垃圾变量。
