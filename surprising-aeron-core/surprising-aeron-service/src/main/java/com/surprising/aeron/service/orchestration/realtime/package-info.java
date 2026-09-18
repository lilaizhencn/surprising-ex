/**
 * 交易实时读取和实时传输边界。
 *
 * <p>这里处理已提交状态的用户快照、盘口读取、Leader 角色和 Aeron 实时出口；不拥有订单、余额或持仓事实，
 * 也不参与集群快照的持久化与恢复。</p>
 */
package com.surprising.aeron.service.orchestration.realtime;
