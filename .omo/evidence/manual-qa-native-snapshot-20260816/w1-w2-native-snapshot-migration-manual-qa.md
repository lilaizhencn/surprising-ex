# manualQa: PASS

Review target: main commit `39d9149de35797503d751ee2c32838fc58096286` and fork commit `9819b9fea48b8b962bdef6bfcf67ed5f5a04981f`.

The clean detached worktrees were exact-SHA and clean. JDK 25 was IBM Semeru 25.0.2. The main targeted reactor run passed 52/52; the margin/snapshot boundary run passed 50/50; the fork full suite passed 300/300. No product source was edited or committed by this QA run.

## Scenario brainstorm

P0: exact SHA, JDK 25 enforcement, fork artifact identity, sole executable book, ME0/RE0 pairing, native round-trip, FIFO preservation, active-order reconciliation, engine/book hashes, metadata divergence, hash divergence, matcher corruption, outer corruption, pending-match rejection, snapshot admission fail-closed, fatal divergence propagation, timer backpressure.

P1: all six ProductLines, spot funds, derivative margin/positions, option premium/positions, CROSS/ISOLATED state, command replay/idempotency, post-restore matching, journal-disabled restore, single dependency provenance.

P2: CoreState v6, TradingState v19 without book state, default route/core shard, duplicate module rejection, clean diff, ZIP metadata-only build variance.

## Command register

