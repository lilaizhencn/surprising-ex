# Aeron 统一交易核心术语表

## 目的

本文统一迁移期间的架构和业务语言。代码、测试、监控和 Runbook 使用不同含义时，应先更新本文并在
ADR 中说明，而不是为同一概念继续增加别名。

| 术语 | 定义 | 不代表什么 |
| --- | --- | --- |
| 权威状态 | 用于决定资金、订单、持仓、风险和强平结果的唯一在线状态。迁移后位于 Aeron Cluster。 | PostgreSQL、Valkey或 Kafka consumer 的查询投影。 |
| Aeron Cluster Log | 由 Consensus Module 复制和提交的确定性命令日志。 | 应用自己写的本地 WAL。 |
| Aeron Archive | 记录 Cluster Log 并支持 Snapshot 之后的重放。 | 业务报表归档或 PostgreSQL 审计库。 |
| Aeron Snapshot | Clustered Service 在确定 log position 上的完整可恢复状态。 | 任意时刻导出的调试 JSON。 |
| Cluster position | 命令在复制日志中的确定位置，用于恢复、审计和状态版本。 | Kafka offset 或数据库 sequence。 |
| Cluster logical time | 由 Cluster 驱动且在所有 Member 一致的时间输入。 | Member 本机 `Instant.now()`。 |
| Member | 一套产品线 Cluster 的一个复制节点，运行 Media Driver、Archive、Consensus Module 和 Service。 | 一个产品线或一个业务用户。 |
| Leader | 当前接受客户端 ingress 并推进共识的 Member。 | 唯一保存完整状态的节点；Follower 也执行相同已提交命令。 |
| Follower | 复制并确定性执行已提交命令的 Member。 | 只读缓存。 |
| Product Line Cluster | 单条产品线的三节点 Aeron Cluster。六条产品线拥有六套逻辑 Cluster。 | 六条线共享的一套大 Cluster。 |
| Unified Core State | 一条产品线内 User、Order、Book、Risk、Liquidation 和 Export 的顶层状态。 | 把六条产品线资金合并。 |
| User State | 用户产品账户余额、冻结、保证金、持仓和账务版本。 | 用户登录资料、KYC 或 wallet 私钥。 |
| Order State | 订单、预占、已成交/剩余数量、状态和幂等索引。 | 仅供后台展示的订单表。 |
| Reservation State | 订单维度的预占资产、原始数量、已释放、已消费和剩余锁定；剩余锁定必须可重建 Balance locked。 | 只保存一个按资产汇总的冻结数。 |
| Business State Hash | 只覆盖规范化业务状态的稳定哈希。 | 包含幂等窗口和来源高水位的完整内部恢复哈希。 |
| Internal Recovery Hash | 同时覆盖业务状态、幂等结果和来源高水位，用于 Snapshot/replay 完整一致性验证。 | 对外账户余额或订单查询结果。 |
| Strong Core Query | 从当前 Product Line Cluster 已提交状态直接返回 User/Order 等权威视图。 | PostgreSQL 或 Valkey 的最终一致查询。 |
| Book State | 能精确恢复价格时间优先级的开放订单簿权威状态。 | Kafka depth 行情快照。 |
| Exchange Core Adapter | 把统一核心命令映射到 Exchange Core 并将 fill 原子应用回权威状态的适配层。 | 独立权威撮合服务或本地 journal。 |
| Risk State | 标记价、风险参数、账户风险快照、索引和扫描游标。 | Redis 风险缓存。 |
| Liquidation State | 强平从发现、撤单、重算、下强平单、结算到保险/ADL 的可恢复进度。 | Redis candidate queue 中的一条临时记录。 |
| Export State | Cluster 内保存的待导出事件、连续确认游标和稳定内容摘要；使用条数与总字节双硬上限。 | Kafka 本身的 consumer offset。 |
| Reliable Exporter | 从 Export State 向 Kafka at-least-once 发布并将 ack 提交回 Cluster 的进程。 | Aeron 与 Kafka 的分布式事务。 |
| Export Sequence | 单条产品线内由 Aeron State 连续分配的对外事件序号；Kafka key 和 PG 幂等主键都包含它。 | Kafka offset 或 Cluster position。 |
| Export ACK | Kafka 全批成功后提交回 Aeron、只推进连续区间的命令；自身不生成 Export Event。 | Kafka producer ack 本身。 |
| Export Backpressure | pending 达到条数或字节硬上限时，在业务 reducer 前返回 `EXPORT_BACKLOG_FULL`；这不是业务裁决。 | 可提交 Kafka input offset 的拒单。 |
| Kafka Input Bridge | 将版本化外部 Kafka envelope 转换为稳定 commandId 的 Aeron 命令，明确业务裁决后再确认 offset。 | 核心业务状态存储。 |
| Projection | 从 Kafka 事件构建的 PostgreSQL、Valkey、WebSocket 或报表读模型。 | 可回写或覆盖核心状态的权威源。 |
| commandId | 标识一次业务意图的稳定幂等 ID；重试必须复用。 | 每次网络请求随机生成的新 ID。 |
| correlationId | 用于串联调用、日志和响应的追踪 ID。 | 状态变化幂等键。 |
| sourceId | 同类命令来源内的稳定实例或分区标识，例如 Gateway client agent ID 或 Kafka partition。 | 用户 ID 或随机请求 ID。 |
| sourceSequence | 在 `(source, sourceId)` 范围内严格单调的外部序号，例如 Kafka offset。 | Cluster position 或跨所有来源共用的全局序号。 |
| commandStatus | 命令首次执行时的原始业务裁决；重复响应的 transport status 为 `DUPLICATE` 时仍返回该值。 | 本次网络传输是否为重试。 |
| resultCode | 首次业务裁决的稳定机器可读结果码，随幂等结果和 Snapshot 保存。 | 只供日志阅读的异常 message。 |
| Source High Watermark | Cluster 对每个 `(source, sourceId)` 已执行最大序号的记录；完整幂等结果淘汰后仍阻止旧命令重放。 | 可返回原业务响应的完整幂等窗口。 |
| eventId | Exporter 重试时保持不变的稳定事件 ID，供所有消费者幂等。 | Kafka producer 自动生成的消息 ID。 |
| stateVersion | 聚合在状态变化后的单调业务版本。 | 数据库更新时间。 |
| State Hash | 对规范化权威状态计算的稳定哈希，用于副本和回放一致性验证。 | Java 对象默认 `hashCode()`。 |
| Book State Hash | 对开放订单、剩余数量和价格时间优先序计算的规范化稳定哈希，用于 Snapshot 恢复。 | 行情 depth 前几档或 Exchange Core 内部对象 hash。 |
| Exchange Core Runtime Hash | `StateHashReportQuery` 的 `MATCHING_ORDER_BOOKS` 子模块结果，用于运行中 Member 执行器一致性。 | 从开放订单重建后必须与包含成交历史的旧内部 hash 相同。 |
| Deterministic Replay | 对相同命令、顺序和逻辑时间重复执行，得到完全相同状态。 | 从 PostgreSQL 猜测性重建开放订单。 |
| RPO | 故障后允许丢失的已提交核心命令数量；目标为零。 | Kafka/PG 短时可见延迟。 |
| RTO | 从故障发生到恢复规定服务能力的时间，按 Leader 切换、Follower rejoin 和冷启动分别测量。 | 单个 JVM 启动耗时。 |
| Drain Mode | 为 Snapshot、升级或 Export 极限积压进入的安全模式，限制新风险，只允许安全类命令。 | 直接杀死服务。 |
| Safe Command | 撤单、reduce-only、强平续跑等减少或控制风险的命令。 | 所有管理员命令。 |
| Functional Gate | 性能测试前的完整功能、恢复和故障前置门禁。 | 简单 health check。 |
| Funds Gate | 性能测试前用户/做市账户、手续费、PnL、资金费、强平、交割/行权逐项对平且差异为零。 | 只检查总余额大致相等。 |
| Single Product-Line Load Test | 只启动并压测一条产品线，形成该线独立容量结论。 | 六线混压或纯 Exchange Core benchmark。 |
| Core OPS | Aeron Cluster 每秒提交并执行的核心命令数。 | Gateway HTTP TPS。 |
| Gateway Accepted OPS | Gateway 接受并获得核心已提交结果的每秒操作数。 | 仅发送到网络的请求数。 |
| Settled Trades/s | 成交已在核心完成双边订单和资金结算的每秒成交数。 | 仅 Exchange Core 产生的 match 数。 |
| Legacy WAL | `UserPartitionWal`、Matching 本地 checkpoint/outbox 等应用级权威恢复实现。 | Aeron Cluster Log 或 Archive。 |
| Shadow Cluster | 与主链路长期并行消费并比较结果的第二套 Cluster。当前未上线项目不采用。 | 离线确定性 replay 测试。 |
| Dual Authority | 两套系统都可能被当作资金或订单最终事实。迁移明确禁止。 | Aeron 权威状态加异步审计投影。 |

## 产品线术语

| 名称 | `ProductLine` | 结算特点 |
| --- | --- | --- |
| 现货 | `SPOT` | 基础资产和计价资产直接互换。 |
| U 本位永续 | `LINEAR_PERPETUAL` | 计价稳定币保证金，线性 PnL，无到期日。 |
| 币本位永续 | `INVERSE_PERPETUAL` | 基础币保证金，反向 PnL，无到期日。 |
| U 本位交割 | `LINEAR_DELIVERY` | 稳定币结算，线性 PnL，到期现金交割。 |
| 币本位交割 | `INVERSE_DELIVERY` | 基础币结算，反向 PnL，到期交割。 |
| 欧式期权 | `OPTION` | 权利金、卖方保证金，到期现金行权或失效。 |

## 文档维护规则

- 新增核心状态、命令、恢复概念或监控指标时同步补充本文。
- 术语含义变化必须通过 ADR，不允许只修改代码变量名。
- 产品文档可使用中文展示名，但配置、协议和指标使用表中固定英文名称。
