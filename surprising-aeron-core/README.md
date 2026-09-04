# Surprising Aeron 统一交易核心

本目录承载按产品线隔离的 Aeron Cluster 交易核心。每个 `ProductLine` 变体使用相同代码、独立 `clusterId`、
端口空间、Archive 和数据卷；一个逻辑 Core 固定由三个 Member 组成，并管理该变体全部 symbol。

W1/W2 已完成单一可执行盘口改造：`TradingCoreRuntime` 是 Core 单写边界，撮合命令只进入 fork 的
exchange-core 0.5.18-emporia；`TradingCoreState` 不再保存 `CoreBookState` 或任何 FIFO priority map。
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
| `surprising-aeron-tools` | Cluster 探针、状态 hash 查询和只读离线 replay 诊断；不得作为生产恢复或 snapshot 来源。 |

## 当前按 Symbol 分片的 Matcher 流水线主链路

每个 Product Core 仍只有一个 Aeron Cluster service owner，但可以在 fresh compatible state 启动前配置 1–64 个、
数量为 2 的幂的 matcher shard。每个 shard 固定拥有一个 worker、一条预分配有界 SPSC ring 和一个独立的
`SynchronousMatchingEngine(matchingEnginesNum=1)`；symbol 通过 `routeVersion=3` 稳定路由，运行中不 rebalance。
exchange-core risk engine 固定为 0。不同 shard 的完成队列互不等待，同一 symbol 始终在同一 matcher worker 内保持 FIFO。
各 shard 可以乱序完成并写回各自的 sequence context，但 owner 只从全局连续完成头按序安装 Account Lane 终态并发布 Aeron
边界；因此撮合可以并行在途，同账户资金和订单仍保持确定性顺序。
snapshot、全盘口 bootstrap、全局状态 hash 和 userId=0 控制命令仍建立显式全分片 fence。

PLACE 在进入 matcher 前先以单向事件投递给用户所属 Lane，由该 Lane 串行完成余额冻结、order/reservation 和 client-order
索引写入；owner 只收集已完成的准入结果并提交 matcher。matcher 返回后，owner 再把同一条不可变 matcher fact 按 userId
路由到目标 Lane。固定 Account Lane worker 永久拥有本 Lane 状态并串行完成
资金、订单、持仓和手续费变化，并生成该 sequence 的最终索引值和资金增量；活动订单准入统计由 Lane 按 user/symbol 随订单变更
增量维护，准入不再扫描用户订单。Coordinator 根据 completion bitmap 按 sequence
安装这些终态、合并 Treasury delta 并发布响应和 Aeron 边界。
Account Lane 按 owner 发布的全局连续 Core sequence 串行应用，因此跨 symbol 的同账户事件不会因 matcher 乱序完成而重排。
普通命令需要推进多个 Lane watermark 时使用可复用的 sequence-local `LaneCommitEvent` 一次投递到全部目标 SPSC ring，owner
只观察 completion bitmap；允许固定 256 个 commit sequence 同时在途，不再逐 Lane `await/awaitConsumed`。同步命令响应边界仍等待
该 sequence 的完整 bitmap，查询和 snapshot fence 仍保留必要的一致性等待。不存在 Disruptor、逐命令 Future、临时 Runnable
fan-out 或 Owner/Lane 所有权切换。

Account Lane 同时是资金隔离和业务执行边界。多成交、多用户、跨 Lane 的一笔命令共享同一条
`MatcherSettlementEvent`，不是为每笔 fill 创建 maker/taker 两个任务。Lane 可以连续应用多个 sequence；
一个 Lane task 同时完成账户变更、pending reservation 终结、订单 commit metadata、applied/committed watermark 和终态增量生成，
不存在成交后的第二次订单 stamping Lane 往返或第二个 commit task。Coordinator 只按 completion bitmap
推进连续 sequence 和 Aeron 发布边界。任何 Lane 或提交不变量失败都会 fail-stop，并从 snapshot/Cluster Log
恢复，不在交易热路径对已应用 Lane 做反向回滚。

`RuntimeCommitJournal` 只保留 entry 容量准入和连续 sequence，不维护按字节容量、逐命令审计 hash 或热 projection
replica、projector 线程或 per-command freeze。普通命令只更新权威 mutable runtime、增量索引和可复用
`RuntimeFactFrame.Builder`；不构造逐命令完整状态副本。`TradingCoreState` 与完整业务/资金 hash 只在显式
query/snapshot fence 直接物化。生产不启动 Core Fact materializer；`CoreExportEvent`/codec 仅作为未来历史出口的
协议边界保留。

