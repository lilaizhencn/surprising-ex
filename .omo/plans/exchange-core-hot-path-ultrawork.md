# Exchange Core Hot-Path, JVM Stability, and Recovery Plan

## TL;DR
> Summary:      Make `TradingRuntimeState` the owner-thread online authority, remove global work and avoidable copies from the command path, then prove the result with stage-by-stage JFR/benchmark evidence and destructive recovery/capacity gates.
> Deliverables: 
> - A persistent, indexed Runtime State path with no per-command projection, materialization, or full parity
> - Bounded continuation scheduling, flyweight protocol I/O, compact outbox frames, and allocation-controlled matcher results
> - A snapshot admission barrier with segmented deterministic snapshots and fail-closed recovery
> - A JDK 25/JFR capacity harness and an evidence-backed update to `docs/exchange-core-trading-hot-path-review.md`
> - 60-minute 100,000 finalized commands/s, 10-second 200,000/s burst, 24-hour soak, failover, and six-product-line funds-conservation evidence
> Effort:       XL
> Risk:         High - the work changes funds/position authority, continuation ordering, snapshots, and the single-product-line capacity envelope.

## Scope
### Must have
- Preserve one isolated Product Core per `ProductLine`, exchange-core as the only executable order book, Aeron Cluster Log/Archive as command authority, and Product Core -> Audit Exporter -> Kafka -> History Projector -> PostgreSQL as the audit path (`CONTEXT.md:3-31`, `docs/exchange-core-trading-hot-path-review.md:65-77`).
- Make `TradingRuntimeState` the owner-thread online authority; normal commands and online queries must not construct a complete `TradingCoreState`. Keep `RuntimeStateMaterializer` and `RuntimeStateProjector` only at snapshot, restore/replay, debug sampling, and explicit parity boundaries.
- Replace all persistent-path calls from perpetual match, funding, risk, and liquidation processors to `RuntimeStateProjector.project(...)` with owner-thread mutation plus validate-before-commit or a touched-entity undo/change set.
- Maintain direct user-scoped reservation, position, and active-order indexes and direct commandId-to-pending-sequence lookup. Non-fill operations must have average `O(1)` lookup/update; fills are `O(k)` for `k` maker/fill events; market/FOK work is `O(k + levels)`; lifecycle/risk work is bounded `O(batch)` (`docs/exchange-core-trading-hot-path-review.md:249-264`).
- Replace one-timer-per-pending and full pending rescheduling with a bounded completion queue, one drain wakeup, fixed drain budget, explicit max-in-flight, and deterministic backpressure.
- Decode Aeron ingress from `DirectBuffer + offset + length`; never retain an Aeron callback slice after the callback. Copy at most once into a compact owned command only when asynchronous lifetime requires it; encode egress into bounded reusable Agrona buffers.
- Preserve replicated outbox durability while storing pre-encoded frames and terminal metadata, batching via one contiguous copy, ACKing without event decode, and enforcing pending bytes as the primary capacity gate.
- Unify Aeron/Agrona/LZ4 dependencies, add a duplicate-class build gate, pin Temurin/HotSpot 25 and required native/module flags, and choose G1 versus ZGC plus heap/direct-memory limits from identical JFR capacity runs.
- Add an admission fence that drains accepted continuations, fixes a snapshot epoch, writes deterministic segmented Runtime State plus the paired matcher snapshot, then reopens admission. Restore must fail closed on product-line, checksum, sequence, manifest, state-hash, or open-order mismatch.
- Run RED-before-GREEN tests, affected-module Maven tests, a comparable benchmark, JFR capture/summary, report update, atomic commit, and push for every numbered stage. A stage is not accepted on throughput alone.
- Final evidence must cover 1,000,000 users and 4,000,000 active orders, hot-symbol/hot-user skew, 0/1/10/100-maker fills, all six product lines and their lifecycle operations, corrected acceptance-to-finalization p50/p99/p99.9, full-load leader kill, Kafka/Projector outage, outbox limit, Archive disk full, follower lag, snapshot corruption, Archive replay, and user/market-maker funds conservation (`docs/exchange-core-trading-hot-path-review.md:295-313`).

### Must NOT have (guardrails, anti-slop, scope boundaries)
- Do not split Product Core by symbol or risk domain in this plan. Reassess sharding only after P0-P2 and the single-owner capacity gate (`docs/exchange-core-trading-hot-path-review.md:289-293`).
- Do not add a second executable order book, a second command journal, PostgreSQL/Valkey reads in online adjudication, wallet-service dependencies, cross-product-line state/topics/accounts, or asynchronous Runtime State writers.
- Do not weaken price-time priority, idempotency, source-sequence ordering, funds/reservation atomicity, snapshot/matcher pairing, outbox replication, terminal retention, or fail-closed recovery.
- Do not retain a transport-backed buffer beyond its callback lifetime, use unbounded queues/futures, busy-idle indefinitely on the owner thread, or replace a bounded copy with unsafe buffer ownership.
- Do not claim strict `O(1)` for fills. A taker matching `k` makers is `O(k)`; market/FOK traversal may additionally be proportional to crossed price levels.
- Do not run parity/materialization on every production command. Do not delete parity/projector/materializer: keep them as cold-path verification and migration tools.
- Do not remove pending/completion Kafka facts until an explicit producer/consumer audit proves they are not an external contract. Default decision is compatibility-preserving export.
- Do not change the exchange-core fork unless Task 7 JFR proves the adapter remains a material bottleneck after local state, scheduling, protocol, and outbox work; any fork change must retain the pinned provenance/checksum gate (`surprising-aeron-core/surprising-aeron-service/pom.xml:57-90`).
- Do not accept a benchmark based on matcher publish rate, HTTP receipt rate, a closed-loop client, a short peak, or uncorrected latency. The numerator is Aeron-committed commands with final business adjudication.
- Do not commit `.idea/`, `.local-logs/`, `data/`, raw JFR files, generated capacity state, or local runtime output.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD + JUnit 5/AssertJ, Maven Surefire, shell integration harnesses, Java 25 JFR, and corrected open-loop latency histograms
- QA policy: every task has agent-executed scenarios
- Evidence: `<attemptDir>/task-<N>-<slug>.<ext>` — under ulw-loop, `<attemptDir>` is the `currentAttemptDir` from `omo ulw-loop status --json` (`.omo/evidence/ulw/<session>/<goalId>/a<attempt>`); outside ulw-loop use `.omo/evidence/`
- RED rule: before production changes, add the named test and run the exact task test command. Capture a failing assertion caused by the missing behavior in `<attemptDir>/task-<N>-<slug>-red.log`; compilation errors, environment failures, and unrelated failures do not count as RED.
- GREEN rule: rerun the identical test command after implementation and capture zero failures in `<attemptDir>/task-<N>-<slug>-green.log`.
- Stage closure: Task 1 creates `scripts/run-exchange-core-hot-path-stage.sh`. Every later task must invoke it with Java 25, three measured forks, fixed workload/seed, `-XX:+AlwaysPreTouch`, `Xms=Xmx`, and `-XX:StartFlightRecording=settings=profile`; the runner emits benchmark stdout, `stage-result.json`, `.jfr`, `jfr-summary.txt`, `jfr-hot-methods.txt`, `jfr-allocation-by-site.txt`, `jfr-gc.txt`, and `jfr-safepoints.txt` under the task attempt directory.
- Stage PASS rule: all functional tests pass; the targeted forbidden frame/allocation/scan is absent or below the stage threshold; median finalized throughput does not regress more than 5% versus the immediately preceding accepted stage on the same host/JDK/config; p99.9 does not regress more than 10%; funds/hash checks remain exact; and `docs/exchange-core-trading-hot-path-review.md` contains the stage result. If environmental variance exceeds 5%, rerun three more forks and compare medians; never lower a gate to make a stage pass.
- Report fields per stage: `stage`, `parent commit`, UTC time, hardware/OS/container limits, exact Temurin/HotSpot 25 build and flags, exact test/benchmark command, seed/state/workload, offered/finalized rate, p50/p99/p99.9, pending/completion/outbox maxima, heap-after-GC/direct-memory/GC/safepoint data, top CPU/allocation frames, state hashes, funds deltas, artifact paths, before/after delta, PASS/FAIL, rollback boundary, and remaining risk. Section 10 must also maintain an explicit “not yet run” list until Task 12.
- Numeric stability defaults: until Task 1 records a stricter product SLO, use p99 <= 10 ms, p99.9 <= 50 ms, zero Full GC, no safepoint > 100 ms, total GC pause < 1% of wall time, and no post-warmup heap/direct-memory/pending/outbox growth across four consecutive 15-minute windows. Task 1 may tighten, never loosen, these defaults without an approved product SLO cited in the report.
- Commit/push gate: after PASS, inspect the full diff, stage only task files, commit the task and its direct tests/report row together, run `git log -1 --oneline`, then `git push`. Verify `test "$(git rev-parse HEAD)" = "$(git rev-parse @{u})"`. On failure, do not commit/push; retain evidence and fix inside the same stage. After a pushed regression, revert that single stage commit rather than resetting shared history.

