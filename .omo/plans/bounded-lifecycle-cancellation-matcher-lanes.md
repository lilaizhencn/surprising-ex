# Bounded Lifecycle Cancellation, Scope Fencing, Matcher Lanes, and Liquidation Batch Plan

## TL;DR
> Summary:      Replace unbounded lifecycle cancellation with snapshot-safe order cursors, fence only overlapping lifecycle scopes, serialize matcher submissions per symbol, and let the liquidation provider submit one deterministic bounded batch command per maintenance cycle. Preserve Aeron Core as the sole authoritative state machine and apply every matcher outcome on the cluster owner thread.
> Deliverables: Additive batch and progress wire contracts; bounded active-order paging; durable liquidation/settlement cursors and fences; per-symbol matcher lanes; partial-failure recovery; one-command liquidation cycles; settlement dual-cursor resume; protocol/runtime/provider tests and documentation.
> Effort:       XL
> Risk:         High - cancellation changes cross protocol, matching, funds, snapshots, recovery, provider retry, and lifecycle concurrency boundaries.

## Scope
### Must have
- Cancel at most 1,024 active orders per authoritative Core command, resume from an exclusive order-id cursor, and never materialize every order in a lifecycle scope.
- Persist liquidation cancellation progress for `(productLine, userId, symbol)` and settlement cancellation/user progress for `(productLine, symbol)` so snapshot restore resumes the exact next chunk.
- Fence settlement against every order-changing operation on its symbol; fence liquidation against every order-changing operation for the same user and symbol; reject overlaps with a deterministic `LIFECYCLE_IN_PROGRESS` result while unrelated scopes continue.
- Include direct place/cancel/replace/amend operations, trigger-order child placement, liquidation, and settlement in fence evaluation.
- Return ordered per-order matcher outcomes, apply the successful prefix to Core state, and rebuild matcher state from authoritative Core state after a rejected/exceptional/expired continuation before retrying.
- Serialize matcher operations within one symbol lane while allowing submissions for different symbols to proceed independently; retain global authoritative completion order in `CoreProbeState`.
- Submit exactly one `EXECUTE_LIQUIDATION_BATCH` command after each successful liquidation-work query. The command must share one 1,024-order cancellation budget across the batch and may advance one bounded risk-scan page.
- Derive the batch command ID from the canonical encoded payload and product line so a result-unknown retry of unchanged work uses the same ID.
- Keep settlement command IDs stable across both order and user cursors and finish order cancellation before user settlement.
- Version and round-trip every changed wire/snapshot shape, reject malformed bounds/cursors, and update all direct tools and test helpers that assume one matching completion.
- Verify balances, reservations, positions, insurance/fee transfers, and order states after every cancellation chunk and final liquidation/settlement transition.
- Start from the current dirty branch without discarding or overwriting concurrent work; inspect the relevant diff before each task and reconcile compatible in-progress protocol/state changes.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- Do not start, modify, or depend on the wallet service.
- Do not mix spot, perpetual, delivery, or option order/account/topic semantics; exercise only the product line affected by each test.
- Do not move authoritative mutation, cursor advancement, lifecycle completion, or export emission off the Aeron Cluster owner thread.
- Do not increase Exchange Core's configured matching-engine count in this work. Based on exploration, the pinned engine already shards symbols internally when configured with multiple engines, but the repository deliberately configures one engine; this plan removes the adapter's software-wide submission gate only.
- Do not let per-symbol lanes weaken the existing global sequence used to commit completed matcher work into the replicated state machine.
- Do not encode a cancellation page as an unbounded order-id list, allocate `N actions × maxOrders` futures, or allow a liquidation batch to multiply the 1,024-order total budget per action.
- Do not silently defer fenced commands. Reject them deterministically so callers can retry from an observable state.
- Do not clear pending matching state before the terminal result, Core mutation, export record, and next cursor are durably represented in the same deterministic completion step.
- Do not remove the existing single-action liquidation or risk-scan commands in this change; retain them for direct tools and coordinated compatibility while the provider switches to the batch path.
- Do not add links to removed `docs/` or `scripts/` paths, commit local runtime output, or broaden into unrelated architecture refactoring.
- Do not claim physical parallel matching throughput from lanes: with `matchingEnginesNum(1)`, the expected gain is removal of adapter-level head-of-line blocking and cleaner scope isolation.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD + JUnit 5/Maven; write each regression first, observe the targeted failure, implement, then rerun the exact class/method and affected-module suite on IBM Semeru JDK 25.
- QA policy: every task has agent-executed scenarios
- Evidence: `<attemptDir>/task-<N>-<slug>.<ext>` — under ulw-loop, `<attemptDir>` is the `currentAttemptDir` from `omo ulw-loop status --json` (`.omo/evidence/ulw/<session>/<goalId>/a<attempt>`); outside ulw-loop use `.omo/evidence/`
- Environment gate: the default shell currently reports JDK 21 while the project requires JDK 25. Every Maven invocation below must use `/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home`; record `java -version` beside test output.
- Financial invariant: for every lifecycle scenario, assert `opening balances + explicit adjustments - fees/funding/settlement transfers = closing balances + remaining reservations`, and assert user plus market-maker positions/orders agree with the authoritative Core state.

## Execution strategy
### Parallel execution waves
> Target 5-8 tasks per wave. <3 per wave (except final) = under-splitting.
> Extract shared dependencies as Wave-1 tasks to maximize parallelism.

Wave 1 (no dependencies):
- Task 1: Define bounded lifecycle and one-shot liquidation batch protocol contracts
- Task 2: Add cursor-based active-order scope paging
- Task 3: Introduce per-symbol matcher lanes and ordered cancellation outcomes

