package com.surprising.liquidation.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record LiquidationOrderResponse(
        long liquidationOrderId,
        long candidateId,
        long orderId,
        long userId,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        OrderSide side,
        long quantitySteps,
        long bankruptcyPriceTicks,
        long takeoverPriceTicks,
        long liquidationFeeRatePpm,
        long liquidationFeeUnits,
        LiquidationOrderStatus status,
        String reason,
        Instant createdAt) {

    public LiquidationOrderResponse {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public LiquidationOrderResponse(long liquidationOrderId,
                                    long candidateId,
                                    long orderId,
                                    long userId,
                                    String symbol,
                                    OrderSide side,
                                    long quantitySteps,
                                    LiquidationOrderStatus status,
                                    String reason,
                                    Instant createdAt) {
        this(liquidationOrderId, candidateId, orderId, userId, symbol, MarginMode.CROSS, PositionSide.NET, side, quantitySteps,
                0L, 0L, 0L, 0L, status, reason, createdAt);
    }

    public LiquidationOrderResponse(long liquidationOrderId,
                                    long candidateId,
                                    long orderId,
                                    long userId,
                                    String symbol,
                                    MarginMode marginMode,
                                    OrderSide side,
                                    long quantitySteps,
                                    LiquidationOrderStatus status,
                                    String reason,
                                    Instant createdAt) {
        this(liquidationOrderId, candidateId, orderId, userId, symbol, marginMode, PositionSide.NET, side, quantitySteps,
                0L, 0L, 0L, 0L, status, reason, createdAt);
    }

    public LiquidationOrderResponse(long liquidationOrderId,
                                    long candidateId,
                                    long orderId,
                                    long userId,
                                    String symbol,
                                    MarginMode marginMode,
                                    OrderSide side,
                                    long quantitySteps,
                                    long bankruptcyPriceTicks,
                                    long takeoverPriceTicks,
                                    long liquidationFeeRatePpm,
                                    long liquidationFeeUnits,
                                    LiquidationOrderStatus status,
                                    String reason,
                                    Instant createdAt) {
        this(liquidationOrderId, candidateId, orderId, userId, symbol, marginMode, PositionSide.NET, side,
                quantitySteps, bankruptcyPriceTicks, takeoverPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits,
                status, reason, createdAt);
    }

    public LiquidationOrderResponse(long liquidationOrderId,
                                    long candidateId,
                                    long orderId,
                                    long userId,
                                    String symbol,
                                    MarginMode marginMode,
                                    OrderSide side,
                                    long quantitySteps,
                                    long bankruptcyPriceTicks,
                                    long takeoverPriceTicks,
                                    long liquidationFeeUnits,
                                    LiquidationOrderStatus status,
                                    String reason,
                                    Instant createdAt) {
        this(liquidationOrderId, candidateId, orderId, userId, symbol, marginMode, PositionSide.NET, side, quantitySteps,
                bankruptcyPriceTicks, takeoverPriceTicks, 0L, liquidationFeeUnits, status, reason, createdAt);
    }
}
