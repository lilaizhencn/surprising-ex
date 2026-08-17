# Security and reliability W1/W2 gate review

## recommendation

APPROVE

## blockers

None.

## originalIntent

Ship W1/W2 with exchange-core as the sole executable price/time FIFO book, paired CoreState v6 and native ME0/RE0 snapshots, bounded and checksummed untrusted snapshot decoding, fail-closed provenance/divergence handling, snapshot-only production restore, immutable fork source packaging, package-phase TOCTOU closure, reproducible pinned artifacts, and no production per-order replay or second executable book. Specifically, prevent mutable working-tree source from entering the executable fork JAR after initial attestation.

## desiredOutcome

- Main is exactly `7e78e04ae4dac16d364117392f960a65a4f4db2d`; fork is exactly `627ddf68fbb0594b07e4b59a1a0e3377354e26b9`.
- Main consumes only `exchange.core2:exchange-core:0.5.15-emporia` with whole-JAR SHA-256 `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21` and exact clean embedded fork SHA.
- Fork compilation, resources, tests, and delombok use an archive of the initially attested commit; after JAR creation, HEAD, worktree cleanliness, and packaged provenance are checked again.
- Snapshot corruption, oversize allocation, provenance mismatch, version mismatch, and Core/matcher divergence fail closed; restore uses paired native snapshots without active-order replay.
- Projection wire names remain compatible without creating a second executable book.

## userOutcomeReview

PASS. The shipped artifacts meet the requested W1/W2 outcome. The fork build redirects `sourceDirectory`, `testSourceDirectory`, resources, test resources, and delombok to `target/fork-source-tree`, which is populated by `git archive` of the SHA captured only after a clean-worktree check. The executable JAR therefore does not compile or copy Java/resources from the mutable working tree. The package-phase antrun execution requires the JAR to already exist, then rejects changed HEAD or any tracked/untracked worktree state and validates the JAR's embedded exact SHA and clean marker. This closes the named working-tree TOCTOU route.

Main independently validates the complete dependency JAR SHA-256 before compilation and checks embedded provenance. The locally resolved JAR reproduced the pinned SHA and exact clean fork metadata. Snapshot codecs enforce total and module bounds before allocation/copy, CRC32C at outer and matcher/module layers, exact version/module counts, trailing-byte rejection, and Core/matcher reconciliation. Native restore selects `fromSnapshotOnly`; journaling is disabled. Core's active-order data is metadata/derived indexing, while read-only book projection is sourced from exchange-core.

The initial no-snapshot branch uses `cleanStart` for a fresh Core. This is not a restore fallback and does not violate the stated from-snapshot-only recovery criterion.

## criterion results

- `SR-1 untrusted snapshot allocation/checksums`: PASS — `CoreStateSnapshotCodec.java:32,52-60,98-125,309-312`; `MatcherSnapshotCodec.java:28-30,37,67-85,92-103,125-164,176-190`.
- `SR-2 atomic restore/fatal behavior`: PASS — paired matcher/business state is decoded and reconciled before restored state is returned at `CoreStateSnapshotCodec.java:297-306`; no production order replay/rebuild path was found.
- `SR-3 provenance/whole-JAR validation`: PASS — main `surprising-aeron-service/pom.xml:57-91`; reproduced local whole-JAR digest and embedded provenance.
- `SR-4 immutable source export`: PASS — fork `pom.xml:341-352,360-405,527-548`.
- `SR-5 package-phase TOCTOU closure`: PASS — fork `pom.xml:409-468`; the check first requires the packaged JAR and then re-attests HEAD/worktree and packaged provenance.
- `SR-6 reproducibility/pinning`: PASS — exact coordinate, fork SHA, JDK, and JAR digest are pinned in `surprising-parent/pom.xml:35-39`; local artifact digest reproduced exactly. The supplied two-clean-build result was not rerun, but no criterion requires a third reproduction and the immutable archive/pinned toolchain path supports it.
- `SR-7 rollback/version rules`: PASS — CoreState rejects unsupported versions and fork/main coordinate, SHA, and digest move together in the reviewed commits and READMEs.
- `SR-8 failure-recovery tail risk`: PASS for W1/W2 — oversize/corrupt/mismatch/divergence fail closed and native snapshot restore preserves FIFO. P4-P6 deployment/soak gates are explicitly outside implementation acceptance.
- `SR-9 sole executable book/projection compatibility`: PASS — `CoreMatchingStateTest` exercises FIFO across native snapshot without CoreBookState; `BOOK_STATE_QUERY` and `CoreBookLevelView` remain projection names while production request/view names are `CoreOrderBookQuery/View`.

