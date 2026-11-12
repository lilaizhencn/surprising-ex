package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 按查询时的持仓和行情即时计算风险及 ADL 候选，不把后台分批强平扫描的旧风险缓存直接当查询结果。生产查询由 Owner 固定市场输入，再由所属账户 Lane 读取账户。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RiskQueries {

    /** 候选顺序依次为优先分数从大到小、未实现盈利从大到小、用户编号从小到大、币对编号字符串从小到大。 */
    private static final Comparator<CoreAdlCandidateView> ADL_ORDER =
            Comparator.comparingLong(CoreAdlCandidateView::priorityScorePpm).reversed()
                    .thenComparing(Comparator.comparingLong(
                            CoreAdlCandidateView::unrealizedProfitUnits).reversed())
                    .thenComparingLong(CoreAdlCandidateView::userId)
                    .thenComparing(CoreAdlCandidateView::instrumentId);

    /** 比例放大一百万倍后的统一分母，避免用浮点数计算查询风险和 ADL 分数。 */
    private static final long PPM = 1_000_000L;

    /** 生产账户 Lane 的单用户入口，要求正用户编号和 Owner 已固定的市场输入，再按当前持仓计算风险结果。 */
    public static List<CoreRiskSnapshotView> userSnapshots(TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities, long userId, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("risk query Lane inputs are required");
        ArrayList<CoreRiskSnapshotView> result = new ArrayList<>();
        if (runtime.positionCountForUser(userId) > OperationalQueries.MAX_INDEX_SCAN)
            throw new QueryTooLargeException();
        var values = new ArrayList<PositionValuation>();
        var cross = new java.util.HashMap<String, long[]>();
        var wallets = new java.util.HashMap<String, Long>();
        for (long key : runtime.positionKeysForUser(userId)) {
            Position position = runtime.position(key);
            if (position == null || position.signedQuantitySteps() == 0) continue;
            if (values.size() >= OperationalQueries.MAX_QUERY_ENTITIES)
                throw new QueryTooLargeException();
            CoreInstrument instrument = markets.instrument(position.symbolId());
            MarkPrice mark = markets.mark(position.symbolId());
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
            wallets.computeIfAbsent(instrument.settleAsset(), asset -> crossWalletBalance(runtime, identities, userId, asset, markets));
            if (position.marginMode() == CoreMarginMode.CROSS) {
                long[] totals = cross.computeIfAbsent(instrument.settleAsset(), asset -> new long[2]);
                totals[0] = Math.addExact(totals[0], equityDelta);
                totals[1] = Math.addExact(totals[1], maintenance);
            }
        }
        for (PositionValuation value : values) {
            Position position = value.position();
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
            result.add(new CoreRiskSnapshotView(userId, value.instrument().instrumentId(), position.marginMode(),
                    position.positionSide(), asset, position.signedQuantitySteps(), position.entryPriceTicks(),
                    value.mark().markPriceTicks(), value.notional(), position.positionMarginUnits(),
                    value.mark().priceSequence(), wallet, equity, value.pnl(), value.maintenance(), ratio,
                    CoreRiskStatus.of(ratio).name()));
        }
        result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                .thenComparing(CoreRiskSnapshotView::instrumentId)
                // 排序时当前比较的查询结果行，最后按持仓腿的枚举顺序打破同用户同币对的顺序。
                .thenComparingInt(value -> value.positionSide().ordinal()));
        return List.copyOf(result);
    }

    /** 生产 Lane 按 Owner 准备的整数持仓键读取目标用户仓位，核对路由用户一致，再用固定市场输入计算候选。 */
    public static CoreAdlCandidateView adlCandidate(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            long userId, long positionKey, String asset, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("ADL query Lane inputs are required");
        Position position = positionKey == 0 ? null : runtime.position(positionKey);
        if (position == null) return null;
        if (position.userId() != userId) throw new IllegalStateException("ADL query position route differs");
        MarkPrice mark = markets.mark(position.symbolId());
        CoreInstrument instrument = markets.instrument(position.symbolId());
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
                ? position.positionMarginUnits() : totalBalance(runtime, userId, asset, markets);
        long profitRate = ratio(profit, notional);
        long leverage = margin <= 0 ? Long.MAX_VALUE : ratio(notional, margin);
        long priority = multiplyDivideCapped(profitRate, leverage, PPM);
        return new CoreAdlCandidateView(userId, instrument.instrumentId(), asset, position.marginMode(),
                position.positionSide(), position.signedQuantitySteps(), position.entryPriceTicks(),
                mark.markPriceTicks(), mark.priceSequence(), notional, profit, margin, profitRate, leverage, priority);
    }

    /** 按统一 ADL 比较规则二分插入候选，超过条数限制时删除最末项，调用方传入的列表应已按同一规则排序。 */
    public static void addAdlCandidate(ArrayList<CoreAdlCandidateView> candidates, CoreAdlCandidateView candidate, int limit) {
        int insertAt = java.util.Collections.binarySearch(candidates, candidate, ADL_ORDER);
        candidates.add(insertAt < 0 ? -insertAt - 1 : insertAt, candidate);
        if (candidates.size() > limit) candidates.removeLast();
    }

    /** 对各 Lane 已排序的有限候选列表做多路归并，每次选最优头项，达到 limit 或全部耗尽后结束。 */
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

    private static long crossWalletBalance(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, long userId, String asset, MarketInputs markets) {
        long wallet = totalBalance(runtime, userId, asset, markets);
        if (runtime.reservationCountForUser(userId) > OperationalQueries.MAX_INDEX_SCAN) {
            throw new QueryTooLargeException();
        }
        for (long positionKey : runtime.positionKeysForUser(userId)) {
            Position position = runtime.position(positionKey);
            if (position != null && position.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(position.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, position.positionMarginUnits());
            }
        }
        for (long orderId : runtime.reservationIdsForUser(userId)) {
            OrderFundsReservation reservation = runtime.reservation(orderId);
            Order order = runtime.order(orderId);
            if (reservation != null && order != null && order.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(reservation.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, reservation.reservedUnits());
            }
        }
        if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
        return wallet;
    }

    /** 按结算资产定位用户余额，缺少资产编号或余额记录时按 0，存在时返回可用与冻结金额之和并检查整数溢出。 */
    private static long totalBalance(
            TradingAccountRuntime runtime, long userId, String asset, MarketInputs markets) {
        Integer assetId = markets.assetIds.get(asset);
        AccountBalance balance = assetId == null ? null : runtime.balance(userId, assetId);
        return balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
    }

    private static long ratio(long numerator, long denominator) {
        return numerator <= 0 || denominator <= 0 ? 0 : multiplyDivideCapped(numerator, PPM, denominator);
    }

    /** 先精确整数乘法再做整数除法，发生乘法溢出等算术异常时返回最大 long。按中间运算是否溢出来封顶，不重试大整数计算。 */
    private static long multiplyDivideCapped(long left, long right, long divisor) {
        try {
            return Math.multiplyExact(left, right) / divisor;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /** 单个持仓在本次查询中的临时估值，先算并保留本仓盈亏、维持保证金和市值变化，再在用户内按结算资产合并全仓风险。 */
    private record PositionValuation(Position position, CoreInstrument instrument, MarkPrice mark,
                                     long pnl, long maintenance, long equityDelta, long notional) {
    }
}