Cluster 本地进度不写复制 timer。Aeron publication 遇到 `BACK_PRESSURED`/`ADMIN_ACTION` 时保留有界 egress，
遇到 `CLOSED`/`NOT_CONNECTED`/`MAX_POSITION_EXCEEDED` 时 fail closed。默认 UDP term length 为 16 MiB，
Archive 本地 control 为 1 MiB；term buffer 使用非 sparse 文件。线程模式可显式配置，否则 12 CPU 及以上用
`DEDICATED`，更小机器用 `SHARED_NETWORK`，避免在桌面环境制造无意义的 busy-spin 争用。

## Owner commit 单一路径（P11）

本实现改动触及共享 protocol 与通用 Product Core owner commit；运行时与性能验证范围仅为
`LINEAR_PERPETUAL`。其他五条产品线没有以本轮命令、JMH 或 JFR 验证，不能据此推断其性能或生产认证状态。每条产品线仍各自拥有
`ProductLine`、账户 Lane、instrument、topic、风险和结算边界，绝不跨线合并订单或资金状态。

唯一写路径为：Account Lane worker 原地修改各自拥有的 `TradingRuntimeState` 分区，owner 在命令边界把首个 before 与最终 after 收集为
`PreparedChanges`；business/funds rolling hash 完成校验后只 seal 一份
`RuntimeFactFrame`。该 fact frame 驱动 `RuntimeFactIndexes` 和轻量 sequence journal；普通下单、撤单、成交和风险命令
不全量 materialize，也不等待后台 projection。显式 snapshot/query fence 直接从权威 runtime
构造不可变视图；任何 sequence、hash、产品线或 Lane 拓扑不匹配均在替换状态前 fail closed。

`RuntimeFactIndexes` 的唯一 fact frame apply 入口覆盖九个索引：`PositionUserIndex`、`OpenInterestIndex`、
`TriggerOrderIndex`、`AlgoOrderIndex`、`LiquidationIndex`、`CancelAllAfterIndex`、`ActiveOrderIndex`、
`AdlPositionIndex` 与 `RiskSnapshotIndex`。terminal ID 只在运行时索引和恢复所需边界保留，不为历史投影维护 tombstone。
订单的 canonical business/export view 只在 owner 的 `prepareCommitPatch` 中由一处 `recordOrder` producer 预封装；
fact frame 的 typed change 随后由 index 和恢复边界直接读取，不再生成 `businessAfter` / `exportAfter` 状态副本。

owner 提交暂存采用可复用 `RuntimeFactFrame.Builder`；reset 只清理本轮触碰的槽位，materialized fact frame 仍持有独立不可变值。
用户与余额 before-value 按 Account Lane 写入 primitive journal，避免共享 `ConcurrentHashMap<Long, ...>` 的装箱和节点竞争；
Lane 内 order、reservation、position、client-order 和 pending reservation 索引均使用 primitive key/value 容器；
`TreeMap`/排序只允许出现在 query 或 snapshot 边界，不进入普通 PLACE/成交 mutation；
命令级 changed-ID 使用 first-touch primitive 数组和 generation 哈希索引，clear 不扫描历史容量；无删除项的 Core Fact 复用空 tombstone，
有删除项时也只创建实际使用的类别列表；
资金 posting 按本命令 first-touch 顺序直接线性合并，不排序；rolling hash 直接前向消费 typed change，
不创建 staging operation 数组、镜像 reverse fact frame 或每用户临时更新对象。
未来历史出口只需消费已提交的 typed event 并通过 `CoreExportCodec` 编码；当前交易命令不创建历史 event buffer。

准入只预留当前 transaction 的 fact frame 数/字节，之后才允许 mutation；reservation 只能消费一次，提交前拒绝或失败必须
释放。owner、Account Lane、matcher 与 risk 的失败会 poison 相应路径；观察并应用 matcher fact 后不撤销 Lane 或 hash/index
transition，而是停止服务并从 snapshot/Cluster Log 恢复，且不会推进可见 fact frame/core sequence。资金 posting 以 `(asset, ownerKind, ownerId, subledger)` 的 before/after 精确导出，
含 fee、insurance、funding、liquidation、rounding、clearing、deficit 后逐资产守恒；materialized fact frame 的 business/funds hash、
idempotency response、订单终态与 snapshot/replay hash 必须一致。

