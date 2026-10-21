/**
 * 交易步骤 2：订单规则决策、所属账户的资金和持仓检查，以及准入交接。
 *
 * <ol>
 *   <li>{@link com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver}
 *       结合币对、标记价和费率，把下单意图解析成确定的订单参数。</li>
 *   <li>{@link com.surprising.aeron.service.state.admission.PlaceAdmissionEvent}
 *       交接准入所需数据；所属账户使用
 *       {@link com.surprising.aeron.service.state.admission.RuntimeOrderAdmission}
 *       检查余额、保证金、客户订单身份和平仓容量。</li>
 *   <li>订单状态入口创建订单和预留，返回准入完成事实；批量与改单沿用各自准入事件。</li>
 * </ol>
 *
 * <p>前后步骤在 orchestration.trade：MatchingCommandAdmission 组织准入，CoreMatchingFlow
 * 接入 Matcher。准入完成只允许进入撮合；资金结算、终态提交不在本包重复执行。
 * FUSED 内联执行，PIPELINED 使用原 Account Lane 交接，保持相同账户所有权。</p>
 */
package com.surprising.aeron.service.state.admission;
