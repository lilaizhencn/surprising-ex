# W3-W5 Production Closure

## TL;DR
> Summary:      Close canonical W3-W5 from exact baseline `7e78e04ae4dac16d364117392f960a65a4f4db2d`: version the default-route wire; make command identity, bounded batches, admission and unknown-result handling deterministic; complete Core-owned lifecycle/financial invariants; and make PostgreSQL/Kafka/WebSocket projections bounded, restart-safe and observable.
> Deliverables:
> - Wire schema v2 with `coreShardId=default` / `routeVersion=1` and an executable W1/W2 invariant fence
> - Bounded fingerprint/result ledger, restart-stable order identity, ordered non-atomic batch commands, fixed Aeron agents and typed HTTP outcomes
> - Core-selected bounded funding/settlement/liquidation/ADL/insurance work plus perpetual, delivery and option financial matrices
> - Exporter-owned PostgreSQL migration, contiguous projection watermark, projection-only order reads, adaptive export polling and lag metrics
> - Kafka-authoritative at-least-once WebSocket restart behavior, deterministic event IDs, PG audit mirror and slow-client isolation
> - Checked-in one-line `LINEAR_PERPETUAL` runtime, real W3/W4/W5 fault gates, exact evidence/rollback ledger and pushed atomic history
> Effort:       XL
> Risk:         High - funds, wire/snapshot compatibility, idempotency, asynchronous matching, lifecycle ownership and replicated projections intersect.

## Scope
### Must have
- Execute in a task-owned worktree created from exact commit `7e78e04ae4dac16d364117392f960a65a4f4db2d`; never implement in the protected main worktree.
- Preserve the main-worktree `AD` status and index blob `d0e0f6d9441319c6d9ce9cf4229f31c5b9f47dbf` for `surprising-gateway/src/main/java/com/surprising/websocket/provider/WebSocketRuntimeHints.java`; the path does not exist in the baseline commit and must never be materialized, staged, deleted or committed by this plan.
- Preserve one logical three-member Core per `ProductLine`, source epoch encoded in `sourceId`, exchange-core as the sole executable book, paired `ME0/RE0` snapshot-only restore, sticky post-matcher divergence, and cold-path derived-index reconstruction only.
- Version command, response and export framing to explicit route `default/1`; reject every unsupported schema, shard and route before dispatch.
- Compute a canonical SHA-256 fingerprint in Core from immutable command intent. Same key and same fingerprint returns the original result; same key with a changed fingerprint returns `IDEMPOTENCY_CONFLICT` without mutation.
- Retain command fingerprints/results under both count and byte bounds. Outside the retained window, return an explicit unknown/outside-retention result; external callers reconcile by command-result/projection and never mint or automatically submit a new command ID.
- Derive external order/replacement identity deterministically from ProductLine, user and explicit client keys; retain the current source-epoch scheme and do not create a source registry.
- Implement place/cancel/amend batches as one outer `commandId`, one user/ProductLine, input-ordered processing and exactly one ordered per-item outcome. Preserve limits 20/50/20 and non-atomic partial success.
- Run a fixed Aeron owner agent and egress dispatcher with command sessions/mailboxes isolated from one reserved read-only control session/mailbox. Admission is one untimed attempt and returns typed backpressure immediately; no request-level `supplyAsync`.
- Expose native single/batch Gateway HTTP semantics: terminal result, `409 IDEMPOTENCY_CONFLICT`, immediate `429 CLIENT_BACKPRESSURED`, unavailable `503`, admitted `202 RESULT_UNKNOWN`, and a command-result endpoint using the original command ID.
- Add the minimal additive response/event mapping that records the actual `requiredExportSequence` allocated by `CoreExportState`; never use `appliedCommandCount` as a projection sequence.
- Keep lifecycle selection and progress authoritative in Core. Providers may wake/query bounded control work but PostgreSQL cannot select funding, settlement, liquidation, ADL or insurance mutations.
- Enforce ProductLine guards at every provider boundary and finite per-run limits/cursors for all funding, settlement, liquidation, ADL and insurance continuation loops.
- Prove LINEAR/INVERSE perpetual CROSS/ISOLATED funding, mark, liquidation, ADL and insurance conservation; prove LINEAR/INVERSE delivery settlement; prove CALL/PUT ITM/ATM/OTM option premium, exercise/expiry and counterparty conservation.
- Add only exporter-owned additive PostgreSQL migration(s). Facts, audit rows and one contiguous ProductLine watermark advance in one transaction; gaps roll back and duplicate replay is idempotent.
- Route ordinary external get/by-client/open/history order reads only to PostgreSQL projection with limit, stable cursor, response-byte cap and bounded `minExportSequence` waiting. Command-result, preflight and lifecycle/admin controls alone use reserved Core query capacity.
- Make export batches carry sufficient status to avoid redundant Core status queries; use adaptive bounded waiting plus a low-frequency reconnect safety poll and expose event age, backlog, projection lag, retry/failure and WebSocket rejection metrics.
- Keep WebSocket delivery at-least-once, Kafka committed offsets authoritative on restart, deterministic event IDs derived from ProductLine/export sequence/sub-index, a PG audit mirror, and per-client bounded queues.
- Correct exactly the seven matching-provider test fixtures identified in `.omo/evidence/w5-collect-gate-review.md`; set explicit ProductLine only in tests and add no production fallback.
- Check in one run-owned `LINEAR_PERPETUAL` full-stack orchestration: isolated Kafka/PG/topics/processes, three Core members, required providers/exporter/projector/Gateway, maker started last, wallet absent, and ownership-safe cleanup.
- Execute real W3, W4 and W5 QA with JDK 25, one ProductLine at a time, six financial manifests sequentially, user/maker funds reconciliation, adversarial fault classes, cleanup receipts, atomic commits and required pushes.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- No Aeron authentication or service-principal feature; it is outside canonical W3-W5.
- No source registry v2, lease service or change to epoch-in-`sourceId`.
- No wallet startup, hot-symbol split, non-default shard, route migration, cross-line/shared state, cross-Core funds transaction, P6 network/disk certification, 24-hour soak or capacity declaration.
- No matcher replay, rebuild, resubmit, clean-start reconstruction, second FIFO/book, `CoreBookState`, or production per-order restore. Calls named `rebuild` are permitted only for derived indexes during cold restore/test and must never submit matcher commands.
- No JDBC/Kafka/Redis/HTTP dependency in synchronous adjudication and no PostgreSQL-selected lifecycle work.
- No unbounded queue, map, retry, scan, wait, cursor loop or response; no timed mailbox put, hidden Aeron offer retry, batch retry after unknown, or per-command `CompletableFuture.supplyAsync`.
- No atomic-batch claim, rollback of earlier valid items after a later business rejection, or exchange-core barrier interpreted as per-item success.
- No ordinary order-read fallback to Core and no use of unrelated counters as `requiredExportSequence`.
- No exactly-once WebSocket claim; no process-local restart watermark treated as durable truth.
- No production null ProductLine fallback to repair matching fixtures.
- No modification of `surprising-gateway/src/main/java/com/surprising/websocket/provider/WebSocketRuntimeHints.java`, unrelated `.omo` artifacts, main-worktree state, or Git history outside task-owned commits/pushes.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD with JUnit 5/AssertJ/Mockito, Maven Surefire/Failsafe, Testcontainers or the checked-in run-owned Kafka/PostgreSQL stack, plus shell/curl/real WebSocket clients.
- Failing-first policy: every behavior task first adds the named assertion and records the exact red command/output before production edits; test-only matrix/orchestration tasks record the missing-row/safety assertion red before adding the fixture or runner.
- QA policy: every task has a deterministic happy path and an adversarial/failure path; sleeps are forbidden where a latch, fake clock, bounded deadline or observable status can decide pass/fail.
- JDK 25 prefix: `task_java_home=$(/usr/libexec/java_home -v 25) && export JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" && java -version` must precede every Maven/runtime command and its output must show Java 25.
- Baseline/worktree prefix: `test "$(git rev-parse HEAD)" = 7e78e04ae4dac16d364117392f960a65a4f4db2d` before Task 1; later tasks record the current task commit and verify ancestry with `git merge-base --is-ancestor 7e78e04ae4dac16d364117392f960a65a4f4db2d HEAD`.
- Evidence: `<attemptDir>/task-<N>-<slug>.<ext>` - under ulw-loop, `<attemptDir>` is `currentAttemptDir` from `omo ulw-loop status --json` (`.omo/evidence/ulw/<session>/<goalId>/a<attempt>`); outside ulw-loop use `.omo/evidence/`.
- Real-runtime lock: Tasks 15-17 acquire `<attemptDir>/w3-w5-runtime.lock` and run in numeric order so only one ProductLine stack owns ports, containers, topics and processes at a time.

## Execution strategy
### Parallel execution waves
> Five implementation waves. Shared contracts and isolated infrastructure land first; same-wave tasks have disjoint write scopes. Wave 4 has one explicit serial edge, Task 5 -> Task 15, while Tasks 16-17 may prepare in parallel and all live runs serialize on the runtime lock.

Wave 1 (independent foundations; 6 tasks):
- Task 1: route/shard wire v2 and W1/W2 invariant fence
- Task 4: fixed Aeron agents, typed admission and reserved control capacity
- Task 6: Core-authoritative bounded lifecycle work and provider guards
- Task 9: exporter-owned PostgreSQL migration and contiguous watermark
- Task 13: exactly seven matching fixture corrections
- Task 14: checked-in run-owned `LINEAR_PERPETUAL` orchestration

Wave 2 (after Wave 1; 4 tasks):
- Task 2: fingerprint/result ledger, required export sequence and stable identity; depends [1]
- Task 7: perpetual financial matrix; depends [6]
- Task 10: projection waiter/cursor/byte bounds and order-read repository; depends [4, 9]
- Task 11: export batch status, adaptive polling and metrics; depends [9]

Wave 3 (after Wave 2; 3 tasks):
- Task 3: ordered non-atomic batch protocol and Core execution; depends [1, 2]
- Task 8: delivery and option financial matrix; depends [6, 7]
- Task 12: deterministic WebSocket restart/audit/isolation; depends [1, 9, 11]

