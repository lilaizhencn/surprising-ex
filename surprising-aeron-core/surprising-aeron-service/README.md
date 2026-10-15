# 核心提交日志回放

`CommittedTradeReplay` 由独立导出进程持有核心状态，只应用 Archive 已提交的命令；不会向在线 Cluster 发送交易命令。订单和成交沿用实时捕获，资金费通过 `PerpetualFundingCommands` 的回放观察接口交出实际支付结果，形成不可变的 `CommittedFundingPage`。该对象的生命周期仅为当前完整命令，下一次 apply 清空；没有第二份资金账本或长期支付缓存。

资金费页保留产品线、日志位置、核心时间、实际命令费率、完成游标及支付明细。重复或被拒绝的命令不产生支付页。在线异步 Account Lane 路径不安装观察接口、不读取数据库，资金计算、快照格式和核心权威状态不变。查询投影在 realtime 模块持久化，SQL 成功后才能推进导出 checkpoint。

HotSpot JDK 27 下 `CommittedFundingReplayTest` 覆盖 U 本位及币本位永续的实际多空支付、分页、快照恢复和重复命令；原有 funding processor、异步 funding、订单成交回放及权利金/资金费回归一并执行。此改动供独立回放使用，不要求重启在线核心。


## Matcher 结果与账户结算交接

`publishedPositionCount()` 只读取 Owner 已提交的持仓记录计数，不再扫描 Lane 权威表或申请账户读取栅栏。
所属 Lane 在既有 `LaneCommitDelta.preparePositionRetirement` 中记录当前表大小；Owner 收集控制、批量准入、
成交终态回执时，以每 Lane 上一次计数的差值更新总数。每个回执保存自己的命令边界，Lane 后续命令
不能改变较早回执的计数；回收时清空标记。初始化及命令回滚也通过相同交接更新。
计数沿用持仓记录数语义，包含权威表保留的零仓位记录，不代表未平仓量。
这只增加固定长度的 Owner 计数元数据，没有持仓对象副本；按 ID 查找等其他迁移仍需继续。
功能范围见 [验证清单](../../docs/validation/published-position-count-20261015.json)。

用户、订单及 clientOrderId 内部查询由 `OrderBookQueryService.AccountQuery` 将已选定的
`AccountLaneState` 传入 `RuntimeStateQueryService`。投影中的用户、余额、预留、持仓、杠杆与订单读取
均使用显式 Lane 参数，校验该 Lane 属于当前运行时且由当前线程持有；不再通过查询门面反查 worker。
排序、临时余额副本、DTO 和状态哈希仍只在查询边界构建。Owner 只使用既有路由并消费已编码结果，
保留终态订单没有活跃路由时仍由各 Lane 原位查询，不引入全局订单镜像。
风险及其他控制路径的显式参数迁移仍未完成。
功能范围见 [验证清单](../../docs/validation/explicit-account-query-lane-20261015.json)。

账户分区按 `LaneTopology` 隔离，由对应的 `SettlementLaneWorker` 多线程消费。主线采用实验目录已修复的实现：Owner 定序并准备命令，Matcher 产生确定性撮合结果，`MatcherSettlementDispatcher.publishMatcherOwned` 通过每个 Lane 的分片 SPSC 队列直接发布成交及撤单结果，所属 Lane 串行应用账户结算；完成回执到达后，Owner 按提交顺序发布终态响应。

Matcher 在 `MatcherSettlementEvent.notifyDirectPublication()` 中校验结果并完成 Lane 投递，再发布就绪位。池化事件必须等实际消费者完成后才能复用。工作线程异常传给 Owner，避免命令在消费者退出后继续等待。全局控制命令沿用该实现已有的 Lane 所有权交接边界。

单笔及批量撤单的 Owner 入口只通过现有 `ActiveOrderIndex` 校验归属并准备路由，不读取 Lane 正在增删的 `orders` 普通 HashMap。`cancelIdentityAndPreparationNeverReadLaneOrderTables` 确定性禁止这类读取；跨用户仍拒绝，缺失订单仍返回 `ORDER_NOT_FOUND`，实际取消和释放冻结由所属 Lane 完成。编码层符号缓存、DirectBuffer 读取及实验目录已有低分配改动一并保留。

HotSpot JDK 27 的干净构建及 167 项撮合、资金、并发顺序、撤单、协议和实时投影契约回归通过；其中六产品线的单笔和批量撤单均有覆盖。本轮未执行 Aeron Cluster 快照或恢复场景，也未启动 wallet。吞吐和限频记录见主 README 的本次采用说明；本轮未据此宣称全链路零堆分配。


### 2026-10-11 Lane 活动上下文与停车唤醒

`DeterministicExchangeCoreAdapter` 的命令时间戳/scope 和 primitive 用户注册表分别归每个 Matcher 分片线程，不再以一个共享字段替代 ThreadLocal。`TradingRuntimeState.currentLaneCommandScope()` 根据实际工作线程取得所属 Lane，Owner 使用独立的控制 scope；无关查询线程不能借 Owner 的活动 scope 获得写权限。Scope 进入、完成和回收不新增业务镜像。 这是当时移除 ThreadLocal 后的实现，仍有线程身份比较和 worker 线性遍历成本，不能视为 Lane 上下文开销已根除；2026-10-15 的显式 Lane 参数改造继续消除实际业务路径上的反查。

`SettlementLaneWorker` 先公布停车标记，再复查准入邮箱、控制队列、直接 Matcher 事件和所有权交接；生产者在停车标记为 true 时才 unpark，忙时不逐任务调用。仍保留 100µs 定时兜底和关闭唤醒。226项回归及50项边界/微基准测试执行全部通过（含Owner/Lane并发隔离）。固定256窗口的最新真实单节点观测408,903.948业务项/s、资金差/未完成0；CPU限频使容量证据无效，不宣称50万或完整Owner迁移完成。JMH提交边界约1472B/次，整链路仍有对象分配。

Owner仍有部分权威业务读取及Treasury写入，因此 pending reservation 监视器暂留；只删锁不能保证共享状态正确。外部账户查询继续使用启用 realtime 后的 Redis/Valkey 投影。完整记录见 `../../docs/validation/lane-owned-scopes-wakeup-20261011.json`；本轮没有快照、恢复或wallet场景。

### 普通下单的平仓容量检查

`PlaceAdmissionEvent.execute` 在所属 Account Lane 内校验持仓与存量订单，普通平仓超过可用容量时选取最新冲突的 reduce-only 订单，仅保留不可变撤单 ID。Lane 完成冻结准入后，Matcher 把这些 ID 与 Owner 从路由索引确定的自成交撤单合并、去重并按订单 ID 执行，随后撮合新订单；Owner 不等待准入，也不在这条准备路径读取 Lane 持仓或订单表。

资金准入被拒绝时不执行前置撤单。撤单成功后新订单被 post-only 拒绝，结果明确标记已接受的撤单前缀，并按完成收据中的最终授权校验、释放冻结；不会当作没有状态变化的拒单。收据必须在发布 Matcher 消费标记前转入池化结算事件，回收后不再读取准入事件字段。保留现有同账户命令 fence 与 Lane FIFO，不新增全局账户镜像或 Owner 合并阶段。

触发单、批次最终响应/元数据及 Treasury 写入尚未全部迁移。局部性能采集计划见 `../../../performance-artifacts/lane-parallel-20261010T144735Z/lane-close-validation-plan.json`；结果按时间追加到根目录 `PERFORMANCE_VALIDATION.md`。本轮仍排除快照、恢复和 wallet 场景。

### Matcher 财务回执的线程交接

