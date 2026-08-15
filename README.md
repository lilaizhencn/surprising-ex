# surprising-ex


Surprising-EX 是基于 Java 25、Aeron Cluster、PostgreSQL、Kafka 和 Valkey 的交易所后端。
仓库覆盖现货、永续、交割和欧式现金结算期权四条业务线（含 U/币本位变体）；每条业务线使用独立的
三节点 Aeron Cluster、Topic、投影和账户类型。

完整架构、问题追踪、唯一参数来源、阶段台账、脚本矩阵和验收门禁以
[`docs/high-performance-trading-core-implementation.md`](docs/high-performance-trading-core-implementation.md)
为唯一实施依据。canonical 脚本均绑定显式产品线的内存 Core；旧 DB/旧 trigger/旧 matching 业务脚本不得
直接复用，真实 provider/做市进程仍由部署编排单独管理。

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
- 保证金率、risk brackets、杠杆和持仓上限只由 Instrument Provider 版本化下发到 `CoreInstrumentState`；
  Core 是唯一计算/执行来源，Risk Provider 只查询并展示 Core 风险快照，不再维护本地保证金阈值副本。
- `surprising-liquidation` 是无状态协调器：查询 Aeron Liquidation Work、续跑 Risk Scan，使用
  稳定 `commandId` 重试强平命令；它不消费强平 Kafka 回环，也不维护 Redis 队列或 PostgreSQL 强平事务。
- Core Exporter 以连续 Export Sequence 向 Kafka at-least-once 发布并幂等写 PostgreSQL；只有完整批次
  成功后才向 Aeron 提交 ACK，不新增数据库 outbox 或应用 WAL。
- Matching Provider 只做 Market Data Projection：启动从 Aeron 强查询恢复 L2 和 watermark，随后消费
  单分区连续 Core Event 发布公共深度与成交；历史成交和 24h 查询读取 PG 投影。
- 四条业务线必须隔离部署和验证；压测前当前变体必须达到 `functional-gate=PASS`、`funds-diff=0`。

## 产品线

| 产品线 | `ProductLine` | 账户类型 | Topic 前缀 |
|---|---|---|---|
| 现货 | `SPOT` | `SPOT` | `surprising.spot` |
| U 本位永续 | `LINEAR_PERPETUAL` | `USDT_PERPETUAL` | `surprising.linear-perp` |
| U 本位交割 | `LINEAR_DELIVERY` | `USDT_DELIVERY` | `surprising.linear-delivery` |
| 欧式期权 | `OPTION` | `OPTION` | `surprising.option` |

`INVERSE_PERPETUAL` 和 `INVERSE_DELIVERY` 已有公共枚举和 Topic 映射；Core recovery/capacity 门禁按六个
产品枚举逐条执行，业务 API 的四类生命周期仍按产品边界单独验收。

## 模块

| 模块 | 职责 |
|---|---|
| `surprising-product-api` | 产品线、账户类型和 Topic 命名 |
| `surprising-instrument` | symbol、合约规格、风险档位和生命周期 |
| `surprising-price` | 独立指数价、标记价和汇率服务 |
| `surprising-trading` | Provider/API 边界；最终订单、条件单、算法单和 exchange-core 裁决由 Aeron Core 完成 |
| `surprising-account` | 余额、账本、账户指令、结算、持仓和保证金 |
| `surprising-risk` | Core 风险快照查询和投影 API，不维护保证金/强平裁决副本 |
| `surprising-liquidation` | 强平 API 和独立强平服务 |
| `surprising-funding` | 资金费 API 和独立资金费服务 |
| `surprising-insurance` | 保险基金 API 和独立保险基金服务 |
| `surprising-adl` | ADL API 和独立自动减仓服务 |
| `surprising-candlestick` | Kafka Streams + RocksDB K 线 |
| `surprising-gateway` | REST gateway、WebSocket fanout 和统一对外入口 |
| `surprising-maker` | 内部做市和交易链路压测 |

Repository 默认只操作一张物理表，由 Service 在事务内聚合。在线交易、风控和结算链路若因一致性或原子性
必须跨表，源码需要逐项写明中文“不可拆原因”。后台订单时间线、资金对账和运营报表不得在交易主库新增
多表 JOIN；后续财务运营模块应消费领域事件，并使用独立数据库建立查询投影。
边界约束由对应模块的源码、Maven 测试和主规格维护；canonical 检查脚本按单产品线、资金对账、恢复和
容量职责执行。

Controller 只负责 HTTP 参数校验、请求上下文提取和响应映射，不直接访问 Repository，也不承载事务或
业务编排。`task` 包只负责声明定时触发时机，所有实际执行都委托给 Service。入口层边界由源码审查和
对应 Maven 测试维护。

## 构建与本地启动

要求 JDK 25。基础设施启动、数据库初始化和 Topic 创建脚本仍由各部署编排管理；Core 本地三节点可以先执行：

```bash
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh fresh
PRODUCT_LINE=SPOT scripts/integration-smoke.sh
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh down
```

恢复与容量门禁分别使用 `scripts/run-product-line-recovery-matrix.sh` 和
`scripts/run-product-line-capacity.sh`。`fresh` 只删除显式产品线的 Docker volume；`up/down` 默认保留卷。
脚本不会启动 wallet，也不会把未接入的 HTTP provider、做市进程或 Kafka 集群验证伪装成 Core 已完成能力。

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
| price-provider（index/mark/fx） | 9082 |
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

当前 Maven 测试覆盖构建和核心 Aeron 客户端/工具链；产品线资金守恒、恢复和容量脚本按主规格的单线门禁执行，
未通过真实 provider/做市/Kafka 集群验证的场景必须在证据中明确标记。根 `mvn test` 当前仍会在既有
`surprising-gateway` 的 `BinanceApiControllerTest` 泛型断言编译错误处停止；Core、Risk 和 Trigger 模块的定向
Maven 测试可独立通过，详见主规格验证证据。
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
- [Gateway WebSocket 说明](surprising-gateway/README-websocket.md)；
- 其他模块的 README 位于各自模块目录下。

新的部署、产品线、资金模型、撮合和压测文档请按主题补回，并同步更新本文档入口。
