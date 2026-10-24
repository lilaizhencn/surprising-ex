# 第六十五轮：强平直接读取原命令并交接两项金额

本轮基于 `99ebb9c32ddc41e5a3a8bef772be382a2f166e08`，解决A202–A205四组；累计200项解决、A11候选、4项保留，全量审计继续。

Owner保留解码后的不可变单笔/批量命令，按原路由派发资格检查；源账户Lane逐项核对计划、游标、价格、持仓和风险，Owner收齐结果后才允许Matcher撤单。账户步骤先整组校验撤单回执，再解冻、平仓并保存实际资金结果。原System Ledger按原请求顺序消费两项金额，Owner等账本完成后提交修订。

| 编号 | 清理内容 |
| --- | --- |
| A202 | ExecutionCheck不再复制liquidationIds、priceSequences、prices、cursors四组long数组；直接保留互斥的原单笔/批命令引用，清理时一起释放 |
| A203 | 撤单回执先按原顺序整组校验，再直接迭代原稳定集合；删除临时orderIds数组，校验仍发生在后续合约/持仓读取和任何撤单写入前 |
| A204 | 单笔结果别名、laneId缓存、批量results并行数组和强平通用多资产差额对象删除；已有账户步骤保存两项真实金额，直接交原账本 |
| A205 | canceledCount只被判断是否为零，改为实际撤单布尔事实；失效计划仍不撤单，原修订增量1/2保持一致 |

本类及嵌套类型实例字段39→37，净减2字段；删除8个旧字段、新增6个实际输入/结果字段。四组输入数组、批量并行资金数组、每笔撤单编号数组及强平专用的通用差额对象均不再分配。生产1文件新增187行、删除190行，净减3行。没有新增生产类、接口、容器或执行阶段；通用差额类型仍服务其他业务，没有把其全类字段算作删除或给出性能结论。

原命令是不可变record，批动作列表在协议构造时List.copyOf；CommandSlot持有命令直到结束，检查仅保留引用并在clearReferences释放。新增checkAction只共用单笔与批量相同的资格校验正文，经输入读取替换和continue/return归一化后与原校验一致。Lane路由数组、有效前缀、结果字节和源强平回执仍承担真实线程交接。

强平金额只有保险变化和未覆盖亏空两项独立值，亏空同时记入保险亏空和清算对手项，资产直接取步骤已有assetId。保留liquidated事实以区分实际平仓与仅撤单/失效计划，零金额平仓也继续登记账户变化。计算方法移到原账户步骤，去掉重复资产/持仓参数；资产别名替换后，PnL/期权市值、费用、现金、余额/持仓/强平写入正文与原实现一致，applyCash和后续公式/守卫原样保留。

账本依次用addExact预先计算保险、亏空、清算三个新值，再调用setInsurance/setClearingPnl。原差额其余列恒0，不再搬运或写回；setInsurance仍捕获完整资产前值，原资金校验及范围回滚未变。批量仍逐原请求顺序应用，没有合并跨资产或改变溢出与回滚边界。SystemLedgerCommand、TreasuryRuntime、ControlLaneDispatcher、CommandSlot、撮合/提交编排及三种强平协议源码与基线一致。

HotSpot JDK27、Maven3.9.16和磁盘核对后，最终18模块干净构建成功；12类146个不同回归通过，另PIPELINED补跑6例全部通过，共152次当前回归执行，0失败/错误/跳过。新增用例在修改生产代码前已通过6例基线：扩容为6个动作、混合有效/旧价/无路由，再清理复用为不同单笔输入；同批两账户撤4单并平仓，硬核对保险220、亏空1760、清算1760、用户余额/持仓归零、守恒和账户归属。

首次PIPELINED补跑出现1项新增测试启动错误：账户Lane已经启动，测试再次调用start。其余5项通过；修改为只在尚未启动时启动，并断言已启动。随后完成两项金额清理，重新干净构建并执行最终146例及PIPELINED 6例，均通过。中间通过记录和首次失败单独归档，不替代最终结果。

