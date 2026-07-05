# 全链路功能测试用例清单

本文是后续回归和压测的主清单。状态说明：

- `[x]` 已有本轮或现有报告中的验证证据。
- `[ ]` 尚未完成，需要后续执行。
- `[!]` 当前不适用或有设计限制，原因写在预期结果中。

验收原则：功能完成必须优先以用户实际调用方式验证，也就是经 gateway HTTP/WebSocket、真实 provider、Kafka、PostgreSQL、matching、account、risk、funding、insurance、ADL 的全链路执行。单元测试只作为补充，用来定位和证明交易前后资金守恒、做市账号/用户账号余额、强平、保险基金和 ADL 等局部资金计算不出错；不能用单元测试替代全链路验收。

本地复跑统一使用 Homebrew PostgreSQL/Kafka/Redis：`localhost:5432`、`localhost:9092`、`localhost:6379`。当前脚本默认 `START_INFRA=false`、`INFRA_MODE=local`，不要再为测试启动独立 Docker 中间件。

## 本轮新增验证

- [x] TP close long：SELL TAKE_PROFIT，mark price >= trigger price 时生成 reduce-only 平仓单。
  预期：trigger condition 为 `GREATER_OR_EQUAL`，订单为 reduce-only，触发后状态推进到 `TRIGGERED`。
- [x] SL close long：SELL STOP_LOSS，mark price <= trigger price 时生成 reduce-only 平仓单。
  预期：trigger condition 为 `LESS_OR_EQUAL`，数量使用该档 `quantitySteps`。
- [x] SL close short：HEDGE + SHORT + BUY STOP_LOSS，mark price >= trigger price 时生成 reduce-only 平仓单。
  预期：`positionSide=SHORT` 传到 order-provider，不落到 NET。
- [x] TP/SL 多档位后端触发：同一 symbol 下两条满足条件的 trigger order 被 claim。
  预期：两档分别生成 `trigger-<triggerOrderId>` client order，每档使用自己的 `quantitySteps`。
- [x] TP/SL 多档位总待平量前置校验。
  预期：放置条件单时锁定当前仓位，聚合已有 active reduce-only 平仓单和待触发 TP/SL；同一 OCO 组合按最大平仓量占用额度，不按两条 sibling 叠加；超过当前可平仓位时拒绝。
- [x] TP/SL mark price 不可用。
  预期：trigger-provider 查不到触发事件对应的持久化 mark price 时，不 claim 条件单、不生成平仓单。
- [x] 强平早于 TP/SL。
  预期：如果强平已经清掉仓位，后续 TP/SL 触发时生成的 reduce-only 平仓单会被 order-provider 拒绝，条件单转 `TRIGGER_FAILED`，不会反向开仓。
- [x] Web 与移动端多档位录入和打通。
  预期：Web 交易面板和 Flutter iOS/Android 共用交易页都可以新增多条 TP/SL，每档选择平多/平空、触发价、数量；提交后走 gateway `trading-trigger` 下单；账户面板展示开放条件单并支持撤销。
- [x] 撮合 HEDGE 仓位侧成交事件。
  预期：taker 的 `positionSide` 来自 order command，maker 的 `positionSide` 从订单表读取，成交事件保留 `LONG/SHORT`。
- [x] 风控 HEDGE 仓位侧扫描与强平候选事件。
  预期：账户持仓事件触发扫描时按 `positionSide=SHORT` 定位仓位，风险事件和强平候选事件不退回 `NET`。
- [x] 强平 HEDGE 仓位侧下单。
  预期：SHORT 仓位强平时预撤同侧 reduce-only，生成 `BUY + reduceOnly + positionSide=SHORT` 的系统市价单，并写强平审计。
- [x] 资金费 HEDGE 仓位侧扣款。
  预期：资金费候选从 DB 映射 `positionSide`，扣款时只消耗对应 `LONG/SHORT` 仓位保证金，ledger reference 包含仓位侧。
- [x] ADL HEDGE 仓位侧审计。
  预期：ADL 更新/释放对应 `positionSide` 的仓位和保证金，并在 `adl_events.target_position_side` 记录被减仓仓位桶。
- [x] ADL 后 symbol open interest 对账。
  预期：ADL 减少目标仓位后，同一事务同步更新 `trading_symbol_open_interest`；全链路 smoke 最终从 `account_positions` 重算 long/short/open interest 并和 OI 表对账，非法 OI 行数为 `0`。
- [x] 撮合侧 mark price 不可用兜底。
  预期：order-provider 校验后到 matching 前 mark price 过期时，matching 返回 `MARK_PRICE_UNAVAILABLE`，不撮合、不生成成交。
- [x] index source 不足导致 mark price 停止刷新后的下单 fail-closed。
  预期：index-price 有效源不足时输出 `INSUFFICIENT_SOURCES` 且不带 `indexPrice`；mark-price 不发布新的 mark price；order-provider 对普通市价单拒绝 `mark price unavailable`；恢复健康指数输入后 mark price 重新发布。
- [x] 行情失效期间撤单和应急交易模式。
  预期：instrument admin API 切到 `HALT` 后，用户经 gateway 新开订单拒绝为 `instrument is cancel-only`，存量开放订单仍能经 gateway 撤成 `CANCELED`；切到 `SETTLING` 后，普通开仓订单拒绝为 `instrument is reduce-only`，多空双方 reduce-only 订单仍能经 gateway/order/matching/account 完成平仓结算。最新日志：`/tmp/surprising-full-stack-real-config.A7PtM8`。