Wave 4 (integration and real QA; 4 tasks):
- Task 5: Gateway native batch and HTTP result semantics; depends [2, 3, 4, 10]
- Task 15: real W3 QA; depends [5, 14] and runs after Task 5 inside this wave
- Task 16: real W4 provider/funds QA; depends [6, 7, 8, 14]
- Task 17: real W5 fault QA; depends [9, 10, 11, 12, 14]

Wave 5 (final implementation closure; 1 task):
- Task 18: documentation, status, evidence, rollback and push closure; depends [15, 16, 17]

Critical paths: Task 1 -> Task 2 -> Task 3 -> Task 5 -> Task 15 -> Task 18; Task 9 -> Task 11 -> Task 12 -> Task 17 -> Task 18.

### Dependency matrix
| Task | Depends on | Blocks | Can parallelize with |
|------|------------|--------|----------------------|
| 1 | none | 2, 3, 12 | 4, 6, 9, 13, 14 |
| 2 | 1 | 3, 5 | 7, 10, 11 |
| 3 | 1, 2 | 5 | 8, 12 |
| 4 | none | 5, 10 | 1, 6, 9, 13, 14 |
| 5 | 2, 3, 4, 10 | 15 | 16, 17 |
| 6 | none | 7, 8, 16 | 1, 4, 9, 13, 14 |
| 7 | 6 | 8, 16 | 2, 10, 11 |
| 8 | 6, 7 | 16 | 3, 12 |
| 9 | none | 10, 11, 12, 17 | 1, 4, 6, 13, 14 |
| 10 | 4, 9 | 5, 17 | 2, 7, 11 |
| 11 | 9 | 12, 17 | 2, 7, 10 |
| 12 | 1, 9, 11 | 17 | 3, 8 |
| 13 | none | none | 1, 4, 6, 9, 14 |
| 14 | none | 15, 16, 17 | 1, 4, 6, 9, 13 |
| 15 | 5, 14 | 18 | 16, 17 preparation; live run serialized |
| 16 | 6, 7, 8, 14 | 18 | 15, 17 preparation; live run serialized |
| 17 | 9, 10, 11, 12, 14 | 18 | 15, 16 preparation; live run serialized |
| 18 | 15, 16, 17 | F1-F5 | none |

## Todos
> Implementation + Test = ONE task. Every task includes failing-first proof, exact JDK 25 commands, real/behavioral QA, adversarial cases, cleanup and an atomic commit boundary.

- [x] 1. Version route/shard wire v2 and install the W1/W2 invariant fence

  What to do: In a task-owned worktree, first capture baseline SHA plus the protected main-worktree porcelain-v2/index-blob receipt. Add failing protocol tests, keep the fixed 76-byte header, encode shard code `0` (`default`) in reserved offset 11 and unsigned route version `1` in offsets 14-15, set schema version 2, and carry route through command/query/response/export factories. Reject schema v1, unknown shard/route, malformed length, truncation, trailing bytes and oversized payload before dispatch. Add a source/behavior fence proving exchange-core remains the only executable book, restore still uses paired matcher bytes and `InitialStateConfiguration.fromSnapshotOnly`, matcher divergence remains sticky, and only derived indexes rebuild during cold restore.
  Must NOT do: No non-default route, dual decoder, source registry, matcher replay/rebuild/resubmit, `CoreBookState`, or protected-path write.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [2, 3, 12] | Blocked by: []

  References:
  - Pattern: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreProtocol.java:9-10` - baseline schema 1 and fixed header length.
  - Pattern: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java:19-80` - header encode/decode and reserved bytes.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageHeader.java:7-76` - factories and response/export derivation.
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:100-111,380` - restore reconciliation and `fromSnapshotOnly`.
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java:206-224` - derived-index-only reconstruction.
  - Test: existing `surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreMessageCodecTest.java`; add new invariant test `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreNativeSnapshotProductLineTest.java`.

  Acceptance criteria:
  - [ ] Red-before-green transcript exists for `CoreMessageCodecTest#rejectsImplicitOrUnknownRoute`; final schema/default/1 round-trip and all malformed cases pass without changing header length.
  - [ ] `mvn -pl :surprising-aeron-protocol,:surprising-aeron-service -am -Dtest=CoreMessageCodecTest,CoreExportCodecTest,W1W2InvariantFenceTest,CoreNativeSnapshotProductLineTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 under the JDK 25 prefix.
  - [ ] `git grep -n -E 'CoreBookState|fromOrders|replay.*order|rebuild.*matcher|resubmit.*matcher' -- '*src/main/java*'` returns no forbidden production mechanism; derived-index `rebuild` matches are explicitly classified.
  - [ ] Main-worktree protected status/index blob exactly matches the preflight receipt after the task.

  QA scenarios:
  ```
  Scenario: explicit default route round-trips without weakening restore
    Tool:     bash
    Steps:    Run the JDK 25 prefix, then `mvn -pl :surprising-aeron-protocol,:surprising-aeron-service -am -Dtest=CoreMessageCodecTest#roundTripsExplicitDefaultRoute,W1W2InvariantFenceTest#keepsSingleBookSnapshotOnlyRestore -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: schema 2/default/1 is decoded; paired native matcher snapshot restores; no replay/rebuild/resubmit path executes.
    Evidence: <attemptDir>/task-1-route-invariant.txt

  Scenario: unsupported route and protected-state drift fail closed
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-aeron-protocol -am -Dtest=CoreMessageCodecTest#rejectsImplicitOrUnknownRoute -Dsurefire.failIfNoSpecifiedTests=false test`, then compare `git status --porcelain=v2` and `git ls-files -s -- surprising-gateway/src/main/java/com/surprising/websocket/provider/WebSocketRuntimeHints.java` with preflight.
    Expected: all invalid frames throw `ProtocolException`; protected status/blob are byte-identical.
    Evidence: <attemptDir>/task-1-route-invariant-error.txt
  ```

  Commit: YES | Message: `feat(aeron-protocol): version default route wire` | Files: [surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreProtocol.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreRoute.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageHeader.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java, surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreMessageCodecTest.java, surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreExportCodecTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/W1W2InvariantFenceTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreNativeSnapshotProductLineTest.java (new)]

- [x] 2. Add canonical fingerprints, bounded result retention, required export sequence and stable external identity

  What to do: Add protocol-owned SHA-256 canonicalization over schema, message type, ProductLine, default route, source kind, user ID and exact canonical payload bytes; exclude sourceId/sequence, time, correlation and transport position. Compute it in Core. Replace the count-only result map with a count+byte-bounded insertion-ordered ledger storing fingerprint, original terminal/pending response bytes, `requiredExportSequence` returned by `CoreExportState.append`, and retention metadata in a new fail-closed snapshot version. Same key/same fingerprint returns the stored result; changed fingerprint returns `IDEMPOTENCY_CONFLICT` before source watermark/state/matcher/outbox changes. Missing query results return `RESULT_UNKNOWN_OUTSIDE_RETENTION`. Add deterministic external order/command/replacement IDs from length-prefixed `(identityVersion,ProductLine,userId,client key)` tuples; require nonblank `clientOrderId`, `clientRequestId` and batch key. Expose actual `requiredExportSequence` in `CoreResponse`/command-result; never map `appliedCommandCount` to it.
  Must NOT do: No unbounded tombstone store, volatile fingerprint fields, wall-clock/random order IDs, new source registry, retry with a new ID, or old snapshot dual-read.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [3, 5] | Blocked by: [1]

  References:
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:533-550,651-664,2694-2712` - payload-blind duplicate path, count-only eviction and stored result.
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java:64-95` - actual export sequence allocation; change `append` to return it.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResponse.java:3-45` - baseline exposes applied count but no export sequence.
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:37-88,104-193` - strict snapshot encoding/manifest.
  - Test: add new snapshot-ledger coverage at `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreStateSnapshotCodecTest.java`.
  - Pattern: `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java:42-99,212-245` - process-local IDs and mutable-intent command IDs.
  - API/Type: `surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/AmendOrderRequest.java` and batch request records - client-key additions.

  Acceptance criteria:
  - [ ] Red tests prove baseline reuses one command ID across changed payload without conflict and allocates different IDs after service reconstruction; green tests prove same fingerprint returns original and changed fingerprint returns conflict with identical funds/book/outbox/source state.
  - [ ] The ledger evicts under both 128-entry and 32 MiB bounds, preserves fingerprints and actual required export sequences across snapshot, rejects old/corrupt snapshots, and returns `RESULT_UNKNOWN_OUTSIDE_RETENTION` for missing entries.
  - [ ] `requiredExportSequence` equals the sequence returned by `CoreExportState.append`; a test deliberately makes `appliedCommandCount != exportSequence` and proves no substitution.
  - [ ] `mvn -pl :surprising-aeron-service,:surprising-order-provider -am -Dtest=CoreCommandFingerprintTest,CoreResultLedgerTest,CoreStateSnapshotCodecTest,StableOrderIdentityTest,AeronOrderCommandServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios:
  ```
  Scenario: retry survives source epoch and snapshot restore
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-aeron-service,:surprising-order-provider -am -Dtest=CoreCommandFingerprintTest#returnsOriginalAcrossEpochAndRestore,StableOrderIdentityTest#survivesProviderReconstruction -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: one mutation/export, stable command/order IDs, original response and exact required export sequence return after restore.
    Evidence: <attemptDir>/task-2-idempotency-identity.txt

  Scenario: conflict and outside-retention retry cannot mutate
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-aeron-service -am -Dtest=CoreResultLedgerTest#conflictAndExpiredResultFailWithoutMutation -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: changed fingerprint is `IDEMPOTENCY_CONFLICT`; evicted result query is `RESULT_UNKNOWN_OUTSIDE_RETENTION`; state/source/export hashes remain equal.
    Evidence: <attemptDir>/task-2-idempotency-identity-error.txt
  ```

  Commit: YES | Message: `feat(core): persist bounded command identity results` | Files: [surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CommandFingerprint.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResponse.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreProtocol.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResultCode.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreCommandFingerprintTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreResultLedgerTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreStateSnapshotCodecTest.java (new), surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java, surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/PlaceOrderRequest.java, surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/ClosePositionRequest.java, surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/AmendOrderRequest.java, surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/BatchPlaceOrderRequest.java, surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/BatchCancelOrdersRequest.java, surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/BatchAmendOrdersRequest.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/StableOrderIdentity.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/StableOrderIdentityTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/AeronOrderCommandServiceTest.java]

