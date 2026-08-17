# W3 Wire, Idempotency, Batch, and Capacity Closure

## TL;DR
> Summary:      Close W3 on baseline `7e78e04ae4dac16d364117392f960a65a4f4db2d` by versioning the default-route wire, detecting same-command/different-payload conflicts, making external order identity restart-stable, replacing synchronous batch loops with bounded Core batches, and replacing blocking/retrying Aeron calls with typed outcomes and fixed command/query capacity. Ordinary order reads move to the PostgreSQL projection with bounded read-your-write; only command-result, preflight, and lifecycle/admin control queries retain reserved read-only Aeron capacity.
> Deliverables:
> - Protocol schema v2 with explicit `coreShardId=default` / `routeVersion=1` on commands, responses, and export events
> - Canonical SHA-256 command fingerprint persisted with the bounded idempotency result window in fail-closed Core snapshot v7
> - Restart-stable external order IDs and explicit idempotency keys without a source registry v2
> - Bounded place/cancel/amend batch commands with ordered per-item results in one Aeron round trip
> - Typed admission/result-unknown/command-result APIs backed by one fixed Aeron agent, fixed mailboxes, one egress dispatcher, and reserved query capacity
> - Projection-backed external order reads with bounded `minCoreSequence` waiting
> - JDK 25 module gates, six-product-line financial matrix, and real SPOT provider/Kafka/PostgreSQL/WebSocket QA with cleanup evidence
> Effort:       XL
> Risk:         High - wire/snapshot incompatibility, idempotency, asynchronous matching, funds, and public API failure semantics intersect.

## Scope
### Must have
- Bind execution to exact baseline `7e78e04ae4dac16d364117392f960a65a4f4db2d`; preserve unrelated dirty/untracked files and never stage or modify `surprising-gateway/src/main/java/com/surprising/websocket/provider/WebSocketRuntimeHints.java`.
- Preserve one logical three-member Core per `ProductLine`; only route `default/v1` exists. Encode it explicitly and reject every other shard/version before command dispatch.
- Keep the current source-epoch-in-`sourceId` construction. Do not add source registry v2, leases, database state, or a second source-sequence authority.
- Define a canonical SHA-256 fingerprint over immutable command intent: schema version, message type, product line, shard ID, route version, command source, user ID, and exact canonical payload bytes. Exclude `sourceId`, `sourceSequence`, submission time, correlation ID, and transport position so a legitimate retry has the same fingerprint.
- For a retained `commandId`: same fingerprint returns the original stored decision; different fingerprint returns `IDEMPOTENCY_CONFLICT` without source-watermark, matcher, funds, state, outbox, or WebSocket changes.
- Preserve the bounded result window. A missing result is typed `RESULT_UNKNOWN`; it is never translated to rejection, success, or permission to mint a new command ID.
- Require nonblank `clientOrderId` for external place requests. Derive the positive 63-bit `orderId` deterministically from `(productLine,userId,clientOrderId)` using the canonical SHA-256 utility; let the existing Core order/client-order indexes fail closed on the improbable different-identity collision. Internal trigger/lifecycle order identity is unchanged.
- Give amend and batch operations explicit client request/idempotency keys so legitimate later amendments do not reuse a target-only command ID. Derive amend replacement IDs before transport admission and return them even when the outcome is unknown or the client is backpressured.
- Preserve existing public batch limits: place 20, amend 20, cancel 50. Require one user and one product line per external batch; reject mixed-user, empty, oversized, duplicate-key-with-different-payload, truncated, trailing-byte, or oversized encoded batches before submission/mutation.
- Preserve existing non-atomic batch behavior: process items deterministically in input order, produce exactly one result per input, allow predictable per-item rejection without rolling back prior applied items, and treat any post-matcher invariant failure as the existing sticky fatal divergence. Never claim exchange-core's barrier-only batch helper means all items succeeded.
- Replace provider hot-path executor submission, slot spin/park, synchronous offer retry, and per-call egress polling with one owner agent per `AeronClientPool`, one egress dispatcher, explicit fixed capacities, and untimed single-attempt mailbox/offer admission.
- Configure exact defaults: 4 command sessions, 1 reserved query session, command mailbox 256, query mailbox 64, maximum 64 in-flight correlations per command session, maximum 32 in-flight correlations on the reserved query session, and egress fragment limit 32. Validate all capacities as positive at startup; do not derive or grow them from load.
- Return typed `Completed`, `ResultUnknown`, and `NotAccepted` outcomes. `NotAccepted` distinguishes `CLIENT_BACKPRESSURED`, `NOT_CONNECTED`, `ADMIN_ACTION`, `CLOSED`, `MAX_POSITION_EXCEEDED`, and unknown raw Aeron codes. A positive Aeron offer means transport admission only; timeout/session loss after it is `ResultUnknown(commandId)`.
- Use the command mailbox only for mutations. Reserve the query mailbox/session for `COMMAND_RESULT_QUERY`, `ORDER_PREFLIGHT_QUERY`, and explicitly enumerated lifecycle/admin control queries. A full query mailbox must not consume command capacity.
- Route ordinary external order get/by-client/open/history reads through PostgreSQL projection/cache. Support `limit`, cursor, max encoded bytes, and optional `minCoreSequence` with a configured bounded wait; timeout returns a typed projection-lag response and does not fall back to Core.
- Preserve WebSocket at-least-once delivery and event-ID/export-sequence dedupe; do not design distributed exactly-once delivery.
- Verify real one-product-line provider/Kafka/PostgreSQL/WebSocket behavior with SPOT while wallet remains stopped, then run deterministic all-six-line financial fixtures, including CROSS and ISOLATED for every derivative line where applicable.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- No second executable order book, Core FIFO/price tree, matcher replay, clean-start rebuild, per-order restore, retry/resubmit after matcher failure, or replacement for paired `ME0/RE0` snapshot-only restore.
- No hot-symbol Core, non-default shard, route migration, dual write, cross-Core cancel, cross-line state, shared CROSS balance, or more than one `ProductLine` per logical Core.
- No source registry v2, database-backed source lease, or change to source epoch encoded in `sourceId`.
- No wallet startup, JDBC/Kafka/Redis/HTTP call in synchronous trading adjudication, or projection data driving funds, risk, matching, liquidation, settlement, or recovery.
- No unbounded queue/map/retry/scan, timed queue put, HTTP-worker spin/park, hidden reconnect retry of an admitted command, or `CompletableFuture.supplyAsync` per request.
- No automatic retry of an entire or partial batch after `ResultUnknown`; callers query the original batch `commandId`.
- No batch atomicity claim, no interpretation of exchange-core `submitCommandsSync` as per-item success, and no success inferred from a completion barrier.
- No fallback from projection reads to Core and no ordinary order query in reserved Aeron capacity.
- No backward-compatible v1 wire decoder or v6 Core snapshot reader. The product is not launched: schema v2/snapshot v7 requires a fresh Cluster and fails closed on old bytes.
- No modification, deletion, staging, or commit of the protected Gateway path or unrelated existing `.omo` artifacts.
- No P6 production network/disk certification, 24-hour soak, TPS declaration, or hot-shard decision.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD with JUnit 5, AssertJ, Mockito, Maven Surefire/Failsafe, Testcontainers for real PostgreSQL/Kafka integration, and shell-driven real provider/Gateway/WebSocket QA on JDK 25.
- QA policy: every task has agent-executed scenarios; every behavior-changing task begins by recording the named baseline test failure before production edits, then records the same exact test passing after the change.
- Evidence: `<attemptDir>/task-<N>-<slug>.<ext>` — under ulw-loop, `<attemptDir>` is the `currentAttemptDir` from `omo ulw-loop status --json` (`.omo/evidence/ulw/<session>/<goalId>/a<attempt>`); outside ulw-loop use `.omo/evidence/`
- Common shell prefix: `cd "$W3_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH"` where `W3_WORKTREE` is a task-owned worktree created from exact baseline; record `git rev-parse HEAD`, `git status --short`, and the protected-path index/worktree hashes before work.

## Execution strategy
### Parallel execution waves
> Target 5-8 tasks per wave. <3 per wave (except final) = under-splitting.
> Extract shared dependencies as Wave-1 tasks to maximize parallelism.

Wave 1 (no dependencies):
- Task 1: version explicit default-route command/response/event wire
- Task 2: define typed command admission and command-result API contracts
- Task 3: define restart-stable external order/request identity
- Task 4: add bounded place/cancel/amend batch protocol codecs
- Task 5: add transactional projection watermark and bounded read contract

