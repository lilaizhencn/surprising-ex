package com.surprising.trading.order.model;

/** 算法单与用户分区普通订单之间的本地切片映射。 */
public record AlgoOrderChild(
        long algoOrderId,
        int sliceIndex,
        long orderId,
        long quantitySteps) {

    public AlgoOrderChild {
        if (algoOrderId <= 0L || sliceIndex < 0 || orderId <= 0L || quantitySteps <= 0L) {
            throw new IllegalArgumentException("算法单切片映射无效");
        }
    }
}
