# surprising-trading

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange trading module for spot, perpetual, delivery, and option product lines. The current implementation contains `surprising-order-provider`, `surprising-trigger-provider`, `surprising-trading-entry-provider`, and `surprising-matching-provider`: order entry, take-profit/stop-loss trigger orders, instrument-rule validation, idempotent persistence, product-line Kafka command publishing, real exchange-core order-book matching, matching results, and trade events.

## Modules

- `surprising-trading-api`: order RPC contracts, DTOs, and Kafka command/event models.
- `surprising-order-provider`: order entry provider.
- `surprising-trigger-provider`: take-profit and stop-loss conditional order provider.
- `surprising-trading-entry-provider`: combined deployable provider for order entry and trigger orders.
- `surprising-matching-provider`: `exchange-core` based matching provider.

## Long Fixed-Point Model

Order entry does not use `BigDecimal`. API, database, and Kafka commands use long values:

- `priceTicks`: number of price ticks. Display price = `priceTicks * price_tick_units / quote_asset_scale`.
- `quantitySteps`: number of quantity steps. Display quantity = `quantitySteps * quantity_step_units / base_asset_scale`.
- `MARKET` orders must use `priceTicks = 0`.
- `MARKET` orders only allow `IOC` or `FOK`.
- Notional validation uses `contract_type`. Linear contracts check `priceTicks * quantitySteps * notional_multiplier_units`; inverse contracts check `quantitySteps * notional_multiplier_units` because the multiplier is the quote face value per contract step. Both paths use `Math.multiplyExact` to reject long overflow.
- Market orders are protected by `markPriceTicks +/- marketMaxSlippagePpm` before exchange-core IOC/FOK submission. Order entry uses the same mark-derived execution band for risk checks: linear contracts reserve initial margin at the upper bound for both BUY and SELL, while inverse contracts reserve at the lower bound because collateral requirement increases as price falls.
- When `surprising.trading.order.risk.limit-price-protection-enabled=true`, limit orders also require a fresh mark price. BUY limit prices cannot exceed `markPriceTicks * (1 + limitPriceBandPpm / 1_000_000)`, and SELL limit prices cannot be below `markPriceTicks * (1 - limitPriceBandPpm / 1_000_000)`. Passive low bids and high asks are still allowed.
- Instrument versions store default maker/taker ppm fees. Order entry resolves user/VIP/market-maker overrides, snapshots the final rates on `trading_orders`, and account settlement charges fills from that snapshot.

Example for `BTC-USDT` with `price_tick_units = 10000000`, `quantity_step_units = 100000`,
USDT scale `100000000`, and BTC scale `100000000`:

- `priceTicks = 650000` means price `65000.0`.
- `quantitySteps = 10` means quantity `0.01 BTC`.

This aligns with the long-based input model used by `exchange-core`, so the matching provider passes `priceTicks` and `quantitySteps` directly to the order book.
Keep this invariant for the trading path: do not convert order, fill, margin, PnL, or funding-settlement values to `BigDecimal` in Java. External market-data modules may still store decimal display values, but trading execution and accounting must use scaled `long`.
Decimal values are only allowed at system boundaries: external market-data/FX parsing, admin input, REST display, and reports. Before a value enters order entry, matching, account, risk, liquidation, funding, insurance, or ADL, it must already be converted to instrument-defined ticks, steps, ppm, or asset units.
Critical core-path aggregations use checked long addition. Matching filled quantity and reduce-only pending close quantity fail with overflow instead of wrapping into a smaller value.

## Core Flow

```text
client / internal gateway
  -> POST /api/v1/trading/orders
  -> surprising-trading-entry-provider / surprising-order-provider
  -> PostgreSQL trading_orders + trading_order_events + trading_outbox_events
  -> outbox publisher
  -> surprising.<product-segment>.order.commands.v1
  -> surprising-matching-provider / exchange-core
  -> trading_match_results / trading_match_trades
  -> surprising.<product-segment>.match.results.v1 / surprising.<product-segment>.match.trades.v1
```

Trigger orders use a separate path:

```text
client / internal gateway
  -> POST /api/v1/trading/trigger-orders
  -> surprising-trading-entry-provider / surprising-trigger-provider
  -> PostgreSQL trading_trigger_orders
  -> consume surprising.<product-segment>.mark.price.v1
  -> submit reduceOnly order through the order-entry API
  -> normal order / matching / account / WebSocket flow
```

The order provider does not match orders and does not own WebSocket fanout. Order-state push should be a separate service consuming product-line order and match topics. Legacy `surprising.perp.*` topics remain available when product-topic routing is disabled.

## Margin Mode

Orders, matching commands, match trades, account reservations, and account positions now carry `marginMode`.
The default is `CROSS`. `ISOLATED` is wired through order entry, matching events, account margin, positions,
risk snapshots, funding, and liquidation. Cross-margin losses, trading fees, and funding payments can use cross
available balance plus cross position margin as collateral. Isolated margin consumes only the exact
`userId + symbol + asset + marginMode` position margin and does not touch other symbols or cross balance.
Manual isolated margin add/remove is handled by `surprising-account-provider` through
`POST /api/v1/accounts/position-margin-adjustments`. Switching a symbol between `CROSS` and `ISOLATED`
still requires the user to close that symbol's existing position and cancel open normal or trigger
orders first; order-provider and trigger-provider serialize this check with a PostgreSQL transaction
advisory lock on `userId + symbol`.

