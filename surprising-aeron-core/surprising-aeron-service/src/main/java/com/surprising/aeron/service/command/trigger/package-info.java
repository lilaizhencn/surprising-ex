/**
 * 触发单、止盈止损与 OCO 的阅读顺序，入口为
 * {@link com.surprising.aeron.service.command.trigger.TriggerOrderCommands}。
 *
 * <ol>
 *   <li>executePlaceTriggerOrder / executePlaceTriggerOcoPair 登记条件，
 *       state.order 的状态入口写入权威记录，TriggerOrderIndex 提供候选路由。</li>
 *   <li>标记价命令初始化扫描，后续风险续扫中的剩余预算推进 PendingTriggerScan，
 *       分别处理触发、过期和追踪更新。</li>
 *   <li>TriggerExecutionContinuation 再次校验状态和价格序号，处理 OCO，
 *       在所属账户完成子订单准入和冻结。</li>
 *   <li>MatchingCommandAdmission.appendQueuedMatching 登记子订单，接回交易步骤 3–6：
 *       Matcher、账户结算、有序提交和输出。</li>
 * </ol>
 *
 * <p>条件记录和子订单有各自生命周期；条件成立不代表成交。重试、失效、OCO 和完成状态
 * 沿用既有命令及 Lane 边界，不另建一套撮合或资金状态。</p>
 */
package com.surprising.aeron.service.command.trigger;
