# Cross-risk workload-bound evidence

Date: 2026-08-16 (Asia/Shanghai)

## Implementation scope

- `TradingCoreReducer.continueRiskScan` now treats `maxUsers` as a hard map-entry work budget.
- A cross-risk user advances through deterministic position aggregation, reservation aggregation, and cross-snapshot emission phases without materializing the full portfolio.
- `CoreRiskState.RiskScan` persists the active user, phase, position/reservation cursors, scalar aggregates, and independent risk/trigger completion state.
- Trading snapshot version 17 and the business-state hash include every continuation field.

## Binary evidence

1. Scenario: compile the affected service and all required Maven reactor dependencies with JDK 25.
   Invocation: `JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests compile -Dcheckstyle.skip=true`
   Observable: process exit code `0`, Maven `BUILD SUCCESS`.
   Artifact: `surprising-aeron-core/surprising-aeron-service/target/classes/com/surprising/aeron/service/state/TradingCoreReducer.class`.

2. Scenario: a one-entry cross-risk continuation pauses within a user's position scan, survives snapshot encode/decode with the same business hash and trigger continuation fields, then produces the same snapshots/liquidations as an unpaged scan.
   Invocation: `JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreRiskStateTest,TradingStateSnapshotCodecTest -Dsurefire.failIfNoSpecifiedTests=false test -Dcheckstyle.skip=true`
   Observable: process exit code `0`; `CoreRiskStateTest` 8/8 and `TradingStateSnapshotCodecTest` 4/4 passed; total 12 tests, 0 failures/errors/skips.
   Artifacts: `surprising-aeron-core/surprising-aeron-service/target/surefire-reports/TEST-com.surprising.aeron.service.state.CoreRiskStateTest.xml` and `surprising-aeron-core/surprising-aeron-service/target/surefire-reports/TEST-com.surprising.aeron.service.state.TradingStateSnapshotCodecTest.xml`.

3. Scenario: whitespace/static patch validation for the shared dirty worktree.
   Invocation: `git diff --check`
   Observable: process exit code `0`, no output.
   Artifact: this evidence record plus the Git diff for the changed source/test files.

## Test-scope rationale

The change is local to Aeron service risk reduction and its persisted state contract. Focused reducer risk tests and trading-state snapshot/hash tests cover the changed behavior. No wallet service or other product-line runtime was started.
