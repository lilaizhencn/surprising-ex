# 实时行情、私有状态和 Valkey 查询

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
