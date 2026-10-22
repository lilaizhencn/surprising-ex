package com.surprising.aeron.service.state.settlement;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
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
 * 下单步骤 4.4 的衍生品结算入口。在线 applyLane 只处理所属账户的 taker 和 maker，选择单笔或连续成交计算，更新订单、持仓及剩余预留；金额由 RuntimeDerivativeFillCalculator 和各产品规则决定。通用 applyRuntime 保留原运行时读取边界，不与在线 Lane 的财务交接混用。
 * 构造约定：只提供静态结算入口，不允许单独创建工具实例。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeDerivativeMatchProcessor {


    /**
     * 先核对传入冷状态与运行时的产品线和修订号，再委托通用运行时成交入口；不把冷状态重新投影或作为账户副本更新。
     * @param before 与当前运行时产品线及修订号一致的冷状态参照，只用于入口校验
     * @param takerOrderId 主动成交订单编号
     * @param matches 原始撮合事件列表，只有 TRADE 参与成交计算
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param identities 运行时资产及预准备持仓身份字典
     */
    public static TradingRuntimeState apply(TradingCoreState before, long takerOrderId,
                                            List<MatcherEvent> matches, TradingRuntimeState runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual match apply");
        }
        return applyRuntime(takerOrderId, matches, runtime, identities);
    }

    /**
     * 在原 Owner 可读写边界核验成交并准备持仓身份，逐事件应用 taker 和 maker、释放终态预留，再应用系统差额并推进产品修订；不是在线跨 Lane 直接消费事实的入口。
     * @param takerOrderId 主动成交订单编号
     * @param matches 原始撮合事件列表，只有 TRADE 参与成交计算
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param identities 运行时资产及预准备持仓身份字典
     */
    public static TradingRuntimeState applyRuntime(long takerOrderId, List<MatcherEvent> matches,
                                                   TradingRuntimeState runtime,
                                                   RuntimeIdentityRegistry identities) {
        if (matches == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual match apply");
        }
        runtime.assertOwner();
        // 本次主动订单的当前权威值，每笔应用前按既有入口重新读取。
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        if (matches.isEmpty() && !taker.timeInForce().immediate()
                && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
            return runtime;
        }
        // 从运行时取得且与订单输入相同的规范币对对象。
        CoreInstrument instrument = runtime.instrument(identities.instrumentId(taker.symbolId()));
        if (instrument == null || instrument != taker.instrument()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        validateAndPrepare(takerOrderId, matches, runtime, identities);
        // 当前币对结算资产的稠密编号。
        int settleAssetId = identities.assetId(instrument.settleAsset());
        // 当前通用结算借用的系统资金差额缓冲。
        RuntimeTreasuryDelta treasuryDelta = runtime.scratchTreasuryDelta();
        for (/* 通用事件列表当前遍历下标。 */ int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
            // 当前原始 Matcher 事件，非 TRADE 跳过。
            MatcherEvent match = matches.get(matchIndex);
            if (match.eventType() != MatcherEventType.TRADE) continue;
            taker = requireOpen(runtime, takerOrderId);
            // 当前成交在允许读取边界内取得的对手订单值。
            OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
            // 本笔数量是否正好耗尽 maker 当前剩余量。
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
                // 终态订单仍可释放的订单预留金额，正数才额外推进用户修订。
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
            // 终态订单仍可释放的订单预留金额，正数才额外推进用户修订。
            long releaseUnits = runtime.reservation(takerOrderId).reservedUnits();
            runtime.releaseTerminalReservation(takerOrderId);
            if (releaseUnits > 0) runtime.advanceUserRevision(runtime.order(takerOrderId).userId());
        }
        treasuryDelta.apply(runtime.treasury());
        treasuryDelta.clear();
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return runtime;
    }

    /**
     * 核对主动订单及全部成交的身份、价格与累计数量，然后为主动及各 maker 的衍生持仓准备身份键，不应用成交余额。
     * @param takerOrderId 主动成交订单编号
     * @param matches 原始撮合事件列表，只有 TRADE 参与成交计算
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param identities 运行时资产及预准备持仓身份字典
     */
    static void validateAndPrepare(long takerOrderId, List<MatcherEvent> matches,
                                   TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        // 本次主动订单的当前权威值，每笔应用前按既有入口重新读取。
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        validateMatches(runtime, taker, matches);
        // 从运行时取得且与订单输入相同的规范币对对象。
        CoreInstrument instrument = runtime.instrument(identities.instrumentId(taker.symbolId()));
        identities.positionKey(taker.userId(), instrument, taker.positionSide());
        for (/* 通用事件列表当前遍历下标。 */ int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
            // 当前原始 Matcher 事件，非 TRADE 跳过。
            MatcherEvent match = matches.get(matchIndex);
            if (match.eventType() != MatcherEventType.TRADE) continue;
            // 当前成交在允许读取边界内取得的对手订单值。
            OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
            identities.positionKey(maker.userId(), instrument, maker.positionSide());
        }
    }

    /**
     * 在线所属 Lane 入口：至多一次成交直接应用，多笔成交复用累加器；处理完本地 taker 后结束市价或即时单的未成交部分，并释放终态预留，系统差额由外层事件交给账本。
     * @param takerOrderId 主动成交订单编号
     * @param plan 当前代只读结算计划及 Lane 事件索引
     * @param lane 当前线程持有的账户 Lane
     * @param laneId 当前账户 Lane 编号，用于限定事件范围
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param identities 运行时资产及预准备持仓身份字典
     * @param instrument 本轮订单规范币对
     * @param settleAssetId 结算资产的运行时编号
     * @param treasuryDelta 当前 Lane 的系统财务差额接收槽
     * @param commitTimestamp 成交结果需要保存的复制业务时间
     * @param commitPosition 成交结果需要保存的复制日志位置
     */
    static void applyLane(long takerOrderId, MatcherSettlementPlan plan, AccountLaneState lane, int laneId,
                          TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                          CoreInstrument instrument, int settleAssetId,
                          RuntimeTreasuryDelta treasuryDelta,
                          long commitTimestamp, long commitPosition) {
        if (plan == null || treasuryDelta == null || laneId < 0
                || laneId >= runtime.topology().accountLaneCount()) {
            throw new IllegalArgumentException("invalid perpetual matcher settlement plan");
        }
        // 本 Lane 当前持有的主动订单，不属于本 Lane 时为空。
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
                // 终态订单仍可释放的订单预留金额，正数才额外推进用户修订。
                long releaseUnits = lane.reservation(takerOrderId).reservedUnits();
                runtime.releaseTerminalReservationInLane(lane, takerOrderId);
                if (releaseUnits > 0) runtime.advanceUserRevision(lane, localTaker.userId());
            }
        }
    }

    /**
     * 沿本 Lane 事件范围逐笔应用，只有本 Lane 持有 taker 或 maker 才计算对应一侧；maker 完全成交时释放预留，保留原事件顺序。
     * @param takerOrderId 主动成交订单编号
     * @param plan 当前代只读结算计划及 Lane 事件索引
     * @param lane 当前线程持有的账户 Lane
     * @param laneId 当前账户 Lane 编号，用于限定事件范围
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param identities 运行时资产及预准备持仓身份字典
     * @param instrument 本轮订单规范币对
     * @param settleAssetId 结算资产的运行时编号
     * @param treasuryDelta 当前 Lane 的系统财务差额接收槽
     * @param commitTimestamp 成交结果需要保存的复制业务时间
     * @param commitPosition 成交结果需要保存的复制日志位置
     */
    private static void applyLaneDirect(long takerOrderId, MatcherSettlementPlan plan, AccountLaneState lane, int laneId,
                                        TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                        CoreInstrument instrument, int settleAssetId,
                                        RuntimeTreasuryDelta treasuryDelta,
                                        long commitTimestamp, long commitPosition) {
        // 本 Lane 当前持有的主动订单，不属于本 Lane 时为空。
        OrderRuntime localTaker = lane.order(takerOrderId);
        for (/* 沿本 Lane 事件链或原事件顺序访问的下标。 */ int index = plan.firstMatcherEvent(laneId); index >= 0;
                index = plan.nextMatcherEvent(index, laneId)) {
            // 当前原始 Matcher 事件，非 TRADE 跳过。
            MatcherEvent match = plan.matcherEvent(index);
            if (match.eventType() != MatcherEventType.TRADE
                    || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
            if (localTaker != null) {
                localTaker = requireOpen(lane, takerOrderId);
                applyFill(runtime, lane, identities, instrument, localTaker, match.price(), match.size(), true,
                        settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
            }
            // 当前成交在允许读取边界内取得的对手订单值。
            OrderRuntime maker = lane.order(match.matchedOrderId());
            if (maker != null) {
                maker = requireOpen(lane, maker.orderId());
                // 本笔数量是否正好耗尽 maker 当前剩余量。
                boolean makerTerminal = match.size() == maker.remainingQuantitySteps();
                applyFill(runtime, lane, identities, instrument, maker, match.price(), match.size(), false,
                        settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
                if (makerTerminal) {
                    // 终态订单仍可释放的订单预留金额，正数才额外推进用户修订。
                    long releaseUnits = lane.reservation(maker.orderId()).reservedUnits();
                    runtime.releaseTerminalReservationInLane(lane, maker.orderId());
                    if (releaseUnits > 0) runtime.advanceUserRevision(lane, maker.userId());
                }
            }
        }
    }

    /**
     * 借用当前 Lane 的连续成交累加器，按原事件顺序计算本地各订单，最后发布剩余游标；无论成功与否都清理暂存引用，已写回变化的回滚由外层负责。
     * @param takerOrderId 主动成交订单编号
     * @param plan 当前代只读结算计划及 Lane 事件索引
     * @param lane 当前线程持有的账户 Lane
     * @param laneId 当前账户 Lane 编号，用于限定事件范围
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param identities 运行时资产及预准备持仓身份字典
     * @param instrument 本轮订单规范币对
     * @param settleAssetId 结算资产的运行时编号
     * @param treasuryDelta 当前 Lane 的系统财务差额接收槽
     * @param commitTimestamp 成交结果需要保存的复制业务时间
     * @param commitPosition 成交结果需要保存的复制日志位置
     * @param localTaker 当前 Lane 持有的主动订单，其他 Lane 可以为空
     */
    private static void applyLaneAccumulated(long takerOrderId, MatcherSettlementPlan plan, AccountLaneState lane, int laneId,
                                             TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                             CoreInstrument instrument, int settleAssetId,
                                             RuntimeTreasuryDelta treasuryDelta,
                                             long commitTimestamp, long commitPosition,
                                             OrderRuntime localTaker) {
        // 所属账户 Lane 持有并在本次结算独占复用的衍生品累加器。
        DerivativeSettlementAccumulator accumulator =
                lane.derivativeSettlementAccumulator();
        accumulator.reset(runtime, lane, identities, instrument, settleAssetId, commitTimestamp, commitPosition);
        try {
            for (/* 沿本 Lane 事件链或原事件顺序访问的下标。 */ int index = plan.firstMatcherEvent(laneId); index >= 0;
                    index = plan.nextMatcherEvent(index, laneId)) {
                // 当前原始 Matcher 事件，非 TRADE 跳过。
                MatcherEvent match = plan.matcherEvent(index);
                if (match.eventType() != MatcherEventType.TRADE
                        || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
                if (localTaker != null) {
                    accumulator.apply(takerOrderId, true, match.price(), match.size(), treasuryDelta);
                }
                // 当前成交在允许读取边界内取得的对手订单值。
                OrderRuntime maker = lane.order(match.matchedOrderId());
                if (maker != null) {
                    accumulator.apply(maker.orderId(), false, match.price(), match.size(), treasuryDelta);
                }
            }
            accumulator.publish();
        } finally { accumulator.clear(); }
    }

    /**
     * 所属 Lane 独占的多笔衍生品成交累加器。每订单使用复用游标，同用户切换订单先写回前单；每笔财务差额仍按成交顺序生成，最终发布不重复收费，外层仍负责失败回滚。
     */
    public static final class DerivativeSettlementAccumulator {
        /** 本轮按订单编号保存的独占成交游标，不是第二份长期订单表。 */
        private final LongObjectHashMap<RuntimeDerivativeFillCalculator.FillCursor> cursors =
                new LongObjectHashMap<>();
        /** 本轮每用户最近的活动订单编号，切换时先写回旧游标以保持同用户账户顺序。 */
        private final LongLongHashMap activeOrderByUser = new LongLongHashMap();
        // 一轮结算只使用一个币对，按用户分别暂存全仓和逐仓杠杆查询结果。
        /** 本轮单币对各用户全仓杠杆的查询暂存，clear 清空。 */
        private final LongLongHashMap crossLeverageByUser = new LongLongHashMap();
        /** 本轮单币对各用户逐仓杠杆的查询暂存，与全仓分开。 */
        private final LongLongHashMap isolatedLeverageByUser = new LongLongHashMap();
        /** 当前 Lane 的空闲游标池，游标清理引用后归还并保留容量。 */
        private final java.util.ArrayDeque<RuntimeDerivativeFillCalculator.FillCursor> free =
                new java.util.ArrayDeque<>();
        /** 本轮账户写入与修订捕获使用的交易运行时。 */
        private TradingRuntimeState runtime;
        /** 本轮累加器独占服务的账户 Lane，不共享给其他账户线程。 */
        private AccountLaneState lane;
        /** 读取已经准备持仓身份的运行时字典。 */
        private RuntimeIdentityRegistry identities;
        /** 本轮所有成交共用的规范币对。 */
        private CoreInstrument instrument;
        /** 本轮结算资产运行时编号。 */
        private int settleAssetId;
        /** 本轮写回订单时携带的复制业务时间。 */
        private long commitTimestamp;
        /** 本轮写回订单时携带的复制日志位置。 */
        private long commitPosition;

        /**
         * 保存本次单币对结算使用的所属 Lane、运行时、资产及业务元数据；不清空旧游标，调用方必须先结束并 clear 上一轮。
         * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
         * @param lane 当前线程持有的账户 Lane
         * @param identities 运行时资产及预准备持仓身份字典
         * @param instrument 本轮订单规范币对
         * @param settleAssetId 结算资产的运行时编号
         * @param commitTimestamp 成交结果需要保存的复制业务时间
         * @param commitPosition 成交结果需要保存的复制日志位置
         */
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

        /**
         * 首次遇到订单时准备独占游标；同用户切换订单先写回前单，使后单读到最新余额和持仓。按保证金模式复用本轮杠杆查询，再逐笔计算并记录财务差额。
         * @param orderId 当前要累加、刷新或读取的订单编号
         * @param taker 本笔是否为主动成交，用于选择费率
         * @param price 本笔实际成交价刻度
         * @param quantity 本笔成交数量步长
         * @param treasury 当前 Lane 逐笔记录系统费用和清算差额的接收槽
         */
        void apply(long orderId, boolean taker, long price, long quantity,
                   RuntimeTreasuryDelta treasury) {
            // 当前订单的独占成交游标，可能从本 Lane 空闲池借出。
            RuntimeDerivativeFillCalculator.FillCursor cursor = cursors.get(orderId);
            if (cursor == null) {
                // 当前首次累加或发布后重新取得的权威订单引用。
                OrderRuntime order = requireOpen(lane, orderId);
                // 当前用户上一笔尚需写回的活动订单编号，零表示还没有记录。
                long activeOrder = activeOrderByUser.get(order.userId());
                if (activeOrder != 0 && activeOrder != orderId) flush(activeOrder);
                // 按当前订单全仓或逐仓模式选出的本轮用户杠杆暂存表。
                LongLongHashMap leverageByUser = order.marginMode()
                        == com.surprising.aeron.protocol.CoreMarginMode.CROSS
                        ? crossLeverageByUser : isolatedLeverageByUser;
                // 本轮当前用户及保证金模式实际使用的杠杆。
                long leverage;
                if (leverageByUser.containsKey(order.userId())) {
                    leverage = leverageByUser.get(order.userId());
                } else {
                    // 本用户已设置的可空杠杆，空时使用币对最大值。
                    Long configured = runtime.leverage(lane, order.userId(), instrument.instrumentId(), order.marginMode());
                    leverage = configured == null ? instrument.maxLeveragePpm() : configured;
                    leverageByUser.put(order.userId(), leverage);
                }
                // 为当前订单已经准备好的所属账户持仓身份键。
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

        /**
         * 把本轮仍保留的每个订单游标写回账户，不重复记入已经按笔记录的财务差额；不同用户的遍历顺序不作为撮合顺序。
         */
        void publish() {
            cursors.forEachKeyValue((/* 遍历活动游标时对应的订单编号，本回调不另外读取它。 */ orderId, /* 当前订单的独占成交游标，可能从本 Lane 空闲池借出。 */ cursor) -> publishCursor(cursor));
        }

        /**
         * 清空活动游标引用并归还本 Lane 游标池，清除同用户活动订单及两种杠杆暂存，释放本轮上下文；不自动发布或回滚账户。
         */
        void clear() {
            cursors.forEachKeyValue((/* 遍历活动游标时对应的订单编号，本回调不另外读取它。 */ orderId, /* 当前订单的独占成交游标，可能从本 Lane 空闲池借出。 */ cursor) -> {
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

        /**
         * 同用户切换订单时移除并发布前一订单游标，再清理入空闲池；活动编号没有对应游标即为状态错误。
         * @param orderId 当前要累加、刷新或读取的订单编号
         */
        private void flush(long orderId) {
            // 当前订单的独占成交游标，可能从本 Lane 空闲池借出。
            RuntimeDerivativeFillCalculator.FillCursor cursor = cursors.remove(orderId);
            if (cursor == null) throw new IllegalStateException("active derivative cursor is missing");
            publishCursor(cursor);
            cursor.clear();
            free.addFirst(cursor);
        }

        /**
         * 把游标最终累计结果写回所属 Lane，财务参数传空避免重复；订单达到终态时释放剩余预留，实际有释放金额才再增加用户修订。
         * @param cursor 本轮独占的订单成交游标
         */
        private void publishCursor(RuntimeDerivativeFillCalculator.FillCursor cursor) {
            // 当前首次累加或发布后重新取得的权威订单引用。
            OrderRuntime order = cursor.publish(runtime, lane, cursor.positionKey(), null);
            if (order != null && order.canceled()) {
                // 终态订单仍可释放的订单预留金额，正数才额外推进用户修订。
                long releaseUnits = lane.reservation(order.orderId()).reservedUnits();
                runtime.releaseTerminalReservationInLane(lane, order.orderId());
                if (releaseUnits > 0) runtime.advanceUserRevision(lane, order.userId());
            }
        }
    }

    /**
     * 逐成交核对 maker 与 taker 的用户、币对、方向及正价正量，按订单累计扣减剩余数量以防同一 maker 重复成交超量；临时数量表在 finally 清空。
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param taker 已经读取并确认可成交的主动订单
     * @param matches 原始撮合事件列表，只有 TRADE 参与成交计算
     */
    private static void validateMatches(TradingRuntimeState runtime, OrderRuntime taker,
                                        List<MatcherEvent> matches) {
        // 逐成交扣减后主动订单的累计剩余量，仅用于提前校验。
        long takerRemaining = taker.remainingQuantitySteps();
        // 本次验证按 maker 订单累计的剩余量原语暂存表。
        LongLongHashMap makerRemaining = runtime.matcherSettlementRemainingScratch();
        makerRemaining.clear();
        try {
            for (/* 通用事件列表当前遍历下标。 */ int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
                // 当前原始 Matcher 事件，非 TRADE 跳过。
                MatcherEvent match = matches.get(matchIndex);
                if (match == null) {
                    throw new IllegalArgumentException("runtime match is required");
                }
                if (match.eventType() != MatcherEventType.TRADE) continue;
                // 当前成交在允许读取边界内取得的对手订单值。
                OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
                if (maker.userId() != match.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                        || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                    throw new IllegalStateException("runtime match does not match authoritative orders");
                }
                if (match.price() <= 0 || match.size() <= 0) {
                    throw new IllegalArgumentException("invalid runtime match price or quantity");
                }
                takerRemaining = Math.subtractExact(takerRemaining, match.size());
                // 当前 maker 扣除本笔成交后的累计剩余量。
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

    /**
     * 通用入口根据订单用户取得账户 Lane，委托显式 Lane 成交计算，时间及日志位置以负一沿既有规则处理。
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param identities 运行时资产及预准备持仓身份字典
     * @param instrument 本轮订单规范币对
     * @param order 本笔需要成交或结束剩余量的订单引用
     * @param priceTicks 本笔实际成交价刻度
     * @param quantitySteps 本笔成交数量步长
     * @param taker 本笔是否为主动成交，用于选择费率
     * @param settleAssetId 结算资产的运行时编号
     * @param treasuryDelta 当前 Lane 的系统财务差额接收槽
     */
    private static void applyFill(TradingRuntimeState runtime,
                                  RuntimeIdentityRegistry identities, CoreInstrument instrument,
                                  OrderRuntime order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId, RuntimeTreasuryDelta treasuryDelta) {
        applyFill(runtime, runtime.accountLaneForUser(order.userId()), identities, instrument, order, priceTicks, quantitySteps, taker,
                settleAssetId, treasuryDelta, -1, -1);
    }

    /**
     * 读取本用户及保证金模式的杠杆，未设置时采用币对最大杠杆，使用预先准备的持仓键调用衍生品成交计算器。
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param lane 当前线程持有的账户 Lane
     * @param identities 运行时资产及预准备持仓身份字典
     * @param instrument 本轮订单规范币对
     * @param order 本笔需要成交或结束剩余量的订单引用
     * @param priceTicks 本笔实际成交价刻度
     * @param quantitySteps 本笔成交数量步长
     * @param taker 本笔是否为主动成交，用于选择费率
     * @param settleAssetId 结算资产的运行时编号
     * @param treasuryDelta 当前 Lane 的系统财务差额接收槽
     * @param commitTimestamp 成交结果需要保存的复制业务时间
     * @param commitPosition 成交结果需要保存的复制日志位置
     */
    private static void applyFill(TradingRuntimeState runtime, AccountLaneState lane,
                                  RuntimeIdentityRegistry identities, CoreInstrument instrument,
                                  OrderRuntime order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId, RuntimeTreasuryDelta treasuryDelta,
                                       long commitTimestamp, long commitPosition) {
        // 直接逐笔路径从所属 Lane 读取的可空用户杠杆。
        Long configuredLeverage = runtime.leverage(lane, order.userId(), instrument.instrumentId(), order.marginMode());
        // 本轮当前用户及保证金模式实际使用的杠杆。
        long leverage = configuredLeverage == null ? instrument.maxLeveragePpm() : configuredLeverage;
        RuntimeDerivativeFillCalculator.apply(runtime, lane, identities, instrument, order,
                identities.preparedPositionKey(lane, order),
                priceTicks, quantitySteps, taker, leverage, settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
    }

    /**
     * 从指定权威 Lane 读取订单，要求存在、非终态且剩余数量非零。
     * @param lane 当前线程持有的账户 Lane
     * @param orderId 当前要累加、刷新或读取的订单编号
     */
    private static OrderRuntime requireOpen(AccountLaneState lane, long orderId) {
        // 当前首次累加或发布后重新取得的权威订单引用。
        OrderRuntime order = lane.order(orderId);
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0)
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        return order;
    }

    /**
     * 从通用运行时允许读取的订单视图取得存在、非终态且仍有剩余量的订单。
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param orderId 当前要累加、刷新或读取的订单编号
     */
    private static OrderRuntime requireOpen(TradingRuntimeState runtime, long orderId) {
        // 当前首次累加或发布后重新取得的权威订单引用。
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0) {
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        }
        return order;
    }

    /**
     * 为即时或市价单尚未成交的部分生成 CANCELED 值，并精确递增订单修订号。
     * @param order 本笔需要成交或结束剩余量的订单引用
     */
    private static OrderRuntime terminal(OrderRuntime order) {
        return order.withStatus(CoreOrderStatus.CANCELED, Math.incrementExact(order.revision()));
    }
}
