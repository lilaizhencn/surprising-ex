# 统一行情应用：K 线与实时路由

`surprising-realtime-provider` 现在同时运行 K 线与实时路由，默认 HTTP 端口 **9095**。
盘口查询已迁入 gateway；不再部署 market-data provider。原有实时协议、Valkey 查询视图和 Core 权威状态保持不变。

## K 线链路

`CommittedTradeExportMain` → 产品成交 Kafka topic → `CandlestickStreamConfiguration` →
RocksDB 聚合/去重/水位线 → PostgreSQL 历史与 K 线 Kafka 事件。
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
- `REALTIME_ENABLED=false` 只关闭 Router，K 线聚合和 HTTP 查询仍启用；开启 Router 时先启动 MediaDriver。
- 先启动 gateway 并等待 liveness，再启动 price 和本行情应用，以便 `SymbolRegistryService` 拉取合约快照。
- 保留旧 market-data 数据库、Kafka Streams application-id、topic/changelog；迁移前停止旧实例。
  `CANDLESTICK_STATE_DIR` 指向原状态目录，或在空目录从原 changelog 恢复，不能误改 application-id 后当成无损迁移。
  本地脚本默认使用本轮运行目录下 `candlestick-state`。
- 继续运行成交导出器才能获得新的真实成交 K 线；Core 仍独立，资金账本不在行情应用中。
- 部署数据源、Kafka、Valkey、`BUSINESS_INTERNAL_TOKEN` 和当前产品配置。RocksDB 原有 native 内存预算仍需保留，合并不表示这些缓存消失。

## 实时行情、私有状态和 Valkey 查询

实时数据在交易提交完成后进入有界 outbox，由独立 Aeron 线程发送到 Router。
Router 在 Valkey 查询订阅节点，为每个目标 WS 节点维护一个 Publication；不向所有 WS 节点广播。
Valkey 只保存可重建的查询视图，不裁决余额、风控或成交。交易 owner 不调用 Valkey/Kafka/网络。
新增编码、队列和异步快照有实际 CPU/分配成本；性能证据与限制见根目录 `PERFORMANCE_VALIDATION.md`。

## 进程配置

所有组件使用同一协议版本和相互隔离的六产品线标识。Aeron MediaDriver 需要先启动，并向对应 Java 进程提供
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
   每个用户可连接多个节点，Router 只发给这些节点；节点再匹配本地频道、symbol、用户与 period。
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

- 所有订阅明确 `productLine`。私有订阅用户来自已认证连接，不能指定其他用户；公共频道按 symbol 路由。
  新增 depth/bookTicker 使用具体 symbol，20档完整深度覆盖更新，不发送需要连续重放的差量 order book。
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
Router 每200ms每产品最多请求16用户/4 symbol、全局32个待完成用户快照；快照新鲜度15秒，HTTP/WS兴趣租约30秒。
这些是明确的首版容量边界，在线规模过大时会返回 STALE，不能承诺任意在线用户数下都在15秒内修复。

每个 Router 最多64个目标节点 Publication，30秒未使用释放；8MiB term，每个 Publication 大约映射三个 term，需单独预算 native/mapped memory。
慢节点或断连节点只丢它自己的 offer，不重试、不拖住其他节点，更不会把反压传入 Core。
Valkey 故障影响查询/路由，独立线程重连；交易仍可提交。恢复后通过快照校准。
暴露 Micrometer `realtime.router.dropped/failures/queued.bytes/queued.frames` 和 `realtime.receiver.failures`；
Aeron Sender/Outbox 同时提供 sent/dropped/failures/droppedBatches 计数。部署需对这些计数、READY比例、Valkey延迟和租约续期告警。

## 可靠成交与 K 线

`CommittedTradeExportMain` 是独立进程，从 Aeron Archive **已提交**范围回放 Core 命令，恢复确定性成交，写入原有各产品线 `match.trades.v1` topic。
它不消费可丢失的 realtime outbox，不在 live Core 内等待 Kafka。

```text
java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar \
  com.surprising.aeron.tools.CommittedTradeExportMain \
  PRODUCT_LINE CLUSTER_DIR AERON_DIR ARCHIVE_CONTROL_CHANNEL KAFKA_BOOTSTRAP CHECKPOINT [CLUSTER_ID]
```

checkpoint 是 exporter 自己的 Core 快照/已处理logPosition/tradeSequence，SHA-256校验，Kafka事务确认后才原子保存。
commit counter 暂停在 Aeron 分片中间时，恢复游标保留在前一个完整 Cluster 命令后；后续重读未完成命令，不从中间分片续读。
Crash 在 Kafka确认与保存之间会重发相同 tradeId/sequence；K线按 sequence 去重，Kafka消费者使用 read_committed。
Kafka长时间故障会使 exporter 落后，不能推进 checkpoint；Archive 保留期必须覆盖 exporter 的恢复位置。
新 exporter 从0开始，需要完整保留的记录；缺失历史直接失败，不默默跳到当前。此进程读取本机/共享目录 RecordingLog 和 commit counter，部署在可访问这些数据的集群成员旁。
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
盘口测试验证共用 Core 客户端、symbol/depth、买卖档位映射、错误不得伪装为空盘口，以及本地路由协议与权限。

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
