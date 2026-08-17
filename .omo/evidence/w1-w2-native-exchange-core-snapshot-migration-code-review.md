# Code quality review — W1/W2 native exchange-core snapshot migration

## Scope and evidence checked

- Reviewed exact target commit `39d9149de35797503d751ee2c32838fc58096286` against parent `459fba04f116b0284ff3b2babe56f3332929b4a6`; `git diff --check` is clean.
- Inspected the supplied exchange-core fork at `9819b9fea48b8b962bdef6bfcf67ed5f5a04981f`. The locally resolved `exchange-core-0.5.10-emporia.jar` hashes to the claimed `b2ee6f235f9dbde4d2a37e407a8a855938b0f7cc0622ea28cb6e778552ff934a`.
- The submitted “300 tests” and “246 tests” claims have no exact-commit log artifact in `.omo/evidence` to inspect. An independent affected-reactor run was attempted, but this environment uses JDK 21 and the project enforcer correctly rejects it before tests (`Surprising EX must be built with JDK 25`). Thus those JDK-25 results remain unverified, not contradicted.
- Skill-perspective check: ran `omo:remove-ai-slops` and `omo:programming` criteria before assessing maintainability/tests. No deletion-only, prompt-prose, tautological, or implementation-constant-only test was found. The diff does violate the remove-ai-slops performance criterion through an unnecessary ordered reconciliation structure; the programming perspective found no applicable typed-escape-hatch violation in this Java diff.

## Verdict

**FAIL — BLOCK / REQUEST_CHANGES**

The native snapshot restore, checksum checks, fail-closed divergence path, disabled journaling, FIFO restore scenario, and per-symbol barrier tests are substantively present. However, three HIGH/MAJOR goal violations remain.

## Findings

### CRITICAL

None.

### HIGH / MAJOR

1. Reconciliation is O(active-order log active-order), not the required exact O(active-order).

   `DeterministicExchangeCoreAdapter.reconcileOpenOrdersAsync` constructs two `TreeMap`s at [DeterministicExchangeCoreAdapter.java:287](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:287) and [DeterministicExchangeCoreAdapter.java:300](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:300). Each insertion is logarithmic, so restore and snapshot reconciliation are O(n log n). Equality does not require ordered maps: order IDs are unique keys and `Map.equals` is order-independent. This directly fails the goal's explicit exact O(active-order) constraint and adds avoidable latency on the consensus/snapshot path.

   Required correction: use an O(n) keyed reconciliation representation (with explicit duplicate detection) and add a scale-oriented regression that distinguishes the intended algorithm/operational bound from ordered-map behavior.

2. Outer snapshot restore has no total-size or per-section upper bound before it buffers and allocates attacker-controlled lengths.

   `SurprisingClusteredService.loadSnapshot` accumulates every Aeron fragment in an unbounded `ByteArrayOutputStream` at [SurprisingClusteredService.java:201](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:201)-[205](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:205). The outer decoder checks only positivity and remaining-byte arithmetic for `matcherStateLength` and `tradingStateLength` at [CoreStateSnapshotCodec.java:230](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:230)-[236](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:236), then performs allocations at [290](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:290)-[294](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:294). `MatcherSnapshotCodec` has a 1 GiB inner cap, but it is reached only after the full outer image is already buffered; `TradingStateSnapshotCodec` is not given a comparable outer cap here.

   A valid-checksum oversized or malformed consensus image can therefore exhaust heap before fail-closed validation. This is a codec-bounds failure in a funds-sensitive recovery path.

   Required correction: cap total Aeron snapshot bytes while reading, cap each outer section (and events) before copying/allocating, reject with `ProtocolException`, and test oversized total, matcher, trading, and event sections (including a checksum-valid malformed fixture).

3. The reviewed exchange-core artifact is not mechanically pinned to the declared fork commit or SHA-256.

   The dependency is resolved only by mutable Maven coordinates at [surprising-parent/pom.xml:64](/Users/atomex/Desktop/surprising/surprising-ex/surprising-parent/pom.xml:64)-[68](/Users/atomex/Desktop/surprising/surprising-ex/surprising-parent/pom.xml:68). The expected fork commit and JAR digest are merely properties at [surprising-parent/pom.xml:35](/Users/atomex/Desktop/surprising/surprising-ex/surprising-parent/pom.xml:35)-[39](/Users/atomex/Desktop/surprising/surprising-ex/surprising-parent/pom.xml:39); repository-wide inspection finds no consumer of either property. The Maven enforcer checks only JDK version at [surprising-parent/pom.xml:134](/Users/atomex/Desktop/surprising/surprising-ex/surprising-parent/pom.xml:134)-[154](/Users/atomex/Desktop/surprising/surprising-ex/surprising-parent/pom.xml:154).

   The currently installed JAR happens to match the declared digest, but a same-version replacement in a repository or local cache would build and run without detection. This does not meet the goal's pinned-fork/provenance and fail-closed requirement.

   Required correction: make the build resolve an immutable artifact and verify its SHA-256 (and fork provenance where required) during Maven validation; test that a wrong same-version artifact is rejected.

### MEDIUM / MINOR

None.

### LOW

None.

## Positive verification

- Native restore uses `InitialStateConfiguration.fromSnapshotOnly` at [DeterministicExchangeCoreAdapter.java:370](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:370)-[373](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:373); journaling is disabled at [382](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:382)-[385](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:385).
- Restore reconciles all OPEN orders and fail-closes on mismatch at [281](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:281)-[314](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:314), then checks engine/book hashes at [99](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:99)-[105](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:105).
- Snapshot and matcher codecs use CRC32C and reject checksum corruption; existing tests cover native restore/fail-closed behavior, FIFO after snapshot across all product lines, and lane-barrier ordering ([DeterministicExchangeCoreAdapterTest.java:52](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapterTest.java:52), [CoreMatchingStateTest.java:265](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java:265), [SymbolMatchingLanesTest.java:57](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/SymbolMatchingLanesTest.java:57)).

## Blockers before approval

1. Make reconciliation exact O(active-order), not O(active-order log active-order), and add a meaningful scale regression.
2. Add bounded outer snapshot ingestion/section validation before allocation, with adversarial checksum-valid oversize tests.
3. Enforce the exchange-core artifact digest/provenance in Maven and prove a same-version digest mismatch fails.
4. Provide an inspectable JDK-25 exact-commit Maven log (command, commit, module list, and final test summary) for the claimed 246/300 test passes.
