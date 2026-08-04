# surprising-account


Surprising Exchange 账户和产品结算模块。当前实现 long-based 基础余额、产品账户、余额流水、成交幂等处理、现货资产结算、永续/交割/期权持仓更新、成交后订单保证金到持仓保证金的迁移、期权买卖双方权利金、maker/taker 手续费结算、资金费结算、强平成交后的强平费收取和保险基金入账，以及带不可变结算价的交割结算和欧式现金行权。

## 模块

- `surprising-account-api`：账户/持仓 RPC 合约和 DTO。
- `surprising-account-provider`：余额、ledger、持仓、预占、亏空、资金费、ADL、交割/行权和成交侧结算的唯一写者。

## 持久化边界

- 写模型以“一张业务表对应一个 Repository”为目标，Repository 只负责本表 SQL，不编排跨表业务。
- `AccountQueryService` 只聚合账本、划转和后台调整等异步审计查询；余额、亏空、持仓和产品余额统一读取
  `AccountService` 的本地用户快照。
- `AccountCommandGateway` 统一接收余额调整、划转和资金命令，追加到用户分区 WAL，由本地 reducer 顺序裁决。
- `PositionRepository`、`PositionMarginRepository` 和 `PositionModeRepository` 只负责启动恢复快照所需的单表读取；
  成交侧结算状态由用户分区本地状态和异步审计投影维护。
- `AccountQueryService` 和持仓快照服务只聚合恢复/查询所需的单表 Repository；交易命令不再通过同步
  数据库服务编排，而是进入用户分区 WAL，由本地 reducer 串行执行。
- account-provider 通过 `/internal/v1/accounts/open-interest/snapshot` 提供当前产品线未平仓量启动快照；
  账户用户分区提交后发布带分片修订号的 Kafka 绝对值事件，其他模块不直接读取该表。
- 账户启动时通过 Instrument 内部聚合 RPC 加载本产品线完整 JVM 快照，并消费
  `surprising.instrument.events.v1` 增量更新；`AccountInstrumentRepository`、资产精度读取和持仓投影
  只从本地快照取得合约正文、结算资产与精度，不再读取 Instrument 相关表。
- 现货预占、成交结算和衍生品资金命令都使用同一用户分区 WAL 入口；产品线规则在 reducer 内隔离，
  不通过同步 Repository 组合业务状态。
- ADL、亏空回补和资金费统一提交账户用户分区命令；账户 reducer 是唯一资金写者，数据库只保留异步投影、
  启动恢复和审计入口。
- `PositionCacheCoordinator` 只把本地账户持仓快照重放到 Redis；在线持仓读取直接使用账户 JVM 快照，
  Redis 只作为跨节点读模型和恢复投影，不参与资金裁决。
- 账户状态事件由用户分区 WAL 在本地提交后直接发布 Kafka，数据库不再作为热路径 outbox；
  `AccountStateProjectionConsumer` 使用独立消费组把完整状态异步投影到余额、负债、持仓、逐仓保证金、
  仓位模式和订单锁定汇总表，重复或过期修订号幂等忽略。
- 账户持仓事件同时进入按产品线隔离的 `PositionSnapshotCache`，按精确持仓键进行 revision 防回退。
  该 JVM 快照是订单、风控和强平实时计算输入；数据库只接受异步投影，正式部署仍必须遵循
  [账户单写者与单用户串行通道](../docs/account-single-writer-command-lane.md)。
- 账户用户分区执行器只保留命令幂等、顺序、资金守恒和崩溃恢复编排，不在生产热路径构造 JDBC Repository；
  数据库写入由独立异步投影器完成。
- `account_commands` 审计和 `account_product_ledger_entries` 账本使用独立的 WAL 投影水位；账本投影
  失败不会推进账本水位，也不会让下一轮重复扫描已经成功的历史明细。
- 只有在线正确性无法拆分的路径允许多表 Repository，并必须写明 `不可拆原因`：
  余额与亏空的联合锁、余额变更与幂等流水的单语句快速路径、持仓模式切换前的未结算成交检查，
  以及 Redis 最终状态投影。这些路径都禁止复用于后台时间线、财务对账或运营报表。
