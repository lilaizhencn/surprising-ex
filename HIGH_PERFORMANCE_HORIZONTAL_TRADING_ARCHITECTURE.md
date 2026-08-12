# 高性能、低竞争、可横向扩展的交易链路架构

> 状态：目标架构提案，尚未全部实现<br>
> 适用项目：Surprising-EX 现货、永续、交割、期权后端<br>
> 最后更新：2026-08-12<br>
> 核心原则：资金正确性优先于吞吐和延迟

## 1. 文档目的

本文给出 Surprising-EX 核心交易链路从当前实现演进到高吞吐、低延迟、低竞争、可横向扩展架构的完整方案。

本文覆盖：

- 普通下单、撤单、改单和批量命令。
- 订单校验、资金预占、释放、成交和结算。
- 用户订单、余额、冻结、持仓和保证金状态。
- 交易对撮合、撮合结果、成交事实和行情事件。
- Kafka 分区、单写者、状态恢复、幂等、故障转移和横向扩容。
- 本地 WAL、RocksDB、Outbox、数据库投影和同步结果返回。
- Kafka 分片状态机与 Aeron Cluster 两种长期方案的取舍。

本文不把“无锁”理解为所有代码都能并行执行。交易系统存在两个无法取消的串行边界：

1. 同一用户的余额、冻结、持仓和订单状态必须按确定顺序修改。
2. 同一交易对的订单簿必须按确定顺序撮合，才能维持价格时间优先。

目标是消除这些边界之外的共享竞争：

> 分区内部单写者串行，分区之间完全并行；不使用跨分区共享可变状态、全局锁、全局序号、逐条 fsync 或逐条同步网络等待。

## 2. 架构结论

### 2.1 推荐主路线

当前生产目标优先采用 **Kafka 分片复制状态机**：

- Gateway 保持无状态。
- 用户命令按 `productLine:userId` 进入固定 User Shard。
- 订单、资金、持仓的热写状态由同一用户状态机串行处理。
- 撮合命令按 `productLine:symbol` 进入固定 Book Shard。
- 每个状态机由一个事件循环写入，内部状态不加锁。
- 输入 offset、输出事件和状态 changelog 按批次原子提交。
- RocksDB 只做可重建 checkpoint，不再对每条命令同步落盘。
- PostgreSQL 只做异步查询投影、审计、报表和对账，不参与普通下单到撮合的同步热路径。

### 2.2 极低延迟备选路线

当内部处理目标进入亚毫秒级，并且团队能够承担更高运维复杂度时，再评估 **Aeron Cluster 分片复制状态机**：

- Aeron Cluster 负责共识日志、主从切换和快照。
- Kafka 保留为外部事件总线、审计流和异步投影入口。
- 状态机必须完全确定性，不能直接读取数据库、系统时间或外部服务。

在当前文档定义的 `3000 ops/s` 下单类吞吐和 `1000 trades/s` 成交吞吐目标下，Kafka 主路线已经足够，直接迁移 Aeron 的收益不足以抵消复杂度。

## 3. 当前实现审查

### 3.1 当前链路的正确方向

当前实现已经具备后续演进需要的部分基础：

- 用户命令按 `productLine:userId` 作为 Kafka key。
- 撮合命令按 `symbol` 作为 Kafka key。
- 订单和账户已经使用用户分区状态、WAL 和 reducer。
- 撮合使用 `exchange-core2`，同一 symbol 固定进入一个 matching shard。
- 数据库逐步退出普通下单、撮合和账户状态热路径。
- 状态和结果具备命令级幂等校验。
- 四条产品线使用独立 Topic 和 ProductLine 边界。

相关设计见：

- [账户单写者命令通道](docs/account-single-writer-command-lane.md)
- [撮合交易对分片和容量](docs/matching-symbol-sharding-and-capacity.md)
- [生产性能测试计划](docs/production-performance-test-plan.md)
- [产品线架构](docs/product-line-architecture.md)

### 3.2 `PartitionOwnerLane` 不是无竞争执行器

当前 [`PartitionOwnerLane`](surprising-event-store/src/main/java/com/surprising/eventstore/PartitionOwnerLane.java) 使用：

- `ConcurrentLinkedQueue` 作为每个 shard 的 MPSC 队列。
- 共享 `AtomicInteger pending` 做容量 CAS。
- 每个任务创建 `Task` 和 `CompletableFuture`。
- 非 Owner 调用线程通过 `CompletableFuture.join()` 同步等待。
- 空闲 Owner 使用 `LockSupport.parkNanos()` 轮询。

它避免了显式互斥锁，但仍存在：

- 多生产者对队列和 `pending` 缓存行的竞争。
- 每命令对象分配和 Future 完成开销。
- 调用线程到 Owner 线程的上下文切换。
- 一个本地 shard 下不同用户之间的队头阻塞。
- Owner 中执行同步磁盘或网络调用时，整个 shard 停止处理。

因此，`PartitionOwnerLane` 适合作为当前单写语义的过渡实现，不应作为最终极致性能执行器。

### 3.3 订单号生成是全局串行和同步磁盘点

当前 [`OrderIdSequenceStore`](surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderIdSequenceStore.java) 每生成一个订单号都执行：

```text
single order-id owner
  -> update timestamp/sequence
  -> RocksDB put
  -> WriteOptions.sync=true
  -> fsync before return
```

