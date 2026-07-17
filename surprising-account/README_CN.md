# surprising-account

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 账户和产品结算模块。当前实现 long-based 基础余额、产品账户、余额流水、成交幂等处理、现货资产结算、衍生品持仓更新、成交后订单保证金到持仓保证金的迁移、maker/taker 手续费结算、资金费结算、交割/行权流水，以及强平成交后的实际强平费收取和保险基金入账事件。

## 模块

- `surprising-account-api`：账户/持仓 RPC 合约和 DTO。
- `surprising-account-provider`：余额、ledger、持仓和成交消费实现。

## long 单位

- 余额使用 `availableUnits`、`lockedUnits`、`equityUnits`，全部是资产最小单位的 long。
- 持仓使用 `signedQuantitySteps`，正数为净多，负数为净空。
- 持仓保存 `instrumentVersion`，当前敞口的合约数学固定到开仓时的版本。
- 持仓和持仓保证金都会保存 `marginMode`；单向净持仓链路下 `CROSS` 和 `ISOLATED` 都可执行。
- 持仓查询响应返回 `positionSide = NET`。账户表当前仍按 `userId + symbol + marginMode` 保存一条净持仓；
  hedge-mode `LONG/SHORT` 持仓还没有持久化。
- 开仓均价使用 `entryPriceTicks`。
- 持仓保证金记录在 `account_position_margins.margin_units`。
- 已实现盈亏按 `realizedPnlUnits` 累计，单位是 instrument 的结算资产最小单位。U 本位线性合约使用 tick-step notional；币本位反向合约使用合约面值和入场/出场价格倒数公式。
- 交易手续费使用 `MatchTradeEvent` 必须携带的 `takerFeeRatePpm` / `makerFeeRatePpm`。正费率扣用户余额，负费率给用户返佣；账户结算热路径不再回查 `trading_orders` 费率。
- 强平费使用 `liquidation_orders.liquidation_fee_rate_ppm` 冻结费率。账户结算只扣实际可从用户保证金中收上的金额，并把已收金额发布给保险基金。
- 当亏损超过 `availableUnits + lockedUnits` 时，超额亏损写入 `account_deficits`，不让余额列变成负数。

## 成交处理

`surprising-account-provider` 消费：

```text
surprising.<product-segment>.match.trades.v1
```

每条 `MatchTradeEvent` 会同时更新：

- taker 用户持仓，方向使用 `takerSide`。
- maker 用户持仓，方向为 taker 的反方向。
- 成交中的开仓数量只会把按实际成交价计算出的初始保证金迁移到 `account_position_margins`；委托价改善或市价保护价多冻结的部分会释放回 `availableUnits`。
- 成交中的平仓数量会按比例把旧持仓保证金从 `lockedUnits` 释放回 `availableUnits`。
- 平仓产生的已实现盈亏会写入 `account_ledger_entries`，`reference_type = TRADE_PNL`。
- 每笔成交的 maker/taker 手续费会写入 `account_ledger_entries`，`reference_type = TRADE_FEE`，并保存 `trade_id`、`order_id`、`symbol`、`fee_rate_ppm` 方便对账。
- 如果成交订单是强平订单，account-provider 还会写 `reference_type = LIQUIDATION_FEE` 的强平费流水。扣款按实际可收 collateral 封顶：全仓可使用同结算资产的可用余额和全仓持仓保证金；逐仓只使用该逐仓持仓保证金。收不上的部分不会生成新的 deficit，也不会进入保险基金。
- 强平费收取成功后会通过 account transactional outbox 发送到 `surprising.<product-segment>.account.liquidation-fee.events.v1`，Kafka key 是结算资产。insurance-provider 消费后按 `tradeId:orderId` 幂等写入 `insurance_fund_ledger(reference_type = LIQUIDATION_FEE)`。
- 翻仓成交先平旧仓，再把剩余成交数量作为新仓处理。
- 如果成交导致翻仓，已实现盈亏使用旧持仓版本计算，翻仓后剩余新仓使用成交的 `instrumentVersion`。
- 开仓成交必须找到对应的 `account_margin_reservations` 并成功迁移订单保证金；缺失 reservation、reduce-only 订单出现开仓数量、或迁移写入返回 0 都会失败并回滚整笔成交处理。
- 平仓成交只有 `reduceOnly=true` 订单可以跳过订单保证金释放。非 reduce-only 订单只要平掉了仓位，就仍必须存在原始 reservation 行。
- 持仓更新、余额更新、发生数值变化的 deficit 更新、PnL/fee ledger 插入/回填、订单保证金释放、持仓保证金增减都要求写入 1 行。任何异常都不应静默跳过。
- 持仓数量或版本变化后会触发 reduce-only 挂单剪枝：反向、版本不一致或超过新持仓容量的未完成 reduce-only 订单会被写为 `CANCEL_REQUESTED` 并通过 order command outbox 发送撤单。容量检查使用 checked absolute value 和 checked 待平数量累加，异常持仓数量会在发出撤单命令前失败。
- 结算把持仓降为零时，account-provider 还会在同一个事务里把精确持仓范围内全部 `PENDING` 止盈、止损和追踪止损置为 `CANCELED`，原因为 `POSITION_CLOSED`。持仓 outbox 事件在该更新进入事务后才写出，trigger-provider 因而只会在数据库提交后清理 Redis 二级索引。

