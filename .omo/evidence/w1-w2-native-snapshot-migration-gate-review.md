# W1/W2 Native Snapshot Migration — Security and Reliability Gate Review

## recommendation

**REJECT / FAIL** — overall severity: **HIGH**

## originalIntent

At exact main commit `39d9149de35797503d751ee2c32838fc58096286` and exact fork commit `9819b9fea48b8b962bdef6bfcf67ed5f5a04981f`, migrate recovery to paired CoreState v6 and native exchange-core ME0/RE0 snapshots. exchange-core must remain the sole executable FIFO/order book; restore must be snapshot-only, exactly reconcile active orders in O(active-order), validate complete engine/book state, fail closed, disable journaling and avoid replay/retry/fallback. The pinned fork artifact must be provenance-bound and corrupt/untrusted snapshots must have safe allocation bounds.

## desiredOutcome

A corrupt, hostile, truncated, oversized, mismatched, or wrong-provenance snapshot is rejected before unsafe allocation or state replacement. Recovery starts exchange-core only with `fromSnapshotOnly`, performs no order replay or hidden FIFO reconstruction, reconciles every OPEN order and validates full engine/book hashes. Maven resolves only the reviewed fork artifact and mechanically verifies its pinned digest/commit provenance.

## userOutcomeReview

The core architectural migration is substantially present: TradingState v19 no longer contains `CoreBookState`; restore imports exactly two native modules, uses `fromSnapshotOnly`, performs exact OPEN-order map equality and engine/book hash checks, propagates fatal divergence, and disables all journal operations in the fork processor. Snapshot counts and arithmetic are generally guarded with long/`Math.*Exact`, nested CRC32C checks, duplicate checks, fixed module count, and trailing-byte rejection.

The shipped artifact nevertheless fails two explicit security/reliability criteria. Aeron snapshot ingestion is unbounded before codec validation, permitting memory exhaustion from a corrupt/hostile snapshot stream. The declared fork commit and SHA-256 are metadata only: Maven selects a mutable version coordinate and no build rule verifies the resolved JAR digest or commit, so the reviewed artifact is not mechanically pinned.

## blockers

1. **HIGH — violatedCriterion: R6 resource exhaustion / allocation bounds**
   - Observation: `loadSnapshot` appends every Aeron fragment to an unconstrained `ByteArrayOutputStream` until end-of-stream and then makes another full copy with `toByteArray()`. The 1 GiB matcher-codec bound runs only after this accumulation; the outer Core codec has no total-size ceiling. A corrupt/non-terminating or oversized snapshot image can exhaust heap before fail-closed parsing.
   - evidencePointer: `39d9149d:surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:200-215`; `.../matching/MatcherSnapshotCodec.java:28,89-97`.

2. **HIGH — violatedCriterion: R4 supply-chain pin / checksum provenance**
   - Observation: Maven resolves `exchange.core2:exchange-core:0.5.10-emporia`; the fork URL, commit SHA and JAR SHA-256 are unused POM properties. No enforcer/plugin/code path compares the resolved artifact against `exchange-core.sha256` or builds from `exchange-core.git-sha`. A repository/local artifact can be replaced under the same version while the build still passes.
   - evidencePointer: `39d9149d:surprising-parent/pom.xml:35-39,64-68,133-154`; `git grep -n 'exchange-core.git-sha\|exchange-core.sha256' 39d9149d -- ':!*.md'` returns only those property declarations.

## findings

### Verified PASS areas

