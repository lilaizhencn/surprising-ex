# Task 8 delivery/option financial matrix evidence

Workspace: `/Users/atomex/Desktop/surprising/w3w5-t08-delivery-option`

Branch: `codex/w3w5-t08-delivery-option`

Requested base: `7c57be7b0d3e98297c93e00b20c90e0b3b2d6bbf`

## Failing-first baseline

The baseline was run at the requested SHA before production files were changed.

Command:

```text
task_java_home=$(/usr/libexec/java_home -v 25) && export JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" && java -version && mvn -pl :surprising-aeron-service -am -Dtest=CoreDeliveryOptionFinancialMatrixTest#failingFirstCompletenessManifestReportsMissingDeliveryAndOptionRows -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: RED, exit 1; reactor 6 modules; 1 test, 1 failure, 0 errors, 0 skipped.

Missing rows: 9 total:

```text
INVERSE_DELIVERY:CROSS
INVERSE_DELIVERY:ISOLATED
OPTION:CALL:ITM
OPTION:CALL:ATM
OPTION:CALL:OTM
OPTION:PUT:ITM
OPTION:PUT:ATM
OPTION:PUT:OTM
LINEAR_DELIVERY:ISOLATED
```

The raw baseline transcript is retained in `task-8-delivery-option-red.txt`.

## Green acceptance

JDK: IBM Semeru/OpenJ9 25.0.2.1.

Exact required command:

```text
task_java_home=$(/usr/libexec/java_home -v 25) && export JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" && java -version && mvn -pl :surprising-aeron-service -am -Dtest=CoreDeliveryOptionFinancialMatrixTest,CoreContractMathTest,CoreLifecycleStateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: GREEN; all 6 reactor modules succeeded; 25 tests, 0 failures, 0 errors, 0 skipped.

```text
CoreLifecycleStateTest: 14 passed
CoreDeliveryOptionFinancialMatrixTest: 10 passed
CoreContractMathTest: 1 passed
```

The focused matrix also passed independently: 10 tests, 0 failures, 0 errors, 0 skipped.

Named QA methods, each run as an individual JDK25 Maven test:

```text
CoreDeliveryOptionFinancialMatrixTest#coversDeliveryAndOptionMoneyness: 1 passed
CoreDeliveryOptionFinancialMatrixTest#rejectsUntrustedCashDuplicateMutationAndWrongLine: 1 passed
```

Affected reactor:

```text
mvn -pl :surprising-aeron-service -am test
```

Result: GREEN; 152 tests, 0 failures, 0 errors, 0 skipped; all 6 reactor modules succeeded. No wallet service was started.

## Exact formula and funds manifests

All rows use fresh state with user and maker opening balances of 2,000 units. Delivery positions are quantity 2 at entry 100 and settle at 120. Option positions are quantity 2, strike 100, premium 10, and 100,000 ppm taker fee. Every row has `FUNDS_DIFFERENCE=0` both in the expected manifest and in observed ending state.

| Row | Core cash formula | Payout | User final | Maker final | Fee final | Effective insurance final | FUNDS_DIFFERENCE |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| LINEAR_DELIVERY:CROSS | `q * multiplier * (S - E)` | 40 | 2,040 | 1,960 | 0 | 0 | 0 |
| LINEAR_DELIVERY:ISOLATED | `q * multiplier * (S - E)` | 40 | 2,040 | 1,960 | 0 | 0 | 0 |
| INVERSE_DELIVERY:CROSS | `round(q * multiplier * scale * (S - E) / (E * S * tick))` | 33 | 2,033 | 1,967 | 0 | 0 | 0 |
| INVERSE_DELIVERY:ISOLATED | `round(q * multiplier * scale * (S - E) / (E * S * tick))` | 33 | 2,033 | 1,967 | 0 | 0 | 0 |
| OPTION:CALL:ITM | `q * multiplier * max(S - K, 0)` | 40 | 2,018 | 1,980 | 2 | 0 | 0 |
| OPTION:CALL:ATM | `q * multiplier * max(S - K, 0)` | 0 | 1,978 | 2,020 | 2 | 0 | 0 |
| OPTION:CALL:OTM | `q * multiplier * max(S - K, 0)` | 0 | 1,978 | 2,020 | 2 | 0 | 0 |
| OPTION:PUT:ITM | `q * multiplier * max(K - S, 0)` | 40 | 2,018 | 1,980 | 2 | 0 | 0 |
| OPTION:PUT:ATM | `q * multiplier * max(K - S, 0)` | 0 | 1,978 | 2,020 | 2 | 0 | 0 |
| OPTION:PUT:OTM | `q * multiplier * max(K - S, 0)` | 0 | 1,978 | 2,020 | 2 | 0 | 0 |

