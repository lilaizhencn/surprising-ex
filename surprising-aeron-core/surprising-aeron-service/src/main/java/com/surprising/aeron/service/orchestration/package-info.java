/**
 * 交易步骤 1：把已复制的命令交给唯一 Owner 循环，推进业务执行及完成条件。
 *
 * <ol>
 *   <li>{@link com.surprising.aeron.service.orchestration.TradingOwnerLoop} 管理线程、启动恢复、
 *       Cluster 到 Owner 的输入交接和退出。</li>
 *   <li>{@link com.surprising.aeron.service.orchestration.TradingCoreOwner} 推进输入环、
 *       未完成命令和终态响应交接，保留 Cluster 日志顺序。</li>
 *   <li>{@link com.surprising.aeron.service.orchestration.TradingCoreRuntime} 组织业务路由；
 *       撮合命令进入 trade 的步骤 2–3，其他命令进入对应 command 业务包。</li>
 * </ol>
 *
 * <p>账户、Matcher 和 System Ledger 的权威状态仍归各自执行者；Owner 使用交接与完成回执，
 * 不因为持有运行时引用就取得跨线程访问权。步骤 5 的终态由 trade.commit 提交，
 * 步骤 6 经 result、realtime 和 egress 输出；查询、快照与回放各在自己的读取边界执行。
 * 启动编号和各业务流程见本模块 README 及 Aeron 总 README。</p>
 */
package com.surprising.aeron.service.orchestration;