Wave 2 (after Wave 1):
- Task 6: enforce canonical fingerprint conflicts and snapshot v7; depends [1, 2]
- Task 7: replace pool executors/retries with fixed Aeron agent, egress dispatcher, and reserved query capacity; depends [1, 2]
- Task 8: execute deterministic Core order batches through existing async matcher APIs; depends [4, 6]
- Task 9: migrate provider single-command and command-result HTTP behavior; depends [2, 3, 6, 7]
- Task 10: replace provider batch loops with one-round-trip commands; depends [3, 4, 8, 9]
- Task 11: move ordinary order reads to projection and whitelist control queries; depends [5, 7, 9]
- Task 12: migrate every existing AeronClientPool caller and Kafka/export envelope to the single route/capacity contract; depends [1, 7]

Wave 3 (after Wave 2):
- Task 13: run affected-reactor and all-six-product-line deterministic financial/recovery matrix; depends [6, 8, 9, 10, 11, 12]
- Task 14: run real SPOT provider/Kafka/PostgreSQL/WebSocket/backpressure QA with cleanup; depends [10, 11, 12, 13]
- Task 15: synchronize documentation, remove superseded mechanisms, and seal commit/evidence ledger; depends [13, 14]

Critical path: Task 1 -> Task 6 -> Task 8 -> Task 10 -> Task 13 -> Task 14 -> Task 15

### Dependency matrix
| Task | Depends on | Blocks | Can parallelize with |
|------|------------|--------|----------------------|
| 1    | none       | 6, 7, 12 | 2, 3, 4, 5 |
| 2    | none       | 6, 7, 9 | 1, 3, 4, 5 |
| 3    | none       | 9, 10 | 1, 2, 4, 5 |
| 4    | none       | 8, 10 | 1, 2, 3, 5 |
| 5    | none       | 11 | 1, 2, 3, 4 |
| 6    | 1, 2       | 8, 9, 13 | 7, 12 |
| 7    | 1, 2       | 9, 11, 12 | 6, 8 |
| 8    | 4, 6       | 10, 13 | 7, 9, 11, 12 |
| 9    | 2, 3, 6, 7 | 10, 11, 13 | 8, 12 |
| 10   | 3, 4, 8, 9 | 13, 14 | 11, 12 |
| 11   | 5, 7, 9    | 13, 14 | 8, 10, 12 |
| 12   | 1, 7       | 13, 14 | 6, 8, 9, 10, 11 |
| 13   | 6, 8, 9, 10, 11, 12 | 14, 15 | none |
| 14   | 10, 11, 12, 13 | 15 | none |
| 15   | 13, 14     | F1-F4 | none |

## Todos
> Implementation + Test = ONE task. Never separate.
> Every task MUST have: References + Acceptance Criteria + QA Scenarios + Commit.

- [ ] 1. Version explicit default-route wire semantics

  What to do: First add failing protocol characterizations proving the current 76-byte v1 header has only reserved route bytes. Change `CoreProtocol.SCHEMA_VERSION` to 2 while retaining the fixed 76-byte header: encode shard code `0` (`coreShardId=default`) in the existing reserved byte at offset 11 and unsigned route version `1` in the existing reserved short at offsets 14-15. Add one canonical protocol value object/constant for `default/v1`; extend `CoreMessageHeader` factories and response/export derivation to carry it. Decode only schema v2 and only `default/v1`; reject v1, unknown shard code, route version 0/2, malformed header length, truncation, trailing bytes, and oversized payload before dispatch. Update all direct header constructor sites and export event round trips; do not alter `sourceId/sourceSequence` fields or header length.
  Must NOT do: Do not add a route table, non-default route, schema dual-read, second Core, registry v2, or change snapshot/matcher routing in this task.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [6, 7, 12] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreProtocol.java:6-12` - current v1/76-byte constants and response framing.
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java:14-40` - reserved route bytes and exact current field order.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageHeader.java:7-18` - header model to extend.
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageHeader.java:40-78` - factories that must preserve route on response/export.
  - Test:     `surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreMessageCodecTest.java` - binary round-trip/malformed-wire style.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:63-74` - snapshot already encodes default shard and route v1; do not duplicate it.
  - External: `https://aeron.io/docs/aeron/publications-subscriptions/` - Aeron payload is application-defined; local schema must reject unsupported bytes explicitly.

  Acceptance criteria (agent-executable only):
  - [ ] Before production edits, `CoreMessageCodecTest#rejectsImplicitOrUnknownRoute` fails on baseline and its failure transcript is captured; after edits it passes.
  - [ ] `CoreMessageCodecTest` asserts exact offsets, unchanged 76-byte header, schema 2, default shard code 0, route 1, response/export route preservation, and rejection of every malformed/unknown case.
  - [ ] `rg -n 'SCHEMA_VERSION = 1|new CoreMessageHeader\(' surprising-aeron-core --glob '*.java'` finds no stale production v1 literal or constructor missing the route contract.
  - [ ] `mvn -pl :surprising-aeron-protocol -am -Dtest=CoreMessageCodecTest,CoreExportCodecTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: default/v1 command and export event round-trip
    Tool:     bash
    Steps:    Run the common JDK 25 prefix, then `mvn -pl :surprising-aeron-protocol -am -Dtest=CoreMessageCodecTest#roundTripsExplicitDefaultRoute,CoreExportCodecTest#roundTripsEventBatchAckAndStatus -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Both tests pass; decoded command, response, and export headers report exactly `default/1` and preserve all source fields.
    Evidence: <attemptDir>/task-1-route-wire.txt

  Scenario: unsupported route fails before dispatch
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-protocol -am -Dtest=CoreMessageCodecTest#rejectsImplicitOrUnknownRoute -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Mutated v1, shard-code 1, route 0, and route 2 frames each throw `ProtocolException`; no decoded `CoreMessage` is returned.
    Evidence: <attemptDir>/task-1-route-wire-error.txt
  ```

  Commit: YES | Message: `feat(aeron-protocol): version explicit default route wire` | Files: [surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreProtocol.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreRoute.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageHeader.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java, surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreMessageCodecTest.java, surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/CoreExportCodecTest.java]

- [ ] 2. Define typed admission, result-unknown, and command-result contracts

  What to do: Add a sealed client result algebra with `Completed(commandId, CoreResponse)`, `ResultUnknown(commandId, reason)`, and `NotAccepted(commandId, AdmissionFailure)`. Make `AdmissionFailure` enumerate `CLIENT_BACKPRESSURED`, `NOT_CONNECTED`, `ADMIN_ACTION`, `CLOSED`, `MAX_POSITION_EXCEEDED`, and `UNKNOWN(rawCode)`. Add a typed command-result query value with `Known`, `Pending`, and `Unknown`; map Core `MATCHING_PENDING` to `Pending` and missing `commandId` to `Unknown`, never a business rejection. Keep `ResultUnknownException` only as a deprecated compatibility edge for direct tools until Task 9 removes provider callers; new pool/provider code must not throw it for normal control flow.
  Must NOT do: Do not implement queues/threads here, map positive `offer` to execution success, mint a new command ID, or retry.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [6, 7, 9] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - API/Type: `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/ResultUnknownException.java:5-16` - current untyped exception to supersede at provider boundaries.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResponse.java:3-16` - terminal response payload.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResultCode.java:5-67` - current result codes including `MATCHING_PENDING`.
  - Pattern:  `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:242-245` - current raw command-result query.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:237-250` - Core lookup semantics.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:55-79` - known/unknown query characterization.
  - External: `https://github.com/aeron-io/aeron/blob/1.52.2/aeron-client/src/main/java/io/aeron/Publication.java` - exact negative offer codes.

  Acceptance criteria (agent-executable only):
  - [ ] A compile-first test fails before types exist, then proves exhaustive pattern matching over all outcomes and unknown raw-code preservation.
  - [ ] No typed outcome exposes a nullable `commandId`; `ResultUnknown` and every admission failure preserve the original ID.
  - [ ] Missing `COMMAND_RESULT_QUERY` maps to typed `Unknown`, not `Completed(REJECTED)`.
  - [ ] `mvn -pl :surprising-aeron-client -am -Dtest=CoreCommandOutcomeTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: terminal, pending, and unknown results are distinct
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-client -am -Dtest=CoreCommandOutcomeTest#classifiesKnownPendingAndUnknownWithoutThrowing -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Three distinct sealed variants are asserted and all carry the original command ID.
    Evidence: <attemptDir>/task-2-typed-results.txt

  Scenario: unknown Aeron code is not success
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-client -am -Dtest=CoreCommandOutcomeTest#preservesUnknownAdmissionCode -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Raw `-77` becomes `NotAccepted(UNKNOWN(-77))`; it is neither completed nor result-unknown.
    Evidence: <attemptDir>/task-2-typed-results-error.txt
  ```

  Commit: YES | Message: `feat(aeron-client): type command admission and unknown results` | Files: [surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/CoreCommandOutcome.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AdmissionFailure.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/CommandResultLookup.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/ResultUnknownException.java, surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/CoreCommandOutcomeTest.java]

