# Security/Reliability Final Gate Review

## recommendation

**APPROVE (PASS).** No stated success criterion is violated at main commit `18f62de4e16a1493079b918a8b37c7357dd66d1d` paired with fork commit `0511efca1458e9733d7911732ccab1fd83fc373b`, coordinate `exchange.core2:exchange-core:0.5.13-emporia`, and JAR SHA-256 `ed2dbcf86f2ebf6c1b2bd5642e7585ce5568104da9b61f3649a5a506cd37931f`.

## blockers

None.

## originalIntent

Ship W1/W2 with exchange-core as the sole executable FIFO; retain only Core metadata and derived indexes; restore paired native ME0/RE0 state snapshot-only; prohibit production order replay/rebuild/resubmit; reconcile exactly in O(active orders); fail closed on malformed, corrupt, provenance-mismatched, or divergent state; bound snapshot ingest; and make the fork artifact clean-source, exact-SHA, reproducible, and consumer-validated. Preserve unrelated Gateway AD state and make no product edits or commits during review.

## desiredOutcome

A decisive exact-SHA security/reliability verdict proving snapshot corruption and length boundaries, fail-closed restoration and divergence, native paired restore and FIFO continuity, artifact digest/provenance positive and negative gates, dirty-worktree rejection, reproducibility, and documented pre-launch rollback compatibility.

## userOutcomeReview

PASS. The supplied exact commits are checked out in both repositories. Two independent clean JDK 25 builds of fork SHA `0511efca...` passed the full Maven test/package path and produced byte-identical JARs with the supplied SHA-256. The JAR embeds `fork.git.sha=0511efca...` and `fork.git.dirty=false`; adding an untracked file makes the fork build exit 1. Main SHA `18f62de4...` validates the correct artifact, rejects wrong whole-JAR bytes, and—when supplied a separately re-hashed JAR containing a wrong embedded Git SHA—rejects provenance independently of the digest check.

Targeted main tests under IBM Semeru JDK 25 passed for `MatcherSnapshotCodecTest`, `DeterministicExchangeCoreAdapterTest`, `SurprisingClusteredServiceTest`, and `ActiveOrderIndexTest`. These exercise CRC/truncation and ingest-capacity boundaries, native restore and fatal divergence, state-preservation on corrupt restore, and derived active-order indexing. Fork full-suite tests exercise real ExchangeCore ME/RE persist and snapshot-only startup. Source inspection confirms bounded 48 MiB matcher/64 MiB outer snapshots, per-module limits and CRC32C, exact paired module checks, `fromSnapshotOnly`, no production live-order replay/rebuild/resubmit, and only derived-index rebuilds. README rollback instructions allow rollback only before v6 accepts commands and prohibit old binaries from reading v6/v19 afterward.

The exact deltas introduce only build provenance enforcement, version/SHA/digest pins, constants, and matching documentation. Direct `remove-ai-slops`/`programming` review found no excessive, deletion-only, requested-removal-only, tautological, output-derived, or implementation-mirroring tests; no unnecessary production extraction/parsing/normalization; no maintenance burden, false confidence, or scope drift. The available comprehensive code-review report explicitly covers those perspectives and overfit classes but is bound to predecessor SHAs; the direct exact-SHA pass supplies current coverage.

## criterionResults

| Criterion | Result | Evidence |
|---|---|---|
| SR-1 sole executable FIFO; metadata/derived indexes only; no production replay/rebuild/resubmit | PASS | Main source and README at exact SHA; targeted adapter/index tests; only `TradingCoreRuntime` derived indexes call `rebuild` |
| SR-2 native paired ME0/RE0 snapshot-only restore and fail closed | PASS | Fork full suite in two exact-SHA clean clones; main `DeterministicExchangeCoreAdapterTest` and `SurprisingClusteredServiceTest` |
| SR-3 exact O(active-order) reconciliation and divergence handling | PASS | Adapter/index source inspection and targeted tests; no reconciliation sort; mismatches throw fatal divergence |
| SR-4 CRC and length boundaries; bounded ingest | PASS | `MatcherSnapshotCodec` 48 MiB/32 MiB module limits, `CoreStateSnapshotCodec` 64 MiB limit, `ensureSnapshotCapacity`; codec/service tests pass |
| SR-5 exact digest and embedded Git provenance positive/negative gates | PASS | Main validate rc=0; wrong digest rc=1; wrong embedded SHA with matching altered-JAR digest rc=1 |
| SR-6 clean-worktree rejection, exact SHA embedding, reproducibility | PASS | Dirty fork build rc=1; embedded properties exact; two JAR hashes both `ed2dbcf...` |
| SR-7 rollback compatibility | PASS | Root and Aeron READMEs: pre-command rollback only; no v6/v19 backward reader/fallback |
| SR-8 exact identities and unrelated Gateway state preserved | PASS | `rev-parse` equals both requested SHAs; main status still contains `AD .../WebSocketRuntimeHints.java`; neither exact commit touches it |

## checkedArtifactPaths

- `/Users/atomex/Desktop/surprising/surprising-ex`, commit and diff `18f62de4^..18f62de4`
- `/Users/atomex/Desktop/exchange-core-lilaizhencn`, commit and diff `0511efc^..0511efc`
- `.omo/plans/w1-w2-single-book-snapshot.md`
- `.omo/evidence/w1-w2-native-exchange-core-snapshot-migration-code-review.md`
- `.omo/evidence/w1-w2-review-18f62de4.md`
- `.omo/evidence/manual-qa-native-snapshot-20260816/`
- Installed JAR `/Users/atomex/.m2/repository/exchange/core2/exchange-core/0.5.13-emporia/exchange-core-0.5.13-emporia.jar`
- Independent reproduction trees and negative-gate logs under `/tmp/w1w2-final-gate.JKYfY5/`

## exactEvidenceGaps

- No ULW plan exists for this review session (`ULW_LOOP_PLAN_MISSING`), so the required fallback report path is used.
- No notepad path was supplied.
- The comprehensive code-review report is predecessor-SHA-bound; direct inspection and reproduction cover the exact follow-up commits.
- No live three-member Aeron cluster or external Kafka/database/provider environment was started. This is a deployment-evidence gap, not a failed stated criterion: the exact follow-up changes are provenance/pinning only, while exact-SHA module tests cover the inherited snapshot and divergence paths.

## notes

- Maven/JDK emitted upstream Unsafe, native-access, Mockito-agent, and SLF4J warnings; tests and gates still exited successfully. These are not stated-criterion failures.
- Review created no product-code edits and no commit. Temporary clones/logs are isolated under `/tmp`. Unrelated Gateway AD state remains present.
