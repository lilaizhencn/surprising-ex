# surprising-account

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange account and perpetual position module. The current implementation provides long-based balances, ledger entries, idempotent trade processing, net position updates, order-margin to position-margin migration, and maker/taker fee settlement after trades.

## Modules

- `surprising-account-api`: account/position RPC contracts and DTOs.
- `surprising-account-provider`: balances, ledger, positions, and match-trade consumption.

## Long Units

- Balances use `availableUnits`, `lockedUnits`, and `equityUnits`, all long values in the asset's smallest unit.
- Positions use `signedQuantitySteps`: positive is net long, negative is net short.
- Positions store `instrumentVersion`, pinning contract math to the version that opened the current exposure.
- Positions and position collateral store `marginMode`; current executable order flow is `CROSS` only.
- Entry price uses `entryPriceTicks`.
- Position collateral is stored in `account_position_margins.margin_units`.
- Realized PnL is accumulated as `realizedPnlUnits` in the instrument settlement asset. Linear contracts use tick-step notional; inverse contracts use the contract face value and reciprocal entry/exit price formula.
- Trading fees use `maker_fee_rate_ppm` / `taker_fee_rate_ppm` snapshotted on each side's order. Positive rates debit the user; negative rates credit rebates. The instrument version only provides the default rate.
- Losses beyond `availableUnits + lockedUnits` are recorded in `account_deficits` instead of making balance columns negative.

## Trade Processing

`surprising-account-provider` consumes:

```text
surprising.perp.match.trades.v1
```

Each `MatchTradeEvent` updates:

- The taker user's position using `takerSide`.
- The maker user's position using the opposite side.
- The filled opening quantity moves only the actual fill-price initial margin into `account_position_margins`; price-improvement or market-protection excess is released back to `availableUnits`.
- The filled closing quantity releases old position margin back from `lockedUnits` to `availableUnits`.
- Realized PnL from closing fills is written to `account_ledger_entries` with `reference_type = TRADE_PNL`.
- Maker/taker fees from every fill are written to `account_ledger_entries` with `reference_type = TRADE_FEE`, including `trade_id`, `order_id`, `symbol`, and `fee_rate_ppm` audit fields.
- Flip trades close old exposure first, then treat the remaining fill as new exposure.
- If a trade flips a position, realized PnL uses the old position version and the new remainder adopts the fill's `instrumentVersion`.
- Opening fills must find the matching `account_margin_reservations` row and migrate order margin successfully. Missing reservations, reduce-only orders producing opening quantity, or skipped migration writes fail and roll back the whole trade.
- Closing fills may skip order-margin release only for `reduceOnly=true` orders. A non-reduce-only order that closes exposure must still have its original reservation row.
- Position updates, balance/deficit updates, PnL/fee ledger inserts/backfills, order-margin release, and position-margin changes must each affect one row. Unexpected skipped writes are not ignored.
- After position quantity or version changes, reduce-only pruning runs: wrong-side, stale-version, or excess open reduce-only orders are marked `CANCEL_REQUESTED` and cancel commands are emitted through the order-command outbox. Capacity checks use checked absolute value and checked pending-quantity accumulation, so impossible position quantities fail before cancel commands are emitted.

`account_processed_trades(symbol, trade_id)` is the trade idempotency key, so repeated delivery does not update positions twice and same-number trade ids from different symbols are not treated as duplicates.

## API

Query balances:

```bash
curl 'http://localhost:9086/api/v1/accounts/balance?userId=1001&asset=USDT'
curl 'http://localhost:9086/api/v1/accounts/balances?userId=1001'
```

Query positions:

```bash
curl 'http://localhost:9086/api/v1/accounts/position?userId=1001&symbol=BTC-USDT'
curl 'http://localhost:9086/api/v1/accounts/position?userId=1001&symbol=BTC-USDT&marginMode=CROSS'
curl 'http://localhost:9086/api/v1/accounts/positions?userId=1001'
```

Admin balance adjustment:

```bash
curl -X POST 'http://localhost:9086/api/v1/accounts/admin/balance-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"asset":"USDT","amountUnits":100000000,"referenceId":"deposit-1001-1","reason":"INITIAL_DEPOSIT"}'
```

In production, admin APIs must only be callable by deposit, settlement, or controlled back-office systems.

## Database

Root [init.sql](../init.sql) creates:

- `account_sequences`
- `account_balances`
- `account_deficits`
- `account_ledger_entries`
- `account_margin_reservations`
- `account_position_margins`
- `account_positions`
- `account_processed_trades`

Core indexes:

- `account_ledger_reference_uidx`
- `account_deficits_user_idx`
- `account_margin_reservations_user_idx`
- `account_position_margins_user_idx`
- `account_positions_user_idx`
- `account_processed_trades_symbol_idx`

## Local Run

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-account-provider -am spring-boot:run
```

Port:

- `9086`: account and position service.

## Production Notes

- Balance adjustments must include a globally unique `referenceId` to prevent duplicate deposits or reversals. A replay with the same reference is accepted only when `amountUnits` and `reason` match the original ledger row; conflicting payloads fail before mutating balances.
- Account provider must deduplicate match trades by `(symbol, trade_id)`, not by bare `tradeId`.
- Order entry reserves initial margin before publishing to matching. Account provider consumes match trades, calculates opening collateral from the actual fill price, migrates that amount into position margin, and releases any order-price or market-protection excess.
- `account_positions`, `account_position_margins`, and `account_margin_reservations` persist `margin_mode`. Do not drop this field from events or queries; later isolated-margin risk depends on it.
- Closing trades release position margin proportionally by closed quantity. This is long-only accounting and must stay consistent with exchange-core ticks/steps.
- Reduce-only pruning is not a matching-engine feature; account-provider locks the affected orders inside the position-update transaction and publishes cancel commands. Multi-node deployments must share the same PostgreSQL state and symbol-partitioned order-command topic.
- Reduce-only pruning must fail fast on impossible signed quantities such as `Long.MIN_VALUE`; do not let capacity math wrap and cancel or retain orders based on a negative absolute value.
- If `missing order margin reservation for opening fill` appears, inspect order-provider reservation writes, matching processing of invalid orders, and any manual database changes to reservations.
- If `missing order margin reservation for closing fill` appears, check whether a non-reduce-only close/flip order was accepted without the order-provider reservation transaction.
- Realized losses may consume `availableUnits` and position-margin-backed `lockedUnits`, but they must not consume open-order reservation locks. If position-backed locked collateral is debited, `account_position_margins` is reduced in the same transaction.
- Trade-fee debits use the same balance/deficit safety path as realized losses. Fee rebates first clear deficits, then increase available balance. Settlement must read the rate snapshot from `trading_orders`, not recompute old orders from the user's current tier or the current instrument fee.
- `contract_type` controls realized PnL: `LINEAR_PERPETUAL` settles `signedQty * (exitTicks - entryTicks) * notional_multiplier_units`; `INVERSE_PERPETUAL` settles `signedQty * faceValueUnits * settleScaleUnits * (exitTicks - entryTicks) / (entryTicks * exitTicks * price_tick_units)`.
- Maintenance margin and unrealized PnL are calculated by the risk module. Funding fees, insurance fund, and auto-deleveraging are handled by separate settlement modules, not by this provider.

## Verification

```bash
mvn -pl :surprising-account-provider -am test
```
