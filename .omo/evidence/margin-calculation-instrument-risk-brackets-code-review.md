# Code-quality review: margin calculation to instrument/risk brackets

## Result

- `codeQualityStatus`: **BLOCK**
- `recommendation`: **REQUEST_CHANGES**
- Scope reviewed: the current uncommitted Aeron core and Risk Provider changes relevant to bracket-selected margin/risk policy. Broad unrelated worktree edits were not evaluated.
- No executor evidence paths were provided. I inspected the live worktree diff and neighboring implementation/tests directly.

## Verification performed

- Inspected `git diff` and untracked task files, including `CoreContractMathTest` and `CoreRiskPolicy`.
- Inspected bracket validation, order reservation/fill accounting, mark-price risk scanning, snapshots, and Risk Provider REST/runtime-rule paths.
- Ran `git diff --check -- surprising-aeron-core/surprising-aeron-service surprising-risk/surprising-risk-provider`: clean.
- Attempted `mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-risk/surprising-risk-provider -am test -DskipTests=false`. It did not start tests: Maven Enforcer rejected the installed JDK (project requires JDK 25).

## Required skill-perspective check

Ran before evaluating maintainability and test relevance:

- `omo:programming`: **ran**. The diff does not introduce the skill's named typed-language escape hatches or needless abstractions. Its behavior-first testing perspective is violated by missing end-to-end/reducer coverage for the bracket transition.
- `omo:remove-ai-slops`: **ran**. The production diff contains no material needless parsing/normalization/data extraction. The new provider test that only asserts rejection of the retired update path is a deletion-only/removal-verification test, and the core test does not cover the financial state transition it changes.

## Findings

### CRITICAL

None.

### HIGH

1. **Crossing into a higher bracket under-reserves and under-records initial margin for the already-open part of the position.**

   - Files: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:1711-1716`, `1768-1773`, `1906-1938`
   - The reservation and fill paths select the rate for the **post-fill total** notional but multiply it only by `openSteps`. They never debit the additional margin required when the existing position is repriced into a higher bracket.
   - Example: an existing 9-step position at price 100 with a 10% first bracket has 90 units locked. Adding 2 steps crosses into a 20% bracket, so the 11-step position needs 220 units. The code reserves/debits 2 × 100 × 20% = 40 and stores 130 units (`90 + 40`), leaving 90 units unreserved.
   - This causes collateral and `positionMarginUnits` to drift below the bracket policy and can allow an order that should fail for insufficient funds. Calculate the delta between required margin for the complete next position and releasable margin for the previous/closed position, consistently in both reservation and fill settlement.

2. **A mark-price move above the final bracket cap aborts risk scanning instead of computing maintenance/liquidation risk.**

   - Files: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreContractMath.java:61-68,92-105`; `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java:911-918`
   - `maintenanceMarginUnits` uses mark-price notional and calls `riskBracket`, which throws `RISK_BRACKET_EXCEEDED` above the selected cap. A previously valid position can cross that cap solely because the mark rises. The exception bubbles through `positionRisk` during the deterministic risk scan, preventing the scan from creating snapshots or liquidation work precisely during a severe market move.
   - Keep order-entry cap rejection separate from maintenance calculation. Define and test the intended over-cap policy (normally applying the highest bracket for maintenance while the position is liquidated/reduced), so a mark update cannot halt risk processing.

### MEDIUM

1. **The tests do not exercise either high-risk state transition.**

   - File: `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreContractMathTest.java:15-35`
   - The added test only calls math helpers. It does not place/fill across a bracket boundary and assert the locked balance and `positionMarginUnits`, and it does not apply a mark-price increase beyond the final cap and assert that a risk snapshot/liquidation plan is emitted. Therefore it stays green with both HIGH defects above.
   - This violates the programming skill's behavior-focused test criterion; it is not a tautological test, but it is insufficient for the changed financial workflow.

2. **Deletion-only test adds false confidence without proving externally observable compatibility.**

   - File: `surprising-risk/surprising-risk-provider/src/test/java/com/surprising/risk/provider/service/RiskServiceTest.java:89-97`
   - `rejectsRiskProviderOwnedMarginPolicyUpdates` only verifies that the requested removal throws the new error. Under the remove-ai-slops perspective, this is a deletion/removal-verification test, not coverage of the new source-of-truth behavior. Prefer a contract-level test that reads the risk rule/runtime-config endpoint and verifies it exposes a core-owned policy with no mutable local thresholds, or omit the test if no such machine-consumed contract is required.

### LOW

1. **The public runtime-config request still exposes retired threshold fields but turns previously accepted requests into HTTP 400 without a versioned replacement or migration contract.**

   - Files: `surprising-risk/surprising-risk-provider/src/main/java/com/surprising/risk/provider/controller/RiskController.java:66-86`; `surprising-risk/surprising-risk-provider/src/main/java/com/surprising/risk/provider/service/RiskRuntimeConfigService.java:34-42`
   - Keeping the fields can be intentional for explicit rejection, but existing admin clients that send their old full payload now fail. The diff supplies neither an API version/deprecation response nor a discoverable instrument-policy endpoint from this API. Document or version this deliberate breaking change, or provide a stable redirect/discovery mechanism.

## Blockers before approval

1. Correct margin delta accounting when a fill changes the applicable initial-margin bracket, and cover reservation plus final position/balance values.
2. Ensure mark-price risk scans remain executable above the largest configured bracket cap, with an explicit maintenance/liquidation policy and regression coverage.
3. Run the relevant Maven tests with JDK 25 and attach the resulting artifact/log path.