`MatcherSettlementEvent` 保留一个可复用的财务槽。Owner 将其投递给 `SystemLedgerLane`；队列满则保留该槽重试，即使账户已完成也不允许收集。账本的 readiness 只 acquire 读取各账户完成位，不修改 Owner 的完成缓存，不调用账户结算。Maker 和 Taker 由各自 Account Lane 独立推进。

两侧完成后，账本在原 `TreasuryRuntime` 上应用手续费等原语 delta，将账户和财库的资金变化放入槽内缓冲并校验守恒。发布财务完成前捕获通知目标，此后不再访问事件；Owner acquire 完成后交换原语缓冲，再收集路由及终态结果。重复收集、非空目标、提前回收均受校验；非空目标拒绝不会消费回执。

新增财务槽承担跨线程所有权和回收边界，既不复制账户状态，也不新增全局资金镜像。该路径已用跨 Lane 实际手续费和满队列/复用用例独立验证，但 `TradingRuntimeState` 尚未启用生产 `SystemLedgerLane`。剩余资金费、保险、强平和提交/查询路径完成迁移后才可启用；当前仍不可宣称 Owner 已退出资金写入。记录见 `../../docs/validation/matcher-ledger-receipt-20261011.json`。


### 2026-10-11 普通改单准入由所属 Lane 判断

`MatchingCommandAdmission` 用现有活动订单索引确定订单归属，捕获不可变币对、标记价与费率输入，然后把普通 AMEND/REPLACE 投递给所属 `SettlementLaneWorker`。`ReplacementAdmissionEvent.execute` 在 Lane 中读取原订单、账户、冻结、持仓和杠杆，计算改单所需冻结及平仓冲突；Owner 收集完成结果后才提交 Matcher。这是只读准入判断，实际撤旧单、冻结新单和版本校验仍由 Lane 结算完成，不新增账户镜像。

完成通知复用按 Matcher 分片的原子就绪位图，不搬运业务状态。命令槽保留解析结果直到终态；未完成不能归还池，完成发布后的通知只使用提前捕获的运行时及分片号。普通改单这条准备路径不再由 Owner 读取 Lane 账户表，批量改单、触发及生产账本迁移仍未完成；现有命令依赖与提交顺序保留。验证记录按时间追加到根 `PERFORMANCE_VALIDATION.md`，本轮排除快照、恢复和 wallet。


### 批量改单账户准入（2026-10-11）

`OrderBatchExecutor` 按批次原顺序派发当前 AMEND 项，复用 `ReplacementAdmissionEvent` 的所属 Lane 校验：原订单、余额、冻结、客户标识、持仓和关闭冲突只在 Lane 读取。现货沿用原冻结资金与可用资金的撮合前检查；Owner 根据既有路由索引检查归属和跨 Lane 新订单 ID 冲突，收集完成结果后提交 Matcher，保留事件直到该项结算结束。AMEND/CANCEL 不再创建或重复更新无用途的 Owner 批次准入索引，PLACE 保留既有路径。

`OpenInterestIndex.pendingOpenInterestSteps` 消费 Owner 已接管的不可变 Lane 持仓变更和既有风险索引，计算尚未提交批次的持仓总量；不查询正在写入的 Lane 表，不新增账户 Map，保留双向持仓、跨 Lane maker、持仓关闭和溢出处理。新增 scratch 只供 Owner 计算并在 finally 清除引用。

319次相关测试执行和实际节点资金核对通过。整链路仍约1172B/业务项，不能称为零分配；生产账本、最终批次响应/元数据和触发/强平/保险/ADL的 Owner 边界仍未全部切开。完整证据见 [批量改单验证](../../docs/validation/batch-amend-lane-admission-20261011.json)，本轮不测试快照/恢复/wallet。


### 顺序批量下单准入迁移（2026-10-12）

`OrderBatchExecutor.beginBatchReservation` 只准备不可变市场配置并派发当前批次项；`BatchPlaceReservation` 在所属 Account Lane 中检查客户订单号、余额、持仓、风险限额与平仓冲突并冻结资金。Owner 等既有控制完成通知后消费不可变准入回执、登记待结算路由并提交 Matcher，不读取账户表。任务保存在已有批次槽中复用，未完成不得清空输入或再次派发。

删除无权威业务职责的 `BatchAdmissionOrderIndex`。Lane 使用自己的准入索引；批次前一项完成后，后一项读取实际资金和持仓，保留成交所得再下单及部分成功语义。订单 ID 重复与命令用户归属仍用已有 Owner 路由索引校验，不做 Lane 间账户查询。

HotSpot JDK 27 的12个必要用例通过，覆盖禁止 Owner 读取账户表、部分成功和资金回退、成交所得再下单、永续累计风险与撤改单回归。按用户要求未执行压测、JMH/JFR、快照/恢复或wallet；生产账本与触发/风险等剩余 Owner 边界尚未全部迁移。证据见 [批量下单验证](../../docs/validation/batch-place-lane-admission-20261012.json)。


### 触发子订单准入与撮合回执（2026-10-12）

`TriggerExecutionContinuation` 把不可变指令和资金费/结算只读进度标志交给所属Lane。客户订单号与持仓身份、余额、风险准入、资金冻结以及Lane待结算标记在Lane执行；Owner在既有ControlLaneDispatcher完成后登记路由。删除会在Lane线程修改Owner索引的旧方法，删除Owner按订单ID回读Lane预留的旧收集入口。

`QueuedTriggerMatching`、`CommandSlot.triggerAdmissionOrder` 与 `DirectMatcherSettlementPreparation.prepareTrigger` 沿用同一不可变准入值，直到命令槽回收；不新增订单Map或深拷贝。子订单编号冲突使用已有活动路由、本命令变更回执及终态身份索引，覆盖其他Lane的占用ID。关闭持仓的触发取消使用已收集的持仓变更值。

HotSpot JDK27的18个必要测试执行通过：六产品线直接/扫描触发，禁止Owner账户表读取、OCO、撮合与准入拒绝/回退、终态ID冲突及批量/命令槽回归。精选方法走无快照分支；未执行快照、恢复、压测、JMH/JFR或wallet。生产SystemLedgerLane、保险/强平/ADL/资金费等剩余资金边界仍需迁移。详见 [触发回执验证](../../docs/validation/trigger-child-lane-receipt-20261012.json)。


### 账本完成回执与批次已有流水（2026-10-12）

`RuntimeFundsAccumulator.transferTo` 保留空目标原语数组交换；目标已有前一项流水时，先检查所有同键金额溢出，再合并完成回执并清空源。失败保留目标与源，`MatcherSettlementEvent` 此时不标记已消费；拒绝无接收目标，防止财务流水被静默回收。实际业务资金仍由原Treasury单写者应用，资金流水不是第二份账户状态。

12个必要用例通过，含跨Lane实际手续费、已有批次前缀、溢出可重试、空缓冲交换、复用与Mailbox顺序。不做压测/JMH/JFR或快照/恢复/wallet；尚未启用完整生产账本链路，不宣称Owner已退出全部资金写入。证据见 [账本流水验证](../../docs/validation/ledger-batch-funds-receipt-20261012.json)。


### 账本投递与响应退休分离（2026-10-13）

生产 `SystemLedgerLane` 已由 `CoreRuntimeLifecycle` 自动启动。原先 `financialHeadReady` 只在响应队首投递财务事实，导致后续独立命令即使完成账户结算，也要等 Owner 退休前一条响应才能进入账本。

