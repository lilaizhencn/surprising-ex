# 第五十轮：撤单、命令槽与财库观察包装清理

基于 `5fa64664df8a84f4f9d3e2682184e7d590141cee`，清理A162–A164，并沿扫描新增、解决A165。累计160项已解决，新增A166待清理；A11候选和4项保留，全量字段/约束审计尚未完成。

| 项目 | 删除内容 | 保留的业务 |
| --- | --- | --- |
| A162 | 内部CoreCancellationResult的resultCode、构造搬运及非空检查，3组件减为2组件 | 订单号、接受标记、整体失败原因、成功撤单前缀、原生事件与公开批撤明细 |
| A163 | CommandSlot.reusableResolvedOrder字段、分配、getter及清空 | 单笔PlaceAdmissionEvent、批次resolvedOrderSlots、真实改单输入和槽位发布/退休 |
| A164 | CommandSlotRing和PendingMatchingRing两级构造中的laneCount及重复范围检查 | 原容量/掩码、真实LaneTopology、预期/完成Lane位和恢复路由 |
| A165 | 财库两个集合复制观察方法、前值物化方法和7组件AssetState | 原语前值数组、七类资金分录、精确运算、亏空反号、进度捕获和回滚 |

共删除1个运行态字段、8个record组件、2个构造形参；未新增生产字段、抽象、线程阶段或状态副本。ResolvedPlaceOrder仍用于CommandSlot的真实方法签名，因此其import保留。Cluster命令、在线权威数据和快照布局均未改变，schema16、交易快照54、分段快照30、Matcher10、保留格式4继续沿用。

测试迁移18处槽池构造和3处人工撤单夹具。实际撤单用例继续检查整体MATCHING_UNKNOWN_ORDER_ID、逐项订单号/接受状态、KNOWN_PREFIX_APPLIED及第三笔订单仍可撤。财库用例去掉观察包装断言，保留全部七类分录与七项金额回滚核对，并核对清理后结算完成编号、下一轮实际100单位差额和再次回滚。这里只编译测试源码，没有执行这些场景。

A162–A164完成后，首次18模块干净编译通过。后续扫描确认财库观察包装只有一个测试类调用，删除后对最终13份变更Java再次执行相同范围：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-benchmarks,surprising-aeron-core/surprising-aeron-tools,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-gateway,surprising-realtime/surprising-realtime-provider -am -DskipTests clean test-compile
```

HotSpot JDK27、Maven3.9.16，18个reactor模块全部SUCCESS，退出码0，用时56.432秒。日志SHA-256：`39e3e0e07a34846769572837ca529a4122a3f2508cd983e1d462d25a3019fa34`。4017份最终classfile均为major71，旧字段/方法/构造描述符及AssetState定义和引用为零，13份源码哈希匹配最终编译输入。

静态对照确认TreasuryRuntime仅删除四个观察块，其余源码逐字保留。LaneTopology、有序提交、结算计划、Lane结果契约、实际准入事件、批次上下文、账户回滚、资金身份保留及两种快照编码源码未改变。它们是保留实现边界的证据，不能替代运行场景验证。

继续扫描确认A166：PositionUserIndex.riskQueryRoutes收集合约TreeSet，再复制到RiskQueryRoutes.instrumentIds，但RiskAccountQuery只读取userIds；合约列表只被测试读取。Owner在prepare时已固定市场输入，Ledger产出的合约列表没有估值或一致性读端。下一步可去掉列表及包装，直接交出原有序用户数组；仍须保留包含空仓的全局/单用户扫描上限、未知用户路由和原线程交接。A166本轮尚未修改。

同时排除误判：JFR失败事件字段通过commit消费；显式填充及继承/VarHandle交接字段不按字节码零读取删除；ReconciledOrder由Map值相等比较承担精确订单对账；OrderRuntimeSource.instrumentId通过协议接口编码。changedAssets仍有现有基准调用，本轮保留，未运行基准或评估性能。

仅执行相关编译与静态核对。未运行测试、SQL、服务、Aeron/Kafka、交易、恢复、UI或性能工具；没有据此声明全部功能、资金恢复或性能提升。完整来源、描述符、哈希和两次编译证据见[审计记录](command-data-audit-20261023.json)的fiftieth_cleanup_round。
