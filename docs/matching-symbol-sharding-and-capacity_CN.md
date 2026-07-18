# Matching symbol 分片和上线容量注意事项

本文记录 `surprising-matching-provider` 使用 `exchange-core2` 时的 symbol 分片模型和上线前容量检查点。重点结论：`exchange-core2` 不会为每个 symbol 自动创建独立的单生产者、单消费者队列；它使用共享 Disruptor RingBuffer，并按整数 `symbolId` 把 symbol 路由到固定数量的 matching engine shard。

## 当前实现模型

- 订单服务通过 outbox 把 `OrderCommandEvent` 写入 order commands topic，Kafka key 使用订单的 `symbol`。
- matching provider 使用一个共享 `@KafkaListener` 消费 order commands topic，要求 Kafka key 必须等于 payload 中的 `symbol`，保证同一 symbol 能稳定进入同一 Kafka partition。
- Kafka partition 由单个 listener 线程串行消费，同一 symbol 的命令不再额外获取本地 stripe lock；不同 partition 可以并行进入 exchange-core。
- `ExchangeCoreEngine` 只创建一个 `ExchangeCore` 和一个 `ExchangeApi`。业务代码不会为每个 symbol 创建单独的 `ExchangeCore`、producer 或 consumer。
- `exchange-core2` 内部把所有命令发布到同一个 Disruptor RingBuffer，底层 producer 类型是 `ProducerType.MULTI`。
- `exchange-core2` 创建固定数量的 `MatchingEngineRouter`，每个 router 持有一组 `symbolId -> orderBook`。路由规则是 `symbolId & shardMask == shardId`。

## shard 的含义

这里的 shard 指 `exchange-core2` 内部的 matching engine shard，也就是一个 `MatchingEngineRouter` 实例。它不是 Kafka partition，也不是每个 symbol 一个独立队列。

`exchange-core2` 内部仍然是单个 Disruptor RingBuffer。所有命令先进入这个共享 RingBuffer，然后经过处理链路：

```text
ExchangeApi
  -> single Disruptor RingBuffer
    -> GroupingProcessor
    -> RiskEngine R1
    -> MatchingEngineRouter[0..N-1]
    -> RiskEngine R2
    -> ResultsHandler
```

多个 `MatchingEngineRouter` 都会接收同一个 RingBuffer 上的命令事件，但只有负责该 `symbolId` 的 router 会真正处理这个 order book。底层判断逻辑等价于：

```java
private boolean symbolForThisHandler(final long symbol) {
    return (shardMask == 0) || ((symbol & shardMask) == shardId);
}
```

例如 `matchingEnginesNum = 4` 时，`shardMask = 3`：

```text
symbolId=100 -> 100 & 3 = 0 -> shard 0
symbolId=101 -> 101 & 3 = 1 -> shard 1
symbolId=102 -> 102 & 3 = 2 -> shard 2
symbolId=103 -> 103 & 3 = 3 -> shard 3
symbolId=104 -> 104 & 3 = 0 -> shard 0
```

每个 `MatchingEngineRouter` 内部维护自己的 `symbolId -> orderBook` map。同一个 symbol 只归属一个 matching shard，所以这个 symbol 的 order book 修改是串行的；多个 symbol 如果落在不同 shard，可以在 matching 阶段并行；如果落在同一个 shard，仍然会在同一个 router 上排队。

相关源码：

- `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderService.java`
- `surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OutboxPublisher.java`
- `surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/service/MatchingCommandConsumer.java`
- `surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/service/ExchangeCoreEngine.java`
- `surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/repository/MatchingSymbolRepository.java`
- Maven 依赖 `exchange.core2:exchange-core` 中的 `ExchangeCore` 和 `MatchingEngineRouter`

## 不是每个 symbol 一个消费者

上线容量评估时不要按“每个交易对一个消费者线程”理解当前系统。当前链路的并行边界是：

- Kafka topic partition 和 listener concurrency 决定入口消费并行度。
- `matching.engine.matching-engines` 决定 `exchange-core2` 内部 matching shard 数量。
- 单个 symbol 只能落到一个 matching shard，同一 symbol 的 order book 必须串行处理。
- 多个 symbol 可能落到同一个 matching shard，是否分散取决于 `symbolId` 和 `matchingEnginesNum`。

