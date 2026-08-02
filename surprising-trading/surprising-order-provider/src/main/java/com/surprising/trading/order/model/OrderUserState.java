package com.surprising.trading.order.model;

import java.util.List;

/** 用户分区订单状态快照；订单事实不依赖数据库表才能继续推进。 */
public record OrderUserState(
        List<OrderRecord> orders,
        List<String> appliedEventIds,
        List<AlgoOrderRecord> algoOrders,
        List<AlgoOrderChild> algoChildren,
        List<Long> appliedTradeIds) {

    public OrderUserState {
        if (orders == null) {
            throw new IllegalArgumentException("订单用户分区状态不能为空");
        }
        orders = List.copyOf(orders);
        appliedEventIds = appliedEventIds == null ? List.of() : List.copyOf(appliedEventIds);
        algoOrders = algoOrders == null ? List.of() : List.copyOf(algoOrders);
        algoChildren = algoChildren == null ? List.of() : List.copyOf(algoChildren);
        appliedTradeIds = appliedTradeIds == null ? List.of() : List.copyOf(appliedTradeIds);
        java.util.HashSet<Long> uniqueTradeIds = new java.util.HashSet<>();
        for (Long tradeId : appliedTradeIds) {
            if (tradeId == null || tradeId <= 0L || !uniqueTradeIds.add(tradeId)) {
                throw new IllegalArgumentException("订单成交幂等索引无效");
            }
        }
    }

    public OrderUserState(List<OrderRecord> orders) {
        this(orders, List.of(), List.of(), List.of(), List.of());
    }

    public OrderUserState(List<OrderRecord> orders, List<String> appliedEventIds) {
        this(orders, appliedEventIds, List.of(), List.of(), List.of());
    }

    public OrderUserState(List<OrderRecord> orders,
                          List<String> appliedEventIds,
                          List<AlgoOrderRecord> algoOrders,
                          List<AlgoOrderChild> algoChildren) {
        this(orders, appliedEventIds, algoOrders, algoChildren, List.of());
    }
}
