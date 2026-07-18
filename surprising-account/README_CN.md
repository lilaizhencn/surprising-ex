# surprising-account

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 账户和产品结算模块。当前实现 long-based 基础余额、产品账户、余额流水、成交幂等处理、现货资产结算、衍生品持仓更新、成交后订单保证金到持仓保证金的迁移、maker/taker 手续费结算、资金费结算、交割/行权流水，以及强平成交后的实际强平费收取和保险基金入账事件。

## 模块

- `surprising-account-api`：账户/持仓 RPC 合约和 DTO。
- `surprising-account-provider`：余额、ledger、持仓、预占、亏空、资金费、ADL、交割/行权和成交侧结算的唯一写者。

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

## 单用户指令处理

所有资金变更统一进入产品线隔离的账户指令 Topic：

```text
surprising.<product-segment>.account.user.commands.v1
```

Kafka key 固定为 `<PRODUCT_LINE>:<userId>`。命令、DLT 和结果 Topic 都使用 32 个分区，
account-provider 使用 32 个 listener lane 和逐记录确认。同一产品线、同一用户的资金指令自然串行，
不再依赖应用层 stripe 锁；不同用户可以并行。订单、撮合、资金费、ADL、保险、交割/行权和 HTTP
写接口只产生账户命令，不能直接更新可变账户表。

撮合为同一成交分别产生 taker 和 maker 的 `TRADE_SIDE_SETTLE`。每一侧在对应用户的本地事务中更新：

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
- 持仓数量或版本变化后，account-provider 只发送带完整快照的持久化持仓事件。order-provider 按用户顺序消费，并在自己的事务里把事件发生前创建、反向、版本不一致或超过新持仓容量的未完成 reduce-only 订单写为 `CANCEL_REQUESTED`，再通过 order command outbox 发送撤单。
- 结算把持仓降为零时，trigger-provider 消费同一事件，按精确持仓范围取消不晚于关仓事件创建的全部 `PENDING` 止盈、止损和追踪止损。触发单更新与状态 outbox 在同一事务提交，Redis 在提交后清理；account-provider 不再写订单或触发单表。

`account_commands.command_id` 与不可变 envelope hash 是执行幂等键。
`account_trade_settlement_sides(product_line, symbol, trade_id, participant_role)` 在 taker/maker
各自资金事务末尾写一条不可变参与方记录，两个用户分区不再更新同一结算行；身份冲突会使整笔
事务回滚。`account_trade_settlement_completions` 只暴露双边均已完成的成交。监控通过仅包含
待核对记录的部分索引分批核对完成记录；单侧长时间未完成时 `accountTradeSettlement` health
会变为 `DOWN`。命令依赖持久化为
`depends_on_command_id`，正确性不依赖生产顺序或结果 Topic 顺序。完整设计见
[账户资金单写者与单用户串行通道](../docs/account-single-writer-command-lane_CN.md)。

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

- 原生 PostgreSQL 账户 ID Sequence，并由固定 10,000 ID 的 Hi/Lo 号段分配，覆盖账本、划转、
  订单预占、持仓/强平事件以及账户命令 outbox/result/retry 事件
- `account_balances`
- `account_deficits`
- `account_ledger_entries`
- `account_admin_balance_adjustments`
- `account_margin_reservations`
- `account_position_margins`
- `account_positions`
- `account_commands`
- `account_command_submissions`
- `account_trade_settlement_sides`
- `account_trade_settlement_completions`（只读视图）

核心索引：

- `account_ledger_reference_uidx`
- `account_ledger_liquidation_fee_order_idx`
- `account_deficits_user_idx`
- `account_margin_reservations_user_idx`
- `account_position_margins_user_idx`
- `account_positions_user_idx`
- `account_commands_processing_idx`
- `account_commands_dependency_idx`
- `account_trade_settlement_sides_monitor_idx`
- `account_outbox_pending_stream_idx`

## 配置

```yaml
surprising:
  account:
    kafka:
      product-line: LINEAR_PERPETUAL
      product-topics-enabled: true
      position-events-topic: surprising.linear-perp.account.position.events.v1
      liquidation-fee-events-topic: surprising.linear-perp.account.liquidation-fee.events.v1
      concurrency: 2
      user-command-concurrency: 32
      max-poll-records: 500
    trade-settlement:
      stale-after: 1m
    command-wait:
      timeout: 10s
      poll-delay-ms: 20
    outbox:
      batch-size: 1000
      publish-delay-ms: 20
      async-enabled: true
      max-in-flight: 32
      max-rows-per-key: 32
      send-window-size: 5
      send-timeout: 3s
    cache:
      contract-spec-max-entries: 4096
```

启动独立产品线实例时，把 `product-line` 设置为 `SPOT`、`LINEAR_PERPETUAL`、
`LINEAR_DELIVERY` 或 `OPTION`。账户命令、DLT 和结果 Topic 始终按产品线隔离，不提供共享 legacy
资金指令回退。

本地缓存只用于不可变读快照：

- `contract-spec-max-entries` 按 `(symbol, instrumentVersion)` 缓存合约数学配置。

余额、持仓、保证金冻结、命令幂等、ledger 和 outbox 状态仍以 PostgreSQL 为准。Redis 持仓只作为
带 revision 的可重放查询投影。

account outbox 发布不改变 Kafka key，并按 `topic + event_key` claim 有界连续到期前缀。
普通事件 Topic 会按 id 顺序一次提交最多 `send-window-size` 条，只把连续 ACK 成功的前缀批量确认为已发布。
account user command Topic 固定使用窗口 1，保证同一用户后一条资金指令不会越过尚未确认的前一条。
每轮成功 id 使用一条 SQL 批量确认。

