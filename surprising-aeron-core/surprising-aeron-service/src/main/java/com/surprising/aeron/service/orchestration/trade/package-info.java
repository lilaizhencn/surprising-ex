/**
 * 交易编排的源码阅读顺序；编号对应模块 README 的“包结构”，不新增运行阶段。
 *
 * <ol>
 *   <li>步骤 2：{@link com.surprising.aeron.service.orchestration.trade.MatchingCommandAdmission}
 *       校验命令并派发所属账户的准入。</li>
 *   <li>步骤 3：{@link com.surprising.aeron.service.orchestration.trade.CoreMatchingFlow}
 *       登记在途命令并交接 Matcher；
 *       {@link com.surprising.aeron.service.orchestration.trade.MatchingPipelineProgress} 推进可继续工作。</li>
 *   <li>步骤 4：state.settlement 将撮合事实交给账户所有者应用。</li>
 *   <li>步骤 5：trade.commit 收集结算和账本完成，按命令顺序提交终态。</li>
 * </ol>
 *
 * <p>批量入口为 {@link com.surprising.aeron.service.orchestration.trade.OrderBatchExecutor}，
 * 复用上述业务边界。CommandSlot、PendingMatchingRing、ClusterCommandWindow 和
 * DirectCommandSlot 等是跨业务使用的在途设施，不属于单一步骤。
 * 包内访问保留槽位与生命周期约束；不因流程图拆包而扩大可见性。</p>
 */
package com.surprising.aeron.service.orchestration.trade;
