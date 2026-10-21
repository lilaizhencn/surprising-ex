package com.surprising.aeron.service.state.model;

/**
 * 单个账户 Lane 可跨页继续的风险估值游标及累计值，外层列表下标就是 Lane 编号；Owner 收到账户任务完成后提交它。
 * @param lastUserId 本轮最后一个已经处理完的用户，后续从它之后继续
 * @param complete 本轮是否已遍历该 Lane 的账户风险，完成时不能残留正在估值用户
 * @param userId 正在分片估值的原用户，零表示没有未完成用户
 * @param phase 估值阶段：零累计持仓风险，一累计逐仓委托冻结，二写入全仓风险
 * @param positionCursor 最后处理的持仓键，空白或空引用统一为短横线
 * @param reservationCursor 最后处理的原预留订单号，零表示尚未开始遍历
 * @param unrealizedPnlUnits 累计的风险权益变化，期货用未实现盈亏，期权用持仓市值
 * @param maintenanceMarginUnits 已累计的维持保证金，单位为结算资产最小单位
 * @param isolatedMarginUnits 已累计的逐仓持仓保证金，需从全仓共享资金中扣除
 * @param isolatedReservationUnits 已累计的逐仓委托冻结资金，需从全仓共享资金中扣除
 * @param userRevision 当前分片累计结果对应的非负账户修订
 * @param marketRevision 当前分片累计结果对应的非负全局行情修订
 */
public record RiskLaneProgress(
        long lastUserId,
        boolean complete,
        long userId,
        int phase,
        String positionCursor,
        long reservationCursor,
        long unrealizedPnlUnits,
        long maintenanceMarginUnits,
        long isolatedMarginUnits,
        long isolatedReservationUnits,
        long userRevision,
        long marketRevision) {
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
