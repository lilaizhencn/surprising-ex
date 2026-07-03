# 集成验证报告

本文记录 Surprising Exchange 永续合约交易链路当前已有的验证证据。
报告只把有测试、数据库 smoke 或构建门禁证明过的内容列为已验证。

## 相关报告

- [全链路功能测试用例清单](full-chain-test-plan_CN.md)：记录下单、撤单、仓位、逐仓/全仓、NET/LONG/SHORT、TP/SL、撮合、强平、ADL、WebSocket 和压测场景的验证状态。
- [撮合、账户一致性与端到端性能评估报告](trading-consistency-and-performance-report_CN.md)：说明 mark/index 不可用时的下单保护、撮合/账户/订单和数据库一致性，以及当前端到端压测证据和缺口。

## 验证门禁

在仓库根目录执行：

```bash
./scripts/integration-smoke.sh
mvn -q test
mvn -q -DskipTests package
```

如果本地可用 Docker，可以再执行真实 Kafka 交易 smoke：

```bash
./scripts/kafka-trading-smoke.sh
PAIR_COUNT=50 LOAD_CONCURRENCY=16 ./scripts/kafka-trading-load-smoke.sh
```

需要覆盖全部 provider 的真实进程链路时，执行：

```bash
KEEP_TMP=true ./scripts/full-stack-real-config-smoke.sh
```

日常调试时不要为了小改动反复重启所有服务。若服务已经启动且只需要做定向验证，使用已有进程并跳过状态重置和故障注入：

```bash
RESET_STATE=false START_PROVIDERS=false RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false ./scripts/full-stack-real-config-smoke.sh
```

这个增量模式用于人工定位问题，不替代干净状态下的全量 smoke。不要在复用 provider 时清库、删 Kafka topic 或清 RocksDB，否则服务内存状态会和底层状态不一致。

本地验证后清理构建产物：

```bash
mvn -q clean
find . -type d -name target -prune -print
```

清理成功后，`find` 不应输出任何 `target` 目录。

最近一次本地验证：

