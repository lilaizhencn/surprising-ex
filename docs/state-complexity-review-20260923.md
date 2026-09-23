# Core state 复杂度审查（2026-09-23）

范围：`surprising-aeron-service` 的 `state` 包，并沿命令、Lane、提交、快照、恢复和查询链路检查调用方。命令回滚职责拆分后，生产源码为 152 个 Java 文件、31,000 行（含子包）；文件数不是删除依据，重点看一份事实是否被重复维护，以及抽象是否承担真实边界。

## 已确认并处理：测试快照混入生产源码

`TradingRuntimeState.snapshot(long)` 只被测试调用。它经 `RuntimeSnapshotBuilder` 生成 `TradingRuntimeSnapshot`，用于逐字段比较运行态。真实集群快照是 `CoreSnapshotLifecycle` → `RuntimeStateMaterializer` → `TradingCoreState` → `SectionedCoreSnapshotWriter`；恢复经 `SectionedCoreSnapshotParser` 和 `RuntimeStateProjector`。测试快照既不是集群写盘格式，也不参与恢复。

本次将 `RuntimeSnapshotBuilder`（134 行）和 `TradingRuntimeSnapshot`（286 行）移入 `src/test`，移除运行态的 `snapshot(long)` 入口，测试改为直接调用测试构造器。保留逐字段断言能力，生产包减少两个类型、约 420 行。`clean package` 的服务模块 919 个测试，0 失败、0 错误、2 跳过；产物 JAR 不含这两个类。

## 下一步最有价值：清掉旧的不可变增量更新模型

`StateMapSupport`（985 行）除了只读有序 `freezeSorted`，还实现 `DeltaMap`、`LazyDeltaMap`、`PersistentTreeMap`、增量血缘和变更键追踪。生产源码中 `delta` 的调用仅位于 `CoreUserState`、`CoreTreasuryState`、`TradingCoreState` 自身的不可变更新方法；这些更新入口在全项目生产源码中没有调用方。相应的 `transition`、`stampOrderChanges`、`requireOnlineDeltaLineage`、`changed*` 方法由旧测试直接使用或只在这些类内部互相调用。测试业务主入口 `RuntimeTestStateTransitions` 已经将 `TradingCoreState` 投影为 `TradingRuntimeState`，执行真实运行态逻辑，再在断言边界物化。

这意味着可逐步删除不可变快照上的在线更新 API，并把测试改为验证现有运行态路径。随后收缩 `StateMapSupport`，只保留快照构造所需的不可变有序 Map。**不能直接删除整个类**：`TradingCoreState`、`CoreUserState`、`CoreTreasuryState`、`CoreRiskState` 的快照构造仍依赖 `freezeSorted`。也不能为了省行数改成每条命令全量复制 `TreeMap`；这会把快照边界的开销带回热路径。拆除时逐步验证快照字节、业务/资金哈希、恢复后状态、六产品线资金矩阵，再测内存和吞吐。

## 可做的小清理，但收益较低

`RuntimeInsuranceFundStateTransitions`（27 行）只有 `InsuranceFundCommands` 一个生产调用方。其业务顺序是校验 owner、解析资产、检查保险基金余额、调整余额、增加 revision；可直接放回命令入口并保留现有保险基金和恢复测试。测试夹具也直接调用此类，需同步改为通过命令或运行态入口。这个改动能消除一次跨包跳转，但不解决主要阅读负担。

`RuntimeCommitJournal` 原只保存已发布序号及激活/关闭标志，并不存事件。本轮已将发布序号、激活与关闭校验并入 `CommitPublication`；快照屏障直接保存状态及业务哈希，删除仅在该屏障中使用的 `ProjectionVersion`。提交发布仍检查健康状态，恢复时初始化原有发布序号；资金结算和快照格式未变。

交易 Owner 原有“入队并立即推进”和“只入队、稍后轮询”两个入口。本轮保留生产使用的入队入口，测试显式轮询，删除入口模式参数和额外转发。`TradingCoreRuntime` 至撮合流程以及撮合流程至在途推进器的同包纯转发也已删除。独立回放和工具仍依赖 `apply` 返回 `MATCHING_PENDING` 后逐序号完成撮合；在途幂等、批量提交屏障测试也验证这一阶段性语义。尝试把 `apply` 直接改为阻塞等待异步终态时，这些断言和提交屏障失败，因此本轮保留同步调用契约，只收敛集群 Owner 的重复入口和相同条件分支。后续若要彻底删除同步分支，须先迁移回放协议与在途状态测试，并单独验证六产品线资金和恢复行为。

## 不建议按类数删除的边界

`TradingCoreState` 是快照、恢复、查询及状态哈希的值模型；`RuntimeStateMaterializer` / `RuntimeStateProjector` 是运行态和快照态之间的转换边界。`AdmissionOrderIndex` 有 Lane、全局活动订单和批量预准入三个实现；`SettlementBatchInput` 跨 Owner 到结算批次；`RuntimeFactIndexes` 管理派生索引的应用/重建；`LaneColdState` 隔离冷索引；`TerminalPruneBatch` 传递终态清理结果。这些类型有实际状态所有权或线程/恢复边界，机械内联会增加耦合。

`TradingRuntimeState` 原为 6,123 行，其中 `MatcherSettlementChanges`、`LaneCommitDelta`、`LaneClientOrderCaptures`、`LaneBalancePatches` 共 777 行。先把四个缓冲类型及其原有数据和操作移为同包独立类，运行态缩至 5,343 行；`BalanceState` 随余额补丁留在 `LaneBalancePatches.java`。

随后按命令回滚职责继续拆分：`RuntimeAccountRollback` 保存每个 Account Lane 的用户、余额、预留、订单、持仓和客户单号 before-image，负责首次变更前捕获、失败时先恢复 Lane 状态再恢复 owner 已发布索引，以及成功后清理。`RuntimeGlobalRollback` 保存清算、风险快照、杠杆、算法单、触发单、定时器、标记价、风险扫描、合约维护、待完成转账和手续费策略等 before-image，按同样的 Lane→owner 顺序恢复。`TradingRuntimeState` 仍负责命令、Lane 交接、提交和回滚的时序，因此 `beginCommandRollback` 与 `rollbackActiveCommand` 的步骤可连续阅读。运行态现在为 4,819 行；两个新类分别为 332、233 行。拆分没有改变权威状态所有者、资金发布顺序或快照格式，也没有宣称降低总代码量。

`MatcherSettlementDispatcher` 约 430 行且执行真实结算分发；并回运行态只会使这个大类更难读。接下来应以独立业务职责和状态所有权为依据，继续检查命令入口、状态变更和快照边界，避免仅为降低文件行数而机械拆类。
