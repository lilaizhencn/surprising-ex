# 内存与无锁热点路径

本文记录当前已经落地的第一批、第二批内存化改造。内存结构只服务于读多写少或由单线程/分区顺序驱动的热点路径，资金事实、恢复数据和最终审计仍保留在 PostgreSQL、Redis 或 Kafka 日志中。

## 撮合保护

`surprising-trading/surprising-matching-provider` 的 `MatchingProtectionIndex` 保存订单簿中的最小订单字段，并维护以下索引：

- `productLine + userId + symbol`：自成交检查；
- `productLine + symbol`：合约规格标识一致性检查；
- `orderId`：成交、撤单后的无锁更新。

撮合订单簿恢复成功后由 `ExchangeCoreEngine` 重建索引，恢复完成前 `MatchingProtectionRepository` 继续走数据库兜底。撮合结果和成交持久化成功后才应用索引变更，避免未提交状态被后续指令看到。

## 行情与 K 线

- 标记价沿用 `LatestMarkPriceCache`，按合约规格标识和新鲜度读取；
- 盘口深度由撮合引擎内存订单簿生成，公共深度发布失败不会影响资金事务；
- 最新公共成交由 `LatestPublicTradeCache` 保存在撮合进程内，并提供 `/api/v1/trading/market/latest-trade` 查询；
- K 线由 Kafka Streams RocksDB 保存热状态，`CandleHotCache` 提供本地查询；数据库只查询关闭时间范围内的归档 K 线，并与热数据按开盘时间合并。
- `CandleHotCache` 按 `symbol + period` 分桶并使用时间有序跳表，区间和最新查询不再扫描全量 K 线；超过容量时按各桶最早开盘时间淘汰。

RocksDB changelog、Kafka 事件和数据库关闭快照共同组成恢复链路，不能把账户余额或持仓账本迁移到这些缓存中。

## 合约与风控配置

`InstrumentStorageService` 启动时按四条产品线预热当前规格，完整组装风险档位、指数源和精度后一次性发布到 `InstrumentSpecSnapshotCache`。`InstrumentSpecId` 是内部不可变身份，键中必须包含产品线、symbol 和规格序号，防止同名合约串线。

`RedisRiskCalculator` 使用不可变快照和 `AtomicReference` 整体替换，风控计算命中缓存时不查库；未命中时才回源并发布新快照。当前数据库字段和 Kafka 字段仍保留 `instrumentVersion` 兼容读取，网关会移除该内部字段，不能直接删除历史数据列或旧事件。

## Trigger 与风险组

`RedisTriggerOrderIndex` 在 Redis ZSET 之外维护节点本地一级索引。Redis readiness、Lua 区间查询和跨节点恢复仍是完整性边界；本地索引只作为候选合并来源，Redis 不可用时上游回退 PostgreSQL 精确抢占。
本地索引内部按触发价维护有序桶，只遍历命中的价格区间。由于节点之间仍可能存在不同订单，Redis 查询不能删除；本地索引只减少同节点扫描和序列化开销。

`RiskService.scanPositionUpdates` 先按 `productLine:userId:accountType:settleAsset` 聚合事件，在本地风险组快照上合并最新持仓，再调用现有风险计算和事务写入。定期 `scan` 仍从数据库重建，Redis 仍负责跨节点投影、租约和最终候选审计；缓存失效或租约丢失会清空本地组并恢复数据库路径。

## WebSocket 背压

每个 `ClientConnection` 使用有界内存 FIFO（环形队列语义）和独立虚拟线程发送。`SubscriptionRegistry.publishBatch` 将同一批事件一次性编码和入队；队列满或发送超时只关闭当前慢连接，不阻塞 Kafka 消费线程和其他连接。公共行情多节点仍必须使用不同 Kafka consumer group，产品线 topic 和订阅键不能混用。
高频公共行情使用 `webSocketKafkaBatchListenerContainerFactory` 批量拉取，按订阅主题分组后调用 `publishTimedBatch`；私有事件保留逐条确认。K 线的部分状态继续由合并器限频，避免丢失关闭事件。

## 指标与规格代际迁移

K 线热缓存、合约规格快照和 WebSocket 注册表暴露命中、未命中、替换、批量 fanout 和背压拒绝指标。替换次数可作为规格快照漂移告警信号，背压拒绝必须结合慢连接 ID 排查，不能通过无限增大队列掩盖问题。

`InstrumentSpecEpoch` 是 `InstrumentSpecId` 的内部代际别名，迁移阶段与旧 `instrumentVersion` 数值保持一一对应。数据库列、历史 Kafka 事件和内部计算模型暂不删除；新缓存键优先使用 `productLine + symbol + epoch`，公共网关继续过滤旧内部字段。完成全链路双读、回放和账务核对后，才能安排旧字段下线。

## 部署与故障处理

四条产品线仍分别部署和配置：现货、永续、交割、期权使用各自的 topic、账户类型、撮合实例和 WebSocket consumer group。服务重启时先完成内存索引/快照恢复，再宣告 readiness；恢复失败必须保留数据库或 Redis 兜底，不得以空索引接受下单。

发现账账不平、跨线 topic、风险组缺失或触发单候选异常时，应暂停对应产品线入口，保留 Kafka 位点、Redis readiness 和数据库快照，完成重建及逐项资金核对后再恢复流量。
