# 第六十轮：收敛命令执行模式并清除同步驱动

基于 `802b39ba5ddac53dc6b89b0262a4ff4b7919a42e`，完成A189–A192四组。当前累计186项解决，A181仍确认待处理，A11候选、4项保留；全量业务字段和约束审计未结束。原范围见[第五十九轮审计](deep-code-audit-20261024-round59.md)。

正常Core命令及独立日志回放均先激活账本。现在命令入口与有序提交直接进入原工作对象，不再重复选择冷执行流程；账户Lane改账户，系统账本改总账，Owner按原顺序收集、校验、提交或回滚并生成结果。实际交接、资金守恒、订单前缀与恢复边界保留。

| 编号 | 本轮结果 |
| --- | --- |
| A189 | 9命令类删除12处分流；删除转出发布、资金费收尾及Core.asynchronousCommands别名3个失去用途的包装 |
| A190 | 有序提交删除6处分流及4个冷路径编排方法；强平、到期、批量执行和风险续扫继续使用实际工作对象 |
| A191 | 删除7个同步金融/风险根、5个独占辅助、3个私有模式分流器和3个同步完成辅助；存活正文直接并入原begin入口，共18个方法退出生产 |
| A192 | 删除2个executeLifecycleSettlements重载及资金费/到期各1个省略reuse的prepare重载；测试显式传null并调用实际工作 |

合计删除18处Core模式判断和29个方法，生产Java修改20份，新增107行、删除872行，净减765行。另移除3个asynchronous选择参数、恒true的chunked参数及旧同步结算的非分页UUID输入。没有新增生产类、字段、接口、状态副本或异步阶段。

测试侧扩展已有Funding、Liquidation和Risk夹具，增加小型RuntimeLifecycleSettlementFixture，均推进实际工作对象；共享等待放在已有CoreTestCompletion中，十秒内没有完成就失败。测试用生命周期入口也移除合成UUID参数。资金/持仓等期望值不从被测对象回填。冷投影与真实线程测试现在共享生产计算和续步，其状态一致不能作为独立公式或独立编排证明；原有金额和状态硬预期继续用于具体场景核对。

静态对照确认最初6个修改测试类中的721条列明断言方法和期望参数相同。真实Work中54个保留方法正文逐字相同；12个命令保留分支与原true分支一致，ADL、强平确认及强平执行的begin正文等于原存活分流正文。账本命令/线程、派发器、风险协调器、金额计算器、快照写入/配对/交易状态编解码等8份关键源码全文与基线相同。资金精度、舍入、冻结、保险、回滚及线程所有权没有改为新的规则。

本轮没有删除Cluster持久化字段，也没有改变命令/快照或HTTP/WebSocket契约。Core schema16、交易状态快照v55、分段快照v30、Matcher快照v10保持原值。asynchronousCommandScope禁止异步阻塞等待的守卫、SystemLedgerCommand阶段完成/范围关闭、SettlementWork的Owner/账本阶段及两个真实laneMask重载继续保留。

执行前确认Amazon Corretto HotSpot JDK27+33、Maven3.9.16和充足磁盘。先进行迁移后的干净编译，17个reactor模块全部SUCCESS，退出0，用时74.555秒：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-gateway,surprising-realtime/surprising-realtime-provider -am -DskipTests clean test-compile
```

首批金融、风险与账户回归172例全通过，0失败/错误/跳过：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreSpotAssetUnitsTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreFinancialLedgerProductionTest,CoreLifecycleStateTest,CoreRiskStateTest,TradingAccountRuntimeTest,AsyncFundingCommandTest,CoreOptionPremiumFeeFundingTest -Dsurefire.failIfNoSpecifiedTests=false test
```

