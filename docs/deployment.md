# 部署说明

## Kafka 主题

生产环境必须关闭 Topic 自动创建。首次部署 `LINEAR_PERPETUAL` 时，分区数属于应用协议，不能依赖
Broker 默认值：

```bash
PRODUCT_LINES=LINEAR_PERPETUAL \
INCLUDE_SHARED_TOPICS=true \
INCLUDE_LEGACY_PERP_TOPICS=false \
PARTITIONS=32 \
ACCOUNT_COMMAND_PARTITIONS=32 \
REPLICATION_FACTOR=3 \
BOOTSTRAP_SERVERS='<bootstrap-brokers>' \
./scripts/create-topics.sh
```

该命令创建产品线 Topic 和共享 Topic。每个 Topic 固定 32 个分区；具体清单以脚本输出为准，
RF=3 时按实际 Topic 数量计算副本。

| 范围 | Topic 后缀或完整名称 | 分区数 | 必须使用的 Key |
|---|---|---:|---|
| 共享 | `surprising.instrument.events.v1`（compact） | 32 | `PRODUCT_LINE:SYMBOL` |
| 共享 | `surprising.instrument.lifecycle-drain.v1` | 32 | `symbol` |
| 共享 | `surprising.account.position.events.v1` | 32 | `<PRODUCT_LINE>:<userId>` |
| 共享 | `surprising.account.liquidation-fee.events.v1` | 32 | 结算资产 |
| 共享 | `surprising.risk.account.events.v1` | 32 | `<userId>:<accountType>:<asset>` |
| 共享 | `surprising.risk.position.events.v1` | 32 | `symbol` |
| 产品线 | `trade.events.v1` | 32 | `symbol` |
| 产品线 | `candle.events.v1` | 32 | `symbol` |
| 产品线 | `order.commands.v1` | 32 | `symbol` |
| 产品线 | `order.events.v1` | 32 | `symbol` |
| 产品线 | `trigger-order.events.v1` | 32 | `symbol` |
| 产品线 | `match.results.v1` | 32 | `symbol` |
| 产品线 | `match.trades.v1` | 32 | `symbol` |
| 产品线 | `orderbook.depth.v1` | 32 | `symbol` |
| 产品线 | `mark.price.v1` | 32 | `symbol` |
| 产品线 | `index.price.v1` | 32 | `symbol` |
| 产品线 | `book.ticker.v1` | 32 | `symbol` |
| 产品线 | `funding.rate.v1` | 32 | `symbol` |
| 产品线 | `account.position.events.v1` | 32 | `<PRODUCT_LINE>:<userId>` |
| 产品线 | `account.liquidation-fee.events.v1` | 32 | 结算资产 |
| 产品线 | `account.state.events.v1`（compact） | 32 | `<PRODUCT_LINE>:<userId>` |
| 产品线 | `risk.account.events.v1` | 32 | `<userId>:<accountType>:<asset>` |
| 产品线 | `risk.position.events.v1` | 32 | `symbol` |
| 产品线 | `account.user.commands.v1` | **32** | `<PRODUCT_LINE>:<userId>` |
| 产品线 | `account.user.commands.dlt.v1` | **32** | 原命令 Key 和分区 |
| 产品线 | `account.command.results.v1` | **32** | `<PRODUCT_LINE>:<userId>` |
| 产品线 | `order.user.commands.v1` | **32** | `<PRODUCT_LINE>:<userId>` |
| 产品线 | `order.user.command.results.v1` | **32** | `<PRODUCT_LINE>:<userId>` |
| 产品线 | `order.state.events.v1`（compact） | 32 | `<PRODUCT_LINE>:<userId>` |

三个账户命令 Topic 由 `ACCOUNT_COMMAND_PARTITIONS=32` 明确固定，分区数必须一致，DLT 必须保留原始
分区号。所有 account-provider 的 `ACCOUNT_USER_COMMAND_CONCURRENCY` 总和超过 32 不会增加有效
并行度；双节点 AWS 基线每节点为 16。

`instrument.events.v1`、`account.state.events.v1` 和 `order.state.events.v1` 使用 `cleanup.policy=compact`，键分别是合约和用户
分区的最新状态。它们是 JVM 快照广播源；每个订单/账户实例必须使用唯一的 `client-id`，并把该值附加到
快照消费组，不能让多个实例共享快照消费组而互相分摊状态。账户命令和订单命令消费组仍然共享，用于保证
每个用户分区只有一个单写者。

