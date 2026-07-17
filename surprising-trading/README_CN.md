# surprising-trading

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 现货、永续、交割和期权交易模块。当前已实现 `surprising-order-provider`、`surprising-trigger-provider`、`surprising-trading-entry-provider` 和 `surprising-matching-provider`：订单入口、止盈止损条件单、instrument 规则校验、幂等落库、产品线 Kafka 撮合命令发布、exchange-core 真实订单簿撮合、撮合结果和成交事件输出。

## 模块

- `surprising-trading-api`：订单 RPC 合约、DTO、Kafka command/event 模型。
- `surprising-order-provider`：订单入口 provider。
- `surprising-trigger-provider`：止盈止损条件单 provider。
- `surprising-trading-entry-provider`：订单入口和条件单的合并部署 provider。
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
  -> surprising-trading-entry-provider / surprising-order-provider
  -> PostgreSQL trading_orders + trading_order_events + trading_outbox_events
  -> outbox publisher
  -> surprising.<product-segment>.order.commands.v1
  -> surprising-matching-provider / exchange-core
  -> trading_match_results / trading_match_trades
  -> surprising.<product-segment>.match.results.v1 / surprising.<product-segment>.match.trades.v1
```

止盈止损走独立链路：

```text
client / internal gateway
  -> POST /api/v1/trading/trigger-orders
  -> surprising-trading-entry-provider / surprising-trigger-provider
  -> PostgreSQL trading_trigger_orders
  -> consume surprising.<product-segment>.mark.price.v1
  -> 通过订单入口 API 提交 reduceOnly 平仓单
  -> 正常订单 / 撮合 / 账户 / WebSocket 链路
```

订单 provider 不直接撮合，也不直接承担 WebSocket 推送。订单状态推送服务应该消费当前产品线的订单和撮合 topic 后独立 fanout。关闭产品线 topic 路由时仍可使用 legacy `surprising.perp.*` topic。

## 保证金模式

订单、撮合 command、成交事件、账户 reservation 和账户持仓现在都会携带 `marginMode`。
默认值是 `CROSS`。`ISOLATED` 已经进入订单入口、撮合事件、账户保证金、持仓、风控快照、资金费和强平链路。
全仓亏损、手续费和资金费可以使用全仓可用余额以及全仓持仓保证金兜底；逐仓只消耗同一 `userId + symbol + asset + marginMode`
下的逐仓持仓保证金，不会动用其他 symbol 或全仓余额。用户手动追加/减少逐仓保证金由
`surprising-account-provider` 的 `POST /api/v1/accounts/position-margin-adjustments` 处理。同一用户同一 symbol
要在 `CROSS` 和 `ISOLATED` 之间切换，必须先关闭该 symbol 已有持仓并取消普通开放订单和待触发条件单；order-provider 和
trigger-provider 会用 `userId + symbol` 的 PostgreSQL transaction advisory lock 串行化这条检查。

持仓模式按用户维度配置，默认是 `ONE_WAY`。用户只能在无非零持仓、无活动挂单、无待触发条件单、无未结算撮合/账户状态时通过
account 的 `position-mode` API 切换到 `HEDGE`。`ONE_WAY` 使用 `positionSide = NET`；`HEDGE` 下普通订单和条件单必须携带
`positionSide = LONG` 或 `SHORT`，关闭所选仓位腿会被规范化为 reduce-only，并且 `positionSide` 会贯穿撮合、账户持仓/保证金、
风控快照、强平、资金费、ADL 和 WebSocket 推送。

## 手续费

- `init.sql` 默认 `BTC-USDT`、`ETH-USDT` 使用 maker `200 ppm`、taker `500 ppm`，即 `0.02% / 0.05%`。
- `trading_fee_schedules` 可配置用户全局或单 symbol 覆盖，`source_type` 支持 `USER_OVERRIDE`、`VIP`、`MARKET_MAKER`、`PROMOTION`、`RISK_OVERRIDE`。
  单 symbol 优先于用户全局，最后回退 instrument 默认费率。
- 多个用户全局费率同时 active 时，source 优先级是 `RISK_OVERRIDE`、`USER_OVERRIDE`、`PROMOTION`、`MARKET_MAKER`、`VIP`，防止自动 VIP 刷新覆盖风控、人工、活动或做市商费率。
- 管理接口：`POST /api/v1/admin/trading/fees/schedules` 新增/更新费率，`POST /api/v1/admin/trading/fees/schedules/{feeScheduleId}/disable` 禁用费率，
  `GET /api/v1/admin/trading/fees/schedules` 查询配置。查询支持 `limit/cursor/sort` 游标分页，排序白名单为 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc`、`effectiveTime.desc`、`effectiveTime.asc`，响应保留 `schedules/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。
- VIP 档位接口：`POST /api/v1/admin/trading/fees/tiers` 新增/更新档位规则，`GET /api/v1/admin/trading/fees/tiers` 查询规则，`POST /api/v1/admin/trading/fees/tiers/refresh?userId=...` 重算单个用户，`POST /api/v1/admin/trading/fees/tiers/refresh-active` 重算活跃用户，`GET /api/v1/admin/trading/fees/tiers/users/{userId}` 查询当前档位。
  档位查询支持 `limit/cursor/sort` 游标分页，排序白名单为 `priority.desc`、`priority.asc`，响应保留 `tiers/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。

