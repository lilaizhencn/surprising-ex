# W1/W2 native exchange-core snapshot migration: final gate review

## recommendation

**REJECT (FAIL), HIGH confidence.**

## blockers

1. **violatedCriterion: G5 — "exact O(active-order) reconciliation"**
   - The fork canonicalizes every open-order report with `ArrayList.sort`, which is O(n log n), and the Aeron adapter then builds two `TreeMap<Long, ReconciledOrder>` instances, whose n insertions are also O(n log n). Exact equality does not require ordering. The shipped reconciliation therefore does not meet the explicit linear-time requirement.
   - evidencePointer: `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/common/api/reports/OpenOrdersReportResult.java:22-31` at `9819b9fea48b8b962bdef6bfcf67ed5f5a04981f`; `/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:281-314` at `39d9149de35797503d751ee2c32838fc58096286`.

## originalIntent

Migrate W1/W2 to a paired native exchange-core snapshot design at main commit `39d9149de35797503d751ee2c32838fc58096286` and fork commit `9819b9fea48b8b962bdef6bfcf67ed5f5a04981f`: exchange-core is the only executable FIFO/order book; Aeron retains business metadata, balances, positions, risk, and derived indexes; CoreState v6 pairs Core state with native ME0/RE0; TradingState v19 contains no book; restore is snapshot-only, exactly linear in active orders, fully hash-checked, and fail-closed; one three-member logical core serves each ProductLine with CROSS/ISOLATED together and no hot-pair sharding.

## desiredOutcome

An exact, JDK-25-buildable artifact in which recovery imports the reviewed fork's native matching/risk modules, never reconstructs or replays a second FIFO, validates all active-order fields and complete engine/book hashes in O(active orders), rejects divergence, preserves ProductLine isolation and funds ownership, and keeps journaling and hot-path external I/O disabled.

## userOutcomeReview

The main architectural outcome is substantially achieved and the exact commits build/test successfully under JDK 25. Native ME0/RE0 state is embedded in CoreState v6; TradingState v19 and `TradingCoreState` no longer contain `CoreBookState`; restore uses `fromSnapshotOnly`; the service performs exact field equality and full engine/book hash checks; divergence is fatal; journaling is disabled; and the six-product-line FIFO recovery test passes. However, the implementation's ordered report and ordered maps make reconciliation O(n log n), so the expressly required user-visible/operational complexity guarantee is not shipped.

## goal and constraint matrix

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| G1 | exchange-core is sole executable FIFO/order book | achieved | `CoreBookState` and `CoreBookOrder` deleted; `TradingCoreState` has no book field; order-book queries route to `DeterministicExchangeCoreAdapter`. |
| G2 | Aeron retains metadata, funds, positions, risk, derived indexes | achieved | Full `TradingCoreState` and v19 codec retain users, balances/reservations, positions, orders, instruments, risk, treasury, leverage/algo/trigger state. |
| G3 | CoreState v6 pairs Core state with native ME0/RE0 | achieved | `CoreStateSnapshotCodec.VERSION = 6`; matcher codec requires exactly one `MATCHING_ENGINE_ROUTER/0` and one `RISK_ENGINE/0`. |
| G4 | TradingState v19 has no book | achieved | `TradingStateSnapshotCodec.VERSION = 19`; book serialization removed; deleted book-state classes. |
| G5 | restore is snapshot-only and exact O(active-order) reconciliation | **partial / blocker** | `fromSnapshotOnly` and exact field equality are present, but fork sort plus two service `TreeMap`s are O(n log n). |
| G6 | full engine/book hash and fail-closed | achieved | Snapshot stores both hashes; restore reconciles then compares both; fatal divergence stops/rejects further state actions. |
| G7 | one ProductLine per three-member core; no hot-pair sharding; CROSS/ISOLATED together | achieved | fixed shard `default`, route 1, matching/risk engine count 1; topology retains three members; all six ProductLines exercise native FIFO restore; margin modes remain state fields, not routing keys. |
| C1 | Java/Maven JDK25 | achieved | Reproduced fork `mvn -q test` and main affected reactor `mvn ... clean test -q` with IBM Semeru JDK 25.0.2.1. |
| C2 | funds safety | achieved for changed scope | Six-line snapshot/FIFO test includes balance setup and native continuation; fork integration checks global balances zero after restored FIFO match. No changed fund-authority boundary moved into exchange-core. |
| C3 | six ProductLine isolation | achieved | ProductLine is encoded/validated in outer, matcher, trading, and domain state; parameterized test covers all six variants. |
| C4 | no wallet | achieved | No wallet module/service is introduced or started by the changed code/tests. |
| C5 | no DB/Kafka/Redis/network I/O in hot path | achieved | Adapter and reducer use in-memory exchange-core/Aeron structures; no changed hot-path DB/Kafka/Redis/network dependency found. |
| C6 | no rebuild/replay/retry/fallback/hidden FIFO | achieved | matcher rebuild/recovery/resubmit paths and book-priority state deleted; restore uses native snapshot only. Fresh initial creation still legitimately uses `cleanStart`; snapshot restore does not. Trigger business retry remains outside matcher-recovery semantics. |
| C7 | journaling disabled | achieved | `enableJournaling(false)`; fork journal operations throw and replay is not used for restore. |
| C8 | preserve unrelated gateway AD change | achieved at exact-commit scope | `39d9149d^..39d9149d` does not touch `WebSocketRuntimeHints.java`; current unrelated `AD` worktree state was not modified by this review. |
| C9 | docs only root/module README | achieved | Main commit changes only root `README.md` and module `surprising-aeron-core/README.md`; no `docs/` or `scripts/` additions. |
| C10 | proportional tests for service/client/exporter/tools | achieved | Clean affected reactor passed under JDK 25; current Surefire reports across `-am` reactor total 825 tests, 0 failures, 0 errors, 22 skipped. Dependency tree resolves only `exchange-core:0.5.10-emporia`. |