订单完整快照同时携带跨节点单调 `stateRevision` 和当前用户订单状态；本地 RocksDB 只把它作为
未应用 WAL 分区的恢复基线，不能覆盖已有本地事实。订单分区迁移时必须先消费压缩快照，缺少快照或
本地 WAL 与快照无法合并时保持失败关闭，禁止从落后的 `trading_orders` 投影猜测在线状态。

order-provider 使用专用批量监听器消费 `account.command.results.v1`。单实例可设置
`ORDER_ACCOUNT_COMMAND_RESULTS_CONCURRENCY=32`，多实例应共享同一个全局上限。
`<PRODUCT_LINE>:<userId>` Key 保证用户结果有序；只有批量订单状态变更、审计事件和
`ACCEPTED` / `PLACE` Outbox 在同一事务提交后才确认 Kafka 位点。

本地单节点验证可将 `PARTITIONS` 和 `ACCOUNT_COMMAND_PARTITIONS` 设为 4；生产环境仍按容量规划使用 32 分区和 RF=3。

跨服务延迟应以生产者事件或 Outbox 的 `created_at` 为起点、消费者本地处理时间为终点。
`published_at` 只有 Kafka 确认后才落库，可能晚于消费者时间，只能衡量 Outbox 发布延迟。

其他生产 Topic 由 `PARTITIONS=32` 固定。业务流量开始后禁止原地增加按 `symbol` 分区的 Topic：
Kafka 会重新映射交易对，而撮合和 Kafka Streams 持有分区状态。扩容必须创建版本化 Topic，
协调生产者与消费者切换，重启撮合并恢复订单簿，同时重建 Streams 状态。

启动服务前校验：

```bash
TOPIC_PATTERN='surprising\.(instrument\.(events|lifecycle-drain)\.v1|account\.(position|liquidation-fee)\.events\.v1|risk\.(account|position)\.events\.v1|linear-perp\..*)'
kafka-topics --bootstrap-server '<bootstrap-brokers>' \
  --describe --topic "${TOPIC_PATTERN}" > /tmp/linear-perp-topics.txt

test "$(grep -c 'PartitionCount: 32' /tmp/linear-perp-topics.txt)" -eq 25
test "$(grep -c 'ReplicationFactor: 3' /tmp/linear-perp-topics.txt)" -eq 25
test "$(grep -c 'Partition: ' /tmp/linear-perp-topics.txt)" -eq 800
```

缺少任何必需 Topic、分区数不是 32 或 RF 小于 3 时必须阻止部署。Broker/Topic 设置
`min.insync.replicas=2`，关闭非同步副本 Leader 选举；生产者使用 `acks=all` 并启用幂等。

Topic 创建脚本是幂等的，不需要每次删除重建。本地测试可以使用 `RESET_KAFKA=true` 清理旧 Topic
和位点；共享环境及生产环境禁止用删除重建 Topic 的方式清数据。

## Java 运行时

所有服务使用 JDK 25。`surprising-aeron-service` 在 Cluster 内嵌 exchange-core/OpenHFT Chronicle，
启动 Core 节点时需要以下模块参数；`surprising-matching-provider` 已是纯行情投影，不再加载 exchange-core：

```bash
export JAVA_TOOL_OPTIONS="--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
```

`exchange.core2:exchange-core:0.5.3` 会传递引入 Chronicle；Chronicle 不是打包在
exchange-core 内部的私有代码，而是独立的 Maven 依赖。父 POM 通过 Chronicle BOM 统一管理版本，
当前使用的版本为 `chronicle-wire 2026.7`、`chronicle-bytes 2026.4`、`chronicle-core 2026.5`
和 `chronicle-threads 2026.3`。这样不需要修改 exchange-core 源码，也能修复旧版 Chronicle
在 JDK 25 中查找已不存在的 `FileChannelImpl.unmap0` 所造成的 `Bytes` 初始化失败。

Core 服务仍必须使用 JDK 25，并保留上面的 module opens/exports。升级 Chronicle 后必须执行受影响的
Aeron Core 撮合/恢复目标测试，并在阶段出口执行 Core 联合门禁。启动时若仍看到 Chronicle 关于直接内存
反射的非致命告警，只要门禁和撮合运行正常即可；该告警不等同于 `Bytes` 初始化失败。

## 多节点规则

- 所有 candlestick-provider 使用相同的 `surprising.candlestick.kafka.application-id`，但每个节点
  使用唯一的本地 `state-dir`。
- 每个消费者组的有效并行度上限是 32。禁止为每个交易对创建消费者，也禁止给运行中的 Topic
  原地加分区。
