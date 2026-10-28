package com.surprising.aeron.service.settlement;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

/**
 * 测试用衍生品运行时投影与修订对齐辅助类。simulate 使用本测试参考编排及原有 Lane 成交计算器；simulateTransition 及 applyTransition 只对齐测试产品修订，不覆盖实际计算的账户状态。不是独立资金公式或在线异步交易入口。
 */
public final class RuntimeDerivativeMatchFixture {
    /**
     * 检查衍生品输入，投影独立内存运行时并调用测试参考成交入口，返回结果供状态一致性测试检查。
     * @param before 测试起点冷状态，须为衍生产品线
     * @param takerOrderId 本次主动成交订单编号
     * @param matches 用例直接构造的原生成交事件列表
     * @param identities 投影和成交共同使用的运行时身份字典
     */
    public static TradingAccountRuntime simulate(TradingCoreState before, long takerOrderId,
                                               List<MatcherEvent> matches,
                                               RuntimeIdentityRegistry identities) {
        if (before == null || matches == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual match simulation");
        }
        // 由起点冷状态投影出的独立测试运行时。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        return applyRuntime(takerOrderId, matches, runtime, identities);
    }

    /**
     * 检查前后产品线并从起点投影运行时，再委托带期望修订计划的测试转换入口。
     * @param before 测试起点冷状态，须为衍生产品线
     * @param expected 期望冷状态，仅提供测试产品修订，不替代账户计算
     * @param takerOrderId 本次主动成交订单编号
     * @param matches 用例直接构造的原生成交事件列表
     * @param identities 投影和成交共同使用的运行时身份字典
     */
    public static TradingAccountRuntime simulateTransition(TradingCoreState before, TradingCoreState expected,
                                                         long takerOrderId, List<MatcherEvent> matches,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || identities == null
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        // 由起点冷状态投影出的独立测试运行时。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        return applyTransition(before, expected, takerOrderId, matches, runtime, identities);
    }

    /**
     * 在给定运行时应用原有 Lane 成交计算后对齐测试产品修订；用户修订、余额和持仓均保留实际执行结果。
     * @param before 测试起点冷状态，须为衍生产品线
     * @param expected 期望冷状态，仅提供测试产品修订，不替代账户计算
     * @param takerOrderId 本次主动成交订单编号
     * @param matches 用例直接构造的原生成交事件列表
     * @param runtime 已经由起点投影且由当前测试持有的运行时
     * @param identities 投影和成交共同使用的运行时身份字典
     */
    public static TradingAccountRuntime applyTransition(TradingCoreState before, TradingCoreState expected,
                                                       long takerOrderId, List<MatcherEvent> matches,
                                                       TradingAccountRuntime runtime,
                                                       RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || runtime == null || identities == null
                || !before.productLine().isDerivative()
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        applyRuntime(takerOrderId, matches, runtime, identities);
        runtime.setMetadata(expected.productLine(), expected.revision());
        return runtime;
    }

    /**
     * 在原 Owner 可读写边界核验成交并准备持仓身份，逐事件应用 taker 和 maker、释放终态预留，再应用系统差额并推进产品修订；不是在线跨 Lane 直接消费事实的入口。
     * @param takerOrderId 主动成交订单编号
     * @param matches 原始撮合事件列表，只有 TRADE 参与成交计算
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param identities 运行时资产及预准备持仓身份字典
     */
    public static TradingAccountRuntime applyRuntime(long takerOrderId, List<MatcherEvent> matches,
                                                   TradingAccountRuntime runtime,
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
        // 本次冷测试结算独占的系统资金差额缓冲。
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
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
                com.surprising.aeron.service.core.CoreStateTestFixtures.releaseRemainingReservation(runtime, maker.orderId());
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
            com.surprising.aeron.service.core.CoreStateTestFixtures.releaseRemainingReservation(runtime, takerOrderId);
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
                                   TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
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
     * 逐成交核对 maker 与 taker 的用户、币对、方向及正价正量，按订单累计扣减剩余数量以防同一 maker 重复成交超量；临时数量表在 finally 清空。
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param taker 已经读取并确认可成交的主动订单
     * @param matches 原始撮合事件列表，只有 TRADE 参与成交计算
     */
    private static void validateMatches(TradingAccountRuntime runtime, OrderRuntime taker,
                                        List<MatcherEvent> matches) {
        // 逐成交扣减后主动订单的累计剩余量，仅用于提前校验。
        long takerRemaining = taker.remainingQuantitySteps();
        // 本次验证按 maker 订单累计的剩余量原语暂存表。
        LongLongHashMap makerRemaining = new LongLongHashMap();
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
    private static void applyFill(TradingAccountRuntime runtime,
                                  RuntimeIdentityRegistry identities, CoreInstrument instrument,
                                  OrderRuntime order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId, RuntimeTreasuryDelta treasuryDelta) {
        AccountLaneState lane = runtime.accountLaneForUser(order.userId());
        long leverage = 0;
        if (!instrument.contractType().isOption()) {
            Long configuredLeverage = runtime.leverage(lane, order.userId(), instrument.instrumentId(), order.marginMode());
            leverage = configuredLeverage == null ? instrument.maxLeveragePpm() : configuredLeverage;
        }
        RuntimeDerivativeFillCalculator.apply(runtime, lane, identities, instrument, order,
                identities.preparedPositionKey(lane, order),
                priceTicks, quantitySteps, taker, leverage, settleAssetId, treasuryDelta, -1, -1);
    }

    /**
     * 从通用运行时允许读取的订单视图取得存在、非终态且仍有剩余量的订单。
     * @param runtime 当前所属交易运行时，具体读写边界由通用或 Lane 入口决定
     * @param orderId 当前要累加、刷新或读取的订单编号
     */
    private static OrderRuntime requireOpen(TradingAccountRuntime runtime, long orderId) {
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
