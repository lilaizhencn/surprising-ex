package com.surprising.aeron.protocol.export;

import java.math.BigInteger;
import java.util.List;

/** 交易活动汇总。 */
public record CommittedTradingActivity(long openOrders, long usersWithPositions, long openPositions,
                                       long pendingTriggers, long expiredPendingTriggers,
                                       List<Instrument> instruments) {
    public CommittedTradingActivity {
        instruments = instruments == null ? List.of() : List.copyOf(instruments);
    }

    /** 单个币对的挂单及非空持仓统计。数量使用任意精度整数求和，多空分别以正的绝对步数累计。 */
    public record Instrument(String instrumentId, long openOrders, BigInteger openQuantitySteps,
                             long openPositions, long users, long longPositions, long shortPositions,
                             BigInteger longQuantitySteps, BigInteger shortQuantitySteps) {}
}