- Kafka Streams 在再均衡或重启时从 changelog Topic 恢复 RocksDB。
- 订单下单、撤单、账户结果、撮合结果和只减仓清理必须先进入
  `order.user.commands.v1`，key 固定为 `<PRODUCT_LINE>:<userId>`，由当前分区所有者进入本地单写入 lane；
  不允许 HTTP 节点直接写另一个节点的用户 WAL。
- `order.user.command.results.v1` 是同步等待通知，不是事实源。每个订单 HTTP 节点必须配置唯一
  `surprising.trading.order.kafka.client-id`，使结果消费组独立，否则跨节点请求可能收不到终态。
- 每条产品线的 `core.events.v1` 必须固定为一个分区；它承载全局连续 Export Sequence，禁止原地增加分区。
- 所有 matching-provider 使用相同 market-data `group-id`，同一产品线仅一个实例应用实时 L2 增量；
  WebSocket 等下游可独立横向扩展。
- matching-provider 启动时直接从 Aeron `BOOK_STATE_QUERY` 恢复聚合价格层级和 Export watermark，随后
  从消费组已提交 offset 继续；新消费组从 earliest 开始并跳过 watermark 以前事件。PostgreSQL 不参与
  实时深度/成交恢复，仅承接 24h、历史、审计和对账查询；PG 故障不得阻断下单、撮合或实时行情增量。
- instrument、order、matching、price、risk、liquidation、funding 生产者统一使用
  `acks=all`、`enable.idempotence=true`、`compression.type=zstd` 和
  `max.in.flight.requests.per.connection=5`。
- matching、account、risk、liquidation、insurance 消费者关闭自动提交，使用
  `auto.offset.reset=earliest` 和 cooperative-sticky 分配。
- matching-provider 采用单消费者有界批量读取 Core Event，默认 `MATCHING_MAX_POLL_RECORDS=1024`；事件
  序号不连续时失败关闭，不使用数据库事务、outbox 或本地 WAL 修补缺口。
- 根据消费者积压、处理延迟和数据库事务时间调整 `max-poll-records`。默认值只针对本地开发。
- 生产保持 `restart-on-partition-reassignment=true`；只允许在本地调试时关闭。
- `partition-assignment-startup-grace-ms` 应覆盖并发监听容器完成首次分配的时间，默认 30 秒。
- Trading Order Outbox 在每个 `(topic,eventKey)` 流内保持顺序；积压时优先处理
  `ORDER_RESERVE`、`PLACE`、`CANCEL` 等资金相关记录。
- 避免高频伸缩撮合节点，因为分区移动会触发重启和订单簿恢复。
- 账户持仓及实际收取的强平费都通过 `account_outbox_events` 发布，语义为至少一次；
  WebSocket 必须容忍重复，insurance-provider 通过基金流水唯一键保证幂等。
- 每个 websocket-provider 必须使用唯一 `group-id`，使每个节点都能接收全量公共行情并本地扇出。
  WebSocket 节点不能共用一个消费者组，否则不同节点的客户端会漏消息。
- WebSocket 会话和订阅仅保存在进程内，客户端重连后必须重新订阅。
- gateway-provider 无状态，部署在负载均衡器后，内部路由指向内部 LB、DNS 或服务发现。
- 做市节点通过 `market_maker_strategy_leases(strategy_id, symbol)` 协调。资金账户未充值或风险上限
  未复核前，保持 `surprising.market-maker.engine.enabled=false`。

## 条件单 Redis 索引

静态 TP/SL 价格范围索引固定使用 Spring Data Redis 与 Lettuce，没有功能开关。同一产品线的
trigger-provider 或组合交易入口必须配置相同 Redis 和 Key 前缀：

```bash
export REDIS_HOST=redis.internal
export REDIS_PORT=6379
export REDIS_PASSWORD='replace-with-secret-manager-value'
export REDIS_DATABASE=0
export TRIGGER_REDIS_KEY_PREFIX=surprising:trigger:v1
```

- 每个实例只配置一条产品线，ZSET Key 包含产品线、交易对、价格来源和 Redis Cluster hash tag，
  TP/SL 状态不能跨产品线。
- 推荐 `maxmemory-policy noeviction`，也可使用仅淘汰有 TTL Key 的策略，并启用持久化故障转移。
  禁止 all-keys 淘汰策略，否则可能只丢区间 ZSET 而保留就绪标记。
