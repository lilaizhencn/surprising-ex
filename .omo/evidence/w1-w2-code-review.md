# W1/W2 code-quality and correctness review

## Verdict

PASS — `codeQualityStatus: CLEAR`; `recommendation: APPROVE`.

## Exact scope and independently checked inputs

- Main: `/tmp/surprising-w1w2-final.zo1EL8/main` at `7e78e04ae4dac16d364117392f960a65a4f4db2d` (clean worktree).
- Fork: `/tmp/surprising-w1w2-final.zo1EL8/fork` at `627ddf68fbb0594b07e4b59a1a0e3377354e26b9` (clean worktree).
- Maven artifact coordinate: `exchange.core2:exchange-core:0.5.15-emporia`.
- Independently computed installed dependency JAR SHA-256: `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21`.
- Independently inspected JAR provenance: `fork.git.sha=627ddf68fbb0594b07e4b59a1a0e3377354e26b9`, `fork.git.dirty=false`.

## Findings

### CRITICAL

None.

### HIGH

None.

### MEDIUM

None.

### LOW

None.

## Review basis

- The sole executable price/time book is exchange-core. Main no longer contains `CoreBookState`, `CoreBookOrder`, or a priority-sequence FIFO representation. The remaining `CoreOrderBookQuery/View` and `orderBookLevelsAsync` path is a read-only native projection ([DeterministicExchangeCoreAdapter.java:334](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:334)).
- Restore imports the paired native modules, starts exchange-core with `fromSnapshotOnly`, reconciles expected Core OPEN orders against one native open-order report in two hash maps, then checks complete engine and book hashes ([DeterministicExchangeCoreAdapter.java:88](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:88), [283](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:283), [325](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:325), [377](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:377)). No production per-order replay, rebuild, or resubmission path was found.
- Snapshot validity is closed: CoreState v6 has a fixed size cap and checksum, validates counts and exact remainder, and delegates matcher bounds/checksum/module validation ([CoreStateSnapshotCodec.java:32](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:32), [104](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:104)); MatcherSnapshot requires precisely `MATCHING_ENGINE_ROUTER/0` and `RISK_ENGINE/0` ([MatcherSnapshot.java:65](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshot.java:65)).
- Serialization uses an in-memory snapshot processor whose journaling/replay operations reject, while production exchange configuration explicitly disables journaling ([InMemorySerializationProcessor.java:175](/tmp/surprising-w1w2-final.zo1EL8/fork/src/main/java/exchange/core2/core/processors/journaling/InMemorySerializationProcessor.java:175), [DeterministicExchangeCoreAdapter.java:390](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:390)).
- The snapshot barrier drains every symbol lane before snapshot/report/persist and blocks later enqueues; per-symbol work remains ordered ([SymbolMatchingLanes.java:28](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/SymbolMatchingLanes.java:28), [42](/tmp/surprising-w1w2-final.zo1EL8/main/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/SymbolMatchingLanes.java:42)).
- The fork packages only archive-extracted sources/tests/resources after clean-Git validation, then rechecks clean HEAD/worktree and JAR provenance after JAR creation ([fork pom.xml:340](/tmp/surprising-w1w2-final.zo1EL8/fork/pom.xml:340), [368](/tmp/surprising-w1w2-final.zo1EL8/fork/pom.xml:368), [409](/tmp/surprising-w1w2-final.zo1EL8/fork/pom.xml:409)). Main verifies the complete dependency JAR digest and embedded clean source SHA in `validate`.

## Tests and evidence

I did not execute tests in either supplied review worktree, to preserve the explicit no-write constraint. I inspected the referenced immutable evidence and the actual test sources rather than treating their summaries as sufficient.

- Fork full JDK 25 suite evidence records 302 tests, 0 failures/errors; the focused in-memory snapshot test records 4/4 and open-orders test 2/2.
- Main focused runtime evidence records 53/53 across `DeterministicExchangeCoreAdapterTest`, `CoreProbeStateTest`, `SurprisingClusteredServiceTest`, and `CoreMatchingStateTest`; the reported affected reactor run is 193/193.
- Tests exercise native snapshot round-trip, FIFO continuation after restore, exact divergent-order rejection, checksums/corruption, oversize rejection, and fatal divergence propagation. They are behavior-oriented; no deletion-only, removal-only, tautological, prompt-prose, or implementation-constant-mirroring test was found.

## Skill-perspective check

Ran the required `remove-ai-slops` and `programming` skill-perspective check before assessing maintainability and tests. Neither perspective is violated in this diff: no needless executable-book abstraction, production parsing/normalization beyond snapshot trust boundaries, untyped escape hatch, brittle prompt test, or implementation-mirroring test was identified. The native open-order report is necessary for exact reconciliation and remains an unsorted linear scan, not a second book.

## Non-blocking notes

- Existing `ProductionSimulation` supports journaling for fork simulation APIs, but the W1/W2 production adapter neither uses it nor enables it; this is not a production replay path.
- P4–P6 are correctly rollout gates, outside this implementation-acceptance decision.

## Blockers

None.
