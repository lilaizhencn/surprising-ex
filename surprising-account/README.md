# surprising-account


Surprising Exchange 账户和产品结算模块。当前实现 long-based 基础余额、产品账户、余额流水、成交幂等处理、现货资产结算、永续/交割/期权持仓更新、成交后订单保证金到持仓保证金的迁移、期权买卖双方权利金、maker/taker 手续费结算、资金费结算、强平成交后的强平费收取和保险基金入账，以及带不可变结算价的交割结算和欧式现金行权。

## 模块

- `surprising-account-api`：账户/持仓 RPC 合约和 DTO。
- `surprising-account-provider`：账户 HTTP/Kafka 入口、Aeron Core 命令/查询网关，以及成交侧结算编排；不直接持有或更新实时余额。

## 资金权威边界

- `surprising-aeron-core` 中的 `CoreUserState` 是可用余额、冻结余额、订单预占、持仓、持仓保证金和杠杆的唯一在线权威状态。
- 余额调整、保证金调整、下单、撤单、成交、资金费、强平、交割和行权等资金变化都必须通过 Aeron Core Command，由 Core reducer 顺序裁决。
- 账户余额、产品余额和持仓查询通过 `AccountAeronGateway.userState()` 发送 `USER_STATE_QUERY`，不能从 PostgreSQL 当前余额表读取。
- Aeron Cluster 重启使用 Snapshot 加 Snapshot 之后的 Cluster Log Replay 恢复 Core 状态；账户数据库不是实时资金恢复源。
- PostgreSQL 只保存 Core Export 的异步账本、审计、对账和查询投影。投影延迟或失败不能改变 Core 的余额裁决。
- `init.sql` 中保留的 `account_balances`、`account_deficits`、`account_positions` 等表属于历史/投影/对账数据结构，不能重新作为在线资金写模型。

## 持久化边界

- Repository 只负责账本、审计和投影表的查询或幂等追加，不负责余额、冻结资金和持仓裁决。
- `AccountService` 的余额、产品余额和持仓接口通过 `AccountAeronGateway` 读取 Aeron Core 用户状态；`AccountQueryService` 只查询账本、划转和管理员调整记录。
- `AccountCommandGateway` 将余额调整、仓位模式和逐仓保证金调整编码为 Aeron Core Command；成功后再读取 Core 状态返回结果。
- 交易命令由 order-provider 直接提交 Aeron Core，Core 负责订单预占、释放、成交和用户资金状态变更；账户 Provider 不维护第二套可变余额状态。
- 账户启动时通过 Instrument 内部聚合 RPC 加载本产品线完整 JVM 快照，并消费
  `surprising.instrument.events.v1` 增量更新；账户运行时只从本地快照取得合约正文、结算资产与精度。
- 账户状态快照通过内部 RPC/Kafka 提供给 order、risk、liquidation 和 WebSocket 等下游，缓存只作为读模型，不能参与资金裁决。
- Core Export 将已提交的用户状态和资金事实发送给异步投影器；账本投影使用业务引用幂等，冲突必须停住，不能覆盖原始事实。
- 账户数据库连接池只服务异步投影、审计和历史查询；在线命令路径不得调用 JDBC Repository 或执行余额表 DML。
- 财务对账、订单时间线、运营报表和后台多表聚合查询不得访问交易主库。未来
  财务运营模块必须配置独立数据源和独立数据库，通过交易事件、outbox 或受控 CDC
  建立自己的查询投影；交易库 Repository 不为该模块提供跨表查询接口。

## long 单位

- 余额使用 `availableUnits`、`lockedUnits`、`equityUnits`，全部是资产最小单位的 long。
- 持仓使用 `signedQuantitySteps`，正数为净多，负数为净空。
- 持仓保存 `instrumentVersion`，当前敞口的合约数学固定到开仓时的版本。
- 持仓和持仓保证金都会保存 `marginMode`；单向净持仓链路下 `CROSS` 和 `ISOLATED` 都可执行。
- 持仓查询响应返回 `positionSide = NET`。Core 当前按 `userId + symbol + marginMode` 保存一条净持仓；
  `account_positions` 只是这份 Core 状态的异步投影，hedge-mode `LONG/SHORT` 持仓还没有进入当前在线模型。
- 开仓均价使用 `entryPriceTicks`。
- 持仓保证金由 CoreUserState 维护；`account_position_margins.margin_units` 只是异步投影字段。
- 已实现盈亏按 `realizedPnlUnits` 累计，单位是 instrument 的结算资产最小单位。U 本位线性合约使用 tick-step notional；币本位反向合约使用合约面值和入场/出场价格倒数公式。
- 交易手续费使用 `MatchTradeEvent` 必须携带的 `takerFeeRatePpm` / `makerFeeRatePpm`。正费率扣用户余额，负费率给用户返佣；账户结算热路径不再回查 `trading_orders` 费率。
- 强平费使用 `liquidation_orders.liquidation_fee_rate_ppm` 冻结费率。账户结算只扣实际可从用户保证金中收上的金额，并把已收金额发布给保险基金。
- 当亏损超过 `availableUnits + lockedUnits` 时，Core 维护超额亏损状态，不让在线余额变成负数；
  `account_deficits` 只保留异步投影/对账记录。

