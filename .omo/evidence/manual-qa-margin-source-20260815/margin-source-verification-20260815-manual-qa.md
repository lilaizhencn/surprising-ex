# Manual QA matrix — margin-source verification

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| C1 | CoreContractMath, TradingCoreReducer, CoreInstrumentState risk/bracket behavior | Maven JUnit reactor with JDK 25 | `env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreContractMathTest,TradingCoreReducerTest,CoreRiskStateTest,CoreValueValidationTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 32/32, 0 failures/errors/skips | `A-C1` |
| R1 | RiskService, RiskRuntimeConfigService margin-source ownership and status mapping | Maven JUnit reactor with JDK 25 | `env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-risk/surprising-risk-provider -am -Dtest=RiskServiceTest,RiskRuntimeConfigServiceTest,RiskPropertiesTest,RiskApplicationYamlTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 9/9, 0 failures/errors/skips | `A-R1` |
| S1 | Static diff hygiene | Git diff checker | `git diff --check` | PASS — no whitespace errors | `A-S1` |
| S2 | Java 25 compilation and module packaging | Maven verify with tests skipped and JDK 25 | `env JAVA_HOME=/Library/Java/VirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-risk/surprising-risk-provider -am -DskipTests verify` | PASS — 12/12 reactor projects, BUILD SUCCESS | `A-S2` |

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| A1 | CoreContractMath / instrument risk bracket | authority bypass / dynamic bracket | Current position notional uses the instrument risk bracket for maintenance margin and leverage; risk-provider thresholds do not override it. | PASS — `maintenanceMarginUsesTheInstrumentRiskBracketForCurrentNotional`, `dynamicOpenInterestFloorAndRiskBracketLeverageAreCoreAuthority` | `A-C1` |
| A2 | TradingCoreReducer | boundary and fail-closed arithmetic | Instrument limits and arithmetic overflow reject safely rather than accepting an understated or invalid reservation. | PASS — `projectedSameSideOrdersCannotExceedCoreInstrumentLimit`, `arithmeticOverflowFailsClosed`, `coreComputesExactReservationAndRejectsUnderstatedPositiveHint` | `A-C1` |
| A3 | CoreRiskState / RiskService | status-source mismatch | Account status follows authoritative Core snapshot statuses, including liquidation/warning, rather than local threshold configuration. | PASS — `markPriceComputesRiskPlansLiquidationAndSurvivesSnapshot`, `accountStatusUsesCoreSnapshotStatusInsteadOfLocalThresholds` | `A-C1`, `A-R1` |
| A4 | RiskService / RiskRuntimeConfigService | unauthorized local policy mutation | Attempts to write warning/liquidation thresholds through Risk surfaces are rejected; runtime output identifies `AERON_CORE_INSTRUMENT` as the source. | PASS — `rejectsRiskProviderOwnedMarginPolicyUpdates`, `rejectsLocalMarginThresholdUpdates`, `exposesCoreAsTheOnlyMarginPolicySource` | `A-R1` |
| A5 | RiskService | aggregation/recalculation drift | Risk reads authoritative wallet/core position data and maps core position risk without recalculating a conflicting local margin policy. | PASS — `aggregatesCrossPositionsUsingAuthoritativeWallet`, `mapsCorePositionRiskWithoutRecalculation`, `crossMarginUsesPortfolioEquityAcrossSameSettlementAsset` | `A-C1`, `A-R1` |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| `A-B0` | baseline | Pre-existing dirty worktree and post-QA no-write check | `.omo/evidence/manual-qa-margin-source-20260815/baseline-worktree.txt` |
| `A-C0` | blocker transcript | Default JDK 21 Maven enforcer failure; no tests ran | `.omo/evidence/manual-qa-margin-source-20260815/c1-default-jdk-blocker.log` |
| `A-C1` | Maven transcript | Core targeted test command, exit 0, 32 passing tests | `.omo/evidence/manual-qa-margin-source-20260815/c1-core-targeted-tests.log` |
| `A-R1` | Maven transcript | Risk targeted test command, exit 0, 9 passing tests | `.omo/evidence/manual-qa-margin-source-20260815/r1-risk-targeted-tests.log` |
| `A-S1` | static check | `git diff --check`, exit 0 | `.omo/evidence/manual-qa-margin-source-20260815/s1-diff-check.log` |
| `A-S2` | Maven transcript | JDK 25 compile/package/verify command, exit 0 | `.omo/evidence/manual-qa-margin-source-20260815/s2-maven-verify.log` |
| `A-T0` | test inventory | Relevant executed test methods and adversarial mapping | `.omo/evidence/manual-qa-margin-source-20260815/test-method-inventory.txt` |

## blockers and unrelated findings

- Initial default-JDK invocation exited 1 before tests because the shell default was JDK 21 while the project enforces JDK 25. An installed JDK 25 rerun removed this environment blocker; the successful rerun is the verdict-bearing execution.
- No in-scope test failures were observed.
- Maven warnings were non-blocking: Mockito/Byte Buddy dynamic agent deprecation, deprecated Surefire `systemProperties`, and existing Shade overlap/module-info warnings.
- Full repository test coverage and service/integration execution were not run; this assignment was limited to the two requested modules and targeted tests/static checks.
