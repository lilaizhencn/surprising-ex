# Aeron 统一交易核心迁移实施方案

## 1. 文档状态

| 字段 | 当前值 |
| --- | --- |
| 文档状态 | `APPROVED_FOR_IMPLEMENTATION` |
| 基线分支 | `master` |
| 基线提交 | `dc46edabcd606fea85517974391739942d5f51e2` |
| 目标实施分支 | `codex/aeron-unified-core` |
| 当前阶段 | `P6 删除旧 WAL、Redis Risk 和旧强平链路` |
| 最后更新日期 | `2026-08-13` |
| 上线状态 | 项目尚未上线，无生产历史数据和兼容包袱 |
| 架构决策 | [ADR-0001：按产品线部署统一 Aeron 复制状态机](adr/0001-aeron-unified-trading-core.md) |
| 术语表 | [Aeron 统一交易核心术语表](aeron-unified-trading-core-glossary.md) |

本文档既是设计方案，也是实施台账。每完成一个阶段，实施者必须在同一次提交中更新：

1. 本节的“当前阶段”和“最后更新日期”。
2. 第 18 节阶段状态表。
3. 对应阶段的任务勾选、实际修改文件和验收证据。
4. 第 19 节决策与偏差记录。
5. 如果协议、状态边界或恢复语义发生变化，同步更新 ADR 和术语表。

任何阶段不得只修改代码而不更新本文档。未经证据支持，不得把阶段标记为 `DONE`。

## 2. 最终结论

Surprising-EX 一次性迁移为按产品线隔离的 Aeron Cluster 交易核心。六条产品线各运行一套三节点
Aeron Cluster，每套 Cluster 内由一个统一、确定性的复制状态机同时持有并更新：

- User State：余额、冻结、保证金、持仓和账务序号。
- Order State：订单、预占、幂等结果和生命周期状态。
- Book State：交易对、开放订单、价格档位和时间优先级。
- Exchange Core Adapter：继续复用 `exchange-core:0.5.3` 的订单簿和撮合算法。
- Risk State：标记价、风险参数、账户风险快照和风险触发索引。
- Liquidation State：强平计划、强平进度、强平订单和最终结果。
- Export State：待导出事件、导出游标和幂等标识。

迁移不采用长期双写、影子集群或兼容模式。Aeron 新核心通过功能、资金和恢复门禁后，立即删除旧
User WAL、Matching 本地 checkpoint/outbox、Redis Risk 权威状态和旧强平链路；然后才进行单产品线
性能压测。性能不达标时优化唯一的新链路，不恢复旧权威实现。

## 3. 经过审问后冻结的决策

本方案在实施前对最容易产生架构漂移的问题做出以下明确回答。

| 问题 | 冻结答案 |
| --- | --- |
| 项目未上线，是否需要影子集群？ | 不需要长期影子集群；使用离线确定性重放和状态哈希验证。 |
| Aeron 是否完全替代 Kafka？ | 否。Aeron 是交易裁决和权威状态；Kafka 保留外围输入缓冲和对外事件总线。 |
| 是否保留应用级 WAL？ | 不保留权威应用 WAL；Aeron Cluster Log、Archive 和 Snapshot 组成唯一恢复链。 |
| 是否重写撮合算法？ | 不重写；继续使用 Exchange Core，通过确定性 Adapter 纳入 Aeron 状态机。 |
| Risk 和强平是否继续使用 Redis？ | 不继续作为权威状态或候选队列；Risk 和强平进入 Aeron 状态机。 |
| PostgreSQL 是否参与下单、撮合、风险或强平裁决？ | 不参与；只承担异步投影、审计、查询和对账。 |
| Valkey 是否可以故障而不影响核心交易状态？ | 可以；核心不得依赖 Valkey 恢复资金、订单、持仓或强平进度。 |
| 是否拆成 User、Book、Risk 多套 Cluster？ | 不拆；每条产品线一套统一 Cluster，避免跨 Cluster 资金事务。 |
| 六条产品线是否共用一个 Cluster？ | 不共用；每条产品线独立三节点，故障域和状态完全隔离。 |
| 旧链路何时删除？ | 新核心功能、资金、快照和恢复门禁通过后立即删除，不等性能达标。 |
| 压测方式是什么？ | 一次只压一条产品线；压测前必须先通过该产品线完整功能和资金门禁。 |
| 能否用 Exchange Core benchmark 代表最终 OPS？ | 不能；必须同时报告核心吞吐和 Gateway 到投影可见的端到端吞吐。 |

尚未通过实测确定的参数，例如 Snapshot 周期、事件积压高水位、Busy Spin 与 Yielding 的选择，不能
在编码时随意拍定。它们必须保留为可配置项，并在恢复测试和单产品线压测中确定生产值。

## 4. 目标和非目标

### 4.1 目标

- 用单一复制日志保证用户资金、订单、订单簿、风险和强平的一致顺序。
- 消除 Kafka、RocksDB、本地 WAL、Redis 和 PostgreSQL 多重权威造成的恢复组合爆炸。
- 六条产品线使用相同运行骨架、命令协议、Snapshot、Exporter、恢复和监控方式。
- 保持产品线资金数学差异，不用错误的“统一模型”抹平现货、线性、反向和期权语义。
- Leader 故障时 RPO 为零；新 Leader 从一致状态继续处理已提交命令。
- Exporter、Kafka、PostgreSQL 或 Valkey故障不能损坏核心权威状态。
- 为生产部署提供三机拓扑、备份、容量、监控和故障操作方案。

### 4.2 非目标

- 本次迁移不同时升级 Exchange Core 版本。
- 不在第一轮实现跨产品线组合保证金。
- 不把 wallet、充值、提现私钥或链上签名放进 Aeron Cluster。
- 不把行情 K 线、WebSocket 会话、报表或通知变为核心状态。
- 不承诺未经实测的 OPS 数字。
- 不用本地 16GB 开发机的结果宣称生产容量。

## 5. 资金与确定性不变量

以下不变量优先级高于吞吐和代码复用。任何一个失败都必须停止当前阶段。

### 5.1 通用不变量

1. 同一 `commandId` 重复提交只能产生一次状态变化，并返回同一业务结果。
2. 同一 `tradeId` 的双边结算只能发生一次。
3. 余额不得无来源增加或无去向减少。
4. `available + frozen + positionMargin + otherLocked` 必须能由期初余额和全部账务事件推导。
5. 开放订单的剩余数量、已成交数量和原始数量必须满足产品线对应的数量守恒关系。
6. 撤单、拒单和终态订单必须完整释放未消费预占。
7. Leader 切换、客户端重试、Exporter 重试和投影重放不能造成重复扣款或重复结算。
8. 所有核心数学使用整数定点单位；禁止在权威状态机中使用 `double`、本地时区或随机数。
9. 同一命令序列在任意 Member 和离线重放器上必须得到同一状态哈希。
10. 所有影响状态的时间必须来自 Cluster 时间事件或命令字段，不得调用 `Instant.now()` 裁决业务。

### 5.2 产品线不变量

| 产品线 | 必须保持的不变量 |
| --- | --- |
| `SPOT` | 买方计价资产冻结和卖方基础资产冻结正确；成交后双资产互换、手续费和余量解冻正确；不得生成衍生品持仓。 |
| `LINEAR_PERPETUAL` | USDT 线性 PnL、初始/维持保证金、资金费、强平费和保险缺口可对账。 |
| `INVERSE_PERPETUAL` | 基础币结算、反向 PnL、价格越低保证金需求越高的边界正确，资金费使用反向合约单位。 |
| `LINEAR_DELIVERY` | U 本位交割价不可变，交割后持仓归零、保证金释放、交割流水完整。 |
| `INVERSE_DELIVERY` | 基础币反向交割结算正确，交割后持仓和预占清零。 |
| `OPTION` | 买方权利金和最大亏损、卖方保证金、现金行权收益、到期失效与释放正确。 |

## 6. 目标运行拓扑

```text
Gateway / Internal API
        |
        | Aeron Cluster Client command
        v
+----------------------------------------------------------+
| ProductLine Aeron Cluster: 3 members                     |
|                                                          |
| Command Dispatcher                                       |
|   -> Idempotency State                                   |
|   -> User / Order State                                  |
|   -> Exchange Core Adapter / Book State                  |
|   -> Risk State                                          |
|   -> Liquidation State                                   |
|   -> Export State                                        |
|                                                          |
| Cluster Log + Archive + Snapshot = 唯一权威恢复链          |
+----------------------------------------------------------+
        |                                ^
        | query / export batch           | export ack command
        v                                |
Kafka Exporter --------------------------+
        |
        +--> Kafka --> PostgreSQL projection / audit
        +--> Kafka --> WebSocket / candlestick / notification
        +--> Kafka --> reporting / warehouse

Kafka Input Bridge --> Aeron command
  instrument / mark price / funding / delivery / exercise
```

六套逻辑 Cluster：

| Cluster | Cluster ID 建议 | 生产 Member 数 | 核心差异 |
| --- | ---: | ---: | --- |
| `SPOT` | 101 | 3 | 无衍生品 Risk/强平状态 |
| `LINEAR_PERPETUAL` | 102 | 3 | 线性 PnL、资金费、强平、保险、ADL |
| `INVERSE_PERPETUAL` | 103 | 3 | 反向 PnL、基础币保证金、资金费、强平 |
| `LINEAR_DELIVERY` | 104 | 3 | 线性交割、到期状态机 |
| `INVERSE_DELIVERY` | 105 | 3 | 反向交割、基础币结算 |
| `OPTION` | 106 | 3 | 权利金、卖方保证金、到期行权 |

Cluster ID 只是初始建议。真正使用前必须与 Aeron channel、端口、目录和监控标签一起做自动冲突校验。

## 7. 领域模型和状态所有权

### 7.1 聚合边界

`ProductLineCoreState` 是单条产品线的顶层状态容器：

```text
ProductLineCoreState
  metadata
    schemaVersion
    productLine
    clusterLogicalTime
    lastAppliedPosition
  instruments: InstrumentKey -> InstrumentState
  users: UserId -> UserState
  orders: OrderId -> OrderState
  books: InstrumentKey -> BookState
  risk: RiskState
  liquidations: LiquidationId -> LiquidationState
  commandResults: CommandId -> CommandResultState
  export: ExportState
```

### 7.2 User State

每个 `UserState` 至少包含：

- 产品账户资产余额：`availableUnits`、`frozenUnits`。
- 订单预占：按 `orderId` 记录资产、数量、已消费和剩余值。
- 持仓：按 instrument、保证金模式和持仓方向记录数量、开仓均价、保证金和已实现 PnL。
- 资金费累计和最近结算序号。
- 交割/行权生命周期序号。
- 用户级单调 `stateVersion`。
- 用于查询和对账的稳定状态哈希输入。

账户资金状态不得分散到另一个 Aeron Cluster。跨产品线划转仍由外围协调器提交两个独立、幂等的产品线
命令，并由 PostgreSQL 审计 Saga 追踪；它不是单条产品线撮合命令的同步热路径。

### 7.3 Order State

`OrderState` 至少包含：

- `orderId`、`clientOrderId`、`commandId` 和 `userId`。
- 产品线、symbol、instrument version。
- side、type、time-in-force、reduce-only、margin mode、position side。
- 原始数量、已成交数量、剩余数量、价格。
- 预占引用和费率快照。
- 当前状态及最后变更的 Cluster position。

`clientOrderId` 和 `commandId` 的幂等索引属于权威状态。不能依赖 PostgreSQL 唯一约束完成核心幂等。

### 7.4 Book State 与 Exchange Core Adapter

Exchange Core 继续作为确定性订单簿执行器，但不再独立拥有恢复事实。Adapter 负责：

