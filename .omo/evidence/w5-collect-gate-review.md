# W5 collect independent gate review

- recommendation: **APPROVE**
- baseline: `7e78e04ae4dac16d364117392f960a65a4f4db2d` (confirmed by `git rev-parse HEAD`)
- review mode: read-only source/artifact inspection; no tests or services run
- goalId: `w5-collect`
- report location fallback: `.omo/evidence/w5-collect-gate-review.md` because `omo ulw-loop status --json` returned `ULW_LOOP_PLAN_MISSING`

## originalIntent

Independently falsify five W5 collection claims at the named baseline, inspect exact production and test artifacts, distinguish code gaps from missing runtime infrastructure, and preserve unrelated Gateway AD state and existing `.omo` artifacts.

## desiredOutcome

For each claim: `CONFIRMED`, `PARTIAL`, or `FALSIFIED`, exact source/counterevidence, confidence, the smallest code/evidence task that would close the gap, and an explicit infrastructure-only boundary.

## userOutcomeReview

The requested outcome is satisfied by direct baseline source inspection plus inspection of retained evidence. Three claims are source-confirmed, one compound claim is partial because implementation exists but live proof does not, and the seven-failure claim is partial because the fixture defect is exact but the retained artifact is prose rather than a raw failing transcript. No service or test execution was used to promote evidence.

## Verdicts

### W5-C1: KafkaProjectionWorker lacks a PG-coupled watermark/read-your-write contract

- verdict: **CONFIRMED**
- confidence: **high**
- exact evidence:
  - `KafkaProjectionWorker.java:30-43` polls records, calls `projector.project(...)`, accumulates Kafka offsets, and commits offsets after projection. It has no requested sequence, projection watermark, await API, or freshness result.
  - `JdbcCoreEventProjector.java:18-23,103-106,114-136` transactionally inserts each event and facts; `(product_line, export_sequence)` is used for event identity/deduplication. There is no separate contiguous PG watermark row updated in that transaction and no reader-facing read-your-write contract.
  - `V001__create_core_event_projection.sql:1-17` provides the event table and primary key, not a projection-watermark contract.
  - repository-wide runtime search found no `projectionSequence`, `readYourWrite`, or read-your-write implementation under `src/main`/`src/test`.
- counterevidence considered: the event table can be queried for a maximum sequence and DB commit precedes Kafka offset commit. That is transactional event persistence and at-least-once deduplication, not a declared contiguous watermark or an API that waits for a caller's core sequence.
- minimal code/evidence task: add one product-line projection-watermark row updated in the same JDBC transaction as event/fact projection; reject/hold non-contiguous advancement; expose projection sequence/freshness and a bounded wait-until-sequence query contract; add a real-Postgres transaction/rollback/replay/gap test and reader contract test.
- infrastructure-only boundary: Kafka+Postgres failure injection is required to prove crash windows and replay behavior, but the missing schema/API contract is a code gap and cannot be closed by infrastructure evidence alone.

### W5-C2: CoreEventFanoutConsumer restart watermark is process-local

- verdict: **CONFIRMED**
- confidence: **high**
- exact evidence:
  - `CoreEventFanoutConsumer.java:42-49` declares `private long lastAppliedExportSequence` and initializes it only as ordinary instance state.
  - `CoreEventFanoutConsumer.java:75-81,103` deduplicates/checks contiguity against that field and advances it only after fanout.
  - `CoreEventFanoutConsumerTest.java:41-60` proves duplicate suppression only by invoking the same consumer instance twice. It has no reconstructed consumer/restart case.
- minimal code/evidence task: define the intended durable ownership first (Kafka committed offset only, or a durable product-line fanout watermark). If exact export-sequence continuity across consumer recreation is required, persist/load the watermark and test new-instance restart, duplicate replay, gap, and publish-failure ordering.
- infrastructure-only boundary: a broker-backed consumer-group restart test is needed to prove offset/rebalance behavior. It cannot establish a durable application watermark that production code does not load.

### W5-C3: slow WebSocket consumer isolation lacks implementation/proof

