# 集成验证报告

本文记录 Surprising Exchange 永续合约交易链路当前已有的验证证据。
报告只把有测试、数据库 smoke 或构建门禁证明过的内容列为已验证。

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

- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-trigger-provider,:surprising-gateway-provider,:surprising-order-provider,:surprising-account-provider,:surprising-risk-provider,:surprising-liquidation-provider,:surprising-funding-provider,:surprising-integration-test -am test`：通过，覆盖 TP/SL 条件推导、`clientTriggerOrderId` 幂等、gateway trigger 路由、mark price payload 解析、Kafka consumer 可重放配置、`FOR UPDATE SKIP LOCKED` 抢占触发单、生成 reduce-only 平仓单和核心 Java 交易链路。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-order-provider,:surprising-account-provider,:surprising-risk-provider,:surprising-liquidation-provider,:surprising-funding-provider,:surprising-integration-test -am test`：通过，覆盖用户杠杆配置、按风险档位冻结订单保证金、自动 VIP 手续费档位、手续费 source 优先级、手续费快照、账户结算、风控、强平、资金费和 Java 集成链路。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-liquidation-provider -am test`：通过，覆盖强平候选复核、分阶段 sizing、reduce-only 前置撤单，以及撮合结果后候选生命周期更新的 fail-fast 行为。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-risk-provider -am test`：通过，覆盖风控账户组 keyset 分页扫描、账户持仓事件触发风控扫描、账户组级 mark price 新鲜度和多节点扫描租约行为。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-integration-test -am test`：通过，覆盖 order -> matching -> account -> risk -> liquidation -> funding -> insurance -> ADL 的 Java 集成链路。
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q test`：通过，覆盖当前全部 Maven 模块测试。
- `KEEP_TMP=true BUILD_SERVICES=false ./scripts/full-stack-real-config-smoke.sh`：在加入 TP/SL 触发场景前曾通过，覆盖真实 PostgreSQL/Kafka、多 provider、WebSocket、高频下单、深度盘口、资金费、强平、保险基金和 ADL 链路。

## 证据矩阵