1. 将核心命令映射为 Exchange Core long 单位命令。
2. 在同一状态机调用中同步取得完整撮合结果。
3. 将 maker/taker fill 转为核心内部成交事实。
4. 校验成交数量、订单身份和 instrument version。
5. 更新权威 `OrderState`、`UserState` 和可快照的 `BookState`。
6. 提供状态哈希和重建验证。

Adapter 中禁止：

- 查询 PostgreSQL。
- 调用远程 instrument 或 mark price HTTP 接口。
- 读取 Valkey。
- 自行生成 wall-clock 时间。
- 写 Kafka 或本地 outbox。
- 在副本间使用不同线程调度决定业务顺序。

第一版 Snapshot 保存足以精确重建价格时间优先级的开放订单序列。运行中的三个 Member 使用 Exchange Core
`StateHashReportQuery` 的 `MATCHING_ORDER_BOOKS` 子模块检查执行器一致性；Snapshot 恢复使用规范化
`BookState hash` 检查逻辑订单簿一致性。Exchange Core 0.5.3 的内部订单簿 hash 包含已成交历史字段，不能把
“剩余开放订单重建后的内部 hash 不同”误判为逻辑订单簿不一致。只有实测恢复不达标时，才实现内存版
`ISerializationProcessor` 把 Exchange Core 原生序列化片段嵌入 Aeron Snapshot；不启用其本地磁盘 journal。

### 7.5 Risk State

Risk State 包含：

- 当前 instrument 风险参数版本。
- 最新已提交标记价及其 source sequence。
- 用户权益、未实现 PnL、维持保证金、风险率和风险状态。
- symbol 到受影响用户的确定性索引。
- 待处理风险重算游标，避免一次标记价命令无限执行。

标记价更新采用可分片、可续跑的确定性命令：`ApplyMarkPrice` 更新价格并创建扫描任务，后续
`ContinueRiskScan` 按稳定 userId 顺序处理有界批次。批次数量必须可配置，并通过强平风暴压测确定。

### 7.6 Liquidation State

强平状态机至少包含：

```text
DETECTED
  -> CANCELING_OPEN_ORDERS
  -> RECALCULATING
  -> SUBMITTING_LIQUIDATION_ORDER
  -> MATCHING
  -> SETTLING
  -> COVERING_DEFICIT
  -> COMPLETED | ADL_REQUIRED | FAILED_SAFE
```

每个阶段由 Aeron Log 中的命令驱动，并保存已完成步骤和幂等 ID。强平单直接走同一 Exchange Core
Adapter，不再经过 Redis candidate queue、线程池、Kafka 回环或 PostgreSQL sequence 热路径。

保险基金与 ADL 第一版保持外围业务模块，但边界调整为：

- Aeron 导出不可变的 `InsuranceCoverageRequired` 或 `AdlRequired` 事件。
- 外围模块完成审计决策后，以稳定 `commandId` 提交 Aeron 资金命令。
- 最终用户资金变化仍只在 Aeron 核心执行。
- 后续若外围往返成为正确性或性能瓶颈，再单独 ADR 决定是否内聚，不能在本次迁移中暗中扩大范围。

## 8. 命令与事件协议

### 8.1 协议要求

新增独立模块建议命名为 `surprising-aeron-core`，至少拆分：

```text
surprising-aeron-core/
  surprising-aeron-protocol
  surprising-aeron-service
  surprising-aeron-client
  surprising-aeron-exporter
  surprising-aeron-tools
```

协议使用 SBE 或等价的固定二进制 schema。正式选择前必须做一个含版本演进、未知字段和回放兼容的
小型验证。禁止以 Java 原生序列化或无版本 JSON 作为 Cluster Log 权威协议。

每个命令头必须包含：

- `schemaVersion`
- `commandType`
- `commandId`
- `productLine`
- `source`
- `sourceId`，标识 Gateway client agent、Kafka partition 或其他稳定来源实例
- `sourceSequence`
- `userId` 或明确的系统作用域
- `submittedAtEpochMillis`，只作为确定性输入，不由 Member 本地生成
- `correlationId`

### 8.2 首批命令集合

| 类别 | 命令 |
| --- | --- |
| Instrument | `UpsertInstrument`、`ChangeInstrumentStatus`、`BeginLifecycleDrain` |
| 用户资金 | `AdjustBalance`、`TransferIn`、`TransferOut` |
| 订单 | `PlaceOrder`、`CancelOrder`、`ReplaceOrder`、`CancelAllOrders` |
| 行情风险 | `ApplyMarkPrice`、`ContinueRiskScan` |
| 资金费 | `ApplyFundingRate`、`ContinueFundingSettlement` |
| 交割 | `ApplyDeliveryPrice`、`ContinueDeliverySettlement` |
| 期权 | `ApplyExercisePrice`、`ContinueOptionExercise` |
| 强平 | `StartLiquidation`、`ContinueLiquidation` |
| 外围回执 | `ApplyInsuranceCoverage`、`ApplyAdlSettlement` |
| 导出 | `AckExportSequence` |
| 运维 | `RequestSnapshot`、`VerifyStateHash`、`EnterDrainMode`、`ExitDrainMode` |

所有批处理命令必须有最大工作量和确定性续跑游标，禁止单条命令遍历无限用户集合。

### 8.3 响应语义

客户端只在命令已被 Cluster 提交并执行后收到业务响应。超时代表“结果未知”，不是失败；客户端必须
使用同一 `commandId` 查询或重试。Cluster 中保存有界 `CommandResultState`：

- 活跃幂等窗口内返回完整原结果。
- 超出窗口的命令可由 PostgreSQL 审计投影查询，但不得再次执行状态变化。
- 幂等窗口大小和保留时间通过容量测试确定，Snapshot 必须保存窗口内状态。

## 9. Kafka、PostgreSQL 与 Valkey边界

### 9.1 Kafka 保留范围

Kafka继续承担：

- API 外围入口缓冲，仅用于不要求同步响应的系统命令。
- instrument、标记价、资金费、交割价和行权价等外部输入。
- Aeron 对外业务事件。
- PostgreSQL 投影、WebSocket、K 线、通知、审计、报表和数据仓库。

普通同步下单优先由 Gateway 使用 Aeron Cluster Client 直连，以减少一次 Kafka 往返。Kafka Input
Bridge 仍必须存在，用于外部系统事件和需要缓冲的批处理输入。

### 9.2 Kafka Input Bridge

Bridge 的提交规则：

1. 校验 Topic、key、产品线和 schema。
2. 生成稳定 `commandId = sourceTopic/sourcePartition/sourceOffset`，或验证上游提供的业务 ID。
3. 提交到对应产品线 Aeron Cluster。
4. 收到 committed response 后才提交 Kafka offset。
5. 重启或 rebalance 后重复提交由 Aeron 幂等状态吸收。

Bridge 不保存独立业务状态，不成为权威恢复源。

### 9.3 Reliable Exporter

Cluster 内的 `ExportState` 保存：

- 单调 `nextExportSequence`。
- 有界待导出事件队列。
- 最后确认的 `ackedExportSequence`。
- 每个事件稳定 `eventId`、event type、key、schema version 和 payload。

Exporter 流程：

1. 从 Leader 查询 `ackedExportSequence + 1` 开始的批次。
2. 按事件指定 Topic/key 写 Kafka。
3. 等待 Kafka producer ack。
4. 向 Aeron 提交 `AckExportSequence`。
5. Cluster 只清理连续确认区间。

Kafka 写成功但 Aeron ack 丢失时允许重复导出。所有消费者必须以 `eventId` 或业务唯一键幂等。
PostgreSQL 表必须有相应唯一约束。不能声称跨 Aeron 和 Kafka 做到了分布式 exactly-once。

P5 实现使用 `1,000,000` 条和 `64 MiB` 双重硬上限，任一达到即在业务 reducer 前返回
`EXPORT_BACKLOG_FULL`，调用方必须重试，Kafka Input Bridge 不得提交 offset。第一版不在积压极限下区分
普通新开仓与撤单/强平，避免给同一条确定性出口再增加旁路；Exporter 恢复或运维 Drain 后统一恢复。
预警水位与生产告警阈值仍由 P9 单产品线实测确定，但不能高于这两个硬上限。

### 9.4 PostgreSQL

PostgreSQL 保留：

- 订单、成交、余额、持仓、账务、风险和强平审计投影。
- 后台查询和历史报表。
- 资金守恒与账账核对。
- 跨产品线划转、wallet 和外围 Saga 审计。

PostgreSQL 不得参与正常下单、撮合、结算、风险和强平同步裁决。投影故障允许查询延迟，但不允许
核心状态回退到数据库版本。

### 9.5 Valkey

Valkey只保留：

- Gateway 限流。
- 查询缓存。
- WebSocket 会话和非权威协调。
- 可完全由 Kafka/Aeron 事件重建的索引。

删除 Redis Risk State、Liquidation Candidate Queue 以及任何“Redis 丢失会导致资金状态无法恢复”的实现。

## 10. Snapshot、Archive 与故障恢复

### 10.1 Snapshot 内容

每个 Snapshot 必须包含：

- schema version、product line、Cluster log position 和 logical time。
- 全部 instrument 及版本状态。
- User State、Order State 和幂等结果窗口。
- 可精确恢复时间优先级的开放订单簿状态。
- Risk State、风险扫描游标。
- Liquidation State、资金费/交割/行权批处理游标。
- Export State 和待确认事件。
- 分域 hash 和全局 hash。

Snapshot 写入必须使用 Aeron Cluster snapshot 机制。禁止再写一套“更权威”的 RocksDB 或本地 JSON
快照。可为了诊断导出只读状态，但诊断文件不能参与自动恢复。

### 10.2 Snapshot 触发

同时支持：

- 按已提交命令数量触发。
- 按时间触发。
- 运维命令手动触发。
- Drain 或版本升级前触发。

默认值在恢复基准测试后确定。验收目标不是“快照越频繁越好”，而是在运行抖动、Snapshot 大小、
Archive replay 长度和恢复时间之间取得可测量平衡。

### 10.3 恢复流程

```text
启动 Member
 -> 加载最新有效 Aeron Snapshot
 -> 校验 schema/productLine/checksum
 -> 重建 Exchange Core books
 -> 校验 Exchange Core book hash
 -> 从 Archive replay Snapshot position 之后的 Cluster Log
 -> 计算全局状态 hash
 -> 与其他 Member/期望 manifest 对比
 -> 加入服务
```

### 10.4 故障语义

| 故障 | 预期行为 |
| --- | --- |
| Leader 进程退出 | 剩余 Member 选主，已提交命令不丢失，客户端用同一 commandId 重试未知结果。 |
| Follower 退出 | Cluster 继续服务；Follower 从 Snapshot/Archive 恢复后重新加入。 |
| 单 Member 磁盘损坏 | 清理该 Member 独立数据目录，从 Cluster Backup 或其他 Member 重建；不得复制运行中的目录。 |
| Kafka 不可用 | 核心继续到 backlog 门限；撤单、强平优先；恢复后幂等导出。 |
| PostgreSQL 不可用 | 核心继续；投影积压告警；恢复后从 Kafka 重放。 |
| Valkey 不可用 | 限流/查询降级；核心资金和交易状态不受影响。 |
| Exporter 重启 | 从 Aeron ack cursor 继续；允许重复事件，消费者幂等。 |
| 输入 Bridge rebalance | Kafka offset 未提交的命令重复发送，由 commandId 幂等吸收。 |

### 10.5 RPO 和 RTO

- 已提交核心命令 RPO 必须为 `0`。
- Kafka/PG 投影允许短时延迟，但恢复后最终 lag 必须为 `0`。
- RTO 分别测量 Leader 切换、Follower rejoin、全 Cluster 冷启动和单产品线完整恢复。
- 具体生产 RTO 门槛在部署硬件上实测后写回本文档，不能以开发机结果代替。

## 11. 六产品线策略

