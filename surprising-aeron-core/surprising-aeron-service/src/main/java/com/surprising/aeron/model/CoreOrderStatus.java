package com.surprising.aeron.model;

/**
 * 普通订单在 Core 中的状态；只有 OPEN 还可以继续成交，其他状态均为终态。
 */
public enum CoreOrderStatus {
    /** 仍然有效且有未成交量，允许后续成交或撤销。 */
    OPEN,
    /** 未成交部分已撤销，历史成交量和未成交量仍保留。 */
    CANCELED,
    /** 委托数量已经全部成交。 */
    FILLED,
    /** 订单处理被拒绝，不能继续参与成交。 */
    REJECTED;

    /**
     * 判断订单是否已经结束；撤销、全部成交和拒绝都算终态。
     */
    public boolean terminal() {
        return this != OPEN;
    }
}
