# Transaction-chain code-quality review

Scope: read-only source audit of Aeron client/protocol/service, order/matching/account/risk/liquidation/funding/insurance/ADL, price, exporter, gateway and WebSocket surfaces. No tests were run: this review makes code-proven findings from control/data-flow inspection, not execution claims. `git diff --check` passed. The worktree already contains unrelated staged/untracked files; they were not assessed as this audit's implementation.

## Skill-perspective check

Ran: yes. I loaded `remove-ai-slops` and `programming` before assessing maintainability/test relevance.

- `remove-ai-slops`: the production path has needless allocation/copy work on every synchronous Aeron command and an unnecessary unbounded queue; no deletion-only, tautological, or implementation-mirroring tests were relied upon.
- `programming`: this Java transaction path has no untyped escape hatch in the findings below, but it does contain boundary/reliability design violations: blocking waits in the clustered-service owner thread and production queues without a declared bound/overload policy.

## Findings

### CRITICAL

None identified in this static pass.

### HIGH

1. **The order placement retry key changes after a result-unknown failure, so a client retry cannot reliably recover the original result.**

   - Evidence: [AeronOrderCommandService.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java:61) allocates a new `orderId` before each `place`; [lines 240-249](/Users/atomex/Desktop/surprising/surprising-ex/surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java:240) include that new ID only when there is no client order ID. The Aeron client tells callers to retry/query using the *same* command ID on an ambiguous timeout at [SurprisingAeronClient.java:206](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java:206). Core only deduplicates the same command ID at [CoreProbeState.java:459](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:459).
   - Current behavior: a no-`clientOrderId` HTTP retry gets a fresh order and command ID. The original order may already have committed while the client receives a rejection/unknown for the retry; the caller cannot query that original generated ID. A retry with a client ID happens to reuse the command ID, but that is implicit and incomplete API-level idempotency.
   - Harm: a timeout turns into user-visible ambiguity in an order/funds path; retries may return a misleading duplicate-client-id rejection instead of the original accepted/fill result.
   - Minimal remediation: require/persist a caller idempotency key for `PLACE_ORDER`, derive both command/order identity deterministically from it (or persist the generated identity before submit), and on `ResultUnknownException` query `COMMAND_RESULT_QUERY` with that same ID. Add an integration test that commits the first command while dropping its egress response, then retries.

2. **Mark-price submission has an unbounded in-memory queue behind a single worker that can block for up to 15 seconds per event and then silently drops the final failure.**

   - Evidence: [MarkPriceCorePublisher.java:25](/Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceCorePublisher.java:25) uses `ConcurrentLinkedQueue`; [lines 41-45](/Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceCorePublisher.java:41) unconditionally enqueue; [lines 58-63](/Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceCorePublisher.java:58) drain only one worker; [lines 77-89](/Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceCorePublisher.java:77) retry synchronously three times. Each Aeron call defaults to five seconds ([MarkPriceProperties.java:79](/Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/config/MarkPriceProperties.java:79)).
   - Current behavior: Aeron disruption or congestion creates an unlimited backlog; each oldest event monopolizes the only worker for up to 15 seconds, all later marks age, and the final failed mark is logged then discarded.
   - Harm: direct memory-exhaustion risk under sustained market input; stale marks delay trigger/liquidation/risk decisions, then an unreported sequence gap causes the core to operate on obsolete price state.
   - Minimal remediation: replace the FIFO with a bounded, per-symbol latest-value coalescer (mark price is a state update), enforce an explicit overload policy/metric, and retain/retry the current latest value with capped exponential backoff. Make shutdown drain/hand off explicitly. Add outage tests proving bounded memory and eventual latest-sequence delivery.

3. **Cluster command processing blocks its single deterministic service thread on exchange-core futures, including unbounded work proportional to all open orders/positions.**

   - Evidence: [DeterministicExchangeCoreAdapter.java:76](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:76) builds a future per batch item and blocks with `allOf(...).join()` at [line 82](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:82); cancellation does the same at [lines 115-124](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:115). Liquidation materializes and cancels every open order for a user/symbol on the service path at [CoreProbeState.java:1223](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1223); settlement does so across an instrument and additionally materializes all position users at [lines 1245-1259](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1245).
   - Current behavior: an exceptional liquidation/expiry batch is one synchronous, unbounded command. Because `CoreProbeState.apply` runs from `SurprisingClusteredService.onSessionMessage` ([SurprisingClusteredService.java:52](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:52)), it stalls every unrelated ingress command, query response, risk update and price event for that product line.
   - Harm: latency tail and availability collapse during exactly the volatility conditions that produce large liquidation/cancellation sets; arrays/futures amplify allocation and GC in the owner thread.
   - Minimal remediation: make liquidation/settlement an explicitly persisted, bounded-chunk continuation with a fixed work budget per cluster command/tick; ensure exchange-core interaction is deterministic and bounded within that budget. Add load/integration tests with thousands of open orders verifying other users' order/mark processing stays within an SLO while continuation advances.