共享执行框架通过 `ProductLineStrategy` 调用产品线纯计算，不使用大段散落的 `if/else`。建议接口：

```text
ProductLineStrategy
  validateInstrument
  reserveForOrder
  settleTrade
  calculateUnrealizedPnl
  calculateInitialMargin
  calculateMaintenanceMargin
  calculateLiquidationPrice
  applyFunding
  applyLifecycleSettlement
```

### 11.1 现货

- BUY 按最坏成交价和手续费冻结计价资产。
- SELL 冻结基础资产。
- 成交原子更新 maker/taker 双方资产和订单余量。
- 市价保护仍使用确定性参考价输入。
- 不创建衍生品持仓，不进入 Risk/强平流程。

### 11.2 U 本位永续

- 使用 USDT 等计价/结算资产。
- 线性 PnL 和线性保证金。
- 标记价命令触发风险重算。
- 资金费按稳定批次游标结算。
- 强平、保险缺口和 ADL 事件完整验证。

### 11.3 币本位永续

- 使用基础币作为保证金和结算资产。
- 使用现有 `PerpetualContractMath` 的反向合约公式。
- 下单预占按价格下边界计算最坏保证金。
- 资金费、PnL、手续费和强平费全部验证资产单位，禁止套用 U 本位值。

### 11.4 U 本位交割

- 基础交易链与 U 本位永续一致，但无资金费。
- 到期前进入 reduce-only/drain。
- 使用命令中不可变交割价分批结算。
- 完成后开放订单、预占和持仓必须归零。

### 11.5 币本位交割

- 基础交易链与币本位永续一致，但无资金费。
- 到期使用反向合约交割数学，结果进入基础币余额。
- 交割后持仓、保证金和预占全部清零。

### 11.6 期权

- 第一版继续现金结算欧式期权。
- 买方成交扣权利金，卖方冻结保证金。
- 到期价作为不可变 Aeron 命令输入。
- CALL/PUT payoff 使用整数定点数学。
- 到期行权或失效后持仓、卖方保证金和未完成订单全部清理。

## 12. 代码改造清单

### 12.1 新增模块

| 模块 | 职责 |
| --- | --- |
| `surprising-aeron-protocol` | SBE schema、命令/响应/查询模型、版本兼容测试。 |
| `surprising-aeron-service` | Clustered Service、统一状态、Dispatcher、Snapshot、状态哈希。 |
| `surprising-aeron-client` | Gateway、内部服务和测试使用的 Leader 感知客户端。 |
| `surprising-aeron-exporter` | Aeron Export State 到 Kafka 的可靠导出和 ack。 |
| `surprising-aeron-tools` | 离线 replay、状态 hash、Snapshot 检查和压测 client。 |

如果实施中证明五个 Maven module 造成不必要依赖复杂度，可合并为三个，但 protocol 必须保持独立，
service 与 client 不得循环依赖。任何合并都要记录在第 19 节。

### 12.2 复用并抽取纯业务逻辑

- 从 `AccountUserStateReducer` 抽取不依赖 Spring、Kafka、RocksDB 和 repository 的资金 reducer。
- 复用 `RiskMath`。
- 复用 `PerpetualContractMath`。
- 复用 `LiquidationPriceCalculator`。
- 复用 `LiquidationSizingPolicy`。
- 复用 `LiquidationSideResolver`。
- 复用交割、期权、Insurance 和 ADL 的纯数学；I/O orchestration 不直接搬入核心。

### 12.3 Exchange Core

重点修改：

- `ExchangeCoreEngine`：去掉数据库恢复依赖和本地状态职责，改为 Aeron Adapter。
- `MatchingService`：去掉本地时间、外部查询和本地 outbox。
- `MatchingPersistenceService`：由 Aeron State 更新和 Kafka 投影取代。
- `MatchingLocalStateStore`：迁移完成后删除权威职责并删除类。
- `MatchingLocalOutboxPublisher`：由统一 Exporter 取代。
- `MatchingPartitionAssignmentGuard`：Kafka assignment fencing 由 Aeron Leader/Cluster session 语义取代。

### 12.4 User、Order 和 Account

- Gateway/Order API 在验证、鉴权后提交 Aeron 命令。
- `OrderUserStateService` 和 `AccountUserStateReducer` 的纯业务部分迁入统一状态机。
- PostgreSQL repository 改为事件投影消费者，不再被核心命令调用。
- 订单和账户查询明确区分 `CORE_STRONG` 与 `PROJECTED_EVENTUAL`，用户交易确认优先读 Aeron query。

### 12.5 Risk 和强平

- `RiskService` 改为核心内部 reducer 或删除外部 orchestration。
- `RedisRiskStateStore`、`RedisRiskCalculator` 中只有纯计算可保留，Redis 状态类删除。
- `MarkPriceRiskTrigger` 改为 `ApplyMarkPrice` 命令入口。
- `RedisLiquidationCandidateQueue` 删除。
- `LiquidationCandidateQueueProcessor` 和专用线程池删除。
- `LiquidationOrderPersistenceService` 不再决定 sequence 或提交核心强平单；保留的审计逻辑改为投影。

### 12.6 Funding、Delivery、Option、Insurance 和 ADL

- Funding rate、delivery price、exercise price 经 Bridge 进入 Aeron。
- 用户资金结算由有界批处理命令在核心完成。
- Insurance/ADL 第一版保留外围审计模块，但任何用户资金变化通过 Aeron 命令完成。
- 删除重复的数据库命令结果轮询和本地资金执行权威。

## 13. 旧链路删除清单

删除发生在阶段 P6，新核心功能、资金、Snapshot/Replay 门禁通过之后，性能压测之前。

必须删除：

- `UserPartitionWal`。
- `UserPartitionResultStore`。
- `UserPartitionStateStore` 作为权威恢复存储的职责。
- `user.mutations.v1` 作为旧 reducer 双写迁移入口的逻辑。
- `user.state.changelog.v1` 作为权威状态恢复链的逻辑。
- `OrderWalConfiguration`、Account WAL 配置和相关 Bean。
- `MatchingLocalStateStore`、per-shard checkpoint 和本地 outbox。
- `MatchingLocalOutboxPublisher`。
- PostgreSQL 开放订单重建 Exchange Core 的正常恢复路径；可保留独立审计检查工具，不得自动裁决。
- `RedisRiskStateStore` 和 Risk projection lease。
- `RedisLiquidationCandidateQueue`。
- `LiquidationCandidateQueueProcessor` 和强平线程池。
- PostgreSQL 强平订单 sequence、锁、事务和 outbox 的核心同步职责。
- 已无消费者的旧 Topic、配置键、健康检查、指标、测试和文档。

删除门禁：

1. 全仓 `rg` 不再发现生产代码引用旧类。
2. 全仓 Maven 测试通过。
3. 六线至少完成核心策略单测；当前被验收产品线完成全链路功能和资金门禁。
4. Snapshot/Replay 和 Leader kill 测试通过。
5. 部署清单不再启动旧 Risk/强平权威 provider。

不保留 `legacy.enabled`、`dual-write.enabled` 或运行时回退开关。Git 历史已经提供代码级回溯能力。

## 14. 测试金字塔

### 14.1 协议和纯计算测试

- SBE encode/decode golden files。
- schema 前向/后向兼容。
- 六线资金、PnL、保证金和生命周期边界表格测试。
- 最大 long、舍入、负数、零价格、极小价格和溢出测试。
- commandId、tradeId 和 eventId 幂等测试。

### 14.2 单 Member 确定性测试

- 同一命令序列运行三次，状态 hash 完全相同。
- 不同 JVM 启动顺序、GC 和线程调度不影响结果。
- 任意命令失败不留下半更新状态。
- 批处理暂停和续跑后结果与一次性数学基准一致。

### 14.3 三节点 Cluster 测试

- Leader kill、Follower kill、Follower rejoin。
- 客户端超时后同 commandId 重试。
- Snapshot 期间持续交易。
- Snapshot 后 Archive replay。
- 全 Cluster 冷启动。
- 单 Member 空目录重建。
- Member 状态 hash 一致。

### 14.4 Exporter 和投影测试

- Kafka 写成功、ack 前 Exporter kill。
- Kafka 写失败和超时。
- Kafka 恢复后顺序追平。
- PostgreSQL 投影重复事件幂等。
- WebSocket/K 线重复事件不产生错误累计。
- backlog 警告、拒单和 Drain 水位行为。

### 14.5 六产品线全链路测试

每条线独立执行：

- instrument 创建、启用、版本和关闭。
- API 下单、撤单、改单、IOC/FOK/GTX、市价保护。
- maker/taker 成交、部分成交和多次成交。
- 用户和做市账户资金、手续费、冻结和释放。
- 对应产品线持仓和 PnL。
- 永续资金费。
- 衍生品风险和强平。
- 交割持仓归零。
- 期权权利金、行权和失效。
- Snapshot/Replay 后重复完整核对。

必须补齐：

- `run-product-line-recovery-matrix.sh` 对 `INVERSE_PERPETUAL` 和 `INVERSE_DELIVERY` 的支持。
- `product-line-api-flow-smoke.sh` 币本位 instrument、价格、资产和完整生命周期。
- `product-line-funds-reconcile.sh` 默认六线范围。
- 币本位专用部署 Runbook。

## 15. 功能和资金前置门禁

任何性能压测开始前，当前被压产品线必须通过本节全部门禁。门禁失败时禁止“先压一下看看”。

### 15.1 功能门禁

- [ ] 所有相关 Maven 单元和组件测试通过。
- [ ] 三节点 Aeron Cluster 健康，状态 hash 一致。
- [ ] Gateway 鉴权、限流和 Aeron Client 路由正确。
- [ ] 下单、撤单、改单和订单查询正常。
- [ ] 撮合、部分成交、完全成交和订单簿正常。
- [ ] 余额、冻结、持仓和账务查询正常。
- [ ] WebSocket 公共和私有事件正常。
- [ ] 当前产品线特殊功能全部通过。
- [ ] Snapshot、Replay、Leader kill、Exporter 重启通过。
- [ ] Kafka、PG、Valkey故障降级符合设计。
- [ ] 无旧权威链路在运行。

### 15.2 资金门禁

- [ ] 用户期初余额与注入流水一致。
- [ ] 做市账户期初余额与注入流水一致。
- [ ] 订单预占和释放逐笔一致。
- [ ] maker/taker 成交数量和资产/PnL 双边一致。
- [ ] 手续费总额与用户扣减、平台收入一致。
- [ ] 资金费付款和收款加平台舍入差为零。
- [ ] 强平费、保险基金和 deficit 一致。
- [ ] 交割或行权的双边总额一致。
- [ ] 期末余额可由期初和全部 ledger 推导。
- [ ] Aeron 权威状态与 PostgreSQL 投影逐用户、逐资产、逐持仓一致。
- [ ] 重启/重放前后资金 hash 一致。
- [ ] 资金差异、重复扣款、重复结算均为 `0`。

门禁证据必须写入测试报告目录，并把报告 URI、Git SHA、配置 manifest 和结论登记在第 18 节。原始
大文件不提交仓库，可保存在 CI artifact 或对象存储。

## 16. 单产品线性能压测方案

### 16.1 强制隔离原则

- 每次只启动和压测一条产品线。
- 不用六线并发压测推导单线能力。
- 测试前清理当前产品线 Aeron data、Kafka测试 Topic 和 PostgreSQL 测试数据，目标必须精确校验。
- 其他五条产品线核心不得占用测试节点 CPU、磁盘和网络。
- 做市进程必须持续运行，真实 API 用户通过 Gateway 产生流量。
- 压测结束后等待 Exporter 和所有投影 lag 清零，再执行最终资金核对。

### 16.2 压测顺序

每条产品线按以下顺序执行，前一项失败不得继续：

