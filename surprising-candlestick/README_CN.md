# surprising-candlestick

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 合约 K 线服务。

## 模块

- `surprising-candlestick-api`：K 线 RPC 合约、成交和 K 线 DTO。
- `surprising-candlestick-provider`：基于 Kafka Streams 和 PostgreSQL 的 K 线服务实现。

## 架构

K 线服务按多节点部署设计，采用分区化行情数据流水线：

- 撮合模块把 `MatchTradeEvent` JSON 写入 Kafka topic：`surprising.perp.match.trades.v1`。
- Kafka record key 必须是标准化后的交易对，例如 `BTC-USDT`。
- Kafka partition 在多个服务节点之间分配 symbol，不采用“一个合约一个线程”的模型。
- Kafka Streams 使用 RocksDB state store 保存热 K 线状态、成交幂等状态、待落库 dirty snapshot、每个 symbol 的最新 sequence。
- PostgreSQL 只接收周期性的完整快照 upsert。服务不会在每笔成交时读取数据库 K 线行。
- 合约 K 线变更事件发送到 `surprising.perp.candle.events.v1`，websocket/行情推送服务应独立消费这个 topic。
- 默认从 `surprising-instrument` 的 `instruments` 当前版本读取启用交易对。设置 `surprising.candlestick.symbols.source=CANDLESTICK_SYMBOLS` 后，才使用旧的 `candlestick_symbols` 表。

## 多节点核心机制

服务支持水平多节点部署：

- 所有 provider 实例使用同一个 Kafka Streams `application-id`：`surprising-candlestick-v1`。
- 每条成交消息用 `symbol` 作为 Kafka key。
- Kafka 会把 topic partition 分配给不同 provider 实例。
- 同一个 symbol 的成交会留在同一个 Kafka partition，因此这个 symbol 的 K 线计算仍然是串行且并发安全的。
- 不同 symbol 会分散到不同 partition，可由不同 stream 线程或不同服务节点并行处理。
- RocksDB state store 在本地保存热 K 线状态。服务重启或 rebalance 时，Kafka Streams 通过 changelog topic 恢复状态。
- PostgreSQL 是共享持久化查询库，写入方式是按 `(symbol, period, open_time)` 做完整快照 upsert。

## 生产注意事项

- `surprising-candlestick-provider` 至少部署 2 个实例。
- Kafka 生产集群建议至少 3 个 broker，topic replication factor 建议为 `3`。
- PostgreSQL 使用高可用方案，例如云 RDS、Patroni 或其他主备架构。
- 多个服务进程不能共用同一个 `surprising.candlestick.stream.state-dir`。
- 生产环境关闭 Kafka 自动创建 topic，部署前显式创建 topic。
- 输入 topic 的 partition 数要提前规划。生产流量开始后不要随意增加 partition，因为同一个 symbol 可能 hash 到新的 partition，导致状态被拆开。如果必须调整 partition 数，建议创建新的版本化 topic/application-id，并从历史成交重放重建状态。

## RocksDB 安装和调优

本项目不把 RocksDB 当成独立服务部署。Kafka Streams 会通过 `org.rocksdb:rocksdbjni`
把 RocksDB 嵌入到应用进程里，这个依赖由 `org.apache.kafka:kafka-streams` 传递引入。
因此不需要执行 `apt install rocksdb`，也不需要维护 RocksDB daemon。

运行环境要求：

- 使用 glibc 系 Linux 镜像，例如 Debian/Ubuntu JRE。不要直接用 Alpine/musl，除非已经验证 `rocksdbjni` native library 可以正常加载。
- `java.io.tmpdir` 必须可写，因为 `rocksdbjni` 启动时会解压 native library。
- `surprising.candlestick.stream.state-dir` 放到本地 SSD/NVMe。不要放 NFS 或共享网络文件系统。
- 每个 provider 进程必须使用独立 state 目录，例如 `/data/kafka-streams/${HOSTNAME}`。
- 预留 RocksDB state、changelog restore 和 compaction 所需磁盘空间。生产建议磁盘剩余低于 30% 前告警。
- 生产环境提高文件句柄限制，例如 `ulimit -n 100000`。

当前优化默认值：

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

代码里使用的是进程级共享 RocksDB block cache 和 write-buffer manager。单个 provider 进程的
native 内存可以按下面估算：

```text
native_memory ~= block-cache-size + write-buffer-manager-size + compaction/read overhead
```

还要给 JVM heap、direct buffer、Netty/Tomcat 和 OS page cache 留空间。4 GB 容器可以先用这个配置起步：

```bash
JAVA_TOOL_OPTIONS="-Xms1g -Xmx2g"
surprising.candlestick.rocksdb.block-cache-size=512MB
surprising.candlestick.rocksdb.write-buffer-manager-size=512MB
```

调优规则：

