package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/** One execution of the authenticated user's own order; no counterparty identities. */
public record TradeFillResponse(String tradeId, ProductLine productLine, String instrumentId,
        long orderId, OrderSide side, PositionSide positionSide, String liquidityRole,
        long priceTicks, long quantitySteps, long feeRatePpm, Instant eventTime) {}
