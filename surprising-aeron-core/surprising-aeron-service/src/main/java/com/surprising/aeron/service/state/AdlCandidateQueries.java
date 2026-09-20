package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CorePositionState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.positionKey;

/** Builds ADL candidate views without changing positions, balances, or liquidation state. */
final class AdlCandidateQueries {

    private static final long PPM = 1_000_000L;

    private AdlCandidateQueries() {
    }

    static List<CoreAdlCandidateView> find(
            TradingCoreState state, String asset, int limit, AdlPositionIndex index) {
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        ArrayList<CoreAdlCandidateView> result = new ArrayList<>();
        Iterable<AdlPositionIndex.PositionKey> keys = index == null
                ? state.users().values().stream().flatMap(user -> user.positions().values().stream()
                        .filter(position -> position.signedQuantitySteps() != 0
                                && position.marginAsset().equals(normalizedAsset))
                        .map(position -> new AdlPositionIndex.PositionKey(user.userId(), position.symbol(),
                                position.positionSide()))).toList()
                : index.positions(normalizedAsset);
        for (AdlPositionIndex.PositionKey key : keys) {
            CoreUserState user = state.user(key.userId());
            CorePositionState position = user == null ? null
                    : user.positions().get(positionKey(key.symbol(), key.positionSide()));
            if (user == null || position == null) continue;
            if (position.signedQuantitySteps() == 0 || !position.marginAsset().equals(normalizedAsset)) continue;
            CoreInstrument instrument = state.instruments().get(position.symbol());
            CoreMarkPriceState mark = state.riskState().markPrices().get(position.symbol());
            if (instrument == null || mark == null
                    || !(instrument.contractType().isPerpetual() || instrument.contractType().isDelivery()
                    || instrument.contractType().isOption())
                    || !instrument.settleAsset().equals(normalizedAsset)) continue;
            long profit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                    position.entryPriceTicks(), mark.markPriceTicks());
            if (profit <= 0) continue;
            long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                    instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                    instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
            long margin = position.marginMode() == CoreMarginMode.ISOLATED
                    ? position.positionMarginUnits()
                    : user.totalUnits(normalizedAsset);
            long profitRate = ratio(profit, notional);
            long leverage = margin <= 0 ? Long.MAX_VALUE : ratio(notional, margin);
            long priority = multiplyDivideCapped(profitRate, leverage, PPM);
            result.add(new CoreAdlCandidateView(user.userId(), position.symbol(),
                    normalizedAsset, position.marginMode(), position.positionSide(),
                    position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks(),
                    mark.priceSequence(), notional, profit, margin, profitRate, leverage, priority));
        }
        return result.stream().sorted(Comparator
                        .comparingLong(CoreAdlCandidateView::priorityScorePpm).reversed()
                        .thenComparing(Comparator.comparingLong(
                                CoreAdlCandidateView::unrealizedProfitUnits).reversed())
                        .thenComparingLong(CoreAdlCandidateView::userId)
                        .thenComparing(CoreAdlCandidateView::symbol))
                .limit(limit).toList();
    }

    private static long ratio(long numerator, long denominator) {
        return numerator <= 0 || denominator <= 0 ? 0 : multiplyDivideCapped(numerator, PPM, denominator);
    }

    private static long multiplyDivideCapped(long left, long right, long divisor) {
        try {
            return Math.multiplyExact(left, right) / divisor;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
