# surprising-ex


Surprising-EX 是基于 Java 25、PostgreSQL、Kafka 和 Redis/Valkey 的多产品线交易所后端。仓库覆盖
现货、U 本位永续、U 本位交割和欧式现金结算期权；生产部署时每个进程只运行一条产品线，并使用
独立 Topic、消费组、订单簿和账户类型。

## 核心边界

- 订单和账户的交易事实由按用户分区的本地 WAL/RocksDB 单写者维护；PostgreSQL 只承担异步投影、启动恢复和审计。
- account-provider 是资金、持仓、保证金和亏空的唯一写者；其他模块通过按用户分区的账户指令请求变更，快照缺失时失败关闭。
- 跨服务一致性使用本地事实流、Kafka 至少一次投递和消费端幂等，不使用 XA；数据库投影落后不能改变交易裁决。
- 交易订单和撮合 Outbox 通过一次 pending 行窗口扫描，按 `topic + eventKey` 领取有上限的连续前缀并用
  MVCC CAS 竞争，不同 stream 并发流水线写入 Kafka，ACK 后批量标记发布状态。
- 用户持仓和订单状态优先从各模块 JVM 快照读取；Redis 只能作为查询或跨节点协调投影。Redis、数据库
  或 Kafka 投影未就绪时不得把空集合当成正确状态，也不授权成交、撤单、资金变更或最终强平执行。
- risk-provider 在 Redis 维护完整风险组和 `symbol + instrumentVersion -> group` 反向索引；标记价更新
  只计算受影响的风险组，PostgreSQL 在同一事务内批量写风险快照、强平 candidate 和 candidate Outbox，
  liquidation 执行前仍重新校验并锁定 PostgreSQL 权威状态。内部规格缓存已引入
  `productLine + symbol + version` 规格键，历史订单和持仓继续使用同一内部版本号定位规格。
- 风险持仓事件和标记价事件优先复用本 JVM/Redis 风险组快照；定时扫描仍承担恢复核对职责。
  强平候选输入直接使用候选事件中的风险值和持仓锁结果，只查询最新风险状态，避免重复读取持仓和账户快照。
- 强平 candidate 进入带版本和租约的候选队列，由 JVM/Redis 做排序与跨节点协调；最终资金命令仍必须
  进入 account-provider 用户分区事实流。候选投影丢失时暂停执行并等待事件或恢复，不回退到主库猜测。
- 同一产品线、同一用户的账户指令固定使用 `<PRODUCT_LINE>:<userId>` 作为 Kafka key，并通过
  32 个分区串行处理。
- 撮合命令、成交、盘口和价格事件使用 `symbol` 作为 key。同一 symbol 的命令必须保持有序。
- 撮合保护、最新成交、盘口和热 K 线优先使用进程内内存或 RocksDB；行情数据库只负责关闭 K 线、恢复快照和最终审计，具体边界见[内存与无锁热点路径](docs/in-memory-acceleration.md)。
- WebSocket 公共行情按 Kafka 批次分组入队，连接使用有界队列和背压指标；私有事件仍按用户和产品线隔离。
- Instrument 是唯一配置入口：各模块启动时通过 Instrument 内部聚合 RPC 加载本产品线快照，运行中消费
  `surprising.instrument.events.v1` 增量事件，在本 JVM 内以不可变引用整体替换。下单、撮合、账户、风控、
  价格、K 线和做市热路径不再逐笔访问 Instrument 主库；快照未完成时服务不得接受交易流量。
- 未平仓量由 account-provider 唯一写入分片表；订单等模块启动时通过账户内部快照 RPC 初始化，运行中消费
  产品线隔离的 Kafka 分片绝对值事件，在各自 JVM 聚合。下单保证金命中快照后不查询未平仓量视图，数据库
  只承担账户持久化、启动恢复和最终审计。