- [ ] 3. Make external order and request identity restart-stable

  What to do: Add one shared deterministic identity utility in order-provider. Require normalized nonblank `clientOrderId` for external place. Derive `orderId` from SHA-256 of the length-prefixed tuple `(identityVersion=1,productLine,userId,clientOrderId)`, mask to positive 63 bits, reject zero, and rely on existing Core `orderId` plus `(userId,clientOrderId)` indexes to reject any different-identity collision. Derive place `commandId` only from `(productLine,userId,clientOrderId)`, not mutable order fields; Task 6's fingerprint detects changed payload. Add `clientRequestId` to amend requests and `clientBatchId` to all three batch request records; use them for command IDs and derive amend replacement IDs from `(productLine,userId,originalOrderId,clientRequestId)`. Keep internal trigger/lifecycle IDs and source IDs unchanged.
  Must NOT do: Do not use wall clock, process-local counters, database/Redis sequences, random UUIDs, a new registry, or transport positions for external order identity.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [9, 10] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderIdGenerator.java:8-37` - process-local time/node generator to remove from external placement.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java:57-100` - place/amend IDs currently allocated before command construction.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java:212-245` - current command ID includes mutable intent, preventing conflict detection.
  - API/Type: `surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/BatchPlaceOrderRequest.java` - batch API to receive `clientBatchId`.
  - API/Type: `surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/BatchCancelOrdersRequest.java` - cancel batch identity.
  - API/Type: `surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/BatchAmendOrdersRequest.java` - amend batch identity.
  - Test:     `surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/AeronOrderCommandServiceTest.java:131-158` - current stable-same-intent test to replace with stable-key/conflict behavior.
  - External: `https://github.com/exchange-core/exchange-core/blob/master/src/main/java/exchange/core2/core/ExchangeApi.java` - exchange-core accepts caller-supplied `orderId` and preserves it through results.

  Acceptance criteria (agent-executable only):
  - [ ] Failing-first tests prove baseline changes ID across a simulated provider restart for no-client-ID requests; final tests reject missing IDs and return the same order/replacement IDs from two fresh service instances.
  - [ ] Same client key with changed price/quantity produces the same command ID and order ID, allowing Task 6 to reject by fingerprint.
  - [ ] Distinct product line, user, client order ID, amend request ID, or batch ID produces a distinct deterministic identity in the test matrix.
  - [ ] `rg -n 'orderIds\.next\(\)|System\.currentTimeMillis\(\)' surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java` returns no matches.
  - [ ] `mvn -pl :surprising-order-provider -am -Dtest=StableOrderIdentityTest,AeronOrderCommandServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: identity survives provider reconstruction
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-order-provider -am -Dtest=StableOrderIdentityTest#sameExternalIdentitySurvivesFreshServiceInstance -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Place command ID, order ID, amend command ID, and replacement order ID are byte-for-byte equal across fresh instances.
    Evidence: <attemptDir>/task-3-stable-order-identity.txt

  Scenario: missing or colliding identity fails closed
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-order-provider -am -Dtest=StableOrderIdentityTest#rejectsMissingClientKeysAndDifferentIdentityCollision -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Missing client keys fail before Aeron interaction; the injected hash collision is rejected and no second order is submitted.
    Evidence: <attemptDir>/task-3-stable-order-identity-error.txt
  ```

  Commit: YES | Message: `feat(order): stabilize external command and order identity` | Files: [surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/AmendOrderRequest.java, surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/BatchPlaceOrderRequest.java, surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/BatchCancelOrdersRequest.java, surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/BatchAmendOrdersRequest.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/StableOrderIdentity.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/StableOrderIdentityTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/AeronOrderCommandServiceTest.java]

- [ ] 4. Add bounded place/cancel/amend batch wire codecs

  What to do: Add command wire codes 70/71/72 for `BATCH_PLACE_ORDERS`, `BATCH_CANCEL_ORDERS`, and `BATCH_AMEND_ORDERS`. Add immutable protocol records/codecs using existing single-item canonical codecs and length-prefixed item frames. Encode version 1, batch ID UUID, one positive user ID, item count, then ordered item lengths/bytes. Cap place/amend at 20, cancel at 50, each item at the existing command payload limit, and the aggregate at `CoreMessageCodec.MAX_PAYLOAD_LENGTH`. Add an ordered batch-result codec carrying index, command/result status, result code, stable order/replacement IDs, and optional bounded `CoreCommandResultView`; require exactly one result per input index with no gaps/duplicates.
  Must NOT do: Do not add JSON/Java serialization, mixed-user batches, unbounded lists, all-or-nothing claims, or call exchange-core in protocol code.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [8, 10] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageType.java:5-36` - stable wire-code registry.
  - Pattern:  `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingCommandCodec.java` - canonical single place/cancel/amend binary format to compose, not duplicate.
  - API/Type: `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreCommandResultCodec.java` - authoritative changed-order/execution result format.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:101-113` - place limit 20.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:150-162` - amend limit 20.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:223-235` - cancel limit 50.
  - External: `https://github.com/exchange-core/exchange-core/blob/master/src/main/java/exchange/core2/core/ExchangeApi.java` - upstream synchronous batch helper is only a barrier; local wire must carry per-item results.

  Acceptance criteria (agent-executable only):
  - [ ] Baseline compile/test fails before batch types exist; final round-trip preserves exact ordered single-item payload bytes and result indexes.
  - [ ] Decoder rejects version 0/2, empty, 21-place, 21-amend, 51-cancel, mixed user, negative/oversized lengths, truncation, trailing bytes, duplicate/missing result indexes, and aggregate overflow.
  - [ ] Wire codes 70/71/72 are unique and never reused by response/query/event kinds.
  - [ ] `mvn -pl :surprising-aeron-protocol -am -Dtest=TradingOrderBatchCodecTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: maximum legal batches round-trip in input order
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-protocol -am -Dtest=TradingOrderBatchCodecTest#roundTripsMaximumPlaceCancelAndAmendBatches -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Counts are exactly 20/50/20; every decoded item and result index equals the corresponding input.
    Evidence: <attemptDir>/task-4-batch-codec.txt

  Scenario: malformed and oversized batches fail closed
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-protocol -am -Dtest=TradingOrderBatchCodecTest#rejectsMalformedMixedUserAndOversizedBatches -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Every invalid frame throws `ProtocolException` or constructor `IllegalArgumentException` before a command object is exposed.
    Evidence: <attemptDir>/task-4-batch-codec-error.txt
  ```

  Commit: YES | Message: `feat(aeron-protocol): add bounded order batch wire` | Files: [surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageType.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/PlaceOrderBatchCommand.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CancelOrderBatchCommand.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/AmendOrderBatchCommand.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreOrderBatchItemResult.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreOrderBatchResult.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingOrderBatchCodec.java, surprising-aeron-core/surprising-aeron-protocol/src/test/java/com/surprising/aeron/protocol/TradingOrderBatchCodecTest.java]