Position mode is user-scoped and defaults to `ONE_WAY`. Users can switch to `HEDGE` through the
account `position-mode` API only when they have no non-zero positions, no active orders, no pending
trigger orders, and no unsettled matching/account state. `ONE_WAY` orders use `positionSide = NET`.
`HEDGE` orders and trigger orders must carry `positionSide = LONG` or `SHORT`; closing the selected
hedge leg is normalized to reduce-only and the position side is carried through matching, account
positions/margins, risk snapshots, liquidation, funding, ADL, and WebSocket events.

## Trading Fees

- `init.sql` defaults `BTC-USDT` and `ETH-USDT` to maker `200 ppm` and taker `500 ppm`, or `0.02% / 0.05%`.
- `trading_fee_schedules` can configure user-global or per-symbol overrides. `source_type` supports `USER_OVERRIDE`, `VIP`, `MARKET_MAKER`, `PROMOTION`, and `RISK_OVERRIDE`. Per-symbol user fees win over user-global fees, then the instrument default is used.
- When multiple user-global schedules are active, source priority is `RISK_OVERRIDE`, `USER_OVERRIDE`, `PROMOTION`, `MARKET_MAKER`, then `VIP`; this prevents the automatic VIP job from overriding manual risk, user, campaign, or market-maker terms.
- Admin APIs: `POST /api/v1/admin/trading/fees/schedules` creates or updates schedules, `POST /api/v1/admin/trading/fees/schedules/{feeScheduleId}/disable` disables a schedule, and `GET /api/v1/admin/trading/fees/schedules` lists schedules. Schedule lists support cursor paging with `limit`, `cursor`, and `sort`; supported sort values are `updatedAt.desc`, `updatedAt.asc`, `createdAt.desc`, `createdAt.asc`, `effectiveTime.desc`, and `effectiveTime.asc`. Responses keep `schedules/count` and add `nextCursor`, `hasMore`, `sort`, and `limit`.
- VIP tier APIs: `POST /api/v1/admin/trading/fees/tiers` upserts tier rules, `GET /api/v1/admin/trading/fees/tiers` lists rules, `POST /api/v1/admin/trading/fees/tiers/refresh?userId=...` recalculates one user, `POST /api/v1/admin/trading/fees/tiers/refresh-active` recalculates active users, and `GET /api/v1/admin/trading/fees/tiers/users/{userId}` returns the current assignment. Tier lists support cursor paging with `limit`, `cursor`, and `sort`; supported sort values are `priority.desc` and `priority.asc`. Responses keep `tiers/count` and add `nextCursor`, `hasMore`, `sort`, and `limit`.

Admin order-audit APIs use the `/api/v1/admin/trading` prefix and are reached through the gateway admin security domains `/api/v1/admin/gateway/trading-orders` and `/api/v1/admin/gateway/trading-trigger`. `GET /orders`, `GET /trigger-orders`, and `GET /orders/trades` support `limit`, `cursor`, and `sort`; responses keep the original `orders` or `trades` field and additionally return `nextCursor`, `hasMore`, `sort`, and `limit`. Order and trigger-order lists support `createdAt.desc` and `createdAt.asc`; match-trade lists support `eventTime.desc` and `eventTime.asc`.
- The scheduled VIP refresher computes each user's 30-day maker+taker filled notional and total account asset value in USD/USDT units, then writes the selected tier back to `trading_fee_schedules` as a user-global `VIP` schedule. Stable balances count 1:1; non-stable balances use the latest mark price for an active `baseAsset-USDT/USD` instrument and are ignored if no mark is available.
- Runtime query: `GET /api/v1/trading/fees/effective?userId=...&symbol=...` returns the current maker/taker ppm and source, such as `INSTRUMENT` or `VIP_SYMBOL`.
- Order admission writes the final `maker_fee_rate_ppm` and `taker_fee_rate_ppm` to `trading_orders`. Later VIP or promotion changes do not reinterpret already accepted resting orders.
- The account provider settles fills from the order snapshot and writes `TRADE_FEE` ledger rows with `trade_id`, `order_id`, `symbol`, and `fee_rate_ppm`.
- Market-maker rebates should still be configured by a market-maker program or admin workflow that verifies quote quality; the default automatic tiers only assign `VIP` fees.

## Leverage Settings

- User leverage settings are stored in `trading_leverage_settings`, keyed by `user_id + symbol + margin_mode`.
- `leveragePpm` uses ppm: `10_000_000 = 10x`, `100_000_000 = 100x`.
- User APIs: `POST /api/v1/trading/leverage/settings` sets leverage, and `GET /api/v1/trading/leverage/settings?userId=...&symbol=...&marginMode=...` returns the current setting.
- Setting leverage first validates it against the current instrument version's `max_leverage_ppm`.
- Order margin reservation then selects the matching `instrument_risk_brackets` row from the order notional plus the current same-`marginMode` position notional. If the user setting exceeds that bracket's `max_leverage_ppm`, the order is rejected.
- Effective initial-margin rate = `max(leverage-derived margin rate, risk-bracket initial_margin_rate_ppm)`. When no user setting exists, order entry uses the current risk bracket's max leverage / initial margin rate.

## Order Amend

