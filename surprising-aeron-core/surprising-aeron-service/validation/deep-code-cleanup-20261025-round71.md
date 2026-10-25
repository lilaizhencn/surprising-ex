# 第七十一轮：风险和ADL查询使用唯一固定市场输入

本轮基于23ddfc5b28302d65006b40798027fdba7a1ef9fc，解决A227–A228两组，累计223项解决、A11候选、4项保留。全量字段、约束和持久化审计继续。

业务链路为：风险/ADL查询协议 → Owner校验参数并固定配置、行情和资产编号 → 账本以原持仓用户/ADL索引选择路由 → 所属账户Lane估值 → Owner按原规则排序、限制条数并编码。异常仍区分业务拒绝、响应过大和系统故障，账本及账户所有权不变。

| 编号 | 清理 |
| --- | --- |
| A227 | 删除2个同步汇总入口和只被其调用的全Lane扫描助手；全局查询继续由原账本持仓索引选路，账户Lane处理正用户。测试通过现有协议取得检查点查询结果，不搬运被删扫描循环。 |
| A228 | 单用户估值直接在原userSnapshots中展开，ADL单仓计算并入原账户入口；统一使用Owner捕获的配置/行情/资产编号，删除5处备用读。保留初始持仓、钱包冻结记录和最终响应上限，取消两个已被调用路径证明重复的计数。 |

生产2文件新增84行、删除212行，净减128行；删除3个冷同步公共方法、2个私有转发层，并移除余额私有助手的一个无用身份表参数。没有新增生产类、接口、容器、队列或阶段。

CodeGraph及全部当前classfile的完整所属类/名称/描述符证据表明：同步snapshots和adlCandidates只有测试调用，usersWithPublishedPositions只由已删同步风险路径调用；不能用同名CoreRiskState.snapshots等真实访问器的调用混作证据。旧5个方法和旧余额助手签名均无定义或引用残留。Core、tools、benchmarks中相关18份classfile与当前编译结果一致，其他生产Java源码哈希与基线一致，反射/配置字面量检查无对应入口。

单用户入口本来已要求正用户和非空MarketInputs，因此不再创建单元素TreeSet、识别用户0或经私有通用层转发；全局用户0由原账本选路处理。估值只读派发前捕获的配置、价格和资产编号，删除5处空输入时改读全局状态的备用来源。ADL单仓估值直接在原入口完成，账户用户及持仓路由校验保留。

单用户计算开始时检查持仓总量，计算钱包时仅再检查冻结记录总量，响应行数限制继续存在。第一轮估值尚未产生输出行，因此上限只计临时估值行数；钱包助手不再重复检查同一Lane任务中未改变的持仓总量。总规模、零数量持仓计数、全局及单Lane响应上限和ADL候选上限继续由原索引/账户/编码边界承担，没有放宽实际业务限制。

排序、市场输入捕获及整数比例/饱和处理等8段方法正文对照一致。单用户风险、ADL、钱包和余额方法在明确删除聚合脚手架、内联原非空分支、移除无用参数和重复计数后，剩余计算语句一致。资金、逐仓扣减、全仓共享权益、期权市值与盈亏区分、维持保证金、溢出和排序规则保留。

实例字段未删除。MarketInputs的3字段用于固定配置、价格及资产编号；PositionValuation的7字段用于两阶段估值、同资产归组和响应输出，均有实际用途。命令、Cluster日志、快照、HTTP/WebSocket与前端契约保持当前布局。

测试的金额断言直接使用非空市场输入的原账户计算入口；全局风险及ADL检查点参照改走真实查询协议，原排序/筛选/索引逻辑没有搬入测试。单用户/全局/空结果、逐仓冻结扣减、候选限一条、查询阻塞时Owner更新行情及配置、零数量持仓和响应超限均覆盖。保留手工BTC精度、钱包/权益、期权和交割盈利33/40等金额断言。检查点查询共享生产路由与公式，不作为独立金融公式证明。

HotSpot JDK27、Maven3.9.16和磁盘检查后，18模块干净构建通过。修改前262例基线、首次262例、最终默认262例及PIPELINED262例全部0失败/错误/跳过；本轮没有失败迭代。验证聚焦相关11类及六线实测，没有重复运行完整1981例。

| 产品线 | HTTP/WebSocket | 冷恢复次数 | 主场景恢复命令数 | 主场景恢复哈希 |
| --- | --- | --- | --- | --- |
| SPOT | 通过 | 1 | 41 | a049ddfe05d2befb |
| LINEAR_PERPETUAL | 通过 | 4 | 921 | dc4bcad4317cdb24 |
| INVERSE_PERPETUAL | 通过 | 4 | 546 | 107a73ec69153127 |
| LINEAR_DELIVERY | 通过 | 1 | 786 | 92f21e05df8dad18 |
| INVERSE_DELIVERY | 通过 | 1 | 857 | 71ba88fd22d78cb3 |
| OPTION | 通过 | 1 | 2364 | 1104fc3f4feca92d |

六线顺序验证做市、下单/撤单/成交、余额和终态、公共/私有推送、输入拒绝及配置。两种永续另实测资金费、强平、保险和ADL的合法应用、拒绝、重复命令及恢复；交割验证到期持仓/冻结归零；期权验证OCO和CALL/PUT实平虚值六组到期、权利金/费用及守恒。逐线停止后再启动下一线，没有启动wallet。

12次真实Cluster冷恢复包括六条主场景，另两种永续在强平、保险及ADL后各恢复一次。每次实际请求快照，有效service/consensus记录各增1，停止原JVM、确认驱动失活，从原目录和日志恢复，命令数与完整业务哈希相同。失活轮询中的暂时非零返回为等待，最终全部通过。

实际命令、退出码、262例身份、金额和恢复值、源码/运行包/日志SHA与门禁源码见[机器证据](round71-runtime-20261025.json)。

    mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
    mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimeRiskQueryServiceTest,CoreRiskLaneQueryTest,CoreLedgerQueryTest,CoreAccountLaneQueryTest,CoreDeliveryOptionFinancialMatrixTest,CoreLifecycleStateTest,CoreRiskStateTest,CorePerpetualFinancialMatrixTest,RuntimeCommitRecoveryTest,TradingAccountRuntimeTest,RuntimeStateBusinessRulesTest -Dsurefire.failIfNoSpecifiedTests=false test
    mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimeRiskQueryServiceTest,CoreRiskLaneQueryTest,CoreLedgerQueryTest,CoreAccountLaneQueryTest,CoreDeliveryOptionFinancialMatrixTest,CoreLifecycleStateTest,CoreRiskStateTest,CorePerpetualFinancialMatrixTest,RuntimeCommitRecoveryTest,TradingAccountRuntimeTest,RuntimeStateBusinessRulesTest -Dsurprising.aeron.execution-mode=PIPELINED -Dsurefire.failIfNoSpecifiedTests=false test
    mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test

真实Cluster为单成员FUSED，PIPELINED另由262例覆盖；未测多节点切主、强杀或在途崩溃、长周期定时及全部可选功能。未运行浏览器/Flutter点击，公开契约无变化；未做JMH/JFR/压测，不宣称性能提升。摘要归档后停止并删除本轮临时资源，实际PID、端口和清理状态见机器证据cleanup。
