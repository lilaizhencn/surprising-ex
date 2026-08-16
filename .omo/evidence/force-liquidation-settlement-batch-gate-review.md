# Force Liquidation and Settlement Batch Gate Review

recommendation: REJECT

## originalIntent

Perform an executable, read-only funds-safety and failure-mode audit of force-liquidation and expiring-contract settlement batch processing, covering `CoreProbeState`, the reducer/command path, providers, and tests. Explain partial completion, Future waits, retries/duplicates, state consistency, and ordinary-order starvation, and distinguish correctness-critical sequencing from performance-only concerns.

## desiredOutcome

Evidence-backed assurance that liquidation and settlement commands either complete safely and resumably or fail in a way that cannot strand funds, positions, progress, or unrelated orders; findings must include severity and exact source/test pointers.

## userOutcomeReview

The reducer-level monetary transitions are generally conservative and settlement progress is persisted in core state, but the asynchronous matching continuation has an unbounded head-of-line failure mode and an uncovered exceptional-completion window. Those violate the implied funds-safety/liveness criteria: a permanently incomplete Future can prevent all later matching commands from finalizing, while an unexpected continuation exception can remove the pending command before recording a terminal result.

The settlement provider's loop itself is sequenced correctly against the clustered service: the clustered service withholds client egress until `completeMatching`, so the synchronous client wait normally receives final settlement progress rather than the initial `MATCHING_PENDING` response. Stable command IDs and persisted cursors make ordinary timeout retries resumable. Liquidation batch items are independently committed; a provider exception stops the current loop before risk-scan continuation, but the fixed-delay task retries and completed liquidation plans are no longer returned as work.

## blockers

### B1 — HIGH

- violatedCriterion: FS-ASYNC-1 — every accepted liquidation/settlement/matching command must eventually reach a terminal result or a bounded failure path without indefinitely blocking unrelated commands.
- observation: Matching completion is strictly gated by the first pending sequence, but there is no deadline, cancellation, or watchdog for a Future that never completes. A hung first settlement/liquidation/matching Future makes `takeMatchingResult` return null forever for every later sequence; timer callbacks continuously reschedule. Ordinary orders can be accepted and reserve funds during `beginMatching`, yet never finalize.
- evidencePointer: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:972-974`; `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:170-190`; reservation-before-completion path at `CoreProbeState.java:670-772`.

### B2 — HIGH

- violatedCriterion: FS-ATOMIC-1 — asynchronous command completion must atomically leave either a terminal command result plus consistent state, or a recoverable pending record.
- observation: `completeMatching` removes the pending entry before applying the reducer and only catches selected domain/argument/arithmetic exceptions. An unexpected runtime failure in state adoption, stamping, delta generation, export append, or result encoding can escape after the pending record is removed and before the final `commandResults` overwrite. A retry can then observe the earlier stored `MATCHING_PENDING` result while `matchingSequence(commandId)` is zero, so no continuation is reattached. This can strand settlement progress or expose partially adopted state without a terminal response.
- evidencePointer: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:982-1135`, especially removal at 986, narrow catches at 1100-1110, and terminal result write at 1131-1133; duplicate lookup at `CoreProbeState.java:506-511`; no adversarial continuation-exception test in `CoreProbeStateTest.java` or `CoreMatchingStateTest.java`.

## findingsAndSequencing

### Correctness-critical constraints

1. **Continuation order must remain FIFO.** Core state is mutated in cluster-log order while matcher work completes asynchronously. `takeMatchingResult` permits only the oldest pending sequence to commit. Removing this ordering without a different serialization proof would allow later orders to finalize against stale state. Evidence: `CoreProbeState.java:972-974`.
2. **Settlement chunks must advance only from core-owned progress.** The reducer verifies settlement identity, parameters, and exact cursor continuity, cancels symbol orders only for the first chunk, persists intermediate cursor state, and records the lifecycle marker only on completion. Evidence: `TradingCoreReducer.java:1260-1348`; `CoreTreasuryState.java:89-92,120-126`.
3. **Provider retries must reuse deterministic IDs and cursor.** The settlement provider derives a command ID from product line, symbol, settlement ID, and cursor; liquidation derives one from liquidation identity and execution inputs. This prevents duplicate monetary application across response-unknown retries while allowing each settlement chunk to have a distinct ID. Evidence: `ExpiringContractSettlementFanoutService.java:43-61`; `LiquidationAeronGateway.java:49-67`; duplicate handling `CoreProbeState.java:506-522`.
4. **Client response must represent continuation completion.** `SurprisingClusteredService` stores clients by matching sequence and emits only after `completeMatching`; this is what makes the provider's synchronous `command` safe to decode as final progress. Evidence: `SurprisingClusteredService.java:70-92,170-190`; initial pending response construction `CoreProbeState.java:640-774`.