- Ordinary order amend uses cancel-replace semantics through order-provider; exchange-core is not modified.
- Only open `LIMIT` orders in `ACCEPTED` or `PARTIALLY_FILLED` state can be amended.
- Amend can change `priceTicks`, remaining `quantitySteps`, resting `timeInForce` (`GTC`/`GTX`), and `postOnly`.
- Amend cannot change `side`, `symbol`, `orderType`, `marginMode`, `positionSide`, or `reduceOnly`.
- The replacement order must use a new `newClientOrderId` for idempotency. Opening replacement orders run the same validation and fund reservation path as normal orders; original-order release still follows cancel matching result and account settlement.
- REST endpoints: `POST /api/v1/trading/orders/amend` and `POST /api/v1/trading/orders/batch-amend`.

## Cancel All After

`POST /api/v1/trading/orders/cancel-all-after` implements a dead-man switch for API clients:

- `countdownMs=0` disables the timer.
- A positive `countdownMs` refreshes the timer for the user and optional `symbol`; omitting `symbol` applies to all symbols.
- When the timer expires, order-provider cancels the user's open ordinary orders through the existing `cancel-open` path and calls trigger-provider to cancel pending TP/SL orders.
- The timer state is stored in `trading_cancel_all_after`; the latest execution records ordinary-order and trigger-order cancellation counts.

## Algo Orders

TWAP and Iceberg are implemented as an order-provider algo layer before exchange-core. The parent algo order never enters the live order book; scheduled child orders are ordinary order-provider orders and continue through matching, account settlement, risk, liquidation checks, and WebSocket fanout.

- `TWAP` validates `durationSeconds >= intervalSeconds` and that `childQuantitySteps` can finish the target quantity inside the configured duration. Child orders are IOC; `priceTicks=0` creates MARKET IOC children, while a positive price creates LIMIT IOC children.
- `ICEBERG` requires a positive limit price and `GTC` or `GTX`. It keeps only one visible child active at a time, then places the next slice after the previous child fills or is canceled.
- Active algo orders block margin-mode and position-mode switches because future child orders would otherwise be emitted under stale mode assumptions.
- Canceling a parent algo order also cancels any active child orders. `cancel-open` supports user-level and optional symbol-level bulk cancellation.

REST endpoints:

- `POST /api/v1/trading/orders/algo`
- `POST /api/v1/trading/orders/algo/cancel`
- `POST /api/v1/trading/orders/algo/cancel-open`
- `GET /api/v1/trading/orders/algo/{algoOrderId}`
- `GET /api/v1/trading/orders/algo/open`

## Take-Profit, Stop-Loss, And Trailing Stop

Large exchanges implement TP/SL as conditional orders outside the live order book. This module follows that model:

- A trigger order is stored in `trading_trigger_orders` with `PENDING` status. It does not enter exchange-core and does not reserve new margin.
- `MARK_PRICE`, `INDEX_PRICE`, and `LAST_PRICE` trigger sources are supported. `LAST_PRICE` consumes the real match-trade stream and is available for user TP/SL/trailing orders, but it remains riskier than mark/index in thin books and is not used for liquidation.
- Direction is derived from close side and trigger type: long TP is `SELL + TAKE_PROFIT` and triggers when the selected trigger source is greater than or equal to the trigger; long SL is `SELL + STOP_LOSS` and triggers when the selected source is less than or equal to the trigger. Short closes use the inverse conditions with `BUY`.
- `TRAILING_STOP` requires `MARKET` execution, `callbackRatePpm` in `[1000, 100000]` (`0.1%` to `10%`), and optional `activationPriceTicks`. A SELL trailing stop activates when the source reaches the activation price, tracks the highest post-activation price, and triggers when the source falls by the callback. A BUY trailing stop tracks the lowest price and triggers after an upward callback. Only trailing stops have these watermarks; fixed TP/SL rows never update them, and PostgreSQL writes a trailing watermark only on activation or a new extreme rather than on every price event.
- The trigger provider consumes the current product line's mark-price topic for `MARK_PRICE`, index-price topic for `INDEX_PRICE`, and match-trades topic for `LAST_PRICE`; it validates Kafka key = payload `symbol`, converts persisted mark/index rows to current instrument ticks, and claims due rows with PostgreSQL `FOR UPDATE SKIP LOCKED`. Legacy `surprising.perp.*` topics are used only when product-topic routing is disabled.
- Multiple trigger-provider nodes can run at the same time. Only one node can claim a due trigger row, and stale `TRIGGERING` rows are reset after `surprising.trading.trigger.execution.stale-triggering-after` so downstream failures retry on later mark events.
- Static `TAKE_PROFIT`/`STOP_LOSS` rows are always indexed in product-line/symbol/price-source Redis ZSETs through Spring Data Redis and Lettuce; no feature flag is required. One Lua call reads both greater-or-equal and less-or-equal ranges, then PostgreSQL rechecks the candidate ids and performs the existing `FOR UPDATE SKIP LOCKED` state transition. Trailing-stop high/low watermark updates stay in PostgreSQL.
- Redis scores use exact integer ticks up to `2^53-1`; an existing value above that range keeps the index not-ready and uses database fallback instead of risking floating-point misses. New static TP/SL placement is fail-closed when its Redis index write fails. Existing committed orders still trigger through the database fallback when Redis lookup is unavailable.
- The readiness marker is short-lived and refreshed after reconciliation. A token-owned `SET NX` lease with compare-and-delete Lua release prevents rebuild stampedes; it is not used to serialize trigger execution. Redis removal is idempotent and happens after authoritative terminal DB transitions, so a crash can leave only a safe stale candidate, never a false terminal DB state.
- When triggered, it calls order-provider with `reduceOnly=true`, `postOnly=false`, and `clientOrderId=trigger-<triggerOrderId>`. The order-provider idempotency key protects retries from creating duplicate close orders.
- Triggered orders go through the same order, matching, account, fee, PnL, risk, liquidation, and WebSocket flow as user-submitted close orders. The trigger service never mutates balances or positions directly.
- `MARKET` trigger execution requires `priceTicks=0` and `IOC` or `FOK`. Static TP/SL can also use `LIMIT` execution with a positive `priceTicks`; `GTX` is rejected for trigger execution.
- Optional `ocoGroupId` supports paired TP/SL. When any pending order in the same `userId + symbol + marginMode + ocoGroupId` group is claimed by its configured price-source event, the database claim statement also moves the other pending siblings to `CANCELED` before the generated reduce-only order is submitted.
- A fully closed position cancels all remaining `PENDING` triggers for its exact `productLine + userId + symbol + marginMode + positionSide` scope inside the account settlement transaction, with `rejectReason=POSITION_CLOSED`. This covers normal closes, liquidation fills, delivery settlement, and option exercise. After commit, the account position outbox event drives idempotent Redis ZSET cleanup; `TRIGGERING` rows are not stolen from an in-flight claim and finish through the existing reduce-only state machine.
- Batch trigger placement accepts `atomic=true` for composite TP/SL submissions. In atomic mode any validation failure rejects the whole batch, rolls back all inserted trigger rows, and returns per-item failed results; default batch mode still keeps item failures isolated.
- Because OCO siblings are canceled at claim time, a later downstream execution failure leaves the group consumed. This avoids duplicate close attempts under multi-node trigger-provider concurrency; clients can place a fresh TP/SL pair if execution fails.
- Every committed trigger status change (`PENDING`, `TRIGGERING`, `TRIGGERED`, `TRIGGER_FAILED`, `CANCELED`, or `EXPIRED`) writes a full `TriggerOrderUpdatedEvent` snapshot to `trading_outbox_events` in the same database transaction. The trigger outbox publisher sends it on the product line's `trigger-order.events` topic, and WebSocket exposes it as the authenticated private `triggerOrders` channel. Delivery is at least once, so clients deduplicate by `eventId` and reload `GET /open` after reconnect.
- The public API has no in-place trigger amend. A user price/quantity update is cancel-and-place with a new `clientTriggerOrderId`, which preserves validation and avoids a cross-store move race. `GET /open` reads authoritative `PENDING`/`TRIGGERING` rows from PostgreSQL; the private `triggerOrders` channel updates or removes list rows immediately, while generated close-order and fill changes continue through the normal private WebSocket channels.

