package com.surprising.aeron.model;

/** 普通订单在 Core 中的状态。 */
public enum CoreOrderStatus {
    OPEN,
    CANCELED,
    FILLED,
    /** 订单处理被拒绝，不能继续参与成交。 */
    REJECTED;

    /** 判断订单是否已经结束。 */
    public boolean terminal() {
        return this != OPEN;
    }
}
