package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CorePositionSide;

/** 某次价格下对用户某个持仓腿的风险估值结果，保存当时权益、盈亏、维持保证金及风险比例，等级按固定阈值读取。 */
public record CoreRiskSnapshot(
        long userId,
        String instrumentId,
        CorePositionSide positionSide,
        long priceSequence,
        long equityUnits,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long marginRatioPpm) {
    public CoreRiskSnapshot {
        instrumentId = InstrumentId.requireValid(instrumentId);
        if (userId <= 0 || positionSide == null || priceSequence <= 0 || maintenanceMarginUnits < 0
                || marginRatioPpm < 0) {
            throw new IllegalArgumentException("invalid risk snapshot");
        }
    }

    /** 按已固定的风险比例读取等级，不重新估值账户或行情。 */
    public CoreRiskStatus status() {
        return CoreRiskStatus.of(marginRatioPpm);
    }

    /** 按用户、合约和必要的持仓腿组成风险记录键，使同一用户的双向仓位不会互相覆盖。 */
    public String key() {
        return positionSide == CorePositionSide.NET
                ? userId + ":" + instrumentId
                : userId + ":" + instrumentId + ":" + positionSide.name();
    }
}