- [ ] 5. Add transactional projection watermark and bounded order-read contract

  What to do: Extend the PostgreSQL projection transaction so each committed `(product_line,export_sequence)` event advances a single contiguous `core_projection_watermark` row in the same transaction after all order/trade/funds facts succeed. Duplicate sequence is idempotent; gap/reorder rejects and rolls back both facts and watermark. Add order-provider projection query types for by-ID, by-client-ID, open orders, and history with `limit<=1000`, stable `(updated_at,order_id)` cursor, maximum encoded response bytes, and optional `minCoreSequence`. Implement a bounded wait using repeated read-only transactions until the configured timeout, then return typed `PROJECTION_LAG` with observed/required sequence; never query Core.
  Must NOT do: Do not make PG authoritative, update Core from projection, use `SELECT FOR UPDATE` in trading, wait without a deadline, or fall back to Aeron ordinary order queries.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [11] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/JdbcCoreEventProjector.java:18-23` - JDBC transaction boundary.
  - Pattern:  `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/JdbcCoreEventProjector.java:103-136` - event/fact insertion that must share watermark commit.
  - Pattern:  `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/KafkaProjectionWorker.java:30-43` - project-then-commit consumer behavior.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:265-274` - external reads currently delegate to Core.
  - Test:     `surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/JdbcCoreEventProjectorTest.java` - projection idempotency transaction style.
  - Test:     `surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/OrderServiceTest.java:111-122` - current Core-read characterization to invert in Task 11.

  Acceptance criteria (agent-executable only):
  - [ ] Failing-first PostgreSQL tests prove baseline has no coupled watermark; final Testcontainers tests prove atomic advancement, rollback, duplicate replay, gap rejection, and restart persistence.
  - [ ] Query tests prove stable pagination, product/user isolation, `limit`, max bytes, exact watermark success, bounded lag timeout, and no Aeron interaction.
  - [ ] `rg -n 'SELECT FOR UPDATE|clients\.query' surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/projection` returns no matches.
  - [ ] `mvn -pl :surprising-aeron-exporter,:surprising-order-provider -am -Dtest=JdbcCoreEventProjectorPostgresTest,ProjectedOrderQueryRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25 with Docker available.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: facts and watermark commit atomically
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-exporter -am -Dtest=JdbcCoreEventProjectorPostgresTest#commitsFactsAndContiguousWatermarkAtomically -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Sequence N facts and watermark N are visible after commit; duplicate N changes no row counts.
    Evidence: <attemptDir>/task-5-projection-watermark.txt

  Scenario: projection gap/lag never falls back to Core
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-order-provider -am -Dtest=ProjectedOrderQueryRepositoryTest#timesOutAtBoundWhenWatermarkLagsWithoutAeronFallback -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Returns `PROJECTION_LAG` with observed/required sequence within the configured bound; Aeron mock has zero interactions.
    Evidence: <attemptDir>/task-5-projection-watermark-error.txt
  ```

  Commit: YES | Message: `feat(projection): add contiguous order read watermark` | Files: [surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/JdbcCoreEventProjector.java, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/JdbcCoreEventProjectorPostgresTest.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/projection/ProjectedOrderQueryRepository.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/projection/ProjectionReadResult.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/projection/ProjectedOrderQueryRepositoryTest.java]

- [ ] 6. Enforce canonical fingerprint conflict semantics and snapshot v7

  What to do: Add one protocol-owned `CommandFingerprint` SHA-256 implementation over the immutable fields defined in Scope. Compute it inside Core from the decoded message, never trust a caller-supplied digest. Extend `StoredResult` with the 32-byte fingerprint and bounded response bytes; compare fingerprint before returning duplicate. Add `IDEMPOTENCY_CONFLICT` result code and return it without advancing source sequence or any state. Upgrade Core snapshot to v7, preserving paired matcher bytes and `fromSnapshotOnly`: each result entry is variable length `[commandId 16,status 4,resultCode 4,applied 8,stateHash 8,fingerprint 32,responseLength 4,response]`. Bound each response at 1 MiB and the entire result section at 32 MiB; evict oldest complete entries before inserting until both count 128 and byte budget fit. If one response cannot fit, retain fingerprint/status/hash with zero response bytes and make command-result lookup typed `Unknown`, never fabricate an empty success payload. Reject v6 rather than dual-read.
  Must NOT do: Do not include volatile transport fields in the fingerprint, silently accept a changed payload, modify matcher snapshot format, replay/resubmit matcher orders, or create an unbounded tombstone/result store.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [8, 9, 13] | Blocked by: [1, 2]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:533-550` - duplicate check currently ignores payload.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:651-664` - count-only eviction and stored result insertion.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:2694-2712` - stored result model.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:23-32` - v6 fixed result layout.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:81-88` - snapshot currently omits response bytes and fingerprint.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:197-270` - strict decode/paired restore pattern.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:40-94` - duplicate/result-window behavior.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:110-118` - stable command identity and bounded explicit unknown contract.

  Acceptance criteria (agent-executable only):
  - [ ] Same ID/same canonical payload across changed source epoch/sequence/time/correlation returns the original decision once; same ID with any immutable intent field or payload byte changed returns `IDEMPOTENCY_CONFLICT` with identical funds/book/export/source watermark.
  - [ ] Snapshot v7 round-trip preserves fingerprint and retained response bytes; v6, malformed digest/length, duplicate IDs, oversize result section, corruption, and checksum mismatch fail closed before matcher restore/admission.
  - [ ] Paired snapshot still imports exactly ME0/RE0 and starts only through `InitialStateConfiguration.fromSnapshotOnly`; no replay/rebuild/resubmit source appears.
  - [ ] `mvn -pl :surprising-aeron-service -am -Dtest=CoreProbeStateTest,CoreStateSnapshotCodecTest,SurprisingClusteredServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: canonical retry returns original decision after snapshot restore
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-service -am -Dtest=CoreProbeStateTest#returnsOriginalResultForCanonicalRetryAcrossSourceEpochAndSnapshot -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: One mutation/export occurs; restored Core returns the original status, hash, and response payload for the same command intent.
    Evidence: <attemptDir>/task-6-idempotency-fingerprint.txt

  Scenario: same command ID with changed payload is conflict without mutation
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-service -am -Dtest=CoreProbeStateTest#rejectsSameCommandIdWithDifferentCanonicalPayload -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: `IDEMPOTENCY_CONFLICT`; applied count, source watermark, funds, native book hash, and pending export count are unchanged.
    Evidence: <attemptDir>/task-6-idempotency-fingerprint-error.txt
  ```

  Commit: YES | Message: `feat(aeron-core): reject idempotency payload conflicts` | Files: [surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CommandFingerprint.java, surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResultCode.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreStateSnapshotCodecTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/SurprisingClusteredServiceTest.java]

- [ ] 7. Replace pool retries with one fixed Aeron agent and reserved query capacity

  What to do: Refactor `AeronClientPool` so HTTP/provider threads only perform untimed `offer` into exact fixed mailboxes. One named owner agent/thread owns all AeronCluster sessions, connection progress, exactly-one ingress `offer` per admitted request, egress polling, correlation maps, deadlines, and completion. Keep four user-hashed command sessions and one separate query session. Enforce mailbox/in-flight/fragment defaults from Scope through explicit constructor/config fields. On full mailbox/in-flight capacity return `NotAccepted(CLIENT_BACKPRESSURED)` immediately. Map each negative Aeron offer exactly; do not advance a command lane's source sequence unless offer is positive. After a positive offer, timeout/disconnect yields `ResultUnknown`; never retry/offer again. Drain command work before at most one bounded query duty slice so query floods cannot consume command mailboxes; still give queries progress. Close rejects new work, completes pending outcomes deterministically, joins the single agent within the configured bound, and closes shared MediaDriver once.
  Must NOT do: Do not retain `commandExecutor`, `connectionExecutor`, `supplyAsync`, `MAX_SUBMIT_ATTEMPTS`, slot spin/park acquisition, per-call `pollEgress`, unbounded response maps, or ordinary order reads on query capacity.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [9, 11, 12] | Blocked by: [1, 2]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:30-55` - current pool/executor state.
  - Pattern:  `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:132-175` - derived queue and per-command `supplyAsync` to remove.
  - Pattern:  `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:225-245` - queries currently share arbitrary slots.
  - Pattern:  `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:337-398` - blocking slot spin/park to remove.
  - Pattern:  `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:504-523` - hidden three-attempt retry to remove.
  - Pattern:  `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java:148-188` - synchronous offer/egress loops and one-shot offer seam.
  - Test:     `surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/AeronClientPoolTest.java:64-81` - current direct connection backpressure test.
  - Test:     `surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/AeronClientPoolTest.java:125-133` - current derived command queue test to replace.
  - External: `https://aeron.io/docs/aeron/publications-subscriptions/` - positive/negative offer semantics and application-owned retry.
  - External: `https://github.com/aeron-io/agrona/blob/master/agrona/src/main/java/org/agrona/concurrent/Agent.java` - owner duty-cycle contract.

  Acceptance criteria (agent-executable only):
  - [ ] Deterministic fake-Aeron tests prove exactly one owner thread invokes connect/offer/poll/close and exactly one offer occurs per request.
  - [ ] Saturating 256 command entries does not block the caller and does not consume any of 64 query slots; saturating query capacity does not reduce command admission.
  - [ ] Four command lanes remain stable by user across concurrency; one source epoch remains encoded in each lane's `sourceId`; rejected offers do not advance sequence.
  - [ ] Positive offer then timeout is `ResultUnknown`; each named negative and unknown raw code is `NotAccepted`; none triggers reconnect/resubmit inside the call.
  - [ ] `rg -n 'MAX_SUBMIT_ATTEMPTS|supplyAsync|commandExecutor|connectionExecutor|parkNanos|while \(true\).*offer' surprising-aeron-core/surprising-aeron-client/src/main/java` returns no hot-path matches.
  - [ ] `mvn -pl :surprising-aeron-client -am -Dtest=AeronClientAgentTest,AeronClientPoolTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: command and reserved query capacities are independent
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-client -am -Dtest=AeronClientAgentTest#isolatesExactCommandAndQueryCapacities -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: The 257th command and 65th query are immediately backpressured independently; admitted requests complete after the fake agent drains them.
    Evidence: <attemptDir>/task-7-aeron-capacity.txt

  Scenario: positive offer timeout is unknown and never resubmitted
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-client -am -Dtest=AeronClientAgentTest#doesNotRetryAfterPositiveOfferOrNamedFailure -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Fake publication records one offer; timeout returns `ResultUnknown`; each negative code returns the matching `NotAccepted` variant with no sequence advance.
    Evidence: <attemptDir>/task-7-aeron-capacity-error.txt
  ```

  Commit: YES | Message: `refactor(aeron-client): bound agent egress and query capacity` | Files: [surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientAgent.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientCapacity.java, surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java, surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/AeronClientAgentTest.java, surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/AeronClientPoolTest.java]

