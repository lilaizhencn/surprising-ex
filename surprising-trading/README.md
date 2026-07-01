# surprising-trading

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange perpetual trading module. The current implementation contains `surprising-order-provider` and `surprising-matching-provider`: order entry, instrument-rule validation, idempotent persistence, Kafka matching-command publishing, real exchange-core order-book matching, matching results, and trade events.

## Modules

- `surprising-trading-api`: order RPC contracts, DTOs, and Kafka command/event models.
- `surprising-order-provider`: order entry provider.
- `surprising-matching-provider`: `exchange-core` based matching provider.

## Long Fixed-Point Model

Order entry does not use `BigDecimal`. API, database, and Kafka commands use long values:

- `priceTicks`: number of price ticks. Display price = `priceTicks * price_tick_units / quote_asset_scale`.
- `quantitySteps`: number of quantity steps. Display quantity = `quantitySteps * quantity_step_units / base_asset_scale`.
- `MARKET` orders must use `priceTicks = 0`.
- `MARKET` orders only allow `IOC` or `FOK`.
- Notional validation uses `contract_type`. Linear contracts check `priceTicks * quantitySteps * notional_multiplier_units`; inverse contracts check `quantitySteps * notional_multiplier_units` because the multiplier is the quote face value per contract step. Both paths use `Math.multiplyExact` to reject long overflow.
- Market orders are protected by `markPriceTicks +/- marketMaxSlippagePpm` before exchange-core IOC/FOK submission. Order entry uses the same mark-derived execution band for risk checks: linear contracts reserve initial margin at the upper bound for both BUY and SELL, while inverse contracts reserve at the lower bound because collateral requirement increases as price falls.
- Instrument versions store default maker/taker ppm fees. Order entry resolves user/VIP/market-maker overrides, snapshots the final rates on `trading_orders`, and account settlement charges fills from that snapshot.

Example for `BTC-USDT` with `price_tick_units = 10000000`, `quantity_step_units = 100000`,
USDT scale `100000000`, and BTC scale `100000000`:

- `priceTicks = 650000` means price `65000.0`.
- `quantitySteps = 10` means quantity `0.01 BTC`.

This aligns with the long-based input model used by `exchange-core`, so the matching provider passes `priceTicks` and `quantitySteps` directly to the order book.
Keep this invariant for the trading path: do not convert order, fill, margin, PnL, or funding-settlement values to `BigDecimal` in Java. External market-data modules may still store decimal display values, but trading execution and accounting must use scaled `long`.
Decimal values are only allowed at system boundaries: external market-data/FX parsing, admin input, REST display, and reports. Before a value enters order entry, matching, account, risk, liquidation, funding, insurance, or ADL, it must already be converted to instrument-defined ticks, steps, ppm, or asset units.
`CoreFixedPointArchitectureTest` scans the trading, account, risk, liquidation, funding, insurance, and ADL main Java sources and fails the build if `BigDecimal`, `double`, or `float` is introduced into those core paths.
Critical core-path aggregations use checked long addition. Matching filled quantity and reduce-only pending close quantity fail with overflow instead of wrapping into a smaller value.

## Core Flow

```text
client / internal gateway
  -> POST /api/v1/trading/orders
  -> surprising-order-provider
  -> PostgreSQL trading_orders + trading_order_events + trading_outbox_events
  -> outbox publisher
  -> surprising.perp.order.commands.v1
  -> surprising-matching-provider / exchange-core
  -> trading_match_results / trading_match_trades
  -> surprising.perp.match.results.v1 / surprising.perp.match.trades.v1
```

The order provider does not match orders and does not own WebSocket fanout. Order-state push should be a separate service consuming `surprising.perp.order.events.v1` and `surprising.perp.match.results.v1`.

## Margin Mode

Orders, matching commands, match trades, account reservations, and account positions now carry `marginMode`.
The default is `CROSS`. `ISOLATED` is wired through order entry, matching events, account margin, positions,
risk snapshots, funding, and liquidation. Cross-margin losses, trading fees, and funding payments can use cross
available balance plus cross position margin as collateral. Isolated margin consumes only the exact
`userId + symbol + asset + marginMode` position margin and does not touch other symbols or cross balance.
Manual isolated margin add/remove, margin-mode switching constraints, and hedge-mode `positionSide` are not implemented
yet, so clients should present one-way net positions.

## Trading Fees

- `init.sql` defaults `BTC-USDT` and `ETH-USDT` to maker `200 ppm` and taker `500 ppm`, or `0.02% / 0.05%`.
- `trading_fee_schedules` can configure user-global or per-symbol overrides. `source_type` supports `USER_OVERRIDE`, `VIP`, `MARKET_MAKER`, `PROMOTION`, and `RISK_OVERRIDE`. Per-symbol user fees win over user-global fees, then the instrument default is used.
- Admin APIs: `POST /api/v1/admin/trading/fees/schedules` creates or updates schedules, `POST /api/v1/admin/trading/fees/schedules/{feeScheduleId}/disable` disables a schedule, and `GET /api/v1/admin/trading/fees/schedules` lists schedules.
- Runtime query: `GET /api/v1/trading/fees/effective?userId=...&symbol=...` returns the current maker/taker ppm and source, such as `INSTRUMENT` or `VIP_SYMBOL`.
- Order admission writes the final `maker_fee_rate_ppm` and `taker_fee_rate_ppm` to `trading_orders`. Later VIP or promotion changes do not reinterpret already accepted resting orders.
- The account provider settles fills from the order snapshot and writes `TRADE_FEE` ledger rows with `trade_id`, `order_id`, `symbol`, and `fee_rate_ppm`.

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
- `maker_fee_rate_ppm` and `taker_fee_rate_ppm` are not passed into exchange-core. Instrument provides the default, `trading_fee_schedules` can override by user globally or by symbol, and order admission freezes the final rates on `trading_orders`.

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
- `trading_sequences` atomically allocates `orderId`, `eventId`, `commandId`, and `outboxId` with PostgreSQL `INSERT ... ON CONFLICT ... RETURNING`.
- `trading_outbox_events` is committed in the same transaction as the order.
- `trading_order_events` and `trading_outbox_events` inserts must affect exactly one row; otherwise the transaction fails to avoid order/message divergence.
- The outbox publisher uses `FOR UPDATE SKIP LOCKED`, so multiple order-provider nodes can publish concurrently without locking the same rows.
- Failed outbox rows retry with exponential backoff through `next_attempt_at`, avoiding hot loops during Kafka incidents.
- Kafka producer uses `acks=all` and `enable.idempotence=true`.
- Downstream consumers must deduplicate commands by `commandId/orderId` and events by `eventId`.

