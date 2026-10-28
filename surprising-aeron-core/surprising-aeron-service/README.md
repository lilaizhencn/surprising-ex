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

## 幂等与恢复

近期结果由 `CommandResultLedger` 保留；长期资金命令身份由 `FundsCommandIndex` 保存。Owner 在激活时绑定资金索引 I/O 所有权，冷导入可在启动线程完成。长期身份检查先于来源序号；资金索引故障属于系统故障。

`CoreSnapshotLifecycle` 等待真实账户和 Matcher 完成，冻结同一水位，完整审计后直接编码。没有编码 Future 或可替换编码器。恢复器验证格式、CRC、字节预算、产品线、业务/资金哈希和 Matcher 配对，再激活候选状态。

## 配置与资源

[application.yml](src/main/resources/application.yml) 定义启动默认值。`CoreConfiguration` 装配拓扑，`CoreRuntimeConfiguration` 绑定执行及快照预算，`CoreTransportConfiguration` 装配 Aeron 资源，`RealtimeConfiguration` 装配可选输出。

默认一个 Matcher、两个账户 Lane；恢复使用快照路由。Owner 在 Cluster 服务线程推进，没有独立账本线程。FUSED 内联交易计算；PIPELINED 使用 Matcher 和账户工作线程。关闭先停止借用命令状态的工作资源，再释放槽、索引和输入租约；异常保留首个原因和 suppressed 信息。

Matcher 管线统一先装配，再由启动边界明确传入分片激活和 CPU 配置；关闭统一使用 `closeShards`，不保留构造时自动启动和末尾回调的另一套遍历。测试与基准在各自源集中读取已有命令槽事实，完成撮合直接调用 `OrderedCommitCoordinator`，Runtime 不再提供两层转发。

无调用的同步触发扫描包装、整组索引重建、财库及客户身份表清空入口已删除。运行时不再重复构造测试专用的指标视图，指标编码和真实查询入口继续使用；可选持仓身份用实际的已准备身份/原语查询检查。

只供测试构造删除事实的 Runtime 用户/订单/持仓删除入口，以及对应的无调用表操作已移出生产代码。正常订单退休和失败回滚仍在各自 Lane 路径执行；测试夹具只准备必要事实，并继续使用真实回滚前值及索引撤销。变化集合测试直接观察原缓冲，不再为测试复制集合；划转分页检查真实路由页和完成游标。

## 测试与清理规则

生产代码不保留只用于测试/基准的对象复制、状态观察或故障注入接口。测试夹具、原生结果观察和线程状态访问放在 `src/test`。已有的资金、所有权、租约、顺序和恢复约束必须通过相关功能用例验证。

验证命令与未测范围统一记在 [当前清理记录](validation/cleanup-20261028.md)。旧轮次报告不再充当源码导航；历史证据保存在 Git 历史中。
