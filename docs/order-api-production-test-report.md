# 交易接口补齐与全链路压测报告

日期：2026-07-05

> 本地复跑统一使用 Homebrew PostgreSQL/Kafka/Redis：`localhost:5432`、`localhost:9092`、`localhost:6379`。历史命令中的 `POSTGRES_PORT=55432/55433` 只记录当时执行环境，当前测试不要再启动独立 Docker 中间件。

## 范围

本报告记录本轮对标 Binance Futures 和 OKX V5 后完成的交易接口补齐、真实网关链路测试、做市压测分级结果，以及仍未完成的交易所级能力。

本轮测试口径按真实用户调用方式执行：用户订单、做市订单、批量订单、撤单、条件单、一键平仓都经 `surprising-gateway` 入口进入；单元测试只用于校验交易前后资金、保证金、reduce-only、强平和账户数学不变量。

## 对标依据

- Binance USD-M Futures 官方文档：批量下单接口为 `POST /fapi/v1/batchOrders`，单批最多 5 条；测试订单接口为 `POST /fapi/v1/order/test`，不会提交到撮合引擎。
- OKX V5 官方文档：批量下单/撤单/改单单批最多 20 条，批量下单接口为 `POST /api/v5/trade/batch-orders`；`Cancel All After` 是账户或 tag 级的断线保护。
- OKX/Binance 的高级订单都放在普通撮合前的 algo/trigger 层；本项目继续保持这个边界，不改 `exchange-core` 核心撮合。

参考链接：

- https://developers.binance.com/docs/derivatives/usds-margined-futures/trade/rest-api/Place-Multiple-Orders
- https://developers.binance.com/docs/derivatives/usds-margined-futures/trade/rest-api/New-Order-Test
- https://tr.okx.com/docs-v5/en/

## 已完成接口

普通订单：

| 能力 | Gateway 路径 | Provider 路径 | 说明 |
| --- | --- | --- | --- |
| 单笔下单 | `POST /api/v1/gateway/trading` | `POST /api/v1/trading/orders` | 既有能力，继续保留 `clientOrderId` 幂等。 |
| 批量下单 | `POST /api/v1/gateway/trading/batch` | `POST /api/v1/trading/orders/batch` | 默认支持最多 20 条，返回逐条结果；压测 maker 默认按 5 条一批。 |
| 测试下单 | `POST /api/v1/gateway/trading/test` | `POST /api/v1/trading/orders/test` | 只做规则、reduce-only 和资金预估，不插入订单、不冻结资金、不发撮合命令。 |
| 单笔改单 | `POST /api/v1/gateway/trading/amend` | `POST /api/v1/trading/orders/amend` | 对开放 LIMIT 订单采用 cancel-replace 语义，先请求撤原单，再提交新的 `newClientOrderId` 替换单。 |
| 批量改单 | `POST /api/v1/gateway/trading/batch-amend` | `POST /api/v1/trading/orders/batch-amend` | 单批最多 20 条，逐条隔离成功/失败；不修改 exchange-core。 |
| 一键平仓 | `POST /api/v1/gateway/trading/close-position` | `POST /api/v1/trading/orders/close-position` | 锁定当前仓位后自动推导 BUY/SELL，提交 reduce-only MARKET IOC。 |
| 批量撤单 | `POST /api/v1/gateway/trading/batch-cancel` | `POST /api/v1/trading/orders/batch-cancel` | 对多个普通订单逐条撤销并返回逐条结果。 |
| 撤全部开放普通订单 | `POST /api/v1/gateway/trading/cancel-open` | `POST /api/v1/trading/orders/cancel-open` | 支持按 userId 和可选 symbol 撤销开放订单。 |
| Cancel All After | `POST /api/v1/gateway/trading/cancel-all-after` | `POST /api/v1/trading/orders/cancel-all-after` | 账户级或 symbol 级 dead-man switch；`countdownMs=0` 关闭，正数刷新倒计时，到期后台撤开放普通单和开放 TP/SL。 |

条件单：

| 能力 | Gateway 路径 | Provider 路径 | 说明 |
| --- | --- | --- | --- |
| 批量放置 TP/SL | `POST /api/v1/gateway/trading-trigger/batch` | `POST /api/v1/trading/trigger-orders/batch` | 仍走 trigger-provider，触发后生成 reduce-only 平仓单；`atomic=true` 时整组全部成功或全部不落库。 |
| 批量撤 TP/SL | `POST /api/v1/gateway/trading-trigger/batch-cancel` | `POST /api/v1/trading/trigger-orders/batch-cancel` | 只撤用户自己的 pending 条件单。 |
| 撤全部开放 TP/SL | `POST /api/v1/gateway/trading-trigger/cancel-open` | `POST /api/v1/trading/trigger-orders/cancel-open` | 只撤 `PENDING`，不抢正在 `TRIGGERING` 的条件单。 |
| Mark/Index/Last 触发源 | 同上 | 同上 | `MARK_PRICE`、`INDEX_PRICE` 和 `LAST_PRICE` 都由 trigger-provider 消费 Kafka 流后 claim 条件单；`LAST_PRICE` 来自真实撮合成交 topic。 |

算法单：

| 能力 | Gateway 路径 | Provider 路径 | 说明 |
| --- | --- | --- | --- |
| 放置 TWAP/Iceberg | `POST /api/v1/gateway/trading/algo` | `POST /api/v1/trading/orders/algo` | 算法单保存在 order-provider，不进入 exchange-core；到期后生成普通子订单继续走 order/matching/account 链路。 |
| 撤销算法单 | `POST /api/v1/gateway/trading/algo/cancel` | `POST /api/v1/trading/orders/algo/cancel` | 撤父单并撤当前仍开放的子订单。 |
| 撤全部开放算法单 | `POST /api/v1/gateway/trading/algo/cancel-open` | `POST /api/v1/trading/orders/algo/cancel-open` | 支持 userId、可选 symbol 和 limit；真实 smoke 已覆盖 active child 撤销。 |
| 查询开放算法单 | `GET /api/v1/gateway/trading/algo/open` | `GET /api/v1/trading/orders/algo/open` | 返回父单状态、累计成交、活跃子单数量和当前子单。 |

私有 WebSocket：

