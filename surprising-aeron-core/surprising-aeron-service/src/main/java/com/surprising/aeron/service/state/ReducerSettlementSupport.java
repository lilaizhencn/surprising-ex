package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreMarginMode;
import java.util.List;

final class ReducerSettlementSupport {

    private ReducerSettlementSupport() {
    }

    static CashResult applyCash(AssetBalance balance, long delta) {
        if (delta >= 0) {
            return new CashResult(delta == 0 ? balance : balance.credit(delta), delta);
        }
        long debit = Math.min(balance.availableUnits(), Math.negateExact(delta));
        return new CashResult(debit == 0 ? balance : balance.adjustAvailable(Math.negateExact(debit)),
                Math.negateExact(debit));
    }

    static LiquidationCashResult applyLiquidationCash(AssetBalance balance, CoreMarginMode marginMode,
                                                              long releasedMargin, long pnl, long feeDue) {
        AssetBalance settled = balance;
        long appliedPnl;
        long isolatedFeeCapacity;
        if (marginMode == CoreMarginMode.ISOLATED) {
            if (pnl < 0) {
                long loss = Math.negateExact(pnl);
                long consumedMargin = Math.min(releasedMargin, loss);
                if (consumedMargin > 0) settled = settled.consumeLocked(consumedMargin);
                long remainingMargin = Math.subtractExact(releasedMargin, consumedMargin);
                if (remainingMargin > 0) settled = settled.release(remainingMargin);
                appliedPnl = Math.negateExact(consumedMargin);
                isolatedFeeCapacity = remainingMargin;
            } else {
                if (releasedMargin > 0) settled = settled.release(releasedMargin);
                if (pnl > 0) settled = settled.credit(pnl);
                appliedPnl = pnl;
                isolatedFeeCapacity = Math.addExact(releasedMargin, pnl);
            }
            long collectedFee = Math.min(feeDue, isolatedFeeCapacity);
            if (collectedFee > 0) settled = settled.adjustAvailable(Math.negateExact(collectedFee));
            return new LiquidationCashResult(settled, appliedPnl, collectedFee);
        }
        if (releasedMargin > 0) settled = settled.release(releasedMargin);
        CashResult pnlCash = applyCash(settled, pnl);
        CashResult feeCash = applyCash(pnlCash.balance(), Math.negateExact(feeDue));
        return new LiquidationCashResult(feeCash.balance(), pnlCash.appliedDelta(),
                Math.negateExact(feeCash.appliedDelta()));
    }

    static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    static OrderReservation requireReservation(CoreUserState user, long orderId) {
        OrderReservation reservation = user.reservations().get(orderId);
        if (reservation == null) {
            throw new IllegalStateException("open order reservation is missing orderId=" + orderId);
        }
        return reservation;
    }

    static AssetBalance requireBalance(CoreUserState user, String asset) {
        AssetBalance balance = user.balances().get(asset);
        if (balance == null) {
            throw new IllegalStateException("reservation balance is missing asset=" + asset);
        }
        return balance;
    }

    static String positionKey(String symbol, com.surprising.aeron.protocol.CorePositionSide side) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        return side.hedgeSide() ? normalized + ':' + side.name() : normalized;
    }

    static long openingMarginForFill(
            CoreInstrumentState instrument,
            long projectedQuantitySteps,
            long signedFillSteps,
            long openSteps,
            long priceTicks,
            long leveragePpm,
            long indexPriceTicks,
            long forwardPriceTicks) {
        if (openSteps == 0 || instrument.contractType().isOption() && signedFillSteps > 0) return 0;
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantitySteps), instrument.contractType().isOption()
                        ? indexPriceTicks : priceTicks);
        com.surprising.aeron.protocol.CoreRiskLimitBracket bracket = CoreContractMath.maintenanceRiskBracket(
                instrument, projectedNotional);
        long effectiveRate = instrument.contractType().isOption() ? bracket.initialMarginRatePpm()
                : Math.max(Math.max(instrument.initialMarginRatePpm(), bracket.initialMarginRatePpm()),
                CoreContractMath.initialMarginRateFromLeverage(leveragePpm));
        CoreOrderSide side = signedFillSteps > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL;
        return CoreContractMath.openingMarginUnits(instrument, side, priceTicks, openSteps, effectiveRate,
                indexPriceTicks, forwardPriceTicks, bracket.optionMarginFactorPpm());
    }
    record CashResult(AssetBalance balance, long appliedDelta) {
    }
    record LiquidationCashResult(AssetBalance balance, long appliedDelta, long collectedFeeUnits) {
    }
    static List<CoreOrderState> userOrders(TradingCoreState state, CoreUserState user) {
        return user.reservations().keySet().stream()
                .map(state.orders()::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
