package com.surprising.aeron.service.state.change;

import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;

/** 仅测试读取指定回执值，校验其与 Lane 权威对象的租约隔离。 */
public final class LaneCommitTestAccess {
    private LaneCommitTestAccess() {}
    public static boolean usersEmpty(LaneCommitDelta delta) { return delta.users.isEmpty(); }
    public static UserRuntime user(LaneCommitDelta delta, long id) { return delta.users.get(id); }
    public static ReservationRuntime reservation(LaneCommitDelta delta, long id) { return delta.reservations.get(id); }
}
