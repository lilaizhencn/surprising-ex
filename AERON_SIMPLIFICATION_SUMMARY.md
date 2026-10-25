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

---

## 6. 深入优化：彻底消除过度设计与全工程代码冗余（第 2 阶段全量完成）

### 6.1 P0 - `OwnerIngressRing` 无锁化与容量 2048 收敛
- **背景与痛点**：
  在去除了独立线程后，Aeron 回调线程是唯一且原生的就地推进者。`OwnerIngressRing` 原先保留了 Disruptor 风格的 4 个 volatile 计数器（`producedEntries`, `producedBytes`, `consumedEntries`, `consumedBytes`）、4 个可见性批量缓存游标（`cachedConsumedEntries`, `cachedConsumedBytes`, `cachedProducedEntries`, `uncommittedConsumedBytes`），以及复杂的 64MB 字节载荷预算追踪逻辑。这不仅带来了无谓的 CPU 内存屏障与缓存行颠簸，而且队列满时若外部调用者同步等待可能引发死循环。
- **重构措施**：
  1. **彻底消除 4 个 volatile 与批量缓存游标**：删除 `producedEntries`, `producedBytes`, `consumedEntries`, `consumedBytes` 等 volatile 字段，改用纯内部原生游标 `long head, tail`；
  2. **消除 64MB 载荷预算核算机制**：不再追踪动态载荷字节，槽位从 8,192 紧凑收敛至 2,048 原位槽，极大地精简了内存开销与循环检查；
  3. **就地推进机制与消除死循环**：重构 `AeronTradingClusterService.enqueue(...)`，入队前先就地推进排空已有就绪命令；若环满则在当前上下文调用 `owner.pollCommands()` 推进排空，既消除了单线程死循环，又兼容了测试并发场景下的安全等待。代码从 208 行锐减至 140 行（精简 32.7%）。

### 6.2 P1 - 拔除 `IdleStrategy` 遗留与空转自旋统一
- **背景与痛点**：
  在固定 `BUSY_SPIN` 并内联后，`OwnerLogContext` 与 `TradingCoreOwner` 依然保留并传递了 `IdleStrategy idle` 接口入参和字段，快照等待与空分片读取时调用了形式上的 `idle.idle()`，实际上在内联单线程模式下这是无用的冗余参数与抽象。
- **重构措施**：
  1. **移除 `OwnerLogContext.idle` 字段**：清理其所有 Getter/Setter、构造器及导入；
  2. **重构 `TradingCoreOwner` 构造与等待逻辑**：移除 `idleStrategy` 字段与 `idleCommand`，等待快照生成与空片段轮询处统一直接使用 JVM 原生的 `Thread.onSpinWait()`；
  3. **收敛构造参数**：将 `AeronTradingClusterService`、`ClusteredBatchTradingBenchmark`、`TradingOwnerTestSupport`、`OwnerIngressRingTest` 中的构造函数从 4 参数收缩为纯粹的 3 参数，消除抽象摆设。

### 6.3 P2 - 统一 9 个 Copy-Paste 的 `AdminCursorPage`（消灭 ~800 行纯重复代码）
- **背景与痛点**：
  在微服务与领域拆分演进过程中，跨模块分页模型产生了严重的机械式复制代码腐化：`surprising-trading-api`、`surprising-derivatives-lifecycle-api`（adl/insurance/liquidation/risk）、`surprising-derivatives-lifecycle-provider`（funding）、`surprising-maker`、`surprising-gateway`（auth/account）9 个子包各自复制了一份结构完全一致（约 107 行）的 `AdminCursorPage`，并附带多份重复单元测试。
- **重构措施**：
  1. **集中收敛**：在公共契约模块 `surprising-trading-api` 统一定义 `com.surprising.trading.api.utils.AdminCursorPage`，并扩展支持 `SortSpec` 二参数便利构造与完整字段序列化；
  2. **物理删除 8 份冗余类与 2 份测试**：
     - 物理删除 `surprising-derivatives-lifecycle-api` 下的 4 个重复类；
     - 物理删除 `surprising-derivatives-lifecycle-provider` 下的 1 个重复类；
     - 物理删除 `surprising-maker` 下的 1 个重复类；
     - 物理删除 `surprising-gateway` 下的 3 个重复类及 2 个重复测试；
  3. **全局引用规范化**：更新 `surprising-gateway`、`surprising-maker`、`surprising-derivatives-lifecycle` 等 44 处生产与测试代码，统一导向 `com.surprising.trading.api.utils.AdminCursorPage`；
  4. **补全高质量单元测试**：在 `surprising-trading-api` 编写全面的 `AdminCursorPageTest`，测试边界覆盖率 100%。