批量订单先在 owner 上做 mutation domain 与容量 preflight，并保存一个 primitive runtime revision；
`BatchAdmissionOrderIndex` 只读取 `currentPatchOrderBefore` / `currentPatchPositionBefore` 的 typed before-value。
它不以 `TradingCoreState`、snapshot、projection await 或 full materialization 作为基线。fatal batch 路径统一进入
`failOrderBatch`：只要已经观察到 matcher fact 就直接 poison/fail-stop，不回滚已应用的 Lane、position identity、hash 或 index；
实例从最后一个 committed snapshot 与 Cluster Log 恢复。只有 matcher fact 发布前的 batch preflight 拒绝才使用 typed before-value
撤销未发布 mutation，并按逆序回收预分配 client-order key。这样批量 idempotency、Lane 隔离和 admission/open-interest 语义仍在同一 owner commit 边界内。

保留的背压参数只有当前 transaction 的 commit admission 字节上限。旧 projection、export materialization 和 matcher
completion 等待参数不参与生产链路；只有有界 admission 能影响新命令接收。

静态/目标验证（执行前必须确认 HotSpot JDK 25，且本任务未执行这些 Java 命令）：

```bash
java -version 2>&1 && mvn -version
mvn -pl surprising-aeron-core/surprising-aeron-service \
  -Dtest=TradingCoreRuntimeAuthorityTest,W1W2InvariantFenceTest test
surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh jmh
```

最后一条资格命令固定使用 HotSpot 25、ZGC、10,000 users、512 listed/active symbols、4 个逻辑 Account Lane、
1 个同步 matching engine 和 256 in-flight；其 scale、GC、JFR、soak 与 saturation artifact 会写入当前脚本指定目录。JMH/JFR 只能在前置审计、目标测试和
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

当前写格式为 command/envelope schema v4、trading snapshot v24、matcher snapshot v5 和
sectioned snapshot v18；decoder 和 startup 只接受这些当前版本，对任何旧版本 fail closed，不保留 legacy reader、兼容 reader
或隐式降级。

P10 的目标不是物理 Core shard；每个三节点 Product Core 只运行一个 Adapter 和一个 exchange-core matching engine。
生产默认 `accountLaneCount=4`，每个 Lane 由一个固定 SPSC worker 永久拥有，负责资金状态、局部 hash 和快照 section。
`MatcherSettlementPlan` 单次校验 matcher events，只保留需要终结的 primitive order ID，并直接引用 matcher 已有的确定性事件序列，
不再复制 user、trade、remaining quantity 和 per-trade Lane-mask 数组；相关 Lane 按 userId 过滤并行消费，Coordinator 合并 Treasury delta、
验证资金守恒，以一个连续 core sequence 发布 Runtime State/Core Fact。BLOCKING/YIELDING/BUSY_SPIN 只控制空闲等待。
Runtime commit 只存一套 canonical sequence；prepare、seal、hash、index、publish 属于同一 owner transaction，失败后 fail-stop 恢复。
P10-G 仍需真实 HTTP/JFR 长稳 artifact；没有对应 artifact 时不得宣称生产认证完成。

## 协议约束

- Cluster Log 权威消息禁止 Java serialization 和无版本 JSON。
- schema v4 header 包含 `commandId`、`productLine`、`source`、`sourceId`、`sourceSequence`、`userId`、
  外部提交时间、`correlationId` 和 route v3；旧 envelope/route 不再接受。
- Instrument Provider 通过版本化 `UpsertInstrumentCommand` 下发保证金率、risk brackets、最大杠杆和
  最大持仓名义价值；CoreInstrumentState 是运行时唯一参数副本，Risk Provider 只能查询 Core 快照。
- exchange-core 0.5.18-emporia 是唯一可执行订单簿（sole executable order book），独占价格树/FIFO；`GTX` 使用原生
  post-only 语义，外层不得查 book 后模拟，也不得建立并行可执行 book。
  Core 的 `CoreOrderState` 只保存业务元数据和活动状态，不保存可重建 FIFO 的 priority sequence。
