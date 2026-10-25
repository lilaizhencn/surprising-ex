# 第七十五轮：普通准入只交接真实结果

基于a277ec6e4afdb2aed2e9e6167cc94efe471d9711，解决A238–A241，累计236项解决、A11候选、4项保留。完整字段、业务约束及落地数据审计继续。

原命令及Header → Owner绑定意图、路由、价格和费率策略 → 账本提供OI → 所属Lane完成决策、用局部客户键准备身份、计算并写入真实冻结或回滚 → 完成位 → Matcher接收订单身份、接受标记、结果码及前置撤单 → Owner登记原待预留索引/修订并保留实时输入 → 原生撮合、Lane资金结算、有序提交和原业务持久化。

| 项 | 删除的冗余 | 保留的业务责任 |
| --- | --- | --- |
| A238 | reservedAmount、admittedAccountVersion及其回执参数、重置和两个非负检查。原“金额/版本核对”注释与实际不符，接收方没有保存或使用这两个值。 | requiredReservation局部值仍送入原placeOrderInLane，实际冻结/费用与订单、账户版本更新均保留；改单仍在applyReplacement核对原订单/用户版本，并按真实requiredReservationUnits写入预留。 |
| A239 | preparedClientKey实例字段、重置及只有测试读取的clientKey方法。 | 同一次execute用原局部键准备、写入或回滚；不跨越Lane或创建第二份身份状态。测试改查真实Lane身份索引。 |
| A240 | admittedOrder副本、admissionCompleted空转setter/约束及仅供测试的getter。 | 实际collectPlaceAdmission调用、待预留索引、修订、收取标记与消费后回收仍按原顺序执行；原事件已有完成/拒绝校验，真实只减仓、金额和版本检查保留。 |
| A241 | PlaceSlotContext字段容器、preparedOrder重复入口、无用spins局部量及未消费的Lane路由计算。 | 三个真实字段归入原命令槽，实时捕获直接沿现有访问器读取；matchingOrder保留相同路由检查。prepare正用户检查及真实Lane路由检查、回收与发布边界不变。 |

7份生产Java源码新增35行、删除135行，净减100行。净减5项实例字段及1个内部类：PlaceAdmissionEvent自身24→21；CommandSlot自身59→61，是三个真实字段移入并删除原包装引用；旧PlaceSlotContext4项不再存在。这三个类直接声明的实例字段合计87→82，未把移动字段伪称删除数据。没有新业务类、接口、队列或执行阶段，不宣称性能提升。

CodeGraph CLI结构分析结合完整owner/name/descriptor：原admittedPlaceOrder和clientKey仅测试读取，admissionReceipt只有PlaceAdmissionEvent调用。删除前4017份、干净构建后4016份classfile，均major71；退役类、六个旧签名和删除字段的定义及引用均为0。Core、tools和benchmarks各47份相关classfile与当前编译结果逐字节相同，共141份。

7份生产文件逐一作明确变换后的整文件对照：回执只删两参数及检查；客户键只局部化；命令槽展开原包装并删除无业务消费的副本；CoreMatchingFlow仍收取真正准入，实时交接只改相同引用的访问；TradingAccountRuntime只删未使用的纯路由结果。RuntimeOrderAdmission、CoreOrderDecisionResolver、RuntimeOrderStateTransitions、RuntimeIdentityRegistry、ReplacementAdmissionEvent、LaneTopology及协议/快照源码哈希保持。完成、输入就绪、重复收据、订单身份、Matcher消费与对象池回收守卫继续存在。此对照不是独立金融公式或并发正确性证明。

删除前两个阻塞Matcher方法在六线共12例通过。初次迁移脚本把四处同后缀调用误当两处，随后一次独立恢复缺少导入；完成测试迁移前启动的编译读到了四处旧getter，产生编译及后续类型推断错误，未执行运行用例。完成迁移后串行复验12例通过，再执行18模块干净构建；过程、首次编译错误段及复验均入机器证据；各原始日志保存SHA与末12行摘要，未宣称保留完整原始输出。

两个原阻塞测试现在等待Owner已收齐标记，再读取原准入事件，保留订单身份、价格、资金、串行对照、恢复及“暂停Owner仍由Matcher/Lane完成”的断言。身份用例不再只断言副本为零：实际查Lane身份索引，覆盖过期价格拒绝、完成价格决策后准入拒绝、同一事件复用成功，验证拒绝无残留、成功有真实客户键及原费率；单例复验通过，最终完整回归再次覆盖。

HotSpot JDK27/Maven3.9.16及磁盘检查后，18模块clean package通过，核心及依赖1986例0失败/错误，1项Linux CPU亲和性因macOS跳过；28类显式PIPELINED共354例0失败/错误/跳过。默认全量中部分类强制PIPELINED和4账户Lane，不能把1986例均称为FUSED。

| 产品线 | HTTP/WebSocket | 连续改单 | 冷恢复 | 主场景恢复前/后业务哈希 |
| --- | --- | --- | --- | --- |
| SPOT | 通过 | 2 | 1 | 933430dbdc11e70c |
| LINEAR_PERPETUAL | 通过 | 2 | 4 | 9ab74493ffa059e8 |
| INVERSE_PERPETUAL | 通过 | 2 | 4 | edb17869dc324e23 |
| LINEAR_DELIVERY | 通过 | 2 | 1 | 43b313499a02de54 |
| INVERSE_DELIVERY | 通过 | 2 | 1 | 79c625c8ebb5f4a8 |
| OPTION | 通过 | 2 | 1 | 997ea4749302ba9f |

六线顺序独立启停，做市参与下单、成交、撤单和推送，资金差额均0。每线两次真实HTTP改单，核对旧单取消、新身份/价格/数量、数量先增后减及最终冻结/预留归零。永续另覆盖资金费、强平、保险、ADL的应用/拒绝/幂等及恢复；交割覆盖到期、费用及持仓/冻结归零；期权覆盖OCO和CALL/PUT实平虚值到期、费用与守恒。12次实际Cluster快照均核对service/consensus记录各加1，原JVM停止和驱动失活，从原目录恢复，命令数及完整业务哈希相同。

删掉的是运行中的无用副本，Cluster实际业务数据和格式未变：Core schema17、触发状态v4、交易快照v56、分段快照v30。没有旧格式读取或备用路径。公共HTTP/WS契约及前端产品源码未变，本轮未重做浏览器或手机点击。真实Cluster为单成员FUSED，未测多节点切主、强杀/在途崩溃、长期稳定性和全部可选功能。未启动wallet，未做JMH/JFR/压测。具体命令、退出码、逐例结果、源文件及产物哈希、辅助源码见[机器证据](round75-runtime-20261025.json)。归档后停止所属依赖并删除本轮临时目录，清理结果见cleanup。
