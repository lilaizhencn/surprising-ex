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

- `surprising-parent`：公共父 POM，统一管理依赖版本和插件版本。
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
本地集成测试统一使用 Homebrew PostgreSQL/Kafka，标准端口是 `localhost:5432`、`localhost:9092`；详细配置见 [docs/local-homebrew-infra.md](docs/local-homebrew-infra.md)。

## 本地启动顺序

```bash
brew services start postgresql@18
brew services start kafka
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
- `surprising.perp.trigger-order.events.v1`：事务型止盈止损状态快照，供认证用户的私有 WebSocket 推送消费。
- `surprising.perp.match.results.v1`：撮合结果事件。
- `surprising.perp.match.trades.v1`：撮合成交事件，long 定点数，供 K 线和公开成交推送消费。
- `surprising.perp.orderbook.depth.v1`：exchange-core L2 盘口 `SNAPSHOT`/`DELTA` 事件，供前端深度推送消费。
- `surprising.account.position.events.v1`：账户持仓变化事件，供私有 WebSocket 推送消费。
- `surprising.<product-segment>.account.position-cache.events.v1`：带 revision 的用户持仓 Redis 投影事件；例如 `surprising.linear-perp.account.position-cache.events.v1`。仅 account-provider 缓存消费者使用。
- `surprising.perp.liquidation.candidates.v1`：爆仓候选事件。
- `surprising.perp.index.price.v1`：指数价格输出。
- `surprising.perp.book.ticker.v1`：合约盘口最优价输入。
- `surprising.perp.funding.rate.v1`：实时预测资金费率输出，供 mark price 服务消费；funding provider 和其他消费者按 symbol 缓存最新事件。
- `surprising.perp.mark.price.v1`：唯一标记价格输出，同时携带业务结果和完整审计输入。

所有市场数据 topic 都使用 `symbol` 作为 Kafka key。

## API 快速检查

```bash
curl 'http://localhost:9080/api/v1/instruments/latest?symbol=BTC-USDT'
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
curl 'http://localhost:9082/api/v1/price/mark/latest?symbol=BTC-USDT'
curl -X POST 'http://localhost:9084/api/v1/trading/orders' -H 'Content-Type: application/json' -d '{"userId":1001,"clientOrderId":"cli-1001-1","symbol":"BTC-USDT","side":"BUY","orderType":"LIMIT","timeInForce":"GTC","priceTicks":650000,"quantitySteps":10,"reduceOnly":false,"postOnly":false}'
curl -X POST 'http://localhost:9084/api/v1/trading/trigger-orders' -H 'Content-Type: application/json' -d '{"userId":1001,"clientTriggerOrderId":"tp-1001-1","ocoGroupId":"bracket-1001-1","symbol":"BTC-USDT","side":"SELL","triggerType":"TAKE_PROFIT","triggerPriceTicks":700000,"orderType":"MARKET","timeInForce":"IOC","priceTicks":0,"quantitySteps":10,"marginMode":"CROSS"}'
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
完整产品线用户流程、做市、强平/生命周期和资金核对覆盖使用 `scripts/product-line-api-flow-smoke.sh`。

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
- 止盈、止损和追踪止损只由 Kafka 标记价格事件触发。公共消费者会校验产品线、定点数 ticks、事件/发布时间和 3 秒时效；trigger-provider 每秒只处理各 symbol 最新且新鲜的一笔，不再按 sequence 回查审计表。满足规则后通过 order-provider 提交幂等的 `reduceOnly=true` 平仓单，`clientOrderId=trigger-<triggerOrderId>`。追踪止损使用整数 `callbackRatePpm`（`1000` = `0.1%`，`100000` = `10%`）和可选 `activationPriceTicks`，最高/最低水位同样只按每秒采样的最新标记价格更新。
- 静态止盈止损默认且始终使用 Spring Data Redis + Lettuce ZSET 候选索引，无需功能开关。Redis 只做价格范围候选过滤；PostgreSQL 仍负责精确价格、状态、过期、OCO 条件校验和最终抢占，也是唯一权威状态。普通用户未完成订单查询在 Redis 投影完成重建后读取 Redis；投影缺失、不完整或 Redis 不可用时安全回退 PostgreSQL。用户撤单、过期、OCO 互撤、触发成功/拒绝以及事务回滚都会移除或校准索引；Redis 不可用或索引未 ready 时，已提交条件单退回原数据库 claim 链路。
- 成对 TP/SL 可以使用同一个 `ocoGroupId`；同一用户、symbol、保证金模式组里任意一条 pending 触发单被抢占后，其它 pending sibling 会在同一条数据库语句里先置为 `CANCELED`，然后再提交生成的平仓单。
- 普通平仓、强平成交、交割结算或期权行权把持仓降为零时，account 会在同一个 PostgreSQL 事务里取消该持仓剩余的 `PENDING` 条件单，并把 `CANCELED` 状态快照写入 trading outbox。提交后的持仓事件驱动 trigger-provider 删除对应静态止盈止损 Redis member，`trigger-order.events` 驱动认证用户 WebSocket 主动刷新；`GET /open` 始终以数据库已取消状态为准。
- Account 消费撮合成交，按 `tradeId` 幂等更新 long-based 净持仓，把开仓成交保证金迁移到持仓保证金，并把已实现盈亏结算进余额。
- 用户持仓、持仓列表和持仓保证金查询只读 Redis Hash；PostgreSQL 仍是唯一事实源。持仓/保证金数据库触发器会在同一事务写入 revision 化的 `POSITION_CACHE_PROJECTED` account outbox，覆盖成交、平仓、强平、资金费、ADL、交割/行权和手工逐仓调整。Redis 用 Lua CAS 原子更新持仓、保证金和 revision；未 ready 或 Redis 异常时用户查询返回 503，不回退数据库。详细设计见 [docs/position-redis-cache_CN.md](docs/position-redis-cache_CN.md)。
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
- Funding 基于 mark/index premium 和 instrument 利率/上限/下限计算 long ppm 预测资金费率，直接发布到 Kafka；每个结算时点才把缓存中的最新预测冻结为 `FINAL` 数据库行并结算到账户余额。
- Insurance 使用 long 资产单位覆盖显式账户亏损，并同时写保险基金流水和账户流水。
- ADL 在保险基金耗尽后处理剩余亏损，削减盈利高优先级仓位，并把实现盈利转去覆盖 deficit。
- ADL 的 Redis 候选排序、ready 标记和重建租约按 `ProductLine + settleAsset` 隔离；风险持仓事件携带产品线，ADL provider 仅接收自身产品线的事件，随后仍由 PostgreSQL 复核和加锁。
- K 线服务用 Kafka Streams + RocksDB 分区状态，按 Kafka partition 水平扩展。
- 指数价格和标记价格服务用 PostgreSQL 的 symbol 租约和数据库 sequence 避免多节点重复发布和 sequence 回退。
- 每条标记价格 Kafka 事件都携带产品线、instrument 版本、定点数 units/ticks、计算时间和发布时间，并在同一消息中包含指数成分、盘口、最新成交、资金费、基差中间值和最终结果等完整审计输入。独立 consumer group 异步批量写入 PostgreSQL 并只保留 3 天；任何实时业务都不等待这次入库。
- 订单入口用 PostgreSQL 幂等键、原子 sequence 和 outbox `FOR UPDATE SKIP LOCKED` 支持多节点部署。
- Trigger provider 可先用 Redis Lua 范围查询过滤候选，再由 PostgreSQL `FOR UPDATE SKIP LOCKED` 完成多节点最终抢占，并通过 Kafka 行情重放和 stale `TRIGGERING` 重置处理故障重试。带 token 的 Redis lease 只防止多个节点重复重建索引，不承担业务正确性。Trigger provider 不直接修改余额或持仓。
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