## Execution strategy
### Parallel execution waves
> Target 5-8 tasks per wave. <3 per wave (except final) = under-splitting.
> Extract shared dependencies as Wave-1 tasks to maximize parallelism.

Wave 1 (no dependencies):
- Task 1: freeze measurement/decision contracts and capture the JFR baseline

Wave 2 (after Wave 1; implement in isolated worktrees, integrate and close stages in numeric order so the shared report and benchmark baseline remain linear):
- Task 2: Runtime authority and touched-entity transactions, depends [1]
- Task 5: bounded matching completion scheduler and indexes, depends [1]
- Task 6: flyweight ingress and reusable egress, depends [1]
- Task 8: pre-encoded outbox and metadata ACK, depends [1]
- Task 9: dependency/JDK/GC stability, depends [1]

Wave 3 (after the relevant Wave-2 bases):
- Task 3: persistent perpetual match commits, depends [2]
- Task 7: matcher/result allocation control, depends [5, 6]

Wave 4 (after Wave 3):
- Task 4: persistent funding/risk/liquidation continuations, depends [2, 3]

Wave 5 (after P0/P1 integration):
- Task 10: snapshot admission barrier and segmented snapshot/recovery, depends [2, 4, 5, 8, 9]

Wave 6 (after snapshot format is fixed):
- Task 11: three-node fault and recovery matrix, depends [6, 8, 9, 10]

Wave 7 (final capacity integration):
- Task 12: scale, burst, 60-minute, soak, skew, fill-depth, and six-line funds gates, depends [3, 4, 5, 6, 7, 8, 9, 10, 11]

Critical path: Task 1 -> Task 2 -> Task 3 -> Task 4 -> Task 10 -> Task 11 -> Task 12

### Dependency matrix
| Task | Depends on | Blocks | Can parallelize with |
|------|------------|--------|----------------------|
| 1 | none | 2, 5, 6, 8, 9 | none |
| 2 | 1 | 3, 4, 10 | 5, 6, 8, 9 |
| 3 | 2 | 4, 12 | 7, 8, 9 |
| 4 | 2, 3 | 10, 12 | 7, 8, 9 |
| 5 | 1 | 7, 10, 12 | 2, 6, 8, 9 |
| 6 | 1 | 7, 11, 12 | 2, 5, 8, 9 |
| 7 | 5, 6 | 12 | 3, 4, 8, 9 |
| 8 | 1 | 10, 11, 12 | 2, 5, 6, 9 |
| 9 | 1 | 10, 11, 12 | 2, 5, 6, 8 |
| 10 | 2, 4, 5, 8, 9 | 11, 12 | none |
| 11 | 6, 8, 9, 10 | 12 | none |
| 12 | 3, 4, 5, 6, 7, 8, 9, 10, 11 | none | none |

## Todos
> Implementation + Test = ONE task. Never separate.
> Every task MUST have: References + Acceptance Criteria + QA Scenarios + Commit.