- [x] 私有 WebSocket 统一执行回报。
  预期：新增 `executionReports` 私有频道，不改 exchange-core，由 websocket-provider 把 `ORDER_EVENT`、`MATCH_RESULT` 和逐笔 `TRADE` 统一映射成客户端执行回报；maker/taker 成交回报分别带 `liquidityRole=MAKER/TAKER`。Web/Flutter 客户端已订阅该频道，full-stack 和 market-maker 脚本已加入真实 WebSocket 捕获断言。2026-07-05 clean full-stack smoke 已通过，日志 `/tmp/surprising-full-stack-real-config.z3LnIV`；full taker、partial maker、cancel、isolated、position-mode 五个私有用户分别捕获 `executionReports=10/5/4/6/8`，覆盖 `ORDER_EVENT`、`MATCH_RESULT` 和 `TRADE`。
- [x] 真实 provider full-stack smoke。
  预期：在真实 PostgreSQL/Kafka 和 instrument/candlestick/index-price/mark-price/order/matching/account/risk/liquidation/funding/insurance/ADL/websocket/trigger/gateway/market-maker provider 下，仓位模式切换、HEDGE LONG/SHORT 持仓、TP/SL OCO、逐仓保证金、部分成交撤单、撮合/account 重启恢复、资金费、强平、保险基金、ADL、公开/私有 WebSocket 推送和会计不变量全部通过。
- [x] 清算/ADL 期间后台成交流。
  预期：强平、保险基金和 ADL 场景执行期间，另有普通用户 maker/taker 持续成交；后台成交必须被 matching 写入、account 结算，且不能干扰 depth WebSocket 序列。
- [x] 清算 outbox 发布范围。
  预期：liquidation provider 只认领 `ORDER`/`LIQUIDATION_ORDER` outbox 行，并按 `topic + event_key` 取最早未发布行和 advisory lock；不得发布 matching-owned `ORDER_BOOK_DEPTH`、`MATCH_RESULT`、`MATCH_TRADE` 行，避免 depth 事件乱序。
- [x] 连续做市刷新真实进程 smoke。
  预期：不重新打包 jar，复用现有 provider artifact，在干净测试状态下连续执行 2 轮 maker 刷新挂单和 taker 流量，最终成交全部被 account 结算，Kafka lag 为 0，无负余额、无非法 OI，WebSocket 公开/私有事件可达。
- [x] market-maker provider 连续报价真实链路 smoke。
  预期：真实 market-maker provider 通过 gateway `market-maker/run-once` 连续执行报价引擎，生成 post-only 报价；普通用户 taker 经 gateway `trading` 单笔吃单，最终撮合成交、account 结算、做市账号和用户账号资金不为负，开放做市报价清理为 0。
- [x] market-maker provider scheduled engine 真实链路 smoke。
  预期：资金和测试 fixture 就绪后重启 market-maker provider 并启用 `surprising.market-maker.engine.enabled=true`，由 provider 自己的 `@Scheduled` 调度持续报价；普通用户 taker 经 gateway 单笔吃单，最终撮合成交、account 结算、做市账号和用户账号资金不为负，开放做市报价清理为 0。
- [x] market-maker provider scheduled engine 账户服务重启故障 smoke。
  预期：scheduled engine 持续报价时停止真实 account provider，普通用户 taker 仍通过 gateway/matching 成交；重启 account provider 后成交结算追平，做市账号和用户账号资金不为负，outbox 清空，保险基金/ADL remaining deficit 为 0。
- [x] market-maker provider 参考行情 WebSocket + 账户服务重启故障 smoke。
  预期：启用 `MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true` 后，scheduled engine 用外部 Binance/OKX/Bybit 风格深度样本校准报价；account provider 停机窗口内普通用户 taker 仍通过 gateway/matching 成交，重启后 account 结算追平，资金、outbox、保险基金和 ADL 不变量通过。
- [x] market-maker provider 参考行情 WebSocket 180 秒持续做市 smoke。
  预期：启用参考行情和 scheduled engine 后，真实 market-maker provider 持续报价 180 秒，普通用户 taker 仍通过 gateway 成交；最终 depth WebSocket 序列单调递增、engine taker 成交/account 结算 30/30、做市账号和用户账号资金不为负，运行期 trading/account/risk/funding outbox 全部清空。
- [x] funding 静默后停机 outbox 收口。
  预期：最终 WebSocket/accounting 断言前通过 funding provider runtime config 关闭 calculation/settlement，停 provider 后再查 trading/account/risk/funding outbox pending 仍为 `0`。
- [x] L3 单节点真实接口压测。
  预期：4 个 maker、BTC/ETH 每侧 40 档初始深度、3 轮 maker 刷新、6000 笔普通用户 taker、128 并发全部经 gateway/order/matching/account/websocket 链路执行；最终 6000 笔成交全部被 account 结算，trading/account outbox 清空，无负余额、无非法 OI，前 3 个 taker 用户私有 orders/positions 推送不串号。
- [x] L4 单节点真实接口压测。
  预期：4 个 maker、BTC/ETH 每侧 50 档初始深度、5 轮 maker 刷新、10000 笔普通用户 taker、128 并发全部经 gateway/order/matching/account/websocket 链路执行；最终 10000 笔成交全部被 account 结算，trading/account outbox 清空，无负余额、无负产品余额、无负逐仓保证金、无预占超释放、无账户/产品 deficit 残留、无非法 OI，前 5 个 taker 用户私有 orders/positions 推送不串号。
- [x] `executionReports` 做市压测回归。
  预期：新增私有执行回报频道后，做市压测脚本仍能在真实 gateway/order/matching/account/websocket 链路中通过；本轮 2 个 maker、BTC/ETH 每侧 10 档、200 笔普通用户 taker、64 并发已通过，前 3 个私有用户均收到各自 `ORDER_EVENT`、`MATCH_RESULT`、`TRADE`，报告 `docs/market-maker-stress-report-executionReports.md`。