这会形成所有用户共享的串行点。当前配置还要求每个 Order 实例拥有唯一的 `ORDER_WAL_NODE_ID`；若多个实例使用默认值 `1`，可能生成冲突订单号。

因此，在允许 Order Provider 横向扩容前，必须先移除逐订单同步序列和默认 node-id 风险。

### 3.4 账户命令存在重复持久化和同步发布

当前账户命令主要经过：

```text
Kafka batch
  -> AccountUserCommandWalIngress
  -> 每条命令 UserPartitionWal.append(sync=true)
  -> 每用户 applyPendingPartition
  -> UserPartitionResultStore.put(sync=true)
  -> reducer checkpoint(sync=false)
  -> Kafka state snapshot send.get()
  -> Kafka position event send.get()
  -> Kafka command result send.get()
  -> Kafka batch offset commit
```

关键实现：

- [`AccountUserCommandConsumer`](surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/AccountUserCommandConsumer.java)
- [`AccountUserCommandWalIngress`](surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/AccountUserCommandWalIngress.java)
- [`AccountUserStateCommandWorker`](surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/AccountUserStateCommandWorker.java)
- [`UserPartitionWal`](surprising-event-store/src/main/java/com/surprising/eventstore/UserPartitionWal.java)
- [`UserPartitionResultStore`](surprising-event-store/src/main/java/com/surprising/eventstore/UserPartitionResultStore.java)

虽然 Kafka listener 已经是 batch listener，但 WAL 入口仍逐条调用 `append`。如果一个批次包含大量不同用户，仅按用户分组仍可能接近每条命令一次 fsync。

### 3.5 撮合事实提交仍有跨 symbol 全局串行点

当前 [`MatchingLocalStateStore`](surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/store/MatchingLocalStateStore.java) 使用：

```java
new PartitionOwnerLane<>(1, "matching-outbox-owner")
```

撮合结果、订单状态、成交和 Outbox 写入虽然位于同一个同步 `WriteBatch`，但不同 symbol 最终都经过这个单 Owner。它保护了全局 Outbox sequence，同时也把多 symbol 的提交重新串行化。

当前 [`MatchingLocalOutboxPublisher`](surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/service/MatchingLocalOutboxPublisher.java) 还会逐条执行：

```text
Kafka send.get()
  -> markOutboxPublished(sync=true)
```

这会让网络往返和 RocksDB fsync 共同限制撮合结果发布吞吐。

### 3.6 当前 exchange-core 是共享、多生产者、阻塞等待模式

当前 [`ExchangeCoreEngine`](surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/service/ExchangeCoreEngine.java) 在一个 JVM 中创建一个 `ExchangeCore`，不同 Kafka listener 线程共同调用一个 `ExchangeApi`。

底层特征：

- 所有 symbol 共享一个 Disruptor RingBuffer。
- RingBuffer 为多生产者模式。
- symbol 再按 `symbolId` 路由到 matching engine shard。
- 当前 wait strategy 为 `CoreWaitStrategy.BLOCKING`。

`BLOCKING` 适合共享 CPU 和低空载消耗，但不是无锁低延迟配置。增加 listener 和 matching engine 数量，只能提高多 symbol 并行度，无法提高单热点 symbol 的撮合并行度。

### 3.7 同步结果广播反向影响横向扩容

当前 Order 和 Account 的同步等待器为每个实例创建独立 Kafka consumer group，以便所有实例都收到所有结果：

- [`OrderUserCommandResultWaiter`](surprising-trading/surprising-order-provider/src/main/java/com/surprising/trading/order/service/OrderUserCommandResultWaiter.java)
- [`AccountCommandResultWaiter`](surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/AccountCommandResultWaiter.java)

两个实现都维护 `completed` Map，但当前没有 TTL、容量上限或成功后删除逻辑。

结果是：

```text
网络流量 约等于 命令结果量 × API实例数
completed内存 约等于 历史命令量 × API实例数
```

实例越多，广播和内存成本越高。这是横向扩容前必须处理的正确性和容量问题。

## 4. 目标设计原则

### 4.1 单写者原则

每份可变业务状态只能由一个逻辑事件循环写入：

| 状态 | 唯一写者键 | 并行边界 |
|---|---|---|
| 用户订单、余额、冻结、持仓 | `productLine:userId` | 不同用户并行 |
| 订单簿 | `productLine:symbol` | 不同 symbol 并行 |
| 风险组状态 | `productLine:riskGroupId` | 不同风险组并行 |
| 资金费批次 | `productLine:settlementShard` | 不同结算 shard 并行 |
| 公共行情 latest state | `productLine:symbol` | 不同 symbol 并行，可合并更新 |

单写者内部使用普通集合和普通字段，不需要 `ConcurrentHashMap`、用户级锁或 CAS 状态机。

### 4.2 无共享原则

禁止在热路径引入以下共享点：

- 跨用户的全局订单序号。
- 跨 symbol 的全局 Outbox sequence。
- 所有 shard 共用的同步磁盘提交锁。
- 所有 API 实例广播消费全部结果。
- 所有 matching shard 共用一个必须同步提交的状态库。
- 数据库 sequence、`SELECT FOR UPDATE` 或全局 Redis 锁。

### 4.3 分区稳定原则

