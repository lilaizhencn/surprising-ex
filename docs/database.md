# Database Design

Initialize a new PostgreSQL database from repository root:

```bash
brew services start postgresql@18
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

Local integration tests use the Homebrew PostgreSQL instance on `localhost:5432`; see
[local-homebrew-infra.md](local-homebrew-infra.md) for the service and tuning commands.

## Admin gateway tables

`gateway_admin_export_jobs` stores approved CSV export jobs. `gateway_admin_query_tasks` stores
allowlisted long-running admin JSON query tasks, including status, query params, result JSON, row
count, byte size, error message, expiry time, archive time, and archive reason. Expired query-task
results can be cleared while retaining task metadata for audit and capacity tracking. Allowlisted
query types include system latency/backlog checks plus order, trigger-order, and match-trade audit
searches.
`gateway_admin_operation_logs.duration_ms` stores admin gateway proxy request duration for audit
exports and p50/p95/p99 system latency metrics.

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
- `user_open_interest_limit_rate_ppm` / `user_open_interest_limit_floor_units`: dynamic per-user position cap inputs. Order entry computes `max(platformOpenInterestNotional * rate / 1_000_000, floor)` and also applies `max_position_notional_units`.
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
CREATE INDEX price_index_ticks_event_time_brin ON price_index_ticks USING BRIN (event_time);
```

The index provider publishes one complete Kafka event first. A separate audit consumer batches that same
event into this table and `price_index_components`; both tables are retained for three days and are not
real-time price inputs.

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

One row represents one asynchronous mark-price audit snapshot. Real-time order, trigger, risk,
liquidation, funding, ADL, and settlement decisions must consume the Kafka mark-price event and must
not wait for or query this table.

- `sequence`: database-allocated monotonic sequence per symbol.
- `mark_price`: final mark price after median and clamp.
- `product_line` / `instrument_version`: the exact product and instrument encoding used.
- `mark_price_units` / `mark_price_ticks`: final fixed-point values published to real-time consumers.
- `index_price`: latest index input.
- `price1`: funding convergence price.
- `price2`: index plus moving-average basis.
- `last_trade_price`: latest perpetual trade price.
- `best_bid_price` / `best_ask_price`: latest perpetual book top.
- `basis_average`: moving average of `(bid1 + ask1) / 2 - indexPrice`.
- `clamp_low` / `clamp_high`: final protection band around index price.
- `event_time` / `published_at`: calculation time and Kafka publication time used for freshness checks.
- `calculation_inputs`: the complete mark-price Kafka JSON envelope, including the exact index component
  snapshots, book ticker, last trade, funding input, basis intermediate, and final result.

An independent consumer group reads the same mark-price topic in JDBC batches. Rows older than three
days are deleted every minute in bounded batches (up to 100,000 rows per default run). The retention
index is BRIN because inserts are time-ordered. No database write or cleanup operation is on the
real-time price-consumer path.

Primary key:

```sql
PRIMARY KEY (symbol, sequence)
```

## Funding Tables

`funding_rate_ticks` stores only `FINAL` funding rates frozen at a settlement boundary, using long ppm values. Per-second `PREDICTED` rates are Kafka events held in each consumer's latest-by-symbol cache and are not inserted here:

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
- `mark_price_ticks`: latest usable Kafka mark-price event ticks for the position's pinned
  `instrument_version`.
- `notional_units`, `unrealized_pnl_units`, and `maintenance_margin_units`: long settlement-asset units.

When an account position event closes a symbol completely, risk-provider writes a zero-quantity position snapshot for
that symbol. In that flat snapshot, `entry_price_ticks` and `mark_price_ticks` are `0`, and the account snapshot carries
zero unrealized PnL and zero maintenance margin. This prevents `latestPositions` from showing stale nonzero exposure
after a full close.

