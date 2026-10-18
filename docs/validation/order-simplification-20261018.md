# 订单链路八项简化验证（2026-10-18）

## Gateway 入口与无调用逻辑

本阶段完成审查中的第 1、2、5、6、7、8 项：

- `OrderService` 一次完成标准化与对冲方向推导，字段未变化时复用不可变请求；BBO 请求仍解析盘口后构造最终请求。
- 本地校验通过后只传最终请求，删除 `PreparedAeronOrder` 和批次平行 `ValidationResult` 列表；试单仍保留规则版本。
- `AeronOrderCommandService` 不再接收未使用的校验参数，命令结果载荷只复制读取一次。
- 合并 `placeAeron/testLocal/amendAeron` 单调用方转发方法；不改变 Core 命令、幂等 ID 或回执协议。
- `OrderPlacementStateService` 保留实际平仓业务校验，直接返回有符号数量；删除 `ReduceOnlyPosition` 及无生产调用的旧查询方法。
- `OrderMarginMath` 仅保留 `OrderValidator` 实际使用的价格上下界，删除只有测试调用的旧保证金公式及对应孤立测试。

HotSpot Corretto JDK 27（27+33-FR）、Maven 3.9.16：

```bash
mvn -pl surprising-gateway -am -Dtest=OrderServiceTest,OrderBatchServiceTest,AeronOrderCommandServiceTest,OrderControllerTest,OrderPlacementStateServiceTest,OrderValidatorTest,OrderMarginMathTest,BboOrderServiceTest,StableOrderIdentityTest,AlgoOrderServiceTest,CancelAllAfterServiceTest,InstrumentOrderDrainServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

退出码 0；130 项通过，失败/错误/跳过均为 0；20 模块编译通过。
补充用例覆盖六产品线对冲开平仓方向与请求字段、批次全部校验后才提交。首次编译发现测试残留旧调用及仍需使用的枚举 import，修正后以上完整命令通过。

这是本地功能验证，不代表资金端到端或性能验收。此阶段未启动交易集群、做市、wallet，未执行快照恢复或六产品线在线资金对账；后续阶段继续验证点查询和 Core 保证金写入。

工作区并行出现的 `TradingCoreOwner.java` 修改不属于本轮实现，保留原状且不纳入本轮提交。

## 订单点查询（第 3 项）

`ValkeyUserQueries.order` 读取目标 ORDER 字段及必要元数据，直接解码订单；不读取完整用户 Hash，
不加载账户基线，不调用 `UserReadView.from`。没有新增索引、缓存、业务镜像或通用查询框架。
现有余额/仓位点查询改为显式请求基线，保持原有覆盖语义。Gateway 按订单 ID 查询使用该入口。

```bash
mvn -pl surprising-gateway,surprising-realtime/surprising-realtime-api -am -Dtest=ValkeyReadViewIntegrationTest,OrderServiceTest,OrderControllerTest,AccountServiceLocalSnapshotTest,TriggerOrderIdentityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

退出码 0；真实本机 Redis 集成测试覆盖六产品线：目标/缺失/终态订单、晚到快照不复活订单、
最低水位、用户与产品隔离、过期及 epoch 变化。测试故意破坏无关实体与账户基线，确认订单点查询
不读取它们；原余额/仓位的基线、零余额和持仓关闭覆盖测试同时通过。Gateway 验证参数传递和读视图
失败不返回 Core。22 项真实 Redis 测试、80 项 Gateway 测试全部通过，失败/错误/跳过均为 0；
20 模块编译通过。此阶段没有启动交易集群或 wallet。

## Core 保证金写入（第 4 项）

业务顺序为：定位逐仓持仓/需重定价的全仓持仓 → 校验资金与保证金 → 精确计算余额、保证金与版本 →
通过原写入入口更新状态。`DerivativeAccountCommandProcessor` 的逐仓增减、全仓降低杠杆重定价两处
删除构造后立即拆开的临时 `BalanceRuntime`，改传局部数值；所有可能拒绝的算术仍在首次写入前完成。
保留 `PositionRuntime` 预计算、所属 Lane、余额权威单元和原有回滚捕获，不增加中间状态或写入阶段。

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimeStateBusinessRulesTest,RuntimeStateTransitionsTest,RuntimeExecutionScopeTest,CoreLifecycleWorkTest,CoreFinancialLedgerProductionTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,AccountLaneOwnershipTest,ControlLaneDispatcherTest,CoreFundsIdempotencyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

退出码 0；114 项通过，失败/错误/跳过均为 0，7 模块编译通过。
`RuntimeStateBusinessRulesTest` 新增五条衍生产品线的逐仓增减覆盖，直接检查可变运行时在资金不足、
保证金不足和 `Long.MIN_VALUE` 溢出拒绝后与原状态完全一致，并在实际 Account Lane 上连续加减，
与既有业务结果比较，核对资金及快照往返。四条期货线原有全仓降低杠杆用例同时验证实际 Lane、
资金与快照；订单冻结、手续费、资金费、交割、期权行权、账本和幂等回归按以上选定测试执行。

