# 第七十七轮：控制回执按实际输入构造

基于fbc29606ea5f2074fa41f28e498b8a2184bc8371，解决A246–A248，累计243项解决、A11候选、4项保留。完整字段、业务约束及落地数据审计尚未完成。

控制撤单：原命令 → 实际订单簿分片返回新建原生结果 → 按请求顺序聚合成功前缀、状态和事件 → MatcherEvidenceLedger按实际原生分片检查原生序号、按业务分片检查证据序号 → 绑定原UUID/Core序号/分片 → 原Owner有序提交。普通下单、批量、改单、资金和持仓流程保留。

| 项 | 删除的冗余 | 保留的责任 |
| --- | --- | --- |
| A246 | CoreMatchingResult原生序号副本、构造/绑定搬运；12参数构造器中无人消费的7项证据入参及其检查。 | 实际构造改为5项：接受状态、结果码、撤单结果、前缀变化标记、事件。原UUID/Core序号/分片仍由账本绑定；原生与业务分片的严格递增顺序和检查不变。 |
| A247 | Runtime财库暂存对象和getter，仅被衍生品冷测试使用。 | 测试夹具用本次局部RuntimeTreasuryDelta，保留相同金额、费用、系统差额及修订；生产不新增分配、集合或状态副本。 |
| A248 | 无人消费的接口UUID重载/原生对象契约、重复或无调用的事件读取入口、两个UUID getter。 | 真实生产消费的双long UUID核对、结果状态、前缀、事件、Core/Matcher序号和分片继续保留。序号别名读端直接读取同字段coreSequence，测试直接比较原UUID高低位。 |

7份生产源码净减130行，净减2个实例字段：CoreMatchingResult自身12→11、TradingAccountRuntime自身75→74；事件52个实际字段保留，没有新增类、接口、队列、业务阶段或兼容层。21个旧签名已无定义/引用，其中构造和绑定两个签名收窄，不能把它们算成删除整个业务操作。未作性能采样或性能提升声明。

构造12参数入口的完整owner/name/descriptor调用方只有本类2参数构造器和控制撤单聚合，没有测试调用；唯一外部生产调用的7项证据参数全为0、-1或null。真实控制撤单SynchronousMatchingEngine.cancel调用无目标槽的execute，再经MatcherResult.from新建结果；这不是原生命令槽，序号在其使用期限内稳定。删掉的是由该结果再复制的序号；真实shardNativeSequences、shardSequences和快照分片进度没有改动。依赖引擎及原生结果class SHA在三份最终JAR中均与本轮前一致。

CodeGraph CLI结构分析结合4014份完整classfile、方法/字段所属类和描述符；MCP工具本轮未提供。干净构建前后均4014份、major71，删除字段及21旧签名的残留引用为0。三份Core/tools/benchmarks各35份相关class与当前target逐字节相同，共105份；额外核对benchmark中同字段序号别名迁移。结算事件保留的78个方法及TreasurySettlement尾段源码相同，账本整文件只少一个副本实参，Adapter整文件只改常量构造参数，Runtime整文件只去测试缓存；其他生产Java源码哈希不变，包括全部协议、快照和金融公式。

以下相似字段已经核对并保留：admissionOrderId在原准入事件可能提前回收后仍供Lane发布原订单/预留身份；matcherCompletionShard在故障时用于释放实际传输占用，不能用业务证据分片替代；OrderBatchItem.resultCode经OrderBatchPending.resultCode(index)和真实编码接口读取，不能因具体实现没有直接调用而删除。JFR事件字段、VarHandle和继承游标同样不能按直接getfield数量判无用，本轮未动它们。整模块字节码扫描只产生候选，不作全量字段必要性证明。

清理前91例通过；清理后99例通过，没有删减用例。仅迁移3处非空UUID断言、1处池化序号读取，以及冷测试局部财库缓冲；旧控制证据分片测试继续覆盖不同订单簿原生序号大小相反而业务证据递增。HotSpot JDK27/Maven3.9.16及磁盘检查后：18模块clean package通过，核心及依赖1986例0失败/错误、1项Linux CPU亲和性因macOS跳过；34类显式PIPELINED附加418例0失败/错误/跳过。默认全量中部分类自行强制PIPELINED，不能把1986例均称为FUSED。

| 产品线 | HTTP/WebSocket | 连续改单 | 冷恢复 | 主场景恢复前/后业务哈希 |
| --- | --- | --- | --- | --- |
| SPOT | 通过 | 2 | 1 | 9cb671de1d448f7c |
| LINEAR_PERPETUAL | 通过 | 2 | 4 | 6221444ed19a84a3 |
| INVERSE_PERPETUAL | 通过 | 2 | 4 | b051b2c1fbd49abb |
| LINEAR_DELIVERY | 通过 | 2 | 1 | 771677e0d3bb8b83 |
| INVERSE_DELIVERY | 通过 | 2 | 1 | eaec85d903a0457a |
| OPTION | 通过 | 2 | 1 | b4add3c807ef5e84 |

六线顺序独立启停，做市参与真实下单、成交、撤单和推送，资金差额均0。每线两次HTTP改单，核对旧单取消、新身份/价格/数量、数量先增后减及最终预留/冻结归零。两种永续另覆盖资金费、强平、保险及ADL的应用/拒绝/幂等和恢复；两种交割覆盖到期流水、费用、持仓/冻结归零；期权覆盖OCO和CALL/PUT实平虚值六种到期、费用及守恒。12次实际Cluster快照核对service和consensus记录各加1，停止原JVM并确认驱动失活，再从原目录冷启动，命令计数及业务哈希相同。

命令和业务快照格式未改：Core schema17、触发状态v4、交易快照v56、分段快照v30。公共HTTP/WS和前端产品源码未变，本轮未重复浏览器或手机点击。真实Cluster为单成员FUSED，未测多节点切主、强杀/在途崩溃、长期稳定性和全部可选功能；未启动wallet、未做JMH/JFR/压测。源码/classfile比较不替代独立金融或并发正确性证明。实际命令、退出码、逐例结果、日志SHA与摘要、辅助源码及产物哈希见[机器证据](round77-runtime-20261025.json)。归档后停止本轮依赖并删除临时目录，结果见cleanup；原两项工具删除在工作期间已单独提交为b9515edce，本轮保留该提交；对比补丁SHA与本轮开始时的暂存删除一致，业务源码未变。
