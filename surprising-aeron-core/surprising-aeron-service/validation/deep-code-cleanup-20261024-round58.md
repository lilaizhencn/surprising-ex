# 第五十八轮：删除无调用入口并迁出测试成交编排

基于 `0025a39576de00dc2f0c14975e86112c7ee365f5`，完成第五十七轮的 **A182–A188 七组**。累计182项已解决，A181仍确认待处理，A11候选及4项保留；全量业务字段和约束审计未结束。

| 编号 | 本轮结果 | 保留的实际职责 |
| --- | --- | --- |
| A182 | 删除2个旧响应物化重载及结果账本size() getter | 命令槽物化、size计数、幂等及条数/字节限制 |
| A183 | 删除账户运行时12个旧入口及2个仅供其使用的下游方法 | Lane写入/发布、原语索引、真实回滚前值及权威引用恢复 |
| A184 | 删除5个Core、触发和到期编排旧入口 | 异步快照生命周期、SnapshotNotReadyException和实际命令续步 |
| A185 | 按完整签名删除3个字符串持仓身份旧重载 | 真实持仓身份入口、确定性键、反向核对和Lane校验 |
| A186 | 现货5个仅服务测试的方法迁入RuntimeSpotMatchFixture | applyLane、单笔/累加结算、费用及终态解冻 |
| A187 | 衍生品6个仅服务测试的方法迁入settlement包的RuntimeDerivativeMatchFixture | 实际Lane编排、持仓准备、杠杆选择及RuntimeDerivativeFillCalculator |
| A188 | CoreSnapshotManifest从13组件减为12组件，删除globalFundsHash摘要副本及无用构造检查/搬运 | 原始HeaderManifest资金哈希、落盘字节和恢复配对校验 |

合计25个无调用方法/访问器删除、11个测试专用方法退出生产源码、1个公开检查摘要副本字段删除。A188不属于Cluster持久化字段删除；其对应的真实落盘哈希继续参与资金恢复核对。本轮Core schema16、交易状态快照v55、分段快照v30及Matcher快照v10均未改变，不新增旧格式读取入口。

在线成交仍从Matcher事实进入所属账户Lane，只处理本Lane的taker/maker、余额、订单、持仓和预留；系统差额交现有账本，Owner按原顺序完成命令。移走的测试参考编排依旧核对输入、按原顺序推进测试运行时。现货夹具复用现有单笔计算及终态释放，两个既有生产方法由private改为包访问，正文不变，没有增加生产入口；衍生品夹具按原杠杆选择调用既有包内计算器。11个迁移方法中8个方法体逐字相同，另3个仅调整对既有计算/释放方法的调用。

CoreDeliveryOptionFinancialMatrixTest和RuntimeDerivativeMatchProcessorTest只调整导入及夹具调用，硬编码金额、手续费、持仓和冻结断言未变。参考编排仍共享生产资金计算，不能把两份投影结果一致当成独立公式证明；本轮保留并执行原有金额断言。

