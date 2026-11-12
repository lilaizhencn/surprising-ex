package com.surprising.aeron.service.settlement;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.product.OptionTradingRules;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** 下单步骤 4.4 的衍生品结算入口。构造约定：只提供静态结算入口，不允许单独创建工具实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DerivativeTradeSettlementService {

    /** 在线所属 Lane 入口：至多一次成交直接应用，多笔成交复用累加器。 */
    static void applyLane(long takerOrderId, TradeSettlement.Plan plan, AccountPartition lane, int laneId,
                          TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                          CoreInstrument instrument, int settleAssetId,
                          RuntimeTreasuryDelta treasuryDelta,
                          long commitTimestamp, long commitPosition) {
        if (plan == null || treasuryDelta == null || laneId < 0
                || laneId >= runtime.topology().accountLaneCount()) {
            throw new IllegalArgumentException("invalid perpetual matcher settlement plan");
        }
        Order localTaker = lane.order(takerOrderId);
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
            TradeSettlement.releaseCanceledReservation(runtime, lane, localTaker);
        }
    }

    /** maker 完全成交时释放预留，保留原事件顺序。 */
    private static void applyLaneDirect(long takerOrderId, TradeSettlement.Plan plan, AccountPartition lane, int laneId,
                                        TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                        CoreInstrument instrument, int settleAssetId,
                                        RuntimeTreasuryDelta treasuryDelta,
                                        long commitTimestamp, long commitPosition) {
        Order localTaker = lane.order(takerOrderId);
        for (int index = plan.firstMatcherEvent(laneId); index >= 0;
                index = plan.nextMatcherEvent(index, laneId)) {
            MatcherEvent match = plan.matcherEvent(index);
            if (match.eventType() != MatcherEventType.TRADE
                    || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
            if (localTaker != null) {
                localTaker = TradeSettlement.requireOpen(lane, takerOrderId);
                applyFill(runtime, lane, identities, instrument, localTaker, match.price(), match.size(), true,
                        settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
            }
            Order maker = lane.order(match.matchedOrderId());
            if (maker != null) {
                maker = TradeSettlement.requireOpen(maker, maker.orderId());
                boolean makerTerminal = match.size() == maker.remainingQuantitySteps();
                applyFill(runtime, lane, identities, instrument, maker, match.price(), match.size(), false,
                        settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
                if (makerTerminal) {
                    long releaseUnits = runtime.releaseTerminalReservationInLane(lane, maker, null);
                    if (releaseUnits > 0) runtime.advanceUserRevision(lane, maker.userId());
                }
            }
        }
    }

    /** 借用当前 Lane 的连续成交累加器，按原事件顺序计算本地各订单，最后发布剩余游标。无论成功与否都清理暂存引用，已写回变化的回滚由外层负责。 */
    private static void applyLaneAccumulated(long takerOrderId, TradeSettlement.Plan plan, AccountPartition lane, int laneId,
                                             TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                             CoreInstrument instrument, int settleAssetId,
                                             RuntimeTreasuryDelta treasuryDelta,
                                             long commitTimestamp, long commitPosition,
                                             Order localTaker) {
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
                Order maker = lane.order(match.matchedOrderId());
                if (maker != null) {
                    accumulator.apply(maker.orderId(), false, match.price(), match.size(), treasuryDelta);
                }
            }
            accumulator.publish();
        } finally { accumulator.clear(); }
    }

    /** 所属 Lane 独占的多笔衍生品成交累加器。每笔财务差额仍按成交顺序生成，最终发布不重复收费，外层仍负责失败回滚。 */
    public static final class DerivativeSettlementAccumulator {
        private final LongObjectHashMap<FillCursor> cursors =
                new LongObjectHashMap<>();
        /** 本轮每用户最近的活动订单编号，切换时先写回旧游标以保持同用户账户顺序。 */
        private final LongLongHashMap activeOrderByUser = new LongLongHashMap();
        private final LongLongHashMap crossLeverageByUser = new LongLongHashMap();
        private final LongLongHashMap isolatedLeverageByUser = new LongLongHashMap();
        private final java.util.ArrayDeque<FillCursor> free =
                new java.util.ArrayDeque<>();
        private TradingAccountRuntime runtime;
        /** 本轮累加器独占服务的账户 Lane，不共享给其他账户线程。 */
        private AccountPartition lane;
        private RuntimeIdentityRegistry identities;
        private CoreInstrument instrument;
        private int settleAssetId;
        private long commitTimestamp;
        private long commitPosition;

        /** 保存本次单币对结算使用的所属 Lane、运行时、资产及业务元数据。不清空旧游标，调用方必须先结束并 clear 上一轮。 */
        void reset(TradingAccountRuntime runtime, AccountPartition lane, RuntimeIdentityRegistry identities,
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

        /** 首次遇到订单时准备独占游标。 */
        void apply(long orderId, boolean taker, long price, long quantity,
                   RuntimeTreasuryDelta treasury) {
            FillCursor cursor = cursors.get(orderId);
            if (cursor == null) {
                Order order = TradeSettlement.requireOpen(lane, orderId);
                long activeOrder = activeOrderByUser.get(order.userId());
                if (activeOrder != 0 && activeOrder != orderId) flush(activeOrder);
                long leverage = 0;
                if (!instrument.contractType().isOption()) {
                    LongLongHashMap leverageByUser = order.marginMode()
                            == com.surprising.aeron.protocol.CoreMarginMode.CROSS
                            ? crossLeverageByUser : isolatedLeverageByUser;
                    if (leverageByUser.containsKey(order.userId())) {
                        leverage = leverageByUser.get(order.userId());
                    } else {
                        Long configured = runtime.leverage(lane, order.userId(), instrument.instrumentId(), order.marginMode());
                        leverage = configured == null ? instrument.maxLeveragePpm() : configured;
                        leverageByUser.put(order.userId(), leverage);
                    }
                }
                long key = identities.preparedPositionKey(lane, order);
                cursor = free.pollFirst();
                if (cursor == null) cursor = new FillCursor();
                begin(cursor, runtime, lane, instrument, order, key,
                        leverage, settleAssetId, commitTimestamp, commitPosition);
                cursors.put(orderId, cursor);
                activeOrderByUser.put(order.userId(), orderId);
            }
            cursor.applyNext(price, quantity, taker, treasury);
        }

        /** 把本轮仍保留的每个订单游标写回账户，不重复记入已经按笔记录的财务差额。不同用户的遍历顺序不作为撮合顺序。 */
        void publish() {
            cursors.forEachKeyValue((orderId, cursor) -> publishCursor(cursor));
        }

        /** 清空活动游标引用并归还本 Lane 游标池，清除同用户活动订单及两种杠杆暂存，释放本轮上下文。不自动发布或回滚账户。 */
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

        /** 同用户切换订单时移除并发布前一订单游标，再清理入空闲池。 */
        private void flush(long orderId) {
            FillCursor cursor = cursors.remove(orderId);
            if (cursor == null) throw new IllegalStateException("active derivative cursor is missing");
            publishCursor(cursor);
            cursor.clear();
            free.addFirst(cursor);
        }

        /** 把游标最终累计结果写回所属 Lane，财务参数传空避免重复。 */
        private void publishCursor(FillCursor cursor) {
            TradeSettlement.releaseCanceledReservation(runtime, lane, cursor.publish(runtime, lane, cursor.positionKey(), null));
        }
    }

    private static void applyFill(TradingAccountRuntime runtime, AccountPartition lane,
                                  RuntimeIdentityRegistry identities, CoreInstrument instrument,
                                  Order order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId, RuntimeTreasuryDelta treasuryDelta,
                                       long commitTimestamp, long commitPosition) {
        long leverage = 0;
        if (!instrument.contractType().isOption()) {
            Long configuredLeverage = runtime.leverage(lane, order.userId(), instrument.instrumentId(), order.marginMode());
            leverage = configuredLeverage == null ? instrument.maxLeveragePpm() : configuredLeverage;
        }
        apply(runtime, lane, identities, instrument, order,
                identities.preparedPositionKey(lane, order),
                priceTicks, quantitySteps, taker, leverage, settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
    }

    /** 校验单笔成交、账户预留及余额归属，借用所属 Lane 的单笔游标计算并发布结果。 */
    static void apply(TradingAccountRuntime runtime, AccountPartition lane, RuntimeIdentityRegistry identities,
                      CoreInstrument instrument, Order order,
                      long positionKey, long fillPriceTicks, long fillQuantitySteps,
                      boolean taker, long leveragePpm, int settleAssetId,
                      RuntimeTreasuryDelta treasuryDelta, long commitTimestamp, long commitPosition) {
        if (runtime == null || identities == null || instrument == null || order == null || treasuryDelta == null
                || fillPriceTicks <= 0 || fillQuantitySteps <= 0
                || !instrument.contractType().isOption() && leveragePpm <= 0 || settleAssetId < 0) {
            throw new IllegalArgumentException("invalid perpetual fill arguments");
        }
        ProductTradingRules kernel = ProductTradingRules.forInstrument(instrument);
        if (kernel.productLine() == com.surprising.product.api.ProductLine.SPOT
                || order.symbolId() < 0 || settleAssetId < 0) {
            throw new IllegalArgumentException("runtime fill instrument identity mismatch");
        }
        if (order.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET
                && order.priceTicks() < 0) {
            throw new IllegalArgumentException("invalid market order price");
        }
        if (fillQuantitySteps > order.remainingQuantitySteps()) {
            throw new IllegalStateException("fill exceeds runtime order remaining quantity");
        }
        OrderFundsReservation reservation = lane.reservation(order.orderId());
        AccountBalance balance = runtime.balance(lane, order.userId(), settleAssetId);
        if (reservation == null || balance == null || reservation.userId() != order.userId()
                || reservation.assetId() != settleAssetId) {
            throw new IllegalStateException("runtime fill entities are missing: " + order.orderId());
        }
        Position current = lane.position(positionKey);
        if (current != null && current.userId() != order.userId()) {
            throw new IllegalStateException("runtime position owner mismatch: " + positionKey);
        }

        MarkPrice riskMark = runtime.markPrice(lane, order.symbolId());
        if (instrument.contractType().isOption()) OptionTradingRules.requireRiskMark(riskMark);
        runtime.requireLaneAccess(lane, order.userId());
        FillCursor cursor = lane.singleFillCursor();
        try {
            cursor.reset(instrument, order, reservation, current, balance.availableUnits(),
                    balance.lockedUnits(), settleAssetId, riskMark);
            cursor.step(fillPriceTicks, fillQuantitySteps, taker, leveragePpm, commitTimestamp, commitPosition);
            cursor.publish(runtime, lane, positionKey, treasuryDelta);
        } finally { cursor.clear(); }
    }

    /** 单个账户 Lane 独占的成交计算游标，可承接同一订单多笔成交。只保留本轮标量及权威起点引用，publish 写回、clear 释放，不共享给其他 Lane，也不提供自动事务回滚。 */
    public static final class FillCursor {
        private CoreInstrument instrument;
        private Order originalOrder;
        private OrderFundsReservation originalReservation;
        private MarkPrice riskMark;
        private int settleAssetId;
        private boolean hasPosition;
        private long available, locked, quantity, entryPrice, entryValue, realizedPnl, margin;
        private long executed, remaining, cumulativeFee, orderRevision, consumed, fills;
        private long executedValueHigh, executedValueLow;
        private long timestamp, clusterPosition;
        /** feeTreasuryUnits：最近一笔成交对应的系统手续费变化，逐笔覆盖而非累计。clearingTreasuryUnits：最近一笔实际计入余额盈亏的反向系统清算变化，逐笔覆盖。 */
        private long feeTreasuryUnits, clearingTreasuryUnits;
        private long positionKey, leveragePpm;

        /** 把当前订单、预留、持仓和余额起点装入游标，重置本轮成交次数。 */
        void reset(CoreInstrument instrument, Order order, OrderFundsReservation reservation,
                   Position current, long available, long locked, int assetId, MarkPrice mark) {
            if (instrument == null || order == null || reservation == null || available < 0 || locked < 0
                    || assetId < 0 || reservation.userId() != order.userId() || reservation.assetId() != assetId
                    || current != null && current.userId() != order.userId()) {
                throw new IllegalArgumentException("invalid perpetual fill calculation");
            }
            this.instrument = instrument; originalOrder = order; originalReservation = reservation;
            riskMark = mark; settleAssetId = assetId; this.available = available; this.locked = locked;
            hasPosition = current != null;
            quantity = current == null ? 0 : current.signedQuantitySteps();
            entryPrice = current == null ? 0 : current.entryPriceTicks();
            entryValue = current == null ? 0 : current.entryValueTicks();
            realizedPnl = current == null ? 0 : current.realizedPnlUnits();
            margin = current == null ? 0 : current.positionMarginUnits();
            executed = order.executedQuantitySteps(); remaining = order.remainingQuantitySteps();
            executedValueHigh = order.executedValueHigh(); executedValueLow = order.executedValueLow();
            cumulativeFee = order.cumulativeFeeUnits(); orderRevision = order.revision();
            consumed = reservation.consumedUnits(); fills = 0;
        }
        /** 以预留总额减去已释放额和游标累计已消耗额，得到本轮仍可使用的订单预留。 */
        long reservedUnits() {
            return Math.subtractExact(originalReservation.totalReservedUnits(),
                    Math.addExact(originalReservation.releasedUnits(), consumed));
        }
        /** 按给定价格、数量、角色和杠杆计算下一笔成交，更新游标。 */
        void step(long price, long size, boolean taker, long leverage, long timestamp, long position) {
            calculateInto(this, price, size, taker, leverage, timestamp, position);
        }
        /** 使用 begin 保存的杠杆和业务元数据累加一笔成交，并立刻按原事件顺序记入本笔费用及实际盈亏财务差额。 */
        void applyNext(long price, long size, boolean taker, RuntimeTreasuryDelta treasury) {
            step(price, size, taker, leveragePpm, timestamp, clusterPosition);
            // 每笔立即记财务差额，保持与 maker 差额交错出现的原始成交顺序。
            treasury.addFee(settleAssetId, feeTreasuryUnits);
            treasury.addClearing(settleAssetId, clearingTreasuryUnits);
        }
        /** 返回 begin 为本聚合订单准备的持仓身份键。 */
        long positionKey() { return positionKey; }
        /** 有成交才把游标累计的预留、余额、持仓、订单和用户修订写回所属 Lane。 */
        Order publish(TradingAccountRuntime runtime, AccountPartition lane, long positionKey, RuntimeTreasuryDelta treasury) {
            if (fills == 0) return null;
            CoreOrderStatus nextStatus = remaining == 0 ? CoreOrderStatus.FILLED : originalOrder.status();
            long feeDelta = Math.subtractExact(cumulativeFee, originalOrder.cumulativeFeeUnits());
            runtime.updateReservationInLane(lane, originalOrder.orderId(), consumed, originalReservation.releasedUnits(), remaining != 0);
            runtime.replaceBalance(lane, originalOrder.userId(), settleAssetId, available, locked);
            runtime.updatePositionInLane(lane, positionKey, originalOrder.userId(), originalOrder.symbolId(), settleAssetId,
                    originalOrder.instrument(), quantity, entryPrice, entryValue,
                    realizedPnl, margin, originalOrder.marginMode(), originalOrder.positionSide());
            Order nextOrder = runtime.updateOrderInLane(lane, originalOrder.orderId(), executed, remaining,
                    feeDelta, executedValueHigh, executedValueLow, nextStatus, orderRevision, timestamp, clusterPosition);
            if (treasury != null) {
                treasury.addFee(settleAssetId, feeTreasuryUnits);
                treasury.addClearing(settleAssetId, clearingTreasuryUnits);
            }
            runtime.advanceUserRevision(lane, originalOrder.userId(), fills);
            return nextOrder;
        }
        /** 释放本轮币对、订单、预留和标记价引用，并清除成交数、持仓键及缓存杠杆。其余原语值在下次 reset 或 begin 覆盖，不提供账户回滚。 */
        void clear() {
            instrument = null; originalOrder = null; originalReservation = null; riskMark = null;
            fills = 0; positionKey = 0; leveragePpm = 0;
        }
    }

    /** 为连续成交准备调用方独占游标，要求旧游标已清理。 */
    static void begin(FillCursor cursor, TradingAccountRuntime runtime, AccountPartition lane, CoreInstrument instrument,
                      Order order, long positionKey, long leverage, int assetId,
                      long timestamp, long position) {
        if (cursor == null || runtime == null || instrument == null || order == null
                || order.symbolId() < 0 || !instrument.contractType().isOption() && leverage <= 0 || assetId < 0
                || instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                || timestamp < -1 || position < -1 || timestamp >= 0 && position < 0) {
            throw new IllegalArgumentException("invalid perpetual fill cursor");
        }
        if (cursor.originalOrder != null) throw new IllegalStateException("perpetual fill cursor is still active");
        AccountBalance balance = runtime.balance(lane, order.userId(), assetId);
        OrderFundsReservation reservation = lane.reservation(order.orderId());
        if (balance == null || reservation == null || reservation.userId() != order.userId()
                || reservation.assetId() != assetId) {
            throw new IllegalStateException("runtime fill entities are missing: " + order.orderId());
        }
        MarkPrice mark = runtime.markPrice(lane, order.symbolId());
        if (instrument.contractType().isOption()) OptionTradingRules.requireRiskMark(mark);
        cursor.reset(instrument, order, reservation, lane.position(positionKey),
                balance.availableUnits(), balance.lockedUnits(), assetId, mark);
        cursor.positionKey = positionKey;
        cursor.leveragePpm = leverage;
        cursor.timestamp = timestamp;
        cursor.clusterPosition = position;
    }

    private static void calculateInto(FillCursor state, long fillPriceTicks, long fillQuantitySteps,
                                      boolean taker, long leveragePpm, long commitTimestamp, long commitPosition) {
        CoreInstrument instrument = state.instrument;
        Order order = state.originalOrder;
        OrderFundsReservation reservation = state.originalReservation;
        MarkPrice riskMark = state.riskMark;
        long availableUnits = state.available, lockedUnits = state.locked;
        if (instrument == null || fillPriceTicks <= 0 || fillQuantitySteps <= 0
                || !instrument.contractType().isOption() && leveragePpm <= 0
                || fillQuantitySteps > state.remaining) throw new IllegalArgumentException("invalid perpetual fill calculation");
        ProductTradingRules kernel = ProductTradingRules.forInstrument(instrument);
        long signedFill = order.side() == CoreOrderSide.BUY ? fillQuantitySteps : Math.negateExact(fillQuantitySteps);
        long currentQuantity = state.quantity;
        long currentAbs = Math.absExact(currentQuantity);
        boolean opposite = currentQuantity != 0 && Long.signum(currentQuantity) != Long.signum(signedFill);
        long closeSteps = opposite ? Math.min(currentAbs, fillQuantitySteps) : 0;
        long openSteps = Math.subtractExact(fillQuantitySteps, closeSteps);
        if (order.reduceOnly() && openSteps != 0) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only fill would create reverse exposure");
        }

        long releasedMargin = !state.hasPosition || closeSteps == 0 ? 0
                : com.surprising.instrument.api.math.PerpetualContractMath.proportionalUnits(state.margin, closeSteps, currentAbs);
        long nextQuantity = Math.addExact(currentQuantity, signedFill);
        long remainingMargin = Math.subtractExact(state.margin, releasedMargin);
        long marginIncrease = openingMarginForFill(instrument, nextQuantity, signedFill, openSteps,
                fillPriceTicks, leveragePpm, riskMark);
        long feeRatePpm = taker ? order.takerFeeRatePpm() : order.makerFeeRatePpm();
        long premiumDelta = kernel.premiumDeltaUnits(instrument, order.side(), fillPriceTicks, fillQuantitySteps);
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRatePpm);
        long premiumDebit = Math.max(0, Math.negateExact(premiumDelta));
        long feeDebit = Math.max(0, Math.negateExact(feeDelta));
        long premiumMarginFunding = instrument.contractType().isOption()
                ? OptionTradingRules.premiumMarginFunding(
                        instrument, premiumDelta, openSteps, marginIncrease, fillPriceTicks) : 0;
        long premiumFeeFunding = instrument.contractType().isOption() && premiumDelta > 0
                ? Math.min(feeDebit, Math.subtractExact(premiumDelta, premiumMarginFunding)) : 0;
        long proportionalBudget = com.surprising.instrument.api.math.PerpetualContractMath.proportionalUnits(state.reservedUnits(), fillQuantitySteps,
                state.remaining);
        long fillReservationBudget = Math.min(state.reservedUnits(),
                Math.max(feeDebit, proportionalBudget));
        boolean betterFill = order.side() == CoreOrderSide.BUY
                ? fillPriceTicks < order.matchingPriceTicks()
                : fillPriceTicks > order.matchingPriceTicks();
        boolean samePriceRounding = fillPriceTicks == order.matchingPriceTicks() && closeSteps == 0
                && marginIncrease > Math.max(0, fillReservationBudget - feeDebit)
                && !instrument.contractType().isOption()
                && reservation.totalReservedUnits() >= Math.addExact(
                        openingMarginForFill(instrument, nextQuantity, signedFill, order.quantitySteps(),
                                fillPriceTicks, leveragePpm, riskMark),
                        Math.max(0, Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                                fillPriceTicks, order.quantitySteps(), Math.max(order.makerFeeRatePpm(), order.takerFeeRatePpm())))));
        if ((betterFill || samePriceRounding) && !instrument.contractType().isOption()) {
            marginIncrease = Math.min(marginIncrease, Math.max(0,
                    Math.subtractExact(fillReservationBudget, Math.addExact(premiumDebit, feeDebit))));
        }
        long reservationDebit = Math.addExact(Math.subtractExact(marginIncrease, premiumMarginFunding),
                Math.addExact(premiumDebit, Math.subtractExact(feeDebit, premiumFeeFunding)));
        long reservationShortfall = Math.max(0,
                Math.subtractExact(reservationDebit, state.reservedUnits()));
        if (reservationShortfall > 0 && closeSteps == 0) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "runtime reservation is insufficient: orderId=" + order.orderId()
                            + ", required=" + reservationDebit
                            + ", remaining=" + state.reservedUnits());
        }
        long releasedMarginDebit = reservationShortfall;
        if (releasedMarginDebit > releasedMargin) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "runtime reservation and released position margin are insufficient");
        }
        long orderReservationDebit = Math.subtractExact(reservationDebit, releasedMarginDebit);
        long nextAvailable = availableUnits;
        long nextLocked = lockedUnits;
        long marginReleaseUnits = Math.subtractExact(releasedMargin, releasedMarginDebit);
        if (marginReleaseUnits > 0) {
            nextAvailable = Math.addExact(nextAvailable, marginReleaseUnits);
            nextLocked = Math.subtractExact(nextLocked, marginReleaseUnits);
        }

        long realizedPnl = 0;
        if (closeSteps > 0) {
            long signedClose = currentQuantity > 0 ? closeSteps : Math.negateExact(closeSteps);
            realizedPnl = kernel.realizedPnlUnits(
                    instrument, signedClose, state.entryPrice, fillPriceTicks);
        }
        long appliedPnl;
        if (realizedPnl >= 0) {
            nextAvailable = Math.addExact(nextAvailable, realizedPnl);
            appliedPnl = realizedPnl;
        } else {
            long debit = Math.min(nextAvailable, Math.negateExact(realizedPnl));
            nextAvailable = Math.subtractExact(nextAvailable, debit);
            appliedPnl = Math.negateExact(debit);
        }
        if (premiumDelta < 0) nextLocked = Math.subtractExact(nextLocked, Math.negateExact(premiumDelta));
        else if (premiumDelta > 0) {
            long retainedPremium = Math.addExact(premiumMarginFunding, premiumFeeFunding);
            nextLocked = Math.addExact(nextLocked, retainedPremium);
            nextAvailable = Math.addExact(nextAvailable,
                    Math.subtractExact(premiumDelta, retainedPremium));
        }
        if (feeDelta < 0) nextLocked = Math.subtractExact(nextLocked, Math.negateExact(feeDelta));
        else if (feeDelta > 0) nextAvailable = Math.addExact(nextAvailable, feeDelta);
        if (nextAvailable < 0 || nextLocked < 0) {
            throw new IllegalStateException("runtime fill balance would become negative");
        }
        long nextEntryPrice;
        long nextEntryValue;
        if (nextQuantity == 0) {
            nextEntryPrice = 0;
            nextEntryValue = 0;
        } else if (currentQuantity == 0 || Long.signum(nextQuantity) != Long.signum(currentQuantity)) {
            nextEntryPrice = fillPriceTicks;
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), fillPriceTicks);
        } else if (Long.signum(signedFill) == Long.signum(currentQuantity)) {
            nextEntryPrice = CoreContractMath.weightedEntryPrice(instrument, currentAbs,
                    state.entryPrice, fillQuantitySteps, fillPriceTicks);
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        } else {
            nextEntryPrice = state.entryPrice;
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        }
        long nextMargin = Math.addExact(remainingMargin, marginIncrease);
        long nextRealizedPnl = Math.addExact(state.realizedPnl, realizedPnl);
        long nextRemaining = Math.subtractExact(state.remaining, fillQuantitySteps);
        long nextExecuted = Math.addExact(state.executed, fillQuantitySteps);
        long nextFee = Math.addExact(state.cumulativeFee, Math.negateExact(feeDelta));
        long nextRevision = Math.incrementExact(state.orderRevision);
        long nextConsumed = Math.addExact(state.consumed, orderReservationDebit);
        if (nextMargin < 0 || nextQuantity == 0 && (nextMargin != 0 || nextEntryPrice != 0 || nextEntryValue != 0)
                || nextQuantity != 0 && (nextEntryPrice <= 0 || nextEntryValue <= 0)
                || orderReservationDebit < 0 || orderReservationDebit > state.reservedUnits()) {
            throw new IllegalArgumentException("invalid runtime fill state");
        }
        if (commitTimestamp >= 0 && commitPosition < 0) throw new IllegalArgumentException("invalid runtime order");
        state.available = nextAvailable; state.locked = nextLocked;
        state.quantity = nextQuantity; state.entryPrice = nextEntryPrice; state.entryValue = nextEntryValue;
        state.realizedPnl = nextRealizedPnl; state.margin = nextMargin; state.hasPosition = true;
        state.executed = nextExecuted; state.remaining = nextRemaining; state.cumulativeFee = nextFee;
        state.orderRevision = nextRevision; state.consumed = nextConsumed;
        state.executedValueHigh = com.surprising.aeron.protocol.OrderExecutionValue.addHigh(
                state.executedValueHigh, state.executedValueLow, fillPriceTicks, fillQuantitySteps);
        state.executedValueLow = com.surprising.aeron.protocol.OrderExecutionValue.addLow(
                state.executedValueLow, fillPriceTicks, fillQuantitySteps);
        state.fills = Math.incrementExact(state.fills);
        state.timestamp = commitTimestamp; state.clusterPosition = commitPosition;
        state.feeTreasuryUnits = Math.negateExact(feeDelta);
        state.clearingTreasuryUnits = Math.negateExact(appliedPnl);
    }

    private static long openingMarginForFill(CoreInstrument instrument,
                                             long projectedQuantitySteps,
                                             long signedFillSteps,
                                             long openSteps,
                                             long priceTicks,
                                             long leveragePpm,
                                             MarkPrice riskMark) {
        return instrument.contractType().isOption()
                ? OptionTradingRules.openingMarginForFill(instrument, projectedQuantitySteps, signedFillSteps, openSteps, riskMark)
                : CoreContractMath.openingMarginForFill(instrument, projectedQuantitySteps, openSteps, priceTicks, leveragePpm);
    }

}
