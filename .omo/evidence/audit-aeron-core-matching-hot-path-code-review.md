# Aeron Core / Matching Hot-Path Performance Audit

Date: 2026-08-15  
Scope: read-only static audit of `surprising-aeron-core`, exporter/client/protocol modules, matching market-data projection, relevant configuration/tests, and recent benchmark history. No services (including wallet) were started and no source was edited.

## Verdict

- `codeQualityStatus`: **BLOCK**
- `recommendation`: **REQUEST_CHANGES**
- Skill-perspective check: **ran**. I read `omo:remove-ai-slops` and `omo:programming` before judging maintainability/test relevance. The production hot path violates their relevant perspectives: it contains avoidable stream/list/object allocation and in-core normalisation/serialization on every command. No deletion-only, prompt, or implementation-mirroring performance test was added by this audit; existing capacity evidence is inadequate rather than slop.

## Confirmed architecture facts

- All six product lines have distinct Aeron cluster IDs and port ranges: `ProductLineClusterLayout.java:22-30`; node directories are also product-line-specific in `ClusterTopology.java:63-69`. I found **no direct shared Java matching lock across SPOT, LINEAR_PERPETUAL, INVERSE_PERPETUAL, LINEAR_DELIVERY, INVERSE_DELIVERY, and OPTION**.
- Each cluster nevertheless has a single clustered-service callback path. `SurprisingClusteredService.onSessionMessage` decodes, applies, encodes, and offers the response serially (`SurprisingClusteredService.java:51-71`). `TradingCoreRuntime` enforces one owner thread (`TradingCoreRuntime.java:53-63`).
- Evidence below is static unless marked measured. Existing reports were treated as untrusted and checked against their benchmark source.

## Findings

### P0 / CRITICAL — matching waits synchronously inside the consensus command callback

Affected: **all six product lines**.

Evidence:

- The clustered callback invokes `state.apply` before it can return (`SurprisingClusteredService.java:51-71`).
- Place and replace synchronously call the adapter (`CoreProbeState.java:750-780`, `875-917`); cancel does likewise (`795-824`).
- Adapter methods call `CompletableFuture.join()` for place, cancel, move, user creation, symbol creation, report and book query (`DeterministicExchangeCoreAdapter.java:57-59`, `111-147`, `159-191`, `267`, `294-300`).
- The adapter starts a separate Exchange Core with one matching engine, one risk engine, and busy spin (`DeterministicExchangeCoreAdapter.java:237-250`).

Impact: each order command serially occupies the Aeron service agent while waiting for an inter-thread Exchange Core round trip. Any queueing, GC pause, busy-spin contention, or adapter recovery turns into cluster-command latency and head-of-line blocking for unrelated symbols/users in that product line. A first order for a user/instrument adds further synchronous AddUser/AddSymbol waits. This is not a network round trip, but it is a blocking in-process handoff on the consensus path.

Minimal change: retain the per-product-line single writer, but make matching execute *in that writer* (an owned, synchronous matching-book API), producing fills before reducer/export work. Do not merely move matching to an arbitrary executor: completion ordering then becomes a replicated-state/determinism problem. If Exchange Core must remain, establish a deterministic single-command API or a per-symbol shard whose input/output ordering is committed explicitly; that is larger and has cross-symbol/risk trade-offs.

Benchmark to confirm: JFR/async-profiler plus per-stage monotonic timings for ingress decode, reducer reserve, adapter submit-to-complete, match application, export encoding, and egress. Drive hot same-symbol and many-symbol traffic at 1/10/100/1,000 users for each product line; report throughput and p50/p99/p99.9 command latency. The adapter wait must disappear from the clustered-service-agent stacks and tail latency must improve without changing fill order/state hash.

### P1 / HIGH — mark-price risk scanning repeatedly materializes/scans global user state and allocates per position

Affected: **derivative lines primarily** (LINEAR_PERPETUAL, INVERSE_PERPETUAL, LINEAR_DELIVERY, INVERSE_DELIVERY, OPTION); the generic command path is not product-line guarded, so assess any SPOT mark-price usage too.

Evidence:

- Every mark price immediately runs a batch of 256 users (`TradingCoreReducer.java:780-810`).
- `continueRiskScan` walks users after the cursor (`816-857`), but `usersAfter` falls back to `users.values().stream().filter(...).toList()` when the state map is not a plain navigable map (`2155-2165`). That makes each batch allocate and inspect all remaining users instead of advancing an iterator/index.
- For every visited user, `positionsForSymbol` streams and collects a new list (`2169-2175`); cross margin then streams all eligible user positions into a `List<PositionRisk>` (`894-904`) before scanning it twice (`905-922`).
- A new mark price while an earlier scan is incomplete restarts from its cursor/state mechanics (`794-800`, `858-862`); high-rate mark updates can keep command time dominated by risk continuation.

