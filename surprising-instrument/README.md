# surprising-instrument

[English](README.md) | [简体中文](README_CN.md)

Perpetual instrument configuration module for Surprising Exchange. It is the product-rule center for matching, risk, account, candlestick, index price, mark price, and funding-rate services.

## Modules

- `surprising-instrument-api`: RPC contracts, DTOs, and event models.
- `surprising-instrument-provider`: persistence, query/admin APIs, and Kafka change-event publishing.

## Responsibilities

- Base contract metadata: `symbol`, base/quote/settle asset, contract type, contract size.
- Price and quantity rules: tick size, step size, min/max order quantity, notional limits, precision.
- Order rules: supported order types, time in force, post-only, reduce-only, market-order switches.
- Risk rules: maximum leverage, initial margin rate, maintenance margin rate, risk limit brackets.
- Default trading fee schedule: maker/taker fee rates in ppm. Positive values charge the user; negative values represent rebates. User/VIP/market-maker/promotion overrides are resolved from order-provider `trading_fee_schedules` and snapshotted on the order.
- Funding configuration: interval, interest rate, cap/floor, impact notional.
- Index components: external spot source REST/WS config, weights, USD/USDT conversion rules.
- Versioning: every change creates a new `version`; `instrument_current_versions` switches the current snapshot.
- Multi-node safety: `instrument_symbol_sequences` atomically allocates versions per symbol and prevents version conflicts during concurrent admin requests.

## Long Unit Model

Instrument configuration is stored in exchange-core-friendly long units:

- `price_tick_units`: quote asset smallest units per one price tick.
- `quantity_step_units`: base asset smallest units per one quantity step.
- `min_quantity_steps` / `max_quantity_steps`: order quantity bounds already expressed as steps.
- `min_notional_units` / `max_notional_units`: long notional bounds. For `LINEAR_PERPETUAL` they are settlement asset units; for `INVERSE_PERPETUAL` they are quote face-value units.
- `notional_multiplier_units`: for `LINEAR_PERPETUAL`, settlement units per `priceTick * quantityStep`; for `INVERSE_PERPETUAL`, quote face-value units per contract step.
- `contract_type` is not descriptive metadata only. Account, risk, funding, liquidation, and ADL formulas branch on it.
- `maker_fee_rate_ppm` / `taker_fee_rate_ppm`: product default fees. Order entry applies `trading_fee_schedules` overrides and writes the final rates to `trading_orders`; account settlement writes `TRADE_FEE` ledger entries from that order snapshot. Positive values debit the user, and negative values credit a rebate. Default BTC/ETH contracts use maker `200 ppm` and taker `500 ppm`.
- `user_open_interest_limit_rate_ppm` / `user_open_interest_limit_floor_units`: dynamic per-user position cap settings. Order provider uses `max(platform OI notional * rate, floor)`, additionally bounded by `max_position_notional_units`. BTC/ETH default to `300000 ppm` and `25000000000000`, equal to a 250,000 USDT floor.
- `*_rate_ppm`, `max_leverage_ppm`, `weight_ppm`: rates, leverage, and weights use ppm.

`surprising-instrument-api` also owns `PerpetualContractMath`, the shared long-unit formula implementation for linear/inverse notional, unrealized PnL, notional-per-step, and maintenance margin. Risk, funding, liquidation, and ADL should call this shared math instead of reimplementing contract formulas in SQL.

Admin APIs should send these integer fields directly. Human decimal formatting belongs at the admin UI/API gateway edge.

## Dynamic Configuration Flow

```text
instrument-provider
  -> PostgreSQL instruments / instrument_current_versions
  -> surprising.instrument.events.v1
  -> candlestick / price / future matching / risk local cache
```

Currently integrated:

- `surprising-candlestick-provider` strict mode reads enabled symbols from the current `instruments` snapshot.
- `surprising-index-price-provider` dynamically reads symbols and index sources from `instruments + instrument_index_sources`; static BTC/ETH YAML config is only a fallback before DB initialization.

## Status Semantics

- `PRE_TRADING`: market data warm-up; real matching is usually disabled.
- `TRADING`: normal trading and market-data calculation.
- `HALT`: matching paused; market-history services can still recognize the symbol.
- `SETTLING`: settlement in progress.
- `CLOSED`: retired market.

