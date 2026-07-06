package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;

public record LiquidationFeeSettledEvent(
        long eventId,
        long tradeId,
        long orderId,
        long liquidationOrderId,
        long candidateId,
        long userId,
        String symbol,
        MarginMode marginMode,
        String accountType,
        String asset,
        long amountUnits,
        long feeRatePpm,
        Instant eventTime,
        String traceId) {

    public LiquidationFeeSettledEvent {
        marginMode = MarginMode.defaultIfNull(marginMode);
        accountType = accountType == null || accountType.isBlank()
                ? "USDT_PERPETUAL"
                : accountType.trim().toUpperCase();
    }

    public LiquidationFeeSettledEvent(long eventId,
                                      long tradeId,
                                      long orderId,
                                      long liquidationOrderId,
                                      long candidateId,
                                      long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      String asset,
                                      long amountUnits,
                                      long feeRatePpm,
                                      Instant eventTime,
                                      String traceId) {
        this(eventId, tradeId, orderId, liquidationOrderId, candidateId, userId, symbol, marginMode,
                "USDT_PERPETUAL", asset, amountUnits, feeRatePpm, eventTime, traceId);
    }
}
