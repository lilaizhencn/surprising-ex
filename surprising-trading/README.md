# surprising-trading


Surprising Exchange 现货、永续、交割和期权交易模块。当前已实现独立的 `surprising-order-provider`、`surprising-trigger-provider` 和 `surprising-matching-provider`：订单入口、止盈止损条件单、instrument 规则校验、Core 幂等状态、产品线 Kafka 撮合命令发布、exchange-core 真实订单簿撮合、撮合结果和成交事件输出。

## 模块

- `surprising-trading-api`：订单 RPC 合约、DTO、Kafka command/event 模型。
- `surprising-order-provider`：订单入口 provider。
- `surprising-trigger-provider`：止盈止损条件单 provider。
- `surprising-matching-provider`：基于 `exchange-core` 的撮合 provider。

## long 定点数模型

订单入口不使用 `BigDecimal`。API、数据库、Kafka command 都使用 long：

- `priceTicks`：价格 tick 个数，展示价格 = `priceTicks * price_tick_units / quote_asset_scale`。
- `quantitySteps`：数量 step 个数，展示数量 = `quantitySteps * quantity_step_units / base_asset_scale`。
- `MARKET` 订单要求 `priceTicks = 0`。
- `MARKET` 订单只允许 `IOC` 或 `FOK`。
- notional 校验按 `contract_type` 分支。U 本位线性合约校验 `priceTicks * quantitySteps * notional_multiplier_units`；币本位反向合约校验 `quantitySteps * notional_multiplier_units`，因为 multiplier 表示每个合约 step 的报价币面值。两条路径都用 `Math.multiplyExact` 防止 long 溢出。
- 市价单用 `markPriceTicks +/- marketMaxSlippagePpm` 做提交 exchange-core 前的价格保护。订单入口使用同一个 mark 派生的可成交区间做风控：U 本位线性合约无论 BUY/SELL 都按上边界冻结初始保证金，币本位反向合约按下边界冻结，因为价格越低所需抵押越高。
- 当 `surprising.trading.order.risk.limit-price-protection-enabled=true` 时，限价单也要求新鲜 mark price。BUY 限价不能高于 `markPriceTicks * (1 + limitPriceBandPpm / 1_000_000)`，SELL 限价不能低于 `markPriceTicks * (1 - limitPriceBandPpm / 1_000_000)`。被动低价买单和高价卖单仍然允许。
- instrument version 保存产品默认 maker/taker ppm 费率；订单入口会叠加用户/VIP/做市覆盖后，把本订单实际费率快照写入 `trading_orders`，成交后由 account provider 按快照结算。

例子：`BTC-USDT` 的 `price_tick_units = 10000000`、`quantity_step_units = 100000`，USDT scale 为 `100000000`，BTC scale 为 `100000000`。

- `priceTicks = 650000` 表示价格 `65000.0`。
- `quantitySteps = 10` 表示数量 `0.01 BTC`。

这和 `exchange-core` 的 long 输入模型一致，撮合服务可以直接把 `priceTicks`、`quantitySteps` 传给 order book。
交易链路必须保持这个约束：订单、成交、保证金、PnL、资金费结算的 Java 代码不要转换成 `BigDecimal`。外部行情模块可以存展示用 decimal 值，但交易执行和账户记账必须使用缩放后的 `long`。
允许使用小数的地方只在系统边界：外部行情/汇率解析、管理后台录入、REST 展示和报表输出。进入订单、撮合、账户、风控、强平、资金费、保险基金、ADL 前，必须先转换为 instrument 定义的 tick、step、ppm 或 asset unit。
关键核心链路聚合使用 checked long addition。matching 成交总量和 reduce-only 待平数量溢出时会失败，而不是回绕成更小的值。

## 核心链路

```text
client / internal gateway
  -> POST /api/v1/trading/orders
  -> surprising-order-provider
  -> surprising.<product-segment>.order.user.commands.v1（key = PRODUCT_LINE:userId）
  -> 用户分区单写入消费者
  -> 用户分区 WAL/RocksDB 顺序追加订单事实
  -> surprising.<product-segment>.order.state.events.v1（按用户键压缩的完整状态广播）
  -> surprising.<product-segment>.order.user.command.results.v1
  -> Aeron Core 订单命令（预占由 Core 原子裁决）
  -> surprising.<product-segment>.order.commands.v1
  -> surprising-matching-provider / exchange-core
  -> MatchResultEvent / MatchTradeEvent
  -> 可靠资金链路：match.results.v1 + account.user.commands.v1
  -> 内存公共行情链路：match.trades.v1 + orderbook.depth.v1
  -> OrderStateProjectionWorker 异步投影 trading_orders 和审计表
```

止盈止损走独立链路：

```text
client / internal gateway
  -> POST /api/v1/trading/trigger-orders
  -> surprising-trigger-provider
  -> Aeron Core CoreTriggerOrderState
  -> Core 接收 APPLY_MARK_PRICE 并按增量索引触发
  -> Core 原子创建 reduce-only 子订单并撮合
  -> 账户 / WebSocket 链路，状态异步导出投影
```

订单 provider 不直接撮合，也不直接承担 WebSocket 推送。订单状态推送由独立服务消费当前产品线的订单和撮合 Topic 后完成 fanout。

订单 provider 启动时通过 account-provider 内部 RPC 加载未平仓量 JVM 快照，运行中消费产品线隔离的分片事件并按修订号幂等聚合；
保证金计算始终从 JVM 快照读取未平仓量，快照尚未恢复时失败关闭，不回查数据库也不跳过动态持仓上限校验。

## 保证金模式

