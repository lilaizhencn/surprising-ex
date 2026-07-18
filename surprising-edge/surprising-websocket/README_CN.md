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

私有条件单订阅示例：

```json
{"op":"subscribe","id":"t1","channel":"triggerOrders","symbol":"BTC-USDT","productLine":"LINEAR_PERPETUAL"}
```

私有风险订阅示例：

```json
{"op":"subscribe","id":"r1","channel":"positionRisk","symbol":"BTC-USDT"}
{"op":"subscribe","id":"r2","channel":"accountRisk"}
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
| `triggerOrders` | 否 | 可选 `symbol` | `surprising.perp.trigger-order.events.v1` |
| `matches` | 否 | 可选 `symbol` | `surprising.perp.match.results.v1`, `surprising.perp.match.trades.v1` |
| `positions` | 否 | 可选 `symbol` | `surprising.account.position.events.v1` |
| `positionRisk` | 否 | 可选 `symbol` | `surprising.risk.position.events.v1` |
| `accountRisk` | 否 | 可选 `symbol` 会按通配符处理 | `surprising.risk.account.events.v1` |

私有订阅不传 `symbol` 时使用通配符 `*`，表示接收该认证用户的所有相关事件。

`triggerOrders` 推送完整的 `TriggerOrderUpdatedEvent` 包装，包含 `eventId`、`productLine`、`order`、`eventTime` 和 `traceId`。客户端把 `PENDING`/`TRIGGERING` 快照保留在开放条件单列表，收到终态立即移除；重复或乱序 `eventId` 必须忽略，重连后要重新拉 REST 开放条件单快照。

## 盘口深度推送链路

盘口深度由 matching 基于 live exchange-core L2 book 生成：

```text
order command
  -> exchange-core 修改订单簿
  -> matching 把最新全量 L2 快照交给按 symbol 隔离的行情 publisher
  -> 独立 Kafka producer 发布 surprising.perp.orderbook.depth.v1
  -> websocket 节点消费事件，并推送 channel=depth
```

每条 depth 都是全量 `SNAPSHOT`。每个 symbol 有独立的 latest-only 待发送槽位，热点 symbol 的中间快照可以被合并，但不会影响其他 symbol。客户端收到快照后整体替换该 symbol 的本地盘口。

稳妥的客户端流程：

1. 订阅 `depth`，并先缓存该 symbol 的事件。
2. 拉取 `GET /api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50`。
3. 用 REST 快照初始化本地盘口。
4. 收到该 symbol 更新的 WebSocket 快照后整体替换。
5. 重连时丢弃本地盘口，重新拉 REST 快照并重新订阅。

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

`positions` 是账户持仓状态频道，不是实时 PnL 计算器。它在账户结算后推送签名数量、开仓均价、已实现盈亏和保证金模式。后端权威的未实现盈亏、权益、维持保证金和保证金率要订阅：

- `positionRisk`：单持仓风险快照，包含 `markPriceTicks`、`notionalUnits`、`unrealizedPnlUnits`、`maintenanceMarginUnits`、`positionMarginUnits`、`marginRatioPpm` 和 `status`。
- `accountRisk`：`userId + settleAsset` 维度账户风险快照，包含钱包余额、总未实现盈亏、权益、维持保证金、保证金率和状态。

前端可以把 `positions` 和公共 `mark` 更新组合起来做更高频的视觉插值，但和爆仓系统一致的权威值来自 risk-provider 的 `positionRisk` / `accountRisk` 事件。
由账户持仓更新触发的风险事件会尽量携带原交易 `traceId`；定时兜底扫描产生的风险事件可能没有 traceId。

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

- 开发和小规模部署可以使用 `surprising-edge-provider`，在一个进程里同时提供 REST 和 `/ws/v1`。
- 生产环境如果长连接很多，继续单独部署 `surprising-websocket-provider`，让 WebSocket 独立扩容。
- WebSocket 节点至少部署 2 个。
- 每个 WebSocket 节点必须使用唯一 Kafka consumer group，例如默认值 `surprising-websocket-${HOSTNAME:${random.uuid}}`。
- 不要让所有 WebSocket pod 共用一个 group。共用 group 会导致每条 Kafka 记录只被一个 pod 收到，连接在其他 pod 上的客户端会漏掉公共行情。
- 生产环境建议显式配置稳定的 pod/node 值，例如 `surprising-websocket-${POD_NAME}`。每次重启都使用纯随机 group 会在 Kafka 里留下过期 group，让 consumer lag 看板变得很吵。
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
      max-poll-records: 1000
      order-book-depth-topic: surprising.perp.orderbook.depth.v1
      position-events-topic: surprising.account.position.events.v1
      account-risk-events-topic: surprising.risk.account.events.v1
      position-risk-events-topic: surprising.risk.position.events.v1
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

如果未来公共行情推送规模非常大，可以再加 NATS 或独立 market-data fanout 层。当前设计先保持简单和正确：Kafka 保存权威事件流，每个 WebSocket 节点做本地 fanout。

## 运维注意事项

- 后台指标接口：`GET /api/v1/admin/websocket/metrics`。该接口要求 gateway 注入 `X-Admin-User-Id`，返回本节点连接数、认证/匿名连接数、订阅数、唯一 topic 数和频道分布。
- 服务同时暴露 Micrometer Gauge：`surprising.websocket.connections.active`、`surprising.websocket.connections.authenticated`、`surprising.websocket.subscriptions.active`、`surprising.websocket.topics.active`，可由 `/actuator/prometheus` 抓取。
- 监控 WebSocket 连接数、发送队列压力、Kafka consumer lag 和 send timeout 关闭次数。
- consumer lag 告警应只看活跃 WebSocket 节点 group。历史随机 group 应删除，或在看板里过滤。
- Kafka topic 继续按 `symbol` 分区。
- 前端应先用 REST 获取快照，再用 WebSocket 订阅增量。
- 客户端要发送周期性 `ping`，断线后用指数退避重连。
- 每次重连成功后，客户端必须重新订阅所有频道。

## 构建和测试

```bash
mvn -pl :surprising-websocket-provider -am test
mvn -pl :surprising-websocket-provider -am spring-boot:run
```