1. `functional-gate`：第 15 节全部通过。
2. `warmup`：稳定订单簿、JIT 和缓存，不计入最终结果。
3. `baseline`：逐级提高吞吐，确定无错误稳定基线。
4. `capacity-step`：阶梯增长直到首个 SLO 失败点。
5. `hot1`：80% 流量集中单 symbol。
6. `hot3`：80% 流量集中三个 symbol。
7. `match-heavy`：高可成交比例。
8. `cancel-heavy`：高撤单和改单比例。
9. `burst`：目标两倍短时冲击。
10. `soak`：目标负载持续至少 60 分钟。
11. 衍生品执行 `liquidation-storm`；交割/期权执行生命周期批处理压力。
12. 压测后 Snapshot、冷恢复和最终资金核对。

### 16.3 必报指标

核心指标：

- Aeron committed commands/s。
- successful/rejected commands/s。
- Exchange Core commands/s 和 matches/s。
- risk evaluations/s。
- liquidation decisions/s 和 completed liquidations/s。
- Snapshot 时长、大小、期间吞吐抖动。
- Archive replay commands/s 和完整恢复时间。

端到端指标：

- Gateway accepted ops/s。
- trades/s。
- HTTP p50/p95/p99/p99.9/max。
- accepted 到 match result 延迟。
- trade 到 Aeron 余额/持仓可见延迟。
- trade 到 Kafka、WebSocket 和 PostgreSQL 投影可见延迟。
- Export backlog、Kafka lag、PG projection lag。
- CPU、内存、GC、磁盘、网络和 Aeron counters。
- 错误率、超时率、重试率和幂等命中率。

正确性指标：

- 订单不变量错误数。
- 资金差异。
- 重复扣款、重复结算、丢失事件。
- Member 状态 hash 差异。
- 最终 Export backlog 和投影 lag。

### 16.4 OPS 结论格式

最终报告必须区分：

```text
core_committed_ops_per_sec
core_match_events_per_sec
gateway_accepted_ops_per_sec
end_to_end_settled_trades_per_sec
```

不得只报告 Exchange Core 的内部 benchmark。最大可用 OPS 定义为同时满足延迟、错误率、资源、恢复和
资金正确性门槛的最高稳定档位，不是进程尚未崩溃时看到的瞬时峰值。

### 16.5 产品线压测优先级

推荐顺序：

1. `LINEAR_PERPETUAL`：覆盖交易、资金费、风险、强平、保险和 ADL，作为完整骨架基线。
2. `SPOT`：验证双资产高吞吐路径。
3. `INVERSE_PERPETUAL`：验证反向数学和基础币资产热点。
4. `LINEAR_DELIVERY`：验证生命周期结算。
5. `INVERSE_DELIVERY`：验证反向交割。
6. `OPTION`：验证权利金、卖方风险和批量行权。

每条线单独形成容量报告，不能用 U 本位永续结果替代其他产品线。

## 17. 部署方案

### 17.1 本地开发环境

当前 `/Users/a123/docker/aeron-cluster/compose.yaml` 运行的是 Aeron 官方
`BasicAuctionClusteredServiceNode`，使用 JDK 21，不能直接承载本项目。

本地需要新增项目 Compose：

- 基于 JDK 25 构建交易 Cluster 镜像。
- 每次只启动一个产品线的三个 Member。
- 每个 Member 独立 Aeron、Archive、Consensus 和 Snapshot 目录。
- Kafka 使用 `127.0.0.1:9092`。
- PostgreSQL 使用 `127.0.0.1:5432`。
- Valkey使用 `127.0.0.1:6379`。
- 提供 `make start-product-line-cluster PRODUCT_LINE=...` 等等价脚本。

本机 Apple M1 Pro、16GB 内存只用于功能、资金和小规模恢复验证，不用于生产容量结论。

### 17.2 生产三机部署

三台核心服务器 A/B/C，每台运行六个 Member，每条产品线在三台服务器各一个：

```text
Server A: spot-0, linear-perp-0, inverse-perp-0, linear-delivery-0, inverse-delivery-0, option-0
Server B: spot-1, linear-perp-1, inverse-perp-1, linear-delivery-1, inverse-delivery-1, option-1
Server C: spot-2, linear-perp-2, inverse-perp-2, linear-delivery-2, inverse-delivery-2, option-2
```

要求：

- 每台物理机使用独立 NVMe，产品线使用独立目录和 I/O 预算。
- Media Driver、Archive、Consensus Module 和 Clustered Service 同机部署。
- Aeron UDP 网络与公共 API 网络隔离。
- CPU pinning、busy spin 和 IRQ 调优只能在压测证据支持后启用。
- Member 数据目录不能放 NFS 或共享卷。
- 第四台独立服务器运行 Cluster Backup，备份六条产品线，不参与共识。
- Kafka、PostgreSQL、Valkey不与核心 Member 混部。

### 17.3 配置隔离

每个实例至少独立：

- `PRODUCT_LINE`
- `AERON_CLUSTER_ID`
- member id
- ingress/consensus/log/transfer/archive channel 和端口
- Aeron directory、archive directory、cluster directory
- Snapshot directory
- exporter client id 和 Kafka producer transactional/idempotent 配置
- JVM heap、GC、CPU affinity 和日志路径

启动脚本必须在进程启动前检查跨产品线端口和目录冲突。

### 17.4 监控和告警

必须采集：

- Cluster role、leadership term、commit position、append position。
- Member 间 position 差距。
- Aeron errors、back pressure、NAKs、retransmits。
- Archive recording position 和磁盘剩余空间。
- command latency、result unknown、幂等命中。
- Export backlog 和 ack position。
- Snapshot 时长、大小、失败次数。
- Risk scan backlog、Liquidation 状态分布。
- Kafka/PG 投影 lag。
- 资金对账最后成功时间和差异值。

资金差异非零、Member hash 不一致、Archive 停止录制、磁盘不足和 Export 极限水位必须触发最高级告警。

## 18. 分阶段实施台账

状态只允许：`NOT_STARTED`、`IN_PROGRESS`、`BLOCKED`、`DONE`。

| 阶段 | 状态 | 目标 | 完成证据 | 完成提交 |
| --- | --- | --- | --- | --- |
| P0 | `DONE` | 方案冻结、基线和垃圾文件清理 | 本文档、ADR、术语表；基线模块测试通过 | `e4917e3` |
| P1 | `DONE` | Aeron 协议、三节点骨架和工具 | schema v1 golden；Snapshot/幂等测试；三节点 Leader kill 后 hash 连续 | `6bd1cb3` |
| P2 | `DONE` | User/Order State 和资金预占 | 订单/账户单测、六线规则、幂等、资金不变量和 Snapshot v2 | `20d5bbd` |
| P3 | `DONE` | Exchange Core Adapter 和 Book State | 六线撮合组件测试；SPOT 三节点成交、Leader kill、冷恢复资金守恒 | `e206eee` |
| P4 | `DONE` | Risk、强平和生命周期进入核心 | 35 个 service 测试；SPOT、线性永续三节点恢复；资金守恒 | `cb525dc` |
| P5 | `DONE` | Snapshot、Replay、Exporter 和投影 | SPOT Leader/Follower kill、冷恢复、Exporter 故障、Kafka/PG 幂等投影 | `本 P5 阶段提交` |
| P6 | `IN_PROGRESS` | 删除旧 WAL、Redis Risk 和旧强平链 | ADL、Order 子阶段已通过；全仓引用仍未清零 | `65769e6` |
| P7 | `NOT_STARTED` | 补齐六条产品线 | 六线 smoke、恢复、资金核对 | `scripts/run-six-product-line-gates.sh` |
| P8 | `NOT_STARTED` | 单产品线功能和资金正式验收 | 第 15 节门禁报告 | `scripts/run-six-product-line-gates.sh` |
| P9 | `NOT_STARTED` | 单产品线性能和故障容量测试 | 六份独立容量报告 | `scripts/run-uncapped-aeron-capacity.sh` |
| P10 | `NOT_STARTED` | 生产部署与 Runbook 冻结 | 三机演练、Backup 恢复、值班手册 | 待填写 |

### 18.1 P0：方案冻结与基线固化

任务：

- [x] 拉取并记录最新 `master` 基线。
- [x] 使用 JDK 25 运行核心 15 模块测试，结果 `BUILD SUCCESS`。
- [x] 确认当前 Aeron Docker 是教程 Cluster，不能直接复用。
- [x] 确认六条产品线和当前币本位测试缺口。
- [x] 冻结 Kafka、PostgreSQL、Valkey与 Aeron 权威边界。
- [x] 冻结“不长期双写、不建影子集群”的迁移策略。
- [x] 编写主方案、ADR 和术语表。
- [x] 提交并推送垃圾文件清理与本方案。
- [x] 创建 `codex/aeron-unified-core` 分支。

阶段出口：文档已审阅，基线清洁，分支创建，所有后续实现引用本方案。

### 18.2 P1：协议和 Cluster 骨架

任务：

- [x] 父 POM 引入 Aeron `1.52.2` 和五个新模块。
- [x] 冻结固定小端二进制 schema version 1，并完成 header 扩展兼容验证。
- [x] 实现 command header、response、query 和 export event envelope 边界。
- [x] 实现单产品线 `ClusteredService` 确定性探针状态、幂等窗口和 Snapshot 骨架。
- [x] 实现 JDK 25 三节点镜像和按单产品线启动的 Compose。
- [x] 实现 Gateway/测试 client 的 Leader 自动发现、切换回调和结果未知语义。
- [x] 实现状态 hash 查询和离线 replay 工具骨架。
- [x] 添加协议 golden、未知 header 扩展、六线隔离、幂等高水位和 Snapshot 测试。

实际修改：

- 新增 `surprising-aeron-core` 聚合模块及 protocol、service、client、exporter、tools 五个子模块。
- `CoreMessageCodec` 固定 76 字节 v1 header；`sourceId` 与 `sourceSequence` 共同界定来源顺序。
- `CoreProbeState` 只用于 P1 复制状态验证，保存有界完整结果和不淘汰的来源序列高水位。
- `SurprisingClusterNode` 在同一 JVM 启动 Media Driver、Archive、Consensus Module 和 Service。
- `ProductLineClusterLayout` 给六条线分配互不重叠的 `clusterId` 与端口空间。
- `SurprisingAeronClient` 使用 Aeron ingress endpoint 列表自动发现 Leader；超时抛出
  `ResultUnknownException`，不伪装成业务失败。
- `compose.yaml` 和 `scripts/aeron-core-local.sh` 一次只启动一条产品线，`down` 不删除数据卷。

验收证据（2026-08-13，JDK `25.0.4`，Aeron `1.52.2`）：

- Maven：protocol 4 个测试、service 4 个测试、tools 2 个测试，以及依赖的 product-api 18 个测试全部通过。
- 本机三节点：首命令 `appliedCommandCount=1`；杀死实际 Leader `node1` 后第二命令为
  `appliedCommandCount=2`，查询得到相同 `stateHash=928a62b8bace0684`。
- Docker JDK 25 三节点：三个容器均为 `Up`，首命令成功；杀 Leader 后选举窗口内首次客户端提交
  超时且查询证明命令未执行，稳定后重试成功为 `appliedCommandCount=2`，查询 hash 同为
  `99aa20509fd8695f`。
- Docker Desktop daemon 未继承终端 Clash 代理，官方 JRE 25 镜像首次拉取超时；验证时由宿主机通过
  Clash 下载官方 Temurin `25.0.4+7`，覆盖本地已有 Temurin Linux 基础层。仓库 Dockerfile 仍引用
  官方 `eclipse-temurin:25-jre`，生产构建不依赖本地临时镜像。

已知边界：

- Docker Compose 使用临时容器 DNS 时，故障节点容器被删除后其主机名会短暂不可解析；生产三机必须使用
  固定 DNS/IP。客户端把这段选举窗口报告为结果未知，调用方必须复用 `commandId`。
