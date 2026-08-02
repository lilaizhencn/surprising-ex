package com.surprising.trading.order.model;

/** 算法单切片命令载荷。 */
public record OrderUserAlgoChildCommand(AlgoOrderRecord order, AlgoOrderChild child) {
    public OrderUserAlgoChildCommand {
        if (order == null || child == null || order.algoOrderId() != child.algoOrderId()) {
            throw new IllegalArgumentException("算法单切片命令载荷不完整");
        }
    }
}