## Kafka

- `surprising.perp.order.commands.v1`: matching commands, key = `symbol`.
- `surprising.perp.order.events.v1`: order entry events, key = `symbol`.
- `surprising.perp.match.results.v1`: matching results, key = `symbol`.
- `surprising.perp.match.trades.v1`: matching trades, key = `symbol`, with prices and quantities still represented as long ticks/steps.
- `surprising.perp.orderbook.depth.v1`: L2 order book depth updates, key = `symbol`.

Partitions should continue to scale by symbol. All commands for the same symbol must route to the same matching shard/order book.

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

The provider uses the real exchange-core order book and matcher event chain, but exchange-core risk processing and built-in fees are disabled. Order entry reserves initial margin before command publication; account-provider migrates filled opening margin into position margin and writes maker/taker `TRADE_FEE` ledger entries from the order fee snapshot. Funding, insurance, and ADL are handled by separate settlement modules.

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

`surprising.perp.order.commands.v1` must be keyed by `symbol`. Kafka assigns each partition to one consumer in the `surprising-matching-v1` group, so one live matcher owns a symbol partition at a time.

The matching consumer uses cooperative sticky assignment and `MatchingPartitionAssignmentGuard`:

- At process start, the node restores open books from DB before consuming commands.
- If a running matcher that has already processed commands receives a new partition, it closes the Spring context. Kubernetes/systemd should restart it, and the fresh process restores current books from DB before consuming.
- This avoids an unsafe case where a long-running process receives a symbol whose local exchange-core book is stale.
- If command processing fails after the payload has been decoded, the matcher also closes the Spring context. This avoids retrying a Kafka command against an exchange-core book that may already have been mutated before PostgreSQL/outbox persistence failed.
- This covers fail-fast write failures for match results, trades, order status updates, margin release, and matching outbox rows. Check sequences, unique-index conflicts, missing order rows, and transaction errors first.
- Keep `surprising.trading.matching.kafka.restart-on-partition-reassignment=true` in production.
- `surprising.trading.matching.kafka.partition-assignment-startup-grace-ms` defaults to `30000` so concurrent listener containers can finish their initial assignment before the guard starts treating new partitions as unsafe runtime movement.
- The command-failure restart is unconditional; the partition-reassignment flag only controls restart behavior for partition movement.

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

- `trading_sequences`
- `trading_orders`
- `trading_order_events`
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
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-instrument-provider -am spring-boot:run
mvn -pl :surprising-order-provider -am spring-boot:run
JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" \
mvn -pl :surprising-matching-provider -am spring-boot:run
```

Port:

- `9084`: order entry service.
- `9085`: matching service.

## Production Notes

- `surprising-order-provider` can run multiple instances, sharing the same PostgreSQL and Kafka clusters.
- Run the matching provider on JDK 21. Chronicle/OpenHFT dependencies used by exchange-core require explicit Java module opens/exports; set the same `JAVA_TOOL_OPTIONS` shown in local run for production pods.
- A new symbol must first be enabled in instrument, Kafka partitions must be checked, and only then should order entry be opened.
- MARKET orders require fresh mark price at order entry and matching. Order entry enforces min/max notional with the configured mark-derived execution band before publishing a matching command; linear max-notional and initial-margin checks use the upper bound to avoid under-collateralized market SELL opens. Tune `surprising.trading.*.market-max-slippage-ppm` per product liquidity.
- Matching open-order recovery is implemented from PostgreSQL; native exchange-core journal/snapshot persistence is still optional future hardening for faster very-large-book recovery.
- Instrument `max_notional_units` is enforced for both limit orders and protected-price market orders. Keep product notional limits conservative until real venue depth, latency, and liquidation stress tests prove larger limits safe.
- User-initiated close orders should use `reduceOnly=true`; liquidation orders are generated by the liquidation provider and bypass user order-entry validation after a risk re-check.
- Outbox delivery is at least once; downstream matching and push consumers must be idempotent.
- Matching results are idempotent by `commandId`; trades are idempotent by `tradeId`.
- A matching process that gets a new Kafka partition after processing commands will exit and restart to rebuild a fresh exchange-core book from DB. Treat this as expected failover behavior.
- Do not create one thread per symbol in order entry. Symbol scaling should be handled by Kafka partitions and matching-shard scheduling.

## Verification

```bash
mvn -pl :surprising-order-provider -am test
mvn -pl :surprising-matching-provider -am test
rg -n "BigDecimal" surprising-trading -g '*.java'
```