Impact: the nominal `maxUsers=256` bound does not bound allocations or the fallback scan work. With many users/positions, a price update can monopolize the single cluster writer and create GC-driven tails exactly when risk responsiveness is required.

Minimal change: maintain an owner-thread `symbol -> ordered user/position keys` risk index (separate isolated/cross buckets). Scan the ordered keys incrementally without `toList`; calculate cross risk in one loop with primitive accumulators, then a second indexed pass only when snapshots/liquidations must be emitted. Coalesce price updates per symbol by retaining only the latest sequence before the next bounded scan command. Trade-off: index update logic must be snapshot/rebuild deterministic and carefully tested for position close/asset/margin-mode changes.

Benchmark to confirm: 10k/100k users, 1/5/20 positions per user, varied cross ratios; issue mark updates faster than scan completion and interleave orders. Record scan CPU/allocation bytes per update, time to newest-price completion, liquidation correctness, and order p99. Baseline must expose the current all-remaining-users allocation; the replacement should be proportional to the configured batch plus affected positions.

### P1 / HIGH — snapshots serialize/copy the entire authoritative state and can busy-wait in the service agent

Affected: **all six product lines**.

Evidence:

- `onTakeSnapshot` builds one full `byte[]`, then loops until every publication offer succeeds (`SurprisingClusteredService.java:74-86`).
- Snapshot loading also repeatedly allocates fragments into a `ByteArrayOutputStream` (`127-143`).
- `CoreStateSnapshotCodec.encode` first serializes all trading state, then separately encodes and retains every pending export event, calculates total size, allocates one exact `ByteBuffer`, and CRCs the complete byte array (`CoreStateSnapshotCodec.java:37-88`).
- `TradingStateSnapshotCodec` iterates all users, balances, reservations, positions, orders, instruments, risk state, treasury, algos, timers, and triggers (`TradingStateSnapshotCodec.java:51-259`).

Impact: snapshot cost grows with total state and unacknowledged export backlog, causing an unbounded stop-the-world latency spike on that product-line writer. Publication backpressure additionally turns it into a busy wait. This is a liveness/tail-latency risk, not a justification to remove snapshot integrity checks.

Minimal change: preserve deterministic snapshots but chunk/stream encoding directly into reusable bounded buffers; cap snapshot work per agent cycle and use the cluster idle strategy between chunks. Store/export durable event payloads outside the duplicated in-memory snapshot (or reference a replicated journal position) so backlog is not copied twice. Trade-off: recovery format/versioning becomes more complex; use a compatibility test and hash/recovery equivalence test.

Benchmark to confirm: state-size sweep (orders, users, positions and 0/10k/1m pending exports), force publication backpressure, and measure snapshot bytes, allocation, duration, command-latency pause, recovery time and recovered business hash.

### P1 / HIGH — exporter is serial synchronous I/O and eventually backpressures acceptance

Affected: **all six product lines** (downstream persistence/projection, not the immediate match call).

Evidence:

- The core stores up to 1m events/64 MiB; new commands are rejected at either bound (`CoreExportState.java:22-25`, `64-95`).
- One export cycle does query -> decode -> `sink.publish` -> ACK serially (`ReliableCoreExporter.java:41-67`).
- Projection/input workers call `consumer.commitSync` for every individual record (`KafkaProjectionWorker.java:29-39`, `KafkaInputWorker.java:28-47`).
- JDBC projection executes update-then-possible-insert per changed order instead of batching that upsert (`JdbcCoreEventProjector.java:163-195`), while `requireChangedOrder` linearly scans changed orders for every execution (`229-234`).

Impact: Kafka broker RTT and JDBC latency cap exporter throughput; lag accumulates in replicated core memory and then rejects trading commands. This does not prove a direct matching-thread database call, but it is a concrete availability and burst-concurrency ceiling.

Minimal change: publish/commit in bounded Kafka batches with asynchronous commit guarded by contiguous processed offsets; batch DB upserts using database-native upsert and build one order-id map per event for execution lookup. Keep the ACK only after the durable sink confirms the batch. Trade-off: batching increases recovery/replay window; preserve idempotency keyed by product line/export sequence.

Benchmark to confirm: inject broker and DB latency/failure, measure events/s, ACK lag, pending bytes, time-to-`EXPORT_BACKLOG_FULL`, duplicate/restart behavior, and foreground command latency at the six-line peak mix.

### P2 / MEDIUM — steady command path creates multiple arrays/collections and serializes full response/export views

Affected: **all six product lines**, magnified for match-heavy orders.

Evidence:

- Aeron ingress copies each session payload into a new byte array and codec object; response does another encode/allocation (`SurprisingClusteredService.java:58-70`).
- Match processing constructs stream pipelines with `distinct().toList()` three times on a place and replace (`CoreProbeState.java:762-777`, `897-913`), while `matchingResult` allocates one `CoreMatch` per fill (`DeterministicExchangeCoreAdapter.java:86-96`).
- Every export command materializes changed views and encodes a new export event (`CoreProbeState.java:492-508`, `CoreExportState.java:76-95`).
- Delta maps allocate a `TreeMap` for updates/removals per changed collection (`StateMapSupport.java:35-48`, `196-214`); several navigation methods materialize a full `TreeMap` on read (`368-446`).