- 财务对账、订单时间线、运营报表和后台多表聚合查询不得访问交易主库。未来
  `surprising-finance-ops` 必须配置独立数据源和独立数据库，通过交易事件、outbox 或受控 CDC
  建立自己的查询投影；交易库 Repository 不为该模块提供跨表查询接口。

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
account-provider 使用独立的批量 listener factory、32 个 listener lane 和批次确认；每次 poll 最多 500 条命令，
命令先进入用户分区 WAL，由本地 reducer 和 RocksDB 状态库按序处理。同一产品线、同一用户的资金指令自然串行，
不再依赖应用层 stripe 锁；不同用户可以并行。订单、撮合、资金费、ADL、保险、交割/行权和 HTTP
写接口只产生账户命令，不能直接更新可变账户表。

撮合为同一成交分别产生 taker 和 maker 的 `TRADE_SIDE_SETTLE`。每一侧在对应用户的本地事务中更新：

- taker 用户持仓，方向使用 `takerSide`。
- maker 用户持仓，方向为 taker 的反方向。
- 成交中的开仓数量只会把按实际成交价计算出的初始保证金迁移到 `account_position_margins`；委托价改善或市价保护价多冻结的部分会释放回 `availableUnits`。
- 成交中的平仓数量会按比例把旧持仓保证金从 `lockedUnits` 释放回 `availableUnits`。
- 成交、资金费、余额调整和其他已接入命令的净权益变化会随本地终态生成不可变账本增量，由
  `AccountLedgerProjectionService` 异步写入 `account_product_ledger_entries`；成交手续费和已实现盈亏
  先在同一用户 reducer 中合并到余额变化，数据库投影不重新计算资金规则。
- 订单预占、释放以及持仓保证金在可用/锁定之间的转移不产生净权益账本行；逐仓保证金调整会保留带
  `symbol` 的审计明细。账本投影使用 `(reference_type, reference_id, user_id, account_type, asset)`
  幂等键，重复投影内容不变，内容冲突直接失败关闭。
- 如果成交订单是强平订单，account-provider 还会写 `reference_type = LIQUIDATION_FEE` 的强平费流水。扣款按实际可收 collateral 封顶：全仓可使用同结算资产的可用余额和全仓持仓保证金；逐仓只使用该逐仓持仓保证金。收不上的部分不会生成新的 deficit，也不会进入保险基金。
- 强平费收取成功后会通过 account transactional outbox 发送到 `surprising.<product-segment>.account.liquidation-fee.events.v1`，Kafka key 是结算资产。insurance-provider 消费后按 `tradeId:orderId` 幂等写入 `insurance_fund_ledger(reference_type = LIQUIDATION_FEE)`。
- 翻仓成交先平旧仓，再把剩余成交数量作为新仓处理。
- 如果成交导致翻仓，已实现盈亏使用旧持仓版本计算，翻仓后剩余新仓使用成交的 `instrumentVersion`。
- 开仓成交必须携带订单接受时的不可变预占快照，并把实际成交保证金从 `account_balances.locked_units` 迁移到 `account_position_margins`；缺失快照或 reduce-only 订单出现开仓数量都会失败并回滚。
- `account_trade_settlement_sides` 记录每侧成交已消费/已释放的订单保证金；终态 `ORDER_RELEASE` 用该审计值释放余额表中的剩余冻结，不查询独立预占记录。
- 持仓更新、余额更新、发生数值变化的 deficit 更新、PnL/fee ledger 插入/回填、订单保证金释放、持仓保证金增减都要求写入 1 行。任何异常都不应静默跳过。
- 持仓数量或版本变化后，account-provider 只发送带完整快照的持久化持仓事件。order-provider 按用户顺序消费，
  在本地订单状态机中标记需要撤销的 reduce-only 订单，再通过订单用户 WAL 发布撤单命令。
- 结算把持仓降为零时，trigger-provider 消费同一事件，按精确持仓范围更新本地索引并发布撤单命令；Redis 只做跨节点投影，
  account-provider 不写订单或触发单表。

`account_commands.command_id` 与不可变 envelope hash 是执行幂等键。
`account_trade_settlement_sides(product_line, symbol, trade_id, participant_role)` 在 taker/maker
各自资金事务末尾写一条不可变参与方记录，两个用户分区不再更新同一结算行；身份冲突会使整笔
事务回滚。`account_trade_settlement_completions` 只暴露双边均已完成的成交。监控通过仅包含
待核对记录的部分索引分批核对完成记录；单侧长时间未完成时 `accountTradeSettlement` health
会变为 `DOWN`。命令依赖持久化为
`depends_on_command_id`，正确性不依赖生产顺序或结果 Topic 顺序。完整设计见
[账户资金单写者与单用户串行通道](../docs/account-single-writer-command-lane.md)。

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