- P1 Snapshot 只保存探针状态，不代表 P2–P5 的 User/Order/Book/Risk/Liquidation Snapshot 已完成。
- Exporter 模块当前只有协议和 sink 边界，可靠发布、ack 与积压恢复在 P5 实现。

阶段出口：三节点可提交测试命令、Leader 切换后状态一致，协议可版本化。

### 18.3 P2：User/Order State

任务：

- [x] 定义 User、Balance、Reservation、Position、Order 状态结构。
- [x] 抽取账户和订单纯 reducer。
- [x] 实现余额调整、下单预占、撤单释放和幂等结果。
- [x] 实现现货双资产预占和衍生品保证金预占。
- [x] 实现强一致核心查询。
- [x] 建立每命令不变量检查和 debug hash。

实际修改：

- 新增固定小端 `ADJUST_BALANCE`、`PLACE_ORDER`、`CANCEL_ORDER` payload，显式携带
  `instrumentVersion`、`baseAsset`、`quoteAsset`、`settleAsset` 和预占资产，不从 symbol 字符串猜资产。
- 新增 `TradingCoreState`、`CoreUserState`、`CoreOrderState`、`AssetBalance`、
  `OrderReservation` 和 `CorePositionState`。Map 使用稳定排序的不可变副本，资金使用 `long` 定点单位和
  `Math.*Exact`。
- `TradingCoreReducer` 原子完成余额调整、资金预占、订单插入、撤单与剩余预占释放。失败不发布半状态；
  终态重复撤单直接返回原状态，不重复释放。
- 现货 BUY 只能预占 quote asset，SELL 只能预占 base asset；五条衍生品线统一要求
  `DERIVATIVE_MARGIN` 且预占 instrument settle asset。
- `CoreProbeState` 演进为包含正式 `TradingCoreState` 的顶层权威对象；业务拒绝也推进来源高水位并保存
  原始裁决。重复 `commandId` 返回 `DUPLICATE + commandStatus + resultCode`，可恢复原成功/拒绝及错误码。
- 新增完整 User/Order 强一致查询视图，以及 business/user/order 分域 hash；查询直接读取已提交核心状态。
- Snapshot 升级为 v2，保存 User/Order、幂等结果和来源高水位，仍可读取 P1 v1；写入按 Aeron
  `maxPayloadLength` 分块，恢复可组装多 fragment。

验收证据（2026-08-13，JDK `25.0.4`，Aeron `1.52.2`）：

- Maven：product-api 18、protocol 9、service 20、tools 2 个测试全部通过，client/exporter 编译通过。
- 资金不变量：充值总额、预占前后 `available + locked`、撤单全释放、重复撤单、余额不足、重复 orderId、
  整数溢出均通过 fail-closed 测试。
- 产品线规则：`SPOT` 买卖双资产规则和五条衍生品线 margin/settle asset 规则全部通过参数化测试；反向
  永续和反向交割使用基础结算币，不套用 U 本位资产。
- 幂等与恢复：成功和拒绝命令均保存原始裁决；同 `commandId` 重试不重复扣款；Snapshot round-trip 后
  顶层 hash、业务 hash、User/Order 状态和去重结果一致。
- 强一致查询：User 查询返回余额、预占和持仓视图，Order 查询返回订单状态；截断和尾随字节均拒绝。
- Docker 三节点 SPOT：固定 seed 执行充值 `10000`、预占 `2500`、查询、撤单，结果
  `fundsSmoke=PASS totalUnits=10000 lockedUnits=0`；三节点全部停止且保留卷后重启，同一 userId 只读验证为
  `fundsRecovery=PASS totalUnits=10000 lockedUnits=0`。脚本含 Cluster 就绪门禁，避免选举窗口产生假失败。

阶段边界：

- P2 不接 Exchange Core，因此不处理 fill、position margin transfer 或成交结算；P3 与 maker/taker
  订单和资金原子应用。
- P2 对 `reduceOnly=true` fail-closed，拒绝码为 `REDUCE_ONLY_REQUIRES_POSITION_STATE`；P3 接入可验证的
  Position/Book State 后放开。
- Snapshot v2 已能恢复 P2 状态，但 P5 仍负责 checksum、manifest、Archive replay、冷恢复矩阵和
  Export State；P2 完成不代表生产恢复链已经验收。

阶段出口：不接撮合也能证明预占/释放/重复命令资金正确。

### 18.4 P3：Exchange Core Adapter

任务：

- [x] 将 Exchange Core 生命周期放进 Clustered Service。
- [x] 去除 Adapter 的数据库、HTTP、Valkey和 wall-clock 依赖。
- [x] 实现 place/cancel/replace 和 Exchange Core trade fill 映射。
- [x] 原子更新 maker/taker Order/User State。
- [x] 实现可快照 Book State 和恢复重建。
- [x] 使用 `StateHashReportQuery` 校验运行中执行器，并用 Book State hash 校验恢复重建。

实际修改：

- 新增 `DeterministicExchangeCoreAdapter`，在每个 Clustered Service Member 内启动单线程、无风险裁决、
  无 journal 的 Exchange Core 0.5.3；symbol/user ID 由稳定函数生成，不访问数据库、HTTP、Kafka、Valkey
  或 wall clock。
- 新增 `CoreBookState` 和 `CoreBookOrder`，保存开放订单剩余数量及严格单调的价格时间优先序；Snapshot
  升级为业务状态 v2，仍兼容读取 P2 v1 状态。
- `PLACE_ORDER`、`CANCEL_ORDER` 和新增 `REPLACE_ORDER` 同步驱动 Exchange Core；trade callback 先被
  规范化为 `CoreMatch`，再由 `TradingCoreReducer` 原子更新 maker/taker 订单、Reservation、Balance、
  Position 与 Book State。
- 现货成交原子执行 base/quote 双资产交换并逐资产保持总额守恒；衍生品开仓按已成交比例把订单预占转为
  Position Margin。撮合已改变但状态 reducer 拒绝时，Adapter 立即从提交前 Book State 重建，防止执行器
  与权威状态分叉。
- 状态内拒绝同用户自成交和同 symbol 不同 instrument version 混簿；BUY 的 Exchange Core
  `reservePrice` 使用最大 long，使资金和改单上限只由 Aeron Reservation 裁决，不产生第二资金裁决者。
- 新增 `ClusterSpotMatchSmokeMain` 和脚本入口，用固定 seed 对 seller/buyer 做真实成交、资金守恒和恢复查询。

验收证据（2026-08-13，JDK `25.0.4`，Aeron `1.52.2`，Exchange Core `0.5.3`）：

- Maven：product-api 18、protocol 9、service 28、tools 2 个测试全部通过；client 编译通过。
- 六线组件测试：SPOT 验证 maker/taker 双资产交换、部分成交、完全成交、总 BTC/USDT 守恒和 Snapshot
  重建；四条非期权衍生品线逐线验证双边成交、订单终态和 Position Margin 转移；OPTION 在权利金模型
  缺失时拒绝成交并回滚 Exchange Core，业务 hash、maker 订单和资金保持不变。
- replace 测试验证补充 Reservation、失去原价格时间优先级、成交和终态释放；Snapshot 恢复后继续吃掉
  剩余 maker，规范化 Book State hash 和最终开放订单集一致。
- Docker SPOT 三节点：固定 seed 真实成交后输出
  `spotMatchSmoke=PASS btcTotal=5 usdtTotal=500`；实际 Leader `node1` 停止后选出 `node2`，同 seed 查询输出
  `spotMatchRecovery=PASS`；三节点全停、保留卷、重启后再次输出相同恢复结果。项目容器已停止，卷保留。

阶段边界：

- P3 完成撮合生命周期、Book 恢复和现货资金结算；衍生品当前只验收同方向开仓和 Position Margin 转移。
  反向合约/平仓 PnL、手续费、期权权利金、reduce-only 容量和生命周期数学属于 P4，目前相关路径
  fail-closed，不用近似公式生成错误资金。
- P3 的 `StateHashReportQuery` 用于运行中 Member 的 Exchange Core 执行器一致性；Snapshot 恢复门禁使用
  规范化 Book State hash。Exchange Core 内部 hash 包含成交历史字段，不能替代规范化恢复 hash。
- P5 仍负责 Snapshot checksum/manifest、Archive replay 完整矩阵、Exporter 和 PostgreSQL 投影。

阶段出口：三节点撮合结果、订单簿 hash、资金和订单状态完全一致。

### 18.5 P4：Risk、强平和生命周期

任务：

- [x] 将 mark price 变为确定性命令。
- [x] 实现有界 Risk scan 和续跑游标。
- [x] 复用纯 Risk/强平数学。
- [x] 实现强平状态机和核心强平单。
- [x] 实现资金费批处理。
- [x] 实现线性/反向交割批处理。
- [x] 实现期权行权/失效批处理。
- [x] 接通 Insurance/ADL 外围事件和 Aeron 回执命令。

完成日期：`2026-08-13`

实际修改模块：

- `surprising-aeron-protocol`：新增 Instrument、Mark Price、Risk scan、Funding、Settlement、Liquidation
  命令及固定二进制编解码。
- `surprising-aeron-service`：新增 Instrument/Risk/Liquidation/Treasury State、纯整数合约数学，完成
  六线成交资金、主动平仓/反手、资金费、交割、期权和强平状态变更。
- `surprising-aeron-tools`：新增线性永续三节点 smoke，升级 SPOT smoke 以先写入权威 Instrument State。

验证命令与结果：

- JDK 25 执行 `mvn -pl surprising-aeron-core/surprising-aeron-tools -am test`：`BUILD SUCCESS`；
  product-api 18、protocol 9、instrument-api 11、service 35、tools 2 个测试全部通过。
- 单元资金证据：资金费双边净额为零；delivery/option 结算后持仓归零且资金守恒；重复 settlementId
  在业务层拒绝；强平亏空必须由精确 Insurance/ADL 回执覆盖；300 用户 Risk scan 分两批完成。
- SPOT 三节点固定 seed `4001`：成交后 `spotMatchSmoke=PASS btcTotal=5 usdtTotal=500`；实际 Leader
  `node2` 停止后选出 `node1`，恢复查询通过；全 Cluster 停止并保留 Archive 卷后冷启动，恢复查询再次通过。
- LINEAR_PERPETUAL 三节点固定 seed `5001`：双边开仓、Mark Price、Funding 后输出
  `derivativeSmoke=PASS usdtTotal=2000 fundingNet=0`；实际 Leader `node1` 停止后选出新 Leader，恢复输出
  `derivativeRecovery=PASS usdtTotal=2000 fundingNet=0`。

阶段边界：

- P4 已证明 Risk、强平、资金费和生命周期裁决不读取 Redis 或 PostgreSQL；外围 Insurance/ADL 仅能通过
  `ResolveLiquidationCommand` 将精确覆盖结果写回 Aeron。
- 强平未覆盖额只保存在 `CoreLiquidationState.deficitUnits`，不同时写 Treasury deficit，避免双重记账；
  Treasury 的负余额只用于用户现金效果的守恒对手项。
- Snapshot v3 是尚未发布分支上的最终 P4 schema，兼容已提交的 v1/v2；P5 将升级为带 checksum 和
  manifest 的正式 schema，并完成 Archive/Snapshot 恢复矩阵。
- P4 相关 8 模块测试全部通过。额外执行全仓 52 模块测试时，前 49 个模块通过，既有
  `MarketMakerServiceTest.runOnceSplitsQuoteBatchesAtTheOrderServiceLimit` 稳定失败：40 个报价请求实际按
  `20 + 10` 生成，但测试对每个批次都断言 20；该模块与本阶段无代码重叠，P6 全仓门禁时修正并重新全量验证。