- [x] `executionReports` 中等压力做市压测回归。
  预期：在更高用户流量下继续验证私有执行回报不串号且资金正确；本轮 4 个 maker、BTC/ETH 每侧 20 档、2 轮刷新、2000 笔普通用户 taker、96 并发、前 10 个私有 WebSocket 用户已通过。最终 2000/2000 成交并 account 结算，trading/account outbox 清空，负余额和 deficit 聚合异常为 0；account lag 峰值 680，后置结算延迟需要继续优化。报告 `docs/market-maker-stress-report-executionReports-tier2.md`。
- [x] account 热路径缓存/剪枝真实链路回归。
  预期：account-provider 跳过开仓/加仓成交的 reduce-only 剪枝查询，并对 `liquidation_orders` 强平费上下文做订单维度 JVM 负缓存后，仍按用户真实 gateway/order/matching/account/websocket 链路验证资金正确；本轮 200 笔普通用户 taker、64 并发、前 3 个私有 WebSocket 用户已通过，200/200 成交并 account 结算，trading/account outbox 清空，负余额和 deficit 聚合异常为 0，三个私有用户均收到 `ORDER_EVENT`、`MATCH_RESULT`、`TRADE`。报告 `docs/market-maker-stress-report-account-cache-smoke.md`。同规格 2000/96 剪枝回归也已通过，报告 `docs/market-maker-stress-report-account-pruner.md`。
- [x] account available-balance fast path 与 metadata cache 真实链路回归。
  预期：跨仓普通负向资金变动在可用余额足够时走 guarded available-balance fast path，避免无必要的 `account_position_margins` 查询/锁；instrument type、spot spec、contract spec、order fee snapshot 使用有界 JVM 只读缓存，不缓存余额、持仓、保证金或 deficit。本轮 2000/96 fast path 回归已通过，资金、outbox、deficit 和前 10 个私有用户 `executionReports` 断言通过，account 单笔 processing avg 从同规格上一轮 `0.222556s` 降到 `0.198013s`，event lag avg/max 从 `59.555878s/110.615114s` 降到 `50.469238s/91.006244s`；100/64 metadata cache smoke 已通过，100/100 成交并 account 结算，account lag max `15` 且最终 `0`。报告 `docs/market-maker-stress-report-account-fastpath.md`、`docs/market-maker-stress-report-account-instrument-cache-smoke.md`。account 后置结算写库尾部仍需继续优化。
- [x] matching 单 JVM 多 consumer 真实链路回归。
  预期：通过 `MATCHING_CONSUMERS_PER_NODE` 验证单个 matching jar 内不同 symbol 分区可并行，但报告必须显示热 symbol 是否真实分散到不同 consumer；本轮 `MATCHING_CONSUMERS_PER_NODE=2` 时 BTC/ETH 均在同一 consumer，`split=false`，`MATCHING_CONSUMERS_PER_NODE=6` 时 BTC/ETH 分散到不同 consumer，`split=true`。两轮 2000 taker/96 并发均通过真实 gateway/order/matching/account/websocket 链路，资金、outbox、deficit 和 `executionReports` 断言通过；`split=true` 后 matching p50 降低，主要尾部瓶颈后移到 account 结算写库。报告 `docs/market-maker-stress-report-matching-concurrency2.md`、`docs/market-maker-stress-report-matching-concurrency6.md`。
- [x] TWAP / Iceberg 算法单真实链路 smoke。
  预期：通过用户实际调用的 gateway API 放置算法单；TWAP 父单按 interval 调度 3 笔 IOC 子单并全部成交后转 `COMPLETED`；Iceberg 同一时间只保留一笔活动子单，三片逐步成交后转 `COMPLETED`；子单继续走普通 order-provider、exchange-core、matching、account、risk 和 WebSocket 链路。
- [x] 活动算法单阻断持仓模式切换。
  预期：用户有活动 Iceberg 父单/子单时，`ONE_WAY`/`HEDGE` 切换返回 409；调用 `trading/algo/cancel-open` 后父单和活动子单都为 `CANCELED`，再切换持仓模式成功。

验证命令：

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

PAIR_COUNT=3 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=5 RUN_FAILURE_SCENARIOS=true \
BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=240 \
MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false \
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  ./scripts/full-stack-real-config-smoke.sh

START_INFRA=false \
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
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/full-stack-real-config-smoke.sh

START_INFRA=false \
PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 RUN_FAILURE_SCENARIOS=false \
BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=900 \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=true \
MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false \
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  ./scripts/full-stack-real-config-smoke.sh

KEEP_TMP=true RUN_FAILURE_SCENARIOS=false \
PAIR_COUNT=10 LOAD_CONCURRENCY=4 BOOK_DEPTH_LEVELS=20 \
  bash scripts/full-stack-real-config-smoke.sh

START_INFRA=false STOP_INFRA=false RESET_STATE=true \
START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false \
BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=900 \
PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 \
FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false \
MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false \
MM_TRADE_ENABLED=false \
MM_PROVIDER_CONTINUOUS_SECONDS=8 \
MM_PROVIDER_CONTINUOUS_INTERVAL_SECONDS=1 \
MM_PROVIDER_CONTINUOUS_TAKER_ORDERS=2 \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/full-stack-real-config-smoke.sh

