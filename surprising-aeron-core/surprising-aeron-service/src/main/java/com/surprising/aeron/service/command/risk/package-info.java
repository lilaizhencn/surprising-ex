/**
 * 风控链路的阅读顺序；此处编号属于风控流程，不是下单步骤。
 *
 * <ol>
 *   <li>{@link com.surprising.aeron.service.command.risk.RiskCommands}
 *       的 executeApplyMarkPrice 更新标记价和扫描状态，同时初始化触发扫描。</li>
 *   <li>后续 CONTINUE_RISK_SCAN 进入 RiskCommands.executeContinueRiskScan，检查开关与预算。</li>
 *   <li>state.risk.RiskScanCoordinator 通过账本取得持仓路由，RiskLaneProcessor
 *       在所属账户 Lane 估值；Owner 按确定顺序分配强平编号，再由 Lane 写入 PLANNED 计划。</li>
 *   <li>提交扫描进度，剩余预算用于 command.trigger 的条件单续扫。</li>
 * </ol>
 *
 * <p>扫描有价格序号、用户修订、预算和游标，不能把旧估值当作当前风险。
 * 生成计划不等于已经平仓；执行与确认由后续强平命令负责。公共状态计算仍在 state.risk，
 * 本包负责命令入口和既有异步续接，完整图见本模块 README。</p>
 */
package com.surprising.aeron.service.command.risk;
