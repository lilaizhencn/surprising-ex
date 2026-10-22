# 统一行情应用：成交导出、K 线与实时路由

本目录保留聚合 POM，供 Maven/IDE 将 `surprising-realtime-api` 和
`surprising-realtime-provider` 分组导航；两个子模块统一直接继承 `surprising-parent`。
构建从仓库根目录执行 `mvn -pl surprising-realtime/surprising-realtime-provider -am package`；
现有 artifactId、Java 包名和行情进程入口不变。

## 共享契约与查询入口（2026-10-21）

`CandleUpdatedEvent`、`CandleStatus` 归入 `surprising-realtime-api`，Java 包仍为
`com.surprising.candlestick.api.model`。Gateway 和行情应用直接使用现有 realtime-api 依赖，
不再构建 market-data API 或聚合 POM。事件字段、枚举值和 JSON 保持不变。

盘口入口仍是 Gateway 的 `/api/v1/gateway/trading-market/orderbook`，由
`com.surprising.trading.matching` 读取 realtime 写入 Valkey 的按产品线、币对隔离视图。
视图未初始化、缺失或超过 5 秒未刷新返回 503，不回查 Core。请求深度接受 1–100，
实际窗口最多每侧 50 档；响应 depth 反映实际窗口。内部 `/api/v1/trading/market/orderbook`
仍由 Gateway 9094 承接。K 线聚合、历史查询与路由仍运行在本行情进程。

## 未收盘 K 线实时更新（2026-10-09）

`CandlestickStreamConfiguration` 将所有 1m 完整快照（PARTIAL/CLOSED）交给
`CandleRollupProcessor`，因此 5m、15m、1h、4h、1d 等周期不再等待分钟收盘才更新。
`CandleRollupAccumulator.activeMinute` 在原有 RocksDB 值中保存唯一活动分钟快照；
同一分钟更新替换该快照，跨分钟只折入累计值一次，晚到的 CLOSED 通知通过原有 seen 标记去重。
历史分钟修正仍从已持久化分钟有界重建，并保留尚未包含在 SQL 中的活动分钟。
普通实时更新不访问 SQL；状态所有者仍是该产品线 Kafka Streams 分区，恢复沿用原有 changelog。
HTTP 热缓存与 WebSocket 都使用同一完整 OHLCV 快照，前端无需用张数重算基础币成交量。

`surprising-realtime-provider` 现在同时运行可靠成交导出、K 线与实时路由，默认 HTTP 端口 **9095**。
盘口 HTTP 入口位于 gateway，查询读取 realtime 的 Valkey 盘口视图；不再部署 market-data provider。原有实时协议、Valkey 查询视图和 Core 权威状态保持不变。

## K 线链路

`TradeExportService`（独立线程）→ 产品成交 Kafka topic → `CandlestickStreamConfiguration` →
RocksDB 聚合/去重/水位线 → PostgreSQL 历史与 K 线 Kafka 事件。
`PublicTradeEventMapper` 将永续 U 本位成交的 `quantitySteps` 按合约快照中的 `contractMultiplierPpm` 换算为基础币数量；K 线成交量、逐笔成交 `quantity` 与前端盘口数量保持同一基础币单位。其他产品线仍按各自合约数量规格处理。
`CandlestickStreamConfiguration` 启动时用当前产品线的 Kafka bootstrap 创建 32 分区的 K 线事件 topic，确保 Kafka Streams 的 sink/source topology 可以正常进入 RUNNING。成交导出只读取 Core 已提交状态；批量撮合预派发必须按实际结果数遍历，否则 Core 回放被未完成撮合阻断，K 线也会停在旧成交。
K 线更新同时以本地方法进入 `RealtimeRouter.offer` 的有界队列，由路由线程按订阅推送。
不再创建本进程的 `RealtimeJsonPublisher` 或通过 Aeron 将 K 线回送给自己。

Kafka Streams 线程只做聚合、持久化与非阻塞入队；路由线程负责 Valkey 和下游 Aeron 发送，
不在路由线程查询 K 线数据库或执行聚合。队列满时沿用实时丢帧计数；历史 K 线仍由数据库和 Kafka 路径保存。
共享 JVM 的 CPU、GC 和内存故障域仍会相互影响，并不等于资源完全隔离。

保留原 1m 关闭不可改写、迟到数据处理、高周期汇总、去重及状态恢复规则；没有改动 K 线算法。
每个行情实例只聚合一个 `PRODUCT_LINE`，使用独立数据库 schema、Kafka Streams application-id 和状态目录。
旧 `candlestick_candles` 表没有 product_line 列，禁止多产品共用 schema。

## 启动与迁移

- `REALTIME_ROUTER_PORT` 默认 9095；K 线 Feign 和 gateway 路由默认已改为此端口。自定义地址需同步修改。
- `REALTIME_ENABLED=false` 只关闭 Router，K 线聚合和 HTTP 查询仍启用；开启 Router 时先启动 gateway，由 gateway 内嵌启动共享 MediaDriver。
- 先启动 gateway 并等待 liveness，再启动 price 和本行情应用，以便 `SymbolRegistryService` 拉取合约快照。
- 保留旧 market-data 数据库、Kafka Streams application-id、topic/changelog；迁移前停止旧实例。
  `CANDLESTICK_STATE_DIR` 指向原状态目录，或在空目录从原 changelog 恢复，不能误改 application-id 后当成无损迁移。
  本地脚本默认使用本轮运行目录下 `candlestick-state`。
- `TRADE_EXPORT_ENABLED=true` 在本进程启动成交导出线程，才能持续获得真实成交 K 线；它与 `REALTIME_ENABLED` 独立。Core 仍独立，行情中的回放状态不是交易权威账本。
- 部署数据源、Kafka、Valkey 和当前产品配置。RocksDB 原有 native 内存预算仍需保留，合并不表示这些缓存消失。

## 实时行情、私有状态和 Valkey 查询

实时数据在交易提交完成后进入有界 outbox，由独立 Aeron 线程发送到 Router。
Router 在 Valkey 查询订阅节点，为每个目标 WS 节点维护一个 Publication；不向所有 WS 节点广播。
`RealtimeRouter` 每轮最多取 256 帧，在 `ValkeyRouteDirectory.targets(Collection, now)`
按产品线、用户/公共频道与币对去重，流水线查询有效订阅租约及节点端点。
查询结果只属于这一轮，下一轮重新读取，不持久缓存订阅或延长租约。
帧仍按接收顺序组装完整 commit，每个用户的绝对值更新仍由原 Lua 原子应用；
跨轮完成的 commit 若包含本轮未查询的路由，会实时补查。发生查询错误时使来源失效，依靠权威快照恢复。
保留 8192 帧 / 16 MiB 有界队列，并分别暴露 `realtime.router.input.dropped`
（入队/查询丢弃）与 `realtime.router.transport.dropped`（节点传输丢弃），便于区分积压和连接问题。

`ValkeyReadViewStore.applyCommits` 将本轮已收齐的提交按原顺序送入 Redis pipeline；
每个用户、每次提交仍单独执行原子 Lua，不合并提交版本或资金事实。写入失败不继续向订阅节点发送。
初始快照和来源切换先刷新前序提交，保持快照及增量的先后边界。

`RealtimeFrameCodec` 直接向最终消息写入 UTF-8，解码时直接读取输入数组中的文本区间；
保留 v1 字节格式、128/256 字节文本上限，以及 payload 的防御复制。
Redis pipeline 在 `ValkeyReadViewStore.deltaCommand` 直接生成 Base64 字节，避免中间字符串再转 UTF-8。
`RealtimeVersion.bytes` 直接生成 19 位日志位置和 10 位 ordinal 的补零 ASCII 版本号，
替代 `String.format` 和数值装箱；保留冒号分隔、字典序及 `Long.MAX_VALUE` / snapshot fence 边界。
Router 线程复用单个 256 帧批次列表，每轮结束（包括失败）清除引用，不缓存业务状态或订阅结果。

realtime 使用 `spring-boot-starter-data-redis` 的 Lettuce。普通命令复用共享连接；
pipeline 使用专用连接，因此显式引入 `commons-pool2` 并启用
`spring.data.redis.lettuce.pool`：最大连接数/最大空闲数均为 4，最小空闲配置为 1，
借用等待上限 500ms；连接和命令超时同为 500ms。池用于复用专用连接，不为每轮流水线建立 TCP 连接。
`MarketApplicationContextTest` 校验实际创建 pooled Lettuce 配置及池上限，并覆盖六产品线启动边界。
盘口快照请求每 100ms 最多轮询 8 个订阅币对；20 个币对约三轮覆盖。实际 WS 延迟仍受
Core、路由队列和网络影响，不能把该周期当作端到端延迟承诺。

