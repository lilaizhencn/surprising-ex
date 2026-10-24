# surprising-gateway WebSocket


面向前端的 WebSocket 推送服务。

它接收 Realtime 经 Aeron 转发的 Core 状态、成交和盘口，以及价格、K 线等行情，在本节点维护订阅关系并推给当前节点的客户端。

Product Core 的当前状态和恢复只由 Aeron Cluster 的 log、snapshot 与 runtime 负责。Core Export、历史投影、审计和公共
成交/盘口历史不通过 WebSocket 回放；实时成交与盘口由 RealtimeWebSocketBridge 转发，gateway 不在交易 owner 热路径增加 fanout。

## 模块

WebSocket 能力已经合并进 `surprising-gateway`，与 REST gateway 共用一个 Spring Boot 进程、端口和部署单元。

## 入口

- HTTP 端口：`9094`
- WebSocket 路径：`/ws/v1`

公共 K 线订阅示例：

```json
{"op":"subscribe","id":"c1","channel":"candles","symbol":"BTC-USDT","period":"1m"}
```

私有持仓订阅示例：

```json
{"op":"authenticate","id":"a1","token":"<access-token>"}
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

私有频道必须先完成认证。客户端通过第一条 `authenticate` 命令在 WebSocket 消息体中发送 access token，收到 `op=authenticated` 后再发送私有订阅；token 不得放入 URL query。握手身份头和 URL 用户 ID 不作为认证依据；HTTP 和 WebSocket 均调用交易 `AuthService` 检查登录。

## 频道

| 频道 | 公共频道 | 必填字段 | 数据来源 |
| --- | --- | --- | --- |
| `candles` | 是 | `symbol`, `period` | `surprising.linear-perp.candle.events.v1` |
| `index` | 是 | `symbol` | `surprising.linear-perp.price.events.v1` (`eventType=INDEX_PRICE`) |
| `mark` | 是 | `symbol` | `surprising.linear-perp.price.events.v1` (`eventType=MARK_PRICE`) |
| `funding` | 是 | `symbol` | `surprising.linear-perp.funding.rate.v1` |
| `orders` | 否 | 可选 `symbol` | Core `ORDER` 帧，经 Aeron 桥接 |
| `triggerOrders` | 否 | 可选 `symbol` | Core `TRIGGER` 帧，经 Aeron 桥接 |
| `executionReports` | 否 | 可选 `symbol` | Core `EXECUTION` 帧，经 Aeron 桥接 |
| `positions` | 否 | 可选 `symbol` | Core `POSITION` 帧，经 Aeron 桥接 |
| `positionRisk` | 否 | 可选 `symbol` | Core `RISK` 帧，经 Aeron 桥接 |
| `accountRisk` | 否 | 可选 `symbol` 会按通配符处理 | 同一 Core `RISK` 帧，经 Aeron 桥接 |

私有订阅不传 `symbol` 时使用通配符 `*`，表示接收该认证用户的所有相关事件。

风险更新由启用的`RealtimeWebSocketBridge`发布现有`VersionedEvent(version, entityId, value)`，快照仍包含`positionRisks`，客户端按原版本栅栏维护风险实体。旧Kafka账户/持仓风险事件没有当前生产者，已删除监听器、31字段事件模型及专属topic配置；关闭原生实时通道不会切换到旧风险Kafka投递。风险HTTP查询及真实Core风险值保留。本轮只编译相关模块，未运行推送或UI场景。

公共Kafka fanout消费者只通过唯一构造接收显式`WebSocketProperties`，不再留三参测试构造或固定回退到线性永续的配置工厂。测试自行提供产品配置，价格、资金费和跨产品拒绝场景保留。

私有增量沿用`VersionedEvent(version, entityId, value)`。`triggerOrders`的`value`是Core条件单视图列表；客户端保留`PENDING`/`TRIGGERING`，终态移除，并按原版本栅栏处理重复/乱序。重连后先建立现有用户快照。旧订单、条件单、持仓Kafka事件及派生成交报告已删除，没有当前生产者的topic入口、旧版本/修订约束和Redis持仓包装一并清理；Core金融字段及REST模型保留。

## 持仓推送链路

`RealtimeWebSocketBridge`按EXECUTION和TRADE实际二进制帧长度解码；这两类帧不携带`instrumentChangeId`。六产品HTTP集成用例统一等待真实Core推送，保留做市/用户两套订阅、就绪快照、划转守恒、成交/撤单/平仓、预占释放及用户隔离断言，删除手工Kafka撤单事件和旧模式开关。本轮仅编译这些测试源码，没有运行用例。

持仓只能在账户结算后推送：

```text
Matcher 原生成交事实
  -> 账户 Lane 应用余额、预占、保证金、PnL、手续费和持仓
  -> Owner 收齐交接并按序提交
  -> Core 原生 POSITION 帧经 Aeron 路由
  -> WebSocket 桥接发布版本化增量，用户快照仍从原生读视图建立
