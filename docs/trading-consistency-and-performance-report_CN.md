# 撮合、账户一致性与端到端性能评估报告

> 2026-07-05 起，本地复跑统一使用 Homebrew PostgreSQL/Kafka/Redis：`localhost:5432`、`localhost:9092`、`localhost:6379`。下文历史命令中的 `POSTGRES_PORT=55432/55433` 或旧端口说明仅保留为当时执行证据，当前不要再为测试启动独立 Docker 中间件。

本文回答三个问题：

- 指数价/标记价不可用时，下单是否有限制，以及建议策略。
- 撮合中的账户余额、订单状态如何与数据库保持一致。
- 当前是否已经做过真实全链路压力测试，瓶颈和缺口在哪里。

## 结论摘要

- 永续合约下单当前已经按“标记价不可用则拒绝”的思路做了保护。市价单需要新鲜 mark price；LIMIT 订单默认也启用 mark price 价格带保护。mark-price 只接受新鲜且状态可用的 `HEALTHY/DEGRADED` index event；指数有效源不足时会停止发布 mark price。撮合侧对市价单再次读取新鲜 mark price，取不到会拒绝 `MARK_PRICE_UNAVAILABLE`。
- 这套设计符合主流交易所的风险控制方向：Binance Futures 的价格过滤、market order 保护区间基于 mark price；OKX 使用价格区间和 mark price 降低异常成交/异常强平风险；Bybit 的强平以 mark price 为核心，且止损可能晚于强平触发。因此衍生品下单入口建议继续 fail closed。
- 账户余额、订单和持仓的一致性不是靠撮合内存状态兜底，而是靠 PostgreSQL 事务、幂等键、行锁/advisory lock、guarded update、transactional outbox 和 Kafka replay 共同保证。
- 本轮修复了一个真实进程下暴露的 outbox 归属问题：liquidation provider 以前会扫描整张 `trading_outbox_events`，可能抢占 matching 拥有的 `ORDER_BOOK_DEPTH` / `MATCH_RESULT` / `MATCH_TRADE` 行并与 matching outbox publisher 竞争，导致 WebSocket depth Kafka 事件出现 `65,67,66,68` 这种乱序。现在清算发布器只认领 `ORDER`/`LIQUIDATION_ORDER`，并按 `topic + event_key` 取最早未发布行和 advisory lock，避免破坏 matching 的 per-symbol depth 顺序。
- 止盈止损多档位已补放置时前置校验：锁定当前仓位后聚合 active reduce-only 平仓单和待触发 TP/SL 总待平量；同一 OCO 组合按最大 sibling 数量计入容量，避免止盈/止损双倍占用。
- HEDGE 仓位侧已经补充到关键资金链路的回归验证：撮合成交事件、风控事件/强平候选、强平下单、资金费扣款和 ADL 审计都保留 `positionSide`。ADL 事件新增 `target_position_side`，避免审计里只看到 LONG/SHORT 方向而无法区分 NET/LONG/SHORT 仓位桶。
- TWAP / Iceberg 算法单已经按真实用户 API 路径接入 full-stack smoke。父算法单不进入 exchange-core 订单簿；子单是普通 order-provider 订单，继续经过撮合、account 结算、risk/WebSocket 链路。活动算法单会阻断持仓模式切换，`cancel-open` 后再允许切换。
- 已有压测包含真实 provider、PostgreSQL、Kafka、做市商铺单和普通用户 taker 流量。2026-07-05 追加 L4 单节点真实接口压测：4 个 maker、50 档盘口、5 轮刷新、10000 笔普通用户 taker、128 并发全部经 gateway/order/matching/account/websocket 链路，最终 10000 笔成交全部 account 结算、Kafka lag 回到 0、outbox 清空，负余额、负保证金、deficit、非法 OI 均为 0，前 5 个 taker 用户私有推送不串号。当前又补了 market-maker provider 连续 run-once smoke、scheduled engine 自主报价 full-stack smoke、账户服务重启故障 smoke、启用参考行情 WebSocket 的真实 full-stack smoke、参考行情 + account 重启组合 smoke、180 秒参考行情持续做市 smoke、account 热路径缓存/剪枝、available-balance fast path、instrument metadata cache 真实链路回归，以及 matching 单 JVM 多 consumer 回归：`MATCHING_CONSUMERS_PER_NODE=6` 时 BTC/ETH 热 symbol 分散到不同 consumer，matching p50 降低，瓶颈后移到 account 结算写库。余额/持仓/保证金/deficit 仍以 PostgreSQL 事务为事实源。但现有压测仍不是生产级全链路压力测试：还缺多节点、多 broker、长时间运行和大规模 WebSocket 长连接证据。

## 标记价/指数价不可用时的下单限制

### 当前代码行为

下单校验在 `OrderValidator` 中做了两层限制：

- 市价单：`validateMarket` 调用 `markPriceLookup.latestMarkPriceTicks(...)`，取不到新鲜 mark price 时返回 `mark price unavailable`。
- LIMIT 单：默认启用 `limit-price-protection-enabled`，`validateLimitPriceBand` 同样要求新鲜 mark price，并按方向限制价格带。

价格新鲜度来自 `OrderMarkPriceRepository`：只读取 `price_mark_ticks.event_time >= now() - maxAgeMs` 的 mark price。也就是说，不是表里有旧价格就能继续下单。

指数价链路有 WebSocket 本地缓存和 REST 兜底，只有新鲜 source quote 才参与指数价计算。有效源数量不足时，index-price provider 输出 `INSUFFICIENT_SOURCES` 且 `indexPrice=null`。mark-price provider 必须拿到新鲜、非空且状态为 `HEALTHY/DEGRADED` 的 index price 才会发布 mark price；book ticker 或 trade 不新鲜时可以用 index 合成兜底，但 index price 缺失、过期或状态不可用时不会继续发布 mark price。

撮合侧也有二次保护：`MatchingService.effectivePriceTicks(...)` 对 MARKET order 读取新鲜 mark price，取不到会把撮合结果拒绝为 `MARK_PRICE_UNAVAILABLE`。这可以防止 order-provider 校验后到 matching 处理前 mark price 过期。

触发单侧支持 `MARK_PRICE`、`INDEX_PRICE` 和 `LAST_PRICE` 触发。`MARK_PRICE`/`INDEX_PRICE` 会先读取对应价格表，取不到对应新鲜价格时不会 claim 触发单；`LAST_PRICE` 直接消费真实撮合成交事件的 `priceTicks`。实际效果是：mark/index 价格源不可用时不会误触发止盈止损；last price 可按最新成交触发用户 TP/SL，但薄盘口下比 mark/index 更容易受短时冲击影响，强平仍只使用 mark price。当前还新增了 `TRAILING_STOP`：激活后维护最高/最低水位，回调达到 `callbackRatePpm` 后仍通过 reduce-only 市价平仓单进入普通撮合/account 链路。

2026-07-04 的最小真实配置回归专门验证了该链路：开启 mark-price Kafka listener 后，脚本只暂停 `index-price provider`，注入 `INSUFFICIENT_SOURCES` index event，并确认不再产生新鲜 mark price；随后普通市价单被 order-provider 拒绝为 `mark price unavailable`。再发布健康 index/book/trade 输入后，mark price 恢复发布，脚本继续跑完 TP/SL、强平、ADL 和 WebSocket/accounting invariants。

2026-07-05 已补应急交易模式真实链路：通过 instrument admin API 把 `BTC-USDT` 切到 `HALT` 后，新开单经 gateway/order-provider 拒绝为 `instrument is cancel-only`，但存量开放单仍能经 gateway 撤成 `CANCELED`；切到 `SETTLING` 后，普通开仓拒绝为 `instrument is reduce-only`，多空双方 reduce-only 平仓继续进入 matching/account 并完成结算。

### 主流交易所参考

- Binance Futures 的 `PERCENT_PRICE` 价格过滤基于 mark price，BUY 不能高于 `markPrice * multiplierUp`，SELL 不能低于 `markPrice * multiplierDown`。
- OKX 的价格限制规则会实时计算最高/最低价格，超出价格范围的订单会被拒绝；API 可以选择自动改价策略。OKX 公开规则还说明永续/交割合约价格限制以 index、近期溢价和上限参数组合计算。
- Bybit 衍生品价格限制用于防操纵，开仓和平仓都适用；Bybit 下单 API 还说明市价单会转换为 IOC 限价单，滑点阈值参考订单价格相对 mark price 的偏离。Bybit mark price 文档说明，当某个指数来源异常或取不到数据时，可使用平台 last traded price 作为兜底计算 mark price。

