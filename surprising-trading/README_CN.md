# surprising-trading

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 合约交易模块。当前已实现 `surprising-order-provider` 和 `surprising-matching-provider`：订单入口、instrument 规则校验、幂等落库、Kafka 撮合命令发布、exchange-core 真实订单簿撮合、撮合结果和成交事件输出。

## 模块

- `surprising-trading-api`：订单 RPC 合约、DTO、Kafka command/event 模型。
- `surprising-order-provider`：订单入口 provider。
- `surprising-matching-provider`：基于 `exchange-core` 的撮合 provider。

## long 定点数模型

订单入口不使用 `BigDecimal`。API、数据库、Kafka command 都使用 long：

- `priceTicks`：价格 tick 个数，展示价格 = `priceTicks * price_tick_units / quote_asset_scale`。
- `quantitySteps`：数量 step 个数，展示数量 = `quantitySteps * quantity_step_units / base_asset_scale`。
- `MARKET` 订单要求 `priceTicks = 0`。
- `MARKET` 订单只允许 `IOC` 或 `FOK`。
- notional 校验按 `contract_type` 分支。U 本位线性合约校验 `priceTicks * quantitySteps * notional_multiplier_units`；币本位反向合约校验 `quantitySteps * notional_multiplier_units`，因为 multiplier 表示每个合约 step 的报价币面值。两条路径都用 `Math.multiplyExact` 防止 long 溢出。
- 市价单用 `markPriceTicks +/- marketMaxSlippagePpm` 做提交 exchange-core 前的价格保护。订单入口使用同一个 mark 派生的可成交区间做风控：U 本位线性合约无论 BUY/SELL 都按上边界冻结初始保证金，币本位反向合约按下边界冻结，因为价格越低所需抵押越高。
- instrument version 保存产品默认 maker/taker ppm 费率；订单入口会叠加用户/VIP/做市覆盖后，把本订单实际费率快照写入 `trading_orders`，成交后由 account provider 按快照结算。

例子：`BTC-USDT` 的 `price_tick_units = 10000000`、`quantity_step_units = 100000`，USDT scale 为 `100000000`，BTC scale 为 `100000000`。

- `priceTicks = 650000` 表示价格 `65000.0`。
- `quantitySteps = 10` 表示数量 `0.01 BTC`。

这和 `exchange-core` 的 long 输入模型一致，撮合服务可以直接把 `priceTicks`、`quantitySteps` 传给 order book。
交易链路必须保持这个约束：订单、成交、保证金、PnL、资金费结算的 Java 代码不要转换成 `BigDecimal`。外部行情模块可以存展示用 decimal 值，但交易执行和账户记账必须使用缩放后的 `long`。
允许使用小数的地方只在系统边界：外部行情/汇率解析、管理后台录入、REST 展示和报表输出。进入订单、撮合、账户、风控、强平、资金费、保险基金、ADL 前，必须先转换为 instrument 定义的 tick、step、ppm 或 asset unit。
`CoreFixedPointArchitectureTest` 会扫描 trading、account、risk、liquidation、funding、insurance、ADL 的 main Java 源码，如果这些核心路径引入 `BigDecimal`、`double` 或 `float`，全量测试会失败。
关键核心链路聚合使用 checked long addition。matching 成交总量和 reduce-only 待平数量溢出时会失败，而不是回绕成更小的值。

## 核心链路

```text
client / internal gateway
  -> POST /api/v1/trading/orders
  -> surprising-order-provider
  -> PostgreSQL trading_orders + trading_order_events + trading_outbox_events
  -> outbox publisher
  -> surprising.perp.order.commands.v1
  -> surprising-matching-provider / exchange-core
  -> trading_match_results / trading_match_trades
  -> surprising.perp.match.results.v1 / surprising.perp.match.trades.v1
```

订单 provider 不直接撮合，也不直接承担 WebSocket 推送。订单状态推送服务应该消费 `surprising.perp.order.events.v1`、`surprising.perp.match.results.v1` 后独立 fanout。

## 保证金模式

订单、撮合 command、成交事件、账户 reservation 和账户持仓现在都会携带 `marginMode`。
默认值是 `CROSS`。`ISOLATED` 已经进入订单入口、撮合事件、账户保证金、持仓、风控快照、资金费和强平链路。
全仓亏损、手续费和资金费可以使用全仓可用余额以及全仓持仓保证金兜底；逐仓只消耗同一 `userId + symbol + asset + marginMode`
下的逐仓持仓保证金，不会动用其他 symbol 或全仓余额。当前仍未实现用户手动追加/减少逐仓保证金、持仓模式切换约束和 hedge
`positionSide`，因此前端应按单向净持仓展示。

