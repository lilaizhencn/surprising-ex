/**
 * 强平命令的账户执行与结果确认入口，步骤对应 README“强平、保险基金与 ADL”。
 *
 * <ol>
 *   <li>步骤 2–4：MatchingCommandAdmission 与 CoreMatchingFlow 校验并完成 Matcher 分页撤单。</li>
 *   <li>步骤 5–6：{@link com.surprising.aeron.service.liquidation.command.LiquidationCommands}
 *       的 executeLiquidationRuntime 或 advanceLiquidationCancellationRuntime 是同步入口；
 *       异步执行由命令槽及提交编排持有 state.liquidation 的续步，处理最终执行或仅推进撤单游标。</li>
 *   <li>步骤 7：后续命令由 executeResolveLiquidation 交给 RuntimeLiquidationResolution；
 *       异步完成进度保存在 DirectCommandSlot。</li>
 * </ol>
 *
 * <p>此包不持有账户或账本权威状态，沿用所属 Lane 与 Ledger 的执行边界。
 * 跨分片撤单由 CrossShardCancellationCoordinator 保持原顺序；分页推进不等于强平结束。
 * 过期估值、游标冲突及已结束计划按既有规则处理，重试不能重复扣减资金或持仓。
 * 强平执行提交不等于保险补亏或 ADL 已经完成。</p>
 */
package com.surprising.aeron.service.liquidation.command;