- [x] 3. Implement bounded ordered non-atomic place/cancel/amend batch protocol and Core execution

  What to do: Add unique command types and strict binary codecs for place/cancel/amend batches, one outer ID/user/ProductLine, versioned length-prefixed canonical item payloads and ordered result frames. Enforce 20/50/20, aggregate payload/response byte limits, exact indexes and no mixed user. In Core, execute items in input order through existing asynchronous matcher continuation APIs, committing each predictable applied/rejected item before the next. Return one item outcome per input, preserve prior applied items after later rejection, and store/replay the whole aggregate under Task 2's outer fingerprint/result ledger. Pre-matcher validation failure is per-item; any post-matcher invariant failure remains sticky fatal divergence.
  Must NOT do: No all-or-nothing rollback, N outer command IDs, completion-barrier success inference, synchronous `join`, whole/partial retry after unknown, or second book.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [5] | Blocked by: [1, 2]

  References:
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageType.java:5-36` - wire-code registry.
  - Pattern: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingCommandCodec.java` - canonical single-item bytes to compose.
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:120-190` - ordered matcher continuation/timer boundary.
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:170-238` - async place/cancel batch APIs.
  - Pattern: `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:101-113,150-162,223-235` - public limits and current N-round-trip loops.
  - External: `https://github.com/exchange-core/exchange-core/blob/master/src/main/java/exchange/core2/core/ExchangeApi.java` - caller IDs and batch barrier are not per-item adjudication.

  Acceptance criteria:
  - [ ] Failing-first tests prove no batch message types/aggregate behavior on baseline; final codecs reject empty, 21/51/21, mixed user, duplicate/missing index, negative/oversized length, truncation, trailing bytes and response overflow.
  - [ ] Core tests prove ordered `[APPLIED, REJECTED, APPLIED]`, exactly one outcome per item, prior-state retention, one outer export event/result, same-batch retry replay and changed-batch conflict.
  - [ ] `mvn -pl :surprising-aeron-protocol,:surprising-aeron-service -am -Dtest=TradingOrderBatchCodecTest,CoreOrderedOrderBatchTest,SurprisingClusteredServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.
  - [ ] Source scan finds no new `join`, `submitCommandsSync`, matcher replay/rebuild/resubmit or per-item outer command submission.

  QA scenarios:
  ```
  Scenario: maximum legal batches produce ordered outcomes
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-aeron-service -am -Dtest=CoreOrderedOrderBatchTest#processesMaximumBatchesInInputOrder -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: 20/50/20 inputs each produce exact indexed results and one retained outer command result/export sequence.
    Evidence: <attemptDir>/task-3-core-batch.txt

  Scenario: mid-batch rejection and post-matcher divergence differ
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-aeron-service -am -Dtest=CoreOrderedOrderBatchTest#keepsPriorItemsButFailsStickyAfterMatcherDivergence -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: business rejection preserves earlier applied item and continues; injected post-matcher invariant failure closes the member and processes no later item.
    Evidence: <attemptDir>/task-3-core-batch-error.txt
  ```

  Commit: YES | Message: `feat(core): execute ordered bounded order batches` | Files: [surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageType.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/PlaceOrderBatchCommand.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CancelOrderBatchCommand.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/AmendOrderBatchCommand.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreOrderBatchResult.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingOrderBatchCodec.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java, surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/TradingOrderBatchCodecTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreOrderedOrderBatchTest.java]

- [x] 4. Replace request executors/retries with fixed Aeron agents and reserved control capacity

  What to do: Replace `AeronClientPool`'s command executor, slot spin/park, synchronous offer retry and per-call egress polling with fixed owner agents, bounded command/query mailboxes and one egress dispatcher. Use exact defaults: four command sessions, one reserved query session, command mailbox 256, query mailbox 64, 64 in-flight correlations per command session, 32 on the reserved session, egress fragment limit 32. Validate positive fixed capacities at startup. Admission is `offer` once: positive means admitted, timeout/session loss after admission is `ResultUnknown`; negative codes map to typed `NotAccepted` values `CLIENT_BACKPRESSURED`, `NOT_CONNECTED`, `ADMIN_ACTION`, `CLOSED`, `MAX_POSITION_EXCEEDED`, or `UNKNOWN(raw)`. Whitelist command-result, preflight and lifecycle/admin controls on reserved capacity; ordinary reads are rejected locally.
  Must NOT do: No `supplyAsync`, task per request, hidden reconnect/resubmit, timed mailbox put, dynamic capacity growth or command use of reserved query capacity.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [5, 10] | Blocked by: []

  References:
  - Pattern: `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:32-55,132-175,177-239` - retries, executor, slot and mixed query capacity.
  - Pattern: `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java:148-187,190-210` - blocking offer/egress and response map.
  - API/Type: `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/ResultUnknownException.java:5-16` - current untyped unknown edge.
  - API/Type: `surprising-parent/pom.xml:40` - Aeron 1.52.2 pin.
  - External: `https://github.com/real-logic/aeron/blob/1.52.2/aeron-client/src/main/java/io/aeron/Publication.java` - exact negative offer codes.

  Acceptance criteria:
  - [ ] Red tests prove baseline creates executor tasks/retries and lets ordinary reads use pooled slots; green tests assert fixed thread/session counts and immediate independent mailbox saturation.
  - [ ] Positive offer plus response timeout returns `ResultUnknown(originalCommandId)`; negative offer returns the exact typed admission result with zero retry.
  - [ ] `rg -n 'MAX_SUBMIT_ATTEMPTS|supplyAsync|ThreadPoolExecutor|LockSupport|while \(true\).*offer' surprising-aeron-core/surprising-aeron-client/src/main/java` finds no request-level executor/spin/retry path.
  - [ ] `mvn -pl :surprising-aeron-client -am -Dtest=AeronClientAgentTest,AeronClientCapacityTest,CoreCommandOutcomeTest,CoreQueryClassTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios:
  ```
  Scenario: command and control capacity remain independent
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-aeron-client -am -Dtest=AeronClientCapacityTest#isolatesCommandAndReservedControlMailboxes -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: 257th command and 65th control query fail immediately with typed backpressure; saturation of either does not consume the other capacity.
    Evidence: <attemptDir>/task-4-aeron-agents.txt

  Scenario: admitted timeout is not rejection or retry
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-aeron-client -am -Dtest=AeronClientAgentTest#returnsUnknownAfterPositiveOfferTimeoutWithoutRetry -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: one offer, one original command ID, zero resubmissions, `ResultUnknown` outcome.
    Evidence: <attemptDir>/task-4-aeron-agents-error.txt
  ```

  Commit: YES | Message: `refactor(aeron-client): use fixed bounded agents` | Files: [surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientCapacity.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/CoreCommandOutcome.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/CoreQueryClass.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java, surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/AeronClientAgentTest.java, surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/AeronClientCapacityTest.java, surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/CoreCommandOutcomeTest.java, surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/CoreQueryClassTest.java]

- [x] 5. Make order/Gateway single and batch commands native with typed HTTP result semantics

  What to do: Migrate every order mutation to Task 4 outcomes and Task 2 stable IDs. Replace public place/amend/cancel batch loops with one Task 3 command and ordered aggregate decode. Return 200/terminal business response, 409 `IDEMPOTENCY_CONFLICT`, 429 immediate `CLIENT_BACKPRESSURED`, 503 not connected/admin/closed/max-position/unknown raw admission, and 202 `RESULT_UNKNOWN` only after positive admission; include original command ID, prospective order/replacement IDs, command-result URL and `requiredExportSequence` when known. Add `GET /api/v1/trading/orders/commands/{commandId}` on reserved control capacity: 200 known terminal, 202 pending/unknown, 410 outside retention. Same key/same fingerprint returns original; changed payload conflicts. Remove success fallback reads and retries.
  Must NOT do: No N command calls, random/replacement ID after admission, HTTP-worker blocking/spin, unknown-to-rejection conversion, per-item retry/query or ordinary Core read.

  Parallelization: Can parallel: YES (Task 16/17 preparation only) | Wave 4 | Blocks: [15] | Blocked by: [2, 3, 4, 10]

  References:
  - Pattern: `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java:24-61` - synchronous commands and ordinary Core reads.
  - Pattern: `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java:57-109,141-149` - command responses and cancel fallback query.
  - Pattern: `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:101-113,150-162,223-235` - N-round-trip batches.
  - Pattern: `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java:60-141` - HTTP single/batch surfaces.
  - Test: `surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/AeronOrderCommandServiceTest.java` and `.../controller/OrderControllerTest.java`.

  Acceptance criteria:
  - [ ] Failing-first controller/service tests distinguish all status/code bodies and prove maximum 20/50/20 batches invoke the Gateway exactly once each.
  - [ ] Same key/same payload returns byte-equivalent original receipt/result; changed payload returns 409; admitted timeout returns 202; mailbox rejection returns 429 and no command-result claim.
  - [ ] Command-result returns actual `requiredExportSequence` only when stored; no assertion equates it with `appliedCommandCount`.
  - [ ] `mvn -pl :surprising-order-provider -am -Dtest=OrderControllerTest,OrderAeronGatewayTest,AeronOrderCommandServiceTest,OrderBatchServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios:
  ```
  Scenario: native batch and same-key retry return original aggregate
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-order-provider -am -Dtest=OrderBatchServiceTest#usesOneRoundTripAndReplaysOriginalAggregate -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: one Gateway offer per batch; indexes/IDs/results preserve input order; exact retry performs no second mutation.
    Evidence: <attemptDir>/task-5-gateway-batch-http.txt

  Scenario: conflict, immediate backpressure and admitted unknown remain distinct
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-order-provider -am -Dtest=OrderControllerTest#mapsConflictBackpressureAndUnknownSeparately -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: statuses 409/429/202 and machine codes match; no hidden retry or ordinary Core query occurs.
    Evidence: <attemptDir>/task-5-gateway-batch-http-error.txt
  ```

  Commit: YES | Message: `feat(order): expose native batch result receipts` | Files: [surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/OrderCommandReceipt.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/controller/OrderControllerTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/OrderAeronGatewayTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/AeronOrderCommandServiceTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/OrderBatchServiceTest.java]