成交导出 `CommittedTradeExporter` 在 Aeron 回放回调出错后保留第一个异常并停止本轮处理，
从 poll 返回后向导出服务抛出；不再由 Aeron 默认错误处理器只打印错误后继续前进。
失败批次不推进持久 checkpoint，后续重试仍从已确认位置开始。

Valkey 只保存可重建的查询视图，不裁决余额、风控或成交。交易 owner 不调用 Valkey/Kafka/网络。
新增编码、队列和异步快照有实际 CPU/分配成本；性能证据与限制见根目录 `PERFORMANCE_VALIDATION.md`。

## 进程配置

所有组件使用同一协议版本和相互隔离的六产品线标识。同机部署先启动 gateway（内嵌应用侧 MediaDriver），再启动 price/realtime，并向对应 Java 进程提供
`--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`。
只配置内网端点，Aeron 数据面及控制面不直接面向公网；它们不承担用户身份验证。

1. Core 每个成员配置 JVM 属性：

   ```text
   -Dsurprising.realtime.directory=/dev/shm/aeron-core
   -Dsurprising.realtime.channel=aeron:udp?endpoint=ROUTER_PRIVATE_IP:21010
   -Dsurprising.realtime.stream=2101
   -Dsurprising.realtime.control-channel=aeron:udp?endpoint=CORE_PRIVATE_IP:21020
   -Dsurprising.realtime.control-stream=2102
   ```

   只有 leader 发布实时帧和处理查询请求；复制状态、恢复和下单裁决仍使用原有 Cluster。
   三成员 HA 环境使用 Router 的 Aeron manual MDC 控制 Publication，给每个成员独立的 UDP destination。
   查询控制请求送达所有 Core 成员，但只有 leader 执行；不依赖公网 multicast，也不向所有 WS 节点广播。
   下例可以替代对应产品的单播配置，六产品分别配置自己的三个成员：

   ```yaml
   surprising:
     realtime:
       router:
         control-channels:
           SPOT: aeron:udp?control-mode=manual
         control-destinations:
           SPOT:
             - aeron:udp?endpoint=SPOT_CORE_0:21020
             - aeron:udp?endpoint=SPOT_CORE_1:21020
             - aeron:udp?endpoint=SPOT_CORE_2:21020
   ```

2. Router：构建 `mvn -pl surprising-realtime/surprising-realtime-provider -am package`，以 Spring Boot 启动。
   可执行产物为 `surprising-realtime/surprising-realtime-provider/target/surprising-realtime-provider-1.0.0-SNAPSHOT-exec.jar`，使用 `java` 加上述 JVM 参数后 `-jar` 启动，并用 `--spring.config.additional-location=file:/path/to/router.yml` 加载外部配置。
   自带 `application.yml` 使用 `VALKEY_HOST/PORT/USERNAME/PASSWORD`、`AERON_DIR`、`REALTIME_ROUTER_CHANNEL`。
   在外部配置文件明确六条 control-channels：

   ```yaml
   surprising:
     realtime:
       router:
         control-channels:
           SPOT: aeron:udp?endpoint=SPOT_CORE_PRIVATE_IP:21020
           LINEAR_PERPETUAL: aeron:udp?endpoint=LINEAR_PERP_CORE_PRIVATE_IP:21020
           INVERSE_PERPETUAL: aeron:udp?endpoint=INVERSE_PERP_CORE_PRIVATE_IP:21020
           LINEAR_DELIVERY: aeron:udp?endpoint=LINEAR_DELIVERY_CORE_PRIVATE_IP:21020
           INVERSE_DELIVERY: aeron:udp?endpoint=INVERSE_DELIVERY_CORE_PRIVATE_IP:21020
           OPTION: aeron:udp?endpoint=OPTION_CORE_PRIVATE_IP:21020
   ```

   单个路由分区只运行一个 active Router。多个 active Router 同时修改相同 source epoch 会互相使快照失效；需要按产品线分区，不能直接多副本抢跑。

3. WS 节点设置 `surprising.realtime.enabled=true`、`surprising.realtime.directory`、
   `surprising.realtime.ws.channel=aeron:udp?endpoint=THIS_WS_PRIVATE_IP:21030`，以及同一 Valkey 连接配置。
   端点必须是 Router 可达的本节点地址。每次进程启动生成新 UUID，节点租约15秒，每5秒续期；最后一个本地订阅取消后删除路由。
   每个用户可连接多个节点，Router 只发给这些节点；节点再匹配本地频道、instrumentId、用户与 period。
   Aeron receiver 不可用时停止发布节点租约并拒绝新订阅。

4. Account/Trading Provider 设置 `surprising.realtime.enabled=true` 和同一 Valkey。
   余额、仓位、未完成订单、用户触发单查询使用 `ValkeyUserQueries`。指令所需的权威校验以及管理员查询保持 Core 路径。
   未初始化/版本不足/过期返回 HTTP 503，不因 Valkey 故障回查交易 Core。
   此属性是整套进程迁移的部署开关，不应只开 WS 而遗漏查询 Provider。

5. Price/Market Data Provider 设置 `surprising.realtime.publish-json=true`、`surprising.realtime.channel` 指向 Router、
   `surprising.realtime.directory`。指数、标记、资金费率与 K 线更新使用这个独立有界出口；Core 需要的可靠价格输入 Kafka 不改为可丢失推送。
   WS 启用实时路由后停用原 KafkaFanoutConsumer，避免重复推送。Kafka 的其它可靠用途保留。

Valkey TLS/ACL/集群地址使用标准 Spring Data Redis 配置；生产务必设置连接和命令超时，例如500ms。
每个用户 hash 和版本 hash 使用相同 hash tag，Lua 更新在单 slot 原子执行。

## 状态与客户端协议

- 所有订阅明确 `productLine`。私有订阅用户来自已认证连接，不能指定其他用户；公共频道按 instrumentId 路由。
  新增 depth/bookTicker 使用具体 instrumentId，50 档完整深度覆盖更新，不发送需要连续重放的差量 order book。
- 实时实体以 `(productLine, userId, kind, entityId)` 为身份。`version` 是固定宽度字符串 `logPosition:ordinal`，不能转成 JavaScript Number。
  payload 是实体变更后的绝对值，余额不是加减差额；旧版本/重复版本直接忽略。订单终态删除未完成列表中的对应订单，仓位数量0删除当前仓位，终态触发单删除当前触发单。
- 私有频道覆盖 orders、triggerOrders、positions、accountState、executionReports、positionRisk、accountRisk。
  accountRisk 推送按 position entity 给出风险变化，不把不同资产/保证金模式的 equity 简单求和。
  快照 `positionRisks` 保存当前仓位最近一次 Core 风险评估，并携带 priceSequence；它不是实时重新计算的风控裁决。
- subscribe ACK 表示路由登记成功。随后收到 `snapshot`，状态可能 INITIALIZING/STALE；只有 READY 可建立完整基线。
  客户端必须先缓存订阅开始后的增量，收到 READY 快照后用 snapshotVersion 作围栏，保留并重放大于围栏的更新。
  此规则同样适用于后续自动补偿快照；不可直接覆盖比快照新的本地实体。迟到终态/空仓事件也参与版本判断。
- `GET /api/v1/realtime/{productLine}/state` 使用 Bearer JWT，只查询认证用户，返回统一快照及状态。
  旧业务查询中的 minExportSequence 使用单独的 Core 导出水位，不能拿 logPosition 代替。
- 丢失一条私有推送允许暂时不展示，但 Valkey 不允许永久依赖有缺口的增量：提交帧需 BEGIN/END 连续完整，
  丢失整次提交通过 exportSequence 前后水位识别，source epoch 改变后旧视图变为 STALE。
  每个活跃用户最短每5秒请求一次完整快照，即使之后没有交易，也能修复最后一次更新丢失。Router 重启同样重建 epoch 并补快照。
- Valkey 保存当前状态及短期终态 tombstone，完整快照移除旧 tombstone。它不提供永久订单/成交历史，executionReports 不落入 Valkey hash。

## 有界容量和故障行为

