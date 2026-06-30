# Deployment

## Kafka Topics

Create the perpetual trade input topic with enough partitions for your active symbols and expected throughput.

```bash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic surprising.instrument.events.v1 \
  --partitions 24 \
  --replication-factor 3

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

Initialize the schema from repository root:

```bash
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

The root `init.sql` creates:

- `instruments`: immutable product-rule snapshots keyed by `(symbol, version)`.
- `instrument_current_versions`: current version pointer per symbol.
- `instrument_symbol_sequences`: atomic instrument version allocator per symbol.
- `instrument_risk_brackets`: risk tiers keyed by `(symbol, version, bracket_no)`.
- `instrument_index_sources`: index component source config keyed by `(symbol, version, source)`.
- `candlestick_symbols`: optional symbol registry.
- `candlestick_candles`: OHLCV storage keyed by `(symbol, period, open_time)`.
- `price_index_ticks`: index price ticks keyed by `(symbol, sequence)`.
- `price_index_components`: index component audit rows keyed by `(symbol, sequence, source)`.
- `price_symbol_leases`: active publisher ownership keyed by `(module, symbol)`.
- `price_symbol_sequences`: database-allocated price sequence keyed by `(module, symbol)`.
- `price_exchange_rates`: fiat and stable-coin bridge rates keyed by `(base_currency, quote_currency)`.
- `price_mark_ticks`: mark price ticks keyed by `(symbol, sequence)`.

The query API uses fixed table names and parameter binding. It does not concatenate user input into table names.

Recommended production settings:

- Use PostgreSQL 16 or newer.
- Put `candlestick_candles` on fast storage.
- Keep the primary key `(symbol, period, open_time)`.
- For very large history, add native PostgreSQL range partitioning by `open_time` or move closed candles into TimescaleDB hypertables.
- Keep `surprising.candlestick.flush.max-batch-size` between `500` and `5000` depending on DB latency.

## Price Provider Feeds

- Run the index provider with external venue WebSocket enabled for production.
- REST polling is only a cold-start and stale-cache fallback; do not size production around REST for every symbol/source pair.
- Keep `surprising.price.index.web-socket.reconnect-initial-delay` and `reconnect-max-delay` conservative enough to avoid reconnect storms after provider incidents.
- Store customer-facing fiat conversion rates in `price_exchange_rates`; app and gateway requests should query local APIs.
- Use a paid FX provider with SLA in production, and keep the default public endpoint only for development or backup.
- Keep `surprising.price.*.coordination.enabled=true` for multi-node deployments.
- Set `surprising.price.*.coordination.node-id` to a stable pod name, hostname, or instance id.
- Keep `coordination.lease-duration` several times longer than the publish interval. Default is `15s`.
- Do not publish index or mark prices when PostgreSQL is unavailable; lease and sequence guarantees depend on PostgreSQL.

## Deployment Order

1. Start PostgreSQL and Kafka.
2. Apply `init.sql`.
3. Create Kafka topics with `scripts/create-topics.sh`.
4. Start instrument providers.
5. Start candlestick providers.
6. Start index price providers.
7. Start mark price providers after index, book ticker, trade, and funding-rate topics are available.
8. Start WebSocket/fanout services that consume candle, index, and mark output topics.

## API Smoke Tests

```bash
curl 'http://localhost:9080/api/v1/instruments/latest?symbol=BTC-USDT'
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
curl 'http://localhost:9083/api/v1/price/mark/latest?symbol=BTC-USDT'
```

## Failure Behavior

- Duplicate trades are dropped by `symbol + tradeId`.
- Old replays are also guarded by per-symbol `sequence`.
- Dirty candle snapshots are stored in a persistent RocksDB state store before PostgreSQL flush.
- PostgreSQL writes are full-snapshot upserts, so retrying the same dirty snapshot is idempotent.
- Perpetual candle update Kafka events are separate from DB persistence. A websocket service should consume the candle topic and maintain its own client fanout.
- Index and mark providers use `price_symbol_leases` so only one live node publishes a given `module + symbol`.
- Index and mark providers use `price_symbol_sequences` so a failover cannot reset sequence numbers.
- External-source failures are stored in component/audit records; unusable index prices are not published.
- Instrument changes are immutable versions; downstream services replace local cache after reading current snapshots or consuming `surprising.instrument.events.v1`.

## Troubleshooting

- `price_symbol_leases` owner does not move after a node dies: wait until `lease_until`; if it is far in the future, verify node clock synchronization.
- Price sequence has gaps: expected after failed attempts. Investigate only if a sequence moves backwards, which should not happen.
- Index price missing: inspect `price_index_components` for `STALE`, `OUTLIER`, `ERROR`, or conversion failure reasons.
- Binance returns `451` or Bybit returns `403`: collector egress region/IP is blocked by the venue.
- WebSocket reconnect loop: check venue connectivity, ping/pong behavior, idle timeout, and egress firewall.
- Kafka consumer lag: increase topic partitions, provider instances, or stream threads. Do not create one topic per symbol.
- RocksDB restore is slow: check changelog topic retention, local state directory persistence, disk throughput, and container file descriptor limits.

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
      accept-unknown-symbols: false
      source: INSTRUMENT
```

With this mode, a new trading pair starts producing candles after `surprising-instrument` exposes it
in the current `instruments` snapshot. The index price provider also reads current symbols and index
sources from `instruments + instrument_index_sources`.

Legacy registry fallback:

```yaml
surprising:
  candlestick:
    symbols:
      accept-unknown-symbols: false
      source: CANDLESTICK_SYMBOLS
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
