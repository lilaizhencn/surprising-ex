/**
 * 触发单、止盈止损与 OCO 的命令和续扫入口，步骤对应 README 同名流程图。
 *
 * <ol>
 *   <li>步骤 1：{@link com.surprising.aeron.service.command.trigger.TriggerOrderCommands}
 *       登记条件，state.order 保存触发状态，TriggerOrderIndex 提供候选索引。</li>
 *   <li>步骤 2–3：标记价及后续风险续扫按预算推进 PendingTriggerScan，检查触发、过期和追踪条件。</li>
 *   <li>步骤 4：TriggerExecutionContinuation 校验价格序号及状态，处理 OCO、claim 和子订单准入。</li>
 *   <li>步骤 5–7：appendQueuedMatching 把子订单接回共用撮合、账户结算及有序提交，
 *       最后发布触发完成或失败结果。</li>
 * </ol>
 *
 * <p>条件记录与子订单分别管理生命周期，重试、失效、OCO 和完成沿用既有命令及 Lane 边界。
 * 条件成立仍需重新校验资金和冻结，
 * 不能把已登记条件或触发扫描完成当作子订单成交。</p>
 */
package com.surprising.aeron.service.command.trigger;
