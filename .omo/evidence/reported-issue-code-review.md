# Code review — reported concurrency issue

## Verdict

- Claim accuracy: **partially accurate**.
- `codeQualityStatus`: **BLOCK**
- `recommendation`: **REQUEST_CHANGES**

The reported synchronous-owner-thread problem is fixed: Core submits matcher work asynchronously and the Cluster timer completes it on the owner thread. `cancelBatchAsync` does **not** submit cancellation commands in parallel; each call reserves `matchingSubmissionTail`, so exchange-core submission is serialized. However, the new continuation design leaves two correctness holes which can desynchronize the authoritative Core state from exchange-core and permit orders across liquidation/settlement boundaries.

## Findings

### P0

None found.

### P1 — partial `cancelBatchAsync` failure leaves Core and matcher with different open orders

**Evidence:**

- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:111-122` submits every cancellation and returns the first rejected result only after the preceding futures have completed. A successful earlier cancellation is therefore not rolled back when a later cancellation is rejected.
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:903-904,912-913` uses this aggregate result for liquidation and settlement.
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1083-1097` mutates business state only when the aggregate is accepted. A rejected aggregate records no successful individual cancellations.
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1100-1135` schedules matcher recovery only when the reducer throws; a normal `matchingResult.accepted()==false` result does not rebuild exchange-core from the unchanged Core state.

This is reachable when an earlier pending cancellation succeeds in exchange-core but has not yet completed on the Core timer, then liquidation/settlement snapshots that still-open Core order and submits a second cancel. The batch can cancel one order and reject another. Core retains all orders while exchange-core has removed a subset. Later matching then has an incorrect book relative to reservations/funds.

**Smallest safe architecture bound:** make the batch completion carry per-order outcomes. On any non-all-success outcome, rebuild the matcher from the pre-command Core snapshot before accepting another matching continuation (or atomically reconcile only the confirmed cancellations in the same owner transition). Do not treat a Boolean aggregate as a transactional batch.

### P1 — liquidation and settlement have no lifecycle fence against later matching commands

**Evidence:**

- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:833-847` validates liquidation/settlement and snapshots active-order IDs, but records no pending lock or lifecycle state.
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:702-709` accepts and reserves a later `PLACE_ORDER` while the earlier lifecycle command is still pending.
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:896-914` submits only the snapshotted cancellations. Because matcher submission is ordered, a subsequent place is submitted after those cancellations and therefore survives them.
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1083-1097` then executes liquidation or marks the instrument settled without revalidating/rejecting the later pending placement.
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:75-83,170-190` accepts further log entries while a matching response waits for timer completion; it does not supply that fence.

For liquidation, a user can place an order on the same symbol between snapshot and completion; for settlement, any symbol order can enter during this window. The order can be present in exchange-core after the cancellation batch while Core liquidates/settles based on the earlier snapshot. This threatens order sequencing and, for the liquidated user, funds/position safety.

**Smallest safe architecture bound:** add an owner-thread pending-lifecycle fence keyed by `(symbol)` for settlement and `(userId, symbol)` for liquidation. While present, reject or deterministically defer all matching commands in that key; revalidate immediately before lifecycle state transition. Include the fence in snapshot/recovery or derive it from `pendingMatching`. Do not broaden this into a global matcher lock.

### P2 — direct Aeron use in the liquidation worker is bounded but result-unknown semantics are not covered

**Evidence:**

- `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationService.java:33-45` synchronously queries work, executes every action serially, then continues the risk scan.
- `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java:49-67` gives `EXECUTE_LIQUIDATION` a stable command ID, which is correct for idempotent retry.
- `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java:41-45` uses a new UUID for `CONTINUE_RISK_SCAN`; the client explicitly reports result-unknown outcomes as requiring same-command-ID retry at `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java:255-257`.
- `surprising-liquidation/src/test/java/com/surprising/liquidation/provider/service/LiquidationServiceTest.java:24-39` only verifies mock calls. It contains no result-unknown/retry test and no Core integration test.

The synchronous worker is not an owner-thread block and serial action processing is an appropriate safety bias. Still, retry behavior for a lost response is unproven; a new ID for a retried risk-scan continuation can advance the cursor twice. Use a stable ID for a particular continuation intent (or query Core state before retry) and add one narrow integration test for result-unknown replay.

## Test and evidence assessment

I ran with IBM Semeru JDK 25:

- `mvn -q -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreMatchingStateTest,CoreLifecycleStateTest -Dsurefire.failIfNoSpecifiedTests=false test` — pass (13 + 12 tests).
- `mvn -q -pl surprising-aeron-core/surprising-aeron-client -am -Dtest=AeronClientPoolTest -Dsurefire.failIfNoSpecifiedTests=false test` — pass (8 tests).
- `mvn -q -pl surprising-liquidation -am -Dtest=LiquidationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` — pass (3 tests).

The earlier `.omo/evidence/fullchain-runtime-qa-20260815/source-audit.txt` describes the old `allOf(...).join()` implementation, so it is stale and cannot validate this diff. The current tests do not cover partial cancellation, a place arriving behind lifecycle cancellation, snapshot/recovery of either state, or a real Aeron result-unknown path.

## Skill-perspective check

Ran the required `remove-ai-slops` and `programming` skill perspectives. The diff violates neither through deletion-only tests, prose/prompt tests, untyped escape hatches, nor unnecessary parsing/normalization. It does introduce over-complex asynchronous aggregation without a transactional outcome model (`cancelBatchAsync`), and the new tests largely adapt existing happy paths rather than lock the lifecycle concurrency behavior. This is a P2 test-relevance issue by itself; the P1 findings above are independent correctness failures.

## Required blockers

1. Make partial batch cancellation fail closed without Core/exchange-core divergence.
2. Fence or defer matching commands that overlap pending liquidation/settlement, and prove the behavior with deterministic interleaving tests.