- [x] 6. Make lifecycle work selection Core-authoritative and every provider continuation bounded/ProductLine-guarded

  What to do: Extend existing reserved Core work/progress views so Core selects bounded funding, settlement, liquidation, insurance-deficit and ADL work from its own indexes/state with deterministic cursors and max counts/bytes. PostgreSQL remains audit/read-only. Replace `FundingService` and `LiquidationAeronGateway` infinite loops with configured `maxPagesPerRun` plus persisted Core cursor; make ADL/insurance consume Core-selected liquidation work instead of PG-selected rows. Validate configured ProductLine, command payload ProductLine, instrument contract family/account type and returned work ProductLine at every provider boundary. Preserve Core atomic funds mutation and snapshot progress.
  Must NOT do: No provider risk recomputation, PG-selected mutation, unbounded drain-to-completion, local business retry queue or cross-line work.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [7, 8, 16] | Blocked by: []

  References:
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:900-998,1137-1169,1436-1706` - bounded liquidation/settlement cursors and Core selection seams.
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreTreasuryState.java:12-27` - authoritative insurance and lifecycle progress state.
  - Pattern: `surprising-funding/surprising-funding-provider/src/main/java/com/surprising/funding/provider/service/FundingService.java:91-121` - baseline unbounded funding continuation.
  - Pattern: `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java:42-90` - Core work query followed by unbounded loop.
  - Pattern: `surprising-adl/src/main/java/com/surprising/adl/provider/service/AdlService.java:48-73` and `surprising-insurance/src/main/java/com/surprising/insurance/provider/service/InsuranceService.java:64-83` - projection-selected resolution work.
  - Test: `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java` and provider service tests.

  Acceptance criteria:
  - [ ] Red tests prove baseline funding/liquidation can exceed one run bound and ADL/insurance selection originates in PG; green tests cap commands/pages/bytes and select every mutation target from Core.
  - [ ] Wrong ProductLine/account/contract/cursor is rejected before mutation; cursor non-advance, repeat and gap are exact failures; progress survives snapshot and resumes exactly once.
  - [ ] `rg -n 'for \(;;\)|while \(true\)' surprising-funding surprising-liquidation surprising-adl surprising-insurance --glob '*.java'` finds no lifecycle drain loop; PG repository calls remain only query/audit paths.
  - [ ] JDK 25 command `mvn -pl :surprising-aeron-service,:surprising-funding-provider,:surprising-liquidation,:surprising-adl,:surprising-insurance -am -Dtest=CoreLifecycleWorkTest,FundingServiceTest,LiquidationServiceTest,AdlServiceTest,InsuranceServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0.

  QA scenarios:
  ```
  Scenario: bounded Core-selected lifecycle work resumes exactly once
    Tool:     bash
    Steps:    Run the JDK 25 prefix and the Acceptance Maven command, selecting `CoreLifecycleWorkTest#resumesAllWorkKindsFromSnapshotWithinBounds` where supported.
    Expected: every invocation processes at most configured pages/items/bytes; snapshot restart resumes from the exact Core cursor without duplicate funds events.
    Evidence: <attemptDir>/task-6-lifecycle-ownership.txt

  Scenario: projection and cross-line work cannot authorize mutation
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-adl,:surprising-insurance,:surprising-funding-provider,:surprising-liquidation -am -Dtest=AdlServiceTest#rejectsProjectionSelectedWork,InsuranceServiceTest#rejectsProjectionSelectedWork,FundingServiceTest#rejectsWrongProductLine,LiquidationServiceTest#rejectsWrongProductLine -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: zero Aeron mutation offers and exact ProductLine/authority errors.
    Evidence: <attemptDir>/task-6-lifecycle-ownership-error.txt
  ```

  Commit: YES | Message: `feat(lifecycle): bound Core-authoritative resolution work` | Files: [surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationWorkView.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationWorkCodec.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreTreasuryState.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreLifecycleWorkTest.java, surprising-funding/surprising-funding-provider/src/main/java/com/surprising/funding/provider/service/FundingService.java, surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java, surprising-adl/src/main/java/com/surprising/adl/provider/service/AdlService.java, surprising-insurance/src/main/java/com/surprising/insurance/provider/service/InsuranceService.java, surprising-funding/surprising-funding-provider/src/test/java/com/surprising/funding/provider/service/FundingServiceTest.java, surprising-liquidation/src/test/java/com/surprising/liquidation/provider/service/LiquidationServiceTest.java, surprising-adl/src/test/java/com/surprising/adl/provider/service/AdlServiceTest.java, surprising-insurance/src/test/java/com/surprising/insurance/provider/service/InsuranceServiceTest.java]

- [x] 7. Close the LINEAR/INVERSE perpetual CROSS/ISOLATED financial matrix

  What to do: Add a table-driven deterministic matrix for `LINEAR_PERPETUAL` and `INVERSE_PERPETUAL`, each in CROSS and ISOLATED. Cover opening/closing/reversal, tier change, maker/taker fees, positive/negative funding, stale mark rejection, mark risk scan, partial/full liquidation, capped liquidation fee, insurance full/partial cover, ADL ordering/coverage and snapshot continuation. Every row records user, maker, fee treasury and insurance opening/flow/ending units with exact difference zero. Fix only formula/ownership defects exposed by the red matrix.
  Must NOT do: No shared collateral across ProductLines, SPOT formula substitution, BigDecimal in Core, provider adjudication or broad refactor.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [8, 16] | Blocked by: [6]

  References:
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreContractMath.java:21-188` - linear/inverse notional, margin, PnL and funding math.
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1230-1295,1500-1818,1997-2056` - funding, liquidation, ADL and derivative fills.
  - Test: `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java:30-150,233-365` - baseline is mainly linear/CROSS.
  - Pattern: `docs/high-performance-trading-core-implementation.md:65-76,87-108` - margin domains and funds equation.

  Acceptance criteria:
  - [ ] Failing-first completeness assertion reports all missing `(contractType,marginMode,scenario)` rows on baseline; final manifest has no missing/duplicate row.
  - [ ] Every row asserts exact expected integer formula and `FUNDS_DIFFERENCE=0` across users, maker, fee treasury and insurance; ISOLATED loss never consumes unrelated isolated/CROSS collateral.
  - [ ] `mvn -pl :surprising-aeron-service -am -Dtest=CorePerpetualFinancialMatrixTest,CoreContractMathTest,CoreLifecycleStateTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.
  - [ ] Production edits, if any, are limited to `CoreContractMath`/`TradingCoreReducer` and each is linked to a red matrix row.

  QA scenarios:
  ```
  Scenario: four perpetual variants conserve funds through lifecycle
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-aeron-service -am -Dtest=CorePerpetualFinancialMatrixTest#coversLinearInverseCrossIsolated -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: four manifests pass every funding/mark/liquidation/ADL/insurance row with exact zero difference.
    Evidence: <attemptDir>/task-7-perpetual-matrix.txt

  Scenario: stale mark, cross-line and isolated-collateral attacks fail closed
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-aeron-service -am -Dtest=CorePerpetualFinancialMatrixTest#rejectsStaleCrossLineAndCollateralLeakage -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: no balance/position/treasury/export mutation for any adversarial row.
    Evidence: <attemptDir>/task-7-perpetual-matrix-error.txt
  ```

  Commit: YES | Message: `test(core): close perpetual financial matrix` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreContractMath.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CorePerpetualFinancialMatrixTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java]