- `C1`: in `/tmp/w1w2-native-qa-main-39d9149`, `task_java_home=$(/usr/libexec/java_home -v 25); JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DeterministicExchangeCoreAdapterTest,CoreProbeStateTest,CoreMatchingStateTest,SurprisingClusteredServiceTest test`.
- `C2`: in `/tmp/w1w2-native-qa-main-39d9149`, `task_java_home=$(/usr/libexec/java_home -v 25); JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -f surprising-aeron-core/surprising-aeron-service/pom.xml -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TradingCoreReducerTest,TradingStateSnapshotCodecTest,CoreLifecycleStateTest,CoreRiskStateTest test`.
- `C3`: in `/tmp/w1w2-native-qa-fork-9819b9`, `task_java_home=$(/usr/libexec/java_home -v 25); JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn test`.
- `C4`: in `/tmp/w1w2-native-qa-main-39d9149`, `task_java_home=$(/usr/libexec/java_home -v 25); JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -f surprising-aeron-core/surprising-aeron-service/pom.xml dependency:tree -Dverbose -Dincludes=exchange.core2:exchange-core`.
- `C5`: in `/tmp/w1w2-native-qa-fork-9819b9`, `task_java_home=$(/usr/libexec/java_home -v 25); JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -DskipTests package`, followed by SHA-256 and per-entry content comparison against the pinned local artifact.
- `C6`: exact-commit `git diff --check 39d9149de35797503d751ee2c32838fc58096286^ 39d9149de35797503d751ee2c32838fc58096286` plus line-stamped `git show` contract/source checks.

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| S01 | exact commit | clean worktree identity | clean-worktree integrity check | PASS | A1 |
| S02 | JDK25 | Maven enforcer/reactor | C1 | PASS; `require-jdk-25` passed | A2 |
| S03 | fork release | exchange-core full Maven suite | C3 | PASS; 300/300 | A3 |
| S04 | sole executable FIFO | `DeterministicExchangeCoreAdapterTest.nativeSnapshotRoundTripRestoresTheOnlyExecutableBook` | C1 | PASS | A2, A7 |
| S05 | ME0/RE0 native pairing | matcher snapshot module validation and round-trip | C1 | PASS; two native modules restored | A2, A8 |
| S06 | exact O(active-order) reconciliation | `reconcileOpenOrdersAsync` exact expected/actual open-order maps | C1 | PASS | A2, A8 |
| S07 | engine/book hash gate | native restore state-hash assertions | C1 | PASS | A2, A8 |
| S08 | divergence fail-closed | adapter metadata mismatch test | C1 | PASS; `FatalMatchingDivergenceException` | A2, A7 |
| S09 | divergence no retry | `CoreProbeStateTest.failsClosedWithoutRetryOnMatcherDivergence` | C1 | PASS | A2, A7 |
| S10 | matcher corruption | `matcherSnapshotCodecRejectsCorruption` | C1 | PASS; checksum rejection | A2, A7 |
| S11 | Core snapshot corruption | `snapshotChecksumRejectsCorruption` | C1 | PASS | A2, A7 |
| S12 | pending matching fence | `rejectsSnapshotWhileMatchingPending` | C1 | PASS | A2, A7 |
| S13 | service admission safety | `doesNotReplaceStateAfterCorruptSnapshot` | C1 | PASS | A2, A7 |
| S14 | fatal callback propagation | `propagatesFatalMatcherDivergenceFromSnapshotCallback` | C1 | PASS | A2, A7 |
| S15 | timer backpressure | `retriesTimerSchedulingUntilAeronBackpressureClears` | C1 | PASS | A2, A7 |
| S16 | six-product isolation | parameterized FIFO restore over `Stream.of(ProductLine.values())` | C1 | PASS; 6/6 cases | A2, A7, A9 |
| S17 | spot funds safety | spot match/freeze/release assertions | C1 | PASS | A2 |
| S18 | derivatives funds/positions | perpetual and delivery matching assertions | C1 | PASS | A2 |
| S19 | option premium/positions | option fill accounting assertions | C1 | PASS | A2 |
| S20 | CROSS/ISOLATED boundary | reducer and risk state tests | C2 | PASS; 50/50 | A11 |
| S21 | replay/idempotency | CoreProbe duplicate command and restored deduplication tests | C1 | PASS | A2 |
| S22 | post-restore matching | FIFO test crosses after native restore | C1 | PASS | A2, A7 |
| S23 | snapshot versions | CoreState v6 and TradingState v19 codecs | C2/C6 | PASS | A8, A11 |
| S24 | no Core FIFO payload | `preservesFifoAcrossNativeSnapshotWithoutCoreBookState` plus codec/source evidence | C1/C6 | PASS | A7, A8 |
| S25 | snapshot-only startup | `InitialStateConfiguration.fromSnapshotOnly` restore branch | C1/C6 | PASS | A2, A8 |
| S26 | journaling disabled | native adapter configuration and fork serialization tests | C1/C3/C6 | PASS | A2, A3, A8 |
| S27 | single dependency provenance | filtered Maven dependency tree | C4 | PASS; only `exchange-core:0.5.10-emporia` | A4 |
| S28 | pinned artifact identity | POM SHA versus installed JAR SHA | C4 | PASS; exact pinned SHA | A4 |
| S29 | source artifact content | fresh fork package compared entry-by-entry | C5 | PASS; whole-JAR variance is ZIP metadata only, 0 content mismatches | A5, A6 |
| S30 | exact target diff hygiene | parent-to-target `git diff --check` | C6 | PASS | A8 |

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| A01 | native restore | corrupt outer snapshot bytes | reject before replacing state | PASS | A2, A7, A8 |
| A02 | native restore | corrupt matcher module/checksum | reject with protocol/fatal error | PASS | A2, A7 |
| A03 | reconciliation | missing or mismatched Core OPEN order | fail closed, no rebuild/replay | PASS | A2, A7, A8 |
| A04 | reconciliation | duplicate native OPEN order ID | fail closed | PASS by native reconciliation path and exact duplicate check | A2, A8 |
| A05 | hash integrity | engine/book hash divergence | fail closed with fatal divergence | PASS | A2, A7, A8 |
| A06 | snapshot fence | pending matcher continuation | refuse snapshot | PASS | A2, A7, A8 |
| A07 | timer delivery | Aeron timer backpressure | keep retrying until publication succeeds | PASS | A2, A7, A8 |
| A08 | product isolation | wrong ProductLine snapshot/command | reject without state mutation | PASS | A2, A8, A9 |
| A09 | replay | duplicate command after restore | return duplicate/original result, no second mutation | PASS | A2 |
| A10 | provenance | alternate exchange-core dependency | no alternate dependency admitted | PASS | A4 |
| A11 | provenance | fresh build differs from pinned whole-JAR SHA | distinguish metadata-only variance from content drift | PASS; 218 non-directory entries, 0 content mismatches | A5, A6 |
| A12 | mode boundary | CROSS versus ISOLATED state mutation | preserve mode-specific risk/position semantics in one product-line core | PASS | A11 |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| A1 | git transcript | clean detached worktrees and exact SHAs | `.omo/evidence/manual-qa-native-snapshot-20260816/clean-worktree-integrity.txt` |
| A2 | Maven transcript | exact main commit JDK25 reactor and 52-test high-risk run | `.omo/evidence/manual-qa-native-snapshot-20260816/clean-main-exact-jdk25.log` |
| A3 | Maven transcript | exact fork commit JDK25 full suite, 300 tests | `.omo/evidence/manual-qa-native-snapshot-20260816/clean-fork-exact-jdk25.log` |
| A4 | Maven/provenance transcript | single dependency, committed fork SHA/JDK/SHA, installed JAR hash | `.omo/evidence/manual-qa-native-snapshot-20260816/clean-dependency-provenance-jdk25.log` |
| A5 | Maven transcript | exact fork clean package and fresh JAR hash | `.omo/evidence/manual-qa-native-snapshot-20260816/clean-fork-artifact-sha-jdk25.log` |
| A6 | parsed artifact output | fresh versus pinned JAR entry/content comparison | `.omo/evidence/manual-qa-native-snapshot-20260816/fork-artifact-content-comparison.txt` |
| A7 | JUnit report extract | six FIFO cases and named corruption/divergence/backpressure tests | `.omo/evidence/manual-qa-native-snapshot-20260816/clean-testcase-report-evidence.txt` |
| A8 | source evidence | exact-commit line-stamped snapshot, restore, reconciliation, hash, module, and version evidence | `.omo/evidence/manual-qa-native-snapshot-20260816/source-line-evidence.txt` |
| A9 | source/test evidence | six ProductLine enum values and parameter source | `.omo/evidence/manual-qa-native-snapshot-20260816/product-line-and-summary-evidence.txt` |
| A11 | Maven transcript | exact main commit CROSS/ISOLATED and snapshot boundary suite, 50 tests | `.omo/evidence/manual-qa-native-snapshot-20260816/clean-main-exact-jdk25.log` |

## Coverage limits

No database, Kafka, Redis, wallet service, or network-backed production environment was started. The executed surface is the in-memory Aeron service/native matcher path and the fork test surface required by this migration. The full unrelated main-repository service/client/exporter/tools reactor was not rerun beyond the exact high-risk service reactor because this review scope is the snapshot/matching surface; that broader scope remains a follow-up if required.