- 内部做市账户之间的自成交继续产生公共成交、盘口、K 线和 WebSocket 行情，但不生成经济成交、
  持仓、手续费和资金结算；做市账户与真实用户成交时执行完整结算。

## 产品线

| 产品线 | `ProductLine` | 账户类型 | Topic 前缀 |
|---|---|---|---|
| 现货 | `SPOT` | `SPOT` | `surprising.spot` |
| U 本位永续 | `LINEAR_PERPETUAL` | `USDT_PERPETUAL` | `surprising.linear-perp` |
| U 本位交割 | `LINEAR_DELIVERY` | `USDT_DELIVERY` | `surprising.linear-delivery` |
| 欧式期权 | `OPTION` | `OPTION` | `surprising.option` |

`INVERSE_PERPETUAL` 和 `INVERSE_DELIVERY` 已有公共枚举和 Topic 映射，但当前进程级验收主要覆盖上表
四条产品线。

## 模块

| 模块 | 职责 |
|---|---|
| `surprising-product-api` | 产品线、账户类型和 Topic 命名 |
| `surprising-instrument` | symbol、合约规格、风险档位和生命周期 |
| `surprising-price` | 独立指数价、标记价和汇率服务 |
| `surprising-trading` | 独立普通订单、条件单、算法单和 exchange-core 撮合服务 |
| `surprising-account` | 余额、账本、账户指令、结算、持仓和保证金 |
| `surprising-risk` | 风险 API 和独立风控服务 |
| `surprising-liquidation` | 强平 API 和独立强平服务 |
| `surprising-funding` | 资金费 API 和独立资金费服务 |
| `surprising-insurance` | 保险基金 API 和独立保险基金服务 |
| `surprising-adl` | ADL API 和独立自动减仓服务 |
| `surprising-candlestick` | Kafka Streams + RocksDB K 线 |
| `surprising-gateway` | 独立 REST gateway |
| `surprising-websocket` | 独立 WebSocket fanout |
| `surprising-market-maker` | 内部做市和交易链路压测 |

Repository 默认只操作一张物理表，由 Service 在事务内聚合。在线交易、风控和结算链路若因一致性或原子性
必须跨表，源码需要逐项写明中文“不可拆原因”。后台订单时间线、资金对账和运营报表不得在交易主库新增
多表 JOIN；后续财务运营模块应消费领域事件，并使用独立数据库建立查询投影。
CI 可运行 `./scripts/check-persistence-boundaries.sh`，拦截 Repository 之外的生产 JDBC 访问，
以及未写中文“不可拆原因”的多表 Repository。

Controller 只负责 HTTP 参数校验、请求上下文提取和响应映射，不直接访问 Repository，也不承载事务或
业务编排。`task` 包只负责声明定时触发时机，所有实际执行都委托给 Service。CI 可运行
`./scripts/check-entry-layer-boundaries.sh`，阻止 Controller 越过 Service，以及 `@Scheduled`
回流到 Service、Repository 或客户端实现。

## 构建与本地启动

要求 JDK 25。先启动 PostgreSQL、Kafka 和 Redis，再初始化数据库与 Topic：

```bash
mvn -DskipTests package
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
PRODUCT_LINES=LINEAR_PERPETUAL PARTITIONS=32 ACCOUNT_COMMAND_PARTITIONS=32 ./scripts/create-topics.sh
PRODUCT_LINE=LINEAR_PERPETUAL PORT_OFFSET=100 ORDER_WAL_NODE_ID=101 BUILD_SERVICES=false ./scripts/start-product-line-providers.sh
```

matching 使用 `exchange.core2:exchange-core:0.5.3` 及其 Chronicle/OpenHFT 传递依赖，必须使用
[部署文档](docs/deployment.md) 中列出的 `--add-opens/--add-exports` JVM 参数。Chronicle 版本由
父 POM 的 BOM 统一管理，避免旧版在 JDK 25 中触发 `unmap0`/`Bytes` 初始化错误。默认合并进程和端口：