| 范围 | 当前证据 |
| --- | --- |
| Schema 初始化 | `scripts/integration-smoke.sh` 会启动临时 PostgreSQL 并导入根目录 `init.sql`。 |
| 真实 Kafka 交易 smoke | `scripts/kafka-trading-smoke.sh` 会启动 Docker Compose PostgreSQL/Kafka，把 `init.sql` 导入隔离的 smoke 数据库，创建 Kafka topics，启动 order/matching/account 三个 provider，通过 REST 给两个用户入金并提交可成交的对手单，等待 exchange-core 撮合和 account Kafka 结算完成，然后把同一条 match-trade payload 重新发布到 Kafka，验证账户持仓和 processed-trade 行数不变化。`scripts/kafka-trading-load-smoke.sh` 会额外启动 WebSocket，订阅 depth/private 频道，并检查全部成交、部分成交后撤单、只撤单、全部撤单、并发 maker/taker 用户、持仓正确性、盘口深度推送和私有订单/撮合/持仓推送接收。`scripts/full-stack-real-config-smoke.sh` 现在会启动 instrument、candlestick、index-price、mark-price、order、matching、account、risk、liquidation、funding、insurance、ADL、websocket、trigger、gateway 全部 provider，并包含 TP/SL 触发执行场景。这次改动后还需要重新跑一次 full-stack，才能形成 TP/SL 的新进程级证据；上面的最近验证是 Maven 级别。 |
| long 定点模型 | 订单、撮合、账户、风控、强平、资金费、保险基金、ADL 核心链路使用 ticks、steps、ppm、asset units；`CoreFixedPointArchitectureTest` 会拒绝这些 main Java 路径里的 `BigDecimal`、`double`、`float`，相关数学测试覆盖溢出敏感逻辑，也覆盖会改变容量或风险的 checked 聚合。 |
| 共享合约公式 | `PerpetualContractMathTest` 验证共享的线性/反向 notional、未实现 PnL、每 step notional、初始保证金、维持保证金和溢出行为；account、risk、funding、liquidation、ADL 共同使用这套公式。 |
| instrument version 锁定 | `integration-smoke.sh` 把 BTC-USDT 当前版本切到 v2，并验证已有 v1 持仓仍用 v1 规则计算风险和资金费。 |
| 下单校验 | `TraceContextTest`、`TraceIdFilterTest`、`OrderValidatorTest`、`OrderMarginMathTest`、`LeverageServiceTest`、`ReduceOnlyValidatorTest`、`OrderServiceTest`、`OrderRepositoryTest`、`OrderMarginRepositoryTest`、`OrderMarkPriceRepositoryTest`、`MarketPriceProtectionTest` 覆盖 traceId 清洗、请求作用域清理、订单事件和 command payload 传递、显式 `CROSS` 和 `ISOLATED` 保证金模式传递、用户杠杆配置、instrument 最大杠杆校验、按风险档位在冻结保证金前拒绝超限杠杆、订单边界、币本位 notional、市价可成交区间 notional 上限、新鲜 mark price 校验、long-only 初始保证金公式，包括线性合约市价 SELL 按上边界冻结抵押、按保证金模式隔离且带 checked 待平聚合的 reduce-only 可平量、限定范围的 `clientOrderId` 幂等不重复冻结、以及保证金冻结 guarded update。 |
| 交易手续费档位和快照 | `TradingFeeServiceTest`、`OrderFeeRepositoryTest`、`FeeTierServiceTest`、`TradeFeeMathTest`、`AccountServiceTest` 验证 instrument 默认 maker/taker 费率、用户全局和单 symbol 覆盖、风控/人工/活动/做市商费率优先于自动 VIP 的 source 优先级、按 30 日成交名义价值和账户资产估值自动刷新 VIP 档位、自动生成用户全局费率的激活/禁用、下单入口不可变手续费快照，以及账户按该快照写手续费扣款/返佣 ledger。 |
| 止盈止损条件单 | `TriggerOrderServiceTest`、`TriggerOrderRepositoryTest`、`MarkPriceTriggerParserTest`、`TriggerKafkaConfigurationTest` 验证 TP/SL 触发条件推导、限定用户范围的 `clientTriggerOrderId` 幂等、mark price 事件解析、保持 key 语义的 Kafka consumer 配置、`FOR UPDATE SKIP LOCKED` 抢占到期触发单、通过 order-provider 生成 reduce-only 平仓单、生成订单被拒后转为 `TRIGGER_FAILED`，以及 stale `TRIGGERING` 重置重试。 |
| 跨模块 Java 链路 | `PerpetualTradingChainIntegrationTest` 使用真实 exchange-core 和内存 adapter，验证下单 -> 撮合 -> 账户结算 -> U 本位线性和币本位反向用户 reduce-only 主动平仓、币本位保证金释放和已实现 PnL、市价单风险边界冻结 -> 更优价格成交 -> 差额保证金释放、线性合约市价 SELL 在吃到高于 mark 的买单前已按上边界足额冻结、用户撤单 -> exchange-core cancel -> 预冻结保证金释放、部分成交 -> 撤剩余单 -> 只释放未成交委托保证金且保留已迁移仓位保证金，以及风险候选 -> 强平 reduce-only 市价单 -> 强平成交结算。`PostLiquidationFundingInsuranceAdlIntegrationTest` 验证资金费结算 -> 账户余额/保证金迁移、保险基金亏损覆盖、ADL 剩余亏损转移。 |
| exchange-core 撮合 | `ExchangeCoreEngineRecoveryTest` 验证 DB 开放订单恢复到 exchange-core，并拒绝交叉订单簿恢复。 |
| matching fail-fast 恢复 | `MatchingCommandConsumerTest`、`MatchingPartitionAssignmentGuardTest`、`MatchingResultRepositoryTest`、`MatchingOutboxRepositoryTest` 验证已解析 command 处理失败会请求 matcher 重启后再让 Kafka 重放；matcher 处理过命令后遇到 partition reassignment/lost 会重启；撮合结果/成交/outbox 写入冲突会 fail-fast；无法解析的 payload 不会误标记 partition 已处理。 |
| Kafka 运行语义 | `TradingOrderKafkaConfigurationTest`、`MatchingKafkaConfigurationTest`、`AccountKafkaConfigurationTest`、`RiskKafkaConfigurationTest`、`LiquidationKafkaConfigurationTest`、`FundingKafkaConfigurationTest` 锁定 producer 使用 `acks=all`、幂等、`zstd` 和受限 in-flight 请求，并锁定 consumer 使用可重放的 `earliest`、关闭 auto commit、cooperative-sticky assignment 和 Spring Kafka record 级 ack。 |
| 前端 Gateway 和 WebSocket fanout | `GatewayTraceFilterTest` 和 `GatewayProxyControllerTest` 验证 gateway traceId 清洗/转发、白名单目标 URI 拼接和私有路由身份校验；`GatewayHttpConfigurationTest` 验证 gateway 后端连接/读取超时；`SubscriptionTopicTest` 验证公共/私有订阅标准化和用户 id 校验，包括公共 depth 频道；`KafkaFanoutConsumerTest` 验证盘口深度 Kafka 事件会 fanout 到 `channel=depth`；`WebSocketKafkaConfigurationTest` 验证 WebSocket 每节点 fanout consumer 使用 `latest`、关闭 auto commit、cooperative-sticky assignment 和 record 级 ack；`WebSocketServerConfigurationTest` 验证可配置 WebSocket Origin 白名单和本地开发回退。 |
| symbol-key 分区不变量 | `KafkaSymbolKeyValidatorTest`、`MatchingCommandConsumerTest`、`MatchTradeConsumerTest`、`PositionRiskTriggerConsumerTest`、`LiquidationCandidateConsumerTest` 验证 matching command、account match trade、risk position trigger、liquidation candidate 在 Kafka record key 和 payload `symbol` 不一致时，会在业务处理前被拒绝。 |
| Outbox 重试语义 | `OutboxRepositoryTest`、`MatchingOutboxRepositoryTest`、`RiskOutboxRepositoryTest`、`FundingOutboxRepositoryTest` 验证未发布且到期的 outbox 行会用 `FOR UPDATE SKIP LOCKED` 认领；Kafka 发送失败会增加 attempts、记录截断后的错误，并通过 `next_attempt_at` 安排有上限的指数退避重试。 |
| 撮合服务输出 | `MatchingServiceTest` 启动真实 exchange-core 完成撮合，并验证 maker/taker `MatchTradeEvent` 的 instrument version、maker/taker 保证金模式查询和传递、command traceId 传递到撮合结果/成交、订单簿 `SNAPSHOT`/`DELTA` 输出中的剩余数量和删除价格档、command 幂等、重复 result/trade 冲突跳过副作用，以及 `CANCEL` 命令不会被 post-only 流动性检查误拒绝；`MatchingResultRepositoryTest` 验证 result/trade 幂等键。 |
| 撮合持久化副作用 | `MatchingResultRepositoryTest` 验证撤单和 immediate 终态订单会先释放保证金，再清空 remaining quantity；订单成交数量更新使用 guarded update，不做静默夹取；非 reduce-only 订单缺失 reservation 会失败；同时验证冻结余额不足时保证金释放会失败，而不是把不存在的冻结金额加回可用余额。 |
| 账户持仓、PnL、手续费和持仓推送源 | `PositionCalculatorTest`、`MarginTransferMathTest`、`PnlSettlementMathTest`、`TradeFeeMathTest`、`AccountRepositoryTest`、`AccountServiceTest`、`ReduceOnlyOrderPrunerTest` 验证线性/反向合约 PnL、maker/taker 手续费按订单快照扣款和返佣、手续费 ledger 保存 `trade_id/order_id/symbol/fee_rate_ppm` 审计字段、按保证金模式隔离的持仓和持仓保证金写入、按实际成交价迁移保证金、委托价/市价保护价多冻结差额释放、翻仓成交先平旧仓再把剩余数量作为新仓消费开仓保证金、开仓/平仓成交缺失必要 reservation 时 fail-fast、平仓保证金释放、`TRADE_PNL`/`TRADE_FEE` ledger 写入 fail-fast、`(symbol, trade_id)` 成交幂等，包括不同 symbol 下同号 tradeId 不互相去重、持仓变化后剪枝超额/反向 reduce-only 挂单、checked reduce-only 容量数学、已实现亏损只扣持仓保证金支撑的 locked collateral，以及双边结算完成后才写带 traceId 的持仓更新 outbox。 |
| 风控和爆仓候选 | `RiskMathTest`、`RiskRepositoryTest`、`RiskOutboxRepositoryTest`、`PositionRiskTriggerConsumerTest`、`RiskServiceTest` 覆盖权益/保证金率状态、long-only 线性/反向 notional/PnL/维持保证金公式、`userId + settleAsset` 账户组 keyset 分页扫描、账户持仓事件携带保证金模式触发账户组扫描、完全平仓后的账户和仓位 0 风险快照、整组 fresh mark 校验、全仓账户级 PnL 和维持保证金 checked 聚合、逐仓按仓位保证金权益计算风险、快照/outbox fail-fast 写入、按 `userId + settleAsset` 隔离的风控扫描事务、多节点 provider 的 PostgreSQL 扫描租约获取/跳过行为、按 `userId + symbol + marginMode` 限定 active 候选冲突、候选事件一致性；`integration-smoke.sh` 验证风险快照和活跃强平候选唯一性。 |
| 强平执行 | `LiquidationSizingPolicyTest`、`LiquidationSideResolverTest`、`LiquidationServiceTest`、`LiquidationRepositoryTest`、`LiquidationOrderRepositoryTest` 验证分阶段 sizing、基于共享公式计算 sizing notional、平仓方向、instrument version 传递、新鲜风险快照复核、带 checked 待平聚合的强平前置撤用户 reduce-only 平仓挂单、按完整实时持仓而不是 pending close 容量做强平 sizing、候选状态 fail-fast 更新、撮合结果生命周期更新时如果候选不能继续从 `PROCESSING` 推进则 fail-fast、强平 trading order 插入不做宽泛冲突吞噬，以及强平审计/outbox 写入被冲突跳过时 fail-fast。 |
| 资金费 | `FundingMathTest`、`FundingTimeTest`、`FundingRepositoryTest`、`FundingOutboxRepositoryTest`、`FundingServiceTest`、`PostLiquidationFundingInsuranceAdlIntegrationTest` 验证 long ppm 方向、UTC funding 时间、基于共享公式按持仓版本折算 notional、费率/outbox fail-fast 发布、按 `symbol + fundingTime` 隔离结算事务、结算/账户 fail-fast 更新、资金费扣款不消耗开放订单冻结，以及资金费亏损只先消耗持仓保证金支撑的 locked collateral，之后才形成 deficit。 |
| 保险基金 | `InsuranceMathTest` 验证全额/部分覆盖；`InsuranceRepositoryTest` 验证基金调整幂等、覆盖流水冲突 fail-fast、repository 层部分覆盖会写入保险基金负流水和亏损账户正向覆盖流水，以及保险基金为空时不修改 `account_deficits`，把亏损留给后续补充保险基金或 ADL 扫描；`integration-smoke.sh` 和 `PostLiquidationFundingInsuranceAdlIntegrationTest` 验证部分覆盖只减少 `account_deficits`，不增加 available balance，并在保险基金耗尽后把剩余 deficit 留给 ADL。 |
| ADL | `AdlMathTest` 验证队列优先级；`AdlRepositoryTest` 验证基于共享公式计算候选 notional/盈利、ADL 执行前锁定保险基金余额、repository 层成功 ADL 会减少目标仓位、释放保证金、转移已实现盈利并清空 deficit，同时验证目标仓位更新和 guarded 保证金释放 fail-fast；`integration-smoke.sh` 和 `PostLiquidationFundingInsuranceAdlIntegrationTest` 验证 ADL 减仓、释放保证金、转移盈利和清除剩余亏损。 |
| 运营熔断开关 | `RiskServiceTest`、`LiquidationServiceTest`、`FundingServiceTest`、`InsuranceServiceTest`、`AdlServiceTest` 验证风险扫描、强平执行、资金费率发布、资金费结算、保险基金覆盖、ADL 扫描都可以暂停，且暂停时不会创建候选、claim 强平任务、写账户变化或修改 deficit。 |
| 价格服务 | 指数价格和标记价格测试覆盖异常源剔除、标记价格计算、外部 ticker 解析，以及在 price 边界持久化 `mark_price_units` 后由核心模块按版本转换成 ticks。 |
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