后台订单审计接口使用 `/api/v1/admin/trading` 前缀，并由 gateway 的 `/api/v1/admin/gateway/trading-orders` 与 `/api/v1/admin/gateway/trading-trigger` 后台安全域转发。`GET /orders`、`GET /trigger-orders` 和 `GET /orders/trades` 均支持 `limit/cursor/sort` 游标分页，响应保留 `orders` 或 `trades` 字段，并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。订单/条件单列表支持 `createdAt.desc`、`createdAt.asc`，成交列表支持 `eventTime.desc`、`eventTime.asc`。
- 定时 VIP 刷新会计算用户 30 日 maker+taker 成交名义价值和账户资产估值，单位统一为 USD/USDT 最小单位，然后把命中的档位写回 `trading_fee_schedules`，作为用户全局 `VIP` 费率。稳定币余额按 1:1 计算；非稳定币余额用活跃 `baseAsset-USDT/USD` 合约的最新 mark price 估值，没有 mark price 时不计入。
- 业务查询：`GET /api/v1/trading/fees/effective?userId=...&symbol=...` 返回当前最终 maker/taker ppm 和来源，例如 `INSTRUMENT`、`VIP_SYMBOL`。
- 订单接受时会把最终 `maker_fee_rate_ppm`、`taker_fee_rate_ppm` 写入 `trading_orders`。后续用户 VIP 等级或活动费率变化，不会重解释已接受挂单。
- account provider 结算成交时按订单快照写 `TRADE_FEE`，并在 ledger 保存 `trade_id`、`order_id`、`symbol`、`fee_rate_ppm`。
- 做市商返佣仍应由做市商计划或后台流程根据挂单质量确认后配置；默认自动档位只分配 `VIP` 费率。

## 杠杆设置

- 用户杠杆配置保存在 `trading_leverage_settings`，唯一键是 `user_id + symbol + margin_mode`。
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

## Cancel All After

`POST /api/v1/trading/orders/cancel-all-after` 为 API 客户端提供 dead-man switch：

- `countdownMs=0` 关闭倒计时。
- 正数 `countdownMs` 会刷新用户级倒计时；传 `symbol` 时只作用于该交易对，不传则作用于全部 symbol。
- 倒计时到期后，order-provider 复用现有 `cancel-open` 路径撤用户开放普通单，并调用 trigger-provider 撤 pending TP/SL 条件单。
- timer 状态保存在 `trading_cancel_all_after`；最近一次执行会记录普通单和条件单撤单数量。