订单、撮合 command、成交事件、账户 reservation 和账户持仓现在都会携带 `marginMode`。
默认值是 `CROSS`。`ISOLATED` 已经进入订单入口、撮合事件、账户保证金、持仓、风控快照、资金费和强平链路。
全仓亏损、手续费和资金费可以使用全仓可用余额以及全仓持仓保证金兜底；逐仓只消耗同一 `userId + symbol + asset + marginMode`
下的逐仓持仓保证金，不会动用其他 symbol 或全仓余额。用户手动追加/减少逐仓保证金由
`surprising-account-provider` 的 `POST /api/v1/accounts/position-margin-adjustments` 处理。同一用户同一 symbol
要在 `CROSS` 和 `ISOLATED` 之间切换，必须先关闭该 symbol 已有持仓并取消普通开放订单和待触发条件单；这项状态检查和裁决统一在 Aeron Core 的单写者状态机内完成。

持仓模式按用户维度配置，默认是 `ONE_WAY`。用户只能在无非零持仓、无活动挂单、无待触发条件单、无未结算撮合/账户状态时通过
account 的 `position-mode` API 切换到 `HEDGE`。`ONE_WAY` 使用 `positionSide = NET`；`HEDGE` 下普通订单和条件单必须携带
`positionSide = LONG` 或 `SHORT`，关闭所选仓位腿会被规范化为 reduce-only，并且 `positionSide` 会贯穿撮合、账户持仓/保证金、
风控快照、强平、资金费、ADL 和 WebSocket 推送。

## 手续费

- `init.sql` 默认 `BTC-USDT`、`ETH-USDT` 使用 maker `200 ppm`、taker `500 ppm`，即 `0.02% / 0.05%`。
- `trading_fee_schedules` 可配置用户全局或单 symbol 覆盖，`source_type` 支持 `USER_OVERRIDE`、`VIP`、`MARKET_MAKER`、`PROMOTION`、`RISK_OVERRIDE`。
  单 symbol 优先于用户全局，未匹配时使用当前 Instrument 默认费率。
- 多个用户全局费率同时 active 时，source 优先级是 `RISK_OVERRIDE`、`USER_OVERRIDE`、`PROMOTION`、`MARKET_MAKER`、`VIP`，防止 VIP 费率覆盖风控、人工、活动或做市商费率。
- 管理接口：`POST /api/v1/admin/trading/fees/schedules` 新增/更新费率，`POST /api/v1/admin/trading/fees/schedules/{feeScheduleId}/disable` 禁用费率，
  `GET /api/v1/admin/trading/fees/schedules` 查询配置。查询支持 `limit/cursor/sort` 游标分页，排序白名单为 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc`、`effectiveTime.desc`、`effectiveTime.asc`，响应保留 `schedules/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。
- 交易服务不再计算 30 日成交量、资产估值或自动 VIP 档位，也不再提供 `/fees/tiers` 系列接口。
  这些逻辑属于未来财务运营系统，必须消费事件投影并使用独立数据库；产出的最终费率通过明确的费率配置接口或事件进入交易服务。

后台订单审计接口使用 `/api/v1/admin/trading` 前缀，并由 gateway 的
`/api/v1/admin/gateway/trading-orders` 与 `/api/v1/admin/gateway/trading-trigger`
后台安全域转发。当前交易服务只保留直接读取单表的 `GET /orders` 和
`GET /trigger-orders` 列表；订单事件、撮合结果、成交明细和聚合时间线接口不再从交易主库提供。
这些跨表后台查询、资金对账和运营报表必须由未来财务运营系统消费事件投影，并连接独立数据库实现。

订单事实入口不再依赖订单数据库仓储：下单、撤单、账户预占结果和撮合结果先按用户分区命令
Topic 路由，再统一追加到用户分区 WAL/RocksDB，由 `OrderUserStateService` 按序应用。
`OrderRepository` 仅作为异步投影写入
`trading_orders`，不参与订单状态裁决；订单事件、仓位、仓位模式和算法单状态均从本地事实快照产生。
账户完整快照 Topic 使用用户键压缩，订单节点每个 JVM 通过唯一 `client-id` 使用独立消费组接收完整快照；
不能让多个订单实例共享快照消费组，否则本地缓存会被 Kafka 分摊而缺失用户状态。
订单状态也通过 `order.state.events.v1` 使用同样的用户键压缩广播，事件中的 `stateRevision` 是跨节点
单调修订号，本地 WAL 序号只负责当前节点顺序。分区迁移时没有完整快照或本地事实无法安全合并，订单节点
必须失败关闭，不能从落后的 `trading_orders` 投影恢复在线状态。
- 业务查询：`GET /api/v1/trading/fees/effective?userId=...&symbol=...` 返回当前最终 maker/taker ppm 和来源，例如 `INSTRUMENT`、`VIP_SYMBOL`。
- 订单接受时会把最终 `maker_fee_rate_ppm`、`taker_fee_rate_ppm` 写入 `trading_orders`。后续用户 VIP 等级或活动费率变化，不会重解释已接受挂单。
- account provider 结算成交时按订单快照写 `TRADE_FEE`，并在 ledger 保存 `trade_id`、`order_id`、`symbol`、`fee_rate_ppm`。
- 做市商返佣仍应由做市商计划或后台流程根据挂单质量确认后配置。

## 杠杆设置

- 用户杠杆配置的事实通过 `LeverageSettingEvent` 发布到产品线 Topic，订单 JVM 快照直接读取；
  `trading_leverage_settings` 只作为异步投影和启动恢复来源，唯一键是 `user_id + symbol + margin_mode`。
