package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 同一到期期权的外部权利金和远期风险价格；标的指数单独保存在指数事件中。 */
public record OptionPriceReference(BigDecimal premiumPrice, BigDecimal sameExpiryForwardPrice, Instant expiryTime) {
    public OptionPriceReference {
        if (premiumPrice == null || premiumPrice.signum() <= 0 || sameExpiryForwardPrice == null
                || sameExpiryForwardPrice.signum() <= 0 || expiryTime == null)
            throw new IllegalArgumentException("positive option premium, same-expiry forward and expiry are required");
    }
}
