# 交易核心跳板类、无意义包装转发与多余栈跳转深度治理总结报告（第九阶段）

## 1. 治理背景与目标

依据项目工程准则（KISS 原则、第一性原理、直接实现、去除多余抽象与跳板）以及用户的治理目标，交易核心在经历了前面的哈希削减、过度校验清理、UUID 裁剪之后，仍然存在大量历史重构遗留的“胶水类/跳板类”、“乒乓往返调用”以及“仅作单行委托的转发方法”。

本次治理针对深排发现的 5 大类跳转瓶颈进行了彻底根治：
1. **消除 Admission 与 Flow 间的乒乓跳板**：消除拒绝路径上的交叉回调。
2. **命令类直调权威构建器，消灭 14 个胶水转发方法**：消除 `TradingCoreRuntime` 机械代理 `CommandResultBuilder` 的胶水层。
3. **内联收敛 `OrderBatchExecutor` 的 4 个专用准备方法**：将批处理执行专用流程自闭环收敛于执行器内部。
4. **消除快照/撮合/发布链路的多层透传与嵌套方法**：外部与测试直调权威对象 `snapshots`，删除无引用方法与过度嵌套重载。
5. **精简 `TradingRuntimeState` 结算与预留透传层**：收敛死代码重载，消除未使用的 `signalDirectSettlement` 与内部 `pendingReservation*` 胶水方法。

---

## 2. 核心治理改动明细

### 2.1 任务 1：消除 `MatchingCommandAdmission` 与 `CoreMatchingFlow` 乒乓跳板
- **问题**：在撮合准入阶段拒绝时，准入器委托给 `flow.recordRejectedMatching(...)`，而该方法仅作参数透传又反向回调 `admission.rejectDirectMatching(...)`，造成了无意义的双重跳转与职责交叉。
- **治理措施**：
  - 在 [`MatchingCommandAdmission.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/MatchingCommandAdmission.java) 内部增加私有自闭环拒绝处理 `rejectMatching`；
  - 在 [`CoreMatchingFlow.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/CoreMatchingFlow.java) 中彻底删除了 5 参数的 `recordRejectedMatching` 跳板方法。

### 2.2 任务 2：命令类直调 `owner.resultBuilder`，清除 14 个胶水方法
- **问题**：`TradingCoreRuntime` 充当了 `CommandResultBuilder` 的“大管家”，定义了大量仅有 1 行代码的纯透传方法。
- **治理措施**：
  - 将 [`CommandResultBuilder.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/CommandResultBuilder.java) 和 `TradingCoreRuntime.resultBuilder` 正式声明为权威 public 组件；
  - 各命令处理类（`AdlCommands`, `BalanceTransferCommands`, `PerpetualFundingCommands`, `LeverageCommands`, `PositionCommands`, `RiskCommands`, `InstrumentSettlementCommands`, `TriggerOrderCommands`, `DirectCommandSlot`）统一直接访问 `owner.resultBuilder` 权威字段与方法；
  - 从 [`TradingCoreRuntime.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreRuntime.java) 中彻底删除了 13 个 `resultBuilder` 透传胶水方法和 1 个 `deferTriggerOcoPair` 遗留胶水方法。

### 2.3 任务 3：内联收敛 `OrderBatchExecutor` 的 4 个专用准备方法
- **问题**：`preparePipelinedPlaceBatch`, `prepareBatchPlaceItem`, `prepareBatchAmend`, `prepareCancelBatch` 仅供 `OrderBatchExecutor` 内部使用，却被定义在 `TradingCoreRuntime` 中跨文件跳转。
- **治理措施**：
  - 将这 4 个方法内联至 [`OrderBatchExecutor.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/OrderBatchExecutor.java) 作为私有实现，直接操作 `owner.runtimeState`；
  - 从 `TradingCoreRuntime.java` 中彻底删除了这 4 个方法。

### 2.4 任务 4：快照生命周期与撮合发布内联直调
- **问题**：快照方法在 `TradingCoreOwner` -> `TradingCoreRuntime` -> `CoreSnapshotLifecycle` 之间存在多级调用链路；`TradingCoreRuntime` 内存在无人调用的死代码方法以及仅包裹一行的 `reserveMatcherPublication` 重载。
- **治理措施**：
  - 在 [`TradingCoreRuntime.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreRuntime.java) 中将 `snapshots` 暴露为权威字段，[`TradingCoreOwner.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreOwner.java) 和测试直接访问 `state.snapshots.beginSnapshot` 与 `state.snapshots.pollSnapshotSections`；
  - 彻底删除了 `TradingCoreRuntime` 中的 `beginSnapshot`, `pollSnapshotSections`, `pollSnapshot`, `captureSnapshotSections`；
  - 删除了死代码 `settlementLifecycleOrders`，`InstrumentSettlementCommands` 直调 `owner.matchingFlow.lifecycleOrders(...)`；
  - 将 `reserveMatcherPublication(pending, direct)` 中的内部调用内联为 `direct.reserveMatcherPublication()`，并彻底删除了冗余的单参数重载。

### 2.5 任务 5：收敛 `TradingRuntimeState` 结算与预留透传层
- **问题**：`TradingRuntimeState` 包含无人引用的重载、仅有一行的空壳信号方法，以及 4 个供内部模块访问却多包一层的 `pendingReservation*` 胶水方法。
- **治理措施**：
  - 删除了 `TradingRuntimeState` 中无人引用的 10 参数 `prepareDirectReplacement` 重载；
  - 在 [`MatcherSettlementEvent.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/MatcherSettlementEvent.java) 中直接调用 `completionRuntime.settlements.publishMatcherOwned(this)` 与 `completionRuntime.signalOwnerCompletion()`，并从 `TradingRuntimeState` 中删除了 `publishMatcherSettlementDirect` 和无意义的 `signalDirectSettlement(laneMask)`；
  - 将 `RuntimeOrderStateTransitions`、`RuntimeStateMaterializer` 及测试直接对齐权威组件 `runtime.pendingReservations`，彻底删除了 `TradingRuntimeState` 中 4 个透传方法。

---

## 3. 验证与测试结果

- **构建与编译**：
  全工程 29 个模块使用 HotSpot JDK 27（Corretto 27）通过 `mvn test-compile` 全量编译成功。
- **单元与集成测试**：
  - 核心状态与执行器测试套件全部通过：
    `CoreOrderedOrderBatchTest`, `OrderBatchSlotReuseTest`, `SectionedCoreSnapshotCodecTest`, `TradingCoreOwnerTest`, `OrderBatchSettlementWaitTest`, `TradingRuntimeStateTest`（173 tests, 0 failures, 0 errors）；
  - 集群命令管线与故障恢复测试全部通过：
    `ClusterCommandPipelineTest`, `RuntimeCommitRecoveryTest`（338 tests, 0 failures, 0 errors）；
  - 累计回归验证 **511 项核心交易链路测试**，全部通过，业务语义、资金状态一致性与六产品线隔离完全吻合。