- `leveragePpm` 使用 ppm 表示杠杆：`10_000_000 = 10x`，`100_000_000 = 100x`。
- 用户接口：`POST /api/v1/trading/leverage/settings` 设置杠杆，`GET /api/v1/trading/leverage/settings?userId=...&symbol=...&marginMode=...` 查询当前设置。
- 设置杠杆时会先校验不能超过 instrument 当前版本的 `max_leverage_ppm`。
- 下单冻结保证金时还会按订单名义价值和当前同 `marginMode` 持仓名义价值选择 `instrument_risk_brackets` 档位；如果用户设置杠杆超过该档 `max_leverage_ppm`，订单会拒绝。
- 有效初始保证金率 = `max(用户杠杆换算出的保证金率, 风险档位 initial_margin_rate_ppm)`。未设置用户杠杆时，按当前风险档位最大杠杆/初始保证金率冻结。

## 普通订单改单

- 普通订单改单在 order-provider 中使用 cancel-replace 语义，不修改 exchange-core。
- 只允许改单开放的 `LIMIT` 订单，订单状态必须是 `ACCEPTED` 或 `PARTIALLY_FILLED`。
- 可修改 `priceTicks`、未成交 `quantitySteps`、挂单 `timeInForce`（`GTC`/`GTX`）和 `postOnly`。
- 不允许修改 `side`、`symbol`、`orderType`、`marginMode`、`positionSide` 或 `reduceOnly`。
- 替换单必须使用新的 `newClientOrderId` 保持幂等。开仓替换单会重新走普通订单校验和资金预占；原单释放仍由撤单撮合结果和 account 结算链路完成。
- REST 接口：`POST /api/v1/trading/orders/amend`、`POST /api/v1/trading/orders/batch-amend`。

## 全部撤单倒计时

`POST /api/v1/trading/orders/cancel-all-after` 为 API 客户端提供 dead-man switch：

- `countdownMs=0` 关闭倒计时。
- 正数 `countdownMs` 会刷新用户级倒计时；传 `symbol` 时只作用于该交易对，不传则作用于全部 symbol。
- 倒计时到期后，order-provider 复用现有 `cancel-open` 路径撤用户开放普通单，并调用 trigger-provider 撤 pending TP/SL 条件单。
- timer 状态保存在订单用户分区的本地 WAL/RocksDB；数据库不参与倒计时、到期判断或撤单裁决。
  数据库若配置了订单投影，只用于后台查询和审计，投影落后不会影响倒计时执行。

## 算法单

`TWAP` 和 `ICEBERG` 在 order-provider 中作为 exchange-core 之前的算法单层实现。父算法单不会进入实时订单簿；被调度出来的子单是普通 order-provider 订单，继续走撮合、账户结算、风控、强平检查和 WebSocket fanout。

- `TWAP` 要求 `durationSeconds >= intervalSeconds`，并校验 `childQuantitySteps` 能在配置时间内完成目标数量。子单使用 IOC；`priceTicks=0` 会生成 MARKET IOC 子单，正数价格会生成 LIMIT IOC 子单。
- `ICEBERG` 要求正数限价，`timeInForce` 必须为 `GTC` 或 `GTX`。它同一时间只保留一笔可见子单，前一片成交或取消后再放出下一片。
- 活动算法单会阻断保证金模式和持仓模式切换，避免未来子单按旧模式假设继续发出。
- 取消父算法单会同时取消活动子单；`cancel-open` 支持用户级和可选 symbol 级批量取消。
- 算法单父指令、子单映射、进度和撤单状态都由 `OrderUserStateService` 写入同一用户分区 WAL；
  `AlgoOrderService` 只负责参数校验和调度，不能通过数据库表补偿或重新拼装状态。

REST 接口：

- `POST /api/v1/trading/orders/algo`
- `POST /api/v1/trading/orders/algo/cancel`
- `POST /api/v1/trading/orders/algo/cancel-open`
- `GET /api/v1/trading/orders/algo/{algoOrderId}`
- `GET /api/v1/trading/orders/algo/open`

## 止盈、止损和追踪止损

大型交易所的 TP/SL 通常是活跃订单簿外的条件单。本模块按这个模型实现：