- [ ] 8. Execute deterministic Core order batches through existing async matcher APIs

  What to do: Add Core dispatch for the three batch message types. Validate envelope, fingerprint, route, one user, limits, item identity, instrument/fee/risk, worst-case response/outbox capacity, and all predictable item errors before each affected matcher submission. Process input order through one bounded pending-batch continuation using existing `DeterministicExchangeCoreAdapter` structured async place/cancel APIs; collect each future result without owner-thread `join`, apply each accepted item exactly once, append one ordered aggregate Core response and one replicated command delta/export fact for the batch command, and complete only after all items have terminal per-item results. Predictable item rejection is recorded and later items continue; any accepted matcher result that cannot be applied is sticky fatal divergence. Snapshot remains forbidden while the batch is pending and never serializes/resubmits it.
  Must NOT do: Do not build another order book, call exchange-core `submitCommandsSync`, infer success from a barrier, parallelize the same symbol FIFO, rollback already applied successful items for a later predictable rejection, or replay pending work after restore.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [10, 13] | Blocked by: [4, 6]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:561-687` - matching-command dispatch and pre-mutation export capacity.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:800-836` - current pending matching registration/result.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1474-1509` - sticky divergence and terminal result application.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java` - existing structured async place/cancel/batch seams; extend, do not replace.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java` - timer-driven ordered completion and snapshot barrier.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/SurprisingClusteredServiceTest.java` - pending matching/timer/owner-thread tests.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java` - native book/funds/order behavior.
  - External: `https://github.com/exchange-core/exchange-core/blob/master/src/main/java/exchange/core2/core/ExchangeApi.java` - batch helper is a processing barrier, not aggregate success.

  Acceptance criteria (agent-executable only):
  - [ ] Place 20, amend 20, and cancel 50 complete in one Core ingress command each, preserving exact input/result order and one authoritative aggregate response.
  - [ ] Mixed success/rejection produces one item result per input; successful item funds/book/export effects occur once; rejected item effects are zero.
  - [ ] Duplicate same-fingerprint batch returns the original aggregate without matcher submission; changed payload conflicts before mutation.
  - [ ] Owner thread never calls `join/get`; snapshot rejects while batch pending; late/stale completion cannot cross the ordered completion fence.
  - [ ] `mvn -pl :surprising-aeron-service -am -Dtest=CoreOrderBatchStateTest,SurprisingClusteredServiceTest,CoreMatchingStateTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: bounded mixed-result batch preserves order and conservation
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-service -am -Dtest=CoreOrderBatchStateTest#executesMaximumMixedPlaceCancelAmendBatchesInInputOrder -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Exactly 20/50/20 item results; successful user/maker funds and native book match expected; rejected items have no state/export effect.
    Evidence: <attemptDir>/task-8-core-batches.txt

  Scenario: post-matcher batch divergence kills member without replay
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-service -am -Dtest=CoreOrderBatchStateTest#failsStickyAfterAcceptedMatcherResultCannotApply -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Fatal divergence escapes, readiness closes, snapshot is refused, and matcher submit count never increases from retry/rebuild/resubmit.
    Evidence: <attemptDir>/task-8-core-batches-error.txt
  ```

  Commit: YES | Message: `feat(aeron-core): execute bounded order batches` | Files: [surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java, surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreOrderBatchStateTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/SurprisingClusteredServiceTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java]

- [ ] 9. Migrate single-order provider and HTTP command-result semantics

  What to do: Change `OrderAeronGateway`/`AeronOrderCommandService` to submit typed outcomes asynchronously through Task 7 and block only at the Spring boundary up to the configured response deadline without occupying Aeron agent capacity. Completed applied/rejected responses retain current authoritative response behavior. `ResultUnknown` throws a dedicated API exception carrying command/order/replacement IDs and result URL; `NotAccepted` maps immediate backpressure to HTTP 429 and unavailable/closed/not-connected/admin/max-position to HTTP 503 with stable machine codes. Add `GET /api/.../orders/commands/{commandId}` using reserved query capacity: return 200 for terminal known, 202 for pending/unknown, and never 404/business rejection for absence. Require retries to reuse the same client key/command ID. Remove the cancel success fallback query at lines 145-149.
  Must NOT do: Do not generate a new ID, retry inside controller/service, translate timeout to conflict/rejection, or perform a success-path order query.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [10, 11, 13] | Blocked by: [2, 3, 6, 7]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java:24-40` - raw synchronous command and exception mapping.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java:57-109` - direct authoritative place/amend response.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java:141-149` - cancel fallback query to delete.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java:60-70` - current timeout/conflict conflation.
  - Test:     `surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/AeronOrderCommandServiceTest.java:111-129` - no-success-query assertion.
  - Pattern:  `surprising-aeron-core/README.md:72` - existing same-command-ID unknown-result guidance.

  Acceptance criteria (agent-executable only):
  - [ ] Controller tests prove applied, business rejected, immediate backpressure, unavailable, admitted timeout, known query, pending query, and unknown query have distinct status/body contracts with stable IDs.
  - [ ] Place/cancel/amend success performs one command round trip and zero follow-up query; unknown performs zero retry and returns the original IDs.
  - [ ] Command-result endpoint is the only ordinary HTTP surface invoking `COMMAND_RESULT_QUERY`.
  - [ ] `mvn -pl :surprising-order-provider -am -Dtest=OrderControllerTest,OrderAeronGatewayTest,AeronOrderCommandServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: admitted timeout returns stable result receipt
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-order-provider -am -Dtest=OrderControllerTest#returnsAcceptedReceiptForAdmittedUnknownResult -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: HTTP 202 body contains `RESULT_UNKNOWN`, original command ID, stable order/replacement ID, and command-result URL; Aeron offer count is one.
    Evidence: <attemptDir>/task-9-order-result-api.txt

  Scenario: immediate backpressure is not result unknown
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-order-provider -am -Dtest=OrderControllerTest#mapsImmediateBackpressureTo429WithoutRetry -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: HTTP 429 `CLIENT_BACKPRESSURED`; no result URL claim, no query, no retry, and stable prospective order identity remains in the body.
    Evidence: <attemptDir>/task-9-order-result-api-error.txt
  ```

  Commit: YES | Message: `feat(order): expose typed unknown command results` | Files: [surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/OrderCommandReceipt.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderCommandResultUnknownException.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/controller/OrderControllerTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/OrderAeronGatewayTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/AeronOrderCommandServiceTest.java]