Risk, funding, liquidation, and ADL take one fresh immutable snapshot from their local Kafka mark-price
cache, require the event's exact instrument version, and combine it with PostgreSQL account/instrument
state. They calculate contract notional/PnL/margin amounts through shared Java `PerpetualContractMath`
long formulas with exact integer intermediates; none reads the mark-price audit table.

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
    ON risk_liquidation_candidates (snapshot_id, user_id, symbol, margin_mode);

CREATE UNIQUE INDEX risk_liquidation_candidates_active_uidx
    ON risk_liquidation_candidates (user_id, symbol, margin_mode)
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

`risk_admin_rule_overrides` stores admin-managed risk rule overrides:

- `GLOBAL_MARGIN_POLICY` persists warning and liquidation margin-ratio thresholds.
- `RISK_SCAN_CONTROL` persists scan enablement, scan delay, and scan batch size.
- `admin_user_id`, `reason`, and `updated_at` provide an audit trail for risk policy changes.
- The risk-provider applies a successful write to the current node's runtime configuration immediately; the row is the durable source for admin inspection and later startup/rollout automation.

## Insurance Tables

`insurance_fund_balances` stores the current insurance fund balance per settlement asset.
Amounts are long asset units and are never negative.

`insurance_fund_ledger` stores immutable fund balance changes:

- Positive `amount_units`: fund deposit or operational top-up.
- Positive `amount_units` with `reference_type = LIQUIDATION_FEE`: actual liquidation-fee income
  collected by account-provider and replay-protected by `reference_id = tradeId:orderId`.
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
- `target_position_side`: `NET`, `LONG`, or `SHORT`; records which position bucket was deleveraged.
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
  receives the executed-side rates through `MatchTradeEvent` instead of querying the current instrument,
  user tier, or order rows on the hot path, so old resting orders are not reinterpreted after a VIP,
  rebate, or promotion change.
- `reduce_only` and `post_only`: execution flags.
- `(user_id, client_order_id)` is unique when `client_order_id` is present.
- `trading_orders_stp_open_idx` supports self-trade prevention checks by user, symbol, side, and price.
- `trading_orders_recovery_idx` supports startup order-book recovery by scanning open `LIMIT` + `GTC/GTX` orders in maker-priority order.

`trading_cancel_all_after` stores the user dead-man switch timers used by `POST /trading/orders/cancel-all-after`:

- `(user_id, symbol_scope)` is the primary key. `symbol_scope='*'` means account-wide; otherwise it is a concrete symbol.
- `countdown_ms=0` with `DISABLED` turns the timer off. Positive countdowns set `ACTIVE` with `trigger_at`.
- The order provider claims due `ACTIVE` rows through `trading_cancel_all_after_due_idx`, moves them to
  `TRIGGERING`, cancels open ordinary orders through the same `cancel-open` path, calls trigger-provider
  `cancel-open` for pending TP/SL, then marks the timer `TRIGGERED`.
- `canceled_order_count` and `canceled_trigger_order_count` record how many cancellation requests were issued in
  the latest trigger execution; they are reset whenever the timer is refreshed or disabled.

`trading_algo_orders` stores parent TWAP/Iceberg instructions before their child orders enter the live order path:

- `client_algo_order_id` is scoped by `user_id` for idempotent placement.
- `algo_type` is `TWAP` or `ICEBERG`; `status` moves through `PENDING/RUNNING/CANCEL_REQUESTED` and terminal
  `CANCELED/COMPLETED/FAILED`.
- TWAP uses IOC child orders. If `price_ticks=0`, the child is a MARKET IOC order; otherwise it is a LIMIT IOC
  order at `price_ticks`.
- Iceberg requires `price_ticks > 0` and uses LIMIT `GTC` or `GTX` child orders. Only one visible child order is
  active at a time; after that child fills or is canceled, the scheduler places the next slice.
- `quantity_steps` is the total target and `child_quantity_steps` is the per-slice visible quantity. `interval_seconds`
  and `duration_seconds` bound TWAP scheduling validation.