Impact: allocations are not merely cosmetic on a busy-spin, single-writer path: they become young-GC pressure and tail jitter. Exact materialization frequency needs profiling; no claim is made that every listed allocation is dominant.

Minimal change: first remove stream/`distinct` collectors from the per-fill command path in favour of bounded, pre-sized loops and primitive/order-id scratch buffers owned by the service thread; encode directly to Agrona buffers where protocol permits. Do not introduce a generic abstraction or pre-emptively replace every immutable map—profile the top allocators first. Avoid invoking `NavigableMap` navigation on a delta map in hot logic until it has a non-materializing implementation.

Benchmark to confirm: allocation flame graph (`-prof gc` / JFR), bytes/op and GC pause count under no-match, one-fill and 100-fill orders; verify same response/export payload/state hash.

### P2 / MEDIUM — egress and market-data fanout share serialized work with their consumers

Affected: **all six product lines**; public market-data projection is per product line.

Evidence:

- Service background work iterates all pending client sessions and drains each queue without a per-session/per-cycle budget (`SurprisingClusteredService.java:95-100`, `158-172`). Slow sessions queue up to 64 responses then are closed (`175-181`), but many non-full queues still consume the agent cycle.
- `CoreMarketDataProjection` serializes Kafka apply, bootstrap, HTTP snapshots and sequence state with `synchronized` (`59-66`, `83-143`). Per event it allocates maps/sets, creates every trade, and rebuilds full depth snapshots for every changed symbol (`95-127`, `154-235`).
- It also rejects any core-events topic partition other than zero (`70-80`), intentionally enforcing one ordered consumer lane. This is a product-line projection bottleneck, not an exchange-core correctness defect.

Impact: slow egress clients can steal foreground service time; separate public-depth/HTTP load contends with Kafka projection under one monitor and causes per-symbol O(depth) payload construction per export event.

Minimal change: budget egress offers per service cycle and round-robin sessions; retain bounded disconnection. In market data, keep the single ordered apply lane but publish immutable/copy-on-write depth views to readers and coalesce dirty-symbol depth emissions once per configured interval. Trade-off: public depth becomes intentionally sampled/coalesced; document sequence semantics.

Benchmark to confirm: attach 1k slow egress consumers plus an order load; separately run 100 HTTP book readers with high fill rate. Measure service-agent foreground p99, egress queue age/disconnects, Kafka consumer lag, projection-lock contention and WebSocket/depth publish rate.

### LOW

No independent LOW-severity finding. The remaining observations are either intentional startup/close-path locks, unmeasured allocation candidates already covered above, or deployment-level shared-resource risks that need the proposed benchmarks before they can be ranked.

## Measured evidence and test assessment

- No benchmark was run: the assignment prohibited starting/stopping services, and this audit remains read-only.
- `CoreInMemoryBenchmark` is SPOT-only (`CoreInMemoryBenchmark.java:47-48`, `91-97`), sends serial place/cancel pairs (`57-74`), and runs `CoreProbeState` directly. It excludes Aeron ingress/egress, multiple connections, fills, mark-risk scans, snapshots, Kafka/JDBC/exporter, market data, and all non-SPOT product lines. It cannot substantiate high-concurrency or complete-six-line claims.
- Existing test references cover several product lines for correctness, but the inspected benchmark tooling has explicit spot/perpetual-oriented scenarios rather than a full six-line saturated path. No performance regression gate, allocation budget, snapshot backpressure test, risk-scan scale test, or exporter-lag capacity test was found in the inspected sources.

## Not found / avoid false positives

- No `synchronized`/`ReentrantLock` was found in the matching service path itself; the primary command-path serialization is the intended Aeron clustered-service single writer and the synchronous Exchange Core join.
- The media-driver `synchronized` blocks in `AeronClientPool` are lazy-init/close paths (`AeronClientPool.java:208-211`, `304-318`), not steady matching-path locks.
- Product lines are assigned separate cluster IDs/ports, so a claim of a single application-level six-product matching lock would be unsupported. Shared machine CPU, NIC, Kafka, database and scheduler resources still require deployment-level capacity measurement.

## Approval blockers

1. Remove the synchronous Exchange Core future wait from the clustered service command path while preserving deterministic ordering and replicated state semantics.
2. Replace the risk-scan all-remaining-user materialization with an incremental per-symbol index/cursor and demonstrate bounded work/allocation.
3. Establish an evidence-backed six-product capacity suite that includes match-heavy orders, snapshots, risk scans where applicable, exporter/Kafka/JDBC lag and slow fanout; publish stage timings and allocation profiles.