## 手续费

- `init.sql` 默认 `BTC-USDT`、`ETH-USDT` 使用 maker `200 ppm`、taker `500 ppm`，即 `0.02% / 0.05%`。
- `trading_fee_schedules` 可配置用户全局或单 symbol 覆盖，`source_type` 支持 `USER_OVERRIDE`、`VIP`、`MARKET_MAKER`、`PROMOTION`、`RISK_OVERRIDE`。
  单 symbol 优先于用户全局，最后回退 instrument 默认费率。
- 管理接口：`POST /api/v1/admin/trading/fees/schedules` 新增/更新费率，`POST /api/v1/admin/trading/fees/schedules/{feeScheduleId}/disable` 禁用费率，
  `GET /api/v1/admin/trading/fees/schedules` 查询配置。
- 业务查询：`GET /api/v1/trading/fees/effective?userId=...&symbol=...` 返回当前最终 maker/taker ppm 和来源，例如 `INSTRUMENT`、`VIP_SYMBOL`。
- 订单接受时会把最终 `maker_fee_rate_ppm`、`taker_fee_rate_ppm` 写入 `trading_orders`。后续用户 VIP 等级或活动费率变化，不会重解释已接受挂单。
- account provider 结算成交时按订单快照写 `TRADE_FEE`，并在 ledger 保存 `trade_id`、`order_id`、`symbol`、`fee_rate_ppm`。

## 杠杆设置

- 用户杠杆配置保存在 `trading_leverage_settings`，唯一键是 `user_id + symbol + margin_mode`。
- `leveragePpm` 使用 ppm 表示杠杆：`10_000_000 = 10x`，`100_000_000 = 100x`。
- 用户接口：`POST /api/v1/trading/leverage/settings` 设置杠杆，`GET /api/v1/trading/leverage/settings?userId=...&symbol=...&marginMode=...` 查询当前设置。
- 设置杠杆时会先校验不能超过 instrument 当前版本的 `max_leverage_ppm`。
- 下单冻结保证金时还会按订单名义价值和当前同 `marginMode` 持仓名义价值选择 `instrument_risk_brackets` 档位；如果用户设置杠杆超过该档 `max_leverage_ppm`，订单会拒绝。
- 有效初始保证金率 = `max(用户杠杆换算出的保证金率, 风险档位 initial_margin_rate_ppm)`。未设置用户杠杆时，按当前风险档位最大杠杆/初始保证金率冻结。

## TraceId 链路追踪

- 前端或 BFF 可以传 `X-Trace-Id`；未传时 gateway/order 入口会自动生成。
- `surprising-order-provider` 只在当前 HTTP 请求内用 ThreadLocal 保存 traceId，请求结束会清理；进入异步链路前会显式写入 `OrderEvent` 和 `OrderCommandEvent`。
- outbox payload 会携带 traceId，所以 outbox 重试和 Kafka 重放仍然保持同一个请求身份。
- `surprising-matching-provider` 必须把 `OrderCommandEvent.traceId` 原样复制到每个 `MatchResultEvent` 和撮合产生的 `MatchTradeEvent`，撮合层不要重新生成 traceId。
- `surprising-account-provider` 会把 `MatchTradeEvent.traceId` 写入 `PositionUpdatedEvent`，这样私有 WebSocket 持仓推送也能和订单入口、撮合审计行关联。
- PostgreSQL 的 `trading_order_events`、`trading_match_results`、`trading_match_trades` 都保存 `trace_id`。生产日志建议同时输出 `traceId`、`orderId`、`commandId`、`tradeId`、symbol 和 Kafka topic/partition/offset。

## 保证金冻结

普通开仓/挂单在 `surprising-order-provider` 内完成初始保证金冻结：

- 从 instrument 当前版本读取 `contract_type`、`initial_margin_rate_ppm`、`notional_multiplier_units`、`price_tick_units`、`settle_asset` 和资产 scale。
- 在 Java `OrderMarginMath` 中换算 `initialMarginUnits`：输入和输出都是 long ticks/steps/asset units，中间乘除使用精确整数计算，溢出会拒绝而不是回绕。
- 下单事务会先插入 `trading_orders`，确认没有命中 `clientOrderId` 幂等冲突后，再把 `account_balances.available_units` 转入 `locked_units`，并写入 `account_margin_reservations`。
- `account_margin_reservations.order_id` 有外键指向 `trading_orders.order_id`，防止出现没有订单的幽灵冻结。
- 如果订单已插入但保证金不足，订单会在同一事务内改为 `REJECTED`，只发布拒单事件，不发布撮合命令。

