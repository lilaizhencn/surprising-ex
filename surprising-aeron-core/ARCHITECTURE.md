# Aeron Core 架构基线

本文件记录当前 `master` 的代码职责和第一阶段重构边界。它不是新的运行时抽象，也不改变现有交易逻辑；后续代码移动必须先更新这里对应的状态所有者和线程边界。

## 1. 当前生产入口

当前生产节点的调用关系是：

```text
SurprisingClusterNode.main
    -> ContinuousTradingClusterService
        -> Aeron Cluster service thread
        -> input queue
        -> trading owner thread
            -> TradingCoreOwner
                -> TradingCoreRuntime
                    -> command / matching / account-lane / snapshot state
        -> output queue
        -> Aeron egress
```

关键边界：

- `SurprisingClusterNode` 只负责节点启动、Aeron/Archive/Consensus 配置和进程级错误处理。
- `ContinuousTradingClusterService` 负责 Aeron 回调、输入输出队列、会话出口和 owner 线程生命周期。
- `TradingCoreOwner` 负责已复制命令的准入、撮合推进、有序提交、实时读取和快照状态边界；不实现 `ClusteredService`，不访问真实 Aeron 会话。
- `SurprisingClusteredService` 只保留旧的 `ClusteredService` 回调适配，供兼容测试和独立回放使用，生产入口不再依赖它。
- `TradingCoreRuntime` 当前同时承载命令路由、撮合流程、提交推进、查询、快照和大量状态门面。

## 2. 现货下单链路

现货链路作为最小业务样本，先用来说明命令、账户 Lane、撮合和提交的边界。产品公式已经按业务归属放入 `business` 包，但本轮不改变任何订单、资金或成交流程。

```text
CoreMessage / PlaceOrderCommand
    -> ContinuousTradingClusterService.onSessionMessage
    -> owner queue
    -> TradingCoreOwner.enqueueCommittedCommand
    -> TradingCoreRuntime.apply / applyCommandIngress
    -> MatchingCommandAdmission
        - 解码和通用订单校验
        - 检查订单、用户、Instrument 和依赖序列
        - 生成 ResolvedPlaceOrder / CoreMatchingOrder
    -> TradingRuntimeState.dispatchPlaceAdmission
        - 进入账户 Lane
        - 计算和登记现货冻结
    -> MatcherCommandPipeline
    -> DeterministicExchangeCoreAdapter
        - 只负责 exchange-core 适配和撮合结果
    -> OrderedCommitCoordinator
        - 校验 matcher 证据和序列
        - 收集 Lane 完成
        - 发布有序提交变化
    -> RuntimeSpotMatchProcessor
        - 应用现货成交后的资产、冻结和订单变化
    -> CommandResultBuilder / response sink
        - 生成可重放终态响应
```

当前链路中需要特别核对的状态所有者：

| 状态 | 当前候选所有者 | 重构要求 |
|---|---|---|
| 订单和用户状态 | `TradingRuntimeState` 及其运行时对象 | 只能有一个权威写入口 |
| 现货冻结和资产变化 | Account Lane / `RuntimeSpotMatchProcessor` | 不能由 matcher 或响应构建器直接修改 |
| 订单簿 | `DeterministicExchangeCoreAdapter` 和 matcher lane | 不携带账户余额和持仓状态 |
| 在途命令 | `PendingMatchingRing`、`CommandSlot` | 只表示执行过程，不是业务状态副本 |
| 提交顺序 | `OrderedCommitCoordinator` | 只负责提交边界，不重新计算资金业务 |
| 查询索引 | `ActiveOrderIndex` 等 index 类 | 必须从权威状态变化维护，不能反向成为权威 |
| 响应 | `CommandResultLedger`、`CommandResultBuilder` | 保存终态结果，不保存第二份订单或余额 |

## 3. 职责分类

### 3.1 交易基础设施

包括 Cluster、Aeron、线程、队列、协议编解码、快照传输和会话出口。基础设施不判断现货、永续或期权规则。

主要位置：

- `surprising-aeron-service/.../cluster`
- `surprising-aeron-service/.../orchestration/ContinuousTradingClusterService.java`
- `surprising-aeron-service/.../orchestration/TradingCoreOwner.java`
- `surprising-aeron-protocol/.../protocol`
- `surprising-aeron-service/.../orchestration/*Snapshot*`

### 3.2 运行时流程

包括 owner 线程上的日志顺序、命令窗口、撮合在途、完成通知和提交前缀。它负责“什么时候推进”，不负责“资金如何计算”。

主要位置：

- `TradingCoreRuntime`
- `MatchingCommandAdmission`
- `OrderedCommitCoordinator`
- `CommandSlot`、`PendingMatchingRing`

