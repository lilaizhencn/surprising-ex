# surprising-risk

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 合约风险模块。当前实现账户级保证金率快照、持仓风险快照和爆仓候选输出，为强平执行模块提供输入。

## 模块

- `surprising-risk-api`：风险查询和爆仓候选 DTO。
- `surprising-risk-provider`：风险扫描、快照落库、爆仓候选生成和事件发布。

## 计算口径

Java 层保持 long-based：

- `walletBalanceUnits`：`account_balances.available_units + locked_units - account_deficits.deficit_units`。
- `unrealizedPnlUnits`：未实现盈亏最小单位。
- `equityUnits = walletBalanceUnits + unrealizedPnlUnits`。
- `maintenanceMarginUnits`：维持保证金最小单位。
- `marginRatioPpm = maintenanceMarginUnits / equityUnits * 1_000_000`。

instrument 使用 long 保存 `contract_type`、`price_tick_units`、`quantity_step_units`、`notional_multiplier_units` 和 `maintenance_margin_rate_ppm`。price-provider 会把标记价格保存成 quote asset 最小单位 `mark_price_units`，risk-provider 再用持仓锁定版本的 `price_tick_units` 把这个 long 值转换成版本对应的 `markPriceTicks`。risk-provider 会按当前仓位 notional 匹配 `instrument_risk_brackets` 中最高适用档位的 `maintenance_margin_rate_ppm`；如果没有风险档位配置，才回退使用 `instruments.maintenance_margin_rate_ppm`。之后 `RiskMath` 使用 long 输入/输出和精确整数中间值计算 notional、未实现盈亏和维持保证金。
risk 会用 `account_positions.instrument_version` join `instruments`，不会用最新 instrument 版本重新解释已有持仓。
账户级风险合计会用 checked long addition 聚合 `unrealizedPnlUnits` 和 `maintenanceMarginUnits`。如果溢出，只回滚当前 `userId + settleAsset` 扫描事务，并等待下一轮扫描重试。

合约公式：

- `LINEAR_PERPETUAL`：notional 和 PnL 使用 `priceTicks * quantitySteps * notional_multiplier_units`。
- `INVERSE_PERPETUAL`：持仓价值和 PnL 用 `faceValueUnits * settleScaleUnits / priceQuoteUnits` 以及 entry/mark price 倒数关系折算成结算币单位。

状态：

- `NORMAL`：保证金率低于预警线。
- `WARNING`：保证金率达到预警线但未达到强平线。
- `LIQUIDATION`：保证金率达到或超过强平线，生成候选。

默认阈值：

- 预警线：`800000 ppm`。
- 强平线：`1000000 ppm`。

## 核心链路

```text
account_positions + account_balances + account_deficits
  + price_mark_ticks
  + instruments / instrument_current_versions
  + account_asset_scales
  + 可选触发: surprising.account.position.events.v1
  -> risk_account_snapshots
  -> risk_position_snapshots
  -> surprising.risk.account.events.v1 / surprising.risk.position.events.v1
  -> risk_liquidation_candidates
  -> surprising.perp.liquidation.candidates.v1
```

`risk-provider` 按 keyset 分页扫描账户资产组：`userId ASC, settleAsset ASC`，每次数据库读取
`surprising.risk.calculation.scan-batch-size` 个 `userId + settleAsset` 组。只有这个账户资产组里的所有开放持仓
都有新鲜 mark price 时，该组才会进入风险计算。每个账户资产组再单独使用一个 Spring 事务：账户快照、持仓快照、
爆仓候选插入和 outbox 插入要么一起提交，要么一起回滚。某个组失败后只记录日志，等待下一轮扫描重试；同一轮扫描里的其他账户组继续处理。

provider 还会消费 `surprising.account.position.events.v1`。账户完成成交结算并更新持仓后，会触发对应
`userId + settleAsset` 组立即扫描；risk 会用事件里的 `symbol` 和持仓锁定的 `instrumentVersion` 定位风险组。
如果事件版本是 `0`，会回退查 `instrument_current_versions`，这样完全平仓后的事件仍能定位结算资产。这个事件驱动链路用于降低爆仓发现延迟；keyset 定时扫描仍是兜底，用来覆盖 Kafka 漏处理、mark 过期、重放和人工暂停后的恢复。
如果该事件关闭了账户资产组里的最后一个开放持仓，risk-provider 仍会写一条账户快照：未实现盈亏为 0、
维持保证金为 0、状态为 `NORMAL`。同时会给该事件 symbol 写一条 0 仓位 position snapshot，避免最新持仓风险查询继续返回旧的非零仓位。

每次成功提交的风险扫描也会写事务 outbox 事件，用于前端私有推送：

- `RISK_ACCOUNT_UPDATED`：包含 `userId + settleAsset` 维度的钱包余额、未实现盈亏、权益、维持保证金、保证金率和状态。
- `RISK_POSITION_UPDATED`：包含单个持仓的 mark price、名义价值、未实现盈亏、维持保证金、逐仓保证金、保证金率和状态。