现在 `PendingMatchingRing.ledgerDispatchHead` 只表示尚未投递的日志前缀，复用原有槽位和 `nextSlots`，由 Owner 单线程维护。`OrderedCommitCoordinator.advanceMatchingProgress` 先推进 Matcher/Lane，再连续投递已派发的独立命令与流水下单批次；`TradingRuntimeState.dispatchMatcherFinancialSettlement` 只负责非阻塞投递，原 `pollMatcherFinancialSettlement` 继续用于队首完成检查。Ledger 单写者按 FIFO 等账户完成位、应用原 Treasury 并验证资金守恒；Owner 只消费既有回执，响应和槽位回收仍等原终态条件。

控制命令、顺序批次和尚未就绪的前缀不能被跳过。Mailbox 满时不推进游标、不重建或回收事实；已有 Owner 本地工作判定保留有界重试，避免没有新 Matcher 通知时停止推进。没有新增线程、业务镜像、队列或全窗口重复扫描，等待配置及在途上限不变。

现货/U本位永续的独立单笔和流水批次已验证：暂停 Ledger、完成账户任务，停止 Owner 轮询后放行 Ledger，两条命令能在第一条响应退休前结算；随后响应顺序、手续费、资金、订单与持仓正确。72 个必要用例通过，另 3 个旧用例误含快照恢复已记录并排除。本轮未压测/JMH/JFR或启动 wallet，性能改善仍待实测。证据见 [账本流水验证](../../docs/validation/ledger-pipeline-20261013.json)。


### 命令槽位复用与连续 Ledger（2026-10-13）

普通下单与改单的权威 `OrderRuntime` 由 Account Lane 创建。已终态且脱离 Lane 表的订单进入
该 Lane 的有界回收缓存；必须等 `CoreMatchingFlow` 发布对应 Owner 退休序号后才能重新初始化。
开放订单继续持有自身对象。订单变更在 Lane 完成时写入 `RuntimeOrderChangeBuffer` 的复用副本，
副本与物理数组一起交接；长于单项结算的批量响应、链式改单身份和成交记录由批次自己的槽位持有。
这两种存活期不能混用，也不建立全局订单镜像。

`DeterministicExchangeCoreAdapter` 的直达入口使用 exchange-core `placeInto/cancelInto`。
每条在途命令、每个批量项以及改单的撤单结果分别持有 native 结果槽；在 SharedPool 回收原生成交链
前复制 primitive 字段，响应与结算消费者完成后复用。冷边界的独立值入口仍保留不可变语义。

入口 `CoreMessageFlyweightDecoder.decodeBorrowed` 复用有界消息/Header/payload 槽，Owner 与响应出口
通过固定槽的读者计数交接。Egress 必须在发布 Output 前保留 Header，并在编码、丢弃旧 epoch 或清理
Output 时释放；Owner 退休后仍有出口读者时，Decoder 不得复用该槽。槽忙或帧大于 16 KiB 时使用独占
消息存储，缓存 payload 总上限 8 MiB。公开独立消息构造与 `decode` 保留独立值；需要在租约外保存 Header
时调用 `immutableValue()`。单笔及批量意图随命令窗口复用；UTF-8 编码直接写已有 ByteBuffer，字段、长度、
布尔校验及 wire version 不变。`CoreMessageHeader`、`PlaceOrderCommand`、`PlaceOrderBatchCommand` 改为带同名
访问器的类，反射调用方不能再假定它们是 Java record。

Ledger 投递只受日志前缀、实际资金屏障和 mailbox 容量约束，不再要求已就绪事实占据响应队首。
顺序批量的逐项财务事件可以先入队，但在最后一项或业务关闭前保留资金游标；控制命令仍保留
commit/rollback 屏障。Ledger 取到就绪任务即释放队列容量，再执行原 Treasury 上的资金写入。
全部资金合并、守恒校验和资金临时缓冲清理均留在 Ledger 线程，生产路径不会把资金明细传回 Owner。
Owner 只观察完成并推进路由、日志顺序、投影发布与响应退休。未激活账本的独立测试执行使用原同步
Treasury，生产线程启动后非空财务 delta 不能由 Owner 应用。

验证范围与局部分配结果见 [槽位与 Ledger 验证](../../docs/validation/hot-command-slots-ledger-20261013.json)。
局部 JMH 不代表整链路 `bytes/business op` 或真实 Aeron 吞吐；本轮不执行单节点压测、快照恢复或 wallet。


### 变长输入与持仓、响应槽复用（2026-10-14）

普通命令从 `CoreMessageFlyweightDecoder` 进入时，按载荷长度寻找未租用的槽；缓存最多512项、单项16KiB，超长或全忙输入沿用独立所有权。`RuntimePositionChangeBuffer` 把持仓 after-image 复制到自身物理槽，随变更缓冲一起交接，消费者清空后才复用。变更用户/订单ID直接暴露当前命令范围内的只读primitive视图；挂起命令保留其原始集合，恢复时重新绑定视图。

`CommandSlot.LaneResultTarget` 在Lane编码一条或两条订单结果。终态 `CoreResponse` 与独立的响应Header由已租用的 `CoreMessage` 持有；`OwnerResponsePublisher` 使用该响应Header，`ClusterServiceEgress` 保留租约直至Aeron编码消费，随后归还响应和8192个出口槽。异步消费者必须持有Header租约，不能保留已释放槽的引用。正常公开构造的消息和响应继续拥有独立值。

必要验证覆盖六产品线入口、资金守恒、挂起集合恢复、延迟响应和超过出口容量的循环。持仓索引事实、部分控制与实时输出、背压缓冲仍有分配；Owner业务迁移及完整Core百字节目标尚未验收。范围和一次误选旧快照用例的失败记录见 [功能验证](../../docs/validation/leased-response-position-slots-20261014.json)。


### 下单价格与费率决策归属（2026-10-14）

`TradingCoreRuntime.dispatchPlaceAdmission` 通过 `CoreOrderDecisionResolver.prepare` 捕获不可变instrument、mark和 `FeePolicyBook` 版本，绑定命令路由。`PlaceAdmissionEvent.execute` 在账户Lane计算价格保护、撮合/预留价格与费率，再做冻结准入；Matcher收到准入完成后才读取这些结果。Owner提前构造结算事件只读已固定的路由身份，该身份不会由Lane重写。`BatchPlaceReservation` 同样把顺序批量项的决策放在Lane。改单、整批下单、触发和预检继续在既有Lane执行，费率输入统一改为配置版本，消除逐单 `CoreFeeRate` 对象。

`FeePolicyCommands` 复用既有控制任务把配置更新交给Lane 0，Owner消费完成通知并发布命令修订。`FeePolicyBook` 是唯一不可变策略配置，优先级在更新时排序；Lane按命令时间选择有效策略，已捕获版本不随后续更新改变。没有逐命令复制策略，也没有新增可变策略镜像或线程。Owner侧的OI、ADL数量、转账与市场/风险控制仍在后续范围，不能据此认定Owner迁移完成。验证见 [下单价格及费率边界](../../docs/validation/lane-price-fee-decisions-20261014.json)。


### 划转生命周期与内部查询归属（2026-10-14）

`AccountTransferOut` 把指令交给来源账户Lane，由Lane检查完整划转身份、扣减可用资金并保存 `TransferRuntime`。`AccountTransferCompletion` 在同一Lane移除身份。完整指令仅保存在 `LaneColdState.pendingTransfers`；Owner保留有界的 `transferId → userId` 路由、容量和公平分页游标，收到完成通知后更新路由，不做扣款或完整幂等比较。相同转出可在容量满时重试，已完成身份的再次完成不改变修订。

