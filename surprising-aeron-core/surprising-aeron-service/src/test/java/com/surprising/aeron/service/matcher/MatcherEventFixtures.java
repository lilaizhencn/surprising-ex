package com.surprising.aeron.service.matcher;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;

/**
 * 成交事件测试构造器，只生成一条固定类型的 TRADE 事实，不运行订单簿或账户结算。
 */
public final class MatcherEventFixtures {
    /**
     * 工具类不创建实例。
     */
    private MatcherEventFixtures() {
    }

    /**
     * 用指定 Maker 身份、成交价量及双方完成标记构造 TRADE 事件，未传入的节段和其他附加数值固定为零。
     * @param makerOrderId 被成交的挂单订单编号
     * @param makerUserId 该挂单所属用户编号
     * @param price 本条成交价
     * @param size 本条成交数量
     * @param activeCompleted 本条成交后主动订单是否完成
     * @param makerCompleted 本条成交后挂单是否完成
     */
    public static MatcherEvent trade(long makerOrderId, long makerUserId, long price, long size,
                                     boolean activeCompleted, boolean makerCompleted) {
        return new MatcherEvent(MatcherEventType.TRADE, 0, activeCompleted, makerOrderId,
                makerUserId, makerCompleted, price, size, 0);
    }
}