- adapter 固定使用 `RiskProcessingMode.MATCHING_ONLY` 并禁用 exchange-core margin trading；内部 user/symbol/risk module 是需随 matcher snapshot 恢复的技术状态，不是业务资金、持仓或保证金权威。
- Adapter 只在 matcher worker 上调用 `SynchronousMatchingEngine`，每条命令产生一个确定性的 `CoreMatchingResult`。
  正式性能验收默认使用 1 个 matching engine；matcher 扩展性诊断可使用 2 次幂数量，但必须作为独立的 `256 in-flight` 诊断记录。native sequence 与滚动 prefix 仍写入 snapshot/replay 证据，倒退或 prefix 断裂立即 fail closed。
  命令与结果只经过固定容量 SPSC ring 和当前 `LaneCommandContextRing.Context`，没有 ExchangeCore ring、异步 callback、Future 或 timer。
  `MatcherSettlementPlan` 一次完成结果校验与目标 Lane 计算；后续 Lane 直接读取同一份 matcher event 序列并按 userId 过滤，
  不构造 Lane event slice、maker/taker task、boxed change map 或逐成交 completion 对象。
- Pending matcher ring 同时表达最多 256 个跨线程 in-flight 命令、批量/触发单的确定性 continuation 与故障证据；
  热路不维护逐命令 Future、active set、completed-result map 或 completion token。
- `saturatedMatchingWorkload` 使用共享有界窗口的持续滑动 feeder：同一方向内每完成一组 maker/taker 依赖就立即补入
  下一币对，不再整窗排空后重新提交；一个方向全部完成后才反向，防止预置深度场景产生方向穿越。
  每个 symbol 同一时刻最多一组订单在途，保持账户依赖和 Core sequence 提交顺序；
  每组 maker/taker 固定路由到不同 Account Lane，确保基准真实覆盖并行 settlement/completion bitmap，而不是同 Lane 快路径；
  每个提交批次只等待首个连续完成，随后非阻塞提交该批其余已完成结果并立即 refill，避免 owner 空转与 matcher 争抢 CPU；
  JMH/JFR 同时记录 window/full-window、refill 和 producer-starvation，资格脚本要求测量期存在持续补料且 starvation 为零。
- JFR 热点判断只统计自定义 workload measurement 事件窗口，排除 JMH trial 初始化和 snapshot template。交易窗口内
  的协议 enum 解码无 Stream，typed mutation 不再重复复制已排序 change-set，lazy delta 用有界原子槽缓存变化值；批量
  订单结果和 open-order 状态按精确长度编码，避免每个 item 的中间 frame 与 grow/copy 缓冲。
- matcher 结果使用 typed outcome 区分成交、挂单、拒绝和撤单，并用 primitive UUID 两段值保存 command identity；已知的
  前置撤单 prefix 可以确定性恢复，未知 prefix 或语义分歧仍 fail closed。replace/amend 在进入 matcher 前只执行一次
  identity、reservation 和 admission 解析，完成阶段复用 `ResolvedMatchingAdmission`，不再次做同义业务校验。
- 单用户非撮合命令也通过目标 Lane 的固定 SPSC worker 执行；成交或生命周期命令涉及两个及以上 Lane 时，同一条不可变
  事件直接投递给所有相关 Lane，各 worker 只修改自己拥有的账户状态。Coordinator 非阻塞检查 completion bitmap，按
  sequence 发布 typed fact frame/Core Fact；applied/committed watermark 已在原 Lane task 内连续推进，不再异步投递第二个 commit task；
  不重绑定 Lane，也不执行热路径 rollback。
  `surprising.aeron.settlement-wait-strategy` 可选 `BLOCKING`（默认）、`YIELDING` 或
  `BUSY_SPIN`，正式对照必须保持同一策略并单独报告 busy-spin CPU。
- pending matcher 以 sequence、commandId 和 user 三个有界索引做常数时间定位；不创建 Core Fact materializer 线程或逐 Fact task。
- Cluster response 直接写入 session egress scratch，并以调用时的 committed Core sequence 编码；内部不再构造
  visible response、临时 response payload 或二次 `CoreMessage` payload copy。只有 Aeron backpressure 入队时复制 scratch。
