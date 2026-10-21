package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Controller已完成格式和范围校验的筛选；市场精度与VIP资格仍由业务Service判断。 */
public record C2cOfferFilter(UUID marketId, Side side, BigDecimal amount, String method, Long merchantId,
                              boolean vipOnly, int minCompleted, int minCompletionRate, int maxPaymentSeconds,
                              Sort sort, int limit, int offset) {
    public enum Side { BUY, SELL }
    public enum Sort { PRICE, COMPLETED }
}
