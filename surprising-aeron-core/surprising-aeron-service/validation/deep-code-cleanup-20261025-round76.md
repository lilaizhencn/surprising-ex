# 第七十六轮：结算计划只保留原生业务路径

基于99e505217ce60f6a426a8784ff3d9973d33a4b5c，解决A242–A245，累计240项解决、A11候选、4项保留。全量字段、约束及持久化数据审计尚未完成。

原命令 → 所属Lane完成准入及冻结 → 原生Matcher维护订单簿并产生数量受限的成交 → buildDirectNative整理原始事件、订单编号及Lane路由 → 所属Lane核对当前订单身份、币对和maker剩余量 → 原有资金/持仓应用及账本 → 有序提交、原业务日志和快照恢复。

| 项 | 删除的冗余 | 保留的实际责任 |
| --- | --- | --- |
| A242 | 无生产调用的buildInto、buildSingleInto、buildBatchItem、empty/emptyInto、通用build、两个旧构造器及Owner订单/持仓准备；BatchValidationScratch三字段和initialOrderScratch。 | 原生Matcher限制共享maker数量；原生计划累计核对taker总剩余量，Lane核对maker单次剩余量和权威身份，实际账户处理顺序及金额规则保留。 |
| A243 | Runtime两个仅支持退役构建器/测试的缓存及getter。 | 现有冷金融测试夹具各自持有原局部映射；生产热路径没有新增集合、扫描、状态复制或阶段。 |
| A244 | 只有退役build写入、原生生产路径始终为空的resolvedTaker副本、getter/清空及事件读取条件。 | ResolvedPlaceOrder原生入口继续读取原准入值；真正admittedTaker和eventAdmission发布条件、身份与租约保留。 |
| A245 | 仅测试使用的OrderIdView包装及引用、orderIdList、整数组撤单复制入口、按期望列表再次筛选成功撤单的分支。 | 直接读取orderCount/orderId；原Matcher已确认成功的撤单按原执行顺序写入有效前缀，原替换撤单、追加复制及容量复用保留。 |

3份生产源码新增16行、删除560行，净减544行；净减9个实例字段和2个内部类。MatcherSettlementPlan自身25→22、TradingAccountRuntime自身77→75，另外两个退役内部类共4字段，合计106→97。没有新增业务类、接口、队列或执行阶段，没有把删除的生产构建逻辑搬入测试，也不据此宣称性能提升。

CodeGraph CLI结构分析结合完整classfile owner/name/descriptor确认这些公开构建/视图/筛选入口只有旧MatcherSettlementPlanTest调用；MCP工具未提供，本轮使用本地已初始化CLI。干净构建前后4016→4014份classfile，major均71，退役签名、字段和类的定义/引用为零。三份Core/tools/benchmarks各20份相关classfile与当前target逐字节相同，共60份。保留的27个Plan方法体逐一与原源码比较，只允许清空语句去掉已删除字段；Runtime整文件只删两个缓存及getter，Event整文件只删恒空字段条件；其他生产Java源码哈希不变，包含金融公式、协议和快照。

首次局部编译发现块内addUnique仍供原生深度成交使用，已按原方法体保留；该次未执行测试。随后65例有1项夹具错误：迁移测试用普通命令范围调用要求结算变化槽的方法。夹具改为显式四Lane、taker和maker分属不同Lane，复用计划后在maker实际结算作用域核对当前剩余量拒绝，未放松生产守卫。最终65例通过，之后干净构建、全量及PIPELINED再次通过。首次错误与修复证据均归档。初次PIPELINED选择器还误含两个不存在的测试名，实际执行31类377例；核对后按准确31类选择器再运行377例通过，两次命令与证据分别保留。

原发布、就绪、收集顺序、Owner回收、发布失败及非交易Lane租约的7项用例源码保持。9项旧构建器用例迁至实际原生构建、原生Matcher和有效前缀接口：真实共享maker五单位先成交三再成交二；计划复用读当前Lane maker；九事件深度索引缩为一项；批次有效项和尾部隔离；成交/撤单槽切换；taker单笔及累计超量拒绝。真实跨项maker资金守恒、冻结/终态清理和恢复由保留的CoreOrderedOrderBatchTest覆盖。删除的通用跨项预校验从未处于生产路径，不能将它描述成生产入口原有的拒绝保障。

HotSpot JDK27/Maven3.9.16及磁盘检查后：18模块clean package通过；核心及依赖1986例、0失败/错误、1项Linux CPU亲和性在macOS跳过；31类显式PIPELINED附加377例、0失败/错误/跳过。默认全量中部分类自行强制PIPELINED，不能把1986例均称为FUSED。

| 产品线 | HTTP/WebSocket | 连续改单 | 冷恢复 | 主场景恢复前/后业务哈希 |
| --- | --- | --- | --- | --- |
| SPOT | 通过 | 2 | 1 | 890202d5bf920ff0 |
| LINEAR_PERPETUAL | 通过 | 2 | 4 | ccd3547276b98ec5 |
| INVERSE_PERPETUAL | 通过 | 2 | 4 | 5b5a0606ea24b8d4 |
| LINEAR_DELIVERY | 通过 | 2 | 1 | 7be05bc49f710b9d |
| INVERSE_DELIVERY | 通过 | 2 | 1 | 834016a131a6ca58 |
| OPTION | 通过 | 2 | 1 | c31e723bd80e0ed4 |

六条产品线顺序独立启停，做市参与下单、成交、撤单和推送，资金差额均0。每线两次HTTP改单，验证旧单取消、新身份/价格/数量、数量先增后减及最终预留/冻结归零。两种永续另覆盖资金费、强平、保险和ADL的应用/拒绝/幂等及恢复；两种交割覆盖到期流水、费用及持仓归零；期权覆盖OCO和CALL/PUT实平虚值六种到期、费用及守恒。12次真实Cluster快照核对service和consensus记录各加1，原JVM停止及驱动失活后从原目录恢复，命令计数与业务哈希一致。

Cluster实际业务数据及格式未变：Core schema17、触发状态v4、交易快照v56、分段快照v30，不增加旧格式读取。公共HTTP/WS和前端产品源码未变，本轮未重做浏览器或手机点击。实际Cluster为单成员FUSED，未测多节点切主、强杀/在途崩溃、长期稳定性及全部可选功能；未启动wallet、未做JMH/JFR/压测。源码比较不是金融公式或并发正确性的独立证明。命令、逐例结果、失败复验、日志摘要及SHA、辅助源码和产物哈希见[机器证据](round76-runtime-20261025.json)。归档后停止本轮所属依赖并删除临时目录，结果见机器证据cleanup；其他任务暂存删除保持原样。
