# Trigger and mark-price security/stability audit

Scope reviewed: current branch changes plus the trigger, mark-price, matching-queue, OCO, and risk-scan paths named in the audit request. This is a read-only review; no production code was changed.

Skill-perspective check: ran. `programming` and `remove-ai-slops` were consulted before assessing test relevance/maintainability. The new tests violate the `programming` perspective by using `Thread.sleep` to prove timing behavior and by asserting the undesirable no-retry behavior; they do not violate the anti-slop perspective in production code beyond the unchecked/unbounded queues listed below.

## CRITICAL

None.

## HIGH

### H1 — a transient mark-price publication failure leaves the symbol pending forever

Confirmed. In [MarkPriceCorePublisher.java](../../surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceCorePublisher.java), a failed send retains the symbol in `pendingBySymbol` (lines 89-104), but the `finally` block schedules another drain only if a *new* publication increments `publishGeneration` (lines 106-110). If the last update for a symbol fails while Aeron is briefly unavailable, no timer/backoff exists to retry it. The core can therefore retain a stale mark indefinitely, preventing correct trigger execution and risk/liquidation evaluation until another price update arrives.

The test explicitly locks this behavior: [MarkPriceCorePublisherTest.java](../../surprising-price/surprising-price-provider/src/test/java/com/surprising/price/mark/service/MarkPriceCorePublisherTest.java#L48) expects exactly one attempt after failure, and uses `Thread.sleep` at line 60. That is a brittle timing test which protects a harmful liveness failure rather than the intended delivery contract.

Remediation: retain per-symbol coalescing, but schedule a bounded delayed retry whenever pending work remains, with exponential backoff/jitter and cancellation on close. Test a controlled scheduler/clock: a failed latest event must retry without another publication, and an older retry must never supersede a newer event.

### H2 — duplicate in-flight matching/query requests accumulate unbounded `PendingClient` objects

Confirmed. [SurprisingClusteredService.java](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java#L76) now appends every request with an already-pending matching/query sequence to an `ArrayDeque` (lines 76-92). There is no per-sequence or per-session cap. The deque is only removed on terminal timer handling (lines 159 and 181); `onSessionClose` removes egress state but does not remove that session's queued `PendingClient` references (lines 147-149). A client can resend a command ID while matching is delayed/unavailable, or open/close sessions while doing so, causing unbounded memory retention and later fan-out work on the clustered service thread.

Remediation: deduplicate pending replies by `(sessionId, commandId/correlationId)`, cap outstanding waiters globally and per session, and remove matching `PendingClient` entries on session close. Return the normal duplicate/pending response rather than registering additional waiters. Add a deterministic test with a stalled completion proving a bounded number of waiters and cleanup on close.

### H3 — matcher recovery mutates/iterates core-owned state from an arbitrary future-completion thread

Confirmed. [CoreProbeState.java](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java#L1177) installs `whenComplete` on the asynchronous matcher rebuild. Its callback iterates the non-thread-safe `LinkedHashMap pendingMatching` (lines 1180-1187) or calls `resumePendingMatching`, which iterates that same map and submits more work (lines 1198-1200). `completeMatching` concurrently removes entries from that map on the Aeron clustered-service owner thread (lines 982-987). `DeterministicExchangeCoreAdapter.rebuildAsync` builds a chain of asynchronous exchange-core calls ([DeterministicExchangeCoreAdapter.java](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java#L233)), so the callback is not guaranteed to run on the cluster owner thread. This creates data races/possible `ConcurrentModificationException`, duplicate matcher submissions, and non-deterministic replay behavior.

Remediation: future callbacks must only publish an immutable completion signal; have the clustered-service timer/owner thread consume that signal and exclusively access `pendingMatching`. Carry a recovery generation/token and ignore stale completion signals. Add a deterministic test that completes a rebuild from a non-owner executor while another matching item is completed, then verifies ordered, exactly-once continuation.

## MEDIUM

### M1 — mark-price/scan command authority is not enforced inside the core

Confirmed in code; exploitability depends on deployment ingress controls, which were not present in the reviewed code. [CoreProbeState.java](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java#L506) accepts any command header with `WireMessageKind.COMMAND` and only uses `source/sourceId` for replay sequencing (lines 513-523). It does not restrict `APPLY_MARK_PRICE` (lines 1344-1351) or `CONTINUE_RISK_SCAN` (lines 1370-1377) by caller identity/source. [AeronClientPool.java](../../surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java#L155) sends all command types as `CommandSource.GATEWAY` (lines 155-159), including mark prices ([MarkPriceCorePublisher.java](../../surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceCorePublisher.java#L114)).

If an untrusted or compromised Aeron client can connect, it can submit a monotonic, arbitrary mark price and force trigger/risk fan-out. This must be treated as a boundary assumption, not as an input-validation guarantee.

Remediation: enforce an allowlist of message types per authenticated service principal at the Aeron ingress; use distinct non-forgeable producer identities/credentials for price and liquidation continuation commands. If cluster networking is intentionally the only control, document and test that isolation as a deployment security requirement.

## LOW

None.

## Notes on fan-out and replay

`TriggerOrderIndex.candidates` constructs a complete new `TreeSet` for every mark update and can legitimately return every pending trigger for a symbol ([TriggerOrderIndex.java](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TriggerOrderIndex.java#L59)). OCO cancellation subsequently scans every sibling ([CoreProbeState.java](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java#L1560)). This is bounded only by the number of accepted trigger orders; I found no configured per-user/per-symbol trigger or OCO-group limit in the reviewed ingress path. It is a capacity risk, but not a confirmed vulnerability without the product-level order quotas/configuration, so it is not elevated to a finding.

Risk scans themselves cap a single command at 4,096 users ([ContinueRiskScanCommand.java](../../surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/ContinueRiskScanCommand.java#L3); [TradingCoreReducer.java](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java#L918)), and mark application starts with 1,024. The main liveness issue is stale mark publication (H1), not an unbounded single scan.

Tests were inspected but not run because this was a read-only audit and the defects are established by control flow. No success claim is made without a test artifact path.