## 算法单

`TWAP` 和 `ICEBERG` 在 order-provider 中作为 exchange-core 之前的算法单层实现。父算法单不会进入实时订单簿；被调度出来的子单是普通 order-provider 订单，继续走撮合、账户结算、风控、强平检查和 WebSocket fanout。

- `TWAP` 要求 `durationSeconds >= intervalSeconds`，并校验 `childQuantitySteps` 能在配置时间内完成目标数量。子单使用 IOC；`priceTicks=0` 会生成 MARKET IOC 子单，正数价格会生成 LIMIT IOC 子单。
- `ICEBERG` 要求正数限价，`timeInForce` 必须为 `GTC` 或 `GTX`。它同一时间只保留一笔可见子单，前一片成交或取消后再放出下一片。
- 活动算法单会阻断保证金模式和持仓模式切换，避免未来子单按旧模式假设继续发出。
- 取消父算法单会同时取消活动子单；`cancel-open` 支持用户级和可选 symbol 级批量取消。

REST 接口：

- `POST /api/v1/trading/orders/algo`
- `POST /api/v1/trading/orders/algo/cancel`
- `POST /api/v1/trading/orders/algo/cancel-open`
- `GET /api/v1/trading/orders/algo/{algoOrderId}`
- `GET /api/v1/trading/orders/algo/open`

## 止盈、止损和追踪止损

大型交易所的 TP/SL 通常是活跃订单簿外的条件单。本模块按这个模型实现：

