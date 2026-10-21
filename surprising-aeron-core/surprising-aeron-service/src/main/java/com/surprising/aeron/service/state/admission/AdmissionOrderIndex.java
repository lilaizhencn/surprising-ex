package com.surprising.aeron.service.state.admission;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;

/**
 * 订单准入需要的挂单汇总读取边界。实现可以复用所属线程的汇总对象，调用方只在当前准入步骤使用，不把它当作完整订单表或跨线程快照。
 */
public interface AdmissionOrderIndex {
    /**
     * 按用户、币对、持仓腿和方向取得准入用数量汇总，并统计另一保证金模式的冲突挂单数。
     * @param userId 需要检查或登记准入的用户编号
     * @param instrumentId 准入订单所属的永久币对编号文本
     * @param positionSide 净持仓或双向持仓腿，用来隔离不同持仓身份
     * @param side 当前订单方向，数量汇总按同向挂单计算
     * @param conflictingMarginMode 与当前订单相反的保证金模式，用来统计切换冲突
     */
    AdmissionSummary inspect(long userId, String instrumentId, CorePositionSide positionSide,
                             CoreOrderSide side, CoreMarginMode conflictingMarginMode);

    /**
     * 通知当前准入视图一笔订单已获准，供需要累计批内影响的实现更新；默认实现不额外维护状态。
     * @param userId 需要检查或登记准入的用户编号
     * @param order 本次已经通过准入的订单参数，批内视图可据此累计影响
     */
    default void admitted(long userId, ResolvedPlaceOrder order) {
    }
}
