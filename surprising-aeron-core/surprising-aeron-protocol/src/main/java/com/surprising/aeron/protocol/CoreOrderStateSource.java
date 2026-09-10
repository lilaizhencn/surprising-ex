package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.UUID;

/** 编码期间借用的订单只读字段；实现可复用游标，编码器不得保留引用。 */
public interface CoreOrderStateSource {
    long orderId();
    ProductLine productLine();
    long userId();
    String symbol();
    long instrumentChangeId();
    CoreOrderSide side();
    long priceTicks();
    long quantitySteps();
    long executedQuantitySteps();
    long remainingQuantitySteps();
    boolean reduceOnly();
    CoreMarginMode marginMode();
    CorePositionSide positionSide();
    CoreOrderType orderType();
    CoreTimeInForce timeInForce();
    boolean postOnly();
    String clientOrderId();
    UUID commandId();
    long makerFeeRatePpm();
    long takerFeeRatePpm();
    long cumulativeFeeUnits();
    long createdAtEpochMillis();
    long updatedAtEpochMillis();
    long clusterPosition();
    String status();
    long revision();
}