后续命令、Lane、快照、回放回归实际执行601例，597通过、2失败/2错误，退出1；因此tools模块当时跳过，不能计为工具测试通过：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools -am -Dtest=ClusterCommandPipelineTest,CoreOrderedOrderBatchTest,LaneReplacementResponseTest,RuntimeCommitRecoveryTest,SectionedCoreSnapshotCodecTest,SharedProductLineSnapshotContractTest,CoreNativeSnapshotProductLineTest,SnapshotFieldEvolutionTest,TradingStateSnapshotCodecTest,OwnerPositionRetirementTest,TradingCoreOwnerTest,CoreFundsLedgerIndexTest,RuntimeDerivativeMatchProcessorTest,MatcherTreasurySettlementTest,SettlementLaneWorkerTest,SettlementChangesReuseTest,ParallelRiskScanTest,RiskBatchBudgetTest,SystemLedgerControlTest,AsyncAccountBalanceCommandTest,CoreCommandFingerprintTest,CommittedFundingReplayTest,CommittedDeliveryReplayTest,CommittedOptionExerciseReplayTest,CommittedLiquidationReplayTest,CommittedTradeReplayTest,OfflineReplayMainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

失败暴露了两份原测试夹具的问题，生产保护保持原样：

- SystemLedgerControlTest的两个局部类构造器使用继承的空runtime字段，现显式接收ownerRuntime；保留保险、费用、资金费进度及账户拒绝后的回滚断言。
- 保险prepare工厂每次新建任务，原测试误要求返回同一实例。测试改为要求独立实例，旧任务的范围关闭、保险75扣20后为55及产品修订2仍核对。曾尝试并不存在的复用重载，测试编译拒绝后撤回，没有为测试增加生产接口。
- CommittedLiquidationReplayTest请求预算4096超过当前scanBatchSize，改为使用实际配置。增加执行成功断言后，确认原标记价1/执行价95样本确实被STALE_MARK_PRICE拒绝。测试行情改为95，测试维持保证金率设9%，确保该价下已满足强平条件；继续要求历史执行价95、实收费、费率、日志时间、重复回放及业务哈希正确。

过程中测试编译与非法样本诊断的失败命令/退出码/日志摘要全部保留在机器记录，未作成功成绩。最终复验覆盖原失败类、撮合状态、结果保留与离线工具，服务65例、tools5例，共70例全部通过，退出0，用时43.207秒：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools -am -Dtest=SystemLedgerControlTest,CommittedLiquidationReplayTest,CoreMatchingStateTest,CoreResultLedgerTest,OfflineReplayMainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

按`classname + testcase name`合并首批、后续及最终复验，得到 **38个测试类、837个不同用例全部通过，0个未解决失败/错误，0跳过**。四个原失败项均已复验通过；这是本轮多条命令的去重结果，不能表述为一次Maven命令运行837例。

范围包括现货、正向/反向永续、交割、期权金额与费用；原账户Lane和账本控制、保险预留/资金回滚、批量风险与强平、有序提交和恢复；资金费、交割、期权、强平明细的已提交日志回放；以及真实离线工具的日志/快照路径。没有启动独立服务、真实多成员网络Cluster、Kafka/SQL、HTTP/WebSocket/UI或wallet。未运行JMH/JFR/压测，不沿用此前六线实测或作性能提升结论。

最终再次检查4018份当前classfile，均为major71：29个删除方法的定义和完整类型调用引用均不存在，旧validateProgress(...,boolean)签名不存在，新增结算夹具只在test-classes。34份最终Java源码哈希及静态对照、各轮命令退出码、失败修复、每份测试XML摘要见[机器审计记录](command-data-audit-20261023.json)的sixtieth_cleanup_round。

关键日志SHA-256：干净编译`ff68193962146cdc0e13960bc416de3c6c80f45d2de8b4a6403909648e61c69d`；首批金融`0bbad73498d3d1ba6b161854ad9024a99f65c712cd32380eddf8ba6994afe2ba`；初次后续回归`fedf83df078b771844fbf8c83a99462f747b4f2545592ebb1d934ff46c490f77`；最终复验`35ff0d02b3949074ffd0691b999fdebb4294e434c3e1f4f14fd5f0f18c27ccf5`。归档后删除本轮临时脚本、源码副本、classfile清单及完整日志，保留Maven产物；未启动需另行停止的服务。