`reduceOnly=true` 的平仓和强平订单不冻结新增保证金。
matching 保证金释放只允许 `reduceOnly=true` 订单缺失 reservation。非 reduce-only 订单缺少 `account_margin_reservations` 是会计不变量错误，必须失败，不能静默继续。

用户主动平仓订单在发布撮合前会做 reduce-only 安全校验：

- 多仓只能提交 reduce-only `SELL`。
- 空仓只能提交 reduce-only `BUY`。
- 已存在的未完成 reduce-only 平仓单会占用可平数量，新订单数量加上已有待平数量不能超过当前持仓。
- 待平数量聚合使用 checked long addition；如果溢出，会拒绝订单或回滚强平事务，不能静默扩大可平容量。
- 校验会用 PostgreSQL `FOR UPDATE` 锁住当前 `account_positions` 行和相关未完成 `trading_orders` 行，多节点 order-provider 并发下也不会超额平仓。
- 持仓被成交、强平或 ADL 改变后，account-provider 会重新检查未完成 reduce-only 挂单，撤销反向、版本不一致或超过新持仓容量的订单。

`surprising-matching-provider` 在撮合拒绝时释放全部冻结；撤单成功或 immediate order 终态时按未成交比例释放未使用保证金。`surprising-account-provider` 消费成交后，按实际成交价计算开仓保证金，把这部分从订单冻结迁移到 `account_position_margins`，并释放委托价改善或市价风险边界多冻结的差额。线性合约市价单即使是 SELL 也故意按上边界冻结，因为 SELL 市价单可能吃到高于 mark 的买一挂单。平仓成交释放旧持仓保证金，不消耗新的订单保证金。

保证金释放使用 PostgreSQL 条件更新：`locked_units >= releaseUnits`。如果冻结余额不足，matching 事务会失败并触发 command failure 重启保护，等待进程重启后通过 DB 恢复订单簿和 Kafka 重放继续处理。这里不能用 `GREATEST(0, locked_units - releaseUnits)` 静默扣减，否则会在异常状态下把不存在的冻结金额释放成可用余额。

## Instrument 规则来源

订单入口动态读取 `surprising-instrument` 写入 PostgreSQL 的当前版本：

- `instrument_current_versions`
- `instruments`

instrument 已经存储和 exchange-core 对齐的 long 规则边界：

- `min_quantity_steps -> minQuantitySteps`
- `max_quantity_steps -> maxQuantitySteps`
- `min_notional_units`、`max_notional_units`、`notional_multiplier_units` 保持 long 原始单位，并按 `contract_type` 校验。
- `LINEAR_PERPETUAL` 订单 notional = `priceTicks * quantitySteps * notional_multiplier_units`。
- `INVERSE_PERPETUAL` 订单面值 = `quantitySteps * notional_multiplier_units`。
- `max_leverage_ppm` 和 `instrument_risk_brackets` 会参与下单保证金冻结；风险档位越高，允许杠杆越低，最低初始保证金率越高。
- `maker_fee_rate_ppm` 和 `taker_fee_rate_ppm` 不传给 exchange-core。instrument 提供默认费率，`trading_fee_schedules` 可提供用户全局或单 symbol 覆盖，订单接受时会把最终费率固化到 `trading_orders`。

所以交易模块 Java 代码仍然保持 long-only。

## Instrument 版本绑定

- 每个已接受订单都会保存校验时使用的 `instrument_version`。
- `reduceOnly` 平仓单绑定当前持仓版本，因此用户可以安全平掉旧版本持仓。
- `OrderCommandEvent` 携带 `instrumentVersion`；撮合结果保留 taker command 版本。
- `MatchTradeEvent` 同时携带 `takerInstrumentVersion` 和 `makerInstrumentVersion`，账户结算时可以按双方各自合约公式处理。
- matching provider 遇到同一 symbol 已有不同 `instrument_version` 的开放订单时，会拒绝新的 `PLACE` command，避免 exchange-core 在同一个 book 里撮合不兼容的 tick/multiplier 版本。
- 运维上，tick size、quantity step、multiplier、contract type、settlement asset 这类核心字段变更前，应先暂停交易并清理开放订单。

## 幂等和多节点

