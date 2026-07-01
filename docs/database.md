# Database Design

Initialize a new PostgreSQL database from repository root:

```bash
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

The local `docker-compose.yml` also mounts `init.sql` into PostgreSQL's init directory. It runs
automatically only when the named PostgreSQL volume is created for the first time.

## `instruments`

One row stores one version of one tradable product:

- `symbol`: normalized market symbol, for example `BTC-USDT`.
- `version`: immutable config version.
- `instrument_type` / `contract_type`: product category, currently perpetual contracts.
- `base_asset` / `quote_asset` / `settle_asset`: display, pricing, and settlement assets.
- `price_tick_units` / `quantity_step_units`: integer tick and step sizes in quote/base asset smallest units.
- `min_quantity_steps` / `max_quantity_steps` / `min_notional_units` / `max_notional_units`: order bounds in long units.
- `notional_multiplier_units`: for `LINEAR_PERPETUAL`, settlement units per `priceTick * quantityStep`; for `INVERSE_PERPETUAL`, quote face-value units per contract step.
- `supported_order_types` / `supported_time_in_force`: order-entry constraints.
- `max_leverage_ppm`, margin-rate ppm fields, `maker_fee_rate_ppm`, `taker_fee_rate_ppm`, funding ppm fields, and `impact_notional_units`: risk, trading-fee, and funding inputs.
- `status`: `PRE_TRADING`, `TRADING`, `HALT`, `SETTLING`, or `CLOSED`.

Primary key:

```sql
PRIMARY KEY (symbol, version)
```

## `instrument_current_versions`

One row points each symbol to its current config version:

```sql
PRIMARY KEY (symbol)
```

Downstream services should use this table to read the current snapshot. Historical versions are kept
in `instruments`.

## `instrument_symbol_sequences`

One row atomically allocates the next immutable instrument config version per symbol:

```sql
PRIMARY KEY (symbol)
```

Admin writes use this table instead of `MAX(version) + 1`, so multiple instrument provider nodes can
handle concurrent changes without assigning the same version.

## `instrument_risk_brackets`

Risk limit brackets for a specific `symbol + version`. Matching and risk services use these to
resolve max leverage and margin rates by position notional.

## `instrument_index_sources`

External spot-source configuration for index price calculation:

- REST endpoint and parser.
- WebSocket endpoint and subscription payload.
- quote currency and target quote currency.
- USD/USDT conversion source and conversion direction.
- source `weight_ppm`, `fallback_weight_multiplier_ppm`, and enabled flag.

The index price provider reads this table dynamically.

## `candlestick_candles`

One row represents one OHLCV candle:

- `symbol`: normalized market symbol, for example `BTC-USDT`.
- `period`: interval code, for example `1m`, `5m`, `1h`, `1d`.
- `open_time`: inclusive UTC bucket start.
- `close_time`: exclusive UTC bucket end.
- `open_price`: price of earliest trade in the bucket.
- `high_price`: max trade price in the bucket.
- `low_price`: min trade price in the bucket.
- `close_price`: price of latest trade in the bucket.
- `base_volume`: sum of trade quantity.
- `quote_volume`: sum of `price * quantity`.
- `trade_count`: accepted trade count after dedupe.
- `first_trade_id` / `last_trade_id`: audit fields for open/close.
- `first_sequence` / `last_sequence`: audit fields for replay checks.
- `source_partition` / `source_offset`: last Kafka record that changed this candle.

Primary key:

```sql
PRIMARY KEY (symbol, period, open_time)
```

This supports fast range queries and idempotent full-snapshot upserts.

## Partitioning Later

For very large production history, keep the logical schema and partition by time:

```sql
CREATE TABLE candlestick_candles_y2026m06
PARTITION OF candlestick_candles
FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
```

The service code does not need dynamic table names when PostgreSQL native partitioning is used.

## `price_index_ticks`

One row represents one calculated index price snapshot:

- `symbol`: contract symbol, for example `BTC-USDT`.
- `sequence`: database-allocated monotonic sequence per symbol.
- `index_price`: weighted fair spot index price.
- `status`: `HEALTHY`, `DEGRADED`, `STALE`, `INSUFFICIENT_SOURCES`, or `CLAMPED`.
- `component_count`: configured source count.
- `valid_component_count`: source count after stale/outlier filtering.
- `total_configured_weight`: original source weight sum.
- `event_time`: calculation time.

Primary key:

```sql
PRIMARY KEY (symbol, sequence)
```

Query index:

```sql
CREATE INDEX price_index_ticks_query_idx ON price_index_ticks (symbol, event_time DESC);
```

## `price_index_components`

This table stores one audit row per external source per index calculation. It records source price,
configured weight, effective weight, status, source timestamp, latency, and reject reason.

Primary key:

```sql
PRIMARY KEY (symbol, sequence, source)
```

## `price_symbol_leases`

This table coordinates active-active price providers:

- `module`: publisher namespace, for example `price-index` or `price-mark`.
- `symbol`: market symbol.
- `owner_id`: current publishing node.
- `lease_until`: when another node may take over if this node stops renewing.
- `updated_at`: local lease update time.

Primary key:

```sql
PRIMARY KEY (module, symbol)
```

## `price_symbol_sequences`

This table allocates global monotonic sequences for active-active price providers:

- `module`: publisher namespace.
- `symbol`: market symbol.
- `sequence`: latest allocated sequence.
- `updated_at`: latest allocation time.

Primary key:

```sql
PRIMARY KEY (module, symbol)
```

Sequence gaps are acceptable after failures, but a sequence must never move backwards for the same
`module + symbol`.

## `price_exchange_rates`

One row stores the latest known FX or stable-coin bridge rate:

- `base_currency`: source currency, for example `USD` or `USDT`.
- `quote_currency`: target currency, for example `CNY`.
- `rate`: multiplier from base to quote.
- `provider`: FX vendor or stable-coin ticker source.
- `rate_time`: timestamp supplied by the rate source.
- `updated_at`: local refresh timestamp.

Primary key:

```sql
PRIMARY KEY (base_currency, quote_currency)
```

The index provider stores both direct and inverse rates, so display services can convert routes such
as `USDT -> USD -> CNY` without calling third-party FX APIs per user request.

## `price_mark_ticks`

One row represents one calculated mark price snapshot:

- `sequence`: database-allocated monotonic sequence per symbol.
- `mark_price`: final mark price after median and clamp.
- `mark_price_units`: final mark price converted once into quote-asset smallest units. Core modules convert this long
  value to version-specific ticks with `instrument.price_tick_units`.
- `index_price`: latest index input.
- `price1`: funding convergence price.
- `price2`: index plus moving-average basis.
- `last_trade_price`: latest perpetual trade price.
- `best_bid_price` / `best_ask_price`: latest perpetual book top.
- `basis_average`: moving average of `(bid1 + ask1) / 2 - indexPrice`.
- `clamp_low` / `clamp_high`: final protection band around index price.

Primary key:

```sql
PRIMARY KEY (symbol, sequence)
```

## Funding Tables

`funding_rate_ticks` stores predicted funding rates using long ppm values:

- `funding_rate_ppm`: final clamped funding rate in parts per million.
- `premium_rate_ppm`: `(markPrice - indexPrice) / indexPrice * 1_000_000`.
- `interest_rate_ppm`: instrument interest rate converted to ppm.
- `funding_time`: UTC interval boundary when the rate should settle.

`funding_settlements` stores one settlement batch per `symbol + funding_time`.
The unique index on `(symbol, funding_time)` prevents duplicate settlement across multiple funding-provider nodes.

`funding_payments` stores each user payment for a settlement:

- Positive `amount_units`: user receives funding.
- Negative `amount_units`: user pays funding.
- `notional_units`: position notional in settlement-asset smallest units.

The funding provider writes `account_ledger_entries` with `reference_type = FUNDING` and applies
payments to `account_balances` / `account_deficits` in the same transaction.

## Risk Tables

`risk_account_snapshots` stores account-level margin state by `snapshot_id`, including wallet balance,
unrealized PnL, equity, maintenance margin, margin ratio, and status.

`risk_position_snapshots` stores the position-level inputs used for each account snapshot:

- `signed_quantity_steps`: long exposure from `account_positions`.
- `mark_price_ticks`: latest usable mark price converted from `price_mark_ticks.mark_price_units` to
  exchange-core ticks with the position's pinned `instrument_version`.
- `notional_units`, `unrealized_pnl_units`, and `maintenance_margin_units`: long settlement-asset units.

When an account position event closes a symbol completely, risk-provider writes a zero-quantity position snapshot for
that symbol. In that flat snapshot, `entry_price_ticks` and `mark_price_ticks` are `0`, and the account snapshot carries
zero unrealized PnL and zero maintenance margin. This prevents `latestPositions` from showing stale nonzero exposure
after a full close.

Risk, funding, liquidation, and ADL read instrument parameters and mark quote units from PostgreSQL, convert them to
version-specific mark ticks, then
calculate contract notional/PnL/margin amounts through shared Java `PerpetualContractMath` long
formulas with exact integer intermediates.

`risk_scan_leases` coordinates active-active risk providers by `user_id + settle_asset`:

- `owner_id`: current scanner node for the account asset group.
- `lease_until`: when another node may take over if the owner stops renewing.
- `updated_at`: local lease update time.

Primary key:

```sql
PRIMARY KEY (user_id, settle_asset)
```

The expiry index keeps stale-owner cleanup and operational inspection cheap:

```sql
CREATE INDEX risk_scan_leases_expiry_idx
    ON risk_scan_leases (lease_until);
