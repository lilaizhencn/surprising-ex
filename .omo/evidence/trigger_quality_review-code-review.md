# Trigger execution code-quality review

## Scope and evidence

Read-only review of current trigger execution: `TriggerOrderIndex` candidate construction; mark-price trigger evaluation; OCO cancellation; queued child matching; export capacity; and risk-scan/cross-risk updates.

Inspected production sources:
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TriggerOrderIndex.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java`
- `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java`

Inspected neighboring tests:
- `CoreProbeStateTest` lines 384-439 (direct and mark-trigger execution)
- `CoreRiskStateTest` lines 70-191 (batching and cross-margin portfolio risk)

Attempted focused verification:
`mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=CoreProbeStateTest,CoreRiskStateTest test`
did not run tests because the environment has no JDK 25; Maven Enforcer failed before compilation or test execution.

Skill-perspective check: ran. The `remove-ai-slops` and `programming` skill criteria were consulted. This Java review found no prompt tests, deletion-only tests, tautological tests, implementation-constant-only tests, untyped escape hatches introduced in this scope, or unnecessary boundary parsing/normalization. The diff does violate the shared performance/simplicity perspective through repeated whole-list copying on the mark-price hot path (finding HIGH-1). Existing tests are behavior-oriented, but do not cover the multi-trigger workload or OCO/queue interaction needed to expose it.

## Findings

### CRITICAL

None.

### HIGH

1. **Quadratic allocations and copying per mark-price command as affected trigger count grows.**
   - Evidence: [CoreProbeState.java:1460](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1460) iterates every candidate. Each transition calls [CoreProbeState.java:1638](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1638), which appends changed trigger IDs via [CoreProbeState.java:1652](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1652). `appendDistinct` recreates a `LinkedHashSet`, copies the entire accumulated list, then materializes a new list on every call at [CoreProbeState.java:1700](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1700). OCO cancellation also performs one transition per sibling at [CoreProbeState.java:1560](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1560).
   - Workload: for **T** candidate trigger mutations (trailing-state updates, sibling cancellations, claims and/or child reservations), metadata aggregation alone is Θ(T²) element copies/allocations. The index is updated for each mutation too, costing Θ(T log N) set/map work, where **N** is the indexed trigger population. A one-tick cascade with tens of thousands of candidates therefore has a quadratic local allocation component on the single owner thread, defeating the purpose of candidate indexing and threatening deterministic command latency.
   - Required fix: accumulate changed IDs in a mutable command-scoped insertion-ordered set (or defer one deduplicated merge) and materialize immutable lists once when producing the delta/export. Preserve command ordering and snapshot semantics.
   - Missing proof: the only mark-trigger test has one candidate ([CoreProbeStateTest.java:415](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:415)); it cannot detect this workload regression.

### MEDIUM

1. **Candidate construction is materially more expensive than the advertised index shape under a broad price move.**
   - Evidence: [TriggerOrderIndex.java:59](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TriggerOrderIndex.java:59) takes both threshold-map views, copies all matching IDs into a new `TreeSet`, then exposes it.
   - Workload: with **G** greater/equal IDs at prices at or below mark, **L** less/equal IDs at prices at or above mark, and **R** trailing orders, each call costs O(log P + (G + L + R) log(G + L + R)) time and O(G + L + R) temporary memory; **P** is the number of distinct trigger-price buckets. The scan is semantically necessary for the triggered set, and all trailing orders must be revisited to update extrema, but re-sorting the union is not demonstrated as necessary by this code. It compounds HIGH-1 when the command processes a broad move.
   - Recommendation: keep deterministic order, but avoid a full union/sort when a deterministic traversal of the relevant maps plus a separate trailing traversal suffices; benchmark before adopting a different iterator strategy.

2. **Risk scans have a bounded user count but not a bounded per-user portfolio workload, and they eagerly materialize both candidate users and cross portfolios.**
   - Evidence: [TradingCoreReducer.java:939](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:939) first builds a full post-cursor user list in [TradingCoreReducer.java:2259](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:2259), although at most `maxUsers` are consumed. For each of those users with a cross position in the marked symbol, [TradingCoreReducer.java:999](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:999) materializes all eligible same-settlement cross positions and traverses them twice more.
   - Workload: command CPU is O(U_remaining + B · P_cross) and allocation is O(U_remaining + P_cross per processed user), where **B ≤ 1024** is the batch cap, **U_remaining** is all indexed users after the cursor, and **P_cross** is a user portfolio’s eligible cross positions. Thus a continuation near the start still scans/copies every remaining symbol user merely to process 1,024; a highly diversified account has no per-command work cap. The broad portfolio recomputation is required for correct cross-margin equity, so this is a bounded-latency/GC concern, not a claim that it can be omitted.
   - Missing proof: [CoreRiskStateTest.java:71](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreRiskStateTest.java:71) validates 1,300 single-position users and [CoreRiskStateTest.java:164](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreRiskStateTest.java:164) validates one two-position portfolio. Neither establishes a no-materialization bound or a multi-instrument cross-risk workload.

3. **No test covers the command-level interaction of multiple simultaneous candidates, OCO sibling cancellation, queued child exports, and backlog reservation.**
   - Evidence: mark-price test uses one trigger with an OCO group but no sibling ([CoreProbeStateTest.java:420](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:420)); direct execution covers one queued child ([CoreProbeStateTest.java:385](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:385)). Production cancels siblings before queueing each child ([CoreProbeState.java:1508](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1508)), reserves 1 + queued-child events before append ([CoreProbeState.java:571](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:571)), and appends children as pending matching commands ([CoreProbeState.java:637](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:637)).
   - Risk: current code appears to skip canceled siblings because it rechecks `PENDING` ([CoreProbeState.java:1462](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1462)), but the relevant state/export/queue invariant is unproved. Add a behavioral test with two crossing siblings and an independent trigger: exactly one OCO child is queued, the independent child is queued, the parent plus each child consume correctly ordered export slots, and a deliberately insufficient backlog restores all trigger and reservation state.

### LOW

1. **Export capacity is deliberately conservative but can reject while actual byte capacity remains.**
   - Evidence: [CoreExportState.java:105](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java:105) reserves `additionalEvents × MAX_EVENT_BYTES`, while append later measures actual encoded bytes ([CoreExportState.java:87](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java:87)).
   - Workload/impact: O(1), correctness-safe admission control. This limits trigger cascades by a worst-case payload reservation and may reduce usable backlog capacity. No change is required unless observed rejection rates make throughput a concern.

## Review outcome

- codeQualityStatus: **BLOCK**
- recommendation: **REQUEST_CHANGES**
- blockers:
  1. Fix the Θ(T²) changed-ID accumulation on the mark-price trigger path.
  2. Add a behavior-level multi-candidate OCO + queued-child + export-capacity regression that proves the command’s atomic state/export outcome.
