# Aeron Service 八项简化验证（2026-10-18）

## 业务顺序与修改范围

开始分析时 HEAD 为 `39af98e24`；只处理本轮列出的八项。期间其他任务提交了 CPU 亲和性改动
`d7fc29a7d`，另有未提交的索引及热路径优化。最终验证使用 `d7fc29a7d` 的源码归档，只叠加本轮
改动，在本轮临时目录中独立编译，避免其他 Maven 进程覆盖共享 target。没有创建或切换分支。
本轮不提交其他任务的改动，也不代替其专项验收。构建使用 HotSpot Corretto 27+33-FR、Maven 3.9.16，
开始及测试中可用磁盘均为 321 GiB。

Owner 固定批次意图和配置 → 所属 Account Lane 逐项解析、校验并冻结 → 交接命令内稳定准入结果 →
Matcher 发布确定性事实 → Account Lane 应用，SystemLedgerLane 校验资金并完成 → Owner 有序回包及回收。
准入失败仍按已有 orders/clientKeys、用户前值及余额补丁回滚；成交和回包仍等待真实完成边界。
未引入新生产类、接口、缓存、状态镜像或异步阶段。

## 八项对应实现

| 项目 | 最终代码与职责 |
| --- | --- |
| 1 五组批量临时数组 | `PlaceBatchAdmissionEvent.execute` 从稳定的 `source.decision(index)` 读取当前项；openInterest、生命周期、资金费状态、symbolId、assetId 使用局部值。删除 `OrderBatchPending` 对应五组数组、初始化、清理及跨层参数；生产唯一调用方始终提供 source，删除 null source 的旧输入分支。orders/clientKeys 和结果数组继续承担回滚、Matcher 交接与完成收集职责。 |
| 2 重复准入订单快照 | `OrderBatchPending.captureAdmittedOrder` 直接捕获到该项 `OrderBatchItem.admissionCopy`，批次输出数组借用同一份稳定引用；删除 admittedOrderCopies 数组及 Matcher/Owner 再次复制。结果 after-image 独立保留。快照只在整个批次终态回收后清理、复用。 |
| 3 恒 null 的 Treasury 参数 | `OrderedCommitCoordinator.finishAppliedMatching` 删除 settlementTreasuryDelta 参数、别名和不可达写入分支；Direct 财务收集仍由原 `completeDispatchedMatcherSettlement` 和 Ledger 路径负责。 |
| 4 临时余额对象 | `RuntimeAccountStateTransitions` 更新已有余额和划转扣款时精确计算局部值，再使用原 primitive replaceBalance 入口。首次注册余额仍创建真实权威单元，且在首次状态写入前完成构造校验。 |
| 5 旧 taker 游标 | 删除 AccountLaneState.takerFillCursor、beginTaker、无 Lane 参数的 begin、单参数 publish、隐含 taker 的 applyNext 和测试便利 apply 重载。保留实际生产 singleFillCursor 与 DerivativeSettlementAccumulator；测试使用相同的显式 Lane 计算和发布入口。 |
| 6 恒真的响应所有权判断 | 删除 builderResponseOwned 标志，沿用原本必然执行的 transferResponseOwnership；Lane lease、Arena 引用和结果持久化顺序保留。 |
| 7 无用接线与参数 | DirectCommandDispatcher 不再注入/保存 InstrumentSettlementCommands；PositionCommands 两个方法删除未使用的 clusterTimestamp。交割/期权的实际 InstrumentSettlementCommands 仍由 OrderedCommitCoordinator 调用。 |
| 8 旧非 Direct 结算通道 | 删除 runtime/dispatcher.dispatchMatcherSettlement 和 event.prepare；删除事件的旧 plan、单项资产字段、isolatedChanges 和 direct 模式字段，以及执行/完成/收集双分支。唯一入口为 prepareDirect → native result publication → Lane/Ledger 完成。direct() 仅由有效 batchPlanCount 派生，保留活动事件校验，不存在模式选择。 |

不删除共享通知队列：`LaneCancelEvent` 仍使用 expectMatcherSettlement/publishMatcherSettlementReady，
它是实际控制命令的完成交接，不属于旧 MatcherSettlementEvent 入口。产品隔离、冻结算法、精度舍入、
资金不变量、账本、幂等索引、协议和持久化格式没有改变。

## 验证

本轮先完成基线编译：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests compile
```

退出 0，7 个模块成功。编译迁移期间发现旧测试签名、未引入 Mockito、跨包 fixture 可见性问题，
分别以新签名、测试内最小输入和同包可见的真实模型修正，不增加测试依赖。

工作区首次全量测试失败，包含两个随结算迁移需同步的完成缓存 fixture、其他任务的亲和性 fixture，
以及并发 Maven 覆盖 target 造成的类加载错误。fixture 修正后改用独立源码目录验证；首次复制整个
共享 TradingRuntimeState 时还带入其他任务尚未提交的 instrument 索引改动，编译未通过，随后重建该文件，
只应用本轮四个修改块。以下最终结果只对应隔离候选源码，不使用上述失败结果作通过证据。

最终全量命令：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am test
```

退出 0，7 个 reactor 模块成功，15 分 20 秒。

| 测试模块 | 总数 | 失败 / 错误 | 跳过 |
| --- | ---: | ---: | ---: |
| aeron-service | 1495 | 0 / 0 | 2 |
| aeron-client | 66 | 0 / 0 | 0 |
| aeron-protocol | 249 | 0 / 0 | 0 |
| instrument-api | 14 | 0 / 0 | 0 |
| product-api | 15 | 0 / 0 | 0 |