- [x] 8. Close LINEAR/INVERSE delivery and CALL/PUT ITM/ATM/OTM option matrices

  What to do: Add deterministic LINEAR_DELIVERY and INVERSE_DELIVERY CROSS/ISOLATED settlement rows and OPTION CALL/PUT rows at ITM, ATM and OTM. Cover premium transfer/fees, long/short rights and obligations, intrinsic cash, expiry, duplicate settlement, order cancellation before settlement, position zeroing, margin release, snapshot cursor resume and exact treasury/user/maker conservation. Make Core derive or validate option cash from option type, strike and underlying settlement price; a provider-supplied unrelated cash value must not be authoritative. Fix only red formula/state defects.
  Must NOT do: No perpetual funding on delivery, nonzero ATM/OTM exercise, caller-authoritative option payout, retained settled position, cross-line asset use or matcher reconstruction.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [16] | Blocked by: [6, 7]

  References:
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreInstrumentState.java:25-26,62-94` - option type/strike authority.
  - Pattern: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1296-1424` - delivery/option settlement and caller cash field.
  - Test: `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java:152-231` - baseline combined linear delivery/one option case.
  - API/Type: `surprising-instrument/surprising-instrument-api/src/main/java/com/surprising/instrument/api/model/OptionType.java:3-5` and `OptionExerciseEvent.java:5-33`.
  - Pattern: `docs/high-performance-trading-core-implementation.md:91-108,634-636` - six-line funds and lifecycle acceptance.

  Acceptance criteria:
  - [ ] Failing-first completeness check identifies both inverse delivery and 6 option moneyness/type rows as absent; final matrix covers delivery 2x2 plus CALL/PUT x ITM/ATM/OTM without duplicates.
  - [ ] ATM/OTM intrinsic is zero, CALL/PUT ITM formula is exact, wrong provider cash is rejected or ignored in favor of Core calculation, duplicate settlement is idempotent, and all positions/margins end at zero.
  - [ ] `mvn -pl :surprising-aeron-service -am -Dtest=CoreDeliveryOptionFinancialMatrixTest,CoreContractMathTest,CoreLifecycleStateTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.
  - [ ] Every manifest reports `FUNDS_DIFFERENCE=0` for both counterparties, maker/fees/insurance as applicable.

  QA scenarios:
  ```
  Scenario: delivery and option matrix settles exactly once
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-aeron-service -am -Dtest=CoreDeliveryOptionFinancialMatrixTest#coversDeliveryAndOptionMoneyness -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: all ten required variant groups pass with zero positions, released margin and zero funds difference.
    Evidence: <attemptDir>/task-8-delivery-option-matrix.txt

  Scenario: mismatched cash, duplicate and cross-line settlement fail safely
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-aeron-service -am -Dtest=CoreDeliveryOptionFinancialMatrixTest#rejectsUntrustedCashDuplicateMutationAndWrongLine -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: payout is Core-derived; duplicate returns original; wrong line/cursor changes nothing.
    Evidence: <attemptDir>/task-8-delivery-option-matrix-error.txt
  ```

  Commit: YES | Message: `test(core): close delivery option financial matrix` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreContractMath.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreDeliveryOptionFinancialMatrixTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java]

- [x] 9. Add exporter-owned additive PostgreSQL schema and transactional contiguous projection watermark

  What to do: Add the next numbered exporter migration (baseline ends at V006) for one ProductLine watermark row and deterministic WebSocket audit mirror. In `JdbcCoreEventProjector`, lock/read the watermark inside the same transaction as event/fact/audit inserts: accept next contiguous sequence, no-op exact duplicate only after payload identity validation, reject gap/reorder/conflicting duplicate, and update watermark last before commit. Rollback any fact/audit/watermark failure. Keep migration ownership in `surprising-aeron-exporter`; other modules only read these tables.
  Must NOT do: No destructive/reordered migration, schema creation in Gateway/order provider, max(sequence) inference, gap skipping, PG authority over Core or partial commit.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [10, 11, 12, 17] | Blocked by: []

  References:
  - Pattern: `surprising-aeron-core/surprising-aeron-exporter/src/main/resources/db/migration/V001__create_core_event_projection.sql:1-17` and `V006__enrich_core_liquidation_projection.sql` - migration ownership/order.
  - Pattern: `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/JdbcCoreEventProjector.java:18-106,114-136` - current event/fact transaction and dedupe.
  - Pattern: `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/KafkaProjectionWorker.java:30-43` - project then commit Kafka offset.
  - Evidence: `.omo/evidence/w5-collect-gate-review.md:17-35` - confirmed missing PG-coupled watermark.

  Acceptance criteria:
  - [ ] Failing-first real-Postgres test proves no watermark table/atomic contract on baseline; final test proves N commit, duplicate N no-op, N+2 rollback, conflicting N rollback, restart persistence and fact failure rollback.
  - [ ] Migration is additive, repeat-safe under Flyway, exporter-owned, and creates keys/indexes for ProductLine/export sequence/event ID audit reads.
  - [ ] `mvn -pl :surprising-aeron-exporter -am -Dtest=JdbcCoreEventProjectorTest,JdbcCoreEventProjectorPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25 with Docker available.
  - [ ] SQL assertions show facts, audit and watermark are all visible or all absent after each injected transaction outcome.

  QA scenarios:
  ```
  Scenario: facts, audit and watermark commit atomically
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-aeron-exporter -am -Dtest=JdbcCoreEventProjectorPostgresTest#commitsContiguousFactsAuditAndWatermark -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: sequence N rows and watermark N commit together; exact replay changes no counts.
    Evidence: <attemptDir>/task-9-pg-watermark.txt

  Scenario: gap/conflict/fact failure rolls back all projection state
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-aeron-exporter -am -Dtest=JdbcCoreEventProjectorPostgresTest#rollsBackGapConflictAndFactFailure -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: exact SQL row counts and watermark remain at N-1; Kafka offset is not authorized.
    Evidence: <attemptDir>/task-9-pg-watermark-error.txt
  ```

  Commit: YES | Message: `feat(projection): persist contiguous export watermark` | Files: [surprising-aeron-core/surprising-aeron-exporter/src/main/resources/db/migration/V007__add_projection_watermark_and_websocket_audit.sql, surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/JdbcCoreEventProjector.java, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/JdbcCoreEventProjectorTest.java, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/JdbcCoreEventProjectorPostgresTest.java, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/KafkaProjectionWorkerTest.java (new)]

