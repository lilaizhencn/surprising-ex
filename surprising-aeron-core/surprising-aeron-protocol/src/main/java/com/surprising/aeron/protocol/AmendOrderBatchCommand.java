package com.surprising.aeron.protocol;

import java.util.List;

public record AmendOrderBatchCommand(List<AmendOrderCommand> orders) {

    public static final int WIRE_VERSION = 1;
    public static final int MAX_ORDERS = 20;
    public static final int MAX_ITEMS = MAX_ORDERS;

    public AmendOrderBatchCommand {
        if (orders == null || orders.isEmpty() || orders.size() > MAX_ORDERS) {
            throw new IllegalArgumentException("invalid amend order batch");
        }
        for (var order : orders) if (order == null) throw new IllegalArgumentException("invalid amend order batch");
        orders = TradingOrderBatchCodec.immutableItems(orders);
    }

    public List<AmendOrderCommand> items() {
        return orders;
    }
}