分片数量与节点数量解耦：

```text
shardId = stableHash(businessKey) % configuredShardCount
```

- 扩容时增加节点，只迁移 shard ownership。
- 扩容时不能改变 `configuredShardCount`。
- 需要增加 shard 数时，创建 versioned topic 和 versioned state schema，执行受控迁移。
- 不在线直接增加已有状态 Topic 的 partition 数量。

### 4.4 持久化一次原则

一个业务阶段只保留一个权威提交边界。

推荐：

```text
Kafka input + Kafka transaction output/offset = 权威事实
RocksDB checkpoint = 可重建缓存
PostgreSQL = 异步查询和审计投影
```

禁止同一命令同时依赖本地 WAL sync、结果库 sync、数据库事务和 Kafka offset 四个独立正确性边界。

### 4.5 产品线与资金边界

所有事件必须携带并校验 `ProductLine`。现货、永续、交割和期权必须使用独立 Topic、consumer group、状态目录和恢复水位。

任何优化都不得削弱：

- 幂等和重复消息处理。
- 同用户顺序。
- 同 symbol 撮合顺序。
- 余额、冻结、持仓和手续费守恒。
- 资金费、强平费、交割和行权流水核对。
- 崩溃恢复后的状态等价性。

## 5. 目标总体架构

```text
Client
  |
  v
Stateless Gateway
  |
  | durable user mutation
  | key = productLine:userId
  v
Kafka user.mutations.v2
  |
  v
User State Shard
  |  single writer event loop
  |  order + balance + frozen + position + margin
  |
  | atomic batch output
  +---------------------> user.state.changelog.v2
  +---------------------> command.results.v2
  +---------------------> order.events.v2
  |
  | key = productLine:symbol
  v
Kafka matching.commands.v2
  |
  v
Matching Book Shard
  |  one writer per order-book shard
  |  independent engine and state
  |
  +---------------------> match.results.v2
  +---------------------> trade.facts.v2
  +---------------------> public.trade/depth
  |
  | two deterministic settlement mutations
  | key = productLine:userId
  +---------------------> Kafka user.mutations.v2
                           |
                           v
User State Shard
  |  idempotent settlement
  +---------------------> account/position events
  +---------------------> private WebSocket events
  +---------------------> async PostgreSQL projections
```

## 6. User State Engine

### 6.1 逻辑职责

User State Engine 是用户热写状态的唯一所有者，统一负责：

- 普通订单、算法单和触发后生成的子订单状态。
- 账户余额、可用、冻结和释放。
- 持仓、逐仓/全仓保证金和杠杆配置。
- 下单校验、资金预占、撤单释放和成交结算。
- `clientOrderId`、`commandId` 和 settlement leg 幂等。
- 用户状态 changelog、结果和私有事件产生。

这里的“统一”是写模型统一，不要求把查询 API、数据库投影和管理 API 放进同一个 Maven 聚合模块。可以保留现有独立模块，但热写命令只能进入同一个用户状态机。

### 6.2 为什么订单和账户热写需要统一

当前普通开仓需要：

```text
Order记录PENDING_RESERVE
  -> Account预占
  -> Account结果
  -> Order更新ACCEPTED
  -> MatchingCommand
```

统一后变为：

```text
同一个User Shard批次内
  -> 校验订单
  -> 校验余额/保证金
  -> 写订单状态
  -> 写冻结状态
  -> 输出MatchingCommand
```

这样可以移除一次以上 Kafka 往返、重复 WAL 和同步结果等待，同时保留用户维度的原子语义。

### 6.3 事件循环模型

优先方案是 Kafka partition consumer thread 直接执行 reducer：

```text
poll partition batch
  -> decode and validate
  -> apply commands in offset order
  -> append outputs to transaction
  -> commit transaction and offsets
```

如果 Kafka consumer 管理线程不能直接承载业务计算，则使用：

```text
one partition poller
  -> one SPSC ring buffer
  -> one shard event loop
```

禁止重新引入多 HTTP 线程或多 Kafka 线程直接写同一个 User Shard 的 MPSC 队列。

### 6.4 内存状态

事件循环独占的状态可以使用：

- 原始类型字段和紧凑结构。
- 普通 `HashMap` 或 primitive collection。
- 按用户维护的订单、余额、持仓和已应用命令水位。
- 有界幂等窗口和高水位，不保存无限历史命令对象。

热路径避免：

- JSON 反复序列化同一对象。
- Stream API 临时集合。
- 每命令 `CompletableFuture`。
- 每用户一个线程。
- 不受限的 `ConcurrentHashMap`。

## 7. Matching Engine 分片

### 7.1 独立 Book Shard

长期目标不再让所有 symbol 共享一个跨实例的 `ExchangeCore`。每个 Book Shard 应拥有：

- 固定 Kafka partition 或 partition 集。
- 一个命令入口事件循环。
- 独立 `ExchangeCore` 或独立 order-book engine。
- 独立快照、序号和恢复水位。
- 独立 CPU 配额和容量指标。
- 不依赖全局 Matching Outbox Owner。

一个进程可以承载一个或少量 Book Shard，但每个 shard 必须有独立写者。热点 symbol 可以独占 shard 和进程。

### 7.2 单热点 symbol 的硬边界

单个订单簿不能通过多个线程同时修改来横向扩展，否则会破坏：

