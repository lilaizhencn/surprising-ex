package com.surprising.aeron.model;

/** 单个账户 Lane 可跨页继续的风险估值游标及累计值，外层列表下标就是 Lane 编号。Owner 收到账户任务完成后提交它。 */
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
    /** 创建尚未附带用户和市场修订号的 Lane 风险游标，这两个修订号都从 0 开始。 */
    public RiskLaneProgress(long lastUserId, boolean complete, long userId, int phase, String positionCursor,
                            long reservationCursor, long unrealizedPnlUnits, long maintenanceMarginUnits,
                            long isolatedMarginUnits, long isolatedReservationUnits) {
        this(lastUserId, complete, userId, phase, positionCursor, reservationCursor, unrealizedPnlUnits,
                maintenanceMarginUnits, isolatedMarginUnits, isolatedReservationUnits, 0, 0);
    }

    /** 已经完成的 Lane 不得残留正在处理的用户，空持仓游标统一记为短横线。 */
    public RiskLaneProgress {
        if (userRevision < 0 || marketRevision < 0 || lastUserId < 0 || userId < 0 || phase < 0 || phase > 2 || reservationCursor < 0
                || maintenanceMarginUnits < 0 || isolatedMarginUnits < 0 || isolatedReservationUnits < 0
                || complete && userId != 0) throw new IllegalArgumentException("invalid risk Lane progress");
        positionCursor = positionCursor == null || positionCursor.isBlank() ? "-" : positionCursor;
    }
}