- `current_order_id` points at the latest child `trading_orders` row. `trace_id` is forwarded to child orders so
  the parent request can be followed through matching, account settlement, risk, and WebSocket events.
- Active algo rows are included in margin-mode and position-mode switch blockers, so a user cannot switch mode while
  future child orders may still be emitted.

`trading_algo_order_children` links each parent slice to the ordinary order that actually enters matching:

- `(algo_order_id, slice_index)` is unique, and `order_id` references `trading_orders(order_id)`.
- `status` mirrors the child order status; the order-provider refreshes it from `trading_orders` before scheduling
  the next slice or returning parent progress.
- Progress is derived from child `executed_quantity_steps` plus active child remaining quantity; balances and
  positions are never updated directly from the algo tables.

Core algo indexes:

```sql
CREATE UNIQUE INDEX trading_algo_orders_user_client_uidx
    ON trading_algo_orders (user_id, client_algo_order_id)
    WHERE client_algo_order_id IS NOT NULL;

CREATE INDEX trading_algo_orders_due_idx
    ON trading_algo_orders (next_slice_at, algo_order_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX trading_algo_orders_user_status_idx
    ON trading_algo_orders (user_id, symbol, status, created_at DESC);

CREATE UNIQUE INDEX trading_algo_order_children_client_uidx
    ON trading_algo_order_children (client_order_id);
```

`trading_trigger_orders` stores take-profit, stop-loss, and trailing-stop conditional orders before they enter the live
order path:

- `client_trigger_order_id` is scoped by `user_id` for idempotent placement.
- `oco_group_id` is optional and scoped by `user_id + symbol + margin_mode`. Pending siblings in the same
  OCO group are canceled when one row is claimed for execution.
- `trigger_condition` is derived from close side and trigger type. Every trigger is evaluated from the latest
  mark price sampled once per second, so there is no configurable price-source field.
- `trigger_price_ticks`, `price_ticks`, and `quantity_steps` stay in the same long tick/step model as
  regular orders. Static TP/SL requires a positive `trigger_price_ticks`; trailing stop allows `0` and uses
  `activation_price_ticks`, `callback_rate_ppm`, `highest_price_ticks`, `lowest_price_ticks`, and `activated_at`
  to track activation and callback state.
- `status` moves through `PENDING -> TRIGGERING -> TRIGGERED` or `TRIGGER_FAILED`; user cancellation moves
  only `PENDING` rows to `CANCELED`, and expiry moves due rows to `EXPIRED`.
- `placed_order_id` links the generated reduce-only close order in `trading_orders`.
- `trigger_sequence` and `triggered_price_ticks` record which sampled mark-price event triggered the row.
- `trace_id` is forwarded to the generated order request so private WebSocket order/match/position pushes can
  be traced back to the original TP/SL placement.

Core trigger indexes:

```sql
CREATE UNIQUE INDEX trading_trigger_orders_user_client_uidx
    ON trading_trigger_orders (product_line, user_id, client_trigger_order_id)
    WHERE client_trigger_order_id IS NOT NULL;

CREATE INDEX trading_trigger_orders_user_oco_idx
    ON trading_trigger_orders (product_line, user_id, symbol, margin_mode, oco_group_id, status, updated_at DESC)
    WHERE oco_group_id IS NOT NULL;

CREATE INDEX trading_trigger_orders_symbol_gte_idx
    ON trading_trigger_orders (product_line, symbol, trigger_price_ticks, trigger_order_id)
    WHERE status = 'PENDING'
      AND trigger_type IN ('TAKE_PROFIT', 'STOP_LOSS')
      AND trigger_condition = 'GREATER_OR_EQUAL';

CREATE INDEX trading_trigger_orders_symbol_lte_idx
    ON trading_trigger_orders (product_line, symbol, trigger_price_ticks DESC, trigger_order_id)
    WHERE status = 'PENDING'
      AND trigger_type IN ('TAKE_PROFIT', 'STOP_LOSS')
      AND trigger_condition = 'LESS_OR_EQUAL';

CREATE INDEX trading_trigger_orders_trailing_pending_idx
    ON trading_trigger_orders (product_line, symbol, trigger_order_id)
    WHERE status = 'PENDING'
      AND trigger_type = 'TRAILING_STOP';
```

