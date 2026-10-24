# 第六十三轮：补亏直接使用 Lane 回执和原账本

本轮基于 `10d11e6978839583eba72665dbfce18c5fb70fd6`，解决A195–A198四组，累计193项解决、A11候选、4项保留，全量审计继续。

Owner只保存实际命令和路由，账户Lane捕获源强平及同资产索赔回执；账本直接接收回执有效前缀，计算确定性份额，完成业务校验后一次更新保险余额及亏空。Owner仍等源账户写入和原财务范围完成再提交；没有新增阶段或跨越所有权边界。

| 编号 | 删除的重复设计 |
| --- | --- |
| A195 | 仅CoreLifecycleStateTest调用的resolve(runtime, identities, candidates, id)冷包装；测试使用真实回执/账本入口，不另写分配算法 |
| A196 | 账本内一次性ResolutionInputs记录的4字段、Work.allocation缓存及两个恒null的prepare参数；回执收集并入真实resolve入口，目标缺口直接取匹配的原事实 |
| A197 | Work.treasuryDelta字段及清空/搬运/应用链；一次保险支付直接精确计算余额与亏空，并调用原ledger.setInsurance |
| A198 | Work.laneMask字段；读取和写回时按既有sourceUserId与不可变拓扑取得源Lane位图 |

合计删除7实例字段、1输入record，生产2文件新增29行、删除110行，净减81行；业务入口/工厂净减2个。没有新增生产类、接口、字段或阶段。sourceUserId、assetId、回执数组/有效前缀和实际异步完成标记仍承担路由、账本和线程交接职责。

原始回执与源记录在同一Lane回调读取同一不可变强平事实，因此删除目标缺口副本不会改金额。回执前缀收集与分配正文在替换输入访问后对照一致；allocateAsset正文未改，业务拒绝码及先后顺序未改。BigInteger比例、舍入、余数补一、缺口/预算/优先级检查保留。

直接记账仍在原Treasury所有者和SystemLedgerCommand范围中：两项Math.subtractExact结果在setter调用前计算，setInsurance仍通过captureAssetBefore保存全部资产前值，后续资金验证与回滚机制未改。SystemLedgerCommand、TreasuryRuntime、RuntimeTreasuryDelta通用实现、RuntimeAdlExecution和ResolveLiquidationCommand源码均与基线相同。通用差额仍供真实成交、ADL等交接使用，只从不需要多资产交接的本流程移除持有字段。

本轮更正第六十二轮报告对ResolutionInputs用途的概括：生产创建与消费均在同一账本回调，仅测试冷包装预先构造输入；没有独立线程交接需求。真正跨线程的回执和AllocationInputs仍保留。

命令、快照、HTTP/WebSocket格式不变。本轮7字段均为工作对象/临时输入，未把它们算成Cluster持久化字段删除。

HotSpot JDK27、Maven3.9.16和214Gi磁盘核对后，18模块干净构建成功；11类136例相关回归通过，0失败/错误/跳过。原金融期望值保留，保险比例测试补全额覆盖、零预算、未知目标、空槽和前缀外重复回执检查，并关闭测试投影资源。

随后依次启动六线真实服务并执行HTTP/WS业务验收，每线1项均0失败/错误/跳过；完成并停止当前产品后才启动下一条。

| 产品线 | 业务实测 | 主场景恢复前后命令数 | 主场景恢复前后哈希 |
| --- | --- | --- | --- |
| SPOT | 通过，资金差额0 | 40 | `221131cc6693789e`，一致 |
| LINEAR_PERPETUAL | 通过，资金差额0 | 760 | `de993d899049a0b9`，一致 |
| INVERSE_PERPETUAL | 通过，资金差额0 | 444 | `2822d903627fc94d`，一致 |
| LINEAR_DELIVERY | 通过，资金差额0 | 767 | `a33fe76c23736f32`，一致 |
| INVERSE_DELIVERY | 通过，资金差额0 | 770 | `d0d97abb4a9f226e`，一致 |
| OPTION | 通过，资金差额0 | 1988 | `826547316ccb9ad2`，一致 |

六线覆盖真实做市、下撤单、市价成交、终态/余额、用户执行推送及公共盘口/成交WS与REST身份核对，以及配置热更新、版本/非法输入拒绝和标的状态变化。现货另覆盖FOK与私有历史/成交；衍生品核对风险。两种交割检查到期撤挂单和两条触发单、幂等结算、精确损益流水、持仓/冻结归零。期权OCO回执/终态/时间线，以及CALL/PUT×实值/平值/虚值6行到期、权利金、费用和守恒通过。

两种永续各执行独立Core小额夹具：资金费→风险扫描→强平→保险支付1→实际ADL。保险申报2必须被分配校验拒绝，实际支付1后亏空为21/120，先保存并恢复待ADL状态。接着减仓1步申请覆盖全部亏空，必须按ADL_PROFIT_INSUFFICIENT拒绝，目标账户、财库和源待办保持原值。合法ADL在线性减2步、反向减4步，余仓分别-8/-6、保证金80/6，目标总额198/179、已实现盈亏40/120；保险亏空归零，源不再出现在执行/保险/ADL待办中。重复相同命令返回DUPLICATE且状态哈希不变。总资金分别保持2183/2182，差额0；再保存并冷恢复已清零状态后复验相同。

本轮共12次真实Cluster冷恢复：六线主场景，以及两种永续的强平后、保险后、ADL后各一次。每次实际请求ClusterTool快照，确认service/consensus有效记录各新增1条，停止旧JVM并确认驱动失活后用原日志/目录启动，比较已应用命令数及完整业务哈希一致。没有删除数据重建空状态，也没有把单测当作这些运行成绩。

最终4016份当前classfile均major71；ResolutionInputs类/类型引用、3个删除Work字段和旧冷resolve定义/调用均不存在。实际Core运行包的Policy/ResolutionWork/外层classfile与当前编译结果一致。完整命令、源码/运行包/日志SHA-256及逐产品输出见[机器证据](round63-runtime-20261025.json)。

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
```

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLifecycleStateTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreFinancialLedgerProductionTest,CoreLedgerQueryTest,CoreRiskStateTest,SystemLedgerControlTest,CommittedLiquidationReplayTest,AdlPositionIndexTest,RuntimeCommitRecoveryTest,CoreFundsLedgerIndexTest -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- 未执行浏览器/Flutter点击，本轮未改变HTTP/WebSocket及前端契约。
- 只验证单成员FUSED网络Cluster，未做三节点切主、强杀、保险/ADL在途崩溃。
- 保险支付和实际ADL命令、盈利不足拒绝、重复幂等及恢复已实测；未执行完整定时调度周期、自动止盈止损和额外算法单/统计可选分支。
- 未启动wallet，未运行JMH/JFR/压测；没有性能提升或全功能覆盖结论。

摘要归档后停止所属进程、核对端口和PID并删除本轮临时目录；最终清理状态见机器证据cleanup。本轮六线门禁通过后才开始下一轮分析。
