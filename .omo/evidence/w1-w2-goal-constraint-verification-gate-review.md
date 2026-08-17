# W1/W2 exact-SHA goal and constraint gate

## recommendation

**APPROVE / PASS**

No concrete W1/W2 success criterion fails at main `7e78e04ae4dac16d364117392f960a65a4f4db2d` and fork `627ddf68fbb0594b07e4b59a1a0e3377354e26b9`.

## blockers

None.

## originalIntent

Make exchange-core the sole executable price/time FIFO book. Keep order business metadata and derived indexes in Core, pair native ME0/RE0 with CoreState v6, restore only through `fromSnapshotOnly`, prohibit production per-order replay/rebuild/resubmit and journaling, reconcile every active order exactly in O(active orders), verify complete engine/book hashes, fail closed for corrupt/oversized/provenance-mismatched/divergent state, preserve ProductLine and CROSS/ISOLATED ownership boundaries, and deploy one logical three-member Core per ProductLine with no hotspot split.

## desiredOutcome

The exact fork SHA produces coordinate `exchange.core2:exchange-core:0.5.15-emporia` as a clean-source, immutable-archive-built, reproducible JAR with SHA-256 `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21`; main mechanically accepts only that whole JAR and its embedded exact clean SHA. Runtime has one executable book and can become usable after recovery only when paired native state, provenance, registries, product line, all open-order fields, and complete hashes agree.

## userOutcomeReview

PASS. Production source and README/spec statements align. `TradingState v19` has no executable FIFO. The adapter is the sole matching/book owner, configures one ME and one RE, uses matching-only risk with margin disabled, disables journaling, and restores only with `fromSnapshotOnly`. Snapshot v6 embeds the Core state and exactly ME0/RE0; restore validates the outer and nested envelopes, fixed route/product/provenance/config/registry values, exact OPEN-order map equality, and engine/book hashes before successful construction. Projection API names are `CoreOrderBookQuery`, `CoreOrderBookView`, and `orderBookProjection`; stable wire/level names remain `BOOK_STATE_QUERY` and `CoreBookLevelView`, and the projection only calls exchange-core's read-only order-book API.

## criterion matrix

| Criterion | Result | Evidence |
|---|---|---|
| Sole executable FIFO; Core metadata/indexes only | PASS | Main deletion of `CoreBookState`/`CoreBookOrder`; `DeterministicExchangeCoreAdapter.java:116-243,334-364`; derived-only rebuilds in `TradingCoreRuntime.java:216-224` |
| CoreState v6 pairs native ME0/RE0 | PASS | `CoreStateSnapshotCodec.java:23-43,96-101,179-194`; `MatcherSnapshot.java:65-84` requires exactly `MATCHING_ENGINE_ROUTER/0` and `RISK_ENGINE/0` |
| Snapshot-only restore; no production replay/rebuild/resubmit | PASS | `DeterministicExchangeCoreAdapter.java:80-113,377-394`; literal audit found only clean start for new state and derived-index rebuild methods, not order-book recovery |
| Journaling disabled | PASS | Main adapter lines 390-393; fork `InMemorySerializationProcessor.java:174-214` rejects journal write/enable/replay |
| Exact O(active orders) reconciliation | PASS | Main adapter lines 283-323 uses two hash maps and exact equality; fork `OpenOrdersReportResult.java:19-27,40-51` performs linear copy/duplicate validation without sorting |
| Complete open-order fields and engine/book hashes | PASS | Adapter `ReconciledOrder` covers symbol/order/user/side/price/size/filled/reserve at lines 300-320,452-460; state and matching-book hashes at 325-331 and restore comparison at 101-105 |
| Corrupt/oversize/provenance mismatch/divergence fail closed | PASS | `CoreStateSnapshotCodec.java:104-194,197+`; `MatcherSnapshotCodec.java:92-174`; `MatcherSnapshot.java:44-93`; adapter stops and throws fatal divergence at 107-113 |
| ProductLine isolation and CROSS/ISOLATED boundary | PASS | ProductLine is encoded/expected in outer and matcher state; adapter configuration is `MATCHING_ONLY` and `MARGIN_TRADING_DISABLED` at lines 382-389, leaving margin mode in Core business/risk metadata |
| One logical three-member Core per ProductLine; no hotspot split | PASS | Fixed `coreShardId=default`, route v1, one ME/one RE in `MatcherSnapshot.java:36-42` and adapter lines 382-389; README and module README state three identical members and no margin/hot-symbol split |
| Projection naming is not a second book | PASS | `CoreOrderBookQuery.java`, `CoreOrderBookView.java`, `MatchingAeronGateway.java:26-31`, `CoreMarketDataProjection.java:61`; stable `BOOK_STATE_QUERY` and `CoreBookLevelView` remain |
| Fork artifact security | PASS | Fork `pom.xml:340-468` validates clean Git, archives attested SHA, compiles archived source, and rechecks HEAD/worktree/JAR provenance after JAR creation |
| Main whole-JAR and embedded SHA validation | PASS | Main service `pom.xml:57-90`; parent POM exact coordinate/SHA/digest at lines 35-39 |
| Rollout boundary | PASS | Main README lines 165-171 and module README lines 83-93 identify v6/v19 incompatibility, fresh-cluster first rollout, identical members, and forward-only recovery after v6 traffic; P4-P6 real-environment gates remain explicitly outside W1/W2 acceptance |

