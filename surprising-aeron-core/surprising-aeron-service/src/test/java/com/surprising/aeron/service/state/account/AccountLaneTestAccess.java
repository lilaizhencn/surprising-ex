package com.surprising.aeron.service.state.account;

import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.position.PositionRuntime;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** 测试检查存储复用与安装读取守卫；不向生产接口开放账户表。 */
public final class AccountLaneTestAccess {
    private AccountLaneTestAccess() {}
    public static LongObjectHashMap<UserRuntime> users(AccountLaneState lane) { return lane.users; }
    public static Long2ObjectHashMap<OrderRuntime> orders(AccountLaneState lane) { return lane.orders; }
    public static Long2ObjectHashMap<ReservationRuntime> reservations(AccountLaneState lane) { return lane.reservations; }
    public static LongObjectHashMap<PositionRuntime> positions(AccountLaneState lane) { return lane.positions; }
    public static LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances(AccountLaneState lane) { return lane.balances; }
    public static LongObjectHashMap<org.agrona.collections.LongHashSet> activeOrderIdsByUser(AccountLaneState lane) { return lane.activeOrderIdsByUser; }
    public static boolean rollbackOrdersEmpty(RuntimeAccountRollback rollback, int lane) { return rollback.patchOrdersBeforeByLane[lane].isEmpty(); }
}
