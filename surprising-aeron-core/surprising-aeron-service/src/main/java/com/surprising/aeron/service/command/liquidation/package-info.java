/**
 * 强平链路的阅读顺序；风控生成计划后，执行由新的复制命令推进。
 *
 * <ol>
 *   <li>MatchingCommandAdmission / CoreMatchingFlow 接收 EXECUTE_LIQUIDATION 或批量命令，
 *       检查计划状态、价格序号、价格、游标及持仓。</li>
 *   <li>Matcher 分页撤销受影响订单，跨分片时由 CrossShardCancellationCoordinator
 *       保持原顺序；未完成时只提交撤单进度，等待后续命令。</li>
 *   <li>{@link com.surprising.aeron.service.command.liquidation.LiquidationCommands}
 *       将完成撤单的执行交给 state.risk.RuntimeDerivativeLiquidationProcessor，
 *       在所属账户处理持仓与资金，收集账本完成后提交。</li>
 *   <li>后续 RESOLVE_LIQUIDATION 经 RuntimeLiquidationResolution 确认结果；
 *       保险基金和 ADL 按业务需要各自提交独立命令。</li>
 * </ol>
 *
 * <p>分页推进不等于强平结束。过期估值、游标冲突和已结束计划仍按既有规则处理，
 * 不能因请求重试重复扣减资金或持仓；公共风险状态留在 state.risk，不复制进本包。</p>
 */
package com.surprising.aeron.service.command.liquidation;
