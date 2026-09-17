package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.model.CoreRiskSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.positionKey;

/** Builds risk query views from authoritative state without changing risk state. */
final class RiskSnapshotQueries {

    private RiskSnapshotQueries() {
    }

    static List<CoreRiskSnapshotView> find(
            TradingCoreState state, long userId, Set<String> snapshotKeys) {
        return snapshotKeys.stream()
                .map(state.riskState().snapshots()::get)
                .filter(Objects::nonNull)
                .filter(risk -> userId == 0 || risk.userId() == userId)
                .filter(risk -> {
                    CoreUserState user = state.user(risk.userId());
                    CorePositionState position = user.positions().get(positionKey(risk.symbol(), risk.positionSide()));
                    return position != null && position.signedQuantitySteps() != 0;
                })
                .map(risk -> toView(state, risk))
                .toList();
    }

    private static CoreRiskSnapshotView toView(TradingCoreState state, CoreRiskSnapshot risk) {
        CoreUserState user = state.user(risk.userId());
        CorePositionState position = user.positions().get(positionKey(risk.symbol(), risk.positionSide()));
        CoreInstrumentState instrument = state.instruments().get(risk.symbol());
        CoreMarkPriceState mark = state.riskState().markPrices().get(risk.symbol());
        if (position == null || instrument == null || mark == null) {
            throw new IllegalStateException("risk snapshot source state is missing");
        }
        long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        long walletBalance = crossWalletBalance(state, user, instrument.settleAsset());
        return new CoreRiskSnapshotView(risk.userId(), risk.symbol(), position.marginMode(), risk.positionSide(),
                position.instrumentChangeId(), instrument.settleAsset(), position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks(), notional, position.positionMarginUnits(),
                risk.priceSequence(), walletBalance, risk.equityUnits(), risk.unrealizedPnlUnits(),
                risk.maintenanceMarginUnits(), risk.marginRatioPpm(), risk.status().name());
    }

    private static long crossWalletBalance(TradingCoreState state, CoreUserState user, String asset) {
        AssetBalance balance = user.balances().get(asset);
        long wallet = balance == null ? 0 : balance.totalUnits();
        for (CorePositionState position : user.positions().values()) {
            if (position.marginMode() == CoreMarginMode.ISOLATED && position.marginAsset().equals(asset)) {
                wallet = Math.subtractExact(wallet, position.positionMarginUnits());
            }
        }
        for (var reservation : user.reservations().values()) {
            CoreOrderState order = state.orders().get(reservation.orderId());
            if (order != null && order.marginMode() == CoreMarginMode.ISOLATED
                    && reservation.asset().equals(asset)) {
                wallet = Math.subtractExact(wallet, reservation.remainingUnits());
            }
        }
        if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
        return wallet;
    }
}