## direct remove-ai-slops / programming pass

- Reviewed the fork final diff, main final diff, production packaging configuration, snapshot codecs, adapter restore configuration, and focused tests directly.
- No excessive/useless, deletion-only, requested-removal-only, tautological, prose-pinning, or implementation-mirroring test was introduced by the final changes. The focused FIFO restore test asserts externally observable matching order after native restore.
- The archive extraction, checksum parsing, and provenance checks are necessary trust-boundary controls, not speculative normalization or needless production extraction.
- The POM security logic is verbose but corresponds to distinct attestation, immutable export, post-package re-attestation, and embedded-provenance checks. It creates no criterion-breaking maintenance burden or scope drift.
- Existing code-review reports include explicit `remove-ai-slops` / `programming` and overfit criteria in several historical reports, including `.omo/evidence/core-book-exchange-core-ownership-code-review.md`. No report specifically covering only the final `0.5.15-emporia` packaging commit was found. The direct pass above supplies that missing coverage; this is an evidence note, not a blocker.

## reproduced evidence

- Both review worktrees' `HEAD` matched the requested exact commits and were clean before and after inspection.
- `git diff --check HEAD^ HEAD`: PASS in main and fork.
- Local dependency JAR SHA-256: `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21`.
- Embedded dependency provenance: `fork.git.sha=627ddf68fbb0594b07e4b59a1a0e3377354e26b9`, `fork.git.dirty=false`.
- JDK 25 focused command: `mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=CoreStateSnapshotCodecTest,MatcherSnapshotCodecTest,CoreMatchingStateTest test` with Semeru 25.0.2.1. Maven selected and ran `CoreMatchingStateTest`: PASS, 18/18, zero failures/errors/skips. The two other requested class patterns were not present as independently selected Surefire classes, so they contributed no separate count.
- The supplied 302/302 fork, 193/193 reactor, 13/13 runtime, mutation-during-package, wrong-digest, wrong-SHA, and two-build reproducibility claims were treated as untrusted supporting context, not as independently reproduced results.

## checked artifact paths

- `/tmp/surprising-w1w2-final.zo1EL8/main` at `7e78e04ae4dac16d364117392f960a65a4f4db2d`
- `/tmp/surprising-w1w2-final.zo1EL8/fork` at `627ddf68fbb0594b07e4b59a1a0e3377354e26b9`
- Fork `pom.xml`, `README.md`, `CHANGELOG.md`, `benchmarks/pom.xml`
- Main `surprising-parent/pom.xml`, `surprising-aeron-core/surprising-aeron-service/pom.xml`
- Main `CoreStateSnapshotCodec.java`, `MatcherSnapshotCodec.java`, `MatcherSnapshot.java`, `DeterministicExchangeCoreAdapter.java`, `TradingCoreRuntime.java`, `CoreProbeState.java`, `TradingCoreState.java`
- Main `CoreMatchingStateTest.java` and relevant protocol/projection classes
- Local Maven artifact `~/.m2/repository/exchange/core2/exchange-core/0.5.15-emporia/exchange-core-0.5.15-emporia.jar`
- Evidence reports under `/tmp/surprising-w1w2-final.zo1EL8/main/.omo/evidence/`, especially `core-book-exchange-core-ownership-code-review.md` and `unique-executable-orderbook-gate-review.md`
- ULW status: no plan (`ULW_LOOP_PLAN_MISSING`), so this report uses the required `.omo/evidence/` fallback.

## exact evidence gaps

- No task-specific executor evidence bundle, manual-QA matrix, or final notepad path was supplied for this gate.
- No task-specific code-review report for the final fork immutable-archive/TOCTOU commit was found; direct review covers it.
- The adversarial package-time mutation and two-clean-build reproducibility experiments were not independently rerun in this read-only gate.
- The full 302/302 fork and 193/193 main reactor suites were not rerun; the focused JDK 25 native-snapshot/FIFO suite was reproduced.
- These gaps are not tied to a failed stated W1/W2 criterion and therefore are NOTES, not blockers.
