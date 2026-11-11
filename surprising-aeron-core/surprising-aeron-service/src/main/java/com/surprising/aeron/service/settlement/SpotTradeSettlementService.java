package com.surprising.aeron.service.settlement;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** 下单步骤 4.4 的现货结算。构造约定：只提供静态现货结算入口，不允许单独创建工具实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SpotTradeSettlementService {

    /** 在线所属 Lane 入口，至多一笔成交直接应用，多笔复用现货累加器。 */
    static void applyLane(long takerOrderId, TradeSettlement.Plan plan, AccountPartition lane, int laneId,
                          TradingAccountRuntime runtime, CoreInstrument instrument,
                          int baseAssetId, int quoteAssetId, RuntimeTreasuryDelta treasuryDelta,
                          long commitTimestamp, long commitPosition) {
        if (plan == null || treasuryDelta == null || laneId < 0
                || laneId >= runtime.topology().accountLaneCount()) {
            throw new IllegalArgumentException("invalid spot matcher settlement plan");
        }
        Order localTaker = lane.order(takerOrderId);
        if (plan.tradeCount() <= 1) {
            applyLaneDirect(takerOrderId, plan, lane, laneId, runtime, instrument, baseAssetId,
                    quoteAssetId, treasuryDelta, commitTimestamp, commitPosition, localTaker);
        } else {
            applyLaneAccumulated(takerOrderId, plan, lane, laneId, runtime, instrument, baseAssetId,
                    quoteAssetId, treasuryDelta, commitTimestamp, commitPosition, localTaker);
        }
        localTaker = lane.order(takerOrderId);
        if (localTaker != null) {
            if (!localTaker.canceled() && (localTaker.timeInForce().immediate()
                    || localTaker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
                runtime.updateOrderStatusInLane(lane, localTaker.orderId(), CoreOrderStatus.CANCELED,
                        Math.incrementExact(localTaker.revision()), commitTimestamp, commitPosition);
            }
            releaseTerminalReservation(runtime, lane, takerOrderId);
        }
    }

    private static void applyLaneDirect(long takerOrderId, TradeSettlement.Plan plan, AccountPartition lane, int laneId,
                                        TradingAccountRuntime runtime, CoreInstrument instrument,
                                        int baseAssetId, int quoteAssetId,
                                        RuntimeTreasuryDelta treasuryDelta,
                                        long commitTimestamp, long commitPosition,
                                        Order localTaker) {
        for (int index = plan.firstMatcherEvent(laneId); index >= 0;
                index = plan.nextMatcherEvent(index, laneId)) {
            MatcherEvent match = plan.matcherEvent(index);
            if (match.eventType() != MatcherEventType.TRADE
                    || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
            if (localTaker != null) {
                localTaker = requireOpen(lane, takerOrderId);
                localTaker = applyFill(runtime, lane, instrument, localTaker, match.price(), match.size(), true,
                        baseAssetId, quoteAssetId, treasuryDelta, commitTimestamp, commitPosition);
            }
            Order maker = lane.order(match.matchedOrderId());
            if (maker != null) {
                maker = requireOpen(maker, maker.orderId());
                boolean makerTerminal = match.size() == maker.remainingQuantitySteps();
                maker = applyFill(runtime, lane, instrument, maker, match.price(), match.size(),
                        false, baseAssetId, quoteAssetId, treasuryDelta, commitTimestamp, commitPosition);
                if (makerTerminal) releaseTerminalReservation(runtime, lane, maker.orderId());
            }
        }
    }

    /** 借用所属 Lane 的现货累加器，按原事件顺序计算本地各订单，先发布余额再发布订单和预留，finally 清理暂存。故障回滚仍由外层结算边界负责。 */
    private static void applyLaneAccumulated(long takerOrderId, TradeSettlement.Plan plan, AccountPartition lane, int laneId,
                                             TradingAccountRuntime runtime, CoreInstrument instrument,
                                             int baseAssetId, int quoteAssetId,
                                             RuntimeTreasuryDelta treasuryDelta,
                                             long commitTimestamp, long commitPosition,
                                             Order localTaker) {
        SpotSettlementAccumulator accumulator = lane.spotSettlementAccumulator();
        accumulator.reset(runtime, lane, instrument, baseAssetId, quoteAssetId, commitTimestamp, commitPosition);
        try {
            for (int index = plan.firstMatcherEvent(laneId); index >= 0;
                    index = plan.nextMatcherEvent(index, laneId)) {
                MatcherEvent match = plan.matcherEvent(index);
                if (match.eventType() != MatcherEventType.TRADE
                        || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
                if (localTaker != null) {
                    accumulator.apply(takerOrderId, true, match.price(), match.size(), treasuryDelta);
                }
                Order maker = lane.order(match.matchedOrderId());
                if (maker != null) {
                    accumulator.apply(maker.orderId(), false, match.price(), match.size(), treasuryDelta);
                }
            }
            accumulator.publish();
        } finally {
            accumulator.clear();
        }
    }

    /** 核对订单预留及扣款资产，按买卖方向计算基础和计价余额、手续费、预留消耗及累计成交，写回所属 Lane 并记录系统费用。发生异常时不在此提供自动事务回滚。 */
    static Order applyFill(TradingAccountRuntime runtime, AccountPartition lane, CoreInstrument instrument, Order order,
                                  long fillPriceTicks, long fillQuantitySteps, boolean taker,
                                  int baseAssetId, int quoteAssetId, RuntimeTreasuryDelta treasuryDelta,
                                       long commitTimestamp, long commitPosition) {
        OrderFundsReservation reservation = lane.reservation(order.orderId());
        if (reservation == null || reservation.userId() != order.userId()) {
            throw new IllegalStateException("runtime spot reservation is missing");
        }
        long quoteUnits = CoreContractMath.spotQuoteUnits(instrument, fillPriceTicks, fillQuantitySteps);
        long baseUnits = CoreContractMath.spotBaseUnits(instrument, fillQuantitySteps);
        long feeRate = taker ? order.takerFeeRatePpm() : order.makerFeeRatePpm();
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRate);
        int debitAssetId = order.side() == CoreOrderSide.BUY ? quoteAssetId : baseAssetId;
        if (reservation.assetId() != debitAssetId) {
            throw new IllegalStateException("spot fill debit asset does not match reservation");
        }
        AccountBalance debit = runtime.balance(lane, order.userId(), debitAssetId);
        if (debit == null) throw new IllegalStateException("runtime spot debit balance is missing");
        AccountBalance base = runtime.balance(lane, order.userId(), baseAssetId);
        AccountBalance quote = runtime.balance(lane, order.userId(), quoteAssetId);
        long nextBaseAvailable = base == null ? 0 : base.availableUnits();
        long nextBaseLocked = base == null ? 0 : base.lockedUnits();
        long nextQuoteAvailable = quote == null ? 0 : quote.availableUnits();
        long nextQuoteLocked = quote == null ? 0 : quote.lockedUnits();
        long reservationDebit;
        if (order.side() == CoreOrderSide.BUY) {
            reservationDebit = Math.addExact(quoteUnits, Math.max(0, Math.negateExact(feeDelta)));
            nextQuoteLocked = Math.subtractExact(nextQuoteLocked, quoteUnits);
            if (feeDelta < 0) nextQuoteLocked = Math.subtractExact(nextQuoteLocked, Math.negateExact(feeDelta));
            else if (feeDelta > 0) nextQuoteAvailable = Math.addExact(nextQuoteAvailable, feeDelta);
            nextBaseAvailable = Math.addExact(nextBaseAvailable, baseUnits);
        } else {
            reservationDebit = baseUnits;
            nextBaseLocked = Math.subtractExact(nextBaseLocked, baseUnits);
            nextQuoteAvailable = Math.addExact(nextQuoteAvailable, quoteUnits);
            nextQuoteAvailable = Math.addExact(nextQuoteAvailable, feeDelta);
        }
        if (nextBaseAvailable < 0 || nextBaseLocked < 0 || nextQuoteAvailable < 0 || nextQuoteLocked < 0) {
            throw new IllegalStateException("runtime spot fill balance would become negative");
        }
        long nextExecuted = Math.addExact(order.executedQuantitySteps(), fillQuantitySteps);
        long nextRemaining = Math.subtractExact(order.remainingQuantitySteps(), fillQuantitySteps);
        CoreOrderStatus nextStatus = nextRemaining == 0 ? CoreOrderStatus.FILLED : CoreOrderStatus.OPEN;
        long nextRevision = Math.incrementExact(order.revision());
        replaceBalance(runtime, lane, order.userId(), baseAssetId, base, nextBaseAvailable, nextBaseLocked);
        replaceBalance(runtime, lane, order.userId(), quoteAssetId, quote, nextQuoteAvailable, nextQuoteLocked);
        long valueHigh = com.surprising.aeron.protocol.OrderExecutionValue.addHigh(order.executedValueHigh(), order.executedValueLow(), fillPriceTicks, fillQuantitySteps);
        long valueLow = com.surprising.aeron.protocol.OrderExecutionValue.addLow(order.executedValueLow(), fillPriceTicks, fillQuantitySteps);
        runtime.updateReservationInLane(lane, order.orderId(),
                Math.addExact(reservation.consumedUnits(), reservationDebit), reservation.releasedUnits(), nextRemaining != 0);
        Order nextOrder = runtime.updateOrderInLane(lane, order.orderId(), nextExecuted, nextRemaining,
                Math.negateExact(feeDelta), valueHigh, valueLow, nextStatus, nextRevision, commitTimestamp, commitPosition);
        treasuryDelta.addFee(quoteAssetId, Math.negateExact(feeDelta));
        runtime.advanceUserRevision(lane, order.userId());
        return nextOrder;
    }

    /** 本 Lane 独占的多笔现货成交累加器。财务费用仍逐笔生成，最后先写余额再写订单和预留，清理不代替外层回滚。 */
    public static final class SpotSettlementAccumulator {
        private final LongObjectHashMap<SpotFillCursor> cursors = new LongObjectHashMap<>();
        private final LongObjectHashMap<IntObjectHashMap<SpotBalanceState>> balances = new LongObjectHashMap<>();
        private final java.util.ArrayDeque<SpotFillCursor> free = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<IntObjectHashMap<SpotBalanceState>> freeBalanceMaps =
                new java.util.ArrayDeque<>();
        /** 本 Lane 保留的空闲单用户资产余额暂存对象池，避免每轮结算重新分配。 */
        private final java.util.ArrayDeque<SpotBalanceState> freeBalanceStates =
                new java.util.ArrayDeque<>();
        private TradingAccountRuntime runtime;
        private AccountPartition lane;
        private CoreInstrument instrument;
        private int baseAssetId;
        private int quoteAssetId;
        private long commitTimestamp;
        private long commitPosition;

        /** 保存本轮单币对、所属 Lane、资产及业务元数据，不清理上一轮活动项，调用方必须先完成 clear。 */
        void reset(TradingAccountRuntime runtime, AccountPartition lane, CoreInstrument instrument,
                   int baseAssetId, int quoteAssetId, long commitTimestamp, long commitPosition) {
            this.runtime = runtime;
            this.lane = lane;
            this.instrument = instrument;
            this.baseAssetId = baseAssetId;
            this.quoteAssetId = quoteAssetId;
            this.commitTimestamp = commitTimestamp;
            this.commitPosition = commitPosition;
        }

        /** 首次遇到订单时检查权威订单和预留，借用游标并关联同用户同资产共用的本轮余额状态。随后按原成交顺序累加本笔并记录财务差额。 */
        void apply(long orderId, boolean taker, long price, long quantity, RuntimeTreasuryDelta treasury) {
            SpotFillCursor cursor = cursors.get(orderId);
            if (cursor == null) {
                Order order = requireOpen(lane, orderId);
                OrderFundsReservation reservation = lane.reservation(orderId);
                if (reservation == null || reservation.userId() != order.userId()) {
                    throw new IllegalStateException("runtime spot reservation is missing");
                }
                cursor = free.pollFirst();
                if (cursor == null) cursor = new SpotFillCursor();
                cursor.reset(order, reservation, balanceState(order.userId(), baseAssetId),
                        balanceState(order.userId(), quoteAssetId));
                cursors.put(orderId, cursor);
            }
            cursor.apply(price, quantity, taker, treasury);
        }

        /** 先把全部用户资产余额写回 Lane，再发布每个订单及预留。预留更新会捕获余额后值，不能颠倒此顺序。 */
        void publish() {
            balances.forEachKeyValue((userId, userBalances) ->
                    userBalances.forEachKeyValue((assetId, balance) -> balance.publish(runtime, lane)));
            // 预留仍待结算时，更新预留会捕获余额后值， 因此必须先发布本轮余额，再发布订单及预留最终值。
            cursors.forEachKeyValue((orderId, cursor) -> cursor.publish(runtime));
        }

        /** 释放游标引用并归还游标池，清空各用户资产 Map 后归还空 Map 池，解除本轮上下文。余额暂存值不跨轮保留，不自动发布或回滚。 */
        void clear() {
            cursors.forEachKeyValue((orderId, cursor) -> {
                cursor.clear();
                free.addFirst(cursor);
            });
            cursors.clear();
            balances.forEachKeyValue((userId, userBalances) -> {
                userBalances.forEachKeyValue((assetId, balanceState) -> freeBalanceStates.addFirst(balanceState));
                userBalances.clear();
                freeBalanceMaps.addFirst(userBalances);
            });
            balances.clear();
            runtime = null;
            lane = null;
            instrument = null;
        }

        private SpotBalanceState balanceState(long userId, int assetId) {
            IntObjectHashMap<SpotBalanceState> userBalances = balances.get(userId);
            if (userBalances == null) {
                userBalances = freeBalanceMaps.pollFirst();
                if (userBalances == null) userBalances = new IntObjectHashMap<>();
                balances.put(userId, userBalances);
            }
            SpotBalanceState state = userBalances.get(assetId);
            if (state == null) {
                AccountBalance current = runtime.balance(lane, userId, assetId);
                state = freeBalanceStates.pollFirst();
                if (state == null) state = new SpotBalanceState();
                state.reset(userId, assetId, current);
                userBalances.put(assetId, state);
            }
            return state;
        }

        /** 仅存活于本轮结算的单用户单资产余额标量后值，由该用户各订单共用。 */
        private static final class SpotBalanceState {
            private long userId;
            private int assetId;
            private boolean exists;
            private long available;
            private long locked;

            /** 保存用户资产身份及本轮起点是否存在，并从权威余额复制可用和冻结标量，缺失时用零。 */
            void reset(long userId, int assetId, AccountBalance current) {
                this.userId = userId;
                this.assetId = assetId;
                exists = current != null;
                available = current == null ? 0 : current.availableUnits();
                locked = current == null ? 0 : current.lockedUnits();
            }

            /** 按起点存在标记在所属 Lane 更新或创建余额，写入本轮聚合后的可用和冻结金额。 */
            void publish(TradingAccountRuntime runtime, AccountPartition lane) {
                if (exists) runtime.replaceBalance(lane, userId, assetId, available, locked);
                else runtime.putBalance(lane, new AccountBalance(userId, assetId, available, locked));
            }
        }

        /** 本 Lane 一笔订单的连续成交游标，保存累计成交、费用及预留消耗，引用同用户共享余额后值。只在本轮租约内使用，清理后归还池。 */
        private final class SpotFillCursor {
            private Order originalOrder;
            private OrderFundsReservation originalReservation;
            private SpotBalanceState base;
            private SpotBalanceState quote;
            private long executed;
            private long remaining;
            private long cumulativeFee;
            private long executedValueHigh, executedValueLow;
            private long consumed;
            private long fills;

            /** 保存订单和预留起点引用，关联同用户共用资产余额，载入累计成交、费用和预留消耗并清零本轮成交数。 */
            void reset(Order order, OrderFundsReservation reservation,
                       SpotBalanceState base, SpotBalanceState quote) {
                originalOrder = order;
                originalReservation = reservation;
                this.base = base;
                this.quote = quote;
                executed = order.executedQuantitySteps();
                remaining = order.remainingQuantitySteps();
                cumulativeFee = order.cumulativeFeeUnits();
                executedValueHigh = order.executedValueHigh(); executedValueLow = order.executedValueLow();
                consumed = reservation.consumedUnits();
                fills = 0;
            }

            /** 核对正价正量及剩余量，按买卖方向更新共享余额暂存，校验预留累计不超额，累计订单数量、费用和成交价值。 */
            void apply(long price, long quantity, boolean taker, RuntimeTreasuryDelta treasury) {
                if (price <= 0 || quantity <= 0 || quantity > remaining) {
                    throw new IllegalArgumentException("invalid runtime spot fill");
                }
                long quoteUnits = CoreContractMath.spotQuoteUnits(instrument, price, quantity);
                long baseUnits = CoreContractMath.spotBaseUnits(instrument, quantity);
                long feeRate = taker ? originalOrder.takerFeeRatePpm() : originalOrder.makerFeeRatePpm();
                long feeDelta = CoreContractMath.feeDeltaUnits(instrument, price, quantity, feeRate);
                int debitAssetId = originalOrder.side() == CoreOrderSide.BUY ? quoteAssetId : baseAssetId;
                if (originalReservation.assetId() != debitAssetId) {
                    throw new IllegalStateException("spot fill debit asset does not match reservation");
                }
                long reservationDebit;
                if (originalOrder.side() == CoreOrderSide.BUY) {
                    reservationDebit = Math.addExact(quoteUnits, Math.max(0, Math.negateExact(feeDelta)));
                    quote.locked = Math.subtractExact(quote.locked, quoteUnits);
                    if (feeDelta < 0) quote.locked = Math.subtractExact(quote.locked, Math.negateExact(feeDelta));
                    else if (feeDelta > 0) quote.available = Math.addExact(quote.available, feeDelta);
                    base.available = Math.addExact(base.available, baseUnits);
                } else {
                    reservationDebit = baseUnits;
                    base.locked = Math.subtractExact(base.locked, baseUnits);
                    quote.available = Math.addExact(quote.available, quoteUnits);
                    quote.available = Math.addExact(quote.available, feeDelta);
                }
                if (base.available < 0 || base.locked < 0 || quote.available < 0 || quote.locked < 0) {
                    throw new IllegalStateException("runtime spot fill balance would become negative");
                }
                consumed = Math.addExact(consumed, reservationDebit);
                long maxConsumed = Math.addExact(originalReservation.consumedUnits(),
                        originalReservation.reservedUnits());
                if (consumed > maxConsumed) throw new IllegalStateException("runtime spot reservation was over-consumed");
                executed = Math.addExact(executed, quantity);
                remaining = Math.subtractExact(remaining, quantity);
                cumulativeFee = Math.addExact(cumulativeFee, Math.negateExact(feeDelta));
                executedValueHigh = com.surprising.aeron.protocol.OrderExecutionValue.addHigh(executedValueHigh, executedValueLow, price, quantity);
                executedValueLow = com.surprising.aeron.protocol.OrderExecutionValue.addLow(executedValueLow, price, quantity);
                fills = Math.incrementExact(fills);
                treasury.addFee(quoteAssetId, Math.negateExact(feeDelta));
            }

            /** 有成交才写回预留及订单累计值，按成交次数推进订单与用户修订。 */
            void publish(TradingAccountRuntime state) {
                if (fills == 0) return;
                CoreOrderStatus status = remaining == 0 ? CoreOrderStatus.FILLED : CoreOrderStatus.OPEN;
                long feeDelta = Math.subtractExact(cumulativeFee, originalOrder.cumulativeFeeUnits());
                state.updateReservationInLane(lane, originalOrder.orderId(), consumed, originalReservation.releasedUnits(), remaining != 0);
                Order nextOrder = state.updateOrderInLane(lane, originalOrder.orderId(), executed, remaining,
                        feeDelta, executedValueHigh, executedValueLow, status, Math.addExact(originalOrder.revision(), fills),
                        commitTimestamp, commitPosition);
                state.advanceUserRevision(lane, originalOrder.userId(), fills);
                if (nextOrder.canceled()) {
                    long releaseUnits = state.releaseTerminalReservationInLane(lane, nextOrder, originalReservation);
                    if (releaseUnits > 0) state.advanceUserRevision(lane, nextOrder.userId());
                }
            }

            /** 释放本轮订单、预留及两资产余额暂存引用并清零成交数，其他原语值由下次 reset 覆盖。 */
            void clear() {
                originalOrder = null;
                originalReservation = null;
                base = null;
                quote = null;
                fills = 0;
            }
        }
    }

    private static void replaceBalance(TradingAccountRuntime runtime, AccountPartition lane, long userId, int assetId,
                                       AccountBalance current, long available, long locked) {
        if (current == null) runtime.putBalance(lane, new AccountBalance(userId, assetId, available, locked));
        else runtime.replaceBalance(lane, userId, assetId, available, locked);
    }

    /** 只在原 Matcher 结算作用域释放终态订单的剩余预留，实际释放金额为正才增加用户修订。 */
    static void releaseTerminalReservation(TradingAccountRuntime runtime, AccountPartition lane, long orderId) {
        Order order = lane.order(orderId);
        if (order == null || !order.canceled()) return;
        long releaseUnits = runtime.releaseTerminalReservationInLane(lane, order, null);
        if (releaseUnits > 0) runtime.advanceUserRevision(lane, order.userId());
    }

    private static Order requireOpen(AccountPartition lane, long orderId) {
        Order order = lane.order(orderId);
        return requireOpen(order, orderId);
    }

    private static Order requireOpen(Order order, long orderId) {
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0)
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        return order;
    }

}