- [x] 10. Add bounded projection waiting/cursors/bytes and migrate ordinary order reads off Core

  What to do: Extend `AeronOrderProjectionRepository` with by-order, by-client, open and history queries scoped to one ProductLine/user, stable `(updated_at_epoch_ms,order_id)` cursor, `limit<=1000`, configured max encoded response bytes and optional `minExportSequence`. Add a fake-clock-testable waiter that checks the Task 9 watermark with bounded attempts/deadline and returns typed `PROJECTION_LAG(observed,required)`; no Core fallback. Wire all ordinary external reads and cancel-open selection to projection. Keep command-result/preflight/lifecycle/admin control queries on Task 4 reserved capacity only. Remove ordinary read methods from provider Gateway, not low-level diagnostic tools.
  Must NOT do: No `appliedCommandCount` watermark, unbounded wait/page, unstable offset pagination, Core fallback, `SELECT FOR UPDATE` on ordinary reads or public use of reserved query capacity.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [5, 17] | Blocked by: [4, 9]

  References:
  - Pattern: `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/repository/AeronOrderProjectionRepository.java:18-77` - existing JDBC query mapping.
  - Pattern: `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:265-330` - mixed Core/projection external reads.
  - Pattern: `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java:43-61,83-90` - ordinary queries to remove and preflight to retain.
  - Pattern: `docs/high-performance-trading-core-implementation.md:429-435` - projection/read-your-write boundary.

  Acceptance criteria:
  - [ ] Failing-first tests prove get/open reads hit Aeron and no watermark wait exists; final tests prove all four ordinary reads use projection only with deterministic ProductLine/user isolation.
  - [ ] Exact watermark succeeds; lag expires within configured fake-clock bound with observed/required values; row/byte/cursor boundaries stop before overflow and make forward progress.
  - [ ] `rg -n 'ORDER_STATE_QUERY|CLIENT_ORDER_STATE_QUERY|USER_OPEN_ORDERS_QUERY' surprising-trading/surprising-order-provider/src/main/java` finds no external-read call.
  - [ ] `mvn -pl :surprising-order-provider,:surprising-aeron-client -am -Dtest=ProjectedOrderQueryRepositoryTest,ProjectionWatermarkWaiterTest,OrderServiceTest,CoreQueryClassTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios:
  ```
  Scenario: read-your-write waits for actual export sequence
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-order-provider -am -Dtest=OrderServiceTest#readsProjectionAtRequiredExportSequenceWithoutAeron -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: response watermark is >= required export sequence, cursor/bytes are bounded and Aeron has zero interactions.
    Evidence: <attemptDir>/task-10-projection-reads.txt

  Scenario: lag and oversized page fail without Core fallback
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-order-provider -am -Dtest=ProjectedOrderQueryRepositoryTest#boundsLagCursorAndEncodedBytesWithoutCoreFallback -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: typed lag or truncated page with next cursor returns by deadline; zero Aeron calls.
    Evidence: <attemptDir>/task-10-projection-reads-error.txt
  ```

  Commit: YES | Message: `feat(order): serve bounded projection-only reads` | Files: [surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/repository/AeronOrderProjectionRepository.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/repository/ProjectionWatermarkWaiter.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/repository/ProjectionReadResult.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/repository/ProjectedOrderQueryRepositoryTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/repository/ProjectionWatermarkWaiterTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/OrderServiceTest.java]

- [x] 11. Piggyback export batch status, use adaptive polling and expose lag/failure metrics

  What to do: Extend `CoreExportBatch`/codec with complete post-query status (acknowledged, next, pending count/bytes and limits) so empty batches and successful ACKs need no extra status query. Replace fixed-base 10ms Core polling with an adaptive bounded policy driven by batch status/activity, capped idle, immediate drain while pending, and low-frequency reconnect safety poll compatible with current Aeron capabilities. Keep Kafka projection polling bounded/adaptive. Add counters/gauges for export backlog count/bytes, oldest event age, export->Kafka lag, Kafka->PG watermark lag, published/duplicate/retry/failure/unknown and reconnect state.
  Must NOT do: No busy loop, fixed 10ms status poll, unbounded exponential sleep, ACK before Kafka publish, unknown ACK retry with new ID or average-TPS capacity claim.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [12, 17] | Blocked by: [9]

  References:
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreExportBatch.java:5-11` - baseline carries only acknowledged sequence/events.
  - Pattern: `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ReliableCoreExporter.java:42-82` - redundant status calls and publish-before-ACK.
  - Pattern: `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ExporterMain.java:16-35` and `ExporterConfiguration.java:31-37` - 10ms base polling/backoff.
  - Pattern: `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ProjectionMain.java:30-34` - fixed 250ms projection polling.

  Acceptance criteria:
  - [ ] Failing-first tests count redundant status queries and fixed poll cadence; final active/idle/failure traces match the deterministic adaptive schedule and capped safety poll.
  - [ ] Nonempty cycle is batch+publish+ACK; empty cycle is one batch query; ACK unknown preserves original ID and does not advance local success.
  - [ ] Metrics expose exact backlog/event-age/export/PG lag and failure counters with ProductLine tags and bounded cardinality.
  - [ ] `mvn -pl :surprising-aeron-protocol,:surprising-aeron-exporter -am -Dtest=CoreExportCodecTest,ReliableCoreExporterTest,AdaptiveExportLoopTest,ExporterMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios:
  ```
  Scenario: active backlog drains without redundant status queries
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-aeron-exporter -am -Dtest=ReliableCoreExporterTest#usesBatchStatusAndPublishesBeforeAck,AdaptiveExportLoopTest#drainsActiveBacklogImmediately -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: one batch query per cycle, no status query, contiguous publish then ACK, lag metrics reach zero.
    Evidence: <attemptDir>/task-11-export-adaptive.txt

  Scenario: idle/failure path is bounded and observable
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-aeron-exporter -am -Dtest=AdaptiveExportLoopTest#capsIdleAndReconnectPolling,ExporterMetricsTest#recordsUnknownAndProjectionLag -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: no 10ms fixed loop, capped safety poll, original ACK ID retained, exact counters increment once.
    Evidence: <attemptDir>/task-11-export-adaptive-error.txt
  ```

  Commit: YES | Message: `feat(exporter): expose status and adaptive lag telemetry` | Files: [surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreExportBatch.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreExportCodec.java, surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ReliableCoreExporter.java, surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/AdaptiveExportLoop.java, surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ExporterMetrics.java, surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ExporterMain.java, surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ProjectionMain.java, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/ReliableCoreExporterTest.java, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/AdaptiveExportLoopTest.java, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/ExporterMetricsTest.java]

- [x] 12. Make WebSocket IDs deterministic, Kafka restart authoritative, PG-audited and slow-client isolated

  What to do: Define stable event IDs from `(ProductLine,exportSequence,eventKind,itemIndex)` and include them in every Core-derived public/private payload. Remove process-local `lastAppliedExportSequence` as restart authority: use Kafka partition ordering/key validation and committed consumer-group offsets; only commit after PG audit mirror and fanout enqueue decision complete. On replay, PG audit/event ID dedupes audit and clients dedupe delivery; server remains at-least-once. Add deterministic blocked-session tests using a controllable real `WebSocketSession`, bounded queue and healthy peer; close only the slow client, keep Kafka progress/healthy latency bounded, and export rejection/queue/lag metrics.
  Must NOT do: No exactly-once claim, distributed socket transaction, application watermark ahead of Kafka offset, shared send queue, sleep-based test, or edit to `WebSocketRuntimeHints.java`.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [17] | Blocked by: [1, 9, 11]

  References:
  - Pattern: `surprising-gateway/src/main/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumer.java:44,67-103,112-145` - process-local watermark and sequence-derived payloads.
  - Pattern: `surprising-gateway/src/main/java/com/surprising/websocket/provider/service/ClientConnection.java:19-22,70-103,136-159` - bounded per-client queue and close behavior.
  - Pattern: `surprising-gateway/src/main/java/com/surprising/websocket/provider/service/SubscriptionRegistry.java:108-122,191-195` - fanout and metrics.
  - Test: `surprising-gateway/src/test/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumerTest.java:41-74` - same-instance dedupe only.
  - Evidence: `.omo/evidence/w5-collect-gate-review.md:37-74` - restart and slow-client proof gaps.
  - External: `https://kafka.apache.org/documentation/#consumerconfigs_enable.auto.commit` - explicit offset ownership.

  Acceptance criteria:
  - [ ] Failing-first tests reconstruct the consumer and show baseline process-local continuity loss; final test resumes from committed Kafka offset, tolerates replay and rejects key/partition/gap corruption without committing.
  - [ ] Every event kind has deterministic collision-free IDs for tested indexes; PG audit unique key is idempotent; duplicate delivery may reach clients but carries the same ID.
  - [ ] A blocked real session reaches queue bound and closes while healthy peer receives within deterministic deadline and Kafka offset advances only after audit/fanout decision.
  - [ ] `mvn -pl :surprising-gateway -am -Dtest=CoreEventFanoutConsumerTest,ClientConnectionIsolationTest,SubscriptionRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25; protected path receipt is unchanged.

  QA scenarios:
  ```
  Scenario: restart/replay preserves stable IDs and Kafka authority
    Tool:     bash
    Steps:    Run the JDK 25 prefix and `mvn -pl :surprising-gateway -am -Dtest=CoreEventFanoutConsumerTest#restartsFromCommittedOffsetWithStableEventIds -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: reconstructed consumer replays safely; PG audit count remains one; emitted duplicate has identical event ID; offset commits after processing.
    Evidence: <attemptDir>/task-12-websocket-restart.txt

  Scenario: blocked client cannot stall healthy fanout
    Tool:     bash
    Steps:    Run `mvn -pl :surprising-gateway -am -Dtest=ClientConnectionIsolationTest#closesOnlyBlockedSessionAtQueueBound -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: slow session closes `SERVICE_OVERLOAD`; healthy session receives all IDs by latch deadline; no unbounded queue/thread growth.
    Evidence: <attemptDir>/task-12-websocket-restart-error.txt
  ```

  Commit: YES | Message: `feat(websocket): persist audit and isolate slow clients` | Files: [surprising-gateway/src/main/java/com/surprising/websocket/provider/service/CoreWebSocketEventId.java, surprising-gateway/src/main/java/com/surprising/websocket/provider/service/CoreEventAuditRepository.java, surprising-gateway/src/main/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumer.java, surprising-gateway/src/main/java/com/surprising/websocket/provider/service/ClientConnection.java, surprising-gateway/src/main/java/com/surprising/websocket/provider/service/SubscriptionRegistry.java, surprising-gateway/src/test/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumerTest.java, surprising-gateway/src/test/java/com/surprising/websocket/provider/service/ClientConnectionIsolationTest.java, surprising-gateway/src/test/java/com/surprising/websocket/provider/service/SubscriptionRegistryTest.java]

- [x] 13. Correct exactly seven matching-provider ProductLine fixtures

  What to do: Capture the raw baseline Surefire failures for four `KafkaPublicTradePublisherTest` methods and three `KafkaOrderBookDepthPublisherTest` methods. Set one explicit valid ProductLine in each of exactly those seven fixture setups (use a shared test helper only if it reduces repetition without touching production). Re-run only both classes and retain raw XML/TXT showing seven prior failures and all final passes.
  Must NOT do: No production fallback/default, no eighth fixture edit, no test deletion/weakening, no Kafka service startup and no unrelated matching behavior change.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [] | Blocked by: []

  References:
  - Test: `surprising-trading/surprising-matching-provider/src/test/java/com/surprising/trading/matching/service/KafkaPublicTradePublisherTest.java:24-124` - four default `MatchingProperties` fixtures.
  - Test: `surprising-trading/surprising-matching-provider/src/test/java/com/surprising/trading/matching/service/KafkaOrderBookDepthPublisherTest.java:25-115` - three fixtures.
  - Pattern: `surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/config/MatchingProperties.java:48-55,107-136` - null ProductLine reaches topic derivation.
  - API/Type: `surprising-product-api/src/main/java/com/surprising/product/api/ProductTopicNames.java:17-18` - ProductLine is mandatory.
  - Evidence: `.omo/evidence/w5-collect-gate-review.md:76-103` - exact seven-fixture diagnosis.

  Acceptance criteria:
  - [ ] Baseline raw output records exactly seven failures across the two classes and no infrastructure dependency.
  - [ ] Final diff modifies exactly the two test files (or one new test helper plus those two), and production diff is empty.
  - [ ] `mvn -pl :surprising-matching-provider -am -Dtest=KafkaPublicTradePublisherTest,KafkaOrderBookDepthPublisherTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25 with exactly seven test methods passing.
  - [ ] `git diff --name-only 7e78e04a -- surprising-trading/surprising-matching-provider` lists only declared test paths.

  QA scenarios:
  ```
  Scenario: all seven explicit-line fixtures pass
    Tool:     bash
    Steps:    Run the JDK 25 prefix and the exact Acceptance Maven command; copy Surefire XML/TXT counts.
    Expected: 7 tests, 0 failures/errors/skips; topics resolve for the explicit ProductLine.
    Evidence: <attemptDir>/task-13-matching-fixtures.txt

  Scenario: missing ProductLine still fails in production configuration
    Tool:     bash
    Steps:    Inspect the captured baseline Surefire XML/TXT for all seven missing-ProductLine failures caused by `ProductTopicNames.of(null)` dereferencing `productLine.topicSegment()`, then run `git diff --exit-code 7e78e04a -- surprising-trading/surprising-matching-provider/src/main` and `git diff --unified=0 7e78e04a --` for the two declared fixture files.
    Expected: baseline evidence proves null ProductLine fails before any topic is produced; production remains byte-identical and every green hunk only supplies an explicit ProductLine in one of the seven fixture setups. The pre-existing NullPointerException shape is recorded, not normalized in this fixture-only task.
    Evidence: <attemptDir>/task-13-matching-fixtures-error.txt
  ```

  Commit: YES | Message: `test(matching): set product line in publisher fixtures` | Files: [surprising-trading/surprising-matching-provider/src/test/java/com/surprising/trading/matching/service/KafkaPublicTradePublisherTest.java, surprising-trading/surprising-matching-provider/src/test/java/com/surprising/trading/matching/service/KafkaOrderBookDepthPublisherTest.java]

- [x] 14. Check in one-run-owned LINEAR_PERPETUAL full-stack orchestration and safe cleanup

  What to do: Add a module-local runtime bundle under `surprising-aeron-core/runtime/w3-w5/` (not a deleted top-level documentation/script assumption). It creates a unique run ID, compose project, Kafka/PG databases, topic prefix/list derived from `ProductTopicNames`, process directory/PIDs/logs and explicit ports. Start Kafka/PG, migrations, three-member `LINEAR_PERPETUAL` Core, exporter/projector, instrument/price/order/matching/trigger/risk/funding/liquidation/insurance/ADL providers, Gateway, then maker last after readiness; never start wallet. Implement `up/status/run/down` and a trap that kills only recorded PIDs/compose project, preserves unrelated volumes by default, supports an explicit task-run fresh flag, and writes before/after ownership manifests. Reject unknown ProductLine and any attempt to run two stacks.
  Must NOT do: No broad `pkill`, `docker system prune`, global volume deletion, wallet, second ProductLine, hidden port reuse or main-worktree execution.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [15, 16, 17] | Blocked by: []

  References:
  - Pattern: `surprising-aeron-core/compose.yaml:1-170` - existing three-member Core topology and ProductLine parameter.
  - Pattern: `scripts/aeron-core-local.sh:1-80` at the exact baseline - scoped Core lifecycle pattern; do not assume this top-level path exists outside the task worktree.
  - API/Type: `surprising-product-api/src/main/java/com/surprising/product/api/ProductTopicNames.java:3-18` - authoritative topic derivation.
  - Pattern: `README.md:121-154` - service ports, maker, Kafka/PG ownership and observable metrics.
  - Pattern: `surprising-maker/src/main/resources/application.yml:41-134` - ProductLine and component enablement; maker must start last.

  Acceptance criteria:
  - [ ] Failing-first dry-run safety test rejects absent run ID, unknown line, wallet flag, occupied port, foreign PID/container/volume and concurrent runtime lock.
  - [ ] `bash -n surprising-aeron-core/runtime/w3-w5/run.sh` and `PRODUCT_LINE=LINEAR_PERPETUAL .../run.sh dry-run` pass under JDK 25 and emit exact ordered services/topics/ports without mutation.
  - [ ] A short `up -> status -> down` proves maker starts after all readiness checks, wallet is absent, every resource is run-labelled, cleanup returns process/container/port inventory to pre-run state and unrelated volumes remain.
  - [ ] Protected main-worktree status/index blob remains exact.

  QA scenarios:
  ```
  Scenario: owned LINEAR_PERPETUAL stack starts maker last and cleans up
    Tool:     bash
    Steps:    Run the JDK 25 prefix, then `RUN_ID=plan14-smoke PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false surprising-aeron-core/runtime/w3-w5/run.sh smoke`.
    Expected: manifest lists one line, Kafka/PG/Core/providers/Gateway, maker as final ready process, wallet absent, and `CLEANUP=PASS`.
    Evidence: <attemptDir>/task-14-runtime-orchestration.txt

  Scenario: foreign ownership and unsafe cleanup are refused
    Tool:     bash
    Steps:    Run `RUN_ID=plan14-adversarial PRODUCT_LINE=SPOT surprising-aeron-core/runtime/w3-w5/run.sh dry-run`, then `RUN_ID=plan14-cleanup-test PRODUCT_LINE=LINEAR_PERPETUAL bash surprising-aeron-core/runtime/w3-w5/tests/ownership-safe-cleanup.sh`; the test creates its own foreign labelled container/PID fixture, invokes `run.sh down`, asserts the foreign resources still exist, and removes only its own fixtures in a trap.
    Expected: SPOT is rejected for full-stack mode; foreign resources survive; command exits nonzero with exact ownership error.
    Evidence: <attemptDir>/task-14-runtime-orchestration-error.txt
  ```

  Commit: YES | Message: `test(runtime): orchestrate owned linear perpetual stack` | Files: [surprising-aeron-core/runtime/w3-w5/compose.yaml, surprising-aeron-core/runtime/w3-w5/run.sh, surprising-aeron-core/runtime/w3-w5/README.md, surprising-aeron-core/runtime/w3-w5/scenarios/common.sh, surprising-aeron-core/runtime/w3-w5/tests/ownership-safe-cleanup.sh]

