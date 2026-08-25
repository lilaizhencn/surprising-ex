package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RuntimeRiskQueryService {

    private static final long PPM = 1_000_000L;

    private RuntimeRiskQueryService() {
    }

    public static List<CoreRiskSnapshotView> snapshots(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId) {
        ArrayList<RiskEntry> entries = new ArrayList<>();
        if (userId == 0) {
            if (runtime.riskSnapshotsForSnapshot().size()
                    > RuntimeOperationalQueryService.MAX_QUERY_ENTITIES) {
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            }
            runtime.riskSnapshotsForSnapshot().forEachKeyValue(
                    (positionKey, risk) -> entries.add(new RiskEntry(positionKey, risk)));
        } else {
            if (runtime.positionCountForUser(userId) > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            }
            for (long positionKey : runtime.positionKeysForUser(userId).toArray()) {
                RiskSnapshotRuntime risk = runtime.riskSnapshotsForSnapshot().get(positionKey);
                if (risk != null) entries.add(new RiskEntry(positionKey, risk));
            }
        }
        if (entries.size() > RuntimeOperationalQueryService.MAX_QUERY_ENTITIES) {
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        }
        entries.sort(Comparator.comparingLong((RiskEntry entry) -> entry.risk().userId())
                .thenComparing(entry -> identities.symbol(entry.risk().symbolId()))
                .thenComparingInt(entry -> entry.risk().positionSide().ordinal()));
        ArrayList<CoreRiskSnapshotView> result = new ArrayList<>(entries.size());
        for (RiskEntry entry : entries) {
            RiskSnapshotRuntime risk = entry.risk();
            PositionRuntime position = runtime.position(entry.positionKey());
            if (position == null || position.signedQuantitySteps() == 0) continue;
            String symbol = identities.symbol(risk.symbolId());
            CoreInstrumentState instrument = runtime.instrument(symbol);
            MarkPriceRuntime mark = runtime.markPrice(risk.symbolId());
            if (instrument == null || mark == null) throw new IllegalStateException("risk query source is missing");
            long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                    instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                    instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
            long wallet = crossWalletBalance(runtime, identities, risk.userId(), instrument.settleAsset());
            result.add(new CoreRiskSnapshotView(risk.userId(), symbol, position.marginMode(), risk.positionSide(),
                    position.instrumentVersion(), instrument.settleAsset(), position.signedQuantitySteps(),
                    position.entryPriceTicks(), mark.markPriceTicks(), notional, position.positionMarginUnits(),
                    risk.priceSequence(), wallet, risk.equityUnits(), risk.unrealizedPnlUnits(),
                    risk.maintenanceMarginUnits(), risk.marginRatioPpm(), risk.status().name()));
        }
        return List.copyOf(result);
    }

    public static List<CoreAdlCandidateView> adlCandidates(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, String asset,
            Iterable<AdlPositionIndex.PositionKey> keys, int limit) {
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        ArrayList<CoreAdlCandidateView> result = new ArrayList<>();
        int scanned = 0;
        for (AdlPositionIndex.PositionKey key : keys) {
            if (++scanned > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            }
            String positionName = key.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? key.symbol() : key.symbol() + ':' + key.positionSide().name();
            Long positionKey = identities.findPositionKey(key.userId(), positionName);
            PositionRuntime position = positionKey == null ? null : runtime.position(positionKey);
            Integer symbolId = identities.findSymbolId(key.symbol());
            MarkPriceRuntime mark = symbolId == null ? null : runtime.markPrice(symbolId);
            CoreInstrumentState instrument = runtime.instrument(key.symbol());
            if (position == null || position.signedQuantitySteps() == 0
                    || !identities.asset(position.assetId()).equals(normalizedAsset)
                    || mark == null || instrument == null || !instrument.contractType().isPerpetual()
                    || !instrument.settleAsset().equals(normalizedAsset)) continue;
            long profit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                    position.entryPriceTicks(), mark.markPriceTicks());
            if (profit <= 0) continue;
            long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                    instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                    instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
            long margin = position.marginMode() == CoreMarginMode.ISOLATED
                    ? position.positionMarginUnits() : totalBalance(runtime, identities, key.userId(), normalizedAsset);
            long profitRate = ratio(profit, notional);
            long leverage = margin <= 0 ? Long.MAX_VALUE : ratio(notional, margin);
            long priority = multiplyDivideCapped(profitRate, leverage, PPM);
            result.add(new CoreAdlCandidateView(key.userId(), key.symbol(), normalizedAsset, position.marginMode(),
                    position.positionSide(), position.signedQuantitySteps(), position.entryPriceTicks(),
                    mark.markPriceTicks(), mark.priceSequence(), notional, profit, margin, profitRate, leverage, priority));
        }
        return result.stream().sorted(Comparator.comparingLong(CoreAdlCandidateView::priorityScorePpm).reversed()
                        .thenComparing(Comparator.comparingLong(CoreAdlCandidateView::unrealizedProfitUnits).reversed())
                        .thenComparingLong(CoreAdlCandidateView::userId).thenComparing(CoreAdlCandidateView::symbol))
                .limit(limit).toList();
    }

    private static long crossWalletBalance(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId, String asset) {
        long wallet = totalBalance(runtime, identities, userId, asset);
        if (runtime.positionCountForUser(userId) > RuntimeOperationalQueryService.MAX_INDEX_SCAN
                || runtime.reservationCountForUser(userId) > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        }
        for (long positionKey : runtime.positionKeysForUser(userId).toArray()) {
            PositionRuntime position = runtime.position(positionKey);
            if (position != null && position.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(position.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, position.positionMarginUnits());
            }
        }
        for (long orderId : runtime.reservationIdsForUser(userId).toArray()) {
            ReservationRuntime reservation = runtime.reservation(orderId);
            OrderRuntime order = runtime.order(orderId);
            if (reservation != null && order != null && order.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(reservation.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, reservation.reservedUnits());
            }
        }
        if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
        return wallet;
    }

    private static long totalBalance(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId, String asset) {
        Integer assetId = identities.findAssetId(asset);
        BalanceRuntime balance = assetId == null ? null : runtime.balance(userId, assetId);
        return balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
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

    private record RiskEntry(long positionKey, RiskSnapshotRuntime risk) {
    }
}
