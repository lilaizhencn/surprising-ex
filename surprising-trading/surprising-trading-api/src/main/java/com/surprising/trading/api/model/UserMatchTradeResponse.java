package com.surprising.trading.api.model;

import java.time.Instant;

/** A user-scoped fill without exposing the counterparty identity. */
public record UserMatchTradeResponse(
        long tradeId,
        long orderId,
        String symbol,
        OrderSide side,
        long priceTicks,
        long quantitySteps,
        long feeRatePpm,
        Instant eventTime) {
}
