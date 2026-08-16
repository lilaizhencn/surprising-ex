# Task 3 Luna gate fix evidence

## Scope

- Branch: `codex/w3w5-t03-batch`
- Base under test: `a1e46c8a2eff34c261e9bff279a5a9e5fcb4a38d`
- Runtime: IBM Semeru Open Edition JDK `25.0.2.1`
- Ownership kept to protocol/service batch behavior and tests. The supplied `task-3-luna-gate-review.md` remains untouched and untracked.
- No wallet or live product-line service was started.

## Fixes

1. `CoreMatchingResult` now carries `matcherStateChanged`. `DeterministicExchangeCoreAdapter.replaceOrderAsync` marks a normal replacement-place rejection after a successful native cancel as state-changing. `CoreProbeState` treats that marker as recovery-required before any item result is appended, so the existing `fatalFailure` path stays sticky. The original Core order remains open in Core state, no replacement is created, no later batch item is submitted, and no normal `MATCHING_REJECTED` aggregate is recorded.

2. `CoreProbeState` pre-scans cancel/amend targets before creating a pending batch. A known order owned by another user rejects the whole batch with top-level `ORDER_OWNER_MISMATCH` and zero pending, export, source-sequence, result-ledger, matcher, or Core-state mutation. ProductLine remains an outer-header invariant: a cross-line batch returns `PRODUCT_LINE_MISMATCH` at the existing pre-command fence with the same zero-mutation guarantees. Accepted same-user/same-line batches retain ordered, non-atomic per-item behavior.

## Red to green

Focused red command on the unmodified production code:

```text
env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home \
mvn -q -pl :surprising-aeron-service -am \
  -Dtest=CoreOrderedOrderBatchTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Observed baseline: `Tests run: 6, Failures: 2, Errors: 0`; the partial amend test observed no fatal exception, and the mixed-user test observed `OK` instead of `REJECTED`.

Final focused result: the same command exited `0`; `CoreOrderedOrderBatchTest` ran 6 tests with 0 failures/errors. The real adapter regression also passed:

```text
... -Dtest=DeterministicExchangeCoreAdapterTest#marksRejectedReplacementAfterSuccessfulCancelAsMatcherStateChanged ... test
exit 0
```

## Acceptance gates

All commands used the JDK25 prefix above.

| Gate | Result |
|---|---:|
| Exact Task 3 suite: `TradingOrderBatchCodecTest,CoreOrderedOrderBatchTest,SurprisingClusteredServiceTest` | 14 passed |
| Affected protocol/service reactor | 198 passed: 53 protocol + 145 service |
| Matching-provider fixture gate from the review | 7 passed: 4 public-trade + 3 order-book depth |
| W1/W2 fence: `CoreMessageCodecTest,CoreExportCodecTest,W1W2InvariantFenceTest,CoreNativeSnapshotProductLineTest` | 18 passed |

All Maven commands exited `0`. `git diff --check` passed. `.factorypath` is absent, the wallet directory is absent, and no wallet process was started. LSP diagnostics could not be queried for this linked worktree because the configured LSP root resolves to the integration worktree; Maven compilation and the affected reactor supplied the final compile/test validation.

## Changed files

- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/CoreMatchingResult.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreOrderedOrderBatchTest.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapterTest.java`

The temporary `.debug-journal.md` is local-only and will be removed before commit. The pre-existing gate-review evidence file will remain uncommitted.