Wave 2 (after Wave 1):
- Task 4: Persist lifecycle cursors and snapshot schema; depends [1, 2]
- Task 5: Enforce same-scope lifecycle fences; depends [1, 2]
- Task 6: Apply bounded cancellation and liquidation/settlement reducer transitions; depends [1, 2]

Wave 3 (after Wave 2):
- Task 7: Orchestrate resumable matching, failure recovery, completion, and export; depends [3, 4, 5, 6]
- Task 8: Switch providers to one liquidation batch and dual-cursor settlement; depends [1, 6]
- Task 9: Document protocol, lifecycle, deployment, and operational boundaries; depends [1, 4, 5, 6]

Wave 4 (after Wave 3):
- Task 10: Update runners and execute cross-module restart, failure, and financial integration gates; depends [7, 8, 9]

Critical path: Task 1 -> Task 4 -> Task 7 -> Task 10

### Dependency matrix
| Task | Depends on | Blocks | Can parallelize with |
|------|------------|--------|----------------------|
| 1    | none       | 4, 5, 6, 8, 9 | 2, 3 |
| 2    | none       | 4, 5, 6 | 1, 3 |
| 3    | none       | 7 | 1, 2 |
| 4    | 1, 2       | 7, 9 | 5, 6 |
| 5    | 1, 2       | 7, 9 | 4, 6 |
| 6    | 1, 2       | 7, 8, 9 | 4, 5 |
| 7    | 3, 4, 5, 6 | 10 | 8, 9 |
| 8    | 1, 6       | 10 | 7, 9 |
| 9    | 1, 4, 5, 6 | 10 | 7, 8 |
| 10   | 7, 8, 9    | none | none |

## Todos
> Implementation + Test = ONE task. Never separate.
> Every task MUST have: References + Acceptance Criteria + QA Scenarios + Commit.

