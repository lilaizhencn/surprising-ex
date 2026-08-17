# Code-quality review: W1/W2 single executable book snapshot migration

## Verdict

- **Exact main commit:** `b64ad00615965172f042bd49c38ffe0a0075fb76`
- **Exact fork commit:** `33a9f135c4e2396aaac0f28fad2afbc1350b7a3c`
- **codeQualityStatus:** `BLOCK`
- **recommendation:** `REQUEST_CHANGES`

The branch does add bounded snapshot checks, native `fromSnapshotOnly` restore, and O(active-order) map reconciliation.  However, the artifact pinned by the main repository cannot be produced from the declared exact fork commit with the required JDK, so Maven is not enforcing the claimed exact-SHA fork artifact.

## Evidence inspected

- Both worktrees were at the requested exact commits before review. Main's unrelated pre-existing Gateway AD change and untracked `.omo` evidence were not modified.
- `git diff --check` passed for both commit diffs.
- Main targeted test run, using `JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home`:
  `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest='SurprisingClusteredServiceTest,DeterministicExchangeCoreAdapterTest,ActiveOrderIndexTest' -Dsurefire.failIfNoSpecifiedTests=false test`
  passed: 10 tests, 0 failures/errors.
- The cached dependency JAR accepted by main had SHA-256 `e7eb6b3cb292a605c30cb1fc224ced5cde7f1f5481306dbf01e76299a860f66d`.
- Two independent `mvn clean package -DskipTests` builds of the exact fork commit with JDK 25 were byte-for-byte identical (`cmp` passed), proving local reproducibility, but each yielded SHA-256 `4e0508f65b339d2d3cedcf15838da865abe2b4b825711644b1f4d413472d989d`.

## Findings

### CRITICAL

None.

### HIGH

1. **The Maven artifact gate does not bind the executable JAR to the declared fork commit, and the declared values are demonstrably inconsistent.**
   - Main pins fork SHA `33a9f135c4e2396aaac0f28fad2afbc1350b7a3c` but pins the different artifact hash `e7eb6b...` in [surprising-parent/pom.xml](/Users/atomex/Desktop/surprising/surprising-ex/surprising-parent/pom.xml:35).
   - The service gate checks only the existing local Maven-cache JAR against that hash; it neither clones/builds the fork nor consumes `exchange-core.git-sha` / `exchange-core.repository` ([surprising-aeron-service/pom.xml](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/pom.xml:57)). Repository-wide reference search found those two properties only in the parent POM.
   - Exact fork commit `33a9...`, built cleanly twice under the required JDK, consistently produces `4e0508...`, not the hash main accepts. Thus a Maven test can pass against an arbitrary cached JAR whose bytes match `e7eb6b...`, while the source commit recorded in snapshot metadata ([MatcherSnapshot.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshot.java:37)) is `33a9...`.
   - This violates the success criterion “Maven-enforced reproducible fork artifact” and breaks the provenance contract needed to safely restore native snapshots. Fix the pinned hash to the artifact reproducibly built from the exact fork SHA, and enforce that source-to-artifact relationship in the build rather than checking cache bytes alone.

### MEDIUM

1. **The new bounded-ingest test is implementation-adjacent and does not exercise the Aeron snapshot ingress path.**
   - [SurprisingClusteredServiceTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/SurprisingClusteredServiceTest.java:38) calls `ensureSnapshotCapacity` directly. It does not feed an oversized fragmented `Image` through `loadSnapshot`, prove rejection before allocation/copy, or prove the current state remains intact after that ingress failure. The production callback is [SurprisingClusteredService.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:168).
   - This is not a deletion-only or tautological test, but it provides weaker behavioral protection than the recovery boundary requires. Add a focused fragment-ingestion test or an equivalent observable recovery-boundary test.

2. **The modified adapter remains an oversized, multi-responsibility production class.**
   - [DeterministicExchangeCoreAdapter.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:1) is 530 pure LOC and now combines lane scheduling, exchange API submission, native snapshot serialization, state hashing, and reconciliation. The changed reconciliation code itself is appropriately O(active orders + reported orders) through `HashMap`s ([lines 283-322](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:283)), but keeping recovery integrity logic in this large class raises review and regression cost.
   - This is maintainability/slop debt, not a demonstrated correctness failure for this goal.

### LOW

None.

## Required skill-perspective check

Ran: `omo:remove-ai-slops` and `omo:programming` were loaded before assessing tests and maintainability. The programming skill's language-specific gate targets Python/Rust/TypeScript/Go; this Java/Maven change has no matching language reference, so its shared principles were applied.

- `remove-ai-slops`: no deletion-only tests, requested-removal-only tests, tautological tests, or unneeded production parsing/normalization were found in this delta. It does flag the oversized adapter as maintainability debt and the direct-helper capacity test as weak behavior coverage.
- `programming`: no untyped escape hatch, brittle prompt/prose test, or needless new abstraction was introduced. Its boundary/contract perspective identifies the missing source-to-artifact verification as the substantive contract failure.

## Positive checks

- Restore starts exchange-core with `InitialStateConfiguration.fromSnapshotOnly`, not a replay mode ([DeterministicExchangeCoreAdapter.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:377)).
- Snapshot and restore reconcile the active-order derived index exactly against native open orders; duplicate IDs and terminal entries fail closed ([DeterministicExchangeCoreAdapter.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:283)).
- Main targeted tests passed and the local cached JAR passed the current checksum-only gate. Those results do not resolve the HIGH provenance mismatch above.
