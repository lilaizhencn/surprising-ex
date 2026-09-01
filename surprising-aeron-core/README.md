# Surprising Aeron 统一交易核心

本目录承载按产品线隔离的 Aeron Cluster 交易核心。每个 `ProductLine` 变体使用相同代码、独立 `clusterId`、
端口空间、Archive 和数据卷；一个逻辑 Core 固定由三个 Member 组成，并管理该变体全部 symbol。

W1/W2 已完成单一可执行盘口改造：`TradingCoreRuntime` 是 Core 单写边界，撮合命令只进入 fork 的
exchange-core 0.5.16-emporia；`TradingCoreState` 不再保存 `CoreBookState` 或任何 FIFO priority map。
matcher 恢复导入 Aeron 配对 snapshot 中全部原生 `ME[0..N)/RE0`，通过 `fromSnapshotOnly` 启动，不逐单回放。

部署基线不按 margin mode 或热点 symbol 分 Core：CROSS 和 ISOLATED 都由同一个 ProductLine Core 裁决；
CROSS 只共享该 Core 内权益，ISOLATED 绑定 position identity。只保留 `coreShardId=default`，当前协议固定
`routeVersion=3`；symbol 只进入同一共享 ExchangeCore 的原生 matcher shard，不启用物理热点 Core 或跨 Core 全仓余额共享。

## 模块

| 模块 | 职责 |
| --- | --- |
| `surprising-aeron-protocol` | schema v4 固定小端二进制 command/envelope、六线 wire code、route v3 和端口布局。 |
| `surprising-aeron-service` | `ClusteredService`、有界幂等状态、Snapshot 和节点启动器。 |
| `surprising-aeron-client` | Leader 自动发现、切换处理和“超时即结果未知”同步客户端。 |
| `surprising-aeron-exporter` | P5 可靠 Exporter 的最小 sink 边界。 |
| `surprising-aeron-tools` | Cluster 探针、状态 hash 查询和只读离线 replay 诊断；不得作为生产恢复或 snapshot 来源。 |

## Owner commit 单一路径（P11）

本实现改动触及共享 protocol 与通用 Product Core owner commit、投影和 Core Fact 出口；运行时与性能验证范围仅为
`LINEAR_PERPETUAL`。其他五条产品线没有以本轮命令、JMH 或 JFR 验证，不能据此推断其性能或生产认证状态。每条产品线仍各自拥有
`ProductLine`、账户 Lane、instrument、topic、风险和结算边界，绝不跨线合并订单或资金状态。

唯一写路径为：owner thread 原地修改 `TradingRuntimeState`，在命令边界把首个 before 与最终 after 收集为
`PreparedChanges`；business/funds rolling hash 先生成可回滚 transition，随后只 seal 一份
`RuntimeCommitPatch`。该 patch 依次驱动 `RuntimeCommitIndexes`、有界 `RuntimeCommitJournal`，再由单 projector
以 item/byte 上限批量更新 mutable projection，并以 Core Fact v10 异步批量编码。普通下单、撤单、成交和风险命令
不全量 materialize、不等待 projector 或 Core Fact；显式 snapshot、query fence、关闭和恢复才请求并等待精确 fence。
snapshot 从已完成 projection 冻结，replay 按连续 patch sequence 重放；任何 sequence、hash、产品线或 lane 拓扑不匹配均
在替换状态前 fail closed。

`RuntimeCommitIndexes` 的唯一 patch apply 入口覆盖九个索引：`PositionUserIndex`、`OpenInterestIndex`、
`TriggerOrderIndex`、`AlgoOrderIndex`、`LiquidationIndex`、`CancelAllAfterIndex`、`ActiveOrderIndex`、
`AdlPositionIndex` 与 `RiskSnapshotIndex`。Core Fact v10 明确编码 tombstone：删除后重建同一用户/资产、订单、持仓、
杠杆或其他实体不会复活旧值；terminal order/liquidation/trigger ID 也以独立、已编码的列表保留。
订单的 canonical business/export view 只在 owner 的 `prepareCommitPatch` 中由一处 `recordOrder` producer 预封装；
patch 的 `businessAfter` / `exportAfter` 随后由 projection、index、retention、export 与 payload consumer 直接读取，
不得再次从 Runtime fallback 构造。

