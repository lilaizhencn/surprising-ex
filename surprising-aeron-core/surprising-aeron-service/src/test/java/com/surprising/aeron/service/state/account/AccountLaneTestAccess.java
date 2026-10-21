package com.surprising.aeron.service.state.account;

import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.position.PositionRuntime;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * 仅供测试取得包内账户表或检查回滚缓冲，便于存储复用断言和读取守卫注入。返回的是实际内部对象，不向生产查询接口提供这些访问能力。
 */
public final class AccountLaneTestAccess {
    /**
     * 不创建辅助类实例，测试使用静态入口。
     */
    private AccountLaneTestAccess() {}
    /**
     * 返回 Lane 内实际用户表，供测试安装守卫或检查对象复用。
     * @param lane 测试已经受控取得的账户 Lane 状态
     */
    public static LongObjectHashMap<UserRuntime> users(AccountLaneState lane) { return lane.users; }
    /**
     * 返回实际订单权威表，供测试检查池化和读取边界。
     * @param lane 测试已经受控取得的账户 Lane 状态
     */
    public static Long2ObjectHashMap<OrderRuntime> orders(AccountLaneState lane) { return lane.orders; }
    /**
     * 返回实际预留表，供测试检查存在性及对象身份。
     * @param lane 测试已经受控取得的账户 Lane 状态
     */
    public static Long2ObjectHashMap<ReservationRuntime> reservations(AccountLaneState lane) { return lane.reservations; }
    /**
     * 返回实际持仓表，不提供查询快照隔离。
     * @param lane 测试已经受控取得的账户 Lane 状态
     */
    public static LongObjectHashMap<PositionRuntime> positions(AccountLaneState lane) { return lane.positions; }
    /**
     * 返回实际用户资产余额表，供测试检查所属 Lane 的访问约束。
     * @param lane 测试已经受控取得的账户 Lane 状态
     */
    public static LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances(AccountLaneState lane) { return lane.balances; }
    /**
     * 返回实际用户活跃订单成员索引，供测试断言容器生命周期。
     * @param lane 测试已经受控取得的账户 Lane 状态
     */
    public static LongObjectHashMap<org.agrona.collections.LongHashSet> activeOrderIdsByUser(AccountLaneState lane) { return lane.activeOrderIdsByUser; }
    /**
     * 只检查指定 Lane 的订单前值缓冲是否为空，不代表其他种类回滚缓冲也为空。
     * @param rollback 要检查订单前值缓冲的回滚实例
     * @param lane 账户 Lane 数组下标
     */
    public static boolean rollbackOrdersEmpty(RuntimeAccountRollback rollback, int lane) { return rollback.patchOrdersBeforeByLane[lane].isEmpty(); }
}