`TRADE_SIDE_SETTLE` 仍会在 `account_commands` 中保留持久终态，但不再产生没有消费者的 command-result
outbox；订单冻结和资金费结算仍会为各自消费者发送结果事件。

账户资金指令默认并发为 32，Hikari 连接池默认 40，为 outbox、定时对账和请求流量预留 8 个连接。
多产品线、多副本部署时应一起调整 `ACCOUNT_USER_COMMAND_CONCURRENCY` 和
`ACCOUNT_DB_MAX_POOL_SIZE`，并使用事务级连接池代理控制数据库总连接预算。

账户指令消费者通过 Actuator/Prometheus 暴露：

- `surprising.account.command.events{outcome=applied|rejected|waiting_dependency|duplicate|failed}`
- `surprising.account.command.processing{outcome=...}`
- `surprising.account.command.event_lag{outcome=...}`
- `accountTradeSettlement` 单侧成交超时健康状态

排障时要与 Kafka lag、DLT 数量、PostgreSQL 延迟、等待依赖和 outbox 年龄一起观察。技术故障会持续
重试并阻塞所在分区，不会跳过资金指令；poison envelope 才进入相同分区号的 DLT。每个
account-provider pod 使用稳定且唯一的 client id，同产品线副本共享相同消费组。

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
- 除 `AccountUserCommandProcessor` 外不能调用账户写服务。CI 运行
  `scripts/check-account-single-writer.sh`，防止其他模块重新引入账户资金表 DML。
- 账户命令 Kafka key 不能从 `<PRODUCT_LINE>:<userId>` 改掉；并发数超过 32 个 Topic 分区不会增加吞吐。
- HTTP 超时表示结果未知，不代表失败。调用方必须使用原 `referenceId` 重试；新 reference 表示一笔新资金意图。
- 订单入口会在发布撮合命令前冻结初始保证金。账户 provider 消费成交后，按实际成交价计算开仓保证金并迁移为持仓保证金，委托价或市价保护价多冻结的部分释放回可用余额。
- `account_positions`、`account_position_margins`、`account_margin_reservations` 都会持久化 `margin_mode`。不要从事件或查询里丢掉这个字段；后续逐仓风控依赖它。
- 用户逐仓保证金调整按 `referenceId` 幂等，并写入 `account_ledger_entries.reference_type = POSITION_MARGIN_ADJUSTMENT`。正向调整只把可用余额转入持仓保证金；负向调整必须先校验最新逐仓风险快照，再释放持仓保证金。
- 平仓成交按平仓数量比例释放持仓保证金。这条链路必须保持 long-only，并与 exchange-core 的 ticks/steps 一致。
- reduce-only 剪枝不是撮合层或账户表写入功能；order-provider 按用户消费持仓事件，在自己的事务里锁定相关订单并发布按 symbol 分区的 cancel command。多节点部署时必须共享 PostgreSQL，并使用同一个 Kafka consumer group。
- reduce-only 剪枝遇到 `Long.MIN_VALUE` 这类不可能的 signed quantity 必须 fail-fast，不能让容量数学回绕后基于负绝对值错误撤单或保留挂单。
- 如果出现 `missing order margin reservation for opening fill`，要检查 order-provider 是否跳过冻结、matching 是否处理了不应存在的订单、或数据库中 reservation 是否被人工改动。
- 如果出现 `missing order margin reservation for closing fill`，要检查是否有非 reduce-only 的平仓/翻仓订单在没有 order-provider reservation 事务的情况下被接受。
- 已实现亏损可以扣 `availableUnits` 和由持仓保证金支撑的 `lockedUnits`，但不能扣未成交订单冻结。只要扣了持仓保证金支撑的 locked，就必须在同一事务内同步减少 `account_position_margins`。
- 手续费扣款复用已实现亏损的余额/deficit 安全路径。手续费返佣先清理 deficit，再增加 available balance。matching 会把订单接受时的不可变费率快照写入 `MatchTradeEvent`；account 结算直接使用命令快照，不查询 `trading_orders`，也不能按当前用户等级重算。
- 余额结算会锁定 `account_deficits` 保证权益计算一致，但当 `deficit_units` 没有变化时会跳过 `UPDATE account_deficits`。不要在成交热路径重新引入无变化的 deficit 写入；真正产生或清理 deficit 时仍必须写入并检查 1 行。
- 成交侧结算在计算下一版持仓前已经锁定当前持仓。后续维护时继续使用传入已锁定旧持仓数量的
  更新路径；每侧额外做 `SELECT ... FOR UPDATE` 或更新后回查会增加两次不必要 SQL 往返。
- 不能根据跨 Topic 到达顺序推断依赖。必须持久化 `dependsOnCommandId`；结果 Topic 只用于降低延迟
  和观测，reconciliation 以 `account_commands` 为准。
- 强平费扣款故意不创建新的 `account_deficits`。保险基金只接收 account-provider 已经从用户 collateral 实际收上的金额，避免把未收上的惩罚费记成保险基金收入。
- `surprising.account.liquidation-fee.events.v1` 是 at-least-once 投递。insurance 消费端必须使用 `(reference_type, reference_id, asset)` 幂等，其中 `reference_id = tradeId:orderId`。
- `contract_type` 决定已实现盈亏公式：`LINEAR_PERPETUAL` 使用 `signedQty * (exitTicks - entryTicks) * notional_multiplier_units`；`INVERSE_PERPETUAL` 使用 `signedQty * faceValueUnits * settleScaleUnits * (exitTicks - entryTicks) / (entryTicks * exitTicks * price_tick_units)`。
- 维持保证金和未实现盈亏由 risk 模块计算。资金费、保险基金和 ADL 模块保留各自编排状态，
  但最终账户资金变更只在本 provider 执行。

## 验证

```bash
mvn -pl :surprising-account-provider -am test
```
