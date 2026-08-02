package com.surprising.trading.order.model;

/** 用户订单分区撤单命令载荷。 */
public record OrderUserCancelCommand(long orderId, String reason) {
    public OrderUserCancelCommand {
        if (orderId <= 0L) {
            throw new IllegalArgumentException("撤单订单编号必须为正数");
        }
        reason = reason == null || reason.isBlank() ? null : reason.trim();
    }
}