Core outbox 8192帧/8MiB，事务整体溢出则整体丢弃；Sender offer 不重试。
Router 入口8192帧/16MiB，每个待组装批次最多8192帧/8MiB，最多32个待组装批次，5秒过期。
单帧上限1MiB。每用户快照当前最多4096个账户/订单/预留/仓位/触发单/杠杆实体；超限不能返回截断的 READY 数据。
Core 每10ms最多接一个后台请求，用户快照与深度各最多一个进行中；Lane 忙时拒绝，后续重试。
Router 每200ms每产品最多请求16用户/4 instrumentId、全局32个待完成用户快照；快照新鲜度15秒，HTTP/WS兴趣租约30秒。
这些是明确的首版容量边界，在线规模过大时会返回 STALE，不能承诺任意在线用户数下都在15秒内修复。

每个 Router 最多64个目标节点 Publication，30秒未使用释放；8MiB term，每个 Publication 大约映射三个 term，需单独预算 native/mapped memory。
慢节点或断连节点只丢它自己的 offer，不重试、不拖住其他节点，更不会把反压传入 Core。
Valkey 故障影响查询/路由，独立线程重连；交易仍可提交。恢复后通过快照校准。
暴露 Micrometer `realtime.router.dropped/failures/queued.bytes/queued.frames` 和 `realtime.receiver.failures`；
Aeron Sender/Outbox 同时提供 sent/dropped/failures/droppedBatches 计数。部署需对这些计数、READY比例、Valkey延迟和租约续期告警。

## 可靠成交与 K 线

`com.surprising.realtime.provider.export.TradeExportService` 通过 Spring 生命周期管理独立的
`committed-trade-export` 线程；`CommittedTradeExporter` 从 Aeron Archive **已提交**范围回放 Core 命令，
恢复确定性成交，写入原有各产品线 `match.trades.v1` topic。原 tools 中的独立 Main 已删除。
它不消费可丢失的 realtime outbox，不在 live Core 或 Router 线程内等待 Kafka。

同一 Archive recording 内复用一个持续的 bounded replay。导出线程把本轮已提交位置
（同时受下一 term 边界和停止位置限制）写入独立 Aeron limit counter，Archive 只回放到此处；
消费端再用 `Image.boundedPoll` 限制应用位置。提交位置增长只更新上限，不反复建销 replay。
分片消息的 assembler 跨提交批次保留，checkpoint 只推进到完整消息边界；切换 recording 时
停止旧 replay、关闭订阅并清空重组缓冲，禁止半条命令跨 recording。停止或失败时释放 replay 和 counter。

`ArchiveReplayIntegrationTest` 在真实 Driver/Archive 上分次推进提交上限，
验证分片命令不会提前交付、同一 image 持续复用，并验证即使消费端普通 poll 也无法越过 Archive 上限。
`ArchiveReplayTest` 覆盖 recording 切换和关闭失败时的资源释放；
`CommittedTradeExportIntegrationTest` 使用真实 Kafka 验证半条消息检查点、恢复后的成交身份、
投影失败和跨产品线命令拒绝。测试没有运行远端服务器或六产品线在线负载，吞吐及 P99 延迟仍需部署后实测。

统一启动脚本自动传入下列环境变量；直接启动行情 JAR 时也需要配置：

| 配置 | 含义 |
| --- | --- |
| `TRADE_EXPORT_ENABLED` | 默认 false；U 永续完整部署脚本默认 true |
| `TRADE_EXPORT_CLUSTER_DIR` | 本产品 Core 成员的 cluster 目录（RecordingLog） |
| `TRADE_EXPORT_AERON_DIR` | 同一 Core 成员的 Aeron 目录，**不是** app MediaDriver 目录 |
| `TRADE_EXPORT_ARCHIVE_CHANNEL` | 同一成员的 Archive 控制通道 |
| `TRADE_EXPORT_CHECKPOINT` | 原导出 checkpoint 文件路径，迁移时保留 |
| `TRADE_EXPORT_CLUSTER_ID` | 可选；默认使用本产品的 ProductLineClusterLayout ID |
| `TRADE_EXPORT_KAFKA_CONFIG` | 可选 Kafka 安全配置文件，不更换 checkpoint/topic/事务身份 |

产品线和 Kafka bootstrap 复用本应用 `surprising.candlestick.kafka` 配置。
`surprising.trade-export.checkpoint-interval` 默认 60s；`retry-interval` 默认 5s。
异常时关闭本次资源，从持久化 checkpoint 重试；损坏 checkpoint 或缺失历史不会跳过，需人工修复。
`/actuator/health/tradeExport` 提供导出组件状态（整体 health 同时包含该组件）；
状态 UP 表示资源已连接且找到本产品 commit counter，**不表示导出已追平**。
默认 liveness/readiness 探针保持独立，避免导出故障触发整个行情进程反复重启。

停止时先请求导出线程结束，在完整 Cluster 消息边界发送剩余成交并保存 checkpoint，再关闭资源。
线程等待上限 90s，Spring 每阶段关闭预算 120s；脚本先向 realtime 发 TERM 并等待最多 120s，
之后才停止 Core/Archive。超时强制终止仍按最后持久化 checkpoint 恢复。
Kafka 默认 max.block=10s、request.timeout=10s、delivery.timeout=30s；自定义 Kafka 配置若增大阻塞时间，
需要相应评估关闭预算。正常线程退出不通过 interrupt 打断 Kafka 提交或快照写入。

迁移前先停止旧 trade-export（避免事务 producer 相互 fencing），将 `TRADE_EXPORT_CHECKPOINT` 指向原文件；
同产品只开一个导出线程。旧 checkpoint 格式、topic、tradeId/sequence 与事务 ID 均不变。
realtime 现在依赖 Core service 的**回放库**，显式组件扫描不包含 Core 配置，不会启动交易集群；
权威交易 Core 仍是独立进程。回放状态、Kafka、RocksDB 和实时队列共享 heap/GC，需要重新评估行情内存预算。
关闭内嵌导出不影响实时 WebSocket 成交链路，但 K 线失去该来源的新成交。

checkpoint 是 exporter 自己的 Core 快照/已处理logPosition/tradeSequence，SHA-256校验，Kafka事务确认后才原子保存。
commit counter 暂停在 Aeron 分片中间时，恢复游标保留在前一个完整 Cluster 命令后；后续重读未完成命令，不从中间分片续读。
Crash 在 Kafka确认与保存之间会重发相同 tradeId/sequence；K线按 sequence 去重，Kafka消费者使用 read_committed。
Kafka长时间故障会使 exporter 落后，不能推进 checkpoint；Archive 保留期必须覆盖 exporter 的恢复位置。
新 exporter 从0开始，需要完整保留的记录；缺失历史直接失败，不默默跳到当前。行情进程需要访问该成员的 RecordingLog、Aeron 共享内存目录和 Archive，部署时必须保留这些挂载/权限。
不同产品有独立 topic、checkpoint、transactional.id；同产品只允许一个活动 exporter。

## 验证

`ValkeyReadViewIntegrationTest` 检查Lua原子更新/乱序/精度/初始化/租约隔离；`RealtimeRouterIntegrationTest` 使用真实Aeron UDP，
检查目标节点隔离、提交缺口失效和快照恢复。测试默认执行本机 redis-server 作为兼容测试；
`-Drealtime.test.server=/path/to/valkey-server` 可改用真实 Valkey。没有真实 Valkey 结果时不能称为 Valkey 环境验收。
`CommittedTradeExportIntegrationTest` 使用真实 MediaDriver/Archive 和 embedded Kafka KRaft，检查 commit fence 与重复恢复。
已在本机从官方 8.0.1 源码构建 Valkey 并通过相同集成测试（2026-09-06），这不替代部署环境的 TLS/ACL/集群故障测试。
六产品 `RealtimeWorkloadTest` 验证开启采集的成交往返资金/冻结/持仓/快照恢复；JMH/JFR范围及未测项单独记录。

最终构建记录（2026-09-06）：`mvn package -Drealtime.test.server=/tmp/valkey-realtime-build/valkey-8.0.1/src/valkey-server` 全 reactor 成功，1410项测试中1388通过、22项提现数据库集成测试因未配置 `SURPRISING_WITHDRAWAL_IT_DATABASE_URL` 跳过，无失败。原始日志 `/tmp/realtime-final-package2.log`。Router可执行JAR也已使用独立MediaDriver和Valkey实际启动通过，记录 `/tmp/realtime-router-startup-result.log`。短JMH覆盖六产品线，SPOT/OPTION各180秒持续状态检查通过资金/终态和短期堆稳定检查；生产API尾延迟、真实集群切主和完整长期容量验收仍未完成，不能据此承诺零性能成本。


## 2026-09-22 盘口与行情进程合并验证

