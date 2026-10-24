# 第五十六轮：资金费价格序号保存链清理

基于 `9bdf0e5324d8a99259f26384a02a61564a1ac3ae`，解决A180，累计175项解决。继续确认A181，A11候选及4项保留，全量字段及约束审计未结束。上一轮已清理A177–A179、完成编译及推送并发现真实快照冗余，归类为进展。本轮中断后核对了工作区，15份源码基线均未变化，再继续实施。

| 位置 | 清理结果 |
| --- | --- |
| FundingWork | 删除fundingPriceSequence字段、局部值、构造/reset参数与复制；不再从当前行情或旧进度取得该序号 |
| FundingProgressRuntime | 6组件减为5组件，保留轮次、配置引用、费率、用户游标和固定标记价 |
| CoreTreasuryState.FundingProgress | 5组件减为4组件，只有轮次、费率、用户游标和固定计算价格；序号正数约束删除 |
| 快照、哈希及恢复 | 删除资金费快照字段7、哈希混入、投影/物化的复制；不提供旧字段读取入口 |
| 测试边界 | 测试快照同样5组件减为4组件；所有精确构造调用移除末尾序号，原资金/回滚/所有权断言保持 |

工作对象、两个生产进度模型及测试模型共移除4个字段副本；两个生产模型和测试模型中随字段存在的正数约束一起删除。未给其补造默认值，也未新增替代状态或兼容层。固定标记价仍参与每个持仓实际收付计算，轮次/费率/游标仍用于续页匹配；币对配置对象一致性检查继续保留。资金费Lane收付、可用余额封顶、应付减免和财库反向差额正文逐字不变，其他业务哈希源码仅删除这一行混入。

交易状态快照从v54升至v55，仍在入口拒绝非当前版本。字段编号1、2、4、6保留原含义，删除的7不复用；没有增加旧格式读取分支。命令协议schema16、分段外壳v30、Matcher格式及HTTP契约未改。按SnapshotFields源码的8字节字段头加8字节long推导，资金费进度正文从5项80字节减为4项64字节；这是静态结构推导，没有执行编码或宣称运行性能改善。

新增fundingProgressStoresOnlySettlementRateCursorAndFixedPrice用例，检查v55、仅含四项字段、正文64字节、恢复四项值及业务哈希相等。既有通用版本拒绝用例使用“当前版本减1”，编译后对应拒绝v54。所有新旧用例均只编译、未执行，不能作为已运行恢复或资金正确性证明。

根据Maven模块的服务反向依赖，选择服务、工具、基准、网关和实时投影，再由-am带入依赖：

```text
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks,surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-gateway,surprising-realtime/surprising-realtime-provider -am -DskipTests clean test-compile
```

执行前确认HotSpot JDK27、Maven3.9.16及227Gi可用磁盘。17个reactor模块全部SUCCESS，退出0，用时64.204秒；日志SHA-256 `7da2ccad810b186b607b10a06ab448cb743b450619fb186090b9efc198ba8077`。15份Java源码哈希匹配编译输入，检查4016份现有classfile（受影响模块及依赖本次干净编译生成），均为major71；字段、访问器及三个旧记录构造签名无残留。风险、强平、行情价格序号、回滚、有序提交、命令编解码及分段封套源码保持不变。

继续确认A181：当前资金费prepare在取得已有进度前，无条件读取并要求当前MarkPriceRuntime存在；续页的实际价格却只取previousProgress.markPriceTicks。删除保存序号后，这份当前行情在续页不再有任何计算或校验用途。下一步应仅首轮读取并要求当前标记价，已有进度时使用原固定价格；继续保留首轮缺价、旧结算号、费率/游标冲突及配置一致性拒绝。本轮未调整此约束，下一批还需覆盖首轮缺价和续页仅有固定价格的场景。

仅执行生产/测试源码编译与静态核对；未运行测试、SQL、服务、Aeron/Kafka、交易、恢复、UI或性能工具，不声明全功能、资金恢复或性能通过。证据与临时资源哈希见[审计记录](command-data-audit-20261023.json)的fifty_sixth_cleanup_round；归档后删除本轮临时文件，保留编译产物。
