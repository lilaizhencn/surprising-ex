# 第七十二轮：批量下单直接使用原账户冻结入口

本轮基于784ad7ca9d2f79689345ebc1cd63f623a82909bd，解决A229–A230两组，累计225项解决、A11候选、4项保留。全量字段、约束和持久化审计继续。

业务链路为：Owner固定批次决策输入，必要时由账本读取OI → 原控制任务进入所属账户Lane → 解析订单、准备客户号、检查准入和平仓冲突 → placePrepared写订单与冻结 → 登记待完成预留并捕获原订单租约 → 原控制调度器发布回执，Owner继续顺序撮合。异常仍回退已准备客户号并原样上抛，finally只发布任务体完成。

| 编号 | 清理 |
| --- | --- |
| A229 | BatchPlaceReservation的冻结资产编号改为单次账户回调局部变量，自身声明的实例字段16→15，删除对应重置。把只由该回调调用的批次冻结包装层并入原回调，删除永远不会进入的无Lane备用分支和重复路由检查。 |
| A230 | 删除仅被测试调用的place身份准备转发入口；测试直接按原顺序准备客户、币对、资产编号后调用现有placePrepared，不复制冻结或业务判断。 |

生产2文件新增6行、删除70行，净减64行；另1测试文件对齐正式冻结入口。没有新增类、接口、容器、队列或执行阶段。

批次回调在解析和冻结之前已要求非空当前Lane、回调编号及用户拓扑一致；其后的调用一直处于同一个控制任务范围。因此原包装层的无Lane分支不可达，重复路由检查没有增加业务约束。当前账户检查、准入公式、重复订单/客户号、用户/余额/Owner、冻结和数值检查仍由原边界执行。

资产编号只在同一个apply(int)中赋值后传给冻结入口，没有跨线程消费者，不进入命令、Cluster日志、快照、哈希或查询。它改为局部变量后仍使用相同产品/买卖方向选择，不增添状态来源。

CodeGraph结构结果由当前classfile的完整所属类/名称/描述符补足：被删批次包装层只有一个生产调用者；place只有测试调用；资产字段只被本类引用。干净构建后两个公共方法、备用分支生成的lambda及资产字段均无定义/引用残留。4021份当前classfile均为major71；Core、tools和benchmarks中相关6份classfile与当前编译结果逐字节一致。其他生产Java源码哈希不变，配置/脚本注册字面量没有对应入口。

没有删除同步触发单预留：CodeGraph未返回调用方，但完整classfile引用显示TriggerOrderCommands.executeTriggerOrder确实调用reservePlaceOrderRuntime。也保留账户状态测试使用的cancel规则，因为它承担订单存在、归属、终态及预留校验；实际cancelOrderInLane还要求Matcher结算范围，不能把两者当作纯转发替换。批次输入、入队、派发和完成标记各有原线程生命周期用途，本轮保留。

原placePrepared、触发子单、cancel以及批次准备/推进/账本输入/租约读取等8段方法正文对照相同。账户回调仅把资产改局部变量、将原有效分支的冻结与待预留标记原顺序展开，其他计算、客户身份回滚及finally正文相同。clear仅移除资产字段重置。此比较不是独立金融公式证明。

HotSpot JDK27、Maven3.9.16及磁盘检查后，18模块干净构建通过；修改前271例、修改后默认271例及PIPELINED271例均0失败/错误/跳过。覆盖14相关类，包括批次有序准入与部分拒绝、原单成功前缀、客户号/冻结回滚、队列继续、单笔/批量撤改单、六产品准入、资金和快照/日志回放。未重复完整1981例。首次classfile清单采样与Maven测试重编译重叠，列出的测试类在读取前被重编译删除，采样退出1；两种测试结束后重新采样通过。构建及业务测试没有失败。

| 产品线 | HTTP/WebSocket | 冷恢复次数 | 主场景恢复命令数 | 主场景恢复哈希 |
| --- | --- | --- | --- | --- |
| SPOT | 通过 | 1 | 37 | 7b05926a260e62a0 |
| LINEAR_PERPETUAL | 通过 | 4 | 772 | d75c63d02209fd17 |
| INVERSE_PERPETUAL | 通过 | 4 | 493 | 3eafd07a4dd043bb |
| LINEAR_DELIVERY | 通过 | 1 | 813 | 801cb59866da2a69 |
| INVERSE_DELIVERY | 通过 | 1 | 771 | 3b9c90989e0e35b6 |
| OPTION | 通过 | 1 | 1945 | 1ab6d276af4ae80f |

六线按上述顺序独立启停，实测做市、下单/撤单/成交、余额/冻结/终态、公共/私有推送、输入拒绝及配置。两种永续另验证资金费、强平、保险及ADL的应用、拒绝、幂等和恢复；交割验证到期持仓/冻结归零；期权验证OCO及CALL/PUT实平虚值六组到期、权利金/费用和守恒。没有启动wallet。

12次真实Cluster冷恢复包括六条主场景及两种永续在强平、保险、ADL后各一次。每次实际请求快照，有效service/consensus记录各增1，停止原JVM并等驱动失活，再从原目录及日志恢复；恢复前后命令数与完整业务状态哈希一致。驱动失活轮询暂时返回1属于等待步骤，最终检查通过。

实际命令、退出码、271例身份、金额、恢复值、源码/运行包/日志SHA及门禁源码见[机器证据](round72-runtime-20261025.json)。

    mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
    mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreOrderedOrderBatchTest,BatchDecisionCaptureTest,OrderBatchSlotReuseTest,CoreOrderDecisionResolverTest,CoreAdmissionInputOwnershipTest,CorePreflightLaneQueryTest,AdmissionSettlementFactTest,LaneAdmissionAggregateTest,LaneAdmissionReuseTest,RuntimeStateBusinessRulesTest,TradingAccountRuntimeTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,RuntimeCommitRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test
    mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreOrderedOrderBatchTest,BatchDecisionCaptureTest,OrderBatchSlotReuseTest,CoreOrderDecisionResolverTest,CoreAdmissionInputOwnershipTest,CorePreflightLaneQueryTest,AdmissionSettlementFactTest,LaneAdmissionAggregateTest,LaneAdmissionReuseTest,RuntimeStateBusinessRulesTest,TradingAccountRuntimeTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,RuntimeCommitRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false -Dsurprising.aeron.execution-mode=PIPELINED test
    mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test

真实Cluster为单成员FUSED，PIPELINED另由271例覆盖。未测多节点切主、强杀/在途崩溃、长周期定时及所有可选业务；公开契约不变，未做浏览器/Flutter点击。未做JMH/JFR/压测，不宣称性能提升。摘要归档后清理本轮临时进程和目录，实际PID、端口及清理状态见机器证据cleanup。