- `trading_orders_user_client_order_uidx` 保证同一用户 `clientOrderId` 幂等。
- 下单插入只允许这个部分 `(userId, clientOrderId)` 唯一键冲突被幂等跳过。`orderId` 或其他唯一键冲突必须失败，不能被当成请求重放。
- 幂等冲突发生在保证金冻结前；重复请求只返回已存在订单，不会创建新的 reservation 或重复锁定余额。
- `trading_sequences` 使用 PostgreSQL 原子 `INSERT ... ON CONFLICT ... RETURNING` 分配 `orderId`、`eventId`、`commandId`、`outboxId`。
- `trading_outbox_events` 与订单写入在同一事务提交。
- `trading_order_events` 和 `trading_outbox_events` 插入必须影响 1 行，否则事务失败，避免订单状态和消息链路不一致。
- outbox 发布器用 `FOR UPDATE SKIP LOCKED`，多个 order-provider 节点可以同时运行，不会重复锁同一批待发消息。
- outbox 失败后按 `next_attempt_at` 指数退避重试，避免 Kafka 故障时热循环。
- Kafka producer 开启 `acks=all` 和 `enable.idempotence=true`。
- 下游消费者需要按 `commandId/orderId` 幂等处理 command，按 `eventId` 幂等处理 event。

## Kafka

- `surprising.perp.order.commands.v1`：订单撮合命令，key = `symbol`。
- `surprising.perp.order.events.v1`：订单入口事件，key = `symbol`。
- `surprising.perp.match.results.v1`：撮合结果，key = `symbol`。
- `surprising.perp.match.trades.v1`：撮合成交，key = `symbol`，价格和数量仍然是 long tick/step。
- `surprising.perp.orderbook.depth.v1`：L2 盘口深度更新，key = `symbol`。

topic partition 继续按 symbol 扩展。同一个 symbol 的 command 必须固定落到同一个 matching shard/order book。

## exchange-core 撮合

`surprising-matching-provider` 启动时从 instrument 当前版本加载 `TRADING` symbol，并为 exchange-core 建立稳定的 `symbolId`、asset/currency id：

- `trading_matching_assets`
- `trading_matching_symbols`

symbol 在 exchange-core 内注册为 `CURRENCY_EXCHANGE_PAIR`。这是有意设计：exchange-core 在这里只作为确定性的 long 订单簿和撮合器使用；合约保证金、交易手续费、强平、资金费率、保险基金和 ADL 都由外围服务负责。

收到 `OrderCommandEvent` 后：

- `PLACE` -> `ApiPlaceOrder`
- `CANCEL` -> `ApiCancelOrder`
- `BUY` -> `OrderAction.BID`
- `SELL` -> `OrderAction.ASK`
- `GTC/IOC/FOK/GTX` -> exchange-core 对应 order type；GTX/post-only 只对 `PLACE` 在提交前检查盘口，若会吃单则拒绝。`CANCEL` 必须绕过 post-only 检查。
- `MARKET` 转换为基于最新 mark price 和配置最大滑点的 IOC/FOK 保护限价单。
- `IOC`、`FOK`、`MARKET` 订单在撮合返回后就是终态，撮合结果落库后会释放未成交部分冻结保证金。如果 MARKET 订单按保守风险边界冻结、但按更优订单簿价格成交，account 结算成交时会释放差额。
- `trading_match_trades` 使用 `(symbol, trade_id)` 作为成交幂等键，和 Kafka 按 symbol 分区、重放的模型保持一致。
- `trading_match_results` 和 `trading_match_trades` 是撮合结果重放幂等门。结果或成交行已存在时，服务会跳过该行后续副作用，不能重复更新订单成交、释放保证金或写 outbox。
- guarded 订单成交/状态更新、保证金释放和 matching outbox 写入仍然必须影响 1 行。若 overfill guard、数量不变量不一致、目标订单缺失或 outbox 写入导致行数异常，matcher 会失败并走重启恢复，不能在已变更的 exchange-core 内存簿上继续处理。

当前 matching-provider 使用 exchange-core 的真实订单簿和成交事件链，但关闭 exchange-core 内置风险处理和内置手续费。订单入口已接入下单前初始保证金冻结；账户 provider 已接入成交后的开仓保证金迁移，并按订单费率快照写入 maker/taker `TRADE_FEE` ledger。资金费率、保险基金和 ADL 由独立结算模块处理。

### 盘口深度

每个成功的 exchange-core 命令改变盘口后，matching 会通过 matching outbox 发布深度事件：

