# Candlestick Deployment

## Kafka Topics

Create the perpetual trade input topic with enough partitions for your active symbols and expected throughput.

```bash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic surprising.perp.trade.events.v1 \
  --partitions 24 \
  --replication-factor 3

kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic surprising.perp.candle.events.v1 \
  --partitions 24 \
  --replication-factor 3
```

For local Docker Compose, use replication factor `1`.

## Multi-Node Rules

- All candlestick provider nodes must use the same `surprising.candlestick.kafka.application-id`.
- Every node must have a unique local `surprising.candlestick.stream.state-dir`.
- Run at least as many Kafka partitions as the desired maximum parallelism.
- Scale by increasing service instances and Kafka partitions, not by creating per-symbol consumers.
- Kafka Streams restores RocksDB state from changelog topics during rebalance or restart.

## PostgreSQL

Flyway creates:

- `candlestick_symbols`: optional symbol registry.
- `candlestick_candles`: OHLCV storage keyed by `(symbol, period, open_time)`.

The query API uses fixed table names and parameter binding. It does not concatenate user input into table names.

Recommended production settings:

- Use PostgreSQL 16 or newer.
- Put `candlestick_candles` on fast storage.
- Keep the primary key `(symbol, period, open_time)`.
- For very large history, add native PostgreSQL range partitioning by `open_time` or move closed candles into TimescaleDB hypertables.
- Keep `surprising.candlestick.flush.max-batch-size` between `500` and `5000` depending on DB latency.

## Failure Behavior

- Duplicate trades are dropped by `symbol + tradeId`.
- Old replays are also guarded by per-symbol `sequence`.
- Dirty candle snapshots are stored in a persistent RocksDB state store before PostgreSQL flush.
- PostgreSQL writes are full-snapshot upserts, so retrying the same dirty snapshot is idempotent.
- Perpetual candle update Kafka events are separate from DB persistence. A websocket service should consume the candle topic and maintain its own client fanout.

## Trading Integration Checklist

1. Produce one Kafka record per executed trade.
2. Use `symbol` as the Kafka key.
3. Ensure all trades for one symbol are sent to the same Kafka topic with monotonic `sequence`.
4. Include `tradeId`, `sequence`, `tradeTime`, `price`, `quantity`, and real `side`.
5. Do not aggregate trades before this service unless the aggregated trade has its own unique id and sequence.

## Dynamic Symbols

Default behavior:

```yaml
surprising:
  candlestick:
    symbols:
      accept-unknown-symbols: true
```

With this mode, a new trading pair starts producing candles as soon as trading publishes keyed Kafka records.

Strict registry mode:

```yaml
surprising:
  candlestick:
    symbols:
      accept-unknown-symbols: false
      refresh-delay-ms: 30000
```

Then insert or update the symbol registry:

```sql
INSERT INTO candlestick_symbols(symbol, base_asset, quote_asset, enabled)
VALUES ('BTC-USDT', 'BTC', 'USDT', TRUE)
ON CONFLICT (symbol) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now();
```

## API

Range query:

```http
GET /api/v1/candlestick/candles?symbol=BTC-USDT&period=1m&startTime=2026-06-30T10:00:00Z&endTime=2026-06-30T11:00:00Z&limit=500
```

Latest candle:

```http
GET /api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m
```

Supported periods are configured by:

```yaml
surprising:
  candlestick:
    periods: [ "1m", "5m", "15m", "30m", "1h", "4h", "1d" ]
```