- 单笔交易只发布 sealed typed commit 和轻量 `RuntimeProjectionPoint` sequence；没有 mutable projector 或 projection replica。
  Snapshot/query fence 从权威 runtime 直接构造 `TradingCoreState`。下单、撤单、撮合及批量 owner 路径不执行 full
  materialization，也不等待 Core Fact。普通及批量订单校验
  直接读取 `OrderRuntime` 与 typed fact frame before-value，matcher 前置撤单只携带最小身份，不构造 `CoreOrderState`；matcher fact
  之前的 batch preflight 拒绝只按 primitive revision 与 typed before-value 撤销，fact 之后的任何失败统一 fail-stop。
- 强平和交割/行权结算的订单撤销均按确定性 cursor 分批执行，单个 Core 命令最多处理 1,024 笔订单；强平 provider
  通过一个 `EXECUTE_LIQUIDATION_BATCH` 同时提交有序 action 和可选 Risk Scan continuation，订单阶段完成后才推进用户阶段。
  每个批次共享最多 `1024` 笔撤单预算，Core 以 `nextCursorOrderId` 保存独占下一页位置。
  生命周期进度保存在 Core 状态中，但 pending matcher continuation 不写入 snapshot。matcher 异常、超时、
  malformed result 或 Core/matcher 分歧直接抛出 `FatalMatchingDivergenceException`，Cluster Member 失败关闭，
  不 rebuild、不 retry、不 resubmit；生命周期期间同 symbol 的普通订单被拒绝，其他 symbol 仍可提交。
- snapshot/query/lifecycle fence 停止新命令并确认当前 owner transaction 已终态。测试固定使用单个
  `matchingEnginesNum(1)`，不得创建第二个 Adapter/ExchangeCore。

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

- `TradingRuntimeState` 是六条产品线生产热路径的唯一 mutation authority。只有 Product Core owner 与固定 Account Lane owner 可以按各自边界写入 Runtime State；
  外围服务、异步 matcher callback、PostgreSQL、Kafka 和 query projection 均不得直接写入。
- matcher settlement 的每个 Account Lane 直接生成最终 order/position index value 和本 Lane primitive funds accumulator；owner 按 sequence
  单遍安装这些值并把标量 posting 合并到对应 sequence context，不扫描 matcher plan，不二次扫描终态订单，也不再次查询或物化 Lane 业务状态。`RuntimeFundsDelta`只在校验、事实或异步交接边界物化。
- `RuntimeFactIndexes` 消费 Lane 已准备的终态值与其他必要 changed key；`RuntimeCommitJournal` 只记录连续 sequence 和 entry
  容量。只有 snapshot/query fence、关闭或恢复才把权威 runtime 冻结为 `TradingCoreState`；普通命令不创建 per-command immutable state。
- 生产默认不编码或发布 Core Fact，不启动 exporter、projector 或 materializer。`CoreExportEvent`/codec 仅作为未来历史
  事件出口的协议边界保留；交易 owner 不等待外部历史处理。
- admission、snapshot batch、changed-key、funds delta 和 matcher completion 都绑定到独立 sequence context；完成或拒绝后统一释放，
  不由 `PendingMatching` 与 owner 全局字段重复持有。稳定 asset/symbol identity 由版本化 append-only registry 发布并随 snapshot 恢复。
- Account Lane 写入各自拥有的 runtime 分区并发布固定终态增量；Sequencer 按 sequence 刷新到 owner-only published map，
  不使用跨 Lane `ConcurrentHashMap`，也不构造第二层历史投影集合。
- `USER_STATE`、`ORDER_STATE`、client-order、活动订单、Treasury、风险、ADL、清算工作和生命周期进度查询都读取 Runtime 或其 ID 索引；
- 产品线划转的扣款、入账和完成都在 owner thread 内执行纯内存命令；源 Runtime 使用有界 pending 索引支持前向恢复，
  不执行数据库、Kafka、HTTP、锁等待或 Future 等待。
  无分页协议设定固定实体/扫描上限，超限返回 `QUERY_RESPONSE_TOO_LARGE`。除异步 book capture 外，查询只占用一次
  有界 owner-thread CPU 片段，不做全局 materialization，也不会等待数据库、Kafka、Valkey 或 matcher callback；因此慢查询或超大结果
  不能把交易下单拖入外部 I/O 等待。
- 主源码不再提供 immutable outcome 反向覆盖 Runtime 的 delta applier。状态索引和 business-state hash 都从已提交 runtime
  增量推进；exchange-core 仍是唯一可执行订单簿，未被 Runtime 或 frozen snapshot 复制。
