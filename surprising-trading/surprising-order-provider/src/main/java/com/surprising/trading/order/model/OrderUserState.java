package com.surprising.trading.order.model;

import java.util.List;

/** 用户分区订单状态快照；订单事实不依赖数据库表才能继续推进。 */
public record OrderUserState(
        List<OrderRecord> orders,
        List<String> appliedEventIds) {

    public OrderUserState {
        if (orders == null || appliedEventIds == null) {
            throw new IllegalArgumentException("订单用户分区状态不能为空");
        }
        orders = List.copyOf(orders);
        appliedEventIds = List.copyOf(appliedEventIds);
    }

    public OrderUserState(List<OrderRecord> orders) {
        this(orders, List.of());
    }
}