- [ ] 15. Execute real W3 HTTP batch/idempotency/backpressure/result-unknown QA

  What to do: Add a checked-in W3 real-surface driver and execute it through Task 14. Seed deterministic users/instrument/maker funds. Through Gateway HTTP, run single and maximum place/amend/cancel batches; verify one outer command each, ordered partial outcome, stable IDs, same-key replay, changed-fingerprint conflict, immediate command/query saturation, admitted response timeout, command-result resolution and projection read at returned `requiredExportSequence`. Reconcile user/maker/fees and capture Kafka/PG evidence. Always cleanup via the runtime trap.
  Must NOT do: No mocked transport, new ID after unknown, wallet, second line, projection fallback or capacity claim.

  Parallelization: Can parallel: NO (shared runtime lock) | Wave 4 | Blocks: [18] | Blocked by: [5, 14]

  References:
  - API/Type: Task 5 HTTP contracts and `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java:60-141`.
  - Pattern: `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterProductLineGateMain.java:90-175` - deterministic users/funds fixture pattern.
  - Pattern: `README.md:145-154` - fixed agents and required metrics.
  - Runtime: `surprising-aeron-core/runtime/w3-w5/run.sh` - Task 14 owned stack.

  Acceptance criteria:
  - [ ] Red dry-run assertion shows baseline runtime lacks W3 HTTP scenario; final driver executes against real Gateway/Aeron/Kafka/PG.
  - [ ] Responses include exact 200/202/409/429/503 contracts, stable IDs, one offer per batch and actual required export sequence; command-result eventually returns the original terminal result.
  - [ ] PG watermark reaches required sequence, ordinary read never invokes Core, and user+maker+fees funds difference is zero.
  - [ ] Cleanup and protected-state receipts pass after success and injected failure.

  QA scenarios:
  ```
  Scenario: real W3 happy path and retry semantics
    Tool:     bash + curl
    Steps:    Run the JDK 25 prefix, acquire runtime lock, then `RUN_ID=w3-real PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false surprising-aeron-core/runtime/w3-w5/run.sh scenario w3-http`.
    Expected: `W3_HTTP=PASS`, batch round trips 3, same-key replay original, projection watermark satisfied, `FUNDS_DIFFERENCE=0`, cleanup pass.
    Evidence: <attemptDir>/task-15-w3-real.txt

  Scenario: conflict, saturation and admitted unknown resolve safely
    Tool:     bash + curl
    Steps:    Run `RUN_ID=w3-fault PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false surprising-aeron-core/runtime/w3-w5/run.sh scenario w3-faults`.
    Expected: exact 409/429/202, no retry/new ID, later 200 command result, no duplicate funds/facts, cleanup pass.
    Evidence: <attemptDir>/task-15-w3-real-error.txt
  ```

  Commit: YES | Message: `test(w3): add real Gateway command gate` | Files: [surprising-aeron-core/runtime/w3-w5/scenarios/w3-http.sh, surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/W3HttpQaMain.java]

- [ ] 16. Execute real W4 provider lifecycle/funds QA and sequential six-line manifests

  What to do: Add a W4 driver that runs the explicit ordered `PRODUCT_LINES=SPOT,LINEAR_PERPETUAL,INVERSE_PERPETUAL,LINEAR_DELIVERY,INVERSE_DELIVERY,OPTION` list sequentially, never concurrently. `run.sh scenario w4-six-line` remains owned by `PRODUCT_LINE=LINEAR_PERPETUAL` but delegates each listed line to `scenarios/w4-six-line.sh`, which uses `scenarios/common.sh` internal `start_line_subset <line>` / `stop_line_subset <line>` helpers; only `LINEAR_PERPETUAL` starts the full Task 14 stack and each subset must be stopped and inventory-clean before advancing. Exercise real provider-to-Core funding, mark, risk, liquidation, insurance, ADL, delivery and option lifecycle calls. Produce six manifests: SPOT conservation/control guard; LINEAR/INVERSE perpetual CROSS+ISOLATED; LINEAR/INVERSE delivery CROSS+ISOLATED; OPTION CALL/PUT ITM/ATM/OTM. Include initial, adjustments, trades, fees, funding, liquidation, insurance/ADL, settlement/exercise and ending user/maker/treasury balances plus snapshots/cursors. Cleanup between lines.
  Must NOT do: No simultaneous line clusters, wallet, generic derivative formula, PG-selected work, fake provider pass or reuse of one manifest for another line.

  Parallelization: Can parallel: NO (shared runtime lock and sequential six-line rule) | Wave 4 | Blocks: [18] | Blocked by: [6, 7, 8, 14]

  References:
  - Test: Task 7 `CorePerpetualFinancialMatrixTest` and Task 8 `CoreDeliveryOptionFinancialMatrixTest` - formula oracle.
  - Pattern: `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterProductLineGateMain.java:97-173,271-277` - baseline line branching.
  - Pattern: `docs/high-performance-trading-core-implementation.md:51-76,91-108,634-636` - six-line isolation and funds acceptance.
  - Rule: `AGENTS.md:12-41` - one line at a time, maker running, wallet absent and funds reconciliation.

  Acceptance criteria:
  - [ ] Red manifest checker reports missing six-line/provider rows before driver implementation; final checker accepts exactly six uniquely named manifests in required order.
  - [ ] All applicable CROSS/ISOLATED and CALL/PUT moneyness cases match Task 7/8 formulas and report `FUNDS_DIFFERENCE=0` for users/maker/treasury.
  - [ ] Provider ProductLine mismatch, cursor repeat/gap and PG-selected work scenarios are rejected with zero mutation.
  - [ ] Each line cleanup completes before the next starts; wallet/process inventory remains absent.

  QA scenarios:
  ```
  Scenario: sequential six-line provider lifecycle matrix
    Tool:     bash
    Steps:    Run the JDK 25 prefix, acquire runtime lock, then `RUN_ID=w4-six-line PRODUCT_LINE=LINEAR_PERPETUAL PRODUCT_LINES=SPOT,LINEAR_PERPETUAL,INVERSE_PERPETUAL,LINEAR_DELIVERY,INVERSE_DELIVERY,OPTION WALLET_ENABLED=false surprising-aeron-core/runtime/w3-w5/run.sh scenario w4-six-line`.
    Expected: six ordered manifests, all required financial rows, provider-to-Core evidence, zero funds difference and cleanup pass per line.
    Evidence: <attemptDir>/task-16-w4-six-line.txt

  Scenario: cross-line, cursor and projection-authority attacks fail closed
    Tool:     bash
    Steps:    Run `RUN_ID=w4-adversarial PRODUCT_LINE=LINEAR_PERPETUAL PRODUCT_LINES=SPOT,LINEAR_PERPETUAL,INVERSE_PERPETUAL,LINEAR_DELIVERY,INVERSE_DELIVERY,OPTION WALLET_ENABLED=false surprising-aeron-core/runtime/w3-w5/run.sh scenario w4-faults`.
    Expected: wrong line/account/cursor/PG-selected targets produce exact errors and no funds/position/export changes.
    Evidence: <attemptDir>/task-16-w4-six-line-error.txt
  ```

  Commit: YES | Message: `test(w4): add six-line lifecycle funds gate` | Files: [surprising-aeron-core/runtime/w3-w5/scenarios/w4-six-line.sh, surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/W4LifecycleQaMain.java]