The trigger provider claims due rows with `FOR UPDATE SKIP LOCKED`, so active-active nodes can consume the
same mark-price stream without executing the same trigger twice. The claim statement partitions candidates by
OCO group, selects one pending row per group, sets that row to
`TRIGGERING`, and cancels pending siblings in the same statement. The expiry and `TRIGGERING` indexes support
scheduled expiry and stale execution retry.

Spring Data Redis with Lettuce always keeps static TP/SL ids
in product-line/symbol sorted sets. Redis performs one atomic Lua range lookup, but the returned ids
still pass through the same PostgreSQL exact predicate and row claim. The database remains authoritative for user
queries and all state transitions. Placement writes the candidate before DB insert and cleans it on rollback;
terminal DB changes remove the member afterward, so a process crash can only leave a harmless stale candidate.
The rebuild coordinator uses a token-owned Redis lease with compare-and-delete Lua release. It is an efficiency
lock only; trigger uniqueness depends on the PostgreSQL conditional update and `SKIP LOCKED`.

When account settlement makes a position quantity zero, it updates matching `PENDING` trigger rows to `CANCELED`
with `reject_reason = 'POSITION_CLOSED'` in the same transaction as `account_positions`. The position outbox event
is therefore published only after the authoritative cancellation commits. Trigger-provider uses the exact event
scope and timestamp to remove the canceled static TP/SL members from Redis; duplicate Kafka delivery is idempotent.

`trading_fee_schedules` stores user-level fee overrides:

- `symbol IS NULL` is a user-global fee tier; a concrete `symbol` is a per-symbol override.
- `source_type` records why the override exists: `USER_OVERRIDE`, `VIP`, `MARKET_MAKER`, `PROMOTION`, or `RISK_OVERRIDE`.
  `tier_code` stores the external VIP/market-maker/program code used by admin systems.
- Resolution order is per-symbol user fee, then user-global fee, then the instrument default. Within the same
  specificity, `RISK_OVERRIDE`, `USER_OVERRIDE`, `PROMOTION`, and `MARKET_MAKER` outrank automatic `VIP`.
- `effective_time` / `expire_time`, `status`, and descending indexes allow deterministic historical
  activation. The resolved ppm is copied into `trading_orders` before a command is published.

`trading_fee_tiers` stores automatic fee-tier rules:

- Thresholds use USD/USDT asset units, so `1000000000000000` means `10,000,000` with 8 quote decimals.
- `qualification_mode` controls whether 30-day filled notional, total account asset value, either, or both are required.
- The default seed tiers assign only `VIP` rates. Market-maker rebates should be configured only when a separate
  market-maker program measures quote uptime, spread, and depth quality.

`market_maker_strategy_leases` coordinates active-active market-maker providers:

- Primary key is `(strategy_id, symbol)`, so one strategy may quote different symbols on different nodes.
- `owner_id` should be the provider's stable node id in production.
- `lease_until` lets another node take over after a failed owner stops refreshing the row.
- The table does not store balances. The provider still reads instruments, order book, mark price, account positions,
  and open orders each cycle before placing normal post-only orders through order-provider.

`market_maker_strategy_overrides` stores the latest admin-approved hot override for a configured strategy:

- Only quote/risk parameters are hot-editable: enabled, base quantity, margin mode, spread, level spacing,
  inventory cap/skew, and quote levels. Account ids and symbols remain deployment-level config.
- Null fields mean "use the configured value from `application.yml`"; deleting the row fully resets the strategy to
  file config.
