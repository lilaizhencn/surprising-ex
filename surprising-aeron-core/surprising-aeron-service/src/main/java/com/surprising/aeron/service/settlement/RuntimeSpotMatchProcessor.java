package com.surprising.aeron.service.settlement;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.product.ProductTradingRulesRegistry;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** 下单步骤 4.4 的现货结算。买方从计价资产冻结中支付成交额及收费、得到基础资产，卖方交付冻结基础资产、收到计价资产并扣费或返佣。在线 applyLane 只处理所属账户，按单笔或累加路径更新订单和预留，系统费用留在本事件财务差额中交给账本。 构造约定：只提供静态现货结算入口，不允许单独创建工具实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeSpotMatchProcessor {

    /**
     * 在通用运行时所有权边界处理现货成交：无成交的非即时限价单直接返回，其余先核对规范币对、产品与累计数量，再逐笔应用买卖双方、释放终态预留，应用系统费用并推进产品修订。
     * @param takerOrderId 主动成交订单编号
     * @param matches 原始撮合事件列表，非 TRADE 事件不应用成交
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param identities 解析实际币对及资产编号的运行时字典
     */
    public static void applyRuntime(long takerOrderId, List<MatcherEvent> matches,
                                    TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        if (matches == null || runtime == null || identities == null || runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid spot match apply");
        }
        runtime.assertOwner();
        // 当前主动订单的权威值，每笔应用前重新读取以累计数量。
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        if (matches.isEmpty() && !taker.timeInForce().immediate()
                && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
            return;
        }
        // 运行时规范现货币对，须与订单保存的对象一致。
        CoreInstrument instrument = runtime.instrument(identities.instrumentId(taker.symbolId()));
        if (instrument == null || instrument != taker.instrument()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        if (ProductTradingRulesRegistry.forInstrument(instrument).productLine()
                != com.surprising.product.api.ProductLine.SPOT) {
            throw new IllegalStateException("spot matcher received a non-spot settlement kernel");
        }
        // 从规范币对解析的基础资产编号。
        int baseAssetId = identities.assetId(instrument.baseAsset());
        // 从规范币对解析的计价及收费资产编号。
        int quoteAssetId = identities.assetId(instrument.quoteAsset());
        validateMatches(runtime, taker, matches);
        // 本轮通用现货结算创建的系统费用差额。
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        for (/* 通用原始撮合事件的当前下标。 */ int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
            // 当前原始 Matcher 事件，只有 TRADE 参与计算。
            MatcherEvent match = matches.get(matchIndex);
            if (match.eventType() != MatcherEventType.TRADE) continue;
            taker = requireOpen(runtime, takerOrderId);
            // 本笔对应的权威对手订单，Lane 路径只读取本地对象。
            OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
            // 本笔成交量是否恰好耗尽 maker 剩余量。
            boolean makerTerminal = match.size() == maker.remainingQuantitySteps();
            // 按订单方向选出的买方订单，不一定是 taker。
            OrderRuntime buyer = taker.side() == CoreOrderSide.BUY ? taker : maker;
            // 按订单方向选出的卖方订单，不一定是 maker。
            OrderRuntime seller = taker.side() == CoreOrderSide.SELL ? taker : maker;
            applyFill(runtime, instrument, buyer, match.price(), match.size(),
                    buyer.orderId() == taker.orderId(), baseAssetId, quoteAssetId, treasuryDelta);
            applyFill(runtime, instrument, seller, match.price(), match.size(),
                    seller.orderId() == taker.orderId(), baseAssetId, quoteAssetId, treasuryDelta);
            if (makerTerminal) releaseTerminalReservation(runtime, maker.orderId());
        }
        taker = runtime.order(takerOrderId);
        if (!taker.canceled() && (taker.timeInForce().immediate()
                || taker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
            runtime.replaceOrder(taker.withStatus(CoreOrderStatus.CANCELED, Math.incrementExact(taker.revision())));
        }
        releaseTerminalReservation(runtime, takerOrderId);
        treasuryDelta.apply(runtime.treasury());
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    /**
     * 在线所属 Lane 入口，至多一笔成交直接应用，多笔复用现货累加器；本地 taker 的市价或即时单剩余量转取消，终态订单释放剩余预留。
     * @param takerOrderId 主动成交订单编号
     * @param plan 本代结算计划和 Lane 事件范围
     * @param lane 当前线程持有的账户 Lane
     * @param laneId 当前账户 Lane 编号
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param instrument 当前订单的规范现货币对
     * @param baseAssetId 基础资产的运行时编号
     * @param quoteAssetId 计价及收费资产的运行时编号
     * @param treasuryDelta 所属 Lane 系统费用差额接收槽
     * @param commitTimestamp 成交结果写回的复制业务时间
     * @param commitPosition 成交结果写回的复制日志位置
     */
    static void applyLane(long takerOrderId, MatcherSettlementPlan plan, AccountLaneState lane, int laneId,
                          TradingAccountRuntime runtime, CoreInstrument instrument,
                          int baseAssetId, int quoteAssetId, RuntimeTreasuryDelta treasuryDelta,
                          long commitTimestamp, long commitPosition) {
        if (plan == null || treasuryDelta == null || laneId < 0
                || laneId >= runtime.topology().accountLaneCount()) {
            throw new IllegalArgumentException("invalid spot matcher settlement plan");
        }
        // 本账户 Lane 持有的主动订单，其他 Lane 可为空。
        OrderRuntime localTaker = lane.order(takerOrderId);
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

    /**
     * 沿当前 Lane 的事件范围处理成交，只对本 Lane 持有的 taker 或 maker 应用资产、费用及订单变化；maker 被耗尽时立即释放剩余预留。
     * @param takerOrderId 主动成交订单编号
     * @param plan 本代结算计划和 Lane 事件范围
     * @param lane 当前线程持有的账户 Lane
     * @param laneId 当前账户 Lane 编号
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param instrument 当前订单的规范现货币对
     * @param baseAssetId 基础资产的运行时编号
     * @param quoteAssetId 计价及收费资产的运行时编号
     * @param treasuryDelta 所属 Lane 系统费用差额接收槽
     * @param commitTimestamp 成交结果写回的复制业务时间
     * @param commitPosition 成交结果写回的复制日志位置
     * @param localTaker 当前 Lane 持有的主动订单，其他 Lane 可为空
     */
    private static void applyLaneDirect(long takerOrderId, MatcherSettlementPlan plan, AccountLaneState lane, int laneId,
                                        TradingAccountRuntime runtime, CoreInstrument instrument,
                                        int baseAssetId, int quoteAssetId,
                                        RuntimeTreasuryDelta treasuryDelta,
                                        long commitTimestamp, long commitPosition,
                                        OrderRuntime localTaker) {
        for (/* 当前沿 Lane 事件链或原始事件顺序读取的下标。 */ int index = plan.firstMatcherEvent(laneId); index >= 0;
                index = plan.nextMatcherEvent(index, laneId)) {
            // 当前原始 Matcher 事件，只有 TRADE 参与计算。
            MatcherEvent match = plan.matcherEvent(index);
            if (match.eventType() != MatcherEventType.TRADE
                    || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
            if (localTaker != null) {
                localTaker = requireOpen(lane, takerOrderId);
                localTaker = applyFill(runtime, lane, instrument, localTaker, match.price(), match.size(), true,
                        baseAssetId, quoteAssetId, treasuryDelta, commitTimestamp, commitPosition);
            }
            // 本笔对应的权威对手订单，Lane 路径只读取本地对象。
            OrderRuntime maker = lane.order(match.matchedOrderId());
            if (maker != null) {
                maker = requireOpen(lane, maker.orderId());
                // 本笔成交量是否恰好耗尽 maker 剩余量。
                boolean makerTerminal = match.size() == maker.remainingQuantitySteps();
                maker = applyFill(runtime, lane, instrument, maker, match.price(), match.size(),
                        false, baseAssetId, quoteAssetId, treasuryDelta, commitTimestamp, commitPosition);
                if (makerTerminal) releaseTerminalReservation(runtime, lane, maker.orderId());
            }
        }
    }

    /**
     * 借用所属 Lane 的现货累加器，按原事件顺序计算本地各订单，先发布余额再发布订单和预留，finally 清理暂存；故障回滚仍由外层结算边界负责。
     * @param takerOrderId 主动成交订单编号
     * @param plan 本代结算计划和 Lane 事件范围
     * @param lane 当前线程持有的账户 Lane
     * @param laneId 当前账户 Lane 编号
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param instrument 当前订单的规范现货币对
     * @param baseAssetId 基础资产的运行时编号
     * @param quoteAssetId 计价及收费资产的运行时编号
     * @param treasuryDelta 所属 Lane 系统费用差额接收槽
     * @param commitTimestamp 成交结果写回的复制业务时间
     * @param commitPosition 成交结果写回的复制日志位置
     * @param localTaker 当前 Lane 持有的主动订单，其他 Lane 可为空
     */
    private static void applyLaneAccumulated(long takerOrderId, MatcherSettlementPlan plan, AccountLaneState lane, int laneId,
                                             TradingAccountRuntime runtime, CoreInstrument instrument,
                                             int baseAssetId, int quoteAssetId,
                                             RuntimeTreasuryDelta treasuryDelta,
                                             long commitTimestamp, long commitPosition,
                                             OrderRuntime localTaker) {
        // 本账户 Lane 独占复用的现货多笔成交累加器。
        SpotSettlementAccumulator accumulator = lane.spotSettlementAccumulator();
        accumulator.reset(runtime, lane, instrument, baseAssetId, quoteAssetId, commitTimestamp, commitPosition);
        try {
            for (/* 当前沿 Lane 事件链或原始事件顺序读取的下标。 */ int index = plan.firstMatcherEvent(laneId); index >= 0;
                    index = plan.nextMatcherEvent(index, laneId)) {
                // 当前原始 Matcher 事件，只有 TRADE 参与计算。
                MatcherEvent match = plan.matcherEvent(index);
                if (match.eventType() != MatcherEventType.TRADE
                        || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
                if (localTaker != null) {
                    accumulator.apply(takerOrderId, true, match.price(), match.size(), treasuryDelta);
                }
                // 本笔对应的权威对手订单，Lane 路径只读取本地对象。
                OrderRuntime maker = lane.order(match.matchedOrderId());
                if (maker != null) {
                    accumulator.apply(maker.orderId(), false, match.price(), match.size(), treasuryDelta);
                }
            }
            accumulator.publish();
        } finally {
            accumulator.clear();
        }
    }

    /**
     * 通用入口按订单用户找到所属账户 Lane，委托显式 Lane 单笔计算，时间及日志位置以负一沿既有规则处理。
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param instrument 当前订单的规范现货币对
     * @param order 当前需要应用成交的订单引用
     * @param fillPriceTicks 本笔实际成交价刻度
     * @param fillQuantitySteps 本笔成交数量步长
     * @param taker 本笔是否为主动成交，决定订单费用率
     * @param baseAssetId 基础资产的运行时编号
     * @param quoteAssetId 计价及收费资产的运行时编号
     * @param treasuryDelta 所属 Lane 系统费用差额接收槽
     */
    private static OrderRuntime applyFill(TradingAccountRuntime runtime, CoreInstrument instrument, OrderRuntime order,
                                  long fillPriceTicks, long fillQuantitySteps, boolean taker,
                                  int baseAssetId, int quoteAssetId, RuntimeTreasuryDelta treasuryDelta) {
        return applyFill(runtime, runtime.accountLaneForUser(order.userId()), instrument, order, fillPriceTicks, fillQuantitySteps, taker,
                baseAssetId, quoteAssetId, treasuryDelta, -1, -1);
    }

    /**
     * 核对订单预留及扣款资产，按买卖方向计算基础和计价余额、手续费、预留消耗及累计成交，写回所属 Lane 并记录系统费用；发生异常时不在此提供自动事务回滚。
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param lane 当前线程持有的账户 Lane
     * @param instrument 当前订单的规范现货币对
     * @param order 当前需要应用成交的订单引用
     * @param fillPriceTicks 本笔实际成交价刻度
     * @param fillQuantitySteps 本笔成交数量步长
     * @param taker 本笔是否为主动成交，决定订单费用率
     * @param baseAssetId 基础资产的运行时编号
     * @param quoteAssetId 计价及收费资产的运行时编号
     * @param treasuryDelta 所属 Lane 系统费用差额接收槽
     * @param commitTimestamp 成交结果写回的复制业务时间
     * @param commitPosition 成交结果写回的复制日志位置
     */
    private static OrderRuntime applyFill(TradingAccountRuntime runtime, AccountLaneState lane, CoreInstrument instrument, OrderRuntime order,
                                  long fillPriceTicks, long fillQuantitySteps, boolean taker,
                                  int baseAssetId, int quoteAssetId, RuntimeTreasuryDelta treasuryDelta,
                                       long commitTimestamp, long commitPosition) {
        // 当前订单的权威预留，须匹配用户及扣款资产。
        ReservationRuntime reservation = lane.reservation(order.orderId());
        if (reservation == null || reservation.userId() != order.userId()) {
            throw new IllegalStateException("runtime spot reservation is missing");
        }
        // 本笔按价格和数量换算的计价资产成交金额。
        long quoteUnits = CoreContractMath.spotQuoteUnits(instrument, fillPriceTicks, fillQuantitySteps);
        // 本笔按数量换算的基础资产交付金额。
        long baseUnits = CoreContractMath.spotBaseUnits(instrument, fillQuantitySteps);
        // 按 taker 或 maker 角色选出的订单费率。
        long feeRate = taker ? order.takerFeeRatePpm() : order.makerFeeRatePpm();
        // 本笔用户计价资产费用变化，负数收费、正数返佣。
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRate);
        // 买单扣计价资产、卖单扣基础资产的实际预留资产编号。
        int debitAssetId = order.side() == CoreOrderSide.BUY ? quoteAssetId : baseAssetId;
        if (reservation.assetId() != debitAssetId) {
            throw new IllegalStateException("spot fill debit asset does not match reservation");
        }
        // 必须已存在的扣款资产权威余额，用于确认冻结资金来源。
        BalanceRuntime debit = runtime.balance(lane, order.userId(), debitAssetId);
        if (debit == null) throw new IllegalStateException("runtime spot debit balance is missing");
        // 当前用户基础资产的可空权威余额。
        BalanceRuntime base = runtime.balance(lane, order.userId(), baseAssetId);
        // 当前用户计价资产的可空权威余额。
        BalanceRuntime quote = runtime.balance(lane, order.userId(), quoteAssetId);
        // 本笔计算后的基础资产可用余额，原余额缺失时从零开始。
        long nextBaseAvailable = base == null ? 0 : base.availableUnits();
        // 本笔计算后的基础资产冻结余额。
        long nextBaseLocked = base == null ? 0 : base.lockedUnits();
        // 本笔计算后的计价资产可用余额。
        long nextQuoteAvailable = quote == null ? 0 : quote.availableUnits();
        // 本笔计算后的计价资产冻结余额。
        long nextQuoteLocked = quote == null ? 0 : quote.lockedUnits();
        // 本笔从订单预留消耗的金额，买单含成交额及收费，卖单只扣基础资产数量。
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
        // 订单加入本笔数量后的累计成交量。
        long nextExecuted = Math.addExact(order.executedQuantitySteps(), fillQuantitySteps);
        // 订单扣除本笔数量后的剩余量。
        long nextRemaining = Math.subtractExact(order.remainingQuantitySteps(), fillQuantitySteps);
        // 本笔后剩余量为零则 FILLED，否则 OPEN。
        CoreOrderStatus nextStatus = nextRemaining == 0 ? CoreOrderStatus.FILLED : CoreOrderStatus.OPEN;
        // 单笔成交后精确递增的订单修订号。
        long nextRevision = Math.incrementExact(order.revision());
        replaceBalance(runtime, lane, order.userId(), baseAssetId, base, nextBaseAvailable, nextBaseLocked);
        replaceBalance(runtime, lane, order.userId(), quoteAssetId, quote, nextQuoteAvailable, nextQuoteLocked);
        // 加入本笔成交价值后的宽整数高六十四位。
        long valueHigh = com.surprising.aeron.protocol.OrderExecutionValue.addHigh(order.executedValueHigh(), order.executedValueLow(), fillPriceTicks, fillQuantitySteps);
        // 加入本笔成交价值后的宽整数低六十四位。
        long valueLow = com.surprising.aeron.protocol.OrderExecutionValue.addLow(order.executedValueLow(), fillPriceTicks, fillQuantitySteps);
        runtime.updateReservationInLane(lane, order.orderId(),
                Math.addExact(reservation.consumedUnits(), reservationDebit), reservation.releasedUnits(), nextRemaining != 0);
        // 写回最终累计值后返回的权威订单引用。
        OrderRuntime nextOrder = runtime.updateOrderInLane(lane, order.orderId(), nextExecuted, nextRemaining,
                Math.negateExact(feeDelta), valueHigh, valueLow, nextStatus, nextRevision, commitTimestamp, commitPosition);
        treasuryDelta.addFee(quoteAssetId, Math.negateExact(feeDelta));
        runtime.advanceUserRevision(lane, order.userId());
        return nextOrder;
    }

    /**
     * 本 Lane 独占的多笔现货成交累加器。订单各有游标，同用户同资产共用本轮余额暂存；财务费用仍逐笔生成，最后先写余额再写订单和预留，清理不代替外层回滚。
     */
    public static final class SpotSettlementAccumulator {
        /** 本轮按订单保存的独占现货游标，不是长期权威订单表。 */
        private final LongObjectHashMap<SpotFillCursor> cursors = new LongObjectHashMap<>();
        /** 本轮按用户和资产共享的余额计算暂存，多个订单共同修改同一用户资产后值。 */
        private final LongObjectHashMap<IntObjectHashMap<SpotBalanceState>> balances = new LongObjectHashMap<>();
        /** 所属 Lane 的空闲现货游标池，clear 后解除业务引用再归还。 */
        private final java.util.ArrayDeque<SpotFillCursor> free = new java.util.ArrayDeque<>();
        /** 本 Lane 保留的空用户资产 Map 池，不保存旧轮余额暂存对象。 */
        private final java.util.ArrayDeque<IntObjectHashMap<SpotBalanceState>> freeBalanceMaps =
                new java.util.ArrayDeque<>();
        /** 本轮账户写回和修订捕获的所属运行时。 */
        private TradingAccountRuntime runtime;
        /** 本轮独占服务的账户 Lane。 */
        private AccountLaneState lane;
        /** 本轮所有成交共用的规范现货币对。 */
        private CoreInstrument instrument;
        /** 本轮基础资产的稠密编号。 */
        private int baseAssetId;
        /** 本轮计价及收费资产的稠密编号。 */
        private int quoteAssetId;
        /** 本轮订单写回携带的复制业务时间。 */
        private long commitTimestamp;
        /** 本轮订单写回携带的复制日志位置。 */
        private long commitPosition;

        /**
         * 保存本轮单币对、所属 Lane、资产及业务元数据，不清理上一轮活动项，调用方必须先完成 clear。
         * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
         * @param lane 当前线程持有的账户 Lane
         * @param instrument 当前订单的规范现货币对
         * @param baseAssetId 基础资产的运行时编号
         * @param quoteAssetId 计价及收费资产的运行时编号
         * @param commitTimestamp 成交结果写回的复制业务时间
         * @param commitPosition 成交结果写回的复制日志位置
         */
        void reset(TradingAccountRuntime runtime, AccountLaneState lane, CoreInstrument instrument,
                   int baseAssetId, int quoteAssetId, long commitTimestamp, long commitPosition) {
            this.runtime = runtime;
            this.lane = lane;
            this.instrument = instrument;
            this.baseAssetId = baseAssetId;
            this.quoteAssetId = quoteAssetId;
            this.commitTimestamp = commitTimestamp;
            this.commitPosition = commitPosition;
        }

        /**
         * 首次遇到订单时检查权威订单和预留，借用游标并关联同用户同资产共用的本轮余额状态；随后按原成交顺序累加本笔并记录财务差额。
         * @param orderId 当前要累加、读取或释放预留的订单编号
         * @param taker 本笔是否为主动成交，决定订单费用率
         * @param price 本笔实际成交价刻度
         * @param quantity 本笔成交数量步长
         * @param treasury 按原成交顺序记录本笔系统费用的差额槽
         */
        void apply(long orderId, boolean taker, long price, long quantity, RuntimeTreasuryDelta treasury) {
            // 当前订单独占的现货累加游标，清理后可归还本 Lane 空闲池。
            SpotFillCursor cursor = cursors.get(orderId);
            if (cursor == null) {
                // 当前首次累加、终态释放或读取时取得的权威订单。
                OrderRuntime order = requireOpen(lane, orderId);
                // 当前订单的权威预留，须匹配用户及扣款资产。
                ReservationRuntime reservation = lane.reservation(orderId);
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

        /**
         * 先把全部用户资产余额写回 Lane，再发布每个订单及预留；预留更新会捕获余额后值，不能颠倒此顺序。
         */
        void publish() {
            balances.forEachKeyValue((/* 当前遍历的用户余额暂存编号。 */ userId, /* 当前用户本轮共用的按资产余额暂存 Map，清空后才归还 Map 池。 */ userBalances) ->
                    userBalances.forEachKeyValue((/* 当前遍历的用户资产暂存编号。 */ assetId, /* 当前用户资产的本轮共享余额后值，订单发布前先写回 Lane。 */ balance) -> balance.publish(runtime, lane)));
            // 预留仍待结算时，更新预留会捕获余额后值，
            // 因此必须先发布本轮余额，再发布订单及预留最终值。
            cursors.forEachKeyValue((/* 当前遍历游标对应的订单编号，本发布或清理回调只使用游标。 */ orderId, /* 当前订单独占的现货累加游标，清理后可归还本 Lane 空闲池。 */ cursor) -> cursor.publish(runtime));
        }

        /**
         * 释放游标引用并归还游标池，清空各用户资产 Map 后归还空 Map 池，解除本轮上下文；余额暂存值不跨轮保留，不自动发布或回滚。
         */
        void clear() {
            cursors.forEachKeyValue((/* 当前遍历游标对应的订单编号，本发布或清理回调只使用游标。 */ orderId, /* 当前订单独占的现货累加游标，清理后可归还本 Lane 空闲池。 */ cursor) -> {
                cursor.clear();
                free.addFirst(cursor);
            });
            cursors.clear();
            balances.forEachKeyValue((/* 当前遍历的用户余额暂存编号。 */ userId, /* 当前用户本轮共用的按资产余额暂存 Map，清空后才归还 Map 池。 */ userBalances) -> {
                userBalances.clear();
                freeBalanceMaps.addFirst(userBalances);
            });
            balances.clear();
            runtime = null;
            lane = null;
            instrument = null;
        }

        /**
         * 为本轮用户资产取得共用余额暂存，首次访问从 Lane 权威余额初始化，缺失余额从零开始；只复用空资产 Map，不复用旧余额值。
         * @param userId 当前余额所属用户编号
         * @param assetId 当前余额所属资产编号
         */
        private SpotBalanceState balanceState(long userId, int assetId) {
            // 当前用户本轮共用的按资产余额暂存 Map，清空后才归还 Map 池。
            IntObjectHashMap<SpotBalanceState> userBalances = balances.get(userId);
            if (userBalances == null) {
                userBalances = freeBalanceMaps.pollFirst();
                if (userBalances == null) userBalances = new IntObjectHashMap<>();
                balances.put(userId, userBalances);
            }
            // 当前用户资产已加载或新建的本轮余额暂存对象。
            SpotBalanceState state = userBalances.get(assetId);
            if (state == null) {
                // 首次加载本轮资产暂存时读取的可空权威余额。
                BalanceRuntime current = runtime.balance(lane, userId, assetId);
                state = new SpotBalanceState(userId, assetId, current);
                userBalances.put(assetId, state);
            }
            return state;
        }

        /**
         * 仅存活于本轮结算的单用户单资产余额标量后值，由该用户各订单共用；发布后清理，不作为长期权威余额镜像。
         */
        private static final class SpotBalanceState {
            /** 本余额暂存对应的账户用户编号。 */
            private final long userId;
            /** 本余额暂存对应的资产编号。 */
            private final int assetId;
            /** 本轮开始时该账户资产是否已有权威余额，决定发布时创建或更新。 */
            private final boolean exists;
            /** 本轮同用户各订单共同累加后的可用余额。 */
            private long available;
            /** 本轮共同累加后的冻结余额。 */
            private long locked;

            /**
             * 保存用户资产身份及本轮起点是否存在，并从权威余额复制可用和冻结标量，缺失时用零。
             * @param userId 当前余额所属用户编号
             * @param assetId 当前余额所属资产编号
             * @param current 起点已有的可空权威余额，空值表示需要创建余额
             */
            SpotBalanceState(long userId, int assetId, BalanceRuntime current) {
                this.userId = userId;
                this.assetId = assetId;
                exists = current != null;
                available = current == null ? 0 : current.availableUnits();
                locked = current == null ? 0 : current.lockedUnits();
            }

            /**
             * 按起点存在标记在所属 Lane 更新或创建余额，写入本轮聚合后的可用和冻结金额。
             * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
             * @param lane 当前线程持有的账户 Lane
             */
            void publish(TradingAccountRuntime runtime, AccountLaneState lane) {
                if (exists) runtime.replaceBalance(lane, userId, assetId, available, locked);
                else runtime.putBalance(lane, new BalanceRuntime(userId, assetId, available, locked));
            }
        }

        /**
         * 本 Lane 一笔订单的连续成交游标，保存累计成交、费用及预留消耗，引用同用户共享余额后值；只在本轮租约内使用，清理后归还池。
         */
        private final class SpotFillCursor {
            /** 当前游标起点的权威订单引用，用于累计量、费用和修订计算。 */
            private OrderRuntime originalOrder;
            /** 当前游标起点的权威预留引用，用于已消费上限及最终释放额。 */
            private ReservationRuntime originalReservation;
            /** 同用户基础资产的共享本轮余额暂存。 */
            private SpotBalanceState base;
            /** 同用户计价资产的共享本轮余额暂存。 */
            private SpotBalanceState quote;
            /** 游标累计后的订单已成交数量。 */
            private long executed;
            /** 游标累计后的订单剩余量。 */
            private long remaining;
            /** 订单累计费用，收费增加、返佣可抵减。 */
            private long cumulativeFee;
            /**
             * executedValueHigh：订单累计成交价格乘数量的宽整数高六十四位。
             * executedValueLow：订单累计成交价格乘数量的宽整数低六十四位。
             */
            private long executedValueHigh, executedValueLow;
            /** 订单预留累计已消耗金额，包括本轮此前成交。 */
            private long consumed;
            /** 本轮已应用成交次数，供最终订单及用户修订累计。 */
            private long fills;

            /**
             * 保存订单和预留起点引用，关联同用户共用资产余额，载入累计成交、费用和预留消耗并清零本轮成交数。
             * @param order 当前需要应用成交的订单引用
             * @param reservation 当前订单对应的权威预留起点
             * @param base 本用户基础资产共用的本轮余额暂存
             * @param quote 本用户计价资产共用的本轮余额暂存
             */
            void reset(OrderRuntime order, ReservationRuntime reservation,
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

            /**
             * 核对正价正量及剩余量，按买卖方向更新共享余额暂存，校验预留累计不超额，累计订单数量、费用和成交价值；每笔费用立即进入系统差额，不在此写回权威订单。
             * @param price 本笔实际成交价刻度
             * @param quantity 本笔成交数量步长
             * @param taker 本笔是否为主动成交，决定订单费用率
             * @param treasury 按原成交顺序记录本笔系统费用的差额槽
             */
            void apply(long price, long quantity, boolean taker, RuntimeTreasuryDelta treasury) {
                if (price <= 0 || quantity <= 0 || quantity > remaining) {
                    throw new IllegalArgumentException("invalid runtime spot fill");
                }
                // 本笔按价格和数量换算的计价资产成交金额。
                long quoteUnits = CoreContractMath.spotQuoteUnits(instrument, price, quantity);
                // 本笔按数量换算的基础资产交付金额。
                long baseUnits = CoreContractMath.spotBaseUnits(instrument, quantity);
                // 按 taker 或 maker 角色选出的订单费率。
                long feeRate = taker ? originalOrder.takerFeeRatePpm() : originalOrder.makerFeeRatePpm();
                // 本笔用户计价资产费用变化，负数收费、正数返佣。
                long feeDelta = CoreContractMath.feeDeltaUnits(instrument, price, quantity, feeRate);
                // 买单扣计价资产、卖单扣基础资产的实际预留资产编号。
                int debitAssetId = originalOrder.side() == CoreOrderSide.BUY ? quoteAssetId : baseAssetId;
                if (originalReservation.assetId() != debitAssetId) {
                    throw new IllegalStateException("spot fill debit asset does not match reservation");
                }
                // 本笔从订单预留消耗的金额，买单含成交额及收费，卖单只扣基础资产数量。
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
                // 当前预留原已消耗额加原剩余可用额得到的本轮累计消耗上限。
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

            /**
             * 有成交才写回预留及订单累计值，按成交次数推进订单与用户修订；达到终态后释放剩余预留，已有余额在外层先行发布。
             * @param state 用于写回当前所属账户结果的交易运行时
             */
            void publish(TradingAccountRuntime state) {
                if (fills == 0) return;
                // 游标最终剩余量对应的 OPEN 或 FILLED 订单状态。
                CoreOrderStatus status = remaining == 0 ? CoreOrderStatus.FILLED : CoreOrderStatus.OPEN;
                // 本轮最终累计手续费与权威订单起点费用的差额。
                long feeDelta = Math.subtractExact(cumulativeFee, originalOrder.cumulativeFeeUnits());
                state.updateReservationInLane(lane, originalOrder.orderId(), consumed, originalReservation.releasedUnits(), remaining != 0);
                // 写回最终累计值后返回的权威订单引用。
                OrderRuntime nextOrder = state.updateOrderInLane(lane, originalOrder.orderId(), executed, remaining,
                        feeDelta, executedValueHigh, executedValueLow, status, Math.addExact(originalOrder.revision(), fills),
                        commitTimestamp, commitPosition);
                state.advanceUserRevision(lane, originalOrder.userId(), fills);
                if (nextOrder.canceled()) {
                    // 终态订单当前仍可释放的预留金额，正数才额外推进用户修订。
                    long releaseUnits = lane.reservation(nextOrder.orderId()).reservedUnits();
                    state.releaseTerminalReservationInLane(lane, nextOrder.orderId());
                    if (releaseUnits > 0) state.advanceUserRevision(lane, nextOrder.userId());
                }
            }

            /**
             * 释放本轮订单、预留及两资产余额暂存引用并清零成交数，其他原语值由下次 reset 覆盖。
             */
            void clear() {
                originalOrder = null;
                originalReservation = null;
                base = null;
                quote = null;
                fills = 0;
            }
        }
    }

    /**
     * 按已有余额是否存在，在所属 Lane 创建余额或更新原余额；current 只用于选择分支。
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param lane 当前线程持有的账户 Lane
     * @param userId 当前余额所属用户编号
     * @param assetId 当前余额所属资产编号
     * @param current 起点已有的可空权威余额，空值表示需要创建余额
     * @param available 要写回的可用余额
     * @param locked 要写回的冻结余额
     */
    private static void replaceBalance(TradingAccountRuntime runtime, AccountLaneState lane, long userId, int assetId,
                                       BalanceRuntime current, long available, long locked) {
        if (current == null) runtime.putBalance(lane, new BalanceRuntime(userId, assetId, available, locked));
        else runtime.replaceBalance(lane, userId, assetId, available, locked);
    }

    /**
     * 从通用可读订单视图确定用户所属 Lane，订单存在才委托显式账户入口释放终态预留。
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param orderId 当前要累加、读取或释放预留的订单编号
     */
    private static void releaseTerminalReservation(TradingAccountRuntime runtime, long orderId) {
        // 当前首次累加、终态释放或读取时取得的权威订单。
        OrderRuntime order = runtime.order(orderId);
        if (order != null) releaseTerminalReservation(runtime, runtime.accountLaneForUser(order.userId()), orderId);
    }

    /**
     * 只处理已终态订单；活跃 Matcher 结算变化作用域使用 Lane 写入入口，其余沿通用运行时入口释放，实际释放金额为正才增加用户修订。
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param lane 当前线程持有的账户 Lane
     * @param orderId 当前要累加、读取或释放预留的订单编号
     */
    private static void releaseTerminalReservation(TradingAccountRuntime runtime, AccountLaneState lane, long orderId) {
        // 当前首次累加、终态释放或读取时取得的权威订单。
        OrderRuntime order = lane.order(orderId);
        if (order == null || !order.canceled()) return;
        // 终态订单当前仍可释放的预留金额，正数才额外推进用户修订。
        long releaseUnits = lane.reservation(orderId).reservedUnits();
        if (lane.matcherSettlementChanges() != null) {
            runtime.releaseTerminalReservationInLane(lane, orderId);
            if (releaseUnits > 0) runtime.advanceUserRevision(lane, order.userId());
        } else {
            runtime.releaseTerminalReservation(orderId);
            if (releaseUnits > 0) runtime.advanceUserRevision(order.userId());
        }
    }

    /**
     * 核对非空成交、正价正量、maker 用户币对和反向身份，累计扣减 taker 及重复 maker 剩余量，拒绝超量；本次校验暂存在 finally 清空。
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param taker 已经读取并确认可成交的主动订单
     * @param matches 原始撮合事件列表，非 TRADE 事件不应用成交
     */
    private static void validateMatches(TradingAccountRuntime runtime, OrderRuntime taker,
                                        List<MatcherEvent> matches) {
        // 验证过程中主动订单逐成交扣减后的累计剩余量。
        long takerRemaining = taker.remainingQuantitySteps();
        // 当前验证过程中各 maker 累计剩余数量的原语暂存表。
        LongLongHashMap makerRemaining = runtime.matcherSettlementRemainingScratch();
        makerRemaining.clear();
        try {
            for (/* 通用原始撮合事件的当前下标。 */ int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
                // 当前原始 Matcher 事件，只有 TRADE 参与计算。
                MatcherEvent match = matches.get(matchIndex);
                if (match == null) {
                    throw new IllegalArgumentException("invalid runtime match");
                }
                if (match.eventType() != MatcherEventType.TRADE) continue;
                if (match.price() <= 0 || match.size() <= 0) {
                    throw new IllegalArgumentException("invalid runtime match");
                }
                // 本笔对应的权威对手订单，Lane 路径只读取本地对象。
                OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
                if (maker.userId() != match.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                        || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                    throw new IllegalStateException("runtime match does not match authoritative orders");
                }
                takerRemaining = Math.subtractExact(takerRemaining, match.size());
                // 当前 maker 扣除本笔成交后的累计剩余量。
                long remaining = Math.subtractExact(makerRemaining.containsKey(maker.orderId())
                        ? makerRemaining.get(maker.orderId()) : maker.remainingQuantitySteps(), match.size());
                if (takerRemaining < 0 || remaining < 0) {
                    throw new IllegalStateException("fill exceeds runtime order remaining quantity");
                }
                makerRemaining.put(maker.orderId(), remaining);
            }
        } finally {
            makerRemaining.clear();
        }
    }

    /**
     * 从指定 Lane 读取存在、非终态且剩余数量非零的订单。
     * @param lane 当前线程持有的账户 Lane
     * @param orderId 当前要累加、读取或释放预留的订单编号
     */
    private static OrderRuntime requireOpen(AccountLaneState lane, long orderId) {
        // 当前首次累加、终态释放或读取时取得的权威订单。
        OrderRuntime order = lane.order(orderId);
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0)
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        return order;
    }

    /**
     * 从通用运行时允许读取的订单视图取得存在、非终态且仍有剩余量的订单。
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param orderId 当前要累加、读取或释放预留的订单编号
     */
    private static OrderRuntime requireOpen(TradingAccountRuntime runtime, long orderId) {
        // 当前首次累加、终态释放或读取时取得的权威订单。
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0) {
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        }
        return order;
    }
}