- 条件单先以 `PENDING` 状态写入 Aeron Core 的 `CoreTriggerOrderState`，触发前不进入 exchange-core，也不冻结新增保证金。
- 标记价格由 price-provider 通过单写入 Aeron `APPLY_MARK_PRICE` 命令送入 Core。Core 按 symbol 的价格范围索引只取 crossing candidates，不做全量条件单扫描。
- 触发方向由平仓方向和条件单类型自动推导：多仓止盈是 `SELL + TAKE_PROFIT`，采样标记价大于等于触发价时触发；多仓止损是 `SELL + STOP_LOSS`，采样标记价小于等于触发价时触发。空仓平仓用 `BUY`，方向相反。
- `TRAILING_STOP` 要求执行单为 `MARKET`，`callbackRatePpm` 在 `[1000, 100000]`（`0.1%` 到 `10%`），`activationPriceTicks` 可选。SELL 追踪止损激活后维护每次标记价更新的最高价，从最高价回撤达到回调比例时触发；BUY 追踪止损维护最低价，反弹达到回调比例时触发。水位和状态只由 Core 维护。
- trigger provider 不消费价格或持仓 Kafka 事件，也不维护条件单副本；Core 直接校验价格 sequence、过期时间、追踪水位和触发条件。
- 多个 trigger-provider 节点可以同时运行，用户查询和撤单通过 Aeron Core 按用户边界执行；`TRIGGERING` 的重试和投影由 Core 状态机负责。
- 静态 `TAKE_PROFIT`/`STOP_LOSS`、追踪止损都进入 Core 的增量 symbol/position/OCO 索引。索引更新随 Core 状态转换完成，标记价命令只访问命中的价格范围，不使用 Redis 或数据库锁抢单。
- 触发裁决、过期、OCO 和子订单创建都在 Aeron Core 内完成；trigger provider 只负责 API 到 Core 的命令和查询转发。
- 触发后的真实子订单继续走 Core 撮合、账户、手续费、PnL、风控、强平和 WebSocket 链路。trigger provider 不直接修改余额或持仓。
- `MARKET` 触发执行要求 `priceTicks=0` 且 `timeInForce` 为 `IOC` 或 `FOK`。静态 TP/SL 也可用 `LIMIT` 执行且要求 `priceTicks > 0`；触发执行不支持 `GTX`。
- 可选 `ocoGroupId` 支持成对 TP/SL 互撤。Core 在同一个命令状态转换内通过 OCO 索引取消其它 pending sibling，再生成 reduce-only 平仓单。
- 持仓完全归零时，Core 直接按用户、symbol、margin、position-side 的 position 索引取消 pending 条件单；不会扫描全量条件单，`TRIGGERING` 状态不会被抢撤。
- `expiresAt` 是可选字段：普通 TP/SL 可以长期有效，策略保护单可指定到期时间。Core 维护按过期时间排序的 pending 索引，维护任务每次只取有界的已到期集合并提交 `EXPIRE_TRIGGER_ORDER`，没有标记价事件也不会长期残留，更不会扫描全量条件单。
- 批量条件单默认逐条提交并保持成功/失败隔离。当前 Aeron Core 命令协议没有批量事务，`atomic=true` 会在不提交任何订单的情况下返回整组拒绝；需要全成全撤语义时必须先增加 Core 原子批命令。
- OCO sibling 在 Core 执行阶段就会取消；如果子订单被拒绝，该 OCO 组也已经被消费。客户端可以重新挂一组 TP/SL。
- 每次已提交的条件单状态变化都会进入 Core Export 的 trigger delta；gateway/WebSocket 按 delta 推送私有 `triggerOrders` 频道，客户端按 event id 去重并在重连后重新拉取 `GET /open`。
- 当前条件单 API 不做原地改单。`GET /open` 按 `userId + symbol + cursor` 查询 Core，返回 `nextCursor/hasMore`；历史审计通过 Core Export 异步投影。

REST 接口：

```bash
curl -X POST 'http://localhost:9084/api/v1/trading/trigger-orders' \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: trace-tp-1001' \
  -d '{
    "userId": 1001,
    "clientTriggerOrderId": "tp-1001-1",
    "ocoGroupId": "bracket-1001-1",
    "symbol": "BTC-USDT",
    "side": "SELL",
    "triggerType": "TAKE_PROFIT",
    "triggerPriceTicks": 700000,
    "orderType": "MARKET",
    "timeInForce": "IOC",
    "priceTicks": 0,
    "quantitySteps": 10,
    "marginMode": "CROSS"
  }'

curl -X POST 'http://localhost:9084/api/v1/trading/trigger-orders/cancel' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"triggerOrderId":1}'

curl 'http://localhost:9084/api/v1/trading/trigger-orders/open?userId=1001&symbol=BTC-USDT&limit=100'
curl 'http://localhost:9094/api/v1/gateway/trading-trigger/open?userId=1001&symbol=BTC-USDT&limit=100' -H 'X-User-Id: 1001'
```

条件单用户接口也可通过 gateway 访问：`/api/v1/gateway/trading-trigger` 对应直连
`/api/v1/trading/trigger-orders`。

- `POST /api/v1/trading/trigger-orders/batch`：批量提交 TP/SL 条件单，最多 20 条；`atomic=true` 当前会被 Core 命令协议明确拒绝。
- `POST /api/v1/trading/trigger-orders/batch-cancel`：批量撤销条件单，最多 50 条。
- `POST /api/v1/trading/trigger-orders/cancel-open`：撤销用户所有 `PENDING` 条件单，可按 `symbol` 过滤，单次最多 1000 条；已经进入 `TRIGGERING` 的条件单不在这里撤销，避免和触发执行抢状态。
- `GET /api/v1/trading/trigger-orders/open?userId=...&symbol=...&limit=...&cursor=...`：按 Core 游标查询用户待触发条件单，响应包含 `nextCursor` 和 `hasMore`。

触发单事实状态只存在 Aeron Core 的 `CoreTriggerOrderState` 和增量索引中。Provider 不加载数据库、Redis 或 Kafka 触发单仓储，数据库只通过 Core Export 接收异步查询投影。

## TraceId 链路追踪

- 前端或 BFF 可以传 `X-Trace-Id`；未传时 gateway/order 入口会自动生成。
- `surprising-order-provider` 只在当前 HTTP 请求内用 ThreadLocal 保存 traceId，请求结束会清理；提交 Aeron 前把它写入稳定 Core command/export 元数据。
- Core command、Core Export 和 WebSocket 事件会携带同一个 traceId，查询投影不参与在线裁决。
- matching projection 必须沿用 Core Export 的 traceId，不能重新生成或把投影 trace 当裁决身份。
- `surprising-account-provider` 会把 `MatchTradeEvent.traceId` 写入 `PositionUpdatedEvent`，这样私有 WebSocket 持仓推送也能和订单入口、撮合审计行关联。
- PostgreSQL 的 `trading_order_events`、`trading_match_results`、`trading_match_trades` 都保存 `trace_id`。生产日志建议同时输出 `traceId`、`orderId`、`commandId`、`tradeId`、symbol 和 Kafka topic/partition/offset。

## 保证金冻结

普通开仓/挂单由 `surprising-order-provider` 完成规则校验和保证金计算，再把带不可变预占快照的下单命令提交给 Aeron Core：

