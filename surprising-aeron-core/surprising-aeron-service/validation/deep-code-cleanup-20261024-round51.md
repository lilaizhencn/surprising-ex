# 第五十一轮：风险查询路由与持仓用户索引重建清理

基于 `c1053986aa4bfc9da0da8b0ee38008b766915d71`，清理A166，并沿同一索引构造/恢复链新增、解决A167。累计162项已解决；新确认A168尚待清理，A11候选及4项保留，全量审计继续。

A166删除RiskQueryRoutes的合约列表收集、列表复制和2组件包装。PositionUserIndex.riskQueryUserIds直接返回原有序用户数组，RiskAccountQuery直接保存该数组。原最大扫描规模、空仓记录计数、指定无持仓用户路由、Owner固定市场输入及Ledger/Lane交接保留，未添加新状态、复制或阶段。

A167删除两个构造及重复rebuild中未读取的身份表形参，共3个无用形参。默认state构造直接连接state/topology完整构造，删除旧身份构造转发；重建只保留原state方法。删除前两份重建正文逐字相同，最终保留正文与两份原实现一致。RuntimeStateProjector仍传原恢复拓扑，FactIndexes其他索引仍接收真实身份表。19处构造调用已迁移，包括两份基准；基准测量方法、输入、资源和校验均未改，只移除构造实参。

原索引用例继续检查全局/单用户选择、排序去重、未知用户及包含空仓的扫描上限，删除不再存在的合约列表断言。并行风险、恢复、触发和Ledger发布用例只迁移构造参数；资金、持仓、预算及恢复断言保留。这里只编译测试源码，没有执行这些场景。

A166先执行5模块干净编译，退出0（22.486秒）：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests clean test-compile
```

A167涉及两份基准构造，最终扩大到其相关依赖，执行：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests clean test-compile
```

HotSpot JDK27、Maven3.9.16，14个reactor模块全部SUCCESS，退出0，用时37.511秒。最终日志SHA-256 `013432531935d5a6355a9d0d0cadcbbfed3af72c28223b33bae5473217cef30c`。13份变更Java哈希匹配最终输入，4016份当前classfile均为major71，旧RiskQueryRoutes、旧查询方法及旧身份构造/重建描述符无定义或引用。

静态对照保留索引线程守卫、全局/逐Lane后继、惰性用户遍历、持仓引用计数与重建。RiskAccountQuery除直接接收数组外逐字保留；恢复投影、FactIndexes及两份基准仅删除相应实参。风险估值、扫描、有序提交、账户回滚、资金身份保留和快照编码源码未改。Cluster命令、HTTP模型及快照布局不变，版本继续沿用schema16/交易54/分段30/Matcher10/保留4。

继续确认A168：users(String)只被三个索引/发布测试读取，方法每次合并、复制完整用户集合；higherUser(String,long)只被一个测试读取，把已有原语后继转为Long/null。可删除两项测试包装，测试改用实际usersAfter/higherUserId。usersAfter由资金费和到期结算实际调用，必须保留；测试读取仍应在所属线程当次消费并保留成员变化与回滚断言。A168本轮未修改。

仅执行编译和静态核对；未运行测试、SQL、服务、Aeron/Kafka、交易、恢复、UI、JMH或JFR，不据此声明全功能或性能通过。详见[审计记录](command-data-audit-20261023.json)的fifty_first_cleanup_round。
