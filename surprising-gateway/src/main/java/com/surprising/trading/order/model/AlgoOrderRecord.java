package com.surprising.trading.order.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Instant;
import java.util.Objects;

public record AlgoOrderRecord(
        long algoOrderId,
        ProductLine productLine,
        long userId,
        String clientAlgoOrderId,
        String instrumentId,
        AlgoOrderType algoType,
        OrderSide side,
        long priceTicks,
        long quantitySteps,
        long childQuantitySteps,
        long intervalSeconds,
        long durationSeconds,
        MarginMode marginMode,
        PositionSide positionSide,
        boolean reduceOnly,
        TimeInForce timeInForce,
        AlgoOrderStatus status,
        Long currentOrderId,
        String rejectReason,
        Instant startAt,
        Instant nextSliceAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public AlgoOrderRecord {
        if (algoType == AlgoOrderType.TWAP) timeInForce = TimeInForce.IOC;
        productLine = Objects.requireNonNull(productLine, "productLine");
    }
    public boolean postOnly() {
        return timeInForce == TimeInForce.GTX;
    }
}
