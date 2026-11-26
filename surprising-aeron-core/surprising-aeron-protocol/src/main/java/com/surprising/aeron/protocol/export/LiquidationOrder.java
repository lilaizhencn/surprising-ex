package com.surprising.aeron.protocol.export;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;

/** 强平单不可变投影视图（协议层扁平化字段，不依赖服务内部状态类型）。 */
public record LiquidationOrder(long liquidationId, long userId, String instrumentId, String contractTypeCode,
                               String settleAsset, CoreMarginMode marginMode, CorePositionSide positionSide,
                               long triggerPriceSequence, long signedQuantitySteps, long closeQuantitySteps,
                               long executionPriceTicks, long liquidationFeeRatePpm,
                               long liquidationFeeUnits, String status) {
    public LiquidationOrder {
        if (liquidationId <= 0 || userId <= 0 || instrumentId == null || instrumentId.isBlank()
                || contractTypeCode == null || contractTypeCode.isBlank()
                || settleAsset == null || settleAsset.isBlank()
                || marginMode == null || positionSide == null || triggerPriceSequence <= 0 || signedQuantitySteps == 0
                || closeQuantitySteps <= 0 || closeQuantitySteps > Math.absExact(signedQuantitySteps)
                || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0
                || status == null || status.isBlank()) {
            throw new IllegalArgumentException("invalid liquidation projection");
        }
    }
}