`amountUnits` 为正数时，从 `availableUnits` 转入 `lockedUnits` 并增加
`account_position_margins.margin_units`；为负数时，把逐仓持仓保证金释放回可用余额。
减少保证金必须依赖最新 risk position snapshot，且减少后逐仓权益必须高于维持保证金加
`surprising.account.position-margin.removal-buffer-ppm` 安全缓冲。
手动逐仓保证金调整成功后，account-provider 会在用户分区 WAL 提交一条 `POSITION_UPDATED` 事件，
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

- 原生 PostgreSQL 账户 ID Sequence，覆盖异步账本投影、账户命令审计和恢复元数据
- `account_balances`
- `account_deficits`
- `account_ledger_entries`
- `account_product_ledger_entries`
- `account_admin_balance_adjustments`
- `account_position_margins`
- `account_positions`
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
      product-topics-enabled: true
      position-events-topic: surprising.linear-perp.account.position.events.v1
      liquidation-fee-events-topic: surprising.linear-perp.account.liquidation-fee.events.v1
      account-state-events-topic: surprising.linear-perp.account.state.events.v1
      concurrency: 2
      user-command-concurrency: 32
      max-poll-records: 500
    trade-settlement:
      stale-after: 1m
    command-wait:
      timeout: 10s
      poll-delay-ms: 20
    cache:
      contract-spec-max-entries: 4096