REST endpoints:

```bash
curl -X POST 'http://localhost:9084/api/v1/trading/trigger-orders' \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: trace-tp-1001' \
  -d '{
    "userId": 1001,
    "clientTriggerOrderId": "tp-1001-1",
    "ocoGroupId": "bracket-1001-1",
    "symbol": "BTC-USDT",
    "side": "SELL",
    "triggerType": "TAKE_PROFIT",
    "triggerPriceType": "MARK_PRICE",
    "triggerPriceTicks": 700000,
    "orderType": "MARKET",
    "timeInForce": "IOC",
    "priceTicks": 0,
    "quantitySteps": 10,
    "marginMode": "CROSS"
  }'

curl -X POST 'http://localhost:9084/api/v1/trading/trigger-orders/cancel' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"triggerOrderId":1}'

curl 'http://localhost:9084/api/v1/trading/trigger-orders/open?userId=1001&symbol=BTC-USDT&limit=100'
curl 'http://localhost:9094/api/v1/gateway/trading-trigger/open?userId=1001&symbol=BTC-USDT&limit=100' -H 'X-User-Id: 1001'
```

Trigger-order user endpoints are also available through the gateway:
`/api/v1/gateway/trading-trigger` maps to direct `/api/v1/trading/trigger-orders`.

- `POST /api/v1/trading/trigger-orders/batch`: place up to 20 TP/SL trigger orders; set `atomic=true` when a multi-leg TP/SL group must be all-or-nothing.
- `POST /api/v1/trading/trigger-orders/batch-cancel`: cancel up to 50 trigger orders.
- `POST /api/v1/trading/trigger-orders/cancel-open`: cancel the user's `PENDING` trigger orders, optionally filtered by `symbol`, up to 1000 rows per call. Rows already in `TRIGGERING` are not canceled here to avoid racing trigger execution.

## Trace Id

- Public clients may send `X-Trace-Id`; otherwise gateway/order entry generates one.
- `surprising-order-provider` keeps the trace id in request scope only while handling the HTTP request, then writes it explicitly into `OrderEvent` and `OrderCommandEvent`.
- The outbox payload carries the trace id, so retries and Kafka replay keep the same request identity.
- `surprising-matching-provider` must copy `OrderCommandEvent.traceId` into every `MatchResultEvent` and generated `MatchTradeEvent`. Do not regenerate it in the matcher.
- `surprising-account-provider` copies `MatchTradeEvent.traceId` into `PositionUpdatedEvent`, allowing private WebSocket pushes to be correlated with order entry and matching audit rows.
- PostgreSQL stores `trace_id` in `trading_order_events`, `trading_match_results`, and `trading_match_trades`. Operational logs should include this id together with `orderId`, `commandId`, `tradeId`, symbol, and Kafka topic/partition/offset.

## Margin Reservation

Regular opening/resting orders reserve initial margin inside `surprising-order-provider`:

- Read `contract_type`, `initial_margin_rate_ppm`, `notional_multiplier_units`, `price_tick_units`, `settle_asset`, and asset scales from the current instrument version.
- Convert the requirement to `initialMarginUnits` in Java `OrderMarginMath`: inputs and outputs are long ticks/steps/asset units, while multiplication/division uses exact integer intermediates to reject overflow instead of wrapping.
- Insert `trading_orders` first, confirm there is no `clientOrderId` idempotency conflict, then move `account_balances.available_units` to `locked_units` and insert `account_margin_reservations`.
- `account_margin_reservations.order_id` has a foreign key to `trading_orders.order_id`, preventing margin reservations without an order.
- If the order row was inserted but margin is insufficient, the order is changed to `REJECTED` in the same transaction; only a rejection event is published and no matching command is emitted.

`reduceOnly=true` close and liquidation orders do not reserve additional margin.
Matching margin release treats missing reservations as valid only for `reduceOnly=true` orders. A non-reduce-only order without `account_margin_reservations` is an accounting invariant failure and must fail instead of silently continuing.

For user close orders, order entry validates reduce-only safety before publishing to matching:

- Long positions can only submit reduce-only `SELL` orders.
- Short positions can only submit reduce-only `BUY` orders.
- Existing open reduce-only close orders reserve close capacity, so the sum of pending close quantity plus the new order cannot exceed the current position.
- Pending close quantity is aggregated with checked long addition; overflow rejects the order or liquidation transaction instead of silently increasing close capacity.
- The validator locks the current `account_positions` row and matching open `trading_orders` rows with PostgreSQL `FOR UPDATE`, which keeps the check safe across multiple order-provider nodes.
- After fills, liquidation, or ADL changes a position, account-provider re-checks open reduce-only orders and cancels wrong-side, stale-version, or excess-capacity orders.

`surprising-matching-provider` releases all reserved margin on matching rejection and releases the unused remaining proportion on cancel or terminal immediate orders. `surprising-account-provider` consumes match trades, calculates filled opening collateral from the actual execution price, moves that amount from order reservation into `account_position_margins`, and releases order-price improvement or market-protection excess. Linear market orders intentionally reserve at the upper risk bound even for SELL orders, because a SELL market order can execute against higher resting bids than mark. Filled closing quantity releases old position margin instead of consuming new order margin.

Margin release uses a guarded PostgreSQL update with `locked_units >= releaseUnits`. If locked balance is insufficient, the matching transaction fails and triggers the command-failure restart path, so the process restarts, restores the book from DB, and lets Kafka replay continue. Do not use `GREATEST(0, locked_units - releaseUnits)` here because it can turn an inconsistent locked balance into newly available funds.

## Instrument Rules

Order entry reads current instrument rules from PostgreSQL tables maintained by `surprising-instrument`:

- `instrument_current_versions`
- `instruments`

Instrument already stores exchange-core-aligned long rule boundaries:

- `min_quantity_steps -> minQuantitySteps`
- `max_quantity_steps -> maxQuantitySteps`
- `min_notional_units`, `max_notional_units`, and `notional_multiplier_units` are kept as long units and evaluated by `contract_type`.
- `LINEAR_PERPETUAL` order notional = `priceTicks * quantitySteps * notional_multiplier_units`.
- `INVERSE_PERPETUAL` order face value = `quantitySteps * notional_multiplier_units`.
- `max_leverage_ppm` and `instrument_risk_brackets` participate in order margin reservation; higher risk brackets lower max leverage and raise the minimum initial-margin rate.
- `maker_fee_rate_ppm` and `taker_fee_rate_ppm` are not passed into exchange-core. Instrument provides
  the default, `trading_fee_schedules` can override by user globally or by symbol, order admission
  freezes the final rates on `trading_orders`, and matching copies the taker/maker rates into
  `OrderCommandEvent` / `MatchTradeEvent` so account settlement does not re-read fee schedules or orders.

The trading Java module remains long-only.

## Instrument Version Pinning

- Every accepted order stores `instrument_version` from the rule snapshot used at validation time.
- Reduce-only close orders bind to the current position version, so users can close an old-version position safely.
- `OrderCommandEvent` carries `instrumentVersion`; matching results keep the taker command version.
- `MatchTradeEvent` carries both `takerInstrumentVersion` and `makerInstrumentVersion`, allowing account settlement to use each side's own contract formula.
- The matching provider rejects a new `PLACE` command when the symbol already has open orders from a different `instrument_version`. This prevents exchange-core from matching two incompatible tick/multiplier versions in the same book.
- Operationally, changes to tick size, quantity step, multiplier, contract type, or settlement asset should be done only after halting the symbol and clearing open orders.

## Idempotency And Multi-Node Safety

