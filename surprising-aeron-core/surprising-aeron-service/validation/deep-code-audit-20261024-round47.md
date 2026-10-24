# 第四十七轮：撮合、调度、身份与快照冗余分析

源码基线：`183c7f332753d5559eb438a122465758cf017c2a`。本轮仅分析及归档，没有修改 Java、命令或快照格式，没有运行编译、测试、服务、SQL 或性能工具。全量审计仍未完成。

从命令槽、Matcher 证据绑定、账户结算、有序提交追到快照写入和恢复，新增确认 A155–A160 六组；原 A154 管理员调账审计缺口仍待修复。累计 160 项：148 已解决、7 已确认待处理、1 候选、4 保留。这里的数量是问题组数，不是待删字段总数。

## A155：撮合结果保留无人消费的订单号副本

- `CoreMatchingResult.nativeOrderId`（`service/matcher/CoreMatchingResult.java:54`）只由构造和 `bindEvidenceInPlace` 写入，唯一读取是生成的 getter。`MatchingResult.nativeOrderId()` 没有生产或测试调用。
- `MatcherSettlementEvent.directOrderId`（`service/settlement/MatcherSettlementEvent.java:149`）在单项、批量、改单和拒绝发布时赋值，唯一读取也是 `nativeOrderId()`；该实现的 4 次调用全部来自测试。
- 普通结果的构造校验仍要求这个无人消费的标量非负；`MatcherEvidenceLedger.bind` 还逐层传递它。`OrderedCommitCoordinator.validateMatchingEvidence` 实际核对 Core 序号、UUID 和 Matcher 分片序号，不读取该副本。

可删除：两份结果字段、getter/接口声明、普通结果的绑定参数与相应自洽约束。**不能删除实际订单身份**：`publishDirectNativeResult`、批量、改单及拒绝分支仍用传入 `orderId` 核对准入订单和构建结算计划。保留这些参数及断言、原生成交事件和撤单成功前缀；测试改为验证真实订单、余额和结算结果，不能整段删除有效交易断言。

这两份字段属于运行中结果搬运，不属于当前 Cluster 命令或快照字段。

## A156：未接入生产的等待轮询缓存

`service/order/OwnerCommandPipelineState.java:76` 的三个字段为：

- `waitingDispatchSequence`
- `waitingMatchingProgress`
- `waitingMatchingNotification`

把通知标记设为 true 的入口只有 `rememberMatchingWait`；它的 2 次调用全部在测试，`canSkipMatchingPoll` 的 6 次调用也全部在测试。生产路径只初始化或清除标记，因此 `TradingCoreOwner.ownerWorkAvailable` 第 513 行的对应分支不会成立。

可删除：三个字段、两个未接入方法、只回显该标记的 getter、清理赋值和不可达分支。现有测试中只模拟此缓存的用例可以移除；真实完成通知、Owner 本地续步、依赖前缀和窗口退休测试继续保留。不能顺带删除 `ownerCompletionAvailable` 或 `MatchingPipelineProgress` 的真实通知处理。

## A157：身份字典版本只有累加，没有业务读端

`service/core/identity/RuntimeIdentityRegistry.java:381` 的 `dictionaryVersion` 在资产、标的、客户端和持仓身份分配时精确递增，并在辅助恢复时重算。getter 的 8 次调用全部在测试；其余读取只用于给自身累加，没有缓存失效、命令准入、查询、哈希或 Cluster 恢复消费者。

可删除：版本字段、getter、累加/恢复公式，以及只断言计数变化的测试内容。保留真实编号分配、确定性键、碰撞检查、引用计数、Owner/Lane 归属和分配检查点回滚。测试应断言身份稳定、正确复用和错误输入不产生额外身份。

该计数并没有单独写入当前 Cluster 快照，删除它不应引入格式迁移或新兼容分支。

## A158：身份表另有一套测试专用快照和恢复结构

同一类的 `snapshot()`（第 1231 行）有 13 次测试调用、无生产调用；`restore(Snapshot)`（第 1362 行）有 6 次测试调用、无生产调用。第 1401 行的 `Snapshot` 保存 8 个组件：四份身份映射，以及 `nextAssetId`、`nextSymbolId`、`nextClientKey`、`nextPositionKey`。

