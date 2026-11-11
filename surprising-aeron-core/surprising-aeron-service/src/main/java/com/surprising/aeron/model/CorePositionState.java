package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;

/** 用户在一个合约及持仓腿上的不可变持仓记录。数量带多空符号，金额用资产最小单位。记录用于状态查看和恢复，不在这里执行成交结算。 */
public record CorePositionState(
        String instrumentId,
        String marginAsset,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long signedQuantitySteps,
        long entryPriceTicks,
        long entryValueTicks,
        long realizedPnlUnits,
        long positionMarginUnits) {

    /** 空仓不能残留入场价、入场价格量或持仓保证金，有仓则必须有正的入场信息。 */
    public CorePositionState {
        instrumentId = InstrumentId.requireValid(instrumentId);
        marginAsset = AssetBalance.normalizeAsset(marginAsset);
        if (marginMode == null || positionSide == null || positionMarginUnits < 0) {
            throw new IllegalArgumentException("position margin must not be negative");
        }
        if (signedQuantitySteps == 0) {
            if (entryPriceTicks != 0 || entryValueTicks != 0 || positionMarginUnits != 0) {
                throw new IllegalArgumentException("flat position contains open-position state");
            }
        } else if (entryPriceTicks <= 0 || entryValueTicks <= 0) {
            throw new IllegalArgumentException("open position is incomplete");
        }
    }

    /** 生成用户持仓映射内的键：净持仓直接用合约编号，双向持仓再拼上多头或空头腿。 */
    public String key() {
        return positionSide == CorePositionSide.NET ? instrumentId : instrumentId + ':' + positionSide.name();
    }
}
