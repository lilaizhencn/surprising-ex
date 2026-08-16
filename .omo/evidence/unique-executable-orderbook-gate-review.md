# Unique Executable Order Book Gate Review

## recommendation

**REJECT (FAIL)**

## originalIntent

Core 仅保留订单元数据和必要索引；`exchange-core` 是唯一可执行盘口。不得存在第二本活动盘口，不得从 Core 活动订单逐单重放恢复 `exchange-core`，不得对 `CoreBookState` 与 `exchange-core` 做双写或一致性校验。

## desiredOutcome

- `CoreBookState` 不再保存或权威决定活动盘口成员、价格时间优先级或盘口哈希。
- 恢复不遍历 Core 活动订单并逐笔 `place` 到 `exchange-core`。
- 撮合、盘口查询和恢复以 `exchange-core` 的持久化状态为唯一事实源；Core 订单元数据仅供账户、审计及必要索引使用。

## userOutcomeReview

目标未完成。当前实现仍维护一份可决定活动订单集合与优先顺序的 `CoreBookState`，每次撮合/撤单后与 exchange-core 同步更新，快照持久化该状态，恢复时再按照该状态逐单向新建 exchange-core 提交订单。该状态还进入业务状态哈希并在恢复前执行活动订单数量一致性校验。因此 exchange-core 不是唯一事实源。

## perCriterion

### C1 — CoreBookState 是否仍为权威状态: FAIL

- `TradingCoreState` 仍将 `CoreBookState bookState` 作为非空主状态字段，并验证其中每个 open order 与 `orders`/instrument 的一致性：`TradingCoreState.java:10-23,25-30,73-88`。
- `CoreBookState` 保存 `openOrders: orderId -> prioritySequence`，提供 `priorityOrder()` 并把它计入 `stateHash()`：`CoreBookState.java:6-24,37-56`。
- reducer 在撤单、拒单和撮合结果应用时增删 `bookState.openOrders` 并推进优先序列：`TradingCoreReducer.java:638-677,680-708,743-844`。
- `TradingCoreState.stateHash()` 仍混入 `bookState.stateHash()`：`TradingCoreState.java:284`。

结论：它不是单纯元数据/非权威索引；它决定活动盘口成员、恢复顺序和业务状态哈希。

### C2 — 恢复是否逐单重放: FAIL

- runtime 构造时调用 `matcher.rebuildAsync(initialState, excludedOrderIds)`：`TradingCoreRuntime.java:38-60`。
- 显式恢复入口再次调用同一 rebuild：`TradingCoreRuntime.java:213-228`。
- adapter 停止并 clean-start exchange-core，随后从 `state.bookState().priorityOrder()` 构造 `PlaceRequest`，并循环调用 `placeAsync`：`DeterministicExchangeCoreAdapter.java:233-280`。
- exchange-core 使用 `InitialStateConfiguration.cleanStart(...)`：`DeterministicExchangeCoreAdapter.java:291-304`。

结论：恢复明确是按 Core 活动订单、按 Core 优先序列逐单重放。

### C3 — exchange-core 是否唯一事实源: FAIL

- 每次 `applyMatches` 根据 exchange-core 返回的 matches 同时更新 Core orders/users 和 CoreBookState：`TradingCoreReducer.java:743-844`。
- rebuild 前比较 `orders` 中 OPEN 数量与 `bookState.openOrders().size()`，形成 Core 内部一致性门禁：`DeterministicExchangeCoreAdapter.java:247-252`。
- snapshot 编码和解码完整持久化 `nextPrioritySequence` 与每笔 book order/priority：`TradingStateSnapshotCodec.java:100-106,361-368`。
- 测试直接断言 CoreBookState 活动订单、Core 盘口哈希与 snapshot 恢复后等价：`CoreMatchingStateTest.java:53,78,99,103-139`。

结论：当前是 exchange-core 活动盘口 + CoreBookState 可恢复活动盘口的双状态设计，并非 exchange-core 唯一事实源。

## blockers

1. **violatedCriterion: C1** — CoreBookState 仍保存并权威校验活动订单集合和价格时间优先序列。 **evidencePointer:** `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreBookState.java:6-56`; `TradingCoreState.java:73-88`; `TradingCoreReducer.java:743-844`.
2. **violatedCriterion: C2** — 恢复 clean-start exchange-core 后按 `CoreBookState.priorityOrder()` 循环 `placeAsync`。 **evidencePointer:** `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:233-280,291-304`; `TradingCoreRuntime.java:213-228`.
3. **violatedCriterion: C3** — CoreBookState 被双写、快照持久化并用于活动订单数量一致性校验，exchange-core 不是唯一事实源。 **evidencePointer:** `TradingCoreReducer.java:638-844`; `TradingStateSnapshotCodec.java:100-106,361-368`; `DeterministicExchangeCoreAdapter.java:247-252`.

## remove-ai-slops / programming direct pass

- 生产代码：`CoreBookState` 作为第二份活动盘口及其手工恢复逻辑是与目标直接冲突的额外状态、解析/重建和一致性负担；这不是仅风格问题，而是违反 C1-C3。
- 测试：`CoreMatchingStateTest` 对 `bookState().openOrders()`、`bookStateHash()` 及 snapshot 后同一 Core book 的断言镜像当前实现，给“双写正确”提供覆盖，却不能证明“移除第二盘口/唯一事实源”。这些测试会锁定被要求移除的实现，构成 false confidence。
- 未发现本任务范围内可支持完成结论的独立行为测试；现有测试覆盖的是旧架构行为。

## checkedArtifacts

- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreBookState.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreState.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingStateSnapshotCodec.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/TradingCoreReducerTest.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/TradingStateSnapshotCodecTest.java`
- Current worktree diff/status.

## exactEvidenceGaps

- Targeted test command attempted: `mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=CoreMatchingStateTest,TradingStateSnapshotCodecTest,TradingCoreReducerTest test`.
- Tests did not execute because Maven Enforcer requires JDK 25 in the current environment. This is a verification gap, not the basis of rejection; the rejection is based on executable production paths that directly violate C1-C3.
- No task-scoped executor evidence, code-review report, manual-QA matrix, or notepad path was supplied/found as an input artifact. Direct artifact review is sufficient to establish the three criterion failures.

