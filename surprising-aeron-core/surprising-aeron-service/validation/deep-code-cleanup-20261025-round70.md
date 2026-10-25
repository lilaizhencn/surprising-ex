# 第七十轮：风险续扫只保留实际账本与账户链路

本轮基于09132769a71196496860c05a8e80f1ba1446244e，解决A225–A226两组，累计221项解决、A11候选、4项保留。全量字段、约束及持久化审计继续。

线上链路：ContinueRiskScan检查扫描开关与预算 → 控制槽复用RiskScanCoordinator → 账本按持仓索引准备各Lane有界用户页并发布完成 → 账户Lane估值 → Owner按固定Lane顺序分配强平编号 → 原Lane写入 → Owner合并游标、修订和提交。未就绪时返回false，线程交接、错误及恢复边界保留。

| 编号 | 清理 |
| --- | --- |
| A225 | 删除3个同步续扫入口、整份runSlice与五秒Owner借权等待、备用用户集合字段和NavigableSet分支；账本路径直接使用原持仓用户索引。测试改走现有RiskScanCoordinator.poll，不增加生产接口或兼容路径。 |
| A226 | executeUserRisk只有测试调用，executeRiskLane只有被删runSlice和测试调用，均删除。实际账户修改权限检查、dispatchRiskLanes线程交接及原异常传播保留，未放宽跨线程访问。 |

生产3文件新增5行、删除214行，净减209行；删除6个非线上同步方法、1个索引转发方法及1个私有备用构造重载。风险工作对象字段24→23，删除indexedUserIds。另删除无人调用的测试simulateContinuation，移除测试间透传的全用户集合参数。没有新增生产类、接口、容器、队列或阶段。

CodeGraph和全部当前classfile的完整所属类/名称/描述符证据相互补足：三个applyContinuationRuntime重载只有内部互转和测试调用，runSlice只由它们调用；executeUserRisk只有测试调用，executeRiskLane只有runSlice及测试调用。删除前检查反射/配置字面量，删除后确认旧7个方法定义及引用、备用字段定义及引用均消失。运行Core、tools和benchmarks相关classfile与当前编译结果一致，不按零文本搜索直接判删。

备用分支要求NavigableSet并逐个过滤Lane，是冷测试专用来源。线上始终提供账本持仓用户索引；删除分支不增加全表扫描或状态副本。预算1–4096、扫描开关、行情序号、期权风险价格、账户读取权限、确定性编号和溢出检查仍由实际入口执行。旧五秒Owner借权等待随无人使用的同步方法删除，实际工作线程访问检查保留。

13段行情登记、续扫、预算、估值调用、编号及游标合并正文对照一致；账本用户页方法除直接内联原索引分支外一致。其他生产Java源码哈希与基线一致。命令、Cluster日志、快照、HTTP/WebSocket和前端布局未改；本轮是内存续步字段清理，不计作持久化字段缩减。

相关测试使用现有真实续步，没有将旧同步逻辑搬入测试。原先指定币对绕过调度的用例改为BTC和ETH各推进一个有界部分页，再更新价格；恢复前后每页实际工作量及完整状态一致，最后所有扫描完成，权益300与无强平断言保留。初始229例中1项页数夹具失败；首次8例复验又发现提前全扫BTC产生真实CANCELED强平历史。改为两币对均未完成且无强平的合法检查点后8例通过，失败输出和退出码已归档，生产逻辑未为测试调整。

HotSpot JDK27、Maven3.9.16和磁盘检查后，18模块干净构建通过。完整依赖模块测试1981例，失败0、错误0、跳过1（macOS下的Linux CPU亲和性场景）；PIPELINED下相关11类229例通过，失败/错误/跳过均0。小预算1/3/7、真实账本与账户线程、跨页强平编号、资金不变、组合累计失效、快照和回滚均覆盖。冷参照共用生产规则，不作为独立公式证明，原手工金额与守恒断言仍在。

| 产品线 | HTTP/WebSocket | 冷恢复次数 | 主场景恢复命令数 | 主场景恢复哈希 |
| --- | --- | --- | --- | --- |
| SPOT | 通过 | 1 | 40 | ff61b37d067d0eb1 |
| LINEAR_PERPETUAL | 通过 | 4 | 762 | 1836dc09dca1df25 |
| INVERSE_PERPETUAL | 通过 | 4 | 443 | 0487014ee3a00b30 |
| LINEAR_DELIVERY | 通过 | 1 | 753 | 1923bca7be4c2031 |
| INVERSE_DELIVERY | 通过 | 1 | 776 | da447a492e1df9f9 |
| OPTION | 通过 | 1 | 2010 | 87af13644b89f72d |

六线依次验证做市、下单/撤单/成交、余额和终态、公共/私有推送、输入拒绝及配置。两种永续另实测资金费、强平、保险与ADL的合法应用、拒绝、重复命令和恢复；交割验证到期持仓/冻结归零；期权验证OCO、CALL/PUT实平虚值六行到期、权利金/费用及守恒。每线停完后才启动下一线，未启动wallet。

12次真实Cluster冷恢复包括六条主场景，另两种永续在强平、保险和ADL后各恢复一次。每次实际请求快照，有效service/consensus记录各增1，停止原JVM、等待驱动失活，使用原目录和日志恢复，命令数与完整业务哈希一致。失活探测中的暂时非零返回是等待，最终全部通过。真实Cluster为单成员FUSED，PIPELINED另由229例覆盖。

实际命令、退出码、完整测试身份、失败与复验、金额、恢复值、源码/运行包/日志SHA及门禁源码见[机器证据](round70-runtime-20261025.json)。

    mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
    mvn -pl surprising-aeron-core/surprising-aeron-service -am test
    mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreRiskStateTest,ParallelRiskScanTest,RuntimeRiskQueryServiceTest,CoreLifecycleStateTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,TradingAccountRuntimeTest,RiskBatchBudgetTest,RuntimeCommitRecoveryTest,CommittedLiquidationReplayTest,CoreLiquidationLaneCheckTest -Dsurprising.aeron.execution-mode=PIPELINED -Dsurefire.failIfNoSpecifiedTests=false test
    mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test

未测多节点切主、强杀或在途崩溃、长周期定时及全部可选功能；未运行浏览器/Flutter点击，公开接口无变化；未做JMH/JFR/压测，不宣称性能提升。摘要归档后关闭并清理本轮临时资源，实际PID、端口和删除状态见机器证据cleanup。
