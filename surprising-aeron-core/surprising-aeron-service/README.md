# 核心提交日志回放

`CommittedTradeReplay` 由独立导出进程持有核心状态，只应用 Archive 已提交的命令；不会向在线 Cluster 发送交易命令。订单和成交沿用实时捕获，资金费通过 `PerpetualFundingCommands` 的回放观察接口交出实际支付结果，形成不可变的 `CommittedFundingPage`。该对象的生命周期仅为当前完整命令，下一次 apply 清空；没有第二份资金账本或长期支付缓存。

资金费页保留产品线、日志位置、核心时间、实际命令费率、完成游标及支付明细。重复或被拒绝的命令不产生支付页。在线异步 Account Lane 路径不安装观察接口、不读取数据库，资金计算、快照格式和核心权威状态不变。查询投影在 realtime 模块持久化，SQL 成功后才能推进导出 checkpoint。

HotSpot JDK 27 下 `CommittedFundingReplayTest` 覆盖 U 本位及币本位永续的实际多空支付、分页、快照恢复和重复命令；原有 funding processor、异步 funding、订单成交回放及权利金/资金费回归一并执行。此改动供独立回放使用，不要求重启在线核心。


## Matcher 结果与账户结算交接

账户分区按 `LaneTopology` 隔离，由对应的 `SettlementLaneWorker` 多线程消费。主线采用实验目录已修复的实现：Owner 定序并准备命令，Matcher 产生确定性撮合结果，`MatcherSettlementDispatcher.publishMatcherOwned` 通过每个 Lane 的分片 SPSC 队列直接发布成交及撤单结果，所属 Lane 串行应用账户结算；完成回执到达后，Owner 按提交顺序发布终态响应。

Matcher 在 `MatcherSettlementEvent.notifyDirectPublication()` 中校验结果并完成 Lane 投递，再发布就绪位。池化事件必须等实际消费者完成后才能复用。工作线程异常传给 Owner，避免命令在消费者退出后继续等待。全局控制命令沿用该实现已有的 Lane 所有权交接边界。

单笔及批量撤单的 Owner 入口只通过现有 `ActiveOrderIndex` 校验归属并准备路由，不读取 Lane 正在增删的 `orders` 普通 HashMap。`cancelIdentityAndPreparationNeverReadLaneOrderTables` 确定性禁止这类读取；跨用户仍拒绝，缺失订单仍返回 `ORDER_NOT_FOUND`，实际取消和释放冻结由所属 Lane 完成。编码层符号缓存、DirectBuffer 读取及实验目录已有低分配改动一并保留。

HotSpot JDK 27 的干净构建及 167 项撮合、资金、并发顺序、撤单、协议和实时投影契约回归通过；其中六产品线的单笔和批量撤单均有覆盖。本轮未执行 Aeron Cluster 快照或恢复场景，也未启动 wallet。吞吐和限频记录见主 README 的本次采用说明；本轮未据此宣称全链路零堆分配。


### 2026-10-11 Lane 活动上下文与停车唤醒

`DeterministicExchangeCoreAdapter` 的命令时间戳/scope 和 primitive 用户注册表分别归每个 Matcher 分片线程，不再以一个共享字段替代 ThreadLocal。`TradingRuntimeState.currentLaneCommandScope()` 根据实际工作线程取得所属 Lane，Owner 使用独立的控制 scope；无关查询线程不能借 Owner 的活动 scope 获得写权限。Scope 进入、完成和回收不新增业务镜像。

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