`PENDING_TRANSFER_QUERY` 是内部划转对账/重试查询：Owner选取至多256个路由ID，`OrderBookQueryService` 复用既有多Lane查询完成位，各Lane原位读取该页身份，最后完成的Lane编码响应。它不申请Owner账户访问权，也不持有长期业务镜像。查询分页只遍历本页ID，避免对131072条尾集合求size。冷初始化与序列化仍使用原划转协议结构，本轮按要求未执行快照/恢复场景。必要验证、入口边界首次失败及修复见 [划转Lane验证](../../docs/validation/lane-transfer-lifecycle-20261014.json)。


### 持仓索引输入与条目生命周期（2026-10-14）

Lane结算通过已有 `RuntimePositionChangeBuffer` 交接冻结after-image，不再为每次持仓变更创建 `RuntimePositionIndexValue`。`RuntimeFactIndexes` 消费其中的原语字段，`AdlPositionIndex` 保有自己的 `IndexedPosition` 单元；它不会保存随后被清空复用的命令槽对象。用户成员变化和OI增量先读取旧字段，再更新索引单元，保留数量、方向、关闭重开、资产变化和删除语义。批内未提交OI同样直接读冻结字段。

按遇到的已注册asset/symbol保留空成员存储与零总量单元，关闭重开不会反复分配它们；零总量不进入公开OI查询，重建时清空缓存。没有新增权威账户表或业务镜像。不过这些业务索引的写入线程仍是Owner，后续迁移尚未完成，不能据此称Owner已纯化。必要功能验证见 [持仓索引生命周期](../../docs/validation/position-index-owned-cells-20261014.json)。


### Lane短期索引删除复用（2026-10-15）

`AccountLaneState.pendingReservationSequences` 与每用户 `clientOrderIndex` 改用既有 Agrona `Long2LongHashMap`。准入写入序号与客户别名，结算或回滚删除；删除时原位整理哈希链，已达到活跃容量的数组继续复用。两表仍由账户Lane独占，正序号和订单ID使用0表示缺失，客户端键0的既有内部语义保留；没有Owner业务镜像或逐命令索引副本。冷序列化出口只做原类型转换。

`LaneEntityMembershipTest` 覆盖唯一ID周转、保留在途项及实际客户端别名回滚，资金计数和六产品线入口的必要验证见 [Lane primitive索引验证](../../docs/validation/lane-primitive-index-churn-20261015.json)。本次只解决对应表的删除分配，预约对象、余额lambda、业务聚合与完整Core分配仍需继续处理。


### Lane余额及杠杆热查询（2026-10-15）

`TradingRuntimeState.replaceBalance` 在已有Lane命令作用域内直接调用 `replaceBalanceInLane`，保留余额前像和after-image，不创建路由lambda；作用域与用户路由必须匹配。`RuntimeOrderAdmission.effectiveLeverage` 使用现有primitive参数查询，查询键由各Lane自己的 `LeverageLookup` 复用并在查询后清除引用，不再逐单创建 `CoreLeverageKey`。不存在新增共享scratch或余额镜像。

并发Lane配置查询、跨Lane写入拒绝、余额回滚、六产品线成交与Ledger的必要验证见 [余额与杠杆路径](../../docs/validation/lane-balance-leverage-direct-20261015.json)。这只减少对应临时对象，完整Core百字节分配和Owner业务索引迁移仍未完成。


### 预约与订单槽共用生命周期（2026-10-15）

普通Lane准入通过 `OrderRuntime.prepareReservation` 初始化一个resident `ReservationRuntime`。它与权威订单共用现有Owner退休序号、返还队列及有界复用缓存；旧命令尚未退休时不能重新初始化。订单的响应副本及独立值副本不保存该引用，预约没有Owner副本或独立回收线程。再次准入覆盖身份与原语计数，只允许重置带内部orderSlot标记的权威对象；公开值副本不能作为复用目标。

`OrderSlotReuseTest` 验证延迟退休、重复准备、身份重置和独立值稳定；六产品线成交、资金计数、批量改单及Ledger回归见 [预约槽生命周期验证](../../docs/validation/reservation-order-slot-lifetime-20261015.json)。普通值复制不等于Cluster快照场景，本轮未对Cluster快照/恢复做验证。完整Core分配及长稳仍需后续验证。


### 内部自动撤单与先前成交（2026-10-15）

`CommandSlot.AdmissionMatchingContinuation` 处理的是下单前“若自己的旧单仍在簿上则撤销”的内部意图。Matcher可能已成交并移除旧单，而所属账户Lane尚未消费该成交；此时原生 `MATCHING_UNKNOWN_ORDER_ID` 代表不再需要撤销，继续提交新单，不追加撤单事实或再次解冻。只有真实撤销成功才记录 `recordDirectPreCancellation`；其他原生失败保留失败处理，公开撤单不存在订单仍拒绝。

`CoreSelfTradePrefixTest` 用受控Maker Lane延迟覆盖六产品线的零、部分及全部成交，并检查资金/持仓及Owner未接管账户。稀有 `surprising.MatcherPrefixFailure` JFR事件记录原生前缀失败码和身份，成功交易路径不创建该事件。必要验证见 [条件自动撤单验证](../../docs/validation/conditional-self-trade-prefix-20261015.json)。


### 成交财务槽保留已用容量（2026-10-15）

`MatcherSettlementEvent` 在所属命令退休并归还后继续保留已创建的 `RuntimeTreasuryDelta` 槽。下单最初只路由到一个账户Lane，Matcher发现其他成交方后扩展路由；参与Lane数缩小时不再重建数组或对象，增大时只补充新槽。Owner准备输入、Matcher发布事实之前清除槽内流水，账户Lane写入各自槽，原SystemLedgerLane在账户完成后按当前 `requiredLaneMask` 归并，数组容量不决定本次业务范围。没有新增账本、账户镜像、线程或回收阶段。

必要验证覆盖槽位扩容和收缩、无成交、未参与槽位排除、独立账户线程和Ledger费用结算、六产品线连续交易及未完成事件禁止回收，见 [财务槽容量验证](../../docs/validation/treasury-slot-capacity-20261015.json)。这只消除该分配源；完整Core百字节目标和Owner业务迁移仍需继续完成。按要求未验证Cluster快照/恢复或启动wallet，分配诊断结果仅保存在本地。


### Lane活跃订单与预约成员集合（2026-10-15）

`AccountLaneState.activeOrderIdsByUser` 与 `reservationIdsByUser` 使用已有Agrona primitive集合。订单准入增加成员，终态/回滚原位删除并整理探测链，达到活跃容量后不因唯一ID周转反复重建数组；集合仍归所属账户Lane，用户删除时移除容器。预约成员的增删集中在该Lane的 `addReservationId` / `removeReservationId`，清除旧通用容器辅助方法。Lane内部扫描复用迭代器并读primitive值；风险游标仍选择最小后继，平仓冲突仍显式按业务顺序排序，外部预约ID查询仍返回独立集合。

必要验证覆盖大ID周转时保留在途项、容器释放、独立查询值、终态及回滚恢复，并覆盖六产品线、风险/ADL查询、平仓容量、预检和realtime输出。原来直接在Owner读写活跃账户的旧测试夹具改为Lane邮箱执行，保留原冻结/别名/回滚断言，生产所有权检查没有放宽。验证及已纠正的失败记录见 [Lane成员集合验证](../../docs/validation/lane-membership-backshift-20261015.json)。完整Owner职责、客户端身份/编解码等剩余分配和完整Core百字节目标仍未完成；本轮不验证Cluster快照/恢复或wallet，数值诊断只留本地。


### 准入交接账本事实与按需OI输入（2026-10-15）