- verdict: **PARTIAL**
- confidence: **high**
- exact counterevidence to “lacks implementation”:
  - `ClientConnection.java:37-46` creates one bounded `ArrayBlockingQueue` and one virtual writer thread per connection.
  - `ClientConnection.java:71-88` rejects a full queue and closes that connection with `SERVICE_OVERLOAD`.
  - `ClientConnection.java:95-145` drains asynchronously and applies a send watchdog; timeout closes only that session.
  - `SubscriptionRegistry.java:202-235` continues iterating subscribers and removes only a connection whose enqueue fails.
- exact evidence supporting “lacks proof”:
  - `SubscriptionRegistryTest.java:55-73` mocks `ClientConnection.send(false)` and proves registry removal/healthy-send behavior, but does not exercise a real queue, blocked `WebSocketSession.sendMessage`, timeout, or fanout latency.
  - no `ClientConnection` production-behavior test exists; all test references use mocks.
  - `.omo/evidence/fullchain-runtime-qa-20260815/fullchain-runtime-qa-manual-qa.md:30,48` records the live slow-consumer scenario as FAIL/unverified because Gateway/Kafka was unavailable.
- slop/overfit assessment: the registry mock test is useful for subscriber-removal control flow but is implementation-mocked and cannot prove the named isolation boundary. Adding more mock-only queue tests would create false confidence.
- minimal code/evidence task: add a deterministic `ClientConnection` test with a controllable blocking `WebSocketSession`, tiny queue, two real connections, and observable close status/fanout completion; avoid sleeps and assert bounded completion through synchronization primitives.
- infrastructure-only boundary: run a live Gateway/Kafka test with one stalled socket and one healthy socket, verifying healthy delivery/latency, stalled-client close, bounded memory/queue, rejection metric, and Kafka consumer progress. Infrastructure is needed for end-to-end proof, not for implementation.

### W5-C4: exporter loop polls contrary to target

- verdict: **CONFIRMED**
- confidence: **high**
- exact evidence:
  - `ProjectionMain.java:30-34` runs `while (!Thread.currentThread().isInterrupted())` and calls `worker.pollOnce(Duration.ofMillis(250))` on every iteration.
  - `KafkaProjectionWorker.java:30-43` directly invokes Kafka `consumer.poll(timeout)` and synchronously commits each non-empty polled batch.
- minimal code/evidence task: if the target forbids an explicit application polling loop, move lifecycle and batch handling to the selected listener/container model and retain project-then-ack semantics; test graceful stop, empty periods, batch commit, projection failure, and rebalance.
- infrastructure-only boundary: broker load/idle measurements are needed to compare latency, CPU, and rebalance behavior, but they cannot falsify the explicit polling loop present in source.

### W5-C5: matching-provider has seven baseline fixture failures

- verdict: **PARTIAL**
- confidence: **medium-high**
- exact evidence:
  - `KafkaPublicTradePublisherTest.java:24-124` contains four tests; each constructs `new MatchingProperties()` without setting `kafka.productLine` (`:30-31`, `:61-62`, `:88-89`, `:110-111`) and reaches `getMatchTradesTopic()` during send.
  - `KafkaOrderBookDepthPublisherTest.java:25-115` contains three tests; each constructs default `MatchingProperties` (`:28`, `:65`, `:98`) and reaches `getOrderBookDepthTopic()` during send.
  - `MatchingProperties.java:48-55` defaults `productLine` to null; `:107-116,134-136` derives both topics via `ProductTopicNames.of(productLine)`; `ProductTopicNames.java:17-18` dereferences `productLine.topicSegment()`. Thus all seven fixtures have the same exact null-product-line defect.
  - `.omo/evidence/w1-w2-review-7e78e04a.md:36-40` claims the seven failures reproduced at baseline `fdfe2114...`, but this is success prose without a referenced raw test transcript.
  - the current `target/surefire-reports` contains only `CoreMarketDataProjectionTest`; there is no retained XML/TXT failure report for either publisher test at the requested SHA.