- 不启用 Spring Redis 全局事务。候选读取和双向移除由 Lua 完成，PostgreSQL 与 Redis 不组成 XA。
- 新静态 TP/SL 写入 Redis 失败时拒绝下单；已提交订单在就绪标记缺失或 Redis 查询失败时回退到
  PostgreSQL 领取。
- 重建租约使用 `SET NX`、TTL 和 token 校验释放；它不能替代执行锁，最终保护仍是 PostgreSQL
  条件更新和 `FOR UPDATE SKIP LOCKED`。
- Redis 超时应明显短于价格事件周期，同时监控 Redis 延迟、错误、数据库回退率、陈旧候选清理、
  `TRIGGERING` 时长和 Kafka 积压。

## 持仓 Redis 读模型

account-provider 的用户持仓接口只读取 Redis。同一产品线的所有实例使用相同 Redis：

```bash
export REDIS_HOST=redis.internal
export REDIS_PORT=6379
export REDIS_PASSWORD='replace-with-secret-manager-value'
export REDIS_DATABASE=0
export POSITION_REDIS_KEY_PREFIX=surprising:position:v1
```

- Redis 启用持久化并使用 `noeviction`。缺少就绪标记时接口返回 HTTP 503，禁止回退 PostgreSQL，
  防止部分缓存丢失被误认为空仓。
- account-provider 在本地事务中合并持仓和抵押品变化，为每个精确持仓 Key 生成一个带 revision
  的最终快照，并与业务变更一起写入 Outbox。提交后相同快照进入本地有界 Redis 加速队列；
  专用消费者负责进程或 Redis 故障后的持久回放，两条路径使用相同 revision CAS。
- 每个用户的 `state`、`margin`、`revision` 三个 Hash 共用
  `surprising:position:v1:{PRODUCT_LINE:userId}` Cluster tag，禁止单独手工修改。
- 启动时由 token 租约协调分页 PostgreSQL 重建；revision CAS 允许提交后写入与重建安全重叠。
- PostgreSQL 事务加 Outbox 是持久边界，Redis 只是可重建快照。队列溢出、非法消息或 Redis
  故障会删除就绪标记，直到持久回放或数据库协调器修复后才恢复读取。

完整设计见 [持仓 Redis 读模型](position-redis-cache.md)。

## 产品线服务实例

后端共用代码，通过配置运行独立产品线实例。订单、撮合、账户、风控、强平、标记价格、K 线、
WebSocket 和资金费流量必须使用产品线 Topic 隔离。

本地启动：

```bash
mvn -q -DskipTests package

PRODUCT_LINE=SPOT PORT_OFFSET=0 ORDER_WAL_NODE_ID=1 ./scripts/start-product-line-providers.sh
PRODUCT_LINE=LINEAR_PERPETUAL PORT_OFFSET=100 ORDER_WAL_NODE_ID=101 ./scripts/start-product-line-providers.sh
PRODUCT_LINE=LINEAR_DELIVERY PORT_OFFSET=200 ORDER_WAL_NODE_ID=201 ./scripts/start-product-line-providers.sh
PRODUCT_LINE=OPTION PORT_OFFSET=300 ORDER_WAL_NODE_ID=301 ./scripts/start-product-line-providers.sh

PRODUCT_LINE=OPTION PORT_OFFSET=300 ORDER_WAL_NODE_ID=301 ACTION=stop ./scripts/start-product-line-providers.sh
```

`PRODUCT_LINE` 必须显式设置，省略时脚本直接失败；脚本向服务传入 `PRODUCT_TOPICS_ENABLED=true`，
若显式传入 `false`，服务启动校验会直接失败。脚本设置产品线 Topic、独立消费者组、唯一客户端 ID 和协调节点 ID，并自动跳过不适用
的服务。`PORT_OFFSET` 加到各服务默认端口。Jar 未构建时使用 `BUILD_SERVICES=true`；用
`SERVICES="order trigger matching account"` 选择子集；CI 未暴露 Actuator 时使用
`WAIT_HEALTH=false`。
脚本会自动把 instrument-provider 放在服务列表第一项并等待其健康检查通过，即使显式 `SERVICES` 未包含
`instrument` 也不会跳过合约 JVM 快照初始化。

六条产品线共同遵循 [Aeron 六产品线统一运行手册](runbook-aeron-six-product-lines.md)，产品特有检查见
[SPOT](runbook-spot.md)、[LINEAR_PERPETUAL](runbook-linear-perpetual.md)、
[INVERSE_PERPETUAL](runbook-inverse-perpetual.md)、[LINEAR_DELIVERY](runbook-linear-delivery.md)、
[INVERSE_DELIVERY](runbook-inverse-delivery.md) 和 [OPTION](runbook-option.md)。不能把一条线的结算资产、
反向数学、funding、delivery 或 exercise 规则复制到另一条线。

