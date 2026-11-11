package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.product.api.ProductLine;
import java.util.UUID;

/** 普通订单的不可变业务记录，集中保存委托、成交进度、费率、状态和提交信息。 */
public record CoreOrderState(
        long orderId,
        ProductLine productLine,
        long userId,
        String instrumentId,
        CoreOrderSide side,
        long matchingPriceTicks,
        long quantitySteps,
        long executedQuantitySteps,
        boolean reduceOnly,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        CoreOrderType orderType,
        CoreTimeInForce timeInForce,
        boolean postOnly,
        String clientOrderId,
        UUID commandId,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long cumulativeFeeUnits,
        long executedValueHigh,
        long executedValueLow,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        long clusterPosition,
        CoreOrderStatus status,
        long revision) {

    /** 有效订单必须仍有未成交量，只挂单属性必须与限价 GTX 一致。 */
    public CoreOrderState {
        if (orderId <= 0 || productLine == null || userId <= 0
                || side == null || matchingPriceTicks < 0
                || quantitySteps <= 0 || executedQuantitySteps < 0 || executedQuantitySteps > quantitySteps
                || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64 || commandId == null
                || createdAtEpochMillis < 0 || updatedAtEpochMillis < createdAtEpochMillis || clusterPosition < 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)
                || status == null || revision <= 0) {
            throw new IllegalArgumentException("invalid order state");
        }
        instrumentId = InstrumentId.requireValid(instrumentId);
        if (status == CoreOrderStatus.OPEN && executedQuantitySteps == quantitySteps) {
            throw new IllegalArgumentException("open order must have remaining quantity");
        }
    }

    /** 原请求价：衍生品市价为0，现货保护边界和限价由实际撮合价提供。 */
    public long priceTicks() {
        return productLine != ProductLine.SPOT && orderType == CoreOrderType.MARKET ? 0 : matchingPriceTicks;
    }

    /** 返回尚未成交的历史数量，撤单后仍保留。 */
    public long remainingQuantitySteps() {
        return Math.subtractExact(quantitySteps, executedQuantitySteps);
    }

    /** 创建只提供基本委托和进度的全仓净持仓限价 GTC 记录。 */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, boolean reduceOnly,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, quantitySteps,
                executedQuantitySteps, reduceOnly, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "", new UUID(0, orderId),
                0, 0, status, revision);
    }

    /** 创建撮合价与委托价相同、尚未附上提交时间及位置的订单，累计费用和提交信息从 0 开始。 */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide, CoreOrderType orderType,
                          CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId, UUID commandId,
                          long makerFeeRatePpm, long takerFeeRatePpm, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, quantitySteps,
                executedQuantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm, 0,
                executedQuantitySteps == 0 ? 0 : -1, 0, 0, 0, 0, status, revision);
    }

    /** 创建指定保证金模式和持仓腿的限价 GTC 记录，撮合价沿用委托价，其余可省略属性采用构造器明确给出的初始值。 */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, quantitySteps,
                executedQuantitySteps, reduceOnly, marginMode, positionSide,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "", new UUID(0, orderId),
                0, 0, status, revision);
    }
}