- 价格优先。
- 时间优先。
- 撤单与成交竞态的确定结果。
- 重放一致性。

单热点 symbol 的优化方式是：

- 独占物理核心。
- 独占 matching shard 或 pod。
- 单生产者 RingBuffer。
- 预分配事件对象。
- 减少行情快照频率和对象分配。
- 将财务事件和公共行情事件分离。
- 低延迟部署中使用 Yielding/BusySpin，且预留专用 CPU。

### 7.3 Wait Strategy 配置档位

| 档位 | Wait Strategy | 适用场景 | 代价 |
|---|---|---|---|
| balanced | BLOCKING/PHASED_BACKOFF | 开发、共享节点、低空载 CPU | 延迟抖动较高 |
| throughput | YIELDING | 专用节点、追求吞吐和较低延迟 | 持续消耗部分 CPU |
| latency | BUSY_SPIN | 独占物理核心、极低 P99 | 空载也占满核心 |

不能同时要求最低 CPU 和最低撮合延迟。生产配置必须通过同机器、同负载、同数据的矩阵测试选择。

## 8. Kafka 分区设计

### 8.1 推荐逻辑 Topic

具体 Topic 名仍必须通过 `ProductTopicNames` 按产品线隔离。下表展示职责，不代表立即创建这些固定名称。

所有会改变用户订单、余额、冻结、持仓或保证金的输入必须进入同一个
`user.mutations.v2` 逻辑流，包括普通下单/撤单、成交结算腿、资金费、强平、ADL、交割、
行权、杠杆调整和人工资金调整。Kafka 只保证单个 Topic partition 内的顺序；如果把这些
输入拆成多个 Topic，即使 key 相同，也无法得到同一用户跨 Topic 的确定总顺序。

| 逻辑 Topic | Key | 写者 | 消费者 | 顺序要求 |
|---|---|---|---|---|
| `user.mutations.v2` | `productLine:userId` | Gateway/Matching/资金与结算服务 | User State Engine | 所有用户状态变化的严格总顺序 |
| `user.state.changelog.v2` | `productLine:userId` | User State Engine | standby/恢复器 | 同用户严格顺序 |
| `matching.commands.v2` | `productLine:symbol` | User State Engine | Matching Engine | 同 symbol 严格顺序 |
| `trade.facts.v2` | `productLine:symbol` | Matching Engine | 审计/结算协调 | 同 symbol 严格顺序 |
| `command.results.v2` | `requestId` 或 reply shard | User State Engine | Result Router | 相关请求顺序即可 |
| `order.events.v2` | 查询/推送所需 key | User State Engine | 投影/WebSocket | 按业务 key 顺序 |
| `public.trade/depth` | `productLine:symbol` | Matching Engine | WebSocket/行情 | 同 symbol 顺序 |

### 8.2 分区数

分区数必须在上线前按容量规划预留。例如：

- User Shard：256、512 或 1024。
- Book Shard：64 或 128。
- Reply Shard：按 Gateway 结果路由容量规划。

这些数值必须通过当前硬件压测决定，不能作为生产默认值直接照搬。

### 8.3 扩容模型

```text
固定shard数 + 增加实例数 = shard重新分配
```

例如 256 个 User Shard：

| User Engine 实例 | 每实例平均 shard 数 |
|---:|---:|
| 2 | 128 |
| 4 | 64 |
| 8 | 32 |
| 16 | 16 |

只要单个热点 shard 未达到上限，增加实例可以近似线性提高总吞吐。

## 9. Kafka 事务和持久化边界

### 9.1 推荐事务模型

每个处理线程或逻辑 shard 使用稳定且可 fencing 的 `transactional.id`：

```text
transactional.id = productLine + component + shardId
```

一个微批次执行：

1. `beginTransaction()`。
2. 按输入 offset 顺序更新内存状态。
3. 写状态 changelog。
4. 写业务输出事件。
5. `sendOffsetsToTransaction()`。
6. `commitTransaction()`。

下游使用 `isolation.level=read_committed`。

新 Owner 使用相同 shard transactional ID 启动时，Kafka producer epoch 会 fencing 旧 Owner，防止网络分区下两个节点同时提交同一 shard。

### 9.2 批次而不是单命令事务

禁止每条命令开启一次 Kafka transaction。应按以下边界形成微批次：

- Kafka poll 返回的同 partition 连续记录。
- 最大记录数。
- 最大字节数。
- 最大批次等待时间。
- 最大事务持续时间。

批次参数必须在吞吐与 P99 延迟之间测试，不在设计文档中固定一个未经验证的数值。

### 9.3 RocksDB 的角色

目标架构中 RocksDB 是 checkpoint，不是唯一事实：

- `sync=false`。
- 状态按 changelog offset 记录水位。
- checkpoint 与水位同批写入。
- checkpoint 丢失时从 Kafka changelog 重建。
- 可使用 standby 副本提前恢复状态。

在 Kafka EOS 完成前的过渡阶段，可以保留本地 WAL，但必须按 Kafka poll 批次 group commit，不能只做“每用户一次 fsync”。

## 10. 订单号、命令号和事件序号

### 10.1 取消全局订单号生成器

推荐从已持久化的 Kafka 输入坐标派生订单号：

