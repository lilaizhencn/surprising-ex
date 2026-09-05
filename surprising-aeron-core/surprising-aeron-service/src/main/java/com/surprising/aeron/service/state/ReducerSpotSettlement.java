package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import java.util.Map;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.*;

final class ReducerSpotSettlement {

    private ReducerSpotSettlement() {
    }

    static SpotFillResult applySpotFill(
            CoreUserState user,
            CoreOrderState order,
            CoreInstrumentState instrument,
            String baseAsset,
            String quoteAsset,
            long fillPriceTicks,
            long fillQuantitySteps,
            long feeRatePpm,
            CoreTreasuryState treasury) {
        OrderReservation reservation = requireReservation(user, order.orderId());
        String debitAsset = order.side() == CoreOrderSide.BUY ? quoteAsset : baseAsset;
        if (!reservation.asset().equals(debitAsset)) {
            throw new IllegalStateException("spot fill debit asset does not match reservation");
        }
        long quoteUnits = Math.multiplyExact(fillPriceTicks, fillQuantitySteps);
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRatePpm);
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        long reservationDebit;
        if (order.side() == CoreOrderSide.BUY) {
            reservationDebit = Math.addExact(quoteUnits, Math.max(0, Math.negateExact(feeDelta)));
            AssetBalance quoteBalance = requireBalance(user, quoteAsset).consumeLocked(quoteUnits);
            quoteBalance = applySpotFee(quoteBalance, feeDelta, true);
            balances.put(quoteAsset, quoteBalance);
            AssetBalance baseBalance = balances.getOrDefault(baseAsset, new AssetBalance(baseAsset, 0, 0));
            balances.put(baseAsset, baseBalance.credit(fillQuantitySteps));
        } else {
            reservationDebit = fillQuantitySteps;
            balances.put(baseAsset, requireBalance(user, baseAsset).consumeLocked(fillQuantitySteps));
            AssetBalance quoteBalance = balances.getOrDefault(quoteAsset, new AssetBalance(quoteAsset, 0, 0))
                    .credit(quoteUnits);
            balances.put(quoteAsset, applySpotFee(quoteBalance, feeDelta, false));
        }
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(order.orderId(), reservation.consume(reservationDebit));
        CoreUserState nextUser = user.transition(Math.incrementExact(user.revision()),
                balances, reservations, user.positions(), user.positionMode());
        return new SpotFillResult(nextUser, treasury.adjustFee(quoteAsset, Math.negateExact(feeDelta)),
                Math.negateExact(feeDelta));
    }

    static AssetBalance applySpotFee(AssetBalance balance, long feeDelta, boolean consumeLocked) {
        if (feeDelta < 0) {
            long feeUnits = Math.negateExact(feeDelta);
            return consumeLocked ? balance.consumeLocked(feeUnits) : balance.adjustAvailable(feeDelta);
        }
        return feeDelta == 0 ? balance : balance.credit(feeDelta);
    }
    record SpotFillResult(CoreUserState user, CoreTreasuryState treasury, long feeUnits) {
    }
}
