/**
 * 强平执行与结果确认；步骤对应 Aeron 总 README 的“强平、保险基金与 ADL”。
 *
 * <ol>
 *   <li>步骤 1–2：state.risk 生成计划；EXECUTE_LIQUIDATION 命令经交易编排进入执行。</li>
 *   <li>步骤 3：{@link com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionCheck}
 *       校验状态、标记价、游标和所属 Lane 的持仓。</li>
 *   <li>步骤 4：CoreMatchingFlow 交给 Matcher 分页撤单；未完成时只提交进度。</li>
 *   <li>步骤 5–6：同类的 ExecutionWork 或 BatchFinancialWork 交接账户与账本，
 *       所属 Lane 更新持仓和资金，Ledger 记账，Owner 收齐完成回执后有序提交。</li>
 *   <li>步骤 7：后续 RESOLVE_LIQUIDATION 命令进入
 *       {@link com.surprising.aeron.service.liquidation.RuntimeLiquidationResolution}，
 *       依次读取索赔、处理账本分配并由源账户更新结果。</li>
 * </ol>
 *
 * <p>共享强平记录沿用 state.risk.LiquidationRuntime，系统资金仍归 state.ledger；
 * 本包只保存当前命令的执行进度。ADL 由另一个复制命令进入 state.adl，
 * 不把扫描、强平提交、保险补亏和自动减仓当作一次必然完成的调用。</p>
 */
package com.surprising.aeron.service.liquidation;