```text
orderId = encode(schemaVersion, productLine, partition, offset, reservedBits)
```

一个可选的正数 `long` 布局：

| 位 | 内容 |
|---|---|
| 63 | 固定为 0，保证 Java `long` 为正数 |
| 62..60 | ID schema version |
| 59..58 | ProductLine |
| 57..48 | Kafka partition，最多 1024 |
| 47..2 | Kafka offset，46 bit |
| 1..0 | 订单命令子类型保留位 |

该布局只是目标设计示例，落地前必须验证：

- Topic 生命周期内 offset 上限。
- partition 最大数量。
- 四产品线编码稳定性。
- 现有订单号 API 和数据库列兼容性。
- 旧 ID 与新 ID 的 schema version 区分。

如果无法安全压入 64 bit，应使用 128 bit ID，而不是恢复全局数据库 sequence。

### 10.2 命令和事件幂等键

- 外部请求：`clientOrderId` 或明确的 `requestId`。
- User Shard 命令：`productLine + userId + commandId`。
- 撮合命令：`bookShard + orderId + commandType`。
- 成交：`bookShard + tradeSequence`。
- 结算腿：`tradeId + maker/taker role + userId`。
- 状态事件：`shardId + epoch + sequence`。

任何幂等缓存都必须有：

- 有界容量。
- 安全清理水位。
- TTL 只作为辅助，不能破坏未过恢复窗口的数据。
- checkpoint 和 changelog 恢复规则。

### 10.3 取消全局 Outbox sequence

下游只需要 Kafka partition 或业务 stream 内顺序，不需要跨 symbol 全局总序。

Outbox/输出事件标识应使用：

```text
topic + eventKey + streamSequence
```

或者直接使用输入/输出 Kafka 坐标。不同 symbol 不再争抢一个 `OUTBOX_NEXT_KEY`。

## 11. 成交和双边结算

### 11.1 Canonical Trade Fact

Matching Shard 对每笔成交产生唯一、不可变的 `TradeFact`，至少包含：

- `tradeId`。
- 产品线、symbol 和 instrumentVersion。
- maker/taker 用户和订单。
- 成交方向、价格和数量。
- maker/taker 手续费率快照。
- 结算资产和合约面值快照。
- matching shard、epoch 和 sequence。
- eventTime 和 traceId。

### 11.2 两条确定性 Settlement Leg

Matching 在同一输出事务中从 `TradeFact` 派生：

- maker settlement leg。
- taker settlement leg。

两条腿作为 settlement mutation 写回 canonical `user.mutations.v2`，分别以用户 key 进入
User Shard。同一用户自成交时，两条腿进入同一个用户分区，并按确定顺序处理。任何其他会
修改用户资金或持仓的模块也必须写入这个 canonical 流，不能绕过它直接调用本地 reducer。

不能对两个用户分区做分布式锁或 XA 事务。正确性来自：

- 两条腿由同一不可变 TradeFact 确定生成。
- 两条腿在同一 Matching 输出事务中提交。
- 每条腿在对应 User Shard 幂等执行。
- 结算协调器异步确认双方完成。
- 对账任务校验资金、仓位、手续费和未完成腿。

### 11.3 资金守恒门槛

每次容量、恢复和故障测试必须核对：

```text
期初余额
+ 充值/人工调整
+ 已实现盈亏
+ 资金费收入
- 资金费支出
- 手续费
- 强平费
- 交割/行权支出
= 期末余额 + 当前冻结 + 当前保证金
```

并校验：

- maker/taker 成交数量一致。
- 多空持仓增减一致。
- 手续费收入与用户扣减一致。
- 结算腿无重复、无缺失。
- 最终未完成结算腿为零。

## 12. API 和同步结果返回

### 12.1 推荐外部语义

高吞吐模式下，Gateway 在命令被 Kafka `acks=all` 接收后返回：

```text
202 RECEIVED
requestId / clientOrderId
```

最终 `ACCEPTED`、`REJECTED`、`FILLED` 等状态通过：

- 私有 WebSocket。
- 按 `clientOrderId` 查询。
- 按 `requestId` 查询结果。

这避免 HTTP 请求线程等待完整 Kafka 往返。

### 12.2 必须同步返回时

若兼容接口必须同步返回 `ACCEPTED/REJECTED`，增加独立 `CommandResultRouter`：

```text
User State Engine result
  -> shared result consumer group，只消费一次
  -> 按 requesterInstanceId 路由到 Gateway 的长连接
  -> 完成该 Gateway 上的有界等待槽
```

要求：

- 不为每个 Gateway 建立一个消费全部结果的独立 group。
- 等待槽必须有 TTL 和容量上限。
- 结果完成后立即删除等待槽。
- 超时只表示响应超时，不代表命令失败。
- 客户端必须使用相同幂等键重试。

## 13. 公共行情与财务事件隔离

财务事件不能被丢弃或覆盖：

- 订单结果。
- TradeFact。
- Settlement mutation。
- 余额、冻结、持仓和手续费事件。

公共行情可以在明确规则下合并：

- order-book depth 使用 latest-only/coalescing。
- 高频 ticker 允许按时间窗口合并。
- 公共 trade 仍需保持成交顺序，但不能阻塞财务提交。

因此 Matching 的输出至少分为：

```text
financial lane: reliable, ordered, never drop
market-data lane: bounded, coalescible, isolated backpressure
```

