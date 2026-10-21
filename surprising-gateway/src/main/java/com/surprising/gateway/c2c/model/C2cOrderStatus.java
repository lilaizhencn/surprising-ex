package com.surprising.gateway.c2c.model;

/** SQL 中的订单流程；资产是否实际移动只由对应 Core 资金指令的已提交结果决定。 */
public enum C2cOrderStatus {
    RESERVING, PAYMENT_PENDING, PAID, APPEALED,
    RELEASE_PENDING, REFUND_PENDING, COMPLETED, CANCELED, EXPIRED, FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELED || this == EXPIRED || this == FAILED;
    }

    public void requirePayment() {
        if (this != PAYMENT_PENDING) throw new IllegalStateException("当前订单不能标记付款");
    }

    public void requireRelease() {
        if (this != PAID) throw new IllegalStateException("核对到账并确认付款状态后才能放币");
    }

    public void requireCancellation() {
        if (this != PAYMENT_PENDING) throw new IllegalStateException("只有待付款订单可以直接取消；已付款请发起申诉");
    }

    public void requireAppeal() {
        if (this != PAYMENT_PENDING && this != PAID)
            throw new IllegalStateException("当前订单不能申诉");
    }
}
