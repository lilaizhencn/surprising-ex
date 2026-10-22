package com.surprising.aeron.service.account;

/**
 * 一次余额前后值捕获中的不可变金额快照，区分可用、冻结和其中仍待完成准入的预留；不承担余额所有权或账务写入。
 * @param availableUnits 捕获时的可用整数金额
 * @param lockedUnits 捕获时的冻结整数金额
 * @param pendingReservedUnits 冻结金额中尚待准入完成的预留合计
 */
public record BalanceState(long availableUnits, long lockedUnits, long pendingReservedUnits) {
    /**
     * 要求三种金额非负且待完成预留不超过冻结金额，拒绝无法表示的捕获值。
     */
    public BalanceState {
        if (availableUnits < 0 || lockedUnits < 0 || pendingReservedUnits < 0
                || pendingReservedUnits > lockedUnits) {
            throw new IllegalArgumentException("invalid runtime balance state");
        }
    }
}
