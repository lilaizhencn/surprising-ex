# 第六十七轮：批量强平直接读取原动作，保险共用真实入口

本轮基于 e5189ff648d631df46b16a9ed3dd16bf8dc32354，解决A211–A213三组；累计208项解决、A11候选、4项保留，全量字段与约束审计继续。

批量强平仍按解码校验→所属Lane前置检查→Matcher撤单→按预算选动作/撤单页→所属账户Lane应用→原账本依次记账→Owner发布修订及可选风险续扫的顺序推进。保险仍由Owner解析资产和预检修订号，账本检查余额并调整，完成后Owner发布预先准备的修订。实际线程交接、资金前值及回滚范围未改。

| 编号 | 清理内容与实际用途核对 |
| --- | --- |
| A211 | RuntimeInsuranceFundStateTransitions.adjust没有线上调用，当前classfile全描述符证明仅3个测试调用方；删除这个生产同步入口及只为它设置的空值/账本激活约束，冷测试共用prepare及原续步 |
| A212 | 批强平不再为每个原动作重建ExecuteLiquidationCommand；ExecutionRequest直接引用原ExecuteLiquidationBatchAction，共用费率由批命令交接一次，账户步骤只接收实际使用的值 |
| A213 | executionRequests在一次Owner轮询内完成构造并交Lane回调持有，没有跨轮询增量生产者；删除续步字段和派发后空列表重置，改为调用内局部列表；等待风险续扫时复用共享空集合 |

生产3文件新增45行、删除58行，净减13行。所核对三个实现及其嵌套/匿名类的非static、非synthetic声明实例字段58→57，仅净减1字段；ExecutionRequest.command→action是必要引用改型，不计为字段删除。没有新增生产类、接口或阶段。临时运行门禁类只放在本轮临时目录，不进入产品包。

原批动作已校验正强平编号、正触发序号、正执行价和非负撤单游标，批命令已校验费率0..1000000、撤单预算1..1024，Owner仍只在剩余预算非零时选择动作。原来重建单笔命令的全部构造约束在该路径均已满足；额外生成的maxOrders又从未被账户阶段读取。单笔协议的maxOrders仍控制其真实Matcher选页，协议字段和校验保持原样；本轮仅移除批路径后段的重复构造和复检。

共同账户步骤用同名局部输入读取原动作或原单笔命令，未把它们保存为新字段。静态方法体对照确认：替换输入别名后execute、executeAccountLiquidation、validatePrice完全相同；撤单归属及可撤性拒绝、过期行情、失效计划、游标推进的判断和顺序保持。原全仓/逐仓现金计算、期权/期货公式、整数加减乘算、费用封顶、保险变化与亏空、用户修订和账本写入顺序均未改。

ExecutionCheck、BatchFinancialWork、现金公式和可执行性判断正文相同。单笔ExecutionWork仅把原不可变命令的同一组值直接传入；回收、复用、账户/账本完成位和修订增量保持。OrderedCommitCoordinator除局部列表、原动作及共用费率替换外完整token对照一致，真实撤单预算、失效动作统计、订单变化标记、控制挂起、账本范围及风险续扫顺序不变。

保险prepare和AdjustmentWork全文保持，包括nextRevision的提前溢出检查和最终发布；它有真实用途，没有当成闲置缓存删掉。冷测试改走原续步：原dispatchLedgerCommand在账本未激活时使用applyBeforeActivation，仍由原Owner同步执行同一applyLedger，不新增生产兼容入口。拒绝和异常场景继续由现有账本测试覆盖。相同底层实现的冷对照不作为独立金额公式证明。

HotSpot JDK27、Maven3.9.16及磁盘核对后，18模块干净构建通过。12类158个不同回归、PIPELINED配置下6类99例通过，共257次当前回归执行，0失败/错误/跳过；修改前先通过86例结算/强平/账本基线。新增8例覆盖两种永续×全仓/逐仓×单/双Lane，每批两个源账户，明确核对同Lane或跨Lane、批费率100000、全仓实际费用80/69和逐仓实际费用0、对应保险余额、完整冷状态与重复完成轮询；原账本资金核对及Owner不可读保险断言均通过。

新增测试首次编译遗漏Comparator导入，补齐后完整回归通过；失败编译日志保留。六线初次在SPOT服务启动前的隔离UPDATE发现本轮新建空库尚无gateway_product_lines；执行仓库init.sql成功后恢复同一验证记录，从现货开始依次实测，未改产品代码或已有交易数据。两次准备失败均如实归档。

中间版本已验证部分场景后，复核发现列表局部化会在风险续扫等待轮询反复创建空列表。确认当前子进程为空、产品运行栈已停后，在恢复等待点主动终止中间runner，保留原日志与数据；修正为动作已选完时共用List.of，不增加风险等待分配。随后重新完成18模块干净构建、158项及PIPELINED99项回归，以新RUN_ID、Core目录及重新初始化的独立数据库/Kafka/Redis从现货重跑完整六线；仅以下最终运行包和结果计入本轮验收，中间记录另存且不混用。

