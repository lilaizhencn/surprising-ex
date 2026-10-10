# 核心提交日志回放

`CommittedTradeReplay` 由独立导出进程持有核心状态，只应用 Archive 已提交的命令；不会向在线 Cluster 发送交易命令。订单和成交沿用实时捕获，资金费通过 `PerpetualFundingCommands` 的回放观察接口交出实际支付结果，形成不可变的 `CommittedFundingPage`。该对象的生命周期仅为当前完整命令，下一次 apply 清空；没有第二份资金账本或长期支付缓存。

资金费页保留产品线、日志位置、核心时间、实际命令费率、完成游标及支付明细。重复或被拒绝的命令不产生支付页。在线异步 Account Lane 路径不安装观察接口、不读取数据库，资金计算、快照格式和核心权威状态不变。查询投影在 realtime 模块持久化，SQL 成功后才能推进导出 checkpoint。

HotSpot JDK 27 下 `CommittedFundingReplayTest` 覆盖 U 本位及币本位永续的实际多空支付、分页、快照恢复和重复命令；原有 funding processor、异步 funding、订单成交回放及权利金/资金费回归一并执行。此改动供独立回放使用，不要求重启在线核心。


## Matcher 结果与账户结算交接

账户分区仍按 `LaneTopology` 隔离，当前账户状态由 Owner 串行更新。`MatcherSettlementDispatcher.dispatchOwnerControlled` 登记派发；Matcher 未发布结果时不执行账户结算，Owner 后续通过 `MatcherSettlementEvent.complete()` 推进已就绪且已登记的事件。直接调用事件的测试需要显式执行未登记的分区。

Matcher 在 `MatcherSettlementEvent.notifyDirectPublication()` 中先校验结果、登记派发、释放提交路由，再以 volatile 就绪位交给 Owner。就绪位是池化事件的所有权交接边界：此后 Matcher 只能用已捕获的 runtime 和 lane mask 唤醒 Owner，不能继续读写事件；Owner 可以立即完成结算并回收复用。`MatcherCommandPipeline.run()` 将包括最终发布在内的工作循环异常传给 Owner，避免工作线程退出后命令仍等待超时。

`MatcherSettlementPlanTest` 覆盖未就绪预派发、生产者最后访问与回收复用的顺序、发布异常传递；业务及性能验证见仓库 `PERFORMANCE_VALIDATION.md`。

## 账户状态权威与查询边界

余额、订单、预留、持仓及冷状态由所属 `AccountLaneState` 保存。清算、风险状态、算法单和触发单的 Owner `published*` 镜像已经删除；`TradingRuntimeState` 的内部读取通过 Lane 作用域访问权威数据。`LaneCommitDelta` 保留本命令的输出及变更信息，命令结束即清理，不再更新第二份长期业务 Map。业务拒绝时由所属 Lane 应用 before-image 并恢复本地索引。

普通用户余额、持仓和挂单在 `surprising.realtime.enabled=true` 时继续通过 `ValkeyUserQueries` 读取 Redis/Valkey 投影。下单资金校验和结算使用核心权威账户状态，投影用于外部读取；投影的就绪状态和导出序号由实时模块检查。

`AccountLaneWorker` 与 `SystemLedgerLane` 已提供常驻独占写线程、有界 SPSC 队列、背压和故障交接。当前 `TradingRuntimeState.startAccountLanes()` 仍只登记初始化标记，生产运行时尚未接入这些工作线程，Owner 资金写入迁移仍在实施，不能据此宣称生产账户并行或吞吐已恢复。相关定向测试共 87 项通过，包含四个分区的冷状态权威、业务拒绝回滚、触发单索引、风险查询和线程交接；按当前任务约束没有运行快照或恢复场景。

### Treasury 结算的分配与溢出边界（2026-10-11）

`TreasuryRuntime` 复用七列原语事务前值，首次写入捕获，成功提交或普通事务回滚后清空并保留容量。它直接向 `RuntimeFundsAccumulator` 追加七个子账的资金变更；`TradingRuntimeState.appendFundsDelta` 不再物化 Treasury 前后值对象或复制变更资产集合。这个缓冲只由 Treasury 所属线程访问，不是 Owner 的资金镜像。

`RuntimeTreasuryDelta.apply` 在写入前检查整笔资金的溢出。单资产结算先计算六个结果到局部变量，全部通过后写入；多资产结算先检查全部资产再应用，不增加逐命令临时对象。回归覆盖手续费、保险、负债、强平费、资金费残差、舍入残差、清算盈亏，及单资产/多资产拒绝时不发生部分落账。

`TreasurySettlementAllocationBenchmark` 单独测量写入、资金变更生成及逐资产守恒，使用 HotSpot JDK 27。修改前后 `applyAndClear` 的 JMH 对比中，1/16 资产轮转的分配分别从约 96/68 B/次降至约 0.0007/0.0011 B/次；包含七个子账与守恒校验的两组 300 万次循环，ThreadMXBean 均测得 0 字节线程分配，JFR 未捕获 Treasury 热路径分配。该结果仅覆盖 Treasury 局部路径，不能替代真实 Aeron 单节点业务吞吐或整个核心的分配验收。SystemLedgerLane 的生产接线和 Owner 退出资金写入仍需继续。

外部余额、持仓、当前挂单查询在 realtime 启用时仍走 Redis/Valkey 投影；Account Lane 的读取面向内部风控、预留、结算和显式核验。
