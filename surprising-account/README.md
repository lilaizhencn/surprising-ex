# surprising-account

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange account and product settlement module. The current implementation provides long-based basic balances, product balances, ledger entries, idempotent trade processing, spot asset settlement, derivative position updates, order-margin to position-margin migration, maker/taker fee settlement, funding settlement, delivery/exercise ledger entries, and actual liquidation-fee collection for insurance-fund funding.

## Modules

- `surprising-account-api`: account/position RPC contracts and DTOs.
- `surprising-account-provider`: the sole writer for balances, ledgers, positions, reservations,
  deficits, funding, ADL, delivery/exercise, and trade-side settlement.

## Long Units

- Balances use `availableUnits`, `lockedUnits`, and `equityUnits`, all long values in the asset's smallest unit.
- Positions use `signedQuantitySteps`: positive is net long, negative is net short.
- Positions store `instrumentVersion`, pinning contract math to the version that opened the current exposure.
- Positions and position collateral store `marginMode`; both `CROSS` and `ISOLATED` are executable in the
  one-way net-position flow.
- Position query responses return `positionSide = NET`. The account schema still stores one net
  signed position per `userId + symbol + marginMode`; hedge-mode `LONG/SHORT` positions are not
  persisted yet.
- Entry price uses `entryPriceTicks`.
- Position collateral is stored in `account_position_margins.margin_units`.
- Realized PnL is accumulated as `realizedPnlUnits` in the instrument settlement asset. Linear contracts use tick-step notional; inverse contracts use the contract face value and reciprocal entry/exit price formula.
- Trading fees use the required `takerFeeRatePpm` / `makerFeeRatePpm` fields carried by
  `MatchTradeEvent`. Positive rates debit the user; negative rates credit rebates. Account settlement
  does not query `trading_orders` for fee rates on the hot path.
- Liquidation fees use the frozen `liquidation_orders.liquidation_fee_rate_ppm`. Account settlement charges only the actually collectible amount from user collateral and publishes the collected amount to the insurance-fund topic.
- Losses beyond `availableUnits + lockedUnits` are recorded in `account_deficits` instead of making balance columns negative.

## Per-user command processing

All fund mutations enter the product-scoped account command topic:

```text
surprising.<product-segment>.account.user.commands.v1
```

The Kafka key is `<PRODUCT_LINE>:<userId>`. The command, DLT, and result topics have 32 partitions,
and account-provider uses record acknowledgement and 32 listener lanes. This serializes one user's
commands within a product line without application locks while allowing unrelated users to run in
parallel. Order, matching, funding, ADL, insurance, delivery/exercise, and HTTP mutations emit
commands and never directly update mutable account tables.

Matching emits one `TRADE_SIDE_SETTLE` command for the taker and one for the maker. Each side runs
in that user's local database transaction and updates:

- The taker user's position using `takerSide`.
- The maker user's position using the opposite side.
- The filled opening quantity moves only the actual fill-price initial margin into `account_position_margins`; price-improvement or market-protection excess is released back to `availableUnits`.
- The filled closing quantity releases old position margin back from `lockedUnits` to `availableUnits`.
- Realized PnL from closing fills is written to `account_ledger_entries` with `reference_type = TRADE_PNL`.
- Maker/taker fees from every fill are written to `account_ledger_entries` with `reference_type = TRADE_FEE`, including `trade_id`, `order_id`, `symbol`, and `fee_rate_ppm` audit fields.
- If the filled order is a liquidation order, account-provider also writes `reference_type = LIQUIDATION_FEE` for the actual collected liquidation fee. The charge is capped by collectible collateral: cross margin can use available balance plus cross position margin in the same asset; isolated margin can use only that isolated position margin. Any uncollectible amount is not turned into a deficit and is not credited to the insurance fund.
- Successful liquidation-fee collection writes an account transactional outbox event to `surprising.<product-segment>.account.liquidation-fee.events.v1` keyed by settlement asset. Insurance-provider consumes this event and credits `insurance_fund_ledger(reference_type = LIQUIDATION_FEE)` idempotently by `tradeId:orderId`.
- Flip trades close old exposure first, then treat the remaining fill as new exposure.
- If a trade flips a position, realized PnL uses the old position version and the new remainder adopts the fill's `instrumentVersion`.
- Opening fills must find the matching `account_margin_reservations` row and migrate order margin successfully. Missing reservations, reduce-only orders producing opening quantity, or skipped migration writes fail and roll back the whole trade.
- Closing fills may skip order-margin release only for `reduceOnly=true` orders. A non-reduce-only order that closes exposure must still have its original reservation row.
- Position updates, balance updates, changed deficit updates, PnL/fee ledger inserts/backfills, order-margin release, and position-margin changes must each affect one row. Unexpected skipped writes are not ignored.
- After position quantity or version changes, reduce-only pruning runs: wrong-side, stale-version, or excess open reduce-only orders are marked `CANCEL_REQUESTED` and cancel commands are emitted through the order-command outbox. Capacity checks use checked absolute value and checked pending-quantity accumulation, so impossible position quantities fail before cancel commands are emitted.
- If settlement reduces the position to zero, account-provider also moves every exact-scope `PENDING` TP/SL/trailing trigger to `CANCELED` with `POSITION_CLOSED` in the same transaction. The position outbox event is emitted only after that update is part of the transaction, allowing trigger-provider to clean its Redis secondary index after commit.

