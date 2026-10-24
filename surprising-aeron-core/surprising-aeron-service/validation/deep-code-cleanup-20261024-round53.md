# 第五十三轮：ADL及强平补亏的多余字段和入口

基于 `81865df06781513211066cb925b71da47b7ebb54`，解决A170，并新增、解决A171–A173。累计168项解决，A11仍为候选，4项保留。全量字段和约束审计未结束。上一轮有实际清理、编译和推送，归类为进展。

| 项目 | 删除内容 | 保留的业务职责 |
| --- | --- | --- |
| A170 | ADL索引的单参positions(asset)无限制转发 | 真实positions(asset,maxPositions)、候选排序、规模拒绝及所属线程校验；14处测试显式传上限，完整参照计算仍使用Integer.MAX_VALUE，原1/2边界断言保留 |
| A171 | AdlWork.sourceUserId、两次字段写入及prepare中的重复Lane计算 | 两条分支原有的sourceLane赋值均在派发前完成；目标/源Lane、强平正文捕获、资金缓冲和异步完成进度继续使用 |
| A172 | 仅测试使用的ADL三参begin转发 | 生产复用入口；两处测试直接传null创建工作，轮询及财务对照断言不变 |
| A173 | 仅测试使用的补亏四参begin转发，以及同步路径不可达的if(asynchronous) | 实际复用入口、索赔读取、保险分配、账本及源Lane更新；一处测试直接传null，异步分支此前已经返回 |

本轮改3份生产源码、6份测试源码，没有增加替代抽象或状态副本。删除的sourceUserId属于命令续步，并非Cluster日志或快照字段；本轮命令、快照和HTTP布局均未改变，不能把这次删除表述为减少持久化字段。

ADL命令中的目标用户、合约、保证金模式和持仓腿用于定位并核对持仓；预期数量/入场价阻止过期目标，价格序号固定估值输入，减仓量决定平仓及保证金释放，覆盖额受实际亏空和盈利容量约束。强平补亏仍按同资产索赔确定顺序校验期望覆盖额，不能删除为无用约束。ADL的账户回调、轮询、账本和资金计算正文，以及完整ResolutionWork正文与清理前逐字一致。

继续检查的两个容易误删点有实际用途：补亏ResolutionWork.sourceUserId由currentLaneOwns读取，定位源强平正文；批量强平beginExecutionBatch及ExecutionRequest被OrderedCommitCoordinator的生产回调使用。ExecutionCheck捕获的强平记录也被CoreMatchingFlow和有序提交读取。因此没有按字段重名或CodeGraph的空动态调用结果删除它们；CLI动态调用覆盖不足处由完整所属类/方法/描述符的classfile引用补充。

验证命令：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests clean test-compile
```

执行前确认HotSpot JDK27、Maven3.9.16及225Gi可用磁盘。最终5个reactor模块全部SUCCESS，退出0，用时21.242秒；日志SHA-256为 `a9358fa1de414b59d37c7aa4b51f702d37afe34800de5a2dfbe2272eea1def1d`。此前A170及ADL清理后的两次编译也均退出0，最终结果覆盖本轮全部9份Java修改。

9份源码哈希与最终编译输入一致。静态检查现有4016份classfile，受影响模块由本次干净编译生成，删除的3个方法和1个字段均无定义或引用，classfile均为major71。ADL索引除删除转发块外逐字不变；ADL执行器仅删除预定内容并同步说明，补亏执行器仅删除转发块和不可达判断。原命令入口、风险查询、强平执行器、账户回滚、风险扫描、有序提交、恢复投影和快照编解码源码均未改变。

只执行相关模块生产及测试源码编译和静态核对；未运行测试、SQL、服务、Aeron/Kafka、交易、恢复、UI或性能工具，不声明全功能、资金恢复或性能通过。继续追踪单笔强平的Owner派发Lane与账户步骤结果Lane保存是否重复，需先确认两种执行路径及作用域语义。临时证据摘要和哈希归档于[审计记录](command-data-audit-20261023.json)的fifty_third_cleanup_round后清理本轮临时文件，编译产物保留。