```

`risk_liquidation_candidates` stores liquidation inputs. A candidate is not execution proof; the
liquidation provider must re-check latest risk before submitting a reduce-only close order.

Core indexes:

```sql
CREATE UNIQUE INDEX risk_liquidation_candidates_snapshot_uidx
    ON risk_liquidation_candidates (snapshot_id, user_id, symbol);

CREATE UNIQUE INDEX risk_liquidation_candidates_active_uidx
    ON risk_liquidation_candidates (user_id, symbol)
    WHERE status IN ('NEW', 'PROCESSING');

CREATE INDEX risk_liquidation_candidates_status_idx
    ON risk_liquidation_candidates (status, event_time ASC);
```

`risk_scan_leases` prevents live nodes from concurrently writing snapshots for the same `user_id + settle_asset`.
The active candidate unique index is the second guard: it prevents duplicate live liquidation
candidates for the same account position even after lease failover or replay. Once the prior
candidate is `COMPLETED` or `CANCELED`, a later scan may create the next staged liquidation candidate if the account is still unsafe.
Risk insertion should target only the partial active-candidate index for `DO NOTHING`; candidate-id
or snapshot uniqueness conflicts are data-integrity issues and must fail rather than being treated
as normal duplicate scans.

## Insurance Tables

`insurance_fund_balances` stores the current insurance fund balance per settlement asset.
Amounts are long asset units and are never negative.

`insurance_fund_ledger` stores immutable fund balance changes:

- Positive `amount_units`: fund deposit or operational top-up.
- Negative `amount_units`: fund withdrawal or account deficit coverage.
- `(reference_type, reference_id, asset)` is unique for idempotency.

`insurance_deficit_coverages` stores actual coverage attempts that paid a positive amount from the
fund. If the fund has no balance, the deficit remains in `account_deficits` and no empty coverage row
is written.

Insurance coverage writes `account_ledger_entries` with `reference_type = INSURANCE_COVERAGE`.
Coverage reduces explicit deficit and does not credit available balance.

Core indexes:

```sql
CREATE UNIQUE INDEX insurance_fund_ledger_reference_uidx
    ON insurance_fund_ledger (reference_type, reference_id, asset);