- `JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" mvn -q -pl :surprising-trigger-provider -am -Dtest=TriggerOrderServiceTest,TriggerOrderRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-04 通过，覆盖 TP/SL 放置时基于锁定实时仓位、active reduce-only 平仓单、待触发多档条件单的总待平量校验；同一 OCO 组合按 sibling 最大数量计入容量，不把止盈和止损双倍相加。
- `JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" mvn -q -pl :surprising-order-provider,:surprising-trigger-provider,:surprising-account-provider -am -Dtest=OrderServiceTest,TriggerOrderServiceTest,AccountServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-04 通过，覆盖 `ONE_WAY`/`HEDGE` 普通订单和条件单仓位侧准入、关闭 hedge 仓位腿自动规范化为 reduce-only、账户按仓位侧查询，以及既有 order/account/trigger service 回归。
- `JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" mvn -q -pl :surprising-account-provider -am -Dtest=AccountRepositoryTest,AccountServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-04 通过，覆盖持仓模式切换必须同时满足无非零持仓、无活动订单、无待触发条件单、`trading_match_trades` 中没有未被 account 处理的成交、无活动保证金预占。
- `JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" mvn -q -pl :surprising-risk-provider,:surprising-liquidation-provider,:surprising-funding-provider,:surprising-adl-provider,:surprising-matching-provider,:surprising-order-provider,:surprising-trigger-provider -am -Dtest=RiskServiceTest,LiquidationServiceTest,FundingRepositoryTest,AdlRepositoryTest,MatchingServiceTest,OrderValidatorTest,TriggerOrderServiceTest,OrderServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-04 通过，覆盖 HEDGE `positionSide` 在撮合成交、风控持仓事件和强平候选、强平 reduce-only 系统单、资金费、ADL 审计事件中的透传，以及 TP/SL 多档触发、mark price 不可用时不 claim 条件单、强平先清仓后 TP/SL 转 `TRIGGER_FAILED`，以及撮合侧 `MARK_PRICE_UNAVAILABLE` 拒绝。
- `JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" mvn -q -pl :surprising-integration-test -am -Dtest=PostLiquidationFundingInsuranceAdlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-04 通过，确认新增 `adl_events.target_position_side` 和 ADL API `targetPositionSide` 响应字段后，强平/资金费/保险/ADL 集成测试仍兼容。
- `POSTGRES_PORT=55433 PAIR_COUNT=3 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=5 RUN_FAILURE_SCENARIOS=true BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=240 JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/full-stack-real-config-smoke.sh`：2026-07-04 通过，日志保留在 `/tmp/surprising-full-stack-real-config.3qu3b9`。该轮因为 provider jar 未变化跳过 Maven package，随后覆盖真实 PostgreSQL/Kafka 和全部 provider 进程、仓位模式切换、HEDGE LONG/SHORT 私有 WebSocket 断言、TP/SL OCO、逐仓保证金、部分成交撤单、matching 开放订单簿恢复、account 未结算成交重放、资金费、强平、保险基金、ADL、market-maker run-once 铺单，以及 WebSocket/accounting invariants。最终捕获 depth events `48`、mark events `233`、funding events `228`，并核对 `adl_events.target_position_side` 为 `1|NET`。第一次使用默认 PostgreSQL 端口时命中本机端口冲突；改用 `POSTGRES_PORT=55433` 隔离 Docker PostgreSQL 后通过。
- `START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true MM_ACCOUNT_COUNT=1 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 MM_REFRESH_CYCLES=2 MM_REFRESH_INTERVAL_SECONDS=1 TAKER_ORDER_COUNT=2 LOAD_CONCURRENCY=2 REPORT_FILE=docs/market-maker-continuous-smoke-report.md SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55433/surprising_exchange JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" ./scripts/market-maker-stress.sh`：2026-07-04 通过，没有重新打包 provider jar，日志保留在 `/tmp/surprising-mm-stress.qiFDht`，报告为 `docs/market-maker-continuous-smoke-report.md`。该干净状态样本复用现有 Docker PostgreSQL/Kafka，启动真实 order/matching/account/websocket/gateway provider，执行 2 轮做市商刷新挂单，4 笔普通用户 taker 订单全部成交，account 全部结算，account Kafka 最终 lag 为 `0`，余额无负数、OI 约束有效，并收到公开 depth 和私有 order/position WebSocket 事件。
- `JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" mvn -q -pl :surprising-market-maker-provider -am -Dtest=QuotePlannerTest,RestReferenceMarketProviderTest,MarketMakerServiceTest,MarketMakerApplicationYamlTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-04 通过，覆盖 market-maker 可选 REST 参考盘口校准。Binance depth、OKX books、Bybit V5 orderbook 风格 payload 可以转换成本地 ticks/steps；QuotePlanner 会按参考盘口每档距离和数量生成本地报价，同时保留 post-only、价格偏离、数量和库存上限保护；该功能在 `application.yml` 中默认关闭。
- `../surprising-ex-web` 中执行 `npm run lint`：2026-07-04 通过，覆盖 Web 交易终端持仓模式切换和条件单管理相关 TypeScript 表面。
- `../surprising-client` 中执行 `flutter analyze` 和 `flutter test`：2026-07-04 通过，覆盖 Flutter 共用 iOS/Android 客户端壳、交易模式到账户类型映射、价格 tick 转换、行情 reducer，以及移动端持仓模式 UI/API 集成的静态分析。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-instrument-provider,:surprising-index-price-provider,:surprising-mark-price-provider -am -Dtest=InstrumentKafkaConfigurationTest,IndexKafkaProducerConfigurationTest,MarkKafkaConfigurationTest,InstrumentValidatorTest,IndexPriceCalculatorTest,MarkPriceCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-02 通过，覆盖 instrument/index/mark 事件 Kafka producer 可靠发送配置、mark live-feed consumer 配置、instrument 校验和价格计算数学。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-index-price-provider,:surprising-mark-price-provider -am -Dtest=IndexKafkaProducerConfigurationTest,MarkKafkaConfigurationTest,IndexPriceCalculatorTest,MarkPriceCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-02 通过，覆盖 index/mark price Kafka producer 可靠发送配置、live-feed mark 输入 consumer 配置和价格计算数学。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-mark-price-provider -am -Dtest=MarkKafkaConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-02 通过，覆盖 mark-price Kafka producer 的可靠发送配置，以及 live-feed 输入 consumer 的可配置 concurrency/max-poll-records、关闭 auto commit、record ack 和 cooperative-sticky rebalance。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-matching-provider,:surprising-risk-provider,:surprising-liquidation-provider,:surprising-insurance-provider -am -Dtest=MatchingKafkaConfigurationTest,RiskKafkaConfigurationTest,LiquidationKafkaConfigurationTest,InsuranceKafkaConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-02 通过，覆盖 matching、risk、liquidation、insurance consumer 的 Kafka `max-poll-records` 可配置。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-websocket-provider -am -Dtest=WebSocketApplicationYamlTest,WebSocketKafkaConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-02 通过，覆盖 WebSocket 每节点稳定 consumer group 默认值、可配置 `max-poll-records` 和 Kafka fanout consumer 配置。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-account-provider -am -Dtest=AccountSettlementMetricsContextTest,MatchTradeConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-02 通过，覆盖 account 结算指标注册、metrics bean 的 Spring 构造注入、processed/duplicate/failed consumer 指标，以及 symbol-key 拒绝路径指标。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-matching-provider,:surprising-websocket-provider -am -DskipTests package`：2026-07-02 通过；matching 和 WebSocket provider 运行时已补充 Bean Validation provider。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-matching-provider -am test`：2026-07-02 通过，覆盖 exchange-core 撮合封装、订单簿增量输出、撮合恢复、消费者 fail-fast/restart、outbox 幂等和持久化副作用。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-integration-test -am test`：2026-07-02 07:11 +08:00 通过；本轮先修复了集成测试内存账户 adapter 对新版 `updatePosition(..., previousSignedQuantitySteps, ...)` repository overload 的覆盖。该门禁覆盖 order -> matching -> account -> risk -> liquidation -> funding -> insurance -> ADL 的 Java 集成链路，包括逐仓保证金追加/减少、account match-trade 兼容性和按实际可收 collateral 封顶收取强平费。
- `./scripts/integration-smoke.sh`：2026-07-02 通过，向临时 PostgreSQL 导入 `init.sql`，并验证 trading/account/risk/liquidation/funding/insurance/ADL 的 schema 和跨表不变量。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/matching-engine-benchmark.sh 100000 10000`：2026-07-02 07:08 +08:00 通过，100,000 笔 taker IOC 全部成交；在不包含 HTTP、Kafka、PostgreSQL、account 结算和 WebSocket fanout 的情况下，本机 `ExchangeCoreEngine.submit(...)` 封装层约 20,597.12 trades/s。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" BUILD_SERVICES=true KEEP_TMP=true ./scripts/market-maker-stress.sh`：2026-07-02 通过，报告在 `docs/market-maker-stress-report.md`，日志在 `/tmp/surprising-mm-stress.12qj5b`；覆盖真实 order/matching/account/websocket/gateway provider 进程、4 个做市商账号、BTC/ETH 双边盘口、做市商并发刷新挂单、1,000 个普通用户通过 gateway 下 IOC 吃单、account 幂等结算、无负余额、OI 约束和 WebSocket 盘口/私有推送接收。最近一次运行采集到 account Prometheus 结算指标：processed `1000`、duplicate `0`、failed `0`、平均处理耗时 `0.299843s`、平均 event lag `42.707596s`、account Kafka 最终 lag `0`、客户端提交吞吐 `68.12 orders/s`、matching event-time 吞吐 `12.35 trades/s`。
- `EXTRA_MATCHING_NODES=1 EXTRA_ACCOUNT_NODES=1 EXTRA_WEBSOCKET_NODES=1 TAKER_ORDER_COUNT=80 MM_DEPTH_LEVELS=6 MM_REFRESH_LEVELS=2 LOAD_CONCURRENCY=16 BUILD_SERVICES=false KEEP_TMP=true REPORT_FILE=docs/market-maker-multinode-stress-report.md JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/market-maker-stress.sh`：2026-07-02 通过，日志在 `/tmp/surprising-mm-stress.KyKVuu`；覆盖本机水平扩展拓扑：2 个 matching provider、2 个 account provider、2 个 WebSocket provider、1 个 order provider、1 个 gateway provider。Kafka group 检查到 `surprising.perp.order.commands.v1` 上有 4 个 matching consumer，`surprising.perp.match.trades.v1` 上有 4 个 account consumer，并且额外 WebSocket 节点使用独立 fanout group。本轮 80 笔普通用户 taker 订单全部成交并结算，account 指标 processed `80`、duplicate `0`、failed `0`，account Kafka 最终 lag `0`，无负余额、无非法 OI 行，两个 WebSocket 节点都收到 depth 事件。
- `EXTRA_MATCHING_NODES=1 EXTRA_ACCOUNT_NODES=1 EXTRA_WEBSOCKET_NODES=1 ACCOUNT_NODE_FAILURE_DURING_SETTLEMENT=true TAKER_ORDER_COUNT=80 MM_DEPTH_LEVELS=6 MM_REFRESH_LEVELS=2 LOAD_CONCURRENCY=16 BUILD_SERVICES=false KEEP_TMP=true REPORT_FILE=docs/market-maker-account-failover-report.md JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/market-maker-stress.sh`：2026-07-02 通过，日志在 `/tmp/surprising-mm-stress.JAOAGZ`；在普通用户 match trades 已写入后停止 `account-2`。account consumer group 收敛到剩余 account 节点，account Kafka 最终 lag 为 `0`，80 笔普通用户 taker 成交全部写入 `account_processed_trades`，无负余额、无非法 OI 行，两个 WebSocket 节点都收到 depth 事件。被停止节点的 Prometheus 指标不可抓取，因此本轮 failover 报告以数据库结算计数作为权威完成证据。
- `EXTRA_MATCHING_NODES=1 EXTRA_ACCOUNT_NODES=1 EXTRA_WEBSOCKET_NODES=1 TAKER_ORDER_COUNT=20 MM_DEPTH_LEVELS=4 MM_REFRESH_LEVELS=1 LOAD_CONCURRENCY=8 BUILD_SERVICES=true KEEP_TMP=true REPORT_FILE=docs/market-maker-topology-observability-report.md JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/market-maker-stress.sh`：2026-07-02 通过，日志在 `/tmp/surprising-mm-stress.sNnIg5`；验证新增显式 Kafka `client.id` 配置后，matching/account 节点在 consumer group 中可稳定识别。报告中能看到 `matching-1`、`matching-2`、`account-1`、`account-2` client id，并能把 BTC-USDT、ETH-USDT 的 `order.commands` partition 映射到具体 matching client id。本轮也完成 20 笔普通用户 taker 成交和结算，无负余额、无非法 OI 行。
- `EXTRA_MATCHING_NODES=1 EXTRA_ACCOUNT_NODES=1 EXTRA_WEBSOCKET_NODES=1 MATCHING_NODE_FAILURE_AFTER_OPEN_BOOK=true TAKER_ORDER_COUNT=20 MM_DEPTH_LEVELS=4 MM_REFRESH_LEVELS=1 LOAD_CONCURRENCY=8 BUILD_SERVICES=false KEEP_TMP=true REPORT_FILE=docs/market-maker-matching-failover-report.md JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/market-maker-stress.sh`：2026-07-02 通过，日志在 `/tmp/surprising-mm-stress.70mzHb`；识别 BTC-USDT 的 `order.commands` partition 14 由 `matching-2` 持有，在 64 笔 maker 挂单已接受后停止该 provider，再重启它，并验证重启后的 matching 从 PostgreSQL 恢复 64 笔开放订单，随后额外 taker 单能吃到故障前 maker 流动性。最终检查显示总成交 21 笔、account 已处理 21 笔、普通用户 taker 成交 20 笔、matching failover 成交 1 笔、无负余额、无非法 OI 行、account Kafka 最终 lag 为 `0`，两个 WebSocket 节点都收到 depth 事件。
- `TAKER_ORDER_COUNT=4 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 LOAD_CONCURRENCY=2 BUILD_SERVICES=false KEEP_TMP=true REPORT_FILE=docs/market-maker-observability-smoke-report.md JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/market-maker-stress.sh`：2026-07-02 通过，日志在 `/tmp/surprising-mm-stress.9P0uvM`；验证做市商压测报告新增的可观测性字段。本轮 4 笔普通用户 taker 成交全部结算，报告采集到 PostgreSQL 并发阶段 delta、关键表行数、Provider Prometheus HTTP/Hikari/CPU/heap 摘要、Kafka lag 和 account settlement 指标；account processed `4`、duplicate `0`、failed `0`，account Kafka 最终 lag 为 `0`，无负余额、无非法 OI 行，并收到 WebSocket depth/private 事件。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-order-provider,:surprising-trigger-provider,:surprising-account-provider -am -Dtest=OrderServiceTest,TriggerOrderServiceTest,AccountServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-07-02 通过，覆盖显式 `positionSide` API 边界：`ONE_WAY` 接受/默认 `NET` 并拒绝 hedge side，`HEDGE` 要求 `LONG/SHORT`，并把所选仓位侧传入普通订单、条件单和账户持仓查询。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-account-provider -am test`：2026-07-02 通过，覆盖账户结算、幂等、逐仓保证金、强平费收取、Kafka 可重放配置、match-trade fetch size 配置化，以及 account settlement 在 processed/duplicate/failed 三类结果上的 Prometheus 指标。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-order-provider,:surprising-account-provider,:surprising-instrument-provider -am -Dtest=OrderMarginRepositoryTest,OrderValidatorTest,AccountRepositoryTest,AccountServiceTest,InstrumentValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，覆盖动态平台 OI 持仓量限额、账户仓位更新按 long/short 方向维护 symbol OI、LIMIT 价格带保护、手动逐仓持仓保证金调整，以及 instrument 对 OI 限额配置的校验。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-account-provider -am -Dtest=AccountServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，覆盖手动逐仓持仓保证金调整成功后写持仓更新 outbox 触发。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-order-provider,:surprising-trigger-provider -am -Dtest=OrderServiceTest,OrderRepositoryTest,TriggerOrderServiceTest,TriggerOrderRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，覆盖单向净持仓下的保证金模式切换约束、共享 `userId + symbol` PostgreSQL advisory lock、另一个保证金模式活跃时普通订单拒绝、reduce-only 风险降低订单绕过切换拒绝，以及另一个保证金模式活跃时条件单拒绝。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-order-provider -am -Dtest=OrderValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，覆盖除现有市价保护外的按方向 LIMIT 订单 mark price 价格带保护。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-trigger-provider,:surprising-gateway-provider,:surprising-order-provider,:surprising-account-provider,:surprising-risk-provider,:surprising-liquidation-provider,:surprising-funding-provider,:surprising-integration-test -am test`：通过，覆盖 TP/SL 条件推导、`clientTriggerOrderId` 幂等、OCO 组持久化和 sibling 取消 SQL、gateway trigger 路由、mark price payload 解析、Kafka consumer 可重放配置、`FOR UPDATE SKIP LOCKED` 抢占触发单、生成 reduce-only 平仓单和核心 Java 交易链路。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-account-provider,:surprising-integration-test -am test`：通过，覆盖 account provider 逐仓持仓保证金追加/减少、持仓保证金调整 reference 幂等、减少保证金前的新鲜风险快照校验、手动持仓保证金调整拒绝 `CROSS`，以及 Java 集成链路。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-order-provider,:surprising-account-provider,:surprising-risk-provider,:surprising-liquidation-provider,:surprising-funding-provider,:surprising-integration-test -am test`：通过，覆盖用户杠杆配置、按风险档位冻结订单保证金、自动 VIP 手续费档位、手续费 source 优先级、手续费快照、账户结算、风控、强平、资金费和 Java 集成链路。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-liquidation-provider -am test`：通过，覆盖强平候选复核、分阶段 sizing、reduce-only 前置撤单、破产价/接管价/预估强平费审计字段、实时仓位和最新风险快照仓位不一致时取消候选，以及撮合结果后候选生命周期更新的 fail-fast 行为。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-risk-provider -am test`：通过，覆盖风控账户组 keyset 分页扫描、账户持仓事件触发风控扫描、账户组级 mark price 新鲜度和多节点扫描租约行为。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-integration-test -am test`：2026-07-02 07:11 +08:00 通过，覆盖 order -> matching -> account -> risk -> liquidation -> funding -> insurance -> ADL 的 Java 集成链路。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q test`：2026-07-02 07:12 +08:00 通过，覆盖当前全部 Maven 模块测试。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -DskipTests package`：2026-07-02 07:13 +08:00 通过，验证集成测试 adapter 修复和 account 热路径优化后，当前全部模块仍可完整打包。
- `PAIR_COUNT=3 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=5 RUN_FAILURE_SCENARIOS=true BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=240 JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./scripts/full-stack-real-config-smoke.sh`：2026-07-02 通过，日志保留在 `/tmp/surprising-full-stack-real-config.pqln9e`，覆盖真实 PostgreSQL/Kafka、instrument、candlestick、index-price、mark-price、order、matching、account、risk、liquidation、funding、insurance、ADL、websocket、trigger、gateway provider，以及 full fill、逐仓保证金追加/安全减少/超额减少拒绝、部分成交撤单、撤单、reduce-only 平仓、TP/SL OCO LIMIT IOC 触发执行、provider 故障恢复、小批量并发、深盘口、WebSocket 公开/私有推送断言、资金费、强平、保险基金和 ADL 链路。本轮先在存在开放委托时重启 matching，并验证恢复后的订单簿能继续撮合延迟到达的 taker 单；随后在 match trades 未结算时重启 account，并验证 Kafka 重放后双方持仓正确落账。最终 PostgreSQL 检查显示撮合成交 15 笔、account 处理成交 15 笔、无负余额、无未清账户缺口、强平单 1 笔且已成交、保险基金台账 4 条、ADL 事件 1 条且剩余缺口为 0、并发 load maker 成交 3 笔、并发 load taker 成交 3 笔。WebSocket 捕获到 38 条 depth、24 条 mark、133 条 funding，以及测试用户的私有 order/match/position 事件。

当前脚本覆盖补充：当 `RESET_STATE=true` 时，`scripts/full-stack-real-config-smoke.sh` 现在也会启动 `surprising-market-maker-provider`，并手动调用私有 `run-once` API。该 smoke 会断言做市商 provider 生成 12 笔 post-only 报价，并验证 provider 生成的报价在 order event 和 matching result 中都保留同一个 traceId。

## 证据矩阵

| 范围 | 当前证据 |
| --- | --- |
| Schema 初始化 | `scripts/integration-smoke.sh` 会启动临时 PostgreSQL 并导入根目录 `init.sql`。 |
| 真实 Kafka 交易 smoke | `scripts/kafka-trading-smoke.sh` 会启动 Docker Compose PostgreSQL/Kafka，把 `init.sql` 导入隔离的 smoke 数据库，创建 Kafka topics，启动 order/matching/account 三个 provider，通过 REST 给两个用户入金并提交可成交的对手单，等待 exchange-core 撮合和 account Kafka 结算完成，然后把同一条 match-trade payload 重新发布到 Kafka，验证账户持仓和 processed-trade 行数不变化。`scripts/kafka-trading-load-smoke.sh` 会额外启动 WebSocket，订阅 depth/private 频道，并检查全部成交、部分成交后撤单、只撤单、全部撤单、并发 maker/taker 用户、持仓正确性、盘口深度推送和私有订单/撮合/持仓推送接收。`scripts/full-stack-real-config-smoke.sh` 会启动 instrument、candlestick、index-price、mark-price、order、matching、account、risk、liquidation、funding、insurance、ADL、websocket、trigger、gateway、market-maker 全部 provider；脚本现在会订阅私有 `accountRisk` 和 `positionRisk`，并断言测试用户能收到后端计算的风险/PnL 推送；最近一次覆盖了仓位模式切换、HEDGE LONG/SHORT WebSocket 断言、TP/SL OCO、有开放委托时重启 matching、match trades 未结算时重启 account、资金费、强平、保险基金、ADL，以及可重放结算。`scripts/market-maker-stress.sh` 会启动真实 order/matching/account/websocket/gateway provider 进程，执行做市商盘口深度和普通用户 taker 并发流；它现在支持通过 `MM_REFRESH_CYCLES` / `MM_REFRESH_INTERVAL_SECONDS` 连续执行多轮 maker 刷新和 taker 流量，也支持额外启动本机 matching/account/WebSocket 节点、稳定 Kafka client-id 拓扑报告、matching symbol partition owner 报告、开放订单簿恢复后的 matching owner 重启验证、account 节点故障检查，以及 PostgreSQL delta 和 Provider Prometheus HTTP/Hikari/CPU/heap 摘要等报告可观测性字段。market-maker provider 现在也支持可选 REST 参考盘口校准，可以在开启后用 Binance/OKX/Bybit 风格盘口快照驱动本地报价档距和数量。最近一次单节点压测完成 1,000 笔普通用户订单成交和结算；最近一次干净状态连续刷新 smoke 执行 2 轮 maker 刷新并完成 4 笔普通用户 taker 成交结算，最终 Kafka lag 为 0；最近一次本机多节点压测在 2 matching、2 account、2 WebSocket provider 下完成 80 笔普通用户订单成交和结算；最近一次 account failover 在 match trades 写入后停掉 1 个 account 节点，仍完成 80 笔 taker 成交结算且最终 Kafka lag 为 0；最近一次 matching failover 停掉 BTC owner、重启后恢复 64 笔开放订单，并成功吃到故障前流动性；最近一次 observability smoke 用 4 笔真实进程成交验证了新增报告字段。最近一次干净状态 full-stack 运行已经通过，覆盖真实 provider 进程、TP/SL OCO 触发执行、确定性的 mark price 触发事件、mark 新鲜度刷新、并发下单、深度盘口 fanout、资金费、强平、保险基金、ADL、provider 重启恢复，以及 WebSocket/账户一致性断言。 |
| long 定点模型 | 订单、撮合、账户、风控、强平、资金费、保险基金、ADL 核心链路使用 ticks、steps、ppm、asset units；`CoreFixedPointArchitectureTest` 会拒绝这些 main Java 路径里的 `BigDecimal`、`double`、`float`，相关数学测试覆盖溢出敏感逻辑，也覆盖会改变容量或风险的 checked 聚合。 |
| 共享合约公式 | `PerpetualContractMathTest` 验证共享的线性/反向 notional、未实现 PnL、每 step notional、初始保证金、维持保证金和溢出行为；account、risk、funding、liquidation、ADL 共同使用这套公式。 |
| instrument version 锁定 | `InstrumentKafkaConfigurationTest` 验证 instrument 变更事件 Kafka producer 可靠发送配置；`integration-smoke.sh` 把 BTC-USDT 当前版本切到 v2，并验证已有 v1 持仓仍用 v1 规则计算风险和资金费。 |
| 下单校验 | `TraceContextTest`、`TraceIdFilterTest`、`OrderValidatorTest`、`OrderMarginMathTest`、`LeverageServiceTest`、`ReduceOnlyValidatorTest`、`OrderServiceTest`、`OrderRepositoryTest`、`OrderMarginRepositoryTest`、`OrderMarkPriceRepositoryTest`、`MarketPriceProtectionTest` 覆盖 traceId 清洗、请求作用域清理、订单事件和 command payload 传递、显式 `CROSS` 和 `ISOLATED` 保证金模式传递、`ONE_WAY` 下 `NET` 强制、`HEDGE` 下 `LONG/SHORT` 必填、关闭所选 hedge 仓位腿时自动规范化为 reduce-only、所选 `positionSide` 传入订单/command/保证金冻结、用户杠杆配置、instrument 最大杠杆校验、按风险档位在冻结保证金前拒绝超限杠杆、基于 `userId + symbol` advisory lock 的保证金模式切换约束，以及对持仓/普通订单/条件单的跨模式冲突检查、订单边界、币本位 notional、市价可成交区间 notional 上限、按方向 LIMIT 订单 mark price 价格带保护、新鲜 mark price 校验、long-only 初始保证金公式，包括线性合约市价 SELL 按上边界冻结抵押、包含同方向未完成委托和动态平台 OI 限额的投影持仓校验、按保证金模式和仓位侧隔离且带 checked 待平聚合的 reduce-only 可平量、限定范围的 `clientOrderId` 幂等不重复冻结、以及保证金冻结 guarded update。 |
| 交易手续费档位和快照 | `TradingFeeServiceTest`、`OrderFeeRepositoryTest`、`FeeTierServiceTest`、`TradeFeeMathTest`、`AccountServiceTest` 验证 instrument 默认 maker/taker 费率、用户全局和单 symbol 覆盖、风控/人工/活动/做市商费率优先于自动 VIP 的 source 优先级、按 30 日成交名义价值和账户资产估值自动刷新 VIP 档位、自动生成用户全局费率的激活/禁用、下单入口不可变手续费快照，以及账户按该快照写手续费扣款/返佣 ledger。 |
| 止盈止损条件单 | `TriggerOrderServiceTest`、`TriggerOrderRepositoryTest`、`MarkPriceTriggerParserTest`、`TriggerKafkaConfigurationTest` 验证 TP/SL 触发条件推导、限定用户范围的 `clientTriggerOrderId` 幂等、可选 `ocoGroupId` 持久化、`ONE_WAY` 下 `NET` 强制、`HEDGE` 下 `LONG/SHORT` 平仓侧校验、放置时必须已有可平仓位且方向必须减仓、active reduce-only 平仓单和待触发多档条件单的总待平量聚合校验、同一 OCO 组合按最大 sibling 数量计入容量、`positionSide` 传入生成的 reduce-only 平仓单、与 order-provider 共享 advisory lock 命名空间的保证金模式切换冲突检查、claim 语句中的 OCO sibling 自动取消、mark price 事件解析、保持 key 语义的 Kafka consumer 配置、`FOR UPDATE SKIP LOCKED` 抢占到期触发单、通过 order-provider 生成 reduce-only 平仓单、生成订单被拒后转为 `TRIGGER_FAILED`，以及 stale `TRIGGERING` 重置重试。 |
| 跨模块 Java 链路 | `PerpetualTradingChainIntegrationTest` 使用真实 exchange-core 和内存 adapter，验证下单 -> 撮合 -> 账户结算 -> U 本位线性和币本位反向用户 reduce-only 主动平仓、币本位保证金释放和已实现 PnL、市价单风险边界冻结 -> 更优价格成交 -> 差额保证金释放、线性合约市价 SELL 在吃到高于 mark 的买单前已按上边界足额冻结、逐仓下单 -> exchange-core 撮合 -> 逐仓持仓保证金迁移 -> 通过 account service 手动追加/减少逐仓保证金、用户撤单 -> exchange-core cancel -> 预冻结保证金释放、部分成交 -> 撤剩余单 -> 只释放未成交委托保证金且保留已迁移仓位保证金，以及风险候选 -> 强平 reduce-only 市价单 -> 强平成交结算，并验证按实际可收 collateral 封顶收取强平费和写出 account outbox 事件。`PostLiquidationFundingInsuranceAdlIntegrationTest` 验证资金费结算 -> 账户余额/保证金迁移、保险基金亏损覆盖、强平费事件重放幂等、ADL 剩余亏损转移。 |
| exchange-core 撮合 | `ExchangeCoreEngineRecoveryTest` 验证 DB 开放订单恢复到 exchange-core，并拒绝交叉订单簿恢复。`scripts/matching-engine-benchmark.sh` 运行本机封装层 benchmark；最近 100,000 笔成交样本约 20,597.12 trades/s，未包含 HTTP/Kafka/PostgreSQL/account/WebSocket 开销。 |
| matching fail-fast 恢复 | `MatchingCommandConsumerTest`、`MatchingPartitionAssignmentGuardTest`、`MatchingResultRepositoryTest`、`MatchingOutboxRepositoryTest` 验证已解析 command 处理失败会请求 matcher 重启后再让 Kafka 重放；matcher 处理过命令后遇到 partition reassignment/lost 会重启；撮合结果/成交/outbox 写入冲突会 fail-fast；无法解析的 payload 不会误标记 partition 已处理。 |
| Kafka 运行语义 | `TradingOrderKafkaConfigurationTest`、`MatchingKafkaConfigurationTest`、`AccountKafkaConfigurationTest`、`RiskKafkaConfigurationTest`、`LiquidationKafkaConfigurationTest`、`InsuranceKafkaConfigurationTest`、`FundingKafkaConfigurationTest` 锁定 producer 使用 `acks=all`、幂等、`zstd` 和受限 in-flight 请求，并锁定 consumer 使用可重放的 `earliest`、可配置 `max-poll-records`、关闭 auto commit、cooperative-sticky assignment 和 Spring Kafka record 级 ack。 |
| 前端 Gateway 和 WebSocket fanout | `GatewayTraceFilterTest` 和 `GatewayProxyControllerTest` 验证 gateway traceId 清洗/转发、白名单目标 URI 拼接和私有路由身份校验；`GatewayHttpConfigurationTest` 验证 gateway 后端连接/读取超时；`SubscriptionTopicTest` 验证公共/私有订阅标准化和用户 id 校验，包括公共 depth 以及私有 `accountRisk`/`positionRisk` 频道；`SubscriptionRegistryTest` 验证私有订单/持仓推送按 userId 隔离，即使另一个用户订阅同一 symbol 也不会泄漏，同一用户 wildcard 私有订阅可收到事件，并且失败订阅者被移除时不会影响其他订阅者；`KafkaFanoutConsumerTest` 验证盘口深度 Kafka 事件会 fanout 到 `channel=depth`，也验证私有风险 Kafka 事件会按认证用户 fanout 到 `accountRisk`/`positionRisk`；`WebSocketApplicationYamlTest` 和 `WebSocketKafkaConfigurationTest` 验证 WebSocket 每节点 fanout consumer 使用基于稳定节点名的 group 默认值、可配置 `max-poll-records`、`latest`、关闭 auto commit、cooperative-sticky assignment 和 record 级 ack；`WebSocketServerConfigurationTest` 验证可配置 WebSocket Origin 白名单和本地开发回退。 |
| symbol-key 分区不变量 | `KafkaSymbolKeyValidatorTest`、`MatchingCommandConsumerTest`、`MatchTradeConsumerTest`、`PositionRiskTriggerConsumerTest`、`LiquidationCandidateConsumerTest` 验证 matching command、account match trade、risk position trigger、liquidation candidate 在 Kafka record key 和 payload `symbol` 不一致时，会在业务处理前被拒绝。 |
| Outbox 重试语义 | `OutboxRepositoryTest`、`MatchingOutboxRepositoryTest`、`RiskOutboxRepositoryTest`、`FundingOutboxRepositoryTest` 验证未发布且到期的 outbox 行会用 `FOR UPDATE SKIP LOCKED` 租约认领；order/matching 的 Kafka 发送发生在数据库事务外；Kafka 发送失败会增加 attempts、记录截断后的错误，并通过 `next_attempt_at` 安排有上限的指数退避重试。 |
| 撮合服务输出 | `MatchingServiceTest` 启动真实 exchange-core 完成撮合，并验证 maker/taker `MatchTradeEvent` 的 instrument version、maker/taker 保证金模式查询和传递、command traceId 传递到撮合结果/成交、订单簿 `SNAPSHOT`/`DELTA` 输出中的剩余数量和删除价格档、command 幂等、重复 result/trade 冲突跳过副作用，以及 `CANCEL` 命令不会被 post-only 流动性检查误拒绝；`MatchingResultRepositoryTest` 验证 result/trade 幂等键。 |
| 撮合持久化副作用 | `MatchingResultRepositoryTest` 验证撤单和 immediate 终态订单会先释放保证金，再清空 remaining quantity；订单成交数量更新使用 guarded update，不做静默夹取；非 reduce-only 订单缺失 reservation 会失败；同时验证冻结余额不足时保证金释放会失败，而不是把不存在的冻结金额加回可用余额。 |
| 账户持仓、PnL、手续费和持仓推送源 | `PositionCalculatorTest`、`MarginTransferMathTest`、`PnlSettlementMathTest`、`TradeFeeMathTest`、`AccountRepositoryTest`、`AccountServiceTest`、`AccountKafkaConfigurationTest`、`MatchTradeConsumerTest`、`ReduceOnlyOrderPrunerTest` 验证线性/反向合约 PnL、maker/taker 手续费按订单快照扣款和返佣、`TRADE_FEE` 和 `LIQUIDATION_FEE` ledger 保存 `trade_id/order_id/symbol/fee_rate_ppm` 审计字段、按保证金模式隔离的持仓和持仓保证金写入、账户持仓响应返回 `positionSide = NET`、仓位翻转时按 long/short 方向维护 symbol open interest、用户手动逐仓保证金追加/减少、`POSITION_MARGIN_ADJUSTMENT` reference 幂等、减少保证金前的新鲜风险快照校验、手动逐仓保证金调整后发出持仓更新 outbox 触发、按实际成交价迁移保证金、委托价/市价保护价多冻结差额释放、翻仓成交先平旧仓再把剩余数量作为新仓消费开仓保证金、开仓/平仓成交缺失必要 reservation 时 fail-fast、平仓保证金释放、`TRADE_PNL`/`TRADE_FEE` ledger 写入 fail-fast、强平费实际结算方法有 repository 级事务边界、强平费按可收 collateral 封顶且不会生成新的 deficit、`(symbol, trade_id)` 成交幂等，包括不同 symbol 下同号 tradeId 不互相去重、Kafka fetch size 配置化、processed/duplicate/failed 结算指标、持仓变化后剪枝超额/反向 reduce-only 挂单、checked reduce-only 容量数学、已实现亏损只扣持仓保证金支撑的 locked collateral，以及账户结算后写带 traceId 的持仓更新/强平费 outbox。 |
| 风控和爆仓候选 | `RiskMathTest`、`RiskRepositoryTest`、`RiskOutboxRepositoryTest`、`PositionRiskTriggerConsumerTest`、`RiskServiceTest` 覆盖权益/保证金率状态、long-only 线性/反向 notional/PnL/维持保证金公式、`userId + settleAsset` 账户组 keyset 分页扫描、账户持仓事件携带保证金模式触发账户组扫描、完全平仓后的账户和仓位 0 风险快照、整组 fresh mark 校验、全仓账户级 PnL 和维持保证金 checked 聚合、逐仓按仓位保证金权益计算风险、快照/outbox fail-fast 写入、用于 WebSocket PnL 展示的私有账户/持仓风险 outbox 事件、按 `userId + settleAsset` 隔离的风控扫描事务、多节点 provider 的 PostgreSQL 扫描租约获取/跳过行为、按 `userId + symbol + marginMode` 限定 active 候选冲突、候选事件一致性；`integration-smoke.sh` 验证风险快照和活跃强平候选唯一性。 |
| 强平执行 | `LiquidationPriceCalculatorTest`、`LiquidationSizingPolicyTest`、`LiquidationSideResolverTest`、`LiquidationServiceTest`、`LiquidationRepositoryTest`、`LiquidationOrderRepositoryTest` 验证分阶段 sizing、线性/反向合约的 long/BigInteger 破产价和接管价计算、预估强平费审计字段、基于共享公式计算 sizing notional、平仓方向、instrument version 传递、全仓账户级和逐仓仓位级新鲜风险复核、最新风险快照仓位和锁定实时仓位不一致时取消候选、带 checked 待平聚合的强平前置撤用户 reduce-only 平仓挂单、按完整实时持仓而不是 pending close 容量做强平 sizing、候选状态 fail-fast 更新、撮合结果生命周期更新时如果候选不能继续从 `PROCESSING` 推进则 fail-fast、强平 trading order 插入不做宽泛冲突吞噬，以及强平审计/outbox 写入被冲突跳过时 fail-fast。 |
| 资金费 | `FundingMathTest`、`FundingTimeTest`、`FundingRepositoryTest`、`FundingOutboxRepositoryTest`、`FundingServiceTest`、`PostLiquidationFundingInsuranceAdlIntegrationTest` 验证 long ppm 方向、UTC funding 时间、基于共享公式按持仓版本折算 notional、费率/outbox fail-fast 发布、按 `symbol + fundingTime` 隔离结算事务、结算/账户 fail-fast 更新、资金费扣款不消耗开放订单冻结，以及资金费亏损只先消耗持仓保证金支撑的 locked collateral，之后才形成 deficit。 |
| 保险基金 | `InsuranceMathTest` 验证全额/部分覆盖；`InsuranceRepositoryTest` 验证基金调整幂等、按 `tradeId:orderId` 的强平费重放幂等、覆盖流水冲突 fail-fast、repository 层部分覆盖会写入保险基金负流水和亏损账户正向覆盖流水，以及保险基金为空时不修改 `account_deficits`，把亏损留给后续补充保险基金或 ADL 扫描；`integration-smoke.sh` 和 `PostLiquidationFundingInsuranceAdlIntegrationTest` 验证部分覆盖只减少 `account_deficits`，不增加 available balance，实际扣到的强平费事件重放只给保险基金入账一次，并在保险基金耗尽后把剩余 deficit 留给 ADL。 |
| ADL | `AdlMathTest` 验证队列优先级；`AdlRepositoryTest` 验证基于共享公式计算候选 notional/盈利、ADL 执行前锁定保险基金余额、repository 层成功 ADL 会减少目标仓位、释放保证金、转移已实现盈利并清空 deficit，同时验证目标仓位更新和 guarded 保证金释放 fail-fast；`integration-smoke.sh` 和 `PostLiquidationFundingInsuranceAdlIntegrationTest` 验证 ADL 减仓、释放保证金、转移盈利和清除剩余亏损。 |
| 运营熔断开关 | `RiskServiceTest`、`LiquidationServiceTest`、`FundingServiceTest`、`InsuranceServiceTest`、`AdlServiceTest` 验证风险扫描、强平执行、资金费率发布、资金费结算、保险基金覆盖、ADL 扫描都可以暂停，且暂停时不会创建候选、claim 强平任务、写账户变化或修改 deficit。 |
| 价格服务 | 指数价格和标记价格测试覆盖异常源剔除、标记价格计算、外部 ticker 解析、index/mark Kafka 可靠发送配置、live-feed 输入 consumer 配置，以及在 price 边界持久化 `mark_price_units` 后由核心模块按版本转换成 ticks。 |
| K 线 | K 线数学测试和模块 README 覆盖 Kafka Streams/RocksDB 聚合机制和最新 K 线推送边界。 |

## 端到端链路状态

当前仓库已经有证据覆盖核心逻辑链路：

```text
instrument 规则
  -> 下单校验和保证金冻结
  -> exchange-core 撮合
  -> 保护价市价成交和按成交价迁移保证金
  -> 止盈止损条件单转成 reduce-only 平仓单
  -> 用户撤单和未成交预冻结保证金释放
  -> 部分成交撤单时保留已迁移仓位保证金
  -> 带版本的撮合成交事件
  -> 账户持仓、PnL、手续费、保证金更新
  -> 风险快照和爆仓候选
  -> 前置撤用户 reduce-only 平仓挂单
  -> reduce-only 强平平仓单
  -> 资金费结算
  -> 保险基金覆盖亏损
  -> ADL 处理剩余亏损