| Provider | 端口 |
|---|---:|
| instrument | 9080 |
| candlestick | 9081 |
| index-price | 9082 |
| mark-price | 9083 |
| order | 9084 |
| trigger | 9095 |
| matching | 9085 |
| account | 9086 |
| risk | 9087 |
| liquidation | 9088 |
| funding | 9089 |
| insurance | 9090 |
| adl | 9091 |
| websocket | 9093 |
| gateway | 9094 |
| market-maker | 9096 |

## 测试

```bash
mvn test
./scripts/integration-smoke.sh

PRODUCT_LINES=LINEAR_PERPETUAL \
BUILD_SERVICES=auto \
CREATE_KAFKA_TOPICS=true \
RECONCILE_FUNDS=true \
./scripts/product-line-api-flow-smoke.sh
```

产品线 smoke 覆盖真实 API 下单、做市、撮合、账户结算、主动平仓、强平和适用的资金费/
交割/行权，最后执行资金守恒核对。压测报告默认写入临时目录；只有稳定、可复现的长期结论才应整理进文档。

## 生产部署

四条产品线必须按独立 Runbook 部署：

- [SPOT 现货 Runbook](docs/runbook-spot.md)
- [LINEAR_PERPETUAL U 本位永续 Runbook](docs/runbook-linear-perpetual.md)
- [LINEAR_DELIVERY U 本位交割 Runbook](docs/runbook-linear-delivery.md)
- [OPTION 欧式期权 Runbook](docs/runbook-option.md)

永续首发的 EC2、JVM、RDS、MSK、Valkey 和容量基线见
[LINEAR_PERPETUAL AWS 生产部署基线](docs/linear-perpetual-aws-production-deployment.md)。
部署前必须：

- 关闭 Kafka 自动建 Topic，使用 [create-topics.sh](scripts/create-topics.sh) 显式创建；
- 永续首发把普通 Topic 和账户指令 Topic 都固定为 32 分区，RF=3、`min.insync.replicas=2`；
- 不在已有 symbol-keyed Topic 上直接增加分区；扩容需要新版本 Topic、维护窗口和状态重建方案；
- 为每条产品线配置独立 Topic、消费组、client id、协调 node id 和 gateway route；`PRODUCT_LINE`
  不允许省略，服务启动会拒绝空值或 `product-topics-enabled=false`；
- Order Provider 的账户指令结果 listener 并发度对齐 32 个分区；同一 `productLine:userId` 保序，
  每个 poll 批量完成订单状态迁移及 ACCEPTED/PLACE Outbox 入库；
- 撮合指令使用有界 poll 批量事务，批量读取幂等及保护状态；同批同用户/标的的潜在冲突仍逐条复查，
  保持 symbol 分区顺序；Outbox 积压时优先发布
  `ORDER_RESERVE/PLACE/CANCEL` 财务指令，再发布通知型订单事件；
- Redis/Valkey 使用持久化、`noeviction` 和同 hash-tag Lua 兼容的部署；
- 保持 PostgreSQL durability，监控 Kafka lag、Outbox pending/最老年龄、数据库锁与慢 SQL、Redis
  readiness、JVM GC，并在上线前完成故障切换和资金零差异核对。

Topic 的精确清单、分区数量和创建后校验命令见 [部署文档](docs/deployment.md)。

## 文档

- [文档索引](docs/README.md)
- [部署与 Topic 规划](docs/deployment.md)
- [数据库设计](docs/database.md)
- [产品线架构](docs/product-line-architecture.md)
- [账户单写者和单用户串行](docs/account-single-writer-command-lane.md)
- [高并发与资金安全改造执行计划](docs/high-concurrency-stability-execution-plan.md)
- [持仓 Redis 读模型](docs/position-redis-cache.md)
- [未完成订单 Redis 投影](docs/open-order-redis-cache.md)
- [测试与资金守恒](docs/product-line-testing-and-funds-reconciliation.md)