CREATE INDEX insurance_coverages_user_time_idx
    ON insurance_deficit_coverages (user_id, created_at DESC);
```

## ADL Tables

`adl_events` stores auto-deleveraging executions after the insurance fund is depleted:

- `deficit_user_id`: user whose `account_deficits` row is being covered.
- `target_user_id`: profitable account whose position is reduced.
- `closed_quantity_steps`: reduced position size.
- `realized_profit_units`: target-side profit realized by the ADL close.
- `covered_units`: amount transferred to cover the deficit.
- `remaining_deficit_units`: deficit left after this ADL event.
- `priority_score_ppm`: long ADL queue score derived from profit rate and effective leverage.

ADL writes these account ledger reference types:

- `ADL_REALIZED_PNL`: target account realizes PnL from the reduced position.
- `ADL_TRANSFER`: target account transfers part of realized PnL to cover deficit.
- `ADL_COVERAGE`: deficit account receives coverage by reducing `account_deficits`.

Core indexes:

```sql
CREATE INDEX adl_events_deficit_user_time_idx
    ON adl_events (deficit_user_id, created_at DESC);

CREATE INDEX adl_events_asset_symbol_time_idx
    ON adl_events (asset, symbol, created_at DESC);
```

## Trading And Account Margin Tables

`trading_orders` stores the accepted/rejected order state using long ticks and steps:

- `instrument_version`: instrument snapshot used by the accepted order. Rejected unknown-symbol orders may have no version.
- `price_ticks`: exchange-core price ticks.
- `quantity_steps`, `executed_quantity_steps`, `remaining_quantity_steps`: exchange-core size steps.
- `maker_fee_rate_ppm` and `taker_fee_rate_ppm`: the order-admission fee snapshot. Account settlement
  uses these order fields instead of the current instrument or user tier so old resting orders are not
  reinterpreted after a VIP, rebate, or promotion change.
- `reduce_only` and `post_only`: execution flags.
- `(user_id, client_order_id)` is unique when `client_order_id` is present.
- `trading_orders_stp_open_idx` supports self-trade prevention checks by user, symbol, side, and price.
- `trading_orders_recovery_idx` supports startup order-book recovery by scanning open `LIMIT` + `GTC/GTX` orders in maker-priority order.

`trading_fee_schedules` stores user-level fee overrides:

- `symbol IS NULL` is a user-global fee tier; a concrete `symbol` is a per-symbol override.
- `source_type` records why the override exists: `USER_OVERRIDE`, `VIP`, `MARKET_MAKER`, `PROMOTION`, or `RISK_OVERRIDE`.
  `tier_code` stores the external VIP/market-maker/program code used by admin systems.
- Resolution order is per-symbol user fee, then user-global fee, then the instrument default.
- `effective_time` / `expire_time`, `status`, and descending indexes allow deterministic historical
  activation. The resolved ppm is copied into `trading_orders` before a command is published.

`trading_leverage_settings` stores the user's target leverage by `user_id + symbol + margin_mode`:

- `leverage_ppm`: leverage in ppm, so `10_000_000` means `10x` and `100_000_000` means `100x`.
- Order entry validates the setting against `instruments.max_leverage_ppm` when it is saved, then re-checks
  it against the matching `instrument_risk_brackets.max_leverage_ppm` for the current order/position notional
  before reserving margin.
- If no user setting exists, order entry uses the selected risk bracket's maximum leverage, which is equivalent
  to reserving at that bracket's `initial_margin_rate_ppm`.
- The effective initial-margin rate is `max(leverage-derived rate, risk-bracket initial margin rate)`.

`trading_match_results` stores one idempotent result per matching command:

- `command_id` is the idempotency key.
- `instrument_version` is the taker command/order version.
- `trace_id` is copied from the order command and links the result to the originating REST request.
- `trading_match_results_success_place_idx` lets the recovery query confirm that an open order had a successful `PLACE` result before restoring it into exchange-core.

`trading_match_trades` stores `taker_instrument_version` and `maker_instrument_version`. Account
settlement uses those side-specific versions because maker orders can be older than the taker command.
`trace_id` is copied from the taker command that produced the trade and is forwarded to account position
events.

`trading_order_events` stores `trace_id` from the HTTP order or cancel request. Use it with
`trading_match_results.trace_id`, `trading_match_trades.trace_id`, Kafka topic/partition/offset, and
the relevant ids (`order_id`, `command_id`, `trade_id`) when tracing a user request across the trading
chain.
The `trading_order_events_trace_idx`, `trading_match_results_trace_idx`, and
`trading_match_trades_trace_idx` partial indexes support incident-time lookups for non-null trace ids.
The same side-specific versions are used for contract math, while maker/taker fee ppm comes from each
side's `trading_orders` fee snapshot.

`account_margin_reservations` tracks initial margin reserved by order entry:

- `margin_mode`: order margin mode. `CROSS` and `ISOLATED` are executable. The value is copied from
  order entry to matching, account settlement, risk, funding, and liquidation so collateral, reduce-only
  checks, and forced closes stay scoped to the same margin bucket.
- `reserved_units`: total order margin moved from `available_units` to `locked_units`.
- `released_units`: order margin returned to `available_units` after rejection, cancel, terminal immediate order, or close-only fill.
- `position_margin_units`: order margin already moved into position collateral after an opening fill.
- `released_units + position_margin_units <= reserved_units` prevents double release or double consumption.
- `order_id` references `trading_orders(order_id)`, so a reservation cannot exist without an order row.
- Order entry inserts the order before reserving margin; a duplicate `clientOrderId` therefore returns the existing order without locking funds again. The insert conflict target is only the partial `(user_id, client_order_id)` index, so `order_id` or unrelated uniqueness conflicts fail.

`account_position_margins` tracks current position collateral by `user_id + symbol + asset + margin_mode`.
Opening fills increase this table by consuming order reservation. Closing fills release the remaining
collateral proportionally back to `account_balances.available_units`.
Opening fills are required to consume an existing non-reduce-only order reservation. Missing
reservation rows or skipped margin migration updates fail the account trade transaction instead
of creating an uncollateralized position.

`account_deficits` tracks bankruptcy deficits without allowing negative `account_balances` columns.
Realized profits first clear deficits, then increase `available_units`. Cross-margin realized losses
and fees reduce `available_units`, then the portion of cross `locked_units` backed by
`account_position_margins`, and any remainder increases `deficit_units`. Isolated-margin losses and
fees do not debit cross available balance; they consume only the exact `user_id + symbol + asset +
ISOLATED` position collateral, then record any remainder as deficit. Order-reservation locked funds
are not eligible for PnL, trading-fee, or funding-fee loss debits. When position-backed locked
collateral is consumed, the matching `account_position_margins` rows are reduced under `FOR UPDATE`
so later position-margin releases cannot over-credit available balance.

`account_positions` stores net perpetual exposure:

- `margin_mode`: `CROSS` or `ISOLATED`. Positions are netted by user, symbol, and margin mode. The
  current project does not yet add hedge-mode `positionSide`; long and short exposure still collapse
  into one net position per margin bucket.
- `instrument_version`: contract version for the current non-zero exposure. It is `NULL` when the position is flat.
- `signed_quantity_steps`: positive for long, negative for short.
- `entry_price_ticks`: average entry in exchange-core ticks.
- `realized_pnl_units`: realized PnL accumulator in the instrument settlement asset. Closing fills are
  written to `account_ledger_entries` as `TRADE_PNL`.
- Maker/taker fee debits or rebates are written to `account_ledger_entries` as `TRADE_FEE`.
  `trade_id`, `order_id`, `symbol`, and `fee_rate_ppm` are stored with the ledger row for audit and
  reconciliation.

The account provider deduplicates `trading_match_trades` with `account_processed_trades(symbol, trade_id)`.
All balance and margin transitions run inside one PostgreSQL transaction and lock the affected
position/margin rows with `FOR UPDATE`. Position, balance, deficit, ledger, reservation, and
position-margin writes are fail-fast when an expected row is not written.
For `TRADE_PNL` and `TRADE_FEE`, both the ledger insert and the balance-after backfill must touch one row; trade
deduplication is handled by `account_processed_trades(symbol, trade_id)`, not by skipping trade ledger
conflicts.

`account_outbox_events` stores account-side Kafka events written inside the same transaction as the
account state change. It currently carries `POSITION_UPDATED` events for WebSocket private position
pushes:

- `id`: database-allocated outbox id.
- `topic`: Kafka destination, for example `surprising.account.position.events.v1`.
- `event_key`: Kafka key, currently the normalized symbol.
- `payload`: JSONB event body.
- `attempts`, `next_attempt_at`, `published_at`, and `last_error`: retry and publish state.
- `POSITION_UPDATED` payloads carry the original match-trade `traceId`, so WebSocket/private-account
  pushes can be correlated with the order and matching rows.

Indexes:

```sql
CREATE INDEX account_outbox_pending_idx
    ON account_outbox_events (next_attempt_at, id)
    WHERE published_at IS NULL;

CREATE INDEX account_outbox_aggregate_idx
    ON account_outbox_events (aggregate_type, aggregate_id);
```

The publisher claims pending rows with `FOR UPDATE SKIP LOCKED`, so multiple account-provider nodes
can drain the outbox safely. Publishing is at-least-once; consumers should deduplicate by event id,
trade id, or their own latest-position version rules.

## Liquidation Tables

`liquidation_orders` audits each liquidation candidate outcome:

- `SUBMITTED` rows must have positive `quantity_steps`.
- `CANCELED` rows may have `quantity_steps = 0` when the account has recovered or the position is gone.
- The provider locks live `account_positions`, locks existing same-side reduce-only `trading_orders`, writes cancel-request events/commands for them, and then sizes the staged liquidation order from `abs(livePosition)`.
- Existing user reduce-only orders must not reduce liquidation capacity; otherwise a far-away GTC close order could block forced liquidation.
- Strong liquidation order creation does not use broad conflict suppression on `trading_orders`; uniqueness violations roll the transaction back.
- Sizing first attempts risk-bracket reduction, then configured partial close ratios, and only fully closes when the margin ratio is above the full-close threshold.