- `trading_orders_user_client_order_uidx` makes `(userId, clientOrderId)` idempotent.
- Order inserts only suppress that partial `(userId, clientOrderId)` conflict. `orderId` or unrelated unique-key conflicts must fail instead of being treated as request replay.
- Idempotency conflicts happen before margin reservation. Replayed requests return the existing order and never create a new reservation or lock balance twice.
- PostgreSQL native sequences (`trading_order_seq`, `trading_event_seq`, `trading_command_seq`, `trading_outbox_seq`, and related trading sequences) allocate ids. They are used instead of table-counter rows to avoid a hot row-lock under concurrent order entry and matching.
- `trading_outbox_events` is committed in the same transaction as the order.
- `trading_trigger_orders_user_client_uidx` makes `(userId, clientTriggerOrderId)` idempotent for TP/SL placement.
- `ocoGroupId` groups paired TP/SL rows for one-cancels-other behavior; it is optional, scoped by `userId + symbol + marginMode`, and does not replace `clientTriggerOrderId`.
- `trading_order_events` and `trading_outbox_events` inserts must affect exactly one row; otherwise the transaction fails to avoid order/message divergence.
- The outbox publisher first claims due rows with `FOR UPDATE SKIP LOCKED` by moving `next_attempt_at` to a short lease, then publishes to Kafka outside the database transaction. This keeps Kafka network waits from holding PostgreSQL row locks or connections.
- Failed outbox rows retry with exponential backoff through `next_attempt_at`, avoiding hot loops during Kafka incidents.
- Kafka producer uses `acks=all` and `enable.idempotence=true`.
- Downstream consumers must deduplicate commands by `commandId/orderId` and events by `eventId`.

## Kafka

- `surprising.<product-segment>.order.commands.v1`: matching commands, key = `symbol`.
- `surprising.<product-segment>.order.events.v1`: order entry events, key = `symbol`.
- `surprising.<product-segment>.match.results.v1`: matching results, key = `symbol`.
- `surprising.<product-segment>.match.trades.v1`: matching trades, key = `symbol`, with prices and quantities still represented as long ticks/steps.
- `surprising.<product-segment>.orderbook.depth.v1`: L2 order book depth updates, key = `symbol`.
- `surprising.<product-segment>.mark.price.v1`: mark-price stream consumed by trigger-provider, key = `symbol`.

Legacy `surprising.perp.*` topics remain available for backward-compatible single-line perpetual startup.

Partitions should continue to scale by symbol. All commands for the same symbol must route to the same matching shard/order book.
`surprising.trading.matching.kafka.max-poll-records` defaults to `500`; tune it with Kafka lag and command processing latency instead of changing code.

## exchange-core Matching

`surprising-matching-provider` loads current `TRADING` symbols from instrument and creates stable exchange-core symbol/currency ids:

- `trading_matching_assets`
- `trading_matching_symbols`

Symbols are registered in exchange-core as `CURRENCY_EXCHANGE_PAIR`. This is intentional: exchange-core is used as a deterministic long-based order book and matcher, while futures margin, trading fees, liquidation, funding, insurance, and ADL are handled by the surrounding services.

For each `OrderCommandEvent`:

- `PLACE` -> `ApiPlaceOrder`
- `CANCEL` -> `ApiCancelOrder`
- `BUY` -> `OrderAction.BID`
- `SELL` -> `OrderAction.ASK`
- `GTC/IOC/FOK/GTX` -> exchange-core order types; GTX/post-only checks the book only for `PLACE` before submit and rejects if it would take liquidity. `CANCEL` must bypass post-only checks.
- `MARKET` maps to an IOC/FOK protected limit order derived from the latest mark price and configured max slippage.
- `IOC`, `FOK`, and `MARKET` orders are terminal when matching returns, so the unfilled frozen margin is released after the matching result is applied. If a filled MARKET order reserved at the conservative risk bound but executed at a better book price, account settlement releases the excess when the trade is processed.
- `trading_match_trades` uses `(symbol, trade_id)` as the trade idempotency key, matching the Kafka partitioning and replay model.
- `trading_match_results` and `trading_match_trades` are idempotent replay gates. If a result or trade row already exists, the service skips the downstream side effects for that row instead of reapplying order fills, margin release, or outbox writes.
- Guarded order fill/status updates, margin release, and matching outbox writes must still affect exactly one row. If an overfill guard, inconsistent quantity invariant, missing target row, or outbox write skips a row, the matcher fails and restarts instead of continuing with a mutated in-memory exchange-core book.

The provider uses the real exchange-core order book and matcher event chain, but exchange-core risk processing and built-in fees are disabled. Order entry reserves initial margin before command publication; account-provider migrates filled opening margin into position margin and writes maker/taker `TRADE_FEE` ledger entries from the required fee rates on `MatchTradeEvent`. Funding, insurance, and ADL are handled by separate settlement modules.

### Order Book Depth

After every successful exchange-core command that changes the book, matching publishes a depth event through the matching outbox:

- first event for a symbol, and every `surprising.trading.matching.engine.order-book-snapshot-interval-events`, is `SNAPSHOT`;
- normal updates are `DELTA` events containing only changed price levels;
- `quantitySteps=0` and `orderCount=0` means delete that price level;
- `sequence` is allocated from PostgreSQL and `previousSequence` links deltas to the last published depth event;
- clients must reload a snapshot when `previousSequence` does not match their local last sequence.

Public REST snapshot endpoint:

```bash
curl 'http://localhost:9085/api/v1/trading/market/orderbook?symbol=BTC-USDT&depth=50'
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
```

`DELTA` levels are absolute price-level states, not quantity differences. Apply them by replacing the local price level, or deleting it when `quantitySteps=0`.

Depth events are market-data fanout, not accounting state. PostgreSQL remains the order-state audit source, and exchange-core remains the live order-book source.

### Order Book Recovery

Startup recovery is enabled by default:

```yaml
surprising:
  trading:
    matching:
      recovery:
        open-order-book-restore-enabled: true
        open-order-batch-size: 10000
```

On startup the matching provider rebuilds exchange-core books from PostgreSQL open orders:

