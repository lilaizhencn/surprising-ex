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
        return snapshots(runtime, identities, userId, null);
    }

    /** 生产查询只允许一个用户的所属Lane读取，市场参数由Owner在派发前固定。 */
    public static List<CoreRiskSnapshotView> userSnapshots(TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities, long userId, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("risk query Lane inputs are required");
        return snapshots(runtime, identities, userId, markets);
    }

    private static List<CoreRiskSnapshotView> snapshots(TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities, long userId, MarketInputs markets) {
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
                CoreInstrument instrument = markets == null ? runtime.instrument(instrumentId) : markets.instrument(position.symbolId());
                MarkPriceRuntime mark = markets == null ? runtime.markPrice(position.symbolId()) : markets.mark(position.symbolId());
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
                wallets.computeIfAbsent(instrument.settleAsset(), asset -> crossWalletBalance(runtime, identities, user, asset, markets));
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
            CoreAdlCandidateView candidate = position == null ? null
                    : adlCandidate(runtime, identities, key.userId(), position, normalizedAsset, null);
            if (candidate != null) addAdlCandidate(laneCandidates.get(
                    runtime.topology().accountLaneId(key.userId())), candidate, limit);
        }
        return mergeAdlCandidates(laneCandidates, limit);
    }

    /** 身份键由Owner路由准备；该方法只在目标Lane读取该持仓和该用户余额。 */
    public static CoreAdlCandidateView adlCandidate(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            long userId, long positionKey, String asset, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("ADL query Lane inputs are required");
        PositionRuntime position = positionKey == 0 ? null : runtime.position(positionKey);
        if (position == null) return null;
        if (position.userId() != userId) throw new IllegalStateException("ADL query position route differs");
        return adlCandidate(runtime, identities, userId, position, asset, markets);
    }

    private static CoreAdlCandidateView adlCandidate(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            long userId, PositionRuntime position, String asset, MarketInputs markets) {
        MarkPriceRuntime mark = markets == null ? runtime.markPrice(position.symbolId()) : markets.mark(position.symbolId());
        CoreInstrument instrument = markets == null ? runtime.instrument(identities.instrumentId(position.symbolId()))
                : markets.instrument(position.symbolId());
        if (position.signedQuantitySteps() == 0 || !identities.asset(position.assetId()).equals(asset)
                || mark == null || instrument == null
                || !(instrument.contractType().isPerpetual() || instrument.contractType().isDelivery()
                || instrument.contractType().isOption()) || !instrument.settleAsset().equals(asset)) return null;
        long profit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks());
        if (profit <= 0) return null;
        long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        long margin = position.marginMode() == CoreMarginMode.ISOLATED
                ? position.positionMarginUnits() : totalBalance(runtime, identities, userId, asset, markets);
        long profitRate = ratio(profit, notional);
        long leverage = margin <= 0 ? Long.MAX_VALUE : ratio(notional, margin);
        long priority = multiplyDivideCapped(profitRate, leverage, PPM);
        return new CoreAdlCandidateView(userId, instrument.instrumentId(), asset, position.marginMode(),
                position.positionSide(), position.signedQuantitySteps(), position.entryPriceTicks(),
                mark.markPriceTicks(), mark.priceSequence(), notional, profit, margin, profitRate, leverage, priority);
    }

    public static void addAdlCandidate(ArrayList<CoreAdlCandidateView> candidates, CoreAdlCandidateView candidate, int limit) {
        int insertAt = java.util.Collections.binarySearch(candidates, candidate, ADL_ORDER);
        candidates.add(insertAt < 0 ? -insertAt - 1 : insertAt, candidate);
        if (candidates.size() > limit) candidates.removeLast();
    }

    /** 只合并有界查询DTO，沿用原Lane归并排序和相同优先级时的顺序。 */
    public static List<CoreAdlCandidateView> mergeAdlCandidates(List<? extends List<CoreAdlCandidateView>> laneCandidates,
            int limit) {
        int[] cursors = new int[laneCandidates.size()];
        ArrayList<CoreAdlCandidateView> result = new ArrayList<>(limit);
        while (result.size() < limit) {
            int bestLane = -1;
            CoreAdlCandidateView best = null;
            for (int laneId = 0; laneId < laneCandidates.size(); laneId++) {
                List<CoreAdlCandidateView> lane = laneCandidates.get(laneId);
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

    /** 本次查询的不可变市场事实引用。Owner构造后只读，完成后释放；没有账户/持仓副本。 */
    public static final class MarketInputs {
        private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments;
        private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPriceRuntime> marks;
        private final java.util.Map<String, Integer> assetIds;

        private MarketInputs(org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments,
                org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPriceRuntime> marks,
                java.util.Map<String, Integer> assetIds) {
            this.instruments = instruments;
            this.marks = marks;
            this.assetIds = java.util.Map.copyOf(assetIds);
        }

        public static MarketInputs capture(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                Iterable<String> instrumentIds) {
            var instruments = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument>();
            var marks = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPriceRuntime>();
            var assets = new java.util.HashMap<String, Integer>();
            for (String instrumentId : instrumentIds) {
                Integer symbolId = identities.findSymbolId(instrumentId);
                if (symbolId == null || instruments.containsKey(symbolId)) continue;
                CoreInstrument instrument = runtime.instrument(instrumentId);
                instruments.put(symbolId, instrument == null ? null : instrument.captureForQuery());
                marks.put(symbolId, runtime.markPrice(symbolId));
                Integer assetId = instrument == null ? null : identities.findAssetId(instrument.settleAsset());
                if (assetId != null) assets.put(instrument.settleAsset(), assetId);
            }
            return new MarketInputs(instruments, marks, assets);
        }

        private CoreInstrument instrument(int symbolId) { return instruments.get(symbolId); }
        private MarkPriceRuntime mark(int symbolId) { return marks.get(symbolId); }
    }

    private static long crossWalletBalance(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId, String asset, MarketInputs markets) {
        long wallet = totalBalance(runtime, identities, userId, asset, markets);
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
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId, String asset, MarketInputs markets) {
        Integer assetId = markets == null ? identities.findAssetId(asset) : markets.assetIds.get(asset);
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
