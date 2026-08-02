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
        String cancelReason) {

    public OrderUserEvent {
        if (eventType == null || eventType.isBlank() || eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("订单事实事件标识不能为空");
        }
        eventType = eventType.trim().toUpperCase();
        eventId = eventId.trim();
    }

    public static OrderUserEvent place(OrderRecord order) {
        return new OrderUserEvent("PLACE", "PLACE:" + order.orderId(), order, null, null, null);
    }

    public static OrderUserEvent accountResult(AccountCommandResultEvent result) {
        return new OrderUserEvent("ACCOUNT_RESULT", "ACCOUNT_RESULT:" + result.commandId(), null, result, null, null);
    }

    public static OrderUserEvent matchResult(MatchResultEvent result) {
        return new OrderUserEvent("MATCH_RESULT", "MATCH_RESULT:" + result.commandId() + ":" + result.orderId(),
                null, null, result, null);
    }

    public static OrderUserEvent cancel(long orderId, String reason) {
        return new OrderUserEvent("CANCEL", "CANCEL:" + orderId, null, null, null, reason);
    }
}