- Only current `TRADING` instruments are restored.
- Only open `LIMIT` + `GTC/GTX` orders with `remaining_quantity_steps > 0` are restored.
- The order must already have a successful `PLACE` row in `trading_match_results`; an accepted DB order that never reached matching is not injected into the book.
- Restore order is `created_at, order_id`, so maker priority is deterministic for recovered books.
- If recovered orders cross the book and generate matcher events during restore, startup fails fast. Do not silently continue with a corrupt persisted book.

This is DB open-order reconstruction, not native exchange-core journal replay. It is enough for service restart and failover correctness because the database is the authoritative order state. If later latency targets require sub-second hot recovery for very large books, add exchange-core snapshot/journal persistence and keep DB recovery as an audit fallback.

### Multi-Node Matching

The product-line order command topic must be keyed by `symbol`. Kafka assigns each partition to one consumer in that product line's matching consumer group, so one live matcher owns a symbol partition at a time.

The matching consumer uses cooperative sticky assignment and `MatchingPartitionAssignmentGuard`:

- At process start, the node restores open books from DB before consuming commands.
- If a running matcher that has already processed commands receives a new partition, it closes the Spring context. Kubernetes/systemd should restart it, and the fresh process restores current books from DB before consuming.
- This avoids an unsafe case where a long-running process receives a symbol whose local exchange-core book is stale.
- If command processing fails after the payload has been decoded, the matcher also closes the Spring context. This avoids retrying a Kafka command against an exchange-core book that may already have been mutated before PostgreSQL/outbox persistence failed.
- This covers fail-fast write failures for match results, trades, order status updates, margin release, and matching outbox rows. Check sequences, unique-index conflicts, missing order rows, and transaction errors first.
- Keep `surprising.trading.matching.kafka.restart-on-partition-reassignment=true` in production.
- `surprising.trading.matching.kafka.partition-assignment-startup-grace-ms` defaults to `30000` so concurrent listener containers can finish their initial assignment before the guard starts treating new partitions as unsafe runtime movement.
- The command-failure restart is unconditional; the partition-reassignment flag only controls restart behavior for partition movement.
- Set `surprising.trading.matching.kafka.client-id` to a stable unique value per matching pod. This keeps Kafka consumer-group output usable for mapping a `symbol` partition to the exact node that owns the local exchange-core order book.

Scaling rule: increase topic partitions and matching instances deliberately. Avoid frequent autoscaling of matching pods because every partition movement may require restart-and-recover.
Treat any matching process exit during command handling as a required DB-recovery restart, not as an in-place consumer retry.

## Self-Trade Prevention

`surprising-matching-provider` rejects a new taker order when the same user has an open opposite-side order that is marketable at the incoming effective price.

- BUY checks own open SELL orders with `priceTicks <= effectivePriceTicks`.
- SELL checks own open BUY orders with `priceTicks >= effectivePriceTicks`.
- `CANCEL_REQUESTED` orders are included because they may still be live in exchange-core until the cancel command is processed.
- The rejected match result uses `SELF_TRADE_PREVENTED` and releases any reserved margin through the normal rejection path.

## API

Place a limit order:

```bash
curl -X POST 'http://localhost:9084/api/v1/trading/orders' \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: trace-demo-1001' \
  -d '{
    "userId": 1001,
    "clientOrderId": "cli-1001-1",
    "symbol": "BTC-USDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "timeInForce": "GTC",
    "priceTicks": 650000,
    "quantitySteps": 10,
    "reduceOnly": false,
    "postOnly": false
  }'
```

Frontend and BFF traffic should normally use the gateway route. `POST /api/v1/gateway/trading`
maps to direct `POST /api/v1/trading/orders`, and child paths are preserved, for example
`/api/v1/gateway/trading/test`, `/batch`, `/close-position`, and `/cancel-open`.

User order endpoints:

- `POST /api/v1/trading/orders`: place a normal order. `clientOrderId` is idempotent per user.
- `POST /api/v1/trading/orders/test`: dry-run an order. It validates request fields, instrument rules, reduce-only safety, fee snapshot availability, and opening-reserve requirements; it does not insert `trading_orders`, reserve balances, or publish Kafka commands.
- `POST /api/v1/trading/orders/batch`: place up to 20 orders and return per-item results. A business-rejected item still returns its order response.
- `POST /api/v1/trading/orders/close-position`: one-click close the current position. The service locks the current `account_positions` row and creates a `reduceOnly=true`, `MARKET + IOC` close order from the live position side and quantity.
- `POST /api/v1/trading/orders/cancel`: cancel by `orderId`.
- `POST /api/v1/trading/orders/batch-cancel`: cancel up to 50 orders.
- `POST /api/v1/trading/orders/cancel-open`: cancel a user's open normal orders, optionally filtered by `symbol`, up to 1000 rows per call.
- `GET /api/v1/trading/orders/{orderId}`, `GET /api/v1/trading/orders/by-client-order-id`, and `GET /api/v1/trading/orders/open`: query orders.

Cancel:

```bash
curl -X POST 'http://localhost:9084/api/v1/trading/orders/cancel' \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: trace-demo-cancel-1001' \
  -d '{"userId":1001,"orderId":1}'
```

Query:

```bash
curl 'http://localhost:9084/api/v1/trading/orders/1'
curl 'http://localhost:9084/api/v1/trading/orders/by-client-order-id?userId=1001&clientOrderId=cli-1001-1'
curl 'http://localhost:9084/api/v1/trading/orders/open?userId=1001&symbol=BTC-USDT&limit=100'
```