systemd/EC2 部署采用相同环境变量：

- `SURPRISING_*_KAFKA_PRODUCT_LINE` 可设为 `SPOT`、`LINEAR_PERPETUAL`、
  `INVERSE_PERPETUAL`、`LINEAR_DELIVERY`、`INVERSE_DELIVERY` 或 `OPTION`。
- 设置 `SURPRISING_*_KAFKA_PRODUCT_TOPICS_ENABLED=true`。
- 拥有独立处理状态的产品线使用独立消费者组。
- Streams 状态目录、撮合客户端 ID、价格/风控/资金费协调节点 ID 必须按进程唯一。
- instrument-provider 默认共享；接口通过 `productLine` 参数或 `X-Product-Line` 请求头区分产品线。

网关路由变量格式为 `GATEWAY_ROUTE_{SERVICE}_{PRODUCT_LINE}_BASE_URL`。客户端只能访问 Gateway
和 WebSocket Edge，不能直接访问产品服务。所有客户端在 REST 请求和 WebSocket 订阅中发送
`productLine`；切换产品线后必须完整刷新 REST 并重新订阅，禁止复用旧行情。

衍生品客户端还应订阅认证后的 `triggerOrders` 通道，按单调 `eventId` 应用完整快照，立即移除
终态记录，并在每次私有 WebSocket 重连后重新加载未完成条件单。

## 可观测性

- 抓取每个服务的 `/actuator/prometheus`。
- 账户结算关注命令处理耗时、端到端事件年龄、失败数、待双边完成成交数和最老未完成时长。
- 撮合关注批量大小、数据库阶段耗时、命令失败、重启次数、订单簿恢复时间和 Kafka 积压。
- Outbox 关注待发布数量、最老记录年龄、尝试次数、发布延迟和清理结果。
- Redis 投影关注就绪状态、重建页数和耗时、CAS 陈旧写、队列丢弃、命令延迟和数据库回退。
- 资金相关报警必须按产品线、用户、交易对、资产和 `traceId` 保留可定位维度。

## PostgreSQL 配置

- 生产 JDBC URL 设置 `reWriteBatchedInserts=true`。
- 连接池总量必须与 PostgreSQL `max_connections` 留出管理和迁移余量。
- 保持 `fsync=on`、`full_page_writes=on`，资金库禁止为性能关闭持久性。
- 定期验证备份恢复和 PITR，不只检查备份任务成功状态。
- 高频表使用短事务、批量写和有界 `FOR UPDATE SKIP LOCKED`，禁止跨全量数据持有长事务。
- 对 sequence、Outbox、持仓、余额、穿仓、基金流水和结算完成表建立容量、膨胀与 VACUUM 监控。
- 运营报表、对账、导出和订单时间线使用未来独立财务运营数据库，禁止在交易主库执行多表报表 JOIN。

## 价格源

- 指数源使用 REST 快照配合 WebSocket 增量，本地维护最新盘口和新鲜度。
- 汇率转换、异常值过滤、最少有效源、降级权重和限幅必须在发布前完成。
- 上游不可用时仍发布不可用状态，使实时消费者主动失效旧价格。
- 标记价格只有在指数事件新鲜、`indexPrice` 非空且状态为 `HEALTHY` 或 `DEGRADED` 时发布。
- 采集器出口 IP 或地区必须满足交易所限制；生产至少使用多个独立价格源。

## Gateway 与 WebSocket

- Gateway 路由名使用白名单，未知服务在访问后端前返回 404。
- 私有路由必须由认证层转发可信 `X-User-Id` 或 `Authorization`。
- WebSocket 私有订阅绑定认证用户，客户端不能通过载荷中的其他 `userId` 订阅他人数据。
- 公共行情每个 WebSocket 节点都要消费，私有事件按认证会话本地扇出。
- 客户端重连后重新认证、重新订阅并通过 REST 快照补齐间隙。

## 定时任务线程池

定时入口只允许位于 `task` 包，负责触发配置并调用 Service。不能在 Task 中直接访问 Repository、
Redis、外部客户端或开启事务。长任务使用独立工作池，避免阻塞 Spring 默认调度线程；批次大小和
并发度必须有上限。

## Core 风控与强平

