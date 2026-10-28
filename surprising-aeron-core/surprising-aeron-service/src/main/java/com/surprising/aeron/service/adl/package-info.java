/**
 * 自动减仓的账户与账本执行；入口为 {@link com.surprising.aeron.service.core.command.DirectCommandDispatcher}，
 * 续步为 {@link com.surprising.aeron.service.adl.RuntimeAdlExecution.AdlWork}。
 *
 * <ol>
 *   <li>步骤 1：读取源强平记录，校验阶段、合约、标记价与命令输入。</li>
 *   <li>步骤 2：目标账户所属 Lane 校验持仓版本，执行减仓、盈亏和余额变化。</li>
 *   <li>步骤 3：源账户所属 Lane 扣减强平亏空；同 Lane 时沿用同一次账户回调。</li>
 *   <li>步骤 4：System Ledger 应用财务差额，Owner 等待账户与账本完成后提交。</li>
 * </ol>
 *
 * <p>源强平沿用 state.risk.LiquidationRuntime，不保存第二份账户或资金状态。
 * 本包处理独立的 EXECUTE_ADL 命令；强平结果确认在 state.liquidation，
 * 不因存在亏空就自行跳过外部选择和命令校验。</p>
 */
package com.surprising.aeron.service.adl;
