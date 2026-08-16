package com.surprising.aeron.protocol;

import java.util.List;

public record PlaceOrderBatchCommand(List<PlaceOrderCommand> orders) {

    public static final int WIRE_VERSION = 1;
    public static final int MAX_ORDERS = 20;
    public static final int MAX_ITEMS = MAX_ORDERS;

    public PlaceOrderBatchCommand {
        if (orders == null || orders.isEmpty() || orders.size() > MAX_ORDERS
                || orders.stream().anyMatch(order -> order == null)) {
            throw new IllegalArgumentException("invalid place order batch");
        }
        orders = List.copyOf(orders);
    }

    public List<PlaceOrderCommand> items() {
        return orders;
    }
}