阶段出口：六线策略测试通过，永续强平和生命周期结算不依赖 Redis/PG 裁决。

### 18.6 P5：Snapshot、Replay、Exporter

任务：

- [x] 完整 Snapshot schema 和 checksum。
- [x] Snapshot + Archive replay。
- [x] Leader/Follower/全 Cluster 恢复矩阵。
- [x] Export State 和 backlog 水位。
- [x] Kafka Exporter 和 ack 命令。
- [x] PostgreSQL 幂等投影。
- [x] Kafka Input Bridge。
- [x] 运维监控、health 和安全 Drain。

完成日期：2026-08-13

完成提交：本 P5 阶段提交

实际修改模块：

- `surprising-aeron-protocol`：新增 Export batch/status/ack、稳定 export sequence、版本化 Kafka input
  envelope、明确 `EXPORT_BACKLOG_FULL` 和 `EXPORT_ACK_AHEAD` 结果码；响应批次严格限制在 16 MiB 协议内。
- `surprising-aeron-service`：新增有序 `CoreExportState`；所有首次裁决（包括业务拒绝）进入审计事件，ACK
  不自反馈；队列使用 O(1) 头部删除、稳定增量 digest、`1,000,000` 条和 `64 MiB` 双硬上限。
- `CoreStateSnapshotCodec`：outer snapshot v3 保存 User/Order/Book/Risk/Liquidation/Treasury/Export 完整状态，
  CRC32C 先校验后解析，继续兼容 v1/v2；`SnapshotInspectMain` 输出产品线、schema、applied count、
  business hash、export cursor 和 checksum。
- `surprising-aeron-exporter`：新增可执行 `ExporterMain`、`ProjectionMain`、`InputBridgeMain`；Kafka producer
  强制 idempotence 和 `acks=all`；只有全批 publish 成功才提交 Aeron ACK；PG 以
  `(product_line, export_sequence)` 主键幂等投影；Kafka input 只有明确业务裁决才提交 offset。
- `ClusterExportSmokeMain` 与 `scripts/aeron-core-local.sh`：新增 status、故障注入和 bounded drain 工具。

验证命令：

- `mvn -pl :surprising-aeron-protocol,:surprising-aeron-service,:surprising-aeron-client,:surprising-aeron-exporter,:surprising-aeron-tools -am test`
- `PRODUCT_LINE=SPOT ./scripts/aeron-core-local.sh build|up|export-status|export-fail|export-drain`
- `docker stop surprising-aeron-spot-node0-1` 后重新执行 export status/drain。
- 停止 node2、继续提交命令、启动 node2 并追平；随后三节点全停保留卷冷启动。
- `ClusterTool <cluster-dir> snapshot`。
- `ExporterInfrastructureSmokeMain` 连接本机 Kafka `localhost:9092` 和 PostgreSQL `localhost:5432`。

验证结果：

- 单元/组件测试：product-api 18、protocol 12、instrument-api 11、service 40、exporter 6、tools 2，
  `BUILD SUCCESS`。
- Exporter 故障：真实三节点 backlog `ack=0,next=26,pending=25`；注入 sink 失败尝试发布 25 条后仍为
  `ack=0,pending=25`。
- Leader kill：停止 Leader node0 后新 Leader 返回同一 `ack=0,next=26,pending=25`；恢复 sink 后重发 25
  条，结果 `ack=25,pending=0`。
- Follower kill/rejoin：停止 node2 期间继续提交 1 条命令成功；node2 重入后 drain 2 条并达到
  `ack=27,pending=0`。
- Snapshot/冷恢复：Aeron `SNAPSHOT applied successfully`；三节点全停、保留卷重启后
  `ack=27,next=28,pending=0`，`appliedCommandCount=29`。
- Kafka/PG：真实发布到 `surprising.spot.core.events.v1`；同一事件投影两次，PG 最终 `pgRows=1`。

残留风险：

- 本阶段证据是 Apple M1 Pro 本地功能与恢复证据，不是生产容量结论。
- Kafka/PG 真实基础设施验证覆盖 SPOT；六产品线 topic、projection 和完整资金对账在 P7 逐线执行。
- Snapshot 命令与 Leader/Follower/冷启动矩阵已覆盖；单 Member 空目录重建和 Cluster Backup 属 P10
  服务器部署演练范围。

阶段出口：RPO 0，Exporter 可重复无重复资金效果，投影能追平。

### 18.7 P6：删除旧权威链路

任务：按第 13 节逐项删除生产类、配置、Topic、Bean、测试和旧文档描述。

执行记录（2026-08-13，子阶段 P6.1）：

- [x] 核心协议新增 `UPDATE_POSITION_MODE`、`ADJUST_POSITION_MARGIN` 和稳定结果码。
- [x] User State 保存 `ONE_WAY/HEDGE`，订单和持仓保存 `CROSS/ISOLATED`、`NET/LONG/SHORT`。
- [x] HEDGE 持仓以 `symbol + positionSide` 分离；模式与下单方向不一致时失败关闭。
- [x] 逐仓保证金增减在单条核心命令内同步移动 available/locked，账户总权益保持不变。
- [x] Risk、Funding、Lifecycle Settlement 和 Liquidation 逐持仓侧执行；isolated 风险只使用该侧保证金。
- [x] Trading Snapshot 升级 v4，v1/v2/v3 默认迁移为 `ONE_WAY/CROSS/NET`；强查询升级 v2 并兼容 v1。
- [x] 协议 12、核心服务 42、Product API 18、Instrument API 11 个测试通过。
- [x] Account 外部余额、产品余额、仓位模式、逐仓保证金命令和在线强查询直接接入 Aeron；不再经过 WAL/result waiter/JVM reducer。
- [x] Account 使用进程内固定 Aeron session 池（默认 4），连接延迟建立、故障后重连；Cluster 不可用时失败关闭且无旧链路回退。
- [x] Account provider 全依赖 `clean test` 122/122 通过；新增门面测试后相关 5/5 通过。
- [x] 保留原三节点 SPOT 卷以当前镜像重启，v3→v4 恢复后 `appliedCommandCount=29`、Export `ack=27,next=28,pending=0`。
- [x] 将固定并发 session、消息头、source sequence、延迟连接和故障重连抽到共享 `AeronClientPool`；业务模块只负责命令 payload、结果码和查询视图映射。
- [x] Aeron Order State 补齐 LIMIT/MARKET、GTC/IOC/FOK/GTX 与 post-only 撮合语义；市价单使用保护价且不入簿，IOC/FOK 未成交余量在同一核心命令内终态释放。
- [x] Trading Snapshot 升级 v5，v1–v4 订单迁移为 LIMIT/GTC；SPOT 三节点保卷 v4→v5 恢复后 `appliedCommandCount=29`、Export `ack=27,next=28,pending=0`。
- [x] 协议 12、核心服务 45 个测试通过；新增 IOC 部分成交余额解锁、post-only 无半状态拒绝、MARKET 保护价和 Snapshot v5 round-trip 证据。
- [x] Order State 补齐 `clientOrderId`、原始 `commandId`、费率快照、创建/更新时间和最后 Cluster position；按 `(userId, clientOrderId)` 构建确定性不可变索引并支持强查询。
- [x] Trading Snapshot 升级 v6，v1–v5 使用确定性默认元数据迁移；SPOT 三节点保卷 v5→v6 恢复后 `appliedCommandCount=29`、Export `ack=27,next=28,pending=0`。
- [x] 重复 `clientOrderId` 在资金预占前拒绝且余额不变；订单强查询 v3 返回完整 API 元数据，协议 12、核心服务 45 个测试通过。
- [x] Order REST 单笔下单、撤单、按 orderId/clientOrderId 强查询切换到共享 Aeron session 池；下单在同一核心命令内完成资金预占、撮合和结算，不再执行旧 `ORDER_RESERVE` 双锁流程。
- [x] 单笔 orderId 查询显式携带 `userId` 并由 Core 校验归属，不增加跨用户扫描或数据库回退；重复 clientOrderId 先读权威 Order State 并校验原始意图。
- [x] Aeron 下单命令映射测试直接解码 payload，覆盖 MARKET 保护价、订单类型/TIF、费率快照和衍生品预留；Order provider 全依赖 `clean test` 128/128 通过。
- [x] `REPLACE_ORDER` 升级为核心内原子撤旧建新，完整携带新 order/clientOrderId、数量、TIF、post-only、费率和资金预留；Exchange Core 任一步失败均重建到命令前 Book State，不暴露外围半撤单状态。
- [x] 原子改单核心撮合 11/11、协议 3/3、Order provider 129/129 通过；旧订单终态、新订单成交/挂簿和 Snapshot 恢复均由同一 Aeron 命令裁决。
- [x] Core Export v2 在命令审计外输出确定性 User 增量事实、完整变化后 Order State 和逐笔 Execution；旧 v1 pending event 仍可解码，避免保卷升级时 Export cursor 断链。
- [x] User 事实仅携带本命令变化的余额/预占/仓位，不随用户历史订单数膨胀；单事件超限时 Core 与 Exchange Core 回滚到命令前状态并失败关闭。
- [x] PostgreSQL 投影在单事务内写 audit、user fact、order latest state 和 execution；重复 `(product_line, export_sequence)` 整体幂等，Exporter 全依赖测试 7/7、Core 45/45 通过。
- [x] Order 开放单、历史和管理查询读取 `core_order_projection.raw_order_state`，单笔在线查询仍直接读 Aeron；用户批量撤单由 PG 选择候选并逐笔提交 Aeron 裁决，不让 PG 成为写权威。
- [x] 开放单使用稳定 orderId cursor，历史支持 symbol/orderId/time 过滤；Order provider 全依赖 129/129 通过。
- [x] 管理单笔/跨用户批量撤单、预览和到期生命周期撤单均由 PG 投影选集后逐笔提交 Aeron；Aeron 已提交撤单返回 `CANCELED` 并按成功统计。
- [x] 管理与生命周期迁移后 Order provider 全依赖 129/129 通过；PG lag 最多造成一次无害重复撤单，Core 终态幂等裁决。
- [x] Funding 到期结算由“逐用户扫描→本地 RocksDB/WAL→Kafka Account 命令→结果对账”收敛为每个
  `(productLine, symbol, fundingTime)` 一条 `APPLY_FUNDING`；Core 在同一确定性命令内扫描权威 Position、
  修改余额和 Treasury，并生成实际入账的逐持仓支付事实。
- [x] Funding 使用 `fundingTime.epochMillis` 作为每个 symbol 单调结算 ID，命令 ID 同时包含 productLine、
  symbol 和结算 ID；外围在 Aeron 成功前不移除到期费率，超时重试不会重复结算，不同合约同一结算时刻
  也不会发生 commandId 冲突。
- [x] Core Export 升级 v3，增加 `CoreFundingPaymentView`；v1/v2 未 ACK 事件仍可解码。Exporter 在原事务中
  写入 `core_funding_settlement_projection` 与 `core_funding_payment_projection`，原 Funding 查询 API 改读
  Core 投影，不新增 outbox、WAL 或第二状态机。
- [x] 删除 Funding 账户命令 producer/consumer、账户 JVM 快照、候选扫描、完成对账、本地结算/序号 RocksDB、
  WAL Bean 和相关测试；Funding provider 生产代码中 `FundingAccount|FundingLocal|funding-wal|AccountUserCommand`
  引用为零。
- [x] Core 资金费事实覆盖多空零和、同用户 HEDGE 净额为零仍保留双腿账单、余额不足时事实等于实际扣款；
  联合 `clean test` 通过：Protocol 12/12、Core 47/47、Exporter 8/8、Funding provider 12/12。
- [x] Delivery/Option 生命周期消费者不再扫描旧 Account User State 并逐用户发送账户命令；每个 CLOSED
  合约事件只提交一条 `SETTLE_INSTRUMENT`，Core 原子撤销该 symbol 开放单、释放保证金、结算全部仓位并
  记录 lifecycle settlement marker。