- [ ] 10. Replace synchronous provider batch loops with one-round-trip commands

  What to do: Prepare/validate all items, stable identities, fee/instrument snapshots, and one-user constraint before sending one batch command. Replace each `for` loop that invokes single `place`, `amend`, or `cancel` with one Task 4 payload and one Task 9 typed submission. Decode exactly one ordered aggregate result into existing item response DTOs. Preserve per-item partial-success semantics. For `ResultUnknown`, return one batch receipt with batch command ID plus every prospective stable order/replacement ID; do not retry or issue per-item queries. For immediate admission failure, fail the whole not-admitted batch with no item submitted. Leave cancel-open/admin/lifecycle batching outside these three public endpoints unless they explicitly call the new bounded cancel batch without Core ordinary reads.
  Must NOT do: Do not loop over synchronous commands, create N futures/tasks, retry items, mix users, or claim atomic rollback.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [13, 14] | Blocked by: [3, 4, 8, 9]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:101-113` - place N-round-trip loop.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:150-162` - amend N-round-trip loop.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:223-235` - cancel N-round-trip loop.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java:73-81` - batch place HTTP surface.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java:104-110` - batch amend HTTP surface.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java:135-141` - batch cancel HTTP surface.
  - Test:     `surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/OrderServiceTest.java` - provider orchestration test style.

  Acceptance criteria (agent-executable only):
  - [ ] Maximum 20/50/20 requests each call `OrderAeronGateway` exactly once and preserve one ordered result per input.
  - [ ] Mixed-user and duplicate batch ID with changed payload fail before Aeron; a same-ID same-payload retry returns the original aggregate.
  - [ ] Result-unknown batch returns one receipt and no per-item retry/query; immediate backpressure submits zero items.
  - [ ] `rg -n 'for \(|\.stream\(\).*aeronOrders\.(place|cancel|replace)' surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java` shows no public place/amend/cancel batch command loop.
  - [ ] `mvn -pl :surprising-order-provider -am -Dtest=OrderBatchServiceTest,OrderControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: maximum public batches use one command round trip
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-order-provider -am -Dtest=OrderBatchServiceTest#usesOneRoundTripForMaximumPlaceCancelAndAmendBatches -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Gateway invocation count is three total, one per endpoint; result indexes and stable IDs match every input.
    Evidence: <attemptDir>/task-10-provider-batches.txt

  Scenario: unknown batch is not split or retried
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-order-provider -am -Dtest=OrderBatchServiceTest#returnsSingleUnknownReceiptWithoutItemRetry -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: One batch offer, zero single-command offers/queries, one 202 receipt containing all prospective IDs.
    Evidence: <attemptDir>/task-10-provider-batches-error.txt
  ```

  Commit: YES | Message: `feat(order): submit bounded batches in one round trip` | Files: [surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/AeronOrderCommandService.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/OrderBatchServiceTest.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/controller/OrderControllerTest.java]

