# In-memory Aeron transaction-chain security and failure-isolation audit

Status: **BLOCK**. Scope was source-reviewed end-to-end (no runtime cluster/deployment exercise was supplied). `omo ulw-loop status --json` reports no plan, so this is the required non-ULW fallback artifact.

## Skill-perspective check

Ran: yes. I read `omo:remove-ai-slops` and `omo:programming` before assessing maintainability/test relevance. Neither finds an AI-slop-style issue that changes this verdict; the material failures are authorization, boundedness, and recovery semantics. No production diff was introduced by this audit. Existing tests cover several happy-path duplicate/snapshot cases, but do not adversarially cover the findings below.

## Trace evidence

* Ingress -> Core: `AeronClientPool.command` constructs a caller-controlled business command with `GATEWAY`, `sourceId`, `sourceSequence`, and `userId`; `SurprisingAeronClient.submit` sends it over unauthenticated UDP; `SurprisingClusteredService.onSessionMessage` decodes and calls `CoreProbeState.apply`.
* Core -> projection: an accepted/rejected non-ACK command is appended to `CoreExportState`; `ReliableCoreExporter` reads, Kafka-publishes, then ACKs; `KafkaProjectionWorker` projects transactionally to JDBC and commits Kafka offsets afterwards. This path is not on Core's synchronous command path; it is bounded by a 64 MiB / 1,000,000-event Core backlog.

## CRITICAL

### C1 — Core has no authenticated/authorized command or query boundary (confirmed in code; network reachability needs runtime evidence)

* Evidence: `/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusterNode.java:57-65` opens an `aeron:udp` ingress with no authenticator/authorizer. `/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:59-71` only decodes then applies. `/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:147-179,459-499` checks only product line, duplicate ID, and a header-supplied source sequence—never session identity, source entitlement, role, or user ownership. The client explicitly serializes `CommandSource.GATEWAY` and the passed `userId` (`AeronClientPool.java:124-136`).
* Scenario: any network principal able to open a cluster session can forge `userId`, `CommandSource.OPERATIONS`, and a unique source ID, then submit `ADJUST_BALANCE`, `APPLY_MARK_PRICE`, liquidation/settlement operations, or `ACK_EXPORT`. An ACK through the current last sequence deletes undelivered Core events and can prune reservations (`CoreExportState.java:108-129`), permanently bypassing Kafka/DB projection. The same principal can query arbitrary users' balances/orders/risk; trigger queries treat `userId == 0` as global visibility (`CoreProbeState.java:230-261`).
* Required correction: make the Aeron session authenticated/authorized at ingress, bind a principal and role to the session rather than message headers, whitelist command types by service principal, and enforce end-user ownership/administrative roles for every query and command. Verify deployed ACLs/mTLS/network policy; they are not visible in this repository and cannot compensate for absent Core checks alone.

## MAJOR

### M1 — Unbounded mark-price queue causes process memory exhaustion and stale risk/trigger processing (confirmed)

* Evidence: `/Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceCorePublisher.java:24-27,41-67` uses an unbounded `ConcurrentLinkedQueue`; the sole drain worker waits synchronously for each Core command and retries it. After three failures it logs and silently proceeds (`:70-90`).
* Scenario: Aeron slows or is unavailable while Kafka continues delivering prices. Arrival rate then exceeds one synchronous Core round trip; heap grows without a ceiling. Eventually the price provider dies, while a long stale queue delays mark-price risk scans and trigger decisions. Failed events are discarded after three tight spin retries, creating price-sequence gaps without a durable replay path.
* Required correction: use a bounded, metric-backed coalescing queue (latest value per symbol is generally the correct price semantic), apply explicit backpressure/pause or durable handoff, and make dropped/failed sequences observable and recoverable. Add outage and sustained-overload tests.

### M2 — Header-controlled source registry is exhaustible and sequence watermarks are poisonable (confirmed in code; direct attack uses C1 reachability)

