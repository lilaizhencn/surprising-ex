# surprising-funding

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 合约资金费率模块。它负责计算预测资金费率，向 mark-price 服务发布资金费率事件，并在资金费时间到达后把资金费结算到账户余额。

## 模块

- `surprising-funding-api`：资金费率 RPC 合约和 long-based 响应模型。
- `surprising-funding-provider`：资金费率发布、资金费结算、账户流水写入和 Kafka outbox 发布。

## long 模型

Funding 核心使用 long 定点值：

- `fundingRatePpm`：百万分之一费率。`100` 表示 `0.000100`，也就是 `0.01%`。
- `premiumRatePpm`：`(markPrice - indexPrice) / indexPrice * 1_000_000`。
- `interestRatePpm`：instrument 利率转换成 ppm。
- `amountUnits`：结算资产最小单位的资金费金额。
- 资金费 notional 在乘以 `fundingRatePpm` 前都会先折算成结算资产单位。U 本位线性合约使用标记价 notional；币本位反向合约按当前标记价把报价币合约面值折成结算币。折算使用 `surprising-instrument-api` 的共享 `PerpetualContractMath` long 公式。

为了兼容现有 mark-price 服务，provider 会向 `surprising.perp.funding.rate.v1` 发布已有 JSON 结构，payload 里包含十进制 `fundingRate` 字段；内部计算和结算仍使用 long ppm。

## 费率计算

对每个 `TRADING` instrument：

```text
premiumRatePpm = (markPrice - indexPrice) / indexPrice * 1_000_000
rawRatePpm = interestRatePpm + premiumRatePpm
fundingRatePpm = clamp(rawRatePpm, fundingRateFloorPpm, fundingRateCapPpm)
nextFundingTime = 下一个 UTC funding interval 边界
```

输入：

- `instruments`：资金费周期、利率、上限、下限。
- `price_mark_ticks`：最新 mark/index price 以及 `mark_price_units`；结算时再把这个 long 值按持仓版本转换成 ticks。
- `price_symbol_leases`、`price_symbol_sequences`：多节点 symbol 租约和全局单调序列。

## 结算

资金费时间到达时，每个 `symbol + fundingTime` 只会创建一笔 settlement。

方向遵循主流永续合约规则：

- 资金费率为正：多头付给空头。
- 资金费率为负：空头付给多头。

provider 会：

- 基于 `account_positions`、持仓自己的 instrument version、mark price、`account_asset_scales` 并按 `contract_type` 计算每个持仓名义价值。
- 使用每个持仓自己的 `instrument_version` 做 notional 折算，避免用新 instrument 版本重新解释旧持仓。
- 为每个用户写一条 `funding_payments`。
- 写 `account_ledger_entries`，`reference_type = FUNDING`。
- 更新 `account_balances` 和 `account_deficits`。
- 通过 `funding_settlements(symbol, funding_time)` 唯一键保证幂等。
- 每个到期的 `symbol + fundingTime` 使用独立数据库事务结算。单笔 settlement 失败会回滚并在后续扫描重试，同一轮 scheduler 中其它到期 settlement 仍可继续完成。

## API

```bash
curl 'http://localhost:9089/api/v1/funding/rates/latest?symbol=BTC-USDT'
curl 'http://localhost:9089/api/v1/funding/rates/history?symbol=BTC-USDT&limit=100'
curl 'http://localhost:9089/api/v1/funding/settlements/latest?symbol=BTC-USDT'
curl 'http://localhost:9089/api/v1/funding/payments?userId=1001&symbol=BTC-USDT&limit=100'
```

后台操作员应通过 gateway 使用 admin namespace：

- `GET /api/v1/funding/admin/rates/latest`
- `GET /api/v1/funding/admin/rates/history`
- `GET /api/v1/funding/admin/settlements/latest`
- `GET /api/v1/funding/admin/payments`
- `GET /api/v1/funding/admin/runtime-config`
- `POST /api/v1/funding/admin/runtime-config`

其中 `/admin/rates/history` 和 `/admin/payments` 支持统一游标分页参数 `limit`、`cursor`、`sort`。资金费历史排序白名单为 `eventTime.desc`、`eventTime.asc`；资金费付款排序白名单为 `createdAt.desc`、`createdAt.asc`。响应保留 `rates/payments/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `funding_sequences`
- `funding_rate_ticks`
- `funding_settlements`
- `funding_payments`
- `funding_outbox_events`

核心索引：

- `funding_rate_ticks_symbol_time_idx`
- `funding_settlements_symbol_time_uidx`
- `funding_payments_settlement_user_uidx`
- `funding_payments_user_time_idx`
- `funding_outbox_pending_idx`

## 本地运行

```bash
brew services start postgresql@18
brew services start kafka
brew services start redis
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-funding-provider -am spring-boot:run
```

端口：

- `9089`：资金费率服务。

## 生产注意事项

- funding-provider 至少部署 2 个实例，symbol 归属通过 PostgreSQL lease 协调。
- 资金费结算按 `symbol + fundingTime` 幂等；Kafka 或 scheduler 重复执行不会重复结算。
- 结算应在撮合成交已经更新账户持仓、mark price 足够新之后执行。
- 资金费可能产生或冲减 `account_deficits`；余额列不会变成负数。
- 资金费扣款可以扣 `available_units` 和由持仓保证金支撑的 `locked_units`，但不能消耗未成交订单冻结。只要扣了持仓保证金支撑的 locked，就必须在同一事务内同步减少 `account_position_margins`。
- 资金费率写入和 `funding_outbox_events` 写入在同一事务内完成；任一 insert/update 被跳过都会 fail-fast，避免 DB 有费率但 Kafka/outbox 没有事件。
- 资金费结算事务按 `symbol + fundingTime` 隔离；单个账户坏行或不变量失败不能回滚其它 symbol 的结算。
- `funding_payments(settlement_id, user_id)` 冲突是合法幂等跳过；但 payment 已插入后的账户流水、余额、deficit 和 settlement 完成更新都必须命中对应行，否则回滚整个结算事务。
- outbox 发布后的 `published_at` 标记和失败重试标记必须更新到对应行；如果行不存在，说明 outbox 状态已损坏，需要让进程失败并排查。
- 保险基金和 ADL 是独立模块，funding 不负责兜底穿仓损失。

## 验证

```bash
mvn -pl :surprising-funding-provider -am test
```
