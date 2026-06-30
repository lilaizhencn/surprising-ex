# Candlestick Database Design

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