Treasury仍由原SystemLedgerLane独占写入，`TreasuryRuntime.settlementStatus` 借出其已经发布的不可变 `SettlementStatus`。Owner准备普通下单、批量、改单、预检和触发子单时只传递该引用；`PlaceAdmissionEvent`、`CoreOrderDecisionResolver.Context` 和 `AdmissionIdentity` 不再由Owner提取或复制生命周期/资金费布尔值。账户Lane执行时读取捕获的事实，随后账本发布新进度或回滚也不会改变旧命令的输入。没有新状态镜像、队列、线程或逐单状态副本。

`CoreInstrument.usesOpenInterestForUserLimit` 明确限额对市场OI的依赖：现货、比例为0或下限已经达到固定上限时无需全局OI输入。对应准入跳过全局查表及批内持仓扫描；Lane仍执行固定下限、持仓上限、风险档位和有效比例限额检查。80个必要用例覆盖固定下限拒绝、有效比例读取、下单/改单/批量/预检、捕获事实延迟执行、六产品线触发与资金费分页，见 [准入输入所有权验证](../../docs/validation/admission-input-ownership-20261015.json)。首次误选含快照用例及查询夹具错误均保留记录，最终仅执行已审查的必要方法。

这一改动不代表Owner迁移完成：有效比例限额仍由Owner准备OI输入，OI/ADL/持仓用户索引与部分业务收据、市场/风险控制及输出编码仍待迁移；完整Core百字节与真实单节点验收尚未完成。


### 持仓业务索引由原SystemLedgerLane独占（2026-10-15）

`RuntimeFactIndexes` 的持仓用户、OI和ADL索引在Ledger激活后绑定该线程，Owner及Account Lane直接读写都会失败；停止Ledger后才允许冷路径物化和重建。正常成交在原 `MatcherSettlementEvent.TreasurySettlement` 中读取已有冻结持仓输出，应用索引后才发布完成。Owner的 `CommitPublication` 只继续维护订单等路由，跳过持仓业务索引。没有新增账户镜像、worker或逐持仓DTO。

`SystemLedgerCommand.ScopeClose` 与仅账户控制的验证边界复用最小primitive前像数组，保留到Owner提交或拒绝；仅持仓变化也会验证并发布，不能因为没有余额流水而漏掉。强平批次内部继续扫描风险时，在同一财务scope内发布索引输入并保留同一回滚前值，不排到尚未请求关闭的队首后面。`LedgerPositionPublicationTest` 覆盖两种控制scope的重复回滚、可变ADL条目原位修改及线程边界。

普通下单/改单复用原准入槽作为Ledger输入请求，账户worker依据 `accountReady` 消费；等待输入时仍能处理先前成交，并保留准入FIFO。顺序批量、触发和预检消费命令自有的OI primitive。有效比例限额复用原控制命令边界保证全局及批内前缀，固定限额保留普通并行窗口，Owner不阻塞等Lane。原Owner待提交OI扫描及其旧基准已删除。

资金费与生命周期在已有continuation内从Ledger选用户页；风险账户任务只携带本片预算加1的primitive用户ID页，不能再读取全局索引。内部Core的OI与维护分页由Ledger编码，风险/ADL由Ledger选路、Account Lane读取并编码；外部Redis投影路径未改动。风险查询仅在明确的查询入口冻结市场配置版本，交易热路径不做该复制。110个必要用例及已修正失败见 [Ledger索引所有权验证](../../docs/validation/ledger-position-index-ownership-20261015.json)。

这完成的是三项业务索引的所有权边界。Owner仍有业务收据遍历、部分控制编排和输出编码要继续迁移；完整Core百字节分配及真实单节点验收仍未完成。数值分配诊断只保留本地，本轮不做Cluster快照/恢复、wallet或真实单节点压测。


### 顺序批量订单、预约输出与提交元数据复用（2026-10-15）

`RuntimeOrderStateTransitions.placePrepared` 在活跃账户线程上使用该Lane已有订单池及订单自带的预约，沿用响应退休序号；冷初始化仍创建独立权威值。`ControlLaneDispatcher` 在发布完成标记前，把订单及预约输出冻结到物理缓冲自己的槽。新增 `RuntimeReservationChangeBuffer` 只承担这一跨线程输出生命周期，清空后保留容量，不形成第二份账户表。

`BatchPlaceReservation` 保留自己的准入输入槽，不能借用随后可能被Owner接管并清空的Lane输出。触发队列的子单输入也保持独立生命周期，目前仍需独立复制。`PendingReservationTracker` 仅登记已接管输出的身份，Owner不再额外做一次订单快照；直接写入已借出的变更值时，先冻结输入，再释放来源缓冲。

`LaneCommitEvent` 在实际Lane上原位填写权威订单的提交元数据，冻结事件输出后按缓冲交接，保留权威订单及其预约的池身份。若命令修改已有订单，先保存回滚前值，拒绝时恢复到同一权威对象；不能用冻结副本替换仍带预约和退休租约的对象。Owner不再为该事件逐订单创建元数据副本。

必要功能验证、缓冲生命周期缺陷的修正以及误选旧矩阵测试的失败记录见 [顺序订单槽验证](../../docs/validation/sequential-order-slot-reuse-20261015.json)。本模块没有完成全部Owner收据/控制/编码迁移，也不代表所有路径零分配或完整Core小于100 B/业务项；分配诊断留在本地，未做真实单节点压测。最终必要用例排除了Cluster快照/恢复及wallet；此前误选含快照方法的旧测试类不计入最终验收。


### 准入及路由索引周转、结果交接回调（2026-10-15）

`AccountLaneState.LaneAdmissionOrderIndex` 在最后订单离开时摘除币对汇总，最多复用256个已归零且不含身份的单元；仍存在的用户保留空索引表，删除用户后释放。若先删除用户再清订单，最后订单负责释放该表，不能提前丢掉有效汇总。原有内部表改用Agrona primitive表，直接支持symbolId 0并保留数组容量；外部币对ID、分组数量和回滚语义不变。

`ActiveOrderIndex` 仍仅保存Owner路由身份。用户和币对的最后订单离开后即移除身份键，空primitive成员表最多缓存64个、单表容量最多4096，过大的表直接释放；清空的路由对象最多缓存4096个。币对表改用开放寻址以保留数组容量，没有新账户镜像或永久历史用户桶。查询游标仍必须在下一次索引修改前消费。

`MatcherSettlementEvent.BatchStorage` 的元数据槽改用以-1表示缺失的primitive表，清空后保留容量，symbolId及槽号0均有效。`TradingRuntimeState.reserveOrderInLane` 在已有Lane作用域直接执行原冻结步骤；`RuntimeIndexedChangeBuffer` 直接把冻结值和可选删除证明交给目标缓冲，再清空源，避免捕获型回调。Owner控制及终态收据的相关小循环沿用原顺序，不再逐次创建lambda；这尚未把所有收据业务搬离Owner。

必要用例覆盖数量替换和部分成交、跨分组复用、0及最大symbolId、用户移除顺序、缓存上限、独立输出租约、拒绝及回滚、六产品线双币对反复交错批量、风险查询、触发及Ledger，见 [索引周转功能验证](../../docs/validation/admission-routing-reuse-20261015.json)。局部索引生命周期基准与进程内交易流水线JMH/JFR分开记录，仅保留本地数值；不做真实单节点压测、Cluster快照/恢复或wallet。Owner剩余控制/输出及完整Core百字节目标继续保持未完成。


### 顺序批量的配置捕获与Matcher输入（2026-10-15）

`OrderBatchExecutor.prepareBatchDecision` 复用批次已有的币对列表和决策数组，同一批、同一币对只捕获一次只读配置与资产身份。资金、仓位、费用选择及依赖OI的准入仍由Account Lane和原SystemLedgerLane逐项执行，不能缓存批内变化的余额或OI。同步准备失败和Lane整批准入拒绝转顺序执行时，都先释放失败前缀的配置引用，再按新的执行前缀准备；批次退休后清空引用。

