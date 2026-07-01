package com.surprising.liquidation.api.model;

import java.util.List;

public record LiquidationOrderQueryResponse(
        int count,
        List<LiquidationOrderResponse> orders) {
}