`account_commands.command_id` plus its immutable envelope hash is the execution idempotency key.
`account_trade_settlements(product_line, symbol, trade_id)` tracks taker and maker completion; stale
one-sided settlement makes the `accountTradeSettlement` health indicator `DOWN`.
Command dependencies are persisted as `depends_on_command_id`, so correctness never depends on
producer order or result-topic delivery order. See
[account single-writer design](../docs/account-single-writer-command-lane_CN.md).

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
curl 'http://localhost:9086/api/v1/accounts/position?userId=1001&symbol=BTC-USDT&marginMode=CROSS&positionSide=NET'
curl 'http://localhost:9086/api/v1/accounts/position-margin?userId=1001&symbol=BTC-USDT&marginMode=ISOLATED'
curl 'http://localhost:9086/api/v1/accounts/positions?userId=1001'
curl 'http://localhost:9086/api/v1/accounts/positions?userId=1001&positionSide=NET'
```

Position queries accept the current one-way `NET` side. Hedge-mode `LONG` and `SHORT`
query values return `400` until the whole trading/account/risk schema supports dual-side positions.

Adjust isolated position margin:

```bash
curl -X POST 'http://localhost:9086/api/v1/accounts/position-margin-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"symbol":"BTC-USDT","marginMode":"ISOLATED","amountUnits":100000000,"referenceId":"iso-margin-add-1001-1","reason":"ADD_POSITION_MARGIN"}'