顺序PLACE通过原 `PlaceBatchIntentSource.captureAdmittedOrder` 在Lane直接填写批次自有的冻结输入槽。`BatchPlaceReservation` 只轮询原控制任务，Owner完成后绑定引用，不再把该输入复制到另一订单对象；它的下一项准备不会清空先前项的批次槽。资产选择在Lane执行，Owner只提供已解析的资产身份。这个配置捕获和输入租约不形成新的账户表、全局镜像、worker或队列。

`CommandSlot.PlaceMatchingContinuation` 复用现有撮合任务，Matcher通过 `DeterministicExchangeCoreAdapter.placeAdmittedDirect` 读取冻结输入，直接调用原生 `placeInto`，保留剩余数量、产品线滑点、订单类型和撮合证据。Owner的普通活跃顺序提交不再创建 `CoreMatchingOrder` 或本项提交lambda；前置撤单仍沿用原wrapper，后续继续迁移。适配入口拒绝Lane活权威对象、错误用户、已释放槽及无剩余数量。原生事实发布前所有输入已经成为调用参数，continuation发布后不再读取这些可复用字段；输入值本身保留至批次退休。冷同步接口仍使用独立公开值。

必要验证见 [批量输入功能验证](../../docs/validation/sequential-matcher-input-20261015.json)，覆盖六产品线的双币对市价/限价、重复项拒绝和转顺序执行、配置版本、独立输入、改单、触发、Ledger和异常退出。异常退出夹具按生产方式推进Ledger，并在等待时保留已提供的撮合结果；关闭前后比较真实致命边界已观察的撮合序号，仍保留精确相等断言。按要求没有执行Cluster快照/恢复或wallet。

分配诊断分别记录单笔请求的每请求字节和20项批量的每项字节，不能把两种口径互代；两者都仍排除真实Aeron网络/Archive及realtime。JMH增加双币对交错场景并逐币对核对终态持仓，数值只留本地。Owner其他业务收据、部分控制及输出编码、客户端身份/用户修订等分配和完整Core百字节目标仍未完成。


### 单笔入口包装、生命周期判断及响应writer复用（2026-10-15）

`TradingOwnerLoop` 沿用原入口SPSC队列，固定预分配8192个 `Input` 包装。Aeron回调从 `availableInput` 取得空槽，填写原命令、日志时间、位置、会话和指纹后发布；Owner把命令接入已有 `PendingClusterIngress` 后，才清空并通过归还队列发布该包装。原命令和响应仍遵循各自租约，包装归还不释放它们。等待字节预算被中断时，未发布包装清空后留在回调线程，下次直接重用；不能由两个线程向同一SPSC归还队列写入。容量、字节预算、截止时间和原等待策略保留，没有新的业务状态表、worker或执行阶段。

普通订单在 `MatchingCommandAdmission` 直接用用户及币对路由检查已有生命周期范围，不再逐请求创建 `LifecycleScope` 或stream；强平/交割需要保留的范围仍是独立值，用户隔离、整币对交割及退休后放行语义保留。`PendingReservationTracker` 的首个预约成员改用Agrona primitive表，清除后保留数组；多成员提升及其他在途序号不会被清空。`TradingRealtimeBoundary.beginCapture` 在确认实时捕获已启用且当前为Leader后才生成缺省追踪字符串。

单订单响应使用原 `ResponseArena.Slot` 持有的 `ByteBuffer`，每次编码重置position，沿用原存储及transport/ledger引用计数；另一个未释放的响应不能借到这个writer。协议新增接受已有ByteBuffer的写入入口，原byte[]及offset接口保持一致字节。两订单/批量等其他编码分配仍待处理。

116个必要用例及选择范围见 [单笔入口与响应功能验证](../../docs/validation/single-ingress-reuse-20261015.json)，覆盖满入口队列、等待中断、两轮真实Owner槽周转、协议边界、延迟出口、生命周期隔离以及六产品线成交、拒绝、触发、资金和实时事件。没有执行Cluster快照/恢复、wallet或真实单节点压测。分配数值只保留本地；完整Core百字节目标，以及Owner其他业务收据、控制及输出职责继续保持未完成。

本轮诊断入口曾误用旧shaded Jar中的协议类，新增编码方法缺失导致预热失败；已要求重新打包并锁定实际JAR指纹，失败轮次单独保留。该故障暴露的空闲故障传播也已修复：`SettlementLaneWorker` 先保留原始故障，再发布通知，报告回调异常不能盖掉原故障；`TradingRuntimeState` 在完成探测时检查原故障引用并唤醒Owner。关闭时无论RuntimeException还是Error，都继续停止其余Account Lane和Ledger，最后再抛故障。补充用例覆盖Error身份、通知失败及所有线程退出，不将异常当作未完成请求持续等待。


### 指纹与提交访问的槽生命周期（2026-10-15）

`AeronTradingClusterService` 显式使用 `CommandFingerprint.Calculator.ofBorrowed`：计算器把SHA-256摘要写入当前decoder槽自己的32字节单元，header/payload在该租约内保持不变。原 `of` API仍返回独立不可变值。原 `CommandResultLedger.StoredResult` 池在提交时把借入摘要复制到该记录自有的常驻单元，输入退出不会修改保留结果；对外 `fingerprint()` 返回独立值，内部重复比对直接读记录摘要，不逐次物化。固定canonical字段、32字节编码及划转v1/v2等价规则未变。

`CoreMessage.matchingPendingResponse` 复用独立的待撮合元数据单元，覆盖普通、延后及批量入口；它与终态响应分开，不能在同一输入租约内改变序号。最后一个Header/出口读者退出后，指纹和两种响应单元一起清除。没有新增业务状态表、线程、队列或执行阶段。

`TradingRuntimeState.visitChangedIndexes` 直接遍历已有primitive变更缓冲，保留direct、Lane号及订单/持仓/强平/算法单/触发单/定时器的原访问顺序；准备好的持仓索引和删除标记仍沿原字段传递。Ledger启用时，Owner继续跳过持仓业务索引。提交后的风险身份先于持仓身份清理，随后才清空缓冲。`LaneCommitDelta` 的用户交接和批量身份登记改用原位循环；`RuntimeFundsAccumulator` 跳过空流水迭代器，非空流水仍按原顺序核算。

130个必要用例及修正夹具见 [指纹和提交访问功能验证](../../docs/validation/commit-fingerprint-reuse-20261015.json)，包括独立canonical摘要向量、六产品线摘要与槽周转、结果记录淘汰、旧暴露值独立、冲突不变更状态、划转重复/淘汰/错误收款人、提交顺序及删除标记、Ledger和六产品线成交/拒绝/风险/触发/实时事件。主矩阵129例通过后，协议小范围收尾及新增固定向量由9例协议测试验证，重复项不计入130个独立用例。未执行Cluster快照/恢复或wallet。

本模块仍未达到完整Core百字节目标，也未完成Owner的全部业务/输出职责。客户端身份、用户修订、任意UTF8解码，以及Owner资金幂等索引的同步RocksDB读写仍待迁移；资金幂等不能因为减小Owner成本而缩短保留。数值诊断只留本地，不做真实单节点压测或提交数值压测记录。


### Lane 用户修订号与命令收据复用（2026-10-15）