- [x] 生命周期 settlementId 使用 deliveryTime/eventTime epochMillis，commandId 包含 productLine、symbol、
  settlementId；Kafka redelivery/客户端超时复用同一幂等身份，不读取 Kafka offset，不保留旧 WAL 回退。
- [x] 删除 `AccountService.planDeliverySettlement/planOptionExercise` 全用户扫描；命令映射测试解码 Delivery 与
  Option payload，Account provider 全依赖测试 126/126 通过。
- [ ] Account/Order/Funding/Risk/Liquidation 现有服务入口切换到 Aeron 后删除旧实现。

执行记录（2026-08-14，子阶段 P6.2）：

- [x] ADL 候选查询新增 `ADL_CANDIDATE_QUERY/RESULT`，候选排序、标记序列和利润容量直接由 Aeron Core 读取。
- [x] ADL 执行通过单条 `EXECUTE_ADL` 原子校验并修改目标仓位与强平缺口；PG 仅读取 `core_liquidation_projection` 选集并写审计。
- [x] 删除 ADL Redis 候选索引、Risk Kafka 消费、Account outbox/deficit/saga/reconcile 和旧 ADL 数据模型；生产代码不再包含旧 ADL 权威类。
- [x] ADL provider 重写为 Aeron gateway + Core liquidation projection；Aeron 查询、事件 cursor 和禁用扫描边界测试 6/6 通过，模块依赖链测试通过。
- [ ] Order/Matching/Account/Liquidation 仍存在旧 WAL 或旧强平入口，继续在 P6.3 清理。
- [x] Order REST 写入、改单、撤单、查询、批量管理和生命周期入口均不再使用 Aeron-null 回退；无 Aeron Bean 时显式失败关闭。
- [x] Order provider 全依赖测试 129/129 通过；旧 WAL 行为测试改为验证无 Aeron 不执行旧命令。
- [ ] Order WAL Bean/投影 worker 仍待 P6.3 后续删除。
- [x] Core 新增只读 `RISK_STATE_QUERY/RESULT`，按 userId 返回现有 Aeron Risk Snapshot；不增加第二状态容器，Protocol 14/14、Core 49/49 通过。
- [x] Risk Provider 删除 Redis Risk、Kafka 账户/持仓/标记价计算消费者、本地 WAL、风险 outbox、旧风险快照和候选仓储；生产模块只保留 Aeron 强查询、`core_liquidation_projection` 只读选集和 PG 风控管理规则。
- [x] `RISK_STATE_RESULT` 返回 instrument、仓位、标记价、名义价值、逐仓保证金和 Core 钱包余额；账户与持仓 API 直接映射 Core 结果，无 PG/Redis 计算回退。
- [x] Core 全仓 Risk 改为按同结算资产组合计算权益、未实现 PnL 和维持保证金；逐仓仍独立，且全仓钱包排除逐仓仓位及挂单占用。多标的回归验证标记价变更会同步更新组合内全部全仓快照和强平计划。
- [x] Risk 联合 `clean test` 通过：Protocol 14/14、Core 50/50、Risk Provider 5/5；Risk Provider 生产源码中 `RedisRisk|RiskOutbox|RiskLocalProjection|risk-projection-wal|risk_liquidation_candidates|risk_account_snapshots|risk_position_snapshots` 引用为零。
- [x] 普通订单的仓位模式、保证金模式冲突与平仓仓位读取全部切换到 Aeron `USER_STATE_QUERY`；下单、批量下单、改单和一键平仓不再读取旧 Order User State。
- [x] 删除旧 Kafka 仓位事件驱动的只减仓订单维护消费者；只减仓最终容量由 Core 在单条下单命令内确定性裁决，不再由外围异步修剪订单。
- [x] 普通订单入口删除 `placeWal/amendWal`、账户预占命令规划及旧批次 reservation sequence；Order 全依赖 `clean test` 127/127 通过。
- [ ] 算法单、杠杆设置和旧订单投影/维护任务仍引用 Order WAL，完成 Aeron/PG 边界迁移后再删除 WAL Bean 与 `surprising-event-store` 依赖。
- [x] Account 在线用户状态和内部永续账户快照统一直接查询 Aeron `USER_STATE_QUERY`；未命中的余额、仓位和模式由权威结果映射，不读取 JVM/Redis/PG 回退。
- [x] Account 开放持仓量新增只读 `OPEN_INTEREST_QUERY/RESULT`，由 Core 权威 Position State 按 symbol 聚合多空数量；不维护第二套 open-interest repository 或 Kafka reducer。
- [x] 删除 Account 用户命令 WAL、command/result waiter、本地 reducer、RocksDB 状态/变更日志、Redis Position、旧账户投影 worker、旧强平/结算协调器及其运行配置；模块移除 `surprising-event-store`、Redis 和 RocksDB 原生构建依赖。
- [x] Account 生产源码中 `UserPartitionWal|surprising.eventstore|RedisPosition|PositionCache|AccountUserStateReducer|AccountUserCommandWalIngress|AccountCommandSubmissionService|account-wal` 引用清零；联合 `clean test` 通过：Protocol 16/16、Core 51/51、Account Provider 49/49。
- [ ] Account 数据库旧表 migration 暂不执行破坏性 drop；它们不再被生产权威链路引用，最终 schema 清理归入部署前独立可回滚 migration。

阶段出口：只有 Aeron Log/Archive/Snapshot 是核心权威恢复链，全仓测试通过。

### 18.8 P7：六线补齐

任务：

- [ ] `SPOT` 完整 smoke/recovery/reconcile。
- [ ] `LINEAR_PERPETUAL` 完整 smoke/recovery/reconcile。
- [ ] `INVERSE_PERPETUAL` 完整 smoke/recovery/reconcile。
- [ ] `LINEAR_DELIVERY` 完整 smoke/recovery/reconcile。
- [ ] `INVERSE_DELIVERY` 完整 smoke/recovery/reconcile。
- [ ] `OPTION` 完整 smoke/recovery/reconcile。
- [ ] 六线 Runbook、Topic、仪表盘和告警一致。

阶段出口：六条线逐线功能、恢复和资金差异均为零。

### 18.9 P8：压测前正式门禁

对将要压测的每条产品线单独执行第 15 节。每条线形成不可修改的 environment manifest 和验收报告。

阶段出口：当前产品线 `functional-gate=PASS`、`funds-diff=0`，方可进入 P9 对应压测。

### 18.10 P9：单产品线压测

按第 16 节顺序逐线执行。每完成一条线，立即更新本节状态、填写报告 URI 和最终稳定 OPS，不等待六线
全部结束才记录。

阶段出口：六份可复现容量报告，无资金差异，恢复和资源 SLO 同时满足。

### 18.11 P10：生产部署冻结

任务：

- [ ] 三机六 Cluster 部署演练。
- [ ] Cluster Backup 和恢复演练。
- [ ] 单机维护、滚动升级和 Drain Runbook。
- [ ] 磁盘损坏、网络分区、Kafka/PG 故障 Runbook。
- [ ] 监控、告警、值班和容量阈值冻结。
- [ ] 上线前最终六线资金核对。

阶段出口：所有 Runbook 经非开发人员按文档演练通过。

## 19. 决策、偏差与证据记录

实施时按以下格式追加，不覆盖历史：

