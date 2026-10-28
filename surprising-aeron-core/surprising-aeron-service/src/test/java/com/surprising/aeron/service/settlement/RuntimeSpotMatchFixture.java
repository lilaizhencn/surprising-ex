package com.surprising.aeron.service.settlement;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.product.ProductTradingRulesRegistry;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

/** 冷状态测试的现货成交编排，复用实际 Lane 单笔计算；不作为独立资金公式或在线交易入口。 */
public final class RuntimeSpotMatchFixture {

    private RuntimeSpotMatchFixture() {}

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
        return RuntimeSpotMatchProcessor.applyFill(runtime, runtime.accountLaneForUser(order.userId()), instrument, order, fillPriceTicks, fillQuantitySteps, taker,
                baseAssetId, quoteAssetId, treasuryDelta, -1, -1);
    }

    /**
     * 从通用可读订单视图确定用户所属 Lane，订单存在才委托显式账户入口释放终态预留。
     * @param runtime 当前所属交易运行时，具体所有权由通用或 Lane 入口决定
     * @param orderId 当前要累加、读取或释放预留的订单编号
     */
    private static void releaseTerminalReservation(TradingAccountRuntime runtime, long orderId) {
        // 当前首次累加、终态释放或读取时取得的权威订单。
        OrderRuntime order = runtime.order(orderId);
        if (order != null && com.surprising.aeron.service.core.CoreStateTestFixtures.releaseRemainingReservation(runtime, orderId) > 0)
            runtime.advanceUserRevision(order.userId());
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
        LongLongHashMap makerRemaining = new LongLongHashMap();
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
