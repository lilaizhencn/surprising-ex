# 账户资金单写者与单用户串行通道

## 目标与边界

账户模块是以下可变资金事实的唯一写者：

- `account_balances`
- `account_product_balances`
- `account_positions`
- `account_position_margins`
- `account_margin_reservations`
- `account_deficits`
- `account_ledger_entries` 与产品账户流水

订单、撮合、资金费、ADL、保险基金、交割/行权和管理端接口只能产生
`AccountUserCommand`，不能直接更新上述表。它们仍可读取账户事实用于计算，但读取结果不授权资金变更；
最终条件检查必须在 account-provider 的数据库事务内重新执行。

串行边界是 `productLine + userId`。每条产品线使用独立 Topic：

```text
surprising.<product-segment>.account.user.commands.v1
surprising.<product-segment>.account.user.commands.dlt.v1
surprising.<product-segment>.account.command.results.v1
```

命令 Kafka key 固定为：

```text
<PRODUCT_LINE>:<userId>
```

三个 Topic 都创建 32 个分区，account-provider 的 `user-command-concurrency` 默认也是 32。
同一产品线、同一用户的所有指令进入同一分区并逐条处理；不同用户可并行。总消费者并发超过 32
不会增加该产品线吞吐。某个技术故障会阻塞所在分区，系统不会为了可用性跳过资金指令。

产品线 Topic 和消费组始终隔离，不再提供共享/legacy 账户指令 Topic。跨产品线共享钱包行仍由
PostgreSQL 行锁和非负约束做最终并发保护；产品线级串行不能被误解为跨所有产品线的全局线程。

## 一致性模型

本方案不使用 XA，也不把 Kafka 事务当作数据库事务：

1. 业务模块在自己的 PostgreSQL 事务中写业务事实和 `account_outbox_events`。
2. outbox 发布器使用原始 key 把命令至少一次发送到 Kafka。
3. account-provider 在一个本地 PostgreSQL 事务中完成：
   - 注册/校验 `account_commands`；
   - 条件更新余额、持仓、保证金、亏空；
   - 写不可变账本；
   - 把命令置为 `APPLIED` 或 `REJECTED`；
   - 写结果和下游事件 outbox。
4. Kafka offset 只在该事务成功后提交。事务失败时记录重投，`commandId` 幂等拦截重复执行。

Redis 持仓只是数据库提交后的 revision 投影。用户持仓查询读 Redis；资金判断、撮合结果、
资金费、强平和 ADL 不以 Redis 为锁或事实源。投影失败不回滚已提交资金，outbox 可以重放恢复。

## 幂等层

幂等不是只靠 Kafka：

- HTTP 写接口必须传稳定的 `referenceId`。`account_command_submissions` 保存规范化请求的
  SHA-256；相同 reference、相同 payload 返回同一个 command，相同 reference、不同 payload
  在任何资金写入前拒绝。
- `account_commands.command_id` 是执行幂等键，并保存完整 envelope 的 SHA-256。重复 command
  只有全部身份字段和 payload 完全一致才允许；冲突重复被视为数据完整性故障。
- 账本、订单 reservation、资金费 payment、ADL execution、保险 coverage 继续保留各自的业务唯一键。
- outbox 是至少一次投递；消费端必须允许同一消息重复出现。

同步 HTTP 等待不是正确性条件。网关提交命令后，账户模块用一个批量数据库轮询器等待
`account_commands` 终态；结果 Topic 只用于低延迟通知和观测。HTTP 超时表示“结果未知”，调用方必须
用原 `referenceId` 重试，不能生成新 reference。

## 不依赖跨 Topic 顺序

任何有先后条件的指令都带 `dependsOnCommandId`。依赖不存在或未完成时，子命令持久化为
`WAITING_DEPENDENCY`，不修改资金；父命令终态后通过 outbox 重投子命令。子命令重新执行时直接查询
`account_commands`：

