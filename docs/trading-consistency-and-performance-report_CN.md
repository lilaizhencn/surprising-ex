# 撮合、账户一致性与端到端性能评估报告

本文回答三个问题：

- 指数价/标记价不可用时，下单是否有限制，以及建议策略。
- 撮合中的账户余额、订单状态如何与数据库保持一致。
- 当前是否已经做过真实全链路压力测试，瓶颈和缺口在哪里。

## 结论摘要

- 永续合约下单当前已经按“标记价不可用则拒绝”的思路做了保护。市价单需要新鲜 mark price；LIMIT 订单默认也启用 mark price 价格带保护。撮合侧对市价单再次读取新鲜 mark price，取不到会拒绝 `MARK_PRICE_UNAVAILABLE`。
- 这套设计符合主流交易所的风险控制方向：Binance Futures 的价格过滤、market order 保护区间基于 mark price；OKX 使用价格区间和 mark price 降低异常成交/异常强平风险；Bybit 的强平以 mark price 为核心，且止损可能晚于强平触发。因此衍生品下单入口建议继续 fail closed。
- 账户余额、订单和持仓的一致性不是靠撮合内存状态兜底，而是靠 PostgreSQL 事务、幂等键、行锁/advisory lock、guarded update、transactional outbox 和 Kafka replay 共同保证。
- 止盈止损多档位已补放置时前置校验：锁定当前仓位后聚合 active reduce-only 平仓单和待触发 TP/SL 总待平量；同一 OCO 组合按最大 sibling 数量计入容量，避免止盈/止损双倍占用。
- HEDGE 仓位侧已经补充到关键资金链路的回归验证：撮合成交事件、风控事件/强平候选、强平下单、资金费扣款和 ADL 审计都保留 `positionSide`。ADL 事件新增 `target_position_side`，避免审计里只看到 LONG/SHORT 方向而无法区分 NET/LONG/SHORT 仓位桶。
- 已有压测包含真实 provider、PostgreSQL、Kafka、做市商铺单和普通用户 taker 流量。2026-07-04 又补跑了一次全 provider real-config smoke，覆盖仓位模式、HEDGE LONG/SHORT、TP/SL OCO、逐仓保证金、重启恢复、资金费、强平、保险基金、ADL 和 WebSocket/accounting invariants；同日又用 `MM_REFRESH_CYCLES=2` 记录了一次干净状态连续做市刷新 smoke，确认脚本可以连续跑 maker 刷新挂单和 taker 流量。当前又补了 market-maker provider 的可选 WebSocket 本地订单簿和 REST 参考盘口兜底：可以把 Binance/OKX/Bybit 风格深度消息映射成本地每档价格距离和数量。但现有压测仍不是生产级全链路压力测试：还没有执行启用流式参考盘口的长时间真实进程样本；规模和持续时间仍不够。

## 标记价/指数价不可用时的下单限制

### 当前代码行为

下单校验在 `OrderValidator` 中做了两层限制：

- 市价单：`validateMarket` 调用 `markPriceLookup.latestMarkPriceTicks(...)`，取不到新鲜 mark price 时返回 `mark price unavailable`。
- LIMIT 单：默认启用 `limit-price-protection-enabled`，`validateLimitPriceBand` 同样要求新鲜 mark price，并按方向限制价格带。

价格新鲜度来自 `OrderMarkPriceRepository`：只读取 `price_mark_ticks.event_time >= now() - maxAgeMs` 的 mark price。也就是说，不是表里有旧价格就能继续下单。

撮合侧也有二次保护：`MatchingService.effectivePriceTicks(...)` 对 MARKET order 读取新鲜 mark price，取不到会把撮合结果拒绝为 `MARK_PRICE_UNAVAILABLE`。这可以防止 order-provider 校验后到 matching 处理前 mark price 过期。

触发单侧目前只支持 `MARK_PRICE` 触发，`TriggerOrderRepository.markPriceTicks(...)` 取不到价格时不会 claim 触发单。实际效果是：mark price 不可用时不会误触发止盈止损。

### 主流交易所参考