## API

Latest version:

```bash
curl 'http://localhost:9080/api/v1/instruments/latest?symbol=BTC-USDT'
```

Specific version:

```bash
curl 'http://localhost:9080/api/v1/instruments/version?symbol=BTC-USDT&version=1'
```

List:

```bash
curl 'http://localhost:9080/api/v1/instruments/list?type=PERPETUAL&status=TRADING'
```

Admin paginated current-market list:

```bash
curl 'http://localhost:9080/api/v1/instruments/admin/list?type=PERPETUAL&status=TRADING&limit=100&sort=symbol.asc'
```

Admin current-market detail and version history:

```bash
curl 'http://localhost:9080/api/v1/instruments/admin/BTC-USDT'
curl 'http://localhost:9080/api/v1/instruments/admin/BTC-USDT/versions?limit=50&sort=version.desc'
```

Admin list endpoints support `limit/cursor/sort` cursor pagination. Current-market sort tokens are `symbol.asc`, `symbol.desc`, `updatedAt.desc`, `updatedAt.asc`, `createdAt.desc`, and `createdAt.asc`; version-history sort tokens are `version.desc` and `version.asc`. Responses keep `count/instruments` and add `nextCursor`, `hasMore`, `sort`, and `limit`.

Status update:

```bash
curl -X POST 'http://localhost:9080/api/v1/instruments/admin/BTC-USDT/status?status=HALT'
```

Full upsert uses `POST /api/v1/instruments/admin/upsert` with an `InstrumentUpsertRequest` body. In production, admin APIs should be called through the gateway admin proxy only; product configuration and status changes must pass approval, permission checks, and operation audit logging.

## Kafka

```text
surprising.instrument.events.v1
```

The event key is `symbol`. Each event carries the full new `InstrumentResponse` snapshot so downstream services can replace their local cache directly.
The producer uses `acks=all`, idempotence, `zstd`, and `max.in.flight.requests.per.connection=5` so version-change events have the same durable Kafka baseline as the trading and price pipelines.

## Database

Root [init.sql](../init.sql) creates:

- `instruments`
- `instrument_current_versions`
- `instrument_symbol_sequences`
- `instrument_risk_brackets`
- `instrument_index_sources`

Default markets:

- `BTC-USDT`: linear USDT perpetual, `contract_multiplier_ppm=1000000`, `contract_value_asset=USDT`,
  `price_tick_units=10000000` (0.1 USDT), `quantity_step_units=100000` (0.001 BTC),
  `quantity_precision=3`, `min_quantity_steps=1`, `max_quantity_steps=100000`,
  `notional_multiplier_units=10000`, `user_open_interest_limit_rate_ppm=300000`,
  `user_open_interest_limit_floor_units=25000000000000`.
- `ETH-USDT`: linear USDT perpetual, `contract_multiplier_ppm=1000000`, `contract_value_asset=USDT`,
  `price_tick_units=1000000` (0.01 USDT), `quantity_step_units=10000000000000000` (0.01 ETH),
  `quantity_precision=2`, `min_quantity_steps=1`, `max_quantity_steps=500000`,
  `notional_multiplier_units=10000`, `user_open_interest_limit_rate_ppm=300000`,
  `user_open_interest_limit_floor_units=25000000000000`.

## Local Run

```bash
brew services start postgresql@18
brew services start kafka
brew services start redis
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-instrument-provider -am spring-boot:run
```

## Production Notes

- Instrument is the single product configuration source. Do not keep a second symbol-rule set in matching, risk, or market-data services.
- Query APIs are stateless and horizontally scalable. Admin writes share PostgreSQL, and `instrument_symbol_sequences` keeps per-symbol versions monotonic.
- Core downstream services should use local caches, not database reads for every request.
- Tick/step, leverage, and status changes must create a new version instead of overwriting history.
- Instrument default maker/taker fee-rate changes also create a new version. Accepted orders keep the fee snapshot stored on `trading_orders`; old positions keep the contract-math version they were opened with.
- Changes affecting matching and risk need approval, audit logs, and controlled effective time.
- To list a new symbol, create the instrument first, confirm Kafka partitions, start external price sources, then open trading.

## Verification

```bash
mvn -pl :surprising-instrument-provider -am test
```
