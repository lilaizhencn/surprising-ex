# surprising-liquidation

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 合约强平执行模块。它消费 risk-provider 生成的爆仓候选，抢占候选、复核最新风险状态，然后生成 reduce-only 市价平仓订单进入现有订单和撮合链路。

## 模块

- `surprising-liquidation-api`：强平订单查询 DTO。
- `surprising-liquidation-provider`：候选消费、任务抢占、风险复核、分阶段 sizing、reduce-only 前置撤单和强平订单提交。

## 核心链路

```text
surprising.perp.liquidation.candidates.v1
  -> surprising-liquidation-provider
  -> claim risk_liquidation_candidates(status NEW -> PROCESSING)
  -> re-check latest risk_account_snapshots
  -> request cancel for existing user reduce-only close orders
  -> trading_orders(reduceOnly=true, MARKET, IOC)
  -> trading_outbox_events
  -> surprising.perp.order.commands.v1
  -> surprising-matching-provider / exchange-core
```

## 强平订单规则

- 多仓爆仓：生成 `SELL` 平仓单。
- 空仓爆仓：生成 `BUY` 平仓单。
- `orderType = MARKET`
- `timeInForce = IOC`
- `reduceOnly = true`
- `postOnly = false`
- `quantitySteps` 按实时仓位 `abs(livePosition)` 做上限，不会被用户已有 reduce-only 挂单挤掉。

生成订单前会重新读取最新且未过期的风险状态：全仓候选复核 `userId + settleAsset` 的账户级状态，逐仓候选复核 `userId + symbol + marginMode` 的仓位级状态。如果风险已经恢复到非 `LIQUIDATION`，或最新风险快照超过 `surprising.liquidation.risk.max-snapshot-age`，候选会被标记为 `CANCELED`，不会提交订单。

提交强平单前还会读取最新 `risk_position_snapshots` 和同一 `snapshot_id` 的账户快照来计算审计价格：

- `bankruptcy_price_ticks`：按当前 mark 和权益推算的破产价，表示权益归零的价格边界。
- `takeover_price_ticks`：按当前 mark 和权益推算的接管价，表示扣除配置的强平费用缓冲后权益接近归零的价格边界。
- `liquidation_fee_rate_ppm`：从 `surprising.liquidation.execution.liquidation-fee-rate-ppm` 冻结下来的强平费率。
- `liquidation_fee_units`：按 `surprising.liquidation.execution.liquidation-fee-rate-ppm` 和 mark notional 计算的预估强平费，默认 `3000 ppm` 即 `0.3%`。

价格计算使用 `PerpetualContractMath` 和 long/BigInteger，不使用浮点数；线性和反向合约都走同一套边界搜索。若最新风险快照里的仓位数量和锁定的实时 `account_positions` 不一致，本次候选会以 `RISK_POSITION_CHANGED` 取消，等待下一次风控扫描重新生成候选，避免用旧快照执行强平。

## 分阶段强平

provider 使用分阶段强平策略：

- 锁定实时 `account_positions` 行。
- 锁定同一 `userId + symbol + closeSide` 下未完成的 reduce-only 平仓单。
- 待平 reduce-only 数量用 checked long addition 聚合；如果溢出，会回滚候选事务，不能把溢出结果当成可强平容量。
- 对这些用户挂单写入 `CANCEL_REQUESTED` 事件，并在同一 symbol outbox 分区里先写 cancel command，再写强平 place command。
- 可平数量 = `abs(livePosition)`；强平不会因为用户远离盘口的 reduce-only GTC 挂单而被阻塞。
- 使用持仓自己的 `instrument_version` 和共享 `PerpetualContractMath` long 公式计算实时 notional 和每 step notional。
- 如果当前 notional 处于更高风险档位，优先减仓到低一档风险下限附近。
- 如果已经处于最低档，按配置比例关闭剩余可平数量。
- 如果保证金率超过 `full-close-margin-ratio-ppm`，关闭全部可平数量。

一个强平候选只提交一笔 reduce-only 订单。如果成交后账户仍处于强平状态，下一次 risk scan 会生成新的候选并继续处理。

## API

查询强平订单：

```bash
curl 'http://localhost:9088/api/v1/liquidations/orders?limit=100'
curl 'http://localhost:9088/api/v1/liquidations/orders?userId=1001&limit=100'
curl 'http://localhost:9088/api/v1/liquidations/orders/by-candidate?candidateId=1'
```

