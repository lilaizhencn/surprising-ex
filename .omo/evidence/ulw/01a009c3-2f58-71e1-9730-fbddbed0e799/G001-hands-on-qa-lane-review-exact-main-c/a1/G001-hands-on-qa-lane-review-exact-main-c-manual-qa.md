# Manual QA matrix — exact-SHA lane

Overall exact-SHA QA verdict: **FAIL — HIGH severity**.

The exact main object (`18f62de4e16a1493079b918a8b37c7357dd66d1d`, tree
`cebc4b2a116c5352f89d8cd767ac1086ec2e3d30`) and exact fork object
(`0511efca1458e9733d7911732ccab1fd83fc373b`, tree
`36a09ffd5cbe4a9e59ff5773f864ae543eb670f9`) pass the focused and fork
scenarios below. The requested fork source checkout did not remain exact during
the lane: it advanced to `310235eadea617fb9a893cd65cd1fb9eef1cb923` (tree
`83486e7b4374ee25571bb69bd8287bda54151fd4`), and a direct probe against that
live checkout accepted a build. Therefore the live fork build gate cannot be
accepted as evidence for exact commit `0511efc…`; exact-object clone evidence is
reported separately and is green.

All commands used IBM Semeru JDK 25 at
`/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home`.
No product code or commit was created. The unrelated Gateway AD worktree/hash
was compared before and after and was unchanged.

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| main-native-restore-fifo | C001 | Main Maven/JUnit service surface, exact isolated Git clone at `18f62de…` | `JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=$JAVA_HOME/bin:$PATH mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreMatchingStateTest,CoreProbeStateTest,SurprisingClusteredServiceTest,DeterministicExchangeCoreAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 53 tests, 0 failures/errors/skips; exact clean clone rerun also passed | artifact-main-runtime |
| main-snapshot-index-boundary | C001 | Main Maven/JUnit snapshot/index/Aeron boundary surface, exact isolated Git clone at `18f62de…` | `JAVA_HOME=... PATH=... mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLifecycleStateTest,CoreRiskStateTest,TradingCoreReducerTest,TradingStateSnapshotCodecTest,ActiveOrderIndexTest,SymbolMatchingLanesTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 55 tests, 0 failures/errors/skips | artifact-main-runtime |
| fork-dirty-rejection | C002 | Fork Maven build gate, exact full Git clone detached at `0511efc…`, with an untracked dirty sentinel | `mvn -DskipTests generate-resources` | PASS — nonzero exit with `exchange-core artifact must be built from a clean Git worktree` | artifact-fork-build |
| fork-report-linear-duplicate | C002 | Fork report/duplicate service and library tests, exact full Git clone detached at `0511efc…` | `mvn -Dtest=DmaOrderLifecycleTest,ProductionSimulationTest,ProductionSimulationAccountingTest,DmaAdvancedOrdersTest,OpenOrdersReportResultTest,DmaLifecycleSnapshotStoreTest test` | PASS — 16 tests, 0 failures/errors/skips; linear report order and duplicate-ID assertions passed | artifact-fork-build |
| fork-full-suite | C002 | Fork full Maven/JUnit suite, exact full Git clone detached at `0511efc…` | `mvn test` | PASS — 302 tests, 0 failures/errors/skips | artifact-fork-build |
| fork-repro-build-1 | C002 | Fork package artifact, exact full Git clone detached at `0511efc…` | `mvn -DskipTests clean package`; `shasum -a 256 target/exchange-core-0.5.13-emporia.jar`; inspect embedded provenance | PASS — SHA-256 equals `ed2dbcf86f2ebf6c1b2bd5642e7585ce5568104da9b61f3649a5a506cd37931f`; embedded SHA is `0511efc…`, dirty is false | artifact-fork-build |
| fork-repro-build-2 | C002 | Independent second fork package artifact, exact full Git clone detached at `0511efc…` | repeat `mvn -DskipTests clean package`; repeat `shasum -a 256 target/exchange-core-0.5.13-emporia.jar`; inspect embedded provenance | PASS — same expected SHA-256 and provenance as build 1 | artifact-fork-build |
| main-provenance-digest | C003 | Main Maven validation and resolved exchange-core JAR | `mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests validate`; `shasum -a 256 ~/.m2/repository/exchange/core2/exchange-core/0.5.13-emporia/exchange-core-0.5.13-emporia.jar`; `unzip -p ... fork-provenance.properties` | PASS — Maven exit 0; whole-JAR SHA and embedded fork SHA match requested values | artifact-main-provenance |
| main-exact-clean-rerun | C003 | Exact main Git clone runtime and validation surface | `git clone --no-local ...`; `git checkout --detach 18f62de…`; run both focused Maven commands above and `mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests validate` | PASS — exact HEAD/tree, 53 + 55 tests, validation exit 0; clone cleaned afterward | artifact-main-runtime |

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| malformed-snapshot | C001 | Corrupt/truncated native snapshot | Reject the snapshot and do not replace the live state | PASS — snapshot codec and matcher/native restore focused tests passed; no test failures/errors/skips | artifact-main-runtime |
| snapshot-divergence | C001 | ME0/RE0 or matcher divergence | Fail closed and surface fatal divergence; no production retry/replay/resubmit path | PASS — deterministic adapter, matcher, and clustered-service focused tests passed; static production-path check found no `replay`/`resubmit` implementation match | artifact-main-runtime, artifact-main-provenance |
| bounded-snapshot-ingest | C001 | Oversized/high-volume snapshot input | Enforce bounded ingest and preserve service progress/backpressure behavior | PASS — snapshot codec and probe-state focused suites passed; 5 codec and 27 probe-state tests ran with zero failures/errors/skips | artifact-main-runtime |
| aeron-capacity-timer | C001 | Aeron publication backpressure/capacity and timer scheduling | Remain bounded, retry through the timer/capacity path, and avoid unbounded blocking | PASS — `CoreProbeStateTest` and `CoreMatchingStateTest` focused runtime suites passed; 27 and 18 tests respectively | artifact-main-runtime |
| six-product-line-fifo | C001 | Product-line isolation and FIFO native restore | Keep six product lines isolated and preserve exchange-core FIFO order after native restore | PASS — exact-main focused service/matching suite passed in the clean exact clone; no failures/errors/skips | artifact-main-runtime |
| fork-dirty-worktree | C002 | Untracked/dirty fork input | Refuse to build and return nonzero | PASS — exact `0511efc…` clone rejected dirty sentinel with clean-worktree error | artifact-fork-build |
| fork-report-order-duplicate | C002 | Nonlinear report order and duplicate order IDs | Preserve linear report order and reject duplicate IDs | PASS — named report assertions passed in the 16-test exact-fork targeted run | artifact-fork-build |
| fork-live-checkout-drift | C002 | Requested fork checkout changes away from exact SHA during QA | Exact-SHA verdict must reject the live checkout as evidence until it is pinned back to `0511efc…` | FAIL — HIGH severity; live checkout was `310235e…`/`0.5.14`, and its direct probe accepted a build | artifact-fork-build, artifact-identity |
| main-dirty-state-preservation | C003 | Existing unrelated Gateway AD worktree state | QA must not reset, overwrite, or otherwise alter unrelated Gateway AD state | PASS — before/after AD index hash and worktree state were identical | artifact-main-provenance, artifact-identity |
| provenance-mismatch | C003 | Embedded fork SHA or whole-JAR digest mismatch | Main Maven validation must fail rather than accept a mismatched artifact | PASS — exact main Maven validation exited 0 against the expected artifact; observed embedded SHA and whole-JAR SHA both matched the requested values | artifact-main-provenance |
| prompt-input-injection | C001 | User-text/prompt parsing injection | Not applicable: the exercised surface is Maven/JUnit and native service state, with no prompt or user-text parser | NOT_APPLICABLE — no prompt/user-text parsing is in scope |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| artifact-main-runtime | terminal transcript | Exact-main identity, JDK 25, focused Maven/JUnit commands, counts, clean-clone rerun, and cleanup receipt | [main-focused-runtime.txt](/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/ulw/01a009c3-2f58-71e1-9730-fbddbed0e799/G001-hands-on-qa-lane-review-exact-main-c/a1/main-focused-runtime.txt) |
| artifact-main-provenance | terminal transcript | Main Maven provenance/digest gate, embedded fork metadata, static production-path check, Gateway AD before/after, and cleanup receipt | [main-provenance-gates.txt](/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/ulw/01a009c3-2f58-71e1-9730-fbddbed0e799/G001-hands-on-qa-lane-review-exact-main-c/a1/main-provenance-gates.txt) |
| artifact-fork-build | terminal transcript | Exact-fork clone identity, dirty rejection, full suite count, report/duplicate count, two reproducible builds, SHA/provenance, and cleanup receipt | [fork-build-report.txt](/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/ulw/01a009c3-2f58-71e1-9730-fbddbed0e799/G001-hands-on-qa-lane-review-exact-main-c/a1/fork-build-report.txt) |
| artifact-identity | terminal transcript | Main/fork source-checkout identity, exact object/tree identity, worktree observations, JDK identity, and preservation/cleanup notes | [exact-sha-identity-and-cleanup.txt](/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/ulw/01a009c3-2f58-71e1-9730-fbddbed0e799/G001-hands-on-qa-lane-review-exact-main-c/a1/exact-sha-identity-and-cleanup.txt) |

