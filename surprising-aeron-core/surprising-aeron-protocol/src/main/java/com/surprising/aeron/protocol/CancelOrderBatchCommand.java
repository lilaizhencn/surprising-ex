package com.surprising.aeron.protocol;

import java.util.List;

public record CancelOrderBatchCommand(List<CancelOrderCommand> orders) {

    public static final int WIRE_VERSION = 1;
    public static final int MAX_ORDERS = 50;
    public static final int MAX_ITEMS = MAX_ORDERS;

    public CancelOrderBatchCommand {
        if (orders == null || orders.isEmpty() || orders.size() > MAX_ORDERS
                || orders.stream().anyMatch(order -> order == null)) {
            throw new IllegalArgumentException("invalid cancel order batch");
        }
        orders = List.copyOf(orders);
    }

    public List<CancelOrderCommand> items() {
        return orders;
    }
}