## 资金命令与结算

- 余额调整、保证金调整、仓位模式调整和其他账户写操作通过 `AccountAeronGateway` 提交 Aeron Core Command。
- order-provider 通过 Aeron Core 提交下单、撤单和改单；Core 负责订单预占、释放、成交、手续费、资金费、强平和结算对用户状态的修改。
- Core reducer 只在有序的 Aeron Cluster 日志中处理一次，`CoreUserState` 同时维护 `availableUnits`、`lockedUnits`、订单预占、持仓和持仓保证金。
- 成交的 taker/maker 两侧、翻仓、平仓和强平都在 Core 内完成资金守恒校验；数据库不参与在线事务，也不重新计算资金规则。
- 成交、资金费、余额调整和其他净权益变化由 Core Export 生成不可变事实，账本投影器异步写入 `account_ledger_entries` 或 `account_product_ledger_entries`。
- 订单预占和释放、可用余额与冻结余额之间的转移属于 Core 状态变化，不要求数据库余额表写入；`balance_after_units` 只能作为账本事实快照。
- 账本和管理员调整使用业务引用幂等；重复投影内容必须一致，冲突必须停住，不能静默覆盖历史事实。
- 账户状态快照通过内部 RPC/Kafka 提供给 order、risk、liquidation 和 WebSocket 等查询下游；条件单由 Aeron Core 直接读取同一份账户状态，不再由 trigger provider 消费账户快照。任何 JVM/Redis 快照都只是读模型，不是资金权威。
- `account_trade_settlement_sides`、`account_commands` 等数据库表只用于异步审计、投影和对账。任何数据库投影失败都不能回滚或改写已经提交的 Core 状态。

## 接口

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

`amountUnits` 为正数时，Aeron Core 将资金从 `availableUnits` 转入 `lockedUnits` 并增加对应持仓保证金；为负数时，Core 将逐仓持仓保证金释放回可用余额。
减少保证金必须依赖最新 risk position snapshot，且减少后逐仓权益必须高于维持保证金加
`surprising.account.position-margin.removal-buffer-ppm` 安全缓冲。
手动逐仓保证金调整成功后，account-provider 通过内部状态快照通知下游；其中 `tradeId=0` 不代表一笔成交。
下游应重新读取最新持仓/风险状态。

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

admin namespace 要求 gateway 注入 `X-Admin-User-Id`，会记录 `X-Admin-Username`。余额调整先提交 Aeron Core，
`account_admin_balance_adjustments` 只作为异步审计记录。生产中 admin API 必须只允许充值系统、清结算系统或受控后台调用。

## 数据库

根目录 [init.sql](../init.sql) 创建的账户表分为 Core 投影/对账表和历史审计表。它们不是实时余额权威来源，
在线账户命令不得对余额、冻结资金和持仓表执行 DML：

- 原生 PostgreSQL 账户 ID Sequence，覆盖异步账本投影、账户命令审计和恢复元数据
- `account_balances`（历史/投影表，非在线余额来源）
- `account_deficits`（历史/投影表，非在线风险裁决来源）
- `account_ledger_entries`
- `account_product_ledger_entries`
- `account_admin_balance_adjustments`
- `account_position_margins`（Core 持仓保证金的异步投影）
- `account_positions`（Core 持仓状态的异步投影）
- `account_state_order_locks`
- `account_commands`
- `account_trade_settlement_sides`
- `account_trade_settlement_completions`（只读视图）

核心索引：

- `account_ledger_reference_uidx`
- `account_ledger_liquidation_fee_order_idx`
- `account_deficits_user_idx`
- `account_position_margins_user_idx`
- `account_positions_user_idx`
- `account_commands_processing_idx`
- `account_commands_dependency_idx`
- `account_trade_settlement_sides_monitor_idx`

## 配置

```yaml
surprising:
  account:
    kafka:
      product-line: LINEAR_PERPETUAL
      position-events-topic: surprising.linear-perp.account.position.events.v1
      liquidation-fee-events-topic: surprising.linear-perp.account.liquidation-fee.events.v1
      account-state-events-topic: surprising.linear-perp.account.state.events.v1
      concurrency: 2
      max-poll-records: 500
    cache:
      contract-spec-max-entries: 4096
```

启动独立产品线实例时，把 `product-line` 设置为 `SPOT`、`LINEAR_PERPETUAL`、
`LINEAR_DELIVERY` 或 `OPTION`。账户消费的订单、成交、持仓、强平费、交割/行权和状态 Topic
始终按产品线隔离。

本地缓存只用于不可变读快照：