- [x] 1. Freeze the performance contract and capture a reproducible JFR baseline

  What to do: Add a stage runner and machine-readable result schema around the existing benchmark mains and `ClusterCapacityMain`. Use a corrected latency histogram (`recordValueWithExpectedInterval` or an equivalently tested algorithm), distinguish acceptance from finalization, capture offered/accepted/finalized counts and queue/outbox/runtime/JVM gauges, and produce the six standard JFR summaries. Run three fixed-seed forks for adapter-only, accept/freeze, full in-memory, concurrent ingress, and perpetual end-to-end baselines. Add the stage evidence table and unresolved-decision register to the audit report. Resolve, by recorded microexperiment rather than interview: (a) HdrHistogram dependency versus an in-repo corrected histogram, (b) validate-before-commit for simple commands versus a compact undo/change set for multi-entity commands, (c) primitive map versus power-of-two ring for possibly gapped matching sequences, (d) pooled owned command structs versus one compact byte copy for asynchronous messages, (e) segmented snapshot writer API, and (f) G1 versus ZGC experiment matrix. Default choices are HdrHistogram, validate-before-commit plus touched-entity change sets, primitive maps unless density is proven, compact owned command copies, an Aeron-publication chunk writer, and both collectors retained until Task 9 evidence.
  Must NOT do: Do not optimize production code in this stage, call a short local benchmark production capacity, use Java other than Temurin/HotSpot 25, or accept an uncorrected/closed-loop latency distribution.

  Parallelization: Can parallel: NO | Wave 1 | Blocks: [2, 5, 6, 8, 9] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterCapacityMain.java:99-170` - current workload configuration, finalization output, and funds/book gate
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreInMemoryBenchmark.java:34-75` - full core place/cancel baseline
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreAcceptFreezeBenchmark.java:33-68` - accept/freeze baseline
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/ExchangeCoreConcurrentBenchmark.java:28-44` - matcher-only control
  - Test:     `surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/AeronClientCapacityTest.java` - current capacity/mailbox test style
  - External: `https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html` - Java 25 JFR start/check/dump/stop contract
  - External: `https://docs.oracle.com/en/java/javase/25/docs/specs/man/jfr.html` - JFR summary and view commands

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-tools -am -Dtest=ClusterCapacityMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test` first fails for corrected coordinated-omission/finalization metrics, then passes.
  - [ ] `bash -n scripts/run-exchange-core-hot-path-stage.sh` passes and `scripts/run-exchange-core-hot-path-stage.sh --stage task-1-baseline --attempt-dir "$ATTEMPT_DIR" --benchmark-suite baseline --forks 3 --jfr-settings profile` exits 0 with all required artifacts.
  - [ ] `jq -e '.result=="PASS" and .forks==3 and .metrics.finalizedPerSecond and .latency.p999Micros and .jfr.hotMethods' "$ATTEMPT_DIR/task-1-baseline-result.json"` passes.
  - [ ] `rg -n "Stage 1.*PASS|Decision register|not yet run" docs/exchange-core-trading-hot-path-review.md` returns the new baseline/report entries.
  - [ ] The task commit is pushed and `test "$(git rev-parse HEAD)" = "$(git rev-parse @{u})"` passes.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: reproducible Java 25 baseline with JFR summaries
    Tool:     bash
    Steps:    `export JAVA_HOME=$(/usr/libexec/java_home -v 25); export PATH="$JAVA_HOME/bin:$PATH"; ATTEMPT_DIR="$(omo ulw-loop status --json | jq -r .currentAttemptDir)"; scripts/run-exchange-core-hot-path-stage.sh --stage task-1-baseline --attempt-dir "$ATTEMPT_DIR" --benchmark-suite baseline --forks 3 --jfr-settings profile`
    Expected: Exit 0; every benchmark prints its PASS marker; stage JSON names the exact JDK/flags and all JFR views are non-empty.
    Evidence: <attemptDir>/task-1-baseline-result.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: wrong JDK is rejected
    Tool:     bash
    Steps:    `JAVA_HOME=$(/usr/libexec/java_home -v 21) scripts/run-exchange-core-hot-path-stage.sh --stage task-1-wrong-jdk --attempt-dir "$ATTEMPT_DIR" --benchmark-suite baseline --forks 1`
    Expected: Exit non-zero with `JDK 25 is required`; no PASS result JSON is written.
    Evidence: <attemptDir>/task-1-baseline-error.log
  ```

  Commit: YES | Message: `perf(core): establish JFR hot-path baseline` | Files: [`surprising-parent/pom.xml`, `surprising-aeron-core/surprising-aeron-tools/pom.xml`, `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterCapacityMain.java`, `surprising-aeron-core/surprising-aeron-tools/src/test/java/com/surprising/aeron/tools/ClusterCapacityMetricsTest.java`, `scripts/run-exchange-core-hot-path-stage.sh`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 2. Make Runtime State authoritative with touched-entity transactional mutation

  What to do: Rework `CoreProbeState`/`TradingCoreRuntime` so owner-thread handlers read and mutate persistent `TradingRuntimeState` directly. Give simple commands validate-before-commit and multi-balance/reservation/position/treasury changes a compact touched-entity change set that can validate and roll back without scanning global state. Maintain per-user reservation/position/active-order indexes during each mutation. Serve online user/order/client-order queries from Runtime. Restrict full materialization/parity to explicit snapshot/debug/shadow hooks and projection to construction/restore. Keep an opt-in deterministic shadow parity mode for RED/GREEN replay evidence, off by default in production.
  Must NOT do: Do not delete materializer/projector/parity, expose Runtime references to other threads, update secondary indexes after publishing a result, or retain immutable `TradingCoreState` as the next transition input.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [3, 4, 10] | Blocked by: [1]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:3059-3091` - current delta, full parity/materialization, immutable transition, and state adoption
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java:188-209` - immutable before/delta/authoritative transition contract to replace
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateMaterializer.java:12-120` - cold-path deterministic materialization, including the current per-user global reservation/position scans
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateProjector.java:11-85` - restore-only complete projection
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java:TradingRuntimeState` - mutable owner-thread authority
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/RuntimeStateProjectorTest.java:22-369` - projection/materialization/parity equivalence patterns
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/W1W2InvariantFenceTest.java` - invariant fence

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimeAuthorityHotPathTest,RuntimeStateProjectorTest,W1W2InvariantFenceTest -Dsurefire.failIfNoSpecifiedTests=false test` proves commands/queries do not materialize/project/full-parity while debug replay still detects an injected mismatch.
  - [ ] A structure test fails if production command handling calls `RuntimeStateMaterializer.materialize`, `RuntimeStateParityChecker.assertMatches`, or `RuntimeStateProjector.project`; only constructor/restore/snapshot/debug allow-list sites remain.
  - [ ] Growth tests run the same place/query/cancel operation at 10, 10,000, and 1,000,000 unrelated users and assert touched-key counts and allocation counts are constant within test tolerance; no scan/copy is proportional to global users/orders/reservations/positions.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-2-runtime-authority --attempt-dir "$ATTEMPT_DIR" --benchmark-suite runtime --forks 3 --jfr-settings profile` returns PASS, shows no materializer/projector/parity hot frame, and meets the common regression gate.
  - [ ] The report Stage 2 row contains JFR before/after frames, touched-entity complexity evidence, state/funds hashes, rollback boundary, and PASS; the atomic commit is pushed.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: owner-thread Runtime completes commands without full-state work
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimeAuthorityHotPathTest,CoreProbeStateTest,CoreResultLedgerTest -Dsurefire.failIfNoSpecifiedTests=false test && scripts/run-exchange-core-hot-path-stage.sh --stage task-2-runtime-authority --attempt-dir "$ATTEMPT_DIR" --benchmark-suite runtime --forks 3 --jfr-settings profile`
    Expected: Tests and runner pass; state hash/funds match shadow replay; JFR has zero sampled hot-path calls to full materialize/project/parity.
    Evidence: <attemptDir>/task-2-runtime-authority.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: failed multi-entity validation is atomic
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimeAuthorityHotPathTest#insufficientFundsRollsBackEveryTouchedEntity -Dsurefire.failIfNoSpecifiedTests=false test`
    Expected: Exact rejection code is `INSUFFICIENT_AVAILABLE_BALANCE`; balances, reservations, positions, orders, indexes, treasury, revision, and business hash equal their pre-command values.
    Evidence: <attemptDir>/task-2-runtime-authority-error.log
  ```

  Commit: YES | Message: `perf(core): make runtime state authoritative` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateMaterializer.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateProjector.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateParityChecker.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/RuntimeAuthorityHotPathTest.java`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 3. Commit perpetual match results into the persistent Runtime in O(k)

  What to do: Change perpetual matching to validate all taker and maker deltas against the existing Runtime, compute a compact fill change set, then update only affected balances, reservations, positions, orders, per-user indexes, treasury, revisions, and facts on the owner thread. Eliminate full projection and `expected.users()` revision sweeps. Reuse primitive collections/bounded arrays for changed IDs; cost may scale with `k` fills, never total users/orders/positions.
  Must NOT do: Do not claim strict `O(1)`, reorder maker fills, partially commit a failed fill batch, mix linear/inverse formulas, or reuse one product line’s reservation/position logic in another.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [4, 12] | Blocked by: [2]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualMatchProcessor.java:8` - current Runtime match processor
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualFillCalculator.java:RuntimePerpetualFillCalculator` - fill math boundary
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/CoreMatchingResult.java:CoreMatchingResult` - ordered matcher result contract
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/RuntimePerpetualMatchProcessorTest.java:1-88` - current parity cases
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CorePerpetualFinancialMatrixTest.java` - linear/inverse, fee, position, insurance, and liquidation funds matrix
  - External: `https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/src/main/java/exchange/core2/core/orderbook/OrderBookDirectImpl.java` - price-time and fill iteration reference

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimePerpetualMatchProcessorTest,CorePerpetualFinancialMatrixTest,PerpetualMatchComplexityTest -Dsurefire.failIfNoSpecifiedTests=false test` covers linear/inverse 0/1/10/100-maker fills, duplicate completion, self-trade prevention, overflow, and insufficient reservation.
  - [ ] `rg -n "RuntimeStateProjector\.project|expected\.users\(\)" surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualMatchProcessor.java` returns no hot-path match.
  - [ ] The complexity test proves touched-order/user counts equal the taker plus actual makers and remain unchanged when 1,000,000 unrelated users and 4,000,000 unrelated orders are present.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-3-perpetual-match --attempt-dir "$ATTEMPT_DIR" --benchmark-suite perpetual-match --fill-depths 0,1,10,100 --forks 3 --jfr-settings profile` returns PASS and the report/commit/push gates pass.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: 100-maker perpetual fill is ordered, conserved, and O(k)
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=PerpetualMatchComplexityTest#fillsOneHundredMakersInMatcherOrder -Dsurefire.failIfNoSpecifiedTests=false test && scripts/run-exchange-core-hot-path-stage.sh --stage task-3-perpetual-match --attempt-dir "$ATTEMPT_DIR" --benchmark-suite perpetual-match --fill-depths 0,1,10,100 --forks 3 --jfr-settings profile`
    Expected: Exactly 100 maker fills in price-time order; user and market-maker balances/positions/fees conserve; work/allocation scale with 100 fills, not global state.
    Evidence: <attemptDir>/task-3-perpetual-match.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: arithmetic failure leaves no partial fill mutation
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=PerpetualMatchComplexityTest#overflowRollsBackAllMakerAndTakerChanges -Dsurefire.failIfNoSpecifiedTests=false test`
    Expected: Exact arithmetic rejection/fail-closed result; pre/post state hash, balances, reservations, positions, orders, indexes, and treasury are identical.
    Evidence: <attemptDir>/task-3-perpetual-match-error.log
  ```

  Commit: YES | Message: `perf(core): commit perpetual fills in runtime` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualMatchProcessor.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualFillCalculator.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/RuntimePerpetualMatchProcessorTest.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/PerpetualMatchComplexityTest.java`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 4. Persist funding, risk, and liquidation continuations in bounded Runtime batches

  What to do: Convert perpetual funding, mark-price/risk, liquidation, insurance, and ADL paths to mutate the persistent Runtime using durable cursors and fixed work budgets. Add direct symbol/user/position/reservation/risk/liquidation indexes so each continuation resumes from its stored cursor without reconstructing Runtime or scanning global maps. Snapshot every durable cursor; reconstruct only transient wakeup timing on restore. Preserve separate linear/inverse risk and treasury math and incremental funds assertions.
  Must NOT do: Do not process an unbounded lifecycle in one Aeron command, use `stream().findFirst()` over global scans, persist wall-clock retry deadlines as authority, or combine U-margined/coin-margined/delivery/option state machines.

  Parallelization: Can parallel: NO | Wave 4 | Blocks: [10, 12] | Blocked by: [2, 3]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualFundingProcessor.java:40` - current full projection
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualRiskProcessor.java:15-85` - mark-price/continuation projection and scan entry
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualLiquidationProcessor.java` - liquidation state machine
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/ContinueRiskScanCommand.java:ContinueRiskScanCommand` - bounded risk continuation command
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RiskScanRuntime.java:RiskScanRuntime` - durable Runtime cursor
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java` - funding/risk/liquidation continuation equivalence
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreLifecycleWorkTest.java` - bounded exactly-once lifecycle work and snapshot resume

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLifecycleStateTest,CoreLifecycleWorkTest,CoreRiskStateTest,PerpetualContinuationComplexityTest -Dsurefire.failIfNoSpecifiedTests=false test` covers funding, mark price, risk scan, liquidation, insurance, ADL, cursor snapshot/resume, stale inputs, and exact-once facts.
  - [ ] No production call to `RuntimeStateProjector.project` remains in `RuntimePerpetualFundingProcessor`, `RuntimePerpetualRiskProcessor`, or `RuntimePerpetualLiquidationProcessor`; no continuation touches more than its configured batch plus fixed bookkeeping.
  - [ ] Linear and inverse perpetual funds matrices show zero delta across user, market-maker, fee, funding, liquidation-fee, insurance, and ADL legs before/after snapshot resume.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-4-perpetual-continuations --attempt-dir "$ATTEMPT_DIR" --benchmark-suite perpetual-lifecycle --forks 3 --jfr-settings profile` returns PASS and the report/commit/push gates pass.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: risk/funding/liquidation resume in bounded exact-once batches
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=PerpetualContinuationComplexityTest#snapshotResumePreservesEveryCursorAndFunds -Dsurefire.failIfNoSpecifiedTests=false test && scripts/run-exchange-core-hot-path-stage.sh --stage task-4-perpetual-continuations --attempt-dir "$ATTEMPT_DIR" --benchmark-suite perpetual-lifecycle --forks 3 --jfr-settings profile`
    Expected: Each command consumes at most the configured work budget; restored execution yields identical facts/state hash and zero funds delta.
    Evidence: <attemptDir>/task-4-perpetual-continuations.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: stale mark/funding/liquidation input fails without cursor drift
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=PerpetualContinuationComplexityTest#staleInputDoesNotAdvanceCursorOrMutateFunds -Dsurefire.failIfNoSpecifiedTests=false test`
    Expected: Exact stale/conflict result; cursor, revision, hash, balances, positions, treasury, and outbox are unchanged.
    Evidence: <attemptDir>/task-4-perpetual-continuations-error.log
  ```

  Commit: YES | Message: `perf(core): persist perpetual continuations` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualFundingProcessor.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualRiskProcessor.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualLiquidationProcessor.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/PerpetualContinuationComplexityTest.java`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 5. Replace pending-matching timer amplification with a bounded completion drain

  What to do: Have matcher callbacks offer `(sequence, result/failure)` into a bounded MPSC queue. The owner thread schedules at most one drain wakeup, drains a configured maximum per invocation, and finalizes only the next global sequence. Add direct commandId-to-sequence and sequence-to-pending/completed primitive indexes, max-in-flight admission, deterministic `MATCHING_BACKPRESSURED`/existing compatible rejection mapping, high/low watermarks, queue telemetry, and restart-time wakeup reconstruction. Use a primitive map unless Task 1 proved a dense ring safe across sequence gaps and recovery.
  Must NOT do: Do not create a timer per pending item, reschedule the whole pending set, scan pending by commandId, busy-idle until `scheduleTimer` succeeds, drop/reorder completions, or permit unbounded pending/completion/client queues.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [7, 10, 12] | Blocked by: [1]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:150-193` - timer completion/finalization and full reschedule
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:278-294` - one timer per pending and owner-thread busy idle
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:2369-2375` - linear commandId lookup
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/PendingMatching.java:6-38` - pending sequence/operation/deadline contract
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/SurprisingClusteredServiceTest.java` - async owner continuation coverage
  - External: `https://aeron.io/docs/aeron-cluster/cluster-troubleshooting/` - cluster starvation/GC/I/O failure signals

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=MatchingCompletionDrainTest,SurprisingClusteredServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` covers out-of-order callbacks, full queue, timer-offer failure, timeout, duplicate callback, restart reconstruction, and max-in-flight backpressure.
  - [ ] For 100,000 pending completions, schedule calls are bounded by `ceil(completions/drainBudget)+constant`, commandId lookup reports one direct index probe, and completion order equals global sequence.
  - [ ] `rg -n "pendingMatching\.values\(\)\.stream|schedulePendingMatchingTimers" surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/{CoreProbeState.java,SurprisingClusteredService.java}` returns no production hot-path match.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-5-completion-drain --attempt-dir "$ATTEMPT_DIR" --benchmark-suite continuation --forks 3 --jfr-settings profile` returns PASS; queue reaches bounded backpressure without OOM; report/commit/push gates pass.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: out-of-order callbacks drain in sequence with one wakeup
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=MatchingCompletionDrainTest#drainsOutOfOrderCallbacksInGlobalSequenceWithSingleWakeup -Dsurefire.failIfNoSpecifiedTests=false test && scripts/run-exchange-core-hot-path-stage.sh --stage task-5-completion-drain --attempt-dir "$ATTEMPT_DIR" --benchmark-suite continuation --forks 3 --jfr-settings profile`
    Expected: All results finalize once in global sequence; timer calls are bounded by drain batches; pending/client/queue counts return to zero.
    Evidence: <attemptDir>/task-5-completion-drain.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: full completion queue produces deterministic backpressure
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=MatchingCompletionDrainTest#fullQueueBackpressuresWithoutDropSpinOrOom -Dsurefire.failIfNoSpecifiedTests=false test`
    Expected: No completion is dropped; admission returns the specified compatible result code; owner thread does not spin; processing resumes below the low watermark.
    Evidence: <attemptDir>/task-5-completion-drain-error.log
  ```

  Commit: YES | Message: `perf(core): bound matching completion drain` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/PendingMatching.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/MatchingCompletionDrainTest.java`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 6. Decode ingress flyweights and encode bounded reusable egress buffers

  What to do: Add strict `DirectBuffer + offset + length` header/payload views with bounds/version/product-line validation; dispatch fields without creating the first full frame copy. For commands that become pending, freeze only needed data once into a compact owned representation. Add encoders accepting `MutableDirectBuffer` and explicit offsets; use per-owner reusable, pre-sized buffers for immediate response and copy only when Aeron backpressure requires queued egress. Preserve existing byte-array codec overloads and wire bytes for external callers.
  Must NOT do: Do not retain callback buffers, use expandable buffers on the hot path, share a mutable response buffer across threads/sessions, change schema bytes, or let malformed length/offset arithmetic read outside the fragment.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [7, 11, 12] | Blocked by: [1]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:59-97` - current DirectBuffer-to-byte[] and response allocations
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java:15-99` - current allocating encode/decode and wire validation
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreProtocol.java:28-40` - allocating response payload
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageHeader.java:CoreMessageHeader` - stable wire header
  - Test:     `surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreMessageCodecTest.java` - malformed/truncated/trailing/oversized compatibility tests
  - External: `https://aeron.io/docs/agrona/direct-buffer/` - fixed-size DirectBuffer/UnsafeBuffer ownership and bounds
  - External: `https://aeron.io/docs/aeron/publications-subscriptions/` - `offer` copy versus bounded `tryClaim` contract

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-protocol,surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreMessageCodecTest,CoreMessageFlyweightTest,SurprisingClusteredServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` proves byte-for-byte compatibility, arbitrary non-zero offsets, malformed bounds, and callback-lifetime safety.
  - [ ] Immediate non-pending request/response performs zero full-frame byte-array copies; pending requests perform exactly one bounded owned copy; egress queue stores immutable owned bytes only on failed offer.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-6-protocol-flyweight --attempt-dir "$ATTEMPT_DIR" --benchmark-suite protocol --forks 3 --jfr-settings profile` returns PASS and JFR allocation-by-site no longer shows `onSessionMessage` frame/payload double copies.
  - [ ] The report records controlled-copy versus any `tryClaim` experiment and the safety decision; report/commit/push gates pass.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: offset flyweight request round-trips wire-compatible response
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-protocol,surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreMessageFlyweightTest#decodesNonZeroOffsetAndMatchesLegacyBytes -Dsurefire.failIfNoSpecifiedTests=false test && scripts/run-exchange-core-hot-path-stage.sh --stage task-6-protocol-flyweight --attempt-dir "$ATTEMPT_DIR" --benchmark-suite protocol --forks 3 --jfr-settings profile`
    Expected: Legacy and flyweight headers/payloads/responses are byte-identical; copy counters meet zero/immediate and one/pending limits.
    Evidence: <attemptDir>/task-6-protocol-flyweight.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: truncated slice is rejected without retaining or reading past bounds
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-protocol -am -Dtest=CoreMessageFlyweightTest#rejectsTruncatedOffsetSliceWithoutMutation -Dsurefire.failIfNoSpecifiedTests=false test`
    Expected: Exact `ProtocolException`/drop contract; no state mutation, response, retained buffer reference, or out-of-bounds read.
    Evidence: <attemptDir>/task-6-protocol-flyweight-error.log
  ```

  Commit: YES | Message: `perf(protocol): add flyweight core message IO` | Files: [`surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java`, `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreProtocol.java`, `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessage.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java`, `surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreMessageFlyweightTest.java`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 7. Remove matcher/result stream, list-copy, and per-command future amplification

  What to do: Use Task 5’s sequence callback to replace avoidable per-command `CompletableFuture`, lambda, stream, boxing, `toList`, and `List.copyOf` allocations in matcher submission/completion and ordered batch/fill changed-ID assembly. Reuse bounded result builders and primitive ID collections, clearing them before reuse. First optimize the local adapter. Modify the pinned exchange-core fork only if JFR still attributes at least 10% of owner/matcher CPU or at least 10% of hot-path allocation to an API shape that cannot be removed locally; if so, add a sequence/callback or batch-publish API without changing matching semantics, rebuild the clean pinned artifact, and update SHA/provenance.
  Must NOT do: Do not remove price-time checks, reuse result storage before owner consumption, make snapshot/query cold paths dictate hot-path structures, or change the fork merely because an alternative API looks cleaner.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [12] | Blocked by: [5, 6]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:514-578` - per-submission gates/futures and ordered batch chain
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1000-1030` - batch future callback/completed-future path
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1943-2014` - fill changed-ID streams/lists
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapterTest.java` - determinism, snapshot, divergence, and order-book contract
  - External: `https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/src/main/java/exchange/core2/core/ExchangeCore.java` - pinned pipeline

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=DeterministicExchangeCoreAdapterTest,MatcherAllocationContractTest,CoreOrderedOrderBatchTest -Dsurefire.failIfNoSpecifiedTests=false test` proves reuse safety, callback ordering, partial batch failure, and snapshot hashes.
  - [ ] JFR shows targeted future/lambda/stream/list-copy allocation sites reduced by at least 80% from Task 1 on the identical matcher benchmark, with zero matcher failures and identical ordered result hashes.
  - [ ] If the fork is unchanged, report evidence shows the 10% threshold was not met. If changed, `mvn -pl surprising-aeron-core/surprising-aeron-service -am validate` passes the new checksum/provenance and the exact fork commit is recorded.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-7-matcher-allocation --attempt-dir "$ATTEMPT_DIR" --benchmark-suite matcher --forks 3 --jfr-settings profile` returns PASS and report/commit/push gates pass.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: ordered matcher callback path preserves results with bounded allocation
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=MatcherAllocationContractTest#reusesBuildersOnlyAfterOwnerConsumption -Dsurefire.failIfNoSpecifiedTests=false test && scripts/run-exchange-core-hot-path-stage.sh --stage task-7-matcher-allocation --attempt-dir "$ATTEMPT_DIR" --benchmark-suite matcher --forks 3 --jfr-settings profile`
    Expected: Result order/hash matches the legacy oracle; targeted allocation bytes/op fall at least 80%; no use-after-clear or dropped result.
    Evidence: <attemptDir>/task-7-matcher-allocation.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: partial batch failure remains fail-closed
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreOrderedOrderBatchTest,MatcherAllocationContractTest#partialFailureDoesNotReuseOrPublishTail -Dsurefire.failIfNoSpecifiedTests=false test`
    Expected: Successful prefix and failure position are deterministic; no tail result/fact/state mutation is published.
    Evidence: <attemptDir>/task-7-matcher-allocation-error.log
  ```

  Commit: YES | Message: `perf(matching): bound result-path allocations` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/CoreMatchingResult.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/MatcherAllocationContractTest.java`, `surprising-parent/pom.xml`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 8. Store pre-encoded outbox frames and ACK with side metadata

  What to do: Replace pending `CoreMessage` entries with immutable pre-encoded frame slices plus sequence, encoded length/digest, and deduplicated terminal-order metadata. Encode once at append, batch by writing length prefixes plus frames into one bounded destination, and ACK by sequence/metadata without `CoreExportCodec.decodeEvent`. Enforce capacity by pending bytes first and count second. Audit exporter, projector, WebSocket, and terminal-retention consumers before deciding whether matching pending/completion facts are externally required; retain them by default.
  Must NOT do: Do not weaken replicated outbox or Kafka `acks=all`, ACK ahead, release terminal state before durable ACK, expose mutable pooled frame bytes, or remove facts without a zero-consumer proof recorded in the report.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [10, 11, 12] | Blocked by: [1]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java:64-95` - allocating event/message append
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java:113-154` - ACK decode and list-copy batch
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TerminalStateRetention.java:45` - terminal pruning by export ACK
  - Pattern:  `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/KafkaCoreExportSink.java:29` - Kafka durability configuration
  - Pattern:  `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/JdbcCoreEventProjector.java:53` - idempotent projection sequence contract
  - Test:     `surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/ReliableCoreExporterTest.java` - export retry/ACK behavior

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-exporter -am -Dtest=CoreExportStateTest,CoreProbeStateTest,ReliableCoreExporterTest -Dsurefire.failIfNoSpecifiedTests=false test` covers append/batch/ACK/restore, terminal dedupe, byte limit, ACK ahead, retry, and corrupt metadata.
  - [ ] A decode counter proves ACK performs zero event decodes; a batch counter proves one contiguous destination write pass; restored pending digest/bytes/sequences/terminal metadata equal pre-snapshot values.
  - [ ] Consumer audit covers `KafkaCoreExportSink`, `KafkaProjectionWorker`, `JdbcCoreEventProjector`, and WebSocket fanout; report explicitly records KEEP or REMOVE for internal continuation facts, with KEEP on uncertainty.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-8-outbox --attempt-dir "$ATTEMPT_DIR" --benchmark-suite outbox --forks 3 --jfr-settings profile` returns PASS and report/commit/push gates pass.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: append/batch/ACK uses one encoding and metadata-only removal
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-exporter -am -Dtest=CoreExportStateTest#encodesOnceBatchesContiguouslyAndAcksWithoutDecode,ReliableCoreExporterTest -Dsurefire.failIfNoSpecifiedTests=false test && scripts/run-exchange-core-hot-path-stage.sh --stage task-8-outbox --attempt-dir "$ATTEMPT_DIR" --benchmark-suite outbox --forks 3 --jfr-settings profile`
    Expected: Export bytes are wire-identical; one encode/event, zero ACK decodes, correct terminal release, pending bytes/count/digest return to zero.
    Evidence: <attemptDir>/task-8-outbox.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: pending-byte limit backpressures before allocation blowup
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreExportStateTest#byteLimitRejectsAtomicallyBeforeEncodingBeyondCapacity -Dsurefire.failIfNoSpecifiedTests=false test`
    Expected: Exact `EXPORT_BACKLOG_FULL`; next sequence/digest/count/bytes/terminal retention and business state are unchanged; no OOM.
    Evidence: <attemptDir>/task-8-outbox-error.log
  ```

  Commit: YES | Message: `perf(export): preencode replicated outbox entries` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TerminalStateRetention.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreExportStateTest.java`, `surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/ReliableCoreExporterTest.java`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 9. Unify dependencies and select stable Java 25 memory/GC settings

  What to do: Generate dependency trees for all shaded Aeron artifacts, lock one compatible Agrona and LZ4 version using dependency management/exclusions, and add a duplicate-class failure gate. Pin the JDK 25 image/build, declare all required native access/opens consistently for service and tools, and eliminate actionable Unsafe/native/Chronicle warnings rather than suppressing them blindly. Run identical 30-minute 100k offered-load experiments with G1 and ZGC across heap candidates sized from measured live set; choose the smaller stable configuration with explicit direct-memory and host headroom. Keep `Xms=Xmx` and pre-touch.
  Must NOT do: Do not choose GC/heap from a microbenchmark, keep 512 MiB as an unmeasured production default, allocate all physical memory to heap/direct memory, or add broad module opens without a warning/stack proving need.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [10, 11, 12] | Blocked by: [1]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-parent/pom.xml:23-42` - Java 25, exchange-core, Aeron, and Chronicle pins
  - Pattern:  `surprising-parent/pom.xml:133-170` - JDK enforcer and current test module flags
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/pom.xml:18-47` - Aeron/exchange-core dependency convergence surface
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/pom.xml:96-117` - shaded service jar
  - Pattern:  `surprising-aeron-core/compose.yaml:13-18` - current ZGC, pre-touch, and 512 MiB heap
  - Pattern:  `surprising-aeron-core/Dockerfile:14` - current single `--add-opens`
  - External: `https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html` - Java 25 GC/JFR/native/module options

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core -am verify` first fails the added duplicate-class convergence fixture/current collision, then passes with one Agrona and one LZ4 class provider in each shaded jar.
  - [ ] `mvn -pl surprising-aeron-core/surprising-aeron-service -am dependency:tree -Dverbose` shows one selected Agrona and LZ4 version; `jar tf`/duplicate checker reports zero duplicate classes in service/tools/exporter shaded jars.
  - [ ] Service and tool startup logs on the pinned Temurin/HotSpot 25 image contain none of the audited native/module/Unsafe warnings; required flags are identical where the same libraries load.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-9-jvm --attempt-dir "$ATTEMPT_DIR" --benchmark-suite cluster-30m --collectors G1,ZGC --heap-candidates 2g,4g,8g --offered-rate 100000 --forks 1 --jfr-settings default` returns one selected configuration meeting stability defaults; report/commit/push gates pass.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: pinned JDK/dependencies and selected collector are stable
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core -am verify && scripts/run-exchange-core-hot-path-stage.sh --stage task-9-jvm --attempt-dir "$ATTEMPT_DIR" --benchmark-suite cluster-30m --collectors G1,ZGC --heap-candidates 2g,4g,8g --offered-rate 100000 --forks 1 --jfr-settings default`
    Expected: Duplicate-class/warning gates pass; selected collector/heap has zero Full GC, pause limits pass, and heap/direct memory stabilize with host headroom recorded.
    Evidence: <attemptDir>/task-9-jvm.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: duplicate transitive class fails the build
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -Pduplicate-class-negative-fixture verify`
    Expected: Exit non-zero naming the duplicated class and both artifacts; normal profile remains green.
    Evidence: <attemptDir>/task-9-jvm-error.log
  ```

  Commit: YES | Message: `build(core): pin Java 25 runtime dependencies` | Files: [`surprising-parent/pom.xml`, `surprising-aeron-core/surprising-aeron-service/pom.xml`, `surprising-aeron-core/surprising-aeron-tools/pom.xml`, `surprising-aeron-core/surprising-aeron-exporter/pom.xml`, `surprising-aeron-core/Dockerfile`, `surprising-aeron-core/compose.yaml`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 10. Add a snapshot admission barrier and segmented deterministic Runtime snapshots

  What to do: Introduce explicit OPEN -> FENCING -> DRAINING -> SNAPSHOTTING -> OPEN admission states. Reject/backpressure new trading commands after fencing while allowing accepted continuations to drain in global order. At zero pending, fix a snapshot epoch, stream deterministic Runtime sections directly into bounded Aeron publication chunks, pair the matcher snapshot at the same core/matcher sequences, include outbox frames/metadata and all lifecycle cursors, then reopen admission. Restore incrementally into a fresh Runtime and validate checksum/product line/config/core/matcher sequences/business hash/open-order set before publishing it. Version the snapshot format and retain only an explicit tested read path for the immediately prior format if product policy requires it; otherwise fail closed because the product is not live.
  Must NOT do: Do not wait for a natural zero-pending window, form a complete immutable state plus a second full snapshot byte array, exceed a measured owner-pause budget, publish a partially restored Runtime, or reopen admission after a failed snapshot without deterministic cleanup.

  Parallelization: Can parallel: NO | Wave 5 | Blocks: [11, 12] | Blocked by: [2, 4, 5, 8, 9]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:2508-2521` - current zero-pending requirement and joined matcher snapshot
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:101-108` - current full byte-array snapshot publication
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:200-226` - current full `ByteArrayOutputStream` restore
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:38-69` - current component arrays and full allocation
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshotCodec.java:112` - matcher manifest/hash/sequence metadata
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreStateSnapshotCodecTest.java` - round-trip metadata
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreNativeSnapshotProductLineTest.java` - paired native snapshots across product lines
  - External: `https://aeron.io/docs/aeron-archive/overview/` - recording position, extension, replay, truncate, and replication

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=SnapshotAdmissionBarrierTest,CoreStateSnapshotCodecTest,CoreNativeSnapshotProductLineTest,CoreMatchingStateTest -Dsurefire.failIfNoSpecifiedTests=false test` covers fence/drain/epoch/chunks/reopen, every cursor/outbox field, prior-format policy, and mismatch failures.
  - [ ] At 1,000,000 users/4,000,000 active orders, peak snapshot allocation is bounded by configured chunk size plus fixed state, not snapshot size; owner admission pause meets the Task 1 numeric budget; Archive retention/disk sizing is recorded.
  - [ ] Restore from every valid product-line snapshot gives identical business hash, matcher book hash, open-order set, outbox digest, cursor state, and next command result; corruption/mismatch never exposes partial state.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-10-snapshot --attempt-dir "$ATTEMPT_DIR" --benchmark-suite snapshot-under-load --state-users 1000000 --active-orders 4000000 --forks 1 --jfr-settings profile` returns PASS and report/commit/push gates pass.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: admission fence drains and streams a paired snapshot under load
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=SnapshotAdmissionBarrierTest#fencesDrainsSnapshotsAndReopensAtOneEpoch -Dsurefire.failIfNoSpecifiedTests=false test && scripts/run-exchange-core-hot-path-stage.sh --stage task-10-snapshot --attempt-dir "$ATTEMPT_DIR" --benchmark-suite snapshot-under-load --state-users 1000000 --active-orders 4000000 --forks 1 --jfr-settings profile`
    Expected: Accepted commands finalize before the epoch; later commands backpressure; chunks are bounded/deterministic; restored core/matcher hashes and open orders match; admission reopens.
    Evidence: <attemptDir>/task-10-snapshot.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: corrupt or mismatched snapshot fails closed
    Tool:     bash
    Steps:    `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=SnapshotAdmissionBarrierTest#rejectsChecksumSequenceHashAndOpenOrderMismatch -Dsurefire.failIfNoSpecifiedTests=false test`
    Expected: Each corruption has a specific error; no Runtime becomes queryable and admission stays closed.
    Evidence: <attemptDir>/task-10-snapshot-error.log
  ```

  Commit: YES | Message: `feat(core): stream deterministic fenced snapshots` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeSnapshotBuilder.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshotCodec.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/SnapshotAdmissionBarrierTest.java`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 11. Prove three-node replay, failover, outage, and disk-pressure recovery

  What to do: Extend the recovery matrix from leader-stop/cold-restart to full-load leader kill, snapshot corruption, Archive replay from explicit position, Kafka/Projector outage, outbox byte-cap, Archive disk full, and follower lag/rejoin. Keep commands flowing at the configured offered rate where safe. Record accepted/finalized command IDs and export sequences before/after each fault; assert no committed loss, no duplicate final fact, deterministic backpressure, state-hash convergence, and funds conservation. Run one product line at a time; no wallet service.
  Must NOT do: Do not treat container restart alone as failover proof, discard data volumes before verification, declare success from process liveness, continue after hash divergence, or hide untested faults in prose.

  Parallelization: Can parallel: NO | Wave 6 | Blocks: [12] | Blocked by: [6, 8, 9, 10]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `scripts/run-product-line-recovery-matrix.sh:19-26` - current dry-run manifest
  - Pattern:  `scripts/run-product-line-recovery-matrix.sh:60-107` - current leader-stop/cold-restart hash gate
  - Pattern:  `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/OfflineReplayMain.java:72` - explicit offline replay completion path
  - Test:     `surprising-aeron-core/surprising-aeron-tools/src/test/java/com/surprising/aeron/tools/OfflineReplayMainTest.java` - replay/truncation test style
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:846-877` - checksum/version/manifest failure patterns
  - External: `https://aeron.io/docs/aeron-archive/overview/` - replay positions, recording extension, truncation, purge, and replication
  - External: `https://aeron.io/docs/aeron-cluster/cluster-troubleshooting/` - GC, CPU, disk, memory, and network failure evidence

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN integration fixtures make each fault case fail before harness support and pass after: `mvn -pl surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-service -am -Dtest=OfflineReplayMainTest,RecoveryEvidenceContractTest -Dsurefire.failIfNoSpecifiedTests=false test`.
  - [ ] `PRODUCT_LINE=LINEAR_PERPETUAL MATRIX_EXECUTE=true RECOVERY_CASES=leader-kill,snapshot-corruption,archive-replay,kafka-down,outbox-full,archive-disk-full,follower-lag OUTPUT_DIR="$ATTEMPT_DIR/task-11-recovery" scripts/run-product-line-recovery-matrix.sh` exits 0 and emits one PASS manifest per case.
  - [ ] For every case: committed command IDs are a gap-free subset through the recorded commit position, each has one final fact, nodes reconverge on business/matcher/outbox hashes, and user/market-maker/fee/funding/liquidation/insurance funds delta is zero.
  - [ ] `scripts/run-exchange-core-hot-path-stage.sh --stage task-11-recovery --attempt-dir "$ATTEMPT_DIR" --benchmark-suite post-recovery --forks 3 --jfr-settings profile` returns PASS and report/commit/push gates pass.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: full-load leader kill preserves committed commands and facts
    Tool:     bash
    Steps:    `PRODUCT_LINE=LINEAR_PERPETUAL MATRIX_EXECUTE=true RECOVERY_CASES=leader-kill OUTPUT_DIR="$ATTEMPT_DIR/task-11-leader-kill" scripts/run-product-line-recovery-matrix.sh`
    Expected: A new leader forms; offered load resumes; zero committed command loss, zero duplicate final facts, converged hashes/open orders/outbox, and funds difference 0.
    Evidence: <attemptDir>/task-11-recovery.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Archive disk full and outbox full backpressure deterministically
    Tool:     bash
    Steps:    `PRODUCT_LINE=LINEAR_PERPETUAL MATRIX_EXECUTE=true RECOVERY_CASES=archive-disk-full,outbox-full OUTPUT_DIR="$ATTEMPT_DIR/task-11-pressure" scripts/run-product-line-recovery-matrix.sh`
    Expected: No OOM/corruption/divergence; exact bounded backpressure result is recorded; after space/export recovery, replay converges and funds/facts remain exact.
    Evidence: <attemptDir>/task-11-recovery-error.json
  ```

  Commit: YES | Message: `test(core): expand clustered recovery matrix` | Files: [`scripts/run-product-line-recovery-matrix.sh`, `scripts/aeron-core-local.sh`, `scripts/aeron-core-tool.sh`, `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/OfflineReplayMain.java`, `surprising-aeron-core/surprising-aeron-tools/src/test/java/com/surprising/aeron/tools/RecoveryEvidenceContractTest.java`, `docs/exchange-core-trading-hot-path-review.md`]

- [ ] 12. Pass the final 100k/s capacity, burst, soak, skew, product-line, and funds gates

  What to do: Extend the capacity harness to prebuild 1,000,000 users and 4,000,000 active orders without including setup in measured time; select uniform, hot-symbol, and hot-user distributions; run no-fill and 1/10/100-maker fills; mix place/cancel/amend/match plus line-specific mark price/funding/liquidation/ADL/delivery/option exercise/expiry. For each product line, run open-loop 100,000 offered commands/s for 60 minutes with corrected acceptance-to-finalization latency and a 200,000/s 10-second burst. Run a 24-hour mixed lifecycle soak on the highest-live-set product line selected by Task 9. Run the Task 11 leader-kill during full load. Continuously reconcile user and market-maker opening balance, adjustments, trades, fees, funding, liquidation fee, delivery/exercise flows, and ending balance. Update the report from AS_IS baseline to a signed final result without deleting failures or unrun scope.
  Must NOT do: Do not average away a failing minute/product line/workload, count deterministic burst backpressure as finalized throughput, use closed-loop clients, omit coordinated-omission correction, start wallet, or declare 100k from matcher-only results.

  Parallelization: Can parallel: NO | Wave 7 | Blocks: [] | Blocked by: [3, 4, 5, 6, 7, 8, 9, 10, 11]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterCapacityMain.java:99-170` - current offered-rate/finalized output and latency fields
  - Pattern:  `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterCapacityMain.java:390-423` - current book-empty and economic-funds check
  - Pattern:  `scripts/aeron-core-tool.sh:60-79` - current capacity/lifecycle command properties
  - Pattern:  `scripts/run-product-line-capacity.sh:25-48` - current fresh cluster and result manifest
  - Pattern:  `docs/exchange-core-trading-hot-path-review.md:295-313` - authoritative 100k/s acceptance matrix
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CorePerpetualFinancialMatrixTest.java` - perpetual funds formulas
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreDeliveryOptionFinancialMatrixTest.java` - delivery/option funds and snapshot-resume formulas

  Acceptance criteria (agent-executable only):
  - [ ] RED then GREEN: `mvn -pl surprising-aeron-core/surprising-aeron-tools -am -Dtest=ClusterCapacityAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false test` validates manifests reject closed-loop, missing minutes, uncorrected histograms, incomplete product/workload/fill matrices, nonzero funds, growth, Full GC, hash divergence, or missing failover.
  - [ ] For each of `SPOT LINEAR_PERPETUAL INVERSE_PERPETUAL LINEAR_DELIVERY INVERSE_DELIVERY OPTION`, `CAPACITY_OFFERED_COMMANDS_PER_SECOND=100000 CAPACITY_DURATION_SECONDS=3600 CAPACITY_WARMUP_SECONDS=300 CAPACITY_USER_COUNT=1000000 CAPACITY_ACTIVE_ORDERS=4000000 CAPACITY_DISTRIBUTIONS=uniform,hot-symbol,hot-user CAPACITY_FILL_DEPTHS=0,1,10,100 PRODUCT_LINE=<line> OUTPUT_DIR="$ATTEMPT_DIR/task-12-<line>" scripts/run-product-line-capacity.sh` exits 0 and every one-minute window finalizes at least 100,000/s after warmup.
  - [ ] For each line, `CAPACITY_OFFERED_COMMANDS_PER_SECOND=200000 CAPACITY_DURATION_SECONDS=10 CAPACITY_WARMUP_SECONDS=60 CAPACITY_BURST=true PRODUCT_LINE=<line> OUTPUT_DIR="$ATTEMPT_DIR/task-12-burst-<line>" scripts/run-product-line-capacity.sh` exits 0; queue/outbox/in-flight caps hold, accepted commands finalize once, rejected commands use deterministic backpressure, and there is no OOM/hash/funds failure.
  - [ ] `CAPACITY_OFFERED_COMMANDS_PER_SECOND=100000 CAPACITY_DURATION_SECONDS=86400 CAPACITY_WARMUP_SECONDS=300 CAPACITY_USER_COUNT=1000000 CAPACITY_ACTIVE_ORDERS=4000000 RUN_LIFECYCLE=true JFR_SETTINGS=default PRODUCT_LINE=LINEAR_PERPETUAL OUTPUT_DIR="$ATTEMPT_DIR/task-12-soak" scripts/run-product-line-capacity.sh` exits 0 with zero Full GC, pause/latency defaults met, stable heap/direct/pending/outbox windows, no fatal JVM/Aeron event, and funds delta 0. If Task 9 identifies a different worst-live-set product line, substitute it and record why.
  - [ ] Full-load leader kill passes during a 100k/s window; Kafka/Projector outage does not stop trading before bounded outbox backpressure; state hashes/facts/funds reconverge afterward.
  - [ ] `docs/exchange-core-trading-hot-path-review.md` maps every section 4-9 finding to a passed stage and links every capacity/JFR/recovery/funds artifact; no “not yet run” item remains. The final task commit is pushed.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: six product lines sustain 100k finalized commands/s
    Tool:     bash
    Steps:    `for line in SPOT LINEAR_PERPETUAL INVERSE_PERPETUAL LINEAR_DELIVERY INVERSE_DELIVERY OPTION; do CAPACITY_OFFERED_COMMANDS_PER_SECOND=100000 CAPACITY_DURATION_SECONDS=3600 CAPACITY_WARMUP_SECONDS=300 CAPACITY_USER_COUNT=1000000 CAPACITY_ACTIVE_ORDERS=4000000 CAPACITY_DISTRIBUTIONS=uniform,hot-symbol,hot-user CAPACITY_FILL_DEPTHS=0,1,10,100 PRODUCT_LINE="$line" OUTPUT_DIR="$ATTEMPT_DIR/task-12-$line" scripts/run-product-line-capacity.sh || exit 1; done`
    Expected: Every line and one-minute window has >=100,000 Aeron-committed, finally adjudicated commands/s; latency/JVM/hash/funds gates all pass.
    Evidence: <attemptDir>/task-12-capacity.json   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: 200k/s burst applies bounded backpressure without loss or OOM
    Tool:     bash
    Steps:    `for line in SPOT LINEAR_PERPETUAL INVERSE_PERPETUAL LINEAR_DELIVERY INVERSE_DELIVERY OPTION; do CAPACITY_OFFERED_COMMANDS_PER_SECOND=200000 CAPACITY_DURATION_SECONDS=10 CAPACITY_WARMUP_SECONDS=60 CAPACITY_BURST=true PRODUCT_LINE="$line" OUTPUT_DIR="$ATTEMPT_DIR/task-12-burst-$line" scripts/run-product-line-capacity.sh || exit 1; done`
    Expected: Queue/in-flight/outbox caps are never exceeded; accepted commands finalize exactly once; excess receives deterministic backpressure; no OOM, Full GC, divergence, duplicate fact, or funds delta.
    Evidence: <attemptDir>/task-12-capacity-error.json
  ```

  Commit: YES | Message: `perf(core): certify 100k finalized commands per second` | Files: [`surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterCapacityMain.java`, `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterLifecycleCapacityMain.java`, `surprising-aeron-core/surprising-aeron-tools/src/test/java/com/surprising/aeron/tools/ClusterCapacityAcceptanceTest.java`, `scripts/aeron-core-tool.sh`, `scripts/run-product-line-capacity.sh`, `scripts/run-product-line-recovery-matrix.sh`, `docs/exchange-core-trading-hot-path-review.md`]

## Final verification wave (MANDATORY - after all implementation tasks)
> Runs in PARALLEL. ALL must APPROVE. Surface results to the caller and wait for an explicit "okay" before declaring complete.
- [ ] F1. Plan compliance audit - every task done, every acceptance criterion met
- [ ] F2. Code quality review - diagnostics clean, idioms match, no dead code
- [ ] F3. Real manual QA - every QA scenario executed with evidence captured
- [ ] F4. Scope fidelity - nothing extra shipped beyond Must-Have, nothing Must-NOT-Have introduced

## Commit strategy
- One logical change per commit. Conventional Commits (`<type>(<scope>): <subject>` body + footer).
- Atomic: every commit builds and passes tests on its own.
- No "WIP" / "fix typo squash later" commits on the final branch - clean up before merge.
- Reference the plan file path in the final commit footer: `Plan: .omo/plans/exchange-core-hot-path-ultrawork.md`.
- Each numbered stage includes its test, JFR/benchmark report row, and implementation in one revertible commit, followed immediately by `git push`; parallel worktrees integrate in task-number order and rerun stage closure after rebase.

## Success criteria
- All Must-Have shipped; all QA scenarios pass with captured evidence; F1-F4 approved; commit history clean.
- Sections 4-9 mapping is complete: Task 2-4 close section 4/P0 and establish sections 6-7; Tasks 5-9 close section 5/P1; Tasks 10-11 close snapshot/recovery/P2; Task 12 closes P3 and every section 9 acceptance item.
- Every stage has a binary PASS result, comparable benchmark, JFR recording/summary, report row, exact funds/state-hash outcome, rollback boundary, atomic commit, and upstream push.