- [ ] 11. Move ordinary order reads to projection and whitelist reserved Core queries

  What to do: Wire Task 5's repository into `OrderService` for get, by-client-ID, open orders, and history; accept optional `minCoreSequence` and return the projection sequence in responses. Remove direct Core order/open-order methods from the external read path. Add one exhaustive `CoreQueryClass` whitelist in the Aeron client: reserved direct reads are `COMMAND_RESULT_QUERY`, `ORDER_PREFLIGHT_QUERY`, state/hash probes used by lifecycle/admin control, and bounded lifecycle progress/work queries; explicitly reject `ORDER_STATE_QUERY`, `CLIENT_ORDER_STATE_QUERY`, and `USER_OPEN_ORDERS_QUERY` from provider external-read code. Keep cancel-open/admin selection bounded and projection-based, but the subsequent cancel mutation goes through Task 10.
  Must NOT do: Do not remove low-level Core diagnostic query support from tools, let public reads consume reserved capacity, or fall back to Core on projection lag/failure.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [13, 14] | Blocked by: [5, 7, 9]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java:43-61` - direct order/open-order queries to remove from external reads.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java:83-90` - preflight remains reserved Core query.
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java:238-274` - cancel-open/get/by-client flow.
  - Test:     `surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/OrderServiceTest.java:111-137` - current Aeron reads to invert.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:429-435` - projection/read-your-write contract.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:603-605` - owner boundary for queries/providers.

  Acceptance criteria (agent-executable only):
  - [ ] Every public order read test proves projection repository interaction and zero Aeron interaction, including lag/error cases.
  - [ ] Command-result, preflight, and each enumerated lifecycle/admin control query use reserved capacity and cannot enter command mailbox.
  - [ ] Public query limit/cursor/max-bytes/minCoreSequence validation is deterministic and product/user isolated.
  - [ ] `rg -n 'ORDER_STATE_QUERY|CLIENT_ORDER_STATE_QUERY|USER_OPEN_ORDERS_QUERY' surprising-trading/surprising-order-provider/src/main/java` finds no external read call; approved diagnostic/control uses are documented inline by enum whitelist, not comments at call sites.
  - [ ] `mvn -pl :surprising-order-provider,:surprising-aeron-client -am -Dtest=OrderServiceTest,CoreQueryClassTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0 on JDK 25.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: bounded read-your-write reaches projection watermark
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-order-provider -am -Dtest=OrderServiceTest#readsProjectionAtRequestedCoreSequenceWithoutAeron -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: Response carries projection sequence >= requested sequence, stable cursor, and zero Aeron calls.
    Evidence: <attemptDir>/task-11-projection-reads.txt

  Scenario: ordinary read cannot consume reserved query slot
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-client -am -Dtest=CoreQueryClassTest#rejectsOrdinaryOrderQueriesFromReservedCapacity -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: All three ordinary order query types are rejected locally; command-result/preflight/lifecycle controls remain admitted.
    Evidence: <attemptDir>/task-11-projection-reads-error.txt
  ```

  Commit: YES | Message: `feat(order): route ordinary reads through projection` | Files: [surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/CoreQueryClass.java, surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/CoreQueryClassTest.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/controller/OrderController.java, surprising-trading/surprising-order-provider/src/test/java/com/surprising/trading/order/service/OrderServiceTest.java]

- [ ] 12. Migrate all pool callers and Kafka/export envelopes to the unified contract

  What to do: Update every production `new AeronClientPool` caller (order, trigger, risk, matching-market-data, mark-price) to pass the same explicit `AeronClientCapacity` plus stable source identity/current random process epoch. Classify each call as command or reserved control query; convert command callers to typed outcomes and remove local retry loops/queues that duplicate pool behavior. Ensure `KafkaInputBridge`, exporter ACK/query paths, tools, response encoding, and Core export events use explicit default/v1 headers. Preserve Kafka source identity from `(topic,partition)` and offset-derived sequence. For fire-and-forget mark/lifecycle paths, queue-full/not-accepted must be explicit and bounded; result unknown must retain command ID for query/reconciliation.
  Must NOT do: Do not add a second pool implementation, alter Kafka offset idempotency, commit an offset for unknown result, add unbounded provider queues, or change product-line topic isolation.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [13, 14] | Blocked by: [1, 7]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java:24-28` - order pool construction.
  - Pattern:  `surprising-trading/surprising-trigger-provider/src/main/java/com/surprising/trading/trigger/service/TriggerOrderAeronGateway.java:30-43` - trigger pool/result handling.
  - Pattern:  `surprising-risk/surprising-risk-provider/src/main/java/com/surprising/risk/provider/service/RiskAeronGateway.java:23` - risk pool caller.
  - Pattern:  `surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/service/MatchingAeronGateway.java:22` - matching projection pool caller.
  - Pattern:  `surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceCorePublisher.java:41` - mark-price pool caller.
  - Pattern:  `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/KafkaInputBridge.java:27-43` - offset commit boundary; unknown must not commit.
  - Pattern:  `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ReliableCoreExporter.java:66-111` - export ACK command/result identity.
  - Test:     `surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/KafkaInputBridgeTest.java` - unknown offset-commit test.

  Acceptance criteria (agent-executable only):
  - [ ] Repository-wide construction search finds only the canonical capacity constructor/factory and no provider-local executor/retry implementation.
  - [ ] Kafka offset may commit only for retained terminal applied/rejected/duplicate same-fingerprint results; conflict, result unknown, route rejection, and admission failure do not commit.
  - [ ] Every command/response/export event in affected tests decodes as schema 2/default/1.
  - [ ] `mvn -pl :surprising-aeron-exporter,:surprising-trigger-provider,:surprising-risk-provider,:surprising-matching-provider,:surprising-price-provider -am -Dtest=KafkaInputBridgeTest,ReliableCoreExporterTest,TriggerOrderAeronGatewayTest,RiskAeronGatewayTest,MatchingAeronGatewayTest,MarkPriceCorePublisherTest -Dsurefire.failIfNoSpecifiedTests=false test` exits 0, allowing Surefire's no-specified-tests flag for modules without a named class.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: every provider uses one route/capacity contract
    Tool:     bash
    Steps:    Run the common prefix, then execute the affected-module Maven command from Acceptance criteria and `rg -n 'new AeronClientPool' surprising-* --glob '*.java'`.
    Expected: Tests pass; each production construction passes explicit fixed capacity and emits only default/v1 headers.
    Evidence: <attemptDir>/task-12-pool-callers.txt

  Scenario: unknown Kafka result cannot authorize offset commit
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-exporter -am -Dtest=KafkaInputBridgeTest#unknownConflictOrBackpressureCannotCommitOffset -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: `mayCommitOffset` is false for result unknown, conflict, unsupported route, and not-accepted outcomes; resubmission count is zero.
    Evidence: <attemptDir>/task-12-pool-callers-error.txt
  ```

  Commit: YES | Message: `refactor(aeron): unify provider route and capacity contract` | Files: [surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceCorePublisher.java, surprising-risk/surprising-risk-provider/src/main/java/com/surprising/risk/provider/service/RiskAeronGateway.java, surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/service/MatchingAeronGateway.java, surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderAeronGateway.java, surprising-trading/surprising-trigger-provider/src/main/java/com/surprising/trading/trigger/service/TriggerOrderAeronGateway.java, surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/KafkaInputBridge.java, surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ReliableCoreExporter.java, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/KafkaInputBridgeTest.java, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/ReliableCoreExporterTest.java]

- [ ] 13. Run affected-reactor and all-six-product-line financial/recovery matrix

  What to do: Create/extend deterministic JUnit/tool fixtures so SPOT, LINEAR_PERPETUAL, INVERSE_PERPETUAL, LINEAR_DELIVERY, INVERSE_DELIVERY, and OPTION each execute stable single and batch place/cancel/amend, duplicate same-payload, changed-payload conflict, result query, backpressure no-admission, paired snapshot-only restore, and post-restore retry. For SPOT prove base/quote lock/trade/fee/unlock; perpetual lines additionally funding, mark, liquidation, ADL, insurance, close; delivery lines settlement/position zero; option line premium, call/put ITM/OTM exercise/expiry. Run CROSS and ISOLATED for all derivatives where supported. Record user and market-maker opening/adjustment/trade/fee/funding/liquidation/settlement/exercise/ending funds and exact conservation difference 0. Run one product line per invocation and keep wallet stopped.
  Must NOT do: Do not start six clusters concurrently, substitute SPOT formulas for derivatives/options, use projection to adjudicate funds, run a 24-hour soak, or infer production capacity.

  Parallelization: Can parallel: NO | Wave 3 | Blocks: [14, 15] | Blocked by: [6, 8, 9, 10, 11, 12]

  References (executor has NO interview context - be exhaustive):
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreNativeSnapshotProductLineTest.java` - six-line paired native snapshot fixture.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java` - lifecycle/funds formula coverage.
  - Pattern:  `surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterProductLineGateMain.java` - one-line gate and source identity.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:51-58` - six isolated logical Cores.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:91-108` - conservation equation and line-specific flows.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:626-636` - recovery and six-line acceptance.
  - Evidence: `.omo/evidence/w1-w2-goal-constraint-verification-gate-review.md:15-41` - inherited single-book/snapshot invariants.

  Acceptance criteria (agent-executable only):
  - [ ] The JDK 25 affected reactor passes protocol, client, service, exporter, gateway, order, trigger, risk, matching, and price modules; report tested/untested scope and rationale.
  - [ ] Six separate matrix invocations produce manifests naming product line, margin mode(s), command IDs, order IDs, core/book hashes before/after restore, result conflict/unknown evidence, and `FUNDS_DIFFERENCE=0` for user and maker.
  - [ ] Every restored run uses paired snapshot-only import and no source contains production matcher replay/rebuild/resubmit.
  - [ ] Baseline protected path hashes/status equal preflight and no unrelated path is staged.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: all-six-line deterministic financial and identity matrix
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-service -am -Dtest=CoreW3ProductLineMatrixTest,CoreNativeSnapshotProductLineTest -Dsurefire.failIfNoSpecifiedTests=false test`; execute the matrix once per `ProductLine` if the test uses a required system property and concatenate six manifests.
    Expected: Six distinct product-line verdicts pass; every applicable CROSS/ISOLATED case and user/maker ledger reports exact difference 0; restored hashes/IDs/results match.
    Evidence: <attemptDir>/task-13-six-line-matrix.txt

  Scenario: conflict/backpressure/restore negative matrix
    Tool:     bash
    Steps:    Run the common prefix, then `mvn -pl :surprising-aeron-service -am -Dtest=CoreW3ProductLineMatrixTest#rejectsConflictBackpressureAndCorruptRestoreAcrossAllLines -Dsurefire.failIfNoSpecifiedTests=false test`.
    Expected: No rejected/not-admitted case changes funds/book/outbox; corrupt or v6 snapshot never reaches ready state; no matcher replay occurs.
    Evidence: <attemptDir>/task-13-six-line-matrix-error.txt
  ```

  Commit: YES | Message: `test(core): cover w3 contracts across product lines` | Files: [surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreW3ProductLineMatrixTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreNativeSnapshotProductLineTest.java, surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreLifecycleStateTest.java, surprising-aeron-core/surprising-aeron-tools/src/main/java/com/surprising/aeron/tools/ClusterProductLineGateMain.java]

- [ ] 14. Prove real SPOT provider/Kafka/PostgreSQL/WebSocket and immediate backpressure

  What to do: Add a scoped orchestration entry that starts exactly SPOT three-member Core, Kafka, PostgreSQL, instrument/account/order/matching providers, Gateway, and market maker; wallet and every other product line remain stopped. Seed deterministic users/instrument/funds. Through real HTTP, submit single and maximum batch place/amend/cancel, same-ID retry, changed-payload conflict, force command/query mailbox saturation, force admitted result timeout then resolve by command-result endpoint, and request a projection read with `minCoreSequence`. Verify Kafka keys/default route, PG facts/watermark/dedupe, public/private WebSocket events by stable event ID under duplicate delivery, and user/maker conservation. Add a slow WebSocket client and prove its bounded disconnect does not delay a healthy client. Use a trap to stop only named processes/compose project, preserve volumes unless an explicit task-local fresh flag is used, and record before/after process/container/port state.
  Must NOT do: Do not start wallet, another product line, production network/disk fault tests, 24-hour soak, delete broad Docker volumes, touch the protected runtime-hints path, or claim capacity from this smoke.

  Parallelization: Can parallel: NO | Wave 3 | Blocks: [15] | Blocked by: [10, 11, 12, 13]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `scripts/aeron-core-local.sh` - scoped three-member Core lifecycle and safe volume behavior.
  - Pattern:  `scripts/start-product-line-providers.sh` - current Core-only launcher to extend with explicit provider orchestration rather than misreporting readiness.
  - Pattern:  `scripts/kafka-trading-smoke.sh` - current bridge-only smoke whose scope must remain explicit.
  - Pattern:  `surprising-gateway/src/main/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumer.java:51-80` - partition/key/contiguity validation.
  - Pattern:  `surprising-gateway/src/main/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumer.java:83-103` - at-least-once export-sequence event fanout.
  - Test:     `surprising-gateway/src/test/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumerTest.java` - duplicate fanout behavior.
  - Evidence: `.omo/evidence/w5-collect-gate-review.md:23-61` - known missing real PG watermark/restart/slow-client proof.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:634-654` - real line flow and performance evidence boundaries.

  Acceptance criteria (agent-executable only):
  - [ ] One command outputs a manifest proving only SPOT services plus Kafka/PG/Gateway/maker ran; wallet and all other line ports/processes remained absent.
  - [ ] Real HTTP proves one round trip for singles/batches, stable IDs, 409 `IDEMPOTENCY_CONFLICT`, 429 immediate command/query backpressure, 202 admitted unknown, and later 200 command-result resolution.
  - [ ] PG watermark reaches the returned core sequence atomically; duplicate/reordered Kafka delivery does not duplicate facts/funds; projection read meets `minCoreSequence` or returns bounded lag without Core fallback.
  - [ ] Healthy WebSocket receives each stable event ID once after client dedupe; slow client is closed at its queue bound without delaying healthy delivery; server remains at-least-once.
  - [ ] User and maker opening/adjustment/trade/fee/ending balances conserve exactly; native book and projected open orders agree at the observed watermark.
  - [ ] Cleanup restores pre-run process/container/port inventory, retains unrelated volumes, and preserves exact protected-path status/hash.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: real SPOT full surface succeeds and cleans up
    Tool:     bash
    Steps:    Run the common prefix, then `PRODUCT_LINE=SPOT WALLET_ENABLED=false W3_QA_FRESH=true scripts/w3-real-surface-smoke.sh`; the script must drive HTTP, Kafka, PG SQL assertions, and two WebSocket clients and then execute its trap.
    Expected: Manifest reports `W3_REAL_SURFACE=PASS`, `PRODUCT_LINE=SPOT`, `BATCH_ROUND_TRIPS=3`, `FUNDS_DIFFERENCE=0`, contiguous PG watermark, stable event IDs, healthy-client delivery, and `CLEANUP=PASS`.
    Evidence: <attemptDir>/task-14-real-spot-surface.txt

  Scenario: saturation, conflict, duplicate, lag, and slow client fail safely
    Tool:     bash
    Steps:    Run the common prefix, then `PRODUCT_LINE=SPOT WALLET_ENABLED=false W3_QA_MODE=failure scripts/w3-real-surface-smoke.sh`; request the 257th command and 65th control query while drains are paused, reuse one command ID with changed payload, replay one Kafka record, pause PG projection, and block one WebSocket client.
    Expected: Immediate typed backpressure/conflict/lag responses match exact codes; no duplicate funds/facts; healthy client remains within the script's bound; cleanup passes.
    Evidence: <attemptDir>/task-14-real-spot-surface-error.txt
  ```

  Commit: YES | Message: `test(w3): add real provider projection websocket gate` | Files: [scripts/start-product-line-providers.sh, scripts/w3-real-surface-smoke.sh, surprising-aeron-core/surprising-aeron-exporter/src/test/java/com/surprising/aeron/exporter/JdbcCoreEventProjectorPostgresTest.java, surprising-gateway/src/test/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumerTest.java]

- [ ] 15. Synchronize docs, delete superseded paths, and seal the evidence ledger

  What to do: Update the canonical Chinese specification, root README, Aeron README, trading README, and relevant module README files with exact v2/default-route, snapshot v7, source-epoch-in-sourceId, fingerprint conflict, stable external identity, non-atomic bounded batch, typed unknown/admission, fixed capacities, projection read, reserved control query, at-least-once WebSocket, and QA results. Mark only evidence-backed W3 exits complete; leave W4/W5/P6 statuses honest. Delete superseded external `AeronOrderIdGenerator` only after all callers are gone, remove dead pool executor/retry APIs and direct ordinary read methods, and run source scans for forbidden duplicate mechanisms. Record each commit SHA, test command/result, real manifest, six-line manifest, tested/untested scope, cleanup receipt, and protected dirty-state receipt. Push each already-verified atomic commit in order as repository instructions require; never stage broad paths.
  Must NOT do: Do not edit `WebSocketRuntimeHints.java`, rewrite user `.omo` evidence, claim W4/W5/P6 completion from W3 evidence, link missing docs/scripts, or weaken a failing test.

  Parallelization: Can parallel: NO | Wave 3 | Blocks: [F1, F2, F3, F4] | Blocked by: [13, 14]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `docs/high-performance-trading-core-implementation.md:60-63` - explicit route requirement and no hot shard.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:110-118` - identity/idempotency requirements.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:409-419` - target Aeron concurrency/batch/unknown semantics; update source-epoch wording to the user's retained design.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:429-435` - projection/read-your-write boundary.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:554-568` - W3-W6 waves/status.
  - Pattern:  `docs/high-performance-trading-core-implementation.md:673-688` - completion status must remain evidence-backed.
  - Evidence: `.omo/evidence/w1-w2-review-7e78e04a.md:22-40` - inherited exact-baseline constraints.
  - Pattern:  `AGENTS.md` - JDK/Maven impact testing, one product line, wallet exclusion, funds conservation, and commit/push rules.

  Acceptance criteria (agent-executable only):
  - [ ] `git diff --check` exits 0 and targeted/full affected commands recorded by Tasks 13-14 remain green at the final SHA.
  - [ ] Source scans find no provider batch N-round-trip loop, pool executor/hidden retry, external wall-clock order ID, ordinary order Core read, implicit v1 route, second FIFO, matcher replay/rebuild/resubmit, unbounded queue, or cross-line state.
  - [ ] `git status --short` and hash/index-stage receipts prove the protected Gateway path and all unrelated pre-existing files are unchanged from preflight.
  - [ ] Every implementation commit contains only its declared files, builds/tests independently, has been pushed, and the final docs commit footer contains `Plan: .omo/plans/w3-wire-idempotency-capacity.md`.
  - [ ] Documentation explicitly says real SPOT QA and deterministic six-line financial matrix passed, while P6 24-hour/network/disk/capacity declaration was not run.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: final source/docs/evidence consistency audit
    Tool:     bash
    Steps:    Run the common prefix, then `git diff --check && rg -n 'coreShardId=default|routeVersion=1|snapshot v7|IDEMPOTENCY_CONFLICT|RESULT_UNKNOWN|CLIENT_BACKPRESSURED|read-your-write|at-least-once' README.md docs/high-performance-trading-core-implementation.md surprising-aeron-core/README.md surprising-trading/README.md` and validate every ledger SHA with `git cat-file -e <sha>^{commit}`.
    Expected: Checks pass; all contracts and exact evidence boundaries are present; every recorded SHA exists.
    Evidence: <attemptDir>/task-15-docs-ledger.txt

  Scenario: forbidden mechanisms and dirty-state regression are absent
    Tool:     bash
    Steps:    Compare preflight/final protected-path status and hashes, then run `rg -n 'MAX_SUBMIT_ATTEMPTS|supplyAsync|AeronOrderIdGenerator|CoreBookState|cleanStart|rebuild\(|submitCommandsSync|ORDER_STATE_QUERY|CLIENT_ORDER_STATE_QUERY|USER_OPEN_ORDERS_QUERY'` over affected production paths and classify every remaining match against Must-NOT-Have.
    Expected: No forbidden production path remains; allowed diagnostic/test mentions are enumerated; protected and unrelated dirty state is byte/index-stage identical.
    Evidence: <attemptDir>/task-15-docs-ledger-error.txt
  ```

  Commit: YES | Message: `docs(w3): record wire identity and capacity closure` | Files: [README.md, docs/high-performance-trading-core-implementation.md, surprising-aeron-core/README.md, surprising-trading/README.md, surprising-aeron-core/surprising-aeron-client/README.md, surprising-trading/surprising-order-provider/README.md, .omo/evidence/w3-implementation-ledger.md, .omo/plans/w3-wire-idempotency-capacity.md]

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
- Reference the plan file path in the final commit footer: `Plan: .omo/plans/w3-wire-idempotency-capacity.md`.
- Use a task-owned worktree from exact baseline; stage only each task's declared paths. Never stash/reset/clean the user's original worktree, never include the protected Gateway path, and push each verified module commit before beginning a dependent wave.
- If a later task needs to fix an earlier task, make a new atomic commit scoped to the violated contract; do not amend already-pushed commits.

