# aeron-service 源码导航

本模块是每条产品线的交易权威节点，采用入口 → 业务服务 → 状态及外部边界的方向。启动、命令处理、查询和恢复都围绕同一个 `TradingCoreRuntime`，账户和 Matcher 保持各自所有权。

## 阅读顺序

路径相对于 `src/main/java/com/surprising/aeron/`。

| 业务步骤 | 包与关键类 |
| --- | --- |
| 节点装配与关闭 | `config`；`bootstrap.AeronCoreLifecycle`、`bootstrap.SurprisingCoreApplication` |
| Cluster 消息入口 | `controller.AeronTradingClusterService` |
| 输入推进、控制命令及有序完成 | `service.core.TradingCoreOwner`、`TradingCoreRuntime`；`support.ingress.OwnerIngressRing` |
| 下单、改单、撤单与批次 | `service.order.MatchingCommandAdmission`、`CoreMatchingFlow`、`OrderBatchExecutor`、`CommandSlot` |
| 账户准入与订单状态 | `service.order.admission`、`service.order.state`、`service.account.AccountLaneState` |
| 撮合与线程交接 | `service.matcher.DeterministicExchangeCoreAdapter`、`service.matcher.pipeline` |
| 成交结算 | `service.settlement.MatcherSettlementEvent` 及各产品结算处理器 |
| 财库、资金费及保险 | `service.treasury`、`service.funding`、`service.insurance` |
| 风控、强平及 ADL | `service.risk`、`service.liquidation`、`service.adl` |
| 条件单与算法单 | `service.trigger`、`service.algo` |
| 交割与期权生命周期 | `service.settlement.lifecycle` |
| 提交、结果和终态保留 | `service.order.commit`、`service.result`、`service.retention` |
| 查询与实时输出 | `service.query`、`service.realtime`、`support.egress` |
| 快照与恢复 | `service.snapshot`、`service.snapshot.state` |

## 命令处理

1. `AeronTradingClusterService` 接收原日志时间、位置与请求租约，交入 Owner 输入环。
2. Owner 检查产品线、命令身份和来源水位，解析为控制命令或交易命令。
3. 交易命令由账户 Lane 执行准入，Matcher 发布真实撮合事实，相关账户完成结算。
4. Owner 收齐账户结果，同步应用 Treasury、核对资金守恒并更新业务持仓索引。
5. 有序提交器按原日志顺序提交，保留终态、生成响应与实时输出，最后归还输入及响应租约。

控制命令可跨真实账户阶段保留财务作用域。保险预留尚未结束时不能验证或提交；财库前值和持仓索引撤销信息保留到提交/回滚结束。资金验证失败立即传播并保留运行时故障。系统故障与业务拒绝分别处理。

同一用户的资产与全仓状态归属于一个账户 Lane；Matcher 按标的划分。热路径不物化完整状态，完整冷状态只在查询、快照或受控恢复边界构造。

账户提交明确传入变化用户、订单、触发取消及原日志元数据，空订单集合也使用同一提交任务。Lane 收据按物理缓冲交给 Owner，保留预计算槽与租约，Owner 使用现有键迭代器和槽位读取准备好的索引事实。持仓发布直接保留已准备的确定性身份键；衍生品杠杆读取使用所属 Lane 自己的复用探针。

## 幂等与恢复

近期结果由 `CommandResultLedger` 保留；长期资金命令身份由 `FundsCommandIndex` 保存。Owner 在激活时绑定资金索引 I/O 所有权，冷导入可在启动线程完成。长期身份检查先于来源序号；资金索引故障属于系统故障。

`CoreSnapshotLifecycle` 等待真实账户和 Matcher 完成，冻结同一水位，完整审计后直接编码。捕获边界核对配对信息与完整业务哈希，资金哈希从冻结状态计算一次；Writer 只编码已捕获镜像。分片进度按完整有序编号检查，模块按数量、范围和唯一实例检查。恢复器验证格式、CRC、字节预算、产品线、业务/资金哈希和 Matcher 配对，再激活候选状态。

## 配置与资源

[application.yml](src/main/resources/application.yml) 定义启动默认值。`CoreConfiguration` 装配拓扑，`CoreRuntimeConfiguration` 绑定执行及快照预算，`CoreTransportConfiguration` 装配 Aeron 资源，`RealtimeConfiguration` 装配可选输出。

默认一个 Matcher、两个账户 Lane；恢复使用快照路由。Owner 在 Cluster 服务线程推进，没有独立账本线程。FUSED 内联交易计算；PIPELINED 使用 Matcher 和账户工作线程。关闭先停止借用命令状态的工作资源，再释放槽、索引和输入租约；异常保留首个原因和 suppressed 信息。

Matcher 管线装配后，由启动边界明确传入分片激活和 CPU 配置；关闭统一使用 `closeShards`。有序提交由 `OrderedCommitCoordinator` 收齐已有命令槽事实后推进。

算法与触发候选路由保存调度、筛选和反向移除所需字段，客户端号查重读取所属账户 Lane 的权威正文。订单、ADL、算法和触发索引在构造时装载已提交冷状态，后续跟随提交增量更新。订单分页与候选查询使用现有 Owner 索引；指标走现有编码/查询路径。

冷模型保存快照与恢复所需的完整数据及客户端索引。用户快照精确汇总预留与持仓锁定额，再核对真实冻结余额；各不可变值构造负责资产规范和非负金额。正常退休、索引撤销和失败回滚在各自业务边界执行。

## 测试与清理规则

生产代码不保留只用于测试/基准的对象复制、状态观察或故障注入接口。测试夹具、原生结果观察和故障注入放在 `src/test` 或基准源集，使用真实回滚与 Lane 操作准备事实；索引数量在工作交接完成后读取原表大小核对。财库差额测试检查真实入账结果，资金费分页测试检查已发布进度；生产结算不维护测试专用计数。已有的资金、所有权、租约、顺序和恢复约束必须通过相关功能用例验证。

旧快照版本在外层封套检查时拒绝；版本测试使用六产品线的当前快照覆盖旧版本号及下一版本号，真实恢复由当前格式的非空业务场景覆盖。

验证命令与未测范围统一记在 [当前清理记录](validation/cleanup-20261028.md)。旧轮次报告不再充当源码导航；历史证据保存在 Git 历史中。
