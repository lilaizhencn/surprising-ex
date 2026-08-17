# manualQa: PASS

Exact targets:

- Main: `b64ad00615965172f042bd49c38ffe0a0075fb76` (tree `4ab84b63a9165a94a847cb1d90d4451bc6ff8319`)
- Fork: `33a9f135c4e2396aaac0f28fad2afbc1350b7a3c` (tree `7746db924f55fa8a3593f3570a1cd915454ed246`)
- Runtime: IBM Semeru JDK 25.0.2 at `/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home`

All scenarios ran in detached exact-SHA worktrees. No product code was edited or committed. Disposable QA worktrees were removed; cleanup receipt is `A8`.

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| S01 | exact SHA / worktree integrity | Git CLI | `git worktree add --detach /tmp/w1w2-qa-main-b64ad006 b64ad00615965172f042bd49c38ffe0a0075fb76`; `git -C /tmp/w1w2-qa-main-b64ad006 rev-parse HEAD`; equivalent fork command for `33a9f135c4e2396aaac0f28fad2afbc1350b7a3c` | PASS | A1, A8 |
| S02 | six-product-line FIFO native restore | Maven/Surefire | `task_java_home=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home; JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl :surprising-aeron-service -am clean test -Dtest=CoreMatchingStateTest,CoreProbeStateTest,SurprisingClusteredServiceTest,DeterministicExchangeCoreAdapterTest,SymbolMatchingLanesTest -Dsurefire.failIfNoSpecifiedTests=false` | PASS; 56/56, including 6/6 FIFO ProductLine cases | A2, A3, A8 |
| S03 | corruption and divergence fail closed | Maven/Surefire | Same S02 invocation; named cases `snapshotChecksumRejectsCorruption`, `matcherSnapshotCodecRejectsCorruption`, `restoreFailsClosedWhenCoreMetadataDoesNotMatchNativeOpenOrders`, `failsClosedWithoutRetryOnMatcherDivergence`, `propagatesFatalMatcherDivergenceFromSnapshotCallback` | PASS | A2, A3 |
| S04 | bounded snapshot ingest and timer backpressure | Maven/Surefire | Same S02 invocation; named cases `rejectsSnapshotFragmentsBeyondBoundedRecoveryBuffer` and `retriesTimerSchedulingUntilAeronBackpressureClears` | PASS | A2, A3 |
| S05 | fork open-order preservation and duplicate rejection | Maven/Surefire | `task_java_home=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home; JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -Dtest=OpenOrdersReportResultTest,InMemorySerializationProcessorTest test` | PASS; 6/6 | A4 |
| S06 | reproducible fork artifact | Maven/package + SHA-256 | `task_java_home=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home; JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn clean install -DskipTests`; `shasum -a 256 target/exchange-core-0.5.12-emporia.jar` | PASS; artifact SHA equals pinned `e7eb6b3cb292a605c30cb1fc224ced5cde7f1f5481306dbf01e76299a860f66d` | A5 |
| S07 | Maven digest gate positive | Maven/validation | `task_java_home=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home; JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl :surprising-aeron-service -am validate` | PASS; validation accepted exact artifact | A6 |
| S08 | Maven digest gate negative | Maven/validation | `task_java_home=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home; JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl :surprising-aeron-service -am validate -Dexchange-core.sha256=0000000000000000000000000000000000000000000000000000000000000000` | PASS; build rejected with SHA mismatch, exit 1 | A7 |
| S09 | sole FIFO book, snapshot-only restore, linear reconciliation, no replay/rebuild/resubmit | CLI source/data audit | `rg -n 'fromSnapshotOnly|enableJournaling\\(false\\)|HashMap|TreeMap|MAX_SNAPSHOT|reconcileOpenOrders'` plus exact report extraction from the detached worktrees | PASS; `HashMap` reconciliation, bound, snapshot-only and disabled journaling observed | A3, A9 |

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| A01 | native restore | corrupt outer or matcher snapshot | Reject before state replacement; checksum/fatal path is observable | PASS | A2, A3 |
| A02 | reconciliation | Core/native open-order divergence or duplicate native order | Fail closed; no replay or resubmission | PASS | A2, A3, A4 |
| A03 | snapshot ingest | oversized fragment / bounded recovery buffer | Reject before unbounded accumulation | PASS | A2, A3 |
| A04 | Aeron scheduling | timer publication backpressure | Retry until publication succeeds | PASS | A2, A3 |
| A05 | fork report contract | input order is non-sorted and IDs are duplicated | Preserve input order; reject duplicate order ID | PASS | A4, A9 |
| A06 | artifact provenance | same dependency with wrong expected SHA | Maven validation fails closed with SHA mismatch | PASS | A7 |
| A07 | dirty worktree | unrelated Gateway AD state present during QA | Preserve unrelated state and avoid edits/commits | PASS; Gateway AD remained present; QA used detached worktrees | A1, A8 |
| A08 | recovery policy | production replay/rebuild/resubmit path | No matcher recovery fallback; only derived indexes may rebuild | PASS; source audit found only derived-index rebuilds and native snapshot restore | A3, A9 |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| A1 | git transcript | Exact detached SHAs, trees, diff checks, and original worktree state | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/qa-final-exact-sha-integrity.txt` |
| A2 | Maven transcript | Main exact-SHA focused reactor; 56 tests, 0 failures/errors | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/qa-main-native-restore-failure-safety-b64ad006-jdk25.log` |
| A3 | JUnit/source audit | Named FIFO, corruption, divergence, bound, timer, and barrier cases plus source gates | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/qa-exact-sha-source-report-audit.txt` |
| A4 | Maven transcript | Fork exact-SHA open-order and native processor tests; 6 tests, 0 failures/errors | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/qa-fork-open-order-native-33a9f135-jdk25.log` |
| A5 | Maven/SHA transcript | Fork exact-SHA clean install and artifact digest | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/qa-fork-artifact-install-33a9f135-jdk25.log` |
| A6 | Maven transcript | Positive Maven artifact digest validation | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/qa-main-digest-positive-b64ad006-jdk25.log` |
| A7 | Maven transcript | Negative Maven artifact digest validation and expected exit 1 | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/qa-main-digest-negative-b64ad006-jdk25.log` |
| A8 | cleanup transcript | Disposable exact-SHA QA worktree removal and no QA Maven processes | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/qa-cleanup-receipt.txt` |
| A9 | source/test evidence | Exact fork linear report implementation and exact main `HashMap` reconciliation/source gates | `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/manual-qa-native-snapshot-20260816/qa-exact-sha-source-report-audit.txt` |

## Result

**PASS.** The requested focused real scenarios pass on the exact main and fork SHAs, including six-product-line native FIFO restore, corruption/divergence fail-closed behavior, bounded ingest/timer backpressure, fork order preservation/duplicate rejection, and positive/negative digest enforcement.

The fork source worktree path was observed at the requested SHA before detached QA and later observed at `0511efca1458e9733d7911732ccab1fd83fc373b` without action by this QA run. The exact requested fork SHA was tested in a detached worktree and produced the pinned artifact; the original path was not changed back to avoid overwriting concurrent user state.
