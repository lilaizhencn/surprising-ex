# Manual QA matrix — Instrument-owned margin policy

## surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| C1 | CoreContractMath, TradingCoreReducer, CoreRiskState | JDK 25 Maven/JUnit reactor | `env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreContractMathTest,CoreRiskStateTest,TradingCoreReducerTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 32/32, 0 failures/errors/skips | `A-C1` |
| R1 | RiskController, RiskRuleRepository, RiskRuntimeConfigService, RiskService | JDK 25 Maven/JUnit reactor | `env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-risk/surprising-risk-provider -am -Dtest=RiskRuntimeConfigServiceTest,RiskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS — 8/8, 0 failures/errors/skips | `A-R1` |
| S1 | In-scope diff/static hygiene | Git diff and source inspection | `git diff --check` plus the source scans recorded in `qa-source-coverage-20260815.txt` | PASS — no whitespace errors; four regression scenarios and policy-field removal verified in current artifacts | `A-S1` |

## adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| A1 | TradingCoreReducer cross-bracket reservation | cross-bracket fill | Adding into a higher risk bracket reserves and carries the full required margin for the existing projected position, without understated locked funds. | PASS — direct test asserts 60 locked and 60 position margin after the second-bracket fill | `A-C1`, `A-S1` |
| A2 | CoreContractMath/CoreRiskState highest bracket | out-of-range mark-price notional | Mark-price risk scanning must continue with the highest available maintenance bracket and produce a snapshot instead of aborting. | PASS — direct test completes scan and finds the snapshot when notional exceeds the bracket cap | `A-C1`, `A-S1` |
| A3 | TradingCoreReducer leverage validation | bracket-specific initial margin | A leverage whose implied initial-margin rate is below the projected bracket rate must be rejected. | PASS — direct test rejects 8x with `LEVERAGE_EXCEEDS_RISK_BRACKET` | `A-C1`, `A-S1` |
| A4 | RiskController/RiskRuntimeConfigService/RiskService/RiskRuleRepository | mutable duplicate global policy | Risk Provider must not accept or expose warning/liquidation global thresholds; margin policy source must be versioned Aeron Core instrument state. | PASS — unit tests cover core source, blocked global rule update, and absent output fields; source/schema scan finds no retained field definitions | `A-R1`, `A-S1` |
| A5 | Risk API/repository integration boundary | HTTP/JDBC wiring | Controller request binding and repository behavior against the migrated database schema should preserve A4 at runtime. | FAIL — blocker: no direct MVC/JDBC test exists in the scoped tests, and running the required service/database prerequisite was out of scope | `A-S1` |

## artifactRefs

| id | kind | description | path |
|---|---|---|---|
| `A-C1` | Maven transcript | Fresh JDK 25 Aeron Core targeted tests, 32 passing | `.omo/evidence/manual-qa-margin-source-20260815/qa-core-targeted-tests-20260815.log` |
| `A-R1` | Maven transcript | Fresh JDK 25 Risk Provider targeted tests, 8 passing | `.omo/evidence/manual-qa-margin-source-20260815/qa-risk-targeted-tests-20260815.log` |
| `A-S1` | static/source evidence | Named regression test bodies, policy-field scan, migration inspection, coverage gaps, and diff-check result | `.omo/evidence/manual-qa-margin-source-20260815/qa-source-coverage-20260815.txt` |

## verdict and gaps

Overall verdict: PASS for the four prior bugs at the targeted unit/source-review level: 40/40 executed tests passed, with no failures, errors, or skips.

Gaps are boundary coverage, not inferred passes: no direct RiskController HTTP test, no JDBC repository/schema integration test, no direct DeterministicExchangeCoreAdapter test, and no service/wallet or full integration execution.