| 能力 | 频道 | 说明 |
| --- | --- | --- |
| 统一执行回报 | `executionReports` | websocket-provider 在不修改 exchange-core 的前提下，把 order event、match result 和 match trade 映射成统一私有回报；`reportType=ORDER_EVENT/MATCH_RESULT/TRADE`，成交回报区分 `liquidityRole=TAKER/MAKER`。旧 `orders`/`matches` 频道继续保留。 |

客户端同步：

- Web SDK：`../surprising-ex-web/src/api/surprising.ts`、`src/types.ts` 已增加普通订单、条件单和算法单 API，并支持 trailing stop 的 `activationPriceTicks`、`callbackRatePpm` 字段。
- Flutter/Dart SDK：`../surprising-client/lib/src/api.dart`、`models.dart` 已增加同等 API、响应模型、算法单提交面板、开放算法单列表和撤销入口。

## 高级订单方案

当前已经落地的高级订单能力：

- 普通订单改单/批量改单采用 cancel-replace：只允许开放 `LIMIT` 订单，支持改价格、未成交数量、GTC/GTX 和 post-only；不能改变 side/symbol/orderType/marginMode/positionSide/reduceOnly。
- 改单替换单使用新的 `newClientOrderId` 保持幂等；开仓替换单会重新走资金校验和预占，原单释放仍由撤单撮合结果和 account 链路完成。
- 多档 TP/SL 继续用多条 `trading_trigger_orders` 表达；批量接口支持 `atomic=true`，用于组合 TP/SL 的全成全撤语义。
- 追踪止损使用 `TRAILING_STOP` 条件单表达，`callbackRatePpm` 范围为 `1000..100000`，可选 `activationPriceTicks`；激活后在 DB 中维护最高/最低水位，触发后仍生成 reduce-only 市价平仓单。
- 同一组 TP/SL 使用 `ocoGroupId` 时，容量校验按 sibling 最大数量计入，不把止盈和止损双倍占用可平仓位。
- 触发时由 trigger-provider 创建 reduce-only 平仓单，资金、仓位、撮合仍回到统一 order/matching/account 链路。
- 触发价来源支持 `MARK_PRICE`、`INDEX_PRICE` 和 `LAST_PRICE`，不需要修改 exchange-core；`LAST_PRICE` 因薄盘口操纵风险更高，不用于强平。
- 批量放置、批量撤销、撤全部开放条件单已经通过真实 gateway smoke。
- Cancel All After 按 `trading_cancel_all_after` 表保存倒计时心跳，支持账户级 `*` 或具体 symbol 作用域；到期由 order-provider 后台任务复用普通订单 `cancel-open`，并通过 trigger-provider RPC 撤用户 pending 条件单。
- TWAP 和 Iceberg 按主流交易所的 algo 层实现，不修改 exchange-core。TWAP 子单使用 IOC，`priceTicks=0` 时生成 MARKET IOC，否则生成 LIMIT IOC；Iceberg 每次只暴露一个 LIMIT GTC/GTX 子单，当前子单成交或撤销后再投放下一片。
- 活跃算法单会阻止净仓/双向持仓模式切换，避免父单后续子单落到旧仓位模式。

后续仍需要生产级扩大验证的主流交易所能力：

- `executionReports` 已补基础 ACK/result/fill 风格统一回报；仍需要在大量长连接、多节点 WebSocket 和高频做市压测中记录 p50/p95/p99 fanout 延迟、慢连接隔离和断线清理。

## 资金与风控修复

本轮真实 gateway 压测暴露并修复了一个保证金边界：

- 线性 SELL 限价开空如果没有 fresh mark，按委托价预占保证金可能不足，因为实际成交价可能高于委托价。
- 修复后，只有在订单可能开仓或翻仓、且成交价改善会增加保证金需求时，才强制 fresh mark 并按保护价预占。
- 对于只减仓或不会新增风险敞口的订单，不扩大 mark 依赖面。

后续参考行情全链路回归又暴露并修复了一个 ADL 后 OI 对账缺口：

- ADL 减少目标盈利账户仓位后，`account_positions` 已正确减少，但 `trading_symbol_open_interest` 没有同步扣减对应 long/short 数量。
- 修复后，ADL 在同一事务内按旧/新签名仓位增量更新 `trading_symbol_open_interest`；如果 OI 更新失败，整条 ADL 事务失败回滚。
- `full-stack-real-config-smoke.sh` 现在会从 `account_positions` 重算 symbol long/short/open interest，并和 `trading_symbol_open_interest` 对账，避免只靠表约束遗漏 ADL、强平等系统结算路径。

相关单元测试覆盖：

- `OrderMarginMathTest`
- `OrderMarginRepositoryTest`
- `OrderServiceTest`
- `AdlRepositoryTest`

## 全链路功能测试

真实配置 full-stack smoke：

```bash
POSTGRES_PORT=55432 KEEP_TMP=true RUN_FAILURE_SCENARIOS=false PAIR_COUNT=10 LOAD_CONCURRENCY=4 BOOK_DEPTH_LEVELS=20 ./scripts/full-stack-real-config-smoke.sh
```

结果：通过。日志：`/tmp/surprising-full-stack-real-config.DsxfGp`

持续做市 provider full-stack smoke：

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=900 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=8 MM_PROVIDER_CONTINUOUS_INTERVAL_SECONDS=1 MM_PROVIDER_CONTINUOUS_TAKER_ORDERS=2 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh
```

结果：2026-07-05 通过。日志：`/tmp/surprising-full-stack-real-config.KW6Dsn`，报告：`docs/market-maker-provider-continuous-report.md`

scheduled engine 做市 provider full-stack smoke：

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1200 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=60 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=20 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh
```

结果：2026-07-05 通过。日志：`/tmp/surprising-full-stack-real-config.MQjxfE`，报告：`docs/market-maker-provider-engine-report.md`

scheduled engine 账户服务重启故障 smoke：

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1200 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=45 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=5 MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true MM_PROVIDER_ENGINE_ACCOUNT_RESTART_DELAY_SECONDS=5 MM_PROVIDER_ENGINE_ACCOUNT_DOWN_SECONDS=5 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh
```

结果：2026-07-05 通过。日志：`/tmp/surprising-full-stack-real-config.F0V0h2`，报告：`docs/market-maker-provider-engine-fault-report.md`

参考行情 WebSocket 做市 provider full-stack smoke：

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1200 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=30 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=5 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh
```

