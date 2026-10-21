/**
 * 风控估值与强平计划；步骤对应 Aeron 总 README 的“标记价与风控扫描”。
 *
 * <ol>
 *   <li>步骤 1–2：command.risk.RiskCommands 接收标记价，
 *       {@link com.surprising.aeron.service.state.risk.RuntimeDerivativeRiskProcessor} 更新价格和扫描进度。</li>
 *   <li>步骤 3–4：后续续扫命令校验预算，
 *       {@link com.surprising.aeron.service.state.risk.RiskScanCoordinator} 分发账户估值。</li>
 *   <li>步骤 5–6：{@link com.surprising.aeron.service.state.risk.RiskLaneProcessor}
 *       在所属 Lane 估值；Owner 按固定顺序分配编号，Lane 写入强平计划。</li>
 *   <li>步骤 7：提交扫描进度，剩余预算推进触发单扫描。</li>
 * </ol>
 *
 * <p>本包生成计划；执行与结果确认在 state.liquidation，自动减仓在 state.adl。
 * LiquidationRuntime、RiskSnapshotRuntime 和 RiskScanRuntime 是共享记录，保留在此包，
 * 不为不同命令复制状态。风险判级和可强平判定继续使用 CoreRiskPolicy。</p>
 */
package com.surprising.aeron.service.state.risk;
