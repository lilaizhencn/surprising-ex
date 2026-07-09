# surprising-ex

[English](README.md) | [简体中文](README_CN.md)

Surprising 多产品线交易所后端服务。

这个仓库是交易所后端模块的根聚合工程。当前在同一套服务架构下支持按产品线隔离运行的现货、U 本位永续、U 本位交割和欧式普通期权链路。每个业务模块维护自己的详细 README 和部署说明。

## 项目解读

如果想了解项目整体架构和实现细节，可以阅读 Surprising-EX 源码和产品线文章：

- 源码深度解读：[中文](https://tokdou.com/tutorials/surprising-ex-perpetual-contract-exchange-source-code-deep-dive) / [English](https://tokdou.com/en/tutorials/surprising-ex-perpetual-contract-exchange-source-code-deep-dive)
- 四产品线架构方案：[中文](https://tokdou.com/tutorials/surprising-ex-four-product-line-architecture-okx-binance-comparison) / [English](https://tokdou.com/en/tutorials/surprising-ex-four-product-line-architecture-okx-binance-comparison)
- 交割合约和期权小白指南：[中文](https://tokdou.com/tutorials/delivery-contract-and-options-beginner-guide) / [English](https://tokdou.com/en/tutorials/delivery-contract-and-options-beginner-guide)

## 模块

- `surprising-dependencies`：从 `surprising-wallet` 复制过来的统一依赖版本管理模块。
- `surprising-parent`：从 `surprising-wallet` 复制过来的公共父 POM。
- `surprising-product-api`：公共产品线枚举、账户类型映射和产品线 topic 命名。
- `surprising-instrument`：现货、永续、交割和期权的基础配置和产品规则中心。
- `surprising-candlestick`：按产品线隔离的 K 线服务。
- `surprising-price`：衍生品指数价格和标记价格服务。
- `surprising-trading`：订单入口、止盈止损条件单、算法单、产品线 Kafka 路由和 exchange-core 撮合。
- `surprising-account`：账户余额、余额流水、产品账户、产品流水、持仓、保证金、现货结算、衍生品结算、交割和期权行权账务。
- `surprising-margin-ops`：风险快照、爆仓候选、强平、资金费、保险基金和 ADL 的 API/provider，以及合并部署 provider。
- `surprising-edge`：面向前端的接入层模块，内部包含 REST gateway、WebSocket fanout 和开发/小规模部署用的合并 edge provider。
- `surprising-market-maker`：内网做市商报价和交易链路压测策略服务。

## 支持的产品线

| 产品线 | `ProductLine` | 账户类型 | 合约类型 | 资金费 | 生命周期 |
| --- | --- | --- | --- | --- | --- |
| 现货 | `SPOT` | `SPOT` | `SPOT` | 否 | 即时资产互换，无持仓 |
| U 本位永续 | `LINEAR_PERPETUAL` | `USDT_PERPETUAL` | `LINEAR_PERPETUAL` | 是 | 无到期日 |
| U 本位交割 | `LINEAR_DELIVERY` | `USDT_DELIVERY` | `LINEAR_DELIVERY` | 否 | 到期现金交割 |
| 欧式普通期权 | `OPTION` | `OPTION` | `VANILLA_OPTION` | 否 | 到期现金行权 |

`INVERSE_PERPETUAL` 和 `INVERSE_DELIVERY` 已经存在于公共产品线 API 和 topic 映射里；当前进程级 smoke 主覆盖集中在上面四条产品线。

## 模块文档

- [docs 文档索引](docs/README.md)
- [部署说明](docs/deployment.md)
- [数据库设计](docs/database.md)
- [产品线拆分与交割/期权落地方案](docs/product-line-split-plan_CN.md)
- [产品线资金守恒与账账核对](docs/product-line-testing-and-funds-reconciliation_CN.md)
- [全链路功能测试清单](docs/full-chain-test-plan_CN.md)
- [四产品线资金与性能压测报告](docs/full-chain-funds-performance-report.md)
- [Matching symbol 分片和上线容量注意事项](docs/matching-symbol-sharding-and-capacity_CN.md)
- [永续合约交易教程和系统实现说明](docs/perpetual-contract-tutorial_CN.md)
- [surprising-candlestick](surprising-candlestick/README_CN.md)
- [surprising-instrument](surprising-instrument/README_CN.md)
- [surprising-price](surprising-price/README_CN.md)
- [surprising-trading](surprising-trading/README_CN.md)
- [surprising-account](surprising-account/README_CN.md)
- [surprising-margin-ops](surprising-margin-ops/README_CN.md)
- [surprising-edge](surprising-edge/README_CN.md)
- [surprising-edge/surprising-websocket](surprising-edge/surprising-websocket/README_CN.md)
- [surprising-edge/surprising-gateway](surprising-edge/surprising-gateway/README_CN.md)
- [surprising-market-maker](surprising-market-maker/README_CN.md)

## 构建

使用 JDK 21。matching provider 依赖 exchange-core/OpenHFT Chronicle，运行时需要下面的 Java module 参数。

```bash
mvn test
mvn -DskipTests package
```

```bash
export JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
```

Provider 打包会保留普通 jar 作为模块依赖产物，并额外生成带 `exec` classifier 的 Spring Boot 可执行 jar，例如 `surprising-trading-entry-provider-1.0.0-SNAPSHOT-exec.jar`。

## 数据库初始化

```bash
brew services start postgresql@18
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

本仓库是新项目，数据库 schema 统一放在根目录 [init.sql](init.sql)，不使用 Flyway。
本地集成测试统一使用 Homebrew PostgreSQL/Kafka/Redis，标准端口是 `localhost:5432`、`localhost:9092`、`localhost:6379`；详细配置见 [docs/local-homebrew-infra.md](docs/local-homebrew-infra.md)。

## 本地启动顺序

```bash
brew services start postgresql@18
brew services start kafka
brew services start redis
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-instrument-provider -am spring-boot:run
mvn -pl :surprising-candlestick-provider -am spring-boot:run
mvn -pl :surprising-price-provider -am spring-boot:run
mvn -pl :surprising-trading-entry-provider -am spring-boot:run
JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" \
mvn -pl :surprising-matching-provider -am spring-boot:run
mvn -pl :surprising-account-provider -am spring-boot:run
mvn -pl :surprising-margin-ops-provider -am spring-boot:run
mvn -pl :surprising-edge-provider -am spring-boot:run
mvn -pl :surprising-market-maker-provider -am spring-boot:run
```

端口：

- `9080`：合约基础配置服务。
- `9081`：K 线服务。
- `9082`：price 合并服务，包含指数价格、标记价格和法币汇率；拆分部署时为指数价格服务。
- `9083`：拆分部署时的标记价格服务。
- `9084`：trading-entry 合并服务，包含普通订单入口和条件单。
- `9085`：exchange-core 撮合服务。
- `9086`：账户和持仓服务。
- `9087`：拆分部署时的风险服务。
- `9088`：margin-ops 合并服务；拆分部署时为强平执行服务。
- `9089`：拆分部署时的资金费率服务。
- `9090`：拆分部署时的保险基金服务。
- `9091`：拆分部署时的 ADL 服务。
- `9093`：拆分部署时的前端 WebSocket 推送服务。
- `9094`：edge 合并服务，包含统一 REST API gateway 和 `/ws/v1`；拆分部署时也可以作为独立 REST gateway。
- `9095`：拆分部署时的止盈止损条件单服务。
- `9096`：内网做市商服务。

## Kafka Topics

- `surprising.instrument.events.v1`：合约配置变更事件。
- `surprising.perp.candle.events.v1`：K 线快照输出。
Legacy 永续 topic 仍保留用于兼容单线启动。产品线实例使用 `surprising.<product-segment>.*.v1`，例如 `surprising.spot.order.commands.v1`、`surprising.linear-perp.match.trades.v1`、`surprising.linear-delivery.delivery.settlements.v1` 和 `surprising.option.option.exercises.v1`。

- `surprising.perp.order.commands.v1`：legacy 永续订单撮合命令。
- `surprising.perp.order.events.v1`：订单入口事件。
- `surprising.perp.match.results.v1`：撮合结果事件。
- `surprising.perp.match.trades.v1`：撮合成交事件，long 定点数，供 K 线和公开成交推送消费。
- `surprising.perp.orderbook.depth.v1`：exchange-core L2 盘口 `SNAPSHOT`/`DELTA` 事件，供前端深度推送消费。
- `surprising.account.position.events.v1`：账户持仓变化事件，供私有 WebSocket 推送消费。
- `surprising.perp.liquidation.candidates.v1`：爆仓候选事件。
- `surprising.perp.index.price.v1`：指数价格输出。
- `surprising.perp.index.components.v1`：指数成分审计输出。
- `surprising.perp.book.ticker.v1`：合约盘口最优价输入。
- `surprising.perp.funding.rate.v1`：资金费率输出，供 mark price 服务消费。
- `surprising.perp.mark.price.v1`：标记价格输出。
- `surprising.perp.mark.price.audit.v1`：标记价格审计输出。

所有市场数据 topic 都使用 `symbol` 作为 Kafka key。

## API 快速检查

```bash
curl 'http://localhost:9080/api/v1/instruments/latest?symbol=BTC-USDT'
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
curl 'http://localhost:9082/api/v1/price/mark/latest?symbol=BTC-USDT'
curl -X POST 'http://localhost:9084/api/v1/trading/orders' -H 'Content-Type: application/json' -d '{"userId":1001,"clientOrderId":"cli-1001-1","symbol":"BTC-USDT","side":"BUY","orderType":"LIMIT","timeInForce":"GTC","priceTicks":650000,"quantitySteps":10,"reduceOnly":false,"postOnly":false}'
curl -X POST 'http://localhost:9084/api/v1/trading/trigger-orders' -H 'Content-Type: application/json' -d '{"userId":1001,"clientTriggerOrderId":"tp-1001-1","ocoGroupId":"bracket-1001-1","symbol":"BTC-USDT","side":"SELL","triggerType":"TAKE_PROFIT","triggerPriceType":"MARK_PRICE","triggerPriceTicks":700000,"orderType":"MARKET","timeInForce":"IOC","priceTicks":0,"quantitySteps":10,"marginMode":"CROSS"}'
curl 'http://localhost:9088/api/v1/funding/rates/latest?symbol=BTC-USDT'
curl 'http://localhost:9088/api/v1/insurance/balances?asset=USDT'
curl 'http://localhost:9088/api/v1/adl/queue?asset=USDT&limit=100'
curl 'http://localhost:9094/api/v1/gateway/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9094/api/v1/gateway/account/1001/positions' -H 'X-User-Id: 1001'
```

前端流量默认走 `surprising-edge` 的 `9094` 端口：REST 使用 `/api/v1/gateway/**`，实时流量使用 `/ws/v1`。
生产环境如果 WebSocket 长连接很多，继续拆分 `surprising-gateway-provider` 和 `surprising-websocket-provider`，让 WebSocket fanout 独立扩容。

## 本地集成 Smoke

使用临时 PostgreSQL 跑数据库级链路检查：

```bash
./scripts/integration-smoke.sh
```

脚本会导入 [init.sql](init.sql)，验证 instrument version 锁定、U 本位线性合约、币本位反向合约、资金费 notional 折算、风险快照、强平候选幂等、保险基金亏损覆盖和 ADL 亏损转移记账。设置 `RUN_MAVEN=true` 可以在同一次执行里顺带运行单元测试。

逐线运行真实进程级产品线 API flow：

```bash
PRODUCT_LINES=LINEAR_PERPETUAL BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=LINEAR_DELIVERY BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=OPTION BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=SPOT BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
```

产品线 smoke 每次只启动当前线所需 provider，让做市商保持运行，模拟普通用户通过 API 下单，覆盖持仓形成、用户主动平仓、强平、风控、撮合、适用产品线的资金费、交割/行权，结束后执行 `scripts/product-line-funds-reconcile.sh`。资金核对会把期初、充值/调整、成交、手续费、资金费、强平费、交割/行权流水和期末余额用整数单位逐项对平。

可以直接运行真实 Kafka/PostgreSQL 进程级交易 smoke：

```bash
./scripts/kafka-trading-smoke.sh
```

它会创建隔离的 smoke 数据库，启动 order/matching/account providers，通过 REST 提交可成交的对手单，等待 exchange-core 撮合和 account Kafka 结算，再重放同一条 match-trade payload 验证账户幂等。
更重的进程级测试会同时启动 WebSocket，并验证全部成交、部分成交后撤单、只撤单、全部撤单、并发用户、持仓正确性、盘口深度推送和私有推送接收：

```bash
PAIR_COUNT=50 LOAD_CONCURRENCY=16 ./scripts/kafka-trading-load-smoke.sh
```

这些 smoke/load 脚本默认使用本机 Homebrew 中间件，不会启动 Docker PostgreSQL/Kafka/Redis。`PAIR_COUNT` 控制并发撮合场景里的 maker/taker 用户组数量。

盘口深度客户端应先拉公共 REST 快照，再套 WebSocket 增量：

```bash
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
```

## 生产部署要点

- 每个 provider 至少部署 2 个实例。
- Kafka topic replication factor 生产建议为 `3`。
- Instrument 是全系统唯一 symbol 和交易规则配置源。
- 产品线实例必须使用独立的 Kafka topic、consumer group、matching client id、price/risk/funding 协调 id 和 gateway route。详见 [docs/deployment.md](docs/deployment.md)。
- Trading 订单入口使用 long 定点数：`priceTicks`、`quantitySteps` 和资产 `units` 对齐 exchange-core；订单、撮合、账户、风控、强平、资金费、保险基金和 ADL 核心链路都不使用 BigDecimal。小数只允许存在于外部行情/汇率解析、后台录入、展示和报表边界。
- Matching 使用 `exchange-core` 真实订单簿消费订单命令，输出 long 定点数撮合结果和成交事件。
- Matching 启动时从 PostgreSQL 恢复开放的 `LIMIT` + `GTC/GTX` 订单簿；遇到不安全的 Kafka partition 重新分配会重启，让 failover 走新鲜 DB 恢复。
- Matching 在 command 已解析但处理失败时也会退出，让 Kafka 重放发生在 DB 订单簿恢复之后，而不是在可能已被修改的内存订单簿上重试。
- Matching 释放订单冻结保证金时要求 `locked_units` 足额；不足会失败并触发重启恢复，不能把异常冻结余额静默释放成可用余额。
- 市价单在订单入口使用新鲜 mark price 的可成交区间校验 notional，matching 再按买卖方向保护价提交 exchange-core；线性合约市价单无论 BUY/SELL 都按上边界冻结初始保证金，保证 SELL 市价单吃到高买价时不会抵押不足。matching 会拒绝会自成交的 taker 订单。
- 普通订单改单由 order-provider 对开放 LIMIT 订单执行 cancel-replace；替换单使用新的 `newClientOrderId`，重新走普通订单校验和资金预占，原单释放仍由撤单撮合结果和 account 结算完成。
- TWAP 和 Iceberg 算法单是 order-provider 里的父单，不进入实时订单簿。调度出的子单仍是普通 order-provider 订单，所以成交继续经过 exchange-core 撮合、账户结算、风控、强平检查和 WebSocket fanout。活动算法单会阻断保证金模式和持仓模式切换，直到取消或完成。
- 止盈、止损和追踪止损都是条件单，触发前只保存在 `trading_trigger_orders`，不进入 exchange-core 订单簿；配置的 `MARK_PRICE`、`INDEX_PRICE` 或 `LAST_PRICE` 触发源满足触发规则后，`surprising-trigger-provider` 通过 order-provider 提交幂等的 `reduceOnly=true` 平仓单，`clientOrderId=trigger-<triggerOrderId>`。追踪止损使用整数 `callbackRatePpm`（`1000` = `0.1%`，`100000` = `10%`）和可选 `activationPriceTicks`，激活后维护最高/最低水位，达到回调幅度才触发。`LAST_PRICE` 来自真实撮合成交，薄盘口下比 mark/index 更容易受短时冲击影响。
- 成对 TP/SL 可以使用同一个 `ocoGroupId`；同一用户、symbol、保证金模式组里任意一条 pending 触发单被抢占后，其它 pending sibling 会在同一条数据库语句里先置为 `CANCELED`，然后再提交生成的平仓单。
- Account 消费撮合成交，按 `tradeId` 幂等更新 long-based 净持仓，把开仓成交保证金迁移到持仓保证金，并把已实现盈亏结算进余额。
- `CROSS` 和 `ISOLATED` 保证金模式会从下单一路传到撮合、账户、风控、资金费和强平。全仓亏损可以使用全仓可用余额和全仓持仓保证金；逐仓亏损只使用该 symbol 的逐仓持仓保证金，亏穿后记录 deficit。
- 逐仓持仓保证金可以通过 account-provider 手动追加或减少。追加会把可用余额转入持仓保证金；减少必须依赖最新风险快照，并保证减少后权益仍高于维持保证金加配置缓冲。
- 用户杠杆配置按 `userId + symbol + marginMode` 生效；订单入口会在冻结初始保证金前按当前风险档位重新校验配置杠杆。
- 自动 VIP 手续费档位由 order-provider 根据 30 日成交名义价值和账户资产估值计算，再写回用户全局 `VIP` 费率。风控、人工、活动和做市商费率拥有更高 source 优先级。
- Account 和 Funding 的亏损结算只会扣持仓保证金支撑的 locked collateral；未成交订单冻结不会被 PnL 或资金费扣款消耗。
- Risk 使用 mark price、instrument 风险参数和 account 持仓/余额生成保证金快照和爆仓候选。
- Risk provider 使用 PostgreSQL `risk_scan_leases` 按 `userId + settleAsset` 协调扫描，多节点部署时不会对同一个风险组重复写快照或候选。
- Liquidation 消费爆仓候选，复核风险并前置撤同方向用户 reduce-only 平仓挂单后，按实时完整持仓生成分阶段 reduce-only 市价平仓订单进入统一订单/撮合链路。
- Liquidation 会把过期或缺失的风险快照视为非强平状态；`surprising.liquidation.risk.max-snapshot-age` 默认 `5s`。
- Liquidation 创建强平订单时走 fail-fast：交易订单、订单事件或审计行被冲突跳过都会回滚候选事务。
- 风控扫描、强平执行、资金费率发布、资金费结算、保险基金覆盖、ADL 扫描都有显式 `*.enabled` 运营开关，用于事故暂停。
- Funding 基于 mark/index premium 和 instrument 利率/上限/下限计算 long ppm 资金费率，并把到期资金费结算到账户余额。
- Insurance 使用 long 资产单位覆盖显式账户亏损，并同时写保险基金流水和账户流水。
- ADL 在保险基金耗尽后处理剩余亏损，削减盈利高优先级仓位，并把实现盈利转去覆盖 deficit。
- K 线服务用 Kafka Streams + RocksDB 分区状态，按 Kafka partition 水平扩展。
- 指数价格和标记价格服务用 PostgreSQL 的 symbol 租约和数据库 sequence 避免多节点重复发布和 sequence 回退。
- 订单入口用 PostgreSQL 幂等键、原子 sequence 和 outbox `FOR UPDATE SKIP LOCKED` 支持多节点部署。
- Trigger provider 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 抢占到期 TP/SL 条件单，通过 Kafka mark price 重放检测触发，并用 stale `TRIGGERING` 重置机制在下游 order-provider 故障后重试。它不直接修改余额或持仓。
- 当前 matching 已接入真实 exchange-core 订单簿，交易链路端到端使用 long ticks/steps。
- matching command topic 必须以 `symbol` 作为 key；matching 扩展依赖 Kafka partition 和受控实例数，不做每 symbol 一个线程。
- Matching 会把 L2 盘口深度发布成 `SNAPSHOT` 加按价格档绝对状态变化的 `DELTA`，topic 是 `surprising.perp.orderbook.depth.v1`。delta 中 `quantitySteps=0` 表示删除该价格档。客户端先拉 `GET /api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50`，再应用 `previousSequence` 等于本地 sequence 的 WebSocket 增量；断号或重连后要丢弃本地盘口，重新拉快照，再继续应用增量。
- REST 客户端可以传 `X-Trace-Id`；gateway 和 order provider 会清洗或生成该值，交易事件会一路携带到订单事件、撮合结果、撮合成交和账户持仓推送。撮合引擎产生的事件必须保留这个字段，因为它连接 HTTP 请求、Kafka 重放、数据库审计行和 WebSocket/账户私有更新。
- 交易链路 Kafka producer 使用幂等 `acks=all` 发布；Kafka consumer 关闭 auto commit，使用 cooperative-sticky assignment 和 record 级 ack，失败记录会通过幂等 DB 状态迁移重放。
- Kafka record key 和 payload `symbol` 不一致时消费者会拒绝处理，因为错误 key 会破坏单 symbol 顺序保证。
- Outbox 发布是至少一次投递；Kafka 发送失败后会按有上限的指数退避重试，下游状态迁移保持幂等。
- 订单入口已冻结初始保证金；账户成交处理会把开仓成交保证金迁移到持仓保证金，并在平仓时释放旧持仓保证金。
- 用户主动平仓订单在进入撮合 command 前会经过 reduce-only 校验。
- Risk 发现爆仓候选；liquidation 会把候选转成分阶段 reduce-only 平仓订单，强平不会被用户已有 reduce-only 挂单阻塞。
- Insurance 吸收 `account_deficits`；保险基金耗尽后的剩余亏损由 ADL 处理。当前保留的合并证据是四产品线主报告：每条线 20 个活跃 symbol、20 个 maker account、2000 个 taker 用户，做市持续运行，`funds-reconcile` 结果 `Violations=0`。生产上线前仍需要更长时间的多节点、多 broker 压测、监控阈值、真实集群熔断演练和多进程 Kafka 证据。
- WebSocket 推送服务应独立消费 Kafka 输出 topic，不要放进 K 线或价格计算服务里。
- 每个 WebSocket 节点必须使用唯一 Kafka consumer group，让公共行情事件到达所有节点做本地 fanout；不要让所有 WebSocket pod 共用一个 group。
- 用户持仓推送从 account 结算事务后的 outbox 产生，再由 WebSocket 消费并按已认证用户订阅过滤。
- 统一 REST 网关是无状态、白名单代理。各业务模块仍保留内部 API；前端/BFF 不应单独配置每个模块地址，而应通过 gateway 入口访问。
- 外部交易所行情采集以 WebSocket 为主，REST 只做冷启动和兜底。
- 法币汇率只用于展示和估值提示，不参与合约风控核心价格替代。

## 文档

- [部署说明](docs/deployment.md)
- [数据库设计](docs/database.md)
- [docs 文档索引](docs/README.md)
- [四产品线资金与性能压测报告](docs/full-chain-funds-performance-report.md)
