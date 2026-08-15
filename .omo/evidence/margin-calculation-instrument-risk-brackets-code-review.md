# Code-quality review: Instrument-owned derivative margin policy

## Verdict

- `codeQualityStatus`: **WATCH**
- `recommendation`: **APPROVE**
- Terminal verdict: **PASS (with one non-blocking watch item)**
- Scope: the 13 supplied Aeron Core/Risk Provider files, plus directly related instrument/protocol code and test evidence. Unrelated dirty worktree files were not assessed.

## Evidence independently inspected

- Current worktree status and complete in-scope diff; `git diff --check -- surprising-aeron-core/surprising-aeron-service surprising-risk/surprising-risk-provider` exited cleanly.
- Instrument bracket validation in `CoreInstrumentState` and `UpsertInstrumentCommand`; brackets are contiguous, bounded, and immutable copies.
- Live margin, reservation, fill, and risk-scan paths in `CoreContractMath` and `TradingCoreReducer`.
- Current targeted test sources and the supplied evidence artifacts. The supplied artifacts were treated as untrusted until compared with the live code; the Maven commands below were then rerun independently.
- JDK 25 test runs (no wallet service started):
  - `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreContractMathTest,TradingCoreReducerTest,CoreRiskStateTest,CoreValueValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`: **35 run, 0 failures/errors**.
  - `mvn -pl surprising-risk/surprising-risk-provider -am -Dtest=RiskServiceTest,RiskRuntimeConfigServiceTest,RiskPropertiesTest,RiskApplicationYamlTest -Dsurefire.failIfNoSpecifiedTests=false test`: **10 run, 0 failures/errors**.

## Recheck of prior findings

1. **Cross-bracket reservation/fill delta — fixed.** `TradingCoreReducer` derives the complete post-fill requirement, subtracts the remaining proportion of current margin, and debits only the increase at [TradingCoreReducer.java:1724](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1724). It stores `remainingMargin + marginIncrease` at [TradingCoreReducer.java:1781](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1781). The reservation path accounts for existing same-side pending orders at [TradingCoreReducer.java:1939](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1939), and `addingIntoHigherRiskBracketFreezesAndAllocatesTheExistingPositionDelta` asserts both 60 locked and 60 recorded position margin at [TradingCoreReducerTest.java:197](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/TradingCoreReducerTest.java:197).
2. **Mark notional above the last cap — fixed.** Entry validation still uses capped `riskBracket` at [CoreContractMath.java:92](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreContractMath.java:92), while maintenance selects the final applicable bracket without imposing its cap at [CoreContractMath.java:101](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreContractMath.java:101). The risk scan uses that maintenance calculation at [TradingCoreReducer.java:926](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:926); its over-cap regression test asserts a completed scan and snapshot at [CoreRiskStateTest.java:93](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreRiskStateTest.java:93).
3. **Bracket-specific leverage — fixed for order admission.** Projected-notional validation rejects leverage above a bracket maximum or whose implied initial-margin rate is below the bracket rate at [TradingCoreReducer.java:1998](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1998). Leverage changes cannot evade this after exposure exists because updates are blocked while matching orders or positions are open at [TradingCoreReducer.java:389](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:389). The projected-bracket rejection is tested at [TradingCoreReducerTest.java:227](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/TradingCoreReducerTest.java:227).
4. **Risk Provider duplicate mutable thresholds — fixed.** The runtime request/model and repository no longer contain margin-threshold fields at [RiskController.java:79](/Users/atomex/Desktop/surprising/surprising-ex/surprising-risk/surprising-risk-provider/src/main/java/com/surprising/risk/provider/controller/RiskController.java:79), [RiskRuleRepository.java:27](/Users/atomex/Desktop/surprising/surprising-ex/surprising-risk/surprising-risk-provider/src/main/java/com/surprising/risk/provider/repository/RiskRuleRepository.java:27), and [RiskService.java:254](/Users/atomex/Desktop/surprising/surprising-ex/surprising-risk/surprising-risk-provider/src/main/java/com/surprising/risk/provider/service/RiskService.java:254). Runtime output identifies the Core instrument source at [RiskRuntimeConfigService.java:27](/Users/atomex/Desktop/surprising/surprising-ex/surprising-risk/surprising-risk-provider/src/main/java/com/surprising/risk/provider/service/RiskRuntimeConfigService.java:27).

## Required skill-perspective check

This check **ran before judging maintainability and test relevance**.

- `omo:remove-ai-slops`: **ran**. No needless production parsing, normalization, data extraction, or deletion-only test was introduced for the margin-policy goal. The added core tests exercise actual reservation/fill and risk-scan behavior rather than constants or requested removals.
- `omo:programming`: **ran**. No new untyped escape hatch or brittle prompt/implementation-mirroring test was found in the in-scope diff. The test suite is behavior-oriented for the financial paths. Both skill perspectives are satisfied by the margin-policy changes.

## Findings

### CRITICAL

None.

### HIGH

None.

### MEDIUM

1. **Matcher-recovery batching is unrelated to the stated margin-policy goal and has no focused recovery/price-time-priority regression test.**

   - Evidence: [DeterministicExchangeCoreAdapter.java:76](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:76), [DeterministicExchangeCoreAdapter.java:194](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:194), [DeterministicExchangeCoreAdapter.java:304](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:304).
   - The change replaces sequential replay with batched user creation and asynchronous order submission during state rebuild. The request stream is ordered, so this review found no demonstrated ordering failure; however, rebuild is a price-time-priority-sensitive boundary and no test establishes that restoring several same-price orders preserves the original book priority or that partial user-add failure leaves the adapter recoverable. Keep this separately scoped or add a narrow recovery regression test before expanding it further.

### LOW

None.

## Blockers

None. The MEDIUM recovery-batching item is a follow-up, not an approval blocker for the stated Instrument-owned margin-policy goal.
