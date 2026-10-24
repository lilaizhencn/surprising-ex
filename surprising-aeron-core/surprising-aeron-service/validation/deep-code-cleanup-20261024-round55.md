# 第五十五轮：资金费及到期用户页、结果清理

基于 `90ddbc0d78e9d9062f335982834835eba95ef2d6`，解决A177–A179，累计174项解决；继续确认A180，A11候选及4项保留，全量审计未结束。上一轮实际删除9个续步字段、编译并推送，归类为进展。

| 项目 | 清理内容 | 保留用途 |
| --- | --- | --- |
| A177 | FundingWork.selectedUserIds只是userPage.userIds的别名，删除字段及重置/赋值 | 选页时用局部变量，计数直接读原用户页；不重新遍历用户索引 |
| A178 | FundingResult删除state组件及两处原运行时引用自校验 | 结果只含payments和progress，仍复制明细；执行始终修改原输入运行时，测试直接核对及续用该实例 |
| A179 | SettlementWork的selectedUserIds和usersComplete合为原UserIndexPage引用 | 页面沿原usersReady、Lane和账本完成屏障交接；直接使用已计算的续页游标，不创建替代页或列表副本 |

删除4个旧字段，以一个已有用户页引用替换其中两个，实例字段净减3个。FundingResult由3组件变为2组件；删除两处仅比较带回输入引用的判断。已核对所有精确类型的调用者，生产state()只用于这两处检查，测试调用直接改读原运行时；扣款封顶用例显式保留原有独立投影实例。金额、进度、状态对照及同一实例续页断言保留，顺便删除同一用例中不再使用的两个UUID局部变量。

到期用户页的空值有明确阶段：订单还有后页时不选用户，只走ORDER_PROGRESS；已完成和已清理对象处于DONE，selectedUsers仍返回空列表。FINAL只会在moreOrders=false且原选页已完成后读取页面。UserIndexPage.select在有后页时将nextCursor设为本页最后用户，空页则complete=true，因此新读取与原列表末尾、完成标记一致。恢复游标和保险不足时不推进用户页的规则未改。

资金费applyLane的收付、可用余额封顶、应付减免分配和账本反向差额正文逐字不变。到期撤单、保险核对/预扣、账户后值应用、财务范围和阶段守卫保持原样。UserIndexPage选择器、持久化财库模型、哈希、快照编解码及恢复投影本轮未改；命令、快照和HTTP格式不变。实际跨线程发布、避免重复入队的标记及Lane分组均保留。

验证命令：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests clean test-compile
```

HotSpot JDK27、Maven3.9.16，执行前226Gi可用磁盘。最终5个reactor模块全部SUCCESS，退出0，用时22.032秒；日志SHA-256 `859e84b4b2273d374c1b352995f7b7c238f36584d0a38a5bd0a5505255c43384`。5份源码哈希匹配编译输入；检查现有4016份classfile（受影响模块由本次干净编译生成），均为major71。4个旧字段及FundingResult旧构造器/state访问器无残留定义或引用；生产源码仅预定变更及过期说明同步。

继续确认A180：FundingWork.fundingPriceSequence从标记价取得后，在FundingProgressRuntime.priceSequence、CoreTreasuryState.FundingProgress.priceSequence、投影/物化、快照字段7及TradingCoreState哈希中循环搬运。精确类型引用没有收付计算或续页校验消费者；ApplyFundingCommand和CoreFundingProgressView也没有此输入/输出，RuntimeSnapshotBuilder的相关复制属于测试。实际计算只使用固定markPriceTicks。下一批应删除这条无用保存链及其正数约束，同步快照版本、哈希、模型和相关构造/测试；保留固定价格、轮次、费率和游标，以及风险、强平、触发等真实使用的行情价格序号。A180本轮只分析，尚未修改，不能宣称快照已经精简。

本轮只执行相关模块生产/测试源码编译及静态核对；未运行测试、SQL、服务、Aeron/Kafka、交易、恢复、UI或性能工具，不声明全功能、资金恢复或性能通过。证据、下一批字段引用及临时资源哈希见[审计记录](command-data-audit-20261023.json)的fifty_fifth_cleanup_round；归档后清理本轮临时文件，保留编译产物。
