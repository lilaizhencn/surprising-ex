# 数据库设计

在仓库根目录初始化新的 PostgreSQL 数据库：

```bash
brew services start postgresql@18
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

本地集成测试使用 `localhost:5432` 上由 Homebrew 管理的 PostgreSQL。服务管理和调优命令见
[本地 Homebrew 中间件](local-homebrew-infra.md)。

## 管理网关表

`gateway_admin_operation_logs.duration_ms` 保存管理网关代理请求耗时，作为本地安全审计证据。
跨表指标、导出和长时间运行的报表查询不存放在网关中，也不允许查询交易主库；这些能力属于使用
独立数据库的财务运营模块。

## `instruments`

每一行保存一个可交易产品的一个不可变版本：

- `symbol`：规范化交易对，例如 `BTC-USDT`。
- `version`：不可变配置版本。
- `instrument_type` / `contract_type`：产品和合约类型。
- `base_asset` / `quote_asset` / `settle_asset`：基础资产、报价资产和结算资产。
- `price_tick_units` / `quantity_step_units`：使用资产最小单位表示的价格档位和数量步长。
- `min_quantity_steps` / `max_quantity_steps` / `min_notional_units` /
  `max_notional_units`：使用长整型单位表示的订单边界。
- `notional_multiplier_units`：`LINEAR_PERPETUAL` 表示每个
  `priceTick * quantityStep` 对应的结算资产单位；`INVERSE_PERPETUAL` 表示每个合约步长的报价面值。
- `supported_order_types` / `supported_time_in_force`：下单约束。
- `max_leverage_ppm`、保证金率、Maker/Taker 手续费率、资金费率和
  `impact_notional_units`：风控、手续费和资金费计算输入。
- `user_open_interest_limit_rate_ppm` / `user_open_interest_limit_floor_units`：
  单用户动态持仓上限。下单入口计算
  `max(platformOpenInterestNotional * rate / 1_000_000, floor)`，并同时应用
  `max_position_notional_units`。
- `status`：`PRE_TRADING`、`TRADING`、`HALT`、`SETTLING` 或 `CLOSED`。

主键：

```sql
PRIMARY KEY (symbol, version)
```

## `instrument_current_versions`

每个 `symbol` 一行，指向当前生效配置版本：

```sql
PRIMARY KEY (symbol)
```

下游服务应通过该表读取当前快照，历史版本继续保存在 `instruments`。

## `instrument_symbol_sequences`

按 `symbol` 原子分配下一个不可变合约版本：

```sql
PRIMARY KEY (symbol)
```

管理端写入使用该表，而不是 `MAX(version) + 1`，避免多个 instrument-provider 节点并发修改时
分配出相同版本。

## `instrument_risk_brackets`

保存指定 `symbol + version` 的风险限额档位。撮合和风控服务根据持仓名义价值解析最大杠杆和保证金率。

## `instrument_index_sources`

保存指数价格使用的外部现货源配置：

- REST 地址与解析器。
- WebSocket 地址与订阅载荷。
- 源报价币种与目标报价币种。
- USD/USDT 换算源及换算方向。
- `weight_ppm`、`fallback_weight_multiplier_ppm` 和启用状态。

指数价格服务动态读取该表。

## `candlestick_candles`

每一行表示一根 OHLCV K 线：

- `symbol`：规范化交易对。
- `period`：周期，例如 `1m`、`5m`、`1h`、`1d`。
- `open_time` / `close_time`：左闭右开的 UTC 时间桶。
- `open_price` / `high_price` / `low_price` / `close_price`：开、高、低、收价格。
- `base_volume`：成交数量之和。
- `quote_volume`：`price * quantity` 之和。
- `trade_count`：去重后的有效成交数。
- `first_trade_id` / `last_trade_id`：开收价格审计字段。
- `first_sequence` / `last_sequence`：回放检查字段。
- `source_partition` / `source_offset`：最后一次修改该 K 线的 Kafka 位点。

```sql
PRIMARY KEY (symbol, period, open_time)
```

该主键支持快速区间查询和完整快照的幂等更新。历史数据量很大时应保持逻辑表不变，使用 PostgreSQL
原生时间分区；服务代码不需要拼接动态表名。

## `price_index_ticks`

每一行表示一份指数价格计算快照：

- `symbol`：合约交易对。
- `sequence`：数据库按交易对分配的单调递增序号。
- `index_price`：加权公平现货指数价格。
- `status`：`HEALTHY`、`DEGRADED`、`STALE`、`INSUFFICIENT_SOURCES` 或 `CLAMPED`。
- `component_count` / `valid_component_count`：配置源数量及过期、异常值过滤后的有效源数量。
- `total_configured_weight`：原始配置权重总和。
- `event_time`：计算时间。

```sql
PRIMARY KEY (symbol, sequence)
CREATE INDEX price_index_ticks_query_idx ON price_index_ticks (symbol, event_time DESC);
CREATE INDEX price_index_ticks_event_time_brin ON price_index_ticks USING BRIN (event_time);
```

指数服务先发布完整 Kafka 事件，独立审计消费者再批量写入本表和 `price_index_components`。
两表只保留三天，不能作为实时价格输入。

## `price_index_components`

每次指数计算的每个外部价格源保存一行审计记录，包含源价格、配置权重、有效权重、状态、源时间戳、
延迟和拒绝原因。

```sql
PRIMARY KEY (symbol, sequence, source)
```

## `price_symbol_leases`

协调价格服务的多节点主主运行：

- `module`：发布者命名空间，例如 `price-index` 或 `price-mark`。
- `symbol`：交易对。
- `owner_id`：当前发布节点。
- `lease_until`：租约截止时间，节点停止续约后其他节点才可接管。
- `updated_at`：本地更新时间。

```sql
PRIMARY KEY (module, symbol)
```

## `price_symbol_sequences`

为多节点价格服务分配全局单调序号。失败后允许出现序号空洞，但同一个 `module + symbol` 的序号
绝不能倒退。

```sql
PRIMARY KEY (module, symbol)
```

## `price_exchange_rates`

每行保存最新的外汇或稳定币桥接汇率：

- `base_currency` / `quote_currency`：源币种和目标币种。
- `rate`：从源币种到目标币种的乘数。
- `provider`：汇率供应方或稳定币行情源。
- `rate_time` / `updated_at`：来源时间和本地刷新时间。

```sql
PRIMARY KEY (base_currency, quote_currency)
```

指数服务同时保存正向和反向汇率，使展示服务能够完成 `USDT -> USD -> CNY` 等换算，无需在每次
用户请求中调用第三方汇率接口。

## `price_mark_ticks`

每行保存一份异步标记价格审计快照。下单、条件单、风控、强平、资金费、ADL 和结算必须消费 Kafka
标记价格事件，不能等待或查询本表。

- `sequence`：数据库按交易对分配的单调序号。
- `mark_price`：经过中位数和限幅后的最终标记价格。
- `product_line` / `instrument_version`：准确的产品线和合约版本。
- `mark_price_units` / `mark_price_ticks`：发布给实时消费者的定点数值。
- `index_price`、`price1`、`price2`：指数输入、资金费收敛价、指数加移动平均基差。
- `last_trade_price`、`best_bid_price`、`best_ask_price`：最新成交价和盘口一档。
- `basis_average`：`(bid1 + ask1) / 2 - indexPrice` 的移动平均。
- `clamp_low` / `clamp_high`：指数价格周围的最终保护区间。
- `event_time` / `published_at`：计算时间和发布时间，用于新鲜度检查。
- `calculation_inputs`：完整的标记价格 Kafka JSON 信封，包含指数成分、盘口、最新成交、
  资金费输入、中间基差和最终结果。

独立消费者组以 JDBC 批次写入本表。超过三天的数据每分钟按有界批次删除，默认每轮最多
100,000 行。由于写入按时间有序，保留期索引使用 BRIN。实时价格消费链路不能执行数据库写入或清理。

```sql
PRIMARY KEY (symbol, sequence)
```

## 资金费表

`funding_rate_ticks` 只保存结算边界冻结的 `FINAL` 资金费率，统一使用长整型 ppm。
每秒产生的 `PREDICTED` 费率只作为 Kafka 事件保存在消费者的按交易对最新缓存中，不写入该表。

- `funding_rate_ppm`：限幅后的最终资金费率。
- `premium_rate_ppm`：`(markPrice - indexPrice) / indexPrice * 1_000_000`。
- `interest_rate_ppm`：转换为 ppm 的合约利率。
- `funding_time`：应执行结算的 UTC 周期边界。

`funding_settlements` 按 `symbol + funding_time` 保存结算批次；唯一索引阻止多个资金费节点重复结算。

`funding_payments` 保存每个用户的结算金额：

- `amount_units > 0`：用户收取资金费。
- `amount_units < 0`：用户支付资金费。
- `notional_units`：以结算资产最小单位表示的持仓名义价值。

资金费服务在同一事务中写入 `reference_type = FUNDING` 的 `account_ledger_entries`，并更新
`account_balances` / `account_deficits`。

## 风控表

`risk_account_snapshots` 按 `snapshot_id` 保存账户级保证金状态，包括钱包余额、未实现盈亏、权益、
维持保证金、保证金率和状态。

`risk_position_snapshots` 保存账户快照所使用的持仓级输入：

- `signed_quantity_steps`：来自 `account_positions` 的长整型敞口。
- `mark_price_ticks`：该持仓固定 `instrument_version` 对应的最新可用 Kafka 标记价格。
- `notional_units`、`unrealized_pnl_units`、`maintenance_margin_units`：
  使用结算资产最小单位表示的名义价值、未实现盈亏和维持保证金。

持仓完全归零时，risk-provider 仍写入数量为零的持仓快照，`entry_price_ticks` 和
`mark_price_ticks` 为 `0`，账户快照的未实现盈亏和维持保证金也为零，防止
`latestPositions` 返回陈旧敞口。

risk-provider 将完整账户风险组从 PostgreSQL 投影到 Redis，并维护
`symbol + instrument_version -> group_id` 反向索引。标记价格事件只读取受影响的 Redis 风险组，
要求版本严格一致，并使用缓存的不可变合约和风险档位元数据。资金费、强平和 ADL 仍必须复核
PostgreSQL 权威状态。所有合约名义价值、盈亏和保证金计算统一使用精确整数
`PerpetualContractMath`，禁止读取标记价格审计表。

持仓风险 Kafka 批次遇到新鲜标记价格暂不可用等瞬时问题时必须持续重试，不能因为定时扫描可以补偿
就提交或丢弃消息。

风险 ID 来自 PostgreSQL 原生 sequence。写事务一次批量申请所需序号，再批量插入账户快照、
持仓快照、强平候选和候选 Outbox。事务回滚后允许序号不连续，但不能倒退。

`risk_liquidation_candidates` 保存强平输入；候选记录不是执行凭证，强平服务提交只减仓订单前必须
重新检查最新风险。

```sql
CREATE UNIQUE INDEX risk_liquidation_candidates_snapshot_uidx
    ON risk_liquidation_candidates (product_line, snapshot_id, user_id, symbol, margin_mode, position_side);