curl -X POST 'http://localhost:9086/api/v1/accounts/position-margin-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"symbol":"BTC-USDT","marginMode":"ISOLATED","amountUnits":-50000000,"referenceId":"iso-margin-remove-1001-1","reason":"REMOVE_POSITION_MARGIN"}'
```

Positive `amountUnits` moves funds from `availableUnits` to `lockedUnits` and increases
`account_position_margins.margin_units`. Negative `amountUnits` moves isolated position collateral
back to `availableUnits`. Reductions require a fresh risk position snapshot and must leave isolated
equity above maintenance margin plus `surprising.account.position-margin.removal-buffer-ppm`.
After a successful manual isolated-margin adjustment, account-provider writes a `POSITION_UPDATED`
transactional outbox event with `tradeId=0`. Downstream risk and WebSocket consumers should treat it
as a position-state trigger and re-read the latest position/risk state instead of interpreting it as
a trade fill.

Admin balance adjustment:

```bash
curl -X POST 'http://localhost:9086/api/v1/accounts/admin/balance-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"asset":"USDT","amountUnits":100000000,"referenceId":"deposit-1001-1","reason":"INITIAL_DEPOSIT"}'
```

Back-office operators should use the admin namespace through gateway:

- `GET /api/v1/admin/accounts/balances`
- `GET /api/v1/admin/accounts/product-balances`
- `GET /api/v1/admin/accounts/positions`
- `GET /api/v1/admin/accounts/ledger`
- `GET /api/v1/admin/accounts/product-ledger`
- `GET /api/v1/admin/accounts/transfers`
- `POST /api/v1/admin/accounts/balance-adjustments`
- `POST /api/v1/admin/accounts/product-balance-adjustments`
- `GET /api/v1/admin/accounts/adjustments`

`ledger`, `product-ledger`, `transfers`, and `adjustments` support the production admin cursor
paging contract: `limit`, `cursor`, and `sort`. Supported sort values are `createdAt.desc` and
`createdAt.asc`; responses keep the original list fields and additionally return `nextCursor`,
`hasMore`, `sort`, and `limit` for large account-history pages behind gateway.

The admin namespace requires `X-Admin-User-Id` from gateway, records `X-Admin-Username`, and writes
`account_admin_balance_adjustments` in the same transaction as the balance mutation. In production,
admin APIs must only be callable by deposit, settlement, or controlled back-office systems.

## Database

Root [init.sql](../init.sql) creates:

- native PostgreSQL account ID sequences with fixed 10,000-ID Hi/Lo allocation for ledger entries,
  transfers, order reservations, position/liquidation events, and account-command outbox/result/retry events
- `account_balances`
- `account_deficits`
- `account_ledger_entries`
- `account_admin_balance_adjustments`
- `account_margin_reservations`
- `account_position_margins`
- `account_positions`
- `account_commands`
- `account_command_submissions`
- `account_trade_settlements`

Core indexes:

- `account_ledger_reference_uidx`
- `account_ledger_liquidation_fee_order_idx`
- `account_deficits_user_idx`
- `account_margin_reservations_user_idx`
- `account_position_margins_user_idx`
- `account_positions_user_idx`
- `account_commands_processing_idx`
- `account_commands_dependency_idx`
- `account_trade_settlements_incomplete_idx`
- `account_outbox_pending_key_idx`

## Configuration

```yaml
surprising:
  account:
    kafka:
      product-line: LINEAR_PERPETUAL
      product-topics-enabled: true
      position-events-topic: surprising.linear-perp.account.position.events.v1
      liquidation-fee-events-topic: surprising.linear-perp.account.liquidation-fee.events.v1
      concurrency: 2
      user-command-concurrency: 32
      max-poll-records: 500
    trade-settlement:
      stale-after: 1m
    command-wait:
      timeout: 10s
      poll-delay-ms: 20
    outbox:
      batch-size: 1000
      publish-delay-ms: 20
      async-enabled: true
      max-in-flight: 32
      max-rows-per-key: 32
      send-window-size: 5
      send-timeout: 3s
    cache:
      contract-spec-max-entries: 4096
```

Set `product-line` to `SPOT`, `LINEAR_PERPETUAL`, `LINEAR_DELIVERY`, or `OPTION` when running
isolated product-line instances. Account command, DLT, and result topics are always product scoped;
there is no shared legacy fallback for financial commands.

The local cache is intentionally limited to immutable read snapshots:

- `contract-spec-max-entries` caches contract math by `(symbol, instrumentVersion)`.

Balances, positions, margin reservations, command idempotency, ledgers, and outbox state remain
PostgreSQL-authoritative. Redis positions are a revisioned, replayable query projection only.

Account outbox publishing keeps the Kafka key unchanged and claims a bounded contiguous due prefix per `topic + event_key`.
For ordinary event topics it submits up to `send-window-size` rows in id order before waiting and only
marks the continuous acknowledged prefix. The account-user-command topic deliberately keeps a window
of one, so a later financial command cannot overtake an unacknowledged command for the same user.
Successful ids are confirmed with one SQL update per drained batch.

`TRADE_SIDE_SETTLE` remains durably terminal in `account_commands`, but does not emit an unused command-result
outbox event. Order reservations and funding settlements still emit result events for their consumers.

The default account command concurrency is 32 and the default Hikari pool is 40, leaving eight connections
for outbox, scheduled reconciliation, and request traffic. Override them together with
`ACCOUNT_USER_COMMAND_CONCURRENCY` and `ACCOUNT_DB_MAX_POOL_SIZE`; use a transaction-pooling proxy and a
database-wide connection budget when running multiple product-line pods.

Account command metrics are exposed through Actuator/Prometheus:

- `surprising.account.command.events{outcome=applied|rejected|waiting_dependency|duplicate|failed}`
- `surprising.account.command.processing{outcome=...}`
- `surprising.account.command.event_lag{outcome=...}`
- `accountTradeSettlement` health details for stale one-sided trades

Use these metrics with Kafka lag, DLT count, PostgreSQL latency, waiting dependencies, and outbox age.
A technical failure deliberately retries without skipping the record and blocks that partition. Poison
envelopes go to the same-numbered DLT partition. Set `surprising.account.kafka.client-id` to a stable
unique value per account-provider pod and keep the product-line consumer group identical across replicas.

## Local Run

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-account-provider -am spring-boot:run
```

