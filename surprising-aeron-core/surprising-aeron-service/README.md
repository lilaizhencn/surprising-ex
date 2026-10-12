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