17个相关模块先完成干净编译及测试源码编译，退出0，用时84.997秒：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-gateway,surprising-realtime/surprising-realtime-provider -am -DskipTests clean test-compile
```

随后运行22个相关测试类：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreSpotAssetUnitsTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreFinancialLedgerProductionTest,CoreOptionPremiumFeeFundingTest,MatcherTreasurySettlementTest,SettlementLaneWorkerTest,SettlementChangesReuseTest,CoreMatchingStateTest,CoreOrderedOrderBatchTest,LaneReplacementResponseTest,RuntimeCommitRecoveryTest,SectionedCoreSnapshotCodecTest,SharedProductLineSnapshotContractTest,CoreNativeSnapshotProductLineTest,SnapshotFieldEvolutionTest,TradingStateSnapshotCodecTest,OwnerPositionRetirementTest,TradingCoreOwnerTest,ClusterCommandPipelineTest,CoreFundsLedgerIndexTest,RuntimeDerivativeMatchProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

首轮实际执行634例，618通过、11失败、5错误、0跳过，退出1。失败集中在此前未同步的测试夹具：

- RuntimeCommitRecoveryTest反射已删除的Owner索引镜像字段；改为读取同一factIndexes中的八个现有索引，保留恢复与规范重建内容比较。
- 快照故障注入还使用旧头部偏移，会改坏其他段。按当前v30的业务哈希57、投影17、Lane摘要33偏移注入，增加段内边界断言；原错误消息、发布引用、业务/资金哈希及原子拒绝断言保留。
- 追踪止损测试把最高价120放入不再承载此字段的创建输入。先用真实120行情扫描建立水位及激活时间，再登记OCO各腿，最后用序号3的100行情完成触发；额外核对最高价形成前提。OCO跨页、过期、恢复、Lane交接及资金哈希断言继续执行。

修正后复验恢复18例、追踪扫描10例均通过；同时新增执行CoreResultLedgerTest的8个既有用例，其中1例因旧夹具未等待真实异步调账完成而失败，第一次复验命令退出1。该用例改用既有CoreTestCompletion等待调账和查询完成，保留淘汰及RESULT_UNKNOWN_OUTSIDE_RETENTION断言，并额外要求首条调账APPLIED。三处测试修正没有新增生产兼容逻辑或放宽业务拒绝。

最终复验命令如下，36例全部通过、0失败/错误/跳过，退出0，用时79.431秒：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimeCommitRecoveryTest,ClusterCommandPipelineTest#internalTriggerPagesExpireTrailAndRecoverWithoutAccountHandoff+internalTriggerPagesWithoutSnapshot,CoreResultLedgerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

按`classname + testcase name`合并首轮及最终复验：**23个测试类、642个不同用例通过，0个未解决失败/错误，0跳过**。这不是同一条命令一次运行642例的成绩。

| 验证范围 | 不同用例数 |
| --- | ---: |
| 现货、永续、交割、期权金额/费用与成交测试编排 | 82 |
| Lane成交及复用结算 | 17 |
| 命令流水线、Owner、撮合状态、顺序批次及响应 | 461 |
| 提交及快照切点恢复 | 18 |
| 六产品线快照、字段演进、分段配对及损坏拒绝 | 43 |
| 持仓退休及资金幂等索引 | 13 |
| 近期结果保留、指纹、条数与字节限制 | 8 |

执行前均确认Amazon Corretto HotSpot JDK27+33、Maven3.9.16及充足磁盘。编译日志SHA-256为`b7934b622fc1a4e0cfea0d9e88fdc9bc5f542f9f1131aced82bd4182f6b607dc`；首轮测试日志为`a778dfb356d6b00f6eead3524bee571946047a1c3d5ae10878aaba8768d9d9bf`；第一次复验为`6bbd4c5f2a0dfbd506aa215bd006e60f865cebcfd3032fcf70c84d8d20b4c6c6`；最终复验为`a757f5dd5456bb9522c7d9f8f000330de57d39c2fa23335b8dc415c506bba2cd`。

删除依据沿用CodeGraph定位和逐签名消费核对，最终再次检查4017份当前classfile（3116生产、901测试，均为major71）：36个旧方法签名在生产类中消失，所有现有编译产物中没有残留调用引用；两个夹具只在test-classes，旧core包夹具产物不存在。真实结果size计数及内部资金哈希字段/getter仍存在。对删除方法所在源码中可解析的792个保留顶层方法核对，方法体逐字不变；现货内部余额累加状态未变。真实衍生品金额计算器、快照写入器、配对验证器和交易状态快照编解码器全文与基线相同。

本轮没有新增生产字段、接口、状态副本或异步阶段。未重启服务、真实多成员网络Cluster、Kafka/SQL、HTTP/WebSocket或UI；不把此前六产品线运行验收当成本轮成绩。未启动wallet，未运行压测、JMH或JFR，不作性能提升结论。A181资金费续页当前行情约束不属于这七组，仍待后续处理。

完整逐签名核对、21份Java路径的最终源码哈希、命令退出码、各测试XML摘要及初次失败/复验记录见[审计记录](command-data-audit-20261023.json)的`fifty_eighth_cleanup_round`。归档后清理本轮临时脚本、源码副本和完整日志，保留Maven产物；未启动需要额外停止的服务。