## 下一阶段计划：生产级多节点撮合

当前 matching 已具备第一阶段多节点基础：订单命令以 `symbol` 为 Kafka key，同一 consumer group 内每个 partition 同时只分配给一个存活节点，节点重启后从 PostgreSQL 恢复开放订单簿。下一阶段将在此基础上建设显式管理的撮合集群，并继续坚持单 symbol 单写者原则：不同节点可以持有不同 symbol shard，但绝不能同时修改同一个订单簿。

### 第一阶段：现有分区模型生产化

- 四条产品线继续隔离，各自使用独立的订单命令 topic、consumer group、client identity、instrument、账户、风险模型和下游 topic。
- 根据预期撮合并行度提前规划足够的 Kafka partition。已经承载流量的 keyed topic 不得直接在线增加 partition；这会改变 `symbol -> partition` 映射，必须按产品线安排维护、迁移和重放方案。
- 同一产品线部署多个 matching provider，使用相同 `group-id`，每个节点使用稳定且唯一的 `client-id`。生产保持 `restart-on-partition-reassignment=true`，采用受控滚动发布和 PodDisruptionBudget，避免高频自动扩缩容。
- 节点只恢复自己已分配 partition 所属的 symbol，不再让每个进程加载全部开放订单簿。完成 partition 分配、订单簿恢复和一致性校验前，readiness 必须保持失败。
- 增加 `symbol -> partition -> client-id` 归属、consumer lag、命令 P99 延迟、恢复耗时、开放订单数、outbox backlog、结算延迟、重启原因和资金核对失败监控。
- 增加多 broker、多 matching 节点故障演练，覆盖优雅 drain、节点硬故障、Kafka rebalance、数据库中断、command replay 和 outbox replay。