准入先同时预留 journal patch 数/字节与 Core Fact event 数/字节，之后才允许 mutation/Cluster Log commit；reservation 只能
消费一次，提交前拒绝或失败必须释放。journal/export 容量满返回 backpressure，不创建无界 executor 或队列。owner、每个
Account Lane、matcher、risk、projector 与 exporter 的失败会 poison 相应路径，撤销 hash/index transition、回收 lane ticket，
且不会推进可见 patch/core sequence。资金 posting 以 `(asset, ownerKind, ownerId, subledger)` 的 before/after 精确导出，
含 fee、insurance、funding、liquidation、rounding、clearing、deficit 后逐资产守恒；sealed patch 的 business/funds hash、
idempotency response、订单终态与 snapshot/replay hash 必须一致。

批量订单先在 owner 上做 mutation domain 与容量 preflight，并保存 `TradingRuntimeState.CommandCheckpoint`；
`BatchAdmissionOrderIndex` 只读取 `currentPatchOrderBefore` / `currentPatchPositionBefore` 的 typed before-value。
它不以 `TradingCoreState`、snapshot、projection await 或 full materialization 作为基线。fatal batch 路径统一进入
`failOrderBatch`：typed runtime checkpoint rollback 后按逆序回收预分配 client-order key，再撤销 position identity；不会调用
通用 immutable-state restore。这样批量 idempotency、Lane 隔离和 admission/open-interest 语义仍在同一 owner commit 边界内。

支持的背压和等待参数包括 `surprising.aeron.commit-journal-capacity`、
`surprising.aeron.commit-journal-capacity-bytes`、`surprising.aeron.projection-batch-size`、
`surprising.aeron.projection-batch-bytes`、`surprising.aeron.projection-batch-delay-nanos`、
`surprising.aeron.projection-wait-strategy`（`PARKING`/`YIELDING`/`BUSY_SPIN`）、
`surprising.aeron.projection-busy-spin`、`surprising.aeron.export-materialization-capacity`、
`surprising.aeron.export-pending-bytes` 和 `surprising.aeron.export-materialization-batch-size`。BUSY_SPIN 会持续占用
CPU，YIELDING 降低抢占强度，PARKING 以延迟换取空闲 CPU；它们都是运行时性能参数，不能改变业务顺序或 snapshot hash。

静态/目标验证（执行前必须确认 HotSpot JDK 25，且本任务未执行这些 Java 命令）：

```bash
java -version 2>&1 && mvn -version
mvn -pl surprising-aeron-core/surprising-aeron-service \
  -Dtest=TradingCoreRuntimeAuthorityTest,W1W2InvariantFenceTest test
surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh jmh
```

最后一条资格命令固定使用 HotSpot 25、ZGC、10,000 users、512 listed/active symbols、4 Account Lanes；其 scale、GC、
JFR、soak 与 BUSY_SPIN/YIELDING saturation 模式会写入当前脚本指定 artifact 目录。JMH/JFR 只能在前置审计、目标测试和
编译通过后执行；本 README 不把它们或六产品线资金验证声明为已完成。

## 本地构建与三节点运行

