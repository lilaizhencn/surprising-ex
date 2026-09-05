package com.surprising.aeron.service.matching;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;

public final class MatcherEventFixtures {
    private MatcherEventFixtures() {
    }

    public static MatcherEvent trade(long makerOrderId, long makerUserId, long price, long size,
                                     boolean activeCompleted, boolean makerCompleted) {
        return new MatcherEvent(MatcherEventType.TRADE, 0, activeCompleted, makerOrderId,
                makerUserId, makerCompleted, price, size, 0);
    }
}