## 14. Backpressure 和过载保护

### 14.1 有界队列

所有内存队列、RingBuffer、等待结果和幂等缓存必须有明确上限。达到上限时：

- Gateway 对新请求返回可重试的 429/503。
- Kafka consumer 暂停对应 partition。
- 保留已持久化命令，不丢弃财务事件。
- 公共行情允许合并旧快照。

禁止：

- 无限队列。
- 无限 `ConcurrentHashMap`。
- 继续接收请求直到 OOM。
- 为降低 lag 绕过用户或 symbol 顺序。

### 14.2 分区级隔离

监控和过载保护必须至少精确到 shard：

- partition lag。
- mailbox/ring occupancy。
- batch processing time。
- transaction commit latency。
- state restore lag。
- hot user/hot symbol TPS。

单个毒命令或热点 shard 不能拖慢所有其他 shard。

## 15. 故障恢复和 fencing

### 15.1 Shard Epoch

每个状态机输出包含：

```text
component + productLine + shardId + epoch + sequence
```

- 新 Owner 获取更高 epoch。
- 旧 Owner 的 Kafka transactional producer 被 fencing。
- 下游拒绝低 epoch 的非法输出。
- sequence 只在 shard 内单调，不追求全局单调。

### 15.2 快照

快照必须包含：

- state schema version。
- productLine 和 shardId。
- 最后应用 input offset。
- 最后提交 changelog offset。
- 用户/订单簿状态。
- 幂等水位。
- 校验和。

恢复流程：

```text
load latest valid snapshot
  -> replay changelog from snapshot offset
  -> catch up input partition
  -> pass state invariant checks
  -> become ready
```

### 15.3 Warm Standby

对资金和撮合关键 shard，建议准备 warm standby：

- 持续消费 changelog 或复制快照。
- 记录恢复 lag。
- active 故障后优先选择已追平的 standby。
- 未达到恢复水位前不能接管写流量。

### 15.4 故障矩阵

必须验证：

1. User State Engine 在事务提交前崩溃。
2. User State Engine 在事务提交后、响应前崩溃。
3. Matching Engine 在生成 TradeFact 前后崩溃。
4. Settlement Leg 只处理一侧后节点崩溃。
5. Kafka rebalance 期间旧 Owner 仍存活。
6. RocksDB checkpoint 丢失或损坏。
7. PostgreSQL 投影停止后恢复。
8. Gateway 等待结果超时后客户端重试。
9. 重复、乱序、延迟事件进入投影端。

所有场景必须满足 RPO=0、最终 lag=0、最终 pending=0、资金差异=0。

## 16. 数据库和投影

PostgreSQL 保留以下职责：

- 订单历史查询。
- 账本和审计。
- 资金核对。
- 报表。
- 管理后台。
- 冷启动基线和离线恢复辅助。
- 强平、ADL、保险基金等非普通订单链路中经评审保留的事务边界。

普通交易热路径不得执行：

- 每单数据库 sequence。
- 每单 `SELECT FOR UPDATE`。
- 同步订单表 insert/update 后才发布撮合命令。
- 等待数据库 Outbox 扫描后才进入撮合。

投影必须：

- 按事件幂等更新。
- 批量写数据库。
- 使用连续水位。
- 允许数据库短暂不可用而不阻塞核心交易状态机。
- 恢复后追平且最终 pending 为零。

## 17. 部署和横向扩容

### 17.1 Gateway

- 完全无状态。
- 独立扩容。
- 不保存无限命令结果。
- 限流按用户、IP、API key 和产品线执行。

### 17.2 User State Engine

- 固定 User Shard 数。
- 每实例承载多个 shard event loop。
- shard 数量不能超过可用执行核心和 Kafka partition 上限。
- 扩容通过 consumer group 迁移 ownership。
- 状态目录按实例和 shard 隔离。

### 17.3 Matching Engine

- 热点 Book Shard 独占 pod 或物理核心。
- 生产低延迟配置使用 CPU pinning。
- 不与 Gateway、WebSocket、数据库投影共享延迟敏感核心。
- 每个 shard 独立 readiness 和恢复状态。

### 17.4 Projection/WebSocket

- 与财务状态机独立扩容。
- WebSocket fanout 可以按 symbol/user 分区。
- 慢客户端不能反压 Matching 或 User State Engine。

## 18. 迁移路线

迁移必须逐阶段完成，每阶段都需要资金核对和故障恢复验证，不能一次性重写全部核心链路。

### 阶段 0：建立可信基线

- 在当前 Git SHA 上重新执行完整生产性能矩阵。
- 采集 p50/p95/p99/p99.9/max。
- 采集 Kafka lag、WAL append、RocksDB sync、Owner queue、Outbox 和 GC。
- 执行 `baseline`、`capacity`、`hot1`、`hot3`、`burst`、`soak`。
- 保存资金守恒和恢复证据。

完成标准：能够定位每个阶段的吞吐、排队和同步 I/O 成本。

### 阶段 1：修复横向扩容阻塞项

- 给 Order/Account 结果等待器增加有界 TTL 和成功删除。
- 停止每个实例广播消费全部命令结果。
- 强制校验每个 Order node-id 唯一，或替换订单号生成方式。
- 为 Owner queue、等待槽和幂等缓存增加容量指标。