- 条件单先以 `PENDING` 状态保存在 `trading_trigger_orders`，触发前不进入 exchange-core，也不冻结新增保证金。
- 当前支持 `MARK_PRICE`、`INDEX_PRICE` 和 `LAST_PRICE` 触发源。`LAST_PRICE` 消费真实撮合成交流，可用于用户 TP/SL/追踪止损条件单；但薄盘口下按最新成交价触发更容易被短时冲击操纵，强平仍只依赖 mark price。
- 触发方向由平仓方向和条件单类型自动推导：多仓止盈是 `SELL + TAKE_PROFIT`，所选触发价源大于等于触发价时触发；多仓止损是 `SELL + STOP_LOSS`，所选触发价源小于等于触发价时触发。空仓平仓用 `BUY`，方向相反。
- `TRAILING_STOP` 要求执行单为 `MARKET`，`callbackRatePpm` 在 `[1000, 100000]`（`0.1%` 到 `10%`），`activationPriceTicks` 可选。SELL 追踪止损激活后维护最高价，价格从最高价回撤达到回调比例时触发；BUY 追踪止损维护最低价，反弹达到回调比例时触发。
- trigger provider 对 `MARK_PRICE` 消费当前产品线的 mark-price topic，对 `INDEX_PRICE` 消费 index-price topic，对 `LAST_PRICE` 消费 match-trades topic；校验 Kafka key 等于 payload `symbol`，再把 mark/index 已落库价格行按当前 instrument tick size 转为 ticks，用 PostgreSQL `FOR UPDATE SKIP LOCKED` 抢占到期条件单。关闭产品线 topic 路由时才使用 legacy `surprising.perp.*` topic。
- 多个 trigger-provider 节点可以同时运行。每条到期条件单只能被一个节点抢到；如果下游 order-provider 故障，`TRIGGERING` 状态超过 `surprising.trading.trigger.execution.stale-triggering-after` 后会重置，等待后续 mark 事件重试。
- 静态 `TAKE_PROFIT`/`STOP_LOSS` 默认且始终通过 Spring Data Redis + Lettuce 写入按产品线、symbol、价格源隔离的 Redis ZSET，无需功能开关。一次 Lua 调用同时读取大于等于和小于等于两个范围，PostgreSQL 再对候选 id 复核并执行原有 `FOR UPDATE SKIP LOCKED` 状态迁移；追踪止损的高低水位更新仍保留在 PostgreSQL。
- Redis score 只使用 `2^53-1` 以内可精确表示的整数 ticks；已有数据超过范围时索引保持 not-ready 并退回数据库扫描，不能用浮点近似冒漏触发风险。新静态 TP/SL 在 Redis 索引写失败时 fail-closed；Redis 查询不可用时，已经提交的条件单仍走数据库 fallback 触发。
- readiness marker 使用短 TTL 并在校准后刷新。带 token 的 `SET NX` lease 和 compare-and-delete Lua 解锁只用于防止多节点重复重建，不串行业务触发。终态 DB 更新成功后再幂等删除 Redis member，因此故障最多留下会被 DB 拒绝的陈旧候选，不会制造错误数据库终态。
- 触发后通过 order-provider 提交 `reduceOnly=true`、`postOnly=false` 的平仓单，`clientOrderId=trigger-<triggerOrderId>`。order-provider 的幂等键会保护重试不会创建重复平仓单。
- 触发后的真实订单继续走普通订单、撮合、账户、手续费、PnL、风控、强平和 WebSocket 链路。trigger 服务不直接修改余额或持仓。
- `MARKET` 触发执行要求 `priceTicks=0` 且 `timeInForce` 为 `IOC` 或 `FOK`。静态 TP/SL 也可用 `LIMIT` 执行且要求 `priceTicks > 0`；触发执行不支持 `GTX`。
- 可选 `ocoGroupId` 支持成对 TP/SL 互撤。同一个 `userId + symbol + marginMode + ocoGroupId` 组里任意一条 pending 条件单被配置的触发价源事件抢占触发时，同一条数据库 claim 语句会先把其它 pending sibling 置为 `CANCELED`，再提交生成的 reduce-only 平仓单。
- 持仓完全归零时，account 会在结算事务内按精确的 `productLine + userId + symbol + marginMode + positionSide` 范围取消剩余全部 `PENDING` 条件单，并写入 `rejectReason=POSITION_CLOSED`。普通平仓、强平成交、交割结算和期权行权都走这条链路；事务提交后再由 account 持仓 outbox 事件驱动 Redis ZSET 幂等清理。已经处于 `TRIGGERING` 的行不会被抢撤，继续走现有 reduce-only 状态机收敛。
- 批量条件单放置支持 `atomic=true`，用于组合 TP/SL 的全成全撤语义。原子模式下任一条校验失败会拒绝整组、回滚已插入条件单，并返回逐项失败结果；默认批量模式仍保持逐条隔离成功/失败。
- OCO sibling 在 claim 阶段就会取消；如果后续 order-provider 执行失败，该 OCO 组也已经被消费。这个取舍可以避免多节点 trigger-provider 并发下重复平仓，执行失败后客户端可以重新挂一组 TP/SL。
- 当前条件单 API 不做原地改单。用户更新触发价或数量时应先撤旧单，再使用新的 `clientTriggerOrderId` 下单，确保重新执行完整校验并避免跨存储 move 竞争。`GET /open` 始终查询 PostgreSQL 的权威 `PENDING`/`TRIGGERING` 状态；触发生成的真实平仓单和成交继续走现有私有 WebSocket 频道。

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
    "triggerPriceType": "MARK_PRICE",
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

