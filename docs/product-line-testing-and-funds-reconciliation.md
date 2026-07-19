# Product-Line Testing and Funds Conservation Reconciliation

English | [简体中文](product-line-testing-and-funds-reconciliation_CN.md)

## Goal

The product-line smoke suite is not a shallow API `200 OK` check. It simulates users calling real APIs through the trading flow and then runs an independent accounting reconciliation. A one-unit asset mismatch must fail the run.

Current scripts:

- `scripts/product-line-api-flow-smoke.sh`: starts the required providers for one product line and executes user orders, matching, position creation, self-close, liquidation, lifecycle events, and market-maker flow.
- `scripts/product-line-funds-reconcile.sh`: independent SQL reconciliation for balances, ledgers, funding, liquidation fees, delivery/exercise, insurance fund credits, and reservations.
- `scripts/run-linear-perpetual-stress-matrix.sh`: generates or executes a perpetual matching-concurrency, hotspot-traffic, and target-TPS comparison matrix.

## Run One Product Line at a Time

Only the providers required for the current product line are started. The four matching businesses do not need to run together:

```bash
PRODUCT_LINES=LINEAR_PERPETUAL BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=LINEAR_DELIVERY BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=OPTION BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=SPOT BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
```

Wallet services are not part of this local smoke. Test funds are injected through account/admin product balance adjustments or script fixtures, and the reconciliation script reports those as `adjustment_units`.

## Perpetual Multi-Symbol Stress

Example single run:

```bash
PRODUCT_LINES=LINEAR_PERPETUAL \
MULTI_SYMBOL_STRESS=true \
RESET_KAFKA=true \
CREATE_KAFKA_TOPICS=true \
STRESS_MATCHING_KAFKA_CONCURRENCY=8 \
STRESS_MATCHING_ENGINE_SHARDS=8 \
STRESS_MATCHING_RISK_SHARDS=4 \
STRESS_HOT_SYMBOL_COUNT=1 \
STRESS_HOT_TRAFFIC_PERCENT=80 \
STRESS_TARGET_TPS=80 \
STRESS_RUN_LABEL=scale8-hot1-80tps \
STRESS_REPORT_FILE=docs/scale8-hot1-80tps.md \
./scripts/product-line-api-flow-smoke.sh
```

`STRESS_HOT_SYMBOL_COUNT=0` distributes takers uniformly. Values such as `1` or `3` direct
`STRESS_HOT_TRAFFIC_PERCENT` of taker requests to the first one or three symbols. `STRESS_TARGET_TPS=0`
retains the original unbounded burst; a positive integer rate-limits API submission.

The matrix script prints its cases by default. Execute it only after confirming the environment and expected duration:

```bash
MATRIX_DRY_RUN=false \
MATRIX_PROFILES="baseline scale8 scale16" \
MATRIX_TRAFFIC_MODES="uniform hot1 hot3" \
MATRIX_TARGET_TPS_LIST="30 50 80 120" \
MATRIX_REPEATS=3 \
./scripts/run-linear-perpetual-stress-matrix.sh
```

The profiles are `4/4/2`, `8/8/4`, and `16/8/4` for Kafka listener concurrency, matching engines,
and risk engines. Every case recreates the test topics, removes old topic state for the stable consumer
groups, performs funds reconciliation, and writes a separate report. Reports now include:

- segmented `order created → ACCEPTED → order command published → matching started → match/account command published
  → bilateral settlement` latency;
- p50/p95/p99/max grouped by Outbox provider owner, aggregate type, topic, and event type;
- separate open/close Trading Outbox publish latency, plus exact per-group peak pending rows, maximum pending
  age, and final backlog reconstructed from `created_at/published_at` without polling SQL during the run;
- shared Trading Outbox ownership mapped as `ORDER` to order-provider, `TRIGGER_ORDER` to trigger-provider,
  and the remaining stress aggregates to matching-provider; events without an open/close trace appear as `other`;
- symbol and trade distribution across matching-engine shards;
- Outbox statistics scoped to the current stress traces, taker users, and maker users.
- the top 20 statements by total execution time when `pg_stat_statements` is available.
- peak and final Kafka lag for matching, account, order-result, and position-maintenance consumer groups.

For slow-SQL ranking, PostgreSQL must preload `pg_stat_statements` and have the extension created before
the run. The script does not change instance-level database settings. It reports `N/A` instead of inventing
slow-SQL data when the view cannot be used.

The multi-symbol stress enables the internal market-maker whitelist by default. Trades between a
whitelisted maker and a real user still perform full balance, position, fee, and PnL settlement; only
self-trades where both sides are whitelisted use the internal-maker special path.

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