```

Java 集成测试现在覆盖 order、matching、account、risk、liquidation、funding、insurance、ADL 服务类之间的事件交接，不需要启动真实 Kafka/PostgreSQL 进程。它补充了 PostgreSQL smoke，后者更侧重 schema 和跨表不变量。Kafka 交易 smoke 补上真实 Kafka/PostgreSQL 进程下 order -> matching -> account 的证据；在 Docker Compose 可用时，load/full-stack smoke 会进一步覆盖 WebSocket 盘口深度和私有推送断言。full-stack 脚本现在已经有新的进程级证据，证明 TP/SL OCO 可以经过 trigger-provider、gateway、order-provider、matching、account 和 WebSocket fanout 完整执行。

本地 PostgreSQL smoke 还验证了单元测试不容易覆盖的跨表约束：

- 旧持仓不会被新 instrument 版本重新解释；
- 资金费 notional 折算使用持仓自己的 instrument version；
- 活跃强平候选按 `user_id + symbol` 唯一；
- 保险基金覆盖只减少显式 deficit；
- ADL 会减少盈利目标仓位，并把实现盈利转移去清除剩余亏损。

## 生产前剩余工作

当前门禁适合作为开发验证，但还不能替代生产上线验证。
生产前还需要补充并记录：

- 扩展多节点、多 broker Kafka 端到端测试，覆盖更长运行时间、broker rebalance 和 provider 重启窗口；当前仓库已有本机短时多节点 smoke，但还没有持续多 broker 集群运行证据；
- hedge-mode 生产级持续验证，覆盖 order、matching、account positions、risk snapshots、funding、liquidation、insurance、ADL、WebSocket 和 API 在多用户、多 symbol、长时间和故障窗口下的行为；当前代码已经在这些链路携带 `positionSide`，full-stack smoke 覆盖了 HEDGE 开/平仓和 WebSocket，但生产上线前仍需要更长时间的真实进程验证；
- 扩展真实进程逐仓保证金调整压测，把当前单用户 add/safe-remove/reject 场景扩大到多用户、多 symbol 和长时间运行；
- matching partition rebalance/failover、Kafka outbox 重放、PostgreSQL 锁竞争测试；
- 订单峰值、成交峰值、mark price 更新频率、风控扫描频率的压测；
- PostgreSQL 重启、Kafka broker 故障、mark price 过期、外部交易所断线的故障演练；
- 在真实集群里验证这些运营暂停开关，并补充保险基金充值、symbol halt、instrument version 上线的 runbook。

## 当前结论

当前代码已经形成一条使用 long 定点数、以 exchange-core 为撮合核心、用 PostgreSQL 幂等和锁控制状态迁移的永续合约交易链路。
本地验证门禁已经证明关键会计公式、跨表不变量，以及一条干净状态下的真实 Kafka/PostgreSQL/WebSocket 进程链路。完整生产级就绪仍需要持续压测、多节点故障转移和故障演练证据。