- state store 读压力高时，优先增大 `block-cache-size`。
- Kafka input lag 上升且磁盘没打满时，增大 `write-buffer-manager-size` 和 `max-background-jobs`。
- 默认保持 `compression-type=LZ4_COMPRESSION`，CPU 低、写入吞吐好。只有磁盘成为瓶颈且 CPU 有余量时再考虑 `ZSTD_COMPRESSION`。
- 不要把 `state-dir` 放到很小的容器层临时目录里，应使用挂载卷或本地节点磁盘。
- 节点挂掉后，Kafka Streams 可以从 changelog topic 恢复 RocksDB；持久化本地 state 的主要价值是缩短重启恢复时间。

## Kafka Partition 规划

本项目使用一个合约成交 topic 承载所有 symbol，通过多 partition 扩展：

- 输入 topic：`surprising.perp.match.trades.v1`
- 输出 topic：`surprising.perp.candle.events.v1`

不要按每个 symbol 创建一个 topic。应该在共享 topic 上创建足够多的 partition。

推荐计算公式：

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

脚本默认值：

- `MIN_PARTITIONS=24`
- `TARGET_SYMBOLS_PER_PARTITION=10`
- `STREAM_THREADS=2`
- `PROVIDER_INSTANCES=1`
- `PARTITION_STEP=12`
- `MAX_PARTITIONS=384`

手动指定 partition：

```bash
PARTITIONS=48 REPLICATION_FACTOR=3 ./scripts/create-topics.sh
```

按 symbol 数和部署规模自动计算：

```bash
SYMBOL_COUNT=500 PROVIDER_INSTANCES=4 STREAM_THREADS=4 REPLICATION_FACTOR=3 ./scripts/create-topics.sh
```

上面的例子会计算出 `partitions=60`：

- `ceil(500 / 10) = 50`
- `4 * 4 * 2 = 32`
- `max(24, 50, 32) = 50`
- 按步长 `12` 向上取整得到 `60`

运行时消费配置在这里：

```yaml
surprising:
  candlestick:
    kafka:
      trade-topic: surprising.perp.match.trades.v1
      candle-topic: surprising.perp.candle.events.v1
      application-id: surprising-candlestick-v1
    stream:
      threads: 2
    symbols:
      accept-unknown-symbols: false
      source: INSTRUMENT
```

## WebSocket 监听和推送

本服务不直接维护 WebSocket 连接。它只把 K 线快照发送到 Kafka，由独立的 WebSocket/行情推送服务消费后推给客户端。

WebSocket 服务监听的 topic：

```text
surprising.perp.candle.events.v1
```

推荐的客户端订阅路由 key：

```text
perp:kline:{symbol}:{period}
perp:kline:BTC-USDT:1m
```

推送语义：

- `status=PARTIAL`：更新当前未结束 K 线。
- `status=CLOSED`：固定当前时间桶，客户端不应再修改这根 K 线。
- 事件内容是完整快照，不是增量，所以客户端可以按 `symbol + period + openTime` 直接替换本地 K 线。
- 客户端重连时，先通过 REST/RPC 查询历史和最新 K 线，再订阅 WebSocket 增量更新。

Consumer group 注意事项：

- 如果每个 WebSocket 节点都需要给自己连接的客户端推送数据，不要让所有 WebSocket 节点共用同一个 consumer group，除非你另有共享订阅/转发层。
- 同一个 consumer group 下，一条 Kafka 消息只会被其中一个 WebSocket 节点消费。
- 常见做法是每个 WebSocket 节点使用独立 consumer group，或者由一个行情 fanout 服务消费 Kafka 后，再通过 NATS/内部 pub-sub 分发给各 WebSocket 节点。

热门合约推送控制：

- 不要把热门合约的每笔成交级 K 线更新都直接 fanout 给所有客户端。
- 对 `PARTIAL` 更新按 `symbol + period` 做 100-500 ms 合并，只推送窗口内最新快照。
- 每个时间桶最终的 `CLOSED` 更新不能丢。
- 同一个 `symbol + period` 的推送要保持顺序，避免无序并行推送同一个频道。
- WebSocket 服务需要有有界队列和背压策略，压力过大时可以丢弃中间 `PARTIAL` 快照，但要保留最新快照。

## 成交事件合约

Kafka 消息示例：

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

生产端要求：

- Kafka key 设置为 `symbol`。
- `tradeId` 在同一 symbol 下必须唯一。
- `sequence` 在同一 symbol 下必须单调递增。
- `tradeTime` 必须是撮合引擎产生的 UTC 成交时间。
- `side` 必须是真实吃单方向：`BUY`、`SELL` 或 `UNKNOWN`。

## 本地运行

在仓库根目录执行：

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-candlestick-provider -am spring-boot:run
```

查询 K 线：

```bash
curl 'http://localhost:9081/api/v1/candlestick/candles?symbol=BTC-USDT&period=1m&startTime=2026-06-30T10:00:00Z&endTime=2026-06-30T11:00:00Z&limit=100'
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
```

## 验证

在仓库根目录执行：

```bash
mvn -pl :surprising-candlestick-provider -am test
```

当前模拟测试覆盖 UTC 时间桶取整、OHLCV 聚合、乱序成交处理和重复成交抑制。

生产部署说明见 [deployment.md](../docs/deployment.md)。
