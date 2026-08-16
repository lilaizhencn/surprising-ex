# manualQa — activity book and recovery

Overall verdict: FAIL

The executable matching book is exchange-core, and the targeted FIFO and unique-book scenarios pass. The requested migration is not complete because Core still persists and mutates `CoreBookState`, recovery still stops exchange-core and replays active orders one by one, and the tests do not assert equality between the CoreBookState-derived hash and the exchange-core book hash. The full targeted Maven run also has one teardown error.

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| ABR-01 | unique executable book / BOOK_STATE_QUERY | CoreProbeState + DeterministicExchangeCoreAdapter | `BOOK_STATE_QUERY` → `matchingAdapter.orderBookLevelsAsync()`; adapter → `api.requestOrderBookAsync(...)` | PASS | SRC-01, SRC-02, TEST-02 |
| ABR-02 | exchange-core book hash source | DeterministicExchangeCoreAdapter | `matchingStateHashAsync()` → `runtime.matcherReady()` → `orderBooksStateHashAsync()` → exchange-core `MATCHING_ORDER_BOOKS` StateHashReport | PASS | SRC-02, SRC-03, TEST-02 |
| ABR-03 | Core retains only metadata/index, no executable price-level book | CoreBookState, TradingCoreState, TradingCoreReducer | `CoreBookState(long, Map<Long,Long>)`; reducer `applyMatches()` mutates order-id/priority map; no CoreBookState path serves BOOK_STATE_QUERY | PASS | SRC-01, SRC-03 |
| ABR-04 | recovery behavior | TradingCoreRuntime + DeterministicExchangeCoreAdapter | `rebuildMatcherAsync(restored)` → `rebuildAsync()` → `stop()` → `bookState.priorityOrder()` → one `placeAsync()` per request | FAIL | SRC-04, TEST-01 |
| ABR-05 | snapshot/FIFO recovery observable behavior | CoreMatchingStateTest | `mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=CoreMatchingStateTest#snapshotRecoveryKeepsOriginalFifoAfterPartialFillUpdatesOrderMetadata test` | PASS | TEST-03, SRC-05 |
| ABR-06 | unique book/query and snapshot recovery observable behavior | CoreMatchingStateTest | `mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=CoreMatchingStateTest#spotMatchUpdatesBothUsersFundsOrdersAndRecoverableBookAtomically test` | PASS | TEST-04, SRC-05 |
| ABR-07 | full minimum Maven test set | surprising-aeron-service | `mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=CoreMatchingStateTest,CoreProbeStateTest,TradingStateSnapshotCodecTest test` under JDK 25 | FAIL | TEST-01 |

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| ADV-01 | recovery does not rebuild a second active book | restart/recovery with multiple active FIFO orders | restore one exchange-core executable book without per-order replay | FAIL | SRC-04, SRC-05, TEST-03 |
| ADV-02 | CoreBookState cannot diverge silently from exchange-core | Core state hash versus exchange-core matching-book hash | equality or explicit full consistency proof is asserted | FAIL | SRC-03, SRC-05 |
| ADV-03 | snapshot preserves executable behavior | snapshot after partial fill, then crossing order | original FIFO/order behavior remains observable after restore | PASS | TEST-03 |
| ADV-04 | query returns unique executable book | BOOK_STATE_QUERY after match and restore | levels come from exchange-core and remain queryable after restore | PASS | SRC-02, TEST-04 |
| ADV-05 | shutdown/cleanup boundary | close after replace/rebuild test | matcher shuts down without leaving pending Disruptor events | FAIL | TEST-01 |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| TEST-01 | maven-output | Full targeted test command, JDK 21 blocker, JDK 25 result, and teardown error | `.omo/evidence/manual-qa-activity-book-recovery-20260816/maven-targeted-tests.txt` |
| TEST-02 | maven-output | Targeted unique-book/hash/snapshot scenario, BUILD SUCCESS | `.omo/evidence/manual-qa-activity-book-recovery-20260816/maven-targeted-tests.txt` |
| TEST-03 | maven-output | Targeted FIFO snapshot recovery scenario, BUILD SUCCESS | `.omo/evidence/manual-qa-activity-book-recovery-20260816/maven-targeted-tests.txt` |
| TEST-04 | maven-output | Targeted spot match/recoverable-book scenario, BUILD SUCCESS | `.omo/evidence/manual-qa-activity-book-recovery-20260816/maven-targeted-tests.txt` |
| SRC-01 | source | CoreBookState data shape and priority index implementation | `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreBookState.java:6` |
| SRC-02 | source | exchange-core order-book query and hash APIs | `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:182` |
| SRC-03 | source | Core state validation/hash and reducer book writes | `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreState.java:73` |
| SRC-04 | source | stop plus per-order recovery replay | `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:233` |
| SRC-05 | source | test assertions for FIFO, snapshot, and non-equal hash coverage | `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java:103` |