- `POST /api/v1/trading/trigger-orders/batch`：批量提交 TP/SL 条件单，最多 20 条；需要多腿 TP/SL 组合全成全撤时传 `atomic=true`。
- `POST /api/v1/trading/trigger-orders/batch-cancel`：批量撤销条件单，最多 50 条。
- `POST /api/v1/trading/trigger-orders/cancel-open`：撤销用户所有 `PENDING` 条件单，可按 `symbol` 过滤，单次最多 1000 条；已经进入 `TRIGGERING` 的条件单不在这里撤销，避免和触发执行抢状态。

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
- `maker_fee_rate_ppm` 和 `taker_fee_rate_ppm` 不传给 exchange-core。instrument 提供默认费率，
  `trading_fee_schedules` 可提供用户全局或单 symbol 覆盖，订单接受时会把最终费率固化到
  `trading_orders`，matching 再把 taker/maker 实际费率带入 `OrderCommandEvent` / `MatchTradeEvent`，
  账户结算不再回查 fee schedule 或订单费率。

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
- `orderId`、`eventId`、`commandId`、`outboxId` 等交易链路 ID 使用 PostgreSQL native sequence（例如 `trading_order_seq`、`trading_event_seq`、`trading_command_seq`、`trading_outbox_seq`）。不再用表计数器热点行分配高频 ID，避免并发下单和撮合时形成行锁瓶颈。
- `trading_outbox_events` 与订单写入在同一事务提交。
- `trading_trigger_orders_user_client_uidx` 保证同一用户 `clientTriggerOrderId` 的止盈止损下单幂等。
- `ocoGroupId` 用于把成对 TP/SL 条件单组成 one-cancels-other 互撤组；它是可选、按 `userId + symbol + marginMode` 隔离的字段，不替代 `clientTriggerOrderId`。
- `trading_order_events` 和 `trading_outbox_events` 插入必须影响 1 行，否则事务失败，避免订单状态和消息链路不一致。
- outbox 发布器先用 `FOR UPDATE SKIP LOCKED` claim 到期待发消息，并把 `next_attempt_at` 推进为短租约；随后在数据库事务外发送 Kafka，避免 Kafka 网络等待占住 PostgreSQL 行锁和连接。
- outbox 失败后按 `next_attempt_at` 指数退避重试，避免 Kafka 故障时热循环。
- Kafka producer 开启 `acks=all` 和 `enable.idempotence=true`。
- 下游消费者需要按 `commandId/orderId` 幂等处理 command，按 `eventId` 幂等处理 event。

## Kafka

- `surprising.<product-segment>.order.commands.v1`：订单撮合命令，key = `symbol`。
- `surprising.<product-segment>.order.events.v1`：订单入口事件，key = `symbol`。
- `surprising.<product-segment>.match.results.v1`：撮合结果，key = `symbol`。
- `surprising.<product-segment>.match.trades.v1`：撮合成交，key = `symbol`，价格和数量仍然是 long tick/step。
- `surprising.<product-segment>.orderbook.depth.v1`：L2 盘口深度更新，key = `symbol`。
- `surprising.<product-segment>.mark.price.v1`：trigger-provider 消费的标记价格流，key = `symbol`。

legacy `surprising.perp.*` topic 仍保留，用于兼容单线永续启动。

topic partition 继续按 symbol 扩展。同一个 symbol 的 command 必须固定落到同一个 matching shard/order book。
`surprising.trading.matching.kafka.max-poll-records` 默认 `500`；生产调优时应结合 Kafka lag 和 command 处理延迟调整，不需要改代码。

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

当前 matching-provider 使用 exchange-core 的真实订单簿和成交事件链，但关闭 exchange-core 内置风险处理和内置手续费。订单入口已接入下单前初始保证金冻结；账户 provider 已接入成交后的开仓保证金迁移，并按 `MatchTradeEvent` 必须携带的费率写入 maker/taker `TRADE_FEE` ledger。资金费率、保险基金和 ADL 由独立结算模块处理。

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

当前产品线的 order command topic 必须以 `symbol` 作为 key。Kafka 会把每个 partition 分配给该产品线 matching consumer group 中的一个 consumer，所以同一时刻一个 symbol partition 只能由一个 live matcher 处理。

matching consumer 使用 cooperative sticky assignment 和 `MatchingPartitionAssignmentGuard`：