- 每个 symbol 的第一条，以及每隔 `surprising.trading.matching.engine.order-book-snapshot-interval-events` 条，会发布 `SNAPSHOT`；
- 常规更新发布 `DELTA`，只包含发生变化的价格档；
- `quantitySteps=0` 且 `orderCount=0` 表示删除该价格档；
- `sequence` 由 PostgreSQL 分配，`previousSequence` 指向上一条已发布深度事件；
- 客户端发现 `previousSequence` 和本地最后 sequence 不一致时，必须重新拉快照，不能继续套增量。

公共 REST 快照接口：

```bash
curl 'http://localhost:9085/api/v1/trading/market/orderbook?symbol=BTC-USDT&depth=50'
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
```

`DELTA` 里的档位是价格档绝对状态，不是数量差值。客户端应用时直接替换本地对应价格档；`quantitySteps=0` 时删除该价格档。

深度事件只是行情 fanout，不是账户或订单状态权威。PostgreSQL 仍是订单状态审计源，exchange-core 仍是实时订单簿源。

### 订单簿恢复

启动恢复默认开启：

```yaml
surprising:
  trading:
    matching:
      recovery:
        open-order-book-restore-enabled: true
        open-order-batch-size: 10000
```

matching provider 启动时从 PostgreSQL 的开放订单重建 exchange-core 订单簿：

- 只恢复当前仍为 `TRADING` 的 instrument。
- 只恢复未完成的 `LIMIT` + `GTC/GTX` 订单，并要求 `remaining_quantity_steps > 0`。
- 订单必须已经在 `trading_match_results` 中有成功的 `PLACE` 结果；仅在订单库里 accepted、但从未进入撮合的命令不会被注入订单簿。
- 恢复顺序是 `created_at, order_id`，恢复后的 maker 优先级确定。
- 如果恢复订单互相 crossed 并在恢复阶段产生撮合事件，启动会 fail fast。不能带着损坏的持久化订单簿静默继续。

这是基于 DB 开放订单的重建，不是 exchange-core 原生 journal replay。因为数据库是订单状态权威源，这已经能保证服务重启和故障切换正确性。如果后续超大订单簿要求亚秒级热恢复，再增加 exchange-core snapshot/journal 持久化，DB 恢复保留为审计兜底。

### 多节点撮合

`surprising.perp.order.commands.v1` 必须以 `symbol` 作为 key。Kafka 会把每个 partition 分配给 `surprising-matching-v1` group 中的一个 consumer，所以同一时刻一个 symbol partition 只能由一个 live matcher 处理。

matching consumer 使用 cooperative sticky assignment 和 `MatchingPartitionAssignmentGuard`：

- 进程启动时先从 DB 恢复开放订单簿，再消费命令。
- 如果一个已经处理过命令的 matcher 在运行中拿到新的 partition，会关闭 Spring context。Kubernetes/systemd 应重启进程，新进程先恢复当前 DB 订单簿再消费。
- 这样可以避免旧进程拿到一个本地 exchange-core 订单簿已经过期的 symbol 后继续撮合。
- 如果 command payload 已经解析成功、但后续处理失败，matcher 也会关闭 Spring context。这样可以避免 exchange-core 已经被本次命令修改、但 PostgreSQL/outbox 持久化失败后，在同一个脏内存订单簿上继续重试 Kafka command。
- 这也覆盖撮合结果、成交、订单状态、保证金释放和 matching outbox 的 fail-fast 写入失败。排障时应优先检查 sequence、唯一索引冲突、订单行缺失和数据库事务错误。
- 生产环境保持 `surprising.trading.matching.kafka.restart-on-partition-reassignment=true`。
- `surprising.trading.matching.kafka.partition-assignment-startup-grace-ms` 默认 `30000`，用于让并发 listener 容器完成初始 assignment，然后再把新 partition 视为不安全的运行期迁移。
- command 失败后的重启是无条件保护；partition-reassignment 开关只控制 partition 迁移场景的重启行为。

扩容规则：谨慎增加 topic partition 和 matching 实例。不要对 matching pod 做高频自动扩缩容，因为 partition 迁移可能触发重启并重建订单簿。
把 matching 在命令处理期间退出视为必须通过 DB 恢复重启的场景，不要在同一进程内强行重试。

## 自成交防护

`surprising-matching-provider` 在提交 taker 订单前检查同一用户是否存在可成交的反向挂单，命中则拒绝新订单。

