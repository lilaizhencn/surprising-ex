package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/** One committed match, counted once even when both sides belong to the same user. */
public record AdminTradeResponse(String tradeId, ProductLine productLine, String instrumentId,
        OrderSide takerSide, PositionSide takerPositionSide, PositionSide makerPositionSide,
        long priceTicks, long quantitySteps, long takerOrderId, long makerOrderId,
        long takerUserId, long makerUserId, long takerFeeRatePpm, long makerFeeRatePpm,
        Instant eventTime, long clusterPosition, int executionIndex) {}
