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
        /** 已累计的逐仓委托冻结资金。 */ long isolatedReservationUnits,
        /** 当前累计值对应的账户修订号。 */ long userRevision,
        /** 当前累计值对应的全局行情修订号。 */ long marketRevision) {
    /**
     * 创建尚未附带用户和市场修订号的 Lane 风险游标，这两个修订号都从 0 开始。
     * @param lastUserId 本轮最后一个已经处理完的用户编号，后续从它之后继续
     * @param complete 该 Lane 的本轮账户风险遍历是否已经完成
     * @param userId 正在分批估值的用户编号，0 表示没有未完成用户
     * @param phase 估值阶段：0 累计持仓风险，1 累计逐仓委托冻结，2 写入全仓风险
     * @param positionCursor 最后处理的持仓键，短横线表示尚未开始遍历持仓
     * @param reservationCursor 最后处理的冻结委托编号，0 表示尚未开始遍历
     * @param unrealizedPnlUnits 累计的风险权益变化，期货用未实现盈亏、期权用持仓市值
     * @param maintenanceMarginUnits 维持持仓所要求的保证金金额，单位为结算资产最小单位
     * @param isolatedMarginUnits 已经累计的逐仓持仓保证金，需要从全仓共享资金中扣除
     * @param isolatedReservationUnits 已经累计的逐仓委托冻结金额，需要从全仓共享资金中扣除
     */
    public RiskLaneProgress(long lastUserId, boolean complete, long userId, int phase, String positionCursor,
                            long reservationCursor, long unrealizedPnlUnits, long maintenanceMarginUnits,
                            long isolatedMarginUnits, long isolatedReservationUnits) {
        this(lastUserId, complete, userId, phase, positionCursor, reservationCursor, unrealizedPnlUnits,
                maintenanceMarginUnits, isolatedMarginUnits, isolatedReservationUnits, 0, 0);
    }

    /**
     * 校验游标、累计金额、阶段和修订号；已经完成的 Lane 不得残留正在处理的用户，空持仓游标统一记为短横线。
     */
    public RiskLaneProgress {
        if (userRevision < 0 || marketRevision < 0 || lastUserId < 0 || userId < 0 || phase < 0 || phase > 2 || reservationCursor < 0
                || maintenanceMarginUnits < 0 || isolatedMarginUnits < 0 || isolatedReservationUnits < 0
                || complete && userId != 0) throw new IllegalArgumentException("invalid risk Lane progress");
        positionCursor = positionCursor == null || positionCursor.isBlank() ? "-" : positionCursor;
    }
}