### 3.3 权威业务状态

包括用户、余额、订单、仓位、冻结、Instrument 和产品业务事实。它负责“当前状态是什么”，不负责 Aeron 回调和网络响应。

当前主要集中在：

- `TradingRuntimeState`
- `TradingCoreState`
- `UserRuntime`、`BalanceRuntime`、`OrderRuntime`、`PositionRuntime`

### 3.4 产品线规则

产品线只拥有实际不同的业务不变量：

- 现货：base/quote 资产冻结、成交扣减、手续费和解冻。
- U 本位永续：U 本位保证金、持仓、资金费、标记价、强平和 ADL。
- 币本位永续：币本位保证金、持仓、资金费、强平和 ADL。
- U 本位交割：到期价格、交割流水、持仓归零和结算完成。
- 币本位交割：币本位到期结算和持仓清理。
- 期权：权利金、买卖方权益、行权和到期失效。

产品规则可以消费通用订单和成交事实，但不能直接依赖 Aeron、Cluster、响应队列或 matcher 线程。

### 3.5 当前产品规则包

业务公式按产品线和共享范围放置，`state` 只保留权威状态、重放入口和运行时流程：

| 包 | 当前职责 | 允许共享范围 |
|---|---|---|
| `business.spot` | 现货冻结需求和现货产品规则 | 仅现货 |
| `business.derivative` | 永续/交割共同的订单冻结和成交开仓保证金公式 | 仅四条衍生品线；不包含现货和期权 |
| `business.option` | 期权冻结、成交保证金、风险价格校验和期权产品规则 | 仅期权 |
| `business` | 与产品无关的订单费用扣减数学 | 只允许不含产品分支的通用数学 |
| `state.admission` | 下单意图解析、准入校验和活跃订单查询协议 | 读取状态并返回结果，不拥有业务状态 |
| `state` | `TradingCoreState`、运行时状态、Reducer、Lane 和确定性重放 | 负责调用产品规则并写入权威状态，不拥有产品公式 |

当前已经归位的关键类：

- 现货：`SpotOrderAdmission`、`SpotTradingRules`。
- 共享衍生品：`FuturesOrderAdmission`、`FuturesFillCalculator`。
- 期权：`OptionOrderAdmission`、`OptionFillCalculator`、`OptionRiskRules`、`OptionTradingRules`。

`TradingCoreReducer` 和 `RuntimeDerivativeFillCalculator` 仍是状态侧入口。它们可以把确定性状态或成交事实交给对应产品规则，但不能通过共享父类、统一策略注册或复制快照来消除产品差异。

