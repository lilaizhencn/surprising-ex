# 第六十九轮：财务续步只保存一个账户阶段

本轮基于759f90965cd2fbfe02de8e7aebef98baa78d45e7，解决A222–A224三组，累计219解决、A11候选、4保留。全量命令、字段、约束和持久化审计继续。

| 编号 | 清理及实际边界 |
| --- | --- |
| A222 | AdlWork的6个阶段标记收敛为一个7值phase，净减5字段；源校验、目标减仓、同Lane合并／跨Lane源补亏、账户发布、账本投递与完成的先后顺序保留。 |
| A223 | FundingWork的3个顺序标记改为一个4值phase，净减2字段；usersReady/usersQueued及不可变结果继续承担各自交接职责。 |
| A224 | 删除dispatchedPhase，现有phase在派发前切到CANCEL_WAIT/PREPARE_WAIT/APPLY_WAIT，Lane读取同一等待阶段；内联和工作线程均使用同一顺序，净减1字段。 |

三个工作对象的非static、非synthetic声明实例字段58→50：ADL14→9、资金费18→16、到期结算26→25。删除10个阶段字段，ADL与资金费各改用一个内部phase字段，净减8。生产3文件新增65行、删除81行，净减16行；新增的2个私有枚举直接表达原有阶段，没有新增服务抽象、接口、队列或业务阶段。

ADL仍依次完成源校验→目标减仓→必要的跨Lane源补亏→账户变更收集→账本投递→账本完成→Owner修订发布。同Lane源补亏仍在目标回调内完成。资金费仍先由账本选页并发布usersReady，再派发账户、收齐回执、尝试账本入队、等待完成并发布结果。新阶段值与旧合法标记组合逐项映射，满队列停在SUBMIT_LEDGER，已接纳后停在LEDGER_WAIT，重复轮询不会重做账户或账本操作。

到期结算的等待阶段在dispatchControlLanes前确定，内联回调立即看见正确阶段，工作线程由原队列交接看见同一阶段。Owner只有pollControlLanes收齐原release/acquire完成位后才推进，因此旧dispatchedPhase不再承担独立状态。没有增加用于代替该字段的回调对象。账本的FinancialPhase继续区分订单进度、保险预算和最终清算，入队标记、用户选页完成位及财务作用域关闭仍有实际职责，继续保留。

命令入口校验、账户金额、ADL源补亏、资金费收付与剩余差额、到期保险和清算、最终结果构造共12段方法正文（包含资金费applyLane两个重载）对照一致。整数运算、溢出预检、资金/冻结、手续费、持仓、终态、拒绝、修订和回滚保持原边界。本轮8个字段属于内存续步，Cluster日志、命令、快照、HTTP/WebSocket及前端契约保持当前布局；没有把临时控制字段计成持久化数据删除。

HotSpot JDK27、Maven3.9.16及磁盘检查后，18模块干净构建通过。10类154个不同回归通过，PIPELINED下同154例也通过，均0失败/错误/跳过。修改前122例基线、首次122例及中间96例也通过，本轮无须修改旧失败夹具。

新增8例资金费满邮箱/完成复用，扩展原8例ADL真实账本测试：四种正反向永续×全仓/逐仓组合，各跑单Lane和双Lane，明确核对同/跨Lane。实际账本队首未就绪并填满邮箱，等账户回执全部收齐后仍反复轮询3次，结果继续等待、修订不提前，放行后完整资金/持仓结果只应用一次。ADL完成并释放输入后再次轮询仍安全且修订不增。资金费随后复用同一对象处理空用户页，支付明细为空、处理人数0、完成编号702、账户保持前值，先前的支付明细独立不被清空。原10行交割/期权实际账本、保险不足20后补款复用、撤单分页及回滚测试继续通过。冷对照共用生产规则，不作为独立公式证明；保留原手工金额和守恒断言。

| 产品线 | HTTP/WebSocket | 冷恢复次数 | 主场景恢复命令数 | 主场景恢复哈希 |
| --- | --- | --- | --- | --- |
| SPOT | 通过 | 1 | 37 | 53317838d26ce646 |
| LINEAR_PERPETUAL | 通过 | 4 | 720 | 43940e3c09203e0a |
| INVERSE_PERPETUAL | 通过 | 4 | 441 | c7b080739ae935ea |
| LINEAR_DELIVERY | 通过 | 1 | 783 | 254360bac9f1041d |
| INVERSE_DELIVERY | 通过 | 1 | 786 | 65e8cd5aa956c96d |
| OPTION | 通过 | 1 | 2031 | 86648a447f63870a |

六线完成仓库定义的关键做市、下单/撤单/成交、余额/终态、公共及私有推送、输入拒绝和配置检查。两种永续另实测资金费、强平、保险、ADL：超额保险覆盖和盈利不足拒绝不改资金，合法保险与ADL后源待办消失、目标减仓和清算精确，重复请求不再应用。交割验证到期结算和持仓/冻结归零；期权验证OCO、CALL/PUT实平虚值六行到期、权利金/费用与资金守恒。逐线全部停止后才启动下一线，运行脚本分别释放完整运行锁和Core专用锁。

共12次真实Cluster冷恢复：六条主场景，另两种永续在强平后、保险后和ADL后各恢复一次。每次实际请求快照，有效service/consensus记录各增1，停止原JVM并确认驱动失活，从原日志/目录启动，命令数与完整业务哈希一致。驱动失活轮询中的暂时非零返回是等待记录，最终全部通过。

当前4021份classfile均major71，旧10个字段的定义和完整所属类/名称/描述符引用均已消失；运行Core、tools及benchmarks包的相关12份classfile与当前编译结果相同。其他生产Java源码哈希与基线一致。原命令、快照、资金公式、序列化和下游实现未改。

实际命令、退出码、154例身份、逐线金额与恢复值、源码/运行包/日志SHA及门禁源码见[机器证据](round69-runtime-20261025.json)。

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreLifecycleStateTest,CoreRiskStateTest,CoreLiquidationLaneCheckTest,CoreFundsLedgerIndexTest,SystemLedgerControlTest,RuntimeCommitRecoveryTest,CommittedLiquidationReplayTest,CoreFinancialLedgerProductionTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreLifecycleStateTest,CoreRiskStateTest,CoreLiquidationLaneCheckTest,CoreFundsLedgerIndexTest,SystemLedgerControlTest,RuntimeCommitRecoveryTest,CommittedLiquidationReplayTest,CoreFinancialLedgerProductionTest -Dsurprising.aeron.execution-mode=PIPELINED -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

真实Cluster为单成员FUSED，PIPELINED另跑上述154例；未测多节点切主、强杀或在途崩溃、长周期定时及所有可选功能。未运行浏览器/Flutter点击，公开接口无变化；未启动wallet，未做JMH/JFR/压测，不宣称性能提升。摘要归档后关闭并清理本轮临时资源，实际PID、端口和删除状态见机器证据cleanup。
