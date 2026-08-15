# surprising-ex


Surprising-EX 是基于 Java 25、Aeron Cluster、PostgreSQL、Kafka 和 Valkey 的六产品线交易所后端。
仓库覆盖现货、U/币本位永续、U/币本位交割和欧式现金结算期权；每条产品线使用独立的三节点
Aeron Cluster、Topic、投影和账户类型。

当前分支正在重新整理文档和验证脚本，`docs/` 与 `scripts/` 已移除。本文档和各模块 README
是当前保留的说明入口；部署、压测和资金对账脚本将在重新设计后补回。

## 核心边界

- Aeron Cluster 中的 Unified Core State 是资金、订单、订单簿、持仓、Risk、强平和 Treasury 的唯一
  在线权威；Cluster Log、Archive 和 Snapshot 是唯一核心恢复链。
- Exchange Core 作为确定性订单簿执行器嵌入 Aeron 状态机，不运行独立 journal；同一核心命令原子完成
  资金预占、撮合、成交结算、风险更新和必要的强平状态推进。
- PostgreSQL 只保存 Core Export 查询投影、审计、报表和对账数据；投影延迟或不可用不能改变交易裁决。
- Kafka 保留外部输入缓冲与 WebSocket、K 线、通知、数据仓库等外围事件分发，不恢复核心资金状态。
- Valkey 只承担限流和非权威缓存，不保存 Risk 状态、强平候选、资金或订单恢复进度。
- Risk 按 symbol 保存确定性有界扫描游标；强平 Work、触发价格序列、仓位身份、执行、强平费和
  Insurance Treasury 全部由 Aeron 校验并原子提交。
- `surprising-liquidation-provider` 是无状态协调器：查询 Aeron Liquidation Work、续跑 Risk Scan，使用
  稳定 `commandId` 重试强平命令；它不消费强平 Kafka 回环，也不维护 Redis 队列或 PostgreSQL 强平事务。
- Core Exporter 以连续 Export Sequence 向 Kafka at-least-once 发布并幂等写 PostgreSQL；只有完整批次
  成功后才向 Aeron 提交 ACK，不新增数据库 outbox 或应用 WAL。
- Matching Provider 只做 Market Data Projection：启动从 Aeron 强查询恢复 L2 和 watermark，随后消费
  单分区连续 Core Event 发布公共深度与成交；历史成交和 24h 查询读取 PG 投影。
- 六条产品线必须隔离部署和验证；压测前每条线都必须达到 `functional-gate=PASS`、`funds-diff=0`。

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
边界约束由对应模块的源码和 Maven 测试维护；原有独立检查脚本已随 `scripts/` 一起移除，新的检查方式
将在验证脚本重新整理后补充。

Controller 只负责 HTTP 参数校验、请求上下文提取和响应映射，不直接访问 Repository，也不承载事务或
业务编排。`task` 包只负责声明定时触发时机，所有实际执行都委托给 Service。入口层边界由源码审查和
对应 Maven 测试维护。

## 构建与本地启动

要求 JDK 25。基础设施启动、数据库初始化和 Topic 创建脚本正在重新整理；当前可以先执行：

```bash
mvn -DskipTests package
mvn test
```

matching 使用 `exchange.core2:exchange-core:0.5.8-emporia` 及其 Chronicle/OpenHFT 传递依赖，必须使用
以下 JVM 参数：

```text
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
```

Chronicle 版本由父 POM 的 BOM 统一管理，避免旧版在 JDK 25 中触发 `unmap0`/`Bytes` 初始化错误。
默认合并进程和端口：

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
| websocket | 9097 |
| gateway | 9094 |
| market-maker | 9096 |

## 测试

```bash
mvn test
mvn -pl :surprising-aeron-client,:surprising-aeron-tools -am test
```

当前 Maven 测试覆盖构建和核心 Aeron 客户端/工具链；完整产品线验收、压测和资金守恒核对脚本待重新整理。
交易裁决和结算在 Aeron Core 内存状态机完成；PostgreSQL 只用于异步投影、审计和对账。

## 生产部署

生产部署 Runbook、基础设施初始化和容量基线正在重新整理，当前不提供可直接执行的部署命令。
即使部署文档尚未恢复，四条产品线仍必须独立配置和验证：

- 关闭 Kafka 自动建 Topic，使用经过审查的 Topic 初始化配置显式创建；
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

Topic 的精确清单、分区数量和创建后校验命令待部署文档重新整理后补充。

## 文档

当前保留的文档入口：

- 根目录架构、构建和测试说明：本文档；
- [账户模块 README](surprising-account/README.md)；
- [Aeron 核心模块 README](surprising-aeron-core/README.md)；
- [撮合交易模块 README](surprising-trading/README.md)；
- [网关模块 README](surprising-gateway/README.md)；
- [WebSocket 模块 README](surprising-websocket/README.md)；
- 其他模块的 README 位于各自模块目录下。

新的部署、产品线、资金模型、撮合和压测文档请按主题补回，并同步更新本文档入口。
