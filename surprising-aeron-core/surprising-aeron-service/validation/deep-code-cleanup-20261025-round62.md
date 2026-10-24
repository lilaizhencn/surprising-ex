# 第六十二轮：保险索赔直接使用原强平事实

基于 `2ac60ac6ebf10f8ba328eb7a0d96c029d99041bb`，本轮解决A193/A194。累计189项解决、A11候选、4项保留，全量审计继续。

保险查询由账户Lane读取自己持有的强平事实，账本按结算资产分组和基金余额计算推荐额；实际补亏在原ResolutionWork中收集Lane回执，账本验证确定性优先级和覆盖额，再由源Lane更新强平阶段。该所有权和回滚流程保留。

- A193：删除无调用的prepareResolution(runtime, identities, candidateIds, target, assetId)重载；保留实际工作使用的回执数组重载。先通过CodeGraph定位，再用4018份classfile中的完整所属类/方法/描述符区分同名重载，删除项没有调用；反射/配置字面量未发现相关入口。
- A194：删除私有Claim record及liquidation/instrumentId两个字段。各列表直接保存同一LiquidationRuntime引用；规范合约名直接从其instrument取得，不再为每条索赔创建重复包装。该轮保留AllocationInputs和ResolutionInputs。后续第六十三轮核实：实际跨线程的是Lane回执与AllocationInputs；ResolutionInputs只在账本回调内创建和消费，已在该轮清理。原说明对两者用途的概括过宽，现予更正。

合约身份解析要求规范正整数文本，与身份表反向名称相同。排序仍依次按触发价格序号、用户、合约名的字符串顺序、持仓方向ordinal、强平编号，未改为合约数字排序。状态/资产/配置身份过滤、候选重复规则和列表独占生命周期保留。两个真实分配计算方法在去除访问包装与类型替换后正文一致；BigInteger比例、整除、余数补一、覆盖上限及精确运算未改。

生产仅改1文件，新增30行、删除63行，净减33行；删除1业务重载、1私有record、2实例字段，没有新增生产类、字段、接口或阶段。未改命令、快照或HTTP/WebSocket格式。没有把这两项临时包装字段算作Cluster持久化字段删除。

HotSpot JDK27、Maven3.9.16及218Gi磁盘核对后，18模块干净构建运行包成功；9类108例相关回归全通过，0失败/错误/跳过。未改既有测试源码或金融期望值。随后按现货、正向/反向永续、正向/反向交割、期权顺序启动真实服务，每线HTTP/WS用例1项全通过。

| 产品线 | 业务实测 | 主场景恢复前后命令数 | 主场景恢复前后哈希 |
| --- | --- | --- | --- |
| SPOT | 通过，资金差额0 | 61 | `d63b7793e42a9c9d`，一致 |
| LINEAR_PERPETUAL | 通过，资金差额0 | 701 | `3c9031cead4073fe`，一致 |
| INVERSE_PERPETUAL | 通过，资金差额0 | 579 | `2eddb66cf735dcb3`，一致 |
| LINEAR_DELIVERY | 通过，资金差额0 | 778 | `35f69fb38f56c62c`，一致 |
| INVERSE_DELIVERY | 通过，资金差额0 | 757 | `03ddc50f86a7286d`，一致 |
| OPTION | 通过，资金差额0 | 1994 | `fadbf67113253693`，一致 |

六线均验证真实做市、下撤单、市价成交、终态与余额、用户执行推送及公共盘口/成交WS和REST身份一致，并验证配置热更新、版本/非法参数拒绝及标的状态变化。衍生品核对风险；现货另核对FOK及私有历史/成交。交割核对到期撤单、两条触发单撤销、幂等结算、精确损益流水及持仓/冻结归零。期权核对OCO终态回执和时间线、CALL/PUT×实值/平值/虚值6种到期、权利金、费用及资金守恒。

两种永续各完成真实资金费/风险扫描/强平门禁及带持仓恢复，再补充实际保险流程：原基金178/179、亏空22/121，分别转出177/178，把预算固定为1。推荐额必须为1，申报覆盖2必须按INSURANCE_ALLOCATION_MISMATCH拒绝，工作页保持不变；实际覆盖1后亏空精确变为21/120，进入ADL待办。基金余额为0，资金总量必须分别2183/2182，资金差额0；再次冷恢复后复验一致。这是有限业务门禁，不是压测。

共10次真实Cluster快照冷恢复，均实际请求快照、核对service/consensus有效记录各新增1条，停止旧JVM并确认MediaDriver失活后使用原日志/目录启动，核对完整业务哈希和已应用命令数；未重建空状态。包含保险部分覆盖后仍待ADL的状态恢复，未将其表述为ADL自动减仓通过。

首次补充保险夹具误以为基金为0，实际在任何修改命令前被夹具断言拒绝；查明并显式设置固定预算后完成完整复验。失败及后续结果均保留在[机器证据](round62-runtime-20261025.json)。未调整生产金额逻辑或既有金融断言来适应测试。

最终4017份当前classfile均major71；删除重载定义和调用、Claim类及类型引用均不存在。实际Core运行包中的保险分配classfile与当前编译输出相同。源码、运行包、日志SHA-256、命令退出码、逐产品标记和恢复前后值见机器证据。

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
```

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLifecycleStateTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreFinancialLedgerProductionTest,CoreLedgerQueryTest,CoreRiskStateTest,SystemLedgerControlTest,CommittedLiquidationReplayTest,AdlPositionIndexTest -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- 未执行浏览器/Flutter点击；本轮未修改HTTP/WebSocket及前端契约。
- 只验证单成员FUSED网络Cluster，未执行三节点切主、强杀或金融命令在途崩溃。
- 保险实际补亏及ADL待办恢复已实测；未执行保险/ADL定时调度完整周期、自动ADL减仓、自动止盈止损和额外算法单/统计可选分支。
- 未启动wallet，未运行JMH/JFR/压测；无性能提升或全功能覆盖结论。

本轮摘要归档后停止所属进程、核对端口及PID并清理临时目录；最终清理状态见机器证据cleanup。完成本轮六线验收后才开始下一轮分析。