### 6.4 P3 - 跨模块组件依赖纯净性分析与最佳实践维护
- **深度分析与决策**：
  针对 `surprising-instrument-api` 与各应用模块中的快照分发器 `InstrumentSnapshotSupport` / `InstrumentSnapshotConsumer`，严格遵循 [AGENTS.md](file:///Users/atomex/Desktop/surprising/surprising-ex/AGENTS.md) 规则：“共享 API 不依赖应用实现，框架适配依赖按需设为 optional”。
  当前共享抽象通过纯公式与纯契约 `InstrumentSnapshotSupport.consume(...)` 共享核心消费与去重逻辑，各应用端仅保留 30 行独立的 Spring Kafka `@KafkaListener` 胶水注解；不为抽取 30 行注解胶水而向底层共享 API 强行侵入数百兆的 `spring-kafka` 重量级框架依赖，保持了最清晰的职责边界与依赖纯净性。

### 6.5 全量构建与测试验证
- **全工程反应堆编译**：全系统 23 个 Maven 模块执行 `mvn test-compile` **全部 SUCCESS**；
- **定向核心与业务测试**：
  - `surprising-trading-api`：15 项单元测试全绿；
  - `surprising-gateway`：`AdminCursorPageTest` 3 项测试全绿；
  - `surprising-aeron-service`：核心套件测试（`OwnerIngressRingTest` 4项、`TradingOwnerInputReuseTest` 5项、`OwnerLogContextTest` 6项、`CoreAdmissionInputOwnershipTest` 10项、`ProductionSourceBoundaryTest` 8项）**共 33 项全绿通过**。

---

## 7. 深入清理：全工程过度设计与历史死代码彻底拔除（第 3 阶段完成）

### 7.1 物理删除 8 个未引用的 `@FeignClient` 假 RPC 客户端
在系统演进过程中，曾声明了部分带有 `@FeignClient` 的接口，但系统实际架构完全由内部 Service、Gateway 路由或 Aeron 高性能消息流转驱动。这 8 个 Feign 接口在全工程中 **0 装配、0 注入、0 远程调用**，属于纯粹的形式主义架构遗留。
本次对其进行连根拔起并清理空包：
1. **`surprising-derivatives-lifecycle-api`**：
   - 彻底删除 [`AdlRpcApi.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-api/src/main/java/com/surprising/adl/api/client/AdlRpcApi.java)
   - 彻底删除 [`InsuranceRpcApi.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-api/src/main/java/com/surprising/insurance/api/client/InsuranceRpcApi.java)
   - 彻底删除 [`LiquidationRpcApi.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-api/src/main/java/com/surprising/liquidation/api/client/LiquidationRpcApi.java)
   - 彻底删除 [`RiskRpcApi.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-api/src/main/java/com/surprising/risk/api/client/RiskRpcApi.java)
2. **`surprising-price-api`**：
   - 彻底删除 [`ExchangeRateRpcApi.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-api/src/main/java/com/surprising/price/api/client/ExchangeRateRpcApi.java)
   - 彻底删除 [`IndexPriceRpcApi.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-api/src/main/java/com/surprising/price/api/client/IndexPriceRpcApi.java)
   - 彻底删除 [`MarkPriceRpcApi.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-api/src/main/java/com/surprising/price/api/client/MarkPriceRpcApi.java)
3. **`surprising-maker-api`**：
   - 彻底删除 [`MarketMakerRpcApi.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-maker/src/main/java/com/surprising/marketmaker/api/client/MarketMakerRpcApi.java)

### 7.2 物理删除 Gateway 历史孤岛数学类与模型体系（12 个文件）
- **背景与痛点**：
  在早期架构演进中，网关内部实现了一套针对持仓盈亏、强平释放与资金费扣减的数学计算。随着结算清算能力权威收敛至 Aeron Core，这套类库彻底成为了外部零引用的“代码孤岛”（除其自身 4 个自测测试类互相调用外，任何 Controller 和 Service 均不访问）。
- **清理明细（彻底物理删除 12 个文件，净减少 ~1,000 行纯死代码）**：
  1. **生产核心数学工具（4 个）**：
     - `AccountMarginReleaseMath.java`
     - `MarginTransferMath.java`
     - `PnlSettlementMath.java`
     - `TradeFeeMath.java`
  2. **孤立数据模型（4 个）**：
     - `ContractSpec.java`
     - `BalanceSettlementState.java`
     - `PositionChange.java`
     - `PositionState.java`
  3. **孤立测试用例（4 个）**：
     - `AccountMarginReleaseMathTest.java`
     - `MarginTransferMathTest.java`
     - `PnlSettlementMathTest.java`
     - `TradeFeeMathTest.java`

### 7.3 清除孤立死命令、残余日志工具与死 Record
1. **死命令删除**：
   - 物理删除 `surprising-account-api` 中的 [`AccountUserCommand.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-account-api/src/main/java/com/surprising/account/api/model/AccountUserCommand.java) 与 [`AccountUserCommandType.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-account-api/src/main/java/com/surprising/account/api/model/AccountUserCommandType.java)（旧指令协议，已被统一命令模型取代）；
   - 物理删除 `surprising-gateway` 中的 `ExpiringPositionSettlementAccountCommand.java`（零引用到期交割合约指令模型）。
2. **基准测试残余日志工具删除**：
   - 物理删除 `surprising-aeron-benchmarks` 中过时的 [`StableLedgerJournal.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/src/main/java/com/surprising/aeron/benchmarks/workload/StableLedgerJournal.java) 与 [`StableLedgerJson.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/src/main/java/com/surprising/aeron/benchmarks/workload/StableLedgerJson.java)。
3. **死 Record 清理**：
   - 从 `surprising-gateway` 的 [`AuthModels.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/model/AuthModels.java) 中移除了未使用的 `CurrentPasswordRequest` 与 `TokenPair`。

### 7.4 统一游标跨模块兼容与全量测试修复
1. **跨模块游标编解码兼容**：
   - 在统一的 [`AdminCursorPage.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-trading-api/src/main/java/com/surprising/trading/api/utils/AdminCursorPage.java) 中扩展 `decodeCursor`，无缝兼容 `Instant` 标准格式（`ISO_INSTANT|id`）与 Funding 历史系统的毫秒游标（`epochMillis:id`），同时保证强校验与防越界；并在 [`AdminCursorPageTest.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-trading-api/src/test/java/com/surprising/trading/api/utils/AdminCursorPageTest.java) 中覆盖了双向编解码与非法格式边界测试。
2. **测试修复与对齐**：
   - 修复 [`AccountLaneLifecycleTest.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/bootstrap/account/AccountLaneLifecycleTest.java)：适配精简后的构造函数入参；
   - 修复 [`CoreLedgerQueryTest.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/query/CoreLedgerQueryTest.java)：引入缺失的集合导入并对齐持续查询的游标测试预期；
   - 验证 `FundingQueryBoundaryTest`：21 项资金费率历史分页边界测试 100% 通过。

---

## 9. Aeron Core 架构深度精简（拔除内联残留死代码、死字段、空转计数器与配置过度设计）

针对 `surprising-aeron-core` 在单线程内联化及固定模式改造后的深度残留代码进行彻底拔除：

### 9.1 TradingCoreOwner 历史死代码与死字段清除
1. **清理会话与事件残留死方法**：
   - 彻底删除零外部引用的 [`sessionOpened()`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreOwner.java)、[`sessionClosed(long)`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreOwner.java) 与 `drainFromLogEvent()`。
2. **清理多余的快照中转死方法**：
   - 彻底删除 `ensureSnapshotCapacity(int, int)`、`loadSnapshot(SnapshotFragmentSource, BooleanSupplier)` 以及其关联的嵌套接口 `SnapshotFragmentSource`；快照恢复统一由 `RecoveryBuffer` 与直接 byte 数组承接。
3. **拔除死字段与空轮询方法**：
   - 彻底删除零外部读取的只写死字段 `private BiConsumer<OrderCommand, String> processingLogCallback`。
   - 彻底删除历史预留的 `pollBackgroundWork(long nowNs)`。
   - 彻底删除从未使用过的只读方法 `currentStateOrNull()`。
4. **精简 `idleCommand` 签名**：
   - 将原形虚设的 `idleCommand(int work, long deadline)` 精简为 `idleCommand(long deadline)`，拔除无意义的 `work` 形参与调用处传参 `0`。

### 9.2 TradingAccountRuntime 孤立死方法清除
- 物理删除 `TradingAccountRuntime.java` 中全工程零引用的死方法：
  - `inOwnerThread()`：旧架构跨线程断言残留；
  - `toSortedSet(LongArrayList)`：死集合转换工具。

### 9.3 OwnerCommandPipelineState 消除 4 个空转计数器
在固定流水线模式后，原 `OwnerCommandPipelineState.java` 中维护了 4 个纯递增、没有任何业务读取逻辑的 long 计数器。本次彻底清除：
- 物理删除 `drainedCommands`、`dependencyFences`、`controlFences`、`retryFences` 4 个空转字段；
- 移除 `beginHeadCommit()`、`recordControlFence()`、`recordDependencyFence()` 等递增方法及 `reset()` 中的重置操作；
- 移除 `TradingCoreOwner.java` 中门禁阻塞时的无意义计数递增；
- 简化 `terminationSummary()` 字符串拼装，彻底消除计数器空转与无意义状态维护开销。

### 9.4 AeronTradingClusterService 等待语义与异常文案收敛
- 纠正 `awaitProgress` 中的异常文案，由旧概念 `"cluster boundary progress timed out"` 统一修正为契合当前架构的 `"cluster command progress timed out"`。
- 保证 Cluster idle 策略与 spin wait 统一协调。

### 9.5 快照超时配置过度设计收敛
- 彻底删除 `CoreRuntimeConfiguration.java` 中冗余的 `standaloneSnapshotTimeoutSeconds` 及其校验逻辑，与集群快照超时 `snapshotTimeoutSeconds` 统一合一；
- 同步清理 `application.yml` 与配置文件测试 `CoreRuntimeConfigurationTest.java`；
- 更新 `surprising-aeron-core/README.md` 与 `surprising-aeron-service/README.md` 文档。

---

## 10. 深度消除镜像类、死方法及防御性伪参数

在独立 worktree（分支 `cleanup/aeron-deep`）中实施了针对协议镜像过度设计、死方法及旧同步模式残留伪参数的专项彻底清理：

### 10.1 镜像类 `CoreLaneMetrics` 物理消除与直通优化
- **问题根因**：`surprising-aeron-protocol` 中已有完整的 [`CoreLaneMetricsView.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLaneMetricsView.java)，但 `surprising-aeron-service` 形式主义地定义了字段完全相同的 184 行镜像类 `CoreLaneMetrics.java`，并在 `laneMetrics()` 中解码后再逐字段搬运 new 一遍，导致额外的 24 字段开销和数组二次深拷贝。
- **重构落地**：
  1. `TradingCoreRuntime.java` 中的 `laneMetrics()` 方法直接返回 `CoreLaneMetricsView`；
  2. 物理删除 `CoreLaneMetrics.java`（彻底减少 184 行重复代码）；
  3. 全工程测试、基准测试及工作负载类统一替换为 `CoreLaneMetricsView`。

### 10.2 拔除 `SettlementLaneWorker` 与 `AccountLaneLifecycle` 的死参数与死方法
- **死方法删除**：物理删除 `SettlementLaneWorker.java` 中全工程 0 引用的 `admissionDepthIfAvailable()`。
- **死参数与透传链拔除**：移除 `SettlementLaneWorker` 构造函数中无用的 `CoreRuntimeConfiguration` 死形参，并将 `AccountLaneLifecycle` 中的死字段 `configuration` 与导入彻底拔除，简化构造签名。
- **清理死 import**：清理 `MatcherCommandPipeline.java` 与 `SystemLedgerLane.java` 中遗留的未使用的 `CoreRuntimeConfiguration` 导入。

### 10.3 删除 `ClusterCommandWindow.java` 孤立死方法
- 物理删除重构后全工程 0 引用的 `hasRoute()` 和 `routeUserId()`。

### 10.4 `PlaceAdmissionEvent` 伪参数与防御分支彻底收敛
- **问题根因**：`prepare` 方法中接收 `boolean matcherWaitRequired`，并在类内部维护该字段和做多个分支判断。而调用方传入的恒为 `asynchronousCommands()`。在固定为单一异步流水线模式后，所有普通下单准入必须等待 Matcher 消费。
- **重构落地**：
  1. 移除 `prepare` 方法中无意义的 `boolean matcherWaitRequired` 形参；
  2. 移除 `PlaceAdmissionEvent` 内部的 `matcherWaitRequired` 字段及防御性条件判断，`matcherConsumed()` 直接返回状态位；
  3. 简化 `TradingAccountRuntime.java:2013` 调用处，不再透传多余参数。

---

## 12. 深入排查与第十一轮深度代码清理（Round 2 Deep Simplification）

在完成首轮核心收敛后，进一步使用全工程调用拓扑扫描工具与符号跨模块引用分析，连根拔除了 11 处架构演进后残留的孤立死方法、多余别名封装与过度重载层级：

### 12.1 `TradingCoreOwner` 构造重载链条彻底收敛
- **问题根因**：`TradingCoreOwner` 原先定义了 4 个构造函数（2 参数、3 参数、4 参数、5 参数），其中 3 参数与 4 参数仅作为级联默认值的中间胶水层，在生产及全工程测试中均无任何调用者。
- **重构落地**：
  1. 彻底删除 3 参数与 4 参数两个多余的构造重载；
  2. 2 参数便利构造函数直接委托给 5 参数完整主构造函数，消除中间层级联。

### 12.2 `DeterministicExchangeCoreAdapter` 孤立死方法 `ensureInstrumentAsync` 物理删除
- **重构落地**：物理删除 `public CompletableFuture<Integer> ensureInstrumentAsync(CoreInstrument instrument)`。系统生产与测试统一使用 `ensureSymbolAsync(String symbol)`，彻底消除冗余方法。

### 12.3 `CommitPublication` 孤立死方法 `deferProvisionalProjection` 物理删除
- **重构落地**：物理删除全工程 0 引用的 `public void deferProvisionalProjection()`，保持提交发布器纯净单一。

### 12.4 `LaneCancelEvent` 孤立死方法 `commitsLane` 物理删除
- **重构落地**：物理删除全工程 0 引用的 `public boolean commitsLane() { return commitLane; }`。

### 12.5 `LiquidationIndex` 孤立死方法 `activeId` 物理删除
- **重构落地**：物理删除全工程 0 引用的 `public long activeId(long userId, String instrumentId, CorePositionSide positionSide)`。

### 12.6 `CoreInstrument` 孤立死方法 `restoreConfiguration` 物理删除
- **重构落地**：物理删除全工程 0 引用的 `public void restoreConfiguration(Configuration configuration)`。

### 12.7 `TreasuryRuntime` 孤立死方法 `pendingTransferCount` 物理删除
- **重构落地**：物理删除全工程 0 引用的 `public int pendingTransferCount()`。

### 12.8 `SurprisingAeronClient` 机械转发方法 `trySubmit` 物理删除
- **重构落地**：物理删除仅单纯单行转发给 `offer(message)` 的 `public long trySubmit(CoreMessage message)`。

### 12.9 `CoreTriggerOrderCodec` 孤立单例编码方法 `encodeSingle` 物理删除
- **重构落地**：物理删除全工程 0 引用的 `public static byte[] encodeSingle(CoreTriggerOrderStateView state)`。

### 12.10 `TradingOrderBatchCodec` 别名包装方法彻底删除
- **重构落地**：
  1. 物理删除直接转发给 `encodeResult` 的 `encodeBatchResult(CoreOrderBatchResult result)`；
  2. 物理删除直接转发给 `decodeResult` 的 `decodeBatchResult(byte[] encoded)`。

### 12.11 `CoreOrderBatchResult` 冗余伪别名方法清理
- **重构落地**：
  1. 物理删除 record `CoreOrderBatchResult` 中无外部引用的 `outcomes()`；
  2. 物理删除 record `Item` 中无外部引用的 `itemIndex()` 和 `itemId()`，统一使用 record 原生标准组件访问器 `index()` 和 `orderId()`。

---

## 13. Round 3: 全工程深度排查与跨模块无用代码彻底清理

### 13.1 核心服务与撮合链路死代码拔除
- **`DeterministicExchangeCoreAdapter` 孤立死方法 `ensureSymbolAsync`**：
  - 撮合引擎原生品种初始化已全量走同步 `ensureSymbol`，Future 包装的 `ensureSymbolAsync` 全工程零引用，彻底删除。
- **`BalanceRuntime` 孤立资金扣减方法 `consumeLocked`**：
  - 核心资金扣减由 `SystemLedgerLane` 与 `RuntimeTreasuryDelta` 权威统一管理，未进入事务的 `consumeLocked` 零调用，彻底删除。
- **`AccountLaneState` 用户列表快照方法 `userIdsSnapshot`**：
  - 快照与用户审计统一通过 `AccountLaneSnapshot` 保证水位对齐，临时复制 `LongHashSet` 的包私有方法无调用，彻底删除。
- **`ControlLaneDispatcher` 孤立路由判断方法 `targetsLane`**：
  - 控制任务派发与完成位图直接由原子/位图机制判定，`targetsLane` 无任何生产或测试调用，彻底删除。
- **`RuntimeIdentityRegistry` 装箱查询方法 `findPositionKeyInLane`**：
  - 系统持仓身份查找统一走原生零装箱方法 `findPositionKeyValueInLane` 返回 `long`，删除接受 String key 的装箱死方法。
- **`SystemLedgerCommand` 账本关闭状态方法 `scopeClosed`**：
  - 财库作用域生命周期内联判定，外部无调用的 `scopeClosed` 彻底删除。

### 13.2 跨模块孤立模型与无用代码物理清理
- **`InsuranceFundLedgerRepository` 死查询与孤立模型 `InsuranceLedgerReference`**：
  - 物理删除 0 调用的 `findReference` 数据库查询方法；
  - 物理删除仅为该死查询存在的孤立 record 类 `InsuranceLedgerReference.java`。
- **`SubscriptionTopic` 死衍生方法收敛**：
  - 物理删除全工程无调用的 `withUserId` 和 `withProductLine`，仅保留真实使用的 `withInstrumentId`。
- **`ProductTopicNames` 冗余 Topic 名称生成器拔除**：
  - 物理删除已弃用/未使用的 `orderBookDepthTopic()` 与 `accountStateEventsTopic()`。
- **`ReferenceOrderBookSnapshot` 死方法 `commonDepth`**：
  - 做市策略行情快照中无引用的 `commonDepth()` 物理删除。
- **`Gateway` 网关层死私有方法清理**：
  - `InstrumentRepository`：删除死方法 `asset(String)`、`symbolOrNull(String)`；
  - `AuthService`：删除死方法 `isAdmin(List<String>)`；
  - `UserSecurityService`：删除死方法 `requireUser(long)`；
  - `GatewayProperties`：删除死方法 `readLongMap(String)`。

---

## 14. Round 4: 消除过度拆类、拔除跨模块死参数与层层转发

### 14.1 消除过度拆类：现货规则直接内联与物理删除 `SpotOrderAdmission`
- **问题现象**：现货规则计算仅有 3 行代码（卖单冻结基础资产，买单冻结报价资产及逐步成交手续费），却额外定义了 `SpotOrderAdmission.java` 静态工具类，`SpotTradingRules` 仅做单行转调，属于机械拆类。
- **重构落地**：将现货预留计算公式直接内联至 `SpotTradingRules.java`，物理删除 `SpotOrderAdmission.java` 文件，逻辑一目了然。

### 14.2 核心服务与撮合层死参数拔除
- **`OrderBatchExecutor`**：方法 `collectBatchPlaceReservation(OrderBatchPending batch, long userId)` 拔除未使用的 `userId` 参数，调用方不再无谓解析 `userId`。
- **`LaneCommitDelta`**：方法 `drainTo` 拔除未使用的 `int laneId` 参数，精简调用契约。
- **`DeterministicExchangeCoreAdapter`**：方法 `activateRestoredMatcher` 拔除未使用的 `TradingCoreState state` 参数，消除延迟激活 Lambda 闭包对整个 Core 状态的长期持有。

### 14.3 做市服务与网关层死参数与无用转发清理
- **`MarketMakerService`**：
  - 拔除 `shouldKeep` 与 `isFreshQuote` 中未使用的 `Instant now` 参数；
  - 物理删除单纯转调的 `currentMarkPrice` 私有方法，调用处直接收敛为 `latestMarkPrice`。
- **`ExpiringContractSettlementFanoutService`**：拔除 `submit` 中未使用的 `long version` 参数。
- **`GatewayApiKeyService`**：拔除 `requireSecurity` 中未使用的 `String requestIp` 参数。
- **`BinanceApiController`**：
  - 拔除 `orders` 中未使用的 `boolean openOnly` 参数；
  - 拔除 `depth` 与 `account` 中未使用的 `byte[] body` 参数；
  - 拔除 `proxy` 中未使用的 `boolean privateRoute` 参数，同步收敛全部 10 处调用。
- **`RealtimeRouter`**：拔除 `refresh(Aeron aeron, long now)` 中未使用的 `Aeron aeron` 参数。

---

## 15. 累计重构成果统计与系统现状

| 优化维度 | 重构前状况 | 重构后收敛成果 |
| :--- | :--- | :--- |
| **执行模式** | `FUSED` 与 `PIPELINED` 双模式维护，数百行内联判断与死分支 | 固定纯粹 `PIPELINED` 单一流水线模式，删除全部模式枚举与配置 |
| **等待策略** | `BUSY_SPIN`, `PARKING`, `YIELDING` 等多策略与 CAS 自适应休眠唤醒 | 固定纯粹 `BUSY_SPIN` 忙等自旋，消除锁与伪共享状态机 |
| **伪共享 Padding** | 堆叠 20+ 个抽象 Padding 类，200+ 个无业务意义的填充字段 | 彻底拍平为单一实体类，结构清晰透明，消除内存屏障颠簸 |
| **Aeron 服务架构** | 依赖独立的平台线程与多线程交接队列，跨线程超时等待 | Aeron 容器线程就地推进，彻底删除 `TradingOwnerLoop` 胶水包装 |
| **指标镜像类** | 形式主义维护 `CoreLaneMetrics` 与 `CoreLaneMetricsView` 双重镜像 | 物理删除 `CoreLaneMetrics`，直通协议层，消除二次数组克隆 |
| **Aeron 核心死代码** | 遗留 `sessionOpened/Closed`、`admissionDepthIfAvailable`、`hasRoute`、`ensureInstrumentAsync` 等 | 物理彻底清除所有孤立死方法，精简方法签名 |
| **流水线空转计数器** | 4 个仅递增从不查询的 long 计数器（`drainedCommands`、`*Fences`） | 连同调用方埋点彻底拔除，消除纯空转开销 |
| **准入伪参数** | 透传 `matcherWaitRequired`，内部多处防御分支 | 固定为异步流水线语义，彻底消除多余参数与防御分支 |
| **快照超时配置** | 区分独立快照超时与集群快照超时，多重冗余参数 | 统一收敛为单一 `snapshot-timeout-seconds` |
| **跨模块协议冗余别名** | Codec 中重复定义 `encodeBatchResult`、`trySubmit` 等假抽象别名 | 彻底清理冗余别名包装，直调权威方法 |
| **Feign 接口形式主义** | 声明了 8 个 `@FeignClient` 接口，全工程零装配零调用 | 物理全量删除，消除微服务假架构残留 |
| **Gateway 孤岛计算** | 遗留 12 个仅内部自测的数学类、数据模型与单测，与核心冲突 | 物理彻底删除 12 个孤岛文件，净减少 ~1,000 行纯死代码 |
| **跨模块分页游标** | 9 个子模块 Copy-Paste 完全相同的 `AdminCursorPage` 类 | 统一收敛至 `surprising-trading-api`，并兼容历史双格式 |
| **死命令与死 Record** | `AccountUserCommand`, `ExpiringPositionSettlementAccountCommand` 等遗留 | 物理彻底清除所有死命令与死 Record |
| **系统构建与测试** | 维护成本高、历史测试依赖陈旧参数 | **23 个 Maven 反应堆模块 `mvn test-compile` 100% 成功，相关核心测试全绿** |


---





