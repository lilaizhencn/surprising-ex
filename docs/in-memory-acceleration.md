# 内存与无锁热点路径

本文记录当前已经落地的第一批、第二批内存化改造。内存结构只服务于读多写少或由单线程/分区顺序驱动的热点路径，资金事实、恢复数据和最终审计仍保留在 PostgreSQL、Redis 或 Kafka 日志中。

## 撮合保护

`surprising-trading/surprising-matching-provider` 的 `MatchingProtectionIndex` 保存订单簿中的最小订单字段，并维护以下索引：

- `productLine + userId + symbol`：自成交检查；
- `productLine + symbol`：合约规格标识一致性检查；
- `orderId`：成交、撤单后的无锁更新。

撮合订单簿恢复成功后由 `ExchangeCoreEngine` 重建索引，恢复完成前 `MatchingProtectionRepository` 失败关闭，绝不在撮合热路径回查数据库。撮合结果和成交持久化成功后才应用索引变更，避免未提交状态被后续指令看到。

## 行情与 K 线

- 标记价沿用 `LatestMarkPriceCache`，按合约规格标识和新鲜度读取；
- 盘口深度由撮合引擎内存订单簿生成，公共深度发布失败不会影响资金事务；
- 最新公共成交由 `LatestPublicTradeCache` 保存在撮合进程内，并提供 `/api/v1/trading/market/latest-trade` 查询；
- K 线由 Kafka Streams RocksDB 保存热状态，`CandleHotCache` 提供本地查询；数据库只查询关闭时间范围内的归档 K 线，并与热数据按开盘时间合并。
- `CandleHotCache` 按 `symbol + period` 分桶并使用时间有序跳表，区间和最新查询不再扫描全量 K 线；超过容量时按各桶最早开盘时间淘汰。

RocksDB changelog、Kafka 事件和数据库关闭快照共同组成恢复链路，不能把账户余额或持仓账本迁移到这些缓存中。

## 合约与风控配置

Instrument Service 通过统一聚合 Service 组装合约正文、风险档位、指数源和资产精度。各业务模块启动时调用
`GET /internal/v1/instruments/snapshot`，将结果加载到本模块自己的 `InstrumentSnapshotCache`；运行中消费
`surprising.instrument.events.v1`，按 `PRODUCT_LINE:SYMBOL` key 和版本号校验后以 `AtomicReference` 整体替换。
下单、撮合、账户、风控、指数价、标记价、K 线和做市热路径只读本 JVM 快照，数据库仅保留 Instrument 写入、
启动恢复和审计回源边界。

资金费率发布和结算都从 JVM 快照读取 funding 参数、资产精度和持仓；结算游标、支付命令和发布状态
先同步写入资金费 RocksDB，再追加账户用户分区 WAL。数据库只承担异步历史投影、启动恢复和审计，不能
参与资金费候选计算或决定重复扣款。

`RedisRiskCalculator` 使用不可变快照和 `AtomicReference` 整体替换，风控计算不为规格参数查询数据库；
`instrumentVersion` 是历史订单、持仓和 Kafka 事件定位开仓规格的内部字段，不能删除。

## Trigger 与风险组

`RedisTriggerOrderIndex` 在 Redis ZSET 之外维护节点本地一级索引。Redis readiness、Lua 区间查询和跨节点恢复仍是完整性边界；本地索引只作为候选合并来源，索引或 Redis 未就绪时必须暂停触发执行，不能回退 PostgreSQL 高频扫描。
本地索引内部按触发价维护有序桶，只遍历命中的价格区间。由于节点之间仍可能存在不同订单，Redis 查询不能删除；本地索引只减少同节点扫描和序列化开销。

`RiskService.scanPositionUpdates` 先按 `productLine:userId:accountType:settleAsset` 聚合事件，在本地风险组快照上合并最新持仓，再追加 RocksDB 投影队列。定期任务只负责异步数据库投影和启动恢复，Redis 仍负责跨节点投影、租约和最终候选审计；缓存失效或租约丢失会清空本地组并暂停风险计算。

条件单、保险基金和 ADL 服务使用同一套 Instrument 内部快照初始化与 Kafka 增量消费约定。三者的 Repository
均只读取单表，账户命令、持仓、保证金、缺口和保险余额由 Service 在事务内聚合，不使用 SQL JOIN。

### 未平仓量快照

`account-provider` 是 `trading_symbol_open_interest_shards` 的唯一投影写入方，并通过
`/internal/v1/accounts/open-interest/snapshot` 提供启动快照。仓位和账户状态事件携带分片绝对值和修订号；
订单模块按产品线、合约和分片在 JVM 中幂等更新并聚合。
下单保证金计算在快照就绪后不再查询未平仓量视图，账户数据库仍负责持久化、启动恢复和最终审计。

快照未就绪时，相关实时流量拒绝启动或返回空候选，避免使用不完整规格计算资金结果。

### 指数汇率快照

`ExchangeRateSnapshotCache` 在指数模块启动时从 `price_exchange_rates` 一次性恢复全部当前汇率，运行中由汇率
刷新 Service 在数据库写入成功后原子更新本 JVM。指数换算、最新汇率和汇率列表只读该快照，并按稳定币/法币
新鲜度拒绝过期数据；数据库不再出现在换算热路径，仅承担启动恢复、更新落账和历史查询。

## WebSocket 背压

每个 `ClientConnection` 使用有界内存 FIFO（环形队列语义）和独立虚拟线程发送。`SubscriptionRegistry.publishBatch` 将同一批事件一次性编码和入队；队列满或发送超时只关闭当前慢连接，不阻塞 Kafka 消费线程和其他连接。公共行情多节点仍必须使用不同 Kafka consumer group，产品线 topic 和订阅键不能混用。
高频公共行情使用 `webSocketKafkaBatchListenerContainerFactory` 批量拉取，按订阅主题分组后调用 `publishTimedBatch`；私有事件保留逐条确认。K 线的部分状态继续由合并器限频，避免丢失关闭事件。

## 指标与规格版本

K 线热缓存、合约规格快照和 WebSocket 注册表暴露命中、未命中、替换、批量 fanout 和背压拒绝指标。替换次数可作为规格快照漂移告警信号，背压拒绝必须结合慢连接 ID 排查，不能通过无限增大队列掩盖问题。

合约规格统一使用 `productLine + symbol + version` 作为 JVM 快照的内部定位键。`version` 是规格生命周期序号，
历史订单、持仓和 Kafka 事件必须保留它，才能在重启、结算和风险重算时复原开仓时的精确规格。
Instrument 事件只接受完整字段，不再兼容缺少产品线、序列或使用仅 SYMBOL key 的旧消息。
各模块通过 `AbstractInstrumentSnapshotInitializer` 复用启动恢复模板，通过
`InstrumentSnapshotSupport.consume` 复用增量事件处理模板；消费组、产品线和业务派生刷新仍由模块自行配置。

## 部署与故障处理

四条产品线仍分别部署和配置：现货、永续、交割、期权使用各自的 topic、账户类型、撮合实例和 WebSocket consumer group。服务重启时先完成内存索引/快照恢复，再宣告 readiness；恢复失败必须保持对应交易入口不可用，不能以空索引接受下单，也不能把实时请求转成数据库兜底查询。

发现账账不平、跨线 topic、风险组缺失或触发单候选异常时，应暂停对应产品线入口，保留 Kafka 位点、Redis readiness 和数据库快照，完成重建及逐项资金核对后再恢复流量。
