# 第五十二轮：持仓用户与未平仓量读取接口清理

基于 `264bb33c5b2a169c41734fe47fe1d3f623f680c9`，解决A168，并沿相邻索引新增、解决A169。累计164项已解决；A170新确认，A11候选及4项保留，全量审计继续。

| 项目 | 删除 | 保留及测试迁移 |
| --- | --- | --- |
| A168 | users集合复制、higherUser的Long/null包装及两个import | 10处集合断言使用实际usersAfter，2处后继断言使用higherUserId/0标记；所属Ledger线程、成员变化和回滚断言保留 |
| A169 | longQuantity/shortQuantity四个String读取方法；普通版仅测试调用，Normalized版零调用 | 实际查询Totals、OI规模、MutableTotals、多空公式及守恒相关精确运算保留；测试核对查询中的多空数量、零行过滤及回滚后的15/25 |

共删除6个接口及对应无业务调用的复制、装箱、校验分支，没有删除生产状态字段或新增替代框架。两个索引除对应方法块/import及旧入口参数说明外逐字保留。实际CoreQueryService.LedgerQuery使用Totals及其两项数量，这份查询数据有真实用途；资金费/到期结算使用usersAfter，ADL查询使用带显式上限的positions，这些入口继续保留。

A168先完成5模块干净编译（21.463秒，退出0）；继续清理A169并同步旧入口参数说明后，对最终5份变更Java重新执行：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests clean test-compile
```

HotSpot JDK27、Maven3.9.16，5个reactor模块全部SUCCESS，退出0，用时22.132秒。日志SHA-256 `ead82949e550c9172cf08a5a98fd5acc40ed668fa198078eb1436f0d7b904186`。5份源码哈希匹配最终输入；检查4016份当前classfile，删除接口无定义或引用，均为major71。这里只编译测试源码，没有执行场景。

Core查询及风险账户查询、ADL索引、FactIndexes、恢复投影、风险扫描、有序提交、账户回滚、资金身份保留和快照编码源码均未改变。实际多空静态公式、精确加减/取反、非负约束及索引所有者检查保留，命令、HTTP和快照布局不变。

继续确认A170：ADL positions(asset)只把Integer.MAX_VALUE转发给双参入口，5个调用类均属于测试。生产RiskAccountQuery使用显式MAX_INDEX_SCAN。下一步可去掉该默认无限制重载，测试按场景提供限额，继续保留候选排序、规模拒绝、用户/合约/持仓腿和资金断言；A170本轮未修改。

仅执行编译与静态核对；未运行测试、SQL、服务、Aeron/Kafka、交易、恢复、UI或性能工具，不据删除未调用方法声明运行性能提升或全功能通过。详见[审计记录](command-data-audit-20261023.json)的fifty_second_cleanup_round。