* Evidence: `CoreProbeState.java:469-476,561-565` keys up to 65,536 permanent source entries by header `(source, sourceId)` and accepts any strictly greater sequence, including a jump to `Long.MAX_VALUE`. `AeronClientPool.java:95-121` generates a fresh random source epoch on each process construction, so normal restarts consume new identities as well.
* Scenario: a connected malicious/buggy client emits one valid command for each forged source ID until the cap. New legitimate service instances subsequently get `SOURCE_SEQUENCE_TRACKING_FULL`; a forged high sequence for a known source permanently makes future ordinary sequences stale. Because sequence gaps are accepted, no recovery alarm distinguishes this from legitimate delivery loss.
* Required correction: only allocate source state after authenticated principal registration; use stable, configured source identities with epoch lifecycle/fencing; bound/expire inactive source state safely; and reject impossible forward gaps or require an explicit authenticated epoch transition.

### M3 — Trigger scans have a fixed prefix cap, allowing eligible trigger orders beyond it to starve (confirmed)

* Evidence: `/Users/atomex/Desktop/surprising/surprising-ex/surprising-trading/surprising-trigger-provider/src/main/java/com/surprising/trading/trigger/service/TriggerOrderService.java:764-786,823-849` restarts at `before = 0` for each price event and stops after at most `min(256, configuredMaxPages) * min(1000, batchSize)` rows.
* Scenario: enough pending orders for a symbol fill the scan cap. Orders later in cursor order are never considered on any price event because every scan begins from the first page again; a trigger price can remain satisfied indefinitely, violating execution timing and creating head-of-line Core query load.
* Required correction: use a price-aware index in Core or persist/rotate the scan cursor with a bounded fairness guarantee; alert on page-bound hits and test an order beyond the cap becoming eligible.

## MINOR

### N1 — Result payloads are lost across snapshots, weakening timeout reconciliation (confirmed)

* Evidence: `CoreProbeState.java:572-578,1558-1576` stores response data in memory, but `/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:69-76,160-169` serializes only status/result/count/hash and restores with an empty payload.
* Scenario: a command commits, its response is lost, then leadership recovery restores a snapshot. Retrying/querying by command ID reports the committed status but cannot return command-dependent result data. Current exporter ACK happens to fall back to status, but other result-bearing commands may not.
* Required correction: either snapshot bounded result payloads or declare/rework the reconciliation contract so callers retrieve authoritative state by entity/command ID. Add failover-after-result-loss coverage.

### N2 — Fail-closed exporter backpressure protects correctness but can halt all business commands at a large, stateful backlog (confirmed; operational sizing needs runtime evidence)

* Evidence: `CoreExportState.java:22-23,73-105` retains up to one million events / 64 MiB inside replicated state; `CoreProbeState.java:480-485,544-556` rejects normal commands when capacity is unavailable. `ReliableCoreExporter.java:46-71` only ACKs after synchronous Kafka publication.
* Scenario: Kafka outage/backpressure fills the bounded backlog. Further trading commands are rejected—even rejected business commands themselves normally create export events—until exporter recovery. Snapshot/recovery must also transfer the retained backlog. This is correctness-preserving but a significant availability and recovery-time risk.
* Required correction: monitor backlog age/bytes and fail before the hard stop; capacity-test snapshot/failover at realistic event sizes; document operator response. Do not move Kafka/JDBC into the Core hot path—the existing isolation is the right direction.

## Confirmed safeguards / no finding

* Per-session egress is bounded at 64 and closes a stalled session (`SurprisingClusteredService.java:97-119` and queue helper), so response fanout cannot grow without limit.
* The exporter publishes before ACK, validates contiguous sequence, and JDBC projection commits its transaction before Kafka offsets (`ReliableCoreExporter.java:46-71`, `KafkaProjectionWorker.java:30-43`, `JdbcCoreEventProjector.java:120-135`). This gives at-least-once export with DB-side deduplication rather than contaminating the Core command path with Kafka/DB I/O.
* Snapshot decode checks product line, lengths, export sequence continuity, and CRC for v3 snapshots (`CoreStateSnapshotCodec.java:104-212`).

## Tests/evidence reviewed

Read `CoreProbeStateTest`, exporter tests, and the implementation paths above. No test run was claimed or needed to establish these static facts. Missing adversarial coverage: unauthenticated privileged command/ACK rejection, forged source exhaustion and gap behavior, Aeron outage queue bounds/replay, trigger scan fairness, and snapshot-timeout reconciliation.

Recommendation: **REQUEST_CHANGES**. Blockers: C1, M1, M2, M3.
