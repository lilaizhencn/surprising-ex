# 统一行情应用：成交导出、K 线与实时路由

`surprising-realtime-provider` 现在同时运行可靠成交导出、K 线与实时路由，默认 HTTP 端口 **9095**。
盘口查询已迁入 gateway；不再部署 market-data provider。原有实时协议、Valkey 查询视图和 Core 权威状态保持不变。

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
mvn -pl surprising-trading/surprising-trading-api,surprising-market-data/surprising-market-data-api install
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

K 线查询、周期和聚合输入类型已迁入本模块 `src/main/java/com/surprising/candlestick/api`；`surprising-market-data-api` 仅保留与 gateway WebSocket 共用的 K 线事件和状态。

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

`CommittedOrderProjectionRepository` 只为查询写入 `core_order_projection`，不改变 Core 撮合、资金结算、恢复日志或实时 WebSocket。
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

1. `CommittedOrderProjectionRepository.persist` 每批先查询做市账户目录，即使该批没有 ORDER/EXECUTION，也进入查询水位事务；导出器每次提交位置推进后可能形成很小的批次，`ReliableTradeKafkaSink.publish` 则为非空成交批次提交 Kafka 事务。下一步先统计批次大小、SQL 次数、事务耗时和提交频率，再减少无订单批的目录查询或采用有明确时延上限的合批。空批也可能需要推进可见性水位，不能直接跳过 persist、提前保存 checkpoint 或跨产品合并。
2. `ValkeyReadViewStore.applyCommits` 已做流水线，但每个用户提交仍通过 EVAL 发送完整 DELTA Lua。可评估 SCRIPT LOAD / EVALSHA 减少网络负载；必须处理 NOSCRIPT、保证逐用户原子性与版本屏障，不能简单重放整条 pipeline。
3. `AeronRealtimeReceiver` 复制完整消息后，`RealtimeFrameCodec.decode` 再复制 payload，`RealtimeFrame` 构造和访问仍有保护性复制。可评估直接缓冲区解码或协议边界转移所有权，先测分配量；不能直接移除公共 payload 的防御性复制，或保留 FragmentAssembler 下轮会覆盖的缓冲区。
4. 网关 `SubscriptionRegistry.publishDepth` 在同步方法内为每条连接计算盘口差量并序列化；订阅者基线相同时存在重复工作。可按本次推送的相同基线复用编码，保留首次快照、每连接版本、发送成功才更新基线和慢连接移除规则。其他普通公共 fanout 已复用编码，不应重复优化。

这些是源码确认的成本点，尚未证明各自在实际负载中的收益；未在本轮额外改动。Core 撮合、结算和 Driver 的等待策略需要带真实交易延迟与资金守恒验证再调整。

### 本轮验证与清理

HotSpot JDK 27 定向执行客户端、价格、生命周期、realtime、gateway WebSocket 和 maker 及其直接依赖测试；按测试类最新结果去重，共 509 项，503 通过、6 跳过、0 失败。跳过项为 price/lifecycle 的 PostgreSQL 配置集成各 1 项、gateway 本地真实交易环境 2 项、maker PostgreSQL 配置集成 2 项。真实 Archive/Kafka 导出集成已执行，未部署本轮代码、未运行服务器端全链路交易或长稳测试。

JFR 限制单文件最大 32MiB，已将采样条件和结果记录在各模块 README；分析完成后清理本轮临时程序、录制、日志和对应测试报告，保留此前已有数据与构建产物。