- 从 instrument 当前版本读取 `contract_type`、`initial_margin_rate_ppm`、`notional_multiplier_units`、`price_tick_units`、`settle_asset` 和资产 scale。
- 在 Java `OrderMarginMath` 中换算 `initialMarginUnits`：输入和输出都是 long ticks/steps/asset units，中间乘除使用精确整数计算，溢出会拒绝而不是回绕。
- Aeron Core 校验用户可用余额和订单预占，并在同一有序命令中把 `availableUnits` 转入 `lockedUnits`；`trading_orders` 只保存订单及预占快照投影，不更新账户余额表。
- 账户类型、结算资产和初始冻结量作为 Core 订单/预占元数据的一部分原子保存；`trading_orders` 只接收异步投影，永续不再维护独立的可裁决保证金预占记录。
- 如果 Core 判断保证金不足，命令直接返回 `REJECTED`，不会进入撮合；数据库只接收最终订单状态投影。

`reduceOnly=true` 的平仓和强平订单不冻结新增保证金。
matching 保证金释放只允许 `reduceOnly=true` 订单没有预占快照。非 reduce-only 订单缺少 `reservedUnits` 快照是会计不变量错误，必须失败，不能静默继续。

用户主动平仓订单在发布撮合前会做 reduce-only 安全校验：

- 多仓只能提交 reduce-only `SELL`。
- 空仓只能提交 reduce-only `BUY`。
- 已存在的未完成 reduce-only 平仓单会占用可平数量，新订单数量加上已有待平数量不能超过当前持仓。
- 待平数量聚合使用 checked long addition；如果溢出，会拒绝订单或回滚强平事务，不能静默扩大可平容量。
- 校验使用 Aeron Core 用户状态快照中的 `positionRevision`、订单 revision 和本地未成交索引，多节点
  通过用户 key 串行更新；快照未就绪时失败关闭，不使用数据库行锁猜测可平数量。
- 持仓被成交、强平或 ADL 改变后，order-provider 消费 Core Export 发布的完整持仓状态事件，并在同一订单用户
  分区 WAL 中撤销事件发生前创建且反向、版本不一致或超过新持仓容量的订单。它忽略事件之后创建的
  订单，避免延迟快照误撤重开仓位的新平仓单。

下单保证金热路径还维护 `OrderMarginSnapshotCache`：持仓事件、订单投影和杠杆设置成功后更新本地快照，
快照完整时不再做持仓、杠杆和挂单聚合 JOIN；全市场未平仓量从产品线隔离的 JVM 快照读取。进程启动
时通过账户内部快照 RPC、杠杆配置启动快照和订单本地 WAL 恢复，投影未就绪、事件落后或任一用户状态
未命中时直接拒绝下单，不在下单线程回查数据库。数据库仅是订单/杠杆异步投影和审计来源，不能作为账户
Core 状态的恢复源。

`surprising-matching-provider` 在撮合拒绝时发布释放命令；订单用户分区按未成交比例追加终态事实，
Aeron Core reducer 按实际成交价计算开仓保证金，把这部分从订单预占迁移到持仓保证金，并释放委托价改善
或市价风险边界多冻结的差额。数据库只接收账户和订单完整快照投影。线性合约市价单即使是 SELL
也故意按上边界冻结，因为 SELL 市价单可能吃到高于 mark 的买一挂单。平仓成交释放旧持仓保证金，
不消耗新的订单保证金。

保证金释放由 Aeron Core reducer 按预占记录和 revision 原子校验：`lockedUnits >= releaseUnits`。
如果冻结余额不足，命令拒绝并停在当前用户序号，不能静默扣减或把不存在的冻结金额释放成可用余额。

## Instrument 规则来源

订单入口只读取 `surprising-instrument` JVM 快照中的当前版本：

- 数据库表只由 instrument 模块管理并用于恢复；订单服务不持有 `InstrumentRepository`。

instrument 已经存储和 exchange-core 对齐的 long 规则边界：

- `min_quantity_steps -> minQuantitySteps`
- `max_quantity_steps -> maxQuantitySteps`
- `min_notional_units`、`max_notional_units`、`notional_multiplier_units` 保持 long 原始单位，并按 `contract_type` 校验。
- `LINEAR_PERPETUAL` 订单 notional = `priceTicks * quantitySteps * notional_multiplier_units`。
- `INVERSE_PERPETUAL` 订单面值 = `quantitySteps * notional_multiplier_units`。
- `max_leverage_ppm` 和 `instrument_risk_brackets` 会参与下单保证金冻结；风险档位越高，允许杠杆越低，最低初始保证金率越高。
- `maker_fee_rate_ppm` 和 `taker_fee_rate_ppm` 不传给 exchange-core。instrument 提供默认费率，
  `trading_fee_schedules` 可提供用户全局或单 symbol 覆盖，订单接受时会把最终费率固化到
  Core 订单元数据，成交时直接用 maker/taker 已固化费率计算并导出结算事实，
  投影和账户查询不再回查 fee schedule 决定既有成交。

所以交易模块 Java 代码仍然保持 long-only。

## Instrument 版本绑定

- 每个已接受订单都会保存校验时使用的 `instrument_version`。
- `reduceOnly` 平仓单绑定当前持仓版本，因此用户可以安全平掉旧版本持仓。
- Core place command 携带 `instrumentVersion`；撮合结果和订单元数据保留 taker command 版本。
- `MatchTradeEvent` 同时携带 `takerInstrumentVersion` 和 `makerInstrumentVersion`，账户结算时可以按双方各自合约公式处理。
- ProductExecutionCore 遇到同一 symbol 已有不同 `instrument_version` 的开放订单时拒绝新的 `PLACE` command，避免 exchange-core 在同一个 book 里撮合不兼容的 tick/multiplier 版本。
- 运维上，tick size、quantity step、multiplier、contract type、settlement asset 这类核心字段变更前，应先暂停交易并清理开放订单。

## 幂等和多节点

