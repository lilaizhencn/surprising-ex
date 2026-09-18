/**
 * 交易核心状态边界。
 *
 * <p>本包当前仍处于整理阶段，包含三类不同性质的状态：权威业务状态、命令执行过程状态和
 * 从权威状态派生的索引/查询状态。新增代码必须明确属于哪一类，并说明唯一所有者、生命
 * 周期和写入线程；不能把 Aeron 会话、响应编码或产品线流程继续放入本包。</p>
 *
 * <p>后续重构会优先按状态所有权拆分，而不是按文件大小机械拆类。余额、冻结、订单、持仓
 * 等业务事实必须保持单一权威来源，索引和快照视图不能反向成为业务状态。</p>
 *
 * <p>{@code state.admission} 是订单意图解析、准入校验以及活跃订单查询协议的边界；它可以
 * 读取状态并返回准入结果，但不拥有余额、冻结、订单或持仓。</p>
 *
 * <p>{@code OrderStateTransitions} 是持久化订单生命周期的状态所有者：它原子更新订单、余额、
 * 预留和客户端订单索引；{@code TradingCoreReducer} 只保留兼容入口及其他风险、成交、结算流程。</p>
 *
 * <p>{@code TriggerOrderStateTransitions} 只拥有 {@code triggerOrders} 集合及其状态推进，
 * 不修改余额、普通订单、持仓或成交状态。</p>
 *
 * <p>{@code CancelAllAfterStateTransitions} 只拥有 {@code cancelAllAfterTimers} 集合及其版本
 * 检查和生命周期推进；{@code AlgoOrderStateTransitions} 只拥有 {@code algoOrders} 集合的
 * 创建与修订。两者都不修改普通订单、余额或持仓。</p>
 *
 * <p>{@code LeverageStateTransitions} 只拥有衍生品 {@code leverages} 集合的校验和写入；它
 * 读取订单与持仓来阻止有敞口时修改杠杆，但不修改这些状态。</p>
 *
 * <p>{@code PositionStateTransitions} 负责衍生品位置模式切换和逐仓保证金调整；它在一次用户
 * 状态变更中同时更新余额与持仓，保证资金转移和保证金变化不可分离。</p>
 *
 * <p>{@code BalanceStateTransitions} 只负责直接调整用户可用余额；订单冻结/解冻、持仓保证金和
 * treasury 流水仍由各自的业务状态转换负责。</p>
 *
 * <p>{@code MatchStateTransitions} 负责把 exchange-core 返回的一批成交事件按原顺序应用到权威订单、
 * 用户、持仓和 treasury 状态，并校验撮合事件与订单身份一致；现货和衍生品的单笔成交资金公式分别由
 * {@code ReducerSpotSettlement}、{@code ReducerDerivativeSettlement} 承担。它不拥有撮合簿，也不改变
 * matcher 的事件顺序；{@code TradingCoreReducer.applyMatches} 只保留兼容入口。</p>
 *
 * <p>{@code FundingStateTransitions} 只负责永续资金费的产品线校验、标记价冻结、分页游标、用户余额
 * 变更、资金费事实输出和 treasury 资金费进度；交割/期权到期结算不复用这条流程。</p>
 *
 * <p>{@code SettlementStateTransitions} 负责交割/期权结算的 {@code TradingCoreState} 投影/物化桥接和订单
 * 取消后的 lifecycle 游标推进；同步结算流程由 {@code RuntimeLifecycleSettlement} 负责，
 * 异步 Lane 阶段由 {@code RuntimeLifecycleSettlementContinuation} 负责。</p>
 *
 * <p>{@code RiskSnapshotQueries} 只从风险快照、用户、持仓和行情状态组装查询视图，不写入任何
 * 权威状态；{@code RiskScanControlStateTransitions} 只更新版本化扫描控制，不修改扫描进度、风险
 * 快照或强平状态。</p>
 *
 * <p>{@code InstrumentStateTransitions} 只拥有 {@code instruments} 的版本化配置写入；
 * {@code MarkPriceStateTransitions} 只拥有标记价和风险扫描失效标记，扫描执行仍由风险扫描流程负责。</p>
 *
 * <p>{@code RuntimeInstrumentStateTransitions} 只拥有运行时 {@code instrument} 配置和维护状态的版本校验、
 * 生命周期互斥检查及写入；{@code RuntimeCommandProcessor} 仅保留兼容命令入口，不再混合承载合约配置业务。</p>
 *
 * <p>{@code RuntimeOrderStateTransitions} 负责运行时普通订单的创建、撤单、拒单和预留交接；触发子单、批量
 * 下单只复用这里的普通订单写入，不把触发状态或批量编排带入该状态所有者。</p>
 *
 * <p>{@code RuntimeAccountStateTransitions} 只拥有运行时可用余额和跨产品待处理划转；
 * {@code RuntimeInsuranceFundStateTransitions} 只拥有 treasury 保险基金直接调整；
 * {@code RuntimeRiskStateTransitions} 只拥有风险扫描控制版本和运行时扫描游标投影；
 * {@code RuntimeOrderCommitStateTransitions} 只负责订单提交元数据和终态清理。</p>
 *
 * <p>{@code RuntimeCancelAllAfterStateTransitions}、{@code RuntimeAlgoOrderStateTransitions} 和
 * {@code RuntimeTriggerOrderStateTransitions} 分别拥有撤单定时器、算法订单和触发订单的运行时生命周期；
 * 触发订单的持仓容量校验只读取持仓和普通订单索引。</p>
 *
 * <p>{@code RiskScanExecution} 负责按 Account Lane 分批推进风险扫描，并物化风险快照和强平计划；
 * {@code TradingCoreReducer} 只保留风险扫描公开入口及标记价变更后的调用顺序。</p>
 *
 * <p>{@code LiquidationExecution} 只负责撤单游标、强平执行校验，以及强平执行时的余额、持仓、
 * 手续费和强平状态变更；保险基金决议和 ADL 仍由各自的生命周期入口负责。</p>
 *
 * <p>{@code LiquidationResolution} 只负责保险覆盖的确定性校验、保险基金/亏空变更和强平状态推进；
 * 不参与 ADL 对手方持仓变更。</p>
 *
 * <p>{@code AdlExecution} 只负责 ADL 目标持仓校验、对手方减仓、余额/亏空/清算损益变更和强平状态推进；
 * {@code AdlCandidateQueries} 负责只读候选视图和确定性排序，{@code adlCandidates} 仍是 Reducer 的兼容查询入口。</p>
 *
 * <p>{@code InsuranceFundStateTransitions} 只负责直接调整 treasury 保险基金余额；清算覆盖仍由
 * {@code LiquidationResolution} 按清算状态和确定性分配规则推进。</p>
 */
package com.surprising.aeron.service.state;