完成标准：实例数增加时，结果网络流量和内存不按 `实例数 × 总命令量` 增长。

### 阶段 2：移除逐条同步 I/O

- 订单号使用预分配区间或日志坐标，不再逐 ID fsync。
- Account WAL 按 Kafka poll 批次 group commit。
- Account result 与 WAL 评估合并为一个原子记录。
- Matching published 标记批量提交。
- Kafka 发送改为批量异步发送后统一确认。

完成标准：一次 Kafka poll 批次的 fsync 次数为常数级，而不是命令条数级。

### 阶段 3：移除跨 shard 全局状态

- 删除跨 symbol 全局 Outbox sequence。
- Outbox 改为 per-stream sequence。
- Matching commit 不再经过一个全局 Owner。
- 订单 ID 不再依赖全局单 Owner。

完成标准：不同 symbol 和不同用户的提交不争抢同一 Owner 或 sequence key。

### 阶段 4：直接分区单写事件循环

- Kafka partition 线程直接驱动 reducer，或使用 SPSC bridge。
- 删除热路径 MPSC `PartitionOwnerLane.execute().join()`。
- 状态机内移除并发集合。
- 所有同步网络 I/O 移出 Owner 执行段。

完成标准：一个 shard 只有一个写线程，热路径无跨线程 Future 等待。

### 阶段 5：统一用户热写状态机

- 将订单状态和账户状态的写模型收敛到同一 User Shard。
- 在同一批次内完成订单创建和资金预占。
- 只在预占成功后输出 MatchingCommand。
- 保留现有 API 和异步数据库投影兼容层。

完成标准：普通下单不再经过 Order -> Account -> Order 的同步结果往返。

### 阶段 6：独立 Matching Book Shard

- 每个 Book Shard 独立 engine、状态、序号和快照。
- 热点 symbol 支持独占 shard/pod。
- 财务输出和公共行情输出隔离。
- 删除跨 shard 全局 Matching 状态。

完成标准：增加 Matching pod 能提高多 symbol 总吞吐，且热点 symbol 不影响其他 shard。

### 阶段 7：Kafka EOS 和 Warm Standby

- 输入 offsets、changelog 和输出事件使用 Kafka transaction。
- checkpoint 改为可重建缓存。
- 引入 shard epoch 和 producer fencing。
- 增加 warm standby 和恢复 lag 调度。

完成标准：任意事务边界崩溃后 RPO=0，无重复资金影响，恢复后状态等价。

### 阶段 8：评估 Aeron Cluster

仅在 Kafka 主路线已经达到正确性门槛、但无法满足明确的亚毫秒延迟目标时执行。

## 19. 性能和正确性验收

沿用 [`docs/production-performance-test-plan.md`](docs/production-performance-test-plan.md) 的生产门槛：

| 指标 | 默认目标 |
|---|---:|
| 下单/撤单/改单总吞吐 | 3000 ops/s |
| 成交吞吐 | 1000 trades/s |
| 下单受理 p99 | ≤80ms |
| 受理到 match result p99 | ≤150ms |
| trade 到余额/持仓可见 p99 | ≤300ms |
| 正常 Kafka lag | <1s |
| 故障恢复后 Kafka lag 清零 | ≤300s |
| 单倍负载 CPU | <55% |
| 双倍突发 CPU | <75% |
| GC pause p99 | <20ms |
| 单次 GC pause | <100ms |
| 最终 Outbox/pending | 0 |
| 资金差异、重复扣款、重复结算 | 0 |

新增横向扩展验收：

- 1、2、4、8 个 User Engine 实例的吞吐曲线。
- 1、2、4、8 个 Matching Shard 的多 symbol 吞吐曲线。
- 扩容时无状态丢失、无双 Owner 写入、无资金差异。
- 固定负载下，增加实例不会增加结果广播倍数。
- 所有缓存、等待槽和队列都有稳定上限。
- `hot1` 不得拖慢未共享 shard 的冷门 symbol。
- 一个毒用户分区不得阻塞其他 Kafka partition。

性能结论必须基于完整链路，不得使用纯 `exchange-core` benchmark 代替 Gateway、Kafka、用户状态、结算和投影的端到端结果。

## 20. 必须采集的指标

### 20.1 Gateway

- 请求 TPS、状态码、超时率。
- durable-ack latency。
- 同步结果等待槽数量和年龄。
- 用户/IP/API key 限流。

### 20.2 User State Engine

- 每 shard command TPS。
- batch size 和 batch duration。
- reducer processing latency。
- transaction begin/commit/abort latency。
- changelog lag 和 restore lag。
- shard queue/ring occupancy。
- hot user 分布。

### 20.3 Matching Engine

- 每 symbol、每 shard 的 command/trade TPS。
- RingBuffer occupancy。
- matching command p99。
- snapshot duration 和恢复时间。
- financial lane backlog。
- market-data coalescing/drop count。

### 20.4 结算和对账

- settlement leg produced/applied/duplicate/rejected。
- 双边结算完成延迟。
- 单边未完成成交数量和最老年龄。
- 资金差异、持仓差异、手续费差异。

### 20.5 运行时

- CPU、上下文切换、run queue。
- GC pause、allocation rate、heap/off-heap。
- 磁盘 fsync、IOPS、吞吐和 await。
- Kafka producer batch、compression、request latency。
- 网络吞吐和重传。

