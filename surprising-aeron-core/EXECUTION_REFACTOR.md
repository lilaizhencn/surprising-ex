# 交易执行链路改造状态

本批改动已通过正确性验证，尚未进行性能验收；不代表全部业务已经完成迁移。

## 已修改的业务路径

| 路径 | 执行顺序 | Owner 终态前的工作 |
|---|---|---|
| 普通下单 | Lane 准入 → Matcher → 预先排队的 Lane 结算事件 → 有序提交 | 准入和依赖顺序派发 |
| 普通撤单 | 入口检查订单身份 → Matcher → Lane 接受则解冻撤单、拒绝则不改订单 → 有序提交 | 准备订单引用和 Lane 顺序位置；不再收集撮合结果后派发撤单 |
| 触发单撮合 | 子单准入 → Matcher 构建成交及触发终态 → Lane 一次应用 → 有序提交 | 准入和依赖顺序派发；不再在撮合后构建触发终态 |
| 普通撤改单 | Matcher 撤旧单并撮合新单 → Lane 解冻、冻结、应用成交 → 有序提交 | 提前解析替换输入；删除 LaneReplaceEvent 和 Owner 替换准备续步 |
| 批内改单 | 逐项准入 → Matcher → 同一次 Lane 撤旧、冻结新单和结算 | 保留下一项依赖上一项完成的校验；删除撮合后的第二次冻结任务 |
| 批量撤单 | 同 Matcher 的撤单段 → Matcher 一次发布该段结果 → Lane 依序撤单 | 收集完成的段及逐项响应；最后一段实际撤单同时推进 Lane 序号，不再增加终态提交任务 |
| 其他撮合结果 | Matcher 直接发布到 CommandSlot | 回收传输槽；仍有下述业务续步 |

入口：`orchestration/TradingCoreRuntime.submitMatching`。撮合到 Lane 的事件：`state/MatcherSettlementEvent`。有序提交：`orchestration/OrderedCommitCoordinator`。

撤改单的新单对象提前构造、由 Lane 直接安装；同一事件内完成冻结及成交，不再创建中间 pending reservation 索引。失败的新单不写入账户；只应用 Matcher 已接受的撤单。

撤单事件不解析成交资产、持仓身份或当前合约配置。拒绝的撤单不写订单终态，也不补写订单时间戳。触发单保留已有成功、拒绝和资金处理语义。

## 并发边界

- Matcher 传输槽只由 Owner 回收，先清理内容再推进回收游标。
- Matcher 先构建直达结果、释放命令路由，最后发布 Lane 就绪信号；批内构建结果不会提前放行 Lane。
- Lane 发布完成位后，不再读取可能已被 Owner 回收的事件字段。
- 空的命令结果轮询不得清空 Matcher 并发发布的结果。
- Lane 队列仍为单生产者队列：Owner 排定位置，Matcher 发布该位置的数据；没有让两条线程并发写入原 SPSC 队列。

## 尚未完成

1. 批量命令仍有逐项 Owner 准入推进；逐项改单仍有批终态 Lane 提交。批量撤单已合并最后一次 Lane 提交；下一项准入及其余批终态元数据补写仍待整合。
2. 清算、资金费、交割和期权结算仍使用控制续步。需要把账户业务推进交给对应执行方，Owner 仅在最终阶段合并全局资金及有序事实；回滚、分页和跨账户汇总不能直接删除。
3. 通用命令仍使用 `LaneCommitEvent` 补写订单元数据、取消关联触发单、推进账户序号。需要逐业务合入最后一次实际 Lane 修改，不能提前发布未完成的账户状态。

## 正确性验证（2026-09-15，通过）

按用户最新要求先执行正确性测试，压测留到最后。HotSpot JDK 25；没有新的吞吐结论。

已修复本轮测试发现的问题：

| 问题 | 原因与修复 |
|---|---|
| 触发子订单缺少提交边界 | 内部登记未携带父命令日志时间、位置；登记时一次绑定，再交 Matcher。 |
| 批量撤单多一次 Lane 任务 | 直达事件未承担终态序号；最后实际撤单段同时提交，保留缺失尾项拒绝和跨 Matcher 分段顺序。 |
| 撤单完成后仍有未发布修改 | 删除末尾提交任务后遗漏变更发布；收集直达撤单事件时登记发布，最终 Owner 提交消费。 |
| 批内 post-only 改单拒绝被当成致命错误 | 结果核对只查普通改单准入；改为读取当前批项的原单信息，只认可已授权的撤单结果。 |
| 致命错误被底层异常遮蔽 | 健康检查先探测 Lane 再看已记录错误；优先抛已记录的致命错误，后续请求保持一致停止。 |

测试修正：结果等待读取直达事件；终态订单断言使用活跃索引删除语义；故障在 Matcher→Lane 真实交接边界注入；余额溢出注入到真实账户且在提交前完成。保留资金守恒、禁止错误提交、重复请求和快照重放断言。

