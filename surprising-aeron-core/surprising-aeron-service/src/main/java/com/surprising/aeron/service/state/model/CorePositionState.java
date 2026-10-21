package com.surprising.aeron.service.state.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.order.OrderReservation;

/**
 * 用户在一个合约及持仓腿上的不可变持仓记录。数量带多空符号，金额用资产最小单位；记录用于状态查看和恢复，不在这里执行成交结算。
 * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
 * @param marginAsset 持仓使用的保证金或结算资产代码，会按资产规则规范化
 * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
 * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
 * @param signedQuantitySteps 带方向的持仓数量步数，正数为多头、负数为空头、0 为无持仓
 * @param entryPriceTicks 当前持仓的入场价格 tick，无持仓时必须为 0
 * @param entryValueTicks 持仓入场价格与绝对数量对应的累计价格量，用于保留入场计算信息，无持仓时为 0
 * @param realizedPnlUnits 已经实现的累计盈亏，正数为盈利、负数为亏损，使用结算资产最小单位
 * @param positionMarginUnits 分配给这份持仓的保证金金额，使用保证金资产最小单位；无持仓时为 0
 */
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

    /**
     * 规范合约和资产代码，检查保证金非负；空仓不能残留入场价、入场价格量或持仓保证金，有仓则必须有正的入场信息。
     */
    public CorePositionState {
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
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

    /**
     * 创建全仓、单向净持仓记录；其他字段由调用方明确传入。
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param marginAsset 持仓使用的保证金或结算资产代码，会按资产规则规范化
     * @param signedQuantitySteps 带方向的持仓数量步数，正数为多头、负数为空头、0 为无持仓
     * @param entryPriceTicks 当前持仓的入场价格 tick，无持仓时必须为 0
     * @param entryValueTicks 持仓入场价格与绝对数量对应的累计价格量，用于保留入场计算信息，无持仓时为 0
     * @param realizedPnlUnits 已经实现的累计盈亏，正数为盈利、负数为亏损，使用结算资产最小单位
     * @param positionMarginUnits 分配给这份持仓的保证金金额，使用保证金资产最小单位；无持仓时为 0
     */
    public CorePositionState(String instrumentId, String marginAsset,
                             long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                             long realizedPnlUnits, long positionMarginUnits) {
        this(instrumentId, marginAsset, CoreMarginMode.CROSS, CorePositionSide.NET,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits, positionMarginUnits);
    }

    /**
     * 生成用户持仓映射内的键：净持仓直接用合约编号，双向持仓再拼上多头或空头腿。
     */
    public String key() {
        return positionSide == CorePositionSide.NET ? instrumentId : instrumentId + ':' + positionSide.name();
    }
}