Port:

- `9086`: account and position service.

## Production Notes

- Balance adjustments must include a globally unique `referenceId` to prevent duplicate deposits or reversals. A replay with the same reference is accepted only when `amountUnits` and `reason` match the original ledger row; conflicting payloads fail before mutating balances.
- Do not call account mutation services outside `AccountUserCommandProcessor`. Run
  `scripts/check-account-single-writer.sh` in CI to preserve this boundary.
- Do not change the account command Kafka key from `<PRODUCT_LINE>:<userId>`. Raising consumer
  concurrency above the 32 topic partitions adds no parallelism.
- An HTTP timeout is an unknown result, not a failure. Retry with the same `referenceId`; a new
  reference creates a new financial intent.
- Order entry reserves initial margin before publishing to matching. Account provider consumes match trades, calculates opening collateral from the actual fill price, migrates that amount into position margin, and releases any order-price or market-protection excess.
- `account_positions`, `account_position_margins`, and `account_margin_reservations` persist `margin_mode`. Do not drop this field from events or queries; later isolated-margin risk depends on it.
- User isolated margin adjustments are idempotent by `referenceId` and write `account_ledger_entries.reference_type = POSITION_MARGIN_ADJUSTMENT`. A positive adjustment only moves available balance into position collateral; a negative adjustment only releases position collateral after checking the latest isolated risk snapshot.
- Closing trades release position margin proportionally by closed quantity. This is long-only accounting and must stay consistent with exchange-core ticks/steps.
- Reduce-only pruning is not a matching-engine feature; account-provider locks the affected orders inside the position-update transaction and publishes cancel commands. Multi-node deployments must share the same PostgreSQL state and symbol-partitioned order-command topic.
- Reduce-only pruning must fail fast on impossible signed quantities such as `Long.MIN_VALUE`; do not let capacity math wrap and cancel or retain orders based on a negative absolute value.
- If `missing order margin reservation for opening fill` appears, inspect order-provider reservation writes, matching processing of invalid orders, and any manual database changes to reservations.
- If `missing order margin reservation for closing fill` appears, check whether a non-reduce-only close/flip order was accepted without the order-provider reservation transaction.
- Realized losses may consume `availableUnits` and position-margin-backed `lockedUnits`, but they must not consume open-order reservation locks. If position-backed locked collateral is debited, `account_position_margins` is reduced in the same transaction.
- Trade-fee debits use the same balance/deficit safety path as realized losses. Fee rebates first clear deficits, then increase available balance. Settlement must read the rate snapshot from `trading_orders`, not recompute old orders from the user's current tier or the current instrument fee.
- Balance settlement locks `account_deficits` for equity correctness, but skips the `UPDATE account_deficits` statement when `deficit_units` is unchanged. Do not reintroduce unconditional zero-delta deficit writes on the hot trade path; when a deficit is created or cleared, the changed row must still be written and checked.
- Trade-side settlement already locks the current position before calculating the next state. Keep
  using the update path that accepts the previously locked signed quantity; adding another
  `SELECT ... FOR UPDATE` or post-update read adds two avoidable SQL round trips per side.
- Never infer a dependency from cross-topic arrival order. Persist `dependsOnCommandId`; result topics
  are only latency/observability hints and reconciliation reads `account_commands`.
- Liquidation-fee debits intentionally do not create new `account_deficits`. The insurance fund receives only amounts that account-provider actually collected from user collateral. This prevents the fund from being credited from an unpaid penalty.
- `surprising.account.liquidation-fee.events.v1` is at-least-once. Downstream insurance consumers must use `(reference_type, reference_id, asset)` with `reference_id = tradeId:orderId` as the idempotency key.
- `contract_type` controls realized PnL: `LINEAR_PERPETUAL` settles `signedQty * (exitTicks - entryTicks) * notional_multiplier_units`; `INVERSE_PERPETUAL` settles `signedQty * faceValueUnits * settleScaleUnits * (exitTicks - entryTicks) / (entryTicks * exitTicks * price_tick_units)`.
- Maintenance margin and unrealized PnL are calculated by risk. Funding, insurance, and ADL modules
  own their orchestration state, but all resulting account mutations execute only in this provider.

## Verification

```bash
mvn -pl :surprising-account-provider -am test
```