- `trading_orders_user_client_order_uidx` 保证同一用户 `clientOrderId` 幂等。
- 下单插入只允许这个部分 `(userId, clientOrderId)` 唯一键冲突被幂等跳过。`orderId` 或其他唯一键冲突必须失败，不能被当成请求重放。
- 幂等冲突发生在保证金冻结前；重复请求只返回已存在订单，不会创建新的 reservation 或重复锁定余额。
- `orderId`、`eventId`、`commandId` 等订单事实 ID 在 JVM/WAL 内按节点和时间生成；数据库序列只允许
  用于异步审计或管理配置，不参与订单状态裁决。
- 订单 Kafka 通知由本地事实状态同步发布；数据库投影不得反向驱动订单状态。
- Core 的用户级 clientTriggerOrderId 索引保证同一用户条件单幂等。
- `ocoGroupId` 用于把成对 TP/SL 条件单组成 one-cancels-other 互撤组；它是可选、按 `userId + symbol + marginMode` 隔离的字段，不替代 `clientTriggerOrderId`。
- 订单事实事件由用户分区 WAL/RocksDB 提交后直接发送 Kafka；数据库投影只按用户修订号异步替换，数据库不可用不会回滚订单状态。
- HTTP、账户结果、撮合结果和只减仓清理都必须先写入 `order.user.commands.v1`；订单节点之间不能直接
  调用另一个节点的本地 WAL。结果 Topic 只用于同步等待，终态同时保存在用户分区结果库。
- `trading_outbox_events` 仅由撮合、触发、强平等仍需要数据库事务发件箱的模块使用，不再作为订单下单或账户资金的事实入口。
- Kafka producer 开启 `acks=all` 和 `enable.idempotence=true`。
- 下游消费者需要按 `commandId/orderId` 幂等处理 command，按 `eventId` 幂等处理 event。

## Kafka 事件

- `surprising.<product-segment>.order.commands.v1`：订单撮合命令，key = `symbol`。
- `surprising.<product-segment>.order.events.v1`：订单入口事件，key = `symbol`。
- `surprising.<product-segment>.match.results.v1`：可靠撮合结果，key = `symbol`；私有成交和开放订单投影只消费这条链路。
- `surprising.<product-segment>.account.user.commands.v1`：matching outbox 产生的可靠用户资金命令；账户结算绝不消费公共逐笔流。
- `surprising.<product-segment>.order.user.commands.v1`：订单用户分区单写入命令，key = `<PRODUCT_LINE>:<userId>`；
  HTTP 下单/撤单、账户结果、撮合结果和算法状态更新都必须经过此 Topic。
- `surprising.<product-segment>.order.user.command.results.v1`：订单用户命令终态，key = `<PRODUCT_LINE>:<userId>`；
- `surprising.<product-segment>.order.state.events.v1`：订单用户完整状态压缩广播，key = `<PRODUCT_LINE>:<userId>`；
  每个 HTTP 节点使用独立结果消费组，不能把结果 Topic 当成事实源。
- `surprising.<product-segment>.match.trades.v1`：供 WebSocket 公共逐笔与 K 线计算使用的可丢失 `PublicTradeEvent`，key = `symbol`。matching 按 symbol 使用独立队列，每 50ms 由专用非阻塞 Kafka producer 批量刷新；逐笔保持 FIFO、不合并，同一 symbol 排队超过 10,000 条时只丢弃该 symbol 最旧的消息。
- `surprising.<product-segment>.orderbook.depth.v1`：可丢失的 L2 盘口快照，key = `symbol`；每个 symbol 只保留最新一份待发送快照。
- `surprising.<product-segment>.price.events.v1`：指数价和标记价统一流，`eventType` 区分分支，key = `symbol`。

公共逐笔/盘口链路不读写可裁决数据库，也不能阻塞或回滚 Core 资金处理。真实经济成交事实由 Core Export
至少一次发布，下游按稳定事件键幂等写入审计和查询投影；任何内部做市账号也执行相同的成交、手续费和资金规则。

生产行情/成交投影 Topic 固定为 `32` 个分区并按 symbol 取 key，以保持每个 symbol 的 fanout 顺序；
这些 partition 不拥有可执行盘口。不能直接增加已运行 Topic 的分区，容量超过时使用版本化 Topic 协同迁移消费者。

## exchange-core 撮合

每个 `ProductLine` 的 Aeron Core 内嵌一个 fork exchange-core；它是该产品线唯一价格树、FIFO 和可执行盘口。
`TradingCoreState` 只保存 `CoreOrderState` 业务元数据、资金预留和 `ActiveOrderIndex` 等必要索引，
不保存 `CoreBookState`、价格桶或 priority sequence。`surprising-matching-provider` 只负责行情/成交查询投影，
不持有、恢复或裁决第二个 exchange-core。

命令在 Core 单写 transition 内按以下顺序执行：

1. 校验幂等、instrument 版本、价格/数量、资金或保证金、margin scope 和风险边界。
2. 将订单映射为 fork 原生命令：

- `PLACE` -> `ApiPlaceOrder`
- `CANCEL` -> `ApiCancelOrder`
- `BUY` -> `OrderAction.BID`
- `SELL` -> `OrderAction.ASK`
- `GTC/IOC/FOK/GTX` -> exchange-core 对应 order type；GTX 直接映射到 exchange-core 原生
  `OrderType.GTX`，由订单簿在同一撮合命令内原子判定会否吃单。`CANCEL` 必须绕过 post-only 检查。
- `MARKET` 转换为基于最新 mark price 和配置最大滑点的 IOC/FOK 保护限价单。

3. 在同一 Core 命令中应用成交、手续费、余额、持仓、风险、生命周期和 `CommandDelta`。

