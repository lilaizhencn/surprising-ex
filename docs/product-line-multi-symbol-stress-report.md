# 四产品线 20 Symbol / 2000 用户真实链路压测报告

日期：2026-07-09

## 结论

本轮按产品线单独启动服务，wallet 服务未启动，market-maker 持续运行。永续、交割、期权、现货均完成 20 个活跃 symbol、20 个 maker 账户、2000 个 taker 用户并发 open 与 close/sell 流程；订单经 gateway/API 真实下单，非单元测试。

资金核对结果：四条产品线均为 OK，未发现账账不平、负余额、负保证金、deficit、预占超释放或持仓未归零问题。

主要瓶颈：撮合与 account 结算都能完成本轮压力；尾部最慢的是 risk outbox 发布，永续/交割/期权在 close 后均出现 1.6 万到 2.4 万级别 risk outbox pending，然后逐步清空。资金核对 SQL 的费用引用校验原先 5 分钟未返回，已改为 maker/taker 展开表加临时索引后，交割同量级数据核对约 2 秒内完成。

## 压测参数

- 每次只测一个产品线：`LINEAR_PERPETUAL`、`LINEAR_DELIVERY`、`OPTION`、`SPOT`。
- 每条产品线 active symbols：20。
- Maker accounts：20，每个 symbol 一个 maker account。
- Maker 高频：初始 6 档；open 和 close/sell 阶段各刷新 3 轮，每轮 2 档；maker 并发 32。
- Taker users：每条产品线 2000 个用户；open 和 close/sell 阶段各 2000 笔用户订单，并发 128。
- 用户下单量：每个用户每个阶段 1 笔，四条线合计用户订单 16000 笔；另有 maker 初始挂单和刷新挂单持续运行。
- 并发平仓：永续、交割、期权各 2000 个 taker 持仓并发 close/sell 后全部归零。
- 并发爆仓：本轮高频主链路未触发强平，强平费应为 0，实际为 0。
- Kafka：`RESET_KAFKA=false`，不重建历史 topic；按 RUN_ID 隔离测试数据。

## 执行记录

| 产品线 | RUN_ID | 日志目录 | 结果 |
|---|---:|---|---|
| LINEAR_PERPETUAL | 20260709040000 | `/tmp/surprising-product-line-api.y14zAG` | PASS |
| LINEAR_DELIVERY | 20260709061000 | `/tmp/surprising-product-line-api.tfdrpz` | PASS |
| OPTION | 20260709054000 | `/tmp/surprising-product-line-api.ztSaM8` | PASS |
| SPOT | 20260709054000 | `/tmp/surprising-product-line-api.ztSaM8` | PASS |

## 订单与结算

| 产品线 | Symbol | Maker账户 | 用户 | Open订单/成交/结算 | Open提交耗时 | Open提交速率 | Close订单/成交/结算 | Close提交耗时 | Close提交速率 | 拒单/撤单 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| LINEAR_PERPETUAL | 20/20 | 20/20 | 2000 | 2000 / 2000 / 2000 | 15591.075 ms | 128.3/s | 2000 / 2000 / 2000 | 14332.273 ms | 139.5/s | 0 / 0 |
| LINEAR_DELIVERY | 20/20 | 20/20 | 2000 | 2000 / 2000 / 2000 | 14942.072 ms | 133.9/s | 2000 / 2000 / 2000 | 13542.739 ms | 147.7/s | 0 / 0 |
| OPTION | 20/20 | 20/20 | 2000 | 2000 / 2000 / 2000 | 22488.453 ms | 88.9/s | 2000 / 2000 / 2000 | 19301.178 ms | 103.6/s | 0 / 0 |
| SPOT | 20/20 | 20/20 | 2000 | 2000 / 2000 / 2000 | 14515.315 ms | 137.8/s | 2000 / 2000 / 2000 | 14660.911 ms | 136.4/s | 0 / 0 |

## Account 结算延迟

单位：ms，格式为 `count min p50 p95 p99 max`。