- Runtime 等价检查只保留在测试源码，用于快照恢复回归，不进入生产命令或在线查询路径。

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

- 每个 command 在 owner 内产生不可变、按 `(asset, ownerKind, ownerId, subledger)` 组织的 `FundsDelta`；资金守恒和
  Aeron position 属于当前裁决。历史 Kafka/PG 出口暂不启用。
- 当前写格式为 command/envelope v4、trading snapshot v24、matcher snapshot v5、
  sectioned snapshot v18。decoder、snapshot loader 和 startup 只接受当前版本；旧版本一律拒绝并 fail closed，只能从
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

- fork 坐标为 `exchange.core2:exchange-core:0.5.18-emporia`，Git SHA
  `4636c44b19de90be0bd6c85afdd0e4fa190da9f0`，可复现 JAR SHA-256
  `4a6e41ae66822eddf8539fa8bb80fe77ffc3cc4adc7376d6666b45cf24ee874e`；fork 只允许 clean
  worktree 构建，从该提交的不可变 `git archive` 编译，并在 JAR 生成后重新认证仓库和内嵌 SHA；
  service 的 Maven `validate` 同时校验 provenance 与整包 hash。
  Aeron 依赖固定为 `1.53.0`。matcher 内部使用 `Grouping → Matching → Results` 的 `MATCHING_ONLY`
  直达管线，不创建 R1/R2 风险处理器；热路径用有界 correlation ring 接收不可变 `MatcherResult`，不进入
  exchange-core promises Map、不创建其 Future，也不提交 matcher 用户注册命令。事件链池对象只在 exchange-core 内复用；普通交易热路径不执行全局状态报告。
  开放订单报告和 Core 对账均为 O(活动订单数)，不做排序。
- `Trading snapshot v24` 是唯一外层交易快照写格式；matcher snapshot v5 在直达模式只保存
  `MATCHING_ENGINE_ROUTER/[0..N)`，不生成 `RISK_ENGINE` module，并按 `[-1, 0..N)` 保存 control/native shard 的独立
  evidence sequence 与 prefix。`sectioned snapshot v18` 按相同 Core/book prefix 拆分载荷，
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
- 只接受当前 v24/v18 的 fresh compatible state；command schema v4、export marker v10、trading snapshot v24、sectioned snapshot v18
  以外的输入在 decode/startup 立即拒绝并 fail closed。没有旧 reader、迁移读取路径或使用 PostgreSQL 投影、clean-start、逐单回放
  修复不一致状态的例外。
- `UPDATE_RISK_SCAN_CONTROL` 使用乐观版本检查，`RISK_SCAN_CONTROL_QUERY` 返回当前版本、启停、续跑间隔、
  批次上限和审计元数据；状态随 Cluster Log/Archive 与 `Trading snapshot v24` 恢复。
- `APPLY_MARK_PRICE` 只提交新价格和初始化 risk/trigger cursor；用户风险、强平计划和触发单扫描只能由有界
  `CONTINUE_RISK_SCAN` 推进，不能在标记价命令中隐式执行首批扫描。
- Risk continuation 按确定性的 `accountLaneId` 升序、Lane 内 `userId` 升序推进；默认64 work-unit上限覆盖实际持仓和预留遍历，
  不是用户数量。当前 Lane 在一次 Lane task 内连续处理本 Lane 的用户和单用户分页，owner 只在 Lane completion 边界更新 cursor
  与全局 liquidationId，不再逐用户同步往返；中途 cursor 由 trading snapshot 和 Cluster Log 恢复。
- 同一结算资产出现多个 `INSURANCE_REQUIRED` 时，保险基金按当前未决 deficit 比例生成确定性建议份额；除不尽的最小单位按
  `triggerPriceSequence、userId、symbol、positionSide、liquidationId` 分配。`RESOLVE_LIQUIDATION` 只能按该优先级逐项提交，
  Core 会按当前权威状态重算并校验 `recommendedCoveredUnits`，禁止调用方抢占后续用户份额；余额耗尽时允许 0 覆盖并确定性转入 ADL。
  该扫描、排序和大整数除法只位于 insurance 查询/结算边界，不进入 matcher、Account Lane 成交或 risk scan 热路径。
- 每个 matcher shard 固定为一个同步 engine；Account Lane 是固定线程拥有的执行边界。正式性能验收仍固定单 shard，
  wait strategy 只控制空闲等待，不改变业务语义。
