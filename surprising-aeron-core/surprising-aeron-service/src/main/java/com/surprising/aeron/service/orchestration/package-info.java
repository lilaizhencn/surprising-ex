/**
 * 交易核心的 Owner 编排边界。
 *
 * <p>{@code TradingOwnerLoop} 拥有交易线程，{@code ClusterServiceEgress} 拥有会话状态和响应交接；
 * {@code TradingCoreOwner} 只负责复制命令的准入、撮合完成推进和有序提交。
 * {@code OwnerCommandPipelineState} 保存命令阶段状态，{@code OwnerResponsePublisher} 负责响应编码和出口交接，
 * {@code orchestration.realtime.TradingRealtimeBoundary} 负责实时快照/盘口请求，三者不拥有订单、余额或持仓等业务事实。</p>
 *
 * <p>{@code TradingCoreQueryRouter} 负责只读查询协议路由；{@code DirectCommandSlot} 负责单条异步控制命令续步；
 * {@code CommandSlot} 只保存单条在途撮合命令状态。{@code SurprisingClusteredService} 仅是兼容旧测试和回放工具的
 * Cluster 回调适配器。业务命令处理器位于 {@code service.command.<business>}，Matcher 工作线程和账户 Lane 变更由各自包拥有。</p>
 *
 * <p>协议解码、集群线程调度、运行指标、公开快照格式和实时传输分别位于
 * {@code orchestration.ingress}、{@code orchestration.cluster}、{@code orchestration.metrics}、
 * {@code orchestration.snapshot} 和 {@code orchestration.realtime}。</p>
 */
package com.surprising.aeron.service.orchestration;
