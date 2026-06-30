# surprising-candlestick

[English](README.md) | [简体中文](README_CN.md)

Perpetual candlestick service for Surprising Exchange.

## Modules

- `surprising-candlestick-api`: RPC contracts and trade/candle DTOs.
- `surprising-candlestick-provider`: Kafka Streams + PostgreSQL candlestick service.

## Architecture

The candlestick service is designed for multi-node deployment and follows a partitioned market-data pipeline:

- The perpetual trading service produces `TradeEvent` JSON to Kafka topic `surprising.perp.trade.events.v1`.
- Kafka record key must be the normalized symbol, for example `BTC-USDT`.
- Kafka partitions distribute symbols across service nodes. There is no one-thread-per-symbol model.
- Kafka Streams uses RocksDB state stores for hot candle state, trade idempotency, dirty snapshots, and latest sequence per symbol.
- PostgreSQL receives periodic full-snapshot upserts. The service never reads a candle row for every trade.
- Perpetual candle update events are emitted to `surprising.perp.candle.events.v1`; websocket/push services should consume that topic separately.
- Enabled symbols are read from the current `surprising-instrument` snapshot by default. Set `surprising.candlestick.symbols.source=CANDLESTICK_SYMBOLS` to use the legacy `candlestick_symbols` table.

## Multi-Node Mechanism

The service supports horizontal deployment:

- All provider instances use the same Kafka Streams `application-id`: `surprising-candlestick-v1`.
- Each trade record uses `symbol` as the Kafka key.
- Kafka assigns topic partitions to provider instances.
- Trades for one symbol stay in one Kafka partition, so candle calculation for that symbol remains serial and concurrency-safe.
- Different symbols are spread across partitions and can be processed by different stream threads or service nodes.
- RocksDB state stores keep hot candle state locally. Kafka Streams changelog topics restore that state during restart or rebalance.
- PostgreSQL is the shared durable query store. Writes are full-snapshot upserts keyed by `(symbol, period, open_time)`.

## Production Notes

- Run at least two `surprising-candlestick-provider` instances.
- Use a production Kafka cluster with at least three brokers and replication factor `3`.
- Use PostgreSQL HA, such as managed RDS, Patroni, or another primary/standby setup.
- Do not share one `surprising.candlestick.stream.state-dir` between multiple service processes.
- Keep Kafka auto topic creation disabled in production and create topics explicitly before deployment.
- Plan input topic partition count upfront. Increasing partitions later may cause the same symbol to hash to a different partition, splitting state. If the partition count must change after production traffic exists, create a new versioned topic/application-id and replay history to rebuild state.

## RocksDB Installation And Tuning

RocksDB is not deployed as a separate server in this project. Kafka Streams embeds RocksDB through
the `org.rocksdb:rocksdbjni` dependency, which is pulled transitively by `org.apache.kafka:kafka-streams`.
You do not need to run `apt install rocksdb` or manage a RocksDB daemon.

Runtime requirements:

- Use a glibc-based Linux image such as Debian or Ubuntu JRE. Avoid Alpine/musl images unless you have verified `rocksdbjni` native loading.
- Keep `java.io.tmpdir` writable because `rocksdbjni` extracts native libraries at runtime.
- Put `surprising.candlestick.stream.state-dir` on local SSD/NVMe. Do not use NFS or a shared network filesystem.
- Every provider process must have its own state directory, for example `/data/kafka-streams/${HOSTNAME}`.
- Keep enough free disk space for RocksDB state, changelog restore, and compaction. Alert before disk free space drops below 30%.
- Raise file descriptor limits in production, for example `ulimit -n 100000`.

Current optimized defaults:

```yaml
surprising:
  candlestick:
    stream:
      state-dir: /data/kafka-streams
    rocksdb:
      block-cache-size: 256MB
      write-buffer-size: 64MB
      write-buffer-manager-size: 256MB
      max-write-buffer-number: 3
      block-size: 16KB
      max-background-jobs: 2
      target-file-size-base: 64MB
      bytes-per-sync: 1MB
      compression-type: LZ4_COMPRESSION
      bloom-filter-enabled: true
      bloom-filter-bits-per-key: 10.0
```

The code uses a process-level shared RocksDB block cache and write-buffer manager. A practical
native memory estimate per provider process is:

```text
native_memory ~= block-cache-size + write-buffer-manager-size + compaction/read overhead
```

Leave room for JVM heap, direct buffers, Netty/Tomcat, and OS page cache. For a 4 GB container, a
reasonable first profile is:

```bash
JAVA_TOOL_OPTIONS="-Xms1g -Xmx2g"
surprising.candlestick.rocksdb.block-cache-size=512MB
surprising.candlestick.rocksdb.write-buffer-manager-size=512MB
```

Tuning rules:

- Increase `block-cache-size` when range/latest queries from state stores become read-heavy.
- Increase `write-buffer-manager-size` and `max-background-jobs` when Kafka input lag grows and disk is not saturated.
- Keep `compression-type=LZ4_COMPRESSION` for low CPU and good write throughput. Use `ZSTD_COMPRESSION` only if disk is the bottleneck and CPU has headroom.
- Do not put `state-dir` on ephemeral tiny container layers. Use a mounted volume or local node disk.
- If a node dies, Kafka Streams can restore RocksDB from changelog topics; persistent local state mainly reduces restart time.

## Kafka Partition Planning

This project uses one perpetual trade topic with many partitions:

- Input topic: `surprising.perp.trade.events.v1`
- Output topic: `surprising.perp.candle.events.v1`

Do not create one topic per symbol. Create enough partitions on the shared topic.

Recommended sizing formula:

```text
partitions = roundUp(
  max(
    MIN_PARTITIONS,
    ceil(SYMBOL_COUNT / TARGET_SYMBOLS_PER_PARTITION),
    PROVIDER_INSTANCES * STREAM_THREADS * 2
  ),
  PARTITION_STEP
)
```

Default script values:

- `MIN_PARTITIONS=24`
- `TARGET_SYMBOLS_PER_PARTITION=10`
- `STREAM_THREADS=2`
- `PROVIDER_INSTANCES=1`
- `PARTITION_STEP=12`
- `MAX_PARTITIONS=384`

Create topics manually:

```bash
PARTITIONS=48 REPLICATION_FACTOR=3 ./scripts/create-topics.sh
```

Create topics by symbol count and deployment size:

```bash
SYMBOL_COUNT=500 PROVIDER_INSTANCES=4 STREAM_THREADS=4 REPLICATION_FACTOR=3 ./scripts/create-topics.sh
```

The script calculates `partitions=60` for the example above:

- `ceil(500 / 10) = 50`
- `4 * 4 * 2 = 32`
- `max(24, 50, 32) = 50`
- rounded up by step `12` gives `60`

Runtime processing is configured here:

```yaml
surprising:
  candlestick:
    kafka:
      trade-topic: surprising.perp.trade.events.v1
      candle-topic: surprising.perp.candle.events.v1
      application-id: surprising-candlestick-v1
    stream:
      threads: 2
    symbols:
      accept-unknown-symbols: false
      source: INSTRUMENT
```

## WebSocket Fanout

This service does not hold WebSocket connections directly. It emits candle snapshots to Kafka and
lets a dedicated WebSocket/fanout service push them to clients.

WebSocket service input:

```text
surprising.perp.candle.events.v1
```

Recommended routing key for client subscriptions:

```text
perp:kline:{symbol}:{period}
perp:kline:BTC-USDT:1m
```

Push semantics:

- `status=PARTIAL`: update the currently open candle.
- `status=CLOSED`: finalize the candle; clients should stop mutating that bucket.
- The event payload is a full snapshot, not a delta, so clients can replace the candle by `symbol + period + openTime`.
- On client reconnect, fetch history/latest candle from the REST/RPC API first, then subscribe to WebSocket updates.

Consumer group caution:

- If every WebSocket node must push to its own connected clients, do not put all WebSocket nodes in one shared consumer group unless you have a shared subscription/fanout layer.
- A shared consumer group delivers each Kafka record to only one WebSocket node.
- Common choices are either one unique consumer group per WebSocket node, or one market-data fanout service that consumes Kafka once and redistributes through Redis/NATS/internal pub-sub.

Hot-symbol push control:

- Do not fan out every trade-level update directly for hot symbols.
- Coalesce `PARTIAL` updates per `symbol + period` in a 100-500 ms window and push only the latest snapshot.
- Never drop the final `CLOSED` update for a bucket.
- Preserve ordering per `symbol + period`; avoid unordered parallel push for the same channel.
- Use bounded queues and drop intermediate `PARTIAL` snapshots under backpressure, keeping the newest snapshot.

## Trade Event Contract

Example Kafka message:

```json
{
  "symbol": "BTC-USDT",
  "tradeId": "10000001",
  "sequence": 10000001,
  "tradeTime": "2026-06-30T10:15:20Z",
  "price": 61500.12,
  "quantity": 0.05,
  "side": "BUY",
  "makerOrderId": "m-1",
  "takerOrderId": "t-1"
}
```

Producer requirements:

- Set Kafka key to `symbol`.
- `tradeId` must be globally unique per symbol.
- `sequence` must be monotonic per symbol.
- `tradeTime` must be the matching-engine trade time in UTC.
- `side` must be the real aggressor direction: `BUY`, `SELL`, or `UNKNOWN`.

## Local Run

Run from repository root:

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-candlestick-provider -am spring-boot:run
```

Query candles:

```bash
curl 'http://localhost:9081/api/v1/candlestick/candles?symbol=BTC-USDT&period=1m&startTime=2026-06-30T10:00:00Z&endTime=2026-06-30T11:00:00Z&limit=100'
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
```

## Verification

Run from repository root:

```bash
mvn -pl :surprising-candlestick-provider -am test
```

The current simulation tests validate UTC bucket flooring, OHLCV aggregation, out-of-order trade handling, and duplicate trade suppression.

See [deployment.md](../docs/deployment.md) for production deployment notes.