## reproduced evidence

- Both supplied worktrees were clean and exactly at their requested SHAs before and after review.
- Clean detached clone package under `/tmp/w1w2-gate.X4n93t/fork` produced `exchange-core-0.5.15-emporia.jar` with exact SHA-256 `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21` and embedded `fork.git.sha=627ddf68...`, `fork.git.dirty=false`.
- Detached main clone `/tmp/w1w2-gate.X4n93t/main` passed JDK 25 `mvn -q -pl surprising-aeron-core/surprising-aeron-service validate -DskipTests` against the exact local dependency.
- Existing exact-input evidence reports fork 302/302, main affected reactor 193/193, focused runtime audit 13/13, clean reproducibility, post-JAR mutation rejection, and independent wrong-digest/wrong-SHA rejection. These reports were treated as corroboration; source and artifact claims above were directly checked.
- `git diff --check` passed over the cumulative main W1/W2 range.

## remove-ai-slops and programming review

Direct review covered cumulative W1/W2 production code, tests, and both tip build diffs. No deletion-only/removal-presence test, tautology, prose pin, output-derived expected value, or implementation-mirroring test was found. Native round-trip, corruption, duplicate/divergence, order-field reconciliation, and provenance tests/checks exercise observable boundaries. The open-order report and reconciliation remain linear and do not introduce parsing/normalization/extraction solely to satisfy tests. Large inherited Java classes, nested snapshot parsing, and temporary allocation overhead are maintenance notes, not failures of a stated W1/W2 criterion. The existing code-review report explicitly includes the same skill perspectives and overfit categories, though direct review—not report prose—supports this approval.

## checked artifact paths

- `/tmp/surprising-w1w2-final.zo1EL8/main` at exact main SHA
- `/tmp/surprising-w1w2-final.zo1EL8/fork` at exact fork SHA
- `/tmp/w1w2-gate.X4n93t/fork/target/exchange-core-0.5.15-emporia.jar`
- `/Users/atomex/.m2/repository/exchange/core2/exchange-core/0.5.15-emporia/exchange-core-0.5.15-emporia.jar`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/w1-w2-code-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/w1-w2-review-fdfe2114.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/`
- `/tmp/surprising-w1w2-final.zo1EL8/main/.omo/ultrawork-notepad-20260804.md` (unrelated notepad, not relied upon)

## exact evidence gaps and notes

- No ULW loop plan exists, so the fallback evidence path is used.
- No task-specific notepad or single consolidated manual-QA matrix for the final two tip SHAs was supplied. Direct source/artifact verification and prior exact-input logs cover W1/W2 acceptance; this is not tied to a failed criterion.
- This lane did not rerun the full 302- and 193-test suites or real three-member/Kafka/PG/maker/funds/24h gates. The former are recorded executor evidence; the latter are explicitly P4-P6 rollout gates, not W1/W2 implementation acceptance.
- `SurprisingClusteredService.loadSnapshot` uses a bounded logical length but `ByteArrayOutputStream` growth plus `toByteArray()` can transiently allocate above 64 MiB. Oversize input still fails closed before exceeding the logical cap, so this is an allocation-efficiency NOTE rather than a stated-criterion blocker.