```

启动独立产品线实例时，把 `product-line` 设置为 `SPOT`、`LINEAR_PERPETUAL`、
`LINEAR_DELIVERY` 或 `OPTION`。账户命令、DLT 和结果 Topic 始终按产品线隔离；OPTION 在
账户 reducer 完成前只允许生命周期事件边界，不允许普通下单资金热路径。

本地缓存只用于不可变读快照：

- `contract-spec-max-entries` 按 `(symbol, instrumentVersion)` 缓存合约数学配置。

余额、持仓、保证金冻结、命令幂等和账本事实由用户分区本地 WAL/状态库维护；PostgreSQL 只作为异步
投影、启动恢复和审计存储。当前产品线账户命令成功后发布 `PerpetualAccountStateUpdatedEvent` 到
`account.state.events.v1`（按用户键压缩）发布完整快照，下游每个 JVM 使用独立消费组按用户修订号建立本地快照。
账户命令消费者在 WAL 落盘后立即调用同一用户分区 reducer，只有状态提交和结果发布成功才确认 Kafka 位点；
定时任务只负责恢复进程崩溃后已经落盘的事实。

Kafka 发布沿用用户分区 key；发布失败时本地结果库和 WAL 保留待重试状态，不提交后续用户分区序号。

`TRADE_SIDE_SETTLE` 的终态通过用户分区结果库和 Kafka 结果事件保存；`account_commands` 只保留异步审计副本。
订单冻结和资金费结算通过用户分区结果事件更新各自本地状态，数据库不参与在线裁决。

账户资金指令默认并发为 32，Hikari 连接池仅供异步投影、恢复和查询使用。
多产品线、多副本部署时应一起调整 `ACCOUNT_USER_COMMAND_CONCURRENCY` 和
`ACCOUNT_DB_MAX_POOL_SIZE`，并使用事务级连接池代理控制数据库总连接预算。

账户指令消费者通过 Actuator/Prometheus 暴露：

- `surprising.account.command.events{outcome=applied|rejected|duplicate|failed}`
- `surprising.account.command.processing{outcome=...}`
- `surprising.account.command.event_lag{outcome=...}`
- `accountTradeSettlement` 单侧成交超时健康状态

排障时要与 Kafka lag、DLT 数量、PostgreSQL 投影延迟和等待依赖一起观察。技术故障会持续
重试并阻塞所在分区，不会跳过资金指令；poison envelope 才进入相同分区号的 DLT。每个
account-provider 进程使用稳定且唯一的 client id，同产品线副本共享相同消费组。

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
- 除 `AccountCommandGateway` 和 `AccountUserStateCommandWorker` 外不能调用账户写服务。CI 运行
  `scripts/check-account-single-writer.sh`，防止其他模块重新引入账户资金表 DML。
- 账户命令 Kafka key 不能从 `<PRODUCT_LINE>:<userId>` 改掉；并发数超过 32 个 Topic 分区不会增加吞吐。
- HTTP 超时表示结果未知，不代表失败。调用方必须使用原 `referenceId` 重试；新 reference 表示一笔新资金意图。
- 订单入口会在发布撮合命令前冻结初始保证金。账户 provider 消费成交后，按实际成交价计算开仓保证金并迁移为持仓保证金，委托价或市价保护价多冻结的部分释放回可用余额。
- `account_positions`、`account_position_margins` 和 `trading_orders` 的不可变预占快照都会保留 `margin_mode`/账户范围；后续逐仓风控依赖这些字段。
- 用户逐仓保证金调整按 `referenceId` 幂等，并写入 `account_ledger_entries.reference_type = POSITION_MARGIN_ADJUSTMENT`。正向调整只把可用余额转入持仓保证金；负向调整必须先校验最新逐仓风险快照，再释放持仓保证金。
- 平仓成交按平仓数量比例释放持仓保证金。这条链路必须保持 long-only，并与 exchange-core 的 ticks/steps 一致。
- reduce-only 剪枝不是撮合层或账户表写入功能；order-provider 按用户消费持仓事件，在自己的事务里锁定相关订单并发布按 symbol 分区的 cancel command。多节点部署时必须共享 PostgreSQL，并使用同一个 Kafka consumer group。
- reduce-only 剪枝遇到 `Long.MIN_VALUE` 这类不可能的 signed quantity 必须 fail-fast，不能让容量数学回绕后基于负绝对值错误撤单或保留挂单。
- 如果出现订单预占快照缺失或订单保证金核算不平，要检查 order-provider 是否漏写 `trading_orders.reserved_units` 快照、matching 是否丢失快照字段，以及 `account_trade_settlement_sides` 的消费/释放审计值。
- 已实现亏损可以扣 `availableUnits` 和由持仓保证金支撑的 `lockedUnits`，但不能扣未成交订单冻结。只要扣了持仓保证金支撑的 locked，就必须在同一事务内同步减少 `account_position_margins`。
- 手续费扣款复用已实现亏损的余额/deficit 安全路径。手续费返佣先清理 deficit，再增加 available balance。matching 会把订单接受时的不可变费率快照写入 `MatchTradeEvent`；account 结算直接使用命令快照，不查询 `trading_orders`，也不能按当前用户等级重算。
- 余额结算会锁定 `account_deficits` 保证权益计算一致，但当 `deficit_units` 没有变化时会跳过 `UPDATE account_deficits`。不要在成交热路径重新引入无变化的 deficit 写入；真正产生或清理 deficit 时仍必须写入并检查 1 行。
- 成交侧结算在用户分区 reducer 中按上一版 JVM 快照计算下一版状态。异步数据库投影不能重新
  增加 `SELECT ... FOR UPDATE` 或更新后回查，否则会把数据库重新带回资金热路径。
- 不能根据跨 Topic 到达顺序推断依赖。必须持久化 `dependsOnCommandId`；结果 Topic 只用于降低延迟
  和观测，恢复以用户 WAL、RocksDB 状态库和结果库为准，`account_commands` 只用于异步审计。
- 强平费扣款故意不创建新的 `account_deficits`。保险基金只接收 account-provider 已经从用户 collateral 实际收上的金额，避免把未收上的惩罚费记成保险基金收入。
- `surprising.linear-perp.account.liquidation-fee.events.v1` 是 at-least-once 投递。insurance 消费端必须使用 `(reference_type, reference_id, asset)` 幂等，其中 `reference_id = tradeId:orderId`。
- `contract_type` 决定已实现盈亏公式：`LINEAR_PERPETUAL` 使用 `signedQty * (exitTicks - entryTicks) * notional_multiplier_units`；`INVERSE_PERPETUAL` 使用 `signedQty * faceValueUnits * settleScaleUnits * (exitTicks - entryTicks) / (entryTicks * exitTicks * price_tick_units)`。
- 维持保证金和未实现盈亏由 risk 模块计算。资金费、保险基金和 ADL 模块保留各自编排状态，
  但最终账户资金变更只在本 provider 执行。

## 验证

```bash
mvn -pl :surprising-account-provider -am test
```
