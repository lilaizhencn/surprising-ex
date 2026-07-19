# 线性永续 5000 用户同时强平压测报告

时间：2026-07-19（Asia/Shanghai）

Run label：`linear-perp-liquidation-5000-final`

## 结论

5000 个用户最终全部强平成交、持仓全部归零，强平费与保险基金逐单位相等，最终资金核对
`Violations=0`。但这轮不能判定为性能通过：从统一标记价冲击到 5000 人全部强平完成耗时
`182.264s`，平均只有 `27.433 用户/s`，p99 完成延迟为 `180.449s`。

这轮还发现一个独立的正确性问题：强平平仓后的 `PositionUpdatedEvent` 携带
`instrumentVersion=0`，Risk consumer 拒绝并在重试后丢弃 position-event 批次。最终仓位与资金正确，
是因为 PostgreSQL 持仓结算和定时风险扫描仍然完成；Kafka 风险快路径并不完整。因此脚本进程虽然
返回 0，本报告将总体结论标记为“不通过，需要修复”。

## 测试口径

- 只启动 `LINEAR_PERPETUAL`；wallet 未启动，market-maker 全程运行。
- 20 个 active symbol，20 个 maker account，5000 个 taker 用户均匀分配到 20 个 symbol。
- 所有用户通过 gateway 单笔真实开仓，下单并发 128；撮合、Kafka、account 结算均走真实 provider。
- 用户按实际开仓名义价值的 12% 加开仓手续费预注资；冲击前 5000 个最新风险快照全部为 `NORMAL`。
- 指数和标记价服务在下游 provider 启动后启动；触发时 20 个 symbol 的标记价同时、持续降到原价的 80%。
- matching Kafka concurrency 4、matching engine 4、matching risk engine 2；liquidation consumer concurrency 2。
- 完成条件：5000 个用户均存在 `FILLED` 强平单、非零仓位为 0、三个 Outbox 均清空、资金核对 0 违规。

## 性能结果

| 指标 | count | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|
| 冲击 → candidate | 5421 次尝试 | 92.197s | 171.864s | 180.064s | 181.966s |
| candidate → 强平单提交 | 5421 次尝试 | 0.599s | 5.252s | 7.071s | 11.039s |
| 冲击 → candidate 完成 | 5421 次尝试 | 94.799s | 172.212s | 180.449s | 182.264s |
| 开仓 ACCEPTED → 双边结算 | 5000 | 30.654s | 51.943s | 58.414s | 67.745s |

- 5000 个已完成强平从冲击开始计时：`182.264s`，平均 `27.433 用户/s`。
- 开仓 API 提交耗时：`73.054s`。
- 冲击前完整安全扫描耗时：`60.196s`。
- candidate 状态：`COMPLETED=5000`，`CANCELED=421`。
- 强平单状态：`FILLED=5000`，`CANCELED=419`，`REJECTED=2`。
- 最终非零用户仓位：`0`。

candidate 的 p50/p95/p99 几乎决定了总耗时；candidate 到强平单提交的 p99 只有 7.071 秒，说明
主要瓶颈不在 liquidation consumer，而在风险发现。

## Kafka lag

| consumer group | peak total lag | peak partition lag | 最后一次采样 total lag |
|---|---:|---:|---:|
| account user-command | 1030 | 235 | 0 |
| liquidation | 1582 | 304 | 0 |
| matching | 1696 | 439 | 10 |
| order account-result | 176 | 15 | 16 |
| order position-maintenance | 32 | 6 | 0 |
| risk position-event | 403 | 62 | 0 |

Risk group 的最终 lag 为 0 并不表示所有消息处理成功：本轮日志有 `3612` 个
`Records discarded` 批次，共列出 `7727` 个被丢弃的 record offset。消费位点前进后 lag 同样会归零。

## 资金与状态核对

| 项目 | 整数单位 |
|---|---:|
| 期初 | 0 |
| 充值/调整 | 4,000,204,313,775,000 |
| 成交净额 / 发生额 | 0 / 200,000,000 |
| 交易手续费 | -847,775,000 |
| 资金费净额 / 发生额 | 0 / 0 |
| 用户强平费 | -5,086,350,000 |
| 期末应有 | 4,000,198,379,650,000 |
| 期末实际 | 4,000,198,379,650,000 |

- 用户强平费：5000 条，合计 `-5,086,350,000`。
- 保险基金强平费入账：5000 条，合计 `+5,086,350,000`，与用户扣款逐单位相等。
- 非零亏空：0；保险基金亏空承保：0；ADL event：0；资金费：0。
- Account、Trading、Risk Outbox 最终 pending 均为 0，最老 pending 年龄均为 0。
- 资金核对最终 `Violations=0`。

## 问题定位

### 1. Risk 定时扫描是串行瓶颈

`RiskService.scan()` 在一个 `@Scheduled` 调度线程里分页获取 risk group，随后对每个用户依次执行
`scanRiskGroup()`。`scanBatchSize=500` 只控制分页大小，循环本身没有并行处理；每个 group 又单独获取
lease、查询持仓并开启事务写风险快照和 candidate。标记价变化没有直接生成批量 risk command，因此
5000 用户统一价格冲击退化为逐用户数据库扫描。

证据与本轮延迟一致：冲击到 candidate 的 p99 为 180.064 秒，而 candidate 到强平单提交 p99
只有 7.071 秒。后续优化应优先把“标记价变化影响的持仓集合”做成可分片、可批量 claim 的风险扫描，
而不是继续增加 liquidation consumer 并发。

关键源码：

- `surprising-margin-ops/surprising-risk-provider/.../service/RiskService.java`：`scan()`、`scanRiskGroup()`。
- `surprising-margin-ops/surprising-risk-provider/.../repository/RiskRepository.java`：`riskGroups()`、风险计算查询。

### 2. 平仓事件把 instrument version 清零

`PositionCalculator.apply()` 在 `newQty == 0` 时把 `newInstrumentVersion` 设为 0，repository 将其写成
数据库 `NULL`。`AccountOutboxRepository.enqueuePositionUpdated()` 捕获最终快照时再把 `NULL` 转回 0；
`RiskService.targetFrom()` 则明确要求 version 必须大于 0。三处约定互相冲突，导致强平后的平仓事件必然
被 Risk consumer 拒绝。

这个问题还放大了 candidate churn：前一个 candidate 完成、account 持仓尚未完全可见时，定时扫描可能
再次生成 candidate。本轮额外产生 421 个 canceled candidate、419 个 canceled 和 2 个 rejected
强平单尝试，虽然最终每个用户都只有一个成功平仓结果。

### 3. 监控缺口

- `pg_stat_statements` 未 preload/安装或当前账号无权限，因此 PostgreSQL Top SQL 为 `N/A`；本轮无法给出可信慢 SQL 排名。
- `pg_stat_database.deadlocks=2` 是数据库自创建以来的累计值，测试未在开始前采集/reset 基线，不能归因到本轮。
- 应把 Kafka `Records discarded`、DLT/恢复器结果和 position-event schema 不变量纳入压测失败条件，避免“lag=0、脚本 exit 0”造成假绿。

原始运行报告：`/tmp/linear-perpetual-liquidation-5000-20260719.md`

运行日志目录：`/tmp/surprising-product-line-api.6n1kOh`