- [ ] 1. Define bounded lifecycle and one-shot liquidation batch protocol contracts

  What to do: Reconcile the current uncommitted cursor fields before editing. Keep `ExecuteLiquidationCommand` and `SettleInstrumentCommand` source-compatible constructors, but make their encoded forms carry an exclusive order cursor and `maxOrders` in `1..1024`. Allocate additive wire type `43` to `EXECUTE_LIQUIDATION_BATCH`; add an immutable batch command containing canonical ordered actions, each action's liquidation identity and current cursor, one shared `maxCancelOrders <= 1024`, fee rate, and an optional exact risk-scan continuation token plus bounded user limit. Extend liquidation work v2 to expose enough deterministic state to reconstruct that payload after restart: action status/cursor and the risk-scan token `(symbol, priceSequence, lastUserId)` rather than a bare pending flag. Add a bounded batch-result payload with offered, completed/applied, pending, obsolete, processed-order, and risk-scan-continued counts. Append `LIFECYCLE_IN_PROGRESS(67)` and `MATCHING_CONTINUATION_FAILED(68)` without renumbering existing codes; use the latter for deterministic rejection, exceptional completion, or expiry after recovery is exhausted, with the detailed reason carried in the command result message. Validate nonnegative cursors, sorted unique actions, positive bounds, count/payload limits, and exact decode consumption. Preserve the old single-action types and add codec tests for old constructors, new round trips, deterministic re-encoding, malformed counts, over-limit budgets, and truncated payloads.
  Must NOT do: Do not reuse an existing wire code, change existing numeric result meanings, put mutable collections in records, encode unbounded action/order lists, or silently coerce invalid bounds.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [4, 5, 6, 8, 9] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageType.java:5` - existing stable numeric wire-code registry; codes through 42 are occupied.
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingCommandCodec.java:1` - command tag dispatch, fixed field ordering, and protocol-exception conventions.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/ExecuteLiquidationCommand.java:3` - current dirty cursor/max-order extension to preserve and validate.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/SettleInstrumentCommand.java:3` - current dirty settlement cursor/max-order extension.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationWorkView.java:5` - current work response whose boolean pending flag is insufficient for a stable batch retry.
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationWorkCodec.java:10` - version-1 bounded action codec and existing 1,000-action limit.
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreSettlementProgressCodec.java:1` - fixed progress payload convention; reconcile its current uncommitted order/user cursor fields.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResultCode.java:5` - append-only result-code registry currently ending at `MATCHING_PENDING(66)`.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java:9` - 16 MiB envelope maximum; batch validation must stay comfortably below it.
  - Test:     `surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/TradingCommandCodecTest.java:31` - command round-trip pattern.
  - Test:     `surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreLiquidationWorkCodecTest.java:8` - work-response codec assertions.
  - External: `https://aeron.io/docs/cluster-quickstart/replicated-state-machines/` - deterministic input and snapshot requirements for an Aeron replicated state machine.

  Acceptance criteria (agent-executable only):
  - [ ] `EXECUTE_LIQUIDATION_BATCH` has wire code 43, existing codes are unchanged, and `CoreResultCode` additions are append-only as asserted in `TradingCommandCodecTest`.
  - [ ] Encoding the same logical batch twice yields byte-identical payloads and decoding/re-encoding preserves those bytes.
  - [ ] Unit tests reject `maxOrders/maxCancelOrders` values of 0 and 1025, duplicate or unsorted actions, negative cursors, excessive action counts, and truncated/trailing payloads with `ProtocolException`.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-protocol -am -Dtest=TradingCommandCodecTest,CoreLiquidationWorkCodecTest,CoreLiquidationBatchCodecTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: Canonical batch protocol round-trip
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-protocol -am -Dtest=CoreLiquidationBatchCodecTest#roundTripsCanonicalBatchAndResult -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-1-protocol.log`.
    Expected: Maven exits 0; the test asserts byte-identical re-encoding and every command/result field.
    Evidence: <attemptDir>/task-1-protocol.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Invalid cancellation budget is rejected
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-protocol -am -Dtest=CoreLiquidationBatchCodecTest#rejectsZeroAndOverLimitCancellationBudgets -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-1-protocol-error.log`.
    Expected: Maven exits 0; both malformed payloads produce the exact asserted `ProtocolException`, with no decoded command.
    Evidence: <attemptDir>/task-1-protocol-error.log
  ```

  Commit: YES | Message: `feat(aeron-protocol): add resumable liquidation batch contracts` | Files: [surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageType.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResultCode.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/ExecuteLiquidationCommand.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/SettleInstrumentCommand.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/ExecuteLiquidationBatchAction.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/ExecuteLiquidationBatchCommand.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationActionView.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationWorkView.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationWorkCodec.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationBatchResultView.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationBatchResultCodec.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationProgressView.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationProgressCodec.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreSettlementProgressView.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreSettlementProgressCodec.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingCommandCodec.java, surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/TradingCommandCodecTest.java, surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreLiquidationWorkCodecTest.java, surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreLiquidationBatchCodecTest.java]

- [ ] 2. Add cursor-based active-order scope paging

  What to do: Add package-visible, immutable page results to `ActiveOrderIndex` for all orders, a symbol, and a user+symbol scope. Define `beforeOrderId=0` as the first page and every returned cursor as the exclusive upper bound for the next descending page. Use `NavigableSet.headSet`/`lower` views and copy only up to the validated page limit; return `nextCursorOrderId=0` only when no lower order remains. Keep index update/rebuild behavior unchanged. Add tests for empty scopes, exact boundaries, deleted orders between pages, newly inserted higher IDs after page one, no duplicates, and limits 1/1,024. The lifecycle snapshot freezes the relevant horizon, so explicitly verify whether newly inserted same-scope orders are prevented by Task 5 rather than folded into a running lifecycle.
  Must NOT do: Do not expose mutable index sets, use list offsets, rescan all orders, treat an order ID as a timestamp, or make cursor semantics inclusive.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [4, 5, 6] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/ActiveOrderIndex.java:19` - current descending all/user/symbol/user+symbol sets returned in full.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/ActiveOrderIndex.java:42` - mutation and deterministic rebuild paths to preserve.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/ActiveOrderIndexTest.java:10` - existing index test fixture.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1668` - current unbounded lifecycle cancellation consumer.

  Acceptance criteria (agent-executable only):
  - [ ] Paging 2,049 orders with limit 1,024 returns pages of 1,024, 1,024, and 1 with no duplicate/missing IDs and terminal cursor 0.
  - [ ] Removing an order after page one does not skip a surviving lower ID, and adding a higher ID after page one does not reappear in the running descending traversal.
  - [ ] Page creation performs at most `limit + 1` iterator advances, asserted through a package-visible counting test seam rather than timing.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=ActiveOrderIndexTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: Three-page traversal is complete and bounded
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=ActiveOrderIndexTest#pagesLargeSymbolScopeWithoutDuplicates -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-2-order-pages.log`.
    Expected: Maven exits 0; page sizes are 1,024/1,024/1, combined IDs equal the source set, and final cursor is 0.
    Evidence: <attemptDir>/task-2-order-pages.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Concurrent index removal does not corrupt cursor traversal
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=ActiveOrderIndexTest#removedOrderBetweenPagesDoesNotSkipLowerOrders -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-2-order-pages-error.log`.
    Expected: Maven exits 0; the removed ID is absent, every surviving lower ID appears exactly once, and no exception occurs.
    Evidence: <attemptDir>/task-2-order-pages-error.log
  ```

  Commit: YES | Message: `feat(aeron-service): page lifecycle order scopes` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/ActiveOrderIndex.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/ActiveOrderIndexTest.java]

- [ ] 3. Introduce per-symbol matcher lanes and ordered cancellation outcomes

  What to do: Add a package-private `SymbolMatchingLanes` coordinator keyed by symbol. Enqueue place/cancel/replace/amend and lifecycle cancellation chunks on that symbol's tail; complete the lane tail normally even when an operation fails so later commands are not poisoned. Define an explicit all-lane barrier for reports, rebuild, and close: atomically snapshot current lane tails, await them, execute the global operation on the existing engine gate, and prevent later global operations from overtaking it. Replace `cancelBatchAsync(...): Boolean` with an ordered outcome carrying one result per requested order plus the successful prefix. Submit cancellations sequentially within the symbol lane (or otherwise prove ordered outcomes) and stop at first rejection/exception. Keep `matchingEnginesNum(1)` and do not change Core's completion FIFO. Add deterministic promise-controlled tests showing same-symbol serialization, different-symbol submission overlap, barrier coverage, lane recovery after failure, and ordered partial cancellation.
  Must NOT do: Do not mutate Core state in lane callbacks, use one global matching tail for symbol operations, run two operations concurrently for the same symbol, increase matching-engine shards, or report a batch-wide Boolean after partial matcher mutation.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [7] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:53` - current global submission and matching tails causing adapter-wide head-of-line blocking.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:107` - current all-futures Boolean cancellation batch and partial-success ambiguity.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:183` - report operations that require an explicit all-lane barrier.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:229` - matcher rebuild boundary.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:298` - intentional `matchingEnginesNum(1)` configuration to retain.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:376` - current global enqueue implementation to replace for symbol work.
  - External: `https://github.com/exchange-core/exchange-core` - upstream deterministic matching engine and symbol-sharding architecture; verify behavior against the pinned `0.5.8-emporia` source JAR before changing configuration.

  Acceptance criteria (agent-executable only):
  - [ ] Controlled futures prove operation B for symbol 10 cannot submit before operation A for symbol 10 completes, while operation C for symbol 11 submits before A completes.
  - [ ] A failed symbol-10 future does not poison its lane; the next symbol-10 command runs and returns its own result.
  - [ ] A global report/rebuild barrier waits for every lane that existed at barrier creation and cannot be overtaken by a later global barrier.
  - [ ] Ordered batch cancellation stops at first non-success and returns exact successes/failure without pretending later orders ran.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=SymbolMatchingLanesTest,DeterministicExchangeCoreAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: Different symbols submit independently while each lane stays serial
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=SymbolMatchingLanesTest#sameSymbolSerializesWhileDifferentSymbolsProceed -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-3-symbol-lanes.log`.
    Expected: Maven exits 0; the controlled submission trace is `symbol10-A, symbol11-C, symbol10-B`.
    Evidence: <attemptDir>/task-3-symbol-lanes.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Partial matcher rejection remains ordered and recoverable
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=DeterministicExchangeCoreAdapterTest#cancelBatchStopsAtFirstFailureAndReturnsSuccessfulPrefix -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-3-symbol-lanes-error.log`.
    Expected: Maven exits 0; orders before the failure are successful, the failing order carries its exact matcher code, and later order futures were never submitted.
    Evidence: <attemptDir>/task-3-symbol-lanes-error.log
  ```

  Commit: YES | Message: `feat(aeron-service): serialize matcher work per symbol` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/SymbolMatchingLanes.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/SymbolMatchingLanesTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapterTest.java]

- [ ] 4. Persist lifecycle cursors and snapshot schema

  What to do: Reconcile the current dirty `CoreLiquidationState`, `CoreTreasuryState`, and snapshot changes. Represent an executing liquidation as durable `ORDERED` state with `nextCancelOrderId`; represent settlement progress with `ordersComplete`, `nextCursorOrderId`, and `nextCursorUserId`. Cursor 0 means first page before work begins and means terminal only when the corresponding `ordersComplete` flag is true; never infer completion from cursor alone. Snapshot these fields, pending matching generation, 30,000 ms cluster-time deadline, and recovery-attempt counter plus any new batch continuation data. Bump `CoreStateSnapshotCodec` from 4 to 5 and `TradingStateSnapshotCodec` from 17 to 18 exactly once, fail closed on unsupported older payloads unless an already-deployed version is proven by repository/release evidence, and rebuild indexes/fences before resuming pending matcher work. Add round-trip, restart-at-every-boundary, malformed-version, and index/fence rebuild tests.
  Must NOT do: Do not lose current dirty fields, overload liquidation `COMPLETED` as cancellation-in-progress, reset cursors during ordinary restore, resume matcher work before indexes/fences exist, or accept a payload whose shape does not match its version.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [7, 9] | Blocked by: [1, 2]

  References (executor has NO interview context - be exhaustive):
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreLiquidationState.java:6` - current dirty liquidation record/status/cursor design to reconcile.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreTreasuryState.java:89` - lifecycle-progress lookup/update/clear boundary.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreTreasuryState.java:294` - settlement lifecycle progress record currently being extended with order/user cursors.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingStateSnapshotCodec.java:215` - lifecycle-progress encoding.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingStateSnapshotCodec.java:508` - lifecycle-progress decoding.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:23` - top-level snapshot version currently 4.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/PendingMatching.java:6` - pending matcher shape that must carry deterministic continuation metadata.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:108` - snapshot/restore fixture and pending-state coverage.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java:146` - settlement lifecycle persistence setup.
  - External: `https://aeron.io/docs/cluster-quickstart/replicated-state-machines/` - snapshot determinism and restore model.
  - External: `https://aeron.io/docs/aeron-cluster/cluster-timers/` - deterministic timer snapshot/replay behavior for continuation deadlines.

  Acceptance criteria (agent-executable only):
  - [ ] Restoring after zero, one, middle, and terminal cancellation pages yields the exact same next order/user cursor and lifecycle status as the pre-snapshot state.
  - [ ] Restore reconstructs active-order indexes and scope fences before any pending continuation is submitted.
  - [ ] Unsupported snapshot versions fail with the exact asserted exception and do not return partially initialized state.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreProbeStateTest,CoreLifecycleStateTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: Mid-page lifecycle snapshot resumes exactly once
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreProbeStateTest#restoresLiquidationAndSettlementOrderCursorsBeforeResuming -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-4-snapshot.log`.
    Expected: Maven exits 0; restored cursor/status/fence equal the snapshot and the next lower order is submitted once.
    Evidence: <attemptDir>/task-4-snapshot.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Old or malformed snapshot fails closed
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreProbeStateTest#rejectsUnsupportedLifecycleSnapshotVersion -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-4-snapshot-error.log`.
    Expected: Maven exits 0; decode throws the exact asserted version error before exposing state or scheduling continuation timers.
    Evidence: <attemptDir>/task-4-snapshot-error.log
  ```

  Commit: YES | Message: `feat(aeron-service): persist lifecycle cancellation progress` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreLiquidationState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreTreasuryState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/PendingMatching.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingStateSnapshotCodec.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java]

- [ ] 5. Enforce same-scope lifecycle fences

  What to do: Create one pure scope-conflict resolver used both at admission and immediately before continuation. A settlement fence for symbol S conflicts with place/cancel/replace/amend/trigger child/liquidation/settlement work on S. A liquidation fence for user U and symbol S conflicts with place/cancel/replace/amend/trigger child work for U+S, duplicate liquidation U+S, and settlement S; it does not block another user on S or U on another symbol. Derive fences from both pending matching and durable `ORDERED` liquidation/settlement progress so restart and gaps between chunks remain fenced. Validate replace/amend against the indexed order owner/symbol, not only command fields. Return `LIFECYCLE_IN_PROGRESS` before reservation/matcher submission and export a normal rejected command result. Recheck the fence at every chunk and final transition. Add a conflict matrix test covering both directions and trigger-generated child orders.
  Must NOT do: Do not use one global lifecycle lock, allow a same-scope command between chunks, reserve funds before fence rejection, defer/reorder a fenced command, or omit trigger-child admission.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [7, 9] | Blocked by: [1, 2]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:666` - current matching-command classification.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:689` - admission currently reserves place-order state before asynchronous completion.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:819` - cancel/replace validation and authoritative order lookup.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:851` - current liquidation scope capture.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:874` - current settlement symbol scope capture.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1793` - trigger-generated child placement path that must share admission fencing.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java:34` - matching admission/result fixtures.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java:202` - liquidation setup and state assertions.

  Acceptance criteria (agent-executable only):
  - [ ] The complete conflict matrix asserts settlement-vs-symbol operations and liquidation-vs-user+symbol operations symmetrically.
  - [ ] Unrelated user/symbol commands enter matching while a lifecycle chunk is pending; same-scope commands return `LIFECYCLE_IN_PROGRESS` without creating reservations or matcher submissions.
  - [ ] Snapshot restore and the gap between lifecycle chunks retain the same conflict decisions.
  - [ ] Trigger child placement uses the same fence and cannot bypass a running lifecycle.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=LifecycleScopeFenceTest,CoreMatchingStateTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: Exact lifecycle scopes are fenced and unrelated scopes proceed
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=LifecycleScopeFenceTest#enforcesCompleteConflictMatrix -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-5-fences.log`.
    Expected: Maven exits 0; every matrix cell has the expected allow/reject result and allowed cells submit matcher work.
    Evidence: <attemptDir>/task-5-fences.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Trigger child cannot bypass an active liquidation fence
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=LifecycleScopeFenceTest#rejectsTriggeredChildForFencedUserAndSymbol -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-5-fences-error.log`.
    Expected: Maven exits 0; result code is `LIFECYCLE_IN_PROGRESS`, no child order/reservation exists, and matcher received no child command.
    Evidence: <attemptDir>/task-5-fences-error.log
  ```

  Commit: YES | Message: `feat(aeron-service): fence overlapping lifecycle work` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/LifecycleScopeFence.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/LifecycleScopeFenceTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java]

- [ ] 6. Apply bounded cancellation and liquidation/settlement reducer transitions

  What to do: Split lifecycle transitions into pure deterministic stages: plan/revalidate, select bounded order page, apply an ordered successful cancellation prefix to Core orders/reservations/indexes, persist/export the next cursor, and only then perform final position/treasury/liquidation or settlement-user work. Settlement must finish its symbol order phase before processing at most the existing bounded user page. Liquidation must revalidate trigger price sequence, mark, position, and lifecycle status before every chunk and final flatten. If market recovery makes a partially processed plan obsolete, mark it canceled/obsolete, preserve already-canceled orders and released reservations, release the fence, and allow a later risk scan to plan anew. Process a batch in canonical action order under one shared 1,024 cancellation budget; stop when exhausted and return exact applied/pending/obsolete counts. Retain per-action fee/insurance transfers and assert conservation for user and market-maker accounts. Convert current full-list cancellation helpers into bounded prefix application; do not call the matcher from the reducer.
  Must NOT do: Do not flatten a position before cancellation is complete, process settlement users while orders remain, roll back matcher-successful cancellations, double-release reservations, reuse a stale execution mark, or count an incomplete action as applied.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [7, 8, 9] | Blocked by: [1, 2]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1325` - current settlement transition, including existing bounded user cursor.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1531` - current liquidation revalidation, order cancellation, position flattening, and fee transfer.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1872` - current unbounded cancellation/release loop to replace with successful-prefix application.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreRiskState.java:51` - risk-scan continuation token fields.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/LiquidationIndex.java:24` - active liquidation iteration.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/LiquidationIndex.java:52` - current active-status predicate; `ORDERED` must remain active until terminal.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java:146` - settlement funds/state/snapshot assertions.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java:202` - liquidation funds/state assertions.

  Acceptance criteria (agent-executable only):
  - [ ] A 2,049-order liquidation requires exactly three bounded matcher/apply chunks and does not flatten the position before chunk three completes.
  - [ ] Settlement processes zero users until `ordersComplete=true`, then resumes the existing user cursor without repeating a user.
  - [ ] A successful prefix followed by matcher rejection releases exactly the successful orders' reservations, advances only past that prefix, and preserves funds.
  - [ ] An obsolete mid-flight liquidation leaves successful cancellations intact, marks the plan terminal obsolete/canceled, does not charge liquidation fees, and permits a new risk plan.
  - [ ] A mixed batch never processes more than 1,024 cancellations total and returns exact completed/pending/obsolete counts.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLifecycleStateTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: Large liquidation is bounded and financially conserved
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLifecycleStateTest#liquidationCancellationChunksBeforeFlattenAndConservesFunds -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-6-reducer.log`.
    Expected: Maven exits 0; three page sizes are 1,024/1,024/1, flattening occurs only after the last page, and the explicit balance/reservation equation equals zero.
    Evidence: <attemptDir>/task-6-reducer.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Mid-flight obsolete liquidation exits safely
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLifecycleStateTest#marketRecoveryAfterCancelPrefixMarksPlanObsoleteWithoutFee -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-6-reducer-error.log`.
    Expected: Maven exits 0; successful cancellations remain applied, no flatten/fee occurs, fence clears, and a subsequent scan can create a new plan.
    Evidence: <attemptDir>/task-6-reducer-error.log
  ```

  Commit: YES | Message: `feat(aeron-service): apply bounded lifecycle cancellation chunks` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreRiskState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/LiquidationIndex.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java]

- [ ] 7. Orchestrate resumable matching, failure recovery, completion, and export

  What to do: Rework `CoreProbeState` lifecycle matching so it stores a compact scope/page continuation rather than every active order ID, submits only the bounded page, and applies ordered outcomes on the owner thread. Keep the current oldest-sequence authoritative completion discipline, but permit adapter submissions on different symbol lanes. Within one multi-action batch command, process action segments sequentially in canonical order under the shared budget—do not submit a later action's matcher work until the prior segment has a known ordered outcome—so partial success has one deterministic prefix even when actions span symbols. In one deterministic completion step: validate generation, apply successful prefix, append the export for exactly those authoritative changes, persist the next cursor/result, then remove pending state. Intermediate chunks must retain a pending client result and schedule the next timer; terminal chunks return the final batch/single result. Use a 30,000 ms cluster-time deadline per matcher attempt, a generation token, and exactly one rebuild/resubmit recovery attempt; ignore stale futures, and after the second rejection/exception/expiry return `MATCHING_CONTINUATION_FAILED` without losing the durable successful prefix. On the first failure, apply any known success prefix, rebuild matcher from current Core orders behind the all-lane barrier, and resume at the durable cursor. Update timer restore/resume and ensure exports remain below envelope limits by emitting one bounded chunk at a time. Add interleaving, result-unknown dedup, snapshot restart, stale completion, partial failure, timeout, and export tests.
  Must NOT do: Do not remove pending matching before terminal state/result/export exists, mutate state in completion-stage threads, let a stale future apply after rebuild, skip successful-prefix export, busy-wait, or let one timed-out future block every later Core sequence indefinitely.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [10] | Blocked by: [3, 4, 5, 6]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:60` - pending/completed matching maps and sequence indexes.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:156` - current pending-order exclusion rebuilt from materialized command order IDs.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:547` - dedup and matching dispatch.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1021` - current matcher submission, including full-scope liquidation/settlement batches.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1144` - global oldest-pending completion order to retain.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1154` - matching completion mutation/result boundary to make terminal-safe.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:75` - client response held until matching completion.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:152` - timer-driven matching poll.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:263` - pending-timer restore scheduling.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:280` - asynchronous completion ordering.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java:390` - helper currently assuming one completion.
  - External: `https://aeron.io/docs/aeron-cluster/cluster-timers/` - deterministic timers must be represented in snapshot/replay state.

  Acceptance criteria (agent-executable only):
  - [ ] Each lifecycle matcher submission contains no more than 1,024 orders and pending state size is independent of total active orders.
  - [ ] Authoritative completion remains sequence-ordered even when a later symbol future completes first; unrelated lanes still submit before the earlier future completes.
  - [ ] Snapshot/restart in every intermediate state resumes from the durable cursor with no duplicate reservation release, fee, position transition, or export.
  - [ ] Partial failure and deterministic expiry cannot leave matcher/Core order sets divergent; rebuild completes before further matching resumes.
  - [ ] The first failed/expired attempt rebuilds and retries once; a second failed/expired attempt returns `MATCHING_CONTINUATION_FAILED` at 30,000 ms of cluster time and allows the next sequence to progress.
  - [ ] Replaying the same stable batch command ID returns the stored terminal result and creates no second mutation/export.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreProbeStateTest,CoreMatchingStateTest,CoreLifecycleStateTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: Snapshot-resumed lifecycle completes once with exact exports
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreProbeStateTest#liquidationCancellationResumesAcrossSnapshotWithoutExceedingPage -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-7-core-orchestration.log`.
    Expected: Maven exits 0; each submission is bounded, every order/export appears once, terminal result is stored once, and final matcher/Core order sets match.
    Evidence: <attemptDir>/task-7-core-orchestration.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Hung or partially failed matcher cannot strand Core
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreProbeStateTest#partialCancelFailureAndExpiryRebuildBeforeLaterMatching -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-7-core-orchestration-error.log`.
    Expected: Maven exits 0; known successes are authoritative, stale completion is ignored, rebuild aligns order sets, the command reaches the asserted terminal/retry state, and a later command completes.
    Evidence: <attemptDir>/task-7-core-orchestration-error.log
  ```

  Commit: YES | Message: `feat(aeron-service): resume lifecycle matching safely` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/PendingMatching.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java]

- [ ] 8. Switch providers to one liquidation batch and dual-cursor settlement

  What to do: In `LiquidationService`, keep one work query per cycle and replace the per-action execute loop plus separate risk-scan continuation with exactly one batch command whenever the work response contains actions or a continuation token. Preserve canonical action order, cap actions by `workBatchSize`, share the protocol's 1,024 cancellation budget, and cap scan users by `riskScanBatchSize`. Derive the command ID from product line plus canonical encoded batch bytes; on timeout/result-unknown, re-query and resend the same ID while the returned work token/cursors are unchanged. Decode the batch result into an expanded `WorkCycle` with offered/completed/pending/obsolete/processedOrders/riskScanContinued. In settlement fanout, query progress, include both order and user cursor in the stable ID, send order pages until `ordersComplete`, then user pages until complete. Add mocked gateway/service tests for empty work, one/many actions, shared budget, stable retry ID, obsolete action, scan-only work, result-unknown, restart cursors, delivery and option settlement, and exact one-batch-call counting.
  Must NOT do: Do not send N execute commands, send a second risk-scan command in the same provider cycle, use random batch command IDs, advance settlement users before order cancellation finishes, or change scheduler frequency/config defaults unnecessarily.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [10] | Blocked by: [1, 6]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationService.java:31` - current query, per-action command loop, and separate scan continuation.
  - API/Type: `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationService.java:102` - current `WorkCycle` counts to extend.
  - Pattern:  `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java:32` - work query.
  - Pattern:  `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java:41` - current random-ID risk-scan command.
  - Pattern:  `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java:49` - stable single-action liquidation ID pattern to generalize over canonical batch bytes.
  - Pattern:  `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/task/LiquidationMaintenanceTask.java:16` - one scheduled cycle boundary.
  - API/Type: `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/config/LiquidationProperties.java:67` - existing work/risk batch bounds.
  - Pattern:  `surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/ExpiringContractSettlementFanoutService.java:41` - settlement progress query and order/user-cursor loop.
  - Pattern:  `surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/ExpiringContractSettlementFanoutService.java:83` - progress decode/fallback behavior.
  - Test:     `surprising-liquidation/src/test/java/com/surprising/liquidation/provider/service/LiquidationServiceTest.java:23` - mocked cycle pattern.
  - Test:     `surprising-account/surprising-account-provider/src/test/java/com/surprising/account/provider/service/ExpiringContractSettlementFanoutServiceTest.java:34` - delivery/option fanout and current user resume tests.

  Acceptance criteria (agent-executable only):
  - [ ] A nonempty or scan-only work response causes exactly one batch command and zero single-action/continue-scan commands; fully empty work causes zero commands.
  - [ ] Two retries of byte-identical work use the same command ID, and any cursor/token change produces a different ID.
  - [ ] A batch with many actions carries one total `maxCancelOrders <= 1024`, never one 1,024 cap per action.
  - [ ] Settlement sends order cursor pages first, changes stable ID on each cursor, then sends user cursor pages; restart begins at the decoded cursor.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-liquidation,surprising-account/surprising-account-provider -am -Dtest=LiquidationServiceTest,LiquidationPropertiesTest,ExpiringContractSettlementFanoutServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: One liquidation maintenance cycle sends one stable batch
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-liquidation -am -Dtest=LiquidationServiceTest#processWorkSubmitsOneStableBatchCommand -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-8-providers.log`.
    Expected: Maven exits 0; the mock records one query and one batch command, no legacy execute/scan calls, exact shared budget, and expected cycle counts.
    Evidence: <attemptDir>/task-8-providers.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Result-unknown retry and settlement restart preserve cursors
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-liquidation,surprising-account/surprising-account-provider -am -Dtest=LiquidationServiceTest#resultUnknownRetryUsesSameBatchCommandId,ExpiringContractSettlementFanoutServiceTest#resumesOrderCursorBeforeUserCursor -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-8-providers-error.log`.
    Expected: Maven exits 0; liquidation retry IDs are equal, settlement IDs track exact order/user cursors, and no user page runs while orders remain.
    Evidence: <attemptDir>/task-8-providers-error.log
  ```

  Commit: YES | Message: `feat(liquidation): submit one bounded core batch per cycle` | Files: [surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationService.java, surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java, surprising-liquidation/src/test/java/com/surprising/liquidation/provider/service/LiquidationServiceTest.java, surprising-liquidation/src/test/java/com/surprising/liquidation/provider/config/LiquidationPropertiesTest.java, surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/ExpiringContractSettlementFanoutService.java, surprising-account/surprising-account-provider/src/test/java/com/surprising/account/provider/service/ExpiringContractSettlementFanoutServiceTest.java]

- [ ] 9. Document protocol, lifecycle, deployment, and operational boundaries

  What to do: Update the root and module READMEs in Chinese to describe wire code 43, coordinated protocol/service/provider deployment, order-cursor semantics, the total 1,024 cancellation budget, settlement order-then-user phases, exact fence matrix, result-unknown stable batch retry, snapshot version boundary, matcher partial-failure rebuild, and per-symbol software lanes with one physical matching engine. Record that existing single-action commands remain available, wallet is not required, and affected-module tests—not all four product lines—are the validation scope. Add source-path references to the key classes and a compatibility table for old/new producer and Core combinations; unsupported mixed versions must fail closed rather than decode incorrectly.
  Must NOT do: Do not create or link removed `docs/`/`scripts/` content, promise multi-engine throughput, omit the dirty-branch/coordinated-rollout requirement, or document commands/tests that do not exist.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [10] | Blocked by: [1, 4, 5, 6]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `README.md:25` - root description of stable per-action lifecycle commands to update.
  - Pattern:  `surprising-aeron-core/README.md:39` - Core protocol/runtime and snapshot rules.
  - Pattern:  `surprising-liquidation/README.md:8` - current per-action provider workflow.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageType.java:5` - authoritative wire registry to cite.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:506` - authoritative admission/dedup boundary to cite.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:298` - one-engine boundary to state accurately.

  Acceptance criteria (agent-executable only):
  - [ ] `rg -n 'EXECUTE_LIQUIDATION_BATCH|1024|LIFECYCLE_IN_PROGRESS|nextCursorOrderId|matchingEnginesNum\(1\)' README.md surprising-aeron-core/README.md surprising-liquidation/README.md` finds every required contract in the appropriate README.
  - [ ] `rg -n 'docs/|scripts/' README.md surprising-aeron-core/README.md surprising-liquidation/README.md` returns no newly added stale path; inspect `git diff --` for only task-owned README hunks.
  - [ ] The compatibility table explicitly marks protocol v2 producer -> old Core and old producer -> batch-only provider as unsupported/fail-closed.
  - [ ] `git diff --check -- README.md surprising-aeron-core/README.md surprising-liquidation/README.md` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: Required operational contracts are documented
    Tool:     bash
    Steps:    Run `rg -n 'EXECUTE_LIQUIDATION_BATCH|1024|LIFECYCLE_IN_PROGRESS|nextCursorOrderId|matchingEnginesNum\(1\)' README.md surprising-aeron-core/README.md surprising-liquidation/README.md | tee <attemptDir>/task-9-docs.log && git diff --check -- README.md surprising-aeron-core/README.md surprising-liquidation/README.md`.
    Expected: Both commands exit 0 and output covers batch, bound, fence, cursor, and one-engine caveat.
    Evidence: <attemptDir>/task-9-docs.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Removed documentation/script paths are not reintroduced
    Tool:     bash
    Steps:    Run `git diff --unified=0 -- README.md surprising-aeron-core/README.md surprising-liquidation/README.md | rg '^\+.*(docs/|scripts/)' > <attemptDir>/task-9-docs-error.log; test ! -s <attemptDir>/task-9-docs-error.log`.
    Expected: The final `test` exits 0; the evidence file is empty because no added line references removed paths.
    Evidence: <attemptDir>/task-9-docs-error.log
  ```

  Commit: YES | Message: `docs(core): document lifecycle cancellation contracts` | Files: [README.md, surprising-aeron-core/README.md, surprising-liquidation/README.md]

- [ ] 10. Update runners and execute cross-module restart, failure, and financial integration gates

  What to do: Update direct runners and test helpers that assume one pending matching completion or construct legacy liquidation/settlement payloads. Drain intermediate lifecycle continuations until terminal while preserving Core sequence order in `CoreInMemoryBenchmark` and `OfflineReplayMain`; exercise the new batch command in cluster product-line and lifecycle-capacity gates. Add an integration fixture with one affected perpetual or delivery product line, market maker, user, >1,024 orders, snapshot/restart between chunks, a same-scope rejected command, an unrelated-symbol accepted command, result-unknown replay, and injected partial matcher failure. Assert exact orders, balances, reservations, positions, fee/insurance transfers, exports, dedup result, and matcher/Core convergence. Run protocol, Core, liquidation, and account-provider suites on JDK 25, then `git diff --check`; do not launch wallet or all four product lines. Record tested and intentionally untested scopes with the impact rationale.
  Must NOT do: Do not weaken production bounds for test convenience, bypass the real codec/snapshot path, add sleeps as synchronization, start wallet, run unrelated product-line services, or conceal an unavailable cluster environment.

  Parallelization: Can parallel: NO | Wave 4 | Blocks: [] | Blocked by: [7, 8, 9]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreInMemoryBenchmark.java:101` - runner currently drains pending matching work.
  - Pattern:  `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/OfflineReplayMain.java:69` - replay tool's current completion-drain loop.
  - Pattern:  `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterProductLineGateMain.java:138` - old per-action liquidation gate.
  - Pattern:  `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterLifecycleCapacityMain.java:106` - lifecycle capacity liquidation flow and current terminal assumptions.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:699` - test helper that completes one matcher future.
  - Test:     `surprising-account/surprising-account-provider/src/test/java/com/surprising/account/provider/service/ExpiringContractSettlementFanoutServiceTest.java:34` - provider-to-Core settlement behavior.
  - API/Type: `pom.xml:1` - Maven reactor/JDK configuration and affected-module execution root.

  Acceptance criteria (agent-executable only):
  - [ ] The cross-module integration test observes maximum cancellation chunk size 1,024, restart continuation without duplicates, exact fence allow/reject behavior, stable result replay, and matcher/Core convergence after injected partial failure.
  - [ ] Financial assertions cover user and market-maker opening/closing balances, released reservations, remaining positions, liquidation/settlement fees, and insurance transfer with zero unexplained delta.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-protocol,surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-liquidation,surprising-account/surprising-account-provider -am test` exits 0.
  - [ ] `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools -am -DskipTests package` exits 0, proving the updated service benchmark and direct runners compile against the final continuation API.
  - [ ] `git diff --check` exits 0; `git status --short` contains no `.idea/`, `.local-logs/`, `data/`, `target/`, or evidence files staged for commit.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: Restarted bounded lifecycle completes with conserved funds
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLifecycleIntegrationTest#boundedBatchSurvivesRestartAndConservesUserAndMakerFunds -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-10-integration.log`.
    Expected: Maven exits 0; chunks are bounded, the restart resumes exactly once, final order/position/fund/export assertions pass, and matcher/Core sets are equal.
    Evidence: <attemptDir>/task-10-integration.log   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: Partial failure, stale completion, and scope overlap fail safely
    Tool:     bash
    Steps:    Run `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLifecycleIntegrationTest#partialMatcherFailureRebuildsAndSameScopeFenceRejects -Dsurefire.failIfNoSpecifiedTests=false test | tee <attemptDir>/task-10-integration-error.log`.
    Expected: Maven exits 0; known cancellation prefix is authoritative, stale completion is ignored, rebuild converges, same-scope result is `LIFECYCLE_IN_PROGRESS`, unrelated-symbol command completes, and no fund delta remains.
    Evidence: <attemptDir>/task-10-integration-error.log
  ```

  Commit: YES | Message: `test(core): verify lifecycle batch integration` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreInMemoryBenchmark.java, surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/OfflineReplayMain.java, surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterProductLineGateMain.java, surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterLifecycleCapacityMain.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreLifecycleIntegrationTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java]

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
- Reference the plan file path in the final commit footer: `Plan: .omo/plans/bounded-lifecycle-cancellation-matcher-lanes.md`.

## Success criteria
- All Must-Have shipped; all QA scenarios pass with captured evidence; F1-F4 approved; commit history clean.
