# surprising-websocket

[English](README.md)

面向前端的 WebSocket 推送服务。

这个服务不是计算服务。它消费 Kafka 领域事件，在本节点内存里维护订阅关系，只把实时消息推给连接到当前节点的客户端。

## 模块

- `surprising-websocket-api`：WebSocket 频道、客户端命令和服务端消息模型。
- `surprising-websocket-provider`：Spring Boot WebSocket 服务和 Kafka fanout consumer。

## 入口

- HTTP 端口：`9093`
- WebSocket 路径：`/ws/v1`

公共 K 线订阅示例：

```json
{"op":"subscribe","id":"c1","channel":"candles","symbol":"BTC-USDT","period":"1m"}
```

公共盘口深度订阅示例：

```json
{"op":"subscribe","id":"d1","channel":"depth","symbol":"BTC-USDT"}
```

私有持仓订阅示例：

```json
{"op":"subscribe","id":"p1","channel":"positions","symbol":"BTC-USDT"}
```

私有频道必须有已认证用户 id。生产环境应由 ingress/auth 层注入 `X-User-Id`；本地调试可以用 query 参数传同样的值，但这不是生产安全模型。

## 频道

| 频道 | 公共频道 | 必填字段 | 来源 topic |
| --- | --- | --- | --- |
| `candles` | 是 | `symbol`, `period` | `surprising.perp.candle.events.v1` |
| `trades` | 是 | `symbol` | `surprising.perp.trade.events.v1` |
| `depth` | 是 | `symbol` | `surprising.perp.orderbook.depth.v1` |
| `index` | 是 | `symbol` | `surprising.perp.index.price.v1` |
| `mark` | 是 | `symbol` | `surprising.perp.mark.price.v1` |
| `funding` | 是 | `symbol` | `surprising.perp.funding.rate.v1` |
| `orders` | 否 | 可选 `symbol` | `surprising.perp.order.events.v1` |
| `matches` | 否 | 可选 `symbol` | `surprising.perp.match.results.v1`, `surprising.perp.match.trades.v1` |
| `positions` | 否 | 可选 `symbol` | `surprising.account.position.events.v1` |

私有订阅不传 `symbol` 时使用通配符 `*`，表示接收该认证用户的所有相关事件。

## 盘口深度推送链路

盘口深度由 matching 基于 live exchange-core L2 book 生成：

```text
order command
  -> exchange-core 修改订单簿
  -> matching 对比最新 L2 book 和上一份内存 book image
  -> matching outbox 发布 surprising.perp.orderbook.depth.v1
  -> websocket 节点消费事件，并推送 channel=depth
```

每个 symbol 的第一条 depth 是 `SNAPSHOT`。之后通常是 `DELTA`，只包含变化的价格档。delta 档位是价格档绝对状态，不是数量差值；客户端要直接替换本地档位，`quantitySteps=0` 时删除该价格档。

稳妥的客户端流程：

1. 订阅 `depth`，并先缓存该 symbol 的事件。
2. 拉取 `GET /api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50`。
3. 用 REST 快照和其中的 `sequence` 初始化本地盘口。
4. 只应用 `previousSequence` 等于本地最后 sequence 的缓存/新增 delta。
5. 重连或 sequence 断号时，丢弃本地盘口，重新拉 REST 快照并重新订阅。

## 持仓推送链路

持仓只能在账户结算后推送：

```text
matching trade
  -> account 消费撮合成交
  -> account 在一个 DB 事务里更新余额、保证金、PnL、手续费和持仓
  -> account 写 account_outbox_events 的 POSITION_UPDATED 行
  -> account outbox publisher 发送 surprising.account.position.events.v1
  -> websocket 节点消费事件，并推给匹配的私有订阅
```

这样前端不会看到从原始撮合结果推导出来、但账户状态还没落定的持仓。

account outbox 是至少一次投递。客户端更新本地状态时应把 `eventId` 和 `tradeId` 当成去重/版本提示。
私有持仓消息在可用时也会携带原始交易链路的 `traceId`，方便客服和运维把 WebSocket 推送关联到订单、撮合成交和账户结算行。

## 用户状态和节点选择

WebSocket 用户状态是本地、临时状态：

- `ClientConnection` 保存当前 socket、认证后的 `userId` 和有界发送队列。
- `SubscriptionRegistry` 保存 `sessionId -> subscriptions` 和 `subscription -> local sessions`。
- WebSocket 内存里不保存账户余额、持仓、保证金或订单的权威状态。

用户相关事件不是“精准投递给某个节点”，而是每个节点收到后做本地过滤：

```text
position event on Kafka
  -> 每个 WebSocket 节点用自己的 consumer group 消费
  -> 每个节点检查本机是否有 userId + channel + symbol 的订阅
  -> 只有托管了匹配 session 的节点推给客户端
  -> 其他节点本地丢弃
```

如果同一用户两个设备分别连到两个节点，两个节点都可以把同一账户更新推给各自本地 session。某个节点宕机后，客户端连到任意健康节点并重新订阅即可。

## 水平扩展

- WebSocket 节点至少部署 2 个。
- 每个 WebSocket 节点必须使用唯一 Kafka consumer group，例如默认值 `surprising-websocket-${HOSTNAME:${random.uuid}}`。
- 不要让所有 WebSocket pod 共用一个 group。共用 group 会导致每条 Kafka 记录只被一个 pod 收到，连接在其他 pod 上的客户端会漏掉公共行情。
- 不需要跨节点 session 状态。每个节点只维护本机 WebSocket session 和订阅表。
- 负载均衡器可以把新连接分散到不同节点。已经建立的 WebSocket TCP 连接自然固定在一个节点；断线重连后客户端必须重新订阅。
- 公共行情事件会被每个节点消费，然后按本地订阅过滤。
- 私有事件也会被每个节点消费，但发送前会按认证 `userId` 再过滤。
- 每个连接都有有界发送队列。慢客户端会被关闭，不能让单个连接拖垮本节点 fanout。
- 未结束 K 线会按 `surprising.websocket.fanout.candle-partial-coalesce-window` 合并推送；已结束 K 线立即推送。

## 配置

```yaml
surprising:
  websocket:
    kafka:
      bootstrap-servers: localhost:9092
      group-id: surprising-websocket-${HOSTNAME:${random.uuid}}
      concurrency: 2
      order-book-depth-topic: surprising.perp.orderbook.depth.v1
      position-events-topic: surprising.account.position.events.v1
    session:
      max-subscriptions: 200
      outbound-queue-capacity: 1000
      send-timeout: 5s
    security:
      user-id-header: X-User-Id
      allowed-origins:
        - "https://app.example.com"
        - "https://m.example.com"
    fanout:
      candle-partial-coalesce-window: 250ms
```

默认 `allowed-origins: ["*"]` 方便本地开发。生产环境应配置精确 HTTPS Origin。

如果未来公共行情推送规模非常大，可以再加 Redis、NATS 或独立 market-data fanout 层。当前设计先保持简单和正确：Kafka 保存权威事件流，每个 WebSocket 节点做本地 fanout。

## 运维注意事项

- 监控 WebSocket 连接数、发送队列压力、Kafka consumer lag 和 send timeout 关闭次数。
- Kafka topic 继续按 `symbol` 分区。
- 前端应先用 REST 获取快照，再用 WebSocket 订阅增量。
- 客户端要发送周期性 `ping`，断线后用指数退避重连。
- 每次重连成功后，客户端必须重新订阅所有频道。

## 构建和测试

```bash
mvn -pl :surprising-websocket-provider -am test
mvn -pl :surprising-websocket-provider -am spring-boot:run
```