`account_processed_trades(symbol, trade_id)` 是成交幂等键，重复投递不会重复更新持仓，也不会把不同 symbol 的同号 tradeId 误判为重复。

## API

查询余额：

```bash
curl 'http://localhost:9086/api/v1/accounts/balance?userId=1001&asset=USDT'
curl 'http://localhost:9086/api/v1/accounts/balances?userId=1001'
```

查询持仓：

```bash
curl 'http://localhost:9086/api/v1/accounts/position?userId=1001&symbol=BTC-USDT'
curl 'http://localhost:9086/api/v1/accounts/position?userId=1001&symbol=BTC-USDT&marginMode=CROSS'
curl 'http://localhost:9086/api/v1/accounts/position?userId=1001&symbol=BTC-USDT&marginMode=CROSS&positionSide=NET'
curl 'http://localhost:9086/api/v1/accounts/position-margin?userId=1001&symbol=BTC-USDT&marginMode=ISOLATED'
curl 'http://localhost:9086/api/v1/accounts/positions?userId=1001'
curl 'http://localhost:9086/api/v1/accounts/positions?userId=1001&positionSide=NET'
```

持仓查询接受当前单向持仓模式的 `NET`。在完整交易、账户、风控 schema 支持双向持仓前，
`LONG` 和 `SHORT` 查询值会返回 `400`。

调整逐仓持仓保证金：

```bash
curl -X POST 'http://localhost:9086/api/v1/accounts/position-margin-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"symbol":"BTC-USDT","marginMode":"ISOLATED","amountUnits":100000000,"referenceId":"iso-margin-add-1001-1","reason":"ADD_POSITION_MARGIN"}'

curl -X POST 'http://localhost:9086/api/v1/accounts/position-margin-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"symbol":"BTC-USDT","marginMode":"ISOLATED","amountUnits":-50000000,"referenceId":"iso-margin-remove-1001-1","reason":"REMOVE_POSITION_MARGIN"}'
```

`amountUnits` 为正数时，从 `availableUnits` 转入 `lockedUnits` 并增加
`account_position_margins.margin_units`；为负数时，把逐仓持仓保证金释放回可用余额。
减少保证金必须依赖最新 risk position snapshot，且减少后逐仓权益必须高于维持保证金加
`surprising.account.position-margin.removal-buffer-ppm` 安全缓冲。
手动逐仓保证金调整成功后，account-provider 会写一条 `POSITION_UPDATED` 事务 outbox，
其中 `tradeId=0`。下游 risk 和 WebSocket 消费者应把它当成持仓状态变更触发，重新读取最新
持仓/风险状态，不要把它解释成一笔成交。

管理员余额调整：

```bash
curl -X POST 'http://localhost:9086/api/v1/accounts/admin/balance-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"asset":"USDT","amountUnits":100000000,"referenceId":"deposit-1001-1","reason":"INITIAL_DEPOSIT"}'
```

后台操作员应通过 gateway 使用 admin namespace：

- `GET /api/v1/admin/accounts/balances`
- `GET /api/v1/admin/accounts/product-balances`
- `GET /api/v1/admin/accounts/positions`
- `GET /api/v1/admin/accounts/ledger`
- `GET /api/v1/admin/accounts/product-ledger`
- `GET /api/v1/admin/accounts/transfers`
- `POST /api/v1/admin/accounts/balance-adjustments`
- `POST /api/v1/admin/accounts/product-balance-adjustments`
- `GET /api/v1/admin/accounts/adjustments`