基线 `6fd38b36`，HotSpot Corretto JDK 27、Maven 3.9.16。

| 模块 | 通过 | 跳过 |
| --- | ---: | ---: |
| gateway（含新盘口路径） | 518 | 34 |
| realtime API | 7 | 0 |
| realtime provider（含迁入 K 线） | 35 | 0 |
| maker | 45 | 0 |
| trading API | 20 | 0 |
| 合计 | 625 | 34 |

market-data API 编译通过，当前无独立测试类。12 组“六产品 × Router 开关”验证生产配置与组件扫描；
关闭 Router 时 K 线 HTTP 查询仍返回 200。装配测试替换数据库、合约快照加载与 Router I/O，停止 Kafka 消费，不等于真实外部环境验收。

真实 Redis + Aeron UDP/IPC 测试覆盖：订阅定向、断档快照恢复、本地 CANDLE 入队、超出 16 MiB 队列预算的帧被拒绝及后续正常路由。
Kafka Streams TopologyTestDriver 覆盖原聚合、去重、水位线、迟到分钟及数据库写入重试边界，并验证本地发布 1m 和 5m 更新。
初次新增发布断言漏计一个 5m 汇总帧，修正为验证两条 1m 与一条 5m 后全部通过，未修改聚合算法。
盘口测试验证共用 Core 客户端、instrumentId/depth、买卖档位映射、错误不得伪装为空盘口，以及本地路由协议与权限。

12 组启动 dry-run、脚本语法和 production-chain-preflight 合约测试通过：行情应用只启动一次，位于 gateway 之后；不再启动 market-data provider。
原始 Core 生产代码和协议没有改动，因此本轮未重跑资金主链路 JMH；没有宣称提高撮合吞吐或给出内存节省实测数。
网关 34 项外部环境集成测试跳过；未重跑完整真实 Core 盘口 HTTP 链路、外部 Kafka → PostgreSQL 重启恢复、长稳或容量验收。
完整测试摘要和跳过原因见 [market-realtime-merge-20260922.json](../docs/validation/market-realtime-merge-20260922.json)。

复现命令：

```bash
mvn -pl surprising-gateway,surprising-realtime/surprising-realtime-provider -am -DskipTests install
mvn -pl surprising-realtime/surprising-realtime-provider -am install -DskipTests
mvn -pl surprising-gateway,surprising-realtime/surprising-realtime-api,surprising-realtime/surprising-realtime-provider,surprising-maker test
mvn -pl surprising-gateway,surprising-realtime/surprising-realtime-provider,surprising-maker -DskipTests package
PRODUCT_LINE=LINEAR_PERPETUAL REALTIME_ENABLED=true RUN_ID=market-merge-review ACTION=dry-run AERON_CLUSTER_HOSTNAMES=127.0.0.1 bash scripts/start-product-line-providers.sh
TASK1_TEST_ROOT="$PWD/.local-logs/market-merge-preflight-20260922" bash scripts/test-production-chain-preflight.sh
```

运行规模：U 永续单成员 Core + gateway + price + realtime 行情 + derivatives-lifecycle + maker 共 6 个基础 JVM；
同时启用应用 MediaDriver 与成交导出后共 8 个。与此前完整链路的 9 个 JVM 相比减少一个；不计 PostgreSQL/Kafka/Valkey。

最终 JAR 检查通过：gateway 包含盘口入口且无 K 线聚合，realtime 包含 K 线与 Router 且无旧 market-data 启动入口；两者均不包含 Core service 运行依赖。测试进程已结束，真实 Redis/Aeron 测试资源已关闭，临时日志、preflight 数据及已汇总测试报告已清理。构建包 SHA-256 保存在上述验证摘要。

## 2026-09-22 成交导出合入行情验证

基线 `48a8ef0d`；HotSpot Corretto JDK 27、Maven 3.9.16。
realtime provider 最终 47 项通过，tools 19 项通过、1 项因缺少 `INSTRUMENT_SEED_TEST_JDBC_URL` 跳过；无失败。
真实 Aeron Archive + 内嵌 Kafka 检查已提交范围、分片停机恢复、重复成交身份、checkpoint 排他锁和线程启停。
Kafka Streams 拓扑验证重复成交不重复更新 K 线；真实 Redis/Aeron Router 回归通过。
六产品应用上下文验证导出开关、配置必填及不启动 Core；24 组脚本 dry-run 与模拟停机顺序通过。

完整单成员 U 永续从 8 个减至 **7 个 JVM**：Core、gateway、price、realtime、derivatives-lifecycle、maker、app-media-driver。
基础配置仍为 6 个；成交导出开关不增加进程。原来的独立 exporter Main 与 tools 专用依赖已删除。
realtime JAR 的启动类仍为 RealtimeApplication；增加 Core 回放库，不扫描/启动 Core 配置。

未执行完整外部 Core/Kafka/PostgreSQL/WebSocket 端到端部署、历史追赶压力、长稳、共享 JVM 内存及 p99 验收；
不能据此声称吞吐提升或无延迟影响。Core 主链路、协议及确定性回放算法未改，本轮未做 Core JMH。
验证命令、逐类测试、24 组启动顺序和构建 SHA-256 见
[验证摘要](../docs/validation/trade-export-realtime-merge-20260922.json)。
本轮测试资源已关闭，临时日志和已汇总测试报告已清理，保留构建产物。


## 应用侧 MediaDriver 内嵌 gateway（2026-09-22）

当前完整单成员 U 永续为 **6 个 JVM**：Core、gateway、price、realtime、derivatives-lifecycle、maker。
上面 8→7 的记录是成交导出合并时的历史；本次继续删除独立 app-media-driver 进程。

`GatewayMediaDriverConfiguration` 在 `surprising.realtime.enabled=true` 时创建共享 Driver，
`surprising.realtime.directory` 必须显式设置；统一脚本继续通过 `APP_AERON_DIR` 传给各应用。
realtime/price/gateway 仍使用同一目录，不改 Aeron channel、stream 或业务协议。
Core 与可靠成交导出使用 Core 侧 Driver，不连接这个应用侧 Driver。

gateway 重启会中断共用 Driver 的实时传输；现有 Sender/Receiver/Router 循环负责重连，
期间逐笔实时消息不保证补齐，私有状态仍按快照恢复；可靠成交/K 线来源不变。
Driver 仍使用 SHARED 模式，线程、共享内存与 term buffer 开销保留；仅减少独立 JVM 的开销。
迁移前停止旧独立 Driver；同一个目录只允许一个活跃 Driver，不能让多个 gateway 同时占用。
这是同机共享目录的部署方式；跨主机不能用目录字符串代替各主机本地的 Driver。

K 线查询、周期和聚合输入类型位于 provider 的 `src/main/java/com/surprising/candlestick/api`；与 Gateway WebSocket 共用的 `CandleUpdatedEvent`、`CandleStatus` 统一位于 `surprising-realtime-api` 的同名 Java 包。原 market-data API 和聚合 POM 已移除。

## 提交内订阅查询复用（2026-09-26）

`RealtimeRouter.route` 收齐一次 Core 提交、应用用户查询视图后，按 `RealtimeRoute` 在本次提交内
复用订阅节点查询结果。同一用户的订单、余额、持仓和成交回报共享 USER 路由，避免每个 frame 重复访问
Valkey。临时 Map 由路由线程创建、只活到本次提交结束；下一次提交重新读取租约，不跨提交缓存订阅关系。
无订阅目标时也不编码发送负载。Core 状态、资金记账、快照缺口修复和有界队列行为不变。

Router 的控制及转发 Aeron 客户端也沿用默认 10 秒驱动超时（可由 `aeron.driver.timeout` 配置），
与 Sender/Receiver 一致。驱动错误记录原因；真实 Driver 集成测试使用 1.5 秒心跳覆盖正常路由不误断连。

### 空 Kafka 首次启动

`CandlestickStreamConfiguration` 使用当前 `ProductLine` 的 `ProductTopicNames`，在 Streams
启动前声明成交输入和 K 线事件两个 32 分区 topic；不依赖第一笔成交触发自动建 topic。
`candleStreamsHealthIndicator` 仅在 Streams 为 `RUNNING` 时报告 UP，未启动、重平衡、
错误和关闭状态均报告 DOWN，避免 HTTP 已监听却不生成 K 线。启动器等待该健康状态后
才启动后续服务。六产品线的 topic 隔离及全部 Streams 状态由
`CandlestickStreamConfigurationTest` 验证；本机冷启动联调覆盖 U 本位永续。

### 2026-09-27 盘口查询与私有快照排队修复