当前默认配置为：

```yaml
surprising:
  trading:
    matching:
      kafka:
        concurrency: 4
      engine:
        matching-engines: 4
        risk-engines: 2
```

入口最多由 4 个 Kafka partition 并行消费，exchange-core 使用 4 个 matching shard 和 2 个 risk shard。
同一 symbol 仍固定在一个 Kafka partition 和一个 matching shard 内串行处理。

## 上线影响判断

交易对数量增加后的主要影响：

- 每个 symbol 会增加一个 order book 和相关内存状态。
- 重启时 open order book 恢复耗时会随未完成挂单数量增加。
- 行情深度快照和 outbox 事件量会随活跃 symbol 和撮合结果增加。
- 如果大部分交易对是冷门交易对，影响一般可控。
- 如果流量集中在少数热点交易对，提高 symbol 总数不能提升热点撮合吞吐。

需要重点观察的不是 symbol 总数，而是：

- 热点 symbol 的下单和撤单 TPS。
- `order.commands` topic 的 consumer lag。
- matching provider 单条命令处理耗时和 P99 延迟。
- matching outbox backlog 和 publish 延迟。
- match result / trade 到 account provider 结算完成的端到端延迟。
- matching provider 重启后 open order book 恢复耗时。
- Kafka rebalance 或 partition 丢失后是否触发预期重启恢复。

## 扩容注意事项

如果压测发现 matching 入口或 `exchange-core2` 成为瓶颈，可以评估：

```yaml
surprising:
  trading:
    matching:
      kafka:
        concurrency: 8
      engine:
        matching-engines: 8
        risk-engines: 4
```

注意事项：

- `matching-engines` 和 `risk-engines` 在 `exchange-core2` 中要求是 2 的幂。
- Kafka listener concurrency 需要配合 topic partition 数，否则提高 concurrency 不会带来有效并行。
- 增加 `matching-engines` 后，要检查热点 symbol 的 `symbolId` 是否分散到不同 shard。路由取决于 `symbolId & (matchingEnginesNum - 1)`。
- 如果 BTC-USDT、ETH-USDT 等热点 symbol 落到同一个 shard，热点仍然会互相排队。
- 不要在运行中随意调整 Kafka partition 数。对以 symbol 为 key 的状态链路，partition 数变化可能改变 symbol 到 partition 的映射，需要按产品线做重放或维护窗口方案。
- Kafka partition reassignment 后，matching provider 会要求重启恢复，避免 running JVM 中 exchange-core order book 与 Kafka replay 状态不一致。

## 压测建议

上线前压测应优先模拟真实热点，而不是平均打满所有 symbol：

- 至少压测 1 到 3 个热点 symbol 的高频下单、撤单、IOC/market taker 成交流量。
- 同时保留若干低频 symbol，验证多 symbol 状态和 outbox 不互相污染。
- 做市进程在交易链路压测中保持运行，覆盖真实 maker/taker 交互。
- 每轮压测后核对用户账号、做市账号、挂单、成交、持仓和资金守恒。
- 如调整 Kafka topic、product line topic 或 matching concurrency，需要同步检查 `ProductTopicNames`、topic partition、consumer group、key 校验和 WebSocket fanout。

可优先使用现有脚本：

```bash
scripts/matching-engine-benchmark.sh
scripts/product-line-api-flow-smoke.sh
scripts/product-line-funds-reconcile.sh
scripts/live-runtime-trading-reconciliation.sh
scripts/kafka-trading-smoke.sh
```

## 结论

几十到上百个交易对可以先按当前架构上线评估，但不能把交易对数量等同于撮合并行度。当前默认
`kafka.concurrency: 4`、`matching-engines: 4`、`risk-engines: 2` 是多交易对并行基线；生产上线前仍必须
用热点 symbol 压测确认 Kafka lag、matching 延迟、outbox backlog、account 结算延迟和资金对账结果。
需要继续提升吞吐时，按 Kafka partition、listener concurrency、`matching-engines`、热点 `symbolId`
分布四项一起调整和验证。
