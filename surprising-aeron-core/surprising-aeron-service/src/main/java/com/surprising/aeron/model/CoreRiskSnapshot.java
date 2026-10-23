package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.risk.CoreRiskPolicy;

/**
 * 某次价格下对用户某个持仓腿的风险估值结果，保存当时权益、盈亏、维持保证金及风险比例，等级按固定阈值读取；全仓记录中的权益可以是多仓共享的账户权益。
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
 * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
 * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
 * @param equityUnits 本次风险判断采用的权益金额，逐仓看该仓权益，全仓看共享权益，单位为结算资产最小单位
 * @param unrealizedPnlUnits 按当前风险价格计算的未实现盈亏，单位为结算资产最小单位
 * @param maintenanceMarginUnits 维持持仓所要求的保证金金额，单位为结算资产最小单位
 * @param marginRatioPpm 维持保证金相对权益的风险占比，按一百万倍保存，越大越接近强平
 */
public record CoreRiskSnapshot(
        long userId,
        String instrumentId,
        CorePositionSide positionSide,
        long priceSequence,
        long equityUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm) {
    /**
     * 规范合约编号，并校验用户、价格序号、持仓腿、维持保证金和风险占比等必填范围。
     */
    public CoreRiskSnapshot {
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        if (userId <= 0 || positionSide == null || priceSequence <= 0 || maintenanceMarginUnits < 0
                || marginRatioPpm < 0) {
            throw new IllegalArgumentException("invalid risk snapshot");
        }
    }

    /** 按已固定的风险比例读取等级，不重新估值账户或行情。 */
    public CoreRiskStatus status() {
        return CoreRiskPolicy.status(marginRatioPpm);
    }

    /**
     * 按用户、合约和必要的持仓腿组成风险记录键，使同一用户的双向仓位不会互相覆盖。
     */
    public String key() {
        return positionSide == CorePositionSide.NET
                ? userId + ":" + instrumentId
                : userId + ":" + instrumentId + ":" + positionSide.name();
    }
}