### 第二阶段：显式分片和快速故障切换

- 引入带版本的 `symbol -> matching shard` 元数据，不再只依赖 Kafka 默认 key hash。热点 symbol 可以独占 shard，冷门 symbol 可以共享 shard。
- 为 shard ownership 增加单调递增的 epoch/fencing token。所有撮合状态变更、checkpoint 和 shard lease 都必须拒绝过期 owner 的写入，防止延迟恢复的旧节点形成第二写者。
- 持久化有序撮合 command journal、周期性订单簿 snapshot，以及 Kafka offset/checkpoint 元数据。恢复时加载最新的已校验 snapshot，只重放 checkpoint 之后的命令。
- 支持 shard 级 drain、handoff、recovery 和健康状态，partition 移动时不再要求恢复全部订单簿或重启整个 JVM。
- 如果恢复时间目标需要，为 shard 增加 leader/follower 复制；只有持有有效 fencing token 的 leader 可以发布权威 match result、trade、depth sequence 和结算事件。
- 单个 symbol 仍必须在一个 shard 内串行处理。增加节点提升的是多 symbol 聚合吞吐，不能让多个节点同时撮合一个 BTC-USDT 订单簿。

### 验收门槛

- 每次只运行一条产品线，做市进程保持在线，通过模拟用户 API 覆盖下单、撤单、撮合、开平仓、强平、风控事件以及 WebSocket 公共/私有推送。
- 证明恢复结果确定：每次故障切换前后逐项比较开放订单、价格时间优先级、持仓、余额、深度 sequence 连续性、持久化 checkpoint 和 Kafka offset。
- 用户账号和做市账号逐项资金对账：期初、调整、成交、手续费、资金费、强平费、适用时的交割/行权流水和期末余额必须精确对平。
- 验证产品线特有边界：永续的资金费/标记价/强平/ADL/保险基金，交割的到期结算，期权的权利金/行权/到期失效，以及现货资产冻结/扣减/释放。
- 生产发布前必须通过持续热点 symbol 压测和重复节点 kill/rebalance 测试，且不能出现重复成交、命令丢失、重复结算、恢复后订单簿交叉、过期 owner 写入或资金核对异常。

## 文档

- [部署说明](docs/deployment.md)
- [数据库设计](docs/database.md)
- [docs 文档索引](docs/README.md)
- [四产品线资金与性能压测报告](docs/full-chain-funds-performance-report.md)
