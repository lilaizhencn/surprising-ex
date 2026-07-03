# surprising-adl

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 自动减仓模块。它处理用户保证金和保险基金都耗尽后的剩余穿仓亏损。

损失瀑布按主流永续交易所思路设计：用户保证金 -> 强平 -> 保险基金 -> ADL。ADL 会优先削减盈利且高杠杆的对手仓，并用这部分减仓实现的盈利覆盖剩余亏损。

## 模块

- `surprising-adl-api`：ADL 队列和事件 RPC 契约。
- `surprising-adl-provider`：剩余亏损扫描、ADL 队列选择、仓位削减、余额转移和事件审计。

## Long 模型

ADL 使用 long 定点值：

- `closedQuantitySteps`：被自动减仓的数量 step。
- `entryPriceTicks` / `markPriceTicks`：long 价格 tick。
- `realizedProfitUnits`：ADL 平仓实现的结算资产最小单位盈利。
- `coveredUnits`：从实现盈利中转去覆盖穿仓亏损的金额。
- `priorityScorePpm`：ppm 风格 long 算术下的 ADL 优先级分。

ADL 链路 Java 代码不使用 `BigDecimal`。
候选仓位的 notional 和未实现盈利都按结算资产单位计算，并使用共享 `PerpetualContractMath` long 公式。U 本位线性合约使用标记价 notional；币本位反向合约把报价币面值和 entry/mark price 倒数关系折算成结算币单位。
ADL 队列计算按 `account_positions.instrument_version` 读取合约配置。

## 优先级模型

队列优先级遵循主流合约交易所原则：盈利越多、有效杠杆越高，越先被自动减仓。

```text
profitRatePpm = unrealizedProfitUnits / notionalUnits * 1_000_000
effectiveLeveragePpm = notionalUnits / positionMarginUnits * 1_000_000
priorityScorePpm = profitRatePpm * effectiveLeveragePpm / 1_000_000
```

如果 `positionMarginUnits` 为 0，该仓位会获得最高有效杠杆分，排到队列前面。

## 核心流程

```text
account_deficits(asset, deficitUnits)
  -> 该资产保险基金余额为 0
  -> deficit 行更新时间超过 minDeficitAgeMs
  -> surprising-adl-provider 锁定 deficit 行
  -> 选择同结算资产的盈利 ADL 队列候选
  -> 用 FOR UPDATE SKIP LOCKED 重新锁定目标 account_positions 行
  -> 按最新 mark price 削减目标仓位
  -> 按比例释放目标仓位保证金
  -> 给目标用户记录 ADL_REALIZED_PNL
  -> 从目标用户实现盈利中转出 coveredUnits 覆盖亏损
  -> 减少亏损用户 account_deficits
  -> 写入 adl_events
```

ADL 不发布订单命令，也不伪造成普通盘口成交。它是强平和保险基金不足后的系统结算事件。
执行链路要求目标仓位更新、保证金释放、余额更新、deficit 更新、账户流水和 `adl_events` 都写成功；任何应写入/应更新的行返回 0 都会抛异常并回滚事务。
目标仓位保证金释放使用 `margin_units >= releaseUnits` 的 guarded update；如果持仓保证金状态异常，ADL 会失败回滚，而不是把保证金扣成负数。

## 多节点安全

- 可以部署多个 ADL provider。
- deficit 行用 `FOR UPDATE SKIP LOCKED` 分摊处理。
- 候选仓位在执行前会重新读取并加锁。
- 扫描器只在该资产 `insurance_fund_balances.balance_units = 0` 时才认领 deficit。
- 账户流水 ID 使用 `account_sequences`；ADL 审计 ID 使用 `adl_sequences`。
- 队列选择和执行前复核都要求 mark price 在 `surprising.adl.scanner.max-mark-age-ms` 内新鲜。
- 执行前再次锁定保险基金余额；如果扫描后基金被充值或其他节点恢复了余额，本次 ADL 会跳过，把亏损留给保险基金优先处理。

## API

查询当前 ADL 队列：

```bash
curl 'http://localhost:9091/api/v1/adl/queue?asset=USDT&limit=100'
```

查询 ADL 事件：

```bash
curl 'http://localhost:9091/api/v1/adl/events?asset=USDT&limit=100'
curl 'http://localhost:9091/api/v1/adl/events?userId=1001&limit=100'
```

后台 ADL 查询必须通过 admin gateway 和管理员 token 访问：

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://localhost:8080/api/v1/admin/gateway/adl/admin/queue?asset=USDT&limit=100&sort=priorityScorePpm.desc'

curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://localhost:8080/api/v1/admin/gateway/adl/admin/events?asset=USDT&limit=100&sort=createdAt.desc'
```

后台 ADL 队列支持按实时排名 `priorityScorePpm.desc` 游标分页；ADL 事件支持 `createdAt.desc`、`createdAt.asc`。响应保留 `positions/events/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `adl_sequences`
- `adl_events`

核心索引：

- `adl_events_deficit_user_time_idx`
- `adl_events_target_user_time_idx`
- `adl_events_asset_symbol_time_idx`

## 本地运行

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
mvn -pl :surprising-adl-provider -am spring-boot:run
```

端口：

- `9091`：ADL 服务。

## 生产注意事项

- ADL 应在 account、risk、liquidation、insurance provider 都部署后再启动。
- `surprising.adl.scanner.min-deficit-age-ms` 要保留足够时间让保险基金先覆盖。
- `surprising.adl.scanner.max-mark-age-ms` 应接近 mark price 发布间隔；过期 mark price 不能触发 ADL。
- ADL 精度取决于 instrument 的 `quantity_step_units`；合约 step 过粗时可能多减一个 step。
- 需要监控剩余 `account_deficits`、ADL 事件数量和每个资产的队列顶部用户。
- ADL 是最后兜底的偿付工具。一旦触发 ADL，应同时触发告警和人工运营处理。
- 如果出现 `failed to write ADL ...` 错误，要检查目标仓位是否被异常改动、账户余额/deficit 行是否缺失、账户流水唯一索引是否冲突，以及当前事务是否被拆开。

## 验证

```bash
mvn -pl :surprising-adl-provider -am test
```