使用 JDK 25 构建：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="${JAVA_HOME}/bin:${PATH}"
mvn -pl :surprising-aeron-client,:surprising-aeron-tools -am test
```

三节点部署与验证入口正在重新整理。一次只启动一条产品线；删除 Archive 或数据卷前必须先确认目标产品线，
任何迁移工具只能在检测到既有状态时中止，不能自动删除状态。

## 实现状态与版本切换说明

当前写格式为 command/envelope schema v4、export event marker v10、trading snapshot v24、matcher snapshot v5 和
sectioned snapshot v17；decoder 和 startup 只接受这些当前版本，对任何旧版本 fail closed，不保留 legacy reader、兼容 reader
或隐式降级。

P10 的目标不是物理 Core shard；每个三节点 Product Core 仍只运行一个共享的 Adapter/ExchangeCore，按 symbol 的
Matcher Lane 直接复用 exchange-core 原生 MatchingEngineRouter shard，Account Lane 是默认必须实施的运行时边界，
生产默认 `accountLaneCount=4`；`MatcherSettlementPlan` 单次遍历 matcher events，生成按 Lane 切分的只读事件视图，
每个 Lane 只处理自己的用户和成交，不再重复扫描完整结果。Treasury 保持 Sequencer owner。全局 Core
sequence、Core Fact、snapshot 和恢复仍由一个确定性 Sequencer 协调。默认 topology 为 4 个 native matcher shard、
1 个 risk engine、4 个 Account Lane、一个 Sequencer-owned Treasury；matcher pipeline、pending reservation 隐藏、原生 Lane commit
位图、全局 commit cursor 和实际 Lane snapshot section 已落地。小于并行阈值的普通成交由 owner 原地结算，避免任务切换；
大额多成交由按需创建的 `SettlementLaneWorker` 并行计算纯 `PerpetualLaneJournal`，worker 不写 Runtime State。
owner 不等待 Future、ACK 或逐命令 barrier，而是在后续 duty cycle 轮询完成位图；只有所需 Lane 全部成功且 Core sequence
连续时，才按 Lane 顺序一次性应用 journal、合并 Treasury delta、提交 Runtime State/Core Fact，对外保持原子可见。
BLOCKING worker 空闲时无限 park，由生产者唤醒；也可显式切换 BUSY_SPIN 或 YIELDING。
P10-G 仍需真实 HTTP/JFR 长稳 artifact；没有对应 artifact 时不得宣称生产认证完成。

## 协议约束

- Cluster Log 权威消息禁止 Java serialization 和无版本 JSON。
- schema v4 header 包含 `commandId`、`productLine`、`source`、`sourceId`、`sourceSequence`、`userId`、
  外部提交时间、`correlationId` 和 route v3；旧 envelope/route 不再接受。
- Instrument Provider 通过版本化 `UpsertInstrumentCommand` 下发保证金率、risk brackets、最大杠杆和
  最大持仓名义价值；CoreInstrumentState 是运行时唯一参数副本，Risk Provider 只能查询 Core 快照。
- exchange-core 0.5.16-emporia 是唯一可执行订单簿（sole executable order book），独占价格树/FIFO；`GTX` 使用原生
  post-only 语义，外层不得查 book 后模拟，也不得建立并行可执行 book。
  Core 的 `CoreOrderState` 只保存业务元数据和活动状态，不保存可重建 FIFO 的 priority sequence。
- adapter 固定使用 `RiskProcessingMode.MATCHING_ONLY` 并禁用 exchange-core margin trading；内部 user/symbol/risk module 是需随 matcher snapshot 恢复的技术状态，不是业务资金、持仓或保证金权威。
- Adapter 按 Core sequence 向一个 ExchangeCore ring 提交最多 `matcherWindowSize` 个命令；原生 shard 可并行，
  普通 symbol 结果按 `matcherShardId` 独立推进严格单调 sequence/native sequence 和连续 prefix；control shard `-1`
  承载跨 symbol 批次、前置撤单与生命周期命令。不同 shard 允许乱序完成，任一 shard 内倒退或 prefix 断裂即 fail closed。
  异步完成直接把同一个 `CoreMatchingResult` 引用按 Core sequence release-publish 到预分配 completion mailbox，
  不存在 MPSC queue、publication cursor 或 `Completion` wrapper。owner 只消费当前确定性 sequence；depth 在槽位
  发布前预约，sticky overflow 无论是否存在 pending sequence 都会 fail closed。Sequencer 扫描一次 userId 后把该引用扇出给受影响
  Account Lane；`MatcherSettlementPlan` 在一次遍历内完成结果分类、受影响用户收集和 Lane event slice 构造，后续 Lane
  不再重复遍历 matcher 链。wall-clock readiness/watchdog 只能由 external health
  supervisor 观察，不能进入复制状态或改变裁决顺序。
- Pending matcher 顺序和 continuation 均使用预分配、按 Core sequence 定位的 primitive ring。热路不维护逐命令
  `CompletableFuture` map、active set 或 completed-result map；同步边界可直接读取已完成 future 的确定性结果，不再等待
  completion callback 清理并发集合。只有 snapshot/关闭 fence 会统一等待仍在途的 matcher future。
- `saturatedMatchingWorkload` 使用共享有界窗口的持续滑动 feeder：同一方向内每完成一组 maker/taker 依赖就立即补入
  下一币对，不再整窗排空后重新提交；一个方向全部完成后才反向，防止预置深度场景产生方向穿越。
  每个 symbol 同一时刻最多一组订单在途，保持账户依赖和 Core sequence 提交顺序；
  每个提交批次只等待首个连续完成，随后非阻塞提交该批其余已完成结果并立即 refill，避免 owner 空转与 matcher 争抢 CPU；
  JMH/JFR 同时记录 window/full-window、refill 和 producer-starvation，资格脚本要求测量期存在持续补料且 starvation 为零。
- JFR 热点判断只统计自定义 workload measurement 事件窗口，排除 JMH trial 初始化和 snapshot template。交易窗口内
  的协议 enum 解码无 Stream，typed mutation 不再重复复制已排序 change-set，lazy delta 用有界原子槽缓存变化值；批量
  订单结果和 open-order 状态按精确长度编码，避免每个 item 的中间 frame 与 grow/copy 缓冲。
- matcher 结果使用 typed outcome 区分成交、挂单、拒绝和撤单，并用 primitive UUID 两段值保存 command identity；已知的
  前置撤单 prefix 可以确定性恢复，未知 prefix 或语义分歧仍 fail closed。replace/amend 在进入 matcher 前只执行一次
  identity、reservation 和 admission 解析，完成阶段复用 `ResolvedMatchingAdmission`，不再次做同义业务校验。
- Lane 提交使用 owner 预分配的 apply result、Lane values 和回调，不再创建逐命令 ACK/对象数组或二次 ACK 循环。
  `surprising.aeron.parallel-settlement-min-trades` 控制并行结算阈值，默认 `8`；
  `surprising.aeron.parallel-lifecycle-min-items` 控制生命周期跨 Lane 并行阈值，默认 `2`；单 Lane 工作保持 owner 串行，多 Lane 工作保持 Account Lane 独立 owner；
  `surprising.aeron.settlement-wait-strategy` 接受 `BLOCKING`（默认）、`YIELDING` 或 `BUSY_SPIN`。
- pending matcher 以 sequence、commandId 和 user 三个有界索引做常数时间定位；Core Fact owner→materializer
  使用有界 SPSC ring，ACK 早于 materialization 时由 slot 状态机回收，不创建逐 Fact Future/Task。
- Cluster response 直接写入 session egress scratch，并以调用时的 committed Core sequence 编码；内部不再构造
  visible response、临时 response payload 或二次 `CoreMessage` payload copy。只有 Aeron backpressure 入队时复制 scratch。
- 单笔交易只发布 sealed typed commit 和 `RuntimeProjectionPoint`；mutable projector 顺序消费有界 patch batch，只有 snapshot/query
  fence、关闭和恢复才冻结 `TradingCoreState`。Core Fact v10 从 patch 的 typed before/after、tombstone 和 encoded terminal ID
  异步编码；下单、撤单、撮合及批量 owner 路径不执行 lazy transition view，也不等待 projector 或 Core Fact。普通及批量订单校验
  直接读取 `OrderRuntime` 与 typed patch before-value，matcher 前置撤单只携带最小身份，不构造 `CoreOrderState`；批量失败走
  `CommandCheckpoint` typed rollback，而不是 immutable snapshot baseline 或通用 restore。
- 强平和交割/行权结算的订单撤销均按确定性 cursor 分批执行，单个 Core 命令最多处理 1,024 笔订单；强平 provider
  通过一个 `EXECUTE_LIQUIDATION_BATCH` 同时提交有序 action 和可选 Risk Scan continuation，订单阶段完成后才推进用户阶段。
  每个批次共享最多 `1024` 笔撤单预算，Core 以 `nextCursorOrderId` 保存独占下一页位置。
  生命周期进度保存在 Core 状态中，但 pending matcher continuation 不写入 snapshot。matcher 异常、超时、
  malformed result 或 Core/matcher 分歧直接抛出 `FatalMatchingDivergenceException`，Cluster Member 失败关闭，
  不 rebuild、不 retry、不 resubmit；生命周期期间同 symbol 的普通订单被拒绝，其他 symbol 仍可提交。
- snapshot/query/lifecycle fence 停止新派发并排空 bounded window；不同 symbol 可在原生 shard 并行，但全局可见性、
  Core Fact 和 outbox 永远只按 Core sequence 提交。测试固定使用单个 `matchingEnginesNum(1)`，不得创建第二个 Adapter/ExchangeCore。

生命周期批量协议使用 `EXECUTE_LIQUIDATION_BATCH` wire code 43。`CoreLiquidationWorkCodec` 返回 action 的
`ORDERED` cursor 和精确 Risk Scan token；`CoreLiquidationBatchResultCodec` 返回 offered/applied/pending/obsolete/
processed 计数。`LIFECYCLE_IN_PROGRESS` 明确拒绝重叠生命周期。旧的单 action 强平和 `CONTINUE_RISK_SCAN` 命令仅保留给直接工具，
正常 provider 周期不调用它们。

| 生产者 / Core 组合 | 结果 |
| --- | --- |
| 新 Work v2 + 新 Core | 支持批量 action、共享 1,024 撤单预算和 cursor 恢复 |
| 新 Work v2 / batch producer + 旧 Core | 版本校验失败并拒绝，不按旧 payload 猜测解码 |
| 旧单 action producer + 新 Core | 仅在直接工具路径使用旧协议；provider 正常维护周期不走该路径 |
| 旧单 action producer + batch-only provider | 不支持，必须升级 provider 后再启用维护周期 |
- 下单、撮合、资金、风险、强平和生命周期热路径不访问 JDBC、Redis、Kafka 或 HTTP；这些系统只做输入桥、
  异步导出、投影、审计和查询。
- 幂等结果窗口有界；窗口外仍用 `(source, sourceId)` 的序列高水位阻止旧命令再次执行。
- 同步调用超时表示结果未知。调用方必须复用同一 `commandId` 查询或重试，不能生成新 ID。
- `SurprisingAeronClient` 串行提交消息；Gateway 后续通过固定数量的单线程 client agent 扩展吞吐。

## Product Core P1-P5 规范契约

以下条款是已经落地的 Aeron Cluster Product Core P1-P5 确定性状态机规范。Product Core/Aeron 的确定性状态、Cluster Log、Archive 和快照是在线交易
current state 的唯一权威；PostgreSQL 只负责 Instrument 管理和 history 历史投影，Kafka 只承载审计 history 历史，二者均不得
参与同步交易裁决或覆盖 Core 内存状态。

### P1：命令、预留与唯一订单簿

当前实现状态：P1 已完成。正式下单不执行独立 preflight 往返；同一个 `PLACE_ORDER` command 完成权威校验、
reservation 和 matcher 提交。只读 preflight 只服务显式 dry-run/test API。

- 每个命令按 Aeron `Core sequence` 在 Product Core owner 上执行；命令内同时裁决 User State、Reservation、订单生命周期和
  订单簿输入，提交给 exchange-core 的结果不得再被业务层否决。
- 普通衍生品开仓必须为全量数量预留 reservation price 加最坏正手续费的完整 exposure；只有明确 `reduce-only` 命令可以省略
  开仓保证金。所有平仓侧承诺占用独占的 `PositionCloseCapacity`，冲突的 reduce-only resting order 必须在进入 matcher 前按
  Core sequence 确定性地从最新到最旧取消或缩量。
- exchange-core 的 `MATCHING_ONLY` book 是唯一可执行订单簿；Product Core 只保存业务订单元数据和状态，不计算或维护可执行的
  FIFO book。limit、execution、reservation、mark 四种 price 必须在命令和 Core Fact 中保持不同语义。

### P2：确定性撮合推进与未知结果

当前实现状态：P2 已完成。

- Adapter 允许 bounded MATCHING_ONLY matcher commands in flight，按 Core sequence 提交并直接取得
  exchange-core 的不可变 `MatcherResult`；结果至少携带 Core sequence、command ID、order ID、instrument version、
  process-local native sequence、可恢复 matcher sequence、matcher shard id 和滚动 `MatcherPrefix(before, after)`；
  matcher 命令。事件链对象可以在 exchange-core 内池化，但越过 adapter 边界的结果、事件和 market data 都是不可变值。
- 滚动 prefix digest 覆盖前一 digest、命令身份、结果码、成交、撤单和完整 matcher event；Product Core 应用时必须
  验证 `before` 等于当前已应用 digest 且 matcher sequence 单调递增。process-local native sequence 与按本地刷新节奏
  附带的可选 market data 只用于诊断/行情，不参与跨 Member prefix。普通命令不调用全量 order-book report，不再生成
  或传输逐命令 `BookHashes`。
- 缺失、超时、malformed、无法识别或 prefix/sequence 不匹配都必须标记为 `unknown result`，立即 poison 当前 Member 并
  `fail closed`：停止接收会改变交易状态的命令，保留可诊断证据，不 retry、rebuild、re-submit、按订单回放或猜测恢复。
  wall-clock readiness 和 watchdog 由 external health supervisor 负责告警、摘除和人工恢复，不写入复制状态。
- 任何 snapshot 只能捕获一个精确匹配的 Core/book prefix（matched book prefix）：Core sequence、matcher sequence、
  matcher prefix digest、snapshot position、matcher module hash 和完整 `bookStateHash` 必须来自同一已完成命令边界。
  恢复时任一字段不匹配即拒绝并 fail closed；pending callback 不得进入快照。完整订单簿 hash 只在 snapshot、恢复和
  显式审计边界计算，不进入普通交易命令热路径。

### P3：Runtime 唯一交易裁决状态

当前实现状态：P3 已完成。

- `TradingRuntimeState` 是六条产品线生产热路径的唯一 mutation authority。只有 Product Core owner thread 可以原地写入 Runtime State；
  外围服务、异步 matcher callback、PostgreSQL、Kafka 和 query projection 均不得直接写入。
- `PreparedChanges` 收集 owner 命令内每个 typed key 的首个 before 与最终 after；可回滚的 business/funds hash transition 完成后，
  只 seal 一个 `RuntimeCommitPatch`。该 patch 是 indexes、journal、Core Fact 和恢复的唯一提交载体，不存在 persistent delta tree、
  shadow baseline 或 immutable outcome 回写 Runtime。
- `RuntimeCommitIndexes` 先按 patch owner group 增量更新九个索引，随后有界 `RuntimeCommitJournal` 由单 projector 以连续 sequence、
  item/byte batch 更新 mutable projection。只有 snapshot/query fence、关闭或恢复把该 mutable state 冻结为 `TradingCoreState`；
  普通命令不创建 per-command immutable state。
- Core Fact v10 从 sealed patch 的 typed before/after、funds posting、tombstone 与 terminal ID 异步批量编码至 bounded exporter；
  owner 不等待 projection 或 Core Fact materializer。任何 admission、hash、index、publish 或 projector 失败均 fail closed，
  不推进可见 sequence，且按已完成阶段回滚/回收。
- Core Fact 的 typed fragment 在 owner 已 sealed 的 patch chain 上离线合并；`FactIdentitySlice` 提供精确用户/订单/持仓/资产
  identity，`FactBudget` 在 mutation 前预留 chain node、item 和 byte 上限。off-owner encoder 只消费这些 typed 值，不能回读
  Runtime、冻结 `TradingCoreState` 或创建 fallback fact。
- `USER_STATE`、`ORDER_STATE`、client-order、活动订单、Treasury、风险、ADL、清算工作和生命周期进度查询都读取 Runtime 或其 ID 索引；
- 产品线划转的扣款、入账和完成都在 owner thread 内执行纯内存命令；源 Runtime 使用有界 pending 索引支持前向恢复，
  不执行数据库、Kafka、HTTP、锁等待或 Future 等待。
  无分页协议设定固定实体/扫描上限，超限返回 `QUERY_RESPONSE_TOO_LARGE`。除异步 book capture 外，查询只占用一次
  有界 owner-thread CPU 片段，不做全局 materialization，也不会等待数据库、Kafka、Valkey 或 matcher callback；因此慢查询或超大结果
  不能把交易下单拖入外部 I/O 等待。
- 主源码不再提供 immutable outcome 反向覆盖 Runtime 的 delta applier。状态索引和 business-state hash 都从 sealed patch
  增量推进；exchange-core 仍是唯一可执行订单簿，未被 Runtime 或 frozen snapshot 复制。
- Runtime/materialization 等价检查只保留在测试源码，用于快照恢复回归，不进入生产命令或在线查询路径。

### P4：六条产品线的结算内核

当前实现状态：P4 已完成。`SettlementKernels` 对六个 `ProductLine` 穷尽映射到六个 sealed kernel，未知或不匹配
的产品/合约组合直接拒绝，不走 derivative fallback。

- 结算输入、输出、ledger posting、连续性证据和 idempotency contract 固定；只允许且必须有以下六个 exhaustive kernels：
  `Spot`、`LinearPerpetual`、`InversePerpetual`、`LinearDelivery`、`InverseDelivery`、`Option`。
- 六个 kernel 的差异必须保留在各自的资金、持仓、资金费、交割、权利金、行权、到期、强平和 ADL 规则内；不得使用未命名的
  derivative fallback，也不得把某产品线的订单、账户、instrument、topic 或风险模型混入另一产品线。可共享的仅限纯数学、账本
  posting、连续性校验和幂等工具。

### P5：事实、资金与版本切换

当前实现状态：P5 已完成。

- 每个 command 产生不可变、按 `(asset, ownerKind, ownerId, subledger)` 排序的 `FundsDelta` 和 Core Fact；before/after state hash、
  funds hash、Core/book prefix 和 Aeron position 必须共同标识同一裁决。Audit Exporter 从 replicated outbox 发布 Kafka history，
  History Projector 再幂等写入 PostgreSQL；投影结果不是 current state。
- 当前写格式为 command/envelope v4、export event marker v10、trading snapshot v24、matcher snapshot v5、
  sectioned snapshot v17。decoder、snapshot loader 和 startup 只接受当前版本；旧版本一律拒绝并 fail closed，只能从
  fresh compatible Product Core state 启动，不保留旧 codec reader、迁移读取路径或隐式降级。

### P10：确定性 Lane 与容量门禁

P10-A 至 P10-F 的写路径、协议、快照、Lane-local settlement 和 Lane 原生 Treasury delta 已切换到 route v3。
P10-G 由 `HttpOpenLoopWorkloadMain` 的
`qualification=P10` 门禁强制检查 1,000 用户、至少 200 symbol、100k/s offered rate、40 分钟和活动 JFR，
并要求计量窗口内实际终态吞吐不低于 100k/s；输出目录保存
coordinated-omission-corrected HDR、逐请求事件和 accounting JSON。真实三节点/Provider 环境未生成完整 artifact 前，
只能描述为“P10 实现完成、生产认证未完成”，不能描述为“P10 生产认证完成”。
JFR 中的 `com.surprising.HttpWorkloadMeasurement` 事件精确标记开放环计量窗口，drain 发生在窗口结束后，不能用
事后排空掩盖计量窗口内吞吐不足。

运维可通过 `ClusterProbeMain -Dsurprising.aeron.probe-mode=metrics` 查询 Core 内部 Lane 指标并输出 Prometheus
text format。该查询走 committed Core query surface，不读取 PostgreSQL；采样与计数由独立
`AccountLaneMetricsTracker` 维护，业务 `AccountLaneView` 不包含监控字段；标签固定为产品线、Lane 类型/编号和有界操作类型。

## 六产品线资金守恒契约

六条产品线均使用同一套交易事实和对账边界，差异只由 `ProductLine` 映射到对应的
`ContractType`、结算资产和生命周期能力：

- SPOT：成交资产转移和手续费入 Treasury。
- LINEAR_PERPETUAL / INVERSE_PERPETUAL：成交、正负资金费、标记价、风险扫描、强平、保险基金和 ADL。
- LINEAR_DELIVERY / INVERSE_DELIVERY：成交、手续费、分批 cursor 结算和交割后解冻。
- OPTION：CALL/PUT 的 ITM、ATM、OTM 成交、权利金、手续费和到期结算。

Core 内统一按 `用户可用余额 + 用户冻结余额 + 手续费余额 + 保险基金余额 - 保险基金赤字`
对每个结算资产做守恒校验；交割、期权、强平和 ADL 的生命周期流水只能改变资金归属，不能制造或吞掉资金。
真实链路的 W4 清单要求六条产品线都观察到非零手续费入账，永续同时执行正、负资金费，并在 Treasury
对账后才允许生成 `FUNDS_DIFFERENCE=0` 结果。单元矩阵另外覆盖 maker/taker 费率、交割/期权派生现金、
强平手续费上限、保险基金全额/部分覆盖、ADL 覆盖和 snapshot cursor 恢复。

## W1/W2 原生快照契约

- fork 坐标为 `exchange.core2:exchange-core:0.5.16-emporia`，Git SHA
  `4c4d163b6ba736a43360b325cdd7b9fb8c20648d`，可复现 JAR SHA-256
  `d4ab72853924edc32069ab7158e7bcc5d374ecc1bcd594df04128ab459732b86`；fork 只允许 clean
  worktree 构建，从该提交的不可变 `git archive` 编译，并在 JAR 生成后重新认证仓库和内嵌 SHA；
  service 的 Maven `validate` 同时校验 provenance 与整包 hash。
  matcher 内部启用事件链池化，池对象只在 exchange-core 内复用；对外 `MatcherResult` 为不可变值，普通交易热路径不执行全局状态报告。
  开放订单报告和 Core 对账均为 O(活动订单数)，不做排序。
- `Trading snapshot v24` 是唯一外层交易快照写格式；matcher snapshot v5 保存全部
  `MATCHING_ENGINE_ROUTER/[0..N)`、`RISK_ENGINE/[0..R)`，并按 `[-1, 0..N)` 保存 control/native shard 的独立
  evidence sequence 与 prefix。`sectioned snapshot v17` 按相同 Core/book prefix 拆分载荷，
  并保存按 laneId 升序的实际 Account Lane state section；三个 Member 必须运行完全相同的 topology、fork、配置和 schema。
- capture 在单共享 ExchangeCore 与 Core state 的配对 snapshot fence 内等待全部 native shard module 和 callback；
  pending matching 存在时拒绝发布。当前不存在第二个 ExchangeCore 或 `SymbolMatchingLanes` 运行时。
- Aeron fragment 在复制前执行 64 MiB 外层上限；matcher envelope 为 48 MiB、单个原生 module 为 32 MiB，
  超限时 fail closed。修改这些上限必须同步默认 heap 并完成目标活动订单规模的快照容量测试。
  恢复先校验三层 CRC32C、产品线、默认 shard/route、snapshot ID、Core/matcher sequence、Cluster
  timestamp/position、source digest、outbox cursor/pending digest、fork/config/artifact、symbol/user/instrument
  registry、完整 engine/book hash，再以 O(活动订单数) 一次报告逐字段核对 OPEN 订单；全部通过后才替换内存状态。
- snapshot、恢复、异步 continuation 的任何不确定失败都走失败关闭路径；不允许 clean-start 降级、订单回放、
  隐藏 FIFO、matcher journal 或跨 Member 部分恢复。
- 只接受当前 v24/v17 的 fresh compatible state；command schema v4、export marker v10、trading snapshot v24、sectioned snapshot v17
  以外的输入在 decode/startup 立即拒绝并 fail closed。没有旧 reader、迁移读取路径或使用 PostgreSQL 投影、clean-start、逐单回放
  修复不一致状态的例外。
- `UPDATE_RISK_SCAN_CONTROL` 使用乐观版本检查，`RISK_SCAN_CONTROL_QUERY` 返回当前版本、启停、续跑间隔、
  批次上限和审计元数据；状态随 Cluster Log/Archive 与 `Trading snapshot v24` 恢复。
- `APPLY_MARK_PRICE` 只提交新价格和初始化 risk/trigger cursor；用户风险、强平计划和触发单扫描只能由有界
  `CONTINUE_RISK_SCAN` 推进，不能在标记价命令中隐式执行首批扫描。
- matcher 等待策略默认 `YIELDING`，可在隔离物理 CPU 时显式配置为 `BUSY_SPIN`，或配置为 `BLOCKING`；Account Lane 可配置为
  `BUSY_SPIN`、`BALANCED` 或 `PARK`。等待策略是运行时性能参数，不进入业务状态或 snapshot hash。
