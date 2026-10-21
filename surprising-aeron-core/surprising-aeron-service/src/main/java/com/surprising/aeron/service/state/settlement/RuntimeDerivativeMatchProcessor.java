package com.surprising.aeron.service.state.settlement;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.order.OrderRuntime;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * 下单步骤 4.4 的衍生品结算入口：按成交事实推进本 Lane 的订单、持仓和剩余预留。
 *
 * <p>在线入口为 applyLane，由 MatcherSettlementEvent 按产品选择；
 * 成交金额、保证金、盈亏和费用由 RuntimeDerivativeFillCalculator 及对应产品规则计算。
 * taker 与 maker 分别由所属账户所有者应用，财务差额留给事件的账本边界，
 * 不把不同本位、交割和期权的资金公式合成一套。</p>
 */
public final class RuntimeDerivativeMatchProcessor {

    private RuntimeDerivativeMatchProcessor() {
    }

    public static TradingRuntimeState apply(TradingCoreState before, long takerOrderId,
                                            List<MatcherEvent> matches, TradingRuntimeState runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual match apply");
        }
        return applyRuntime(takerOrderId, matches, runtime, identities);
    }

    public static TradingRuntimeState applyRuntime(long takerOrderId, List<MatcherEvent> matches,
                                                   TradingRuntimeState runtime,
                                                   RuntimeIdentityRegistry identities) {
        if (matches == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual match apply");
        }
        runtime.assertOwner();
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        if (matches.isEmpty() && !taker.timeInForce().immediate()
                && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
            return runtime;
        }
        CoreInstrument instrument = runtime.instrument(identities.instrumentId(taker.symbolId()));
        if (instrument == null || instrument != taker.instrument()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        validateAndPrepare(takerOrderId, matches, runtime, identities);
        int settleAssetId = identities.assetId(instrument.settleAsset());
        RuntimeTreasuryDelta treasuryDelta = runtime.scratchTreasuryDelta();
        for (int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
            MatcherEvent match = matches.get(matchIndex);
            if (match.eventType() != MatcherEventType.TRADE) continue;
            taker = requireOpen(runtime, takerOrderId);
            OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
            boolean makerTerminal = match.size() == maker.remainingQuantitySteps();
            if (maker.userId() != match.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                    || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                throw new IllegalStateException("runtime match does not match authoritative orders");
            }
            applyFill(runtime, identities, instrument, taker, match.price(),
                    match.size(), true, settleAssetId, treasuryDelta);
            applyFill(runtime, identities, instrument, maker, match.price(),
                    match.size(), false, settleAssetId, treasuryDelta);
            if (makerTerminal) {
                long releaseUnits = runtime.reservation(maker.orderId()).reservedUnits();
                runtime.releaseTerminalReservation(maker.orderId());
                if (releaseUnits > 0) runtime.advanceUserRevision(maker.userId());
            }
        }
        taker = runtime.order(takerOrderId);
        if (!taker.canceled() && (taker.timeInForce().immediate()
                || taker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
            runtime.replaceOrder(terminal(taker));
        }
        if (runtime.order(takerOrderId).canceled()) {
            long releaseUnits = runtime.reservation(takerOrderId).reservedUnits();
            runtime.releaseTerminalReservation(takerOrderId);
            if (releaseUnits > 0) runtime.advanceUserRevision(runtime.order(takerOrderId).userId());
        }
        treasuryDelta.apply(runtime.treasury());
        treasuryDelta.clear();
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return runtime;
    }

    static void validateAndPrepare(long takerOrderId, List<MatcherEvent> matches,
                                   TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        validateMatches(runtime, taker, matches);
        CoreInstrument instrument = runtime.instrument(identities.instrumentId(taker.symbolId()));
        identities.positionKey(taker.userId(), instrument, taker.positionSide());
        for (int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
            MatcherEvent match = matches.get(matchIndex);
            if (match.eventType() != MatcherEventType.TRADE) continue;
            OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
            identities.positionKey(maker.userId(), instrument, maker.positionSide());
        }
    }

    static void applyLane(long takerOrderId, MatcherSettlementPlan plan, AccountLaneState lane, int laneId,
                          TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                          CoreInstrument instrument, int settleAssetId,
                          RuntimeTreasuryDelta treasuryDelta,
                          long commitTimestamp, long commitPosition) {
        if (plan == null || treasuryDelta == null || laneId < 0
                || laneId >= runtime.topology().accountLaneCount()) {
            throw new IllegalArgumentException("invalid perpetual matcher settlement plan");
        }
        OrderRuntime localTaker = lane.order(takerOrderId);
        if (plan.tradeCount() <= 1) {
            applyLaneDirect(takerOrderId, plan, lane, laneId, runtime, identities, instrument,
                    settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
        } else {
            applyLaneAccumulated(takerOrderId, plan, lane, laneId, runtime, identities, instrument,
                    settleAssetId, treasuryDelta, commitTimestamp, commitPosition, localTaker);
        }
        localTaker = lane.order(takerOrderId);
        if (localTaker != null) {
            if (!localTaker.canceled() && (localTaker.timeInForce().immediate()
                    || localTaker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
                runtime.updateOrderStatusInLane(lane, localTaker.orderId(), CoreOrderStatus.CANCELED,
                        Math.incrementExact(localTaker.revision()), commitTimestamp, commitPosition);
            }
            if (lane.order(takerOrderId).canceled()) {
                long releaseUnits = lane.reservation(takerOrderId).reservedUnits();
                runtime.releaseTerminalReservationInLane(lane, takerOrderId);
                if (releaseUnits > 0) runtime.advanceUserRevision(lane, localTaker.userId());
            }
        }
    }

    private static void applyLaneDirect(long takerOrderId, MatcherSettlementPlan plan, AccountLaneState lane, int laneId,
                                        TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                        CoreInstrument instrument, int settleAssetId,
                                        RuntimeTreasuryDelta treasuryDelta,
                                        long commitTimestamp, long commitPosition) {
        OrderRuntime localTaker = lane.order(takerOrderId);
        for (int index = plan.firstMatcherEvent(laneId); index >= 0;
                index = plan.nextMatcherEvent(index, laneId)) {
            MatcherEvent match = plan.matcherEvent(index);
            if (match.eventType() != MatcherEventType.TRADE
                    || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
            if (localTaker != null) {
                localTaker = requireOpen(lane, takerOrderId);
                applyFill(runtime, lane, identities, instrument, localTaker, match.price(), match.size(), true,
                        settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
            }
            OrderRuntime maker = lane.order(match.matchedOrderId());
            if (maker != null) {
                maker = requireOpen(lane, maker.orderId());
                boolean makerTerminal = match.size() == maker.remainingQuantitySteps();
                applyFill(runtime, lane, identities, instrument, maker, match.price(), match.size(), false,
                        settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
                if (makerTerminal) {
                    long releaseUnits = lane.reservation(maker.orderId()).reservedUnits();
                    runtime.releaseTerminalReservationInLane(lane, maker.orderId());
                    if (releaseUnits > 0) runtime.advanceUserRevision(lane, maker.userId());
                }
            }
        }
    }

    private static void applyLaneAccumulated(long takerOrderId, MatcherSettlementPlan plan, AccountLaneState lane, int laneId,
                                             TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                             CoreInstrument instrument, int settleAssetId,
                                             RuntimeTreasuryDelta treasuryDelta,
                                             long commitTimestamp, long commitPosition,
                                             OrderRuntime localTaker) {
        DerivativeSettlementAccumulator accumulator =
                lane.derivativeSettlementAccumulator();
        accumulator.reset(runtime, lane, identities, instrument, settleAssetId, commitTimestamp, commitPosition);
        try {
            for (int index = plan.firstMatcherEvent(laneId); index >= 0;
                    index = plan.nextMatcherEvent(index, laneId)) {
                MatcherEvent match = plan.matcherEvent(index);
                if (match.eventType() != MatcherEventType.TRADE
                        || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
                if (localTaker != null) {
                    accumulator.apply(takerOrderId, true, match.price(), match.size(), treasuryDelta);
                }
                OrderRuntime maker = lane.order(match.matchedOrderId());
                if (maker != null) {
                    accumulator.apply(maker.orderId(), false, match.price(), match.size(), treasuryDelta);
                }
            }
            accumulator.publish();
        } finally { accumulator.clear(); }
    }

    /** Coalesces derivative maker and taker fills into one immutable publish per order. */
    public static final class DerivativeSettlementAccumulator {
        private final LongObjectHashMap<RuntimeDerivativeFillCalculator.FillCursor> cursors =
                new LongObjectHashMap<>();
        private final LongLongHashMap activeOrderByUser = new LongLongHashMap();
        // A settlement uses one instrument; cache the two margin-mode leverage lookups by user.
        private final LongLongHashMap crossLeverageByUser = new LongLongHashMap();
        private final LongLongHashMap isolatedLeverageByUser = new LongLongHashMap();
        private final java.util.ArrayDeque<RuntimeDerivativeFillCalculator.FillCursor> free =
                new java.util.ArrayDeque<>();
        private TradingRuntimeState runtime;
        private AccountLaneState lane;
        private RuntimeIdentityRegistry identities;
        private CoreInstrument instrument;
        private int settleAssetId;
        private long commitTimestamp;
        private long commitPosition;

        void reset(TradingRuntimeState runtime, AccountLaneState lane, RuntimeIdentityRegistry identities,
                   CoreInstrument instrument, int settleAssetId,
                   long commitTimestamp, long commitPosition) {
            this.runtime = runtime;
            this.lane = lane;
            this.identities = identities;
            this.instrument = instrument;
            this.settleAssetId = settleAssetId;
            this.commitTimestamp = commitTimestamp;
            this.commitPosition = commitPosition;
        }

        void apply(long orderId, boolean taker, long price, long quantity,
                   RuntimeTreasuryDelta treasury) {
            RuntimeDerivativeFillCalculator.FillCursor cursor = cursors.get(orderId);
            if (cursor == null) {
                OrderRuntime order = requireOpen(lane, orderId);
                long activeOrder = activeOrderByUser.get(order.userId());
                if (activeOrder != 0 && activeOrder != orderId) flush(activeOrder);
                LongLongHashMap leverageByUser = order.marginMode()
                        == com.surprising.aeron.protocol.CoreMarginMode.CROSS
                        ? crossLeverageByUser : isolatedLeverageByUser;
                long leverage;
                if (leverageByUser.containsKey(order.userId())) {
                    leverage = leverageByUser.get(order.userId());
                } else {
                    Long configured = runtime.leverage(lane, order.userId(), instrument.instrumentId(), order.marginMode());
                    leverage = configured == null ? instrument.maxLeveragePpm() : configured;
                    leverageByUser.put(order.userId(), leverage);
                }
                long key = identities.preparedPositionKey(lane, order);
                cursor = free.pollFirst();
                if (cursor == null) cursor = new RuntimeDerivativeFillCalculator.FillCursor();
                RuntimeDerivativeFillCalculator.begin(cursor, runtime, lane, instrument, order, key,
                        leverage, settleAssetId, commitTimestamp, commitPosition);
                cursors.put(orderId, cursor);
                activeOrderByUser.put(order.userId(), orderId);
            }
            cursor.applyNext(price, quantity, taker, treasury);
        }

        void publish() {
            cursors.forEachKeyValue((orderId, cursor) -> publishCursor(cursor));
        }

        void clear() {
            cursors.forEachKeyValue((orderId, cursor) -> {
                cursor.clear();
                free.addFirst(cursor);
            });
            cursors.clear();
            activeOrderByUser.clear();
            crossLeverageByUser.clear();
            isolatedLeverageByUser.clear();
            runtime = null;
            lane = null;
            identities = null;
            instrument = null;
        }

        private void flush(long orderId) {
            RuntimeDerivativeFillCalculator.FillCursor cursor = cursors.remove(orderId);
            if (cursor == null) throw new IllegalStateException("active derivative cursor is missing");
            publishCursor(cursor);
            cursor.clear();
            free.addFirst(cursor);
        }

        private void publishCursor(RuntimeDerivativeFillCalculator.FillCursor cursor) {
            OrderRuntime order = cursor.publish(runtime, lane, cursor.positionKey(), null);
            if (order != null && order.canceled()) {
                long releaseUnits = lane.reservation(order.orderId()).reservedUnits();
                runtime.releaseTerminalReservationInLane(lane, order.orderId());
                if (releaseUnits > 0) runtime.advanceUserRevision(lane, order.userId());
            }
        }
    }

    private static void validateMatches(TradingRuntimeState runtime, OrderRuntime taker,
                                        List<MatcherEvent> matches) {
        long takerRemaining = taker.remainingQuantitySteps();
        LongLongHashMap makerRemaining = runtime.matcherSettlementRemainingScratch();
        makerRemaining.clear();
        try {
            for (int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
                MatcherEvent match = matches.get(matchIndex);
                if (match == null) {
                    throw new IllegalArgumentException("runtime match is required");
                }
                if (match.eventType() != MatcherEventType.TRADE) continue;
                OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
                if (maker.userId() != match.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                        || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                    throw new IllegalStateException("runtime match does not match authoritative orders");
                }
                if (match.price() <= 0 || match.size() <= 0) {
                    throw new IllegalArgumentException("invalid runtime match price or quantity");
                }
                takerRemaining = Math.subtractExact(takerRemaining, match.size());
                long remaining = makerRemaining.containsKey(maker.orderId())
                        ? makerRemaining.get(maker.orderId()) : maker.remainingQuantitySteps();
                remaining = Math.subtractExact(remaining, match.size());
                if (takerRemaining < 0 || remaining < 0) {
                    throw new IllegalStateException("fill exceeds runtime order remaining quantity");
                }
                makerRemaining.put(maker.orderId(), remaining);
            }
        } finally {
            makerRemaining.clear();
        }
    }

    private static void applyFill(TradingRuntimeState runtime,
                                  RuntimeIdentityRegistry identities, CoreInstrument instrument,
                                  OrderRuntime order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId, RuntimeTreasuryDelta treasuryDelta) {
        applyFill(runtime, runtime.accountLaneForUser(order.userId()), identities, instrument, order, priceTicks, quantitySteps, taker,
                settleAssetId, treasuryDelta, -1, -1);
    }

    private static void applyFill(TradingRuntimeState runtime, AccountLaneState lane,
                                  RuntimeIdentityRegistry identities, CoreInstrument instrument,
                                  OrderRuntime order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId, RuntimeTreasuryDelta treasuryDelta,
                                       long commitTimestamp, long commitPosition) {
        Long configuredLeverage = runtime.leverage(lane, order.userId(), instrument.instrumentId(), order.marginMode());
        long leverage = configuredLeverage == null ? instrument.maxLeveragePpm() : configuredLeverage;
        RuntimeDerivativeFillCalculator.apply(runtime, lane, identities, instrument, order,
                identities.preparedPositionKey(lane, order),
                priceTicks, quantitySteps, taker, leverage, settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
    }

    private static OrderRuntime requireOpen(AccountLaneState lane, long orderId) {
        OrderRuntime order = lane.order(orderId);
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0)
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        return order;
    }

    private static OrderRuntime requireOpen(TradingRuntimeState runtime, long orderId) {
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0) {
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        }
        return order;
    }

    private static OrderRuntime terminal(OrderRuntime order) {
        return order.withStatus(CoreOrderStatus.CANCELED, Math.incrementExact(order.revision()));
    }
}