- Risk State、每 symbol 扫描游标和 Liquidation State 都随 Aeron Snapshot/Archive 恢复。
- Liquidation Coordinator 只查询有界 `Liquidation Work`、提交稳定幂等命令并续跑 Risk Scan。
- PostgreSQL 仅保存 `core_liquidation_projection` 查询投影；Valkey/Redis 不参与风险或强平裁决。
- 不创建 `liquidation.candidates.v1`，也不部署 candidate consumer、Redis lease 或 PG 强平事务。

## Kafka 客户端身份

每个进程使用唯一 `client.id`，消费者组按业务状态所有权共享或隔离。协调节点 ID、租约 owner ID
和实例 ID 不能使用所有副本相同的静态值。证书、SASL 密钥和 Redis 密码由密钥管理系统注入，禁止
写入仓库。

## 部署顺序

1. 执行 Core Exporter Flyway migration，至少包含 `V006__enrich_core_liquidation_projection.sql`。
2. 为当前产品线启动三节点 Aeron Cluster，验证 Leader、Archive、Snapshot 目录和三 Member 状态一致。
3. 创建并校验当前产品线 Kafka Topic；启动 Core Exporter，确认 Export backlog 可 drain 且 PG 投影推进。
4. 启动 instrument 和价格输入，确认版本化 instrument、指数价与标记价能提交到当前 Product Line Cluster。
5. 启动 account、order、risk、liquidation、insurance、ADL、funding 和 matching projection；这些服务
   不得在 Aeron 不可用时回退旧 WAL、Redis Risk 或 PostgreSQL 强平事务。
6. 启动 trigger、candlestick、WebSocket、Gateway 和客户端入口。
7. 使用单产品线冒烟和资金核对脚本验证功能、恢复和 `funds-diff=0`，再逐步放量。

## 运行开关

- `surprising.risk.calculation.enabled=false`：暂停定时风控和持仓事件触发。
- `surprising.liquidation.execution.enabled=false`：暂停 Liquidation Work 执行和 Risk Scan 续跑。
- `surprising.insurance.coverage.enabled=false`：暂停保险基金覆盖，穿仓保持显式不变。
- `surprising.adl.scanner.enabled=false`：暂停 ADL 领取。
- `surprising.market-maker.engine.enabled=false`：暂停做市。

开关只暂停对应执行，不得清理待处理状态。强平恢复后重新查询 Aeron Liquidation Work 并续跑
Snapshot 中的 symbol Risk Scan；不能从 Kafka candidate 或 PostgreSQL 猜测执行状态。

## Aeron 六线门禁

P7 使用最终 Aeron 权威边界逐线验证，不使用仍依赖旧 WAL、Redis Risk 或旧强平 Saga 的历史脚本：

```bash
./scripts/run-six-product-line-gates.sh
```

覆盖六线核心撮合、资金、资金费、Risk/强平、交割/行权、Leader 切换、Snapshot、冷恢复及
Kafka/PG 投影。Gateway、WebSocket 和完整对外 API 正式准入属于 P8，必须在用户确认后单独执行。

## 本地集成测试

```bash
./scripts/integration-smoke.sh
```

本地默认使用 Homebrew PostgreSQL 和 Kafka。不要对共享开发数据库运行会重置数据的测试。

## Kafka 交易链路测试

```bash
./scripts/kafka-trading-smoke.sh
```

测试期间做市进程保持运行。除非测试目标明确涉及 wallet，交易后端测试不启动 wallet 服务。

## 故障与一致性行为

- 所有资金、订单、撮合、风控、资金费、保险基金和强平写入采用快速失败。预期行未写入意味着状态
  已分叉，必须回滚并调查，不能把它当作正常幂等。
- 幂等只接受明确设计的唯一键，例如命令 ID、`client_order_id`、成交参与方和基金流水引用；
  信封哈希冲突属于数据错误。
- 共享 trading、account、risk Outbox 的已发布记录保留七天。各发布者每分钟只清理自己负责的
  aggregate 或产品线，最多执行十个 10,000 行短事务，使用 `FOR UPDATE SKIP LOCKED`；
  未发布或失败记录绝不清理。
- order-provider 的死亡开关、算法单到期扫描和普通未完成订单都从用户分区 JVM 状态读取；Redis
  只能作为查询或跨节点协调投影，丢失时暂停相关查询和执行，不回退数据库猜测空状态。
- `GET /orders/open` 遇到快照未就绪、用户分区序号断裂或投影不完整时返回未就绪。撤单、改单、冻结、
  解冻和终态都通过用户分区 WAL 按序追加，数据库仅异步投影和审计。
