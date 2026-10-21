/**
 * 交易步骤 4：将 Matcher 已确定的成交和撤单事实直接交给所属账户应用，并交出完成回执。
 *
 * <ol>
 *   <li>{@link com.surprising.aeron.service.state.settlement.MatcherSettlementDispatcher}
 *       准备原有结算事件和 Lane 路由。</li>
 *   <li>{@link com.surprising.aeron.service.state.settlement.MatcherSettlementEvent}
 *       承载 Matcher 发布的事实，交给所属 Lane 串行处理。</li>
 *   <li>产品结算处理器计算余额、预留、费用、盈亏与持仓变化，财务事实交给 System Ledger。</li>
 *   <li>账户和账本完成回执供 orchestration.trade.commit 核对后，按命令顺序发布终态。</li>
 * </ol>
 *
 * <p>事件发布、单个消费者完成和事件可回收是不同条件；仍被 Matcher 或 Lane 使用的池化事件
 * 不能提前复用。现货与各衍生产品保持自己的资金公式和账户边界；Owner 不在提交阶段重复结算。</p>
 */
package com.surprising.aeron.service.state.settlement;
