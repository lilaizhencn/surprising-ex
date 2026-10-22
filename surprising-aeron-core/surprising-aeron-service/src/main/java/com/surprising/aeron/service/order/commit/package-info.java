/**
 * 交易步骤 5：核验 Matcher 证据，收集账户结算和账本完成，按命令顺序提交终态与水位。
 *
 * <p>账户资金计算属于 state.settlement / state.account，不能在此重复执行。
 * 完成结果交给 orchestration.result、realtime 和 egress；队列提交不代表终态。</p>
 */
package com.surprising.aeron.service.order.commit;
