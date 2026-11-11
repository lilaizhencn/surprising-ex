package com.surprising.aeron.service.result;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateSource;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.order.Order;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 协议编码器使用的订单字段适配器，借用现有 Order，不是订单快照。 */
public final class OrderRuntimeSource implements CoreOrderStateSource {
    private Order order;
    @Accessors(fluent = true, chain = false)
    @Getter
    private String instrumentId;

    /** 借用订单引用并固定本次编码需要的币对文本。 */
    public void set(Order order, String instrumentId) {
        this.order = order;
        this.instrumentId = instrumentId;
    }

    /** 移除适配器中的订单和币对引用，不改变实际订单状态。 */
    public void clear() {
        order = null;
        instrumentId = null;
    }

    public long orderId() { return order.orderId(); }
    public ProductLine productLine() { return order.productLine(); }
    public long userId() { return order.userId(); }
    public CoreOrderSide side() { return order.side(); }
    public long priceTicks() { return order.priceTicks(); }
    public long quantitySteps() { return order.quantitySteps(); }
    public long executedQuantitySteps() { return order.executedQuantitySteps(); }
    public long remainingQuantitySteps() { return order.remainingQuantitySteps(); }
    public boolean reduceOnly() { return order.reduceOnly(); }
    public CoreMarginMode marginMode() { return order.marginMode(); }
    public CorePositionSide positionSide() { return order.positionSide(); }
    public CoreOrderType orderType() { return order.orderType(); }
    public CoreTimeInForce timeInForce() { return order.timeInForce(); }
    public boolean postOnly() { return order.postOnly(); }
    public String clientOrderId() { return order.clientOrderId(); }
    public UUID commandId() { return order.commandId(); }
    public long makerFeeRatePpm() { return order.makerFeeRatePpm(); }
    public long takerFeeRatePpm() { return order.takerFeeRatePpm(); }
    public long cumulativeFeeUnits() { return order.cumulativeFeeUnits(); }
    public long executedValueHigh() { return order.executedValueHigh(); }
    public long executedValueLow() { return order.executedValueLow(); }
    public long createdAtEpochMillis() { return order.createdAtEpochMillis(); }
    public long updatedAtEpochMillis() { return order.updatedAtEpochMillis(); }
    public long clusterPosition() { return order.clusterPosition(); }
    public String status() { return order.status().name(); }
    public long revision() { return order.revision(); }
}