其中 `ledger`、`product-ledger`、`transfers` 和 `adjustments` 支持生产后台统一游标分页参数 `limit`、`cursor`、`sort`。排序白名单为 `createdAt.desc` 和 `createdAt.asc`，响应保留原列表字段并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`，便于后台通过 gateway 做大账户历史明细翻页。

admin namespace 要求 gateway 注入 `X-Admin-User-Id`，会记录 `X-Admin-Username`，并在余额变更同一事务中写入
`account_admin_balance_adjustments`。生产中 admin API 必须只允许充值系统、清结算系统或受控后台调用。

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `account_sequences`
- `account_balances`
- `account_deficits`
- `account_ledger_entries`
- `account_admin_balance_adjustments`
- `account_margin_reservations`
- `account_position_margins`
- `account_positions`
- `account_processed_trades`

核心索引：

- `account_ledger_reference_uidx`
- `account_ledger_liquidation_fee_order_idx`
- `account_deficits_user_idx`
- `account_margin_reservations_user_idx`
- `account_position_margins_user_idx`
- `account_positions_user_idx`
- `account_processed_trades_symbol_idx`
- `account_outbox_pending_key_idx`

## 配置

```yaml
surprising:
  account:
    kafka:
      product-line: LINEAR_PERPETUAL
      product-topics-enabled: true
      match-trades-topic: surprising.linear-perp.match.trades.v1
      position-events-topic: surprising.linear-perp.account.position.events.v1
      liquidation-fee-events-topic: surprising.linear-perp.account.liquidation-fee.events.v1
      concurrency: 2
      max-poll-records: 500
    settlement:
      match-trade-user-lock-stripes: 4096
    outbox:
      batch-size: 200
      publish-delay-ms: 200
      async-enabled: true
      max-in-flight: 32
      max-rows-per-key: 32
      send-timeout: 3s
    cache:
      contract-spec-max-entries: 4096
```

启动独立产品线实例时，把 `product-line` 设置为 `SPOT`、`LINEAR_PERPETUAL`、`LINEAR_DELIVERY` 或 `OPTION`。`product-topics-enabled=false` 时仍可使用 legacy topic。

本地缓存只用于不可变读快照：

- `contract-spec-max-entries` 按 `(symbol, instrumentVersion)` 缓存合约数学配置。

余额、持仓、保证金冻结、成交幂等、ledger 和 outbox 状态不会缓存，仍然以 PostgreSQL 为准。

account outbox 发布不改变 Kafka key，并按 `topic + event_key` claim 有界连续到期前缀。
同 key 仍按 id 顺序发送，不同 key 可以并发发送。

account match-trade consumer 会通过 Actuator/Prometheus 暴露以下指标：

- `surprising.account.match_trade.events{outcome=processed|duplicate|failed}`
- `surprising.account.match_trade.processing{outcome=...}`
- `surprising.account.match_trade.event_lag{outcome=...}`
- `surprising.account.match_trade.user_lock_wait`

压测和生产排障时，把这些指标与 Kafka consumer lag、PostgreSQL 延迟一起看，才能区分瓶颈是在 Kafka fetch、account 结算 SQL、重复重放，还是下游 outbox/fanout。consumer 并行度仍受 Kafka 分区限制：match trade 按 `symbol` 做 key，同一个热门 symbol 必须在一个分区内有序结算，不同 symbol 才能并行。
进入 `processTradeIfNew(...)` 前，listener 会对 taker/maker 做确定性用户级 stripe 锁。这样同一用户跨不同 symbol 的 match-trade 结算不会并发执行，不相关用户仍然可以并行结算。`surprising.account.match_trade.user_lock_wait` 应保持低位；如果持续升高，说明热点用户或做市账号已经在 account 结算层排队。
match-trade listener 使用 Spring Kafka batch delivery 和 `AckMode.BATCH`，减少每条记录单独提交 offset 的开销。批次里的每条记录仍然独立调用 `processTradeIfNew(...)`，所以 PostgreSQL 事务、`(symbol, trade_id)` 幂等、symbol 内顺序和用户级结算锁仍是正确性边界。批次中某条失败时 Kafka 会重投整个批次，已经结算成功的成交会被 processed-trade key 跳过。
`surprising.account.kafka.client-id` 要给每个 account-provider pod 配成稳定且唯一的值。`surprising.account.kafka.group-id` 仍然要在同一服务副本间保持一致；唯一 client id 只用于 consumer group 观测和节点级故障排查。

## 本地运行

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-account-provider -am spring-boot:run
```

端口：

- `9086`：账户和持仓服务。

## 生产注意事项

