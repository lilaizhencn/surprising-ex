package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.util.List;

/** 一次 realtime 快照中固定的撤单目标；不包含将来新建的订单。 */
public record CancellationPlanResponse(long userId, ProductLine productLine, String instrumentId, List<Long> orderIds) {
    public CancellationPlanResponse { orderIds = List.copyOf(orderIds); }
}