- why not CONFIRMED: user prohibited running tests, and no raw failure artifact independently reproduces the exact count at `7e78e04a`. Source proves seven defective fixtures, but the execution/count claim remains one evidence level short.
- minimal code/evidence task: set an explicit, non-fallback `ProductLine` in the seven fixtures (prefer a shared test fixture only if already conventional), then run just the two test classes and retain raw Surefire XML/TXT. This is fixture correction, not a production fallback.
- infrastructure-only boundary: none. These are pure unit fixtures; Kafka, Postgres, Gateway, wallet, and matching services must not be started.

## remove-ai-slops / programming direct pass

- No changed production diff was reviewed for W5; HEAD equals the requested baseline and the only tracked worktree delta is the unrelated deleted `WebSocketRuntimeHints.java`, which was preserved and excluded.
- Existing W5 tests are narrow in count but not all strong in behavior: `CoreEventFanoutConsumerTest` proves only same-instance deduplication; `SubscriptionRegistryTest` mocks the exact slow-consumer seam; neither proves restart/live isolation. These are evidence gaps, not reasons to delete tests.
- The seven publisher tests are not deletion-only or removal-verification tests. Their failure is a fixture boundary mismatch after product-line isolation became mandatory. Fixing production with a null fallback would violate the project rule forbidding fallback/legacy behavior.
- No tautological test, prose-pin test, deletion-only test, unnecessary extraction, parsing, or normalization is proposed. New tests must assert observable transaction/restart/isolation contracts with real objects or controlled fakes, not mirror implementation fields.
- Maintenance note: `ClientConnection`'s watchdog uses thread interruption around potentially blocking WebSocket I/O; only a real blocked-session test can validate the assumption. This is a NOTE, not a blocker beyond the stated proof gap.

## checked artifact paths

- `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/KafkaProjectionWorker.java`
- `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/JdbcCoreEventProjector.java`
- `surprising-aeron-core/surprising-aeron-exporter/src/main/java/com/surprising/aeron/exporter/ProjectionMain.java`
- `surprising-aeron-core/surprising-aeron-exporter/src/main/resources/db/migration/V001__create_core_event_projection.sql`
- `surprising-gateway/src/main/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumer.java`
- `surprising-gateway/src/main/java/com/surprising/websocket/provider/service/ClientConnection.java`
- `surprising-gateway/src/main/java/com/surprising/websocket/provider/service/SubscriptionRegistry.java`
- `surprising-gateway/src/test/java/com/surprising/websocket/provider/service/CoreEventFanoutConsumerTest.java`
- `surprising-gateway/src/test/java/com/surprising/websocket/provider/service/SubscriptionRegistryTest.java`
- `surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/config/MatchingProperties.java`
- `surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/service/KafkaPublicTradePublisher.java`
- `surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/service/KafkaOrderBookDepthPublisher.java`
- `surprising-trading/surprising-matching-provider/src/test/java/com/surprising/trading/matching/service/KafkaPublicTradePublisherTest.java`
- `surprising-trading/surprising-matching-provider/src/test/java/com/surprising/trading/matching/service/KafkaOrderBookDepthPublisherTest.java`
- `surprising-product-api/src/main/java/com/surprising/product/api/ProductTopicNames.java`
- `.omo/evidence/w1-w2-review-7e78e04a.md`
- `.omo/evidence/fullchain-runtime-qa-20260815/fullchain-runtime-qa-manual-qa.md`
- required skill criteria: `omo:remove-ai-slops`, `omo:programming`

## exact evidence gaps

1. No retained raw Surefire transcript/XML shows the claimed seven publisher-test failures at SHA `7e78e04a`.
2. No real-Postgres test proves atomic projection-watermark advancement, rollback, replay, contiguity, or read-your-write waiting because that contract is absent.
3. No reconstructed `CoreEventFanoutConsumer` or broker-backed restart test proves restart behavior.
4. No deterministic real-queue/blocked-session test and no live Gateway/Kafka slow-client run proves isolation under actual WebSocket I/O.
5. The inspected code-review prose does not explicitly record the required remove-ai-slops overfit/slop criterion coverage. This direct gate pass supplies that analysis; the report omission is a NOTE because no supplied success criterion requires a separate code-review report for this collection task.

## blockers

None. This review task asked for independent classification and boundaries, not implementation closure. The two PARTIAL verdicts explicitly identify the missing proof and do not over-promote untrusted prose.