- 余额调整必须携带全局唯一 `referenceId`，防止充值/冲正重复入账。同一 reference 的重放只有在 `amountUnits` 和 `reason` 与原流水一致时才会幂等返回；payload 不一致会在改余额前失败。
- 账户 provider 消费撮合成交时必须按 `(symbol, trade_id)` 幂等，不能只按裸 `tradeId` 去重。
- 调高 `surprising.account.kafka.concurrency` 时不能移除 match-trade 用户级结算保护。同一用户跨 symbol 的资金结算必须在 account-provider 层串行；PostgreSQL 行锁和非负约束是最后防线，不应该作为主要排队机制。
- 订单入口会在发布撮合命令前冻结初始保证金。账户 provider 消费成交后，按实际成交价计算开仓保证金并迁移为持仓保证金，委托价或市价保护价多冻结的部分释放回可用余额。
- `account_positions`、`account_position_margins`、`account_margin_reservations` 都会持久化 `margin_mode`。不要从事件或查询里丢掉这个字段；后续逐仓风控依赖它。
- 用户逐仓保证金调整按 `referenceId` 幂等，并写入 `account_ledger_entries.reference_type = POSITION_MARGIN_ADJUSTMENT`。正向调整只把可用余额转入持仓保证金；负向调整必须先校验最新逐仓风险快照，再释放持仓保证金。
- 平仓成交按平仓数量比例释放持仓保证金。这条链路必须保持 long-only，并与 exchange-core 的 ticks/steps 一致。
- reduce-only 剪枝不是撮合层功能；它依赖 account-provider 在持仓更新事务里锁定相关订单并发布 cancel command。多节点部署时必须保证所有 account-provider 使用同一 PostgreSQL 和按 symbol 分区的 order command topic。
- reduce-only 剪枝遇到 `Long.MIN_VALUE` 这类不可能的 signed quantity 必须 fail-fast，不能让容量数学回绕后基于负绝对值错误撤单或保留挂单。
- 如果出现 `missing order margin reservation for opening fill`，要检查 order-provider 是否跳过冻结、matching 是否处理了不应存在的订单、或数据库中 reservation 是否被人工改动。
- 如果出现 `missing order margin reservation for closing fill`，要检查是否有非 reduce-only 的平仓/翻仓订单在没有 order-provider reservation 事务的情况下被接受。
- 已实现亏损可以扣 `availableUnits` 和由持仓保证金支撑的 `lockedUnits`，但不能扣未成交订单冻结。只要扣了持仓保证金支撑的 locked，就必须在同一事务内同步减少 `account_position_margins`。
- 手续费扣款复用已实现亏损的余额/deficit 安全路径。手续费返佣先清理 deficit，再增加 available balance。结算时必须读取 `trading_orders` 上的费率快照，不能读取当前用户等级或当前 instrument 费率重算历史订单。
- 余额结算会锁定 `account_deficits` 保证权益计算一致，但当 `deficit_units` 没有变化时会跳过 `UPDATE account_deficits`。不要在成交热路径重新引入无变化的 deficit 写入；真正产生或清理 deficit 时仍必须写入并检查 1 行。
- match-trade 结算在计算下一版持仓前已经锁定当前持仓。后续维护时要继续使用传入已锁定旧持仓数量的更新路径；如果每个成交侧再额外做一次 `SELECT ... FOR UPDATE` 或更新后回查，会给每个成交侧增加两次不必要 SQL 往返。
- match-trade Kafka 消费故意使用批量投递，并在 listener 批次成功后提交 offset。不要把整个 Kafka 批次放进一个大数据库事务里做资金结算；那会拉长锁持有时间并扩大回滚影响。这里的批量只用于传输和 ack 优化。
- 强平费扣款故意不创建新的 `account_deficits`。保险基金只接收 account-provider 已经从用户 collateral 实际收上的金额，避免把未收上的惩罚费记成保险基金收入。
- `surprising.account.liquidation-fee.events.v1` 是 at-least-once 投递。insurance 消费端必须使用 `(reference_type, reference_id, asset)` 幂等，其中 `reference_id = tradeId:orderId`。
- `contract_type` 决定已实现盈亏公式：`LINEAR_PERPETUAL` 使用 `signedQty * (exitTicks - entryTicks) * notional_multiplier_units`；`INVERSE_PERPETUAL` 使用 `signedQty * faceValueUnits * settleScaleUnits * (exitTicks - entryTicks) / (entryTicks * exitTicks * price_tick_units)`。
- 维持保证金和未实现盈亏由 risk 模块计算。资金费率、保险基金和自动减仓由独立结算模块处理，不放在本 provider 内。

## 验证

```bash
mvn -pl :surprising-account-provider -am test
```