- `IOC`、`FOK`、`MARKET` 订单在撮合返回后就是终态，撮合结果落库后会释放未成交部分冻结保证金。如果 MARKET 订单按保守风险边界冻结、但按更优订单簿价格成交，account 结算成交时会释放差额。
- Core Event 使用 `(product_line, symbol, trade_id)` 作为投影幂等键。`trade_id` 由
  `commandId * 1_000_000 + matchIndex` 确定性生成，成交热路径不发生数据库序列往返。
- Core 以 `commandId` 做重放幂等；投影端收到重复事件只更新更高 revision，不反向驱动订单状态。
- 资金结算命令携带不可变的订单总量和 `reduceOnly` 快照；account-provider 对照自己持有的 reservation 校验，成交热路径不再 join `trading_orders`。
- matcher 提交后若业务状态、delta 或恢复对账不一致，当前 Member 进入 sticky fail-closed；不得在进程内 rebuild、retry 或 resubmit。

adapter 关闭 exchange-core 内置业务风险、保证金和手续费；这些仍由 ProductExecutionCore 权威裁决。
exchange-core 内的 user/symbol/risk module 只是 matcher 技术状态，随原生 snapshot 恢复，不能作为业务资金查询源。

### 盘口深度

每个成功的 exchange-core 命令改变盘口后，Core Event 驱动 matching projection 获取有界 `BOOK_STATE_QUERY`
并把完整 `SNAPSHOT` 交给独立公共行情 publisher：

- 每个 symbol 有独立的 latest-only 槽位，热点 symbol 不会覆盖其他 symbol 的快照；
- 某个 symbol 有一条快照正在发送时，后续新快照只覆盖该 symbol 唯一的待发送槽位，过时的中间状态直接丢弃，不形成积压；
- 不同 symbol 可并行发送，全局并发上限由 `surprising.trading.matching.market-data.max-in-flight` 控制；
- publisher 使用独立的非事务 Kafka producer，继续写现有 `orderbook.depth` topic，key = `symbol`；
- Kafka 背压或发送失败不会阻塞撮合，也不会产生 outbox 行。失败快照允许丢弃，并由下一次盘口变化发布的新快照修复；
- 每条事件都是可独立使用的全量快照，消费者按 symbol 整体替换本地盘口，不再套用增量。

公共 REST 快照接口：

```bash
curl 'http://localhost:9085/api/v1/trading/market/orderbook?symbol=BTC-USDT&depth=50'
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
```

深度事件只是行情 fanout，不是账户或订单状态权威。Aeron Core 是订单、资金和实时订单簿事实源；
PostgreSQL 仅保存异步查询投影、审计和对账数据。

### 订单簿恢复

Aeron snapshot 以 `CoreState v6` 配对保存 Core 业务状态和 exchange-core 原生 `ME0/RE0` module bytes。
恢复只使用 `InitialStateConfiguration.fromSnapshotOnly`，随后由 Aeron Cluster Log 追赶 snapshot 水位后的命令；
不从 PostgreSQL、订单投影或 `BOOK_STATE_QUERY` 拼装在线簿，也不逐单 place 活动订单。

开放流量前必须校验 CRC32C、产品线/路由、fork/config、registry、完整 engine/book hash 和全部 OPEN
订单逐字段一致。任何断序、损坏、版本或集合不一致都 fail-closed。matching provider 只恢复可重建的
L2/成交投影；它的故障或积压不改变 Core 撮合与资金裁决。

### 多节点撮合

当前采用“一个 `ProductLine` 变体一个逻辑 Core”，每个逻辑 Core 是三 Member Aeron Cluster，
三个 Member 各自运行确定性 exchange-core 副本，只有 Leader 接收入站命令，Cluster Log 决定全序。
Kafka partition 只服务外围输入/导出与投影，不再决定可执行盘口的 owner。

当前不为热点币对单独建 Core。只有单产品线容量证据显示某 symbol 持续破坏 SLO，且 Cross Margin
用户/资金域、强平、ADL、保险基金、幂等和路由协议能一起迁移时，才启用预留的 shard identity 做版本化拆分。
不能仅移动 order book，也不能让同一 Cross Margin 资金域被两个可写 Core 并发裁决。

## 自成交防护

`ProductExecutionCore` 校验 maker/taker 权威订单身份，同一用户成交必须拒绝并触发 fail-closed 保护；
matching provider 不再持有本地盘口或自行裁决自成交。

- BUY 检查自己的 SELL 挂单是否有 `priceTicks <= effectivePriceTicks`。
- SELL 检查自己的 BUY 挂单是否有 `priceTicks >= effectivePriceTicks`。
- `CANCEL_REQUESTED` 订单也会计入，因为 cancel 命令真正处理前订单仍可能在 exchange-core 内有效。
- 拒绝原因是 `SELF_TRADE_PREVENTED`，已冻结保证金会走正常拒单释放链路。
- 当前没有绕过该规则的内部账户白名单；做市账号必须使用不同用户身份并接受相同资金、手续费和风险规则。

## 接口

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

前端/BFF 应通过 gateway 调用同一订单服务：`POST /api/v1/gateway/trading` 对应直连
`POST /api/v1/trading/orders`，其余子路径保持一致，例如
`/api/v1/gateway/trading/test`、`/batch`、`/close-position`、`/cancel-open`。

订单用户接口：