结果：2026-07-05 通过。日志：`/tmp/surprising-full-stack-real-config.W2VWYd`，报告：`docs/market-maker-reference-market-report.md`

参考行情 WebSocket + 账户服务重启做市 provider full-stack smoke：

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1500 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=90 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=10 MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true MM_PROVIDER_ENGINE_ACCOUNT_RESTART_DELAY_SECONDS=10 MM_PROVIDER_ENGINE_ACCOUNT_DOWN_SECONDS=10 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh
```

结果：2026-07-05 通过。日志：`/tmp/surprising-full-stack-real-config.8XwMJ0`，报告：`docs/market-maker-reference-market-fault-report.md`

参考行情 WebSocket 180 秒持续做市 provider full-stack smoke：

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=2400 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=180 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=30 MM_PROVIDER_ENGINE_ACCOUNT_RESTART=false POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh
```

结果：2026-07-05 通过。日志：`/tmp/surprising-full-stack-real-config.xCbq79`，报告：`docs/market-maker-reference-market-sustained-report.md`

参考行情 WebSocket + ADL/OI 回归 full-stack smoke：

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1200 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=30 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=5 MM_PROVIDER_ENGINE_ACCOUNT_RESTART=false POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh
```

结果：2026-07-05 通过。日志：`/tmp/surprising-full-stack-real-config.pSoyiB`；ADL 后 `ETH-USDT` OI 为 `long=19 short=20 open=20`，和账户持仓重算一致，停机后 trading/account/risk/funding outbox pending 均为 `0`。

应急交易模式 full-stack smoke：

```bash
START_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=auto KEEP_TMP=true PAIR_COUNT=3 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=5 RISK_BACKGROUND_LOAD_ENABLED=false POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh
```

结果：2026-07-05 通过。日志：`/tmp/surprising-full-stack-real-config.A7PtM8`；覆盖 instrument admin 状态切换、HALT cancel-only、SETTLING reduce-only、matching/account 重启恢复、强平、保险基金、ADL、market-maker run-once 和最终 WebSocket/accounting invariants。

私有 `executionReports` clean full-stack smoke：

```bash
START_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=auto KEEP_TMP=true RUN_FAILURE_SCENARIOS=false PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh
```

结果：2026-07-05 通过。日志：`/tmp/surprising-full-stack-real-config.z3LnIV`，stdout：`/tmp/surprising-full-stack-executionReports-20260705184957.log`。本轮在真实 gateway/provider/Kafka/PostgreSQL/WebSocket 链路中断言 `executionReports`：full taker 捕获 `ORDER_EVENT=3 MATCH_RESULT=3 TRADE=4`，partial maker 捕获 `ORDER_EVENT=2 MATCH_RESULT=2 TRADE=1`，cancel 用户捕获 `ORDER_EVENT=2 MATCH_RESULT=2`，isolated 用户捕获 `ORDER_EVENT=2 MATCH_RESULT=2 TRADE=2`，position-mode 用户捕获 `ORDER_EVENT=4 MATCH_RESULT=4 TRADE=4`。同轮通过 depth events `184`、mark events `315`、funding events `290`，余额负数、产品余额负数、保证金/现货预占超释放、trading/account/funding outbox 异常均为 `0`；`account_deficits` 和 `account_product_deficits` 表存在强平/保险/ADL 场景的聚合行，但 `SUM(deficit_units)=0`。

覆盖：

- 普通订单批量、测单、改单、批量改单、批量撤单、撤全部和一键平仓；
- 私有 WebSocket `executionReports`：订单事件、撮合结果和逐笔成交映射成统一执行回报，maker/taker 成交角色分开推送；最新 clean full-stack smoke 已通过真实 WebSocket 捕获和断言；
- 条件单批量放置、`atomic=true` 原子组合失败回滚、批量撤销和撤全部开放条件单；
- Cancel All After 真实 gateway 调用，到期撤 1 笔普通开放订单和 1 笔 pending TP/SL；
- TWAP 真实 gateway 调用后生成 3 笔 IOC 子单并全部成交；Iceberg 真实 gateway 调用后逐片暴露 3 笔 GTC 子单并被 taker 逐笔吃掉；`cancel-open` 撤销 active Iceberg 父单和开放子单；活跃算法单阻止持仓模式切换。
- `MARK_PRICE`、`INDEX_PRICE`、`LAST_PRICE` 三类 TP/SL 触发源；
- `MARK_PRICE` trailing stop 的激活、最高/最低水位更新、回调触发和 reduce-only 平仓；
- instrument `HALT`/`SETTLING` 应急交易模式：HALT 拒绝新开单但允许撤存量开放单；SETTLING 拒绝普通开仓但允许多空双方 reduce-only 平仓；
- 资金费、强平、保险基金、ADL、market-maker run-once、market-maker provider 连续 8 秒 quote/reconcile、WebSocket 和 accounting invariants。

关键校验：

- 单笔改单和批量改单真实走 gateway/order/matching/account 链路；源单和替换单最终状态：
  `real-amend-source|CANCELED|0|0|586000|2`、`real-amend-replacement|CANCELED|0|0|585000|1`、`real-batch-amend-source-a|CANCELED|0|0|584000|2`、`real-batch-amend-source-b|CANCELED|0|0|584000|3`、`real-batch-amend-a|CANCELED|0|0|583000|1`、`real-batch-amend-b|CANCELED|0|0|582000|2`。
- `INDEX_PRICE` 条件单真实触发并生成平仓订单：`INDEX_PRICE|TRIGGERED|626194|56`。
- `LAST_PRICE` 条件单真实消费撮合成交并生成平仓订单：`real-last-tp-*|LAST_PRICE|TRIGGERED|600600|62|FILLED|6|0`。
- `TRAILING_STOP` 条件单真实按 mark price 激活、更新最高水位并回调触发：`real-trailing-stop-*|TRIGGERED|activation=649234|callbackRatePpm=100000|highest=651234|triggered=581234|placedOrder=66`；生成的 `trigger-11` reduce-only 市价单和对手方 `real-trailing-close-maker-*` 均 `FILLED|6|0`。
- `TWAP` 父单最终 `COMPLETED|executed=3|active=0|children=3`，3 笔子单均为 `LIMIT/IOC/FILLED`；`Iceberg` 父单最终 `COMPLETED|executed=3|active=0|children=3`，3 笔子单均为 `LIMIT/GTC/FILLED`；active algo blocker 通过 `trading/algo/cancel-open` 后为 `CANCELED|executed=0|active=0|children=1`，子单为 `CANCELED`。
- `atomic=true` 组合 TP/SL 失败后没有留下部分条件单：`real-trigger-atomic-*` 查询结果为 `0`。
- `Cancel All After` 到期后 timer 为 `TRIGGERED|1500|1|1`；`real-caa-open-*` 普通开放订单和 `real-caa-trigger-*` pending 条件单残留开放状态均为 `0`。
- 应急模式真实 gateway 证据：`real-emergency-halt-cancel-*` 为 `CANCELED|0|0`；`real-emergency-halt-reject-*` 为 `REJECTED|instrument is cancel-only`；`real-emergency-settling-reject-*` 为 `REJECTED|instrument is reduce-only`；`real-emergency-close-liquidity-*` 和 `real-emergency-reduce-close-*` 均 `FILLED|2|0`。应急测试用户最终 `positions_abs=0`、`locked_usdt=0`、`deficits=0`、`open_orders=0`，相关成交 `account_processed_trades=2`。
- 私有执行回报真实链路证据：`/tmp/surprising-full-stack-real-config.z3LnIV` 中 full taker/partial maker/cancel/isolated/position-mode 5 个私有用户均收到 `executionReports`，其中有持仓变化的用户均至少收到 `TRADE` 回报；脚本修复了进入持仓模式阻断订单前 mark price 可能过期的编排问题，现在会先刷新 mark 再下单。
- 余额负数、产品余额负数、保证金释放超额、现货预占释放超额均为 `0`。
- `trading_outbox_events`、`account_outbox_events`、`funding_outbox_events`、`risk_outbox_events` 未发布异常均为 `0`。
- 最新 L0 真实链路捕获 depth events `213`、mark events `399`、funding events `390`。
- 持续做市 provider smoke 中，BTC 测试价从最新 mark 同步为 `631107` ticks，避免真实 index/mark 价格刷新时旧固定价格撞限价保护；market-maker provider 产生 `QUOTE_RECONCILED=4`、提交 post-only 报价 `160`，普通用户 taker 成交 `2` 笔且 account 结算 `2` 笔，做市账号和用户账号负余额均为 `0`，开放 market-maker 报价清理后为 `0`。
- scheduled engine smoke 中，脚本在资金和 fixture 就绪后重启 market-maker provider 并开启 `engine.enabled=true`；60 秒 engine 窗口内 `CYCLE_SUCCESS=5`、`QUOTE_RECONCILED=10`、提交 post-only 报价 `400`，普通用户 taker 成交/account 结算 `20/20`，做市账号和用户账号负余额为 `0`，开放 market-maker 报价清理后为 `0`。
- scheduled engine 账户服务重启故障 smoke 中，脚本在报价已接收后停止真实 account provider，经 gateway 提交 5 笔普通用户 taker 订单并等待撮合成交，再重启 account provider；故障窗口内 market-maker 出现预期 `CYCLE_FAILED=103`，重启后恢复 `CYCLE_SUCCESS`，`QUOTE_RECONCILED=9`、提交报价 `360`、拒单 `0`、engine taker 成交/account 结算 `5/5`，开放 market-maker 报价清理后为 `0`；负余额、负逐仓保证金、非法 OI、trading/account/risk/funding outbox 异常均为 `0`，保险基金和 ADL remaining deficit 为 `0`。
- 参考行情 WebSocket smoke 中，`MM_REFERENCE_MARKET_ENABLED=true` 和 `MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true`；provider 写入 `market_maker_reference_samples`，记录 `REST|BINANCE_USDM=2`、`WEBSOCKET|BINANCE_USDM=1`、`WEBSOCKET|BYBIT_LINEAR=1`，每个样本均为 20x20 深度、spread=1 tick；scheduled engine 提交报价 `313`、拒单 `0`、普通用户 taker 成交/account 结算 `5/5`，开放 market-maker 报价清理后为 `0`。
- 参考行情 WebSocket + 账户服务重启 smoke 中，provider 写入 `market_maker_reference_samples`，记录 `REST|BINANCE_USDM=2`、`WEBSOCKET|BINANCE_USDM=142`、`WEBSOCKET|BYBIT_LINEAR=4`、`WEBSOCKET|OKX_SWAP=2`，每个样本均为 20x20 深度、spread=1 tick；scheduled engine 故障窗口内出现预期 `CYCLE_FAILED=144`，恢复后 `CYCLE_SUCCESS=6`、`QUOTE_RECONCILED=12`、提交报价 `476`、拒单 `0`、engine taker 成交/account 结算 `10/10`，开放 market-maker 报价清理后为 `0`；负余额、负逐仓保证金、非法 OI、trading/account/risk/funding outbox 异常均为 `0`，保险基金和 ADL remaining deficit 为 `0`。
- 参考行情 WebSocket 180 秒持续 smoke 中，首次尝试暴露了 WebSocket depth fanout 对合法现货 symbol `BTC-USDT-SPOT` 校验过窄的问题：现货 depth 事件导致 WebSocket Kafka listener retry/seek，旧 BTC-USDT depth 被重放后出现 sequence 回退。已将 `SubscriptionTopic` symbol 规则修复为项目通用格式，并增加现货 depth 订阅/fanout 回归测试；复跑 180 秒通过，depth events `2794`、最终 sequence `3130` 单调递增，`CYCLE_SUCCESS=19`、`QUOTE_RECONCILED=38`、提交报价 `1333`、拒单 `0`、engine taker 成交/account 结算 `30/30`，运行期最终严格要求 trading/account/risk/funding outbox pending 全部为 `0`。
- 参考行情 WebSocket + ADL/OI 回归 smoke 中，新增 OI 对账断言从 `account_positions` 重算 long/short/open interest；修复后 `ETH-USDT long=19 short=20 open=20`，非法 OI 行 `0`，负余额、账户赤字、产品赤字和停机后 outbox pending 均为 `0`。
- L4 单节点真实接口压测中，4 个 maker、BTC/ETH 每侧 50 档初始深度、5 轮刷新、128 并发、5 个私有 WebSocket 订阅用户全部走真实 gateway/order/matching/account/websocket 链路；普通用户 taker 订单 `10000/10000` 撮合并 account 结算，`openTradingOutbox=0`、`openAccountOutbox=0`、负账户余额/负产品余额/负逐仓保证金/预占超释放/账户赤字/产品赤字/非法 OI 均为 `0`。
- `executionReports` 做市压测回归中，2 个 maker、BTC/ETH 每侧 10 档、200 个普通用户 taker、64 并发、前 3 个私有 WebSocket 订阅用户全部走真实 gateway/order/matching/account/websocket 链路；200/200 撮合并结算，三个私有用户均收到 `ORDER_EVENT/MATCH_RESULT/TRADE`，`private_executionReports=3`，trading/account outbox 清空，负余额和 deficit 聚合异常均为 `0`。报告：`docs/market-maker-stress-report-executionReports.md`。
- `executionReports` 中等压力回归中，4 个 maker、BTC/ETH 每侧 20 档、2 轮刷新、2000 个普通用户 taker、96 并发、前 10 个私有 WebSocket 用户全部走真实 gateway/order/matching/account/websocket 链路；2000/2000 撮合并 account 结算，10 个私有用户均收到 `ORDER_EVENT/MATCH_RESULT/TRADE`，`private_executionReports=3`/用户，trading/account outbox 清空，负余额和 deficit 聚合异常均为 `0`。本轮暴露的主要瓶颈是 account 后置结算：Kafka lag 峰值 `680`，`order accepted -> account processed trade p50=242061.830ms p95=387695.905ms p99=401206.487ms max=416195.903ms`。报告：`docs/market-maker-stress-report-executionReports-tier2.md`。
- account 热路径优化回归中，只重新打包 account-provider 后继续按真实 gateway/order/matching/account/websocket 链路测试。`docs/market-maker-stress-report-account-pruner.md` 覆盖 4 个 maker、BTC/ETH 每侧 20 档、2 轮刷新、2000 个普通用户 taker、96 并发、前 10 个私有用户，2000/2000 成交并结算，资金不变量和私有 `executionReports` 通过；该轮说明跳过开仓/加仓时的 reduce-only 剪枝后全链路正确，但单节点延迟仍受 matching/account 顺序分区和 DB 写入约束。`docs/market-maker-stress-report-account-cache-smoke.md` 覆盖 2 个 maker、BTC/ETH 每侧 10 档、200 个普通用户 taker、64 并发、前 3 个私有用户，验证 `liquidation_orders` 强平费上下文 JVM 负缓存后 200/200 成交并结算，负余额和 deficit 聚合异常为 0，三个私有用户均收到 `ORDER_EVENT/MATCH_RESULT/TRADE`。
- account available-balance fast path 回归中，只重新打包 account-provider 后继续按真实 gateway/order/matching/account/websocket 链路测试。`docs/market-maker-stress-report-account-fastpath.md` 覆盖 4 个 maker、BTC/ETH 每侧 20 档、2 轮刷新、2000 个普通用户 taker、96 并发、前 10 个私有用户；2000/2000 成交并结算，资金不变量和私有 `executionReports` 通过。该轮 `surprising.account.match_trade.processing` 平均值从上一轮同规格 `0.222556s` 降到 `0.198013s`，event lag avg/max 从 `59.555878s/110.615114s` 降到 `50.469238s/91.006244s`；但端到端 account processed p50/p95 未整体下降，说明 fast path 降低了单笔结算开销，account 写库尾部仍未消除。
- account instrument metadata cache smoke 中，新 account jar 对 instrument type、spot spec、contract spec、order fee snapshot 做有界 JVM 只读缓存，不缓存余额/持仓/保证金/deficit。`docs/market-maker-stress-report-account-instrument-cache-smoke.md` 覆盖 100 个普通用户 taker、64 并发、前 5 个私有用户；100/100 成交并结算，account lag max `15` 且最终 `0`，资金、outbox、deficit 和私有 `executionReports` 断言通过。
- matching 单 JVM 多 consumer 对比回归中，`MATCHING_CONSUMERS_PER_NODE=2` 时 BTC/ETH 两个热 symbol 仍都分配给同一个 matching consumer，`split=false`，因此不能证明热 symbol 并行；`MATCHING_CONSUMERS_PER_NODE=6` 时 BTC/ETH 分配给不同 consumer，`split=true`，同规格 2000 taker/96 并发下 2000/2000 成交并结算，matching result p50 降到 `88541.509ms`，matching event-time 吞吐提升到 `13.17 trades/s`，但 account processed p50 仍为 `152770.313ms` 且 account lag 峰值 `1352`，说明瓶颈进一步后移到 account 结算写库。报告：`docs/market-maker-stress-report-matching-concurrency2.md`、`docs/market-maker-stress-report-matching-concurrency6.md`。

## 压测分级

| 等级 | 参数 | 结果 | 报告 |
| --- | --- | --- | --- |
| L0 功能全链路 | `PAIR_COUNT=10 LOAD_CONCURRENCY=4 BOOK_DEPTH_LEVELS=20`，真实 gateway/provider/Kafka/PostgreSQL | 通过；新增 TWAP/Iceberg/cancel-open/active-algo blocker 全链路，最终无负余额和无异常 outbox | `/tmp/surprising-full-stack-real-config.DsxfGp` |
| L0 做市 provider 连续链路 | `MM_PROVIDER_CONTINUOUS_SECONDS=8 MM_PROVIDER_CONTINUOUS_TAKER_ORDERS=2`，真实 market-maker provider 经 gateway run-once 连续报价，用户 taker 经 gateway 单笔 | 通过；`QUOTE_RECONCILED=4`、提交报价 160、taker 成交/account 结算 2/2，开放做市单清理为 0 | `docs/market-maker-provider-continuous-report.md` |
| L0 做市 scheduled engine 链路 | `MM_PROVIDER_ENGINE_SECONDS=60 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=20`，资金就绪后重启真实 market-maker provider 并启用 scheduled engine | 通过；engine 自身 `CYCLE_SUCCESS=5`、`QUOTE_RECONCILED=10`、提交报价 400、taker 成交/account 结算 20/20，开放做市单清理为 0 | `docs/market-maker-provider-engine-report.md` |
| L0 scheduled engine 账户重启故障链路 | `MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true MM_PROVIDER_ENGINE_SECONDS=45 MM_PROVIDER_ENGINE_TAKER_ORDERS=5`，真实 account provider 停机期间用户 taker 继续经 gateway 成交，重启后结算追平 | 通过；故障窗口 `CYCLE_FAILED=103`，重启后恢复成功 cycle，提交报价 360、拒单 0、taker 成交/account 结算 5/5，开放做市单和异常 outbox 均为 0 | `docs/market-maker-provider-engine-fault-report.md` |
| L0 参考行情 WebSocket 做市链路 | `MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_PROVIDER_ENGINE_SECONDS=30 MM_PROVIDER_ENGINE_TAKER_ORDERS=5`，真实 market-maker provider 用外部深度样本校准报价 | 通过；记录 Binance/Bybit WEBSOCKET 样本，提交报价 313、拒单 0、taker 成交/account 结算 5/5，开放做市单清理为 0 | `docs/market-maker-reference-market-report.md` |
| L0 参考行情 WebSocket + 账户重启故障链路 | `MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true MM_PROVIDER_ENGINE_SECONDS=90 MM_PROVIDER_ENGINE_TAKER_ORDERS=10`，真实 market-maker provider 用外部深度样本校准报价，account provider 停机期间用户 taker 继续经 gateway 成交 | 通过；记录 Binance/OKX/Bybit WEBSOCKET 样本，故障窗口 `CYCLE_FAILED=144` 后恢复，提交报价 476、拒单 0、taker 成交/account 结算 10/10，开放做市单和异常 outbox 均为 0 | `docs/market-maker-reference-market-fault-report.md` |
| L0 参考行情 WebSocket 180 秒持续链路 | `MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_PROVIDER_ENGINE_SECONDS=180 MM_PROVIDER_ENGINE_TAKER_ORDERS=30`，真实 market-maker provider 持续按参考行情报价，用户 taker 经 gateway 成交 | 通过；修复并验证现货 symbol depth fanout，depth 2794 条单调递增，提交报价 1333、拒单 0、taker 成交/account 结算 30/30，运行期严格 outbox 清空 | `docs/market-maker-reference-market-sustained-report.md` |
| L0 参考行情 WebSocket + ADL/OI 回归链路 | `MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_PROVIDER_ENGINE_SECONDS=30 MM_PROVIDER_ENGINE_TAKER_ORDERS=5`，真实强平/保险基金/ADL 后执行 OI 重算对账 | 通过；修复 ADL 减仓后 OI 未扣减，`ETH-USDT long=19 short=20 open=20`，非法 OI、赤字和停机后 outbox pending 均为 0 | `docs/market-maker-reference-market-sustained-report.md` |
| L0 应急交易模式链路 | instrument admin API 切 `HALT/SETTLING/TRADING`，用户交易仍全部经 gateway/order/matching/account | 通过；HALT 新开单拒绝、存量单撤成 CANCELED；SETTLING 开仓拒绝、reduce-only 平仓成交；应急用户持仓/锁定资金/deficit/open orders 均为 0 | `/tmp/surprising-full-stack-real-config.A7PtM8` |
| L0 私有执行回报 clean 链路 | `RUN_FAILURE_SCENARIOS=false PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2`，真实 gateway/provider/Kafka/PostgreSQL/WebSocket | 通过；5 个私有用户收到 `executionReports`，覆盖 `ORDER_EVENT`、`MATCH_RESULT`、`TRADE`，余额/预占/OI/outbox 断言通过 | `/tmp/surprising-full-stack-real-config.z3LnIV` |
| L0 私有执行回报做市压测回归 | 2 maker、BTC/ETH 每侧 10 档、200 taker、64 并发、前 3 个私有 WebSocket 用户 | 通过；200/200 成交并结算，三个私有用户均收到 `ORDER_EVENT/MATCH_RESULT/TRADE`，trading/account outbox 和资金不变量通过 | `docs/market-maker-stress-report-executionReports.md` |
| L2 私有执行回报中等压力回归 | 4 maker、BTC/ETH 每侧 20 档、2 轮刷新、2000 taker、96 并发、前 10 个私有 WebSocket 用户 | 通过；2000/2000 成交并结算，10 个私有用户均收到 `ORDER_EVENT/MATCH_RESULT/TRADE`；account lag 峰值 680，后置结算延迟需要继续优化 | `docs/market-maker-stress-report-executionReports-tier2.md` |
| L0 account 热路径缓存回归 | 2 maker、BTC/ETH 每侧 10 档、200 taker、64 并发、前 3 个私有 WebSocket 用户 | 通过；`liquidation_orders` 强平费上下文负缓存和 reduce-only 剪枝收窄后，200/200 成交并结算，资金不变量和 `executionReports` 通过 | `docs/market-maker-stress-report-account-cache-smoke.md` |
| L2 account 热路径剪枝回归 | 4 maker、BTC/ETH 每侧 20 档、2 轮刷新、2000 taker、96 并发、前 10 个私有 WebSocket 用户 | 通过；2000/2000 成交并结算，但 account p95 仍高，说明单节点 symbol 分区顺序消费和账户写库仍是瓶颈 | `docs/market-maker-stress-report-account-pruner.md` |
| L2 account available-balance fast path 回归 | 4 maker、BTC/ETH 每侧 20 档、2 轮刷新、2000 taker、96 并发、前 10 个私有 WebSocket 用户 | 通过；2000/2000 成交并结算，单笔 account processing 与 event lag 下降，但端到端 account 尾部仍受写库约束 | `docs/market-maker-stress-report-account-fastpath.md` |
| L0 account instrument metadata cache smoke | 2 maker、BTC/ETH 每侧 10 档、1 轮刷新、100 taker、64 并发、前 5 个私有 WebSocket 用户 | 通过；instrument type/spot spec/contract spec/order fee snapshot 缓存后，100/100 成交并结算，资金不变量和 `executionReports` 通过 | `docs/market-maker-stress-report-account-instrument-cache-smoke.md` |
| L2 matching concurrency=2 对比 | 4 maker、BTC/ETH 每侧 20 档、2 轮刷新、2000 taker、96 并发，matching 单 JVM 2 consumers | 通过；2000/2000 成交并结算，但 BTC/ETH 热分区都在同一 consumer，`split=false` | `docs/market-maker-stress-report-matching-concurrency2.md` |
| L2 matching concurrency=6 对比 | 4 maker、BTC/ETH 每侧 20 档、2 轮刷新、2000 taker、96 并发，matching 单 JVM 6 consumers | 通过；BTC/ETH 热分区分散，`split=true`，matching p50 降低，account 结算成为主要尾部瓶颈 | `docs/market-maker-stress-report-matching-concurrency6.md` |
| L1 单节点真实链路 | 4 maker、20 档、1 轮刷新、1000 taker、64 并发 | 通过；1000/1000 成交并结算 | `docs/market-maker-stress-report.md` |
| L2 单节点更高压力 | 4 maker、30 档、2 轮刷新、3000 taker、96 并发，maker 经 gateway batch | 通过；3000/3000 成交并结算 | `docs/market-maker-stress-report-tier2.md` |
| L3 单节点高压真实链路 | 4 maker、40 档、3 轮刷新、6000 taker、128 并发，maker 经 gateway batch，taker 经 gateway 单笔 | 通过；6000/6000 成交并结算，WebSocket 前 3 个用户私有事件隔离通过 | `docs/market-maker-stress-report-tier3.md` |
| L4 单节点上限样本 | 4 maker、50 档、5 轮刷新、10000 taker、128 并发，maker 经 gateway batch，taker 经 gateway 单笔，WebSocket 前 5 个用户私有 fanout | 通过；10000/10000 成交并结算，trading/account outbox 清空，资金/预占/deficit/OI 对账均为 0 | `docs/market-maker-stress-report-tier4.md` |

L2 关键结果：

- 初始 maker 挂单：480
- maker 刷新挂单：320
- taker 订单：3000
- 撮合成交：3000
- account 结算：3000
- 客户端提交吞吐：40.14 orders/s
- matching event-time 吞吐：10.95 trades/s
- account Kafka max lag：1189，最终 lag：0
- 未出现负 available/locked
- `openTradingOutbox=0`、`openAccountOutbox=0`

L3 关键结果：

- 初始 maker 挂单：640
- maker 刷新挂单：576
- taker 订单：6000
- 撮合成交：6000
- account 结算：6000
- 客户端提交吞吐：27.17 orders/s
- matching event-time 吞吐：9.26 trades/s
- account Kafka max lag：2442，最终 lag：0
- 未出现负 available/locked，非法 OI 行 `0`
- `openTradingOutbox=0`、`openAccountOutbox=0`

L3 首次尝试在 6000 taker、128 并发下暴露 order-provider 连接池耗尽：日志出现 `HikariPool-1 - Connection is not available`，当时普通用户成交只推进到 3677/6000。脚本随后增加可配置 Hikari 连接池和等待窗口参数；复跑时使用 order=60、matching=30、account=40、gateway=20 连接池以及 900s/1200s 等待窗口，最终全部成交、全部结算、outbox 清空。这个失败样本也保留为瓶颈证据，不能只看成功报告。

L4 关键结果：

- 初始 maker 挂单：800
- maker 刷新挂单：800
- taker 订单：10000
- 撮合成交：10000
- account 结算：10000
- 客户端提交吞吐：41.82 orders/s
- matching event-time 吞吐：9.36 trades/s
- account Kafka max lag：3592，最终 lag：0
- WebSocket 前 5 个 taker 用户私有 orders/positions 隔离通过
- 负账户余额、负产品余额、负逐仓保证金、预占超释放、账户赤字、产品赤字、非法 OI 均为 `0`
- `openTradingOutbox=0`、`openAccountOutbox=0`

L4 暴露出的另一个瓶颈不是交易链路，而是压测 fixture 入金：原脚本通过 10004 次 admin HTTP 调整 maker/taker 初始余额，耗时明显高于交易压测准备预期。脚本已把初始资金改为单 SQL 批量写账本、余额和 admin adjustment；这只替代测试前置入金，用户下单、maker batch、taker 单笔、撮合、account 结算和 WebSocket fanout 仍按真实用户入口验证。

## 瓶颈结论

当前单机链路主要瓶颈不是 `exchange-core`，而是 order 入库/outbox、Kafka、PostgreSQL 写放大、account 持仓和 ledger 结算。

本轮已处理：

- ID 热点从 `trading_sequences` 表计数器切到 PostgreSQL native sequence。
- order/matching outbox 改为 DB claim 后事务外发送 Kafka。
- account 结算减少无变化 `account_deficits` 写入。
- match-trade consumer 使用 Kafka batch delivery 和 batch ack。
- account 结算只在成交减少旧仓位时执行 reduce-only 开放平仓单剪枝，普通开仓/加仓跳过无收益的开放 reduce-only 查询。
- account 结算对 `liquidation_orders` 强平费上下文做订单维度有界 JVM 缓存，并缓存普通订单空结果，减少非强平成交的重复负查询。
- account 结算对 instrument type、spot spec、contract spec 等只读元数据做有界 JVM 缓存；成交费率由 `MatchTradeEvent` 必须携带，不再在 account 热路径回查订单费率；跨仓普通负向资金变动在可用余额足够时走 guarded available-balance fast path，避免无必要的 `account_position_margins` 查询/锁。
- matching-provider 单 JVM 可以通过提高 Kafka listener concurrency 让不同 symbol 分区并行，但必须观察 `Matching active symbol consumers`；热 symbol 没有分散时，提高 consumer 数不等于提高实际撮合并行度。
- maker 压测改为真实 gateway batch，以更接近主流交易所多档报价方式。
- 压测脚本现在可按 provider 配置 Hikari 连接池和等待窗口，方便在不改业务代码的前提下复现实用户入口下的单节点极限。
- 压测 fixture 入金改为 SQL 批量账本/余额/admin adjustment 初始化，避免 10000 级 taker 样本被 admin HTTP 入金前置耗时淹没。

缓存结论：

- 不应把余额、持仓、保证金、deficit 放进 Redis 或 JVM cache 作为真实资金源；这些必须以 PostgreSQL 事务和幂等键为准。
- 可以缓存 instrument/risk bracket/fee schedule/mark snapshot、强平费上下文等只读或订单生命周期内不变的数据；订单成交费率应随 `OrderCommandEvent` / `MatchTradeEvent` 显式传递；缓存只能减少读取，不能替代资金账本。
- 下一步扩容优先级是 account 分区/多节点、Kafka partition 与热 symbol 分布、数据库写路径和批量结算，而不是先把账本状态缓存化。

## 验证命令

后端：

```bash
mvn -q -pl :surprising-trading-api,:surprising-order-provider,:surprising-trigger-provider -am -Dtest=CancelAllAfterServiceTest,OrderServiceTest,OrderMarginMathTest,OrderMarginRepositoryTest,TriggerOrderServiceTest,TriggerOrderRepositoryTest,MarkPriceTriggerParserTest,IndexPriceTriggerParserTest,TriggerKafkaConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl :surprising-market-maker-provider -am -Dtest=MarketMakerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl :surprising-order-provider -am -DskipTests package
mvn -q -pl :surprising-market-maker-provider -am -DskipTests package
```

全链路：

```bash
POSTGRES_PORT=15432 RUN_FAILURE_SCENARIOS=false PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false KEEP_TMP=true WS_TIMEOUT=900 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false ./scripts/full-stack-real-config-smoke.sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/surprising_exchange BUILD_SERVICES=false ./scripts/market-maker-stress.sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/surprising_exchange BUILD_SERVICES=false REPORT_FILE=docs/market-maker-stress-report-tier2.md TAKER_ORDER_COUNT=1500 LOAD_CONCURRENCY=96 MM_REFRESH_CYCLES=2 MM_DEPTH_LEVELS=30 MM_REFRESH_LEVELS=10 MAKER_BATCH_SIZE=5 MAKER_LOAD_CONCURRENCY=8 WS_FANOUT_USER_COUNT=2 ./scripts/market-maker-stress.sh
START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true MM_ACCOUNT_COUNT=4 MM_DEPTH_LEVELS=40 MM_REFRESH_LEVELS=12 MM_REFRESH_CYCLES=3 MM_REFRESH_INTERVAL_SECONDS=0 MAKER_BATCH_SIZE=5 MAKER_LOAD_CONCURRENCY=10 TAKER_ORDER_COUNT=2000 TAKER_QUANTITY_STEPS=2 LOAD_CONCURRENCY=128 ORDER_HIKARI_MAX_POOL_SIZE=60 ORDER_HIKARI_CONNECTION_TIMEOUT_MS=10000 ACCOUNT_HIKARI_MAX_POOL_SIZE=40 ACCOUNT_HIKARI_CONNECTION_TIMEOUT_MS=10000 MATCHING_HIKARI_MAX_POOL_SIZE=30 MATCHING_HIKARI_CONNECTION_TIMEOUT_MS=10000 GATEWAY_HIKARI_MAX_POOL_SIZE=20 GATEWAY_HIKARI_CONNECTION_TIMEOUT_MS=10000 TAKER_FILL_WAIT_SECONDS=900 TAKER_TRADE_WAIT_SECONDS=900 ACCOUNT_SETTLEMENT_WAIT_SECONDS=1200 WS_FANOUT_USER_COUNT=3 WS_CAPTURE_TIMEOUT=1500 REPORT_FILE=docs/market-maker-stress-report-tier3.md SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/market-maker-stress.sh
START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=auto KEEP_TMP=true MM_ACCOUNT_COUNT=4 MM_DEPTH_LEVELS=50 MM_LEVEL_QUANTITY_STEPS=50 MM_REFRESH_LEVELS=10 MM_REFRESH_QUANTITY_STEPS=20 MM_REFRESH_CYCLES=5 MM_REFRESH_INTERVAL_SECONDS=1 MAKER_BATCH_SIZE=5 MAKER_LOAD_CONCURRENCY=10 TAKER_ORDER_COUNT=2000 TAKER_QUANTITY_STEPS=2 LOAD_CONCURRENCY=128 ORDER_HIKARI_MAX_POOL_SIZE=60 ORDER_HIKARI_CONNECTION_TIMEOUT_MS=10000 ACCOUNT_HIKARI_MAX_POOL_SIZE=40 ACCOUNT_HIKARI_CONNECTION_TIMEOUT_MS=10000 MATCHING_HIKARI_MAX_POOL_SIZE=30 MATCHING_HIKARI_CONNECTION_TIMEOUT_MS=10000 GATEWAY_HIKARI_MAX_POOL_SIZE=20 GATEWAY_HIKARI_CONNECTION_TIMEOUT_MS=10000 TAKER_FILL_WAIT_SECONDS=1200 TAKER_TRADE_WAIT_SECONDS=1200 ACCOUNT_SETTLEMENT_WAIT_SECONDS=1800 WS_FANOUT_USER_COUNT=5 WS_CAPTURE_TIMEOUT=2100 REPORT_FILE=docs/market-maker-stress-report-tier4.md SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/market-maker-stress.sh
```

客户端：

```bash
# ../surprising-ex-web
corepack pnpm lint

