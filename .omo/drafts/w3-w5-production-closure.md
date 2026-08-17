---
slug: w3-w5-production-closure
status: exploring
intent: clear
review_required: false
pending-action: write .omo/plans/w3-w5-production-closure.md
approach: Preserve the W1/W2 single-book fail-closed core, close W3 at the Gateway/Aeron boundary, then close W4 product-line lifecycle and financial invariants, then close W5 replicated export/query/peripheral failure isolation. Implement and verify each wave independently on JDK 25, one ProductLine runtime at a time, without wallet.
---

# Draft: w3-w5-production-closure

## Components (topology ledger)
<!-- Lock the SHAPE before depth. One row per top-level component that can succeed or fail independently. -->
<!-- id | outcome (one line) | status: active|deferred | evidence path -->
W3 | bounded, idempotent Gateway/Aeron ingress and query isolation | active | docs/high-performance-trading-core-implementation.md sections 9, 13.1, 18.1
W4 | six ProductLine lifecycle correctness with CROSS/ISOLATED financial conservation | active | docs/high-performance-trading-core-implementation.md sections 8, 13.1, 15.4, 18.1
W5-export | replicated outbox and Kafka/PG idempotent failure isolation | active | docs/high-performance-trading-core-implementation.md sections 10.1, 10.3, 13.1, 18.1
W5-query | query bypass, read-your-write, projection freshness, WebSocket slow-consumer isolation | active | docs/high-performance-trading-core-implementation.md sections 10.2, 13.1, 15.5, 18.1
release-evidence | affected-reactor tests, real one-product-line QA, fault injection, docs, commits and push | active | AGENTS.md; docs/high-performance-trading-core-implementation.md sections 15, 17, 19

## Open assumptions (announced defaults)
<!-- Record any default you adopt instead of asking, so the user can veto it at the gate. -->
<!-- assumption | adopted default | rationale | reversible? -->
delivery | direct implementation in a task-owned worktree, atomic commits, fast-forward into codex/aeron-unified-core, push after each verified module | repository AGENTS.md requires commit and push; user explicitly said proceed | yes
runtime scope | one ProductLine at a time; market maker stays running where the real flow requires it; wallet never starts | repository AGENTS.md | yes
hot symbols | no split or migration work | explicitly outside current architecture until W6 capacity proves need | yes
matcher recovery | native paired snapshot and sticky fail-closed only; no replay/rebuild/resubmit fallback | W1/W2 invariant | no
test strategy | baseline characterization plus failing-first tests for changed behavior, then affected-module and real-surface QA | start-work contract and funds-safety risk | yes

## Findings (cited - path:lines)
1. W3 already has fixed-size Aeron command executors, bounded queues, stable lanes, structured results and command-result queries, but `OrderService` batch endpoints still loop through synchronous single commands and the end-to-end idempotency conflict/result-unknown REST contract is not closed. Evidence: `AeronClientPool`, `AeronOrderCommandService`, `OrderAeronGateway`, `OrderService`; canonical spec sections 9 and 13.1.
2. W4 Core state already owns and executes delivery/option settlement, funding, liquidation, ADL, insurance, risk, trigger, treasury and bounded cursors. Independent verification falsified the claim that these lifecycles were absent. The actual gaps are inverse delivery/perpetual coverage, option ITM/OTM call/put/duplicate matrix, ISOLATED lifecycle conservation, six-ProductLine negative gates and live provider-to-Core funds evidence. Evidence: `TradingCoreReducer.settleInstrumentWithProgress`, `applyFundingWithFacts`, `executeLiquidation`, `CoreTreasuryState.LifecycleProgress`, `CoreProbeState`, provider bridges and `CoreLifecycleStateTest`.
3. W5 replicated outbox, publish-before-ACK exporter, Kafka idempotent keying, JDBC projection guards and bounded per-WebSocket-session queues exist. Independently confirmed code gaps: PG-coupled projection watermark/read-your-write, process-local fanout restart watermark and polling exporter loop. Evidence gaps: deterministic blocked-session isolation, event-age/lag observation and real Kafka/PG/Gateway fault injection. Evidence: `CoreExportState`, `ReliableCoreExporter`, `KafkaProjectionWorker`, `JdbcCoreEventProjector`, `CoreEventFanoutConsumer`, `SubscriptionRegistry`.
4. Existing Compose and canonical scripts are Core-only; they do not start Kafka, PostgreSQL or provider processes. Provider ports and startup commands exist, but full W3-W5 acceptance requires one explicitly configured ProductLine process set and a live market maker, while wallet stays stopped. Evidence: `surprising-aeron-core/compose.yaml`, `scripts/aeron-core-local.sh`, `scripts/start-product-line-providers.sh`, module READMEs.
5. The stale `bounded-lifecycle-cancellation-matcher-lanes.md` plan is not executable because it directs matcher rebuild from Core state, contrary to W1/W2 single-book paired-snapshot fail-closed recovery.
6. Adversarial inspection found status/spec drift: W4 provider loops can still drain unbounded pages; ordinary order reads still hit Core; the exporter polls; ADL/insurance work selection still begins from PG projection; current provider launcher starts only Core. These are implementation gaps, not merely missing runtime evidence.
7. The canonical document baseline SHA and AGENTS documentation note are stale relative to exact current source. Planning and review bind to `7e78e04ae4dac16d364117392f960a65a4f4db2d`; tracked current `docs/` and `scripts/` are valid implementation surfaces, while broad cleanup/staging remains forbidden.