## Success criteria
- All Must-Have shipped; all QA scenarios pass with captured evidence; F1-F4 approved; commit history clean.
- Protocol, response, and export event wire is schema v2/default/1 only; Core snapshot is v7 and still restores paired ME0/RE0 only through `fromSnapshotOnly`.
- Same command ID/same canonical intent is exactly-once at Core decision level; changed intent is `IDEMPOTENCY_CONFLICT`; missing retained result is typed unknown.
- External order/replacement identities survive provider restart and are returned for applied, rejected, not-admitted, and unknown outcomes.
- Public place/cancel/amend batches obey 20/50/20 limits, one user/line, one Aeron round trip, deterministic ordered partial results, and no hidden retry.
- Command/query overload returns immediately within fixed independent capacities; no HTTP-worker spin/park, executor task, hidden offer retry, or unbounded correlation map remains.
- Ordinary order reads use projection with bounded read-your-write; command-result/preflight/lifecycle controls use reserved read-only Aeron capacity.
- SPOT real provider/Kafka/PG/WebSocket QA and all-six-line financial matrix pass with exact user/maker funds difference 0 and cleanup receipts.
- One ProductLine per logical Core, source epoch in sourceId, WebSocket at-least-once event-ID dedupe, and protected dirty state remain intact.
- No P6 24-hour soak/network-disk production certification or capacity claim is implied.