订单入口的当前边界是：`state.admission.CoreOrderDecisionResolver` 负责把意图解析为
`ResolvedPlaceOrder`，`state.admission.RuntimeOrderAdmission` 负责运行时状态准入和冻结需求调用，
`OrderStateTransitions` 负责持久化 `TradingCoreState` 中订单、余额和预留的原子变更，
包括下单、撤单、拒单、成交后的预留释放和强平批量撤单。账户 Lane、活跃订单索引和批量编排
只实现 `AdmissionOrderIndex` 协议，不直接拥有订单或余额状态。`TriggerOrderStateTransitions`
单独拥有 `triggerOrders` 集合的创建、撤销、触发、过期和重试状态推进，不修改余额、持仓或普通订单。
`CancelAllAfterStateTransitions` 只拥有 `cancelAllAfterTimers` 的版本检查和生命周期推进，
`AlgoOrderStateTransitions` 只拥有 `algoOrders` 的创建与修订；两者都不修改普通订单、余额或持仓。
`LeverageStateTransitions` 只拥有衍生品 `leverages` 的校验和写入，读取订单与持仓执行敞口检查，
但不修改订单、余额或持仓。
`PositionStateTransitions` 负责衍生品位置模式切换和逐仓保证金调整；逐仓调保证金在一次用户状态
变更中同时更新可用/锁定余额与持仓保证金，保证资金转移和保证金变化不可分离。
`BalanceStateTransitions` 只负责直接调整用户可用余额；订单冻结/解冻、持仓保证金和 treasury
流水仍由各自的业务状态转换负责。
`MatchStateTransitions` 负责把 exchange-core 返回的一批成交事件按原顺序应用到权威订单、用户、
持仓和 treasury 状态，并校验撮合事件与订单身份一致；现货和衍生品的单笔成交资金公式分别由
`ReducerSpotSettlement`、`ReducerDerivativeSettlement` 承担。它不拥有撮合簿，也不改变 matcher
的事件顺序；`TradingCoreReducer.applyMatches` 只保留兼容入口。
`FundingStateTransitions` 只负责永续资金费的产品线校验、标记价冻结、分页游标、用户余额变更、
资金费事实输出和 treasury 资金费进度；交割/期权到期结算不复用这条流程。
`SettlementStateTransitions` 负责交割/期权结算的 TradingCoreState 投影/物化桥接和订单取消后的
lifecycle 游标推进；逐 Lane 的结算计算仍由 `RuntimeSettlementProcessor` 负责。
`RiskSnapshotQueries` 只组装风险查询视图，不写入权威状态；`RiskScanControlStateTransitions`
只更新版本化扫描控制，不修改扫描进度、风险快照或强平状态。
`InstrumentStateTransitions` 只拥有 `instruments` 的版本化配置写入；`MarkPriceStateTransitions`
只拥有标记价和风险扫描失效标记，扫描执行仍由风险扫描流程负责。
运行时对应的 `RuntimeInstrumentStateTransitions` 只拥有运行时 `instrument` 配置和维护状态的版本校验、
生命周期互斥检查及写入；`RuntimeCommandProcessor` 仅保留兼容命令入口，不再混合承载合约配置业务。
`RuntimeOrderStateTransitions` 负责运行时普通订单的创建、撤单、拒单和预留交接；触发子单、批量下单
只复用这里的普通订单写入，不把触发状态或批量编排带入该状态所有者。
`RuntimeAccountStateTransitions` 只拥有运行时可用余额和跨产品待处理划转；`RuntimeInsuranceFundStateTransitions`
只拥有 treasury 保险基金直接调整。`RuntimeRiskStateTransitions` 只拥有风险扫描控制版本和运行时扫描游标投影。
`RuntimeOrderCommitStateTransitions` 只负责订单提交元数据和终态清理，不改变订单成交、余额或持仓业务事实。
`RuntimeCancelAllAfterStateTransitions`、`RuntimeAlgoOrderStateTransitions` 和 `RuntimeTriggerOrderStateTransitions`
分别拥有撤单定时器、算法订单和触发订单的运行时生命周期；触发订单的持仓容量校验仍只读取持仓和普通订单索引。
`RiskScanExecution` 负责按 Account Lane 分批推进风险扫描，并物化风险快照和强平计划；Reducer
只保留风险扫描公开入口及标记价变更后的调用顺序。
`LiquidationExecution` 只负责撤单游标、强平执行校验，以及强平执行时的余额、持仓、手续费和
强平状态变更；保险基金决议和 ADL 仍由各自的生命周期入口负责。
`LiquidationResolution` 只负责保险覆盖的确定性校验、保险基金/亏空变更和强平状态推进；不参与
ADL 对手方持仓变更。
`AdlExecution` 只负责 ADL 目标持仓校验、对手方减仓、余额/亏空/清算损益变更和强平状态推进；
`AdlCandidateQueries` 负责只读 ADL 候选视图和确定性排序，`adlCandidates` 仍是 Reducer 的兼容查询入口。
`InsuranceFundStateTransitions` 只负责直接调整 treasury 保险基金余额；清算覆盖仍由
`LiquidationResolution` 按清算状态和确定性分配规则推进。

## 4. 第一阶段不做的事情

- 不一次性移动全部 package。
- 不批量重命名 `Manager`、`Processor`、`Coordinator`、`Context`。
- 不新增统一产品策略框架、工厂或多层转发接口。
- 不让六条产品线共用资金、仓位或风险计算。
- 不改 Kafka/Aeron topic、线协议、快照格式和 matcher 并发模型。
- 不把查询、哈希、快照物化放进 matcher 或账户 Lane 热路径。

## 5. 已完成的第一处代码边界

`ContinuousTradingClusterService` 与交易 Owner 已按线程边界分开：

1. `ContinuousTradingClusterService` 保留 Aeron `ClusteredService` 回调、会话、输入输出队列、快照发布和 owner 线程生命周期。
2. `TradingCoreOwner` 只接收已复制的不可变命令，负责日志顺序、准入、撮合完成、提交、实时读取和权威状态快照边界。
3. `TradingCoreRuntime` 继续作为交易 Owner 的组合根，不承担 Aeron 会话职责。
4. `SurprisingClusteredService` 仅作为兼容适配器保留；它不拥有业务状态，不复制命令窗口或实时队列。
5. 现货下单、成交、余额冻结、提交恢复和快照恢复均沿用原有逻辑，并由服务模块定向/全量测试覆盖。

这次拆分的理由是存在真实的线程和协议边界，不是为了缩短文件或增加抽象层。下一处边界应在本轮测试和性能验证完成后再单独选择。