## Database

Root [init.sql](../init.sql) creates:

- `trading_sequences` (legacy low-frequency counter compatibility; high-frequency trading ids use native sequences)
- `trading_order_seq`, `trading_event_seq`, `trading_command_seq`, `trading_outbox_seq`
- `trading_orders`
- `trading_order_events`
- `trading_trigger_orders`
- `trading_outbox_events`
- `account_margin_reservations`
- `account_position_margins`
- `trading_matching_assets`
- `trading_matching_symbols`
- `trading_match_results`
- `trading_match_trades`

Core indexes:

- `trading_orders_user_client_order_uidx`
- `trading_orders_open_query_idx`
- `trading_orders_stp_open_idx`
- `trading_orders_recovery_idx`
- `trading_trigger_orders_user_client_uidx`
- `trading_trigger_orders_user_oco_idx`
- `trading_trigger_orders_user_status_idx`
- `trading_trigger_orders_symbol_gte_idx`
- `trading_trigger_orders_symbol_lte_idx`
- `trading_trigger_orders_expiry_idx`
- `trading_trigger_orders_triggering_idx`
- `trading_order_events_order_idx`
- `trading_order_events_trace_idx`
- `trading_outbox_pending_idx`
- `trading_match_results_order_idx`
- `trading_match_results_success_place_idx`
- `trading_match_results_trace_idx`
- `trading_match_trades_symbol_time_idx`
- `trading_match_trades_trace_idx`

## Local Run

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-instrument-provider -am spring-boot:run
mvn -pl :surprising-trading-entry-provider -am spring-boot:run
JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" \
mvn -pl :surprising-matching-provider -am spring-boot:run
```

Port:

- `9084`: trading-entry combined service for order entry and trigger orders.
- `9085`: matching service.
- `9095`: trigger service in split mode.

## Production Notes

- `surprising-trading-entry-provider` is the default deployable entry process for order and trigger traffic.
- `surprising-order-provider` and `surprising-trigger-provider` remain available for split deployment. Both can run multiple instances, sharing the same PostgreSQL and Kafka clusters. Scale trigger by Kafka partitions and database claim batches; do not create one worker per symbol.
- Keep `surprising-matching-provider` independent from trading-entry; it owns exchange-core order books and should be scaled/restarted separately.
- Run the matching provider on JDK 21. Chronicle/OpenHFT dependencies used by exchange-core require explicit Java module opens/exports; set the same `JAVA_TOOL_OPTIONS` shown in local run for production pods.
- A new symbol must first be enabled in instrument, Kafka partitions must be checked, and only then should order entry be opened.
- MARKET orders require fresh mark price at order entry and matching. Order entry enforces min/max notional with the configured mark-derived execution band before publishing a matching command; linear max-notional and initial-margin checks use the upper bound to avoid under-collateralized market SELL opens. Tune `surprising.trading.*.market-max-slippage-ppm` per product liquidity.
- LIMIT order price-band protection is enabled in the default application configuration with `limit-price-band-ppm: 50000` (5%). Tune this per instrument liquidity before exposing high-frequency users or market-maker quoting.
- Matching open-order recovery is implemented from PostgreSQL; native exchange-core journal/snapshot persistence is still optional future hardening for faster very-large-book recovery.
- Instrument `max_notional_units` is enforced for both limit orders and protected-price market orders. Keep product notional limits conservative until real venue depth, latency, and liquidation stress tests prove larger limits safe.
- Order margin admission also enforces projected position exposure. It uses current position plus same-side open non-reduce-only orders plus the new order to check `max_position_notional_units`, the dynamic platform-OI cap, and the selected `instrument_risk_brackets.notional_cap_units`; pure reducing orders are checked against the reduced projection, not current exposure plus order notional.
- Dynamic per-user position caps are implemented. Account settlement maintains `trading_symbol_open_interest` with long, short, and open quantities; `open_quantity_steps=max(long_quantity_steps, short_quantity_steps)`. Order entry converts current platform OI to notional at the admission price and uses `min(max_position_notional_units, max(openInterestNotional * user_open_interest_limit_rate_ppm / 1_000_000, user_open_interest_limit_floor_units))` as the effective user cap. BTC/ETH default to 30% platform OI with a 250,000 USDT floor. Production should periodically rebuild-check this table from `account_positions`, especially after manual data repair or disaster recovery.
- User-initiated close orders should use `reduceOnly=true`; liquidation orders are generated by the liquidation provider and bypass user order-entry validation after a risk re-check.
- TP/SL trigger execution always submits reduce-only close orders through order-provider. WebSocket clients will see the generated order id and fills through the normal private order/match/position channels after trigger execution.
- Outbox delivery is at least once; downstream matching and push consumers must be idempotent.
- Matching results are idempotent by `commandId`; trades are idempotent by `tradeId`.
- A matching process that gets a new Kafka partition after processing commands will exit and restart to rebuild a fresh exchange-core book from DB. Treat this as expected failover behavior.
- Do not create one thread per symbol in order entry. Symbol scaling should be handled by Kafka partitions and matching-shard scheduling.

## Verification

```bash
mvn -pl :surprising-order-provider -am test
mvn -pl :surprising-trading-entry-provider -am test
mvn -pl :surprising-matching-provider -am test
mvn -pl :surprising-trigger-provider -am test
rg -n "BigDecimal" surprising-trading -g '*.java'
```
