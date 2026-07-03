# surprising-insurance

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 保险基金模块。它管理永续合约保险基金，接收实际已收取的强平费，并覆盖账户结算产生的显式穿仓亏损。

## 模块

- `surprising-insurance-api`：保险基金 RPC 契约和 long 定点数响应模型。
- `surprising-insurance-provider`：基金调整 API、强平费事件消费、亏损覆盖 worker、基金流水和账户流水集成。

## Long 模型

保险基金只使用 long 定点资产单位：

- `amountUnits`：保险基金充值或划出金额，资产最小单位。
- `balanceUnits`：保险基金当前余额，资产最小单位。
- 强平费事件里的 `amountUnits` 是正数，表示已经实际收取的金额。这个金额已经由 account-provider 按可收 collateral 封顶。
- `requestedUnits`：覆盖前的账户亏损金额。
- `coveredUnits`：保险基金本次覆盖金额。
- `remainingDeficitUnits`：覆盖后仍未覆盖的用户亏损。

例如 `USDT` 的 `scaleUnits = 100000000` 时，`100000000` 表示 `1 USDT`。

## 核心流程

```text
account-provider
  -> account_deficits(userId, asset, deficitUnits)
  -> surprising-insurance-provider scanner
  -> SELECT ... FOR UPDATE SKIP LOCKED
  -> insurance_fund_balances(asset)
  -> insurance_deficit_coverages
  -> insurance_fund_ledger
  -> account_ledger_entries(reference_type=INSURANCE_COVERAGE)
```

保险基金服务不直接改持仓，也不创建订单。强平和撮合决定最终持仓状态；账户结算把负权益记录到 `account_deficits`；保险基金在有余额时再吸收这部分亏损。

强平费收入走独立事件链路：

```text
account-provider
  -> account_ledger_entries(reference_type=LIQUIDATION_FEE)
  -> account_outbox_events
  -> Kafka surprising.account.liquidation-fee.events.v1, key=asset
  -> insurance_fund_balances(asset)
  -> insurance_fund_ledger(reference_type=LIQUIDATION_FEE, reference_id=tradeId:orderId)
```

保险基金只入账实际已收的强平费。如果被强平账户已经没有可扣 collateral，account-provider 不发送强平费事件，保险基金余额也不会增加。

## 覆盖规则

- 保险基金余额为 0 时，亏损行保持不变，等待后续扫描。
- 保险基金余额小于亏损时，部分覆盖亏损，剩余金额继续保留在 `account_deficits`。
- 保险基金余额足够时，全额清除亏损。
- 覆盖亏损只减少显式 deficit，不增加 `account_balances.available_units`。
- 基金调整请求按 `(referenceType, referenceId, asset)` 幂等，但只有重放的 `amountUnits` 和 `reason` 与原流水一致时才允许。
- 强平费事件按 `LIQUIDATION_FEE + tradeId:orderId + asset` 幂等。Kafka 重放只校验原金额，不会重复增加基金余额。
- 覆盖路径要求 coverage、基金余额扣减、deficit 更新、基金流水、账户流水全部写成功；任一应写入行返回 0 都会抛异常并回滚事务。

## 多节点安全

- 可以部署多个 insurance-provider 节点。
- 扫描器用 `FOR UPDATE SKIP LOCKED` 锁定正数亏损行，不同节点会分摊亏损行，不会重复覆盖同一行。
- 扣减保险基金前也会锁定对应资产基金余额行。
- 基金流水有唯一 reference 索引，防止重复的人工调整、重复强平费事件或重复覆盖流水。
- 强平费事件 Kafka key 使用资产，同一保险基金资产的入账可以先由 Kafka 分区串行，再由 PostgreSQL 基金余额行锁兜底。
- 写账户流水时使用 `account_sequences`，不使用保险模块自己的 sequence，确保账户域内 ledger id 统一。
- 人工基金调整遇到重复 reference 时，只有 payload 完全一致才返回当前余额且不再次更新基金余额；金额或原因不一致会失败并回滚。
- 覆盖流水使用新分配的 `coverageId` 作为 reference；如果唯一索引冲突，视为 sequence 或重放链路异常，服务会失败并依赖事务回滚。

## API

充值保险基金：

```bash
curl -X POST 'http://localhost:9090/api/v1/insurance/admin/fund-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"asset":"USDT","amountUnits":1000000000000,"referenceId":"ops-deposit-20260701-1","reason":"INITIAL_FUND"}'
```

划出保险基金：

```bash
curl -X POST 'http://localhost:9090/api/v1/insurance/admin/fund-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"asset":"USDT","amountUnits":-100000000,"referenceId":"ops-withdraw-20260701-1","reason":"CONTROLLED_WITHDRAWAL"}'
```

查询余额、流水和覆盖记录：

```bash
curl 'http://localhost:9090/api/v1/insurance/balances?asset=USDT'
curl 'http://localhost:9090/api/v1/insurance/ledger?asset=USDT&limit=100'
curl 'http://localhost:9090/api/v1/insurance/coverages?userId=1001&asset=USDT&limit=100'
```

后台操作员应通过 gateway 使用 admin namespace：

- `POST /api/v1/insurance/admin/fund-adjustments`
- `GET /api/v1/insurance/admin/balances`
- `GET /api/v1/insurance/admin/ledger`
- `GET /api/v1/insurance/admin/coverages`
- `GET /api/v1/insurance/admin/runtime-config`
- `POST /api/v1/insurance/admin/runtime-config`

其中 `/admin/ledger` 和 `/admin/coverages` 支持统一游标分页参数 `limit`、`cursor`、`sort`。排序白名单为 `createdAt.desc` 和 `createdAt.asc`，响应保留 `entries/coverages/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `insurance_sequences`
- `insurance_fund_balances`
- `insurance_fund_ledger`
- `insurance_deficit_coverages`

核心索引：

- `insurance_fund_ledger_reference_uidx`
- `insurance_fund_ledger_asset_time_idx`
- `insurance_coverages_user_time_idx`
- `insurance_coverages_asset_time_idx`

## 本地运行

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-insurance-provider -am spring-boot:run
```

端口：

- `9090`：保险基金服务。

## 生产注意事项

- `/admin/fund-adjustments` 必须放在内部鉴权、审批和审计链路之后。
- 正式交易前要先注入保险基金。
- 需要监控 `account_deficits.deficit_units`、`insurance_fund_balances.balance_units` 和部分覆盖记录。
- Kafka 强平费 consumer 的单次拉取数量由 `surprising.insurance.kafka.max-poll-records` 控制，默认 `500`。
- 保险基金是用户保证金之后的第二层亏损吸收。保险基金耗尽后仍有剩余亏损时，由 `surprising-adl` 处理。
- 保险基金不依赖外部行情，只处理已经由 account-provider 最终结算出的亏损和强平费收取事件。
- 不能用预估强平费给保险基金入账。只有 account-provider 在真实成交结算并实际扣到 collateral 后，才能发出 `LIQUIDATION_FEE_SETTLED`。
- 如果看到 `failed to write insurance fund ledger insert` 或类似错误，要优先检查 sequence 表、唯一索引冲突、数据库事务边界和是否存在人工改库。

## 验证

```bash
mvn -pl :surprising-insurance-provider -am test
```
