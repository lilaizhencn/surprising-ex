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
        String instrumentId,
        MarginMode marginMode,
        PositionSide positionSide,
        OrderSide side,
        long quantitySteps,
        long takeoverPriceTicks,
        long liquidationFeeRatePpm,
        long liquidationFeeUnits,
        LiquidationOrderStatus status,
        String reason,
        Instant createdAt) {}