`RealtimeRouter.refresh` 仍按活跃订阅查询核心最新盘口，但同产品线、同币对只有一个在途查询。路由线程独占 `pendingBooks`，收到 BOOK 即移除；丢包超过 2 秒或传输关闭后允许重试。不保存盘口内容，也不改变撮合、结算或快照权威来源。原来每 100ms 重发尚未完成的查询，会占满核心实时请求队列，使用户快照在路由的 5 秒窗口后才返回而被拒绝，页面一直 INITIALIZING。

真实 Redis/Aeron 集成测试覆盖 20 币对同时订阅时不重复投递在途查询、私有快照恢复、产品隔离与传输重连。所有订单/余额仍使用绝对值增量和权威快照，未增加 REST 轮询或旧数据兜底。

### 历史订单保留规则

`export.service.CommittedOrderProjectionService` 聚合提交投影事务；`export.repository.CommittedOrderRepository` 仅写查询表 `core_order_projection`，不改变 Core 撮合、资金结算、恢复日志或实时 WebSocket。
普通用户订单全部保留。产品线内配置的 `surprising.trade-export.market-maker-account-ids` 为专用做市账户（包括模拟成交账户）：
纯做市互成交、同账户自成交、未成交撤单都不入历史订单表；只要订单涉及一次普通用户成交，就保存完整累计状态，后续内部成交和撤单也持续更新。
账户分类以服务端配置 ID 为准，不能用客户端订单号前缀判断。每条产品线单独配置，专用账户不得改作普通用户使用。
本地启动脚本从同一份做市策略和模拟成交账户配置自动生成列表，避免两处手工维护。

导出器从一次已提交命令获取完整 ORDER/EXECUTION 对，先检查成交双方身份，再合并本批订单的最高 revision。
已有历史订单行就是“曾涉及用户成交”的持久化依据；按产品线批量查询，重启后继续使用，不引入长期内存订单缓存或第二份检查点状态。
订单写入和查询水位同事务，SQL 失败不会推进 Archive checkpoint；只有数据库提交和 Kafka 确认后才保存恢复点。
公共成交和 K 线仍包含做市成交，过滤仅针对历史订单表。
这是一套上线前的新保留规则；曾全量写入做市订单的测试历史不能直接沿用，否则旧行会被误当成用户成交凭据。需要保留历史时，清空查询表并从完整 Archive 重建；明确丢弃旧测试历史时，可以停机清空查询表，以选定恢复点作为新历史的起点。后者仅是测试环境的显式重置操作，生产恢复代码不会跳过日志。

### 2026-10-07 测试环境重复高低价 K 线恢复

本次故障位于 `CommittedTradeExporter` 的历史回放检查点：回放订单簿仍有核心已不存在的旧做市订单，
同一订单的实际成交回报与公开成交价格不同，导致 `CandleAggregationProcessor` 重复聚合旧价格。
绘图与实际撮合并非本次重复柱子的原因。现有证据能确认检查点状态偏离，尚未确定最初偏离的历史操作。
不能仅以检查点文件校验和有效，或实时服务健康检查为 UP，判断成交投影与核心一致。

恢复按以下业务顺序执行，未重启或改写交易核心：

1. 暂停测试模拟成交和做市报价，备份成交导出检查点、分钟 K 线、消费位点及部署环境。
2. 从核心权威快照恢复导出状态，保留公开成交序号的连续性；使用独立 Kafka Streams application ID，
   将两个输入 topic 的消费位置明确设在隔离边界，保留旧 topic、状态和检查点供调查。
   此操作只适用于已备份、可回放并明确隔离的修复，不是常规启动时跳过历史的策略。
3. 恢复做市后，用订单号逐笔核对三个合约的真实成交与公开成交，六笔全部相同。
   暂停窗口前后的核心快照中，22 个账户的余额、持仓及平台资金账完全相同。
4. 从 Archive 提取两个权威快照之间的已提交命令，离线 `CommittedTradeReplay` 回放；
   每段结束必须与下一份核心快照的 `businessStateHash` 相等，否则不得写入历史。
   本次 15 段全部一致，按真实成交顺序、价格单位和合约乘数重建分钟 OHLC、量额及笔数。
   一次事务替换 2026-10-06 13:06 UTC 至 2026-10-07 01:22 UTC（右开）的已关闭分钟数据，
   共 11,720 笔成交、2,022 根分钟线。无法映射原公开序号的历史行保留真实成交 ID，序号设空，
   不用 Archive 位置冒充公开成交序号。无成交分钟由查询沿用上次真实收盘价，量额和笔数为零。
5. 将已校验的 CLOSED 分钟快照送入该产品线的 candle events topic，补齐尚未关闭的高周期聚合。
   `CandleRollupProcessor` 按分钟去重；`CandleRollupAccumulator` 按分钟时间确定开收盘，支持乱序补齐。
   已越过水位或已关闭的周期不会重新聚合，修复时必须另外核对数据库查询与热缓存，不能假设晚到事件会覆盖它们。

公开 API 对三个合约的历史 1m、15m、1h，以及当前 1h、1d 汇总逐项对账通过；桌面和移动页面
实际绘制通过。回放回归测试覆盖六产品线的批量撤单（含不存在订单）、批量改单、快照恢复后的成交价格。
这不表示其余五产品线做过本次线上恢复，也未对修复窗口之前的历史进行完整重建。
测试环境原始备份保留在服务器 `/var/backups/surprising/20261007-candles`，包含敏感运行状态，禁止提交仓库。


### 测试环境 Kafka 分区

`CandlestickStreamConfiguration` 仍为每条产品独立创建成交输入和 K 线事件 Topic，但不再写死 32 个分区，
新 Topic 遵循 Kafka Broker 的 `num.partitions`。服务重启不会自动扩容或重建已有 Topic。
`scripts/local-perpetual.sh` 管理的新单节点测试 Kafka 使用 1 个业务分区；消费位点与事务状态内部 Topic
也在首次创建时使用 1 个分区、1 个副本，减少测试环境资源开销。生产容量仍由 Kafka 部署管理。
已有 Topic 的分区不能直接缩小：修改默认值只影响新建 Topic，不能删除或重建已有成交、K 线、
Streams changelog 和内部 Topic 来节省分区，否则会破坏消费进度和恢复状态。
`CandlestickStreamConfigurationTest` 使用真实嵌入式 Kafka，覆盖六产品新建 Topic 为 1 分区，
已有 4 分区成交 Topic 在服务再次初始化后仍为 4，不被强制改成 32。


## 成交导出 Archive 元数据优化（2026-10-08）

`CommittedTradeExporter` 仍在每个回放批次重新加载 RecordingLog，以识别领导任期变化和重叠尾部；选择当前 term 和下一 term 边界改为单次遍历，删除排序及中间列表。不会跨批缓存任期映射。

已有 `ArchiveReplay` 由单一导出线程持有录制起止位置，最长缓存 100ms；切换 recording/term 或到达已停止的录制末端立即重新查询。元数据缺失、截断或缺口继续失败，不能跳过历史；缓存期间的变化最迟在下次刷新时检查。每轮检查 Archive 异步错误。回放仍受已提交位置及 term 边界限制，完整命令、数据库事务、Kafka 确认和 checkpoint 的顺序不变。

确定性测试让提交位置每毫秒增长一次，1000 次边界检查的 Archive 起止位置 RPC 从 2000 次降为 20 次。额外覆盖 term 改变立即刷新、已停止录制扩展、周期检查发现截断和缺失历史；真实 Driver/Archive 的分片续读、回放 Image 复用及真实 Kafka 的成交导出、崩溃窗口和跨产品失败测试通过。以上不等于已证明整条导出链路 CPU 降幅。

### 后续性能分析

2026-10-08 对测试服务器做了 2 秒 `/proc` 只读采样：realtime 的 `committed-trade-export` 约使用 49.3% 单核 CPU。这个短窗口只用于确定排查优先级，不能归因到某一条 SQL 或证明宿主机超分。本轮没有部署或重启服务。