| 产品线 | Open account latency | Close account latency |
|---|---|---|
| LINEAR_PERPETUAL | `2000 2394.304 75258.747 90674.207 94696.360 96994.232` | `2000 1219.812 28102.995 47253.755 51061.725 53245.898` |
| LINEAR_DELIVERY | `2000 1754.598 46779.431 61094.517 65058.104 68803.800` | `2000 1776.516 27912.547 47599.743 51221.564 54349.663` |
| OPTION | `2000 3359.033 82855.406 108471.439 114109.964 118728.067` | `2000 1051.661 84304.929 119301.855 124491.488 128786.184` |
| SPOT | `2000 2237.405 48413.381 62404.240 65878.199 68464.431` | `2000 1479.382 22473.590 36864.852 40528.535 41816.838` |

## 资金逐项核对

口径说明：

- 期初均为 0。
- `充值/调整` 为本轮 fixture 注入并落 ledger 的资金。
- `成交/权利金发生额` 为绝对值发生额。
- 现货手续费在 ledger 中以 `SPOT_TRADE` 的 fee reason 记录；下表按资金项拆分到 `手续费`，因此 SPOT/USDT 的成交净额为 0，手续费为 -868748060。
- `期末应有 = 期初 + 充值/调整 + 成交/权利金净额 + 手续费 + 资金费 + 强平费 + 交割/行权净额`。

| 产品线 | 资产 | 期初 | 充值/调整 | 成交/权利金净额 | 成交/权利金发生额 | 手续费 | 资金费净额/发生额 | 强平费 | 交割/行权净额/发生额 | 期末应有 | 期末实际 | 结果 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| LINEAR_PERPETUAL | USDT | 0 | 404000000000000000 | 0 | 80000000 | -867479815 | 0 / 0 | 0 | 0 / 0 | 403999999132520185 | 403999999132520185 | OK |
| LINEAR_DELIVERY | USDT | 0 | 404000000000000000 | 0 | 80000000 | -868879188 | 0 / 0 | 0 | 0 / 0 | 403999999131120812 | 403999999131120812 | OK |
| OPTION | USDT | 0 | 404000000000000000 | 0 | 99000000000 | -31665316 | 0 / 0 | 0 | 0 / 0 | 403999999968334684 | 403999999968334684 | OK |
| SPOT | AAVE | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | ADA | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | AVAX | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | BCH | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | BNB | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | BTC | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | DOGE | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | DOT | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | ETC | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | ETH | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | FIL | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | LINK | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | LTC | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | NEAR | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | OP | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | SOL | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | TON | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | TRX | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | UNI | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |
| SPOT | USDT | 0 | 404000000000000000 | 0 | 2712800000000 | -868748060 | 0 / 0 | 0 | 0 / 0 | 403999999131251940 | 403999999131251940 | OK |
| SPOT | XRP | 0 | 100000000000 | 0 | 40000000 | 0 | 0 / 0 | 0 | 0 / 0 | 100000000000 | 100000000000 | OK |

## 状态校验

| 校验项 | 结果 |
|---|---|
| 用户订单拒单/撤单 | 四条线均 0 / 0 |
| Margin 产品用户持仓 | 永续、交割、期权 close 后均为 0 |
| 现货预占 | settled + released 未超过 reserved |
| 负余额 | 0 |
| 负 position margin | 0 |
| product deficit / legacy deficit | 0 |
| account outbox | 每条线结束前已 drain |
| trading outbox | 每条线结束前已 drain |
| risk outbox | 永续、交割、期权结束前已 drain |
| 资金核对 violations | 0 |

## 瓶颈与处理

| 环节 | 观察 | 处理/结论 |
|---|---|---|
| Matching | 2000 open + 2000 close/sell 均能完成，未见拒单/撤单 | 当前规模下不是最慢尾部 |
| Account settlement | 能全部入账，p95 在 36.8s 到 119.3s 区间 | 可以跟上本轮撮合，但期权延迟最高 |
| Risk outbox | 永续、交割、期权 close 后出现 1.6 万到 2.4 万级别 pending | 已调高 risk Kafka concurrency 和 outbox batch；仍是最明显尾部瓶颈 |
| Funds reconcile SQL | 原费用引用校验含 maker/taker OR JOIN，交割曾 5 分钟未返回 | 改成 `match_trade_order_refs` 临时展开表 + 临时索引，重跑通过 |
| Kafka topic 创建 | 旧脚本容易全量检查/创建 topic，RESET 时删除面过大 | 已改为按产品线创建；`RESET_KAFKA=true` 时默认只删当前产品线 topic |

## 下一档：永续高 Maker 刷新

目的：解释上一轮用户提交 TPS 偏低，并验证生产级高 maker 刷新下资金是否仍能对平。

参数：