六线随后依次实际启动HTTP/WebSocket服务，每线1项均0失败/错误/跳过，上一线完成并停止后才启动下一线。

| 产品线 | 业务实测 | 主场景恢复命令数 | 主场景恢复哈希 |
| --- | --- | --- | --- |
| SPOT | 通过，资金差额0 | 40 | fd14232ab35db81a，一致 |
| LINEAR_PERPETUAL | 通过，资金差额0 | 708 | 1f4d0373c02e2540，一致 |
| INVERSE_PERPETUAL | 通过，资金差额0 | 446 | b1e727b5ca7a6f20，一致 |
| LINEAR_DELIVERY | 通过，资金差额0 | 783 | 8d07b94362c1c24c，一致 |
| INVERSE_DELIVERY | 通过，资金差额0 | 770 | 5a88f248b48aebf8，一致 |
| OPTION | 通过，资金差额0 | 1980 | 78e410e9f7808431，一致 |

六线覆盖真实做市、下撤单、市价成交、余额/终态、执行推送、公共盘口/成交WS与REST身份核对，以及配置热更新、版本/非法输入拒绝和标的状态变化。现货另覆盖FOK与私有历史/成交；衍生品核对风险。两种交割核对到期撤挂单和触发单、精确损益、幂等、持仓/冻结归零；期权覆盖OCO与CALL/PUT×实值/平值/虚值6行到期、权利金、费用及守恒。

两种永续另以独立Core小额场景验证资金费、风险扫描、强平、保险和实际ADL。保险申报2拒绝，实际支付1后剩余亏空21/120并恢复待ADL状态。ADL减仓1步覆盖全部亏空按盈利不足拒绝，账户、财库和源待办不变；合法线性减2步、反向减4步，余仓-8/-6、保证金80/6、目标总额198/179、已实现盈亏40/120、清算-18/1。保险基金及亏空归零，源从执行/保险/ADL待办消失；重复同一命令返回DUPLICATE且哈希不变。总资金2183/2182、差额0，恢复后复验相同。

共12次真实Cluster冷恢复：六条主场景及两种永续的强平后、保险后、ADL后各一次。每次实际请求快照，service/consensus有效记录各增加1，停止旧JVM并确认驱动失活后使用原日志/目录启动，已应用命令数及完整业务哈希一致。

最终4016份classfile均major71。完整所属类/方法/描述符核对确认旧字段、旧私有签名和调用消失；强平不再引用通用差额类型，不再分配long数组或并行资金数组；真实Core包classfile与当前编译结果一致。完整命令、首次失败及复验、资金/恢复输出、源码和包/日志SHA-256见[机器证据](round65-runtime-20261025.json)。

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
```

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLiquidationLaneCheckTest,CoreLifecycleStateTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreFinancialLedgerProductionTest,CoreLedgerQueryTest,CoreRiskStateTest,SystemLedgerControlTest,CommittedLiquidationReplayTest,AdlPositionIndexTest,RuntimeCommitRecoveryTest,CoreFundsLedgerIndexTest -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLiquidationLaneCheckTest -Dsurprising.aeron.execution-mode=PIPELINED -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- 未执行浏览器/Flutter点击；本轮没有HTTP/WebSocket及前端契约变化。
- 只验证单成员FUSED网络Cluster；PIPELINED另验证6个本地真实账户线程强平用例。未执行三节点切主、强杀或在途崩溃。
- 实际保险/ADL、盈利不足拒绝、重复幂等及恢复已实测；未跑完整定时调度周期、自动止盈止损或额外算法单/统计可选分支。
- 未启动wallet，未运行JMH/JFR/压测，没有性能提升或全功能覆盖结论。部分金融冷对照共用生产逻辑，手工金额断言保留。

摘要归档后停止本轮进程、核对PID/端口并删除临时目录，最终清理状态见机器证据cleanup。本轮六线门禁通过后才开始下一轮。
