package com.surprising.trading.order.model;

/** 已在Controller解码的原生成交分页坐标。 */
public final class TradeReadCursor {
    private TradeReadCursor() {}
    public record Fill(long time, long position, int index, String role) {}
    public record Execution(long time, long position, int index) {}
}
