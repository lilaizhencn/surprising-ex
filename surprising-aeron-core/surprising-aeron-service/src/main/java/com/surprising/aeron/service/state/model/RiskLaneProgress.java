package com.surprising.aeron.service.state.model;

/** 单个账户 Lane 的可恢复风险游标；列表下标就是 Lane 编号，由 owner 在任务完成后提交。 */
public record RiskLaneProgress(
        /** 已完成的用户，下一次从其后继续。 */ long lastUserId,
        /** 本轮价格扫描是否已遍历该 Lane。 */ boolean complete,
        /** 正在分片估值的用户；零表示尚未开始下一用户。 */ long userId,
        /** 0：累计持仓风险；1：累计委托保证金；2：写入全仓风险。 */ int phase,
        /** 当前阶段最后处理的持仓键。 */ String positionCursor,
        /** 最后处理的冻结委托编号。 */ long reservationCursor,
        /** 已累计的未实现权益变化。 */ long unrealizedPnlUnits,
        /** 已累计的维持保证金。 */ long maintenanceMarginUnits,
        /** 已累计的逐仓持仓保证金。 */ long isolatedMarginUnits,
        /** 已累计的逐仓委托冻结资金。 */ long isolatedReservationUnits) {
    public RiskLaneProgress {
        if (lastUserId < 0 || userId < 0 || phase < 0 || phase > 2 || reservationCursor < 0
                || maintenanceMarginUnits < 0 || isolatedMarginUnits < 0 || isolatedReservationUnits < 0
                || complete && userId != 0) throw new IllegalArgumentException("invalid risk Lane progress");
        positionCursor = positionCursor == null || positionCursor.isBlank() ? "-" : positionCursor;
    }
}