包括 `ClusterCommandPipelineTest` 320 项、永续财务矩阵 18 项、交割/期权财务矩阵 29 项、
六产品线生命周期恢复 6 项、提交恢复 18 项及快照恢复 13 项。Service 跳过的是 Linux 专属亲和性用例
（本机 macOS）和需显式开启日志采样属性的单撤单统计用例；后者另行开启属性补测。
全量完成后，最后的源码收尾仅删除重复复制逻辑留下的空条件、修正游标注释，及使测试复用原游标；
以下精确测试重新编译并验证这些最终源码。

精确补跑还暴露 `CoreOrderedOrderBatchTest.keepsPriorItemsButFailsStickyAfterMatcherDivergence` 的
竞态：第一项 nextIndex 前进只能说明第二项已提交，不能说明其 Lane 准入已经完成；测试立即注入
`EXCHANGE_CORE_FAILURE` 会先毒化 Matcher，第二项可能尚无 reservation，之后“第二项必须保持冻结”
的断言因而无效。修正测试在注入前用已有 `awaitMatching` 等待第二项真实准入及 Matcher 交接完成，
仍断言 prior items、冻结、幂等结果、提交序号及 sticky fatal 行为。生产故障处理没有为此修改。

最终精确与集成命令：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=CoreOrderedOrderBatchTest,OrderBatchSlotReuseTest,RuntimeDerivativeFillCalculatorTest,MatcherTreasurySettlementTest,OrderBatchSettlementWaitTest,ProductionSourceBoundaryTest,SpotMixedWorkloadTest,DerivativeMixedWorkloadTest,RealtimeWorkloadTest,CorePerpetualEndToEndBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

退出 0，20 个 reactor 模块成功，2 分 21 秒。Service 精确 70 项（0 失败、0 错误、1 项属性跳过），
benchmark 集成 18 项（全部通过）：现货混合 2、衍生品混合 2、实时事件 12、永续端到端 2。
这些内嵌业务夹具覆盖 maker/taker 下单、撤单、成交、余额冻结、仓位与财务不变量，以及实时快照/增量交接；
不是部署后的网络端到端验收。

诊断属性及故障注入再次补测：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am '-Dtest=CoreOrderedOrderBatchTest#singleCancelFlushesMatchingPhaseStatisticsAndReleasesFunds+keepsPriorItemsButFailsStickyAfterMatcherDivergence' -Dsurprising.aeron.matching-phase-log-interval=1 -Dsurefire.failIfNoSpecifiedTests=false test
```

退出 0，2 项全部通过、没有跳过。Linux 亲和性测试仍因本机 macOS 未执行，属于其他任务的 Linux 平台验收边界。

新增 `OrderBatchSlotReuseTest` 检查每项只持有一份稳定准入副本、不同批次不共享、回收后复用不污染另一批次。
原游标测试保留逐笔舍入、反向持仓、版本与失败重试断言，改用生产显式 Lane 入口。
`MatcherTreasurySettlementTest` 使用 prepareDirect 与 native MatcherResult，保留不同账户线程、满 Ledger 队列、
财务完成前不可回收、重复收集拒绝和 Ledger 通知未返回时复用事件的原有断言。
完成缓存单测使用 Direct 发布/路由标记，检查 Ledger 不执行账户工作、不写 Owner 完成缓存及复用重置。

## 局部分配观测方法

JMH 仅比较两个实际生产类：从 `d7fc29a7d` 提取 `RuntimeAccountStateTransitions` 和
`OrderBatchPending` 源码，以相同 JDK 和候选依赖重新编译，before 将这些 class 放在 classpath 首位；
after 使用候选 class。其余依赖和测量源码相同，避免把并行任务的变化计入收益。
`adjustExisting` 对已存在账户交替加减 1，`debitExisting` 从已充值账户逐次扣 1，均读取余额并清理变更集；
`createBatch20` 构造实际 20 项 `OrderBatchPending` 并交给 JMH 消费。后者是工作区创建成本，
不代表已池化批次每笔订单的分配量。复用快照的收益由所有权与生命周期测试证明，不虚构每笔分配节省。

参数为 JMH 1.37、单线程、1 fork、2 × 1 秒预热、3 × 1 秒测量、G1、256 MiB 堆、`-prof gc`。
单 fork 短测、没有温控及端到端负载，耗时只保留为原始观测，不作延迟、吞吐或容量结论。

两组 JMH 均退出 0，分配观测如下（四舍五入，B/op）：

| 操作 | before | after | 解释 |
| --- | ---: | ---: | --- |
| 已有账户调账 | 120 | 80 | 本路径实测减少约 40 B/op |
| 已有账户划转扣款 | 120 | 120 | 未测得分配下降；仅报告源码和维护复杂度降低 |
| 创建 20 项批次工作区 | 11096 | 10544 | 每次创建实测减少约 552 B；不是每单节省 |

源码、基线提交、class/source SHA-256、原始逐次 JMH 数据及各模块测试汇总见
[机器可读证据](aeron-core-simplification-20261018-evidence.json)。余额及批次测量之外的六项只报告结构与
生命周期简化，不从删行数推断性能收益。

## 当前环境边界

本轮未启动 wallet；部署后的 HTTP/WebSocket、常驻独立做市、真实 UDP/Consensus/Archive 端到端容量和长稳
不由本地 Maven 内嵌夹具或局部 JMH 替代。功能验证与分配观测分别记录，不将结构删除推断为吞吐提升。

清理完成：本轮 Maven/JMH 进程均已退出，证据入档后已删除本轮系统临时目录
`surprising-core-simplify-al1i4600`，包含隔离源码、基线 class、原始日志、采样 class 和独立 target。
该名称仅作历史定位。本轮未创建外部集群、Archive、JFR 或堆转储；标准工作区 Maven 产物、其他任务的文件
和进程均保留。提交前 `git diff --cached --check` 通过，并逐文件确认暂存 Java 源码与隔离验证候选完全一致。