### MEDIUM

4. **Aeron snapshot publication can spin indefinitely in the clustered-service callback, with no close/error/deadline escape.**

   - Evidence: [SurprisingClusteredService.java:75](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:75) enters snapshot handling; [lines 81-86](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:81) loops while `offer < 0` using only `idleStrategy.idle()`.
   - Current behavior: prolonged snapshot-publication backpressure prevents the service callback from returning; no timeout, publication state check, or observable failure exists.
   - Harm: leader snapshot/recovery can hang indefinitely and monopolize the core agent thread, impairing failover and availability. Whether a normal Aeron deployment can sustain this state requires fault-injection profiling, but the lack of a termination condition is proven.
   - Minimal remediation: use Aeron-supported snapshot publication semantics with a bounded deadline and explicit handling of closed/max-position outcomes; emit a health/failure signal suitable for cluster restart/failover. Test a permanently backpressured publication.

5. **Every WebSocket message creates a second virtual watchdog thread, and the timeout does not guarantee cancellation of a blocked socket write.**

   - Evidence: [ClientConnection.java:116](/Users/atomex/Desktop/surprising/surprising-ex/surprising-gateway/src/main/java/com/surprising/websocket/provider/service/ClientConnection.java:116) creates a watchdog via `Thread.ofVirtual()` for each payload; it sleeps at [line 122](/Users/atomex/Desktop/surprising/surprising-ex/surprising-gateway/src/main/java/com/surprising/websocket/provider/service/ClientConnection.java:122) then interrupts the writer at [line 124](/Users/atomex/Desktop/surprising/surprising-ex/surprising-gateway/src/main/java/com/surprising/websocket/provider/service/ClientConnection.java:124). The actual `session.sendMessage` is a synchronous call at [line 136](/Users/atomex/Desktop/surprising/surprising-ex/surprising-gateway/src/main/java/com/surprising/websocket/provider/service/ClientConnection.java:136).
   - Current behavior: traffic doubles virtual-thread scheduling/allocation per connection, while `interrupt()` is only advisory for the underlying WebSocket implementation. A non-interruptible blocking write can leave the writer stuck; its bounded queue then fills and the connection close path may still depend on that same I/O implementation.
   - Harm: avoidable allocation/scheduler pressure at fanout scale and degraded slow-client isolation under blocked writes. The precise thread/pinning impact needs a production profile, but the per-message thread creation is code-proven.
   - Minimal remediation: use the container's asynchronous send API or one reusable scheduled timeout per connection/send future; close/cancel through the transport-supported mechanism and observe stuck writers. Load-test slow/non-reading clients with fanout throughput and carrier-thread pinning metrics.

### LOW

6. **Core request/response encoding copies whole payloads multiple times on the synchronous path; it is measurable overhead, not yet a proven bottleneck.**

   - Evidence: client encodes to a new `byte[]` and wraps it at [SurprisingAeronClient.java:115](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java:115); egress copies into another array then decodes at [lines 152-160](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java:152). The server ingress copies and decodes at [SurprisingClusteredService.java:59](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:59), and serializes a fresh response at [lines 67-71](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:67).
   - Current behavior: each command creates at least distinct ingress, egress and protocol byte arrays; large command-result/export payloads multiply young-gen allocation.
   - Harm: throughput/GC latency risk at high order volume; it is not a correctness defect and must be profiled before changing Aeron buffer ownership.
   - Minimal remediation: benchmark allocation rate and p99 under representative command/result sizes; only if material, add safe reusable/direct-buffer encoding at the protocol boundary with strict ownership/lifetime tests.

## Test/review relevance

Existing targeted tests cover some pool argument validation and publisher queue policies, but I found no evidence in the inspected paths of a result-unknown placement retry test, a mark-price Aeron-outage bounded-backlog test, a large liquidation/settlement fairness test, or a permanently backpressured snapshot test. These are behavioral gaps, not prompts or implementation-mirroring test suggestions.

## Conclusion

`codeQualityStatus`: **BLOCK**

`recommendation`: **REQUEST_CHANGES**

`blockers`:

1. Establish end-to-end order placement idempotency/recovery across a lost Aeron response.
2. Bound and coalesce/fail-safe the mark-price → core publication path.
3. Bound liquidation/settlement work so one large account/instrument cannot block the single cluster owner thread indefinitely.
