# 第六十六轮：资金费和到期结算只交接实际金额

本轮基于 5532d9c4dca7407e4e682ba8ba9900d0ee268a23，解决A206–A210五组；累计205项解决、A11候选、4项保留，全量字段与约束审计继续。

资金费保持Owner校验与固定价格、账本选页、账户Lane收付、账本记残差及进度、Owner完成的顺序。交割和期权保持撤单→准备后值→整页保险核对/预扣→应用账户→清算记账，保险不足时仍保留原用户游标。没有合并产品公式、缩减真实阶段或改变财务范围。

| 编号 | 清理内容 |
| --- | --- |
| A206 | 资金费Lane回执仅需一个残差long，删除Lane及FundingWork的通用多资产差额创建/合并/应用；账本直接使用原setFundingResidual |
| A207 | 资金费Lane直接写自己的明确类型回执槽，删除Object返回值读取、collectAccountReceipt转存及不可能出现的错误类型忽略分支 |
| A208 | 到期Lane只需返回清算long；financialResults改为原语clearingByLane，删除SettlementWork通用差额及各Lane差额对象，账本直接使用原setClearingPnl |
| A209 | 准备后值改为List<UserSettlement>[]；Lane直接写计划/清算槽，删除两次Owner无类型回执复制及多处unchecked读取转换 |
| A210 | UserSettlement.userId与唯一生产者创建的balance.userId恒同；删除重复字段与构造参数，应用时读取原余额身份 |

三个实现及嵌套类型实例字段60→57，净减3字段；另三项字段仅替换必要数据形状，没有虚报为字段删除。生产3文件新增53行、删除79行，净减26行；没有新增类、接口或异步阶段。通用差额类型仍服务其他业务，本轮未把其全部字段计为删除，也不宣称性能提升。

资金费每个用户原来的addFundingResidual转为同位置的addExact，仍在余额写入后、用户修订前累计；可用不足减免、明细生成/顺序、名义金额和币本位舍入正文经标量替换后完全一致。账本按原Lane顺序合并，只有changedUserIds非空才记账：同一用户对冲明细可有金额但净余额不变，不据明细数量补造资金写入；多个实际收付相抵为零仍按原语义写入零差额。

到期prepareLane只去掉重复用户参数，其全仓/逐仓、期权/交割金额、保险和后值计算不变。applyLane的余额/持仓/用户修订顺序保持，逐计划精确加法仍在同一账户变更点执行，未移到晚一阶段。账本按原Lane顺序精确合并；非空计划即使清算为零也仍记账，空计划不写。保险汇总、预算不足处理、预扣和financialScopeComplete原样保留；原Setter继续captureAssetBefore记录完整资产前值，范围回滚机制不变。

回执只在原账户完成位release后由Owner acquire读取，再通过原账本队列交接；保留usersReady/usersQueued、用户页和实际阶段。静态对照确认移除回执复制后Owner阶段转换不变，两个Phase枚举不变，资金费准备/选页/结果、到期进度/撤单守卫及真实业务拒绝码顺序不变。UserSettlement仅为保险核对到应用之间的临时计划，不是命令或快照模型。Core及命令/快照、HTTP/WebSocket格式均未改。

HotSpot JDK27、Maven3.9.16及磁盘核对后，18模块干净构建通过；12类150个不同回归通过，另PIPELINED配置下4类80例通过，共230次当前回归执行，0失败/错误/跳过。修改生产代码前先通过80例结算基线。资金费原账本用例扩为4种永续/保证金组合×单/双账户Lane共8例，显式确认付款与收款账户同Lane或跨Lane，比较完整状态和明细并检查Owner不可读取财库。原到期保险不足、分页、期权零行权和金额硬断言保留；冷对照部分共享生产实现，不作为独立公式证明。

反向永续首次实测出现严格金额断言9999/10000不符。定位为实测独立预期先将名义金额HALF_UP，再算保证金的二次舍入问题。固定向量mark=500050、显示名义金额1999800时，旧预期给9999，完整分数最后CEILING及生产公式均给10000。修正预期保留分子/分母到最终舍入，新增边界、整除反向及整数线性3例通过，金额断言没有容差，生产保证金公式源码与基线一致。共153个不同回归、含PIPELINED补跑233次执行；这3项是验证预期修复，不计为Core字段清理。

失败XML、风险当前读及计算向量已保留。失败场景instrument869、用户13/14通过真实配对减仓平仓，持仓/预留/冻结归零，资金总额前后均2000000000；没有删除或重置交易数据。恢复入口重复调用非幂等up导致启动失败并触发运行栈清理，确认旧进程和驱动失活后从原日志重启，再核对这两账户余额及平仓状态一致。两次失败、补偿操作和复验完整保留；这次额外重启没有当作下面12次具有完整前后哈希的计划恢复。