## Decisions (with rationale)
1. Add only missing W3-W5 contracts; do not duplicate already-complete P1/P2/P3 mechanisms.
2. Use additive, bounded, versioned wire contracts and exact decode limits for any new batch/query/lifecycle payload.
3. Keep all authoritative business mutation on the Aeron owner thread; providers, Kafka, PostgreSQL and WebSocket remain projections/bridges.
4. Each implementation task requires baseline characterization, failing-first proof, JDK 25 affected-reactor tests, one real-surface QA artifact, independent adversarial verification, cleanup receipt, atomic commit and push.
5. W4 cannot be marked DONE from one generic derivative test: all six ProductLine variants and both CROSS/ISOLATED where applicable must have formula-specific funds evidence.
6. W5 cannot be marked DONE from H2 or mock Kafka alone: real Kafka/PG/WebSocket restart, duplicate/reorder/backpressure and lag evidence is required.
7. Do not create replacement W4 lifecycle engines; extend gates and only fix behavior where the new matrix produces a real failing-first defect.
8. Add a complete one-ProductLine provider launcher and cleanup orchestration because current `start-product-line-providers.sh` starts only Core. Correct the stale market-maker README: engine defaults enabled, while trading/reference-market controls are separately disabled.
9. Explicit `coreShardId=default` and `routeVersion=1` become additive W3 wire/header fields; any unknown value/version rejects fail-closed. No non-default routing is implemented.
10. Core lifecycle work/cursors are authoritative. PostgreSQL may wake a provider or supply projection/audit views, but cannot select or advance authoritative ADL/insurance/settlement work.
11. Exporter completion uses notification or adaptive bounded wait with a low-frequency reconnect safety poll; fixed 10ms Core status polling is forbidden. Exact mechanism follows current Aeron capabilities and must be observable in tests.
12. WebSocket delivery is at-least-once with durable `(productLine, exportSequence/eventId)` checkpoint and client-side/event-id dedupe, not distributed exactly-once delivery.
13. External order get/by-client/history/open-order reads use projection/cache with limit, cursor, max bytes and optional `minCoreSequence` bounded wait. Command-result, preflight and lifecycle/admin control queries remain direct Core reads through reserved query capacity.
14. W3-W5 exit requires controlled real Kafka/PG/Gateway/provider/market-maker evidence for one ProductLine and deterministic all-six-line financial fixtures; production network/disk fault certification, 24-hour soak and capacity declaration remain P6.

## Scope IN
Gateway/Aeron entry concurrency and idempotency; product-line lifecycle/provider bridges and funds invariants; replicated export, Kafka/PG projection, query bypass/read-your-write and WebSocket isolation; affected scripts, tests, README/spec status, evidence, commits and push.

## Scope OUT (Must NOT have)
Wallet startup; hot-symbol Core split; cross-ProductLine shared state; database/Kafka/Redis in the synchronous decision path; a second executable book; production matcher replay/rebuild/resubmit; unbounded queues/retries/scans; unrelated user Gateway AD state; P6 24-hour soak and final capacity declaration except for prerequisites needed to prove W3-W5.

## Open questions
None. The canonical specification and repository rules resolve the implementation choices; the user's start-work request authorizes plan generation and execution.

## Approval gate
status: approved-by-start-work-bootstrap
approval: User said "推进完成w3 w4 w5" after the canonical remaining-work brief. Under the start-work no-matching-plan bootstrap exception this approves writing and executing the plan.
<!-- When exploration is exhausted and unknowns are answered, set status: awaiting-approval. -->
<!-- That durable record is the loop guard: on a later turn read it and resume at the gate instead of re-running exploration. -->