| 日期 | 阶段 | 类型 | 决策或偏差 | 原因 | 证据/提交 | 影响 |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-08-13 | P0 | 决策 | 未上线项目不做长期影子集群和双权威 | 无生产历史数据；降低复杂度 | ADR-0001 | 使用离线重放替代影子验证 |
| 2026-08-13 | P0 | 决策 | P6 删除旧链路早于性能压测 | 性能必须测唯一最终架构 | 本文第 13、16 节 | 不保留运行时回退开关 |
| 2026-08-13 | P0 | 决策 | 每次只压一条产品线 | 隔离容量和资金结论 | 本文第 16 节 | 形成六份独立报告 |
| 2026-08-13 | P6 | 实现 | 在删除 Account WAL 前补齐仓位模式、逐仓保证金和双向持仓 | 旧 Account API 已提供这些资金功能，不能以删 API 代替迁移 | `TradingCoreReducerTest`、`TradingStateSnapshotCodecTest` | REST 切换可保持原功能契约且由 Aeron 唯一裁决 |
| 2026-08-13 | P6 | 实现 | Account 对外资金命令与强查询直接使用 Aeron session 池 | 去除 HTTP→Kafka/WAL→轮询结果的额外跳数，Cluster 不可用时明确失败关闭 | `AccountCommandGatewayTest`、`AccountServiceLocalSnapshotTest`、SPOT 保卷重启 | 内部 Order/Matching/Funding 等调用仍需在 P6 后续切换 |
| 2026-08-13 | P6 | 实现 | Aeron session 池下沉到共享 client 模块且不解释业务结果码 | Order、Funding、ADL 等后续迁移复用同一并发和重连机制，避免每个 provider 复制连接管理 | `AeronClientPoolTest`、Account provider 回归 | 共享层保持无 Spring、无业务模块依赖；各 provider 继续失败关闭 |
| 2026-08-13 | P6 | 实现 | Order Core 显式保存订单类型和 TIF，撮合保护价与 API 申报价分离 | 旧 Adapter 硬编码 GTC 会使 IOC/FOK/MARKET 余量错误留簿并持续锁资；REST 迁移前必须先闭合资金生命周期 | `CoreMatchingStateTest` 11/11、SPOT v4→v5 保卷恢复 | 不新增订单权威服务；完整订单 API 元数据和投影继续归入同一 Aeron Order State/Exporter |
| 2026-08-13 | P6 | 实现 | `clientOrderId` 索引由不可变 Order State 确定性派生，命令元数据使用真实 Cluster timestamp/position 盖章 | 满足订单 API 幂等和审计要求，同时避免第二套索引写流程或扩大幂等结果窗口 | `CoreProbeStateTest`、`CoreStateQueryCodecTest`、SPOT v5→v6 保卷恢复 | 单笔强查询直接走 Aeron；历史批量查询由 Exporter/PG 投影承接 |
| 2026-08-13 | P6 | 实现 | Order 单笔生产入口直接提交 Aeron 原子下单/撤单命令，强查询必须携带用户归属 | 旧 reserve→matching 链会与 Core 原子预占重复锁资；无用户归属的查询无法安全路由 | `AeronOrderCommandServiceTest` 2/2、Order provider 全依赖 128/128 | 改单、批量撤单和历史列表仍在 P6 后续迁移；未保留运行时回退开关 |
| 2026-08-13 | P6 | 实现 | 改单以单条 `REPLACE_ORDER` 原子撤销原单并创建完整替代单 | API 允许改数量/TIF/post-only/clientOrderId，Exchange Core 原地 move 只能改价且会造成语义缩水；外围两命令会暴露半状态 | `CoreMatchingStateTest` 11/11、`AeronOrderCommandServiceTest` 3/3、Order provider 129/129 | 新单失效时 Core 和 Exchange Core 同时恢复命令前状态；协议无需兼容未上线系统的旧 replace 日志 |
| 2026-08-13 | P6 | 实现 | 唯一 Core Exporter 输出命令审计、变化 User 增量、变化后 Order 和 Execution 事实，并在一个 PG 事务投影 | 删除旧 Order WAL 前必须承接开放单/历史/成交/私有推送；导出全量 User 会使单事件随历史线性膨胀 | `CoreExportCodecTest`、`JdbcCoreEventProjectorTest`、Core 45/45、Exporter 7/7 | V002 migration 是 Projection 启动前门禁；旧 v1 pending event 可继续 drain，未新增 outbox/WAL/第二状态机 |
| 2026-08-13 | P6 | 实现 | Order 列表/历史/管理查询使用 Core Export PG 投影，批量撤单只用投影选集并由 Aeron 逐单裁决 | 批量与历史查询不应扩大 Cluster duty cycle；旧 RocksDB 用户分区不能在删 WAL 后继续承接查询 | Order provider 129/129 | 投影具有短暂 lag，单实体/资金仍走 Aeron 强查询；管理撤单和生命周期 fanout 后续清零旧引用 |
| 2026-08-13 | P6 | 实现 | 管理、跨用户和生命周期撤单统一为投影选集加 Aeron 单单裁决 | 跨用户扫描属于查询负载，不应进入 Cluster；最终写裁决必须仍由 Core 检查 owner 和终态 | Order provider 129/129 | `CANCELED` 是 Aeron 同步成功，不再沿用旧异步 `CANCEL_REQUESTED` 统计语义 |
| 2026-08-13 | P6 | 实现 | Funding 每个结算周期只提交一条 Aeron 命令，Core Export v3 输出实际逐持仓支付并由 PG 投影承接查询 | 删除逐用户账户命令可减少 O(position) 外围消息、WAL 与结果对账；账单和 funds-diff 仍必须来自权威裁决 | Core 47/47、Exporter 8/8、Funding 12/12 | 命令 ID 包含 productLine/symbol/fundingTime；v1/v2 Export 兼容读取；V003 是 Projection 启动前门禁 |
| 2026-08-13 | P6 | 实现 | Delivery/Option 生命周期事件各提交一条 `SETTLE_INSTRUMENT`，删除 Account 全用户结算规划 | 合约级操作应在唯一 Core 状态上 O(1) 入队并确定性扫描，外围 fanout 会产生大量账户命令和半结算窗口 | Account provider 126/126 | deliveryTime/eventTime 形成每 symbol 单调 settlementId；消费者保留原 Kafka topic 契约 |
| 2026-08-13 | P6 | 实现 | Core Export v4 输出变化后的 Liquidation 与 Treasury 资产事实，V004 在 PostgreSQL 投影最新状态 | Insurance/ADL 不得继续把旧 Account deficit 表、基金表或 Redis 队列当权威；外围只允许用 PG 选集，最终裁决回 Aeron | Protocol 12/12、Core 47/47、Exporter 9/9 | v1-v3 pending event 保持可解码；投影使用 revision/sequence 门禁，不新增 outbox、WAL 或运行时回退 |
| 2026-08-13 | P6 | 实现 | 保险基金余额进入 Core Treasury；保险覆盖支持全额或部分覆盖，余量确定性进入 `ADL_REQUIRED`；ADL 以单条命令原子校验目标仓位、标记价、平仓利润并减少坏账 | 数据库基金余额、Account deficit 和四命令 ADL saga 会形成多资金权威及半减仓窗口；目标仓位方向必须随 Liquidation/Snapshot v7 保存 | Protocol 12/12、Core 49/49、Exporter 9/9；现金+未实现PnL-未决坏账守恒 | 旧 Snapshot v1-v6 可读；旧 `RESOLVE_LIQUIDATION(ADL)` fail-closed，外围只能调用 `EXECUTE_ADL` |
| 2026-08-14 | P6 | 实现 | Insurance 的注资、强平费和坏账覆盖全部同步提交 Aeron；PG 只选择 `core_liquidation_projection` 并保存覆盖审计 | 删除数据库基金余额预留、旧 Account deficit 扫描、Account outbox 和 reconcile 双阶段链，避免资金双权威 | Insurance provider 14/14；生产旧类引用清零 | commandId 由产品线与业务引用确定性生成；Core 成功后审计可幂等补写，不提供旧链回退 |
| 2026-08-14 | P6 | 实现 | Risk Provider 收缩为 Aeron 强查询、Core Liquidation PG 投影和管理规则；Core 全仓风险按同结算资产组合计算 | Redis/Kafka/WAL 重算会形成第二权威；逐仓位计算全仓权益会在多标的盈亏对冲时错误强平 | Protocol 14/14、Core 50/50、Risk 5/5；Risk 旧链生产引用清零 | 无 Redis、Kafka 计算消费者、风险 outbox 或本地 WAL；PG 候选只读且最终状态由 Aeron 裁决 |
| 2026-08-13 | P1 | 决策 | v1 采用等价固定二进制 codec，不引入代码生成 SBE | P1 envelope 字段固定且简单，先控制构建复杂度；golden 和扩展兼容测试已覆盖 | `CoreMessageCodecTest` | P2 新增业务 payload 前重新评估 SBE schema 生成 |
| 2026-08-13 | P1 | 决策 | 幂等由 `commandId` 和 `(source, sourceId, sourceSequence)` 双层保护 | 完整结果窗口必须有界，但资金命令不能因淘汰而重放 | `CoreProbeStateTest` | Snapshot 必须保存两类状态 |
| 2026-08-13 | P1 | 偏差 | Docker Desktop 未继承终端 Clash 代理 | Docker Hub JRE 25 元数据请求 60 秒超时 | P1 本地验证记录 | 宿主机经 Clash 下载官方 JRE 25 构建仅用于验证的本地基础镜像 |
| 2026-08-13 | P2 | 决策 | 业务 payload 继续使用手写固定小端 codec，不在 P2 引入 SBE 生成插件 | 命令仅三类且字段冻结；严格长度、截断、尾随字节和 round-trip 测试已覆盖 | `TradingCommandCodecTest`、`CoreStateQueryCodecTest` | P3 fill/book 事件数量增加前再次评估 SBE |
| 2026-08-13 | P2 | 决策 | 预占命令显式携带 base/quote/settle asset | symbol 命名不能成为资金币种事实源；核心必须校验现货方向资产与衍生品结算资产 | `TradingCoreReducerTest` | P3 instrument state 必须按 version 再校验这些字段 |
| 2026-08-13 | P2 | 决策 | 重复响应增加原始 `commandStatus` | 只有 `DUPLICATE` 无法判断超时前原命令成功还是拒绝 | `CoreProbeStateTest` | Gateway 必须按 `commandStatus` 返回原始业务裁决 |
| 2026-08-13 | P2 | 边界 | `reduceOnly` 在 P2 fail-closed | 未接持仓和成交状态时无法证明订单真的降低风险 | `TradingCoreReducerTest` | P3 接入 Position/Book 后解除 |
| 2026-08-13 | P3 | 决策 | Exchange Core 只做确定性订单簿执行器，资金与改单 reserve 上限只由 Aeron State 裁决 | 避免 Exchange Core 的用户余额和 reservePrice 成为第二资金权威 | `DeterministicExchangeCoreAdapter`、`CoreMatchingStateTest` | P4 Risk 继续只读 Aeron User/Position State |
| 2026-08-13 | P3 | 决策 | 运行时校验 Exchange Core `MATCHING_ORDER_BOOKS` hash，恢复校验规范化 Book State hash | Exchange Core 0.5.3 内部 hash 包含已成交历史字段，开放订单重建后逻辑相同但内部 hash 可不同 | `CoreMatchingStateTest` | P5 恢复报告必须同时标明两种 hash 口径 |
| 2026-08-13 | P3 | 边界 | 衍生品平仓、反向 PnL、手续费、期权权利金继续 fail-closed | P3 没有完整 instrument multiplier、费用和产品 PnL State，近似结算会损坏资金 | `DERIVATIVE_CLOSE_REQUIRES_PNL_MODEL` | P4 必须先补齐产品数学再解除 |
| 2026-08-13 | P4 | 决策 | Instrument 参数进入 Aeron State，成交、Risk 和生命周期只使用绑定 version 的参数 | 外围缓存或 symbol 命名不能成为保证金、PnL 和费用事实源 | `CoreInstrumentState`、`TradingCoreReducer` | P5 Input Bridge 只能提交版本化命令 |
| 2026-08-13 | P4 | 决策 | Risk scan 每个命令最多处理 256 用户并通过续跑命令推进 | 限制单个 Cluster duty cycle，避免强平风暴阻塞共识 | `CoreRiskStateTest` | P9 强平风暴压测后再调整可配置生产值 |
| 2026-08-13 | P4 | 决策 | 未覆盖坏账只记录在 Liquidation State，不同时写 Treasury deficit | 对手方盈利和坏账若同时进 Treasury 会重复计算系统资金 | `CoreLiquidationState.deficitUnits`、`CoreLifecycleStateTest` | Exporter 必须输出 liquidation deficit 和后续覆盖回执 |
| 2026-08-13 | P4 | 证据 | SPOT 和 LINEAR_PERPETUAL 均完成真实三节点 Leader kill 恢复，线性资金费净额为零 | 组件测试不能替代共识日志上的状态连续性 | `ClusterSpotMatchSmokeMain`、`ClusterDerivativeSmokeMain` | P7 继续逐线执行相同门禁，不并行启动六线 |
| 2026-08-13 | P5 | 决策 | Export State 位于 Aeron Snapshot，Kafka 成功后才 ACK；不增加数据库 outbox 或第二套 WAL | 保持单一权威恢复链，跨 Aeron/Kafka 使用 at-least-once 和稳定 sequence | `CoreExportState`、`ReliableCoreExporter` | Kafka/PG 消费者必须按 `(product_line, export_sequence)` 幂等 |
| 2026-08-13 | P5 | 决策 | Export backlog 使用条数与 64 MiB 双硬上限，满载在 reducer 前明确背压 | 防止大 payload 在 100 万条之前耗尽堆或令 Snapshot 超过 JVM 数组限制 | `CoreExportState.hasCapacityFor` | `EXPORT_BACKLOG_FULL` 不属于业务裁决，Kafka offset 不提交 |
| 2026-08-13 | P5 | 决策 | 第一版极限积压统一 fail-closed，不为撤单/强平增加旁路队列 | 旁路会破坏一个连续 Export State 的简单恢复语义 | `CoreProbeState.apply` | 运维先恢复 exporter 或 drain；P9 再以证据决定预警水位 |
| 2026-08-13 | P5 | 证据 | SPOT 真实三节点完成 sink 故障、Leader kill、Follower rejoin、Snapshot 和全停冷恢复 | 模拟 sink 单测不足以证明 cursor 在共识故障中的连续性 | `ClusterExportSmokeMain`、Aeron `ClusterTool` | P7 对其余五线复用相同恢复脚本 |
| 2026-08-13 | P5 | 证据 | 本机 Kafka 和 PostgreSQL 完成真实发布、重复投影，最终唯一行数为 1 | H2 只验证 JDBC 语义，不能替代目标基础设施 | `ExporterInfrastructureSmokeMain` | P7 将事件内容与六线资金投影逐项对账 |

## 20. 阶段更新模板

完成阶段时复制以下内容到对应阶段下：

```text
完成日期：
完成提交：
实施者：
实际修改模块：
与原方案偏差：无 / 见第 19 节记录

验证命令：
- <command>

验证结果：
- 单元测试：
- 三节点测试：
- Snapshot/Replay：
- 功能测试：
- 资金核对：
- 性能测试（仅 P9）：

证据位置：
- CI：
- artifact：
- environment manifest：

残留风险：
- 无 / <risk>
```

如果测试只在本机通过，必须明确写“本地功能证据”，不能写“生产容量通过”。

## 21. 完成定义

整个迁移只有同时满足以下条件才算完成：

- 六条产品线只有 Aeron 是交易核心权威。
- 旧 WAL、Redis Risk 和旧强平权威链路全部删除。
- 六条产品线功能、恢复和资金核对全部通过。
- 资金差异、重复扣款、重复结算为零。
- Leader 切换和冷恢复 RPO 为零。
- Exporter 和投影故障可恢复，最终 lag 为零。
- 六条产品线分别完成单线容量测试。
- 生产部署、Backup、监控、告警和 Runbook 演练通过。
- 本文档、ADR、术语表、部署文档和产品线 Runbook 与最终代码一致。
