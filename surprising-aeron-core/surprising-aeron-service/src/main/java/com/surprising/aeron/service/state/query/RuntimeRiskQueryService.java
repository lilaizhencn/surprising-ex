package com.surprising.aeron.service.state.query;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;

import com.surprising.aeron.service.state.math.*;

import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.risk.*;

import com.surprising.aeron.service.state.model.AssetBalance;

import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RuntimeRiskQueryService {

    private static final Comparator<CoreAdlCandidateView> ADL_ORDER =
            Comparator.comparingLong(CoreAdlCandidateView::priorityScorePpm).reversed()
                    .thenComparing(Comparator.comparingLong(
                            CoreAdlCandidateView::unrealizedProfitUnits).reversed())
                    .thenComparingLong(CoreAdlCandidateView::userId)
                    .thenComparing(CoreAdlCandidateView::instrumentId);

    private static final long PPM = 1_000_000L;

    private RuntimeRiskQueryService() {
    }

    public static List<CoreRiskSnapshotView> snapshots(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId) {
        // Queries value the current positions and prices together. Persisted risk snapshots
        // belong to the bounded liquidation scan and may still refer to an earlier price.
        var users = new java.util.TreeSet<Long>();
        if (userId == 0) {
            runtime.usersWithPublishedPositions(RuntimeOperationalQueryService.MAX_INDEX_SCAN).forEach(users::add);
        } else {
            users.add(userId);
        }
        ArrayList<CoreRiskSnapshotView> result = new ArrayList<>();
        int scanned = 0;
        for (long user : users) {
            scanned = Math.addExact(scanned, runtime.positionCountForUser(user));
            if (scanned > RuntimeOperationalQueryService.MAX_INDEX_SCAN)
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            var values = new ArrayList<PositionValuation>();
            // Temporary query totals, grouped by settlement asset. They never mutate Core state.
            var cross = new java.util.HashMap<String, long[]>();
            var wallets = new java.util.HashMap<String, Long>();
            for (long key : runtime.positionKeysForUser(user).toArray()) {
                PositionRuntime position = runtime.position(key);
                if (position == null || position.signedQuantitySteps() == 0) continue;
                if (result.size() + values.size() >= RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)
                    throw new RuntimeOperationalQueryService.QueryTooLargeException();
                String instrumentId = identities.instrumentId(position.symbolId());
                CoreInstrument instrument = runtime.instrument(instrumentId);
                MarkPriceRuntime mark = runtime.markPrice(position.symbolId());
                if (instrument == null || mark == null)
                    throw new IllegalStateException("risk query source is missing");
                long quantity = position.signedQuantitySteps();
                long pnl = CoreContractMath.pnlUnits(instrument, quantity, position.entryPriceTicks(), mark.markPriceTicks());
                long maintenance = CoreContractMath.maintenanceMarginUnits(instrument, quantity,
                        mark.markPriceTicks(), mark.indexPriceTicks(), mark.forwardPriceTicks());
                long equityDelta = instrument.contractType().isOption()
                        ? OptionContractMath.optionMarketValueUnits(instrument, quantity, mark.markPriceTicks()) : pnl;
                long notional = CoreContractMath.notionalUnits(instrument, Math.absExact(quantity), mark.markPriceTicks());
                values.add(new PositionValuation(position, instrument, mark, pnl, maintenance, equityDelta, notional));
                wallets.computeIfAbsent(instrument.settleAsset(), asset -> crossWalletBalance(runtime, identities, user, asset));
                if (position.marginMode() == CoreMarginMode.CROSS) {
                    long[] totals = cross.computeIfAbsent(instrument.settleAsset(), asset -> new long[2]);
                    totals[0] = Math.addExact(totals[0], equityDelta);
                    totals[1] = Math.addExact(totals[1], maintenance);
                }
            }
            for (PositionValuation value : values) {
                PositionRuntime position = value.position();
                String asset = value.instrument().settleAsset();
                long wallet = wallets.get(asset);
                long equity, maintenance;
                if (position.marginMode() == CoreMarginMode.CROSS) {
                    long[] totals = cross.get(asset);
                    equity = Math.addExact(wallet, totals[0]);
                    maintenance = totals[1];
                } else {
                    equity = Math.addExact(position.positionMarginUnits(), value.equityDelta());
                    maintenance = value.maintenance();
                }
                long ratio = maintenance == 0 ? 0 : equity <= 0 ? Long.MAX_VALUE
                        : multiplyDivideCapped(maintenance, PPM, equity);
                result.add(new CoreRiskSnapshotView(user, value.instrument().instrumentId(), position.marginMode(),
                        position.positionSide(), asset, position.signedQuantitySteps(), position.entryPriceTicks(),
                        value.mark().markPriceTicks(), value.notional(), position.positionMarginUnits(),
                        value.mark().priceSequence(), wallet, equity, value.pnl(), value.maintenance(), ratio,
                        CoreRiskPolicy.status(ratio).name()));
            }
        }
        result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                .thenComparing(CoreRiskSnapshotView::instrumentId)
                .thenComparingInt(value -> value.positionSide().ordinal()));
        return List.copyOf(result);
    }

    public static List<CoreAdlCandidateView> adlCandidates(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, String asset,
            Iterable<AdlPositionIndex.PositionKey> keys, int limit) {
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        ArrayList<ArrayList<CoreAdlCandidateView>> laneCandidates =
                new ArrayList<>(runtime.topology().accountLaneCount());
        for (int laneId = 0; laneId < runtime.topology().accountLaneCount(); laneId++) {
            laneCandidates.add(new ArrayList<>(Math.min(limit, 64)));
        }
        int scanned = 0;
        for (AdlPositionIndex.PositionKey key : keys) {
            if (++scanned > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            }
            String positionName = key.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? key.instrumentId() : key.instrumentId() + ':' + key.positionSide().name();
            Long positionKey = identities.findPositionKey(key.userId(), positionName);
            PositionRuntime position = positionKey == null ? null : runtime.position(positionKey);
            Integer symbolId = identities.findSymbolId(key.instrumentId());
            MarkPriceRuntime mark = symbolId == null ? null : runtime.markPrice(symbolId);
            CoreInstrument instrument = runtime.instrument(key.instrumentId());
            if (position == null || position.signedQuantitySteps() == 0
                    || !identities.asset(position.assetId()).equals(normalizedAsset)
                    || mark == null || instrument == null
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
                    ? position.positionMarginUnits() : totalBalance(runtime, identities, key.userId(), normalizedAsset);
            long profitRate = ratio(profit, notional);
            long leverage = margin <= 0 ? Long.MAX_VALUE : ratio(notional, margin);
            long priority = multiplyDivideCapped(profitRate, leverage, PPM);
            CoreAdlCandidateView candidate = new CoreAdlCandidateView(
                    key.userId(), key.instrumentId(), normalizedAsset, position.marginMode(),
                    position.positionSide(), position.signedQuantitySteps(), position.entryPriceTicks(),
                    mark.markPriceTicks(), mark.priceSequence(), notional, profit, margin, profitRate, leverage,
                    priority);
            ArrayList<CoreAdlCandidateView> lane = laneCandidates.get(
                    runtime.topology().accountLaneId(key.userId()));
            int insertAt = java.util.Collections.binarySearch(lane, candidate, ADL_ORDER);
            if (insertAt < 0) insertAt = -insertAt - 1;
            lane.add(insertAt, candidate);
            if (lane.size() > limit) lane.removeLast();
        }
        int[] cursors = new int[laneCandidates.size()];
        ArrayList<CoreAdlCandidateView> result = new ArrayList<>(limit);
        while (result.size() < limit) {
            int bestLane = -1;
            CoreAdlCandidateView best = null;
            for (int laneId = 0; laneId < laneCandidates.size(); laneId++) {
                ArrayList<CoreAdlCandidateView> lane = laneCandidates.get(laneId);
                if (cursors[laneId] >= lane.size()) continue;
                CoreAdlCandidateView candidate = lane.get(cursors[laneId]);
                if (best == null || ADL_ORDER.compare(candidate, best) < 0) {
                    best = candidate;
                    bestLane = laneId;
                }
            }
            if (bestLane < 0) break;
            result.add(best);
            cursors[bestLane]++;
        }
        return List.copyOf(result);
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

    private record PositionValuation(PositionRuntime position, CoreInstrument instrument, MarkPriceRuntime mark,
                                     long pnl, long maintenance, long equityDelta, long notional) {
    }
}
