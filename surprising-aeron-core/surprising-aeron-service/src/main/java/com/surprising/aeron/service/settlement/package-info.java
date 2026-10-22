/**
 * 交易步骤 4：将 Matcher 已确定的成交和撤单事实直接交给所属账户应用，并交出完成回执。
 *
 * <ol>
 *   <li>4.1：Owner 通过
 *       {@link com.surprising.aeron.service.settlement.MatcherSettlementDispatcher}
 *       的 prepareDirect 系列方法准备事件、命令输入和初始路由。</li>
 *   <li>4.2：Matcher 通过
 *       {@link com.surprising.aeron.service.settlement.MatcherSettlementEvent}
 *       和 {@link com.surprising.aeron.service.settlement.MatcherSettlementPlan}
 *       整理成交、已接受的撤单和实际参与账户。</li>
 *   <li>4.3：Event.notifyDirectPublication 经 Dispatcher.publishMatcherOwned
 *       直接投递各 Lane 的分片 SPSC 队列；FUSED 内联执行。</li>
 *   <li>4.4：Event.execute 选择 RuntimeSpotMatchProcessor 或
 *       RuntimeDerivativeMatchProcessor，衍生品金额由 RuntimeDerivativeFillCalculator
 *       按产品计算；MatcherSettlementChanges 整理终态、修订及资金增量，Lane 交回完成位。</li>
 *   <li>4.5：Event.submitTreasurySettlement 登记财务槽，账户完成后核对资金并应用系统差额。</li>
 *   <li>4.6：Owner 等发布、账户和财务工作完成，经 TradingAccountRuntime.collectMatcherSettlement
 *       收集变化，交给 orchestration.trade.commit 按命令顺序发布终态。</li>
 * </ol>
 *
 * <p>事件发布、单个消费者完成和事件可回收是不同条件；仍被 Matcher 或 Lane 使用的池化事件
 * 不能提前复用。现货与各衍生产品保持自己的资金公式和账户边界；Owner 不在提交阶段重复结算。</p>
 *
 * <p>上述编号对应 service README 的下单步骤 4，不要求类按文件名排序，也不新增运行阶段。
 * readyForLane 只表示事实已发布，ready 还等待发布方交出租约，complete 再核对账户与已登记财务回执。</p>
 */
package com.surprising.aeron.service.settlement;
