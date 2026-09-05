package com.surprising.aeron.protocol;

import java.util.List;

public record CoreOpenOrdersView(List<CoreOrderStateView> orders) {

    public CoreOpenOrdersView {
        orders = List.copyOf(orders);
    }
}