START_INFRA=false STOP_INFRA=false RESET_STATE=true \
START_PROVIDERS=true STOP_PROVIDERS=true RUN_FAILURE_SCENARIOS=false \
BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=900 \
PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 \
FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false \
MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false \
MM_TRADE_ENABLED=false \
MM_PROVIDER_CONTINUOUS_SECONDS=0 \
MM_PROVIDER_ENGINE_SECONDS=60 \
MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 \
MM_PROVIDER_ENGINE_TAKER_ORDERS=20 \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/full-stack-real-config-smoke.sh

START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate \
START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true \
MM_ACCOUNT_COUNT=1 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 \
MM_REFRESH_CYCLES=2 MM_REFRESH_INTERVAL_SECONDS=1 \
TAKER_ORDER_COUNT=2 LOAD_CONCURRENCY=2 \
REPORT_FILE=docs/market-maker-continuous-smoke-report.md \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/market-maker-stress.sh

START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate \
START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=false KEEP_TMP=true \
MM_ACCOUNT_COUNT=1 MM_DEPTH_LEVELS=2 MM_REFRESH_LEVELS=1 \
MM_REFRESH_CYCLES=2 MM_REFRESH_INTERVAL_SECONDS=1 \
TAKER_ORDER_COUNT=3 LOAD_CONCURRENCY=2 \
WS_FANOUT_USER_COUNT=3 WS_CAPTURE_TIMEOUT=900 \
REPORT_FILE=docs/market-maker-fanout-smoke-report.md \
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
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/market-maker-stress.sh

START_INFRA=false RESET_STATE=true RESET_KAFKA_MODE=recreate \
START_PROVIDERS=true STOP_PROVIDERS=true BUILD_SERVICES=auto KEEP_TMP=true \
MM_ACCOUNT_COUNT=4 MM_DEPTH_LEVELS=50 MM_LEVEL_QUANTITY_STEPS=50 \
MM_REFRESH_LEVELS=10 MM_REFRESH_QUANTITY_STEPS=20 \
MM_REFRESH_CYCLES=5 MM_REFRESH_INTERVAL_SECONDS=1 \
MAKER_BATCH_SIZE=5 MAKER_LOAD_CONCURRENCY=10 \
TAKER_ORDER_COUNT=2000 TAKER_QUANTITY_STEPS=2 LOAD_CONCURRENCY=128 \
ORDER_HIKARI_MAX_POOL_SIZE=60 ORDER_HIKARI_CONNECTION_TIMEOUT_MS=10000 \
ACCOUNT_HIKARI_MAX_POOL_SIZE=40 ACCOUNT_HIKARI_CONNECTION_TIMEOUT_MS=10000 \
MATCHING_HIKARI_MAX_POOL_SIZE=30 MATCHING_HIKARI_CONNECTION_TIMEOUT_MS=10000 \
GATEWAY_HIKARI_MAX_POOL_SIZE=20 GATEWAY_HIKARI_CONNECTION_TIMEOUT_MS=10000 \
TAKER_FILL_WAIT_SECONDS=1200 TAKER_TRADE_WAIT_SECONDS=1200 \
ACCOUNT_SETTLEMENT_WAIT_SECONDS=1800 \
WS_FANOUT_USER_COUNT=5 WS_CAPTURE_TIMEOUT=2100 \
REPORT_FILE=docs/market-maker-stress-report-tier4.md \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/market-maker-stress.sh

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-market-maker-provider -am \
  -Dtest=QuotePlannerTest,RestReferenceMarketProviderTest,MarketMakerServiceTest,MarketMakerApplicationYamlTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

npm run lint