`UserRuntime` 的公开构造值保持不可变的字段和值比较语义；Account Lane 首次写入时取得自身的权威对象，随后只原位更新修订号与持仓模式。普通准入先完成算术和资金校验，再冻结资金、登记订单并更新用户；`MatcherSettlementChanges` 仍按用户合并本次成交的修订增量，Lane 结束该结算时更新同一对象。账户调整、划转、模式、保证金和触发子单通过原账户入口更新，不再为每次修订创建新用户对象。Owner 不保存第二份用户状态。

原有 `LaneCommitDelta.users` 和回滚用户缓冲改用 `RuntimeUserChangeBuffer`：每个物理命令槽保留可复用的独立前值或后值，跨线程交接时不暴露 Lane 活对象。回滚前值同时保留原权威对象的身份，失败时在其 Lane 恢复修订号与模式；批量准入事件也保留自身独立的前值槽，不能只保存活用户引用。清空只释放该命令的租约，随后才允许重写槽位。这里增加的是已有命令缓冲的值存储，不新增账户镜像、队列、worker 或提交阶段。

实时增量 metadata 在消费收据时立即编码，编码字节不会引用用户槽；公开查询及冷序列化边界仍返回独立值。142 个必要用例的功能验证见 [用户修订与收据复用](../../docs/validation/user-revision-reuse-20261015.json)，覆盖六产品线、并行在途收据、回滚与模式、批量拒绝、划转和实时输出。未执行 Cluster 快照/恢复或 wallet。两个旧拒绝夹具先由 Owner 路由索引证明没有待划转，再按相同 canonical 规则计算业务哈希，保留原资金、状态和拒绝断言。

完整 Core 百字节目标和 Owner 迁移仍未完成：客户端身份及 UTF8/UUID 解码、业务收据与实时编码、资金幂等索引同步 I/O 以及生产 `stateHash()` 活跃 Lane 读取边界继续处理。当前阶段只在本地保存受影响路径的数值诊断，不做真实单节点压测，不提交数值压测记录。


### 客户端订单身份归属 Account Lane（2026-10-15）

`AccountLaneState.clientIdentities` 独占本 Lane 的客户端订单身份。`LaneClientIdentities` 使用 primitive 键到行号索引及用户、名称、引用计数列；最后一个订单或别名释放引用时清空名称，将行归还复用。容量只随实际同时存活的身份高水位增长，不缓存已经释放的名称。`RuntimeIdentityRegistry` 不再维护全局客户端身份 Map；Owner 只汇总 volatile 数量作诊断，不能访问活跃 Lane 的身份表。

客户端身份仍由 `(userId, clientOrderId)` 确定，原 UTF8 确定性数值键保持不变，数值键在所属 Lane 内使用；不同 Lane 可以持有相同数值键且互不影响，同一 Lane 内用户或名称不符仍报碰撞。冷构造、绑定和重绑定先把完整引用计数转入未发布的目标 Lane，全部成功后才清除原表；活跃运行时禁止重绑定。这里没有新增全局碰撞表、队列或执行阶段。

`OrderBookQueryService` 将客户端订单号原始输入送入现有账户查询任务，在用户所属 Lane 查身份和订单；预检同样在 Lane 查询客户端及持仓身份。重复准入回滚只撤销本次获取的引用，旧订单终态不能提前回收仍被新别名使用的身份。必要验证共 160 个唯一用例，最后移动预检持仓查找后补验其中 23 个，覆盖六产品线、并行 Lane、强制碰撞、回收周转、别名、查询、批量和资金/风险边界，见 [客户端身份验证](../../docs/validation/lane-client-identities-20261015.json)。

完整 Owner 迁移和完整 Core 百字节目标仍未完成；任意 UTF8/UUID 解码、剩余业务收据及输出编码、资金幂等索引同步 I/O、活跃 `stateHash()` 的 Lane 读取仍继续处理。当前只运行受影响路径的必要测试及本地分配诊断，不执行 Cluster 快照/恢复、wallet 或真实单节点压测，不提交数值压测记录。


### 活跃运行时的业务哈希读取边界（2026-10-15）

`TradingCoreRuntime.stateHash()` 经 `RuntimeStateMaterializationCache.businessStateHash()` 在同一个现有冷读取屏障内物化交易状态，并读取手续费策略和各 Lane 的完整待划转字段，然后按原 canonical 规则计算哈希。查询结束或异常时，只归还本次取得的 Lane 所有权；外层已经持有的屏障不被内层查询释放。Owner 在普通活跃路径直接读取 Lane 待划转表仍被拒绝，没有放宽热路径的单写者检查。

14 个必要用例覆盖六产品线、跨 Lane 待划转、手续费、重复及完成、外层屏障和查询后 worker 写入；两个批量拒绝夹具重新使用生产 `stateHash()`，见 [活跃业务哈希验证](../../docs/validation/active-business-hash-20261015.json)。本次是冷查询正确性修复，不执行快照/恢复、wallet 或压测。Owner 其余迁移及完整 Core 分配目标继续处理。


### 成交用户修订回调由 Lane 常驻（2026-10-15）

`MatcherSettlementChanges.applyUserRevisions` 把当前命令合并后的 primitive 修订增量与对应 `LaneCommitDelta` 交给所属 `AccountLaneState`。Lane 构造一次原语 visitor，并按原规则校验用户、精确累加修订号、更新权威用户和独立收据；不再逐次创建捕获 Lane 及收据的 lambda。收据引用只在同步遍历期间借用，正常和异常返回均在 `finally` 清空；源增量仍只在成功后清空。没有新增线程、队列或账户镜像。

167 个必要用例通过，新增用例验证算术溢出不改写用户或失败收据，后续命令不会沿用失败收据；同时覆盖六产品线、独立输出、实际 worker、成交及资金/风险路径，见 [Lane 修订回调验证](../../docs/validation/lane-revision-visitor-20261015.json)。本次运行时分配复验受宿主降频影响，尚未验收其动态成本；不据此宣称零分配或完整 Core 达标。未执行快照/恢复、wallet 或真实节点压测，数值记录仅留本地。


### 资金幂等索引 I/O 归属 SystemLedgerLane（2026-10-15）

资金命令先检查现有内存结果，未命中时由 `DirectCommandSlot` 把完整命令 UUID 的保留身份查询送入既有 SystemLedgerLane 邮箱。Ledger 查询 RocksDB，Owner 只检查完成状态；重复或冲突仍先于 sourceSequence 校验处理。新命令沿原账户及资金流水执行，完成发布与资金守恒检查后，由同一 Ledger 写入幂等身份；只有写入完成才推进已应用计数和来源序号并返回终态。队列满时保留原请求，索引故障仍使 Core 失败，不伪装成业务拒绝。

`FundsCommandIndex` 继续保留全历史资金幂等和原磁盘格式，活跃读写绑定到 Ledger，Owner 直接 get/put 会失败。原单个 DirectCommandSlot 复用 I/O 请求，完成写入先发布结果再唤醒 Owner；没有增加线程、队列或账户镜像。冷构造、序列化及关闭边界保持原职责。

177 个唯一必要用例通过，最后 16 个重叠用例验证完成通知的收尾修改，见 [资金索引 Ledger 验证](../../docs/validation/funds-index-ledger-20261015.json)。覆盖六产品线、结果缓存淘汰后的重复与冲突、四类资金命令、邮箱满、数据库故障及连续请求复用。没有执行快照/恢复、wallet 或真实节点压测。完整 Owner 迁移、显式 Lane 参数、锁归因和完整 Core 分配目标继续处理，不据功能测试宣称性能达标。


### 准入与成交结算显式传递 Account Lane（2026-10-15）

