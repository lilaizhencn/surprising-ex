# Product-Line Testing and Funds Conservation Reconciliation

English | [简体中文](product-line-testing-and-funds-reconciliation_CN.md)

## Goal

The product-line smoke suite is not a shallow API `200 OK` check. It simulates users calling real APIs through the trading flow and then runs an independent accounting reconciliation. A one-unit asset mismatch must fail the run.

Current scripts:

- `scripts/product-line-api-flow-smoke.sh`: starts the required providers for one product line and executes user orders, matching, position creation, self-close, liquidation, lifecycle events, and market-maker flow.
- `scripts/product-line-funds-reconcile.sh`: independent SQL reconciliation for balances, ledgers, funding, liquidation fees, delivery/exercise, insurance fund credits, and reservations.

## Run One Product Line at a Time

Only the providers required for the current product line are started. The four matching businesses do not need to run together:

```bash
PRODUCT_LINES=LINEAR_PERPETUAL BUILD_SERVICES=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=LINEAR_DELIVERY BUILD_SERVICES=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=OPTION BUILD_SERVICES=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=SPOT BUILD_SERVICES=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
```

Wallet services are not part of this local smoke. Test funds are injected through account/admin product balance adjustments or script fixtures, and the reconciliation script reports those as `adjustment_units`.

## API Flow Coverage

| Flow | Spot | Perpetual | Delivery | Option |
| --- | --- | --- | --- | --- |
| User deposit/adjustment | Yes | Yes | Yes | Yes |
| Market-maker continuous quoting | Yes | Yes | Yes | Yes |
| Ordinary user API taker order | Yes | Yes | Yes | Yes |
| Matching and order status | Yes | Yes | Yes | Yes |
| Spot asset exchange | Yes | No | No | No |
| Position creation | No | Yes | Yes | Yes |
| User self-close | No | Yes | Yes | Yes |
| Risk scan | No | Yes | Yes | Yes |
| Liquidation | No | Yes | Yes | Yes |
| Liquidation fee credited to insurance fund | No | Yes | Yes | Yes |
| Funding | No | Yes | No | No |
| Delivery settlement | No | No | Yes | No |
| Option exercise | No | No | No | Yes |
| Reservation release | Yes | Yes | Yes | Yes |
| Independent funds reconciliation | Yes | Yes | Yes | Yes |

## Reconciliation Formula

`product-line-funds-reconcile.sh` uses integer database units. It does not use floating point arithmetic or rounding.

For every product line, user, and asset, the report includes:

- `opening_units`: balance before the first ledger entry. Smoke defaults to strict zero opening.
- `adjustment_units`: deposits, admin adjustments, fixture funding.
- `trade_units`: spot principal, derivative PnL, and option premium.
- `fee_units`: spot fees and derivative trade fees.
- `funding_units`: perpetual funding payments.
- `liquidation_fee_units`: user liquidation fee debits.
- `delivery_settlement_units`: delivery futures cash settlement.
- `option_exercise_units`: option exercise settlement.
- `transfer_units`: product account transfers.
- `margin_adjustment_units`: position margin migration and release.
- `final_ledger_units`: balance after the last ledger entry.
- `final_available_units + final_locked_units - final_deficit_units`: final net balance table state.

The final equation must hold exactly:

```text
opening
  + adjustment
  + trade
  + fee
  + funding
  + liquidation fee
  + delivery settlement
  + option exercise
  + transfer
  + margin adjustment
= final ledger balance
= available + locked - deficit
```

## Failure Conditions

Any of the following fails the reconciliation:

- Ledger running balance is discontinuous.
- Final ledger balance differs from the balance table.
- `available_units`, `locked_units`, `deficit_units`, or position margin contains an invalid negative value.
- Funding payment rows do not match funding ledger entries, or one funding settlement does not sum to zero.
- Spot principal is not conserved per trade and asset.
- Option `OPTION_PREMIUM` is not conserved per trade.
- Derivative cash across `TRADE_PNL + OPTION_PREMIUM + DELIVERY_SETTLEMENT + OPTION_EXERCISE` is not conserved.
- A user `LIQUIDATION_FEE` debit has no equal insurance fund credit.
- Fee, liquidation, delivery, or exercise references are malformed or cannot be tied back to the matching/lifecycle record.
- Spot locked balance does not match active spot reservations.
- Position margin exceeds locked balance, or margin reservation releases exceed the reserved amount.

## Run Reconciliation Directly

```bash
PRODUCT_LINES="LINEAR_PERPETUAL LINEAR_DELIVERY OPTION SPOT" \
DB_NAME=surprising_product_line_smoke \
./scripts/product-line-funds-reconcile.sh
```

Common variables:

| Variable | Default | Description |
| --- | --- | --- |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `POSTGRES_PORT` | `5432` | PostgreSQL port |
| `DB_USER` / `DB_PASSWORD` | `surprising` | Database credentials |
| `DB_NAME` | `surprising_product_line_smoke` | Smoke database |
| `PRODUCT_LINES` | `LINEAR_PERPETUAL LINEAR_DELIVERY OPTION SPOT` | Product lines to reconcile |
| `STRICT_ZERO_OPENING` | `true` | Require test-user opening balance to be zero |
| `MAX_REPORT_ROWS` | `200` | Maximum violation detail rows |

## Latest Verification

The latest per-line run passed:

- `LINEAR_PERPETUAL`: API order flow, matching, position creation, self-close, liquidation, funding, insurance credit, and reconciliation.
- `LINEAR_DELIVERY`: API order flow, matching, position creation, self-close, liquidation, delivery event, position closeout, and reconciliation.
- `OPTION`: API order flow, matching, position creation, self-close, liquidation, exercise event, position closeout, and reconciliation.
- `SPOT`: API order flow, matching, asset exchange, reservation release, no derivative positions, and reconciliation.

All covered scenarios ended with 0 reconciliation violations.
