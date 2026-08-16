# Code review: CoreBookState / exchange-core ownership

## Verdict

**FAIL** — the intended architecture (“Core retains order metadata and necessary indexes only; exchange-core is the only executable order book”) is not complete.

`codeQualityStatus`: **BLOCK**  
`recommendation`: **REQUEST_CHANGES**

## Scope and evidence reviewed

Reviewed only the requested Aeron service state/runtime/matcher paths, their changed tests, and the current worktree diff. `git diff --check` passes.

The required skill-perspective check **ran**:

- `remove-ai-slops`: violated. The change keeps a persistent, execution-shaped mirror (`CoreBookState`'s active IDs plus priority sequence) and tests encode that mirror as expected behaviour.
- `programming`: violated. The new/retained restore design is unnecessary production reconstruction/parsing of executable book state at the wrong boundary; tests mirror implementation state rather than protect the intended ownership contract.

Targeted Maven execution was attempted:

```text
mvn -pl surprising-aeron-core/surprising-aeron-service \
  -Dtest=CoreMatchingStateTest,CoreProbeStateTest test
```

It did not start tests: Maven Enforcer rejected the local JDK because this project requires JDK 25. Therefore there is no passing runtime evidence for this review.

## Lifecycle verification

| Flow | Evidence | Result |
|---|---|---|
| Normal place | `CoreProbeState` persists an OPEN order before submitting it to exchange-core (lines 720-728), then `DeterministicExchangeCoreAdapter.placeAsync` submits the same order (lines 62-80). | Two state holders are involved; Core is not metadata-only. |
| Match | Match completion changes Core orders, balances, and `CoreBookState` membership/priority (`TradingCoreReducer.java:743-844`) after exchange-core has already matched. | Financial/order metadata write is necessary; persistent book membership/priority is a second executable-book representation. |
| Cancel | exchange-core cancel is submitted first (`CoreProbeState.java:1044-1048`); on success Core cancels the order (`CoreProbeState.java:1195-1202`) and removes it from `CoreBookState` (`TradingCoreReducer.java:672-677`). | Duplicated active-book lifecycle state remains. |
| Restart recovery | Snapshot serializes CoreBookState active IDs and priority (`TradingStateSnapshotCodec.java:100-106`), decodes it (`:361-368`), and adapter verifies it before replaying every active order in priority order (`DeterministicExchangeCoreAdapter.java:247-280`). | Directly violates the requirement not to restore by replaying active orders one by one. |

## Findings

### HIGH

1. **CoreBookState remains a second persistent active order book, including queue priority.**

   Evidence: [CoreBookState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreBookState.java:6) stores `openOrders` and `nextPrioritySequence`; [CoreBookState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreBookState.java:42) derives priority order. `TradingCoreState` treats this as authoritative enough to validate every entry against OPEN orders at [TradingCoreState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreState.java:73). Reducer paths explicitly write it on cancellation and placement/matching at [TradingCoreReducer.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:674) and [TradingCoreReducer.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:756).

   Risk: Core and exchange-core jointly define which orders are resting and their restoration priority. Any interruption between matcher success and reducer completion, or a future lifecycle path that updates only one side, can make the snapshot-recovered book differ from the execution book.

   Direction: remove persistent `CoreBookState.openOrders`/priority from the business snapshot and reducer. Keep order status/reservation metadata in Core; retain only a derived metadata index when query/lifecycle selection truly needs one. Do not use Core state as an executable book oracle.

2. **Restart reconstructs exchange-core by replaying every Core active order, in Core-maintained priority order.**

   Evidence: [TradingStateSnapshotCodec.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingStateSnapshotCodec.java:100) persists the order IDs and priority sequences. [DeterministicExchangeCoreAdapter.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:247) first requires Core OPEN-order count to equal `CoreBookState` count, then [lines 253-280](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:253) maps `priorityOrder()` to `PlaceRequest`s and calls `placeAsync` serially. This is precisely per-active-order rebuild.

   Risk: restart time grows with open-order count and correctness depends on re-execution being indistinguishable from the original matching-engine state. The code detects crossing/rejection only after the replay, so it has no independent source of truth to reconcile against.

   Direction: make exchange-core recover its own executable state from its native snapshot/journal, or establish a single exchange-core-owned durable book-recovery artifact. Core should not recreate the matching book from `CoreOrderState` records.

### MEDIUM

1. **The existing tests reinforce the duplicate representation instead of testing the ownership boundary.**

   Evidence: [CoreMatchingStateTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java:113) calculates a Core `bookStateHash`; [lines 131-139](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java:131) asserts it survives snapshot/restart and then relies on a later order matching it. Many tests assert `bookState().openOrders()` directly (for example [CoreMatchingStateTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java:53)).

   This is implementation-mirroring, not an observable contract that exchange-core is the sole executable book. It creates false confidence in exactly the design the goal removes. This is MEDIUM because it is a maintenance/scope failure; the HIGH findings demonstrate the actual architectural violation.

   Direction: replace direct CoreBookState assertions with black-box lifecycle tests: submit/rest/cross/cancel, take Core snapshot plus matcher-native recovery state, restart, then query exchange-core book and execute a crossing order. Add a negative architecture test that Core snapshot contains no active-book queue/priority payload and no recovery loop invokes `placeAsync` for each OPEN order.

### LOW

No additional in-scope LOW findings.

### CRITICAL

No CRITICAL finding identified in the inspected scope.

## Non-blocking boundary note

`ActiveOrderIndex` is a derived index from `TradingCoreState.orders()` ([ActiveOrderIndex.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/ActiveOrderIndex.java:42), [lines 58-65](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/ActiveOrderIndex.java:58)) and is used for Core queries/lifecycle batching ([CoreProbeState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:993)). It contains IDs only, not matching side/price/time priority, so it can remain as a necessary derived metadata index if its rebuild/update semantics are kept tied solely to order metadata.

## Blockers

1. Remove `CoreBookState` as a persisted, priority-bearing active-order mirror and stop reducer double-writes to it.
2. Replace `DeterministicExchangeCoreAdapter.rebuildAsync`'s per-active-order `placeAsync` replay with exchange-core-owned recovery.
3. Replace implementation-mirroring tests with ownership-boundary/restart tests, then run the relevant Maven tests using JDK 25 and record their artifact paths/output.