- Binance USDS-M Futures 的 mark price 接口同时返回 `markPrice` 和 `indexPrice`；mark price stream 以 1s 或 3s 推送 mark/funding/index 数据。Binance common definition 里，Futures 的 `PERCENT_PRICE` filter 基于 mark price，市价单 notional 也使用 mark price，`exchangeInfo` 还暴露 market order 相对 mark price 的最大价差字段。
- OKX 永续文档说明，价格范围会基于现货市场和前一分钟期货价格动态调整，mark price 用于降低单笔异常成交导致的强平风险。
- Bybit 文档说明强平在 mark price 到达强平价时触发；也说明极端情况下 mark price 可能先触发强平，而 stop loss 还没有按用户选择的触发价来源触发。

参考链接：

- [Binance Mark Price](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Mark-Price)
- [Binance USDS-M Common Definition](https://developers.binance.com/docs/derivatives/usds-margined-futures/common-definition)
- [Binance Exchange Information](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Exchange-Information)
- [OKX Perpetual Futures Guide](https://www.okx.com/help/i-perpetual-swaps)
- [Bybit Liquidation Price](https://www.bybit.com/help-center/article/Liquidation-Price-USDT-Contract)
- [Bybit Liquidated Despite Stop Loss](https://www.bybit.com/help-center/article/Position-Liquidated-Despite-Having-Stop-Loss)

### 建议策略

衍生品交易应继续采用 fail closed：

- 新开仓、市价平仓、普通 LIMIT 下单：mark price 或指数来源不可用/过期时拒绝。
- 撤单：不依赖 mark price，必须保持可用。
- 风险降低类操作：建议只在显式“应急交易模式”中允许受控 reduce-only LIMIT，下单价仍需要有保护价带或管理员确认的应急 mark source。不要默认放开。
- 运营上需要给 symbol 增加 `CANCEL_ONLY` / `REDUCE_ONLY` / `HALT` 状态，行情不可用时由风控切到 cancel-only 或 reduce-only，而不是继续正常撮合。

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

最新 full-stack real-config smoke：

- 命令：`POSTGRES_PORT=55433 PAIR_COUNT=3 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=5 RUN_FAILURE_SCENARIOS=true BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=240 MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/full-stack-real-config-smoke.sh`
- 结果：2026-07-04 通过，日志在 `/tmp/surprising-full-stack-real-config.3qu3b9`。
- 范围：真实 PostgreSQL/Kafka 和 instrument、candlestick、index-price、mark-price、order、matching、account、risk、liquidation、funding、insurance、ADL、websocket、trigger、gateway、market-maker provider。
- 覆盖：持仓模式切换保护、HEDGE 同合约 LONG/SHORT、私有 `position`/`positionRisk` 的 `positionSide` 推送、币本位永续开平仓、现货结算、全成交、部分成交撤单、cancel-only/cancel-all、active reduce-only、TP/SL OCO、风控、matching 开放订单簿恢复、account 未结算成交重放、并发小负载、深盘口、资金费、逐仓风险恢复、强平、保险基金、ADL、market-maker run-once post-only 铺单、公开/私有 WebSocket 捕获和会计不变量。
- 关键计数：depth events `48`，mark events `233`，funding events `228`；HEDGE 用户 `LONG` 和 `SHORT` 各收到 2 条 position 与 12 条 positionRisk 推送；ADL 事件表核对为 `1|NET`，即真实 ADL 场景写入了 `target_position_side`。
- 运行说明：第一次使用默认 `5432` 时命中了本机 PostgreSQL/SSH 端口占用，导致 provider 连接到本机库并报 `role "surprising" does not exist`。改用 `POSTGRES_PORT=55433` 后通过，说明失败根因是本机端口冲突，不是业务 schema 或资金链路问题。

最新连续做市刷新 real-process smoke：

- 命令：`START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true MM_ACCOUNT_COUNT=1 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 MM_REFRESH_CYCLES=2 MM_REFRESH_INTERVAL_SECONDS=1 TAKER_ORDER_COUNT=2 LOAD_CONCURRENCY=2 REPORT_FILE=docs/market-maker-continuous-smoke-report.md SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55433/surprising_exchange JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" ./scripts/market-maker-stress.sh`
- 结果：2026-07-04 通过，报告为 `docs/market-maker-continuous-smoke-report.md`，日志在 `/tmp/surprising-mm-stress.qiFDht`。
- 范围：真实 order、matching、account、websocket、gateway provider；复用现有 Docker PostgreSQL/Kafka；`BUILD_SERVICES=false`，未重新打包 provider jar。
- 关键计数：初始 maker 挂单 8 笔、连续刷新挂单 8 笔、普通用户 taker 订单 4 笔、撮合成交 4 笔、account 已结算 4 笔、account Kafka 最终 lag 为 `0`。
- 运行说明：本轮使用 `RESET_STATE=true` 清空测试状态。早前用脏状态直接复跑时遇到旧开放挂单污染成交路径，因此连续压测要么使用干净 fixture，要么显式把已有开放订单、余额和持仓纳入测试前置条件，不能把未知状态下的旧流动性当作可靠基线。

代表性结果：

- 单节点真实链路：4 个做市商账号，BTC/ETH 双 symbol，每侧 20 档初始深度，64 并发，1000 笔普通用户 IOC taker 订单全部成交结算。
- 该轮客户端提交吞吐约 `68.12 orders/s`，matching event-time 吞吐约 `12.35 trades/s`。
- account processed 平均 event lag 约 `42.7s`，最大约 `70.4s`。这说明全链路瓶颈主要在 order 入库/outbox/Kafka/account 结算/PostgreSQL 写放大，不是 exchange-core 裸撮合。
- 裸撮合 benchmark 约 `20,597 trades/s`，但不包含 HTTP、PostgreSQL、Kafka、account 结算、WebSocket fanout，不能拿来代表交易系统吞吐。

已发现并修复过的瓶颈：

- 表计数器 `trading_sequences` 热点行导致高并发下单连接池耗尽，已改 PostgreSQL native sequence。
- order/matching outbox 曾在事务内等待 Kafka ACK，已改为事务外发布。
- account 热路径减少重复持仓行锁和不必要 deficit 写入。
- market-maker stress 已新增 PostgreSQL delta、Provider Prometheus、HTTP/Hikari/CPU/heap、Kafka lag 和 account settlement 指标采集。

## 当前压测不足

当前还不能声称完成生产级真实全链路压力测试，原因：

- `scripts/market-maker-stress.sh` 仍使用静态配置：`MM_DEPTH_LEVELS`、`MM_LEVEL_QUANTITY_STEPS`、`MM_REFRESH_LEVELS`、`MM_REFRESH_QUANTITY_STEPS`。`surprising-market-maker-provider` 已新增可选 WebSocket 本地订单簿和 REST 参考盘口兜底；`scripts/full-stack-real-config-smoke.sh` 也已有 `MM_REFERENCE_MARKET_ENABLED` / `MM_REFERENCE_MARKET_WEBSOCKET_ENABLED` 开关，但还没有在长时间真实进程压测中启用并记录报告。
- 做市程序有 provider 和 run-once smoke；压测脚本现在支持用 `MM_REFRESH_CYCLES` / `MM_REFRESH_INTERVAL_SECONDS` 连续执行多轮 maker 刷新和 taker 流量，并已有 2 轮短时干净状态样本，但还没有执行并记录生产级长时间样本。
- 1000 笔 taker、单机 PostgreSQL/Kafka、本机 provider 规模太小，且持续时间短。
- 还缺少从“用户 REST 下单 -> order DB -> Kafka -> matching -> DB result/trades -> account DB -> risk/liquidation/insurance/ADL -> WebSocket 私有推送”的逐节点 p50/p95/p99 时延链路追踪。
- 强平、爆仓、ADL 在 full-stack smoke 中有功能覆盖，但还没有在高频做市和高并发用户流量持续运行时压测。

## 下一步压测方案

建议新增一个生产前压测任务，要求如下：

- 做市程序必须持续运行，不使用一次性 run-once 作为主要流量来源；本地脚本可先用 `MM_REFRESH_CYCLES` 放大为多轮连续流量。
- 做市深度、价差、每档量、刷新频率来自主流交易所订阅数据。当前最低实现已经有 Binance/OKX/Bybit WebSocket depth/books/orderbook 本地订单簿和 REST 快照兜底；生产前还需要把它启用到长时间真实进程压测，并按中位 spread、每档累计量和成交量分位数生成本地盘口报告。
- 每个阶段都输出延迟分布：REST 入参、order 入库、outbox 发布、matching 收到 command、matching result 落库、account 结算、risk 扫描、liquidation 下单、insurance/ADL、WebSocket fanout。
- 指标必须包含：PostgreSQL CPU/IO/WAL/locks/Hikari pending、Kafka lag/rebalance、provider JVM CPU/heap/gc、HTTP p95/p99、account settlement event lag、WebSocket fanout 延迟。
- 场景必须包含：普通下单、部分成交撤单、reduce-only、TP/SL 多档触发、mark price 过期、index source 断线、强平早于止损、account provider 重启、matching owner 重启、Kafka broker 故障。

## 本轮验证

本轮未做不必要的全量 package。定向单元/集成测试只覆盖改动模块；需要真实 provider 证据时，full-stack smoke 使用 `BUILD_SERVICES=auto`，在 jar 未变化时跳过 Maven package：

```bash
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-trigger-provider -am \
  -Dtest=TriggerOrderServiceTest,TriggerOrderRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-risk-provider,:surprising-liquidation-provider,:surprising-funding-provider,:surprising-adl-provider,:surprising-matching-provider,:surprising-order-provider,:surprising-trigger-provider -am \
  -Dtest=RiskServiceTest,LiquidationServiceTest,FundingRepositoryTest,AdlRepositoryTest,MatchingServiceTest,OrderValidatorTest,TriggerOrderServiceTest,OrderServiceTest \
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

START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate \
START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true \
MM_ACCOUNT_COUNT=1 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 \
MM_REFRESH_CYCLES=2 MM_REFRESH_INTERVAL_SECONDS=1 \
TAKER_ORDER_COUNT=2 LOAD_CONCURRENCY=2 \
REPORT_FILE=docs/market-maker-continuous-smoke-report.md \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55433/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/market-maker-stress.sh

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-market-maker-provider -am \
  -Dtest=QuotePlannerTest,RestReferenceMarketProviderTest,MarketMakerServiceTest,MarketMakerApplicationYamlTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

npm run lint
```

结果：

- trigger 定向测试通过，覆盖 TP/SL 放置时已有仓位、减仓方向、多档总待平量、active reduce-only 占用和 OCO 最大 sibling 容量校验。
- risk/liquidation/funding/adl/matching/order/trigger 定向测试通过，覆盖 HEDGE 仓位侧透传、ADL `target_position_side`、TP/SL 多档触发、TP/SL 在 mark price 不可用时不 claim、强平先清仓后 TP/SL 转 `TRIGGER_FAILED`，以及 mark price 不可用时 matching 拒绝。
- account 定向测试通过，覆盖持仓模式切换全部前置条件：无非零持仓、无活动订单、无待触发条件单、无未结算成交、无活动保证金预占。
- `PostLiquidationFundingInsuranceAdlIntegrationTest` 通过，确认 ADL API 模型兼容性和强平后资金费/保险/ADL 集成链路未破坏。
- full-stack real-config smoke 通过，确认真实进程下仓位模式、TP/SL、撮合/account 恢复、资金费、强平、保险基金、ADL、WebSocket 和会计不变量可跑通；该结果仍是短时 smoke，不等同生产级长时间压测。
- 连续做市刷新 smoke 通过，确认真实 order/matching/account/websocket/gateway 进程在 2 轮 maker 刷新和 taker 流量下可以完成成交、account 结算、Kafka lag 清零和 WebSocket 事件接收；该结果仍是小规模短时样本。
- market-maker provider 定向测试通过，确认参考盘口 WebSocket 本地订单簿、REST 快照兜底和报价规划可用：Binance/OKX/Bybit 风格 payload 可以转换成本地 ticks/steps，QuotePlanner 可按外部每档距离和数量生成本地 post-only 报价；配置默认关闭，未启用时保持原本本地报价模型。
- Web 前端本轮没有改动；上一次 `npm run lint` 已在 2026-07-04 通过。
