package com.surprising.aeron.service.state;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.math.*;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import com.surprising.aeron.protocol.CoreOrderSide;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;

import java.util.List;
import java.util.Map;

import static com.surprising.aeron.service.state.ReducerDerivativeSettlement.*;
import static com.surprising.aeron.service.state.ReducerSpotSettlement.*;

/** Owns the authoritative application of one matcher result to core state. */
final class MatchStateTransitions {

    private MatchStateTransitions() {
    }

    static TradingCoreState apply(
            TradingCoreState state,
            long takerOrderId,
            String baseAsset,
            String quoteAsset,
            List<MatcherEvent> matches) {
        if (matches == null) {
            throw new IllegalArgumentException("matches are required");
        }
        if (matches.isEmpty()) {
            CoreOrderState taker = requireOpenOrder(state.orders(), takerOrderId);
            if (!taker.timeInForce().immediate()
                    && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
                return state;
            }
        }
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        CoreTreasuryState treasury = state.treasuryState();
        CoreOrderState taker = requireOpenOrder(orders, takerOrderId);
        CoreInstrumentState instrument = requireInstrument(state, taker.symbol(), taker.instrumentChangeId());
        CoreMarkPriceState riskMark = state.productLine().isDerivative()
                ? state.riskState().markPrices().get(instrument.symbol()) : null;
        if (instrument.contractType().isOption() && (riskMark == null
                || riskMark.indexPriceTicks() <= 0 || riskMark.forwardPriceTicks() <= 0)) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option fill requires index and same-expiry forward prices");
        }
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            CoreOrderState maker = requireOpenOrder(orders, match.matchedOrderId());
            if (!taker.symbol().equals(maker.symbol()) || taker.side() == maker.side()
                    || maker.userId() != match.matchedOrderUid()) {
                throw new IllegalStateException("exchange-core match does not match authoritative orders");
            }
            if (taker.userId() == maker.userId()) {
                throw new CoreStateRejectedException("SELF_TRADE_PREVENTED", "self trade is not allowed");
            }
            if (state.productLine().isDerivative()) {
                long takerLeverage = state.leverages().getOrDefault(
                        new CoreLeverageKey(taker.userId(), instrument.symbol(), taker.marginMode()),
                        instrument.maxLeveragePpm());
                DerivativeFillResult takerFill = applyDerivativeFill(users.get(taker.userId()), taker,
                        instrument, riskMark, match.price(), match.size(), true, takerLeverage, treasury);
                users.put(taker.userId(), takerFill.user());
                treasury = takerFill.treasury();
                long makerLeverage = state.leverages().getOrDefault(
                        new CoreLeverageKey(maker.userId(), instrument.symbol(), maker.marginMode()),
                        instrument.maxLeveragePpm());
                DerivativeFillResult makerFill = applyDerivativeFill(users.get(maker.userId()), maker,
                        instrument, riskMark, match.price(), match.size(), false, makerLeverage, treasury);
                users.put(maker.userId(), makerFill.user());
                treasury = makerFill.treasury();
            } else {
                CoreOrderState buyerOrder = taker.side() == CoreOrderSide.BUY ? taker : maker;
                CoreOrderState sellerOrder = taker.side() == CoreOrderSide.SELL ? taker : maker;
                long buyerFeeRate = buyerOrder.orderId() == taker.orderId()
                        ? buyerOrder.takerFeeRatePpm() : buyerOrder.makerFeeRatePpm();
                long sellerFeeRate = sellerOrder.orderId() == taker.orderId()
                        ? sellerOrder.takerFeeRatePpm() : sellerOrder.makerFeeRatePpm();
                SpotFillResult buyerFill = applySpotFill(users.get(buyerOrder.userId()), buyerOrder,
                        instrument, AssetBalance.normalizeAsset(baseAsset), AssetBalance.normalizeAsset(quoteAsset),
                        match.price(), match.size(), buyerFeeRate, treasury);
                users.put(buyerOrder.userId(), buyerFill.user());
                treasury = buyerFill.treasury();
                SpotFillResult sellerFill = applySpotFill(users.get(sellerOrder.userId()), sellerOrder,
                        instrument, AssetBalance.normalizeAsset(baseAsset), AssetBalance.normalizeAsset(quoteAsset),
                        match.price(), match.size(), sellerFeeRate, treasury);
                users.put(sellerOrder.userId(), sellerFill.user());
                treasury = sellerFill.treasury();
            }
            long takerFeeUnits = Math.negateExact(CoreContractMath.feeDeltaUnits(
                    instrument, match.price(), match.size(), taker.takerFeeRatePpm()));
            long makerFeeUnits = Math.negateExact(CoreContractMath.feeDeltaUnits(
                    instrument, match.price(), match.size(), maker.makerFeeRatePpm()));
            taker = taker.fill(match.size(), takerFeeUnits);
            maker = maker.fill(match.size(), makerFeeUnits);
            orders.put(taker.orderId(), taker);
            orders.put(maker.orderId(), maker);
            if (maker.status() != CoreOrderStatus.OPEN) {
                users.put(maker.userId(), OrderStateTransitions.releaseTerminalReservation(
                        users.get(maker.userId()), maker.orderId()));
            }
        }
        if (taker.status() == CoreOrderStatus.OPEN && !taker.timeInForce().immediate()
                && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
        } else {
            if (taker.status() == CoreOrderStatus.OPEN) {
                taker = taker.cancel();
                orders.put(taker.orderId(), taker);
            }
            users.put(taker.userId(), OrderStateTransitions.releaseTerminalReservation(
                    users.get(taker.userId()), taker.orderId()));
        }
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                state.instruments(), state.riskState(), treasury, state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(),
                state.clientOrderIndex(), state.triggerOrders());
    }

    private static CoreInstrumentState requireInstrument(TradingCoreState state, String symbol, long version) {
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(symbol));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.changeId() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT", "instrument version differs");
        }
        return instrument;
    }

    private static CoreOrderState requireOpenOrder(Map<Long, CoreOrderState> orders, long orderId) {
        CoreOrderState order = orders.get(orderId);
        if (order == null || order.status() != CoreOrderStatus.OPEN) {
            throw new IllegalStateException("matched order is not open orderId=" + orderId);
        }
        return order;
    }
}