# ../surprising-client
dart format lib/src/api.dart lib/src/models.dart test/widget_test.dart
flutter analyze
flutter test
```

## 当前结论

本轮已经把普通交易接口、改单/批量改单、TP/SL、trailing stop 和 Cancel All After 条件单接口补到可被真实客户端调用的级别。资金正确性检查必须继续以 gateway 全链路脚本结果为准：订单成交应全部被 account 消费，压测用户不能出现负余额，OI 约束保持成立，outbox 清空。

TWAP/Iceberg 已按 algo 层落地并通过真实 gateway 全链路 smoke；market-maker provider 已有连续 run-once 报价、scheduled engine 自主报价、scheduled engine 账户服务重启故障窗口、参考行情 WebSocket 校准、“参考行情 + account 重启”的组合真实 provider 样本，以及 180 秒参考行情持续做市样本，用户 taker 吃单后 account 结算正常；L4 单节点真实链路已完成 10000 笔用户 taker 成交和 account 结算，并把前 5 个用户私有 WebSocket fanout 纳入断言。仍不能把当前结果等同于生产最终压测；生产前还需要更长时间、多节点、多 broker、大规模 WebSocket fanout、外部行情断线、Kafka/PostgreSQL 故障演练、更完整的生产停机编排，以及更完整的私有执行回报协议。
