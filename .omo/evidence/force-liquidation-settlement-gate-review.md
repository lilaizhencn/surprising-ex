# Force Liquidation and Settlement Gate Review

recommendation: APPROVE (user-facing verdict: PASS)

## originalIntent

Verify, read-only, whether force-liquidation and settlement processing construct complete active-order batches, whether the batch cancellation completion waits for every submitted cancellation future, and whether ordinary orders can be blocked. Distinguish same-symbol matching semantics from cross-symbol/cross-user effects and identify deterministic maximum-work implications.

## desiredOutcome

A reproducible PASS/FAIL/INCONCLUSIVE conclusion backed by exact source and test locations, with no production edits or commits.

## userOutcomeReview

PASS. The review claim is supported by the production call chain:

1. **C1 — CoreProbeState constructs full order lists: PASS.**
   - Liquidation snapshots every active order for the liquidation user and symbol with `activeOrderIndex.ids(...).stream()...toList()` at `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:896-905`.
   - Settlement snapshots every active order for the symbol with `activeOrderIndex.ids(symbol).stream()...toList()` at `CoreProbeState.java:906-914`.
   - Validation/export tracking independently materializes the same complete ID sets at `CoreProbeState.java:833-848`.
   - The index APIs return whole descending sets and contain no limit at `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/ActiveOrderIndex.java:19-40`.

2. **C2 — cancelBatch waits for all futures: PASS.**
   - `cancelBatchAsync` creates one cancellation future per order at `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:107-113`.
   - It folds every future into `combined` with `thenCombine` at lines 113-120 and returns only `combined.thenApply(...)` at lines 121-122. Therefore the returned batch future cannot complete until every constituent future completes. It reports the first rejected result in input order but does not short-circuit completion.

3. **C3 — ordinary orders can be blocked across users/symbols: PASS.**
   - Every place/cancel/replace operation uses the same global `matchingSubmissionTail`; declarations are at `DeterministicExchangeCoreAdapter.java:53-56`, and global FIFO reservation is at lines 376-415.
   - All batch cancellation futures are created/reserved before `cancelBatchAsync` returns (`DeterministicExchangeCoreAdapter.java:111-113`). A later ordinary order reserves behind them, regardless of user or symbol.
   - The gate advances when each command is submitted, not when its result completes (`DeterministicExchangeCoreAdapter.java:125-134`, especially line 130), so the cross-symbol blockage is submission-queue delay, not necessarily a wait for every prior result.
   - Exchange Core is configured with one matching engine at `DeterministicExchangeCoreAdapter.java:291-303` (line 298), so symbols are not parallelized by this adapter configuration. Per-symbol book mutation remains serial, but this implementation also creates cross-symbol/cross-user serialization.
   - Authoritative state completion is FIFO as well: only the first pending matching sequence can be taken at `CoreProbeState.java:972-975`; thus a completed later ordinary order cannot be applied ahead of an earlier liquidation/settlement batch completion.

4. **C4 — deterministic maximum work is bounded: FAIL as a property, confirming the claim's concern.**
   - Work per liquidation is O(number of that user's active orders on the symbol); work per settlement is O(all active orders on the symbol). There is no batch ceiling in either `CoreProbeState.java:903-913` or `ActiveOrderIndex.java:19-40`.
   - Consequently there is no configuration-independent deterministic maximum number of cancellation submissions/futures for one command. The practical ceiling is only the current active-order population and available memory/queue capacity.

## blockers

None. No stated verification criterion is contradicted by the artifacts.

## slopAndProgrammingReview

Direct overfit/slop pass completed over the relevant production code, diff, and tests. No excessive/deletion-only/tautological/implementation-mirroring tests or unnecessary extraction/parsing/normalization were introduced for this behavior. The notable false-confidence risk is coverage mismatch: `CoreProbeStateTest.java:482-513` proves a liquidation-work query action limit, not bounded cancellation work, waiting for all batch futures, or cross-symbol/cross-user non-blocking. This is an evidence gap/NOTE, not a blocker for this read-only verification task.

No task-specific code-review report or manual-QA matrix was supplied. Existing `.omo/evidence` reports were enumerated, but none was identified as the executor report for this exact assignment. Direct inspection provides criterion coverage; report absence is not a blocker under the gate rules.

## checkedArtifactPaths

- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/ActiveOrderIndex.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java`
- Current working-tree diff for the above production/test files
- `.omo/evidence/` inventory for review/manual-QA/notepad artifacts

## exactEvidenceGaps

- No relevant test directly creates a multi-order liquidation or settlement batch and proves that the returned batch future waits for the final constituent future.
- No relevant test queues an ordinary order for a different symbol/user behind a liquidation/settlement batch and measures submission or authoritative-completion blocking.
- Targeted command attempted: `mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=CoreProbeStateTest,CoreMatchingStateTest test`. It failed before compilation/tests because Maven Enforcer requires JDK 25 (`RequireJavaVersion: Surprising EX must be built with JDK 25`). Runtime test status is therefore INCONCLUSIVE, while the requested production-code claims are statically decisive.
- No notepad path, original executor evidence bundle, code-review report, or task-specific manual-QA matrix was provided.