corepack pnpm build
flutter analyze
flutter test
```

结果：以上后端定向测试、market-maker 参考盘口校准测试、真实 provider full-stack smoke、连续做市刷新 smoke、market-maker provider 连续报价 smoke、market-maker provider scheduled engine smoke、多用户 WebSocket fanout smoke、Web 构建、Flutter analyze/test 均通过。关键日志和报告包括 `/tmp/surprising-full-stack-real-config.3qu3b9`、`/tmp/surprising-full-stack-real-config.DsxfGp`、`docs/market-maker-provider-engine-report.md`、`docs/market-maker-reference-market-sustained-report.md`、`docs/market-maker-fanout-smoke-report.md`、`docs/market-maker-stress-report-tier3.md` 和 `docs/market-maker-stress-report-tier4.md`。最新 L4 单节点真实接口压测完成 10000 笔 taker 成交/account 结算，Kafka lag 回到 0，资金、预占、deficit、OI 和 outbox 不变量通过，前 5 个 taker 用户私有 fanout 不串号。参考行情 WebSocket 做市已经有 30 秒、90 秒和 180 秒真实 provider 样本；仍未完成的是多节点、多 broker、长时间生产级压测报告。Web/Flutter 的多档 TP/SL、TWAP/Iceberg 入口和开放算法单列表也已通过构建或 analyze/test。

2026-07-05 追加 TWAP / Iceberg 算法单 full-stack smoke 已通过，日志保留在 `/tmp/surprising-full-stack-real-config.DsxfGp`。该轮通过真实 gateway API 放置、查询和撤销算法单，TWAP 与 Iceberg 子单都经过普通 order-provider、exchange-core、matching、account、risk/WebSocket 链路；活动算法单阻断持仓模式切换，`cancel-open` 后切换成功。最终捕获 depth events `213`、mark events `399`、funding events `390`，余额、预占释放、trading/account/funding/risk outbox 不变量通过。历史运行时曾使用隔离端口；当前本地 Homebrew PostgreSQL 已统一使用 `localhost:5432`。

2026-07-05 追加 L3 单节点真实接口压测已通过，报告为 `docs/market-maker-stress-report-tier3.md`，日志保留在 `/tmp/surprising-mm-stress.6Z6Lu8`。该轮未重新打包 provider jar，启动真实 order/matching/account/websocket/gateway 进程，maker 初始 640 笔挂单、刷新 576 笔挂单、普通用户 taker 6000 笔全部经 gateway 进入；最终撮合成交 6000、account 结算 6000、account Kafka lag 回到 0、trading/account outbox 均为 0，无负余额、无非法 OI。第一次同参数尝试暴露 order-provider Hikari 连接池耗尽，脚本随后增加可配置连接池和等待窗口，复跑通过。

2026-07-05 追加 L4 单节点真实接口压测已通过，报告为 `docs/market-maker-stress-report-tier4.md`，日志保留在 `/tmp/surprising-mm-stress.cqqRbO`。该轮启动真实 order/matching/account/websocket/gateway 进程，maker 初始 800 笔挂单、刷新 800 笔挂单、普通用户 taker 10000 笔全部经 gateway 进入；最终撮合成交 10000、account 结算 10000、account Kafka max lag 3592 且最终回到 0、trading/account outbox 均为 0，负账户余额、负产品余额、负逐仓保证金、预占超释放、账户/产品 deficit、非法 OI 均为 0，前 5 个 taker 用户私有 orders/positions fanout 不串号。脚本随后把压测 fixture 初始入金从逐笔 admin HTTP 改为批量 SQL 账本/余额/admin adjustment 初始化，避免非交易路径拖慢 10000 级样本准备。

2026-07-05 追加 market-maker scheduled engine 账户服务重启故障 smoke 已通过，报告为 `docs/market-maker-provider-engine-fault-report.md`，日志保留在 `/tmp/surprising-full-stack-real-config.F0V0h2`。该轮启动真实 instrument/candlestick/index-price/mark-price/order/matching/account/risk/liquidation/funding/insurance/ADL/websocket/trigger/gateway/market-maker provider，在 scheduled engine 报价已被接收后停止真实 account provider，经 gateway 提交 5 笔普通用户 taker 并等待撮合成交，随后重启 account provider；故障窗口内出现预期 `CYCLE_FAILED=103`，重启后恢复 `CYCLE_SUCCESS`，`QUOTE_RECONCILED=9`、提交报价 360、做市拒单 0、engine taker 成交/account 结算 5/5，开放 market-maker 报价为 0，余额/保证金/OI 不变量、trading/account/risk/funding outbox、保险基金和 ADL remaining deficit 均通过。

2026-07-05 追加 market-maker 参考行情 WebSocket + 账户服务重启故障 smoke 已通过，报告为 `docs/market-maker-reference-market-fault-report.md`，日志保留在 `/tmp/surprising-full-stack-real-config.8XwMJ0`。该轮启用 `MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true`，真实 provider 写入 `REST|BINANCE_USDM=2`、`WEBSOCKET|BINANCE_USDM=142`、`WEBSOCKET|BYBIT_LINEAR=4`、`WEBSOCKET|OKX_SWAP=2` 参考盘口样本；scheduled engine 运行 90 秒，account provider 停机窗口内经 gateway 提交 10 笔普通用户 taker 并等待撮合成交，重启后 account 结算 10/10；故障窗口内预期 `CYCLE_FAILED=144`，恢复后 `CYCLE_SUCCESS=6`、`QUOTE_RECONCILED=12`，提交报价 476、做市拒单 0，开放 market-maker 报价为 0，余额/保证金/OI 不变量、trading/account/risk/funding outbox、保险基金和 ADL remaining deficit 均通过。

2026-07-05 追加 market-maker 参考行情 WebSocket 180 秒持续做市 smoke 已通过，报告为 `docs/market-maker-reference-market-sustained-report.md`，日志保留在 `/tmp/surprising-full-stack-real-config.xCbq79`。首次 180 秒尝试暴露 WebSocket depth fanout 对合法现货 symbol `BTC-USDT-SPOT` 校验过窄，导致 Kafka listener retry/seek 并重放旧 BTC-USDT depth 的问题；修复 `SubscriptionTopic` symbol 规则并补现货 depth 订阅/fanout 回归测试后复跑通过。该轮 depth events `2794` 且 sequence 单调到 `3130`，`CYCLE_SUCCESS=19`、`QUOTE_RECONCILED=38`、提交报价 1333、做市拒单 0、engine taker 成交/account 结算 30/30，余额/保证金/OI/缺口不变量通过，并且脚本运行期最终严格要求 trading/account/risk/funding outbox pending 全部为 0。

## 下单

- [x] LIMIT GTC 开仓。
  预期：订单 `ACCEPTED`，冻结保证金，撮合未成交时 remaining=quantity。
- [x] MARKET IOC 开仓。
  预期：使用新鲜 mark price 计算保护价和 notional，成交后账户结算。
- [x] FOK 无法完全成交。
  预期：订单终态不保留开放数量，冻结保证金释放。
- [x] IOC 部分成交。
  预期：已成交部分迁移到持仓保证金，未成交部分释放订单冻结。
- [x] GTX/post-only 不应吃单。
  预期：如果会立即成交，撮合返回 `POST_ONLY_WOULD_TAKE`。
- [x] reduce-only 平仓。
  预期：只能减少已有仓位，超过可平量或反向开仓被拒绝。
- [x] LIMIT 价格带保护。
  预期：BUY 价格超过 mark price 上边界拒绝，SELL 价格低于下边界拒绝。
- [x] 市价单 mark price 不可用。
  预期：order-provider 拒绝 `mark price unavailable`，matching 侧也能拒绝 `MARK_PRICE_UNAVAILABLE`。
- [x] index source 不足导致 mark price 停止刷新后的普通下单 fail-closed。
  预期：mark price 停止刷新后普通下单拒绝 `mark price unavailable`，恢复健康指数输入后可重新产出 mark price。
- [x] 行情失效期间撤单和应急交易模式。
  预期：`HALT` 为 cancel-only，拒绝新开订单但允许撤存量开放订单；`SETTLING` 为 reduce-only，拒绝普通开仓订单但允许已有多空持仓通过 reduce-only 订单平仓。真实 full-stack smoke 已通过 gateway 用户接口、instrument admin 状态切换、matching/account 结算和资金不变量验证。

## 撤单

- [x] 未成交订单撤单。
  预期：订单状态 `CANCELED`，remaining 清零，冻结保证金全部释放。
- [x] 部分成交后撤剩余。
  预期：只释放未成交部分冻结；已成交部分的仓位保证金和手续费保持。
- [x] 重复撤单。
  预期：不会产生重复 cancel command，也不会重复释放冻结余额。
- [x] matching 重启后撤单/继续撮合。
  预期：matching 从 PostgreSQL 开放订单恢复订单簿，恢复后可继续成交或撤单。

## 持仓模式

- [x] NET 净仓。
  预期：同一合约同一保证金模式下只有 `positionSide=NET` 一行，买卖会增加、减少或翻转净仓。
- [x] HEDGE 双向持仓链路的基本支持。
  预期：普通订单、条件单、账户持仓已经支持 `LONG/SHORT` 字段传递和持久化。
- [x] HEDGE 关键资金链路单元回归。
  预期：matching trade、risk event/candidate、liquidation order、funding payment、ADL event 都保留仓位侧。
- [x] HEDGE 真实进程 smoke。
  预期：full-stack smoke 中同一用户同一合约能同时打开 `LONG` 和 `SHORT`，私有 WebSocket `position`/`positionRisk` 推送分别包含 `LONG` 和 `SHORT`。
- [ ] HEDGE 全链路生产级回归。
  预期：LONG 和 SHORT 同时存在时，order/matching/account/risk/funding/liquidation/insurance/ADL/WebSocket 全链路都按方向隔离计算。
- [x] 持仓模式切换保护。
  预期：有开放仓位、开放订单或开放条件单时，拒绝切换净仓/双向模式。
- [x] 持仓模式切换全部前置条件。
  预期：切换前必须无非零持仓、无活动订单、无待触发条件单、无活动算法单、无未被 account 处理的 matched trade、无活动保证金预占；全部满足时才允许从 `ONE_WAY` 切到 `HEDGE` 或反向切换。

## 保证金模式

- [x] CROSS 开仓/平仓。
  预期：账户级余额承担 PnL/手续费/资金费，强平按账户组风险计算。
- [x] ISOLATED 开仓/平仓。
  预期：订单保证金成交后迁移到持仓保证金，平仓释放对应比例持仓保证金。
- [x] 手动追加逐仓保证金。
  预期：减少风险，写出 position updated outbox。
- [x] 安全减少逐仓保证金。
  预期：通过新鲜风险快照校验后释放可减部分。
- [x] 超额减少逐仓保证金。
  预期：拒绝，不产生负持仓保证金。
- [x] 同一 symbol 跨保证金模式冲突。
  预期：另一个模式有活跃仓位/订单/条件单时，普通开仓和条件单拒绝；reduce-only 风险降低订单允许。

## 止盈止损

- [x] 单条止盈。
  预期：达到 mark trigger price 后生成 reduce-only 平仓单。
- [x] 单条止损。
  预期：达到 mark trigger price 后生成 reduce-only 平仓单。
- [x] OCO sibling 取消。
  预期：同一 `ocoGroupId` 内只 claim 一条触发单，其他 sibling 自动取消。
- [x] 多档止盈/止损。
  预期：每个价格是一条独立 trigger order，每档有自己的 `quantitySteps`，满足条件时逐条触发。
- [x] 真实进程 TP/SL OCO 触发执行。
  预期：full-stack smoke 中 trigger-provider 经 gateway/order-provider 生成 reduce-only 平仓单，并完成撮合、账户结算和 WebSocket fanout。
- [x] Web 与移动端多档位。
  预期：用户可以在 Web 和 Flutter iOS/Android 共用客户端一次配置多行，前端逐条调用 trigger order API；提交后可查询和撤销。
- [x] 多档位总数量前置校验。
  预期：同一 symbol/marginMode/positionSide/closeSide 的开放 TP/SL 总待平量加上 active reduce-only 平仓单不超过当前可平仓位；同一 OCO 组合按最大 `quantitySteps` 计数，避免止盈止损 sibling 双倍占用。
- [x] 强平早于 TP/SL。
  预期：如果 mark price 先触发强平，强平 reduce-only 订单优先进入风险处理；之后 TP/SL 触发时应因无可平仓位被拒绝或失败，不得重新开仓。
- [x] mark price 不可用时 TP/SL。
  预期：不 claim 触发单，不生成平仓单；价格恢复后按最新 mark 判断。

## 撮合

- [x] maker/taker 成交。
  预期：撮合结果、maker/taker trade、订单状态、订单簿 delta 都写出。
- [x] 自成交保护。
  预期：会自成交的订单被拒绝或阻断。
- [x] command 幂等。
  预期：同一 commandId 重放不重复撮合。
- [x] order fill guarded update。
  预期：remaining 不足或订单状态非法时 fail fast，不静默夹取数量。
- [x] 开放订单簿恢复。
  预期：matching 重启从 DB 恢复未完成订单。
- [ ] 高频做市持续运行下撮合稳定性。
  预期：做市程序持续报价、撤换单、taker 流量同时存在时，订单簿无交叉、无负 remaining、Kafka lag 可控。

## 账户资金

- [x] 下单冻结保证金。
  预期：available 减少、locked 增加，余额行 `FOR UPDATE` 防并发透支。
- [x] 成交迁移保证金。
  预期：订单冻结按实际成交价迁移到持仓保证金，多冻结部分释放。
- [x] 平仓 PnL。
  预期：按共享合约公式计算 realized PnL，写 ledger。
- [x] 手续费。
  预期：maker/taker 使用下单时手续费快照，不受成交后费率变更影响。
- [x] 成交幂等。
  预期：`account_processed_trades(symbol, trade_id)` 防重复落账。
- [x] 负余额保护。
  预期：余额释放和扣款使用 guarded update，失败时 fail fast。

## 风控、强平、保险基金、ADL

- [x] 风险快照。
  预期：账户组和逐仓风险按新鲜 mark price 计算。
- [x] 强平候选。
  预期：候选复核使用最新风险快照和实时持仓，不一致时取消候选。
- [x] 强平下单。
  预期：前置撤用户 reduce-only，生成系统 reduce-only 市价单。
- [x] 强平费。
  预期：按可收 collateral 封顶，不生成新的 deficit。
- [x] 保险基金覆盖亏损。
  预期：只减少显式 deficit，不增加用户 available。
- [x] ADL。
  预期：保险基金不足时按队列减少盈利方仓位并清除剩余 deficit，审计事件记录 `target_position_side`。
- [ ] 高频做市和并发用户流量下强平/ADL。
  预期：强平、保险基金、ADL 与普通成交并发时不出现重复扣款、负余额、非法 OI。

## WebSocket

- [x] 公开 depth 推送。
  预期：下单、成交、撤单后收到 depth snapshot/delta。
- [x] mark/funding 推送。
  预期：mark price 和 funding 事件可被客户端接收。
- [x] 私有订单推送。
  预期：只推给认证用户，不泄漏给其他订阅者。
- [x] 私有成交/持仓推送。
  预期：account 结算后收到 match/position 事件。
- [x] 私有风险推送。
  预期：`accountRisk` / `positionRisk` 按用户隔离 fanout。
- [x] 多用户私有 fanout 真实进程 smoke。
  预期：`scripts/market-maker-stress.sh` 可通过 `WS_FANOUT_USER_COUNT` 同时订阅多个普通用户私有 orders/positions；每个连接只收到对应认证用户事件，不串号。
- [ ] 生产级多用户长连接 fanout 压测。
  预期：大量用户订阅时私有推送无串号、延迟可观测、失败连接清理不影响其他连接。

## 性能与稳定性

- [x] exchange-core 裸撮合 benchmark。
  预期：只代表内存撮合能力，不代表系统吞吐。
- [x] 真实 order/matching/account/websocket/gateway 做市商压力 smoke。
  预期：订单全部成交结算，Kafka lag 最终为 0，无负余额，无非法 OI。
- [x] 做市商压力脚本支持连续刷新轮次。
  预期：`scripts/market-maker-stress.sh` 可通过 `MM_REFRESH_CYCLES` 和 `MM_REFRESH_INTERVAL_SECONDS` 持续执行多轮 maker 刷新挂单 + taker 流量，并继续输出 PostgreSQL/Kafka/JVM/WebSocket/account 结算指标。
- [x] 小规模连续做市刷新真实进程 smoke。
  预期：`MM_REFRESH_CYCLES=2` 的干净状态样本通过；报告记录连续 maker 刷新挂单 8 笔、普通用户 taker 订单 4 笔、撮合成交 4 笔、account 结算 4 笔、最终 Kafka lag 为 0。
- [x] 小规模多用户 WebSocket fanout 真实进程 smoke。
  预期：`WS_FANOUT_USER_COUNT=3` 的干净状态样本通过；报告记录 2 轮 maker 刷新、6 笔普通用户 taker 全部成交结算、Kafka lag 为 0，前 3 个 taker 用户均收到各自私有 orders/positions 推送。
- [x] 做市商参考盘口 WebSocket 本地订单簿和 REST 兜底。
  预期：`surprising-market-maker-provider` 可选 `reference-market.enabled=true` 后，`websocket-enabled=true` 时优先维护 Binance depth stream、OKX books、Bybit V5 orderbook 本地订单簿；无新鲜流式盘口时回退 REST depth/books/orderbook 快照。外部每档价格距离和数量会转换成本地 ticks/steps，并继续受 post-only、价格偏离、数量和库存上限保护；本地单档数量上限受策略 `baseQuantitySteps` 约束，避免外部深度超过本地做市资金预算；默认关闭，不影响现有本地静态报价模型。已补真实 full-stack smoke：`/tmp/surprising-full-stack-real-config.W2VWYd` 记录 Binance/Bybit WEBSOCKET 样本，提交报价 313、拒单 0、engine taker/account 5/5；180 秒持续做市复跑日志为 `/tmp/surprising-full-stack-real-config.xCbq79`，提交报价 1333、拒单 0、engine taker/account 30/30。
- [x] L4 单节点真实接口压测。
  预期：`docs/market-maker-stress-report-tier4.md` 记录 4 个 maker、50 档初始深度、5 轮刷新、10000 笔普通用户 taker、128 并发和 5 个私有 WebSocket 订阅用户的真实 gateway/order/matching/account/websocket 链路样本；最终全部成交结算，Kafka lag 回到 0，资金、预占、deficit、OI 和 outbox 不变量通过。
- [x] scheduled engine 报价期间 account provider 重启。
  预期：`MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true` 时脚本停止真实 account provider，仍通过 gateway 提交 taker 并等待 matching 成交；account provider 重启后补消费 match trades，最终 account 结算、资金、outbox、保险基金和 ADL 不变量通过。最新日志：`/tmp/surprising-full-stack-real-config.F0V0h2`。
- [x] 参考行情 WebSocket 报价期间 account provider 重启。
  预期：`MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true` 时，market-maker provider 记录 Binance/OKX/Bybit WebSocket 参考盘口样本并持续报价；account provider 重启后补消费 match trades，最终 account 结算、资金、outbox、保险基金和 ADL 不变量通过。最新日志：`/tmp/surprising-full-stack-real-config.8XwMJ0`。
- [x] matching/account/WebSocket 本机多节点 smoke。
  预期：consumer group 成员可识别，WebSocket 节点都收到 depth。
- [x] account failover。
  预期：停掉一个 account 节点后剩余节点消费完 match trades，最终 lag 为 0。
- [x] matching owner failover。
  预期：停止 symbol owner 后重启，开放订单恢复且可继续成交。
- [x] 真实 provider full-stack smoke。
  预期：不全量打包，`BUILD_SERVICES=auto` 在 jar 未变化时跳过 Maven package；脚本覆盖真实 provider 链路和做市商 run-once 铺单，最终 WebSocket/accounting invariants 通过。
- [x] 普通订单改单/批量改单真实链路 smoke。
  预期：通过 gateway 调用 `trading/amend` 和 `trading/batch-amend`，开放 LIMIT 订单按 cancel-replace 语义撤原单并提交替换单；脚本等待撮合确认和账户释放，最终替换单撤销干净，余额/预占/outbox 不变量通过。最新日志：`/tmp/surprising-full-stack-real-config.OKaFPW`。
- [x] Cancel All After / dead-man switch 真实链路 smoke。
  预期：通过 gateway 调用 `trading/cancel-all-after` 设置账户级倒计时；到期后 order-provider 后台任务撤销用户开放普通单，并通过 trigger-provider 撤销 pending TP/SL。最新日志：`/tmp/surprising-full-stack-real-config.EfyZWo`，`trading_cancel_all_after` 结果为 `TRIGGERED|1500|1|1`，普通单和条件单开放残留均为 `0`。
- [ ] 生产级长时间全链路压测。
  预期：使用 `MM_REFRESH_CYCLES` 或 market-maker scheduled engine 放大到更长时间持续运行，盘口参数跟随主流交易所，普通用户高并发下单、强平/ADL/资金费同时发生，并输出逐节点 p50/p95/p99 和数据库/Kafka/JVM 指标；停机窗口还要覆盖更完整的生产停机编排，避免 provider shutdown 后留下 attempts=0 的待发布事件。

## 当前设计限制

- [x] TP/SL 多档位支持原子组合提交。
  现状：多档仍通过多条 trigger order 表达；`POST /api/v1/trading/trigger-orders/batch` 支持 `atomic=true`，任一条校验失败时整组事务回滚，客户端收到逐项失败结果，数据库不留下部分条件单。真实 gateway smoke 已验证 `real-trigger-atomic-*` 残留为 `0`。
- [x] TP/SL trigger price type 支持 `MARK_PRICE`、`INDEX_PRICE` 和 `LAST_PRICE`。
  现状：`INDEX_PRICE` 不改 exchange-core，通过 trigger-provider 消费 index price topic、按 `trigger_price_type` claim 条件单，再生成 reduce-only 平仓单；`LAST_PRICE` 消费真实撮合成交 topic `surprising.perp.match.trades.v1`，使用成交事件里的 `priceTicks` 触发。最新成交价在薄盘口下更容易被短时冲击操纵，强平仍只使用 mark price。
- [x] 追踪止损 `TRAILING_STOP`。
  现状：`TRAILING_STOP` 作为 trigger-provider 条件单落库，`callbackRatePpm` 范围为 `1000..100000`，`activationPriceTicks` 可选；激活后维护最高/最低水位，回调达到比例后生成 reduce-only 市价平仓单。full-stack smoke 已通过真实 gateway + mark-price 激活/回调场景，日志为 `/tmp/surprising-full-stack-real-config.A8FcrR`。
- [x] Cancel All After 支持账户级和 symbol 级倒计时保护。
  现状：`POST /api/v1/trading/orders/cancel-all-after` 保存 `trading_cancel_all_after` timer；`countdownMs=0` 关闭，正数刷新触发时间。到期任务会复用普通订单 `cancel-open` 并调用 trigger-provider 的 `cancel-open`，真实 gateway smoke 已验证可同时撤开放普通单和 pending TP/SL。tag 级过滤需要订单/tag 维度建模后再扩展。
- [x] TWAP / Iceberg 算法单。
  现状：算法单作为 order-provider 父单实现，父单不进入 exchange-core；TWAP 按时间片发 IOC 子单，Iceberg 同一时间维护一笔可见子单。子单都是普通订单，继续走撮合、账户、风控、强平检查和 WebSocket fanout。真实 gateway full-stack smoke 已验证放置、查询、成交完成、`cancel-open`、活动算法单阻断持仓模式切换和资金/outbox 不变量。
- [!] 主流交易所 WebSocket 本地订单簿尚未进入长时间真实进程压测。
  原因：market-maker provider 已支持 Binance/OKX/Bybit WebSocket 本地订单簿和 REST 快照兜底，并已有 30 秒参考行情 full-stack smoke、90 秒参考行情 + account 重启组合 smoke 和 180 秒持续做市 smoke；但还没有把该能力放进长时间、多节点、多 broker 的生产级真实进程压测并记录报告。