- 进程启动时先从 DB 恢复开放订单簿，再消费命令。
- 如果一个已经处理过命令的 matcher 在运行中拿到新的 partition，会关闭 Spring context。Kubernetes/systemd 应重启进程，新进程先恢复当前 DB 订单簿再消费。
- 这样可以避免旧进程拿到一个本地 exchange-core 订单簿已经过期的 symbol 后继续撮合。
- 如果 command payload 已经解析成功、但后续处理失败，matcher 也会关闭 Spring context。这样可以避免 exchange-core 已经被本次命令修改、但 PostgreSQL/outbox 持久化失败后，在同一个脏内存订单簿上继续重试 Kafka command。
- 这也覆盖撮合结果、成交、订单状态、保证金释放和 matching outbox 的 fail-fast 写入失败。排障时应优先检查 sequence、唯一索引冲突、订单行缺失和数据库事务错误。
- 生产环境保持 `surprising.trading.matching.kafka.restart-on-partition-reassignment=true`。
- `surprising.trading.matching.kafka.partition-assignment-startup-grace-ms` 默认 `30000`，用于让并发 listener 容器完成初始 assignment，然后再把新 partition 视为不安全的运行期迁移。
- command 失败后的重启是无条件保护；partition-reassignment 开关只控制 partition 迁移场景的重启行为。
- `surprising.trading.matching.kafka.client-id` 要给每个 matching pod 配成稳定且唯一的值。这样 Kafka consumer group 输出才能把某个 `symbol` partition 映射到真正持有本地 exchange-core 订单簿的节点。

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

前端/BFF 应通过 gateway 调用同一订单服务：`POST /api/v1/gateway/trading` 对应直连
`POST /api/v1/trading/orders`，其余子路径保持一致，例如
`/api/v1/gateway/trading/test`、`/batch`、`/close-position`、`/cancel-open`。

订单用户接口：

- `POST /api/v1/trading/orders`：提交普通订单。`clientOrderId` 在同一用户内幂等。
- `POST /api/v1/trading/orders/test`：测单。只执行基础字段、产品规则、reduce-only、手续费快照和开仓冻结需求测算；不写 `trading_orders`，不冻结余额，不发布 Kafka command。
- `POST /api/v1/trading/orders/batch`：批量下单，最多 20 条。响应逐项返回成功/失败；单项业务拒单仍会返回对应订单响应。
- `POST /api/v1/trading/orders/close-position`：一键平当前仓位。服务端锁定当前 `account_positions` 行，按仓位方向生成 `reduceOnly=true`、`MARKET + IOC` 平仓单；不会冻结新增保证金。
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

