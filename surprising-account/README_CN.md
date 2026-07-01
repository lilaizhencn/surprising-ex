# surprising-account

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 账户和合约持仓模块。当前实现 long-based 余额、余额流水、成交幂等处理、净持仓更新、成交后订单保证金到持仓保证金的迁移，以及 maker/taker 手续费结算。

## 模块

- `surprising-account-api`：账户/持仓 RPC 合约和 DTO。
- `surprising-account-provider`：余额、ledger、持仓和成交消费实现。

## long 单位

- 余额使用 `availableUnits`、`lockedUnits`、`equityUnits`，全部是资产最小单位的 long。
- 持仓使用 `signedQuantitySteps`，正数为净多，负数为净空。
- 持仓保存 `instrumentVersion`，当前敞口的合约数学固定到开仓时的版本。
- 开仓均价使用 `entryPriceTicks`。
- 持仓保证金记录在 `account_position_margins.margin_units`。
- 已实现盈亏按 `realizedPnlUnits` 累计，单位是 instrument 的结算资产最小单位。U 本位线性合约使用 tick-step notional；币本位反向合约使用合约面值和入场/出场价格倒数公式。
- 交易手续费使用成交双方各自 instrument version 上的 `makerFeeRatePpm` / `takerFeeRatePpm`。正费率扣用户余额，负费率给用户返佣。
- 当亏损超过 `availableUnits + lockedUnits` 时，超额亏损写入 `account_deficits`，不让余额列变成负数。

## 成交处理

`surprising-account-provider` 消费：

```text
surprising.perp.match.trades.v1
```

每条 `MatchTradeEvent` 会同时更新：

- taker 用户持仓，方向使用 `takerSide`。
- maker 用户持仓，方向为 taker 的反方向。
- 成交中的开仓数量只会把按实际成交价计算出的初始保证金迁移到 `account_position_margins`；委托价改善或市价保护价多冻结的部分会释放回 `availableUnits`。
- 成交中的平仓数量会按比例把旧持仓保证金从 `lockedUnits` 释放回 `availableUnits`。
- 平仓产生的已实现盈亏会写入 `account_ledger_entries`，`reference_type = TRADE_PNL`。
- 每笔成交的 maker/taker 手续费会写入 `account_ledger_entries`，`reference_type = TRADE_FEE`。
- 翻仓成交先平旧仓，再把剩余成交数量作为新仓处理。
- 如果成交导致翻仓，已实现盈亏使用旧持仓版本计算，翻仓后剩余新仓使用成交的 `instrumentVersion`。
- 开仓成交必须找到对应的 `account_margin_reservations` 并成功迁移订单保证金；缺失 reservation、reduce-only 订单出现开仓数量、或迁移写入返回 0 都会失败并回滚整笔成交处理。
- 平仓成交只有 `reduceOnly=true` 订单可以跳过订单保证金释放。非 reduce-only 订单只要平掉了仓位，就仍必须存在原始 reservation 行。
- 持仓更新、余额/deficit 更新、PnL/fee ledger 插入/回填、订单保证金释放、持仓保证金增减都要求写入 1 行。任何异常都不应静默跳过。
- 持仓数量或版本变化后会触发 reduce-only 挂单剪枝：反向、版本不一致或超过新持仓容量的未完成 reduce-only 订单会被写为 `CANCEL_REQUESTED` 并通过 order command outbox 发送撤单。容量检查使用 checked absolute value 和 checked 待平数量累加，异常持仓数量会在发出撤单命令前失败。

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
curl 'http://localhost:9086/api/v1/accounts/positions?userId=1001'
```

管理员余额调整：

```bash
curl -X POST 'http://localhost:9086/api/v1/accounts/admin/balance-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"asset":"USDT","amountUnits":100000000,"referenceId":"deposit-1001-1","reason":"INITIAL_DEPOSIT"}'
```

生产中 admin API 必须只允许充值系统、清结算系统或受控后台调用。

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `account_sequences`
- `account_balances`
- `account_deficits`
- `account_ledger_entries`
- `account_margin_reservations`
- `account_position_margins`
- `account_positions`
- `account_processed_trades`

核心索引：

- `account_ledger_reference_uidx`
- `account_deficits_user_idx`
- `account_margin_reservations_user_idx`
- `account_position_margins_user_idx`
- `account_positions_user_idx`
- `account_processed_trades_symbol_idx`

## 本地运行

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-account-provider -am spring-boot:run
```

端口：

- `9086`：账户和持仓服务。

## 生产注意事项

- 余额调整必须携带全局唯一 `referenceId`，防止充值/冲正重复入账。同一 reference 的重放只有在 `amountUnits` 和 `reason` 与原流水一致时才会幂等返回；payload 不一致会在改余额前失败。
- 账户 provider 消费撮合成交时必须按 `(symbol, trade_id)` 幂等，不能只按裸 `tradeId` 去重。
- 订单入口会在发布撮合命令前冻结初始保证金。账户 provider 消费成交后，按实际成交价计算开仓保证金并迁移为持仓保证金，委托价或市价保护价多冻结的部分释放回可用余额。
- 平仓成交按平仓数量比例释放持仓保证金。这条链路必须保持 long-only，并与 exchange-core 的 ticks/steps 一致。
- reduce-only 剪枝不是撮合层功能；它依赖 account-provider 在持仓更新事务里锁定相关订单并发布 cancel command。多节点部署时必须保证所有 account-provider 使用同一 PostgreSQL 和按 symbol 分区的 order command topic。
- reduce-only 剪枝遇到 `Long.MIN_VALUE` 这类不可能的 signed quantity 必须 fail-fast，不能让容量数学回绕后基于负绝对值错误撤单或保留挂单。
- 如果出现 `missing order margin reservation for opening fill`，要检查 order-provider 是否跳过冻结、matching 是否处理了不应存在的订单、或数据库中 reservation 是否被人工改动。
- 如果出现 `missing order margin reservation for closing fill`，要检查是否有非 reduce-only 的平仓/翻仓订单在没有 order-provider reservation 事务的情况下被接受。
- 已实现亏损可以扣 `availableUnits` 和由持仓保证金支撑的 `lockedUnits`，但不能扣未成交订单冻结。只要扣了持仓保证金支撑的 locked，就必须在同一事务内同步减少 `account_position_margins`。
- 手续费扣款复用已实现亏损的余额/deficit 安全路径。手续费返佣先清理 deficit，再增加 available balance。
- `contract_type` 决定已实现盈亏公式：`LINEAR_PERPETUAL` 使用 `signedQty * (exitTicks - entryTicks) * notional_multiplier_units`；`INVERSE_PERPETUAL` 使用 `signedQty * faceValueUnits * settleScaleUnits * (exitTicks - entryTicks) / (entryTicks * exitTicks * price_tick_units)`。
- 维持保证金和未实现盈亏由 risk 模块计算。资金费率、保险基金和自动减仓由独立结算模块处理，不放在本 provider 内。

## 验证

```bash
mvn -pl :surprising-account-provider -am test
```
