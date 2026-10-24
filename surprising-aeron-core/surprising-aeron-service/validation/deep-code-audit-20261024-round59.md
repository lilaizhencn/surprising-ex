# 第五十九轮：执行模式分叉与同步业务驱动审计

基于 `e2a916b64745a83dbb56b135447ae425d081235b`，本轮确认A189–A192四组结构冗余，**只分析并归档，业务源码未改，未编译、运行测试或启动服务**。累计182项已解决、5项确认待清理（A181、A189–A192）、A11候选、4项既有保留；全量字段和约束审计尚未结束。

这轮从正常业务命令的生命周期检查复杂度。入口先激活Core，再解码/校验、派发账户Lane或账本，Owner收齐结果、提交或回滚并生成回执。账户写入、系统资金、持仓索引和命令顺序分别由现有所有者维护。值得收敛的是入口仍选择一套冷执行流程，以及为该选择继续存在的第二套同步编排。

## 激活后的命令执行模式已确定

- [TradingCoreRuntime.apply](../src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java#L989)在业务执行前调用activate。
- [TradingCoreRuntime.activate](../src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java#L1876)在FUSED、PIPELINED两种模式下都启动System Ledger，再将activated设为true；启动失败直接抛出。
- [TradingAccountRuntime.asynchronousCommands](../src/main/java/com/surprising/aeron/service/core/TradingAccountRuntime.java#L896)的判断包含账本已经存在。正常已激活Core中该结果恒为true。
- applyDecodedCommand最终调用同一apply入口。独立[CommittedTradeReplay.apply](../src/main/java/com/surprising/aeron/service/replay/CommittedTradeReplay.java#L105)也调用state.apply，并轮询实际直接控制和撮合工作；当前回放同样会激活账本。

因此正常Core命令和有序提交无需再次选择“冷执行还是异步执行”。资金费命令中“回放走同步分支”的注释已经与当前激活行为不符，真正回放观察者仍由FundingControlContinuation在完成后通知，不能删除该资金明细导出。

此结论限定于现有健康Core业务入口。独立构造TradingAccountRuntime的冷态测试和底层计算工具另有生命周期，不能在全项目盲目删除asynchronousCommands、冷态检查或线程所有权守卫。

## 确认范围

| 编号 | 具体范围 | 建议处理 |
| --- | --- | --- |
| A189 | 9个命令类的12处模式分流 | 保留控制续步主线，删冷分支及仅供它们使用的两个收尾包装，修改过时回放注释 |
| A190 | OrderedCommitCoordinator的6处分流，以及4个冷路径专用编排方法 | 单笔强平、到期、批量强平及风险续扫统一使用实际工作对象和有序收集 |
| A191 | 7个金融/风险同步根，及5个只服务同步驱动的辅助方法 | 先迁移测试用途，再从生产退出第二套同步编排；3个asynchronous选择参数及旧非分页开关可随之收敛 |
| A192 | 2个通用生命周期同步入口、2个省略复用参数的prepare重载 | 同步迁移测试到实际工作入口，移除列明重载；真实Lane派发及其他账户操作的通用执行能力保留 |

A189逐类范围如下，行号为本轮源码基线：

| 命令类 | 模式分流数 | 行号 |
| --- | ---: | --- |
| FeePolicyCommands | 1 | 31 |
| LiquidationCommands | 1 | 34 |
| PositionCommands | 2 | 33, 50 |
| LeverageCommands | 1 | 33 |
| AdlCommands | 1 | 31 |
| BalanceTransferCommands | 3 | 41, 57, 96 |
| RiskCommands | 1 | 77 |
| InsuranceFundCommands | 1 | 30 |
| PerpetualFundingCommands | 1 | 44 |

合计12处，覆盖余额、转出/完成划转、杠杆、持仓模式、逐仓保证金、费用政策、保险基金、资金费、ADL、强平确认和风险续扫。转入仍须保留收款用户及目标产品线校验，账户资金和持仓约束继续在实际Lane执行。

A190的6处位于OrderedCommitCoordinator第412、437、875、884、905、929行：单笔强平、到期、批量强平请求生成、批量执行、批次风险续扫和最终控制挂起。4个配套旧方法为LiquidationCommands.executeLiquidationRuntime、advanceLiquidationCancellationRuntime，以及InstrumentSettlementCommands.settleInstrumentRuntime、addSettlementUsersToResult。applySettlementChangedIds仍被实际结算提交读取，不属于删除范围。

A191的7个同步根为：

- RuntimePerpetualFundingProcessor.applyRuntime。
- RuntimeLifecycleSettlement.applyRuntime。
- RuntimeAdlExecution.applyRuntime。
- RuntimeLiquidationResolution.applyRuntime。
- RuntimeDerivativeLiquidationProcessor.applyExecutionRuntime、applyCancellationAdvanceRuntime。
- RuntimeDerivativeRiskProcessor.continueRiskBudget。

它们当前有直接测试用途，生产引用位于上面的冷分支，不能把“存在调用引用”直接等同于“正常业务会执行”。随驱动退出可处理的5个独占辅助为FundingWork.finish(Object[])，以及生命周期的openOrders、cancelOrders、groupUsers、groupOrders。ADL、强平确认和强平执行的私有分流仍接受asynchronous参数，true由实际begin入口传入，false来自列明的旧同步根；先迁移这些根的测试，再收敛此参数及对应分支。

到期同步入口还根据chunkCommandId是否为空选择非分页/分页，并在非分页时物化全部活动订单、按Integer.MAX_VALUE选择用户；当前正常Core使用实际分页续步。这份UUID和chunked选择不再需要混入生产同步驱动。真实SettleInstrumentCommand的结算编号、固定价格、双游标、预算、订单完成状态和保险需求继续用于交易与恢复，不可随旧模式开关删除。

金融测试迁移必须保留现有精确余额、冻结、手续费、持仓、保险、终态及恢复断言。优先让测试调用已有工作对象并推进真实完成；需要保留参考编排时将其留在测试源码。不能删除资金断言，也不能将期望值改成同一生产方法的输出。同步参考编排与实际Lane共享公式的既有事实也必须明确，不将状态一致当成独立公式证明。

A192的4个确切重载为：

- TradingAccountRuntime.executeLifecycleSettlements(Iterable,ToLongFunction,IntFunction)：当前唯一外部使用是TradingAccountRuntimeTest。
- TradingAccountRuntime.executeLifecycleSettlements(long,int,IntFunction)：当前生产消费者只在上述资金费和到期同步驱动。
- RuntimePerpetualFundingProcessor.prepare(command,indexedUsers,runtime,identities)：省略reuse的重载，生产只由旧同步根转发，另有测试使用。
- RuntimeLifecycleSettlementContinuation.prepare(command,indexedUsers,activeOrders,runtime,identities)：省略reuse的重载，当前只有测试使用。

这20个列明的下游方法/重载按完整owner、名称和JVM描述符核对；它们与18处模式判断的清理有先后依赖，尚未实施。不能将它们全部视作当前无调用的方法直接删除。

## 本轮明确保留的边界

- asynchronousCommandScope还被awaitLaneCommit读取，用于禁止显式异步范围等待未完成事件；不能因asynchronousCommands在Core激活后恒真就删除该字段和enter/exit边界。
- SystemLedgerCommand的阶段完成与ScopeClose关闭完成不同；资金校验、保险预留及回滚需要区分它们。
- SettlementWork的Owner Phase和账本FinancialPhase表示不同线程各自的工作，不按字段或枚举相似就合并。
- symbolId是内部稠密编号，instrumentId是外部永久身份，二者不等价。
- RuntimeLifecycleSettlement的两个laneMask重载均被真实SettlementWork消费；共享的prepareLane、applyLane、cancelOrdersOnLane、requireInstrument和进度校验继续保留。
- AccountControlTask.getAsBoolean有基准真实调用，不按普通应用调用少判断为死接口。

本轮没有新增可直接删除的Cluster持久化字段确认项。18处模式分支及同步驱动属于执行结构，不能由它们推导快照数据无用。旧A181仍是单独确认项：资金费续页已有固定计算价却仍要求当前行情存在，尚未修改。

## 证据与后续验证

使用CodeGraph CLI定位入口、定义和调用关系，再按现有4017份classfile的完整类型/方法/描述符补足Java调用与重载；均为major71，这些是前轮编译产物，本轮没有重新构建。动态方法引用也包含在常量池引用核对中。引用扫描是静态证据，不能替代删除后的编译、交易或恢复验证。

建议先收敛A189/A190的入口分流，再迁移A191/A192测试用途及下游驱动，最后独立处理A181。后续实施需干净编译受影响模块，运行资金/持仓、强平与ADL、分页风险、命令顺序、恢复和CommittedFunding/Delivery/Option/LiquidationReplay回归；离线回放工具及明细导出也要覆盖。此处是后续验证要求，本轮没有运行这些命令，不沿用前轮642例或六线HTTP/WebSocket成绩宣称新修改通过。

本轮仅修改本报告、中文README及[机器审计记录](command-data-audit-20261023.json)；执行JSON解析、源码基线哈希核对和git diff --check。逐签名消费者、18处分流位置及24份源码摘要保存在fifty_ninth_analysis_round。摘要归档后删除本轮临时classfile清单和脚本，未启动服务或性能工具。