### Performance-only unless unbounded

- A large but finite settlement requires one synchronous request/response per user chunk and temporarily competes with ordinary matching commands. That is throughput/latency only because each chunk is bounded and FIFO preserves correctness.
- Strict FIFO becomes correctness/liveness critical when the head Future never resolves (B1), because later commands never terminalize and order reservations can remain locked indefinitely.
- Liquidation `processWork` is sequential. A slow item delays later items and risk-scan continuation in that cycle; finite delay is performance-only. An exception aborts the cycle, but fixed-delay scheduling retries and core state excludes terminal plans, so this is at-least-once partial-batch behavior rather than an all-or-nothing batch.

## retriesDuplicatesAndPartialCompletion

- Settlement partial completion is intentionally durable: each accepted chunk commits user/treasury changes and a next-user cursor in the replicated core state. Snapshot coverage verifies cursor persistence and completion exactly once at reducer level (`CoreLifecycleStateTest.java:178-200`).
- A provider restart or Kafka redelivery queries progress first and resumes from the core cursor. A completed settlement returns without another command (`ExpiringContractSettlementFanoutService.java:47-49`).
- The client retries the identical encoded message up to three times after response-unknown runtime failures (`AeronClientPool.java:504-523`). Core command-ID deduplication and pending-client fan-in prevent reapplication when the first attempt committed.
- Liquidation provider partial batch completion is safe per item: prior actions remain committed if a later action throws. The next scheduled cycle re-queries authoritative work. The missing failure-path test is an evidence gap, not by itself a separate blocker.

## testAndSlopReview

- Direct overfit/slop pass: existing tests are mostly behavior-level reducer tests. No deletion-only/removal-pin tests were found. The provider tests are heavily mocked and mirror happy-path calls; they do not reproduce response-unknown, duplicate-while-pending, stuck Future, continuation exception, or partial liquidation batch failure.
- `CoreProbeStateTest.asyncMatchingCompletesOnOwnerContinuationWithoutBlockingApply` proves two successful Futures complete in order, but its spin-wait helper assumes eventual completion and cannot detect the unbounded-head failure class.
- `ExpiringContractSettlementFanoutServiceTest.resumesSettlementFromCoreCursor` proves cursor wiring with mocked final responses, not the real clustered-service wait boundary.
- No supplied code-review report explicitly documents a programming/remove-ai-slops perspective. Direct review covered those criteria; absence of the report is recorded as an evidence gap rather than an independent blocker.

## checkedArtifactPaths

- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreTreasuryState.java`
- `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java`
- `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java`
- `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationService.java`
- `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java`
- `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/task/LiquidationMaintenanceTask.java`
- `surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/ExpiringContractSettlementFanoutService.java`
- `surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/ExpiringContractSettlementConsumer.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java`
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java`
- `surprising-liquidation/src/test/java/com/surprising/liquidation/provider/service/LiquidationServiceTest.java`
- `surprising-account/surprising-account-provider/src/test/java/com/surprising/account/provider/service/ExpiringContractSettlementFanoutServiceTest.java`
- Relevant working-tree diff and `git diff --check` (clean).

## exactEvidenceGaps

- No test where the oldest matcher Future never completes while later ordinary orders complete matcher work.
- No test injecting an unexpected runtime exception after pending removal but before terminal command-result storage.
- No real clustered-service/provider integration test proving duplicate-while-pending fan-in and final progress delivery.
- No liquidation provider test where action N succeeds, action N+1 throws, and the next scheduled cycle resumes without duplicate monetary effects.
- No manual QA matrix, executor evidence bundle, code-review report, or notepad path was supplied for this audit.
- Targeted Maven tests were inspected but not executed because the assignment is explicitly read-only and the workspace already contains unrelated uncommitted changes/build artifacts.

## notes

- The working tree was already dirty. This review did not modify source, tests, configuration, or existing user changes; only this required report artifact was added.
- `git diff --check` passed.
