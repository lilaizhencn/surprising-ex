package com.surprising.account.provider.service;

import com.surprising.account.provider.model.BalanceSettlementState;

/**
 * Applies realized PnL to wallet buckets without allowing negative balance columns.
 * Any loss beyond available plus locked collateral is tracked as an explicit deficit.
 */
public final class PnlSettlementMath {

    private PnlSettlementMath() {
    }

    public static BalanceSettlementState apply(long availableUnits,
                                               long lockedUnits,
                                               long deficitUnits,
                                               long pnlUnits) {
        return apply(availableUnits, lockedUnits, deficitUnits, pnlUnits, lockedUnits);
    }

    public static BalanceSettlementState apply(long availableUnits,
                                               long lockedUnits,
                                               long deficitUnits,
                                               long pnlUnits,
                                               long maxLockedDebitUnits) {
        if (pnlUnits == 0) {
            return new BalanceSettlementState(availableUnits, lockedUnits, deficitUnits);
        }
        if (pnlUnits > 0) {
            return applyProfit(availableUnits, lockedUnits, deficitUnits, pnlUnits);
        }
        return applyLoss(availableUnits, lockedUnits, deficitUnits, Math.negateExact(pnlUnits),
                Math.min(lockedUnits, Math.max(0L, maxLockedDebitUnits)));
    }

    public static long netEquityUnits(long availableUnits, long lockedUnits, long deficitUnits) {
        return Math.subtractExact(Math.addExact(availableUnits, lockedUnits), deficitUnits);
    }

    private static BalanceSettlementState applyProfit(long availableUnits,
                                                      long lockedUnits,
                                                      long deficitUnits,
                                                      long profitUnits) {
        long deficitOffset = Math.min(deficitUnits, profitUnits);
        long nextDeficit = Math.subtractExact(deficitUnits, deficitOffset);
        long remainingProfit = Math.subtractExact(profitUnits, deficitOffset);
        long nextAvailable = Math.addExact(availableUnits, remainingProfit);
        return new BalanceSettlementState(nextAvailable, lockedUnits, nextDeficit);
    }

    private static BalanceSettlementState applyLoss(long availableUnits,
                                                    long lockedUnits,
                                                    long deficitUnits,
                                                    long lossUnits,
                                                    long maxLockedDebitUnits) {
        long fromAvailable = Math.min(availableUnits, lossUnits);
        long nextAvailable = Math.subtractExact(availableUnits, fromAvailable);
        long remainingLoss = Math.subtractExact(lossUnits, fromAvailable);
        long fromLocked = Math.min(maxLockedDebitUnits, remainingLoss);
        long nextLocked = Math.subtractExact(lockedUnits, fromLocked);
        long nextDeficit = Math.addExact(deficitUnits, Math.subtractExact(remainingLoss, fromLocked));
        return new BalanceSettlementState(nextAvailable, nextLocked, nextDeficit);
    }
}
