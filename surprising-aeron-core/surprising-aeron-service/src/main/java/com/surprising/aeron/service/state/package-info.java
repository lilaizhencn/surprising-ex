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
 * <p>{@code RiskSnapshotQueries} 只从风险快照、用户、持仓和行情状态组装查询视图，不写入任何
 * 权威状态；{@code RiskScanControlStateTransitions} 只更新版本化扫描控制，不修改扫描进度、风险
 * 快照或强平状态。</p>
 *
 * <p>{@code InstrumentStateTransitions} 只拥有 {@code instruments} 的版本化配置写入；
 * {@code MarkPriceStateTransitions} 只拥有标记价和风险扫描失效标记，扫描执行仍由风险扫描流程负责。</p>
 */
package com.surprising.aeron.service.state;
