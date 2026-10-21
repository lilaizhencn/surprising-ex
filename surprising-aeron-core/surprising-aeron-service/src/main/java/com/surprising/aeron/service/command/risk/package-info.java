/**
 * 风控命令入口，按 README“标记价与风控扫描”的步骤阅读
 * {@link com.surprising.aeron.service.command.risk.RiskCommands}。
 *
 * <ol>
 *   <li>步骤 1–2：executeApplyMarkPrice 更新标记价、风险扫描状态并初始化触发扫描。</li>
 *   <li>步骤 3：executeContinueRiskScan 校验开关和预算，选择待完成扫描。</li>
 *   <li>步骤 4–7：state.risk 组织估值与计划写入；DirectCommandSlot 保存异步续步，
 *       Owner 收集完成结果，剩余预算交给触发扫描。</li>
 * </ol>
 *
 * <p>扫描按价格序号、用户修订、预算与游标继续，不能把旧估值当作当前风险。
 * 扫描生成 PLANNED 记录。执行强平须由后续复制命令进入 command.liquidation，
 * 不由价格回调直接扣减持仓。</p>
 */
package com.surprising.aeron.service.command.risk;