- 产品线：`LINEAR_PERPETUAL`
- RUN_ID：`20260709153000`
- Symbol：20
- Maker accounts：20
- 用户：5000
- 用户订单：open 5000 + close 5000，共 10000 笔
- Maker：初始 6 档；open/close 每阶段刷新 20 轮，每轮 4 档；maker 并发 128
- Maker 订单：6640，place success 6640
- 用户并发：256

结果：

| 指标 | Open | Close |
|---|---:|---:|
| 用户订单 | 5000 | 5000 |
| 成交 | 5000 | 5000 |
| Account settled | 5000 | 5000 |
| 拒单/撤单 | 0 / 0 | 0 / 0 |
| Client accepted TPS | 110.141/s | 111.862/s |
| Matched TPS | 34.152/s | 31.880/s |
| Account settled TPS | 34.430/s | 29.638/s |
| Account latency p95 | 121101.964 ms | 125766.357 ms |
| Account latency p99 | 131038.342 ms | 130995.539 ms |

Outbox 发布：

| Outbox | Published | Duration | TPS |
|---|---:|---:|---:|
| trading | 273455 | 1006.039s | 271.814/s |
| account | 20000 | 325.510s | 61.442/s |
| risk | 80529 | 986.607s | 81.622/s |

资金核对：

| 产品线 | 资产 | 期初 | 充值/调整 | 成交/权利金净额 | 成交/权利金发生额 | 手续费 | 资金费净额/发生额 | 强平费 | 交割/行权净额/发生额 | 期末应有 | 期末实际 | 结果 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| LINEAR_PERPETUAL | USDT | 0 | 1004000000000000000 | 0 | 200000000 | -2290732187 | 0 / 0 | 0 | 0 / 0 | 1003999997709267813 | 1003999997709267813 | OK |

结论：

- 上一轮表里的 90-148/s 是客户端提交阶段口径，不是撮合内部极限。
- 高频 maker 下，用户请求被 gateway/order 接收约 110/s，但真实 match/account TPS 只有约 30-34/s。
- account 能最终对平，资金无异常，但 p95/p99 延迟已经超过 120s。
- `risk_outbox_events` 是最明显 tail：close 后峰值约 7.1 万 pending，发布约 81.6/s。
- 如果生产 maker 刷新频率更高，当前本地单机链路不能只看撮合；必须重点优化 matching result fanout、account consumer 并行度、risk outbox 发布和 Kafka topic/partition/consumer group 配置。

## Topic 创建方案

后续压测不需要每次重建 Kafka topic：

- 默认 `RESET_KAFKA=false`，保留 topic，通过数据库 reset、唯一 RUN_ID、consumer group offset 和业务 trace 隔离测试数据。
- Kafka 没有真正的“清空 topic”轻量操作。要清空数据通常是 delete/recreate topic 或调整 retention 后等待清理，前者会影响所有消费者，后者不可控。
- 现在 `scripts/product-line-api-flow-smoke.sh` 只向 `scripts/create-topics.sh` 传本次产品线 slug。
- `scripts/create-topics.sh` 支持直接传 `PRODUCT_LINES='OPTION SPOT'`，自动映射到 `option spot`。
- `RESET_KAFKA=true` 时，默认只删除当前产品线前缀 topic；共享 topic 和 legacy perp topic 需要显式开关才会删除。

## 本轮代码调整

- `scripts/product-line-api-flow-smoke.sh`
  - 增加 `MULTI_SYMBOL_STRESS=true` 场景。
  - 支持 20 symbol、20 maker account、2000 用户并发 open/close。
  - 增加 maker book 预铺和处理等待，避免 taker IOC 先到导致取消。
  - 增加 stress 状态校验、资金汇总和报告输出。
  - 增加 accepted/matched/account/outbox TPS 细分指标。
  - Stress 交易阶段结束后先停止 maker/price refresher，再采样 outbox，避免后台新事件污染 pending 指标。
  - Kafka topic reset/create 改为产品线范围。
- `scripts/product-line-funds-reconcile.sh`
  - 费用引用校验改为临时 maker/taker order refs 等值 JOIN。
  - 增加临时索引和 ANALYZE。
  - 修复 `MAX_REPORT_ROWS` 对 per-account 输出不生效的问题。
- `scripts/create-topics.sh`
  - 支持 `PRODUCT_LINES` 到 topic slug 的自动映射。