- BUY 检查自己的 SELL 挂单是否有 `priceTicks <= effectivePriceTicks`。
- SELL 检查自己的 BUY 挂单是否有 `priceTicks >= effectivePriceTicks`。
- `CANCEL_REQUESTED` 订单也会计入，因为 cancel 命令真正处理前订单仍可能在 exchange-core 内有效。
- 拒绝原因是 `SELF_TRADE_PREVENTED`，已冻结保证金会走正常拒单释放链路。

## API

下限价单：

```bash
curl -X POST 'http://localhost:9084/api/v1/trading/orders' \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: trace-demo-1001' \
  -d '{
    "userId": 1001,
    "clientOrderId": "cli-1001-1",
    "symbol": "BTC-USDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "timeInForce": "GTC",
    "priceTicks": 650000,
    "quantitySteps": 10,
    "reduceOnly": false,
    "postOnly": false
  }'
```

撤单：

```bash
curl -X POST 'http://localhost:9084/api/v1/trading/orders/cancel' \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: trace-demo-cancel-1001' \
  -d '{"userId":1001,"orderId":1}'
```

查询订单：

```bash
curl 'http://localhost:9084/api/v1/trading/orders/1'
curl 'http://localhost:9084/api/v1/trading/orders/by-client-order-id?userId=1001&clientOrderId=cli-1001-1'
curl 'http://localhost:9084/api/v1/trading/orders/open?userId=1001&symbol=BTC-USDT&limit=100'
```

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `trading_sequences`
- `trading_orders`
- `trading_order_events`
- `trading_outbox_events`
- `account_margin_reservations`
- `account_position_margins`
- `trading_matching_assets`
- `trading_matching_symbols`
- `trading_match_results`
- `trading_match_trades`

核心索引：

- `trading_orders_user_client_order_uidx`
- `trading_orders_open_query_idx`
- `trading_orders_stp_open_idx`
- `trading_orders_recovery_idx`
- `trading_order_events_order_idx`
- `trading_order_events_trace_idx`
- `trading_outbox_pending_idx`
- `trading_match_results_order_idx`
- `trading_match_results_success_place_idx`
- `trading_match_results_trace_idx`
- `trading_match_trades_symbol_time_idx`
- `trading_match_trades_trace_idx`

## 本地运行

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-instrument-provider -am spring-boot:run
mvn -pl :surprising-order-provider -am spring-boot:run
JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" \
mvn -pl :surprising-matching-provider -am spring-boot:run
```

端口：

- `9084`：订单入口服务。
- `9085`：撮合服务。

## 生产注意事项

- `surprising-order-provider` 可以多节点水平部署，但必须共享同一个 PostgreSQL 和 Kafka 集群。
- matching provider 使用 JDK 21 运行。exchange-core 依赖的 Chronicle/OpenHFT 需要显式 Java module opens/exports；生产 pod 使用本地运行示例里的 `JAVA_TOOL_OPTIONS`。
- 新 symbol 必须先在 instrument 模块上线，确认 Kafka partition 足够，再开放下单。
- MARKET 订单在订单入口和撮合阶段都要求 mark price 新鲜。订单入口会用配置的 mark 派生可成交区间校验 min/max notional，再发布撮合命令；线性合约 max-notional 和初始保证金按上边界计算，避免市价 SELL 开空在高买价成交时抵押不足。`surprising.trading.*.market-max-slippage-ppm` 需要按产品流动性配置。
- 当前已经实现基于 PostgreSQL 的开放订单簿恢复；exchange-core 原生 snapshot/journal 可作为后续超大订单簿更快恢复的增强。
- Instrument `max_notional_units` 已同时约束限价单和保护价市价单。真实盘口深度、延迟和强平压力测试证明更大额度安全之前，产品 notional 限额应保持保守。
- 用户主动平仓应使用 `reduceOnly=true`；强平订单由 liquidation provider 复核风险后生成，不走用户订单入口校验。
- outbox 是至少一次投递；下游撮合和推送必须幂等。
- matching result 通过 `commandId` 幂等，成交通过 `tradeId` 幂等。
- 如果 matching 进程处理过命令后又拿到新的 Kafka partition，会主动退出并由编排系统重启，以便从 DB 重建新的 exchange-core 订单簿。这是预期的 failover 行为。
- 不要在订单入口做每个 symbol 一个线程，symbol 扩展应通过 Kafka partition 和 matching shard 调度完成。

## 验证

```bash
mvn -pl :surprising-order-provider -am test
mvn -pl :surprising-matching-provider -am test
rg -n "BigDecimal" surprising-trading -g '*.java'
```
