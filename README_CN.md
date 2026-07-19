# surprising-ex

[English](README.md) | 简体中文

Surprising-EX 是基于 Java 21、PostgreSQL、Kafka 和 Redis/Valkey 的多产品线交易所后端。仓库覆盖
现货、U 本位永续、U 本位交割和欧式现金结算期权；生产部署时每个进程只运行一条产品线，并使用
独立 Topic、消费组、订单簿和账户类型。

## 核心边界

- PostgreSQL 是订单、余额、持仓、保证金、账本和风险状态的唯一事实源。
- account-provider 是资金、持仓、保证金和亏空的唯一写者；其他模块通过按用户分区的账户指令请求变更。
- 跨服务一致性使用本地事务、transactional outbox、Kafka 至少一次投递和消费端幂等，不使用 XA。
- 交易订单和撮合 Outbox 通过一次 pending 行窗口扫描，按 `topic + eventKey` 领取有上限的连续前缀并用
  MVCC CAS 竞争，不同 stream 并发流水线写入 Kafka，ACK 后批量标记发布状态。
- 用户持仓只从 Redis Hash 读取；未完成订单优先从 Redis ZSET/Hash 投影读取。Redis 不授权成交、
  撤单、资金变更或最终强平执行。
- risk-provider 在 Redis 维护完整风险组和 `symbol + instrumentVersion -> group` 反向索引；标记价更新
  只计算受影响的风险组，PostgreSQL 在同一事务内批量写风险快照、强平 candidate 和 candidate Outbox，
  liquidation 执行前仍重新校验并锁定 PostgreSQL 权威状态。
- 同一产品线、同一用户的账户指令固定使用 `<PRODUCT_LINE>:<userId>` 作为 Kafka key，并通过
  32 个分区串行处理。
- 撮合命令、成交、盘口和价格事件使用 `symbol` 作为 key。同一 symbol 的命令必须保持有序。
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
| `surprising-price` | 指数价、标记价和汇率 |
| `surprising-trading` | 普通订单、条件单、算法单和 exchange-core 撮合 |
| `surprising-account` | 余额、账本、账户指令、结算、持仓和保证金 |
| `surprising-margin-ops` | 风险、强平、资金费、保险基金和 ADL |
| `surprising-candlestick` | Kafka Streams + RocksDB K 线 |
| `surprising-edge` | REST gateway 和 WebSocket fanout |
| `surprising-market-maker` | 内部做市和交易链路压测 |

## 构建与本地启动

要求 JDK 21。先启动 PostgreSQL、Kafka 和 Redis，再初始化数据库与 Topic：

```bash
mvn -DskipTests package
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
PRODUCT_LINES=LINEAR_PERPETUAL PARTITIONS=32 ACCOUNT_COMMAND_PARTITIONS=32 ./scripts/create-topics.sh
PRODUCT_LINE=LINEAR_PERPETUAL BUILD_SERVICES=false ./scripts/start-product-line-providers.sh
```

matching 使用 exchange-core/OpenHFT，必须使用 [部署文档](docs/deployment.md) 中列出的
`--add-opens/--add-exports` JVM 参数。默认合并进程和端口：

| Provider | 端口 |
|---|---:|
| instrument | 9080 |
| candlestick | 9081 |
| price（index + mark） | 9082 |
| trading-entry（order + trigger） | 9084 |
| matching | 9085 |
| account | 9086 |
| margin-ops | 9088 |
| edge（gateway + WebSocket） | 9094 |
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

永续首发的 EC2、JVM、RDS、MSK、Valkey 和容量基线见
[LINEAR_PERPETUAL AWS 生产部署基线](docs/linear-perpetual-aws-production-deployment_CN.md)。
部署前必须：

- 关闭 Kafka 自动建 Topic，使用 [create-topics.sh](scripts/create-topics.sh) 显式创建；
- 永续首发把普通 Topic 和账户指令 Topic 都固定为 32 分区，RF=3、`min.insync.replicas=2`；
- 不在已有 symbol-keyed Topic 上直接增加分区；扩容需要新版本 Topic、维护窗口和状态重建方案；
- 为每条产品线配置独立 Topic、消费组、client id、协调 node id 和 gateway route；
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
- [产品线架构](docs/product-line-architecture_CN.md)
- [账户单写者和单用户串行](docs/account-single-writer-command-lane_CN.md)
- [持仓 Redis 读模型](docs/position-redis-cache_CN.md)
- [未完成订单 Redis 投影](docs/open-order-redis-cache_CN.md)
- [测试与资金守恒](docs/product-line-testing-and-funds-reconciliation_CN.md)