- **R1 corrupt snapshot parsing:** outer CRC32C is checked before structural parsing; count ranges, long arithmetic, event budgets, exact remaining length, duplicate records and nested matcher CRC/module CRC are rejected. Evidence: `CoreStateSnapshotCodec.java:99-188,191-300`; `MatcherSnapshotCodec.java:89-167`.
- **R2 integer overflow:** encoding uses `Math.addExact`, `multiplyExact`, and `toIntExact`; decode count arithmetic is promoted to `long`. Evidence: `CoreStateSnapshotCodec.java:47-56,137-147,230-237`.
- **R3 snapshot provenance manifest:** fork SHA, artifact SHA, route/config, registries, watermark and exactly one ME0/RE0 module are constructor-enforced. Evidence: `MatcherSnapshot.java:36-84`; `MatcherSnapshotCodec.java:135-158`.
- **R5 fail-closed restore:** native module import/start/reconciliation/hash mismatch stops the engine and throws fatal divergence; service replaces current state only after complete decode/restore. Evidence: `DeterministicExchangeCoreAdapter.java:80-112`; `SurprisingClusteredService.java:217-221`.
- **R7 no fallback/replay/journaling:** restore uses `InitialStateConfiguration.fromSnapshotOnly`; serialization config disables journaling; the fork throws for journal write/replay/enable operations. Evidence: `DeterministicExchangeCoreAdapter.java:365-389`; fork `InMemorySerializationProcessor.java:174-214`.
- **R8 sole executable book / exact reconciliation:** deleted `CoreBookState`; expected and native OPEN orders are compared as exact `TreeMap<Long, ReconciledOrder>` equality; full exchange-core state hash and matching-book aggregate hash are persisted and checked. Evidence: deletions in `39d9149d^..39d9149d`; `DeterministicExchangeCoreAdapter.java:281-324`; `MatcherSnapshot.java:87-93`.
- **Pinned artifact observed locally:** installed JAR SHA-256 is `b2ee6f235f9dbde4d2a37e407a8a855938b0f7cc0622ea28cb6e778552ff934a`, and contains the changed fork classes. This confirms the reviewed local artifact, but does not cure R4's absent build enforcement.

### NOTE — checksum threat model

CRC32C detects accidental corruption but is not an authenticity primitive. The manifest's hard-coded provenance fields can be rewritten together with recomputed CRC by an active attacker. This is a note rather than an additional blocker because R4 already captures the stated pinned-artifact failure and the brief did not explicitly require signatures/MACs.

### NOTE — remove-ai-slops / programming direct pass

- No deletion-only test, test that merely proves requested removal, tautological assertion, or clearly useless test was found in the changed tests.
- FIFO round-trip, exact reconciliation failure, corruption rejection and fatal propagation tests assert observable behavior and are relevant.
- Coverage gap: no adversarial test streams an oversized/non-ending Aeron snapshot, and no test/build gate substitutes a wrong same-version JAR and proves digest rejection.
- `MatcherSnapshotCodec`'s 1 GiB total and 512 MiB per-module limits are technically bounded but unusually large and allocate/copy module bytes repeatedly; this adds memory pressure. It is a NOTE because the explicit blocker is the wholly unbounded outer loader.
- The located older code-review/manual-QA reports concern the parent pre-migration design and do not review commit `39d9149d`; they cannot establish current skill-perspective coverage. Direct review supplies the required coverage.

## checkedArtifactPaths

- Main commit/diff: `39d9149de35797503d751ee2c32838fc58096286`, `39d9149d^..39d9149d` (31 files)
- Fork commit: `/Users/atomex/Desktop/exchange-core-lilaizhencn` at `9819b9fea48b8b962bdef6bfcf67ed5f5a04981f`
- Local artifact: `/Users/atomex/.m2/repository/exchange/core2/exchange-core/0.5.10-emporia/exchange-core-0.5.10-emporia.jar`
- Main production/test files in the exact diff, especially `CoreStateSnapshotCodec`, `MatcherSnapshotCodec`, `MatcherSnapshot`, `DeterministicExchangeCoreAdapter`, `SurprisingClusteredService`, `TradingCoreRuntime`, and their changed tests
- Fork `InMemorySerializationProcessor`, open-orders report API/result/configuration, POM and tests
- `.omo/evidence/unique-executable-orderbook-gate-review.md`
- `.omo/evidence/core-book-exchange-core-ownership-code-review.md`
- `.omo/evidence/manual-qa-activity-book-recovery-20260816/activity-book-recovery-manual-qa.md`
- `.omo/ultrawork-notepad-20260804.md` (not task-specific)

## exactEvidenceGaps

- No task-scoped executor evidence path, current code-review report, manual-QA matrix, or task-specific notepad path was supplied/found for commit `39d9149d` and fork commit `9819b9f`. Older reports describe the superseded `CoreBookState`/replay implementation.
- The supplied claims of 300 fork tests and 246 affected-reactor tests were not accompanied by log artifact paths.
- Independent targeted Maven execution was attempted at exact main HEAD, but Maven Enforcer stopped before tests because the available runtime is JDK 21.0.10 and the project requires JDK 25. This gap is not a rejection basis.
- `git show --check` passes for both exact commits.
- No configured task-specific security scanner result was supplied; static/security scan evidence is therefore N/A beyond this direct audit.

