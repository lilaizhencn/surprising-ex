package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;

/** 一份倒计时撤单状态，保存适用范围、触发时刻和修订号。实际撤单由业务流程执行，这份记录用来恢复和展示进度。 */
public record CoreCancelAllAfterState(
        long userId,
        String symbolScope,
        CoreCancelAllAfterStatus status,
        long triggerAtEpochMillis,
        long updatedAtEpochMillis,
        long revision) {

    private static final long CLAIM_LEASE_MILLIS = 30_000L;

    /** 校验状态字段，启用中的定时器必须有具体触发时刻。 */
    public CoreCancelAllAfterState {
        if (userId <= 0 || symbolScope == null || symbolScope.isBlank() || status == null
                || triggerAtEpochMillis < 0 || updatedAtEpochMillis <= 0 || revision <= 0) {
            throw new IllegalArgumentException("invalid cancel-all-after view");
        }
        if (status == CoreCancelAllAfterStatus.ACTIVE && triggerAtEpochMillis == 0) {
            throw new IllegalArgumentException("active cancel-all-after timer requires trigger time");
        }
    }

    /** 取用户和币对范围组成查找键，供定时器状态索引定位。 */
    public CoreCancelAllAfterKey key() {
        return new CoreCancelAllAfterKey(userId, symbolScope);
    }

    /** 已到期的启用记录或认领超时记录才可执行。 */
    public boolean claimableAt(long nowEpochMillis) {
        return (status == CoreCancelAllAfterStatus.ACTIVE && triggerAtEpochMillis <= nowEpochMillis)
                || (status == CoreCancelAllAfterStatus.TRIGGERING
                && nowEpochMillis - updatedAtEpochMillis >= CLAIM_LEASE_MILLIS);
    }

    /** 转换成协议层只读视图，字段原样保留供查询或事件发布使用。 */
    public CoreCancelAllAfterView view() {
        return new CoreCancelAllAfterView(userId, symbolScope, status, triggerAtEpochMillis,
                updatedAtEpochMillis, revision);
    }
}