- `updated_by_admin_user_id`, `reason`, `updated_at`, and `version` record the current effective override metadata.
  Full operator audit and four-eyes approval are enforced in gateway admin operation logs and approval tables.

`market_maker_strategy_run_events` stores best-effort strategy execution events for admin operations:

- Events are keyed by strategy, symbol, account, node id, cycle sequence, event type, trace id, and creation time.
- Event types cover cycle success/failure, quote reconciliation, IOC trade submit/reject outcomes, and skipped cycles.
- The admin `/api/v1/admin/market-maker/strategy-logs` endpoint uses this table for run-log troubleshooting, while
  `/api/v1/admin/market-maker/pnl-attribution` derives financial attribution from configured market-maker scopes,
  market-maker `clientOrderId` prefixes, `trading_match_trades`, `account_ledger_entries` fee rows, and current
  `account_positions`.

`market_maker_reference_samples` stores one best-effort reference-market sample per strategy symbol cycle when an
external order book snapshot is available:

- `source_name` and `transport` identify whether the quote plan used REST fallback or a WebSocket-maintained local book.
- `bid_levels`, `ask_levels`, best bid/ask, mid, and spread ticks allow production tests to prove the maker was
  calibrated from Binance/OKX/Bybit-style depth rather than static local parameters.
- The table is observability only. Balances, positions, order reservations, and deficits still use the account/trading
  transactional tables as the source of truth.

`trading_user_fee_tiers` stores the current calculated fee tier per user:

- The refresher calculates 30-day maker+taker notional from `trading_match_trades`, valuing linear contracts by
  fill price and inverse contracts by face value.
- Account asset value counts USD/USDT balances 1:1 and values other assets from the latest active mark price for
  a matching `baseAsset-USDT/USD` instrument.
- The row owns one user-global `trading_fee_schedules` entry. Refresh updates that schedule only when the user's
  assigned tier or rates change; order rows that were already accepted keep their copied fee snapshot.

`trading_leverage_settings` stores the user's target leverage by `user_id + symbol + margin_mode`:

- `leverage_ppm`: leverage in ppm, so `10_000_000` means `10x` and `100_000_000` means `100x`.
- Order entry validates the setting against `instruments.max_leverage_ppm` when it is saved, then re-checks
  it against the matching `instrument_risk_brackets.max_leverage_ppm` for the current order/position notional
  before reserving margin.
- If no user setting exists, order entry uses the selected risk bracket's maximum leverage, which is equivalent
  to reserving at that bracket's `initial_margin_rate_ppm`.
- The effective initial-margin rate is `max(leverage-derived rate, risk-bracket initial margin rate)`.

`trading_symbol_open_interest` stores the current platform open interest state per symbol:

- Account settlement updates this table in the same transaction as `account_positions` after a new trade changes a position.
- `long_quantity_steps` and `short_quantity_steps` track the absolute live long and short quantities. Direction flips apply both a negative delta on the old side and a positive delta on the new side.
- `open_quantity_steps` is constrained to `GREATEST(long_quantity_steps, short_quantity_steps)`, which avoids double-counting both sides when order entry applies platform-OI-based user caps.
- Order entry reads `open_quantity_steps`, converts it to notional with the current admission price and instrument formula, then applies the instrument's `user_open_interest_limit_*` settings.
- Because this is derived state, production operations should periodically rebuild-check it from `account_positions`, especially after manual repairs, emergency imports, or disaster recovery.

`trading_match_results` stores one idempotent result per matching command:

- `command_id` is the idempotency key.
- `instrument_version` is the taker command/order version.
- `trace_id` is copied from the order command and links the result to the originating REST request.
- `trading_match_results_success_place_idx` lets the recovery query confirm that an open order had a successful `PLACE` result before restoring it into exchange-core.