- `contract-spec-max-entries` 按 `(symbol, instrumentVersion)` 缓存合约数学配置。

余额、持仓和保证金冻结由 Aeron Core 的有序 Cluster Log 和 Core Snapshot 维护；account-provider
只通过 Aeron Client 提交命令和查询状态。`account-state-events-topic` 用于向下游广播完整账户快照，
下游 JVM/Redis 只建立读模型，不能反向写入 Core 资金状态。

PostgreSQL 连接池只供账本、审计、异步投影和历史查询使用。排障时同时观察 Aeron Cluster 状态、
Core Export backlog、Kafka 投影延迟和 PostgreSQL 投影延迟；数据库投影故障不能作为余额失败或余额恢复依据。

## 本地运行

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
# Topic 初始化命令待验证脚本重新整理后补回
mvn -pl :surprising-account-provider -am spring-boot:run
```

端口：

- `9086`：账户和持仓服务。

## 生产注意事项

- 余额调整必须携带全局唯一 `referenceId`，防止充值/冲正重复入账。同一 reference 的重放只有在 `amountUnits` 和 `reason` 与原流水一致时才会幂等返回；payload 不一致会在改余额前失败。
- 除 `AccountCommandGateway` 外不能绕过 Aeron Core 直接执行账户资金写入。不能重新引入账户余额、冻结资金或持仓表 DML。
- HTTP 超时表示结果未知，不代表失败。调用方必须使用原 `referenceId` 重试；新 reference 表示一笔新资金意图。
- 订单入口在校验后向 Aeron Core 发布下单命令；Core 冻结初始保证金，成交后按实际成交价迁移为持仓保证金，委托价或市价保护价多冻结的部分释放回可用余额。
- `trading_orders`、`account_positions`、`account_position_margins` 中的预占和持仓字段是投影/审计快照，不能作为实时资金来源。
- 用户逐仓保证金调整按 `referenceId` 幂等，并由 Aeron Core 把可用余额转入或从持仓保证金释放；`account_ledger_entries.reference_type = POSITION_MARGIN_ADJUSTMENT` 只记录审计事实。
- 平仓成交按平仓数量比例释放持仓保证金。这条链路必须保持 long-only，并与 exchange-core 的 ticks/steps 一致。
- reduce-only 剪枝不是撮合层或账户表写入功能；order-provider 按用户消费持仓事件，在自己的事务里锁定相关订单并发布按 symbol 分区的 cancel command。多节点部署时必须共享 PostgreSQL，并使用同一个 Kafka consumer group。
- reduce-only 剪枝遇到 `Long.MIN_VALUE` 这类不可能的 signed quantity 必须 fail-fast，不能让容量数学回绕后基于负绝对值错误撤单或保留挂单。
- 如果出现订单预占快照缺失或订单保证金核算不平，要检查 order-provider 是否漏写 `trading_orders.reserved_units` 快照、matching 是否丢失快照字段，以及 `account_trade_settlement_sides` 的消费/释放审计值。
- 已实现亏损可以扣 `availableUnits` 和由持仓保证金支撑的 `lockedUnits`，但不能扣未成交订单冻结；该状态转移必须由 Core reducer 原子完成，数据库只接收投影。
- 手续费扣款复用已实现亏损的余额/deficit 安全路径。手续费返佣先清理 deficit，再增加 available balance。matching 会把订单接受时的不可变费率快照写入 `MatchTradeEvent`；account 结算直接使用命令快照，不查询 `trading_orders`，也不能按当前用户等级重算。
- 亏空和权益结算由 Core reducer 维护；异步数据库投影不能增加 `SELECT ... FOR UPDATE`、余额表 UPDATE 或更新后回查，否则会把数据库重新带回资金热路径。
- Aeron Cluster 重启以 Core Snapshot 加 Snapshot 之后的 Cluster Log Replay 为准；数据库账本、审计和投影不能作为 Core 资金恢复源。
- 强平费扣款故意不创建新的 `account_deficits`。保险基金只接收 account-provider 已经从用户 collateral 实际收上的金额，避免把未收上的惩罚费记成保险基金收入。
- `surprising.linear-perp.account.liquidation-fee.events.v1` 是 at-least-once 投递。insurance 消费端必须使用 `(reference_type, reference_id, asset)` 幂等，其中 `reference_id = tradeId:orderId`。
- `contract_type` 决定已实现盈亏公式：`LINEAR_PERPETUAL` 使用 `signedQty * (exitTicks - entryTicks) * notional_multiplier_units`；`INVERSE_PERPETUAL` 使用 `signedQty * faceValueUnits * settleScaleUnits * (exitTicks - entryTicks) / (entryTicks * exitTicks * price_tick_units)`。
- 维持保证金和未实现盈亏由 risk 模块计算。资金费、保险基金和 ADL 模块保留各自编排状态，
  但最终账户资金变更只在本 provider 执行。

## 验证

```bash
mvn -pl :surprising-account-provider -am test
```
