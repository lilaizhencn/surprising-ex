/**
 * 节点启动步骤 3–5：配置 Aeron 资源，启动 Driver/Archive/Consensus 和 Service Container，等待关闭。
 *
 * <p>从 {@link com.surprising.aeron.service.SurprisingCoreApplication} 进入；配置绑定留在
 * {@link com.surprising.aeron.service.config.CoreConfiguration}。交易线程的恢复与激活由
 * {@link com.surprising.aeron.service.orchestration.TradingOwnerLoop} 承担。
 * 完整启动时序和配置来源见 surprising-aeron-core/README.md。</p>
 */
package com.surprising.aeron.service.bootstrap;