## edge cases checked

- FIFO across native snapshot for all six ProductLines: covered and passing.
- Snapshot attempted with pending matching: rejected.
- Corrupt outer/native snapshot: CRC32C rejection covered.
- Core/native active-order field mismatch: fatal rejection covered.
- Full engine and matching-book hash mismatch: fail-closed production checks present.
- Missing/duplicate/wrong-instance ME0/RE0 modules and registry/watermark mismatch: constructor/codec validation present.
- Matcher timeout, malformed result, Core reducer rejection, or arithmetic divergence: no matcher retry/rebuild; fatal state is sticky.
- CROSS and ISOLATED routing: remain in the same ProductLine state/reducer and do not select a separate matcher/core.
- Duplicate open order IDs: rejected, but the canonical sort used around this check is the G5 complexity blocker.

## remove-ai-slops and programming review

Direct pass performed over both diffs, changed production code, and changed tests. No deletion-only test, test merely asserting a requested class removal, tautological output-derived assertion, prompt/prose pin, or clearly useless test was found. FIFO round-trip, corruption, mismatch, fatal propagation, and lane-barrier tests cover behavior. The material slop/overfit finding is the unnecessary canonical sorting and ordered-map extraction: it adds production work, is encouraged by the fork test's order-sensitive `List.of(501L, 502L)` assertion, and directly breaks G5. Several large pre-existing Java units exceed the skill's generic 250-LOC preference, but file-size/style alone is not a stated criterion and is therefore a NOTE, not a blocker.

The newly supplied code-review report `.omo/evidence/w1-w2-native-exchange-core-snapshot-migration-code-review.md` explicitly records both `remove-ai-slops` and `programming` perspectives and independently identifies the same O(n log n) reconciliation defect. Its additional allocation-bound and artifact-pin concerns are retained as NOTES here because the brief does not state a concrete size ceiling or require Maven-time digest enforcement; the exact fork/JAR provenance itself was reproduced.

## checked artifact paths

- Main exact diff: `39d9149d^..39d9149d`, all 31 changed files, with full production/test files inspected at commit objects.
- Fork exact diff: `9819b9f^..9819b9f`, all 9 changed files, with full report/serialization/test files inspected.
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/plans/w1-w2-single-book-snapshot.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/w1-w2-native-exchange-core-snapshot-migration-code-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/w1-w2-native-snapshot-migration-gate-review.md`
- `/Users/atomex/.m2/repository/exchange/core2/exchange-core/0.5.10-emporia/exchange-core-0.5.10-emporia.jar`
- Fork repository `/Users/atomex/Desktop/exchange-core-lilaizhencn`, whose `master` and `origin/master` contain exact commit `9819b9f...` and whose origin is `https://github.com/lilaizhencn/exchange-core.git`.

## reproduced verification

- `git diff --check 39d9149d^ 39d9149d`: PASS.
- Fork JDK 25 `mvn -q test`: PASS; 300 tests, 0 failures/errors/skips.
- Main JDK 25 affected reactor with clean: `mvn -pl :surprising-aeron-service,:surprising-aeron-client,:surprising-aeron-exporter,:surprising-aeron-tools -am clean test -q`: PASS; current reports 825 tests, 0 failures, 0 errors, 22 skipped. An initial non-clean run hit stale compiled output and was discarded after source inspection; clean reproduction is authoritative.
- Main dependency tree: only `exchange.core2:exchange-core:jar:0.5.10-emporia:compile`.
- Local JAR SHA-256: `b2ee6f235f9dbde4d2a37e407a8a855938b0f7cc0622ea28cb6e778552ff934a`.
- Fork provenance: exact commit is local `master`, `origin/master`, and origin's contained branch.

## exact evidence gaps and notes

- No task-scoped executor log, manual-QA matrix, or task-specific notepad path was supplied. The exact-commit builds/tests and source-level recovery scenarios were reproduced directly, so these missing reports are not separate blockers.
- No live three-member Aeron recovery, provider/market-maker, Kafka, or wallet environment was run. The brief's affected-module proportional test criterion and repository README explicitly permit module-level coverage for this bounded change; this remains residual integration risk, not a failed stated criterion.
- Outer Aeron snapshot ingestion lacks an explicit total-size cap, and fork/JAR SHA properties are not Maven-enforced. These are funds-safety/supply-chain hardening NOTES; no stated numeric allocation criterion or build-time checksum-enforcement criterion was provided.