## 21. 不推荐的方案

### 21.1 给单用户或单订单簿加更多线程

这会增加锁、CAS、乱序和重放复杂度，不能安全提高同一状态实体的吞吐。

### 21.2 只把锁换成无锁 MPSC 队列

如果仍然存在多个生产者、共享 CAS、同步 fsync 和 `Future.join()`，只是把等待位置换了，不会形成真正的无竞争架构。

### 21.3 直接调大所有线程池

在存在单 Owner、全局 sequence 和同步 RocksDB 的情况下，提高线程数可能增加：

- CAS 冲突。
- RocksDB writer 排队。
- Kafka in-flight 请求。
- GC 和上下文切换。
- P99 抖动。

### 21.4 把 Redis 当资金权威状态

Redis 可以用于查询缓存、索引和限流，但不能代替可重放的资金事实流和确定性状态机。

### 21.5 用数据库 XA 解决双边结算

跨用户、跨分区 XA 会重新引入全局协调和数据库锁，不符合高吞吐和水平扩展目标。应使用不可变 TradeFact、确定性 Settlement Leg、幂等执行和最终对账。

### 21.6 未经压测直接使用 BusySpin

BusySpin 需要独占核心。在容器超卖、共享 CPU 或 CPU limit 下可能显著恶化系统延迟。

## 22. Kafka 与 Aeron 决策矩阵

| 维度 | Kafka 分片状态机 | Aeron Cluster 分片状态机 |
|---|---|---|
| 与当前技术栈兼容 | 高 | 低 |
| 迁移风险 | 中 | 高 |
| 水平扩展 | Kafka partition/shard | 独立 cluster/shard group |
| 一致性 | Kafka transaction + replay | Raft replicated state machine |
| 延迟 | 毫秒级 | 可进入亚毫秒级 |
| 运维复杂度 | 中 | 高 |
| 快照/恢复 | changelog + checkpoint | cluster log + snapshot |
| 确定性要求 | 高 | 极高 |
| 当前推荐 | 是 | 否，作为后续评估 |

## 23. 最终架构约束清单

目标架构完成后应满足：

- [ ] 同一用户只有一个逻辑写者。
- [ ] 同一 symbol 只有一个逻辑撮合写者。
- [ ] 不存在跨用户全局订单 ID Owner。
- [ ] 不存在跨 symbol 全局 Outbox Owner。
- [ ] 热路径没有数据库查询和事务。
- [ ] 热路径没有每命令 RocksDB fsync。
- [ ] Owner/event loop 内没有逐条 `KafkaTemplate.send().get()`。
- [ ] 没有无限 `completed`、waiting、mailbox 或幂等缓存。
- [ ] API 实例不广播消费全部命令结果。
- [ ] 输入 offset 与输出事件具有一个明确原子提交边界。
- [ ] checkpoint 丢失时可以从权威日志恢复。
- [ ] shard 迁移具有 epoch fencing。
- [ ] 增加实例不改变业务 key 到 shard 的映射。
- [ ] 单热点用户或 symbol 不影响其他 shard。
- [ ] 任意重试和故障恢复不会造成重复资金影响。
- [ ] 四产品线资金、Topic、状态和恢复完全隔离。

## 24. 实施决策

当前建议按以下顺序推进：

1. 先处理结果等待器无限缓存、全实例广播和 Order node-id 风险。
2. 再移除订单号、Account WAL/result、Matching Outbox 的逐条同步 I/O。
3. 再取消全局 sequence 和全局 Matching Outbox Owner。
4. 再把 Kafka partition consumer 改造成直接单写事件循环。
5. 再统一 Order + Account 的用户热写状态机。
6. 再将 Matching 拆成独立 Book Shard。
7. 最后引入 Kafka EOS、warm standby，并根据实测决定是否需要 Aeron。

每个阶段必须在单产品线下完成：功能测试、完整链路压测、故障恢复、资金守恒和提交前后状态等价验证，然后才能进入下一阶段。

## 25. References

- [Apache Kafka Streams configuration and exactly-once-v2](https://kafka.apache.org/42/streams/developer-guide/config-streams/)
- [LMAX Disruptor User Guide](https://lmax-exchange.github.io/disruptor/user-guide/)
- [RocksDB WAL Performance](https://github.com/facebook/rocksdb/wiki/WAL-Performance)
- [Aeron Cluster Replicated State Machines](https://aeron.io/docs/cluster-quickstart/replicated-state-machines/)

## 26. English summary

The recommended target is a Kafka-backed, sharded, single-writer trading architecture. User order, balance, frozen-fund, position, and margin mutations should be owned by a stable user shard. Each order book should be owned by a stable matching shard. Shards run independently and do not share mutable hot-path state, global sequences, synchronous per-command WAL writes, or result broadcasts.

Kafka input offsets, output events, and state changelogs should be committed in one micro-batched transaction. RocksDB becomes a rebuildable checkpoint rather than the authoritative per-command WAL. PostgreSQL remains an asynchronous query, audit, and reconciliation projection. Horizontal scaling adds nodes and reassigns fixed shards without changing key-to-shard mapping.

Aeron Cluster is reserved for a future sub-millisecond latency requirement after the Kafka architecture reaches its correctness and capacity limits.
