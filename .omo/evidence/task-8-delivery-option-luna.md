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

Result: GREEN; all 6 reactor modules succeeded; 20 tests, 0 failures, 0 errors, 0 skipped.

```text
CoreLifecycleStateTest: 14 passed
CoreDeliveryOptionFinancialMatrixTest: 5 passed
CoreContractMathTest: 1 passed
```

The focused matrix also passed independently: 5 tests, 0 failures, 0 errors, 0 skipped.

Named QA methods, each run as an individual JDK25 Maven test:

```text
CoreDeliveryOptionFinancialMatrixTest#coversDeliveryAndOptionMoneyness: 1 passed
CoreDeliveryOptionFinancialMatrixTest#rejectsUntrustedCashDuplicateMutationAndWrongLine: 1 passed
```

Affected reactor:

```text
mvn -pl :surprising-aeron-service -am test
```

Result: GREEN; 147 tests, 0 failures, 0 errors, 0 skipped; all 6 reactor modules succeeded. No wallet service was started.

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

The matrix also verifies expiry metadata, duplicate settlement idempotency, cursor rejection after a repeated chunk, snapshot cursor resume for inverse delivery and options, open-order cancellation before settlement, zero positions, released margin/locks, product-line rejection, and exact user/maker/treasury conservation.

## Final integrity checks

- `git diff --check`: passed.
- Conflict-marker scan over changed Java files: no markers.
- `.factorypath` scan outside build output: none found.
- No integration/runtime, matcher reconstruction, perpetual funding, or wallet-service changes.
- Final commit SHA is reported by the Task 8 handoff after the atomic commit is created.