- `POST /api/v1/trading/orders`：提交普通订单。`clientOrderId` 在同一用户内幂等。
- `POST /api/v1/trading/orders/test`：测单。只执行基础字段、产品规则、reduce-only、手续费快照和开仓冻结需求测算；不写 `trading_orders`，不冻结余额，不发布 Kafka command。
- `POST /api/v1/trading/orders/batch`：批量下单，最多 20 条。响应逐项返回成功/失败；单项业务拒单仍会返回对应订单响应。
- `POST /api/v1/trading/orders/close-position`：一键平当前仓位。服务端读取 Aeron Core 当前用户状态，按仓位方向生成 `reduceOnly=true`、`MARKET + IOC` 平仓单；不会冻结新增保证金，也不锁定 `account_positions` 投影行。
- `POST /api/v1/trading/orders/cancel`：按 `orderId` 撤单。
- `POST /api/v1/trading/orders/batch-cancel`：批量撤单，最多 50 条。
- `POST /api/v1/trading/orders/cancel-open`：撤销用户普通开放订单，可按 `symbol` 过滤，单次最多 1000 条。
- `GET /api/v1/trading/orders/{orderId}`、`GET /api/v1/trading/orders/by-client-order-id`、`GET /api/v1/trading/orders/open`：订单查询。

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

- `trading_order_seq`、`trading_event_seq`、`trading_command_seq`、`trading_outbox_seq`
- `trading_orders`
- `trading_order_events`
- `trading_outbox_events`
- `account_position_margins`
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
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
# Topic 初始化命令待验证脚本重新整理后补回
mvn -pl :surprising-instrument-provider -am spring-boot:run
mvn -pl :surprising-order-provider -am spring-boot:run
mvn -pl :surprising-trigger-provider -am spring-boot:run
JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" \
mvn -pl :surprising-matching-provider -am spring-boot:run
```

端口：

- `9084`：order-provider，普通订单入口。
- `9085`：撮合服务。
- `9095`：trigger-provider，止盈止损条件单服务。

## 生产注意事项

- `surprising-order-provider` 和 `surprising-trigger-provider` 独立部署。普通订单、撮合、账户结算和条件单热路径只依赖 Aeron Core 与内存状态；trigger provider 不连接 PostgreSQL、Redis 或价格/持仓 Kafka，也不保留数据库回退链路。不要做每个 symbol 一个 worker。
- `surprising-matching-provider` 独立于 order/trigger，但只维护可重建的行情与成交查询投影，不持有可执行订单簿。
- Aeron Core 使用 JDK 25 运行。`exchange.core2:exchange-core:0.5.14-emporia` 传递依赖 Chronicle/OpenHFT，
  父 POM 固定 fork Git SHA、整包 SHA-256 和 JDK 25 可用的 2026.x BOM；service Maven `validate`
  同时验证 whole dependency JAR 与内嵌 provenance。
- 新 symbol 必须先在 instrument 模块上线，确认 Kafka partition 足够，再开放下单。
- MARKET 订单在订单入口和撮合阶段都要求 mark price 新鲜。订单入口会用配置的 mark 派生可成交区间校验 min/max notional，再发布撮合命令；线性合约 max-notional 和初始保证金按上边界计算，避免市价 SELL 开空在高买价成交时抵押不足。`surprising.trading.*.market-max-slippage-ppm` 需要按产品流动性配置。
- 默认 application 配置已开启 LIMIT 订单价格带保护，`limit-price-band-ppm: 50000` 表示 5%。正式开放高频用户或做市商报价前，需要按具体产品流动性调整。
- 当前已经实现 Aeron 配对 native snapshot + Cluster Log catch-up 的开放订单簿恢复；PostgreSQL 只保留异步投影，不参与在线簿恢复。
- Instrument `max_notional_units` 已同时约束限价单和保护价市价单。真实盘口深度、延迟和强平压力测试证明更大额度安全之前，产品 notional 限额应保持保守。
- 下单冻结保证金时还会校验投影后的持仓敞口：当前持仓 + 同方向未完成非 reduce-only 委托 + 本次委托，用这个投影值检查 `max_position_notional_units`、动态平台 OI 限额和命中的 `instrument_risk_brackets.notional_cap_units`；纯减仓单按减仓后的投影校验，不会简单用当前敞口加本单 notional 误拒。
- 动态单用户持仓量限额已实现：account 根据 Core Export 的结算事实把 OI 投影到 64 个 `trading_symbol_open_interest_shards`，`trading_symbol_open_interest` 视图向读端聚合 long/short 和 `open_quantity_steps=max(long_quantity_steps, short_quantity_steps)`；order 入口按当前价格折算平台 OI notional，并使用 `min(max_position_notional_units, max(openInterestNotional * user_open_interest_limit_rate_ppm / 1_000_000, user_open_interest_limit_floor_units))` 作为每个用户的有效持仓上限。默认 BTC/ETH 为 30% 平台 OI，固定下限 250,000 USDT。生产需要定期用 Core Export/用户状态快照重建核对分片，`account_positions` 只作为对账投影，尤其在人工修数或灾备恢复之后。
- 用户主动平仓应使用 `reduceOnly=true`；强平订单由 liquidation provider 复核风险后生成，不走用户订单入口校验。
- 止盈止损触发后一定通过 order-provider 提交 reduce-only 平仓单。WebSocket 客户端会在普通私有订单/成交/持仓频道收到触发后生成的真实订单和成交。
- outbox 是至少一次投递；下游撮合和推送必须幂等。
- matching result 通过 `commandId` 幂等，成交通过 `tradeId` 幂等。
- Aeron Member 的 matcher/Core 任一一致性门禁失败必须关闭成员，由 Cluster 选主或从配对 snapshot 恢复；
  matching projection 的 Kafka rebalance 只影响查询/行情滞后，不迁移 executable book。
- 不要在订单入口做每个 symbol 一个线程；当前以一个 ProductLine 一个 Core 为扩展单元。

## 验证

```bash
mvn -pl :surprising-order-provider -am test
mvn -pl :surprising-order-provider -am test
mvn -pl :surprising-matching-provider -am test
mvn -pl :surprising-trigger-provider -am test
rg -n "BigDecimal" surprising-trading -g '*.java'
```
