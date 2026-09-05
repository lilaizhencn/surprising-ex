package com.surprising.aeron.service.state;

import java.util.List;

public record TerminalPruneBatch(
        List<Long> orderIds,
        List<Long> algoOrderIds,
        List<Long> triggerOrderIds,
        List<Long> liquidationIds) {

    public TerminalPruneBatch {
        orderIds = List.copyOf(orderIds);
        algoOrderIds = List.copyOf(algoOrderIds);
        triggerOrderIds = List.copyOf(triggerOrderIds);
        liquidationIds = List.copyOf(liquidationIds);
    }

    public static TerminalPruneBatch empty() {
        return new TerminalPruneBatch(List.of(), List.of(), List.of(), List.of());
    }

    public int size() {
        return orderIds.size() + algoOrderIds.size() + triggerOrderIds.size() + liquidationIds.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