Option premium conservation is `premium * quantity * multiplier = 10 * 2 * 1 = 20`; the taker fee is `ceil(20 * 100,000 / 1,000,000) = 2`; the maker receives 20 and the buyer pays 22 including fee before intrinsic settlement. ATM/OTM intrinsic is exactly zero. The reducer derives payout from authoritative option type, strike, multiplier, and underlying settlement price; the unrelated command cash is not used for payout.

The matrix also verifies expiry metadata, duplicate settlement idempotency, cursor rejection after a repeated chunk, snapshot cursor resume for inverse delivery, both isolated delivery modes, and options, open-order cancellation before settlement with owner reservation release, zero positions, released margin/locks, product-line rejection, and exact user/maker/treasury conservation.

## Blocker closure evidence

The three independent-oracle blockers from the Luna gate are closed at the fix
diff. The user-preserved untracked gate report remains untouched.

1. Delivery payout oracle independence:

   - `CoreDeliveryOptionFinancialMatrixTest.deliveryRow` no longer calls
     `CoreContractMath.pnlUnits` for an expected value.
   - `DELIVERY_EXPECTATIONS` is an explicit four-key manifest for
     `LINEAR_DELIVERY`/`INVERSE_DELIVERY` crossed with `CROSS`/`ISOLATED`:
     linear long/short signed payouts are `40/-40` with final balances
     `2,040/1,960`; inverse long/short signed payouts are `33/-33` with final
     balances `2,033/1,967`.
   - `inverseDeliveryRoundingOracleUsesIndependentSignedHalfUpFormula`
     directly computes and asserts `33` and `-33` using an independent
     half-up formula; it does not call production math.
   - The matrix asserts every row's expected and observed `FUNDS_DIFFERENCE`
     as zero.

2. Shared CROSS/ISOLATED state and snapshot coverage:

   - `settlingIsolatedLossKeepsUnrelatedCrossStateAndReservationIntact` uses
     one shared LINEAR_DELIVERY state with a 40-unit CROSS margin, a 40-unit
     isolated loss against only 20 units of isolated margin, and an unrelated
     open CROSS reservation. It asserts unchanged unrelated position,
     reservation/open status, free balance `139`, CROSS collateral `180`,
     insurance offset `20`, and total funds conservation.
   - The new test was red before the production fix: the current reducer
     changed the unrelated free balance from `139` to `119`. The minimal
     reducer change routes lifecycle settlement through the existing
     margin-mode-aware cash helper; the isolated loss now consumes only its
     isolated margin and the test is green.
   - `snapshotCursorResumeCompletesLinearAndInverseIsolatedDelivery` resumes
     both `LINEAR_DELIVERY:ISOLATED` and `INVERSE_DELIVERY:ISOLATED` after
     snapshot encode/decode and completes settlement with flat, released
     positions.

3. No-funding regression:

   - `rejectsFundingForDeliveryAndOptionWithoutStateMutation` explicitly
     invokes `applyFunding` for `LINEAR_DELIVERY`, `INVERSE_DELIVERY`, and
     `OPTION`. Each rejects with exact code `PRODUCT_LINE_UNSUPPORTED` and
     preserves object identity, business hash, and funding settlements.

The natural reservation strengthening is covered by
`cancelsOpenDeliveryOrderAndReleasesOwnerReservation`, which asserts the
delivery order owner's reservation is fully released, the order is canceled,
the balance lock is zero, and total funds are unchanged.

### Red-to-green transcript

```text
RED  CoreDeliveryOptionFinancialMatrixTest.settlingIsolatedLossKeepsUnrelatedCrossStateAndReservationIntact
     Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
     expected: 139L but was: 119L

GREEN same test after the minimal reducer change
     Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

## Final integrity checks

- `git diff --check`: passed.
- Conflict-marker scan over changed Java files: no markers.
- `.factorypath` scan outside build output: none found.
- No integration/runtime, matcher reconstruction, perpetual funding, or wallet-service changes.
- Final commit SHA is reported by the Task 8 handoff after the atomic commit is created.
