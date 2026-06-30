# Database Design

Initialize a new PostgreSQL database from repository root:

```bash
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

The local `docker-compose.yml` also mounts `init.sql` into PostgreSQL's init directory. It runs
automatically only when the named PostgreSQL volume is created for the first time.

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