1. `CommittedOrderProjectionService.persist` 每批先查询做市账户目录，即使该批没有 ORDER/EXECUTION，也进入查询水位事务；导出器每次提交位置推进后可能形成很小的批次，`ReliableTradeKafkaSink.publish` 则为非空成交批次提交 Kafka 事务。下一步先统计批次大小、SQL 次数、事务耗时和提交频率，再减少无订单批的目录查询或采用有明确时延上限的合批。空批也可能需要推进可见性水位，不能直接跳过 persist、提前保存 checkpoint 或跨产品合并。
2. `ValkeyReadViewStore.applyCommits` 已做流水线，但每个用户提交仍通过 EVAL 发送完整 DELTA Lua。可评估 SCRIPT LOAD / EVALSHA 减少网络负载；必须处理 NOSCRIPT、保证逐用户原子性与版本屏障，不能简单重放整条 pipeline。
3. `AeronRealtimeReceiver` 复制完整消息后，`RealtimeFrameCodec.decode` 再复制 payload，`RealtimeFrame` 构造和访问仍有保护性复制。可评估直接缓冲区解码或协议边界转移所有权，先测分配量；不能直接移除公共 payload 的防御性复制，或保留 FragmentAssembler 下轮会覆盖的缓冲区。
4. 网关 `SubscriptionRegistry.publishDepth` 在同步方法内为每条连接计算盘口差量并序列化；订阅者基线相同时存在重复工作。可按本次推送的相同基线复用编码，保留首次快照、每连接版本、发送成功才更新基线和慢连接移除规则。其他普通公共 fanout 已复用编码，不应重复优化。

这些是源码确认的成本点，尚未证明各自在实际负载中的收益；未在本轮额外改动。Core 撮合、结算和 Driver 的等待策略需要带真实交易延迟与资金守恒验证再调整。

### 本轮验证与清理

HotSpot JDK 27 定向执行客户端、价格、生命周期、realtime、gateway WebSocket 和 maker 及其直接依赖测试；按测试类最新结果去重，共 509 项，503 通过、6 跳过、0 失败。跳过项为 price/lifecycle 的 PostgreSQL 配置集成各 1 项、gateway 本地真实交易环境 2 项、maker PostgreSQL 配置集成 2 项。真实 Archive/Kafka 导出集成已执行，未部署本轮代码、未运行服务器端全链路交易或长稳测试。

JFR 限制单文件最大 32MiB，已将采样条件和结果记录在各模块 README；分析完成后清理本轮临时程序、录制、日志和对应测试报告，保留此前已有数据与构建产物。


## 后续成本点实施（2026-10-08）

上一节的四项后续清单已实现：空订单批不查做市目录、导出小批有限合并、Lua 每批加载后按摘要执行、实时接收免整帧复制，以及盘口按共享基线复用编码。后两项说明分别位于 [协议模块](../surprising-aeron-core/surprising-aeron-protocol/README.md)、[gateway](../surprising-gateway/README.md)；Lua 方案和缓存失效边界见 [realtime-api](surprising-realtime-api/README.md)。

### 订单投影与可靠成交合批

`CommittedOrderProjectionService.persist` 收到空订单批时只在事务中推进查询水位，不查询做市账户目录、不创建订单/成交临时索引或调用空 batchUpdate。非空批仍读取当前服务端目录，保留普通用户历史、做市涉及用户成交的持久化凭据、最高 revision 和六产品隔离，不做长期账户分类缓存。

`CommittedTradeExporter.ExportBatch` 接管原来分散在回调和循环中的两个列表及命令计数，由单一导出线程持有，不新增线程或通用执行框架。累计完整命令，达到原来的 256 命令或 4096 帧阈值就刷新；小批可跨 commit position 的推进合并，目标等待最多 5ms，空闲 park 和 commit counter 暂时不可用时的等待都受该期限约束。单个完整命令仍可超过帧阈值后立即刷新，不能为了容量把一个命令拆开。外部 I/O、回放或线程调度可能超过目标期限，5ms 不是端到端延迟保证。

刷新顺序为订单与可见性水位同事务 → Kafka 确认 → 清空批次。两者成功后才记录已刷新序号；没有新订单、成交且水位相同的重复刷新不再访问 SQL/Kafka。所有 checkpoint 路径和正常退出强制刷新剩余批次；失败向上传递，不推进 checkpoint、不跳过未知结果。查询水位可多等待一个短合批窗口，但不能比数据库中已提交的订单状态超前。

组件验证使用隔离 PostgreSQL、Hikari 单连接池、真实水位事务，比较逐命令刷新与当前合批，两边均使用优化后的空订单 SQL 路径；输入为 10,000 个空订单命令，期限时钟按每条 100µs 的虚拟间隔推进，最后都核对水位 10999。记录 JFR 与调用线程 ThreadMXBean：事务调用 10,000 → 197，分配量 42.422MB → 1.032MB，CPU 919.280ms → 100.312ms，组件总耗时 2744.451ms → 144.686ms。Kafka 为替身，虚拟间隔不是实时时序，因此不把此结果换算为实际撮合吞吐、用户延迟或整体服务 CPU 收益。

### 验证范围

HotSpot JDK 27 定向测试 61 个测试类，按最后一次类结果去重共 325 项，全部通过、无跳过。包括共享 codec/wire、真实 Driver 普通及分片传输、真实 Redis 缓存清空与版本屏障、真实 PostgreSQL 的六产品水位/幂等/事务回滚/做市保留规则、真实 Archive/Kafka 的提交边界与崩溃重复身份、路由恢复、盘口基线/背压、查询水位等待，以及六产品撮合、实时输出和快照恢复。

主要执行范围：`surprising-realtime-provider,surprising-gateway,surprising-price-provider,surprising-derivatives-lifecycle-provider,surprising-maker -am`，选择本轮实时、投影、导出、快照与撮合相关测试；另执行 `*CodecTest,*Wire*Test,ProjectionWatermarkWaiterTest,ProjectedOrderQueryRepositoryTest`。真实 Archive/Kafka 导出另录制最多 32MiB 的 JFR并完成同样断言；该集成样本的导出执行采样不足，不据此断言热点占比。未启动 wallet，未部署或重启测试服务器，未执行真实 HTTP 用户交易全链路、Redis Cluster 故障、三节点切主或长稳容量验收。

本轮使用独立临时 PostgreSQL、自动销毁的 Redis/Archive/Kafka 测试实例；分析入档后停止进程并清理本轮原始 JFR、临时程序、数据库目录、日志和对应测试报告，保留既有工作区数据和构建产物。

清理完成：本轮独立 PostgreSQL 已停止，原始录制、临时程序/数据库/日志及 122 份对应测试报告已删除；没有遗留本轮 profile 进程，既有 Aeron 数据目录保持原样。

## 迟到成交与资金费真实投影（2026-10-09）

分钟关闭仅是持久化边界，不再当作成交事件时间水位。`CandleAggregationProcessor` 在现有去重保留期内继续更新该分钟的 RocksDB 累加器，迟到修订先保存在 dirty store，PostgreSQL 写入成功后才发布 CLOSED 替换；写入失败保留 dirty 状态等待重试。`PostgresCandleSink` 只接受已接纳成交笔数增加的完整分钟（迟到成交可能不改变按成交时间选出的末笔序号），重复写入不能回退历史。超过保留期的输入仍丢弃，不能把任意旧成交当作新历史。

`CandleRollupProcessor` 对普通新分钟继续增量聚合。已处理分钟的修订或已关闭周期的迟到分钟，读取该周期的持久 CLOSED 分钟并替换聚合值，最多读取周线 10080 条；同时登记现有 seen store，防止 SQL 已有但通知仍排队的分钟被重复相加。seen 的 Long 值保存成交笔数的按位取反值，重复或旧版本直接跳过，不再读 SQL；分钟时间已存在于 key 中，清理仍按该时间执行。升级前的正数时间标记在首次持久分钟重建时转换，不重置消费组和 changelog。周线活动时间超过去重保留期时，已有末分钟仍可识别旧输入并重建，不能重复累加。查询失败不提交本条输入。没有新增重复分钟索引、changelog、历史缓存或线程；热缓存拒绝成交笔数、序号倒退的旧通知。旧版本已丢弃且记为去重的成交不会自动补回；不通过重置业务消费组或向行情伪造成交恢复历史。

`CommittedTradeExporter` 在回放真实 APPLY_FUNDING 后，先刷新更早的订单/成交，再将 `CommittedFundingPage` 的支付页及流水放在同一 SQL 事务中。页以产品线与日志位置幂等，完成游标链连续且费率一致后才写完成汇总；页冲突、缺页或数据库失败阻止 checkpoint 前进。费率、金额、多空方向来自核心执行结果，不从当前仓位重建。未知合约配置版本保留 null。

