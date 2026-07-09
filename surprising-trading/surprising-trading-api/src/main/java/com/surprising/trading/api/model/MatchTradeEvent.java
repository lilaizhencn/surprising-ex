package com.surprising.trading.api.model;

import java.time.Instant;

public record MatchTradeEvent(
        long tradeId,
        long commandId,
        String symbol,
        long takerOrderId,
        long takerInstrumentVersion,
        long takerUserId,
        OrderSide takerSide,
        MarginMode takerMarginMode,
        PositionSide takerPositionSide,
        long makerOrderId,
        long makerInstrumentVersion,
        long makerUserId,
        MarginMode makerMarginMode,
        PositionSide makerPositionSide,
        Long takerFeeRatePpm,
        Long makerFeeRatePpm,
        long priceTicks,
        long quantitySteps,
        boolean takerOrderCompleted,
        boolean makerOrderCompleted,
        Instant eventTime,
        String traceId) {

    public MatchTradeEvent {
        takerMarginMode = MarginMode.defaultIfNull(takerMarginMode);
        makerMarginMode = MarginMode.defaultIfNull(makerMarginMode);
        takerPositionSide = PositionSide.defaultIfNull(takerPositionSide);
        makerPositionSide = PositionSide.defaultIfNull(makerPositionSide);
        takerFeeRatePpm = requireFeeRate(takerFeeRatePpm, "takerFeeRatePpm");
        makerFeeRatePpm = requireFeeRate(makerFeeRatePpm, "makerFeeRatePpm");
    }

    public MatchTradeEvent(long tradeId,
                           long commandId,
                           String symbol,
                           long takerOrderId,
                           long takerInstrumentVersion,
                           long takerUserId,
                           OrderSide takerSide,
                           MarginMode takerMarginMode,
                           long makerOrderId,
                           long makerInstrumentVersion,
                           long makerUserId,
                           MarginMode makerMarginMode,
                           long takerFeeRatePpm,
                           long makerFeeRatePpm,
                           long priceTicks,
                           long quantitySteps,
                           boolean takerOrderCompleted,
                           boolean makerOrderCompleted,
                           Instant eventTime,
                           String traceId) {
        this(tradeId, commandId, symbol, takerOrderId, takerInstrumentVersion, takerUserId, takerSide,
                takerMarginMode, PositionSide.NET, makerOrderId, makerInstrumentVersion, makerUserId,
                makerMarginMode, PositionSide.NET, takerFeeRatePpm, makerFeeRatePpm, priceTicks, quantitySteps,
                takerOrderCompleted,
                makerOrderCompleted, eventTime, traceId);
    }

    public MatchTradeEvent(long tradeId,
                           long commandId,
                           String symbol,
                           long takerOrderId,
                           long takerInstrumentVersion,
                           long takerUserId,
                           OrderSide takerSide,
                           long makerOrderId,
                           long makerInstrumentVersion,
                           long makerUserId,
                           long takerFeeRatePpm,
                           long makerFeeRatePpm,
                           long priceTicks,
                           long quantitySteps,
                           boolean takerOrderCompleted,
                           boolean makerOrderCompleted,
                           Instant eventTime) {
        this(tradeId, commandId, symbol, takerOrderId, takerInstrumentVersion, takerUserId, takerSide, makerOrderId,
                makerInstrumentVersion, makerUserId, takerFeeRatePpm, makerFeeRatePpm, priceTicks, quantitySteps,
                takerOrderCompleted, makerOrderCompleted, eventTime, null);
    }

    public MatchTradeEvent(long tradeId,
                           long commandId,
                           String symbol,
                           long takerOrderId,
                           long takerInstrumentVersion,
                           long takerUserId,
                           OrderSide takerSide,
                           long makerOrderId,
                           long makerInstrumentVersion,
                           long makerUserId,
                           long takerFeeRatePpm,
                           long makerFeeRatePpm,
                           long priceTicks,
                           long quantitySteps,
                           boolean takerOrderCompleted,
                           boolean makerOrderCompleted,
                           Instant eventTime,
                           String traceId) {
        this(tradeId, commandId, symbol, takerOrderId, takerInstrumentVersion, takerUserId, takerSide,
                MarginMode.CROSS, PositionSide.NET, makerOrderId, makerInstrumentVersion, makerUserId,
                MarginMode.CROSS, PositionSide.NET, takerFeeRatePpm, makerFeeRatePpm, priceTicks, quantitySteps,
                takerOrderCompleted,
                makerOrderCompleted, eventTime, traceId);
    }

    private static long requireFeeRate(Long feeRatePpm, String field) {
        if (feeRatePpm == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (feeRatePpm < -1_000_000L || feeRatePpm > 1_000_000L) {
            throw new IllegalArgumentException(field + " must be in [-1000000, 1000000]");
        }
        return feeRatePpm;
    }
}
