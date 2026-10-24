# 第五十七轮：无调用旧入口与测试成交分支审计

基于 `59a647a7c96126dac698cc7f3381616380ed0a52`，新增确认 A182–A188 七组；此前 A181 资金费续页的当前行情约束仍未清理。本轮只分析并记录，业务源码未改，未运行编译、测试或服务。上一轮六产品线实测成绩见[运行验收](six-product-runtime-20261024.md)，不能替代未来实施这些删除后的验证。

本轮新增范围：**25 个无调用或仅被无调用入口引用的方法/访问器、11 个只服务测试的成交方法、1 个无消费的公开摘要字段**。其中 11 个成交方法应先迁移测试用途，不能直接删掉金融断言或把预期结果改为同一生产方法的输出。

| 编号 | 业务范围 | 当前确认 | 建议处理 |
| --- | --- | --- | --- |
| A182 | 命令响应 | CommandResultBuilder 的两个旧多订单物化重载、CommandResultLedger.size() 共3项无调用 | 删除旧重载和无消费的 Lombok getter；保留实际命令槽版本、真实 size 计数和幂等保留限制 |
| A183 | 账户运行时与回滚 | 12个旧入口及2个只供这些入口使用的下游方法，共14项 | 成组删除；保留实际 Lane 写入、回滚前值、原语索引和发布通道 |
| A184 | Core、触发和到期编排 | 5个旧入口无存活调用方 | 删除便捷重载或旧单次快照包装，保留当前异步快照/命令续步 |
| A185 | 持仓身份 | 3个旧字符串查询/准备重载无调用 | 删除列明重载，保留确定性身份、反向核对及实际使用的字符串入口 |
| A186 | 现货成交 | applyRuntime及4个专用辅助方法只服务测试 | 测试用途移出生产源码；实际 applyLane、单笔/累加结算、终态解冻与费用继续保留 |
| A187 | 衍生品成交 | applyRuntime及5个专用辅助方法只服务测试 | 同步迁移测试夹具和断言；保留实际 Lane 交易、持仓准备和资金公式 |
| A188 | 快照检查摘要 | CoreSnapshotManifest.globalFundsHash只有复制、构造检查和自身访问器，没有消费方 | 删除公开摘要副本及其无意义搬运；真正快照头的资金哈希及恢复核对必须保留 |

A183的12个无调用旧入口均位于 TradingAccountRuntime：

| 方法 | 具体冗余 |
| --- | --- |
| stageLaneMutation(long, long[]) | 已无调用的同步数组入口；异步派发和实际 Iterable 入口另有用途 |
| dispatchCancel(5个long) | 无调用的省略身份表重载；实际6参入口保留 |
| balancesForUser(long) | 无调用的 Owner 包装；显式 Lane 版本仍使用 |
| leverageKeysForUser(long) | 无调用的 Owner 包装；显式 Lane 版本仍使用 |
| positionKeysForUserAndSymbol(long, int) | 无人读取的装箱、排序、只读集合副本；真实原语持仓索引读取保留 |
| hasActiveLiquidationConflict(long, int, long) | 无调用的旧全 Lane 冲突扫描入口 |
| removeBalance(long, int) | 无业务或测试调用的余额删除流程 |
| updateReservationInLane(省略publish的4参版本) | 无调用的默认发布重载；实际带 publish 版本保留 |
| removeMarkPrice(int) | 无调用的行情删除及变化登记入口 |
| removeRiskScan(int) | 无调用的风险扫描删除及变化登记入口 |
| changedPositions() | 无消费的变化键集合物化；真实原语迭代和变化流保留 |
| currentPatchOrderBefore(long) | 无调用的订单前值转发入口 |

连同无调用入口失去最后用途的两项是 TradingAccountRuntime.hasLiquidationConflict(long,int,long,boolean) 和 RuntimeAccountRollback.currentOrderBefore(long)。前者只被 hasActiveLiquidationConflict 调用，后者只被 currentPatchOrderBefore 调用。**实际订单回滚记录、capturedOrderBefore、恢复原权威引用以及 currentPositionBefore 的独立消费者不在删除范围。**