Java 集成测试现在覆盖 order、matching、account、risk、liquidation、funding、insurance、ADL 服务类之间的事件交接，不需要启动真实 Kafka/PostgreSQL 进程。它补充了 PostgreSQL smoke，后者更侧重 schema 和跨表不变量。Kafka 交易 smoke 补上真实 Kafka/PostgreSQL 进程下 order -> matching -> account 的证据；在 Docker Compose 可用时，load/full-stack smoke 会进一步覆盖 WebSocket 盘口深度和私有推送断言。full-stack 脚本已经补了 TP/SL 触发执行，但这个新的进程级场景还需要重新跑一次 full-stack。

本地 PostgreSQL smoke 还验证了单元测试不容易覆盖的跨表约束：

- 旧持仓不会被新 instrument 版本重新解释；
- 资金费 notional 折算使用持仓自己的 instrument version；
- 活跃强平候选按 `user_id + symbol` 唯一；
- 保险基金覆盖只减少显式 deficit；
- ADL 会减少盈利目标仓位，并把实现盈利转移去清除剩余亏损。

## 生产前剩余工作

当前门禁适合作为开发验证，但还不能替代生产上线验证。
生产前还需要补充并记录：

- 扩展真实多进程 Kafka 端到端测试，订单、撮合、账户、风控、强平、保险基金、ADL 服务同时运行；
- matching partition rebalance/failover、Kafka outbox 重放、PostgreSQL 锁竞争测试；
- 订单峰值、成交峰值、mark price 更新频率、风控扫描频率的压测；
- PostgreSQL 重启、Kafka broker 故障、mark price 过期、外部交易所断线的故障演练；
- 在真实集群里验证这些运营暂停开关，并补充保险基金充值、symbol halt、instrument version 上线的 runbook。

## 当前结论

当前代码已经形成一条使用 long 定点数、以 exchange-core 为撮合核心、用 PostgreSQL 幂等和锁控制状态迁移的永续合约交易链路。
本地验证门禁已经证明关键会计公式和跨表不变量。完整生产级就绪仍需要真实多服务 Kafka 集成测试和压测/故障演练证据。