CREATE UNIQUE INDEX risk_liquidation_candidates_active_uidx
    ON risk_liquidation_candidates (product_line, user_id, symbol, margin_mode, position_side)
    WHERE status IN ('NEW', 'PROCESSING');
```

Redis 投影采用失败关闭。多节点定时对账由产品线 token 租约协调，不清空正在服务的完整投影；
每轮 generation 记录扫描和并发事件观察到的风险组，仅清理同一有效 generation 中未出现的陈旧组。
权威数据库加载与 Redis 替换受风险组锁保护，Lua 原子更新组状态、成员关系和反向索引。只有完整
首轮扫描结束后才建立就绪标记；投影失败会删除就绪标记。

活动候选唯一索引防止并发价格更新和 Kafka 回放为同一持仓创建多个有效候选。旧候选进入
`COMPLETED` 或 `CANCELED` 后，如果账户仍不安全，后续扫描可以创建下一阶段候选。
插入时只能针对活动候选部分唯一索引执行 `DO NOTHING`；候选 ID 或快照唯一性冲突属于数据完整性
错误，必须失败。

强平消费者批量读取候选事件，并写入共享同一 Redis Cluster hash tag 的就绪优先队列、延迟重试、
处理中租约、载荷和优先级五类 key。Lua 原子完成投递去重、到期提升、带租约领取、确认和重排。
固定工作线程按有界批次领取；Redis 为空或不可用时，PostgreSQL 仍是持久恢复来源。

每批候选在 PostgreSQL 中以集合操作完成状态领取、持仓和风险锁定校验、费率快照查询以及只减仓
订单抢占。服务预留原生 sequence 区间，批量插入强平订单、订单事件、`ACCEPTED` / `PLACE`
Outbox 和审计记录。候选成交后保持 `PROCESSING`，直到更新的风险投影证明持仓已经改变，从而在不把
Redis 当作正确性锁的前提下关闭重复强平窗口。

`risk_admin_rule_overrides` 保存管理端风控覆盖：

- `GLOBAL_MARGIN_POLICY`：预警和强平保证金率阈值。
- `RISK_SCAN_CONTROL`：扫描开关、延迟和批量大小。
- `admin_user_id`、`reason`、`updated_at`：策略变更审计。
- 写入成功后当前节点立即应用运行时配置；数据库记录是管理查询及后续启动、发布自动化的持久来源。

## 保险基金表

`insurance_fund_balances` 按结算资产保存当前保险基金余额，使用长整型资产单位且绝不能为负。

`insurance_fund_ledger` 保存不可变基金流水：

- 正 `amount_units`：充值或运营补充。
- `reference_type = LIQUIDATION_FEE` 的正金额：account-provider 实际收取的强平费收入，
  使用 `reference_id = tradeId:orderId` 防止重放。
- 负 `amount_units`：提取资金或覆盖账户穿仓。
- `(reference_type, reference_id, asset)` 唯一，保证幂等。

`insurance_deficit_coverages` 只保存实际支付了正金额的覆盖尝试。基金无余额时，穿仓继续保留在
`account_deficits`，不能写入空覆盖记录。

覆盖操作写入 `reference_type = INSURANCE_COVERAGE` 的 `account_ledger_entries`；它减少显式穿仓，
不会增加可用余额。

## ADL 表

保险基金耗尽后，`adl_events` 保存自动减仓执行结果：

- `deficit_user_id`：被覆盖 `account_deficits` 的用户。
- `target_user_id`：被减仓的盈利账户。
- `target_position_side`：`NET`、`LONG` 或 `SHORT`。
- `closed_quantity_steps`：减少的持仓数量。
- `realized_profit_units`：目标账户在 ADL 平仓中实现的利润。
- `covered_units` / `remaining_deficit_units`：本次覆盖额及剩余穿仓。
- `priority_score_ppm`：由收益率和有效杠杆计算的长整型 ADL 排队分数。

ADL 使用以下账户流水类型：

- `ADL_REALIZED_PNL`：目标账户因减仓实现盈亏。
- `ADL_TRANSFER`：目标账户转出部分已实现利润覆盖穿仓。
- `ADL_COVERAGE`：穿仓账户通过减少 `account_deficits` 获得覆盖。

## 交易与账户保证金表

### 订单、条件单与算法单

`trading_orders` 使用长整型 ticks 和 steps 保存订单状态：

- `instrument_version`：订单接纳时使用的合约快照；未知交易对的拒绝单可以没有版本。
- `price_ticks`、`quantity_steps`、`executed_quantity_steps`、
  `remaining_quantity_steps`：撮合价格和数量。
- `maker_fee_rate_ppm` / `taker_fee_rate_ppm`：准入时冻结的费率快照。结算通过
  `MatchTradeEvent` 接收实际成交侧费率，不在热路径重新查询当前合约、用户等级或订单，
  避免旧挂单被新的 VIP、返佣或活动规则重新解释。
- `reduce_only` / `post_only`：执行标志。
- 存在 `client_order_id` 时，`(user_id, client_order_id)` 唯一。
- `trading_orders_stp_open_idx` 支持按用户、交易对、方向和价格执行自成交保护。
- `trading_orders_recovery_idx` 支持启动时按 Maker 优先级恢复已成功 `PLACE` 的
  `LIMIT + GTC/GTX` 未完成订单。

`trading_cancel_all_after` 保存 `POST /trading/orders/cancel-all-after` 设置的用户死亡开关。
`(user_id, symbol_scope)` 为主键，`symbol_scope='*'` 表示全账户；倒计时为零且状态为
`DISABLED` 表示关闭。到期任务把 `ACTIVE` 改为 `TRIGGERING`，通过统一 `cancel-open` 服务取消
普通订单和待触发 TP/SL，最后改为 `TRIGGERED`。每次刷新或关闭时重置取消计数。

`trading_algo_orders` 保存 TWAP/Iceberg 父指令，子订单进入普通订单链路前由
`trading_algo_order_children` 记录关联关系。`client_algo_order_id` 在用户范围内幂等；
状态从 `PENDING` / `RUNNING` / `CANCEL_REQUESTED` 进入
`CANCELED` / `COMPLETED` / `FAILED`。TWAP 使用 IOC 子单，Iceberg 在当前子单结束后补充下一笔，
所有子单继续遵循普通订单的资金、风控、Outbox 和撮合边界。

`trading_trigger_orders` 保存止盈止损、止损限价、跟踪委托和 OCO 状态。触发价、激活价、回调率、
最高价、最低价、触发序号及实际触发价格均保存为长整型字段。触发后通过普通订单服务创建
`placed_order_id`，不能直接写撮合状态。OCO 一侧触发或取消后，另一侧必须按同一组规则收敛。

### 费率、做市和杠杆

`trading_fee_schedules` 按产品线、费率等级和生效时间保存 Maker/Taker 费率。订单接纳时冻结有效
费率，成交结算不回查后来生效的费率。

`market_maker_strategy_leases` 协调做市策略多节点所有权；
`market_maker_strategy_overrides` 保存运行参数覆盖，空字段表示继续使用 `application.yml`；
`market_maker_strategy_run_events` 保存周期执行、报价协调、IOC 提交或拒绝和跳过周期等尽力而为的
运维事件；`market_maker_reference_samples` 保存外部盘口样本。以上运行表只用于观察，余额、持仓、
冻结和穿仓仍以账户及交易事务表为事实源。跨订单、成交、流水和持仓的财务归因必须由未来独立的
财务运营数据库消费领域事件后完成。

`trading_leverage_settings` 按 `user_id + symbol + margin_mode` 保存用户目标杠杆。
`10_000_000` 表示 10 倍，`100_000_000` 表示 100 倍。保存时校验合约最大杠杆，下单冻结保证金前
再按当前名义价值校验风险档位。没有用户设置时使用风险档位最大杠杆；有效初始保证金率取
杠杆推导值和档位初始保证金率的较大者。

`trading_symbol_open_interest_shards` 按产品线和交易对使用 64 个用户散列分片保存平台持仓量，
`trading_symbol_open_interest` 是聚合读视图。账户结算在同一个数据修改 CTE 中更新持仓和分片，
避免单交易对热点行。聚合值取多空绝对数量总和的较大者，防止计算平台持仓上限时重复统计。
该数据是派生状态，人工修复、紧急导入或灾难恢复后应从 `account_positions` 定期重建核对。

### 撮合结果与追踪

`trading_match_results` 按 `command_id` 幂等保存每条撮合命令结果，并保存合约版本和来自 REST
请求的 `trace_id`。恢复索引用于确认未完成订单曾获得成功 `PLACE` 结果后，才允许恢复进
exchange-core。

`trading_match_trades` 同时保存 Taker/Maker 各自的合约版本和费率，因为 Maker 挂单可能早于
Taker 命令。账户结算使用成交侧版本和冻结费率，`trace_id` 从 Taker 命令传递到持仓事件。

`trading_order_events` 保存下单或撤单请求的 `trace_id`。排查链路时应结合撮合结果、成交、
Kafka topic/partition/offset 和 `order_id`、`command_id`、`trade_id`。相关 trace 索引只保留
关联键，网关禁止 JOIN 主库表拼装时间线；未来运营库可以通过事件或受控 CDC 建立读模型。

### 保证金、余额和持仓

衍生品下单直接把初始保证金从 `account_balances.available_units` 移至 `locked_units`。
`reservation_account_type`、`reservation_asset` 和 `reserved_units` 构成不可变冻结快照。
重复 `clientOrderId` 返回原订单，不能再次冻结资金。

`account_trade_settlement_sides` 按成交参与方记录已迁移到持仓的保证金和释放的多余金额；
终态订单解冻从原始冻结快照中扣除已经审计的消耗及释放额，只释放剩余部分。
`account_trade_settlement_completions` 是买卖双方均完成后的读取视图。

`account_position_margins` 按 `user_id + symbol + asset + margin_mode` 保存当前持仓抵押品。
开仓成交消耗订单冻结资金并增加持仓保证金；平仓按比例把剩余抵押品释放回可用余额。
逐仓手动加减保证金也更新该表；减少保证金前必须由最新风险快照证明扣减后仍高于维持保证金和
配置缓冲。非只减仓开仓订单缺少正数冻结快照或保证金迁移未命中时，账户事务必须失败。

`account_deficits` 显式记录穿仓，`account_balances` 列不允许为负。已实现盈利先偿还穿仓，再增加
可用余额。全仓亏损和费用依次扣可用余额、由 `account_position_margins` 支撑的全仓锁定抵押品，
剩余部分增加穿仓。逐仓亏损只消耗准确持仓范围的抵押品，不能扣全仓可用余额。订单冻结资金不能
用于盈亏、手续费或资金费亏损。消耗持仓抵押品时必须以 `FOR UPDATE` 同步减少保证金行，避免后续
重复释放。

`account_positions` 按持仓桶保存永续敞口：

- `margin_mode`：`CROSS` 或 `ISOLATED`。
- `position_side`：`NET`、`LONG` 或 `SHORT`。单向模式使用 `NET`；双向模式分别保存多空桶。
  只有用户没有非零持仓、活动订单、待触发条件单和未结算状态时才允许切换模式。
- `instrument_version`：当前非零敞口的合约版本，空仓时为 `NULL`。
- `signed_quantity_steps`：多仓为正、空仓为负。
- `entry_price_ticks`：平均开仓价格。
- `realized_pnl_units`：结算资产单位的累计已实现盈亏，平仓写入 `TRADE_PNL` 流水。
- Maker/Taker 手续费或返佣写入 `TRADE_FEE`；实际收取的强平费写入
  `LIQUIDATION_FEE`，金额不超过可收取抵押品且不能制造新的穿仓。保险基金只接收实际收取额，
  不能按估算金额入账。

account-provider 通过 `account_commands` 执行每个成交参与方。不可变命令 ID 和信封 SHA-256
保证执行幂等；身份冲突回滚整个参与方事务。所有余额、持仓、保证金、穿仓、流水和冻结迁移在一个
PostgreSQL 事务内完成，并锁定所需行。预期更新未命中时必须快速失败，不能静默跳过。

`TRADE_PNL`、`TRADE_FEE` 和 `LIQUIDATION_FEE` 流水在扣款成功后才写入最终
`balance_after_units`。人工逐仓保证金调整使用
`reference_type = POSITION_MARGIN_ADJUSTMENT`；正金额表示增加抵押品，负金额表示释放。
后台余额调整还写入 `account_admin_balance_adjustments` 保存操作员、调整类型、账户类型、
引用 ID、金额和调整后余额；账户流水仍是资金事实源。

账户估值、对账、日快照、订单时间线和运营报表不能由交易网关查询或存储。未来
`surprising-finance-ops` 必须使用独立数据源和物理数据库，通过领域事件、Outbox 或受控 CDC
构建读模型，禁止对交易主库执行报表 JOIN。

### 网关本地数据与代码边界

`gateway_support_tickets` 保存客服工单；`gateway_support_ticket_notes` 保存工单时间线。
用户、角色、权限、登录日志、MFA、刷新会话、工单和备注分别由单表 Repository 访问，Service
负责聚合。合规列表投影是唯一网关本地多表例外，因为过滤和游标必须基于同一数据库快照，源码必须
以中文 `不可拆原因` 注释说明。

`scripts/check-persistence-boundaries.sh` 从 DDL 推导物理表词汇，禁止生产 JDBC 在 Repository
之外访问数据库，并要求多表 Repository 明确说明不可拆原因。
`scripts/check-entry-layer-boundaries.sh` 保证 Controller 只校验协议输入、提取非持久化上下文、
调用 Service 并转换响应；定时入口只能位于 `task` 包并委托 Service。

### `account_outbox_events`

账户状态变化与 Kafka 事件在同一事务写入本表。它承载 WebSocket、风控和条件单使用的
`POSITION_UPDATED`，以及保险基金入账使用的 `LIQUIDATION_FEE_SETTLED`。Redis 持仓快照不是
业务事件，不进入本表。

- `id`：数据库分配的 Outbox ID。
- `topic` / `product_line`：目标 Topic 和发布者所属产品线。
- `event_key`：持仓更新使用 `<PRODUCT_LINE>:<userId>`，保证同一账户修订顺序；强平费事件
  使用结算资产，保证基金更新按资产串行。
- `payload`：JSONB 事件体。
- `attempts`、`next_attempt_at`、`published_at`、`last_error`：重试和发布状态。
- 持仓事件携带完整持仓及抵押品快照、PostgreSQL `revision`、产品线和原成交 `traceId`。
- 强平费事件携带 `tradeId`、`orderId`、`liquidationOrderId`、`candidateId`、资产、
  实收金额、费率和 `traceId`。

账户事务按发生变化的精确 key 生成完整快照并插入 Outbox。提交后，相同快照还会进入有界合并
Redis 工作队列以降低延迟，专用 Kafka 消费者负责持久回放。Redis Lua CAS 拒绝旧修订；队列溢出、
消息非法或 Redis 失败会把产品线投影标记为不可用，直到回放或 PostgreSQL 对账修复。

发布者对每个 `topic + event_key` 组合使用事务级 advisory lock 和原子租约更新，使多个节点可以
安全处理不同流。发布语义为至少一次，消费者按事件 ID、成交 ID 或最新持仓版本去重。未来重试项
必须阻塞相同 key 的后续记录，轮询顺序应避免深队列独占整个批次。

已发布 Outbox 是临时投递记录。发布者每分钟按最多十个、每批 10,000 行的短事务清理七天前数据，
使用 `FOR UPDATE SKIP LOCKED`；未发布或失败记录绝不能进入清理范围。

## 强平表

`liquidation_orders` 审计每个强平候选结果：

- `SUBMITTED` 必须具有正 `quantity_steps`。
- 账户恢复或持仓消失时可以写 `CANCELED`，此时数量允许为零。
- 破产价、接管价、强平费率和强平费在提交时冻结，供保险基金和 ADL 对账；取消记录保持为零。
- 服务锁定实时 `account_positions` 和同方向现存只减仓订单，先写撤单事件及命令，再根据
  `abs(livePosition)` 计算分阶段强平数量。
- 用户已有只减仓订单不能占用强平容量，否则远价 GTC 平仓单会阻断强制平仓。
- 强平订单写入不能对 `trading_orders` 使用宽泛冲突忽略，唯一性冲突必须回滚事务。
- 数量计算先尝试降至风险档位，再应用配置的部分平仓比例，只有保证金率达到全平阈值才全部平仓。
- 提交前读取最新风险账户及持仓快照；快照数量与锁定实时持仓不一致时，以
  `RISK_POSITION_CHANGED` 取消候选。

`liquidation_admin_actions` 保存管理端对强平候选的操作。目前支持 `CANCEL_CANDIDATE`，并持久化
管理员、原因和时间。只有候选为 `NEW` 或 `PROCESSING` 且不存在
`SUBMITTED` / `PARTIALLY_FILLED` 活动强平订单时，管理端才可取消。