`trading_match_trades` stores `taker_instrument_version`, `maker_instrument_version`,
`taker_fee_rate_ppm`, and `maker_fee_rate_ppm`. Account settlement uses those side-specific versions
and required fee rates because maker orders can be older than the taker command. `trace_id` is copied
from the taker command that produced the trade and is forwarded to account position events.

`trading_order_events` stores `trace_id` from the HTTP order or cancel request. Use it with
`trading_match_results.trace_id`, `trading_match_trades.trace_id`, Kafka topic/partition/offset, and
the relevant ids (`order_id`, `command_id`, `trade_id`) when tracing a user request across the trading
chain.
The `trading_trigger_orders_trace_idx`, `trading_order_events_trace_idx`,
`trading_match_results_trace_idx`, `trading_match_trades_trace_idx`,
`trading_outbox_events_trace_idx`, `account_outbox_events_trace_idx`,
`gateway_admin_operation_logs_trace_idx`, and
`gateway_admin_approval_requests_consumed_trace_idx` indexes support the admin
TraceId lookup endpoint `/api/v1/admin/traces/{traceId}`.
`gateway_admin_operation_logs.duration_ms` stores admin gateway proxy request duration for audit exports
and p50/p95/p99 system latency metrics.
The same side-specific versions are used for contract math, while maker/taker fee ppm comes from the
required fee fields stored on `trading_match_trades`.

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
User-initiated isolated-margin adjustments also mutate this table: positive adjustments move
`account_balances.available_units` into locked position collateral, while negative adjustments release
position collateral back to available balance only after the latest `risk_position_snapshots` row proves
the position remains above maintenance margin plus the configured removal buffer.
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

`account_positions` stores perpetual exposure by position bucket:

- `margin_mode`: `CROSS` or `ISOLATED`.
- `position_side`: `NET`, `LONG`, or `SHORT`. `ONE_WAY` accounts use the single `NET` bucket. `HEDGE`
  accounts keep independent `LONG` and `SHORT` buckets for the same user, symbol, and margin mode.
  Position mode switching is user-scoped and is allowed only when the user has no non-zero positions,
  no active orders, no pending trigger orders, and no unsettled matching/account state.
- `instrument_version`: contract version for the current non-zero exposure. It is `NULL` when the position is flat.
- `signed_quantity_steps`: positive for long, negative for short.
- `entry_price_ticks`: average entry in exchange-core ticks.
- `realized_pnl_units`: realized PnL accumulator in the instrument settlement asset. Closing fills are
  written to `account_ledger_entries` as `TRADE_PNL`.
- Maker/taker fee debits or rebates are written to `account_ledger_entries` as `TRADE_FEE`.
  `trade_id`, `order_id`, `symbol`, and `fee_rate_ppm` are stored with the ledger row for audit and
  reconciliation.
- Actual liquidation fees are written to `account_ledger_entries` as `LIQUIDATION_FEE` with the same
  trade/order/symbol/fee-rate audit fields. The amount is capped by collectible collateral and never
  creates a new `account_deficits` row. Insurance fund income is driven only by these collected
  amounts, not by liquidation estimates.