后台运营接口必须通过 admin gateway 和管理员 token 访问：

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://localhost:8080/api/v1/admin/gateway/liquidation-admin/orders?limit=100&sort=createdAt.desc'

curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://localhost:8080/api/v1/admin/gateway/liquidation-admin/candidates/1/timeline?limit=200'

curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"risk snapshot recovered after manual review"}' \
  'http://localhost:8080/api/v1/admin/gateway/liquidation-admin/candidates/1/cancel'
```

后台强平订单列表支持 `limit/cursor/sort` 游标分页，排序白名单为 `createdAt.desc`、`createdAt.asc`，响应保留 `orders/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。`timeline` 聚合候选、账户风险快照、仓位风险快照、强平审计单、后台动作、订单状态、订单事件、撮合结果和成交，供风控/客服复盘。取消候选只允许命中 `NEW`/`PROCESSING` 且不存在 `SUBMITTED`/`PARTIALLY_FILLED` 强平单的候选；操作人来自 gateway 注入的 `X-Admin-User-Id`，原因会写入审计表。

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `liquidation_sequences`
- `liquidation_orders`
- `liquidation_admin_actions`

强平订单本身仍写入：

- `trading_orders`
- `trading_order_events`
- `trading_outbox_events`

## 本地运行

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-liquidation-provider -am spring-boot:run
```

端口：

- `9088`：强平执行服务。

## 生产注意事项

- liquidation-provider 必须多节点部署；候选抢占通过 PostgreSQL 条件更新保证同一候选只被一个节点执行。
- Kafka 候选和撮合结果 consumer 的单次拉取数量由 `surprising.liquidation.kafka.max-poll-records` 控制，默认 `500`。
- 强平候选不是执行命令，执行前必须复核最新风险状态。
- 风险复核要求风险快照未过期。默认 `surprising.liquidation.risk.max-snapshot-age` 是 `5s`；快照过期或缺失都按非强平处理。
- 强平订单走统一 order/matching 链路，不直接修改持仓。
- `liquidation_orders` 会固化破产价、接管价、费率和预估强平费，供后台审计、客服争议处理、保险基金/ADL 对账使用。本模块不划转预估强平费。强平订单真实成交后，由 account-provider 按实际成交价和成交数量收取强平费，并按可扣 collateral 封顶，再发布 `surprising.account.liquidation-fee.events.v1`；insurance-provider 只入账实际已收金额。
- 真实强平亏损仍由 account-provider 写 `account_deficits`，随后由 insurance-provider 和 ADL 模块处理亏损瀑布。
- 强平 sizing 已支持分阶段和风险档位降仓；只有严重保证金率场景才全量关闭。
- 提交新的强平订单前会先撤同方向未完成 reduce-only 平仓单。`CANCEL_REQUESTED` 订单仍可能在 exchange-core 内有效，所以会补发 cancel command；matching provider 的 post-only 检查只适用于 `PLACE`，不会拦截撤单。
- 待平数量聚合溢出必须 fail-fast。异常订单集合不能让强平 sizing 或用户可平量发生 long 回绕。
- 强平、前置撤单和后续成交都按 symbol 作为 Kafka key；同一 symbol 内 outbox 顺序必须保持，不能把 cancel 和 liquidation place 拆到不同分区。
- 强平订单创建走 fail-fast。`trading_orders` 不吞唯一键冲突；`trading_order_events`、outbox 行和 `liquidation_orders` 审计行也必须在同一事务内成功写入。任意写入失败都会回滚候选事务。
- 撮合结果生命周期更新会先把本地 `liquidation_orders` 审计行从 `SUBMITTED`/`PARTIALLY_FILLED` 推进到终态；只要这一步成功，对应的 `risk_liquidation_candidates` 也必须在同一事务里从 `PROCESSING` 推进到 `COMPLETED`/`CANCELED`。如果候选不存在或已经是终态，说明状态不一致，必须 fail-fast。
- 候选状态从 `PROCESSING` 更新到 `COMPLETED`/`CANCELED` 必须命中 1 行；outbox 发布后的 `published_at` 标记和失败重试标记也必须命中对应行。
- 强平成交后的仓位和已实现盈亏仍由 account-provider 消费 match trades 更新。

## 验证

```bash
mvn -pl :surprising-liquidation-provider -am test
```