`PlaceAdmissionEvent`、批量及替换准入把实际 worker 收到的 `AccountLaneState lane` 传入 `RuntimeOrderAdmission`。校验直接读取该 Lane 的订单、客户端订单号、用户、持仓和杠杆，保留原资金、模式、生命周期和风险检查。配置校验也使用显式 Lane 入口，不再为每次查状态调用 `currentLaneCommandScope()`。

`MatcherSettlementEvent` 把同一个 Lane 交给现货/衍生品的 plan 执行、逐笔及累计成交计算。余额前后值、订单/预留/持仓原位更新、用户修订合并、终态冻结释放及触发完成均使用传入的 Lane 与既有独立收据；不重新按线程寻找业务状态。取消/拒绝直接更新原权威对象与收据，去掉这条生产路径的中间取消结果对象。已无调用者的两种旧 List 版 Lane 执行入口删除。

Scope 进入和退出仍验证真实线程所有权与当前命令边界，但只检查传入的 Lane；另一个 Runtime 的 Lane 不能进入。客户端身份按已知用户路由，Lane 持仓身份查询直接使用该 Lane 的探针。没有新增 worker、队列、账户镜像或通用执行层。冷构造入口继续使用独立值；这不代表所有控制及查询已经迁移。

209 个唯一必要用例覆盖六产品线、成交/反向/自成交、精确修订、拒绝、批量、改单、触发、资金/风险、查询及输出。首轮两项失败分别为所有权提示断言和旧冷夹具未绑定的 symbolId，修正后 71 项相关复验通过；最后的触发完成与外来 Lane guard 再验 70 项，均为重叠用例，见 [显式 Lane 功能验证](../../docs/validation/explicit-lane-20261015.json)。未执行实际快照/恢复、wallet 或真实节点压测。

`currentLaneCommandScope()` 仍存在于尚未迁移的控制、风险、资金费和查询/冷门面；Owner 按 ID 扫表和持仓计数、客户端/服务端锁归因及剩余分配也继续处理。不能把本节理解为上下文成本全部消除、Owner 迁移完成或完整 Core 百字节目标达标。数值诊断仅保留本地。


### 终态客户端别名释放回调常驻 Lane（2026-10-15）

`MatcherSettlementChanges.prepareLaneTerminal` 按既有 primitive 缓冲下标处理订单和持仓，保持原顺序、拒单保留、冻结检查、终态退休及平仓触发取消规则。订单完成后，由 `AccountLaneState` 的常驻 visitor 释放原客户端身份引用，再由独立常驻 visitor 删除该订单的客户端映射。删除前确认映射仍指向当前订单，旧终态不能抹掉复用该客户端键的新订单映射。

visitor 仅在同步遍历期间借用身份注册表、用户、映射和订单 ID；正常或异常退出都在 `finally` 释放这些引用。原全局 Runtime 的单纯转发 helper 删除；没有新队列、worker、账户镜像或业务阶段。215 个必要用例通过，包括六产品线共享/多别名、零键、失败后的下次遍历、旧终态与新映射，以及成交、改单/批量/触发、资金/风险、查询和输出，见 [终态别名释放验证](../../docs/validation/terminal-client-release-20261015.json)。未执行实际快照/恢复、wallet 或真实节点压测。

数值诊断只留本地。最终代码的分配复验因宿主 swap-in 无效，动态成本尚未验收。字符串、UUID、未迁移的控制及查询路径、Owner 路由/计数、客户端和服务端锁工作仍继续处理，完整 Core 百字节目标未验收。

### Owner 日志元数据边界与测试适配（2026-10-15）

`OwnerLogContext` 不再实现 Aeron `Cluster`。`TradingOwnerLoop` 在服务线程复制初始角色、日志时间和位置，Owner 按既有输入 FIFO 更新这些值；`TradingCoreOwner` 和实时边界只接收所需元数据，Owner 的等待策略仍由自己持有。真实会话、发布器及定时器 API 留在 Cluster 服务边界。

受影响测试改为等待实际异步初始化，待撮合场景只完成准入，账户及身份断言经真实 Lane 查询；测试回调适配器同步更新元数据。没有放宽业务或线程归属校验。67 个必要用例通过，包含六产品线真实 Owner 的 Cluster API 访问隔离；基准模块及直接依赖编译通过。按要求未执行快照、恢复或压测，也不据此宣称全量测试或整体 Owner 迁移完成。见 [D1 功能验证](../../docs/validation/owner-log-metadata-tests-20261015.json)。

D1 补充审查：`ClusterServiceEgress.TransportSession` 仍实现 `ClientSession`，两个未使用的接口方法仍抛 `UnsupportedOperationException`，因此完整 D1 尚未关闭。补充六产品线实际 Owner/会话出口贯通后，79 个必要用例通过，涵盖背压顺序、角色切换、旧任期丢弃、会话关闭和响应租约。该贯通用例仅模拟发布器，不代表真实网络集群或压测验收；见 [D1 复验及未完成项](../../docs/validation/d1-review-rerun-20261015.json)。

### D1 完成：Owner 只传会话 ID（2026-10-15）

`OwnerLogContext` 与 `ClusterServiceEgress.TransportSession` 均不再实现 Aeron 接口；后者删除两处未实现方法和无调用的元数据转发，仅承担服务线程的具体响应出口。`TradingOwnerLoop.Input`、`PendingClusterIngress`、`ClusterCommandWindow`、响应 sink 和出口槽只传 `long sessionId`。真实 `ClientSession` 留在服务线程，响应消费时按 ID 查原会话表；无客户端日志使用 `NO_SESSION_ID=-1`，合法会话 ID 0 保留原语义。没有新增接口、线程、队列、账户镜像或逐命令对象。

已关闭/移除会话及旧任期的响应直接丢弃，同时归还响应与 Header 租约；原背压 FIFO、容量、关闭回调及有序提交规则保留。111 个必要用例通过（服务 109、受影响基准夹具功能用例 2），包括六产品线真实 Owner 线程隔离、关闭后丢弃、池复用、资金及控制流水线。未启动真实网络集群、执行快照恢复或压测。此前 D1 补充审查记录中的会话伪实现未完成项已关闭，见 [D1 完成验证](../../docs/validation/d1-complete-20261015.json)。其他 Owner 迁移及性能验收仍是独立未完成事项。


### D2 旧派发、兼容查询与响应回退清理（2026-10-15）

`MatcherSettlementDispatcher` 和 `TradingRuntimeState` 的 Owner 直达派发入口已删除，改单也统一预留 Matcher 发布槽；Matcher 将成交事实交给各 Lane，Owner 不再补派发或修改事件 fence。`ClusterCommandWindow.complete()` 的扫描回调删除，普通订单由有序队首接收结果；控制回调只保存自身序号的响应，触发/OCO 子单继续独立提交。

`ActiveOrderIndex` 的 boxed `ids()` API 及无调用的旧平仓检查删除。`ImmutableLongArrayList` 保留实际命令、分页和协议 List 契约所需的 primitive 存储，删除无调用的兼容工厂。Lane 必须提供已捕获响应及长度，包括未变化订单、终态后镜像和明确的空结果，Owner 不再回查或重编码；每个命令槽仅增加本代响应项的捕获位，复用时清零。响应存储申请合为一处，大响应及池槽被传输/幂等结果保留时仍使用独立存储，防止丢失已提交命令的响应。

153 项必要功能用例通过（service 151 项、受影响基准夹具 2 项），覆盖六产品线成交资金守恒、跨 Lane IOC、改单/批量/触发、连续 Ledger 和槽位复用，见 [D2 功能验证](../../docs/validation/d2-complete-20261015.json)。未执行快照、恢复、wallet 或压测；未采集 JMH/JFR 性能数据。C1 引用计数并发审计、其他 D 项和整体 Owner 迁移不属于本次完成结论。