完成的现货与线性永续使用原实测预期：现货不计算风险保证金，线性名义金额是整数、分母为1，新旧预期等价。生产运行包始终未变；保留已通过结果，从反向永续继续，后续产品使用修正后的精确预期。

最终依次完成六线真实HTTP/WebSocket验收，每线最终1项均0失败/错误/跳过，完成并停止上一线后才启动下一线。

| 产品线 | 业务实测 | 主场景恢复命令数 | 主场景恢复哈希 |
| --- | --- | --- | --- |
| SPOT | 通过，资金差额0 | 40 | 978736284c65f52e，一致 |
| LINEAR_PERPETUAL | 通过，资金差额0 | 755 | fbc0392cffb7ceaa，一致 |
| INVERSE_PERPETUAL | 通过，资金差额0 | 4236 | b7434ec95a5703db，一致 |
| LINEAR_DELIVERY | 通过，资金差额0 | 785 | 4a2828ff304ceeb9，一致 |
| INVERSE_DELIVERY | 通过，资金差额0 | 743 | a97f87f69c12b60b，一致 |
| OPTION | 通过，资金差额0 | 2033 | 1d6967ed241b99db，一致 |

六线覆盖实际做市、下撤单、市价成交、余额/终态、执行推送、公共盘口/成交WS与REST身份核对，配置热更新、非法输入/版本拒绝和标的状态变化。现货另覆盖FOK及私有历史/成交；衍生品核对风险。两种交割验证到期撤挂单及触发单、精确损益、幂等、持仓/冻结归零。期权覆盖OCO及CALL/PUT×实值/平值/虚值6行到期，零行权行也完成持仓/冻结归零，权利金、费用与资金守恒通过。

两种永续另以独立Core小额场景实际执行资金费、风险扫描、强平、保险和ADL。保险申报2被拒绝，实际支付1后剩余亏空21/120并恢复待ADL状态。ADL减仓1步覆盖全部亏空按盈利不足拒绝，账户、财库和源待办不变；合法线性减2步、反向减4步，余仓-8/-6、保证金80/6、目标总额198/179、已实现盈亏40/120、清算-18/1。保险基金及亏空归零，源从执行/保险/ADL待办消失；重复相同命令返回DUPLICATE且哈希不变，总资金2183/2182、差额0；冷恢复后复验相同。

共12次真实Cluster冷恢复：六条主场景，加两种永续的强平后、保险后和ADL后各一次。逐次实际请求快照，service/consensus有效记录各新增1，停止原JVM并等待驱动失活后使用原日志/目录启动，已应用命令数和完整业务哈希一致。

最终4017份classfile均major71。完整所属类/字段/方法/描述符核对确认旧字段/签名及调用消失，三个实现无通用差额类型或controlLaneResult引用，真实Core包相关classfile与当前编译结果一致。完整命令、源码/运行包/日志SHA-256和逐产品金额/恢复输出见[机器证据](round66-runtime-20261025.json)。

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
```

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreLiquidationLaneCheckTest,CoreLifecycleStateTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,CoreFinancialLedgerProductionTest,CoreLedgerQueryTest,CoreRiskStateTest,SystemLedgerControlTest,CommittedLiquidationReplayTest,AdlPositionIndexTest,RuntimeCommitRecoveryTest,CoreFundsLedgerIndexTest -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CorePerpetualFinancialMatrixTest,CoreLifecycleStateTest,CoreDeliveryOptionFinancialMatrixTest,SystemLedgerControlTest -Dsurprising.aeron.execution-mode=PIPELINED -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-gateway -am -Dtest=SixProductLineRiskExpectationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- 未执行浏览器/Flutter点击；本轮没有HTTP/WebSocket及前端契约变化。
- 网络Cluster只验证单成员FUSED；PIPELINED另跑80项本地回归，包含公式、冷对照及实际账户/账本线程路径，未验证多节点切主、强杀或在途崩溃。
- 实际保险/ADL、盈利不足拒绝、重复幂等及恢复已实测；未跑完整定时调度周期、自动止盈止损或额外算法单/统计可选分支。
- 未启动wallet，未运行JMH/JFR/压测，不宣称性能提升或全功能覆盖。冷对照部分复用生产实现，手工金额断言继续保留。

摘要归档后停止本轮进程、核对PID和端口并删除临时目录；最终清理状态见机器证据cleanup。本轮六线门禁通过后才开始下一轮。
