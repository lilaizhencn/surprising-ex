# Trigger mark-price bounded-work review

## Scope and evidence

- Reviewed the current `TriggerOrderIndex`, `CoreProbeState` mark-price path and matching continuation path, and `TradingCoreReducer` risk scan.
- The requested ULW attempt directory is unavailable: `omo ulw-loop status --json` returned `ULW_LOOP_PLAN_MISSING`; this report therefore uses the required fallback path.
- `git diff --check` passed.
- Targeted test command attempted: `mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=CoreProbeStateTest,CoreRiskStateTest test`. It did not execute tests because the environment supplies JDK 21 while the Maven enforcer requires JDK 25.
- Skill-perspective check: ran. `remove-ai-slops` and `programming` were loaded before test/maintainability judgment. The reviewed trigger/risk code has no prompt tests, deletion-only tests, or untyped escape hatches in this scope. The relevant violation is functional/performance scope: an index has been added but it has no work budget or continuation seam; the sole crossing test tests one trigger and does not prove the intended bound.

## Verdict

The index **does remove the prior full scan of every trigger order**: it has per-symbol/per-condition price maps and `candidates()` is the only mark-price caller.  It **does not guarantee bounded work per mark-price command**.  A single command can scan, copy, mutate, and enqueue an unbounded number of pending trigger orders for its symbol; that fan-out is handled synchronously, not through continuations.

Risk scanning is separately batched by users (default 1,024), with a `CONTINUE_RISK_SCAN` command.  That does not bound trigger evaluation.  It also does not strictly bound per-user cross-margin work because `updateCrossRisk` iterates that user's qualifying positions, but the primary unbounded fan-out at issue is trigger processing.

## Findings

### CRITICAL

None.

### HIGH

1. **A mark-price command remains unbounded in the number of trigger candidates and matching continuations.**

   `TriggerOrderIndex.candidates()` traverses every crossed `GREATER_OR_EQUAL` price bucket, every crossed `LESS_OR_EQUAL` bucket, and all trailing stops, and copies every ID into a new `TreeSet`; there is no limit or cursor ([TriggerOrderIndex.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TriggerOrderIndex.java:59)-[67]). `CoreProbeState` synchronously loops the returned set ([CoreProbeState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1457)-[1510]). Each triggered order can reserve a child order and append an `EXECUTE_TRIGGER_ORDER` continuation ([CoreProbeState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1604)-[1629], [1676]-[1685]). After the whole scan, every queued continuation is synchronously exported, inserted into `pendingMatching`, and submitted ([CoreProbeState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:637)-[648]).

   Complexity for one `APPLY_MARK_PRICE` is at least O(C log C) temporary-set work plus O(C) state/queue work, where C is all crossed threshold orders plus all trailing stops for the symbol; it can grow with the entire per-symbol trigger book. The export capacity check occurs only after that work and rejects/rolls back rather than splitting it ([CoreProbeState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:569)-[575]). Its 1,000,000-event ceiling ([CoreExportState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java:20)-[23]) is backlog protection, not a per-command work budget.

   Required change before approval: introduce a deterministic candidate work budget/cursor and continuation mechanism for trigger evaluation, including an explicit policy for trailing stops. A continuation must carry enough ordered state to preserve deterministic sequence/order and must be resilient to newer mark prices.

2. **Tests do not demonstrate the claimed bounded behavior or split fan-out.**

   The only direct mark-price trigger test creates one trigger and verifies only that it executes ([CoreProbeStateTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:415)-[439]). It would pass with an all-orders scan or with the current unbounded index. The risk tests correctly prove a separate 1,024-user risk batch and a follow-up continuation ([CoreRiskStateTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreRiskStateTest.java:71)-[90]), but no equivalent trigger batch test exists.

   Required change before approval: add an observable scenario with more qualifying triggers than the intended trigger-evaluation budget; prove one mark command processes only the budget, records/dispatches a deterministic continuation, and subsequent commands complete all candidates exactly once. Cover both threshold and trailing candidates, and a new mark price arriving while work remains.

### MEDIUM

1. **Risk batching is bounded by users, not by work per user.** `continueRiskScan` caps users to 4,096 and defaults to 1,024 ([TradingCoreReducer.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:918)-[947]), but each selected cross-margin user runs over all qualifying positions ([TradingCoreReducer.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:999)-[1009]). This is a secondary unbounded dimension if strict command budgets are a requirement; it is not mitigated by the current test's many-single-position fixture.

### LOW

None.

## Recommendation

- `codeQualityStatus`: BLOCK
- `recommendation`: REQUEST_CHANGES
- `blockers`: the two HIGH findings above. The JDK-25 test environment must also be supplied before test success can be claimed; the attempted Maven command is not evidence of passing tests.