最终线性永续主场景冷恢复首次启动被中间gate留下的Core专用锁拒绝，尚未启动Core。确认无产品PID/label且HTTP端口已关闭后，以原中间RUN_ID调用原CORE_ONLY停止入口释放该锁；保留最终检查点的命令数/哈希及快照计数，从原数据目录继续恢复，未重置最终交易数据。该次启动拒绝、锁所有权和后续复验一并记录。

| 产品线 | 最终HTTP/WebSocket实测 | 主场景恢复命令数 | 主场景恢复哈希 |
| --- | --- | --- | --- |
| SPOT | 通过，资金差额0 | 41 | 693d53e69225ba56，一致 |
| LINEAR_PERPETUAL | 通过，资金差额0 | 651 | b1a8f1929f38567b，一致 |
| INVERSE_PERPETUAL | 通过，资金差额0 | 395 | 6156304c000de9d2，一致 |
| LINEAR_DELIVERY | 通过，资金差额0 | 783 | c25d43c4b2475076，一致 |
| INVERSE_DELIVERY | 通过，资金差额0 | 773 | 26325805d66f8681，一致 |
| OPTION | 通过，资金差额0 | 2005 | 4ac1f38035141d11，一致 |

六线覆盖实际做市、下撤单、市价成交、余额/终态、执行推送、公共盘口/成交WS与REST身份核对、配置热更新、非法输入/版本拒绝和标的状态变化。现货另核对FOK及私有历史/成交；衍生品核对风险。两种交割验证到期撤挂单及触发单、精确损益、幂等、持仓/冻结归零。期权覆盖OCO与CALL/PUT×实值/平值/虚值6行到期，权利金、费用与资金守恒通过，零行权同样完成持仓/冻结归零。

两种永续的小额真实Cluster门禁本轮改发EXECUTE_LIQUIDATION_BATCH：使用查询到的原动作，1动作批命令、共用费率100000，经真实协议入口、Matcher与账户/账本完成后资金守恒。门禁临时源码从当前ClusterProductLineGateMain复制，只改类名/输出和这一条单笔→批命令，原开仓、资金费、风险续扫、经济资金及恢复核对保持；源码和适配说明一并归档。网络门禁为1动作批量；同/跨Lane的2动作与实际非零收费由上述8项线程回归覆盖，未混称为网络多动作测试。

强平后继续实际保险与ADL。保险申报2被拒绝，支付1后剩余亏空21/120并恢复待ADL状态。ADL减仓1步覆盖全部亏空按盈利不足拒绝，账户/财库/源待办不变；合法线性减2步、反向减4步后余仓-8/-6、保证金80/6、目标总额198/179、已实现盈亏40/120、清算-18/1。保险基金及亏空归零，源从执行/保险/ADL待办消失；相同命令DUPLICATE且哈希稳定，总资金2183/2182、差额0，冷恢复后复验相同。

共12次实际Cluster冷恢复：六条主场景，加两种永续强平后、保险后及ADL后各一次。逐次实际请求快照，service/consensus有效记录各新增1；停止原JVM、等待驱动失活后从原日志/目录启动，已应用命令数及完整业务哈希一致。完成并停止上一产品线后才启动下一线。

当前4017份classfile均major71；按完整所属类/名称/描述符确认8个旧签名及调用消失，其中只有adjust为整方法删除，其余是改型及编译器lambda签名，不虚报为8个无用方法。批续步和账户步骤已无单笔协议命令引用；运行Core包相关classfile与当前编译结果一致。全部其他生产Java源码哈希与基线相同，命令、快照、HTTP/WebSocket与前端契约未改。删除的是临时字段/搬运，不声称删除Cluster持久化字段。

完整逐产品命令、退出码、金额、恢复哈希、源码/运行包/日志SHA-256见[机器证据](round67-runtime-20261025.json)。

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
```

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLiquidationLaneCheckTest,CoreLifecycleStateTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreFinancialLedgerProductionTest,CoreLedgerQueryTest,CoreRiskStateTest,SystemLedgerControlTest,CommittedLiquidationReplayTest,AdlPositionIndexTest,RuntimeCommitRecoveryTest,CoreFundsLedgerIndexTest -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLiquidationLaneCheckTest,CoreFinancialLedgerProductionTest,CorePerpetualFinancialMatrixTest,CoreLifecycleStateTest,CoreDeliveryOptionFinancialMatrixTest,SystemLedgerControlTest -Dsurprising.aeron.execution-mode=PIPELINED -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- 未执行浏览器/Flutter点击；本轮没有HTTP/WebSocket或前端契约变化。
- 真实Cluster验证单成员FUSED；PIPELINED另跑99项本地回归，包含公式、冷对照及实际账户/账本线程路径，未验证多节点切主、强杀或在途崩溃。
- 本轮两种永续的真实网络强平改用1动作批命令；同/跨Lane的2动作批量和非零实收费由8项原线程回归覆盖，没有将其说成网络多动作测试。
- 实际保险/ADL、盈利不足拒绝、重复幂等及恢复已实测；未跑完整定时周期、自动止盈止损或额外算法单/统计可选分支。
- 未启动wallet，未运行JMH/JFR/压测，不宣称性能提升或全功能覆盖。冷对照部分共享生产实现，仍保留独立手工金额断言。

摘要归档后停止本轮进程，核对PID/端口并删除临时目录；最终清理状态见机器证据cleanup。本轮门禁完成后才进入下一轮。