参考链接：

- [Binance Futures Common Definition](https://developers.binance.com/docs/derivatives/coin-margined-futures/common-definition)
- [OKX Price Limit Rules](https://www.okx.com/en-us/help/iii-price-limit-rules)
- [Bybit Futures Trading Rules](https://www.bybit.com/en/help-center/article/Futures-Trading-Rules)
- [Bybit Place Order API](https://bybit-exchange.github.io/docs/v5/order/create-order)
- [Bybit Mark Price Calculation](https://www.bybit.com/en/help-center/article/Mark-Price-Calculation-Perpetual-Expiry-Contracts)

### 建议策略

衍生品交易应继续采用 fail closed：

- 新开仓、市价平仓、普通 LIMIT 下单：mark price 或指数来源不可用/过期时拒绝。
- 撤单：不依赖 mark price，必须保持可用。
- 风险降低类操作：只在显式应急交易模式中允许受控 reduce-only 下单；当前 `SETTLING` 已按 reduce-only 模式落地，普通开仓会被拒绝。
- 运营上由 instrument 状态控制应急模式：`HALT` 对用户表现为 cancel-only，`SETTLING` 对用户表现为 reduce-only，恢复后切回 `TRADING`。

## 撮合、订单和账户如何保持一致

### 下单入口

`OrderService.place(...)` 在一个数据库事务中完成：

- 规范化请求和 `clientOrderId` 幂等检查。
- 锁用户持仓模式，再锁 `userId + symbol` 保证金模式作用域，防止跨保证金模式并发切换。
- 订单校验、reduce-only 校验、手续费快照。
- 插入 `trading_orders`，唯一约束为 `(user_id, client_order_id)`，重复请求返回第一笔订单。
- 非 reduce-only 开仓单在同一事务里冻结保证金或现货资产。
- 写订单事件 outbox 和撮合 command outbox。

资金冻结使用 `SELECT ... FOR UPDATE` 锁余额行，并且 `UPDATE ... WHERE available_units >= ?`。如果可用余额不足或并发抢占，冻结失败，订单会被拒绝，不会出现负可用余额。

### 撮合入口

`MatchingService.process(...)` 是事务处理：

- 先用 `commandResultExists(commandId)` 做 command 幂等。
- 市价单在撮合侧重新读取新鲜 mark price；取不到拒绝。
- 通过 exchange-core 内存撮合后，把 result/trades/orderbook/outbox 一起写入数据库。
- 订单成交更新使用 guarded update：`remaining_quantity_steps >= fillQty`，并保持 `quantity_steps = executed + remaining`。
- 撤单和终态订单释放保证金时，余额释放要求 `locked_units >= amount`，否则 fail fast，不会把不存在的冻结金额加回可用余额。

这个设计的关键点是：exchange-core 负责快速撮合和订单簿状态，PostgreSQL 的 `trading_orders`、match result、match trades 和 outbox 是权威审计和重放来源。matching 重启时可以从数据库开放订单恢复订单簿。

### 账户结算入口

`AccountService.processTradeIfNew(...)` 先插入 `account_processed_trades(symbol, trade_id)`：

- 插入成功才处理成交。
- 同一 symbol + tradeId 重放时返回 false，不重复扣费、迁移保证金或更新持仓。

每个成交侧会锁定持仓行：`accountRepository.lockPosition(... FOR UPDATE)`，然后按顺序结算：

- 平仓已实现 PnL。
- 开仓消费订单冻结保证金，按实际成交价迁移到持仓保证金。
- 扣 maker/taker 手续费。
- 平仓释放持仓保证金和未成交订单保证金。
- 更新持仓、OI、reduce-only 挂单剪枝。
- 写 position updated outbox，供 risk/WebSocket 后续消费。

Account outbox 用 `FOR UPDATE SKIP LOCKED` 认领待发布事件，Kafka 发送失败会重试。账户数据库更新先提交，事件发布是可重试的异步副作用，因此不会因为 WebSocket 或下游短暂故障回滚资金结算。

## 已有端到端压测证据

已有真实进程压测见：

- `docs/market-maker-stress-report.md`
- `docs/market-maker-topology-observability-report.md`
- `docs/market-maker-multinode-stress-report.md`
- `docs/market-maker-account-failover-report.md`
- `docs/market-maker-matching-failover-report.md`
- `docs/market-maker-observability-smoke-report.md`
- `docs/market-maker-continuous-smoke-report.md`
- `docs/market-maker-stress-report-tier3.md`

最新 full-stack real-config smoke：

- 命令：`START_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=auto KEEP_TMP=true PAIR_COUNT=3 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=5 RISK_BACKGROUND_LOAD_ENABLED=false POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-05 通过，日志在 `/tmp/surprising-full-stack-real-config.A7PtM8`。
- 覆盖：真实 gateway 用户接口、instrument admin 状态切换、order-provider 应急模式校验、matching 在 `HALT/SETTLING` 状态下继续处理撤单和 reduce-only 平仓、account 结算和最终资金不变量。
- 关键状态：`real-emergency-halt-cancel-*` 为 `CANCELED|0|0`，`real-emergency-halt-reject-*` 为 `REJECTED|instrument is cancel-only`，`real-emergency-settling-reject-*` 为 `REJECTED|instrument is reduce-only`，`real-emergency-close-liquidity-*` 和 `real-emergency-reduce-close-*` 均 `FILLED|2|0`。应急用户最终 `positions_abs=0`、`locked_usdt=0`、`deficits=0`、`open_orders=0`，相关成交 `account_processed_trades=2`。

- 命令：`POSTGRES_PORT=55433 PAIR_COUNT=3 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=5 RUN_FAILURE_SCENARIOS=true BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=240 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.3qu3b9`。
- 范围：真实 PostgreSQL/Kafka 和 instrument、candlestick、index-price、mark-price、order、matching、account、risk、liquidation、funding、insurance、ADL、websocket、trigger、gateway、market-maker provider。
- 覆盖：持仓模式切换保护、HEDGE 同合约 LONG/SHORT、私有 `position`/`positionRisk` 的 `positionSide` 推送、币本位永续开平仓、现货结算、全成交、部分成交撤单、cancel-only/cancel-all、active reduce-only、TP/SL OCO、风控、matching 开放订单簿恢复、account 未结算成交重放、并发小负载、深盘口、资金费、逐仓风险恢复、强平、保险基金、ADL、market-maker run-once post-only 铺单、公开/私有 WebSocket 捕获和会计不变量。
- 关键计数：depth events `48`，mark events `233`，funding events `228`；HEDGE 用户 `LONG` 和 `SHORT` 各收到 2 条 position 与 12 条 positionRisk 推送；ADL 事件表核对为 `1|NET`，即真实 ADL 场景写入了 `target_position_side`。
- 运行说明：第一次使用默认 `5432` 时命中了本机 PostgreSQL/SSH 端口占用，导致 provider 连接到本机库并报 `role "surprising" does not exist`。改用 `POSTGRES_PORT=55433` 后通过，说明失败根因是本机端口冲突，不是业务 schema 或资金链路问题。

补充最小真实配置回归：

- 命令：`START_INFRA=false POSTGRES_PORT=55433 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=180 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.xAFD0M`。
- 范围：复用当时已有 PostgreSQL/Kafka，只重启本次测试需要的 provider 进程；`BUILD_SERVICES=auto` 检测 provider jar 已是最新，因此跳过 Maven package。
- 覆盖：持仓模式切换阻断场景现在会先真实开仓，再放置待触发 TP/SL 条件单，避免把“空仓不能挂减仓 TP/SL”的正确校验误判为 smoke 失败；同时覆盖 `LOAD_CONCURRENCY=1` 时 shell 数组为空的收尾边界。最终捕获 depth events `41`、mark events `175`、funding events `171`，WebSocket/accounting invariants 通过。

补充 index source fail-closed 真实配置回归：

- 命令：`START_INFRA=false POSTGRES_PORT=55433 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=900 FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=true MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.ENDSKF`。
- 范围：复用当时已有 PostgreSQL/Kafka，启动真实全 provider；`BUILD_SERVICES=auto` 检测 provider jar 已是最新，因此跳过 Maven package。
- 覆盖：脚本只暂停 `index-price provider` 隔离指数源不足输入，确认 `INSUFFICIENT_SOURCES` 后 mark price 不再刷新、普通市价单拒绝 `mark price unavailable`、健康指数输入恢复后 mark price 重新发布；后续继续通过 TP/SL、风险、强平、保险基金、ADL、market-maker run-once 和 WebSocket/accounting invariants。最终捕获 depth events `122`、mark events `421`、funding events `428`。
- 运行说明：完整链路耗时已经超过旧的 180 秒 WebSocket 捕获窗口，本轮把脚本默认 `WS_TIMEOUT` 提高到 900 秒，避免捕获进程提前结束造成最终推送断言误判。

补充 `MARK_PRICE` / `INDEX_PRICE` 条件单真实链路回归：

- 命令：`POSTGRES_PORT=15432 RUN_FAILURE_SCENARIOS=false PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false KEEP_TMP=true WS_TIMEOUT=900 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.8iFVoH`。
- 覆盖：通过 gateway 真实开仓、放置 `MARK_PRICE` TP/SL、放置 `INDEX_PRICE` TP/SL、Kafka 价格事件触发、trigger-provider 生成 reduce-only 平仓单、matching/account 结算和持仓归零。`INDEX_PRICE` 条件单最终为 `INDEX_PRICE|TRIGGERED|625760|50`。
- 资金不变量：余额负数、产品余额负数、保证金释放超额、现货预占释放超额均为 `0`；trading/account/risk outbox 中 attempts 后仍未发布的行均为 `0`。最终捕获 depth events `142`、mark events `268`、funding events `264`。

补充普通订单改单/批量改单真实链路回归：

- 命令：`POSTGRES_PORT=15432 RUN_FAILURE_SCENARIOS=false PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false KEEP_TMP=true WS_TIMEOUT=900 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.OKaFPW`。
- 覆盖：通过 gateway 调用 `POST /trading/amend` 和 `POST /trading/batch-amend`，普通开放 LIMIT 订单按 cancel-replace 语义先请求撤原单，再提交新的替换单。脚本等待原单撤销、替换单撮合接收、再撤掉替换单，确保不污染后续盘口。
- 关键状态：`real-amend-source|CANCELED|0|0|586000|2`、`real-amend-replacement|CANCELED|0|0|585000|1`、`real-batch-amend-source-a|CANCELED|0|0|584000|2`、`real-batch-amend-source-b|CANCELED|0|0|584000|3`、`real-batch-amend-a|CANCELED|0|0|583000|1`、`real-batch-amend-b|CANCELED|0|0|582000|2`。
- 同轮继续覆盖 `INDEX_PRICE` 条件单，最终为 `INDEX_PRICE|TRIGGERED|626194|56`；余额负数、产品余额负数、保证金释放超额、现货预占释放超额均为 `0`，trading/account/risk outbox 中 attempts 后仍未发布的行均为 `0`。最终捕获 depth events `154`、mark events `364`、funding events `358`。

补充 `LAST_PRICE` 条件单真实链路回归：

- 命令：`POSTGRES_PORT=15432 RUN_FAILURE_SCENARIOS=false PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false KEEP_TMP=true WS_TIMEOUT=900 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.UnxBMi`。
- 覆盖：通过 gateway 真实开仓、放置 `LAST_PRICE` TP、用一笔真实撮合成交把最新成交价推到 `600600`，trigger-provider 消费 `surprising.perp.match.trades.v1` 后 claim 条件单，并生成 reduce-only 平仓单。关键状态为 `real-last-tp-*|LAST_PRICE|TRIGGERED|600600|57|FILLED|6|0`。
- 资金不变量：余额负数、产品余额负数、保证金释放超额、现货预占释放超额均为 `0`；trading/account/risk outbox 中 attempts 后仍未发布的行均为 `0`。最终捕获 depth events `159`、mark events `287`、funding events `283`。

补充 atomic 组合 TP/SL 真实链路回归：

- 命令：`POSTGRES_PORT=15432 RUN_FAILURE_SCENARIOS=false PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false KEEP_TMP=true WS_TIMEOUT=900 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.wAYNHp`。
- 覆盖：通过 gateway 调用 `POST /trading-trigger/batch` 并传 `atomic=true`，提交一条有效 TP 腿和一条超出可平仓位的 SL 腿。trigger-provider 在同一事务中发现总待平量超限，返回整组失败；脚本随后查询 `real-trigger-atomic-*`，确认 `trading_trigger_orders` 中没有部分条件单残留。
- 资金不变量：余额负数、产品余额负数、保证金释放超额、现货预占释放超额均为 `0`；trading/account/risk outbox 中 attempts 后仍未发布的行均为 `0`。最终捕获 depth events `159`、mark events `263`、funding events `259`。

补充 Cancel All After 真实链路回归：

- 命令：`POSTGRES_PORT=15432 RUN_FAILURE_SCENARIOS=false PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false KEEP_TMP=true WS_TIMEOUT=900 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.EfyZWo`。
- 覆盖：通过 gateway 调用 `POST /trading/cancel-all-after` 并传 `countdownMs=1500`。脚本先创建同一用户的开放普通挂单和 pending TP/SL，到期后由 order-provider 后台任务撤普通单并通过 trigger-provider 撤条件单；数据库结果为 `trading_cancel_all_after = TRIGGERED|1500|1|1`，普通单和条件单开放残留均为 `0`。
- 资金不变量：余额负数、产品余额负数、保证金释放超额、现货预占释放超额均为 `0`；trading/account/risk outbox 中 attempts 后仍未发布的行均为 `0`。最终捕获 depth events `161`、mark events `268`、funding events `264`。

补充 `TRAILING_STOP` 真实链路回归：

- 命令：`POSTGRES_PORT=15432 RUN_FAILURE_SCENARIOS=false PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false KEEP_TMP=true WS_TIMEOUT=900 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.A8FcrR`。
- 覆盖：通过 gateway 真实开仓、放置 `MARK_PRICE` `TRAILING_STOP`、经 Kafka mark price 事件激活、更新最高水位、回调触发，并由 trigger-provider 生成 reduce-only 市价平仓单。数据库结果为 `real-trailing-stop-*|TRIGGERED|activation=649234|callbackRatePpm=100000|highest=651234|triggered=581234|placedOrder=66`；生成订单 `trigger-11` 和对手方 `real-trailing-close-maker-*` 都为 `FILLED|6|0`。
- 资金不变量：余额负数、产品余额负数均为 `0`；trading/account/funding/risk outbox 中 attempts 后仍未发布的行均为 `0`。最终捕获 depth events `165`、mark events `356`、funding events `346`。

补充 TWAP / Iceberg 算法单真实链路回归：

- 命令：`POSTGRES_PORT=55432 KEEP_TMP=true RUN_FAILURE_SCENARIOS=false PAIR_COUNT=10 LOAD_CONCURRENCY=4 BOOK_DEPTH_LEVELS=20 bash scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-05 通过，日志在 `/tmp/surprising-full-stack-real-config.DsxfGp`。
- 覆盖：通过 gateway 真实调用算法单接口，TWAP 父单调度 3 笔 IOC 子单并在全部成交后 `COMPLETED`；Iceberg 同一时间只保持一笔活动可见子单，3 片逐步成交后 `COMPLETED`；活动 Iceberg 会让持仓模式切换返回 409，调用 `trading/algo/cancel-open` 后父单和活动子单都为 `CANCELED`，再切换成功。
- 资金不变量：余额负数、产品余额负数、保证金释放超额、现货预占释放超额均为 `0`；trading/account/funding/risk outbox 中 attempts 后仍未发布的行均为 `0`。最终捕获 depth events `213`、mark events `399`、funding events `390`。

补充清算/ADL 并发成交流真实配置回归：

- 命令：`START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=900 PAIR_COUNT=2 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=3 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=true FULL_STACK_RISK_BACKGROUND_PAIR_COUNT=2 FULL_STACK_RISK_BACKGROUND_ROUNDS=4 FULL_STACK_RISK_BACKGROUND_CONCURRENCY=2 FULL_STACK_RISK_BACKGROUND_INTERVAL_SECONDS=1 FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false POSTGRES_PORT=55433 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55433/surprising_exchange JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.ePIRXF`。
- 范围：复用当时已有 PostgreSQL/Kafka，只重启本轮真实 provider；`BUILD_SERVICES=false`，未重新 package 未改模块。
- 覆盖：在强平、保险基金和 ADL 场景运行期间，同时执行 8 组普通 maker/taker 成交；后台成交流完成后，脚本核对 `trading_match_trades` 的成交数量/成交量和 `account_processed_trades` 均为 8。最终捕获 depth events `141`、mark events `237`、funding events `234`，多个测试用户收到私有 orders/matches/positions/positionRisk/accountRisk 推送，WebSocket/accounting invariants 通过。
- 发现并修复：首轮带后台成交流运行时，Kafka depth topic 出现同一 symbol 内序列 `65,67,66,68`，根因是 liquidation provider 的 trading outbox publisher 未按 aggregate_type 限制，抢占并发布了 matching-owned `ORDER_BOOK_DEPTH` 行。修复后 `LiquidationOrderRepository.lockPending(...)` 只认领 `ORDER`/`LIQUIDATION_ORDER`，并用 `DISTINCT ON (topic, event_key)` 和 advisory lock 保证同一 topic/key 的最早事件先发布；`LiquidationOrderRepositoryTest` 已覆盖该 SQL 约束。

最新连续做市刷新 real-process smoke：

- 命令：`START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true MM_ACCOUNT_COUNT=1 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 MM_REFRESH_CYCLES=2 MM_REFRESH_INTERVAL_SECONDS=1 TAKER_ORDER_COUNT=2 LOAD_CONCURRENCY=2 REPORT_FILE=docs/market-maker-continuous-smoke-report.md SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55433/surprising_exchange JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" ./scripts/market-maker-stress.sh`
- 结果：2026-07-04 通过，报告为 `docs/market-maker-continuous-smoke-report.md`，日志在 `/tmp/surprising-mm-stress.qiFDht`。
- 范围：真实 order、matching、account、websocket、gateway provider；复用当时已有 PostgreSQL/Kafka；`BUILD_SERVICES=false`，未重新打包 provider jar。
- 关键计数：初始 maker 挂单 8 笔、连续刷新挂单 8 笔、普通用户 taker 订单 4 笔、撮合成交 4 笔、account 已结算 4 笔、account Kafka 最终 lag 为 `0`。
- 运行说明：本轮使用 `RESET_STATE=true` 清空测试状态。早前用脏状态直接复跑时遇到旧开放挂单污染成交路径，因此连续压测要么使用干净 fixture，要么显式把已有开放订单、余额和持仓纳入测试前置条件，不能把未知状态下的旧流动性当作可靠基线。

最新 market-maker provider 连续报价 full-stack smoke：

- 命令：`START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=900 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=8 MM_PROVIDER_CONTINUOUS_INTERVAL_SECONDS=1 MM_PROVIDER_CONTINUOUS_TAKER_ORDERS=2 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-05 通过，报告为 `docs/market-maker-provider-continuous-report.md`，日志在 `/tmp/surprising-full-stack-real-config.KW6Dsn`。
- 范围：真实 instrument/candlestick/index-price/mark-price/order/matching/account/risk/liquidation/funding/insurance/ADL/websocket/trigger/gateway/market-maker provider；market-maker provider 通过 gateway `market-maker/run-once` 连续 8 秒执行报价引擎，普通用户 taker 通过 gateway `trading` 单笔接口吃单。
- 关键计数：BTC 测试价根据最新 mark 同步为 `631107` ticks；market-maker `QUOTE_RECONCILED=4`，提交 post-only 报价 160 笔；连续 taker 成交 2 笔、account 结算 2 笔；做市账号和 taker 账号负余额为 0，开放 market-maker 报价清理后为 0。
- 资金和风险不变量：负账户余额、负产品余额、负逐仓保证金、零仓位脏 entry price、非法 OI 均为 0；account deficit/product deficit 剩余 sum 为 0；保险基金覆盖 2 笔共 `2000000`，ADL 覆盖 1 笔 `1000000`，remaining deficit 均为 0。
- 发现并修复：首轮尝试在 order API close-position 开仓阶段被正确拒绝，原因是脚本固定 BTC 价格 `600000` ticks，而 index/mark provider 已按 live Binance/OKX/Bybit REST 行情把 mark 刷到约 `631000` ticks，SELL IOC 低于 3% 保护下界。脚本已在首笔 BTC 用户订单前按最新 mark 重算本轮测试价格和安全偏移，避免把真实价格保护误判为业务失败。

最新 market-maker provider scheduled engine full-stack smoke：

- 命令：`START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1200 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=60 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=20 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-05 通过，报告为 `docs/market-maker-provider-engine-report.md`，日志在 `/tmp/surprising-full-stack-real-config.MQjxfE`。
- 范围：真实 instrument/candlestick/index-price/mark-price/order/matching/account/risk/liquidation/funding/insurance/ADL/websocket/trigger/gateway/market-maker provider；market-maker provider 先以安全模式 `engine.enabled=false` 启动，待资金和 fixture 就绪后被脚本重启为 `engine.enabled=true`，由 provider 自己的 `@Scheduled` 引擎每 100ms 驱动报价。
- 关键计数：BTC 测试价根据最新 mark 同步为 `633550` ticks；engine 窗口内 `CYCLE_SUCCESS=5`、`QUOTE_RECONCILED=10`，提交 post-only 报价 400 笔，普通用户 taker 成交 20 笔、account 结算 20 笔，开放 market-maker 报价清理后为 0。
- 资金和风险不变量：负账户余额、负产品余额、负逐仓保证金、零仓位脏 entry price、非法 OI 均为 0；account deficit/product deficit 剩余 sum 为 0；保险基金覆盖 2 笔共 `2000000`，ADL 覆盖 1 笔 `1000000`，remaining deficit 均为 0；trading/account outbox 未发布和失败行均为 0；trigger-provider 日志不再出现无关 ETH index ticks 缺失导致的重试。

最新 market-maker provider 参考行情 WebSocket full-stack smoke：

- 命令：`START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1200 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=30 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=5 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-05 通过，报告为 `docs/market-maker-reference-market-report.md`，日志在 `/tmp/surprising-full-stack-real-config.W2VWYd`。
- 范围：真实 instrument/candlestick/index-price/mark-price/order/matching/account/risk/liquidation/funding/insurance/ADL/websocket/trigger/gateway/market-maker provider；market-maker provider 启用 `reference-market.enabled=true` 和 `websocket-enabled=true`，由外部 Binance/Bybit 流式深度样本和 Binance REST 兜底样本校准本地报价。
- 关键计数：`market_maker_reference_samples` 记录 `REST|BINANCE_USDM=2`、`WEBSOCKET|BINANCE_USDM=1`、`WEBSOCKET|BYBIT_LINEAR=1`，每个样本为 20x20 深度、spread=1 tick；market-maker `CYCLE_SUCCESS=4`、`QUOTE_RECONCILED=8`、提交 post-only 报价 `313`、拒单 `0`，普通用户 engine taker 成交/account 结算 `5/5`，开放 market-maker 报价清理后为 0。
- 资金和风险不变量：负账户余额、负产品余额、负逐仓保证金、零仓位脏 entry price、非法 OI 均为 0；account deficit/product deficit 剩余 sum 为 0；保险基金覆盖 2 笔共 `2000000`，ADL 覆盖 1 笔 `1000000`，remaining deficit 均为 0；trading/account/risk/funding outbox 未发布或失败行均为 0。
- 发现并修复：首轮启用参考行情时，外部盘口数量映射到本地最大 `1000` steps，超出 smoke 做市账号资金规模，80 笔报价中 18 笔被正确拒绝为 `insufficient available margin`。现在 `QuotePlanner` 仍使用参考盘口价格距离和相对数量，但把本地单档数量上限限制在策略 `baseQuantitySteps` 内，保证参考行情校准不会突破本地做市资金预算。

最新 market-maker provider scheduled engine 账户服务重启故障 smoke：

- 命令：`START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1200 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=45 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=5 MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true MM_PROVIDER_ENGINE_ACCOUNT_RESTART_DELAY_SECONDS=5 MM_PROVIDER_ENGINE_ACCOUNT_DOWN_SECONDS=5 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-05 通过，报告为 `docs/market-maker-provider-engine-fault-report.md`，日志在 `/tmp/surprising-full-stack-real-config.F0V0h2`。
- 范围：真实 instrument/candlestick/index-price/mark-price/order/matching/account/risk/liquidation/funding/insurance/ADL/websocket/trigger/gateway/market-maker provider；market-maker provider 以 `engine.enabled=true` 的 `@Scheduled` 引擎报价，脚本在报价被接收后停止真实 account provider，故障窗口内普通用户 taker 仍通过 gateway `trading` 单笔接口吃单，随后重启 account provider 并等待结算追平。
- 关键计数：故障窗口内出现预期 `CYCLE_FAILED=103`，重启后恢复 `CYCLE_SUCCESS`；market-maker `QUOTE_RECONCILED=9`，提交 post-only 报价 360 笔、做市拒单 0；engine taker 成交 5 笔、account 结算 5 笔；开放 market-maker 报价清理后为 0。
- 资金和风险不变量：负账户余额、负产品余额、负逐仓保证金、零仓位脏 entry price、非法 OI 均为 0；trading/account/risk/funding outbox 未发布或失败行均为 0；account deficit/product deficit 剩余 sum 为 0；保险基金覆盖 2 笔共 `2000000`，ADL 覆盖 1 笔 `1000000`，remaining deficit 均为 0。

最新 market-maker provider 参考行情 WebSocket + 账户服务重启故障 smoke：

- 命令：`START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1500 PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_TRADE_ENABLED=false MM_PROVIDER_CONTINUOUS_SECONDS=0 MM_PROVIDER_ENGINE_SECONDS=90 MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 MM_PROVIDER_ENGINE_TAKER_ORDERS=10 MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true MM_PROVIDER_ENGINE_ACCOUNT_RESTART_DELAY_SECONDS=10 MM_PROVIDER_ENGINE_ACCOUNT_DOWN_SECONDS=10 POSTGRES_PORT=55432 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-05 通过，报告为 `docs/market-maker-reference-market-fault-report.md`，日志在 `/tmp/surprising-full-stack-real-config.8XwMJ0`。
- 范围：真实 instrument/candlestick/index-price/mark-price/order/matching/account/risk/liquidation/funding/insurance/ADL/websocket/trigger/gateway/market-maker provider；market-maker provider 启用 `reference-market.enabled=true` 和 `websocket-enabled=true`，由 Binance/OKX/Bybit 风格流式深度样本校准本地报价，并在 scheduled engine 报价期间停止/重启真实 account provider。
- 关键计数：`market_maker_reference_samples` 记录 `REST|BINANCE_USDM=2`、`WEBSOCKET|BINANCE_USDM=142`、`WEBSOCKET|BYBIT_LINEAR=4`、`WEBSOCKET|OKX_SWAP=2`，每个样本为 20x20 深度、spread=1 tick；故障窗口出现预期 `CYCLE_FAILED=144`，恢复后 market-maker `CYCLE_SUCCESS=6`、`QUOTE_RECONCILED=12`，提交 post-only 报价 476 笔、做市拒单 0；engine taker 成交 10 笔、account 结算 10 笔；开放 market-maker 报价清理后为 0。
- 资金和风险不变量：负账户余额、负产品余额、负逐仓保证金、零仓位脏 entry price、非法 OI 均为 0；trading/account/risk/funding outbox 未发布或失败行均为 0；account deficit/product deficit 剩余 sum 为 0；保险基金覆盖 2 笔共 `2000000`，ADL 覆盖 1 笔 `1000000`，remaining deficit 均为 0。

最新多用户 WebSocket fanout real-process smoke：

- 命令：`START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true MM_ACCOUNT_COUNT=1 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 MM_REFRESH_CYCLES=2 MM_REFRESH_INTERVAL_SECONDS=1 TAKER_ORDER_COUNT=3 LOAD_CONCURRENCY=2 WS_FANOUT_USER_COUNT=3 WS_CAPTURE_TIMEOUT=900 REPORT_FILE=docs/market-maker-fanout-smoke-report.md SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55433/surprising_exchange JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" ./scripts/market-maker-stress.sh`
- 结果：2026-07-04 通过，报告为 `docs/market-maker-fanout-smoke-report.md`，日志在 `/tmp/surprising-mm-stress.9BkxPs`。
- 范围：真实 order、matching、account、websocket、gateway provider；复用当时已有 PostgreSQL/Kafka；`BUILD_SERVICES=false`，未重新打包 provider jar。
- 关键计数：初始 maker 挂单 8 笔、连续刷新挂单 8 笔、普通用户 taker 订单 6 笔、撮合成交 6 笔、account 已结算 6 笔、account Kafka 最终 lag 为 `0`。WebSocket 捕获 BTC depth `11`、ETH depth `11`；前 3 个 taker 用户分别收到自己的 `orders=1` 和 `positions=1`，脚本会 fail fast 检查私有事件 `userId` 是否串号。
- 运行说明：`scripts/market-maker-stress.sh` 新增 `WS_FANOUT_USER_COUNT` 和 `WS_CAPTURE_TIMEOUT`。默认仍订阅 1 个用户以保持现有快速 smoke；需要扩大 fanout 时只调参数，不需要改业务代码或重启无关模块。

L3 单节点真实接口压测：

- 命令：`START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true MM_ACCOUNT_COUNT=4 MM_DEPTH_LEVELS=40 MM_REFRESH_LEVELS=12 MM_REFRESH_CYCLES=3 MAKER_BATCH_SIZE=5 MAKER_LOAD_CONCURRENCY=10 TAKER_ORDER_COUNT=2000 TAKER_QUANTITY_STEPS=2 LOAD_CONCURRENCY=128 ORDER_HIKARI_MAX_POOL_SIZE=60 ACCOUNT_HIKARI_MAX_POOL_SIZE=40 MATCHING_HIKARI_MAX_POOL_SIZE=30 GATEWAY_HIKARI_MAX_POOL_SIZE=20 TAKER_FILL_WAIT_SECONDS=900 TAKER_TRADE_WAIT_SECONDS=900 ACCOUNT_SETTLEMENT_WAIT_SECONDS=1200 WS_FANOUT_USER_COUNT=3 WS_CAPTURE_TIMEOUT=1500 REPORT_FILE=docs/market-maker-stress-report-tier3.md SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/market-maker-stress.sh`
- 结果：2026-07-05 通过，报告为 `docs/market-maker-stress-report-tier3.md`，日志在 `/tmp/surprising-mm-stress.6Z6Lu8`。
- 范围：真实 order、matching、account、websocket、gateway provider；maker 报价走 gateway `/trading/batch`，普通用户 taker 逐笔走 gateway 单笔接口；`BUILD_SERVICES=false`，未重新打包 provider jar。
- 关键计数：初始 maker 挂单 640 笔、连续刷新挂单 576 笔、普通用户 taker 订单 6000 笔、撮合成交 6000 笔、account 已结算 6000 笔、account Kafka max lag `2442` 且最终 lag 为 `0`，`openTradingOutbox=0`、`openAccountOutbox=0`、负余额和非法 OI 检查均为 `0`。
- 延迟：order accepted -> matching result 为 `count=6000 p50=333591.006ms p95=545958.762ms p99=608541.815ms`；order accepted -> account processed trade 为 `count=6000 p50=428109.706ms p95=703097.453ms p99=761067.373ms`。这说明单节点端到端瓶颈仍在 order 入库/outbox、Kafka backlog、account 结算和 PostgreSQL 写路径，不是 exchange-core 裸撮合。
- 运行说明：第一次同压力尝试暴露 order-provider Hikari 连接池耗尽，普通用户成交停在 3677/6000；脚本随后增加 provider Hikari 连接池和等待窗口参数，复跑使用 order=60、matching=30、account=40、gateway=20 连接池后通过。失败样本作为容量瓶颈证据保留，不能只看成功样本。

最新 L4 单节点真实接口压测：

- 命令：`START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=auto KEEP_TMP=true MM_ACCOUNT_COUNT=4 MM_DEPTH_LEVELS=50 MM_LEVEL_QUANTITY_STEPS=50 MM_REFRESH_LEVELS=10 MM_REFRESH_QUANTITY_STEPS=20 MM_REFRESH_CYCLES=5 MM_REFRESH_INTERVAL_SECONDS=1 MAKER_BATCH_SIZE=5 MAKER_LOAD_CONCURRENCY=10 TAKER_ORDER_COUNT=2000 TAKER_QUANTITY_STEPS=2 LOAD_CONCURRENCY=128 ORDER_HIKARI_MAX_POOL_SIZE=60 ACCOUNT_HIKARI_MAX_POOL_SIZE=40 MATCHING_HIKARI_MAX_POOL_SIZE=30 GATEWAY_HIKARI_MAX_POOL_SIZE=20 TAKER_FILL_WAIT_SECONDS=1200 TAKER_TRADE_WAIT_SECONDS=1200 ACCOUNT_SETTLEMENT_WAIT_SECONDS=1800 WS_FANOUT_USER_COUNT=5 WS_CAPTURE_TIMEOUT=2100 REPORT_FILE=docs/market-maker-stress-report-tier4.md SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange ./scripts/market-maker-stress.sh`
- 结果：2026-07-05 通过，报告为 `docs/market-maker-stress-report-tier4.md`，日志在 `/tmp/surprising-mm-stress.cqqRbO`。
- 范围：真实 order、matching、account、websocket、gateway provider；maker 报价走 gateway `/trading/batch`，普通用户 taker 逐笔走 gateway 单笔接口；WebSocket 前 5 个 taker 用户订阅私有 orders/positions。
- 关键计数：初始 maker 挂单 800 笔、连续刷新挂单 800 笔、普通用户 taker 订单 10000 笔、撮合成交 10000 笔、account 已结算 10000 笔、account Kafka max lag `3592` 且最终 lag 为 `0`，`openTradingOutbox=0`、`openAccountOutbox=0`，负账户余额、负产品余额、负逐仓保证金、预占超释放、账户/产品 deficit 和非法 OI 检查均为 `0`。
- 延迟：order accepted -> matching result 为 `count=10000 p50=512330.203ms p95=938055.321ms p99=1004577.478ms`；order accepted -> account processed trade 为 `count=10000 p50=728421.087ms p95=1214922.623ms p99=1291768.527ms`。单节点瓶颈仍集中在 order 入库/outbox、Kafka backlog、account 结算和 PostgreSQL 写路径。
- 运行说明：本轮还暴露了压测 fixture 通过 10004 次 admin HTTP 入金过慢的问题；脚本已把 maker/taker 初始资金改为 SQL 批量写账本、余额和 admin adjustment。该优化只影响测试前置入金，交易订单和结算仍走真实 gateway/Kafka/PostgreSQL 链路。

matching 单 JVM 多 consumer 回归：

- `MATCHING_CONSUMERS_PER_NODE=2` 时，报告显示 BTC/ETH 两个热 symbol 都落到同一个 matching consumer，`split=false`，因此增加 consumer 数没有真正让这两个 symbol 并行撮合。
- `MATCHING_CONSUMERS_PER_NODE=6` 时，BTC/ETH 分别落到不同 matching consumer，`split=true`；同规格 2000 taker/96 并发下 matching result 为 `count=2000 p50=88541.509ms p95=129873.085ms p99=139562.695ms`，account processed trade 为 `count=2000 p50=152770.313ms p95=229766.989ms p99=245160.798ms`，资金、outbox、deficit 和私有 `executionReports` 断言通过。
- 结论：matching 扩容要看热 symbol partition 是否真实分散，不能只看 consumer 数；当 matching 追平后，account 结算写库成为主要尾部瓶颈。

account available-balance fast path 与 metadata cache 回归：

- `docs/market-maker-stress-report-account-fastpath.md` 使用同规格 2000 taker/96 并发真实 gateway/order/matching/account/websocket 链路，只重新打包 account-provider；2000/2000 成交并 account 结算，资金、outbox、deficit 和前 10 个私有用户 `executionReports` 断言通过。account 单笔 processing avg 从上一轮同规格 `0.222556s` 降到 `0.198013s`，event lag avg/max 从 `59.555878s/110.615114s` 降到 `50.469238s/91.006244s`；端到端 account p50/p95 未整体下降，说明 fast path 降低了单笔结算开销，但写库尾部仍未消除。
- `docs/market-maker-stress-report-account-instrument-cache-smoke.md` 验证 instrument type、spot spec、contract spec、order fee snapshot 有界 JVM 只读缓存后的真实 provider 链路；100/100 taker 成交并 account 结算，account lag max `15` 且最终 `0`，前 5 个私有用户都收到各自 `ORDER_EVENT`、`MATCH_RESULT`、`TRADE`。
- 结论：可以缓存 symbol/version/order 维度不可变元数据和普通订单强平费上下文空结果；不能把余额、持仓、保证金或 deficit 放到 Redis/JVM 作为资金事实源。

代表性结果：

- L4 单节点真实链路：4 个做市商账号，BTC/ETH 双 symbol，每侧 50 档初始深度，5 轮刷新、128 并发，10000 笔普通用户 IOC taker 订单全部成交结算，前 5 个用户私有 WebSocket fanout 不串号。
- L3 单节点真实链路：4 个做市商账号，BTC/ETH 双 symbol，每侧 40 档初始深度，3 轮刷新、128 并发，6000 笔普通用户 IOC taker 订单全部成交结算。
- 该轮客户端提交吞吐约 `27.17 orders/s`，matching event-time 吞吐约 `9.26 trades/s`。
- account processed 平均 event lag 约 `93.2s`，最大约 `223.3s`；account processed trade p95 约 `703s`。这说明全链路瓶颈主要在 order 入库/outbox/Kafka/account 结算/PostgreSQL 写放大，不是 exchange-core 裸撮合。
- 裸撮合 benchmark 约 `20,597 trades/s`，但不包含 HTTP、PostgreSQL、Kafka、account 结算、WebSocket fanout，不能拿来代表交易系统吞吐。

已发现并修复过的瓶颈：

- 表计数器 `trading_sequences` 热点行导致高并发下单连接池耗尽，已改 PostgreSQL native sequence。
- order/matching outbox 曾在事务内等待 Kafka ACK，已改为事务外发布。
- liquidation outbox publisher 曾错误扫描整张 `trading_outbox_events`，可能发布 matching-owned depth/match 行并造成 depth 序列乱序；已限定 aggregate type，并加同 topic/key 最早行与 advisory lock 约束。
- account 热路径减少重复持仓行锁、不必要 deficit 写入、开仓/加仓时的 reduce-only 剪枝查询，对普通订单的强平费上下文负查询和 instrument/order 元数据做有界 JVM 缓存，并为跨仓普通负向资金变动增加 guarded available-balance fast path；这些缓存和 fast path 不缓存余额、持仓、保证金或 deficit 事实源。
- matching 压测报告新增 `Matching active symbol consumers`，用于判断热 symbol 是否真实分散到多个 matching consumer；`split=false` 时提高 concurrency 不等于提高撮合并行度。
- market-maker stress 已新增 PostgreSQL delta、Provider Prometheus、HTTP/Hikari/CPU/heap、Kafka lag、account settlement 指标采集，以及 provider Hikari 连接池和等待窗口调参。

## 当前压测不足

当前还不能声称完成生产级真实全链路压力测试，原因：

- `scripts/market-maker-stress.sh` 仍使用静态配置：`MM_DEPTH_LEVELS`、`MM_LEVEL_QUANTITY_STEPS`、`MM_REFRESH_LEVELS`、`MM_REFRESH_QUANTITY_STEPS`。`surprising-market-maker-provider` 已新增可选 WebSocket 本地订单簿和 REST 参考盘口兜底，并已有 30 秒真实 full-stack 样本和 90 秒 account 重启组合样本；但还没有在长时间真实进程压测中启用并记录生产级报告。
- 做市程序有 provider run-once/连续 8 秒 full-stack smoke、scheduled engine 60 秒自主报价 full-stack smoke、scheduled engine 账户服务重启故障 smoke、参考行情 WebSocket 30 秒 full-stack smoke、参考行情 WebSocket + account 重启组合 smoke、180 秒参考行情持续做市 smoke 和 L4 单节点压测；压测脚本现在支持用 `MM_REFRESH_CYCLES` / `MM_REFRESH_INTERVAL_SECONDS` 连续执行多轮 maker 刷新和 taker 流量，并已有 5 轮单节点样本，但还没有执行并记录生产级长时间、多节点、多 broker 样本。
- TWAP/Iceberg 已有真实 full-stack 功能 smoke，但还没有在高并发、多用户、多 symbol、长时间做市刷新和 provider 重启窗口下记录生产级算法单压测。
- 最新样本已经提升到 10000 笔 taker，但仍是单机 PostgreSQL、单 Kafka broker、本机 provider，且持续时间短。
- 已有小规模多用户私有 WebSocket fanout smoke，但还缺少大量长连接下的 fanout p50/p95/p99 延迟、断线清理和单连接故障隔离报告。
- 还缺少从“用户 REST 下单 -> order DB -> Kafka -> matching -> DB result/trades -> account DB -> risk/liquidation/insurance/ADL -> WebSocket 私有推送”的逐节点 p50/p95/p99 时延链路追踪。
- 强平、爆仓、ADL 在 full-stack smoke 中有功能覆盖，但还没有在高频做市和高并发用户流量持续运行时压测。

## 下一步压测方案

建议新增一个生产前压测任务，要求如下：

- 做市程序必须持续运行，不使用一次性 run-once 作为主要流量来源；本地脚本可先用 `MM_REFRESH_CYCLES` 放大为多轮连续流量。
- 做市深度、价差、每档量、刷新频率来自主流交易所订阅数据。当前最低实现已经有 Binance/OKX/Bybit WebSocket depth/books/orderbook 本地订单簿和 REST 快照兜底，并已有短时真实 provider 组合样本；生产前还需要把它启用到长时间真实进程压测，并按中位 spread、每档累计量和成交量分位数生成本地盘口报告。
- 每个阶段都输出延迟分布：REST 入参、order 入库、outbox 发布、matching 收到 command、matching result 落库、account 结算、risk 扫描、liquidation 下单、insurance/ADL、WebSocket fanout。
- 指标必须包含：PostgreSQL CPU/IO/WAL/locks/Hikari pending、Kafka lag/rebalance、provider JVM CPU/heap/gc、HTTP p95/p99、account settlement event lag、WebSocket fanout 延迟。
- 场景必须包含：普通下单、部分成交撤单、reduce-only、TP/SL 多档触发、mark price 过期、index source 断线、强平早于止损、account provider 重启、matching owner 重启、Kafka broker 故障；account provider 重启已经有 scheduled-engine 下的短时单节点样本，但仍需要长时间、多节点版本。

## 本轮验证

本轮未做不必要的全量 package。定向单元/集成测试只覆盖改动模块；需要真实 provider 证据时，full-stack smoke 使用 `BUILD_SERVICES=auto`，在 jar 未变化时跳过 Maven package：

```bash
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-liquidation-provider -am \
  -Dtest=LiquidationOrderRepositoryTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-trigger-provider,:surprising-order-provider,:surprising-account-provider -am \
  -Dtest=TriggerOrderServiceTest,TriggerOrderRepositoryTest,OrderServiceTest,AccountServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-order-provider,:surprising-matching-provider,:surprising-index-price-provider,:surprising-mark-price-provider -am \
  -Dtest=OrderValidatorTest,OrderMarkPriceRepositoryTest,MatchingServiceTest,IndexPriceCalculatorTest,MarkPriceServiceTest,MarkPriceCalculatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-trigger-provider -am \
  -Dtest=TriggerOrderServiceTest,TriggerOrderRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-order-provider,:surprising-matching-provider -am \
  -Dtest=OrderValidatorTest,OrderMarginMathTest,MatchingServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-account-provider -am \
  -Dtest=AccountServiceTest,AccountRepositoryTest,MatchTradeConsumerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-risk-provider,:surprising-liquidation-provider,:surprising-funding-provider,:surprising-adl-provider,:surprising-matching-provider,:surprising-order-provider,:surprising-trigger-provider -am \
  -Dtest=RiskServiceTest,LiquidationServiceTest,FundingRepositoryTest,AdlRepositoryTest,MatchingServiceTest,OrderValidatorTest,TriggerOrderServiceTest,OrderServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-index-price-provider,:surprising-mark-price-provider -am \
  -Dtest=IndexPriceCalculatorTest,MarkPriceCalculatorTest,MarkPriceServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-integration-test -am \
  -Dtest=PostLiquidationFundingInsuranceAdlIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

POSTGRES_PORT=55433 \
PAIR_COUNT=3 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=5 RUN_FAILURE_SCENARIOS=true \
BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=240 \
MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false \
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  ./scripts/full-stack-real-config-smoke.sh

START_INFRA=false POSTGRES_PORT=55433 \
START_PROVIDERS=true STOP_PROVIDERS=true RESET_STATE=true RUN_FAILURE_SCENARIOS=false \
BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=900 \
PAIR_COUNT=2 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=3 \
FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=true \
FULL_STACK_RISK_BACKGROUND_PAIR_COUNT=2 \
FULL_STACK_RISK_BACKGROUND_ROUNDS=4 \
FULL_STACK_RISK_BACKGROUND_CONCURRENCY=2 \
FULL_STACK_RISK_BACKGROUND_INTERVAL_SECONDS=1 \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false \
MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55433/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/full-stack-real-config-smoke.sh

START_INFRA=false POSTGRES_PORT=55433 \
PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 RUN_FAILURE_SCENARIOS=false \
BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=900 \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=true \
MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false \
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  ./scripts/full-stack-real-config-smoke.sh

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-trading-api,:surprising-order-provider,:surprising-account-provider -am \
  -Dtest=AlgoOrderServiceTest,OrderServiceTest,OrderRepositoryTest,AccountRepositoryTest,CancelAllAfterServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

POSTGRES_PORT=55432 KEEP_TMP=true RUN_FAILURE_SCENARIOS=false \
PAIR_COUNT=10 LOAD_CONCURRENCY=4 BOOK_DEPTH_LEVELS=20 \
  bash scripts/full-stack-real-config-smoke.sh

START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate \
START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true \
MM_ACCOUNT_COUNT=1 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 \
MM_REFRESH_CYCLES=2 MM_REFRESH_INTERVAL_SECONDS=1 \
TAKER_ORDER_COUNT=2 LOAD_CONCURRENCY=2 \
REPORT_FILE=docs/market-maker-continuous-smoke-report.md \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55433/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/market-maker-stress.sh

START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate \
START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true \
MM_ACCOUNT_COUNT=1 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 \
MM_REFRESH_CYCLES=2 MM_REFRESH_INTERVAL_SECONDS=1 \
TAKER_ORDER_COUNT=3 LOAD_CONCURRENCY=2 \
WS_FANOUT_USER_COUNT=3 WS_CAPTURE_TIMEOUT=900 \
REPORT_FILE=docs/market-maker-fanout-smoke-report.md \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55433/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/market-maker-stress.sh

START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate \
START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true \
MM_ACCOUNT_COUNT=4 MM_DEPTH_LEVELS=40 MM_REFRESH_LEVELS=12 \
MM_REFRESH_CYCLES=3 MM_REFRESH_INTERVAL_SECONDS=0 \
MAKER_BATCH_SIZE=5 MAKER_LOAD_CONCURRENCY=10 \
TAKER_ORDER_COUNT=2000 TAKER_QUANTITY_STEPS=2 LOAD_CONCURRENCY=128 \
ORDER_HIKARI_MAX_POOL_SIZE=60 ORDER_HIKARI_CONNECTION_TIMEOUT_MS=10000 \
ACCOUNT_HIKARI_MAX_POOL_SIZE=40 ACCOUNT_HIKARI_CONNECTION_TIMEOUT_MS=10000 \
MATCHING_HIKARI_MAX_POOL_SIZE=30 MATCHING_HIKARI_CONNECTION_TIMEOUT_MS=10000 \
GATEWAY_HIKARI_MAX_POOL_SIZE=20 GATEWAY_HIKARI_CONNECTION_TIMEOUT_MS=10000 \
TAKER_FILL_WAIT_SECONDS=900 TAKER_TRADE_WAIT_SECONDS=900 \
ACCOUNT_SETTLEMENT_WAIT_SECONDS=1200 \
WS_FANOUT_USER_COUNT=3 WS_CAPTURE_TIMEOUT=1500 \
REPORT_FILE=docs/market-maker-stress-report-tier3.md \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/market-maker-stress.sh

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-market-maker-provider -am \
  -Dtest=QuotePlannerTest,RestReferenceMarketProviderTest,MarketMakerServiceTest,MarketMakerApplicationYamlTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

corepack pnpm build

flutter analyze
flutter test
```

结果：

- trigger 定向测试通过，覆盖 TP/SL 放置时已有仓位、减仓方向、多档总待平量、active reduce-only 占用和 OCO 最大 sibling 容量校验。
- order/matching 定向测试通过，覆盖市价单缺少新鲜 mark price 拒绝、LIMIT 价格带缺少新鲜 mark price 拒绝，以及 matching 侧 `MARK_PRICE_UNAVAILABLE` 二次兜底。
- account 定向测试通过，覆盖账户结算、交易幂等、仓位模式切换全部前置条件、Kafka key/payload 不一致拒绝和资金/持仓事务回归。
- risk/liquidation/funding/adl/matching/order/trigger 定向测试通过，覆盖 HEDGE 仓位侧透传、ADL `target_position_side`、TP/SL 多档触发、TP/SL 在 mark price 不可用时不 claim、强平先清仓后 TP/SL 转 `TRIGGER_FAILED`，以及 mark price 不可用时 matching 拒绝。
- price 定向测试通过，覆盖指数源不足时不输出 index price、mark-price 不接受无价格或不可用状态 index event、健康指数价恢复后重新发布 mark price。
- liquidation repository 定向测试通过，覆盖清算 outbox 发布器不会认领 matching-owned depth/match outbox 行。
- `PostLiquidationFundingInsuranceAdlIntegrationTest` 通过，确认 ADL API 模型兼容性和强平后资金费/保险/ADL 集成链路未破坏。
- full-stack real-config smoke 通过，确认真实进程下仓位模式、TP/SL、撮合/account 恢复、资金费、强平、保险基金、ADL、WebSocket 和会计不变量可跑通；该结果仍是短时 smoke，不等同生产级长时间压测。
- 追加的最小 full-stack real-config smoke 通过，确认脚本在 provider jar 未变化时跳过 Maven package，并修正了持仓模式阻断用例对 TP/SL 可平仓位校验的前置条件，以及 `LOAD_CONCURRENCY=1` 的并发收尾边界。
- 追加的 index source fail-closed full-stack real-config smoke 通过，确认 provider jar 未变化时跳过 Maven package；指数源不足会让 mark-price 停止刷新，普通下单拒绝 `mark price unavailable`，健康指数输入恢复后 mark price 恢复发布，最终 WebSocket/accounting invariants 通过。
- 追加的清算/ADL 并发成交流 full-stack real-config smoke 通过，确认 `BUILD_SERVICES=false` 时只复用已更新 provider jar；强平、保险基金和 ADL 场景期间 8 组后台 maker/taker 成交全部被 matching/account 处理，最终 WebSocket depth 序列单调，公开/私有推送和会计不变量通过。
- 追加的 Cancel All After full-stack real-config smoke 通过，确认真实 gateway 路径设置账户级倒计时后可同时撤开放普通单和 pending TP/SL，且不会破坏余额、预占释放和 outbox 不变量。
- 追加的 trailing stop 后端定向测试和真实 gateway full-stack smoke 均通过，覆盖 `TRAILING_STOP` 参数校验、激活字段落库、mark-price 激活/最高水位更新/回调触发，以及生成 reduce-only 市价平仓单后通过 matching/account 成交结算。
- 追加的 TWAP/Iceberg 算法单定向测试和真实 gateway full-stack smoke 均通过，覆盖算法父单校验、子单调度、父子单取消、活动算法单阻断持仓模式切换，以及子单通过普通撮合/account/risk/WebSocket 链路后资金和 outbox 不变量仍成立。
- 连续做市刷新 smoke 通过，确认真实 order/matching/account/websocket/gateway 进程在 2 轮 maker 刷新和 taker 流量下可以完成成交、account 结算、Kafka lag 清零和 WebSocket 事件接收；该结果仍是小规模短时样本。
- market-maker provider 连续报价 full-stack smoke 通过，确认真实 market-maker provider 可通过 gateway `run-once` 连续生成 post-only 报价，普通用户 taker 经 gateway 单笔吃单后由 matching/account 结算；脚本已按最新 mark 动态同步测试价格，避免真实 index/mark 更新造成价格带误判。该结果仍是 8 秒短时样本。
- market-maker provider scheduled engine full-stack smoke 通过，确认资金就绪后打开 `engine.enabled=true` 时，provider 自己的 `@Scheduled` 调度可以持续生成 post-only 报价，普通用户 taker 经 gateway 单笔吃单后由 matching/account 结算；最新结果是 60 秒单节点样本，仍不是多节点、多 broker、长时间生产样本。
- market-maker provider scheduled engine 账户服务重启故障 smoke 通过，确认 account provider 停机期间用户 taker 仍能经 gateway/matching 成交，account provider 重启后可追平 5/5 笔成交结算；故障窗口内出现的 `CYCLE_FAILED` 是预期观测，恢复后资金、outbox、保险基金和 ADL 不变量均通过。该结果仍是短时单节点样本。
- 多用户 WebSocket fanout smoke 通过，确认真实 order/matching/account/websocket/gateway 进程下，前 3 个 taker 用户同时订阅私有 orders/positions 时都能收到各自事件且不会串号；该结果仍是小规模短时样本，不等同大量长连接压测。
- L3 单节点真实接口压测通过，确认 4 个 maker、40 档盘口、3 轮刷新、6000 笔普通用户 taker、128 并发都经 gateway/order/matching/account/websocket 链路执行；最终 6000 笔成交全部 account 结算，Kafka lag 回到 0，trading/account outbox 清空，无负余额、无非法 OI，前 3 个 taker 用户私有推送不串号。
- L4 单节点真实接口压测通过，确认 4 个 maker、50 档盘口、5 轮刷新、10000 笔普通用户 taker、128 并发都经 gateway/order/matching/account/websocket 链路执行；最终 10000 笔成交全部 account 结算，Kafka lag 回到 0，trading/account outbox 清空，负账户余额、负产品余额、负逐仓保证金、预占超释放、账户/产品 deficit 和非法 OI 均为 0，前 5 个 taker 用户私有推送不串号。
- market-maker provider 定向测试通过，确认参考盘口 WebSocket 本地订单簿、REST 快照兜底和报价规划可用：Binance/OKX/Bybit 风格 payload 可以转换成本地 ticks/steps，QuotePlanner 可按外部每档距离和数量生成本地 post-only 报价；配置默认关闭，未启用时保持原本本地报价模型。
- market-maker provider 参考行情 WebSocket full-stack smoke 通过，确认启用 `MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true` 后，真实 provider 会记录参考盘口采样、用流式深度校准本地 post-only 报价，并完成用户 taker/account 结算；最新结果是 30 秒单节点样本，仍不是多节点、多 broker、长时间生产样本。
- market-maker provider 参考行情 WebSocket + 账户服务重启故障 smoke 通过，确认启用外部参考盘口后，scheduled engine 仍可在 account provider 停机窗口内让用户 taker 经 gateway/matching 成交，重启后追平 10/10 笔 account 结算；本轮记录 Binance/OKX/Bybit WebSocket 样本，资金、outbox、保险基金和 ADL 不变量均通过。该结果仍是 90 秒单节点样本。
- Web 前端 `corepack pnpm build` 通过，覆盖多档 TP/SL 和持仓模式 UI/API TypeScript 表面并生成生产构建。
- Flutter `flutter analyze` 和 `flutter test` 通过，覆盖 iOS/Android 共用客户端壳、移动端交易基础逻辑、持仓模式 UI/API，以及新增的多档 TP/SL 条件单模型、提交面板、开放条件单列表和撤销入口。第一次把 `flutter analyze` 与 `flutter test` 并行执行时，`flutter test` 因 iOS ephemeral 文件锁竞争失败；随后单独重跑通过，结论以单独重跑结果为准；移动端 TP/SL 补丁后已再次单独重跑 `flutter test` 和 `flutter analyze` 通过。