- [ ] 17. Execute real W5 Kafka/PG/exporter/projector/Gateway/WebSocket fault QA

  What to do: Add a W5 fault driver on the owned `LINEAR_PERPETUAL` stack. Exercise publish-before-ACK crash, duplicate/reordered/gapped Kafka records, projector transaction failure/restart, PG pause/recovery, exporter disconnect/adaptive recovery, projection lag timeout, Gateway restart from committed offset, deterministic event-ID replay, PG audit dedupe, and one blocked plus one healthy WebSocket client. Assert lag/failure metrics and no Core/funds/matcher dependence on PG/Kafka/WebSocket availability. Cleanup all resources.
  Must NOT do: No H2/mock-only substitution, exactly-once claim, offset commit before audit/fanout decision, Core fallback, broad broker/volume deletion or production network/disk certification.

  Parallelization: Can parallel: NO (shared runtime lock) | Wave 4 | Blocks: [18] | Blocked by: [9, 10, 11, 12, 14]

  References:
  - Pattern: `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ReliableCoreExporter.java:42-71` - publish-before-ACK boundary.
  - Pattern: `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/KafkaProjectionWorker.java:30-43` - projection then offset commit.
  - Pattern: `surprising-gateway/src/main/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumer.java:67-103` - Kafka key/order/fanout boundary.
  - Evidence: `.omo/evidence/w5-collect-gate-review.md:17-74,105-127` - confirmed code/runtime gaps.

  Acceptance criteria:
  - [ ] Red scenario inventory shows baseline lacks real Kafka/PG/restart/slow-client evidence; final driver executes every named fault with binary assertions.
  - [ ] Duplicate replay creates one PG fact/audit identity; gap/reorder does not advance watermark/offset; crash after Kafka publish replays safely; PG pause causes bounded lag only.
  - [ ] Gateway restart resumes from Kafka committed offsets; slow client closes at bound while healthy client receives stable IDs; metrics show exact lag/retry/failure/rejection transitions.
  - [ ] Core orders/funds/matcher continue or fail only on Core capacity, never on PG/Kafka/WebSocket adjudication; cleanup/protected receipts pass.

  QA scenarios:
  ```
  Scenario: real exporter/projector restart windows replay safely
    Tool:     bash + psql
    Steps:    Run the JDK 25 prefix, acquire runtime lock, then `RUN_ID=w5-restart PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false surprising-aeron-core/runtime/w3-w5/run.sh scenario w5-export-projection`.
    Expected: one fact/audit per event ID, contiguous watermark/offset after recovery, observed lag returns to zero, cleanup pass.
    Evidence: <attemptDir>/task-17-w5-faults.txt

  Scenario: PG pause, Kafka gap and slow socket remain isolated
    Tool:     bash + real WebSocket client
    Steps:    Run `RUN_ID=w5-isolation PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false surprising-aeron-core/runtime/w3-w5/run.sh scenario w5-isolation`.
    Expected: typed projection lag, no gap commit, healthy socket bounded delivery, slow socket overload close, Core funds unchanged, cleanup pass.
    Evidence: <attemptDir>/task-17-w5-faults-error.txt
  ```

  Commit: YES | Message: `test(w5): add replicated projection fault gate` | Files: [surprising-aeron-core/runtime/w3-w5/scenarios/w5-export-projection.sh, surprising-aeron-core/runtime/w3-w5/scenarios/w5-isolation.sh, surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/W5FaultQaMain.java, surprising-gateway/src/test/java/com/surprising/websocket/provider/service/LiveSlowClientIsolationTest.java]

- [ ] 18. Synchronize documentation/status/evidence, prove rollback and push closure

  What to do: Update root/module Chinese READMEs and canonical implementation status with exact W3-W5 contracts, actual commands/counts/manifests, tested/untested scope and remaining P6 exclusions. Add an implementation ledger binding every task commit SHA, failing-first transcript, JDK 25 result, real QA evidence, cleanup/protected-state receipt and push receipt. Document additive DB/wire/snapshot rollback: stop traffic/export consumers, restore previous task commit and fresh Core cluster for incompatible wire/snapshot, roll forward additive PG migration (never destructive down migration), preserve Kafka offsets/audit. Run affected-reactor gates, forbidden-mechanism scans, diff checks, protected state comparison, commit/path audit and push every verified atomic commit in order. Add final commit footer `Plan: .omo/plans/w3-w5-production-closure.md`.
  Must NOT do: No false DONE/P6/capacity claim, missing path link, broad staging, amend of pushed commits, protected path, unrelated `.omo`, wallet or destructive migration rollback.

  Parallelization: Can parallel: NO | Wave 5 | Blocks: [F1, F2, F3, F4, F5] | Blocked by: [15, 16, 17]

  References:
  - Pattern: `docs/high-performance-trading-core-implementation.md:409-435,554-568,673-701` - W3/W5 target and evidence-backed status.
  - Pattern: `README.md:145-154` - public architecture/metrics summary.
  - Pattern: `surprising-aeron-core/README.md:69-80`, `surprising-trading/README.md:396-412,451-458` and `surprising-gateway/README.md` - module contracts.
  - Rule: `AGENTS.md:22-53` - impact-based JDK/Maven testing, docs, commit/push and no deleted-script assumptions.
  - Evidence: Tasks 15-17 manifests and cleanup receipts.

  Acceptance criteria:
  - [ ] JDK 25 affected reactors for protocol/client/service/exporter/order/matching/gateway plus funding/liquidation/ADL/insurance pass; exact commands, counts, skipped scope and rationale are in the ledger.
  - [ ] `git diff --check` passes; source scans find no forbidden matcher/CoreBookState, request `supplyAsync`, unbounded lifecycle loop, ordinary Core read, production ProductLine fallback, fixed 10ms status poll or unrelated counter-as-watermark.
  - [ ] Every commit contains only declared task files, builds/tests independently where applicable, is pushed, and the final branch is a descendant of the exact baseline.
  - [ ] Protected main-worktree status/index blob and unrelated dirty paths are byte-identical to preflight; runtime inventory is clean.
  - [ ] Documentation marks W3-W5 complete only with linked evidence and explicitly leaves wallet/hot split/cross-line/P6/24h/network-disk capacity out.

  QA scenarios:
  ```
  Scenario: final affected-reactor/docs/evidence gate
    Tool:     bash
    Steps:    Run the JDK 25 prefix, `mvn -pl :surprising-aeron-protocol,:surprising-aeron-client,:surprising-aeron-service,:surprising-aeron-exporter,:surprising-order-provider,:surprising-matching-provider,:surprising-gateway,:surprising-funding-provider,:surprising-liquidation,:surprising-adl,:surprising-insurance -am test`, `git diff --check`, and validate each ledger SHA with `git cat-file -e <sha>^{commit}`.
    Expected: all affected gates pass; ledger evidence files exist; tested/untested scope and rollback steps are exact.
    Evidence: <attemptDir>/task-18-release-closure.txt

  Scenario: forbidden scope, dirty-state or rollback drift blocks push
    Tool:     bash
    Steps:    Run the plan's forbidden `rg` scans, compare protected receipts, execute runtime `down/status`, verify migration list/order, then `git log --format='%H %s' 7e78e04a..HEAD` and remote ancestry after push.
    Expected: no forbidden production mechanism or drift; only additive V007 migration; clean runtime; every local task commit exists on remote.
    Evidence: <attemptDir>/task-18-release-closure-error.txt
  ```

  Commit: YES | Message: `docs(core): record w3 w4 w5 production closure` | Files: [README.md, docs/high-performance-trading-core-implementation.md, surprising-aeron-core/README.md, surprising-trading/README.md, surprising-gateway/README.md, surprising-liquidation/README.md, surprising-maker/README.md, .omo/evidence/w3-w5-implementation-ledger.md, .omo/plans/w3-w5-production-closure.md]

## Final verification wave
> Runs after all implementation tasks. No additional user-approval pause: `$start-work` authorizes execution, and completion requires all five gates plus the debugging audit evidence to approve the exact final SHA.

- [ ] F1. Plan compliance and evidence audit - verify all 18 rows, acceptance checks, red/green transcripts, QA artifacts, cleanup receipts, dependency order and commit SHAs against this file; write `<attemptDir>/final-f1-plan-compliance.md` with `APPROVE` or blocking findings.
- [ ] F2. Global five-lane review - run the repository review workflow at the exact full SHA for goal/constraints, code quality, security/data integrity, hands-on QA and context/scope minimality; require five `APPROVE` receipts bound to that SHA in `<attemptDir>/final-f2-review-work.md`.
- [ ] F3. Runtime debugging audit - run the hypothesis-driven debugging audit against the exact final SHA and real `LINEAR_PERPETUAL` stack, covering admitted-unknown, exporter restart, PG lag and blocked WebSocket traces; require `APPROVE` and no unexplained timing/state transition in `<attemptDir>/final-f3-debugging-audit.md`.
- [ ] F4. Real QA replay - re-run Tasks 15-17 in numeric order with fresh run IDs, verify six W4 manifests, W3 status contracts, W5 fault outcomes, funds zero and cleanup; write `<attemptDir>/final-f4-real-qa.md` with links/hashes of raw artifacts.
- [ ] F5. Scope/provenance/release audit - prove baseline ancestry, additive migration order, clean declared files, remote push presence, protected main-worktree AD/index blob identity, no wallet/forbidden matcher/P6 scope and exact docs/status claims; write `<attemptDir>/final-f5-release-audit.md` with `APPROVE`.

## Commit strategy
- Use a task-owned worktree from exact baseline and never stage from the protected main worktree.
- One logical change per task commit; implementation and direct tests remain together. Conventional Commits match current history.
- Before each commit: run its targeted JDK 25 gate, `git diff --check`, inspect `git diff --staged --stat` and full staged diff, then push as required by `AGENTS.md`.
- Never stage broad directories, unrelated `.omo`, local logs/data/runtime artifacts, or `WebSocketRuntimeHints.java`; no WIP commits or amend/rewrite after push.
- If a later task finds an earlier defect, create a new atomic fix commit scoped to the violated task and refresh all SHA-bound reviews.
- Final commit footer: `Plan: .omo/plans/w3-w5-production-closure.md`.

## Success criteria
- All 18 implementation tasks and F1-F5 approve at the exact final pushed SHA with evidence.
- Wire is schema v2/default/1 only; source epoch remains in sourceId; W1/W2 single-book, paired snapshot-only and sticky divergence invariants remain intact.
- Same key/same fingerprint returns original; changed fingerprint conflicts; outside retention is explicit unknown; actual required export sequence drives read-your-write.
- Public batches are bounded, ordered, non-atomic and one-round-trip; fixed Aeron capacity returns immediate typed admission/unknown outcomes without request tasks/retries.
- Lifecycle work is Core-selected and bounded; all six line manifests and every perpetual/delivery/option financial row conserve user/maker/treasury funds exactly.
- PostgreSQL is projection/audit only with contiguous transactional watermark; ordinary reads are bounded projection-only; exporter and WebSocket restart/fault behavior is observable and at-least-once.
- Exactly seven matching fixtures are corrected without production fallback; real stack is one `LINEAR_PERPETUAL`, maker last, wallet absent, ownership cleanup complete.
- Protected main-worktree state is exact; atomic commits are pushed; docs truthfully exclude hot split, cross-line state, P6 network/disk/24h/capacity work.