这些事件是后端计算出的权威展示值。前端可以把 `positions` 和 `mark` 事件组合起来做更高频的视觉插值，
但爆仓、账户风险和真实权益判断必须以 risk-provider 的快照/事件为准。由账户持仓事件触发的风险扫描会
把原事件的 `traceId` 继续写入风险事件；定时兜底扫描产生的风险事件可能没有 traceId。

## Kafka

- `surprising.account.position.events.v1`：账户结算后的持仓更新事件，key = `symbol`；risk-provider 消费用作低延迟扫描触发器。
- `surprising.risk.account.events.v1`：账户风险快照更新事件，key = `userId:settleAsset`；websocket-provider 消费用于私有 `accountRisk` 推送。
- `surprising.risk.position.events.v1`：持仓风险快照更新事件，key = `symbol`；websocket-provider 消费用于私有 `positionRisk` 推送。
  risk outbox 发布时会在不改变 Kafka key 的前提下按 `topic + event_key` claim 连续前缀，避免同一 symbol
  积压时每轮只发布一条造成长尾。
- `surprising.perp.liquidation.candidates.v1`：爆仓候选事件，key = `symbol`。

`surprising-liquidation-provider` 消费这个 topic，抢占候选并提交 reduce-only 强平订单。

## API

查询账户风险：

```bash
curl 'http://localhost:9087/api/v1/risk/account/latest?userId=1001&settleAsset=USDT'
```

查询用户持仓风险：

```bash
curl 'http://localhost:9087/api/v1/risk/positions/latest?userId=1001'
```

查询爆仓候选：

```bash
curl 'http://localhost:9087/api/v1/risk/liquidation-candidates?status=NEW&limit=100'
```

后台风控运营接口必须通过 admin gateway 和管理员 token 访问：

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://localhost:8080/api/v1/admin/gateway/risk-admin/rules'

curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://localhost:8080/api/v1/admin/gateway/risk-admin/high-risk-accounts?limit=100'

curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://localhost:8080/api/v1/admin/gateway/risk-admin/liquidation-candidates?status=NEW&limit=100&sort=eventTime.asc'

curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"warningMarginRatioPpm":800000,"liquidationMarginRatioPpm":1000000,"reason":"quarterly risk policy review"}' \
  'http://localhost:8080/api/v1/admin/gateway/risk-admin/rules/GLOBAL_MARGIN_POLICY'