- ADL 候选和执行条件读取 Aeron，PG 只选择 `core_liquidation_projection` 中待覆盖缺口并保存审计；
  不存在 Redis 或数据库权威候选回退。
- 强平协调器使用 `LIQUIDATION_WORK_QUERY` 获取当前有界工作，只有 trigger sequence 与 Core 当前 mark
  一致的 `PLANNED` action 才会返回；执行命令仍在 Core 内重新校验仓位、Risk 和 mark。
- 市价单在标记价格缺失或过期时拒绝；名义价值和初始保证金按受保护执行区间计算。
- Matching 启动从 Aeron `BOOK_STATE_QUERY` 恢复聚合 L2 与 Export watermark，不从数据库重建订单簿；
  Core Event 断序时停止增量投影并重新 bootstrap。
- 公共深度不写 Outbox；每个交易对只保留一个待发布完整快照，Kafka 背压可以丢中间快照，
  下一份完整快照自愈。公共成交使用独立有界 FIFO，不合并成交；溢出只丢该交易对最旧公共事件。
  这些公共链路失败不能阻断金融结算。
- 成交和双边资金结算在同一 Aeron 命令内完成；Core Export Event 承担成交审计和外围分发，不存在
  Matching 成交 outbox、Account 结算命令回环或数据库订单簿恢复。
- 成交持久化要求订单仍为未完成状态、`remaining_quantity_steps >= fillQty` 且
  `quantity_steps = executed_quantity_steps + remaining_quantity_steps`。禁止用
  `LEAST/GREATEST` 掩盖超额成交。
- 撮合解冻要求 `locked_units >= releaseUnits`。缺少冻结资金时事务失败，不能直接增加可用余额。
- 只有 `reduce_only = TRUE` 订单允许缺少冻结快照；其他订单必须携带正 `reserved_units`、
  账户类型和资产。
- 下单先插入 `trading_orders`，再把可用余额移到锁定余额。只有
  `(user_id, client_order_id)` 部分唯一冲突是幂等重复，返回原订单且不能再次变更余额。
- 开仓成交从不可变订单快照消费锁定资金，并在 `account_trade_settlement_sides` 审计；
  缺少快照或更新未命中时回滚。订单终态只释放扣除已消费和已释放后的剩余金额。
- 每笔成交必须有 Maker/Taker 两行结算侧记录，并出现在双边完成视图。`TRADE_PNL`、
  `TRADE_FEE` 流水和回填快速失败；正费率扣款，负费率返佣。
- 盈亏和资金费只能消耗由 `account_position_margins` 支撑的锁定抵押品，不能动用挂单冻结。
- 持仓事件在账户变更事务内保存完整持仓及抵押品快照和 PostgreSQL revision，Kafka 发布至少一次；
  Redis 用 revision CAS，其他消费者必须容忍重复 `eventId`。
- 持仓缓存消费失败不能跳过记录，并删除产品线就绪标记；回放或 PostgreSQL 对账修复前查询失败关闭。
- 持仓归零事件会原子取消同范围、事件之前的 `PENDING` 条件单；提交后再移除 Redis 成员。
- order-provider 按用户串行消费持仓事件，锁定同范围未完成只减仓订单，对方向错误、版本陈旧或数量
  超限的订单发送条件撤单。account-provider 绝不能写交易订单表。
- risk-provider 不信任持仓事件中的会计数值；它在风险组锁内重新加载 PostgreSQL 的完整持仓、
  余额和穿仓，再替换 Redis。
- 资金费每页的流水、余额、穿仓、支付和增量完成状态快速失败；支付行和账户命令 Outbox 同事务提交。
- Risk Snapshot、每 symbol 扫描游标、Liquidation State 和强平费都随 Aeron Snapshot 恢复；PG 投影失败
  只影响历史查询，不得停止或改变 Core 裁决。
- insurance-provider 使用 `FOR UPDATE SKIP LOCKED` 拆分穿仓，并在每次扣款前锁基金余额。
  基金为空时穿仓保持显式，补充基金后重试。
- Core takeover liquidation 在一条命令内校验用户、symbol、保证金模式、仓位方向、instrument version、
  完整仓位数量、Risk 状态和 trigger price sequence；校验成功后撤销该 symbol 开放单、按当前 mark
  结算仓位、收取实际可收强平费并记入 Insurance Treasury。
- 价格或仓位已变化时 Core 取消/拒绝旧计划且不撤开放单；网络超时重试必须复用由产品线、liquidationId、
  trigger sequence、执行价和费率生成的稳定 `commandId`。
