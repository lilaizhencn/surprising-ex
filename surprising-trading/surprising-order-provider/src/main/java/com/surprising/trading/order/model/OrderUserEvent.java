package com.surprising.trading.order.model;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.trading.api.model.MatchResultEvent;

/** 用户订单事实流中的不可变事件。 */
public record OrderUserEvent(
        String eventType,
        String eventId,
        OrderRecord order,
        AccountCommandResultEvent accountResult,
        MatchResultEvent matchResult,
        String cancelReason,
        AlgoOrderRecord algoOrder,
        AlgoOrderChild algoChild) {

    public OrderUserEvent {
        if (eventType == null || eventType.isBlank() || eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("订单事实事件标识不能为空");
        }
        eventType = eventType.trim().toUpperCase();
        eventId = eventId.trim();
    }

    public static OrderUserEvent place(OrderRecord order) {
        return new OrderUserEvent("PLACE", "PLACE:" + order.orderId(), order, null, null, null, null, null);
    }

    public static OrderUserEvent accountResult(AccountCommandResultEvent result) {
        return new OrderUserEvent("ACCOUNT_RESULT", "ACCOUNT_RESULT:" + result.commandId(), null, result, null, null,
                null, null);
    }

    public static OrderUserEvent matchResult(MatchResultEvent result) {
        return new OrderUserEvent("MATCH_RESULT", "MATCH_RESULT:" + result.commandId() + ":" + result.orderId(),
                null, null, result, null, null, null);
    }

    public static OrderUserEvent cancel(long orderId, String reason) {
        return new OrderUserEvent("CANCEL", "CANCEL:" + orderId, null, null, null, reason, null, null);
    }

    public static OrderUserEvent algoPlace(AlgoOrderRecord order) {
        return new OrderUserEvent("ALGO_PLACE", "ALGO_PLACE:" + order.algoOrderId(), null, null, null, null,
                order, null);
    }

    public static OrderUserEvent algoUpdate(AlgoOrderRecord order) {
        return new OrderUserEvent("ALGO_UPDATE:" + order.status().name(),
                "ALGO_UPDATE:" + order.algoOrderId() + ":" + order.updatedAt().toEpochMilli()
                        + ":" + Integer.toUnsignedString(order.hashCode()),
                null, null, null, null, order, null);
    }

    public static OrderUserEvent algoChild(AlgoOrderRecord order, AlgoOrderChild child) {
        return new OrderUserEvent("ALGO_CHILD", "ALGO_CHILD:" + order.algoOrderId() + ":" + child.sliceIndex(),
                null, null, null, null, order, child);
    }
}
