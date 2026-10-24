# 第六十四轮：ADL只交接实际需要的清算差额

本轮基于 `a66951c3def3f28d0da785ecb11ec851b86cde88`，解决A199–A201三组；累计196项解决、A11候选、4项保留，全量审计继续。

入口仍由Owner核对产品、强平路由和标记价，源账户Lane捕获并校验强平事实，目标Lane减仓与结算，必要时再派发源Lane抵减亏空，最后由原System Ledger记账。Owner等待账户与账本完成后才提交修订，拒绝和回滚仍通过原命令范围处理。

| 编号 | 清理内容 |
| --- | --- |
| A199 | prepare的liquidation参数唯一调用恒为null，改为直接清空、随后由源Lane读取 |
| A200 | Work.targetLane/sourceLane及准备参数重复保存用户和固定拓扑可推导的路由，派发与所有权判断直接使用原数据 |
| A201 | Work.targetDelta通用多资产容器替换为一个必要的long clearingUnits；资产取已有settleAssetId、亏空抵减取命令coveredUnits，删除恒非空reuse参数和无消费者的回调返回值 |

Work净减2字段；删除3个旧字段、增加1个跨线程金额标量。生产1文件新增31行、删除41行，净减10行。没有新增类、接口、容器、阶段或状态镜像。RuntimeTreasuryDelta仍服务其他真实业务，未将该类全部字段算作删除；本轮字段未进入Cluster格式，没有持久化字段或版本改动。

目标Lane先精确计算targetCashDelta，再按原顺序取反目标净现金、取反覆盖额并addExact得到清算对手项；不把期权分支代数改写成直接取反市值，以免改变极值中间溢出行为。期权现金估值、其他合约盈利、比例/舍入、保证金释放和持仓更新未改。账本先计算亏空及清算两项新值，再调用setInsurance/setClearingPnl；setInsurance仍捕获完整资产前值，原财务校验和回滚保留。原先其余财库列差额恒0，不再读写这些列。

ControlLaneDispatcher的poll只收集修订、拒绝及变更，返回值必须显式result读取；ADL没有这种读取，资金一直由Work字段交接。保留账户回调release、Owner acquire及原账本队列的发布顺序。SystemLedgerCommand、TreasuryRuntime、通用差额实现、ControlLaneDispatcher和ExecuteAdlCommand源码与基线一致，所有业务拒绝码及顺序、源强平完成方法和后续公式方法保持一致。

HotSpot JDK27、Maven3.9.16及磁盘核对后，18模块干净构建成功，11类140例相关回归通过，0失败/错误/跳过。原System Ledger强平→保险→ADL用例扩为4种永续/保证金组合×单/双账户Lane，共8例，显式确认源/目标同Lane或跨Lane，逐步比较完整状态并检查工作线程健康。期权ADL及原手工资金/持仓期望保留；冷对照共用生产逻辑，不作为独立公式证明。

随后六条产品线依次启动真实服务并执行HTTP/WebSocket验收，每线1项均0失败/错误/跳过。

| 产品线 | 业务实测 | 主场景恢复命令数 | 主场景恢复哈希 |
| --- | --- | --- | --- |
| SPOT | 通过，资金差额0 | 40 | 2ce815e736537b1f，一致 |
| LINEAR_PERPETUAL | 通过，资金差额0 | 816 | 786ad164e6c03231，一致 |
| INVERSE_PERPETUAL | 通过，资金差额0 | 533 | d830c86e7b244242，一致 |
| LINEAR_DELIVERY | 通过，资金差额0 | 822 | 532539103da64eb7，一致 |
| INVERSE_DELIVERY | 通过，资金差额0 | 810 | 691f87eefe1108f6，一致 |
| OPTION | 通过，资金差额0 | 2048 | beaf5345c0f6386f，一致 |

六线覆盖实际做市、下撤单、市价成交、余额/终态、执行推送及公共盘口/成交WS与REST身份核对，以及配置热更新、版本/非法输入拒绝、标的状态变化。现货另覆盖FOK和私有历史/成交；衍生品检查风险。两种交割验证到期撤挂单/触发单、精确损益、幂等和持仓/冻结归零；期权OCO与CALL/PUT×实值/平值/虚值6行到期、权利金和手续费守恒通过。

两种永续另以独立Core小额场景验证资金费→风险扫描→强平→保险→实际ADL。保险申报2被分配校验拒绝，实际支付1后剩余亏空21/120，保存并恢复待ADL状态。ADL减仓1步覆盖全部亏空被ADL_PROFIT_INSUFFICIENT拒绝，账户、财库及源待办未变；合法线性减2步、反向减4步，余仓-8/-6、保证金80/6、目标总额198/179、已实现盈亏40/120，清算总账-18/1。保险基金及亏空归零，源不再出现在执行/保险/ADL待办；相同命令重复返回DUPLICATE且哈希不变。总资金2183/2182、差额0，并恢复后复验。

共12次真实Cluster快照冷恢复：六条主场景，加两种永续的强平后、保险后、ADL后各一次。每次确认service/consensus有效快照记录各新增1，停止原JVM并确认驱动失活，用原日志与目录启动，完整业务哈希和已应用命令数一致。

当前4016份classfile全部major71；旧3字段、2个私有方法描述符及其调用消失，ADL不再引用通用差额类型，实际Core运行包classfile与当前编译结果一致。完整命令、逐步输出、资金/恢复结果、源码及包/日志SHA-256见[机器证据](round64-runtime-20261025.json)。

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
- 只验证单成员FUSED网络Cluster，未做三节点切主、强杀或保险/ADL在途崩溃；同/跨Lane由实际账户和账本线程回归补验。
- 保险和实际ADL、盈利不足拒绝、重复幂等及恢复已实测；未执行完整定时调度周期、自动止盈止损或额外算法单/统计可选分支。
- 未启动wallet，未运行JMH/JFR/压测；没有性能提升或全功能覆盖结论。测试冷对照共用生产计算，金额和状态硬断言继续保留。

摘要归档后停止本轮所属进程、核对端口与PID并删除临时目录。首次清理因macOS的/tmp与/private/tmp别名比较停止，改为真实路径核对后完成；11个端口均关闭，原用户暂存删除保持不变，逐次记录见机器证据cleanup。本轮六线门禁通过后才开始下一轮。
