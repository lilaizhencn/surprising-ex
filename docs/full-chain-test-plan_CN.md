# 全链路功能测试清单

本文是当前 `surprising-ex` 的全链路验收清单。历史 order、market-maker 和分级压测报告已经合并到
[四产品线资金与性能主报告](full-chain-funds-performance-report.md)，本文件只保留当前代码状态下应如何复跑、覆盖哪些场景、如何判定通过。

## 当前部署拓扑

本地开发和小规模部署默认使用合并 provider，业务逻辑仍按包和持久边界隔离：

| 能力 | 当前默认进程 | 说明 |
| --- | --- | --- |
| 合约配置 | `surprising-instrument-provider` | 四产品线共享配置中心，API 按 `productLine` 区分。 |
| 指数价/标记价 | `surprising-price-provider` | 合并部署；mark price 仍消费 index price topic，不直接读 index 内存变量。 |
| 下单/条件单 | `surprising-trading-entry-provider` | 合并部署；普通订单、条件单、算法单仍使用各自表和服务包。 |
| 撮合 | `surprising-matching-provider` | 独立进程，内部使用 exchange-core；不和 order 合并。 |
| 账户/结算 | `surprising-account-provider` | 余额、流水、持仓、保证金、现货/合约结算事实源。 |
| 风控/强平/资金费/保险/ADL | `surprising-margin-ops-provider` | 合并部署；仍通过 PostgreSQL、Kafka、outbox、幂等键、租约和 sequence 协作。 |
| REST/WS 接入 | `surprising-edge-provider` | 合并 REST gateway 和 WebSocket fanout；生产长连接多时仍可拆分扩容。 |
| 做市 | `surprising-market-maker-provider` | 交易链路测试必须持续运行。 |

钱包服务不参与交易后端 smoke。测试资金通过 account fixture 或 admin adjustment 注入，并在资金核对中作为 `adjustment_units` 列出。

## 复跑命令

每次只测一条产品线，不需要同时启动四条撮合业务。测哪条产品线，就只创建该产品线的 product topics：

```bash
PRODUCT_LINES=LINEAR_PERPETUAL \
BUILD_SERVICES=auto \
KEEP_TMP=true \
RESET_KAFKA=true \
CREATE_KAFKA_TOPICS=true \
KAFKA_INCLUDE_SHARED_TOPICS=true \
KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false \
RECONCILE_FUNDS=true \
STRESS_REPORT_FILE=docs/full-chain-funds-performance-report.md \
./scripts/product-line-api-flow-smoke.sh
```

其他产品线把 `PRODUCT_LINES` 改成 `LINEAR_DELIVERY`、`OPTION` 或 `SPOT`。

`CREATE_KAFKA_TOPICS=true` 是幂等创建，不要求每次重建 topic。`RESET_KAFKA=true` 只适合本地独占 Kafka，用于清理旧 topic/offset 干扰；共享开发环境和生产环境不要用删除重建 topic 来“清空数据”，应使用独立 topic 命名、独立 consumer group、短保留期测试 topic 或重建本地 Kafka 数据目录。

## 高并发压测口径

脚本默认压测规模：

- 每条产品线至少 `20` 个活跃 symbol。
- 每个 symbol 一个 maker account，market-maker provider 为每个 symbol 配置一个 strategy。
- 做市初始挂 `6` 档，压测阶段每个 phase 刷新 `3` 轮，每轮刷新 `2` 档。
- 每条产品线 `2000` 个 taker 用户，经 gateway 单笔真实下单。
- open 阶段 `2000` 笔用户订单，close/sell 阶段 `2000` 笔用户订单，并发 `128`。
- matching/account/risk consumer 并发和 outbox 批量参数由脚本的 `STRESS_*` 环境变量控制。

生产级压测应继续增加运行时长、多节点、多 broker、故障注入和真实监控采样；单机报告不能替代生产容量评估。

## 功能覆盖

| 场景 | SPOT | LINEAR_PERPETUAL | LINEAR_DELIVERY | OPTION |
| --- | --- | --- | --- | --- |
| 用户充值/调整 | 是 | 是 | 是 | 是 |
| 做市持续报价 | 是 | 是 | 是 | 是 |
| 用户经 gateway 下单 | 是 | 是 | 是 | 是 |
| 撮合成交和订单状态 | 是 | 是 | 是 | 是 |
| 主动平仓/卖出 | 卖出资产 | reduce-only 平仓 | reduce-only 平仓 | reduce-only 平仓 |
| 手续费扣减 | 是 | 是 | 是 | 是 |
| 资金费 | 不适用 | 是 | 不适用 | 不适用 |
| 强平/强平费/保险基金 | 不适用 | 是 | 是 | 是 |
| 交割/行权流水 | 不适用 | 不适用 | 交割 | 行权/失效 |
| 期末余额核对 | 是 | 是 | 是 | 是 |

## 资金核对

每个用户、做市账号、产品线和资产都必须满足：

```text
expectedEnding
  = initial
  + adjustment
  + tradeOrPremiumNet
  + tradeOrPremiumGross
  + fee
  + fundingNet
  + fundingGross
  + liquidationFee
  + deliveryOrExerciseNet
  + deliveryOrExerciseGross
```

核对脚本还要检查：

- `available + locked` 与流水推导的期末余额一致。
- product balance、account balance、position margin、reservation 没有负数。
- `account_processed_trades(symbol, trade_id)` 幂等生效，重复成交不会重复结算。
- 强平成交后，用户强平费、保险基金入账和 deficit/ADL 状态一致。
- 主动平仓后仓位归零或剩余仓位正确，PnL、手续费、保证金释放正确。
- outbox pending 最终归零，Kafka lag 最终回落。

## 最新基线

最新保留的基线报告是 [full-chain-funds-performance-report.md](full-chain-funds-performance-report.md)：

- 四产品线功能链路通过。
- 四产品线高并发压测通过：每条线 `20` symbol、`20` maker、`2000` taker 用户。
- `funds-reconcile` 结果 `Violations=0`。
- 永续覆盖资金费和强平，交割覆盖强平和交割，期权覆盖强平和行权/失效，现货覆盖资产冻结、成交扣减和释放。
- 主要尾部瓶颈仍在 account 结算写库和 account outbox 发布链路，不是 gateway 接收层。

## 未完成的生产级验证

- 多节点、多 broker、长时间稳定性压测。
- 节点重启、Kafka rebalance、DB 慢查询、outbox 积压和 market-maker 熔断演练。
- 币本位永续和币本位交割的同等级 process-level smoke。
- 生产钱包/清结算边界联调。
