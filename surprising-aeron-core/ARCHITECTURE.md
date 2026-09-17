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
            -> SurprisingClusteredService
                -> TradingCoreRuntime
                    -> command / matching / account-lane / snapshot state
        -> output queue
        -> Aeron egress
```

关键边界：

- `SurprisingClusterNode` 只负责节点启动、Aeron/Archive/Consensus 配置和进程级错误处理。
- `ContinuousTradingClusterService` 负责 Aeron 回调、输入输出队列、会话出口和 owner 线程生命周期。
- `SurprisingClusteredService` 当前同时实现 `ClusteredService` 回调和 owner 侧命令推进，是当前最明显的职责重叠点。
- `TradingCoreRuntime` 当前同时承载命令路由、撮合流程、提交推进、查询、快照和大量状态门面。

## 2. 现货下单链路

第一阶段只跟踪现货单，不同时改动永续、交割和期权。

```text
CoreMessage / PlaceOrderCommand
    -> ContinuousTradingClusterService.onSessionMessage
    -> owner queue
    -> SurprisingClusteredService.enqueueCommittedCommand
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

## 4. 第一阶段不做的事情

- 不一次性移动全部 package。
- 不批量重命名 `Manager`、`Processor`、`Coordinator`、`Context`。
- 不新增统一产品策略框架、工厂或多层转发接口。
- 不让六条产品线共用资金、仓位或风险计算。
- 不改 Kafka/Aeron topic、线协议、快照格式和 matcher 并发模型。
- 不把查询、哈希、快照物化放进 matcher 或账户 Lane 热路径。

## 5. 第一处代码边界候选

先处理 `ContinuousTradingClusterService` 与 `SurprisingClusteredService` 的职责重叠：

1. `ContinuousTradingClusterService` 保留 Aeron `ClusteredService` 回调、会话和跨线程队列。
2. owner 侧处理器只保留日志命令队列、owner 推进和响应终态交接。
3. `TradingCoreRuntime` 继续作为交易 owner 的组合根，但不承担 Aeron 会话职责。
4. 现货下单行为和状态写入先保持不变，用现有服务测试、现货成交测试、余额冻结核对和快照恢复测试验证。

这个边界有明确的线程和状态所有权，因此值得拆分；它不是为了缩短文件或增加抽象层。