最终按测试类最新有效报告去重：141 个类、1,320 个用例，失败/错误/跳过均为 0。

| 范围 | 用例 |
|---|---:|
| Core 服务：六产品线资金、订单/批量、触发、清算/ADL、资金费、交割/期权、槽位复用、故障和快照恢复 | 952 |
| 协议、客户端 | 156 |
| 产品与合约公共模块 | 24 |
| 9 个受影响基准驱动的功能测试类：完成计数、账户资金、持仓、触发、恢复 | 188 |

服务完整测试首次有 5 个旧辅助断言错误（引用已删除的 `currentAdmission`）；替换为真实未完成工作检查后，恢复类 8 个用例复跑通过。其余完整测试结果保留，不重复计算复跑用例。

```sh
mvn -q -pl surprising-aeron-core/surprising-aeron-service -am test
mvn -q -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimeCommitRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests test-compile
mvn -q -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=LinearPerpetualBenchmarkSupportTest,ClusteredBatchTradingBenchmarkTest,RealtimeWorkloadTest,SpotMixedWorkloadTest,DerivativeMixedWorkloadTest,CorePerpetualEndToEndBenchmarkTest,AccountLaneCommitBenchmarkTest,TriggerCommitBenchmarkTest,ContinuousOwnerBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

基准驱动测试仅验证业务结果，没有启动 JMH 计时或吞吐验收。未测 JFR/分配率、单节点持续饱和吞吐、长稳和 GCP；不推断性能提升。其余外围服务未受本次 Core 内部交接变更影响，未启动 wallet、Kafka 等完整业务环境。上述“尚未完成”的架构迁移不因正确性测试通过而视为完成。

每轮失败及清理记录见根目录 `PERFORMANCE_VALIDATION.md` 的 2026-09-15 正确性验证条目。


## 短时性能诊断（2026-09-15）

按用户追加要求完成真实本机单成员Aeron短测：1 Matcher、4 Lane、BUSY_SPIN、G1、128 symbols、MIXED batch20、在途256，plain/JFR各预热30s+测量30s。plain前两个稳定区间约17.6/18.1万business ops/s，下单p99=26.296ms；驱动含排空汇总17.96万。完成性与资金核对通过，未达30万和5ms参考线。

采样窗口Owner约98%单核、Matcher56%、Lane实际业务执行约25%；Owner仍以结果索引、批量终态及状态发布为主要受限路径。Core分配估算336MB/s、约2.0KB/business op。短轮受IDEA和JIT影响，不证明性能回退或最终容量；详见根目录PERFORMANCE_VALIDATION.md新增记录。未做长稳、GCP，前述架构剩余项不变。

## Owner 冗余工作清理（2026-09-15）

本批按“没有生产消费者就删除；重复计算移到数据所属阶段；资金、顺序及恢复边界保留”的标准实施。

| 入口/职责 | 原因 | 本批处理 |
|---|---|---|
| `CommandResultLedger.storeOwnedResult/get`：最终提交后保留查询结果 | 开放寻址表淘汰只留 deleted 标记，持续插删后 miss/插入可能探测整个512槽；插入还重复查找 | 删除 slotState、find/findInsert、无效满表分支；单次查找，以移位删除闭合探测链，同时更新保留队列位置。修正队列压缩的环绕覆盖风险。保留128条/字节上限、替换原保留顺序及恢复语义。 |
| `TradingRuntimeState` 的账户、持仓、冻结发布表 | 三份 admissionSequences 没有生产读取方，却随每次发布维护 | 这三类表不再分配或维护该索引；订单发布表保留，因为 PendingReservationTracker 依赖它定位尚未结算的批量冻结。直接 put 同时省去一次多余 get。 |
| `OrderBatchExecutor.submitCancelBatchChunk` → Matcher → `MatcherSettlementEvent.execute` → Lane → Owner | 批量撤单直达事件没有复用已有 Lane 结果槽，最终 Owner 重新查结果并编码 | Owner 预构造事件绑定 batch；Matcher 填各项业务状态并发布；用户所属 Lane 应用撤单并捕获结果，结果完整时编码终态响应；Owner 在完成信号后有序提交。复用现有对象/接口，没有新增生产任务、状态容器或中转阶段。 |
| `ClusterMixedCapacityMain.runFor/space`：验收统计 | 排空完成量混入持续吞吐；窗口等待没有独立指标 | 新增 steadyCapacity、drain 和 windowBlockedNanos；保留原完成性汇总。汇总脚本以 steady 为主，并修正 Lane 执行比例70%的单位错误。发压策略、窗口和业务配比未改。 |

仍保留：部分拒单、逐项改单等无法由一次 Lane 结果确定最终查询状态的提交点补齐；命令幂等账本；订单待结算索引；发布视图、故障停机及有序提交。它们有实际业务/恢复消费者。本批没有删除业务动作，也没有宣称 Owner 只剩 offer。

新增生产类/接口/阶段为0；新增 `CommandResultLedgerBenchmark` 仅用于持续插删路径的JMH。新增随机周转测试与无Owner推进的批量撤单终态响应断言；完整service及基准驱动的最新有效结果共1258用例通过（service953、benchmark125、protocol107、client49、公共模块24）。旧反射存储测试不再访问已删除索引，仍检查保留表的数组复用。性能及证据见根目录 `PERFORMANCE_VALIDATION.md` 本轮记录。

## 发布与变更缓冲继续清理（2026-09-15）

主流程仍为 Lane 完成业务修改→Owner接收完成信号→发布状态并按序提交；没有新增阶段或生产类。

- `LanePublication.publish`：账户和冻结记录在发布同一遍释放引用，删除 `LaneDelta.commitTerminalToOwner` 后续清空遍历；订单/持仓还要交给提交变更消费者，保留原缓冲交接。准入发布同遍清空槽位；只有准入使用时才分配 maps/keys/values，结算直接借用 LaneDelta。
- `RuntimeIndexedChangeBuffer`：删除 present 布尔数组及增长、交换、清空代码。单个索引槽区分未预计算、有效值和已预计算删除；最后一种用类内共享哨兵，向消费者仍返回 hasPrepared=true/indexValue=null。保留删除与未计算的区别，避免恢复和控制命令错用旧索引。
- `OwnerIndexedChanges.get`：直接定位槽后读值，删除 containsKey→get 的重复探测；null删除仍能遮盖旧值。
- 删除生产没有调用的 `RuntimeChangeBuffer.drainToAgronaMap` 和 `TerminalTombstoneStore.putKnownAbsent`，相应测试改为实际生产入口。终态客户号索引仍由 `requireOrderIdentityAvailable` 的重复订单检查使用，未删除或改变去重规则。

本轮正确性1258项全部通过：service953、基准驱动125、protocol107、client49、公共模块24；覆盖六产品线资金、批处理、故障与快照恢复。新增预计算删除清理和发布后缓冲释放断言。性能、限制和清理状态见根目录PERFORMANCE_VALIDATION.md本轮记录。

## 从命令生命周期减少分配（2026-09-15）

2KB/business op是整个Core进程口径，包含协议、Matcher、Lane、Owner和响应，不等于一次余额更新。`BalanceRuntime`在所属Lane内原地修改；共享给Owner的订单/冻结/持仓版本仍需保持不可变，否则后续命令会修改尚未提交或正在读取的旧视图。

| 阶段 | 当前分配与原因 | 本批处理/保留理由 |
|---|---|---|
| 解码/决策 | PlaceOrderCommand、symbol/client字符串、ResolvedPlaceOrder等 | 本批未改协议或决策结构；共享币对元数据与请求数据还需分别核对，不能直接删校验。 |
| 准入冻结 | 初始OrderRuntime、ReservationRuntime、用户版本 | 初始订单一次填入已知日志时间/位置，删除未成交订单仅为metadata再复制整份的中间版本。普通、批量和替换单均覆盖。 |
| Matcher结果 | MatcherResult、CoreMatchingResult、证据和成交事件 | 跨Lane只读事实/恢复消费者仍存在；`classify`返回枚举，不分配Outcome对象，不能把JIT内联归因当作独立对象来源。 |
| Lane结算 | 真实变化的订单、冻结、持仓版本 | 实际变化版本保留；释放/消耗为0、剩余冻结不变、FillCursor消耗量未变时复用原ReservationRuntime。 |
| Owner提交 | 变更发布、保留索引、响应编码等 | 保留有序提交和必要结果消费者。新订单未成交时显式登记发布，不能省掉复制后连业务变化一起漏掉。 |

具体入口：`MatchingCommandAdmission`/`OrderBatchExecutor`传递命令已有时间和位置→`PlaceAdmissionEvent`/`PlaceBatchAdmissionEvent`→`TradingRuntimeState.placeOrderInLane/preparedOrder`一次构造；替换单由`MatcherSettlementDispatcher.prepareDirectReplacement`同样一次构造。只在已有池化事件中增加两个primitive字段用于跨线程携带已知数据，没有新阶段或生产类；删除两个纯转发方法和生产无调用的批准入重载。

正确性测试增加：六产品线普通及批量未成交订单从准入到提交保持同一OrderRuntime对象，并与串行结果/快照恢复一致；无变化冻结复用原版本，真实变化不修改旧版本，非法金额仍拒绝。完整验证与分配结果见根目录PERFORMANCE_VALIDATION.md本轮记录。

本批1265项正确性测试通过。短轮无采样273483业务ops/s，下单p99 16.203ms；独立JFR约1996B/business op，Owner98.44%单核。局部构造176B/次；整体分配仍高，主要剩余解码、真实订单/冻结版本和Matcher结果。测量窗有系统swap-in及Owner类加载IO，未通过严格性能验收，详情见验证记录。
