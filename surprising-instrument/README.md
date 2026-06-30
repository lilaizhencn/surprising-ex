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
- Funding configuration: interval, interest rate, cap/floor, impact notional.
- Index components: external spot source REST/WS config, weights, USD/USDT conversion rules.
- Versioning: every change creates a new `version`; `instrument_current_versions` switches the current snapshot.
- Multi-node safety: `instrument_symbol_sequences` atomically allocates versions per symbol and prevents version conflicts during concurrent admin requests.

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

Status update:

```bash
curl -X POST 'http://localhost:9080/api/v1/instruments/admin/BTC-USDT/status?status=HALT'
```

Full upsert uses `POST /api/v1/instruments/admin/upsert` with an `InstrumentUpsertRequest` body. In production, admin APIs should be available only to internal management systems or trusted operations networks.

## Kafka

```text
surprising.instrument.events.v1
```

The event key is `symbol`. Each event carries the full new `InstrumentResponse` snapshot so downstream services can replace their local cache directly.

## Database

Root [init.sql](../init.sql) creates:

- `instruments`
- `instrument_current_versions`
- `instrument_symbol_sequences`
- `instrument_risk_brackets`
- `instrument_index_sources`

Default markets:

- `BTC-USDT`
- `ETH-USDT`

## Local Run

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-instrument-provider -am spring-boot:run
```

## Production Notes

- Instrument is the single product configuration source. Do not keep a second symbol-rule set in matching, risk, or market-data services.
- Query APIs are stateless and horizontally scalable. Admin writes share PostgreSQL, and `instrument_symbol_sequences` keeps per-symbol versions monotonic.
- Core downstream services should use local caches, not database reads for every request.
- Tick/step, leverage, and status changes must create a new version instead of overwriting history.
- Changes affecting matching and risk need approval, audit logs, and controlled effective time.
- To list a new symbol, create the instrument first, confirm Kafka partitions, start external price sources, then open trading.

## Verification

```bash
mvn -pl :surprising-instrument-provider -am test
```