- 父命令 `APPLIED`：子命令进入 `PROCESSING`；
- 父命令 `REJECTED`：子命令以 `DEPENDENCY_REJECTED` 终止；
- 父命令仍未终态或尚未到达：继续等待。

因此，生产者先后发送、不同 outbox publisher 的速度、结果 Topic 延迟或消费者重平衡都不会改变
状态机正确性。结果消费者也必须以数据库中的 `account_commands` 为权威进行定时 reconciliation。

## 关键业务状态机

### 下单

```text
trading_orders.PENDING_RESERVE
  -> ORDER_RESERVE
  -> APPLIED  -> order ACCEPTED -> 发布撮合命令
  -> REJECTED -> order REJECTED
```

订单不能在 reservation 成功前进入撮合。撤单、拒单和成交后的释放使用 `ORDER_RELEASE`；
需要等待成交侧结算时，release 依赖该侧的 `TRADE_SIDE_SETTLE`。

`ORDER_RESERVE` 会把订单总量和 `reduceOnly` 写入 account 自己的 reservation 行；
`TRADE_SIDE_SETTLE` 与 `ORDER_RELEASE` 都携带同一份不可变快照。account 必须校验
产品线、账户类型、用户、订单总量和 `reduceOnly` 后才可迁移或释放保证金，成交热路径不再读取
`trading_orders`。快照不一致、非 reduce-only 订单缺 reservation 或命令用户不匹配都必须整笔回滚。

### 双边成交

一个成交涉及两个用户，不能放进“单用户分区”后仍声称原子。matching-provider 为同一成交写：

- taker 的 `TRADE_SIDE_SETTLE`
- maker 的 `TRADE_SIDE_SETTLE`

两条命令分别使用各自的用户 key、commandId 和数据库事务。每侧在
`account_trade_settlement_sides` 写一条不可变记录，不共享更新行；只有两侧都存在时才会进入
`account_trade_settlement_completions` 视图。任何一侧超过
`trade-settlement.stale-after`（默认 1 分钟）仍未完成，Actuator
`accountTradeSettlement` health 变为 `DOWN`。不能人工把另一侧标成成功，必须修复根因并重放原命令。

单侧事务不会在资金处理前预先插入共享结算行。余额、保证金、流水和仓位全部成功后，事务末尾
通过一次 `INSERT ... ON CONFLICT DO UPDATE` 原子写入本侧 `APPLIED`；冲突更新同时校验
taker/maker 用户和本侧仍为 `PENDING`。校验不通过时 UPSERT 影响 0 行并抛错，单侧事务全部回滚。
这样两侧可以并行处理各自账户，只在事务末尾短暂竞争同一成交行。

### 资金费

funding-provider 只计算并为每个用户写 `FUNDING_SETTLE`。结算保持
`PROCESSING -> WAITING_ACCOUNTS -> COMPLETED/FAILED`，每个 payment 以账户命令终态为准。
结果 Topic 只加速唤醒，定时 reconciliation 直接读账户命令表。

### ADL

```text
ADL_DEFICIT_RESERVE
  -> ADL_TARGET_SETTLE
  -> ADL_DEFICIT_FINALIZE
```

目标仓位变化导致 target 以 `STALE_ADL_TARGET` 拒绝时，saga 产生
`ADL_DEFICIT_RELEASE` 释放已预留亏空。三步通过 command dependency 连接，不依赖 Topic 顺序。
ADL Redis 候选索引按产品线隔离，只用于选候选；账户事务会再次校验目标仓位版本和数量。

### 保险基金

insurance-provider 先在本地事务预留保险基金，再发 `INSURANCE_DEFICIT_RESERVE`，成功后发依赖它的
`INSURANCE_DEFICIT_FINALIZE`。任一步失败都由 coverage 状态机保留可恢复事实，不能直接改
`account_deficits`。

### 交割、行权和管理操作

