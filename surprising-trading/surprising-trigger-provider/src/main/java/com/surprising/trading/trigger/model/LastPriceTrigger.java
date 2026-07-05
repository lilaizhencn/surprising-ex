package com.surprising.trading.trigger.model;

import java.time.Instant;

public record LastPriceTrigger(
        String symbol,
        long sequence,
        long priceTicks,
        Instant eventTime) {
}