- ADL 只在对应资产保险基金为零时领取达到最小年龄的穿仓；减仓前重新锁目标持仓。
  保证金释放要求 `margin_units >= releaseUnits`，未命中时事务失败。
- 合约配置采用不可变版本；下游读取当前快照或消费 `instrument.events.v1` 后替换本地缓存。
- 到期合约先进入 `SETTLING`。order、trigger、account 完成订单排空、撤单和资金释放核验并写入
  相同生命周期版本的确认后，instrument-provider 才允许进入 `CLOSED`。

## 故障排查

- `price_symbol_leases` 节点死亡后未转移：等待 `lease_until`，若时间异常靠后，检查各节点时钟同步。
- 节点重启后没有风控候选：检查产品线 Redis 就绪标记、风险组注册表、反向索引和协调租约。
- 价格 sequence 有空洞：失败重试后属于正常现象；只有序号倒退才需要调查。
- 指数不可用：查看最新指数事件状态，再查 `price_index_components` 中的
  `STALE`、`OUTLIER`、`ERROR` 或换算失败原因。
- Binance 返回 `451` 或 Bybit 返回 `403`：检查采集器出口地区和 IP 是否被交易所限制。
- WebSocket 反复重连：检查上游连通、ping/pong、空闲超时和出口防火墙。
- Kafka 积压：先在 32 分区协议内调整实例数、并发、批量和数据库容量。禁止直接增加运行中 Topic
  分区或按交易对创建 Topic；超过 32 需要版本化迁移和状态重建。
- 部分 WebSocket 节点缺公共行情：确认每个进程使用唯一 `websocket.kafka.group-id`。
- 私有持仓不更新：检查 `account_outbox_events` 积压、持仓 Topic、认证用户和 `positions` 订阅。
- 风险/盈亏不更新：检查风险扫描新鲜度、风险账户及持仓 Topic 和私有
  `positionRisk` / `accountRisk` 订阅。
- 私有 Gateway 路由返回 401：检查认证层是否转发 `X-User-Id` 或 `Authorization`。
- Gateway 返回 404：检查 `{service}` 是否配置在路由白名单。
- RocksDB 恢复慢：检查 changelog 保留期、本地状态目录持久化、磁盘吞吐和文件描述符上限。
- 市价单返回 `MARK_PRICE_UNAVAILABLE`：检查标记价格新鲜度和
  `market-max-mark-age-ms`。
- 限价单提示标记价格不可用：检查 `limit-price-max-mark-age-ms` 和标记价格链路，不能通过放宽
  参数掩盖生产故障。
- 订单返回 `SELF_TRADE_PREVENTED`：取消或等待同用户可成交的反向挂单。
- 撮合再均衡后退出：属于接收新分区后的预期行为，检查 systemd/ASG 重启策略和数据库恢复耗时。
- 撮合命令处理时退出：检查数据库、Outbox 和 Kafka 错误，由编排系统从最后提交位点恢复回放。
- 撮合恢复发现交叉订单：检查该交易对的 `trading_orders` 和 `trading_match_results` 状态。
- 保险基金未覆盖穿仓：检查基金余额、正 `deficit_units` 和数据库连接。
- ADL 未执行：检查穿仓年龄、基金是否为零、标记价格新鲜度和对手盈利队列。

## 成交接入检查

1. 每笔实际成交产生一条 Kafka 记录。
2. 使用 `symbol` 作为 Kafka Key。
3. 同一交易对的成交进入相同 Topic，并具有单调 `sequence`。
4. 载荷包含 `tradeId`、`sequence`、`tradeTime`、`price`、`quantity` 和真实 `side`。
5. 不要在本服务前聚合成交；确需聚合时，聚合成交必须具有自己的唯一 ID 和序号。

## 动态交易对

K 线服务启动时通过 Instrument 内部聚合 RPC 加载本产品线 JVM 快照，并消费
`surprising.instrument.events.v1` 增量事件。快照更新后，K 线服务自动刷新本地交易对注册表，
不再维护独立交易对表或从主库读取合约表。

## K 线 API

区间查询：

```http
GET /api/v1/candlestick/candles?symbol=BTC-USDT&period=1m&startTime=2026-06-30T10:00:00Z&endTime=2026-06-30T11:00:00Z&limit=500
```

最新 K 线：

```http
GET /api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m
```

支持周期：

```yaml
surprising:
  candlestick:
    periods: [ "1m", "5m", "15m", "30m", "1h", "4h", "1d" ]
```