它另行复制/包装映射、生成存活数游标、校验游标并重建身份表。实际 Cluster 恢复使用 `TradingCoreRuntime` 新建身份表，再由 `RuntimeStateProjector.project` 从交易事实重建身份，并恢复账户 Lane；不使用这个 record。

可删除：这套生产包中的测试专用导出、导入及 8 组件结构。先迁移现有测试：跨恢复、故障后保留状态等断言使用实际快照恢复链；仅观察身份集合的断言使用测试目录内必要的观察辅助。不能因删除辅助格式而削弱真正的恢复测试，也不能删除运行期仍用于编号和回滚的分配游标。

这 8 项是辅助格式组件，**不能将其计作已找到 8 个实际 Cluster 落盘字段**。

## A159：快照头将同一 Core 水位保存两次

- `SectionedCoreSnapshotWriter.capture` 第 73 行要求 `coreSequence == state.appliedCommandCount()`。
- 同一快照头第 174、176 行分别写入 `appliedCommandCount` 和 `coreSequence`，占用两个 long。
- `CoreSnapshotImage`、内部 `HeaderManifest`、公开 `CoreSnapshotManifest` 均搬运两份值，再用相等约束检查自身副本。
- 恢复时 `SectionedCoreSnapshotValidation.validatePairing` 第 91 行再次检查二者相等。

可只保存一个 Core 水位，贯穿 Image、Writer、Parser、Manifest 和离线查看工具；每份实际快照可少存一个 long（8 字节），无需为旧格式增加读取分支。实施时需要更新当前外层格式版本及相关偏移测试。

必须保留独立来源的核对：Matcher 段与 Core 水位配对、账户 Lane 水位、来源摘要、业务/资金哈希和封套 CRC。`SnapshotFence.coreSequence` 是异步捕获前固定的水位，之后用于检测冻结期间状态变化，也必须保留。独立的 `projectionSequence` 并不等于 Core 应用数，不能一起合并。本项尚未据此认定其他校验码或摘要多余。

## A160：账户控制准备保留两个零修订号重载

`service/account/AccountControlTask.java:180` 的短版 `prepareLeverageChange` 只传入 `beforeRevision=0`，仅有 1 次测试调用；第 215 行的短版 `preparePositionModeChange` 没有调用。生产路径的 `DirectCommandSlot.deferLeverageChange` 和 `deferPositionModeChange` 已使用带真实 `beforeRevision` 的完整入口。

可删除这两个默认值重载，让测试传递真实命令前修订。完整入口、账户 Lane 派发、状态变更标记及发布语义保留；不能把仍有用途的 `beforeRevision` 字段删除。

## 已排除的误报与未完成项

- `CoreMatchingResult.cancellations/nativeMatcherSequence/nativeMatcherShardId` 有 `MatchingResult` 接口的真实生产消费者；不能按具体类的直接调用数量删除。
- JFR `MatcherPrefixFailure` 的字段由事件机制读取；队列填充字段和 VarHandle/继承访问字段不能按普通 getter 扫描删除。
- A154 的管理员身份、原因和引用号具有真实审计用途。长期资金索引会在近期收据退窗后阻止同一 UUID 再次入账，但返回重复成功时不重建原响应正文。后续实时余额不能作为原调账历史余额。该写链缺口本轮没有修复。
- `RuntimeIdentityRegistry` 的真实线程门禁、身份碰撞检查和持仓/客户端引用生命周期继续保留。

证据使用 CodeGraph 结构查询、当前源码、现有 4,015 份生产/测试 classfile 的 owner/name/descriptor 引用筛选；方法句柄与父类成员访问纳入筛选，接口/JFR/反射语义再逐项核对。已有编译产物仅作证据补充，本轮没有重编译；扫描候选数量不能作为可删除数量。

下一步实施可先处理 A155–A158、A160 的内部冗余，再处理 A159 的当前快照格式；只运行相关模块的干净编译及测试源码编译，不执行测试或启动服务。资金正确性和运行恢复是否正常仍需实际场景验证，不能由本次静态分析推断。