```

这样前端不会看到从原始撮合结果推导出来、但账户状态还没落定的持仓。

客户端按现有`version`与快照栅栏处理重复、乱序和恢复；SQL历史投影不裁决在线持仓。

`positions` 是账户持仓状态频道，不是实时 PnL 计算器。它在账户结算后推送签名数量、开仓均价、已实现盈亏和保证金模式。后端权威的未实现盈亏、权益、维持保证金和保证金率要订阅：

- `positionRisk`：单持仓风险快照，包含 `markPriceTicks`、`notionalUnits`、`unrealizedPnlUnits`、`maintenanceMarginUnits`、`positionMarginUnits`、`marginRatioPpm` 和 `status`。
- `accountRisk`：同一原生持仓风险对象，发往该用户的通配订阅。汇总账户风险通过现有HTTP账户风险接口读取。

前端可以把`positions`与公共`mark`组合估值；原生风险值来自Core，生命周期HTTP查询按当前Core快照聚合。

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

- WebSocket 与 REST gateway 共用部署单元；扩容时按 Gateway 实例整体扩容。
- WebSocket 节点至少部署 2 个。
- 每个 WebSocket 节点必须使用唯一 Kafka consumer group，例如默认值 `surprising-websocket-${HOSTNAME:${random.uuid}}`。
- 不要让所有 WebSocket 进程共用一个 group。共用 group 会导致每条 Kafka 记录只被一个节点收到，连接在其他节点上的客户端会漏掉公共行情。
- 生产环境建议显式配置稳定的 node 值，例如 `surprising-websocket-${HOSTNAME}`。每次重启都使用纯随机 group 会在 Kafka 里留下过期 group，让 consumer lag 看板变得很吵。
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

公共成交、盘口和历史行情需要重新定义事件生产者后再接入；这部分暂不作为交易链路依赖。

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
mvn -pl :surprising-gateway -am test
mvn -pl :surprising-gateway -am spring-boot:run
```

## 盘口快照与增量

`RealtimeWebSocketBridge.receive` 接收 Core 的不可变盘口视图后，交给
`SubscriptionRegistry.publishDepth` 在 WebSocket 边界按连接生成增量；Core 状态及内部传输不变。
`depth` 必须指定 `productLine` 和 `symbol`。每次订阅第一条 `value` 是
`{updateType:"SNAPSHOT",sequence:"...",previousSequence:null,depth:50,bids:[],asks:[]}`，
覆盖本地买卖各最多 50 档；空快照也必须覆盖。
后续 `DELTA` 只包含变化的价格档位，`quantitySteps` 是该档绝对数量，零表示删除，
`orderCount` 是该档当前订单数。`previousSequence` 指向该连接上一次成功入队的盘口序号，
不要求数值连续加一。无档位变化时也可以发送空增量推进序号。

客户端必须按序应用所有增量，不能只保留同一渲染批次的最后一条。
序号断档后重新获取全量基线，重连或重新订阅后重新接收首条快照。
网关仅保留每个订阅连接最后成功入队的不可变盘口引用，退订和断线即删除；
队列背压时断开连接，避免静默丢弃档位增量。不同产品线及交易对的基线独立。

ORDER 与 `/api/v1/realtime/{productLine}/state` 的 `openOrders` 使用同一个
`CoreOrderStateView`。新增 `executedValueTicks`（累计实际成交价 ticks × 成交 steps，字符串）、
`averagePriceTicks`（数量加权平均 ticks，允许小数、无成交为空）、`cumulativeFeeUnits`（累计手续费）。
这些是绝对值；客户端按订单 ID 和版本覆盖旧值，不把多次推送累加。创建时间不会随成交或撤单变更。

## 公共成交标识与查询来源（2026-10-19）

`RealtimeWebSocketBridge.publicTrade` 解码 TRADE 后向 `data.value` 写入 `tradeId`、`instrumentId`、
`priceTicks`、`quantitySteps`、`coreSequence`、`side`、`eventTime`。tradeId 使用原帧 entityId，
与 `ReliableTradeKafkaSink` / 最近成交 REST 的标识一致。`coreSequence` 是 matcher 序号，
不能与 REST 的成交导出 sequence 比较；客户端以 tradeId 去重并精确比较其中的日志位置及成交索引。
盘口 WS 的 SNAPSHOT/DELTA 格式、previousSequence 和产品隔离规则保持不变。

公共盘口 REST 及 BBO 参考价改为读取 realtime Router 的 Valkey 视图，无回查 Core 分支。
初始化/过期/源 epoch 改变时返回 503；盘口刷新依靠原有 Router 需求租约与异步 BOOK 输出。

## C2C 聊天

C2C 与行情共用 `/ws/v1`，复用 authenticate、ping、发送队列和客户端重连。聊天由 `c2c.*` 命令及频道隔离，不作为某个交易产品线的行情订阅，也不流入 Core。完整消息、附件和 R2 配置说明见 [C2C 聊天整合](../docs/c2c-chat.md)。