维护入口 `com.surprising.realtime.provider.export.CommittedFundingRepair` 复用同一 Archive 提交边界和回放状态，参数依次为 product、cluster-directory、aeron-directory、archive-control-channel、独立 `*.funding-repair` checkpoint、有限 end-position；连接数据库沿用部署环境 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`。可从结算窗口之前的有效 trade-export checkpoint 副本开始，只写资金费查询投影，不连接 Kafka、不改订单/水位、不向在线 Core 发送命令，也不覆盖在线 exporter 的 checkpoint。必须在运维脚本外层限制运行时间、堆和磁盘，并核对窗口覆盖；snapshot 之前的结算不可从该 snapshot 推算。

本地验证包括迟到成交 SQL 失败重试、全部 12 个周期、通知排队与重复计数、10080 分钟周线重建、热缓存不回退，真实 PostgreSQL 的版本替换和资金费原子事务/幂等/缺页/冲突/零支付/产品隔离，以及真实 Archive 的分片、提交边界、SQL 失败后 checkpoint 恢复；原 Archive/Kafka 成交导出集成仍执行。在线 Core、撮合资金模型与 Kafka topic 不变，不需要重启 Core。

导出恢复追赶时，已提交位置超过回放位置 64KiB 后，`ExportBatch` 暂将合批目标期限从 5ms 调整到 100ms，减少历史命令逐小批 SQL 往返；接近实时位置后恢复 5ms。原 256 命令/4096 帧上限、完整命令边界、订单事务先于 Kafka、失败不推进 checkpoint 等规则不变；外部 I/O 与调度耗时不计为端到端延迟承诺。此状态只属于原导出线程，不新增配置项或工作线程。六产品线测试覆盖追赶期限、容量上限及恢复实时期限。

## 当前Core协议消费端对齐（2026-10-18，验收中）

`CommittedOrderProjectionService` 及资金页测试直接消费 `CommittedTradeReplay.CommittedFundingPage`。资金费支付记录来自确定性回放的实际已提交页；不增设旧类型转接类。

`CandlestickStreamConfiguration` 按现有十字段 `RealtimeFrame` 构造 K 线帧。`RealtimeRouter` 使用产品线、实体 ID 和日志序号记录发送结果；`ReliableTradeKafkaSink` 使用产品线、成交 ID 和日志位置记录事务提交或失败。Kafka 消息和 WebSocket 推送只携带业务数据。

## 按订单 ID 的点查询

`ValkeyUserQueries.order` 复用 `ValkeyReadViewStore.readFields`，用一次 HMGET 读取目标订单和
`@fence/@epoch/@snapshotAt/@invalid/@exportSequence`。订单是完整实体值，无需载入 `USER:user`、
解码其他订单/余额/仓位或构造 `UserReadView` 的七张排序表。初始化、时效、源 epoch 和最低导出水位
检查与完整查询共用；只返回身份匹配的 OPEN 订单，终态或缺失返回空。

余额和仓位的点查询仍显式读取 `USER:user`，按原有规则将基线和更新叠加；不能为减少分配丢弃基线。
快照安装及晚到更新的版本、顺序和防复活校验保持不变。

## 行情查询与交易 Core 分离（2026-10-19）

公共盘口 `/api/v1/gateway/trading-market/orderbook`、内部 maker 盘口入口和 BBO 参考价统一由
`MatchingMarketDataService` 调用 `ValkeyMarketData.requireBook`；已移除 `OrderAeronGateway.orderBook`。
`RealtimeRouter.publishReady` 只在完整 BOOK commit 收齐后写入 `rt:book:{产品:合约}`，无 WS
订阅也会保存。每个视图只保存当前完整盘口，5 秒过期；读者续期原有盘口需求租约，由 Router
合并 REST/WS 需求，不因 HTTP 请求直接访问交易 Core。Router source epoch 改变后旧视图立即不可读。
不存在、过期、错产品/合约、损坏或 Valkey 不可用返回 `503 MARKET_BOOK_UNAVAILABLE`；空盘口是有效结果。
REST 返回买价降序、卖价升序，最多每侧 50 档，保留盘口序号，eventTime 为 Router 收到完整快照的刷新时间；HTTP 读取不刷新该时间。
安静盘口的 Cluster 时间可能停留在最后一笔交易，不能拿它代替本次快照刷新时间，否则 maker 会误判失效。

Core 仍拥有权威撮合订单簿，按现有异步控制通道输出已提交盘口与账户恢复快照；本次没有删除
恢复/运维控制协议，也没有在 realtime 裁决订单或资产。用户行情查询、K 线、最近成交均从交易处理路径外读取。
最近成交 REST 保持 realtime 内的 `RecentTradeQueryService`，K 线仍沿原聚合/历史路径。
WS `trades.data.value` 新增与可靠导出完全一致的 `tradeId`、`instrumentId`、`eventTime`，保留
`priceTicks/quantitySteps/coreSequence/side`。`coreSequence` 是 matcher 序号，不能与 REST 成交导出 sequence
混用；两端按 tradeId 去重并按其中的精确整数位置排序，避免重复和丢成交。

本次新增状态只有 Router 拥有的可过期盘口查询视图，读取方为 HTTP 和 BBO；无新增权威账本、线程或撮合缓存。
部署时需先更新 realtime，再更新 gateway；同一 Valkey 和 source epoch 为必要依赖。冷启动首个 REST 可能返回
503 并登记需求，客户端继续接收 WS 基线，K 线和逐笔历史加载不被盘口初始化阻断。

### 本轮验证与边界

HotSpot JDK 27（Corretto 27）下执行 realtime-api、realtime-provider 和 gateway 的 14 个相关测试类，
共 243 项通过、0 失败、0 跳过。涵盖真实 Redis/Aeron 完整提交后才发布可查询盘口、无 WS 订阅的
REST 需求、六产品隔离、epoch/过期失效、HTTP 503 传递、BBO 模式和订单入口，以及 WS 成交标识。
命令：`mvn -pl surprising-realtime/surprising-realtime-api,surprising-realtime/surprising-realtime-provider,surprising-gateway -am -Dtest=MarketDataLocalTest,BboOrderServiceTest,OrderServiceTest,RealtimePublicTradeTest,DepthUpdateTest,SubscriptionRegistryTest,SubscriptionRegistryMetricsTest,ValkeyReadViewIntegrationTest,RealtimeRouterIntegrationTest,RecentTradeQueryServiceTest,MarketApplicationContextTest,LocalBusinessApiTest,GatewayProxyServiceTest,ProductBusinessConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`。

Web lint/build 和 147 项测试通过；Flutter analyze 无问题、126 项测试通过。客户端 50 档保留测试
已验证旧 20 档截断失败、恢复修复后通过。桌面 1440px / 手机 390px 的公开页面与登录页冒烟检查
未发现 JS 运行异常或横向溢出；未登录交易页进入登录流程，因此不把它算作真实盘口端到端验收。
未做线上部署、真实下单资金对账或 iOS/Android 真机验收；本次未修改交易裁决、资金或持仓模型，
验证范围按行情查询/推送及直接相关订单入口界定。测试使用本轮临时 Redis、Aeron 目录并在测试结束关闭；
磁盘可用约 297GiB，临时前端服务与日志在汇总后清理，不删除既有集群或运行数据。

## 已提交回执查询（2026-10-19）

`CommittedTradeReplay` 在独立回放完成全部提交后交出命令结果；`CommittedOrderProjectionService` 将普通用户处理回执、订单和水位同事务提交，重放冲突会回滚。新表 `core_command_result_projection` 不保存账户或订单当前状态，做市策略内部报价被过滤。拒单没有订单事件时仍导出回执，网关用户查询改读该投影而不访问 Core 结果缓存。

已有 live checkpoint 不回溯旧回执。先应用 `deployment/migrations/20261019-committed-command-results.sql`，再通过 `CommittedCommandResultRepair` 从日志前缀有限回补；必须新建独立 `*.command-repair` checkpoint，不能复制 live checkpoint。该回补只写回执，不写 Kafka、订单、资金费或水位。完整发布顺序、六个参数、范围与限制见 [说明](../docs/realtime-command-results.md)。

### 逐项批量撤单回执（2026-10-19）

提交回放增加有界 `cancel_batch_results`，保存原批次的 index/orderId/success/code，供两端确认部分失败和继续未发送批次。正常导出与订单/回执/水位同事务；历史回补仅能补充 NULL，非空事实冲突则回滚，不复制订单状态。先执行 `20261019-cancel-batch-results.sql`，再更新 realtime 并按需使用全新的独立 `.command-repair` checkpoint 回补历史，最后更新 gateway/Web/client。原 checkpoint 不回溯旧回执，不重置 live 断点、不重发 Kafka；详见 [查询和发布契约](../docs/realtime-command-results.md#现货批量撤单与固定目标恢复2026-10-19)。

### 交割现金流水与有限回补（2026-10-20）

`CommittedTradeReplay` 在独立的已提交日志回放进程处理 SETTLE_INSTRUMENT 时，通过本地所有者查询读取同一全局用户游标页的结算资产余额，比较结算前后的可用加冻结总额。真实现金差额包括实际保险补偿；释放保证金不是收入，平价结算没有现金变动则不生成流水。不复制在线 Core 状态，不新增撮合或账户热路径阶段，不向在线 Core 发查询。

`CommittedTradeExporter` 在写检查点前调用 `CommittedOrderProjectionService.persistDelivery`，原子写入现有 `account_product_ledger_entries` 与 `account_product_ledger_entry_seq`。类型为 DELIVERY_SETTLEMENT，参考号为产品线:合约ID:结算ID；自然键重复时逐字段核对，内容冲突回滚整页并阻止检查点推进。Gateway 的产品账本 repository 只读，JSON 资金整数和流水 ID 用字符串返回，并提供 instrumentId；Web/client 依币种精度格式化，不经浮点转换。这里提供现金流水，不代表包含零盈亏记录的完整交割报告。

旧 live checkpoint 不自动回溯。历史补齐入口为 `com.surprising.realtime.provider.export.CommittedDeliveryLedgerRepair`，使用 HotSpot JDK 27、部署运行依赖 classpath 和 SPRING_DATASOURCE_URL/USERNAME/PASSWORD。六个位置参数为：

```text
LINEAR_DELIVERY <cluster-directory> <aeron-directory> <archive-control-channel> <独立路径>.delivery-repair <有限的已提交end-position>
```

首次使用不存在的独立回补检查点，从保留的日志前缀回放；后续只续用本任务自己的检查点。`.delivery-repair` 有独立文件标识，复制或重命名 live 检查点会拒绝。不得清零、替换 live 检查点，不重复提交结算。回补只写交割现金流水，不连接 Kafka，不写订单、回执、资金费或水位，不向在线 Core 发送命令。外层限制堆、时间与磁盘，并核对保留日志覆盖范围；缺失前缀不能从后续 snapshot 猜测。


### 期权行权现金分类与历史纠正（2026-10-20）

既有已提交到期回放同时支持 OPTION。`persistDelivery` 现在按产品选择 `OPTION_EXERCISE/期权行权`；仍使用同一现金差额、自然身份与事务，不新增状态副本或在线 Core 查询。平价和价外没有现金变化时不生成零金额流水。

旧数据库在停止本产品 exporter 后执行 `deployment/migrations/20261020-option-exercise-ledger.sql`，只把 OPTION 账户、OPTION 参考号的旧 DELIVERY_SETTLEMENT 分类改正，保留原 ID、金额、余额及检查点。与正确分类已有相同自然身份时整笔失败，先对账，不自动删除历史记录。迁移可重复执行；PostgreSQL 测试同时验证迁移后重放仍只有一条记录及冲突回滚。

历史缺失流水仍使用 `CommittedDeliveryLedgerRepair` 的独立 `.delivery-repair` 检查点和有限 end-position，首参数可为 OPTION；不得复用 live 检查点或再次提交行权。修正生产者的价格单位不能追溯修改已经提交的交易；已提交错误结算需要独立按账本对账处理。本轮实际六组结算与两端读取结果见 [验证记录](../docs/validation/option-trading-20261020.json)。


### 期权参考报价读视图（2026-10-20）

仅 OPTION 进程注册 `OptionQuoteService` 与现有 price.events topic 消费者，保存每合约一份可重建价格发布，按当前精度版本及源数据新鲜度查询；不向在线 Core 发查询。经既有 candlestick 市场数据路由返回真实价格与明确零贴现的参考 Greeks，源数据不合格则503，模型无法求解时greeks为null。停牌的未到期合约仍可查询，交易权限不随查询开放。[模型、公开费用契约和发布步骤](../docs/option-market-reference.md)。

### 算法委托提交投影（2026-10-20）

`CommittedTradeReplay` 在独立回放副本读取父单及子单成交进度，`CommittedAlgoProjection` 写入最新算法单和初始化标记。算法单变化纳入原有 ExportBatch，与订单、回执、水位共用事务和恢复点；相同父单 revision 的成交变化仍按 exportSequence 更新。先执行 `20261020-algo-read-projection.sql`，再配套发布 Core、realtime、gateway。状态归属、启动分批恢复和已知历史快照缺口见 [算法委托查询](../docs/algo-read-projection.md)。

### 提交投影的表职责与事务（2026-10-21）

`export.service` 聚合订单/成交/回执/条件单与可见水位、资金费页、交割账本，以及统计、
组合权益历史、算法委托初始化；SQL收拢至 `export.repository` 的单表Repository。
业务Service不再依赖JdbcTemplate。四个Service共享配置中的REQUIRED事务模板，
一批事实和可见性水位原子提交；SQL失败阻止Kafka发送及检查点推进。

21个Repository各访问一张表。`MarketMakerProjectionAccountsRepository` 是明确注释的只读例外：
合并策略和业务设置中的做市账号，包含停用策略，以保持历史分类；不写这两张表。
资金费汇总从支付Repository读取精确NUMERIC总数，再由结算Repository写汇总，仍在原结算身份锁和事务内。

`export.config.CommittedProjectionConfiguration` 负责相同的事务及Repository装配。
离线修复入口通过 `openPersistence` 使用这一配置并关闭所属容器，不另建写入路径；
关闭导出时不创建这些持久化组件。原生投影行归model，回执事实比较归utils，
`TradeExportProperties` 归config。验证见
[提交投影分层验证](../docs/validation/java-mvc-projection-20261021.json)。

RealtimeRouterProperties统一归provider.config；路由器配置键、默认参数和产品控制通道绑定保持。验证见 [配置拦截归包记录](../docs/validation/java-mvc-infra-packages-20261021.json)。

期权价格视图的状态与清理规则仍归OptionQuoteService；5秒调度入口归option.task.OptionQuotePruneTask，仅OPTION市场装配。验证见 [定时职责记录](../docs/validation/java-mvc-task-boundaries-20261021.json)。

期权入口、聚合和Black-76参考计算分别归option.controller、option.service与option.utils；清理时钟仍归option.task，保持OPTION专属装配。验证见 [其余归包记录](../docs/validation/java-mvc-remaining-packages-20261021.json)。

期权报价的HTTP参数和状态映射由option.controller.OptionQuoteController处理，业务只接收原生int合约ID。
option.consumer.OptionQuotePriceConsumer负责Kafka协议，沿用原Topic、监听工厂及latest消费；
OptionQuoteService只持有源事件与业务聚合，OptionQuoteException为原生业务原因，报价归model.OptionQuote。
保留OPTION范围、短有效期、精度/版本/来源一致性和只读参考定位。
[期权HTTP职责验证](../docs/validation/java-mvc-option-http-20261021.json)包含完整实时模块及负例结果。

共享读视图API的失败使用 `realtime.api.exception.ReadViewUnavailableException`，保留 `READ_VIEW_*` / `MARKET_BOOK_UNAVAILABLE` 原因，不选择HTTP状态。Gateway在Controller/本地协议边界映射503；Redis租约、版本门槛、过期/epoch检查和禁止Core回退的读取规则保持。

Kafka职责归包：独立快照、审计、结算/排空及推送消费入口统一放在各自provider.consumer（价格为index.consumer/mark.consumer），业务和缓存继续由原Service/状态所有者负责。监听topic、group、启动条件与处理顺序保持；做市事件唤醒组件为consumer.MakerQuoteWakeups，原唯一信号状态不变。详见 docs/validation/java-mvc-consumer-packages-20261022.json。


## 输入流连续性恢复（2026-10-22）

接收器的坏帧、处理异常和传输失效暂停接收，由 router 自有工作线程清掉未完成快照/commit、待路由输入与旧发送积压，更新产品 sourceEpoch，再恢复接收；不会等待下一笔成交才把旧账户投影置为不可用。退休下游 Aeron image 使 gateway/price 同步失效各自旧基线，既有 snapshot/book 重试继续申请权威状态。恢复以故障版本确认，新的故障不能被旧确认覆盖。

逐帧发送、排队与 Kafka 成交提交成功日志为有开关保护的 DEBUG，失败诊断限频，累计计数保留；没有据此声称吞吐或零分配改善。回归包含真实 Redis/Aeron 坏帧后即使没有后续 commit，原 READY read view 也必须失效。