- `trading_sequences`（兼容低频 legacy 计数器；高频交易链路 ID 使用 native sequence）
- `trading_order_seq`、`trading_event_seq`、`trading_command_seq`、`trading_outbox_seq`
- `trading_orders`
- `trading_order_events`
- `trading_trigger_orders`
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
- `trading_trigger_orders_user_client_uidx`
- `trading_trigger_orders_user_oco_idx`
- `trading_trigger_orders_user_status_idx`
- `trading_trigger_orders_symbol_gte_idx`
- `trading_trigger_orders_symbol_lte_idx`
- `trading_trigger_orders_expiry_idx`
- `trading_trigger_orders_triggering_idx`
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
./scripts/create-topics.sh
mvn -pl :surprising-instrument-provider -am spring-boot:run
mvn -pl :surprising-trading-entry-provider -am spring-boot:run
JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" \
mvn -pl :surprising-matching-provider -am spring-boot:run
```

端口：

- `9084`：trading-entry 合并服务，包含普通订单入口和条件单。
- `9085`：撮合服务。
- `9095`：拆分部署时的止盈止损条件单服务。

## 生产注意事项

- `surprising-trading-entry-provider` 是默认交易入口部署进程，承载普通订单和条件单流量。
- `surprising-order-provider` 和 `surprising-trigger-provider` 仍然保留，可用于拆分部署。二者都可以多节点水平部署，但必须共享同一个 PostgreSQL 和 Kafka 集群。trigger 按 Kafka partition 和数据库 claim batch 扩展，不要做每个 symbol 一个 worker。
- `surprising-matching-provider` 必须继续独立于 trading-entry；它持有 exchange-core 订单簿，应该单独扩缩容和重启恢复。
- matching provider 使用 JDK 21 运行。exchange-core 依赖的 Chronicle/OpenHFT 需要显式 Java module opens/exports；生产 pod 使用本地运行示例里的 `JAVA_TOOL_OPTIONS`。
- 新 symbol 必须先在 instrument 模块上线，确认 Kafka partition 足够，再开放下单。
- MARKET 订单在订单入口和撮合阶段都要求 mark price 新鲜。订单入口会用配置的 mark 派生可成交区间校验 min/max notional，再发布撮合命令；线性合约 max-notional 和初始保证金按上边界计算，避免市价 SELL 开空在高买价成交时抵押不足。`surprising.trading.*.market-max-slippage-ppm` 需要按产品流动性配置。
- 默认 application 配置已开启 LIMIT 订单价格带保护，`limit-price-band-ppm: 50000` 表示 5%。正式开放高频用户或做市商报价前，需要按具体产品流动性调整。
- 当前已经实现基于 PostgreSQL 的开放订单簿恢复；exchange-core 原生 snapshot/journal 可作为后续超大订单簿更快恢复的增强。
- Instrument `max_notional_units` 已同时约束限价单和保护价市价单。真实盘口深度、延迟和强平压力测试证明更大额度安全之前，产品 notional 限额应保持保守。
- 下单冻结保证金时还会校验投影后的持仓敞口：当前持仓 + 同方向未完成非 reduce-only 委托 + 本次委托，用这个投影值检查 `max_position_notional_units`、动态平台 OI 限额和命中的 `instrument_risk_brackets.notional_cap_units`；纯减仓单按减仓后的投影校验，不会简单用当前敞口加本单 notional 误拒。
- 动态单用户持仓量限额已实现：account 结算在 `trading_symbol_open_interest` 维护 long/short/open 数量，`open_quantity_steps=max(long_quantity_steps, short_quantity_steps)`；order 入口按当前价格折算平台 OI notional，并使用 `min(max_position_notional_units, max(openInterestNotional * user_open_interest_limit_rate_ppm / 1_000_000, user_open_interest_limit_floor_units))` 作为每个用户的有效持仓上限。默认 BTC/ETH 为 30% 平台 OI，固定下限 250,000 USDT。生产需要定期用 `account_positions` 对该表做重建校验，尤其在人工修数或灾备恢复之后。
- 用户主动平仓应使用 `reduceOnly=true`；强平订单由 liquidation provider 复核风险后生成，不走用户订单入口校验。
- 止盈止损触发后一定通过 order-provider 提交 reduce-only 平仓单。WebSocket 客户端会在普通私有订单/成交/持仓频道收到触发后生成的真实订单和成交。
- outbox 是至少一次投递；下游撮合和推送必须幂等。
- matching result 通过 `commandId` 幂等，成交通过 `tradeId` 幂等。
- 如果 matching 进程处理过命令后又拿到新的 Kafka partition，会主动退出并由编排系统重启，以便从 DB 重建新的 exchange-core 订单簿。这是预期的 failover 行为。
- 不要在订单入口做每个 symbol 一个线程，symbol 扩展应通过 Kafka partition 和 matching shard 调度完成。

## 验证

```bash
mvn -pl :surprising-order-provider -am test
mvn -pl :surprising-trading-entry-provider -am test
mvn -pl :surprising-matching-provider -am test
mvn -pl :surprising-trigger-provider -am test
rg -n "BigDecimal" surprising-trading -g '*.java'
```