## 六产品线混合链路补充验证

```bash
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=SpotMixedWorkloadTest,DerivativeMixedWorkloadTest,RealtimeWorkloadTest,CorePerpetualEndToEndBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

退出码 0；18 项通过，失败/错误/跳过均为 0，20 模块编译通过。
这是有限功能调用：实际 Core、Matcher、Account Lane/账本运行现货及五条衍生产品线混合订单，
保留 maker/taker、下单/撤改单/成交及资金检查；六产品线实时 outbox 检查每笔公共成交、两侧执行和
提交包络，无丢批。夹具的最终资金、订单终态、预约及快照检查通过。它不经过部署后的 Gateway HTTP、
真实 UDP/Consensus/Archive、完整 WebSocket 或独立做市进程，不作为集群压测结果。

## 局部分配观测

HotSpot Corretto 27+33-FR、JMH 1.37；单线程、1 fork、2×1s 预热、3×1s 测量、`-prof gc`，
G1、256 MiB 固定堆；按父 POM 为 Agrona 开放 `jdk.internal.misc`。
Margin 使用修改前复制的 processor class 覆盖 classpath 首位作基线；after 使用当前 class，
其他依赖和夹具相同。交替调用真实 `adjustAccountPositionMargin` 加/减 1 单位，检查仓位保证金并清除
本命令变更标记；不测跨线程调度。
Order 使用真实临时 Redis，比较旧 `require(...).openOrders().stream().filter(...)` 表达式与
新 `order(...)`，试验前确认返回同一订单；包含 lease 续约及实际读取，基线/点查询分别独立 fork。

| 场景 | 旧路径 B/次 | 简化后 B/次 |
| --- | ---: | ---: |
| 逐仓保证金调整 | 256.004 | 216.005 |
| 单订单查询：用户 1 个订单 | 12096.713 | 9365.608 |
| 单订单查询：用户 100 个订单 | 282746.013 | 9222.807 |

保证金局部路径减少约 40 B/次；100 个订单时查询分配减少约 96.7%。这些数据只描述该 JVM 的局部分配，
不包含 Redis 进程内存，不代表六产品线网络容量。短窗口耗时误差很大，且未采集热控/频率，
**不据此宣称延迟或吞吐改善，也不作为统一端到端性能验收**。其他六项仅报告结构/可读性收益，
没有独立分配量测值。初次采样因缺少 Agrona export 参数失败，修正后成功结果全部退出 0。
完整 JMH 指标、原始夹具源码、编译方式、基线及当前 class SHA256 见
[采样归档](order-simplification-20261018-jmh.json)。

## 八项对照与交付边界

| 审查项 | 最终处理 |
| --- | --- |
| 1 校验对象及多级包装转发 | 校验只在入口使用；删除 PreparedAeronOrder 和批次平行校验列表 |
| 2 命令载荷重复复制 | requireCommandResult 只调用一次 response.data() |
| 3 单订单全量用户物化 | ValkeyUserQueries.order 只读目标 ORDER 与元数据 |
| 4 临时余额中间对象 | 逐仓调整及全仓降低杠杆直接传数值至既有余额写入口 |
| 5 多次复制标准化请求 | 一次完成标准化与对冲推导；字段不变复用不可变请求 |
| 6 无职责转发方法 | 合并 placeAeron、testLocal、amendAeron 的业务入口 |
| 7 无生产调用的旧方法 | 删除旧状态查询和保证金公式；保留实际使用的价格边界 |
| 8 单字段中间模型 | 删除 ReduceOnlyPosition，平仓校验直接返回有符号数量 |

没有新增生产类、接口、容器、缓存或异步阶段。分三批提交：Gateway `6cef18bbd`、点查询 `66947e4e6`，
最后一批为 Core 与本记录。工作区并行的 `TradingCoreOwner.java` 仍排除在本轮提交之外；本地测试编译
时包含该工作区文件，不能把这些结果解释为其改动的独立审查。

未测：部署环境 HTTP/WebSocket 与常驻做市、真实 Aeron 网络/Archive 的端到端重启恢复、持续资金费/
强平/ADL/止盈止损长稳、集群容量。当前本机无 Gateway/做市/Redis/Kafka 监听，专用 live QA 的
BASE_URL、operations token、Kafka 等环境变量未配置；本轮使用临时 Redis 与 Maven 内嵌业务夹具，
未启用依赖这些配置的 live 测试，未将跳过算作通过。没有启动 wallet，没有修改 topic、协议、资金模型
或匹配/结算顺序。

临时资源：本轮目录 `/tmp/surprising-simplify-UcOD6R0J` 用于 Maven 日志、JMH 夹具和基线 class；
原始路径仅作历史定位。2026-10-18 清理完成：四批 Maven 日志的成功状态、计数和 SHA256，
JMH 原始指标及夹具源码已归档；本轮命令全部退出，临时 Redis 已关闭，该临时目录已删除。
保留标准 Maven 构建产物；其他任务的进程、日志和工作区修改不属于清理范围。
