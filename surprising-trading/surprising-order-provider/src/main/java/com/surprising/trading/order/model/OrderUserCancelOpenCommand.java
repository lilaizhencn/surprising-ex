package com.surprising.trading.order.model;

/** 用户分区批量撤单命令载荷。 */
public record OrderUserCancelOpenCommand(String symbol, int limit, String reason) {
    public OrderUserCancelOpenCommand(String symbol, int limit) {
        this(symbol, limit, null);
    }

    public OrderUserCancelOpenCommand {
        symbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("批量撤单数量必须在 1 到 1000 之间");
        }
        reason = reason == null || reason.isBlank() ? "USER_CANCEL_ALL" : reason.trim();
    }
}
