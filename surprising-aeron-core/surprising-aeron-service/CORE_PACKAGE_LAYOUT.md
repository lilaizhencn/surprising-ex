# Core package boundaries

Core now has explicit responsibility packages. 所有原 `service.execution` 的 Owner 组装、提交、快照和集群入口现已归入 `service.orchestration`；`service.execution` 不再承载生产代码。新代码应使用下面的职责包。

| 包 | 责任 | 已归位的核心类型 |
|---|---|---|
| `service.command` | 命令解码、不可变命令值对象、命令上下文 | `DecodedMatchingCommand`, `ResolvedMatchingAdmission`, `OrderBatchKind`, `ImmutableLongArrayList`, `CommandOwnerContext` |
| `service.command.instrument` | 币对配置和维护命令 | `InstrumentConfigurationCommands` |
| `service.matcher` | Matcher worker、SPSC 队列和完成信号 | `MatcherCommandPipeline`, `MatcherPipelineGroup` |
| `service.lane` | Account Lane worker 和 Lane 调度原语 | `SettlementLaneWorker` |
| `service.orchestration` | Owner 编排、集群入口、准入、批处理、有序提交、快照和 Owner 休眠 | 原 `service.execution` 的 Owner 组件及 `ClusterCommandWindow`, `PendingClusterIngress`, `OwnerIdleStrategy` |
| `service.business.*` | 按现货、线性/反向永续和交割、期权划分业务规则 | `ProductTradingRules` 与注册表位于 `business`；现货、线性永续/交割、反向永续/交割和期权规则实现已分别迁入对应业务包，仍与共享状态计算保持单向依赖 |

`LaneCommandContextRing`、`PendingMatching`、`OrderedCommitCoordinator` 和 `TradingCoreRuntime` 仍处于 Owner 编排包内的强耦合区域：它们已经与 Matcher、Lane worker 和命令值对象隔离，但内部共享在途状态、提交缓冲和 Owner 生命周期；下一阶段先抽取 Lane 事件协议，再拆分状态环和有序提交器。

命令处理器通过 `CommandOwnerContext` 获取最小能力，不能直接持有完整 `TradingCoreRuntime`。新命令处理器应放在 `service.command` 或其业务子包中；撮合和 Lane 线程不得反向依赖命令处理器。