The account provider executes every maker/taker side through `account_commands`. The immutable
command id and envelope SHA-256 provide execution idempotency, while
`account_trade_settlements(product_line, symbol, trade_id)` records bilateral completion.
Each participant writes its `APPLIED` state with a participant-validated atomic UPSERT at the end of
the account transaction; identity conflicts or an already-applied side affect zero rows and roll back
the whole participant transaction.
All balance and margin transitions run inside one PostgreSQL transaction and lock the affected
position/margin rows with `FOR UPDATE`. Position, balance, deficit, ledger, reservation, and
position-margin writes are fail-fast when an expected row is not written.
For `TRADE_PNL` and `TRADE_FEE`, both the ledger insert and the balance-after backfill must touch one
row; command idempotency is checked before dispatch, and ledger conflicts are never silently skipped.
`LIQUIDATION_FEE` uses `tradeId:orderId` as the reference id and is emitted to the insurance fund
only after the balance debit succeeds.
Manual isolated-margin transfers write `account_ledger_entries.reference_type = POSITION_MARGIN_ADJUSTMENT`
with signed `amount_units`: positive means collateral added to the position, negative means collateral
released from the position. The unique `account_ledger_reference_uidx` makes these requests idempotent
by `reference_id + user_id + asset`.
Back-office balance and product-balance adjustments also keep a dedicated operator audit trail in
`account_admin_balance_adjustments`. The ledger rows remain the source of funds truth, while this
admin table stores `admin_user_id`, `admin_username`, adjustment kind, account type, reference id,
amount, balance-after value, and timestamp for back-office traceability.
Gateway account-asset report snapshots are stored separately in
`gateway_admin_account_asset_snapshots`. They are generated from account balances and
`price_exchange_rates` for admin reporting; they are not an account ledger and must not be used as
the source of funds truth.
Snapshots can be generated manually through `/api/v1/admin/reports/account-assets/snapshots` or
automatically by the gateway account-asset snapshot scheduler. Scheduled runs compare the generated
snapshot with the previous day by `account_type + asset`; differences above configured thresholds
raise `SYSTEM` alert events with metric key `ACCOUNT_ASSET_SNAPSHOT_DIFF_PPM`.
Saved snapshot queries use keyset pagination over `snapshot_date`, `total_value`, and `snapshot_id`;
`gateway_account_asset_snapshots_page_idx` supports the admin report paging path.

Gateway support operations use two local admin tables:

- `gateway_support_tickets` stores customer support cases by user, status, priority, category, assignee,
  creator, resolver, and close time.
- `gateway_support_ticket_notes` stores the chronological note timeline for each ticket. Notes carry the
  admin user, note type, visibility, body, and creation time.

Gateway admin alerting uses four tables:

- `gateway_admin_alert_rules` stores configurable alert rules for `SYSTEM`, `MARKET`, `TRADING`, `RISK`, and
  `WALLET` domains.
- `gateway_admin_alert_events` stores the current and historical alert events keyed by a rule/target
  fingerprint. Active events are unique while `OPEN` or `ACKNOWLEDGED`; a resolved fingerprint may open again later.
- `gateway_admin_alert_channels` stores admin-managed notification channels with optional domain scope and minimum
  severity. Channel config writes go through gateway admin RBAC, operation audit, and approval.
- `gateway_admin_alert_deliveries` stores per-event/per-channel delivery queue rows. Evaluation creates `PENDING`
  rows for matching enabled channels; external sender workers can claim these rows and update status to `SENT`,
  `FAILED`, or `SKIPPED`. Admin-web can query and retry failed/skipped rows.

`account_outbox_events` stores account-side Kafka events written inside the same transaction as the
account state change. It carries `POSITION_UPDATED` events for WebSocket private position pushes and
`LIQUIDATION_FEE_SETTLED` events for insurance-fund credits. Redis position snapshots are not business events and
are deliberately excluded from this table:

- `id`: database-allocated outbox id.
- `topic`: Kafka destination, for example `surprising.account.position.events.v1` or
  `surprising.account.liquidation-fee.events.v1`.
- `product_line`: outbox ownership and publisher scope. A provider only claims rows for its configured product line.
- `event_key`: Kafka key. Position updates use the normalized symbol; liquidation-fee events use the
  settlement asset so insurance fund updates can be serialized by asset.
- `payload`: JSONB event body.
- `attempts`, `next_attempt_at`, `published_at`, and `last_error`: retry and publish state.
- `POSITION_UPDATED` payloads carry the original match-trade `traceId`, so WebSocket/private-account
  pushes can be correlated with the order and matching rows.
- `LIQUIDATION_FEE_SETTLED` payloads carry `tradeId`, `orderId`, `liquidationOrderId`, `candidateId`,
  `asset`, collected `amountUnits`, `feeRatePpm`, and `traceId`.