```

`GLOBAL_MARGIN_POLICY` 会持久化保证金预警/强平阈值覆盖，并即时更新当前 risk-provider 节点运行时阈值；`RISK_SCAN_CONTROL` 可持久化扫描启停、扫描间隔和批量大小。高风险账户聚合只读取最新账户风险快照、同快照仓位、活跃爆仓候选数量和最高风险仓位，供后台分层运营和告警联动。后台爆仓候选和高风险账户列表支持 `limit/cursor/sort` 游标分页，排序白名单为 `eventTime.asc`、`eventTime.desc`，响应保留原列表字段并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `account_asset_scales`
- `account_deficits`
- `risk_sequences`
- `risk_scan_leases`
- `risk_account_snapshots`
- `risk_position_snapshots`
- `risk_liquidation_candidates`
- `risk_admin_rule_overrides`
- `risk_outbox_events`

核心索引：

- `risk_account_snapshots_query_idx`
- `risk_position_snapshots_user_idx`
- `account_positions_open_scan_idx`
- `risk_scan_leases_expiry_idx`
- `risk_liquidation_candidates_status_idx`
- `risk_liquidation_candidates_active_uidx`
- `risk_outbox_pending_idx`
- `risk_outbox_pending_key_idx`

## 多节点协调

provider 支持 active-active 多节点部署，使用 PostgreSQL 按 `userId + settleAsset` 做扫描租约。
节点在写风险快照或爆仓候选前，会 upsert `risk_scan_leases`。只有当前节点已持有该行，或上一任
`lease_until` 已过期，才会接管这个账户资产组。

配置：

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `surprising.risk.kafka.group-id` | `surprising-risk-v1` | 多个 risk-provider 节点消费账户持仓事件时共享的 consumer group。 |
| `surprising.risk.kafka.position-events-topic` | `surprising.account.position.events.v1` | 账户结算持仓事件 topic，用作事件驱动扫描触发器。 |
| `surprising.risk.kafka.account-risk-events-topic` | `surprising.risk.account.events.v1` | 账户风险 WebSocket 事件 outbox topic。 |
| `surprising.risk.kafka.position-risk-events-topic` | `surprising.risk.position.events.v1` | 持仓风险 WebSocket 事件 outbox topic。 |
| `surprising.risk.kafka.concurrency` | `2` | Kafka listener 并发数。单节点不要超过实际可用 partition 并行度。 |
| `surprising.risk.kafka.max-poll-records` | `500` | 每次 poll 拉取的 Kafka 记录数。结合持仓事件 lag 和扫描事务耗时调优。 |
| `surprising.risk.coordination.enabled` | `true` | 开启扫描租约。多节点部署保持开启。 |
| `surprising.risk.coordination.node-id` | `${HOSTNAME:}` | 稳定 owner id。为空时进程会生成本地随机 id。 |
| `surprising.risk.coordination.lease-duration` | `15s` | 停止扫描节点的故障转移等待时间，应长于扫描间隔。 |
| `surprising.risk.calculation.scan-batch-size` | `500` | 每次 keyset 分页读取的 `userId + settleAsset` 组数。调大可减少 DB 往返，调小可降低大账户场景的扫描尖峰延迟。 |
| `surprising.risk.outbox.batch-size` | `1000` | 每轮最多 claim 的到期 risk outbox 行数。 |
| `surprising.risk.outbox.publish-delay-ms` | `20` | risk outbox 排空调度间隔。 |
| `surprising.risk.outbox.async-enabled` | `true` | claim 短租约后开启有界并发发布。 |
| `surprising.risk.outbox.max-in-flight` | `32` | 单节点同时发布的 `topic + event_key` 分组上限，应低于 Kafka 和 DB 可承受容量。 |
| `surprising.risk.outbox.max-rows-per-key` | `32` | 单轮对同一个 `topic + event_key` 最多 claim 的连续到期行数；调大可减少同 symbol 积压尾部，调小可降低单 key 对 Kafka/DB 的瞬时压力。 |
| `surprising.risk.outbox.send-timeout` | `3s` | 单条 Kafka send 等待超时；超时后标记失败并按 backoff 重试。 |

## 本地运行

```bash
brew services start postgresql@18
brew services start kafka
brew services start redis
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-risk-provider -am spring-boot:run
```

端口：

- `9087`：风险服务。

## 生产注意事项

- risk-provider 依赖最新 mark price；mark price 超过 `max-mark-age` 时不会参与风险扫描。
- 风险扫描按 `userId + settleAsset` 独立事务隔离。某个异常账户组不能回滚整批扫描，也不能阻塞其他账户。
- risk outbox 发布按 `topic + event_key` claim 短租约，不同 key 有界并发发布。同一个账户风险 key、持仓 symbol key
  或强平 symbol key 内保持顺序；但 Kafka send 成功后、`published_at` 落库前如果进程崩溃，仍可能重复发送，消费者必须幂等。
- 账户组级 PnL 或维持保证金聚合溢出会被当成状态异常处理。provider 会在写入快照、候选或 outbox 前回滚该账户组事务。
- 爆仓候选是强平输入，不是强平执行结果。
- 强平执行必须再次校验最新 mark/equity，避免候选生成后行情恢复仍继续强平。
- 风险扫描和强平执行都必须幂等。
- risk-provider 可以多节点部署保证可用性。`risk_scan_leases` 会按 `userId + settleAsset` 协调写入者，保证同一账户资产组只有一个存活 owner 写风险快照和爆仓候选；owner 挂掉后租约过期，其他节点接管。
- 扫描器使用 `userId + settleAsset` keyset 分页，并依赖 `account_positions_open_scan_idx` 部分索引避免在抢租约前加载全量持仓。不要改成 offset 分页；offset 在持仓变化时会越来越慢，也可能跳过或重复账户组。
- 持仓事件扫描和定时扫描都会走同一个 `risk_scan_leases` ownership guard 和同一个账户组事务。Kafka consumer group 先减少重复事件处理，PostgreSQL 租约再防止事件重放或多节点竞争时出现重复写快照/候选。
- 持仓事件消费者会拒绝 Kafka key 和 payload `symbol` 不一致的记录，保证 symbol 分区和顺序语义与撮合、账户、强平模块一致。
- 如果持仓事件计算结果为空，provider 不会直接假设账户已经平仓。它会先检查该 `userId + settleAsset`
  组是否仍有开放持仓；如果仍有开放持仓，空结果会被视为 mark 过期或缺失，本轮不写误导性的 `NORMAL` 快照。
- 如果某个 `userId + settleAsset` 组里任何开放持仓没有新鲜 mark price，本轮会跳过整个组。风险聚合不允许只计算部分持仓，否则会低估维持保证金。
- 数据库保证同一个 `userId + symbol` 同时最多只有一个 `NEW/PROCESSING` 爆仓候选；上一个候选进入 `COMPLETED` 或 `CANCELED` 后，后续扫描才能生成新的候选。
- 风险快照和 outbox 写入必须影响 1 行；如果 insert/update 被冲突或异常状态跳过，risk-provider 会 fail-fast 并回滚当前事务。
- 爆仓候选 insert 返回 0 只表示部分唯一索引里已经有 `NEW/PROCESSING` 活跃候选。candidate-id 或 snapshot 唯一键冲突不能被当成幂等，必须失败暴露出来；只要候选成功插入，就必须读回候选并写入 outbox，否则扫描失败，避免 DB 有候选但 Kafka 事件丢失。
- outbox 发布后的 `published_at` 标记和失败重试标记也必须更新到对应行；如果行不存在，说明状态不一致，需要让进程失败并人工排查。
- 当前模块负责发现爆仓账户并发布候选。`surprising-liquidation` 会复核最新风险状态后执行 reduce-only 平仓订单。

## 验证

```bash
mvn -pl :surprising-risk-provider -am test
```
