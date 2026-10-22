package com.surprising.aeron.service.core.change;

import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;

/**
 * 仅测试读取指定提交收据的值或空标记，核对其与 Lane 权威对象的租约隔离；不把内部缓冲暴露为生产查询接口。
 */
public final class LaneCommitTestAccess {
    /**
     * 不创建辅助类实例，测试使用静态读取入口。
     */
    private LaneCommitTestAccess() {}
    /**
     * 只检查收据用户变化列是否为空，不推断其他变化列也为空。
     * @param delta 由测试受控持有、租约仍有效的 Lane 提交收据
     */
    public static boolean usersEmpty(LaneCommitDelta delta) { return delta.users.isEmpty(); }
    /**
     * 按用户编号读取本收据保存的用户命令值，不返回 Lane 活用户表。
     * @param delta 由测试受控持有、租约仍有效的 Lane 提交收据
     * @param id 需要读取的用户编号或预留订单编号，具体由方法固定
     */
    public static UserRuntime user(LaneCommitDelta delta, long id) { return delta.users.get(id); }
    /**
     * 按订单编号读取本收据预留变化值，调用方须遵守活引用冻结及回执交接阶段。
     * @param delta 由测试受控持有、租约仍有效的 Lane 提交收据
     * @param id 需要读取的用户编号或预留订单编号，具体由方法固定
     */
    public static ReservationRuntime reservation(LaneCommitDelta delta, long id) { return delta.reservations.get(id); }
}