A184包含 TradingCoreRuntime.isMatchingPending(UUID)、只被该入口调用的 isMatchingPending(long,long)、无调用的 captureSnapshot(long,long,long)、TriggerOrderCommands.executeTriggerOrder 的省略 cancelOco 重载，以及 InstrumentSettlementCommands.beginAsyncSettlement 的省略 reuse 重载。SnapshotNotReadyException仍被 CoreSnapshotLifecycle 的实际快照准备/收集路径使用，不能随旧 captureSnapshot 包装一起删除。

A185只包含 RuntimeIdentityRegistry.findPositionKeyValueInLane(AccountLaneState,long,String)、findPositionKeyValue(long,String)、preparedPositionKey(AccountLaneState,long,String,CorePositionSide)。其他同名重载有真实调用，不能整组按名称删除；持仓的用户、标的、方向和 Lane 核对继续保留。

A186的5项为现货 applyRuntime、无显式 Lane 的 applyFill和releaseTerminalReservation、validateMatches及按 TradingAccountRuntime 读单的 requireOpen。其唯一外部根调用位于 RuntimeTestStateTransitions.applySpotMatches。A187的6项为衍生品 applyRuntime、validateAndPrepare、validateMatches、无显式 Lane 的applyFill、按TradingAccountRuntime读单的requireOpen和terminal。外部根调用均在 RuntimeTestStateTransitions、RuntimeDerivativeMatchFixture 或 RuntimeDerivativeMatchProcessorTest。在线成交根入口来自 MatcherSettlementEvent.applyPlan，走 applyLane 及实际单笔/累加处理。共享的真实账户计算和结算方法不能按“与旧入口同名”误删。

A188仅针对公开检查结果中的副本：SectionedCoreSnapshotParser.Components.manifest把内部头部资金哈希再次传入 CoreSnapshotManifest；现有 SnapshotInspectMain 只读取产品、版本、快照号、命令数、业务哈希和CRC，其他消费者也不读 globalFundsHash。内部 HeaderManifest.globalFundsHash 仍在 SectionedCoreSnapshotValidation.validatePairing 与 FundsStateHash.compute(tradingState) 比较，这是实际资金恢复校验，必须继续落盘和核对。本项不意味着快照中的资金哈希无用。

A181复核：RuntimePerpetualFundingProcessor.prepare先无条件取当前 MarkPriceRuntime并拒绝缺价，后续却在已有 FundingProgressRuntime 时只用进度里的固定 markPriceTicks。可收窄为只有首轮要求当前行情；首轮缺价的拒绝及其优先级、结算编号、费率/游标和配置身份检查继续保留。该项为上轮已有发现，本轮不重复计新增。

核对方法：使用健康的 CodeGraph CLI索引定位业务入口和调用方，再检查具体源码；对可用的4016份当前classfile（均为major71）解析 owner、成员名、描述符、字段读写和调用指令，按签名区分重载，并检查常量池中可能来自方法引用的引用。25项无用途方法没有清理集合之外的生产、工具、基准或测试调用；11项测试分支只有测试外部根调用。源码摘要和逐方法调用证据记录在[审计记录](command-data-audit-20261023.json)的fifty_seventh_analysis_round，静态核对不是本轮重新构建或测试成绩。

本轮排除的误判包括：JFR撤单前缀失败事件的9个字段由录制器读取；缓存行填充承担布局用途；ReconciledOrder及批撤记录的组件参与record相等性核对；MatchingResult和订单源接口经动态派发消费实际字段；submittedAtEpochMillis仍被触发重试、撮合输入和离线回放读取。不能因普通Java调用图上读端少就删除。当前实例字段的纯读写扫描没有增加“只写不读的业务字段”确认项。

建议先清理A182–A185的25项旧入口，再处理A181和A188，最后迁移A186–A187的测试专用成交路径。实施时按实际影响编译并运行相关回归；金融分支迁移须保留精确余额/手续费/持仓/冻结断言和恢复覆盖，公开快照摘要调整须同步tools。A11查询复制候选、4项既有保留边界及其他尚未逐项审查的类继续保留，全量审计未结束。

本轮没有新增可直接删除的 Cluster 持久化字段确认项；A188是公开检查摘要的内存副本。其余命令、运行时与持久化字段仍需按实际消费者继续核对。

本轮新增确认后累计：175项已解决、8项确认待清理（A181–A188）、1项候选、4项保留。仅更新本轮分析文档和审计记录，临时扫描脚本及完整classfile清单在摘要归档后删除。