- Account-provider captures one complete position/collateral snapshot per distinct changed key before commit and
  submits it to a bounded, coalescing Redis worker only after commit. This path does not insert an outbox row or
  publish Kafka. Redis Lua CAS rejects older revisions. Queue overflow or Redis failure marks the product-line
  projection unavailable, and the cache coordinator repairs it from PostgreSQL before reads resume.

Indexes:

```sql
CREATE INDEX account_outbox_pending_idx
    ON account_outbox_events (product_line, next_attempt_at, id)
    WHERE published_at IS NULL;

CREATE INDEX account_outbox_pending_stream_idx
    ON account_outbox_events (product_line, topic, event_key, id)
    INCLUDE (next_attempt_at)
    WHERE published_at IS NULL;

CREATE INDEX account_outbox_aggregate_idx
    ON account_outbox_events (aggregate_type, aggregate_id);

CREATE INDEX account_outbox_published_line_cleanup_idx
    ON account_outbox_events (product_line, published_at, id)
    WHERE published_at IS NOT NULL;
```

The account publisher combines transaction-scoped advisory locks per `topic + event_key` with an
atomic lease update, so multiple account-provider nodes can drain different streams safely.
Publishing is at-least-once; consumers should deduplicate by event id, trade id, or their own
latest-position version rules. Insurance uses
`insurance_fund_ledger(reference_type, reference_id, asset)` with `reference_id = tradeId:orderId`.
The account claim materializes the product-line pending set once, ranks each `topic + event_key`
stream in one window pass, and leases only a continuous due prefix. A future retry still blocks every
later row for that key, and the round-robin candidate order prevents a deep symbol stream from taking
the whole batch.
Published rows are transient delivery records. Every publisher owns a scheduled retention task: the shared
trading table is partitioned logically by `aggregate_type`, the account table by `product_line`, and the risk
publisher owns the risk table. Rows older than seven days are deleted once per minute in up to ten short
10,000-row `FOR UPDATE SKIP LOCKED` batches. Pending or failed rows are never selected for deletion. The
retention duration, cleanup delay, batch size, and maximum batches per run must all be positive.

## Liquidation Tables

`liquidation_orders` audits each liquidation candidate outcome:

- `SUBMITTED` rows must have positive `quantity_steps`.
- `CANCELED` rows may have `quantity_steps = 0` when the account has recovered or the position is gone.
- `bankruptcy_price_ticks`, `takeover_price_ticks`, `liquidation_fee_rate_ppm`, and
  `liquidation_fee_units` are fixed at submission time for audit and insurance/ADL reconciliation.
  Canceled rows keep these fields at zero.
- The provider locks live `account_positions`, locks existing same-side reduce-only `trading_orders`, writes cancel-request events/commands for them, and then sizes the staged liquidation order from `abs(livePosition)`.
- Existing user reduce-only orders must not reduce liquidation capacity; otherwise a far-away GTC close order could block forced liquidation.
- Strong liquidation order creation does not use broad conflict suppression on `trading_orders`; uniqueness violations roll the transaction back.
- Sizing first attempts risk-bracket reduction, then configured partial close ratios, and only fully closes when the margin ratio is above the full-close threshold.
- Before submitting, the provider reads the latest fresh risk position/account snapshots and cancels with `RISK_POSITION_CHANGED` if the snapshot quantity no longer matches the locked live position.

`liquidation_admin_actions` stores admin-side operational actions for liquidation candidates:

- `CANCEL_CANDIDATE` is currently supported.
- Actions reference `risk_liquidation_candidates(candidate_id)`.
- `admin_user_id`, `reason`, and `created_at` are persisted for dispute review and audit.
- A candidate can be canceled from the admin UI only while it is `NEW` or `PROCESSING` and has no active `SUBMITTED` or `PARTIALLY_FILLED` liquidation order.