交割/行权事件先按持仓用户 fan-out 为单用户命令。充值、冲正、产品账户调整、产品账户划转、
仓位模式和逐仓保证金调整同样经过账户命令通道，并要求 `referenceId`。

## 监控与告警

必须采集：

- Kafka `account.user.commands` 消费 lag、最老消息年龄和分区阻塞时间；
- `surprising.account.command.events{outcome=applied|rejected|waiting_dependency|duplicate|failed}`；
- `surprising.account.command.processing` 和 `surprising.account.command.event_lag`；
- `accountTradeSettlement` health；
- account outbox 未发布数量、最老行年龄和重试次数；
- DLT 消息数，生产环境应以非零立即告警；
- funding `WAITING_ACCOUNTS`、ADL pending saga、insurance pending coverage 的最老年龄。

推荐数据库巡检：

```sql
SELECT product_line, status, COUNT(*), MIN(started_at) AS oldest
FROM account_commands
WHERE status IN ('WAITING_DEPENDENCY', 'PROCESSING')
GROUP BY product_line, status;

SELECT product_line, COUNT(*), MIN(applied_at) AS oldest
FROM account_trade_settlement_sides
WHERE reconciled_at IS NULL
  AND applied_at < now() - interval '1 minute'
GROUP BY product_line, symbol, trade_id
HAVING COUNT(*) < 2;

SELECT product_line, topic, COUNT(*), MIN(created_at) AS oldest
FROM account_outbox_events
WHERE published_at IS NULL
GROUP BY product_line, topic;

SELECT status, COUNT(*), MIN(funding_time) AS oldest
FROM funding_settlements
WHERE status IN ('PROCESSING', 'WAITING_ACCOUNTS')
GROUP BY status;

SELECT status, COUNT(*), MIN(created_at) AS oldest
FROM adl_execution_sagas
WHERE status IN ('PENDING', 'RELEASING')
GROUP BY status;

SELECT status, COUNT(*), MIN(created_at) AS oldest
FROM insurance_deficit_coverages
WHERE status IN ('PENDING_RESERVE', 'PENDING_FINALIZE')
GROUP BY status;
```

持续存在的 `PROCESSING` 不属于正常状态，因为注册、资金变更和终态在同一个事务内；应立即排查
人工数据修改或事务边界错误。

## 故障恢复

- 技术异常：保留分区阻塞，修复数据库、配置或代码后让原记录继续重试；禁止跳 offset。
- poison 消息：进入同分区号 DLT。修复生产者后，以原 key、原 commandId 和正确 payload 重放。
- API 超时：查询 command 状态或使用同一 `referenceId` 重试，绝不创建第二笔资金意图。
- 依赖长期等待：先查父 command，再查 account outbox。父已终态时可安全重放原子命令；不要手工改状态。
- 双边成交不完整：对照 taker/maker commandId、outbox 和 DLT，恢复缺失侧；不要直接补余额或持仓。
- Redis 缓存异常：从 PostgreSQL/outbox 重建投影；资金链路继续以数据库为准。

## 部署检查

1. 新环境执行 `scripts/create-topics.sh`，确认每条产品线三个账户 Topic 均为 32 分区且 DLT
   分区数与命令 Topic 完全相同。
2. 同一产品线所有 account-provider 副本使用相同 user-command group id；每个实例使用唯一 client id。
3. 副本数乘单实例有效 concurrency 不必超过 32。
4. Kafka producer 必须保持 `acks=all`、幂等开启；PostgreSQL 必须保持 durability 配置。
5. 运行 `scripts/check-account-single-writer.sh`，确保非 account 模块没有重新引入账户资金表 DML。
6. 运行 account、order、matching、funding、ADL、insurance 测试以及全量 `mvn test`。
7. 上线前压测必须同时检查吞吐、p99、Kafka lag、outbox backlog、双边成交完成率和资金守恒。
